package com.example.read.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Mencatat konfigurasi efektif saat startup, dengan rahasia disamarkan.
 *
 * <p>Yang paling sering ditanyakan saat menyelidiki masalah di sisi baca: cache-nya menyala
 * atau tidak, TTL-nya berapa, dan endpoint gambarnya menunjuk ke mana. Semua itu tersebar di
 * variabel lingkungan dan nilai default, jadi dirangkum sekali di sini.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigLogger {

    private final ReadProperties properties;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.data.redis.host}:${spring.data.redis.port}")
    private String redisAddress;

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBootstrap;

    @EventListener(ApplicationReadyEvent.class)
    public void logEffectiveConfig() {
        ReadProperties.Cache cache = properties.cache();
        ReadProperties.S3 s3 = properties.s3();

        log.info("""
                        Konfigurasi efektif read-service:
                          database        : {}
                          redis           : {}
                          kafka           : {}
                          cache           : {}
                          TTL produk      : {} detik (+ jitter 0..{})
                          TTL "tidak ada" : {} detik
                          TTL daftar      : {} detik
                          timeout redis   : {} ms
                          single-flight   : {}
                          s3 endpoint     : {}
                          mode URL gambar : {}""",
                datasourceUrl,
                redisAddress,
                kafkaBootstrap,
                cache.enabled() ? "AKTIF" : "DIMATIKAN — semua pembacaan langsung ke database",
                cache.ttlSeconds(),
                cache.ttlJitterSeconds(),
                cache.nullTtlSeconds(),
                cache.listTtlSeconds(),
                cache.timeoutMs(),
                cache.singleFlightEnabled() ? "aktif" : "mati",
                s3.endpoint(),
                s3.urlMode());
    }
}
