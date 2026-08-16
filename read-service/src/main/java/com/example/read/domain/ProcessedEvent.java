package com.example.read.domain;

import com.example.common.event.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Penanda bahwa satu event sudah pernah diproses. Lihat penjelasan di migrasi V2. */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_version", nullable = false)
    private Long aggregateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private EventType eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public static ProcessedEvent of(UUID eventId, UUID aggregateId, long aggregateVersion, EventType eventType) {
        ProcessedEvent e = new ProcessedEvent();
        e.eventId = eventId;
        e.aggregateId = aggregateId;
        e.aggregateVersion = aggregateVersion;
        e.eventType = eventType;
        e.processedAt = Instant.now();
        return e;
    }
}
