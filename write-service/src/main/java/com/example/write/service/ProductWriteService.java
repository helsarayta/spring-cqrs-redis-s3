package com.example.write.service;

import com.example.common.dto.ProductPayload;
import com.example.common.dto.ProductStatus;
import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.common.event.EventType;
import com.example.write.api.dto.AdjustStockRequest;
import com.example.write.api.dto.CreateProductRequest;
import com.example.write.api.dto.UpdateProductRequest;
import com.example.write.domain.Product;
import com.example.write.outbox.OutboxRecorder;
import com.example.write.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Semua operasi tulis produk.
 *
 * <p>Pola yang dipakai seragam di setiap method:
 * <ol>
 *   <li>ubah state agregat lewat method domain,</li>
 *   <li>{@code saveAndFlush} — supaya Hibernate menaikkan {@code version} sekarang juga,</li>
 *   <li>catat event ke outbox memakai versi yang sudah baru itu.</li>
 * </ol>
 * Ketiganya berada dalam satu {@code @Transactional}. Urutannya penting: mencatat event
 * sebelum flush akan menghasilkan event dengan versi lama, dan read-service akan membuangnya
 * sebagai event basi — bug yang sunyi dan sangat sulit dilacak.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductWriteService {

    private final ProductRepository productRepository;
    private final OutboxRecorder outboxRecorder;

    @Transactional
    public ProductPayload create(CreateProductRequest req) {
        // Pengecekan awal ini demi pesan error yang enak dibaca. Ia TIDAK menjadi jaminan
        // keunikan — dua request bersamaan bisa sama-sama lolos di sini. Jaminan sebenarnya
        // ada pada unique index di database, yang ditangani di catch di bawah.
        if (productRepository.existsBySku(req.sku())) {
            throw ApiException.skuExists(req.sku());
        }

        Product product = Product.create(
                req.sku(), req.name(), req.description(),
                req.price(), req.currency(), req.stock());

        try {
            productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            if (isSkuConflict(e)) {
                throw ApiException.skuExists(req.sku());
            }
            throw e;
        }

        outboxRecorder.record(EventType.PRODUCT_CREATED, product);
        log.info("Produk dibuat: id={} sku={}", product.getId(), product.getSku());
        return ProductMapper.toPayload(product);
    }

    @Transactional
    public ProductPayload update(UUID id, UpdateProductRequest req, Long expectedVersion) {
        Product product = loadActive(id);
        assertVersionMatches(product, expectedVersion);

        if (req.status() == ProductStatus.DELETED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Gunakan DELETE /api/v1/products/{id} untuk menghapus produk");
        }

        product.update(req.name(), req.description(), req.price(),
                req.currency(), req.stock(), req.status());

        productRepository.saveAndFlush(product);
        outboxRecorder.record(EventType.PRODUCT_UPDATED, product);
        log.info("Produk diubah: id={} versi={}", id, product.getVersion());
        return ProductMapper.toPayload(product);
    }

    @Transactional
    public ProductPayload adjustStock(UUID id, AdjustStockRequest req) {
        Product product = loadActive(id);

        try {
            product.adjustStock(req.delta());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Stok tidak mencukupi: stok sekarang %d, diminta %d".formatted(product.getStock(), req.delta()));
        }

        productRepository.saveAndFlush(product);
        outboxRecorder.record(EventType.PRODUCT_UPDATED, product);
        log.info("Stok diubah: id={} delta={} stok_baru={}", id, req.delta(), product.getStock());
        return ProductMapper.toPayload(product);
    }

    @Transactional
    public void delete(UUID id) {
        Product product = productRepository.findById(id).orElseThrow(() -> ApiException.notFound(id));

        // DELETE dibuat idempotent: memanggilnya dua kali tidak menaikkan versi dan tidak
        // menerbitkan event kedua. Tanpa ini, retry klien menghasilkan event beruntun yang
        // tidak membawa informasi baru sama sekali.
        if (product.isDeleted()) {
            log.debug("Produk {} memang sudah terhapus, tidak ada event baru", id);
            return;
        }

        product.softDelete();
        productRepository.saveAndFlush(product);
        outboxRecorder.record(EventType.PRODUCT_DELETED, product);
        log.info("Produk dihapus (soft): id={}", id);
    }

    // ------------------------------------------------------------- dipakai jalur image

    /**
     * Menyimpan referensi gambar baru. Dipanggil oleh alur upload <b>setelah</b> objek berhasil
     * diunggah ke S3, sehingga kalau transaksi ini gagal, pemanggil masih bisa menghapus objek
     * yang terlanjur terunggah.
     *
     * @return object key gambar lama (boleh null) supaya pemanggil bisa membersihkannya.
     */
    @Transactional
    public ImageUpdateResult attachImage(UUID id, String objectKey, String contentType, long sizeBytes) {
        Product product = loadActive(id);
        String previousKey = product.getImageObjectKey();

        product.attachImage(objectKey, contentType, sizeBytes);
        productRepository.saveAndFlush(product);
        outboxRecorder.record(EventType.PRODUCT_IMAGE_UPDATED, product);
        log.info("Gambar dipasang: id={} key={}", id, objectKey);

        return new ImageUpdateResult(ProductMapper.toPayload(product), previousKey);
    }

    @Transactional
    public ImageUpdateResult detachImage(UUID id) {
        Product product = loadActive(id);
        String previousKey = product.getImageObjectKey();

        if (previousKey == null) {
            return new ImageUpdateResult(ProductMapper.toPayload(product), null);
        }

        product.removeImage();
        productRepository.saveAndFlush(product);
        outboxRecorder.record(EventType.PRODUCT_IMAGE_REMOVED, product);
        log.info("Gambar dilepas: id={} key_lama={}", id, previousKey);

        return new ImageUpdateResult(ProductMapper.toPayload(product), previousKey);
    }

    /** Hasil operasi gambar beserta key lama yang perlu dibersihkan dari storage. */
    public record ImageUpdateResult(ProductPayload product, String previousObjectKey) {
    }

    // ------------------------------------------------------------- helper

    private Product loadActive(UUID id) {
        Product product = productRepository.findById(id).orElseThrow(() -> ApiException.notFound(id));
        if (product.isDeleted()) {
            // Produk terhapus diperlakukan seolah tidak ada, supaya klien tidak bisa
            // "menghidupkan kembali" produk lewat endpoint update.
            throw ApiException.notFound(id);
        }
        return product;
    }

    private void assertVersionMatches(Product product, Long expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(product.getVersion())) {
            throw ApiException.versionConflict(product.getId(), expectedVersion, product.getVersion());
        }
    }

    private boolean isSkuConflict(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("ux_products_sku");
    }
}
