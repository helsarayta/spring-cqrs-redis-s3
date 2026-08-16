package com.example.write.outbox;

import com.example.common.event.Topics;
import com.example.write.config.AppProperties;
import com.example.write.domain.OutboxEvent;
import com.example.write.domain.OutboxStatus;
import com.example.write.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Mengirim isi tabel outbox ke Kafka.
 *
 * <p>Ini satu-satunya komponen yang bicara ke Kafka. Konsekuensinya, endpoint tulis sama
 * sekali tidak terpengaruh kalau Kafka sedang mati: request tetap sukses, event menumpuk
 * sebagai PENDING, dan terkirim sendiri begitu broker pulih.
 *
 * <p><b>Soal urutan.</b> Batch dikirim tanpa menunggu satu per satu, jadi mungkin saja event
 * #5 sukses sementara #4 gagal dan baru terkirim beberapa detik kemudian — read model
 * menerimanya terbalik. Itu <i>ditoleransi dengan sadar</i>: consumer membandingkan
 * {@code aggregateVersion} dan membuang event yang lebih tua dari state yang sudah ia punya.
 * Menjamin urutan sempurna di sini berarti mengirim serial dan menghentikan seluruh antrean
 * setiap kali satu pesan bermasalah — harga yang jauh lebih mahal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    /** Batas tunggu satu batch. Lebih pendek dari delivery.timeout.ms produser (30s). */
    private static final Duration BATCH_SEND_TIMEOUT = Duration.ofSeconds(20);

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AppProperties properties;
    private final MeterRegistry meterRegistry;

    private Counter publishedCounter;
    private Counter retryCounter;
    private Counter deadCounter;

    @PostConstruct
    void initMetrics() {
        publishedCounter = Counter.builder("outbox.events.published")
                .description("Event outbox yang berhasil diterima broker").register(meterRegistry);
        retryCounter = Counter.builder("outbox.events.retry")
                .description("Percobaan kirim yang gagal dan dijadwalkan ulang").register(meterRegistry);
        deadCounter = Counter.builder("outbox.events.failed")
                .description("Event yang menyerah setelah melewati batas percobaan").register(meterRegistry);

        // Gauge ini yang paling layak dipasangi alert: kalau terus naik, artinya Kafka
        // bermasalah atau publisher berhenti bekerja, dan read model sedang tertinggal.
        Gauge.builder("outbox.events.pending", outboxRepository,
                        repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Event yang menunggu dikirim").register(meterRegistry);
        Gauge.builder("outbox.events.dead", outboxRepository,
                        repo -> repo.countByStatus(OutboxStatus.FAILED))
                .description("Event yang berstatus FAILED dan butuh perhatian manusia").register(meterRegistry);
    }

    /**
     * {@code fixedDelay} (bukan {@code fixedRate}): jeda dihitung setelah eksekusi sebelumnya
     * selesai, jadi polling yang lambat tidak menumpuk memanggil dirinya sendiri.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.lockPendingBatch(properties.outbox().batchSize());
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Mengirim {} event dari outbox", batch.size());

        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            futures.add(kafkaTemplate.send(toRecord(event)));
        }

        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            try {
                futures.get(i).get(BATCH_SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished();
                publishedCounter.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduleRetry(event, "Terinterupsi saat menunggu konfirmasi broker");
                break;
            } catch (ExecutionException | TimeoutException e) {
                scheduleRetry(event, rootMessage(e));
            }
        }

        outboxRepository.saveAll(batch);
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        // Key = id produk. Inilah yang menempatkan semua event satu produk di partisi yang
        // sama, sehingga urutannya terjaga di sisi consumer.
        ProducerRecord<String, String> record = new ProducerRecord<>(
                Topics.PRODUCT_EVENTS, event.getAggregateId().toString(), event.getPayload());

        addHeader(record, Topics.Headers.EVENT_ID, event.getEventId().toString());
        addHeader(record, Topics.Headers.EVENT_TYPE, event.getEventType().name());
        addHeader(record, Topics.Headers.AGGREGATE_ID, event.getAggregateId().toString());
        addHeader(record, Topics.Headers.AGGREGATE_VERSION, String.valueOf(event.getAggregateVersion()));
        return record;
    }

    private void addHeader(ProducerRecord<String, String> record, String key, String value) {
        record.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
    }

    private void scheduleRetry(OutboxEvent event, String error) {
        Instant next = nextAttemptAt(event.getAttempts());
        boolean deadLettered = event.markAttemptFailed(error, properties.outbox().maxAttempts(), next);

        if (deadLettered) {
            deadCounter.increment();
            log.error("Event outbox {} MENYERAH setelah {} percobaan. Read model untuk produk {} "
                            + "akan tertinggal sampai ini ditangani manual. Penyebab terakhir: {}",
                    event.getEventId(), event.getAttempts(), event.getAggregateId(), error);
        } else {
            retryCounter.increment();
            log.warn("Event outbox {} gagal dikirim (percobaan ke-{}), dicoba lagi pada {}. Penyebab: {}",
                    event.getEventId(), event.getAttempts(), next, error);
        }
    }

    /** Backoff eksponensial dengan batas atas, supaya broker yang sedang pulih tidak dihajar. */
    private Instant nextAttemptAt(int attemptsBefore) {
        long base = properties.outbox().baseBackoffSeconds();
        long max = properties.outbox().maxBackoffSeconds();
        // Dibatasi 30 supaya pergeseran bit tidak meluap pada nilai attempts yang besar.
        long delay = Math.min(max, base * (1L << Math.min(attemptsBefore, 30)));
        return Instant.now().plusSeconds(delay);
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /**
     * Membersihkan baris yang sudah terkirim.
     *
     * <p>Tanpa ini tabel outbox tumbuh selamanya. Baris PUBLISHED tidak dihapus seketika
     * karena masih berguna untuk menelusuri "event ini benar-benar terkirim jam berapa?"
     * saat menyelidiki masalah.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanupPublished() {
        Instant before = Instant.now().minus(Duration.ofHours(properties.outbox().retentionHours()));
        int deleted = outboxRepository.deletePublishedOlderThan(before);
        if (deleted > 0) {
            log.info("Membersihkan {} baris outbox yang sudah terkirim sebelum {}", deleted, before);
        }
    }

    /** Dipakai health indicator dan test untuk melihat kondisi outbox tanpa query manual. */
    public Map<String, Long> stats() {
        return Map.of(
                "pending", outboxRepository.countByStatus(OutboxStatus.PENDING),
                "failed", outboxRepository.countByStatus(OutboxStatus.FAILED),
                "published", outboxRepository.countByStatus(OutboxStatus.PUBLISHED));
    }
}
