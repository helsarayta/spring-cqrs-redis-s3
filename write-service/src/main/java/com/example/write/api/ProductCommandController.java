package com.example.write.api;

import com.example.common.dto.ProductPayload;
import com.example.write.api.dto.AdjustStockRequest;
import com.example.write.api.dto.CreateProductRequest;
import com.example.write.api.dto.UpdateProductRequest;
import com.example.write.service.IdempotencyService;
import com.example.write.service.ProductWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Endpoint tulis produk.
 *
 * <p>Controller ini sengaja tipis: tidak ada logika bisnis di sini, hanya penerjemahan
 * HTTP ke pemanggilan service. Salah satu manfaatnya, menambahkan autentikasi nanti
 * (yang saat ini di luar scope) tidak perlu menyentuh lapisan service sama sekali.
 *
 * <p>Tidak ada endpoint GET di sini — pembacaan sepenuhnya tugas read-service.
 */
@Tag(name = "Product Command", description = "Operasi tulis produk. Pembacaan ada di read-service (port 8082).")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductCommandController {

    /**
     * Memberi tahu klien bahwa hasil tulis ini belum tentu langsung terlihat di read-service.
     * Jauh lebih baik dinyatakan terang-terangan daripada dibiarkan jadi kejutan.
     */
    private static final String CONSISTENCY_HEADER = "X-Read-Consistency";
    private static final String CONSISTENCY_VALUE = "eventual";

    private final ProductWriteService productWriteService;
    private final IdempotencyService idempotencyService;

    @Operation(summary = "Buat produk baru",
            description = "Sertakan header Idempotency-Key agar retry akibat timeout tidak membuat produk ganda.")
    @PostMapping
    public ResponseEntity<ProductPayload> create(
            @Valid @RequestBody CreateProductRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        IdempotencyService.Outcome<ProductPayload> outcome = idempotencyService.execute(
                idempotencyKey,
                "POST /api/v1/products",
                request,
                ProductPayload.class,
                () -> productWriteService.create(request));

        ProductPayload product = outcome.body();
        URI location = UriComponentsBuilder.fromPath("/api/v1/products/{id}")
                .buildAndExpand(product.id())
                .toUri();

        return ResponseEntity.created(location)
                .header(CONSISTENCY_HEADER, CONSISTENCY_VALUE)
                .header("Idempotent-Replay", String.valueOf(outcome.replayed()))
                .body(product);
    }

    @Operation(summary = "Ubah produk",
            description = "Kirim header If-Match berisi nomor versi terakhir yang Anda lihat untuk "
                    + "mencegah menimpa perubahan orang lain. Tanpa header itu, perubahan terakhir yang menang.")
    @PutMapping("/{id}")
    public ResponseEntity<ProductPayload> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request,
            @RequestHeader(value = "If-Match", required = false) Long expectedVersion) {

        ProductPayload product = productWriteService.update(id, request, expectedVersion);
        return ResponseEntity.ok()
                .header(CONSISTENCY_HEADER, CONSISTENCY_VALUE)
                .header("ETag", String.valueOf(product.version()))
                .body(product);
    }

    @Operation(summary = "Tambah/kurangi stok", description = "delta boleh negatif. Bersifat relatif, bukan absolut.")
    @PatchMapping("/{id}/stock")
    public ResponseEntity<ProductPayload> adjustStock(
            @PathVariable UUID id,
            @Valid @RequestBody AdjustStockRequest request) {

        ProductPayload product = productWriteService.adjustStock(id, request);
        return ResponseEntity.ok()
                .header(CONSISTENCY_HEADER, CONSISTENCY_VALUE)
                .body(product);
    }

    @Operation(summary = "Hapus produk (soft delete)",
            description = "Idempotent: memanggil dua kali tetap 204 dan tidak menerbitkan event kedua.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productWriteService.delete(id);
        return ResponseEntity.noContent()
                .header(CONSISTENCY_HEADER, CONSISTENCY_VALUE)
                .build();
    }
}
