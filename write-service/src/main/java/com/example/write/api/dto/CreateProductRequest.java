package com.example.write.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(

        @NotBlank(message = "sku wajib diisi")
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "sku hanya boleh huruf, angka, titik, garis bawah, dan strip")
        String sku,

        @NotBlank(message = "name wajib diisi")
        @Size(max = 255)
        String name,

        @Size(max = 5000)
        String description,

        @NotNull(message = "price wajib diisi")
        @DecimalMin(value = "0.0", message = "price tidak boleh negatif")
        @Digits(integer = 17, fraction = 2, message = "price maksimal 2 angka di belakang koma")
        BigDecimal price,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency harus 3 huruf kode ISO-4217, mis. IDR")
        String currency,

        @NotNull(message = "stock wajib diisi")
        @Min(value = 0, message = "stock tidak boleh negatif")
        Integer stock
) {
}
