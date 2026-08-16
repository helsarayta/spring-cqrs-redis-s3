package com.example.write.domain;

import com.example.common.event.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Satu baris = satu event yang menunggu dikirim ke Kafka.
 *
 * <p>Baris ini ditulis dalam transaksi yang <b>sama</b> dengan perubahan {@link Product}.
 * Jadi kalau perubahan produk di-rollback, event-nya ikut hilang; kalau perubahan produk
 * berhasil commit, event dijamin ada dan cepat atau lambat akan terkirim.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_version", nullable = false)
    private Long aggregateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private EventType eventType;

    /** Amplop event lengkap dalam bentuk JSON — inilah yang dikirim apa adanya ke Kafka. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String headers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public static OutboxEvent pending(UUID eventId, String aggregateType, UUID aggregateId,
                                      long aggregateVersion, EventType eventType,
                                      String payloadJson, String headersJson) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = eventId;
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.aggregateVersion = aggregateVersion;
        e.eventType = eventType;
        e.payload = payloadJson;
        e.headers = headersJson;
        e.status = OutboxStatus.PENDING;
        e.attempts = 0;
        e.nextAttemptAt = Instant.now();
        return e;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Menandai percobaan gagal dan menjadwalkan percobaan berikutnya.
     *
     * @return true kalau baris ini menyerah (status jadi FAILED)
     */
    public boolean markAttemptFailed(String error, int maxAttempts, Instant nextAttemptAt) {
        this.attempts = this.attempts + 1;
        // Pesan error dipotong: stack trace panjang tidak menambah informasi di kolom ini
        // dan membuat tabel membengkak.
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
            return true;
        }
        this.nextAttemptAt = nextAttemptAt;
        return false;
    }

    @PrePersist
    void onInsert() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
