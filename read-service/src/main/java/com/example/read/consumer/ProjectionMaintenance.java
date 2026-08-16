package com.example.read.consumer;

import com.example.read.config.ReadProperties;
import com.example.read.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Membersihkan catatan {@code processed_events} yang sudah lama.
 *
 * <p>Tabel ini bertambah satu baris untuk setiap event, jadi tanpa pembersihan ia akan
 * tumbuh selamanya dan pengecekan duplikat ikut melambat.
 *
 * <p>Batas retensinya <b>harus lebih panjang dari retensi topic Kafka</b> (default 7 hari).
 * Kalau lebih pendek, event yang masih tersimpan di topic dan kebetulan di-replay akan
 * terlihat seolah belum pernah diproses. Penjaga {@code aggregateVersion} memang masih
 * menahan dampaknya, tapi lapisan deduplikasi jadi kehilangan gunanya.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectionMaintenance {

    private final ProcessedEventRepository processedEventRepository;
    private final ReadProperties properties;

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void cleanupProcessedEvents() {
        Instant before = Instant.now()
                .minus(Duration.ofDays(properties.projection().processedEventRetentionDays()));

        int deleted = processedEventRepository.deleteOlderThan(before);
        if (deleted > 0) {
            log.info("Membersihkan {} catatan processed_events yang lebih tua dari {}", deleted, before);
        }
    }
}
