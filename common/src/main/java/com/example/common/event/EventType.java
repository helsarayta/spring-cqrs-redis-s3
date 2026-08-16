package com.example.common.event;

/**
 * Jenis event yang diterbitkan write-service dan dikonsumsi read-service.
 *
 * <p>Nama konstanta ikut masuk ke JSON dan ke header Kafka, jadi <b>tidak boleh di-rename</b>
 * tanpa menaikkan versi topic ({@link Topics#PRODUCT_EVENTS} berakhiran {@code .v1}).
 * Menambah konstanta baru aman; consumer lama akan mengabaikannya.
 */
public enum EventType {

    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_DELETED,

    /** Gambar baru diunggah / diganti. Payload membawa objectKey terbaru. */
    PRODUCT_IMAGE_UPDATED,

    /** Gambar dihapus. Payload membawa objectKey = null. */
    PRODUCT_IMAGE_REMOVED
}
