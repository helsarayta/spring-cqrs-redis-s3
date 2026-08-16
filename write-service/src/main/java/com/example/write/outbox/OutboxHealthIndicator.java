package com.example.write.outbox;

import com.example.write.domain.OutboxStatus;
import com.example.write.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Melaporkan kondisi outbox di {@code /actuator/health}.
 *
 * <p><b>Indikator ini tidak pernah melaporkan DOWN karena ada event tertunda</b>, dan itu
 * keputusan yang disengaja. Kalau Kafka mati, outbox akan menumpuk — tapi endpoint tulis
 * tetap bekerja sempurna dan datanya tetap tersimpan aman. Menyatakan service ini tidak sehat
 * akan membuat load balancer menariknya, sehingga penulisan yang tadinya masih berhasil jadi
 * ikut gagal. Itu mengubah gangguan pada Kafka menjadi gangguan pada seluruh layanan.
 *
 * <p>Yang benar-benar berarti DOWN di sini hanya satu: database tidak bisa dihubungi sama
 * sekali. Kalau itu terjadi, service ini memang tidak bisa melakukan apa pun.
 *
 * <p>Untuk memasang alert, pakai metrik {@code outbox.events.pending} dan
 * {@code outbox.events.dead} — bukan status health ini.
 */
@Component("outbox")
@RequiredArgsConstructor
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxEventRepository outboxRepository;

    @Override
    public Health health() {
        try {
            long pending = outboxRepository.countByStatus(OutboxStatus.PENDING);
            long failed = outboxRepository.countByStatus(OutboxStatus.FAILED);

            Health.Builder builder = Health.up()
                    .withDetail("pending", pending)
                    .withDetail("failed", failed);

            if (failed > 0) {
                builder.withDetail("catatan",
                        "Ada event yang menyerah setelah melewati batas percobaan. Read model untuk "
                                + "produk terkait tertinggal dan butuh penanganan manual.");
            } else if (pending > 0) {
                builder.withDetail("catatan",
                        "Ada event menunggu dikirim. Wajar kalau jumlahnya kecil dan terus berkurang; "
                                + "kalau terus bertambah, kemungkinan Kafka sedang bermasalah.");
            }

            return builder.build();
        } catch (RuntimeException e) {
            // Satu-satunya kondisi yang layak disebut tidak sehat.
            return Health.down()
                    .withDetail("penyebab", "Tabel outbox tidak bisa dibaca")
                    .withException(e)
                    .build();
        }
    }
}
