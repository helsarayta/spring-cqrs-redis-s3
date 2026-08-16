package com.example.read.consumer;

import com.example.common.dto.ProductPayload;
import com.example.common.event.EventEnvelope;
import com.example.common.event.Topics;
import com.example.common.tracing.Tracing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Menerima event dari Kafka dan menyerahkannya ke {@link ProductProjector}.
 *
 * <p>Listener ini sengaja tipis. Seluruh logika penerapan — deduplikasi, penjaga versi,
 * transaksi, invalidasi cache — ada di projector, yang merupakan bean terpisah supaya
 * {@code @Transactional} miliknya benar-benar melewati proxy Spring.
 *
 * <p>Offset di-commit oleh container setelah method ini selesai tanpa exception
 * ({@code ack-mode: record}). Jadi kalau penerapan gagal, offset tidak maju dan pesan akan
 * dicoba lagi — bukan hilang diam-diam.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventListener {

    private final ObjectMapper objectMapper;
    private final ProductProjector projector;

    @KafkaListener(topics = Topics.PRODUCT_EVENTS, groupId = Topics.READ_MODEL_GROUP)
    public void onProductEvent(ConsumerRecord<String, String> record) {
        String traceId = header(record, Topics.Headers.TRACE_ID);
        if (traceId != null) {
            // Menyambung kembali jejak dari request HTTP di write-service, sehingga satu
            // operasi tulis bisa ditelusuri sampai ke pembaruan read model di sini.
            MDC.put(Tracing.MDC_KEY, traceId);
        }

        try {
            EventEnvelope<ProductPayload> envelope = parse(record);
            ProductProjector.Outcome outcome = projector.project(envelope);

            if (outcome != ProductProjector.Outcome.APPLIED) {
                log.debug("Event {} tidak diterapkan: {}", envelope.eventId(), outcome);
            }
        } catch (ProductProjector.DuplicateEventException e) {
            // Bukan kegagalan: consumer lain sudah memproses event ini. Tidak perlu retry,
            // dan pesan tidak boleh dikirim ke dead letter topic.
            log.debug("{}", e.getMessage());
        } finally {
            MDC.remove(Tracing.MDC_KEY);
        }
    }

    private EventEnvelope<ProductPayload> parse(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readValue(record.value(), new TypeReference<EventEnvelope<ProductPayload>>() {
            });
        } catch (JsonProcessingException e) {
            // Sengaja dibungkus jadi exception yang ditandai tidak-untuk-diulang: JSON yang
            // rusak tidak akan berubah jadi benar berapa kali pun dicoba. Mengulangnya hanya
            // menahan seluruh partisi. Pesan seperti ini harus langsung ke dead letter topic
            // untuk diperiksa manusia.
            throw new UnparseableEventException(
                    "Event di %s-%d offset %d tidak bisa dibaca".formatted(
                            record.topic(), record.partition(), record.offset()), e);
        }
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Event yang isinya tidak bisa dibaca. Tidak pernah diulang — langsung ke DLT. */
    public static class UnparseableEventException extends RuntimeException {

        public UnparseableEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
