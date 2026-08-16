package com.example.read.service;

import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.read.cache.CacheStatus;

/**
 * "Produk tidak ditemukan", lengkap dengan keterangan dari mana kesimpulan itu berasal.
 *
 * <p>Status cache dibawa oleh exception karena response 404 tidak melewati controller —
 * ia langsung ditangani exception handler. Tanpa ini, header {@code X-Cache} hanya muncul
 * pada response sukses, dan justru kasus yang paling menarik untuk diamati yang hilang:
 * apakah 404 tadi dijawab Redis (negative cache bekerja) atau tetap menembak database
 * (negative cache tidak bekerja). Keduanya terlihat identik dari luar.
 */
public class ProductNotFoundException extends ApiException {

    private final transient CacheStatus cacheStatus;

    private ProductNotFoundException(String message, CacheStatus cacheStatus) {
        super(ErrorCode.PRODUCT_NOT_FOUND, message);
        this.cacheStatus = cacheStatus;
    }

    public static ProductNotFoundException byId(Object id, CacheStatus cacheStatus) {
        return new ProductNotFoundException("Product %s tidak ditemukan".formatted(id), cacheStatus);
    }

    public static ProductNotFoundException bySku(String sku, CacheStatus cacheStatus) {
        return new ProductNotFoundException(
                "Produk dengan SKU '%s' tidak ditemukan".formatted(sku), cacheStatus);
    }

    public CacheStatus cacheStatus() {
        return cacheStatus;
    }
}
