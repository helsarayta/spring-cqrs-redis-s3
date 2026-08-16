package com.example.read.api.dto;

import com.example.common.dto.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bentuk produk yang dikirim ke klien.
 *
 * <p>Bedanya dengan yang disimpan di cache maupun database: di sini ada {@code imageUrl},
 * dan tidak ada {@code imageObjectKey}. Object key adalah detail internal storage, sedangkan
 * URL dibentuk baru setiap kali response disusun — karena dalam mode PRESIGNED, URL punya
 * masa berlaku dan tidak boleh ikut tersimpan di mana pun.
 */
public record ProductView(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        String currency,
        Integer stock,
        String imageUrl,
        String imageContentType,
        Long imageSizeBytes,
        ProductStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
