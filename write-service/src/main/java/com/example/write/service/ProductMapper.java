package com.example.write.service;

import com.example.common.dto.ProductPayload;
import com.example.write.domain.Product;

/**
 * Konversi entity ke payload event.
 *
 * <p>Ditulis tangan, bukan digenerate: hanya ada satu mapping di service ini, dan menambahkan
 * annotation processor demi satu method membuat build lebih rapuh daripada manfaatnya.
 */
public final class ProductMapper {

    private ProductMapper() {
    }

    public static ProductPayload toPayload(Product p) {
        return new ProductPayload(
                p.getId(),
                p.getSku(),
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getCurrency(),
                p.getStock(),
                p.getImageObjectKey(),
                p.getImageContentType(),
                p.getImageSizeBytes(),
                p.getStatus(),
                p.getVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
