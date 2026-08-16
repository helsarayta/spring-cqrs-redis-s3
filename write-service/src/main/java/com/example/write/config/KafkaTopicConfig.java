package com.example.write.config;

import com.example.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Deklarasi topic.
 *
 * <p>Di docker-compose topic sudah dibuat oleh {@code kafka-init}; bean ini membuat definisi
 * yang sama tersedia juga saat aplikasi dijalankan di lingkungan lain — terutama Testcontainers,
 * yang tidak menjalankan script init itu.
 *
 * <p>3 partisi, bukan 1: dengan 1 partisi hanya satu consumer yang bisa bekerja, dan
 * keterbatasan itu baru terasa saat beban naik. Urutan tetap aman karena message key adalah
 * id produk, jadi semua event satu produk selalu jatuh ke partisi yang sama.
 *
 * <p>Catatan: KafkaAdmin hanya <i>menambah</i> partisi, tidak pernah mengurangi, dan tidak
 * mengubah konfigurasi topic yang sudah ada.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic productEventsTopic() {
        return TopicBuilder.name(Topics.PRODUCT_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic productEventsDltTopic() {
        return TopicBuilder.name(Topics.PRODUCT_EVENTS_DLT)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
