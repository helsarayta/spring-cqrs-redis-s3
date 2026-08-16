package com.example.write.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Seluruh setelan khusus aplikasi, terkumpul di satu tempat dan tervalidasi saat startup.
 *
 * <p>Validasi di sini disengaja: salah ketik pada nilai seperti {@code max-attempts: 0} akan
 * membuat outbox menyerah pada percobaan pertama. Lebih baik aplikasi menolak menyala
 * daripada berjalan dengan perilaku yang diam-diam salah.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Outbox outbox,
        Idempotency idempotency,
        S3 s3
) {

    public record Outbox(
            @Min(50) long pollIntervalMs,
            @Min(1) @Max(1000) int batchSize,
            @Min(1) int maxAttempts,
            @Min(1) long baseBackoffSeconds,
            @Min(1) long maxBackoffSeconds,
            @Min(1) int retentionHours
    ) {
    }

    public record Idempotency(
            @Min(1) int ttlHours
    ) {
    }

    public record S3(
            @NotBlank String endpoint,
            @NotBlank String bucket,
            @NotBlank String region,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            boolean pathStyleAccess,
            boolean autoCreateBucket,
            @Min(1) @Max(100) int maxImageSizeMb,
            UrlMode urlMode,
            @Min(1) int presignTtlMinutes,
            String publicBaseUrl
    ) {

        public long maxImageSizeBytes() {
            return (long) maxImageSizeMb * 1024 * 1024;
        }
    }

    /** Cara URL gambar dibentuk. Bucket policy di MinIO/S3 harus disetel sesuai pilihan ini. */
    public enum UrlMode {

        /** Bucket private; URL bertanda tangan dan kedaluwarsa setelah {@code presignTtlMinutes}. */
        PRESIGNED,

        /** Bucket/CDN public-read; URL statis dan bisa di-cache lama. */
        PUBLIC
    }
}
