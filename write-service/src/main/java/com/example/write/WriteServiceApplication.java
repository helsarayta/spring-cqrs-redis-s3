package com.example.write;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Command side.
 *
 * <p>Service ini adalah satu-satunya yang boleh menulis ke {@code writedb}, dan satu-satunya
 * yang menerbitkan event ke Kafka. Ia sengaja <b>tidak</b> punya endpoint GET untuk mengambil
 * data — semua pembacaan adalah tugas read-service.
 *
 * <p>{@code scanBasePackages = "com.example"} agar konfigurasi bersama di module
 * {@code common} (mis. setelan Jackson) ikut terbaca.
 *
 * <p>{@code @EnableScheduling} dibutuhkan oleh publisher outbox yang berjalan periodik.
 */
@SpringBootApplication(scanBasePackages = "com.example")
@ConfigurationPropertiesScan
@EnableScheduling
public class WriteServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WriteServiceApplication.class, args);
    }
}
