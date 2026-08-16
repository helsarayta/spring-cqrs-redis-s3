package com.example.common.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Memberi setiap request satu {@code traceId} dan menaruhnya di MDC.
 *
 * <p>Nilai ini kemudian ikut ke event Kafka (lewat outbox) dan dipulihkan lagi di consumer,
 * sehingga satu operasi tulis bisa ditelusuri dari request HTTP di write-service sampai
 * pembaruan read model di read-service — dengan satu nilai pencarian di log.
 *
 * <p>Jalan paling awal ({@code HIGHEST_PRECEDENCE + 1}) supaya log dari filter lain pun
 * sudah membawa trace id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Kalau klien (atau gateway) sudah mengirim trace id, pakai punya mereka supaya
        // penelusuran nyambung lintas sistem, bukan terputus di sini.
        String traceId = request.getHeader(Tracing.HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        MDC.put(Tracing.MDC_KEY, traceId);
        response.setHeader(Tracing.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Wajib dibersihkan: thread request dipakai ulang dari pool, dan trace id yang
            // tertinggal akan menempel pada request berikutnya yang tidak ada hubungannya.
            MDC.remove(Tracing.MDC_KEY);
        }
    }
}
