package com.example.common.event;

import com.example.common.config.CommonJacksonConfig;
import com.example.common.dto.ProductPayload;
import com.example.common.dto.ProductStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Menjaga kontrak wire format event. Kalau test ini gagal, artinya perubahan yang baru dibuat
 * berpotensi membuat event yang sudah ada di Kafka tidak bisa dibaca lagi oleh consumer.
 */
class EventEnvelopeSerializationTest {

    private final ObjectMapper mapper = buildMapper();

    private static ObjectMapper buildMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new CommonJacksonConfig().commonJacksonCustomizer().customize(builder);
        return builder.build();
    }

    private static ProductPayload samplePayload(UUID id) {
        return new ProductPayload(
                id, "SKU-001", "Kopi Gayo 200g", "Arabika single origin",
                new BigDecimal("85000.00"), "IDR", 12,
                "products/%s/abc.jpg".formatted(id), "image/jpeg", 34567L,
                ProductStatus.ACTIVE, 3L,
                Instant.parse("2026-08-16T03:00:00Z"), Instant.parse("2026-08-16T04:00:00Z"));
    }

    @Test
    @DisplayName("envelope + payload bertahan utuh setelah round-trip JSON")
    void roundTrip() throws Exception {
        UUID id = UUID.randomUUID();
        EventEnvelope<ProductPayload> original =
                EventEnvelope.of(EventType.PRODUCT_UPDATED, id.toString(), 3L, "trace-abc", samplePayload(id));

        String json = mapper.writeValueAsString(original);
        EventEnvelope<ProductPayload> restored =
                mapper.readValue(json, new TypeReference<EventEnvelope<ProductPayload>>() {
                });

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("tanggal ditulis sebagai string ISO-8601, bukan angka epoch")
    void datesAreIso8601() throws Exception {
        UUID id = UUID.randomUUID();
        String json = mapper.writeValueAsString(
                EventEnvelope.of(EventType.PRODUCT_CREATED, id.toString(), 1L, "t", samplePayload(id)));

        assertThat(json).contains("\"createdAt\":\"2026-08-16T03:00:00Z\"");
        assertThat(json).doesNotContain("1.7");
    }

    @Test
    @DisplayName("field tak dikenal dari producer versi lebih baru diabaikan, bukan bikin gagal")
    void unknownFieldsAreIgnored() throws Exception {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "PRODUCT_CREATED",
                  "aggregateType": "PRODUCT",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "occurredAt": "2026-08-16T03:00:00Z",
                  "traceId": "t",
                  "payload": { "sku": "SKU-001", "fieldBaruDariMasaDepan": 123 },
                  "amplopFieldBaru": "abaikan saya"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        EventEnvelope<ProductPayload> restored =
                mapper.readValue(json, new TypeReference<EventEnvelope<ProductPayload>>() {
                });

        assertThat(restored.payload().sku()).isEqualTo("SKU-001");
        assertThat(restored.eventType()).isEqualTo(EventType.PRODUCT_CREATED);
    }

    @Test
    @DisplayName("setiap panggilan of() menghasilkan eventId berbeda")
    void eventIdIsUnique() {
        UUID id = UUID.randomUUID();
        var a = EventEnvelope.of(EventType.PRODUCT_CREATED, id.toString(), 1L, "t", samplePayload(id));
        var b = EventEnvelope.of(EventType.PRODUCT_CREATED, id.toString(), 1L, "t", samplePayload(id));

        assertThat(a.eventId()).isNotEqualTo(b.eventId());
    }
}
