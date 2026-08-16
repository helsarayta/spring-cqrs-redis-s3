package com.example.write.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Mencatat konfigurasi efektif saat startup, dengan rahasia disamarkan.
 *
 * <p>Sangat menghemat waktu saat menyelidiki keluhan seperti "gambarnya 403" atau "datanya
 * tidak muncul": pertanyaan pertama selalu "sebenarnya service ini menunjuk ke mana?".
 * Tanpa catatan ini, jawabannya harus dirangkai dari variabel lingkungan, file profil, dan
 * nilai default yang tersebar.
 *
 * <p>Nilai rahasia hanya ditampilkan beberapa karakter awalnya — cukup untuk memastikan
 * kredensial yang terbaca memang yang dimaksud, tanpa membocorkannya ke log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigLogger {

    private final AppProperties properties;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrap;

    @EventListener(ApplicationReadyEvent.class)
    public void logEffectiveConfig() {
        AppProperties.S3 s3 = properties.s3();
        AppProperties.Outbox outbox = properties.outbox();

        log.info("""
                        Konfigurasi efektif write-service:
                          database        : {}
                          kafka           : {}
                          s3 endpoint     : {}
                          s3 bucket       : {}
                          s3 access key   : {}
                          mode URL gambar : {}
                          batas gambar    : {} MB
                          outbox          : poll {} ms, batch {}, maks {} percobaan""",
                datasourceUrl,
                kafkaBootstrap,
                s3.endpoint(),
                s3.bucket(),
                mask(s3.accessKey()),
                s3.urlMode(),
                s3.maxImageSizeMb(),
                outbox.pollIntervalMs(),
                outbox.batchSize(),
                outbox.maxAttempts());
    }

    private String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "(kosong)";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return secret.substring(0, 3) + "*".repeat(Math.min(8, secret.length() - 3));
    }
}
