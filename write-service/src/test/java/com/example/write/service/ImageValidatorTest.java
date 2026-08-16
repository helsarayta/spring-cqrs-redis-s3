package com.example.write.service;

import com.example.common.error.ApiException;
import com.example.common.error.ErrorCode;
import com.example.write.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inti dari test ini: memastikan keputusan diambil dari <b>isi berkas</b>, bukan dari apa yang
 * diklaim klien lewat Content-Type maupun nama file.
 */
class ImageValidatorTest {

    private final ImageValidator validator = new ImageValidator(properties(2));

    private static AppProperties properties(int maxMb) {
        return new AppProperties(
                new AppProperties.Outbox(500, 100, 10, 1, 300, 72),
                new AppProperties.Idempotency(24),
                new AppProperties.S3("http://localhost:9000", "bucket", "us-east-1",
                        "key", "secret", true, true, maxMb,
                        AppProperties.UrlMode.PRESIGNED, 15, "http://localhost:9000"));
    }

    // Byte awal yang menandai tiap format. Sisanya tidak perlu berupa gambar yang sah —
    // yang diuji di sini adalah pengenalan format, bukan pembacaan gambar.
    private static byte[] jpeg() {
        return concat(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "sisanya".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] png() {
        return concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
                "sisanya".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] webp() {
        return concat("RIFF".getBytes(StandardCharsets.UTF_8),
                concat(new byte[]{0, 0, 0, 0}, "WEBPsisanya".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    @DisplayName("PNG, JPEG, dan WEBP asli diterima dan tipenya dikenali dari isi berkas")
    void acceptsSupportedFormats() {
        assertThat(validator.validate(file("a.png", "image/png", png())).contentType()).isEqualTo("image/png");
        assertThat(validator.validate(file("a.jpg", "image/jpeg", jpeg())).contentType()).isEqualTo("image/jpeg");
        assertThat(validator.validate(file("a.webp", "image/webp", webp())).contentType()).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("berkas PDF yang dinamai .png dan mengaku image/png tetap ditolak")
    void rejectsDisguisedFile() {
        byte[] pdf = "%PDF-1.4\nbukan gambar".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(file("gambar.png", "image/png", pdf)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
    }

    @Test
    @DisplayName("tipe hasil deteksi menang atas Content-Type yang diklaim klien")
    void detectedTypeWinsOverClaim() {
        // Klien menyebut ini JPEG, padahal isinya PNG. Yang disimpan harus image/png.
        var result = validator.validate(file("a.jpg", "image/jpeg", png()));

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.extension()).isEqualTo("png");
    }

    @Test
    @DisplayName("berkas melebihi batas ukuran ditolak dengan 413")
    void rejectsOversizedFile() {
        byte[] tooBig = new byte[3 * 1024 * 1024]; // batas dipasang 2 MB
        System.arraycopy(png(), 0, tooBig, 0, png().length);

        assertThatThrownBy(() -> validator.validate(file("besar.png", "image/png", tooBig)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("berkas kosong ditolak")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> validator.validate(file("kosong.png", "image/png", new byte[0])))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("berkas terlalu pendek untuk memuat magic bytes ditolak, bukan bikin error index")
    void rejectsTruncatedFile() {
        // Hanya 2 byte: lebih pendek dari signature mana pun. Pengecekan panjang di
        // regionMatches yang menahan ini; tanpanya akan terjadi ArrayIndexOutOfBounds.
        assertThatThrownBy(() -> validator.validate(file("pendek.png", "image/png", new byte[]{(byte) 0x89, 0x50})))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
    }

    private MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }
}
