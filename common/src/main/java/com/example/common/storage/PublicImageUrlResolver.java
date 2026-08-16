package com.example.common.storage;

/**
 * Membentuk URL statis untuk bucket (atau CDN) yang boleh dibaca publik.
 *
 * <p>URL-nya tidak pernah kedaluwarsa, jadi aman di-cache lama dan enak dipakai di CDN.
 * Harganya: siapa pun yang mengetahui URL bisa mengunduh objeknya, selamanya. Pilih mode ini
 * hanya kalau gambarnya memang tidak rahasia — misalnya foto katalog produk publik.
 *
 * <p>Mode ini <b>harus</b> dipasangkan dengan bucket policy yang mengizinkan pembacaan anonim,
 * kalau tidak semua URL yang dihasilkan akan menjawab 403.
 */
public class PublicImageUrlResolver implements ImageUrlResolver {

    private final String baseUrl;
    private final String bucket;

    public PublicImageUrlResolver(String baseUrl, String bucket) {
        // Slash di ujung dinormalkan supaya tidak menghasilkan URL dengan "//" di tengah.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.bucket = bucket;
    }

    @Override
    public String toUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return "%s/%s/%s".formatted(baseUrl, bucket, objectKey);
    }
}
