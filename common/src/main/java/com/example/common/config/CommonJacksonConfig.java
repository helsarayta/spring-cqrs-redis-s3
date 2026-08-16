package com.example.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Konfigurasi Jackson yang <b>wajib sama</b> di kedua service.
 *
 * <p>Kalau write-service dan read-service punya setelan berbeda, event yang ditulis satu sisi
 * bisa gagal dibaca sisi lain — dan kegagalannya baru ketahuan di runtime, di consumer,
 * setelah data sudah terlanjur di Kafka. Karena itu setelan ini ditaruh di module bersama.
 *
 * <p>Kedua aplikasi memindai {@code com.example} sebagai base package, sehingga kelas ini
 * ikut terbaca otomatis.
 */
@Configuration
public class CommonJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer commonJacksonCustomizer() {
        return builder -> builder
                // Instant ditulis sebagai string ISO-8601, bukan angka epoch.
                // Angka epoch bikin payload di Kafka sulit dibaca manusia saat debugging.
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Producer boleh menambah field baru tanpa merusak consumer versi lama.
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
