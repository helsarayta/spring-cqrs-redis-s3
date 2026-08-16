package com.example.write.domain;

import com.example.common.dto.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Agregat produk — source of truth.
 *
 * <p>Perubahan state dilakukan lewat method di kelas ini, bukan lewat setter dari luar.
 * Tujuannya supaya aturan seperti "stok tidak boleh negatif" hanya ada di satu tempat
 * dan tidak bisa dilewati oleh pemanggil yang lupa.
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

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

    /**
     * Optimistic lock Hibernate, sekaligus nomor urut agregat yang dibawa event.
     *
     * <p>Dipakai read-service untuk membuang event yang datang terlambat: kalau versi di event
     * lebih kecil atau sama dengan versi yang sudah tersimpan, event diabaikan.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------ factory

    public static Product create(String sku, String name, String description,
                                 BigDecimal price, String currency, int stock) {
        Product p = new Product();
        p.id = UUID.randomUUID();
        p.sku = sku;
        p.name = name;
        p.description = description;
        p.price = price;
        p.currency = currency == null ? "IDR" : currency.toUpperCase();
        p.stock = stock;
        p.status = ProductStatus.ACTIVE;
        return p;
    }

    // ------------------------------------------------------------------ perilaku

    public void update(String name, String description, BigDecimal price, String currency,
                       Integer stock, ProductStatus status) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (price != null) this.price = price;
        if (currency != null) this.currency = currency.toUpperCase();
        if (stock != null) setStock(stock);
        if (status != null) this.status = status;
    }

    /**
     * Menambah/mengurangi stok secara relatif.
     *
     * <p>Sengaja relatif (delta), bukan absolut: dua request "kurangi 1" yang berjalan
     * bersamaan menghasilkan -2, sedangkan dua request "set jadi 9" akan saling menimpa
     * dan kehilangan satu pengurangan.
     */
    public void adjustStock(int delta) {
        setStock(this.stock + delta);
    }

    private void setStock(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Stok tidak boleh negatif (diminta: %d)".formatted(value));
        }
        this.stock = value;
    }

    public void attachImage(String objectKey, String contentType, long sizeBytes) {
        this.imageObjectKey = objectKey;
        this.imageContentType = contentType;
        this.imageSizeBytes = sizeBytes;
    }

    public void removeImage() {
        this.imageObjectKey = null;
        this.imageContentType = null;
        this.imageSizeBytes = null;
    }

    /**
     * Soft delete. Baris tetap ada supaya event DELETED punya versi agregat yang bisa
     * diurutkan oleh consumer, dan supaya riwayat tidak hilang.
     */
    public void softDelete() {
        this.status = ProductStatus.DELETED;
    }

    public boolean isDeleted() {
        return this.status == ProductStatus.DELETED;
    }

    // ------------------------------------------------------------------ lifecycle

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
