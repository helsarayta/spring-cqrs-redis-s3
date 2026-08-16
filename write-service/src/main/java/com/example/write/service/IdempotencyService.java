package com.example.write.service;

import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.write.domain.IdempotencyRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Membuat POST bisa diulang dengan aman.
 *
 * <p>Masalah yang diselesaikan: klien mengirim POST, koneksi putus sebelum response sampai,
 * klien tidak tahu apakah datanya masuk, lalu mengulang request. Tanpa perlindungan,
 * hasilnya dua produk dan tidak ada cara otomatis mengetahui mana yang duplikat.
 *
 * <p>Cara kerjanya: klien menyertakan header {@code Idempotency-Key}. Key itu dipesan lebih
 * dulu lewat {@link IdempotencyStore} dalam transaksi terpisah yang langsung commit — sehingga
 * request kembar yang datang bersamaan menabrak primary key dan tahu dirinya duplikat.
 * Response request pertama disimpan, dan request ulang menerima salinan persis response itu.
 *
 * <p>Perhatikan pembagian tugas: seluruh {@code @Transactional} ada di {@code IdempotencyStore},
 * tidak ada satu pun di sini. Lihat penjelasan di kelas tersebut untuk alasannya.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    /** {@code replayed} menandai response berasal dari catatan, bukan eksekusi baru. */
    public record Outcome<T>(T body, boolean replayed) {
    }

    public <T> Outcome<T> execute(String idempotencyKey,
                                  String endpoint,
                                  Object request,
                                  Class<T> responseType,
                                  Supplier<T> action) {

        // Header ini opsional. Tanpa key tidak ada yang bisa dijamin — jalankan apa adanya.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new Outcome<>(action.get(), false);
        }

        String requestHash = sha256(toJson(request));

        Optional<IdempotencyRecord> existing = store.reserve(idempotencyKey, endpoint, requestHash);
        if (existing.isPresent()) {
            return new Outcome<>(replay(existing.get(), idempotencyKey, requestHash, responseType), true);
        }

        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            // Aksi gagal, jadi tidak ada response yang layak diputar ulang. Pesanan dilepas
            // supaya klien bisa mencoba lagi memakai key yang sama.
            store.release(idempotencyKey);
            throw e;
        }

        store.complete(idempotencyKey, 201, toJson(result));
        return new Outcome<>(result, false);
    }

    private <T> T replay(IdempotencyRecord record, String key, String requestHash, Class<T> responseType) {
        if (!record.getRequestHash().equals(requestHash)) {
            // Key sama untuk isi request berbeda — hampir pasti bug di sisi klien.
            // Mengembalikan response lama akan menyesatkan, jadi kita tolak terang-terangan.
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key '%s' sudah dipakai untuk request dengan isi berbeda".formatted(key));
        }
        if (record.isInFlight()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Request dengan Idempotency-Key '%s' masih diproses. Coba lagi sebentar lagi.".formatted(key));
        }

        log.info("Memutar ulang response tersimpan untuk Idempotency-Key {}", key);
        try {
            return objectMapper.readValue(record.getResponseBody(), responseType);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Gagal membaca response tersimpan", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Gagal membuat JSON", e);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia di JVM ini", e);
        }
    }
}
