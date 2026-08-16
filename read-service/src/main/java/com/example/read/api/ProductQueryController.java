package com.example.read.api;

import com.example.common.dto.ProductStatus;
import com.example.read.api.dto.PageResponse;
import com.example.read.api.dto.ProductView;
import com.example.read.service.ProductQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Endpoint baca produk. Tidak ada operasi tulis di service ini sama sekali.
 *
 * <p>Setiap response membawa header {@code X-Cache} yang menyatakan dari mana jawabannya
 * berasal: {@code HIT}, {@code MISS}, {@code NEGATIVE_HIT}, atau {@code BYPASS}.
 * Tanpa header ini, "cache yang ternyata tidak pernah kena" adalah bug yang tak terlihat —
 * jawabannya tetap benar, hanya jauh lebih lambat dan lebih mahal.
 */
@Tag(name = "Product Query", description = "Baca produk. Redis dicek lebih dulu, database menyusul kalau perlu.")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductQueryController {

    private static final String CACHE_HEADER = "X-Cache";

    private final ProductQueryService queryService;

    @Operation(summary = "Ambil produk berdasarkan id",
            description = "Alur: Redis -> (kalau tidak ada) database -> isi Redis. "
                    + "Kalau Redis sedang bermasalah, request tetap dilayani database dengan X-Cache: BYPASS.")
    @GetMapping("/{id}")
    public ResponseEntity<ProductView> getById(@PathVariable UUID id) {
        ProductQueryService.Result<ProductView> result = queryService.getById(id);
        return ResponseEntity.ok()
                .header(CACHE_HEADER, result.status().name())
                .body(result.value());
    }

    @Operation(summary = "Ambil produk berdasarkan SKU")
    @GetMapping("/by-sku/{sku}")
    public ResponseEntity<ProductView> getBySku(@PathVariable String sku) {
        ProductQueryService.Result<ProductView> result = queryService.getBySku(sku);
        return ResponseEntity.ok()
                .header(CACHE_HEADER, result.status().name())
                .body(result.value());
    }

    @Operation(summary = "Daftar produk",
            description = "Hasil daftar di-cache dengan TTL pendek dan tidak di-invalidasi saat ada "
                    + "perubahan, jadi bisa tertinggal beberapa detik. Pembacaan by-id selalu mutakhir.")
    @GetMapping
    public ResponseEntity<PageResponse<ProductView>> list(
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @PageableDefault(size = 20, sort = "sourceCreatedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        ProductQueryService.Result<PageResponse<ProductView>> result =
                queryService.list(status, q, minPrice, maxPrice, pageable);

        return ResponseEntity.ok()
                .header(CACHE_HEADER, result.status().name())
                .body(result.value());
    }
}
