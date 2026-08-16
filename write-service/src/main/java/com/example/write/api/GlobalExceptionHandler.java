package com.example.write.api;

import com.example.common.error.ApiError;
import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.common.tracing.Tracing;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

/**
 * Menerjemahkan exception jadi body error yang seragam.
 *
 * <p>Prinsip yang dipegang: klien tidak pernah menerima stack trace atau pesan internal
 * database. Detail teknisnya masuk ke log bersama {@code traceId}, dan klien cukup
 * menyebutkan trace id itu saat melapor.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e, HttpServletRequest request) {
        ErrorCode code = e.errorCode();
        // 5xx layak dilihat lengkap; 4xx adalah kesalahan klien dan tidak perlu stack trace.
        if (code.httpStatus() >= 500) {
            log.error("{} pada {}: {}", code, request.getRequestURI(), e.getMessage(), e);
        } else {
            log.warn("{} pada {}: {}", code, request.getRequestURI(), e.getMessage());
        }
        return build(code, e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException e,
                                                         HttpServletRequest request) {
        List<ApiError.Violation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.Violation(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
                .toList();

        ApiError body = ApiError.validation("Request tidak valid", request.getRequestURI(), traceId(), violations);
        return ResponseEntity.status(body.status()).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException e, HttpServletRequest request) {
        List<ApiError.Violation> violations = e.getConstraintViolations().stream()
                .map(v -> new ApiError.Violation(v.getPropertyPath().toString(), v.getMessage(), v.getInvalidValue()))
                .toList();

        ApiError body = ApiError.validation("Request tidak valid", request.getRequestURI(), traceId(), violations);
        return ResponseEntity.status(body.status()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        // Pesan asli Jackson membocorkan nama kelas internal, jadi tidak diteruskan ke klien.
        log.warn("Body tidak bisa dibaca pada {}: {}", request.getRequestURI(), e.getMessage());
        return build(ErrorCode.INVALID_REQUEST, "Body request bukan JSON yang valid atau tipenya tidak sesuai", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                       HttpServletRequest request) {
        return build(ErrorCode.INVALID_REQUEST,
                "Nilai '%s' tidak valid untuk parameter '%s'".formatted(e.getValue(), e.getName()), request);
    }

    /**
     * Dilempar Hibernate saat {@code @Version} tidak cocok — ada yang mengubah baris yang sama
     * lebih dulu. Bukan error server: klien perlu mengambil data terbaru lalu mencoba lagi.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException e,
                                                         HttpServletRequest request) {
        log.warn("Konflik versi pada {}: {}", request.getRequestURI(), e.getMessage());
        return build(ErrorCode.VERSION_CONFLICT,
                "Data sudah diubah pihak lain. Ambil ulang datanya lalu coba lagi.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException e, HttpServletRequest request) {
        log.warn("Pelanggaran constraint pada {}: {}", request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return build(ErrorCode.SKU_ALREADY_EXISTS,
                "Data bentrok dengan baris yang sudah ada (kemungkinan SKU duplikat)", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return build(ErrorCode.IMAGE_TOO_LARGE, "Ukuran file melebihi batas yang diizinkan", request);
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
