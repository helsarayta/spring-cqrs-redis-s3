-- Transactional Outbox.
--
-- Alasan tabel ini ada: menyimpan ke DB dan mengirim ke Kafka adalah dua sistem berbeda,
-- dan tidak ada transaksi yang mencakup keduanya. Kalau service mengirim ke Kafka langsung
-- lalu crash sebelum commit DB (atau sebaliknya), kedua sisi jadi berbeda selamanya dan
-- tidak ada cara otomatis untuk mendeteksinya.
--
-- Dengan outbox, "simpan produk" dan "catat event" berada dalam SATU transaksi Postgres.
-- Pengiriman ke Kafka jadi urusan terpisah yang boleh gagal dan diulang.

CREATE TABLE outbox_events
(
    -- BIGSERIAL, bukan UUID: urutan insert = urutan publish, dan poller mengurutkan dengan ini.
    id                BIGSERIAL PRIMARY KEY,

    -- Dibuat saat menulis baris ini, BUKAN saat mengirim. Jadi kalau pengiriman diulang,
    -- consumer melihat event_id yang sama dan bisa mendeteksinya sebagai duplikat.
    event_id          UUID        NOT NULL UNIQUE,

    aggregate_type    VARCHAR(50) NOT NULL,
    aggregate_id      UUID        NOT NULL,
    aggregate_version BIGINT      NOT NULL,
    event_type        VARCHAR(50) NOT NULL,

    payload           JSONB       NOT NULL,
    headers           JSONB,

    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts          INTEGER     NOT NULL DEFAULT 0,
    last_error        TEXT,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,

    CONSTRAINT outbox_status_valid CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Index untuk query poller: "ambil yang PENDING dan sudah waktunya dicoba, urut id".
-- Partial index (WHERE status = 'PENDING') menjaga ukurannya tetap kecil walaupun
-- tabelnya tumbuh besar oleh baris PUBLISHED.
CREATE INDEX ix_outbox_pending ON outbox_events (next_attempt_at, id)
    WHERE status = 'PENDING';

-- Untuk pembersihan berkala baris yang sudah terkirim.
CREATE INDEX ix_outbox_published_at ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';
