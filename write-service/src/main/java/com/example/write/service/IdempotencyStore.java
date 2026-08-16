package com.example.write.service;

import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.write.config.AppProperties;
import com.example.write.domain.IdempotencyRecord;
import com.example.write.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Akses transaksional ke tabel idempotency.
 *
 * <p>Kelas ini terpisah dari {@link IdempotencyService} bukan karena rapi-rapi belaka:
 * {@code @Transactional} bekerja lewat proxy, dan proxy dilewati kalau method dipanggil dari
 * dalam kelas yang sama. Kalau method-method ini ada di {@code IdempotencyService} dan
 * dipanggil oleh {@code execute()}, {@code REQUIRES_NEW} tidak akan berlaku sama sekali —
 * pemesanan key ikut transaksi utama, tidak commit lebih dulu, dan seluruh perlindungan
 * terhadap request kembar hilang tanpa satu pun error muncul.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private final IdempotencyRepository repository;
    private final AppProperties properties;

    /**
     * Memesan key dalam transaksi tersendiri yang langsung commit.
     *
     * @return catatan yang sudah ada kalau key pernah dipakai; kosong kalau pemesanan berhasil.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> reserve(String key, String endpoint, String requestHash) {
        Optional<IdempotencyRecord> existing = repository.findById(key);
        if (existing.isPresent()) {
            return existing;
        }
        try {
            repository.saveAndFlush(
                    IdempotencyRecord.start(key, endpoint, requestHash, properties.idempotency().ttlHours()));
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            // Balapan: request kembar memesan duluan di sela findById dan save.
            return Optional.of(repository.findById(key)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "Idempotency key %s hilang setelah bentrok penyimpanan".formatted(key))));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, int status, String responseJson) {
        repository.findById(key).ifPresent(record -> {
            record.complete(status, responseJson);
            repository.save(record);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        try {
            repository.deleteById(key);
        } catch (RuntimeException e) {
            // Tidak boleh menutupi exception asli yang sedang dilempar pemanggil.
            log.warn("Gagal melepas idempotency key {}: {}", key, e.toString());
        }
    }
}
