package com.example.read.config;

import com.example.common.event.Topics;
import com.example.read.consumer.ProductEventListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Penanganan kegagalan pada sisi consumer.
 *
 * <p>Ini bagian yang paling mudah disepelekan. Tanpa penanganan khusus, satu pesan yang
 * selalu gagal akan diulang selamanya dan <b>menghentikan seluruh partisi</b> — semua event
 * di belakangnya ikut tertahan, dan read model berhenti diperbarui untuk semua produk yang
 * kebetulan berada di partisi itu. Satu data rusak melumpuhkan sebagian besar sistem.
 *
 * <p>Karena itu: kegagalan sementara diulang dengan jeda yang membesar, sedangkan kegagalan
 * yang tidak mungkin membaik (mis. JSON rusak) langsung dipindahkan ke dead letter topic
 * supaya antrean bisa jalan terus.
 *
 * <p>Tidak ada container factory buatan sendiri di sini: Spring Boot otomatis memasang bean
 * {@link DefaultErrorHandler} yang ada di context ke container factory bawaannya. Menulis
 * factory sendiri hanya untuk itu justru menghilangkan seluruh setelan dari
 * {@code application.yml} yang sudah diterapkan Boot.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    private static final long INITIAL_INTERVAL_MS = 1_000L;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_INTERVAL_MS = 10_000L;

    /**
     * Batas total waktu mencoba satu pesan sebelum dilempar ke DLT.
     *
     * <p>Dibatasi karena gangguan sesaat (database sedang restart) biasanya pulih dalam
     * hitungan puluhan detik. Kalau lebih lama dari itu, menahan seluruh partisi lebih
     * merugikan daripada memindahkan satu pesan untuk diperiksa manusia.
     */
    private static final long MAX_ELAPSED_MS = 60_000L;

    @Bean
    public DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, String> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate,
                // Nomor partisi dipertahankan sama dengan topic asal supaya pesan untuk satu
                // produk tetap berkelompok dan mudah ditelusuri saat diperiksa.
                (record, exception) -> new TopicPartition(Topics.PRODUCT_EVENTS_DLT, record.partition()));
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MS, MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        backOff.setMaxElapsedTime(MAX_ELAPSED_MS);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Mengulang pesan yang isinya memang tidak bisa dibaca hanya membuang waktu dan
        // menahan partisi — hasilnya akan sama saja setiap kali.
        handler.addNotRetryableExceptions(ProductEventListener.UnparseableEventException.class);

        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Gagal memproses {}-{} offset {} (percobaan ke-{}): {}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt, exception.toString()));

        return handler;
    }

    /**
     * Mencatat pesan yang berakhir di dead letter topic.
     *
     * <p>Dead letter topic yang tidak pernah dilihat sama saja dengan membuang pesan diam-diam.
     * Listener ini memastikan kejadiannya muncul di log lengkap dengan penyebabnya, beserta
     * peringatan bahwa read model untuk produk tersebut sekarang tertinggal.
     */
    @KafkaListener(topics = Topics.PRODUCT_EVENTS_DLT, groupId = Topics.READ_MODEL_GROUP + ".dlt")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        log.error("PESAN MASUK DEAD LETTER. produk={} asal={}-{} offset={} penyebab={}. "
                        + "Read model untuk produk ini TERTINGGAL sampai ditangani manual. Isi: {}",
                record.key(),
                textHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                numericHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                numericHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                textHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                abbreviate(record.value()));
    }

    private String textHeader(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? "-" : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Membaca header numerik yang ditulis {@link DeadLetterPublishingRecoverer}.
     *
     * <p>Partisi dan offset asal disimpan sebagai bilangan biner (4 dan 8 byte), bukan teks.
     * Membacanya sebagai string UTF-8 menghasilkan karakter kontrol yang tidak terbaca — dan
     * di log tampak seperti nilainya kosong, padahal sebenarnya ada.
     */
    private String numericHeader(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        if (header == null) {
            return "-";
        }
        ByteBuffer buffer = ByteBuffer.wrap(header.value());
        return switch (header.value().length) {
            case Integer.BYTES -> String.valueOf(buffer.getInt());
            case Long.BYTES -> String.valueOf(buffer.getLong());
            default -> new String(header.value(), StandardCharsets.UTF_8);
        };
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "-";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
