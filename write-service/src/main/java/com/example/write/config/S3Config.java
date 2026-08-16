package com.example.write.config;

import com.example.common.storage.ImageUrlResolver;
import com.example.common.storage.PresignedImageUrlResolver;
import com.example.common.storage.PublicImageUrlResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/**
 * Klien S3.
 *
 * <p>Yang dipakai adalah AWS SDK v2 biasa, bukan pustaka khusus MinIO. MinIO berbicara
 * protokol S3, jadi satu-satunya perbedaan antara lingkungan lokal dan AWS asli adalah nilai
 * konfigurasi: {@code endpoint} dan {@code path-style-access}. Kodenya sama persis, sehingga
 * pindah ke S3 sungguhan tidak menuntut perubahan program.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final AppProperties properties;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.s3().endpoint()))
                .region(Region.of(properties.s3().region()))
                .credentialsProvider(staticCredentials())
                .serviceConfiguration(serviceConfiguration())
                .build();
    }

    /**
     * Presigner memakai alamat yang <b>terjangkau klien</b>, bukan alamat internal.
     *
     * <p>Ini penting begitu aplikasi dijalankan sebagai container. Di dalam jaringan docker,
     * storage dihubungi lewat {@code http://minio:9000}, dan nama itu ikut ditandatangani ke
     * dalam URL. Browser pengguna tidak mengenal host {@code minio}, jadi setiap URL gambar
     * yang dihasilkan akan gagal dibuka — padahal tidak ada yang salah dengan berkasnya.
     *
     * <p>Alamat untuk menandatangani dan alamat untuk mengunggah memang boleh berbeda:
     * presigner hanya menghitung tanda tangan secara lokal dan tidak pernah menghubungi S3.
     */
    @Bean
    public S3Presigner s3Presigner() {
        String signingEndpoint = clientFacingEndpoint();
        log.info("Presigned URL ditandatangani untuk alamat: {}", signingEndpoint);

        return S3Presigner.builder()
                .endpointOverride(URI.create(signingEndpoint))
                .region(Region.of(properties.s3().region()))
                .credentialsProvider(staticCredentials())
                .serviceConfiguration(serviceConfiguration())
                .build();
    }

    private String clientFacingEndpoint() {
        String publicBaseUrl = properties.s3().publicBaseUrl();
        return publicBaseUrl == null || publicBaseUrl.isBlank()
                ? properties.s3().endpoint()
                : publicBaseUrl;
    }

    /**
     * Memilih cara pembentukan URL berdasarkan konfigurasi.
     *
     * <p>Pemilihan dilakukan sekali saat startup, bukan setiap request, dan hasilnya dicatat
     * di log — supaya kalau gambar tiba-tiba menjawab 403, mode yang sedang aktif langsung
     * terlihat tanpa perlu menebak.
     */
    @Bean
    public ImageUrlResolver imageUrlResolver(S3Presigner presigner) {
        AppProperties.S3 s3 = properties.s3();
        log.info("Mode URL gambar: {}", s3.urlMode());

        return switch (s3.urlMode()) {
            case PRESIGNED -> new PresignedImageUrlResolver(
                    presigner, s3.bucket(), Duration.ofMinutes(s3.presignTtlMinutes()));
            case PUBLIC -> new PublicImageUrlResolver(s3.publicBaseUrl(), s3.bucket());
        };
    }

    private StaticCredentialsProvider staticCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.s3().accessKey(), properties.s3().secretKey()));
    }

    private S3Configuration serviceConfiguration() {
        return S3Configuration.builder()
                // MinIO hanya melayani gaya path (http://host/bucket/key). AWS S3 asli memakai
                // gaya virtual-host (https://bucket.s3.../key), jadi setel false di sana.
                .pathStyleAccessEnabled(properties.s3().pathStyleAccess())
                .build();
    }

    /**
     * Memastikan bucket ada saat startup.
     *
     * <p>Hanya kenyamanan untuk development. Di produksi bucket dibuat lewat infrastructure
     * as code bersama policy, versioning, dan lifecycle rule-nya — jadi setel
     * {@code app.s3.auto-create-bucket=false} di sana. Aplikasi yang membuat bucket sendiri
     * di produksi cenderung menghasilkan bucket tanpa policy yang benar.
     */
    @Bean
    public ApplicationRunner s3BucketInitializer(S3Client s3Client) {
        return args -> {
            String bucket = properties.s3().bucket();
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                log.info("Bucket S3 '{}' tersedia di {}", bucket, properties.s3().endpoint());
            } catch (NoSuchBucketException e) {
                if (!properties.s3().autoCreateBucket()) {
                    throw new IllegalStateException(
                            "Bucket '%s' tidak ada dan auto-create dimatikan".formatted(bucket), e);
                }
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Bucket S3 '{}' dibuat", bucket);
            } catch (S3Exception e) {
                // Jangan menggagalkan startup hanya karena storage belum siap: endpoint tulis
                // yang tidak menyentuh gambar tetap berguna. Kegagalan upload akan terlihat
                // jelas saat endpoint gambar dipanggil.
                log.warn("Tidak bisa memverifikasi bucket '{}' di {}: {}. "
                                + "Endpoint gambar akan gagal sampai storage tersedia.",
                        bucket, properties.s3().endpoint(), e.getMessage());
            }
        };
    }
}
