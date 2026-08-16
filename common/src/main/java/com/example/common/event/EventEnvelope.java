package com.example.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Amplop standar untuk setiap event yang lewat Kafka.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} disengaja: producer boleh menambah
 * field baru tanpa membuat consumer versi lama meledak (forward compatibility).
 *
 * @param eventId          identitas unik event — dipakai consumer untuk deduplikasi.
 *                         Dibuat sekali saat event ditulis ke outbox, <b>bukan</b> saat dikirim,
 *                         supaya retry pengiriman tetap membawa id yang sama.
 * @param aggregateVersion versi agregat <i>setelah</i> perubahan. Consumer memakai ini untuk
 *                         membuang event basi: kalau versi ini &lt;= versi yang sudah tersimpan
 *                         di read model, event diabaikan.
 * @param payload          snapshot penuh agregat setelah perubahan (bukan delta) — lihat PLAN.md §8.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
        UUID eventId,
        EventType eventType,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        String traceId,
        T payload
) {

    public static final String AGGREGATE_PRODUCT = "PRODUCT";

    public static <T> EventEnvelope<T> of(EventType eventType,
                                          String aggregateId,
                                          long aggregateVersion,
                                          String traceId,
                                          T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                AGGREGATE_PRODUCT,
                aggregateId,
                aggregateVersion,
                Instant.now(),
                traceId,
                payload
        );
    }
}
