package com.example.write.api;

import com.example.write.api.dto.ImageResponse;
import com.example.write.service.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Tag(name = "Product Image", description = "Unggah gambar produk ke object storage (S3/MinIO)")
@RestController
@RequestMapping("/api/v1/products/{id}/image")
@RequiredArgsConstructor
public class ProductImageController {

    private final ProductImageService productImageService;

    @Operation(summary = "Unggah / ganti gambar produk",
            description = "Tipe file ditentukan dari isi berkas, bukan dari Content-Type yang dikirim klien. "
                    + "Hanya JPEG, PNG, dan WEBP yang diterima. Gambar lama otomatis dihapus setelah "
                    + "gambar baru tersimpan.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageResponse> upload(@PathVariable UUID id,
                                                @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(productImageService.upload(id, file));
    }

    @Operation(summary = "Hapus gambar produk",
            description = "Idempotent: produk tanpa gambar tetap dijawab 204.")
    @DeleteMapping
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        productImageService.remove(id);
        return ResponseEntity.noContent().build();
    }
}
