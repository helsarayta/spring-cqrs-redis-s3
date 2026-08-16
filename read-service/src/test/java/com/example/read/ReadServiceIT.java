package com.example.read;

import com.example.common.event.Topics;
import com.example.read.repository.ProcessedEventRepository;
import com.example.read.repository.ProductReadModelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Menguji read-service melawan Postgres, Redis, dan Kafka yang sungguhan.
 *
 * <p>Fokusnya pada dua hal yang tidak bisa dibuktikan unit test:
 * <ul>
 *   <li>apakah urutan "Redis dulu, database kemudian" benar-benar terjadi terhadap Redis asli,</li>
 *   <li>apakah sistem tetap melayani pembacaan ketika Redis benar-benar tidak menjawab.</li>
 * </ul>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    /**
     * Redis memakai {@code GenericContainer} biasa agar bisa di-<i>pause</i> di tengah test.
     * Pause dipilih, bukan stop: menghentikan container akan mengubah port yang dipetakan saat
     * dinyalakan lagi, sedangkan aplikasi sudah mengunci alamatnya saat startup. Pause juga
     * lebih mendekati kegagalan nyata yang paling merepotkan — Redis yang menerima koneksi
     * tapi tidak pernah menjawab.
     */
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Timeout dipendekkan agar test skenario "Redis menggantung" tidak berlama-lama.
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("app.cache.timeout-ms", () -> "200");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProductReadModelRepository readModelRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    // ------------------------------------------------------------------ inspeksi Redis
    //
    // Redis diperiksa lewat redis-cli di dalam container, BUKAN lewat RedisTemplate milik
    // aplikasi. Alasannya: template itu sengaja dipasangi timeout 200 ms agar jalur baca
    // gagal cepat, dan perintah housekeeping seperti FLUSHALL bisa melewatinya saat mesin
    // sedang sibuk. Kalau dipakai di test, yang gagal adalah perkakas testnya sendiri dan
    // hasilnya terbaca seolah aplikasinya yang rusak.

    private String redisCli(String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "redis-cli";
            System.arraycopy(args, 0, command, 1, args.length);
            return REDIS.execInContainer(command).getStdout().trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Terinterupsi saat menjalankan redis-cli", e);
        }
    }

    private void flushRedis() {
        redisCli("FLUSHALL");
    }

    private boolean redisHasKey(String key) {
        return "1".equals(redisCli("EXISTS", key));
    }

    private long redisTtl(String key) {
        return Long.parseLong(redisCli("TTL", key));
    }

    // ------------------------------------------------------------------ helper

    private String envelope(UUID eventId, UUID productId, long version, String name, String sku) {
        return """
                {"eventId":"%s","eventType":"PRODUCT_UPDATED","aggregateType":"PRODUCT",
                 "aggregateId":"%s","aggregateVersion":%d,
                 "occurredAt":"2026-08-16T00:00:00Z","traceId":"it-trace",
                 "payload":{"id":"%s","sku":"%s","name":"%s","description":"d",
                   "price":1000.00,"currency":"IDR","stock":3,
                   "imageObjectKey":null,"imageContentType":null,"imageSizeBytes":null,
                   "status":"ACTIVE","version":%d,
                   "createdAt":"2026-08-16T00:00:00Z","updatedAt":"2026-08-16T00:00:00Z"}}
                """.formatted(eventId, productId, version, productId, sku, name, version);
    }

    private void publish(UUID productId, String json) {
        kafkaTemplate.send(Topics.PRODUCT_EVENTS, productId.toString(), json);
        kafkaTemplate.flush();
    }

    private UUID projectProduct(String name, String sku, long version) {
        UUID productId = UUID.randomUUID();
        publish(productId, envelope(UUID.randomUUID(), productId, version, name, sku));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(readModelRepository.findById(productId)).isPresent());
        return productId;
    }

    // ------------------------------------------------------------------ test

    @Test
    @Order(1)
    @DisplayName("event dari Kafka membangun read model")
    void eventBuildsReadModel() {
        UUID productId = projectProduct("Kopi Gayo", "IT-R-1", 1);

        var model = readModelRepository.findById(productId).orElseThrow();
        assertThat(model.getName()).isEqualTo("Kopi Gayo");
        assertThat(model.getAggregateVersion()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isPositive();
    }

    @Test
    @Order(2)
    @DisplayName("pembacaan pertama MISS lalu terisi ke Redis, pembacaan berikutnya HIT")
    void firstReadMissesThenHits() {
        UUID productId = projectProduct("Kopi Aceh", "IT-R-2", 1);
        flushRedis();

        ResponseEntity<String> first = rest.getForEntity("/api/v1/products/" + productId, String.class);
        ResponseEntity<String> second = rest.getForEntity("/api/v1/products/" + productId, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst("X-Cache")).isEqualTo("MISS");
        assertThat(second.getHeaders().getFirst("X-Cache")).isEqualTo("HIT");

        // Bukti langsung di Redis, bukan sekadar percaya pada header.
        assertThat(redisHasKey("product:v1:" + productId)).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("TTL diberi jitter sehingga key tidak kedaluwarsa serentak")
    void ttlHasJitter() {
        UUID productId = projectProduct("Kopi Toraja", "IT-R-3", 1);
        rest.getForEntity("/api/v1/products/" + productId, String.class);

        long ttl = redisTtl("product:v1:" + productId);

        // TTL dasar 600 detik, jitter 0..60.
        assertThat(ttl).isBetween(590L, 660L);
    }

    @Test
    @Order(4)
    @DisplayName("id yang tidak ada: 404 pertama dari database, 404 kedua dari negative cache")
    void negativeCachingAvoidsRepeatedDatabaseHits() {
        UUID ghost = UUID.randomUUID();

        ResponseEntity<String> first = rest.getForEntity("/api/v1/products/" + ghost, String.class);
        ResponseEntity<String> second = rest.getForEntity("/api/v1/products/" + ghost, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(first.getHeaders().getFirst("X-Cache")).isEqualTo("MISS");
        assertThat(second.getHeaders().getFirst("X-Cache")).isEqualTo("NEGATIVE_HIT");
    }

    @Test
    @Order(5)
    @DisplayName("event duplikat tidak mengubah read model")
    void duplicateEventIsIgnored() {
        UUID productId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(productId, envelope(eventId, productId, 1, "Nama Asli", "IT-R-5"));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(readModelRepository.findById(productId)).isPresent());

        // eventId yang sama, isi berbeda dan versi lebih tinggi. Deduplikasi harus menahannya
        // walaupun versinya terlihat lebih baru.
        publish(productId, envelope(eventId, productId, 9, "SEHARUSNYA DITOLAK", "IT-R-5"));

        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(readModelRepository.findById(productId).orElseThrow().getName())
                        .isEqualTo("Nama Asli"));
    }

    @Test
    @Order(6)
    @DisplayName("event yang lebih tua dari data tersimpan diabaikan")
    void staleEventIsRejected() {
        UUID productId = UUID.randomUUID();

        publish(productId, envelope(UUID.randomUUID(), productId, 5, "Versi Baru", "IT-R-6"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(readModelRepository.findById(productId)).isPresent());

        // eventId baru (lolos deduplikasi) tapi versi lebih tua. Yang menahannya adalah
        // penjaga versi — tanpa itu, data lama menimpa data baru tanpa error apa pun.
        publish(productId, envelope(UUID.randomUUID(), productId, 2, "VERSI LAMA", "IT-R-6"));

        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(readModelRepository.findById(productId).orElseThrow().getName())
                        .isEqualTo("Versi Baru"));
    }

    @Test
    @Order(7)
    @DisplayName("perubahan pada read model membuang salinan cache produk itu")
    void projectionInvalidatesCache() {
        UUID productId = projectProduct("Sebelum", "IT-R-7", 1);
        rest.getForEntity("/api/v1/products/" + productId, String.class);
        assertThat(redisHasKey("product:v1:" + productId)).isTrue();

        publish(productId, envelope(UUID.randomUUID(), productId, 2, "Sesudah", "IT-R-7"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ResponseEntity<String> response = rest.getForEntity("/api/v1/products/" + productId, String.class);
            assertThat(response.getBody()).contains("Sesudah");
        });
    }

    /**
     * Skenario paling penting di kelas ini, dan sengaja dijalankan terakhir karena
     * mengganggu Redis untuk seluruh konteks test.
     */
    @Test
    @Order(99)
    @DisplayName("Redis tidak menjawab: pembacaan tetap dilayani database, bukan gagal")
    void readsSurviveRedisOutage() {
        UUID productId = projectProduct("Tahan Banting", "IT-R-99", 1);
        rest.getForEntity("/api/v1/products/" + productId, String.class);

        pauseRedis();
        try {
            for (int i = 0; i < 3; i++) {
                ResponseEntity<String> response =
                        rest.getForEntity("/api/v1/products/" + productId, String.class);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getHeaders().getFirst("X-Cache")).isEqualTo("BYPASS");
                assertThat(response.getBody()).contains("Tahan Banting");
            }

            // Endpoint daftar juga tidak boleh ikut jatuh.
            ResponseEntity<String> list = rest.getForEntity("/api/v1/products", String.class);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Health check tetap UP: instance ini masih melayani seluruh permintaan dengan
            // benar, jadi menariknya dari load balancer justru memperparah keadaan.
            ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
            assertThat(health.getBody()).contains("\"status\":\"UP\"");
        } finally {
            unpauseRedis();
        }

        // Setelah Redis pulih, cache dipakai lagi tanpa perlu restart aplikasi.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            rest.getForEntity("/api/v1/products/" + productId, String.class);
            ResponseEntity<String> again = rest.getForEntity("/api/v1/products/" + productId, String.class);
            assertThat(again.getHeaders().getFirst("X-Cache")).isEqualTo("HIT");
        });
    }

    private void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private void unpauseRedis() {
        try {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        } catch (RuntimeException e) {
            // Sudah tidak dalam keadaan pause — tidak masalah.
        }
    }
}
