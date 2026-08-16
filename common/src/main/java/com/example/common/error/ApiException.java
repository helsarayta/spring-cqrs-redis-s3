package com.example.common.error;

/**
 * Exception aplikasi yang sudah membawa {@link ErrorCode}, sehingga handler global
 * tidak perlu menebak-nebak status HTTP dari tipe exception.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    // --- factory untuk kasus yang sering dipakai ---

    public static ApiException notFound(Object id) {
        return new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product %s tidak ditemukan".formatted(id));
    }

    public static ApiException skuExists(String sku) {
        return new ApiException(ErrorCode.SKU_ALREADY_EXISTS, "SKU '%s' sudah dipakai produk lain".formatted(sku));
    }

    public static ApiException versionConflict(Object id, Long expected, Long actual) {
        return new ApiException(ErrorCode.VERSION_CONFLICT,
                "Product %s sudah diubah pihak lain (diharapkan versi %s, sekarang %s). Ambil ulang datanya lalu coba lagi."
                        .formatted(id, expected, actual));
    }
}
