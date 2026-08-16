package com.example.write.api.dto;

import java.util.UUID;

/**
 * @param imageUrl URL siap pakai. Dalam mode PRESIGNED, URL ini <b>kedaluwarsa</b> — ambil ulang
 *                 lewat read-service kalau sudah lewat masa berlakunya, jangan disimpan.
 */
public record ImageResponse(
        UUID productId,
        String objectKey,
        String imageUrl,
        String contentType,
        long sizeBytes,
        long version
) {
}
