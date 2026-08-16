package com.example.write.service;

import com.example.common.storage.ImageUrlResolver;
import com.example.write.api.dto.ImageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Mengatur urutan operasi antara storage dan database saat gambar diunggah.
 *
 * <p>Ada dua sistem yang harus berubah bersama — S3 dan Postgres — dan tidak ada transaksi
 * yang mencakup keduanya. Jadi urutannya dipilih supaya kegagalan di titik mana pun
 * meninggalkan keadaan yang paling tidak berbahaya:
 *
 * <pre>
 *   1. unggah objek BARU ke S3
 *   2. simpan key baru ke database (+ catat event outbox, satu transaksi)
 *        gagal -> hapus objek baru yang terlanjur terunggah, lalu lempar error
 *   3. setelah database commit, hapus objek LAMA (sekadar usaha, kegagalan hanya dicatat)
 * </pre>
 *
 * <p>Yang dihindari adalah kebalikannya — menyimpan key ke database lebih dulu lalu mengunggah.
 * Kalau unggahan gagal di situ, database menunjuk ke objek yang tidak pernah ada, dan setiap
 * pembacaan produk itu menghasilkan gambar rusak sampai ada yang memperbaikinya manual.
 *
 * <p>Kegagalan yang tersisa hanyalah objek yatim di bucket: memakan ruang, tidak terlihat
 * pengguna, dan bisa dibersihkan belakangan. Itu pertukaran yang disengaja.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageService {

    private final ProductWriteService productWriteService;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;
    private final ImageUrlResolver imageUrlResolver;

    public ImageResponse upload(UUID productId, MultipartFile file) {
        ImageValidator.ValidatedImage image = imageValidator.validate(file);
        String newKey = imageStorageService.buildObjectKey(productId, image.extension());

        // Langkah 1
        imageStorageService.upload(newKey, image);

        // Langkah 2
        ProductWriteService.ImageUpdateResult result;
        try {
            result = productWriteService.attachImage(productId, newKey, image.contentType(), image.size());
        } catch (RuntimeException e) {
            // Kompensasi: database tidak jadi menunjuk ke objek ini, jadi objeknya tidak boleh
            // ditinggalkan begitu saja.
            log.warn("Penyimpanan ke database gagal setelah unggah; membatalkan objek {}", newKey);
            imageStorageService.deleteQuietly(newKey);
            throw e;
        }

        // Langkah 3
        imageStorageService.deleteQuietly(result.previousObjectKey());

        return new ImageResponse(
                productId,
                newKey,
                imageUrlResolver.toUrl(newKey),
                image.contentType(),
                image.size(),
                result.product().version());
    }

    public void remove(UUID productId) {
        // Di sini urutannya dibalik — database dulu, baru storage — karena tidak ada objek baru
        // yang bisa hilang. Kalau penghapusan objek gagal, database sudah benar (tidak lagi
        // menunjuk ke gambar) dan yang tersisa cuma objek yatim.
        ProductWriteService.ImageUpdateResult result = productWriteService.detachImage(productId);
        imageStorageService.deleteQuietly(result.previousObjectKey());
    }
}
