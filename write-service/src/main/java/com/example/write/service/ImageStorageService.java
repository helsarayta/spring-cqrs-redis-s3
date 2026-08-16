package com.example.write.service;

import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.write.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;

/** Operasi objek di S3/MinIO. Tidak tahu apa-apa soal produk maupun database. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStorageService {

    private final S3Client s3Client;
    private final AppProperties properties;

    /**
     * Menyusun key objek.
     *
     * <p>Setiap unggahan mendapat UUID baru, bukan nama tetap seperti {@code cover.jpg}.
     * Dua alasannya: mengganti gambar tidak menimpa objek lama (jadi masih bisa dibersihkan
     * belakangan secara terkendali), dan URL gambar baru pasti berbeda sehingga tidak
     * tertahan oleh cache browser maupun CDN yang masih memegang versi lama.
     */
    public String buildObjectKey(UUID productId, String extension) {
        return "products/%s/%s.%s".formatted(productId, UUID.randomUUID(), extension);
    }

    public void upload(String objectKey, ImageValidator.ValidatedImage image) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.s3().bucket())
                            .key(objectKey)
                            .contentType(image.contentType())
                            .contentLength(image.size())
                            .build(),
                    RequestBody.fromBytes(image.bytes()));

            log.info("Objek terunggah: {} ({} byte, {})", objectKey, image.size(), image.contentType());
        } catch (S3Exception e) {
            throw new ApiException(ErrorCode.STORAGE_ERROR,
                    "Gagal mengunggah gambar ke storage: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    /**
     * Menghapus objek tanpa melempar exception.
     *
     * <p>Dipakai di dua tempat yang sama-sama tidak boleh dijatuhkan oleh kegagalan hapus:
     * membersihkan gambar lama setelah penggantian berhasil, dan membatalkan unggahan ketika
     * penyimpanan ke database gagal. Objek yatim di bucket hanya memboroskan ruang;
     * menggagalkan request karena gagal menghapusnya justru merugikan pengguna.
     */
    public void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.s3().bucket())
                    .key(objectKey)
                    .build());
            log.info("Objek dihapus: {}", objectKey);
        } catch (RuntimeException e) {
            log.warn("Gagal menghapus objek {} — dibiarkan sebagai objek yatim di bucket. Penyebab: {}",
                    objectKey, e.toString());
        }
    }
}
