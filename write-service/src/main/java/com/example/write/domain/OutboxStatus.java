package com.example.write.domain;

public enum OutboxStatus {

    /** Menunggu dikirim, atau menunggu percobaan ulang berikutnya. */
    PENDING,

    /** Sudah diterima broker (acks=all). Aman untuk dibersihkan setelah masa retensi. */
    PUBLISHED,

    /**
     * Menyerah setelah melewati batas percobaan.
     *
     * <p>Ini kondisi yang butuh perhatian manusia: read model dipastikan tertinggal untuk
     * agregat tersebut. Jumlah baris FAILED diekspos sebagai metrik agar bisa dipasangi alert.
     */
    FAILED
}
