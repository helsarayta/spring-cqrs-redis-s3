package com.example.read;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Query side.
 *
 * <p>Service ini hanya melayani pembacaan. Ia tidak punya endpoint tulis, dan satu-satunya
 * hal yang menulis ke {@code readdb} adalah projector yang mengonsumsi event Kafka.
 *
 * <p>Setiap pembacaan produk melewati Redis lebih dulu; database baru disentuh kalau cache
 * tidak punya jawabannya.
 */
@SpringBootApplication(scanBasePackages = "com.example")
@ConfigurationPropertiesScan
@EnableScheduling
public class ReadServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadServiceApplication.class, args);
    }
}
