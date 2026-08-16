package com.example.write.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Perubahan stok bersifat <b>relatif</b>.
 *
 * <p>Bandingkan dengan "set stok jadi N": dua permintaan bersamaan untuk mengurangi 1 unit
 * menghasilkan pengurangan 2 unit kalau relatif, tapi bisa kehilangan satu pengurangan
 * kalau absolut (yang belakangan menimpa yang duluan).
 */
public record AdjustStockRequest(

        @NotNull(message = "delta wajib diisi (boleh negatif)")
        Integer delta
) {
}
