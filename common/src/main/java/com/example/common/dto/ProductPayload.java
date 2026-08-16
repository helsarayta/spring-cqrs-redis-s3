package com.example.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot penuh sebuah produk, dikirim sebagai payload event.
 *
 * <p>Sengaja membawa <b>seluruh</b> state, bukan hanya field yang berubah. Konsekuensinya
 * projector di read-service cukup melakukan upsert tanpa perlu tahu event sebelumnya —
 * yang membuat pemrosesan idempotent secara alami.
 *
 * <p>Perhatikan: yang disimpan adalah {@code imageObjectKey}, <b>bukan</b> URL. URL dibentuk
 * saat response oleh read-service, karena presigned URL punya masa berlaku dan endpoint
 * S3/CDN bisa berubah tanpa migrasi data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductPayload(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        String currency,
        Integer stock,
        String imageObjectKey,
        String imageContentType,
        Long imageSizeBytes,
        ProductStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
