package com.example.write.outbox;

import com.example.common.dto.ProductPayload;
import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.common.event.EventEnvelope;
import com.example.common.event.EventType;
import com.example.common.event.Topics;
import com.example.common.tracing.Tracing;
import com.example.write.domain.OutboxEvent;
import com.example.write.domain.Product;
import com.example.write.repository.OutboxEventRepository;
import com.example.write.service.ProductMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mencatat event ke tabel outbox.
 *
 * <p>Kelas ini <b>tidak</b> membuka transaksi sendiri — sengaja. Ia harus ikut transaksi milik
 * pemanggil ({@code ProductWriteService}) supaya penulisan produk dan pencatatan event
 * commit atau rollback bersama-sama. Menambahkan {@code @Transactional(REQUIRES_NEW)} di sini
 * akan mematahkan seluruh jaminan outbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param product entity yang <b>sudah di-flush</b>, sehingga {@code version} sudah
     *                mencerminkan nilai setelah perubahan. Kalau dipanggil sebelum flush,
     *                event akan membawa versi lama dan read-service berpotensi membuangnya
     *                sebagai event basi.
     */
    public OutboxEvent record(EventType eventType, Product product) {
        ProductPayload payload = ProductMapper.toPayload(product);
        String traceId = MDC.get(Tracing.MDC_KEY);

        EventEnvelope<ProductPayload> envelope = EventEnvelope.of(
                eventType,
                product.getId().toString(),
                product.getVersion(),
                traceId,
                payload
        );

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(Topics.Headers.EVENT_ID, envelope.eventId().toString());
        headers.put(Topics.Headers.EVENT_TYPE, eventType.name());
        headers.put(Topics.Headers.AGGREGATE_ID, product.getId().toString());
        headers.put(Topics.Headers.AGGREGATE_VERSION, String.valueOf(product.getVersion()));
        if (traceId != null) {
            headers.put(Topics.Headers.TRACE_ID, traceId);
        }

        OutboxEvent row = OutboxEvent.pending(
                envelope.eventId(),
                EventEnvelope.AGGREGATE_PRODUCT,
                product.getId(),
                product.getVersion(),
                eventType,
                writeJson(envelope),
                writeJson(headers)
        );

        OutboxEvent saved = outboxRepository.save(row);
        log.debug("Outbox dicatat: eventId={} type={} produk={} versi={}",
                envelope.eventId(), eventType, product.getId(), product.getVersion());
        return saved;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Kalau ini terjadi, event tidak boleh diam-diam dilewati: lebih baik seluruh
            // transaksi gagal daripada produk tersimpan tanpa event dan read model tertinggal.
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Gagal membuat JSON event", e);
        }
    }
}
