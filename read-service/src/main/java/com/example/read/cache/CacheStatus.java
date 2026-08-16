package com.example.read.cache;

/**
 * Asal jawaban sebuah request. Dikirim balik sebagai header {@code X-Cache}.
 *
 * <p>Membuat ini terlihat dari luar sangat membantu: tanpa header itu, satu-satunya cara
 * memastikan cache benar-benar bekerja adalah mengintip Redis secara manual, dan
 * "cache yang ternyata tidak pernah kena" adalah bug yang sepenuhnya tak terlihat —
 * sistemnya tetap menjawab benar, hanya jauh lebih lambat dan lebih mahal.
 */
public enum CacheStatus {

    /** Dijawab dari Redis. */
    HIT,

    /** Tidak ada di Redis; diambil dari database lalu disimpan ke Redis. */
    MISS,

    /** Redis menjawab "id ini memang tidak ada" — database tidak perlu disentuh. */
    NEGATIVE_HIT,

    /**
     * Redis dilewati: sedang bermasalah, atau cache memang dimatikan lewat konfigurasi.
     * Request tetap dilayani dari database.
     */
    BYPASS
}
