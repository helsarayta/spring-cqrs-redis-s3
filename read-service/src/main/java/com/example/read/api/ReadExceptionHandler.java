package com.example.read.api;

import com.example.common.error.ApiError;
import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.common.tracing.Tracing;
import com.example.read.service.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Penerjemah exception jadi body error seragam.
 *
 * <p>Dibuat terpisah dari milik write-service walaupun sebagian mirip. Kedua service punya
 * mode kegagalan yang berbeda — di sini tidak ada konflik versi maupun pelanggaran constraint,
 * dan sebaliknya di sana tidak ada urusan cache. Menyatukannya akan memaksa module bersama
 * menarik dependensi transaksi dan validasi yang tidak dibutuhkannya.
 */
@Slf4j
@RestControllerAdvice
public class ReadExceptionHandler {

    /**
     * Response 404 tetap membawa {@code X-Cache}.
     *
     * <p>Justru di sinilah header itu paling berguna: tanpa penanda ini, 404 yang dijawab
     * Redis (negative cache bekerja) dan 404 yang tetap menembak database (negative cache
     * tidak bekerja) terlihat persis sama dari luar.
     */
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ProductNotFoundException e, HttpServletRequest request) {
        log.debug("Tidak ditemukan pada {} ({}): {}", request.getRequestURI(), e.cacheStatus(), e.getMessage());
        ApiError body = ApiError.of(e.errorCode(), e.getMessage(), request.getRequestURI(), traceId());
        return ResponseEntity.status(e.errorCode().httpStatus())
                .header("X-Cache", e.cacheStatus().name())
                .body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e, HttpServletRequest request) {
        ErrorCode code = e.errorCode();
        if (code.httpStatus() >= 500) {
            log.error("{} pada {}: {}", code, request.getRequestURI(), e.getMessage(), e);
        } else {
            log.debug("{} pada {}: {}", code, request.getRequestURI(), e.getMessage());
        }
        return build(code, e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                       HttpServletRequest request) {
        return build(ErrorCode.INVALID_REQUEST,
                "Nilai '%s' tidak valid untuk parameter '%s'".formatted(e.getValue(), e.getName()), request);
    }

    /**
     * Kegagalan database.
     *
     * <p>Perhatikan tidak ada handler khusus untuk kegagalan Redis di sini — dan itu memang
     * disengaja. Masalah Redis tidak pernah sampai ke lapisan ini: {@code ProductCache}
     * menelannya dan pembacaan dialihkan ke database.
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiError> handleDatabase(org.springframework.dao.DataAccessException e,
                                                   HttpServletRequest request) {
        log.error("Database tidak bisa diakses pada {}", request.getRequestURI(), e);
        return build(ErrorCode.SERVICE_UNAVAILABLE,
                "Sumber data sedang tidak tersedia. Coba lagi beberapa saat lagi.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Error tak terduga pada {}", request.getRequestURI(), e);
        return build(ErrorCode.INTERNAL_ERROR,
                "Terjadi kesalahan internal. Sertakan traceId berikut saat melapor.", request);
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest request) {
        ApiError body = ApiError.of(code, message, request.getRequestURI(), traceId());
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    private String traceId() {
        return MDC.get(Tracing.MDC_KEY);
    }
}
