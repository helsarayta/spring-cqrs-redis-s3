package com.example.write.repository;

import com.example.write.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Mengambil sekumpulan event yang siap dikirim, sekaligus menguncinya.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} adalah inti dari query ini: kalau nanti write-service
     * dijalankan lebih dari satu instance, tiap instance akan melewati baris yang sudah
     * dipegang instance lain alih-alih menunggu. Tanpa {@code SKIP LOCKED}, instance kedua
     * akan terblokir menunggu instance pertama dan polling jadi serial.
     *
     * <p>{@code ORDER BY id} menjaga event terkirim sesuai urutan penulisannya.
     *
     * <p>Wajib dipanggil di dalam transaksi — kunci baris hanya bertahan selama transaksi.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
              AND next_attempt_at <= now()
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("limit") int limit);

    long countByStatus(com.example.write.domain.OutboxStatus status);

    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.status = com.example.write.domain.OutboxStatus.PUBLISHED AND o.publishedAt < :before")
    int deletePublishedOlderThan(@Param("before") Instant before);
}
