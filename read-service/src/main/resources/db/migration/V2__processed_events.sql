-- Catatan event yang sudah diproses.
--
-- Kafka menjamin pengiriman *at-least-once*, bukan exactly-once. Pesan yang sama bisa
-- datang dua kali: consumer restart sebelum offset ter-commit, rebalance, atau publisher
-- outbox mengulang pengiriman yang sebenarnya sudah sampai.
--
-- Baris di sini ditulis dalam transaksi yang SAMA dengan pembaruan read model. Jadi
-- "read model berubah" dan "event tercatat sudah diproses" selalu terjadi bersamaan —
-- tidak mungkin salah satunya saja.

CREATE TABLE processed_events
(
    event_id          UUID PRIMARY KEY,
    aggregate_id      UUID        NOT NULL,
    aggregate_version BIGINT      NOT NULL,
    event_type        VARCHAR(50) NOT NULL,
    processed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Untuk pembersihan berkala. Tabel ini tumbuh seiring jumlah event, jadi baris lama
-- perlu dibuang; batas retensinya harus lebih panjang dari retensi topic Kafka, kalau
-- tidak event yang di-replay bisa lolos sebagai "belum pernah diproses".
CREATE INDEX ix_processed_events_processed_at ON processed_events (processed_at);

CREATE INDEX ix_processed_events_aggregate ON processed_events (aggregate_id, aggregate_version DESC);
