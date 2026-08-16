package com.example.common.dto;

/**
 * Status siklus hidup produk.
 *
 * <p>{@link #DELETED} adalah soft delete: baris tetap ada di writedb dan di read model.
 * Alasannya, hard delete membuat event {@code PRODUCT_DELETED} tidak punya payload yang
 * bisa dipakai consumer untuk mendeteksi urutan (aggregateVersion), dan menyulitkan audit.
 */
public enum ProductStatus {
    ACTIVE,
    INACTIVE,
    DELETED
}
