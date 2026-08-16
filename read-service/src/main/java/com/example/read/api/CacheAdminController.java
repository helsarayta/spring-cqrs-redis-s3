package com.example.read.api;

import com.example.read.cache.ProductCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Alat bantu operasional untuk cache.
 *
 * <p>Berguna saat menyelidiki keluhan "data saya tidak berubah": hapus key-nya, lalu minta
 * ulang, dan langsung terlihat apakah masalahnya di cache atau di read model.
 *
 * <p><b>Peringatan:</b> endpoint ini terbuka karena autentikasi masih di luar scope
 * (lihat PLAN.md §16). Sebelum dipakai di lingkungan nyata, jalur {@code /api/v1/admin/**}
 * wajib dilindungi.
 */
@Tag(name = "Cache Admin", description = "Alat bantu operasional. Harus dilindungi sebelum dipakai di produksi.")
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/cache")
@RequiredArgsConstructor
public class CacheAdminController {

    private final ProductCache cache;

    @Operation(summary = "Hapus cache satu produk",
            description = "Pembacaan berikutnya akan mengambil ulang dari database.")
    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> evictProduct(@PathVariable UUID id,
                                             @RequestParam(required = false) String sku) {
        log.info("Penghapusan cache manual untuk produk {}", id);
        cache.evict(id, sku);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Hapus seluruh cache hasil daftar")
    @DeleteMapping("/lists")
    public ResponseEntity<Void> evictLists() {
        log.info("Penghapusan seluruh cache daftar secara manual");
        cache.evictLists();
        return ResponseEntity.noContent().build();
    }
}
