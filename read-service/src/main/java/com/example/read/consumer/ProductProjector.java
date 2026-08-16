package com.example.read.consumer;

import com.example.common.dto.ProductPayload;
import com.example.common.event.EventEnvelope;
import com.example.read.cache.ProductCache;
import com.example.read.domain.ProcessedEvent;
import com.example.read.domain.ProductReadModel;
import com.example.read.repository.ProcessedEventRepository;
import com.example.read.repository.ProductReadModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

/**
 * Menerapkan event ke read model.
 *
 * <p>Ada dua penjagaan yang bekerja bersama-sama, dan keduanya diperlukan:
 *
 * <p><b>1. Deduplikasi lewat {@code processed_events}.</b> Kafka menjamin pengiriman
 * at-least-once, jadi pesan yang sama bisa datang dua kali — consumer restart sebelum offset
 * ter-commit, rebalance partisi, atau publisher outbox mengulang kiriman yang sebenarnya
 * sudah sampai.
 *
 * <p><b>2. Penjaga versi.</b> Event bisa datang tidak berurutan. Publisher outbox mengirim
 * satu batch tanpa menunggu satu per satu, jadi event #4 yang gagal bisa menyusul beberapa
 * detik setelah event #5 berhasil. Tanpa penjaga ini, data lama akan menimpa data baru —
 * dan hasilnya adalah read model yang salah secara permanen, tanpa error apa pun yang
 * menandainya.
 *
 * <p>Pembaruan read model dan pencatatan {@code processed_events} berada dalam satu transaksi,
 * sehingga tidak mungkin salah satunya terjadi tanpa yang lain.
 *
 * <p><b>Soal invalidasi cache:</b> penghapusan key Redis dijadwalkan lewat
 * {@code afterCommit}, bukan dijalankan langsung di tengah transaksi. Kalau dihapus lebih
 * awal, ada celah waktu ketika transaksi belum commit tetapi cache sudah kosong — dan
 * pembacaan yang masuk di celah itu akan mengisi ulang cache dari data <i>lama</i>, lalu
 * menyimpannya dengan TTL penuh. Hasilnya justru kebalikan dari yang diinginkan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductProjector {

    private final ProductReadModelRepository readModelRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ProductCache cache;

    public enum Outcome {
        APPLIED,
        /** Event ini sudah pernah diproses sebelumnya. */
        SKIPPED_DUPLICATE,
        /** Event lebih tua dari keadaan yang sudah tersimpan. */
        SKIPPED_STALE
    }

    @Transactional
    public Outcome project(EventEnvelope<ProductPayload> envelope) {
        UUID eventId = envelope.eventId();
        ProductPayload payload = envelope.payload();
        UUID productId = payload.id();

        if (processedEventRepository.existsById(eventId)) {
            log.debug("Event {} sudah pernah diproses, dilewati", eventId);
            return Outcome.SKIPPED_DUPLICATE;
        }

        Optional<ProductReadModel> existing = readModelRepository.findById(productId);

        if (existing.isPresent() && existing.get().isStale(envelope.aggregateVersion())) {
            log.info("Event {} untuk produk {} sudah usang (versi event {} <= versi tersimpan {}), diabaikan",
                    eventId, productId, envelope.aggregateVersion(), existing.get().getAggregateVersion());
            // Tetap dicatat sebagai sudah diproses: kalau tidak, event ini akan diperiksa
            // ulang setiap kali ia dikirim ulang.
            recordProcessed(envelope);
            return Outcome.SKIPPED_STALE;
        }

        String previousSku = existing.map(ProductReadModel::getSku).orElse(null);

        ProductReadModel model;
        if (existing.isPresent()) {
            model = existing.get();
            model.applySnapshot(payload, eventId);
        } else {
            model = ProductReadModel.from(payload, eventId);
        }
        readModelRepository.save(model);
        recordProcessed(envelope);

        scheduleCacheInvalidation(productId, payload.sku(), previousSku);

        log.info("Read model diperbarui: produk={} versi={} event={}",
                productId, envelope.aggregateVersion(), envelope.eventType());
        return Outcome.APPLIED;
    }

    private void recordProcessed(EventEnvelope<ProductPayload> envelope) {
        try {
            processedEventRepository.save(ProcessedEvent.of(
                    envelope.eventId(),
                    envelope.payload().id(),
                    envelope.aggregateVersion(),
                    envelope.eventType()));
        } catch (DataIntegrityViolationException e) {
            // Dua consumer memproses event yang sama secara bersamaan. Primary key menahan
            // yang kedua, dan itu memang perilaku yang diinginkan.
            throw new DuplicateEventException(envelope.eventId(), e);
        }
    }

    private void scheduleCacheInvalidation(UUID productId, String sku, String previousSku) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Di luar transaksi (mis. dipanggil langsung dari test) — hapus saja seketika.
            invalidate(productId, sku, previousSku);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(productId, sku, previousSku);
            }
        });
    }

    private void invalidate(UUID productId, String sku, String previousSku) {
        cache.evict(productId, sku);
        // Kalau SKU berubah, penunjuk lama harus ikut dibuang — kalau tidak, pencarian
        // dengan SKU lama akan terus menunjuk ke produk ini selama TTL-nya belum habis.
        if (previousSku != null && !previousSku.equals(sku)) {
            cache.evict(productId, previousSku);
        }
        cache.evictLists();
    }

    /** Ditandai terpisah supaya listener bisa memperlakukannya sebagai hal normal, bukan error. */
    public static class DuplicateEventException extends RuntimeException {

        public DuplicateEventException(UUID eventId, Throwable cause) {
            super("Event %s sudah diproses consumer lain".formatted(eventId), cause);
        }
    }
}
