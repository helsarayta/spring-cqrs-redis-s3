package com.example.read.service;

import com.example.common.dto.ProductPayload;
import com.example.common.dto.ProductStatus;
import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.common.storage.ImageUrlResolver;
import com.example.read.api.dto.PageResponse;
import com.example.read.api.dto.ProductView;
import com.example.read.cache.CacheKeys;
import com.example.read.cache.CacheMetrics;
import com.example.read.cache.CacheStatus;
import com.example.read.cache.ProductCache;
import com.example.read.config.ReadProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Pembacaan produk dengan pola cache-aside.
 *
 * <p>Urutannya persis seperti yang diminta: <b>Redis dulu, database kemudian</b>.
 *
 * <pre>
 *   GET produk
 *     1. tanya Redis
 *          ada isinya          -> jawab (HIT)
 *          Redis bilang kosong -> jawab 404 tanpa menyentuh database (NEGATIVE_HIT)
 *          Redis tidak tahu    -> lanjut
 *          Redis bermasalah    -> langsung ke database (BYPASS)
 *     2. ambil kunci single-flight supaya tidak semua request menembak database bersamaan
 *     3. baca database
 *          ketemu       -> simpan ke Redis (TTL + jitter) -> jawab (MISS)
 *          tidak ketemu -> simpan penanda "tidak ada" (TTL pendek) -> jawab 404
 * </pre>
 *
 * <p>Kelas ini tidak pernah menulis ke {@code readdb}. Satu-satunya penulis tabel itu adalah
 * projector yang memproses event Kafka. Akses database ada di {@link ProductReadDao} —
 * lihat kelas itu untuk alasan pemisahannya.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductQueryService {

    private final ProductReadDao dao;
    private final ProductCache cache;
    private final CacheMetrics metrics;
    private final CacheKeys cacheKeys;
    private final ImageUrlResolver imageUrlResolver;
    private final ReadProperties properties;
    private final ObjectMapper objectMapper;

    /** Hasil beserta asal jawabannya, supaya controller bisa mengisi header {@code X-Cache}. */
    public record Result<T>(T value, CacheStatus status) {
    }

    // ------------------------------------------------------------------ ambil satu produk

    public Result<ProductView> getById(UUID id) {
        ProductCache.Lookup lookup = cache.get(id);

        switch (lookup.presence()) {
            case FOUND -> {
                metrics.record(CacheStatus.HIT);
                return new Result<>(toView(lookup.payload()), CacheStatus.HIT);
            }
            case ABSENT -> {
                // Redis sudah pernah memastikan id ini tidak ada. Database tidak perlu
                // disentuh sama sekali — inilah gunanya negative caching.
                metrics.record(CacheStatus.NEGATIVE_HIT);
                throw ProductNotFoundException.byId(id, CacheStatus.NEGATIVE_HIT);
            }
            case UNAVAILABLE -> {
                // Metrik BYPASS sudah dicatat di dalam ProductCache. Database tetap melayani,
                // jadi Redis yang mati hanya membuat request lebih lambat, bukan gagal.
                return new Result<>(loadFromDatabase(id, false, CacheStatus.BYPASS), CacheStatus.BYPASS);
            }
            case NOT_CACHED -> {
                // lanjut ke bawah
            }
        }

        return new Result<>(loadWithSingleFlight(id), CacheStatus.MISS);
    }

    /**
     * Mengisi ulang cache dengan perlindungan terhadap stampede.
     *
     * <p>Kalau kunci gagal diambil, berarti ada request lain yang sedang mengisi cache untuk
     * key yang sama. Alih-alih ikut menembak database, request ini menunggu sebentar lalu
     * mengintip cache lagi. Kalau setelah beberapa kali cache masih kosong, ia tetap
     * melanjutkan ke database — menunggu tanpa batas jauh lebih buruk daripada satu query
     * berlebih.
     */
    private ProductView loadWithSingleFlight(UUID id) {
        ReadProperties.Cache config = properties.cache();

        if (!cache.tryLock(id)) {
            metrics.recordLockContended();
            for (int attempt = 0; attempt < config.lockRetries(); attempt++) {
                sleep(config.lockWaitMs());
                ProductCache.Lookup retry = cache.get(id);
                if (retry.presence() == ProductCache.Presence.FOUND) {
                    metrics.record(CacheStatus.HIT);
                    return toView(retry.payload());
                }
                if (retry.presence() == ProductCache.Presence.ABSENT) {
                    metrics.record(CacheStatus.NEGATIVE_HIT);
                    throw ProductNotFoundException.byId(id, CacheStatus.NEGATIVE_HIT);
                }
            }
            log.debug("Kunci single-flight untuk {} tidak didapat setelah menunggu; lanjut ke database", id);
            return loadFromDatabase(id, true, CacheStatus.MISS);
        }

        try {
            // Cek sekali lagi setelah memegang kunci: pemegang kunci sebelumnya mungkin baru
            // saja selesai mengisi cache tepat sebelum kunci ini didapat.
            ProductCache.Lookup afterLock = cache.get(id);
            if (afterLock.presence() == ProductCache.Presence.FOUND) {
                metrics.record(CacheStatus.HIT);
                return toView(afterLock.payload());
            }
            metrics.record(CacheStatus.MISS);
            return loadFromDatabase(id, true, CacheStatus.MISS);
        } finally {
            cache.unlock(id);
        }
    }

    /**
     * @param reportedStatus status yang dilaporkan ke klien kalau produknya ternyata tidak ada.
     *                       Bukan {@code NEGATIVE_HIT}: jawaban ini datang dari database, bukan
     *                       dari cache. Penanda "tidak ada" memang baru <i>ditulis</i> di sini,
     *                       dan barulah permintaan berikutnya yang boleh disebut negative hit.
     */
    private ProductView loadFromDatabase(UUID id, boolean populateCache, CacheStatus reportedStatus) {
        Optional<ProductPayload> found = dao.findActiveById(id);

        if (found.isEmpty()) {
            if (populateCache) {
                // Penanda "tidak ada" dengan TTL pendek. Melindungi database dari permintaan
                // berulang atas id yang memang tidak pernah ada.
                cache.putAbsent(id);
            }
            throw ProductNotFoundException.byId(id, reportedStatus);
        }

        if (populateCache) {
            cache.put(found.get());
        }
        return toView(found.get());
    }

    // ------------------------------------------------------------------ ambil berdasarkan SKU

    /**
     * Pencarian by-SKU memanfaatkan cache yang sama lewat key penunjuk.
     *
     * <p>SKU dipetakan ke id, lalu alur normal {@link #getById} dipakai. Produknya sendiri
     * hanya disimpan sekali di cache; kalau disalin per-SKU juga, setiap invalidasi harus
     * mengenai dua tempat dan keduanya pasti akan tidak sinkron suatu saat.
     */
    public Result<ProductView> getBySku(String sku) {
        Optional<UUID> pointed = cache.getIdBySku(sku);
        if (pointed.isPresent()) {
            return getById(pointed.get());
        }

        ProductPayload payload = dao.findActiveBySku(sku)
                .orElseThrow(() -> ProductNotFoundException.bySku(sku, CacheStatus.MISS));

        cache.put(payload);
        metrics.record(CacheStatus.MISS);
        return new Result<>(toView(payload), CacheStatus.MISS);
    }

    // ------------------------------------------------------------------ daftar produk

    /**
     * Daftar produk, dengan cache ber-TTL pendek.
     *
     * <p>Hasil daftar sengaja <b>tidak</b> di-invalidasi ketika ada produk berubah. Untuk
     * mengetahui daftar mana saja yang terpengaruh oleh satu perubahan, sistem harus melacak
     * setiap kombinasi filter dan halaman yang pernah di-cache — biayanya jauh melebihi
     * manfaatnya. Sebagai gantinya TTL-nya dibuat pendek (default 60 detik), sehingga daftar
     * boleh tertinggal paling lama selama itu. Pembacaan by-id tetap konsisten karena
     * key-nya memang di-invalidasi setiap ada perubahan.
     */
    public Result<PageResponse<ProductView>> list(ProductStatus status, String q,
                                                  BigDecimal minPrice, BigDecimal maxPrice,
                                                  Pageable pageable) {

        String key = cacheKeys.list(describeQuery(status, q, minPrice, maxPrice, pageable));

        Optional<String> cached = cache.getRaw(key);
        if (cached.isPresent()) {
            try {
                PageResponse<ProductPayload> page = objectMapper.readValue(
                        cached.get(), new TypeReference<PageResponse<ProductPayload>>() {
                        });
                metrics.record(CacheStatus.HIT);
                return new Result<>(page.map(this::toView), CacheStatus.HIT);
            } catch (Exception e) {
                log.debug("Isi cache daftar tidak bisa dibaca, mengambil ulang dari database: {}", e.toString());
            }
        }

        PageResponse<ProductPayload> fresh = dao.search(status, q, minPrice, maxPrice, pageable);

        try {
            cache.putRaw(key, objectMapper.writeValueAsString(fresh),
                    Duration.ofSeconds(properties.cache().listTtlSeconds()));
        } catch (Exception e) {
            log.debug("Gagal menyimpan cache daftar: {}", e.toString());
        }

        metrics.record(CacheStatus.MISS);
        return new Result<>(fresh.map(this::toView), CacheStatus.MISS);
    }

    private String describeQuery(ProductStatus status, String q, BigDecimal minPrice,
                                 BigDecimal maxPrice, Pageable pageable) {
        return "status=%s|q=%s|min=%s|max=%s|page=%d|size=%d|sort=%s".formatted(
                status, q, minPrice, maxPrice,
                pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
    }

    // ------------------------------------------------------------------ helper

    private ProductView toView(ProductPayload payload) {
        return ReadModelMapper.toView(payload, imageUrlResolver);
    }

    private void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
