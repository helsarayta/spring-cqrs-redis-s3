package com.example.write.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Jejak satu request POST yang membawa header {@code Idempotency-Key}. */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @Column(name = "idem_key", length = 255)
    private String idemKey;

    @Column(nullable = false)
    private String endpoint;

    /** SHA-256 dari body request, untuk mendeteksi key yang dipakai ulang dengan isi berbeda. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static IdempotencyRecord start(String key, String endpoint, String requestHash, int ttlHours) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.idemKey = key;
        r.endpoint = endpoint;
        r.requestHash = requestHash;
        r.createdAt = Instant.now();
        r.expiresAt = r.createdAt.plus(ttlHours, ChronoUnit.HOURS);
        return r;
    }

    public void complete(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    /**
     * Request pertama yang memakai key ini belum selesai (atau gagal di tengah jalan),
     * sehingga belum ada response yang bisa diputar ulang.
     */
    public boolean isInFlight() {
        return this.responseStatus == null;
    }
}
