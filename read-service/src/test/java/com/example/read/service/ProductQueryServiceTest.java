package com.example.read.service;

import com.example.common.dto.ProductPayload;
import com.example.common.dto.ProductStatus;
import com.example.common.storage.ImageUrlResolver;
import com.example.read.cache.CacheKeys;
import com.example.read.cache.CacheMetrics;
import com.example.read.cache.CacheStatus;
import com.example.read.cache.ProductCache;
import com.example.read.config.ReadProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Menguji aturan yang menjadi inti permintaan: <b>Redis dulu, database kemudian</b>.
 *
 * <p>Yang diperiksa bukan sekadar hasil akhirnya benar, melainkan <i>siapa yang dipanggil</i>
 * pada tiap keadaan cache. Sebuah implementasi yang selalu menembak database tetap
 * mengembalikan jawaban yang benar — dan karena itu bug seperti ini tidak akan pernah
 * ketahuan dari isi response.
 */
@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    @Mock
    private ProductReadDao dao;

    @Mock
    private ProductCache cache;

    private ProductQueryService service;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReadProperties properties = new ReadProperties(
                new ReadProperties.Cache(true, "product", 600, 60, 30, 60, 200, true, 3000, 50, 3),
                new ReadProperties.S3("http://localhost:9000", "bucket", "us-east-1", "k", "s",
                        true, ReadProperties.UrlMode.PUBLIC, 15, "http://localhost:9000"),
                new ReadProperties.Projection(30));

        ImageUrlResolver urlResolver = objectKey -> objectKey == null ? null : "http://img/" + objectKey;

        service = new ProductQueryService(
                dao, cache, new CacheMetrics(new SimpleMeterRegistry()),
                new CacheKeys(properties), urlResolver, properties, new ObjectMapper());
    }

    private ProductPayload payload() {
        return new ProductPayload(id, "SKU-1", "Kopi", "enak", new BigDecimal("10000"), "IDR", 5,
                "products/x.png", "image/png", 100L, ProductStatus.ACTIVE, 3L,
                Instant.parse("2026-08-16T00:00:00Z"), Instant.parse("2026-08-16T01:00:00Z"));
    }

    @Test
    @DisplayName("cache berisi data: dijawab HIT dan database tidak disentuh sama sekali")
    void cacheHitDoesNotTouchDatabase() {
        when(cache.get(id)).thenReturn(new ProductCache.Lookup(ProductCache.Presence.FOUND, payload()));

        var result = service.getById(id);

        assertThat(result.status()).isEqualTo(CacheStatus.HIT);
        assertThat(result.value().name()).isEqualTo("Kopi");
        verifyNoInteractions(dao);
    }

    @Test
    @DisplayName("cache kosong: ambil dari database lalu isi cache, dilaporkan MISS")
    void cacheMissLoadsFromDatabaseAndPopulates() {
        when(cache.get(id)).thenReturn(new ProductCache.Lookup(ProductCache.Presence.NOT_CACHED, null));
        when(cache.tryLock(id)).thenReturn(true);
        when(dao.findActiveById(id)).thenReturn(Optional.of(payload()));

        var result = service.getById(id);

        assertThat(result.status()).isEqualTo(CacheStatus.MISS);
        verify(dao).findActiveById(id);
        verify(cache).put(any(ProductPayload.class));
        verify(cache).unlock(id);
    }

    @Test
    @DisplayName("negative cache: 404 dijawab tanpa menyentuh database")
    void negativeCacheAnswersWithoutDatabase() {
        when(cache.get(id)).thenReturn(new ProductCache.Lookup(ProductCache.Presence.ABSENT, null));

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(ProductNotFoundException.class)
                .extracting(e -> ((ProductNotFoundException) e).cacheStatus())
                .isEqualTo(CacheStatus.NEGATIVE_HIT);

        verifyNoInteractions(dao);
    }

    @Test
    @DisplayName("produk tidak ada: penanda 'tidak ada' ditulis ke cache, dilaporkan MISS bukan NEGATIVE_HIT")
    void missingProductWritesAbsentMarker() {
        when(cache.get(id)).thenReturn(new ProductCache.Lookup(ProductCache.Presence.NOT_CACHED, null));
        when(cache.tryLock(id)).thenReturn(true);
        when(dao.findActiveById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(ProductNotFoundException.class)
                // Jawaban ini datang dari database. Penanda baru DITULIS di sini; barulah
                // permintaan berikutnya yang pantas disebut negative hit.
                .extracting(e -> ((ProductNotFoundException) e).cacheStatus())
                .isEqualTo(CacheStatus.MISS);

        verify(cache).putAbsent(id);
    }

    @Test
    @DisplayName("Redis bermasalah: tetap dijawab dari database dengan BYPASS, bukan error")
    void redisUnavailableFallsBackToDatabase() {
        when(cache.get(id)).thenReturn(new ProductCache.Lookup(ProductCache.Presence.UNAVAILABLE, null));
        when(dao.findActiveById(id)).thenReturn(Optional.of(payload()));

        var result = service.getById(id);

        assertThat(result.status()).isEqualTo(CacheStatus.BYPASS);
        assertThat(result.value().name()).isEqualTo("Kopi");
        // Tidak ada gunanya menulis ke Redis yang sedang bermasalah, dan mencoba menulis
        // hanya menambah satu operasi yang pasti gagal pada tiap request.
        verify(cache, never()).put(any());
    }

    @Test
    @DisplayName("Redis bermasalah dan produk tidak ada: tidak menulis penanda ke cache")
    void redisUnavailableAndMissingDoesNotCacheAbsence() {
        when(cache.get(id)).thenReturn(new ProductCache.Lookup(ProductCache.Presence.UNAVAILABLE, null));
        when(dao.findActiveById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(ProductNotFoundException.class);

        verify(cache, never()).putAbsent(any());
    }

    @Test
    @DisplayName("kunci single-flight dilepas walaupun pencarian gagal")
    void lockIsReleasedOnFailure() {
        when(cache.get(id)).thenReturn(new ProductCache.Lookup(ProductCache.Presence.NOT_CACHED, null));
        when(cache.tryLock(id)).thenReturn(true);
        when(dao.findActiveById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(ProductNotFoundException.class);

        // Kalau kunci tidak dilepas, seluruh request untuk produk ini akan terhambat
        // sampai TTL kuncinya habis.
        verify(cache).unlock(id);
    }

    @Test
    @DisplayName("pencarian by-SKU memakai key penunjuk dan tidak menyalin produk ke cache kedua")
    void skuLookupUsesPointer() {
        when(cache.getIdBySku("SKU-1")).thenReturn(Optional.of(id));
        when(cache.get(id)).thenReturn(new ProductCache.Lookup(ProductCache.Presence.FOUND, payload()));

        var result = service.getBySku("SKU-1");

        assertThat(result.status()).isEqualTo(CacheStatus.HIT);
        verifyNoInteractions(dao);
    }
}
