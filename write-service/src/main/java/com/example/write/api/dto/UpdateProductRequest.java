package com.example.write.api.dto;

import com.example.common.dto.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Semua field opsional: yang bernilai {@code null} dibiarkan apa adanya.
 *
 * <p>Catatan: karena itu, endpoint ini tidak bisa dipakai untuk mengosongkan
 * {@code description}. Itu keterbatasan yang disengaja demi kesederhanaan — kalau nanti
 * dibutuhkan, jalannya lewat endpoint PATCH khusus, bukan dengan menafsirkan string kosong.
 *
 * <p>{@code status} tidak menerima {@link ProductStatus#DELETED}; penghapusan lewat
 * {@code DELETE /products/{id}} supaya niatnya eksplisit dan tercatat sebagai event tersendiri.
 */
public record UpdateProductRequest(

        @Size(max = 255)
        String name,

        @Size(max = 5000)
        String description,

        @DecimalMin(value = "0.0", message = "price tidak boleh negatif")
        @Digits(integer = 17, fraction = 2)
        BigDecimal price,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency harus 3 huruf kode ISO-4217")
        String currency,

        @Min(value = 0, message = "stock tidak boleh negatif")
        Integer stock,

        ProductStatus status
) {
}
