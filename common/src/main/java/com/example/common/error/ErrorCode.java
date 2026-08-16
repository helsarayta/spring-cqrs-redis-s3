package com.example.common.error;

/**
 * Kode error yang stabil untuk klien.
 *
 * <p>Klien sebaiknya bercabang pada {@code code} ini, bukan pada teks {@code message}
 * (yang boleh berubah kapan saja) maupun pada status HTTP saja (yang terlalu kasar —
 * 409 bisa berarti SKU duplikat atau konflik versi, dan keduanya butuh penanganan berbeda).
 *
 * <p>Status HTTP dibawa sebagai {@code int} agar module ini tidak terikat ke Spring Web.
 */
public enum ErrorCode {

    VALIDATION_FAILED(400),
    INVALID_REQUEST(400),

    PRODUCT_NOT_FOUND(404),

    SKU_ALREADY_EXISTS(409),
    /** Terjadi saat If-Match / optimistic lock gagal: ada yang mengubah data duluan. */
    VERSION_CONFLICT(409),
    /** Idempotency-Key yang sama dipakai untuk request dengan isi berbeda. */
    IDEMPOTENCY_KEY_REUSED(409),

    /** Ukuran file melebihi batas. */
    IMAGE_TOO_LARGE(413),
    /** Tipe file tidak didukung, atau isi file tidak cocok dengan content-type yang diklaim. */
    UNSUPPORTED_IMAGE_TYPE(415),

    STORAGE_ERROR(500),
    INTERNAL_ERROR(500),
    /** Dependensi hilir (DB/Kafka) sedang tidak tersedia. */
    SERVICE_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
