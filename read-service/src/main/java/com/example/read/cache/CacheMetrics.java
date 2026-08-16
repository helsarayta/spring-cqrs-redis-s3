package com.example.read.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Pencacah efektivitas cache.
 *
 * <p>Rasio hit adalah satu-satunya cara mengetahui cache benar-benar berguna. Cache dengan
 * rasio hit rendah bukan hal netral — ia menambah satu perjalanan jaringan pada setiap
 * request tanpa memberi imbalan apa pun.
 *
 * <p>{@code bypass} yang naik berarti Redis sedang bermasalah. Karena health check sengaja
 * tidak menandai service ini DOWN saat Redis mati, metrik inilah tempat memasang alert.
 */
@Component
public class CacheMetrics {

    private final Counter hit;
    private final Counter miss;
    private final Counter negativeHit;
    private final Counter bypass;
    private final Counter evict;
    private final Counter lockContended;

    public CacheMetrics(MeterRegistry registry) {
        this.hit = counter(registry, "hit", "Dijawab dari Redis");
        this.miss = counter(registry, "miss", "Tidak ada di Redis, diambil dari database");
        this.negativeHit = counter(registry, "negative_hit", "Redis menjawab bahwa id tidak ada");
        this.bypass = counter(registry, "bypass", "Redis dilewati karena bermasalah atau dimatikan");
        this.evict = counter(registry, "evict", "Key dihapus setelah read model berubah");
        this.lockContended = counter(registry, "lock_contended", "Gagal mengambil kunci single-flight");
    }

    private Counter counter(MeterRegistry registry, String outcome, String description) {
        return Counter.builder("cache.product." + outcome).description(description).register(registry);
    }

    public void record(CacheStatus status) {
        switch (status) {
            case HIT -> hit.increment();
            case MISS -> miss.increment();
            case NEGATIVE_HIT -> negativeHit.increment();
            case BYPASS -> bypass.increment();
        }
    }

    public void recordEvict() {
        evict.increment();
    }

    public void recordLockContended() {
        lockContended.increment();
    }
}
