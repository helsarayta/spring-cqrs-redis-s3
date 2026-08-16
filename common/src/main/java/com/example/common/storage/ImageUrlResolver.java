package com.example.common.storage;

/**
 * Mengubah object key jadi URL yang bisa dibuka klien.
 *
 * <p>Sengaja jadi abstraksi, karena ada dua cara yang sah dan pilihannya bergantung pada
 * bucket policy — lihat {@link PresignedImageUrlResolver} dan {@link PublicImageUrlResolver}.
 * Yang dipakai ditentukan lewat konfigurasi {@code app.s3.url-mode}, bukan lewat perubahan kode.
 *
 * <p>Perhatikan bahwa yang disimpan di database dan dibawa event selalu <b>object key</b>.
 * URL dibentuk baru setiap kali response disusun. Itu disengaja: URL bertanda tangan punya
 * masa berlaku, dan menyimpannya berarti menyimpan sesuatu yang pasti basi.
 */
public interface ImageUrlResolver {

    /**
     * @param objectKey boleh null (produk tanpa gambar)
     * @return URL siap pakai, atau null kalau objectKey null
     */
    String toUrl(String objectKey);
}
