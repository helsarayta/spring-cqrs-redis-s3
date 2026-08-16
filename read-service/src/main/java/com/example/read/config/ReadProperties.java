package com.example.read.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record ReadProperties(
        Cache cache,
        S3 s3,
        Projection projection
) {

    public record Cache(

            /** Sakelar darurat: matikan untuk memaksa semua pembacaan langsung ke database. */
            boolean enabled,

            @NotBlank String keyPrefix,

            @Min(1) int ttlSeconds,

            /**
             * Jitter acak 0..N detik yang ditambahkan ke TTL.
             *
             * <p>Tanpa ini, semua key yang dibuat pada saat yang sama akan kedaluwarsa pada
             * saat yang sama juga — dan database menerima lonjakan serentak setiap kali itu
             * terjadi. Menyebarkan waktu kedaluwarsa meratakan lonjakan tersebut.
             */
            @Min(0) int ttlJitterSeconds,

            /**
             * TTL untuk menandai "id ini memang tidak ada".
             *
             * <p>Sengaja pendek. Tanpa negative caching, permintaan berulang atas id yang tidak
             * ada akan selalu lolos ke database — jalur yang mudah sekali disalahgunakan.
             * Tapi kalau terlalu panjang, produk yang baru dibuat akan tampak belum ada
             * lebih lama dari yang perlu.
             */
            @Min(1) int nullTtlSeconds,

            /**
             * TTL cache hasil daftar/paging.
             *
             * <p>Jauh lebih pendek dari cache per-produk karena hasil daftar TIDAK
             * di-invalidasi saat ada perubahan — melacak daftar mana saja yang terpengaruh
             * oleh satu perubahan produk jauh lebih mahal daripada sekadar membiarkannya
             * kedaluwarsa cepat.
             */
            @Min(1) int listTtlSeconds,

            /**
             * Batas waktu operasi Redis.
             *
             * <p>Harus jauh lebih kecil dari timeout query database. Cache yang lambat
             * seharusnya membuat request sedikit lebih lambat, bukan menggantungnya.
             */
            @Min(10) long timeoutMs,

            /** Perlindungan cache stampede. Lihat ProductQueryService. */
            boolean singleFlightEnabled,
            @Min(100) int lockTtlMs,
            @Min(0) int lockWaitMs,
            @Min(0) int lockRetries
    ) {
    }

    public record S3(
            @NotBlank String endpoint,
            @NotBlank String bucket,
            @NotBlank String region,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            boolean pathStyleAccess,
            UrlMode urlMode,
            @Min(1) int presignTtlMinutes,
            String publicBaseUrl
    ) {
    }

    public record Projection(
            /**
             * Berapa lama catatan processed_events disimpan.
             *
             * <p>Harus lebih panjang dari retensi topic Kafka (default 7 hari), kalau tidak
             * event yang di-replay dari topic akan terlihat seolah belum pernah diproses.
             */
            @Min(1) @Max(365) int processedEventRetentionDays
    ) {
    }

    /** Harus sama persis dengan enum yang sama di write-service — keduanya membaca config yang sama. */
    public enum UrlMode {
        PRESIGNED,
        PUBLIC
    }
}
