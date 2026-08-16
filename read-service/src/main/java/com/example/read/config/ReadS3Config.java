package com.example.read.config;

import com.example.common.storage.ImageUrlResolver;
import com.example.common.storage.PresignedImageUrlResolver;
import com.example.common.storage.PublicImageUrlResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/**
 * Read-service hanya perlu <b>membentuk URL</b> gambar; ia tidak pernah mengunggah atau
 * menghapus objek. Karena itu yang dibuat di sini cuma {@link S3Presigner}, bukan
 * {@code S3Client} penuh — dan presigner menghitung tanda tangan secara lokal, tanpa
 * memanggil S3 sama sekali.
 *
 * <p>Konsekuensinya yang menyenangkan: pembentukan URL tetap berjalan walaupun S3/MinIO
 * sedang tidak bisa dihubungi. Endpoint baca tidak ikut terganggu.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ReadS3Config {

    private final ReadProperties properties;

    /**
     * Presigner memakai alamat yang <b>terjangkau klien</b>, bukan alamat internal.
     *
     * <p>Di dalam jaringan docker, storage dihubungi lewat {@code http://minio:9000} dan nama
     * itu ikut ditandatangani ke dalam URL. Browser pengguna tidak mengenal host tersebut,
     * sehingga semua URL gambar gagal dibuka. Karena presigner hanya menghitung tanda tangan
     * secara lokal dan tidak pernah menghubungi S3, alamat penandatanganan boleh berbeda dari
     * alamat yang dipakai untuk mengunggah.
     */
    @Bean
    public S3Presigner s3Presigner() {
        ReadProperties.S3 s3 = properties.s3();
        String signingEndpoint = (s3.publicBaseUrl() == null || s3.publicBaseUrl().isBlank())
                ? s3.endpoint()
                : s3.publicBaseUrl();
        log.info("Presigned URL ditandatangani untuk alamat: {}", signingEndpoint);

        return S3Presigner.builder()
                .endpointOverride(URI.create(signingEndpoint))
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.pathStyleAccess())
                        .build())
                .build();
    }

    @Bean
    public ImageUrlResolver imageUrlResolver(S3Presigner presigner) {
        ReadProperties.S3 s3 = properties.s3();
        log.info("Mode URL gambar: {}", s3.urlMode());

        return switch (s3.urlMode()) {
            case PRESIGNED -> new PresignedImageUrlResolver(
                    presigner, s3.bucket(), Duration.ofMinutes(s3.presignTtlMinutes()));
            case PUBLIC -> new PublicImageUrlResolver(s3.publicBaseUrl(), s3.bucket());
        };
    }
}
