package com.example.write.service;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductWriteServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OutboxRecorder outboxRecorder;

    @InjectMocks
    private ProductWriteService service;

    private static CreateProductRequest createRequest() {
        return new CreateProductRequest("SKU-1", "Kopi", "enak", new BigDecimal("10000"), "IDR", 5);
    }

    private static Product existingProduct() {
        return Product.create("SKU-1", "Kopi", "enak", new BigDecimal("10000"), "IDR", 5);
    }

    @Test
    @DisplayName("membuat produk mencatat event PRODUCT_CREATED ke outbox")
    void createRecordsOutboxEvent() {
        when(productRepository.existsBySku("SKU-1")).thenReturn(false);

        service.create(createRequest());

        // Yang dijaga di sini: penulisan produk TIDAK boleh terjadi tanpa event pendampingnya.
        // Kalau salah satunya hilang, read model diam-diam tertinggal selamanya.
        verify(productRepository).saveAndFlush(any(Product.class));
        verify(outboxRecorder).record(eq(EventType.PRODUCT_CREATED), any(Product.class));
    }

    @Test
    @DisplayName("SKU yang sudah dipakai ditolak 409 dan tidak menghasilkan event")
    void createRejectsDuplicateSku() {
        when(productRepository.existsBySku("SKU-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.SKU_ALREADY_EXISTS);

        verify(outboxRecorder, never()).record(any(), any());
    }

    @Test
    @DisplayName("If-Match dengan versi yang tidak cocok ditolak 409")
    void updateRejectsVersionMismatch() {
        Product product = existingProduct();
        UUID id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        // Versi entity masih null (belum pernah di-flush Hibernate), klien mengirim 7.
        UpdateProductRequest request = new UpdateProductRequest("Nama baru", null, null, null, null, null);

        assertThatThrownBy(() -> service.update(id, request, 7L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VERSION_CONFLICT);

        verify(productRepository, never()).saveAndFlush(any());
        verify(outboxRecorder, never()).record(any(), any());
    }

    @Test
    @DisplayName("mengubah produk yang sudah dihapus dijawab 404, bukan menghidupkannya kembali")
    void updateOnDeletedProductIsNotFound() {
        Product product = existingProduct();
        product.softDelete();
        UUID id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.update(id,
                new UpdateProductRequest("Nama", null, null, null, null, null), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("status DELETED lewat endpoint update ditolak; penghapusan harus lewat DELETE")
    void updateCannotSetDeletedStatus() {
        Product product = existingProduct();
        UUID id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.update(id,
                new UpdateProductRequest(null, null, null, null, null, ProductStatus.DELETED), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("menghapus produk yang sudah terhapus tidak menerbitkan event kedua")
    void deleteIsIdempotent() {
        Product product = existingProduct();
        product.softDelete();
        UUID id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        service.delete(id);

        // Retry dari klien tidak boleh menghasilkan rentetan event yang tidak membawa
        // informasi baru — dan tidak boleh menaikkan versi agregat tanpa alasan.
        verify(outboxRecorder, never()).record(any(), any());
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("stok tidak boleh jadi negatif")
    void adjustStockRejectsNegativeResult() {
        Product product = existingProduct(); // stok 5
        UUID id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.adjustStock(id, new AdjustStockRequest(-9)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThat(product.getStock()).isEqualTo(5);
        verify(outboxRecorder, never()).record(any(), any());
    }

    @Test
    @DisplayName("perubahan stok bersifat relatif terhadap nilai sekarang")
    void adjustStockIsRelative() {
        Product product = existingProduct(); // stok 5
        UUID id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        service.adjustStock(id, new AdjustStockRequest(-2));

        assertThat(product.getStock()).isEqualTo(3);
        verify(outboxRecorder).record(eq(EventType.PRODUCT_UPDATED), any(Product.class));
    }

    @Test
    @DisplayName("memasang gambar mengembalikan key gambar lama supaya bisa dibersihkan")
    void attachImageReturnsPreviousKey() {
        Product product = existingProduct();
        product.attachImage("key-lama.png", "image/png", 10L);
        UUID id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        var result = service.attachImage(id, "key-baru.png", "image/png", 20L);

        assertThat(result.previousObjectKey()).isEqualTo("key-lama.png");
        assertThat(product.getImageObjectKey()).isEqualTo("key-baru.png");

        ArgumentCaptor<EventType> eventType = ArgumentCaptor.forClass(EventType.class);
        verify(outboxRecorder).record(eventType.capture(), any(Product.class));
        assertThat(eventType.getValue()).isEqualTo(EventType.PRODUCT_IMAGE_UPDATED);
    }

    @Test
    @DisplayName("melepas gambar dari produk yang memang tidak punya gambar tidak menerbitkan event")
    void detachImageWithoutImageDoesNothing() {
        Product product = existingProduct();
        UUID id = product.getId();
        when(productRepository.findById(id)).thenReturn(Optional.of(product));

        var result = service.detachImage(id);

        assertThat(result.previousObjectKey()).isNull();
        verify(outboxRecorder, never()).record(any(), any());
    }
}
