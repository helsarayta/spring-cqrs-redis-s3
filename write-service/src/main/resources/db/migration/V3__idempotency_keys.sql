-- Dukungan header Idempotency-Key pada endpoint POST.
--
-- Skenario yang dilindungi: klien mengirim POST, jaringan timeout sebelum response sampai,
-- klien retry. Tanpa tabel ini, hasilnya dua produk. Dengan tabel ini, retry mengembalikan
-- response yang persis sama dengan yang pertama.

CREATE TABLE idempotency_keys
(
    idem_key        VARCHAR(255) PRIMARY KEY,
    endpoint        VARCHAR(255) NOT NULL,

    -- Hash dari body request. Kalau key sama dipakai untuk body BERBEDA, itu bug di sisi
    -- klien dan kita balas 409 daripada diam-diam mengembalikan hasil yang salah.
    request_hash    VARCHAR(64)  NOT NULL,

    response_status INTEGER,
    response_body   JSONB,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_idempotency_expires_at ON idempotency_keys (expires_at);
