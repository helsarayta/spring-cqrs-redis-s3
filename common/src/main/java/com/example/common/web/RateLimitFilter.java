package com.example.common.web;

import com.example.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pembatas laju sederhana per alamat IP, <b>dimatikan secara default</b>.
 *
 * <p><b>Batasan yang harus dipahami sebelum memakainya.</b> Hitungannya disimpan di memori
 * proses ini saja. Kalau service dijalankan dalam tiga instance, satu klien praktis mendapat
 * tiga kali jatah yang dikonfigurasi, dan angka yang Anda pasang tidak berarti apa-apa secara
 * keseluruhan. Ini juga tidak melindungi dari serangan terdistribusi, dan alamat IP di
 * belakang proxy bisa dipalsukan lewat header.
 *
 * <p>Jadi anggap ini pagar terhadap klien yang tidak sengaja mengulang request terlalu cepat —
 * bukan perlindungan terhadap penyalahgunaan. Pembatasan yang sungguhan tempatnya di API
 * gateway atau berbasis Redis, di mana hitungannya dibagi seluruh instance.
 *
 * <p>Dinyalakan dengan {@code app.rate-limit.enabled=true}.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true")
public class RateLimitFilter extends OncePerRequestFilter {

    private final int maxRequestsPerWindow;
    private final int windowSeconds;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.rate-limit.requests:100}") int maxRequestsPerWindow,
                           @Value("${app.rate-limit.window-seconds:60}") int windowSeconds) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowSeconds = windowSeconds;
        log.warn("Rate limit aktif: {} request per {} detik per IP. "
                        + "Ingat hitungan ini per-instance, bukan lintas instance.",
                maxRequestsPerWindow, windowSeconds);
    }

    private record Window(long startEpochSecond, AtomicInteger count) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Endpoint pemantauan tidak dibatasi: kalau ikut kena limit, sistem monitoring justru
        // melaporkan service mati tepat pada saat trafiknya sedang tinggi.
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String client = clientKey(request);
        long nowWindow = Instant.now().getEpochSecond() / windowSeconds;

        Window window = windows.compute(client, (key, existing) ->
                existing == null || existing.startEpochSecond() != nowWindow
                        ? new Window(nowWindow, new AtomicInteger(0))
                        : existing);

        int used = window.count().incrementAndGet();

        response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequestsPerWindow));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, maxRequestsPerWindow - used)));

        if (used > maxRequestsPerWindow) {
            long retryAfter = ((nowWindow + 1) * windowSeconds) - Instant.now().getEpochSecond();
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter)));
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"status":429,"code":"%s","message":"Terlalu banyak request. Coba lagi dalam %d detik."}"""
                    .formatted(ErrorCode.SERVICE_UNAVAILABLE.name(), Math.max(1, retryAfter)));
            return;
        }

        // Membersihkan jendela lama agar map tidak tumbuh selamanya oleh IP yang tidak kembali.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().startEpochSecond() < nowWindow);
        }

        filterChain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        // X-Forwarded-For dipercaya HANYA kalau ada proxy tepercaya di depan. Kalau service
        // ini terekspos langsung, header itu dikirim klien dan bisa diisi sesuka hati —
        // artinya batas laju bisa dilewati cukup dengan mengganti-ganti nilainya.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
