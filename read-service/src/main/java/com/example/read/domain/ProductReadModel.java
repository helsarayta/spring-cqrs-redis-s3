package com.example.read.domain;

import com.example.common.dto.ProductPayload;
import com.example.common.dto.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bentuk produk yang dioptimalkan untuk dibaca.
 *
 * <p>Perhatikan tidak ada {@code @Version} di sini. Optimistic locking tidak relevan:
 * satu-satunya penulis tabel ini adalah projector, dan urutan event untuk satu produk
 * sudah dijamin oleh partisi Kafka. Yang dipakai sebagai penjaga adalah
 * {@link #aggregateVersion}, yaitu versi dari sisi tulis — bukan versi baris ini sendiri.
 */
@Entity
@Table(name = "product_read_model")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductReadModel {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar(3)")
    private String currency;

    @Column(nullable = false)
    private Integer stock;

    @Column(name = "image_object_key", length = 512)
    private String imageObjectKey;

    @Column(name = "image_content_type", length = 100)
    private String imageContentType;

    @Column(name = "image_size_bytes")
    private Long imageSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "aggregate_version", nullable = false)
    private Long aggregateVersion;

    @Column(name = "last_event_id")
    private UUID lastEventId;

    @Column(name = "source_created_at", nullable = false)
    private Instant sourceCreatedAt;

    @Column(name = "source_updated_at", nullable = false)
    private Instant sourceUpdatedAt;

    @Column(name = "projected_at", nullable = false)
    private Instant projectedAt;

    /**
     * Menyalin seluruh isi payload event ke baris ini.
     *
     * <p>Payload event selalu berupa snapshot penuh, bukan perubahan sebagian. Karena itu
     * penerapannya cukup menimpa semua kolom — tidak perlu tahu event sebelumnya, dan
     * memproses event yang sama dua kali menghasilkan baris yang identik.
     */
    public void applySnapshot(ProductPayload payload, UUID eventId) {
        this.id = payload.id();
        this.sku = payload.sku();
        this.name = payload.name();
        this.description = payload.description();
        this.price = payload.price();
        this.currency = payload.currency();
        this.stock = payload.stock();
        this.imageObjectKey = payload.imageObjectKey();
        this.imageContentType = payload.imageContentType();
        this.imageSizeBytes = payload.imageSizeBytes();
        this.status = payload.status();
        this.aggregateVersion = payload.version();
        this.lastEventId = eventId;
        this.sourceCreatedAt = payload.createdAt();
        this.sourceUpdatedAt = payload.updatedAt();
        this.projectedAt = Instant.now();
    }

    public static ProductReadModel from(ProductPayload payload, UUID eventId) {
        ProductReadModel model = new ProductReadModel();
        model.applySnapshot(payload, eventId);
        return model;
    }

    /**
     * @return true kalau event dengan versi ini lebih tua atau sama dengan yang sudah
     *         diterapkan, sehingga tidak boleh menimpa data yang ada.
     */
    public boolean isStale(long incomingVersion) {
        return this.aggregateVersion != null && incomingVersion <= this.aggregateVersion;
    }
}
