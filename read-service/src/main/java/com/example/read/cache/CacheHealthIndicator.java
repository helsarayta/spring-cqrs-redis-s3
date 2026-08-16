package com.example.read.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Melaporkan keterjangkauan Redis <b>tanpa pernah menyatakan service ini DOWN</b>.
 *
 * <p>Health indicator Redis bawaan sengaja dimatikan di {@code application.yml}, dan diganti
 * dengan yang ini. Alasannya: pembacaan sudah dirancang untuk tetap berjalan lewat database
 * ketika Redis mati. Kalau statusnya dilaporkan DOWN, load balancer akan menarik instance
 * yang sebenarnya masih melayani seluruh permintaan dengan benar — hanya sedikit lebih lambat.
 * Gangguan kecil pada cache berubah menjadi layanan yang tidak bisa diakses sama sekali.
 *
 * <p>Jadi status di sini selalu UP; yang berubah hanya detailnya. Untuk memasang alert,
 * gunakan metrik {@code cache.product.bypass} yang akan melonjak saat Redis bermasalah.
 */
// Nama bean menentukan kunci komponen di /actuator/health. Tidak boleh "productCache",
// karena nama itu sudah dipakai bean ProductCache dan konteks gagal dimuat.
@Component("readCache")
@RequiredArgsConstructor
public class CacheHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;

    @Override
    public Health health() {
        try {
            String pong = redis.execute(connection -> connection.ping(), true);
            return Health.up()
                    .withDetail("redis", "terjangkau")
                    .withDetail("ping", pong)
                    .withDetail("dampakJikaMati", "tidak ada; pembacaan dialihkan ke database")
                    .build();
        } catch (RuntimeException e) {
            return Health.up()
                    .withDetail("redis", "TIDAK terjangkau")
                    .withDetail("penyebab", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .withDetail("dampak",
                            "Pembacaan dialihkan ke database dan tetap dilayani. Status sengaja "
                                    + "tetap UP; pantau metrik cache.product.bypass.")
                    .build();
        }
    }
}
