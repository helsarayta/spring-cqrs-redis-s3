package com.example.read.cache;

import com.example.read.config.ReadProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Penyusun key Redis.
 *
 * <p>Semua key mengandung penanda versi {@code :v1:}. Kalau suatu saat bentuk data yang
 * disimpan berubah (misalnya ada field baru pada payload), cukup naikkan menjadi {@code v2}:
 * seluruh cache lama otomatis terabaikan dan kedaluwarsa sendiri. Tanpa penanda ini,
 * deploy versi baru akan membaca data berbentuk lama dan gagal saat deserialisasi —
 * pada setiap cache hit, di produksi.
 */
@Component
public class CacheKeys {

    private static final String VERSION = "v1";

    private final String prefix;

    public CacheKeys(ReadProperties properties) {
        this.prefix = properties.cache().keyPrefix();
    }

    /** Key utama: menyimpan snapshot produk. */
    public String product(UUID id) {
        return "%s:%s:%s".formatted(prefix, VERSION, id);
    }

    /**
     * Key penunjuk dari SKU ke id.
     *
     * <p>Yang disimpan hanya id-nya, bukan salinan kedua dari produk. Kalau produknya
     * disalin di sini juga, setiap invalidasi harus mengenai dua tempat dan cepat atau
     * lambat keduanya akan tidak sinkron.
     */
    public String sku(String sku) {
        return "%s:sku:%s:%s".formatted(prefix, VERSION, sku);
    }

    /**
     * Key hasil daftar. Kombinasi parameter query dipadatkan jadi hash pendek.
     *
     * <p>Dipakai SHA-256 yang dipotong 16 digit heksadesimal (64 bit), bukan checksum seperti
     * CRC32. Alasannya bukan keamanan melainkan tabrakan: CRC32 hanya 32 bit, dan dua
     * kombinasi filter yang berbeda bisa menghasilkan nilai yang sama. Akibatnya bukan sekadar
     * cache meleset — pemanggil menerima <b>daftar milik query lain</b> dan tetap dijawab 200,
     * jadi kesalahannya tidak menimbulkan error apa pun. Dengan 64 bit, peluangnya bisa
     * diabaikan.
     */
    public String list(String queryDescriptor) {
        return "%s:list:%s:%s".formatted(prefix, VERSION, shortHash(queryDescriptor));
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia di JVM ini", e);
        }
    }

    /** Key kunci single-flight, dipakai agar hanya satu pemanggil yang mengisi ulang cache. */
    public String lock(UUID id) {
        return "%s:lock:%s:%s".formatted(prefix, VERSION, id);
    }

    public String listPattern() {
        return "%s:list:%s:*".formatted(prefix, VERSION);
    }
}
