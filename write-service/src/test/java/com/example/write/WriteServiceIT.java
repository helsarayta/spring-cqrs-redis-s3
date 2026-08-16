package com.example.write;

import com.example.write.domain.OutboxStatus;
import com.example.write.repository.OutboxEventRepository;
import com.example.write.repository.ProductRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Menguji write-service melawan Postgres, Kafka, dan MinIO yang sungguhan.
 *
 * <p>Yang tidak bisa dibuktikan oleh unit test dan karena itu diuji di sini: apakah migrasi
 * Flyway cocok dengan pemetaan entity, apakah kolom JSONB benar-benar bisa ditulis, apakah
 * event benar-benar diterima broker, dan apakah objek benar-benar mendarat di object storage.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WriteServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    @Container
    static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-09-13T20-26-02Z"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.s3.endpoint", MINIO::getS3URL);
        registry.add("app.s3.access-key", MINIO::getUserName);
        registry.add("app.s3.secret-key", MINIO::getPassword);
        // Bucket belum ada di container baru; biarkan aplikasi membuatnya sendiri.
        registry.add("app.s3.auto-create-bucket", () -> "true");
        registry.add("app.outbox.poll-interval-ms", () -> "200");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private S3Client s3Client;

    private static int skuCounter = 0;

    @BeforeEach
    void freshSku() {
        skuCounter++;
    }

    private String createBody(String sku) {
        return """
                {"sku":"%s","name":"Kopi Gayo","description":"enak","price":85000.00,"currency":"IDR","stock":10}
                """.formatted(sku);
    }

    private HttpEntity<String> json(String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(body, headers);
    }

    @Test
    @DisplayName("membuat produk menyimpan ke database dan menerbitkan event ke Kafka")
    void createPersistsAndPublishes() {
        String sku = "IT-CREATE-" + skuCounter;

        ResponseEntity<String> response =
                rest.postForEntity("/api/v1/products", json(createBody(sku), null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Read-Consistency")).isEqualTo("eventual");
        assertThat(productRepository.findBySku(sku)).isPresent();

        UUID productId = productRepository.findBySku(sku).orElseThrow().getId();

        // Status PUBLISHED hanya ditulis setelah broker mengonfirmasi penerimaan (acks=all),
        // jadi ini bukti event benar-benar sampai, bukan sekadar dicoba kirim.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isZero());

        List<ConsumerRecord<String, String>> records = drainTopic();
        assertThat(records).isNotEmpty();

        ConsumerRecord<String, String> last = records.get(records.size() - 1);
        // Key wajib berupa id produk: inilah yang menjamin semua event satu produk mendarat
        // di partisi yang sama sehingga urutannya terjaga.
        assertThat(last.key()).isEqualTo(productId.toString());
        assertThat(last.value()).contains("PRODUCT_CREATED").contains(sku);
    }

    @Test
    @DisplayName("Idempotency-Key yang sama tidak menghasilkan produk kedua")
    void idempotencyKeyPreventsDuplicate() {
        String sku = "IT-IDEM-" + skuCounter;
        String key = "kunci-" + UUID.randomUUID();

        ResponseEntity<String> first = rest.postForEntity("/api/v1/products", json(createBody(sku), key), String.class);
        ResponseEntity<String> second = rest.postForEntity("/api/v1/products", json(createBody(sku), key), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        // Yang terpenting: hanya ada satu produk, dan response kedua identik dengan yang pertama.
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(productRepository.findAll().stream().filter(p -> p.getSku().equals(sku))).hasSize(1);
    }

    @Test
    @DisplayName("SKU duplikat tanpa idempotency key ditolak 409")
    void duplicateSkuRejected() {
        String sku = "IT-DUP-" + skuCounter;
        rest.postForEntity("/api/v1/products", json(createBody(sku), null), String.class);

        ResponseEntity<String> second =
                rest.postForEntity("/api/v1/products", json(createBody(sku), null), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("SKU_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("unggah gambar menaruh objek di storage dan menyimpan key-nya di database")
    void imageUploadStoresObject() {
        String sku = "IT-IMG-" + skuCounter;
        rest.postForEntity("/api/v1/products", json(createBody(sku), null), String.class);
        UUID productId = productRepository.findBySku(sku).orElseThrow().getId();

        ResponseEntity<String> response = uploadImage(productId, "foto.png", pngBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"contentType\":\"image/png\"");

        String objectKey = productRepository.findById(productId).orElseThrow().getImageObjectKey();
        assertThat(objectKey).isNotNull().startsWith("products/" + productId);

        // Baris database menunjuk ke sebuah objek — pastikan objeknya memang ada.
        // Inilah keadaan yang paling merugikan kalau sampai tidak sinkron.
        var listing = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket("product-images").prefix("products/" + productId).build());
        assertThat(listing.contents()).extracting(o -> o.key()).contains(objectKey);
    }

    @Test
    @DisplayName("berkas non-gambar yang menyamar sebagai PNG ditolak 415 dan tidak menyentuh storage")
    void disguisedFileRejected() {
        String sku = "IT-BAD-" + skuCounter;
        rest.postForEntity("/api/v1/products", json(createBody(sku), null), String.class);
        UUID productId = productRepository.findBySku(sku).orElseThrow().getId();

        ResponseEntity<String> response = uploadImage(productId, "jahat.png",
                "%PDF-1.4 ini bukan gambar".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(productRepository.findById(productId).orElseThrow().getImageObjectKey()).isNull();

        var listing = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket("product-images").prefix("products/" + productId).build());
        assertThat(listing.contents()).isEmpty();
    }

    @Test
    @DisplayName("mengganti gambar menghapus objek yang lama")
    void replacingImageDeletesPreviousObject() {
        String sku = "IT-REPL-" + skuCounter;
        rest.postForEntity("/api/v1/products", json(createBody(sku), null), String.class);
        UUID productId = productRepository.findBySku(sku).orElseThrow().getId();

        uploadImage(productId, "satu.png", pngBytes());
        String firstKey = productRepository.findById(productId).orElseThrow().getImageObjectKey();

        uploadImage(productId, "dua.png", pngBytes());
        String secondKey = productRepository.findById(productId).orElseThrow().getImageObjectKey();

        assertThat(secondKey).isNotEqualTo(firstKey);

        var listing = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket("product-images").prefix("products/" + productId).build());
        assertThat(listing.contents()).extracting(o -> o.key())
                .containsExactly(secondKey);
    }

    @Test
    @DisplayName("validasi request menolak harga negatif dengan rincian per-field")
    void validationRejectsNegativePrice() {
        String body = """
                {"sku":"IT-VAL-%d","name":"Uji","price":-1,"stock":1}
                """.formatted(skuCounter);

        ResponseEntity<String> response = rest.postForEntity("/api/v1/products", json(body, null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED").contains("price");
    }

    @Test
    @DisplayName("menghapus produk dua kali tetap 204 dan tidak menambah event")
    void deleteIsIdempotent() {
        String sku = "IT-DEL-" + skuCounter;
        rest.postForEntity("/api/v1/products", json(createBody(sku), null), String.class);
        UUID productId = productRepository.findBySku(sku).orElseThrow().getId();

        rest.delete("/api/v1/products/" + productId);
        long afterFirst = outboxRepository.count();

        rest.delete("/api/v1/products/" + productId);
        long afterSecond = outboxRepository.count();

        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    // ------------------------------------------------------------------ helper

    private ResponseEntity<String> uploadImage(UUID productId, String filename, byte[] content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        return rest.exchange("/api/v1/products/" + productId + "/image", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
    }

    /** PNG 1x1 yang sah — cukup untuk melewati pemeriksaan magic bytes. */
    private static byte[] pngBytes() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    private List<ConsumerRecord<String, String>> drainTopic() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-verifier-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of("product.events.v1"));
            java.util.List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
            // Beberapa kali poll: poll pertama biasanya habis untuk penugasan partisi.
            for (int i = 0; i < 5; i++) {
                ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(2));
                batch.forEach(collected::add);
            }
            return collected;
        }
    }
}
