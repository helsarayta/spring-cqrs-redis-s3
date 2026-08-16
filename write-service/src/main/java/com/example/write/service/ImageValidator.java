package com.example.write.service;

import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.write.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;

/**
 * Memeriksa file yang diunggah sebelum menyentuh storage.
 *
 * <p>Poin terpenting kelas ini: <b>tipe file ditentukan dari isi file, bukan dari klaim klien</b>.
 * Header {@code Content-Type} dan ekstensi nama file dikirim oleh klien dan bisa diisi apa saja.
 * Menerima file berdasarkan klaim itu berarti apa pun bisa masuk ke bucket dengan menyamar
 * sebagai gambar — cukup dengan menamainya {@code .png}.
 *
 * <p>Karena itu yang dicek adalah magic bytes di awal file, dan content-type yang disimpan
 * adalah hasil deteksi, bukan yang diklaim.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageValidator {

    private final AppProperties properties;

    /** Hasil pemeriksaan: tipe dan ekstensi di sini sudah terverifikasi dari isi file. */
    public record ValidatedImage(byte[] bytes, String contentType, String extension) {

        public long size() {
            return bytes.length;
        }
    }

    private enum ImageKind {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp");

        final String contentType;
        final String extension;

        ImageKind(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    public ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "File gambar tidak boleh kosong");
        }

        long maxBytes = properties.s3().maxImageSizeBytes();
        if (file.getSize() > maxBytes) {
            throw new ApiException(ErrorCode.IMAGE_TOO_LARGE,
                    "Ukuran gambar %d byte melebihi batas %d MB"
                            .formatted(file.getSize(), properties.s3().maxImageSizeMb()));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Gagal membaca file yang diunggah", e);
        }

        ImageKind kind = detectKind(bytes);
        if (kind == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_IMAGE_TYPE,
                    "Isi file bukan JPEG, PNG, atau WEBP. Tipe yang diklaim: %s"
                            .formatted(file.getContentType()));
        }

        // Ketidakcocokan tidak menggagalkan request — yang menentukan tetap isi file — tapi
        // dicatat, karena polanya berguna saat menyelidiki klien yang berperilaku aneh.
        if (file.getContentType() != null && !kind.contentType.equals(file.getContentType())) {
            log.info("Content-Type yang diklaim '{}' tidak cocok dengan isi file ({}). Memakai hasil deteksi.",
                    file.getContentType(), kind.contentType);
        }

        return new ValidatedImage(bytes, kind.contentType, kind.extension);
    }

    /**
     * Mengenali tipe gambar dari beberapa byte pertama.
     *
     * @return null kalau tidak cocok dengan format mana pun yang didukung
     */
    private ImageKind detectKind(byte[] b) {
        // JPEG: FF D8 FF
        if (startsWith(b, new int[]{0xFF, 0xD8, 0xFF})) {
            return ImageKind.JPEG;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (startsWith(b, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return ImageKind.PNG;
        }
        // WEBP: "RIFF" pada byte 0-3, lalu "WEBP" pada byte 8-11.
        // Byte 4-7 adalah panjang berkas, jadi bagian itu memang dilewati.
        if (startsWith(b, new int[]{'R', 'I', 'F', 'F'}) && regionMatches(b, 8, new int[]{'W', 'E', 'B', 'P'})) {
            return ImageKind.WEBP;
        }
        return null;
    }

    private boolean startsWith(byte[] data, int[] signature) {
        return regionMatches(data, 0, signature);
    }

    private boolean regionMatches(byte[] data, int offset, int[] signature) {
        if (data.length < offset + signature.length) {
            return false;
        }
        return Arrays.equals(
                Arrays.copyOfRange(data, offset, offset + signature.length),
                toBytes(signature));
    }

    private byte[] toBytes(int[] values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
