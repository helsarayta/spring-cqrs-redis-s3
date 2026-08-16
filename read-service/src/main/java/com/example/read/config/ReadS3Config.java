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

    @Bean
    public S3Presigner s3Presigner() {
        ReadProperties.S3 s3 = properties.s3();
        return S3Presigner.builder()
                .endpointOverride(URI.create(s3.endpoint()))
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
