package com.example.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Bentuk body error yang seragam di kedua service.
 *
 * <p>{@code traceId} disertakan supaya saat user melapor "request saya gagal", satu nilai itu
 * cukup untuk menelusuri log lintas write-service dan read-service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        List<Violation> violations
) {

    /** Detail per-field, hanya diisi untuk error validasi. */
    public record Violation(
            String field,
            String message,
            Object rejectedValue
    ) {
    }

    public static ApiError of(ErrorCode code, String message, String path, String traceId) {
        return new ApiError(Instant.now(), code.httpStatus(), code.name(), message, path, traceId, null);
    }

    public static ApiError validation(String message, String path, String traceId, List<Violation> violations) {
        return new ApiError(Instant.now(), ErrorCode.VALIDATION_FAILED.httpStatus(),
                ErrorCode.VALIDATION_FAILED.name(), message, path, traceId, violations);
    }
}
