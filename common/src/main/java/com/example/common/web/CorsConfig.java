package com.example.common.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS, dimatikan secara default.
 *
 * <p>Default-nya <b>tidak mengizinkan apa pun</b>, dan itu disengaja. Selama daftar origin
 * belum diisi, tidak ada header CORS yang dikirim dan browser menolak permintaan lintas-origin
 * — perilaku bawaan yang aman. Kebalikannya, memasang {@code *} sebagai default berarti
 * setiap situs mana pun bisa memanggil API ini dari browser pengunjungnya, dan karena
 * autentikasi belum dipasang (lihat PLAN.md §16), tidak ada lapisan lain yang menahannya.
 *
 * <p>Perlu diingat CORS <b>bukan</b> kontrol keamanan server. Ia hanya berlaku di browser;
 * curl, skrip, dan server lain sama sekali tidak terpengaruh. Isi daftar origin untuk
 * memudahkan aplikasi front-end Anda, bukan untuk mengamankan API.
 *
 * <p>Contoh: {@code CORS_ALLOWED_ORIGINS=http://localhost:3000,https://katalog.contoh.id}
 */
@Slf4j
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter(@Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        List<String> origins = allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();

        if (origins.isEmpty()) {
            log.info("CORS dimatikan (app.cors.allowed-origins kosong)");
            return new CorsFilter(source);
        }

        // allowedOriginPatterns, bukan allowedOrigins: yang pertama mendukung wildcard
        // seperti https://*.contoh.id dan tetap bekerja saat kredensial diizinkan.
        configuration.setAllowedOriginPatterns(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // Tanpa ini, JavaScript di browser tidak bisa membaca header buatan kita sendiri —
        // termasuk X-Cache dan X-Trace-Id, yang justru berguna untuk debugging dari sisi klien.
        configuration.setExposedHeaders(List.of("X-Cache", "X-Trace-Id", "X-Read-Consistency", "Location", "ETag"));
        configuration.setMaxAge(3600L);

        source.registerCorsConfiguration("/**", configuration);
        log.info("CORS diizinkan untuk origin: {}", origins);
        return new CorsFilter(source);
    }
}
