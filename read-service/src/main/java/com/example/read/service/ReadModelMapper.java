package com.example.read.service;

import com.example.common.dto.ProductPayload;
import com.example.common.storage.ImageUrlResolver;
import com.example.read.api.dto.ProductView;
import com.example.read.domain.ProductReadModel;

/** Konversi antara entity read model, bentuk yang di-cache, dan bentuk yang dikirim ke klien. */
public final class ReadModelMapper {

    private ReadModelMapper() {
    }

    /**
     * Bentuk yang disimpan di cache.
     *
     * <p>Yang di-cache adalah ini, bukan {@link ProductView}, karena view mengandung URL
     * bertanda tangan yang masa berlakunya lebih pendek dari TTL cache. Menyimpan view
     * berarti menyimpan URL yang dijamin basi sebelum cache-nya sendiri kedaluwarsa.
     */
    public static ProductPayload toPayload(ProductReadModel model) {
        return new ProductPayload(
                model.getId(),
                model.getSku(),
                model.getName(),
                model.getDescription(),
                model.getPrice(),
                model.getCurrency(),
                model.getStock(),
                model.getImageObjectKey(),
                model.getImageContentType(),
                model.getImageSizeBytes(),
                model.getStatus(),
                model.getAggregateVersion(),
                model.getSourceCreatedAt(),
                model.getSourceUpdatedAt());
    }

    /** URL dibentuk di sini, saat response disusun — bukan saat data disimpan. */
    public static ProductView toView(ProductPayload payload, ImageUrlResolver urlResolver) {
        return new ProductView(
                payload.id(),
                payload.sku(),
                payload.name(),
                payload.description(),
                payload.price(),
                payload.currency(),
                payload.stock(),
                urlResolver.toUrl(payload.imageObjectKey()),
                payload.imageContentType(),
                payload.imageSizeBytes(),
                payload.status(),
                payload.version(),
                payload.createdAt(),
                payload.updatedAt());
    }
}
