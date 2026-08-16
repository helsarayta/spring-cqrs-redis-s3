package com.example.common.storage;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * Membentuk URL bertanda tangan yang berlaku sementara.
 *
 * <p>Dipakai saat bucket bersifat private — pilihan default. Tanda tangan dihitung lokal dari
 * kredensial, jadi tidak ada panggilan jaringan ke S3 di sini dan pembentukan URL murah.
 *
 * <p>Konsekuensi yang perlu diingat: URL yang dihasilkan <b>kedaluwarsa</b>. Jangan menyimpannya
 * ke cache yang umurnya lebih panjang dari {@code ttl}, dan jangan menaruhnya di dokumen yang
 * berumur panjang. Yang boleh disimpan adalah object key-nya.
 */
public class PresignedImageUrlResolver implements ImageUrlResolver {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    public PresignedImageUrlResolver(S3Presigner presigner, String bucket, Duration ttl) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.ttl = ttl;
    }

    @Override
    public String toUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }

        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build())
                .build();

        return presigner.presignGetObject(request).url().toString();
    }

    public Duration ttl() {
        return ttl;
    }
}
