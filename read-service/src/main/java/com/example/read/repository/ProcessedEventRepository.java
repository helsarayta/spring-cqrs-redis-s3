package com.example.read.repository;

import com.example.read.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Membuang catatan lama.
     *
     * <p>Retensinya harus lebih panjang dari retensi topic Kafka. Kalau lebih pendek, event
     * yang masih ada di topic dan kebetulan di-replay akan terlihat "belum pernah diproses"
     * dan diterapkan ulang — walaupun penjaga {@code aggregateVersion} tetap menahan
     * dampaknya, dedup-nya sendiri jadi tidak berguna.
     */
    @Modifying
    @Query("DELETE FROM ProcessedEvent e WHERE e.processedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
