-- Read model produk.
--
-- Tabel ini BUKAN source of truth. Isinya sepenuhnya turunan dari event yang diterbitkan
-- write-service, dan boleh dibangun ulang dari nol dengan memutar ulang topic Kafka.
-- Karena itu ia bebas dibentuk sesuai kebutuhan pembacaan, tanpa terikat normalisasi
-- di sisi tulis.

CREATE TABLE product_read_model
(
    id                 UUID PRIMARY KEY,
    sku                VARCHAR(64)    NOT NULL,
    name               VARCHAR(255)   NOT NULL,
    description        TEXT,
    price              NUMERIC(19, 2) NOT NULL,
    currency           CHAR(3)        NOT NULL,
    stock              INTEGER        NOT NULL,

    image_object_key   VARCHAR(512),
    image_content_type VARCHAR(100),
    image_size_bytes   BIGINT,

    status             VARCHAR(20)    NOT NULL,

    -- Versi agregat dari event terakhir yang diterapkan.
    -- Inilah pertahanan terhadap event yang datang terlambat: event dengan versi
    -- lebih kecil atau sama dengan nilai ini diabaikan, sehingga data lama tidak
    -- pernah menimpa data yang lebih baru.
    aggregate_version  BIGINT         NOT NULL,
    last_event_id      UUID,

    -- Waktu dari sisi tulis, dibawa event. Dipisahkan dari projected_at supaya
    -- "kapan produk ini dibuat" dan "kapan baris ini terakhir disinkronkan" tidak tertukar.
    source_created_at  TIMESTAMPTZ    NOT NULL,
    source_updated_at  TIMESTAMPTZ    NOT NULL,
    projected_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- SKU unik, sama seperti di sisi tulis. Kalau constraint ini sampai dilanggar, artinya
-- ada yang salah pada aliran event — lebih baik ketahuan di sini daripada diam-diam.
CREATE UNIQUE INDEX ux_prm_sku ON product_read_model (sku);

-- Menopang query daftar produk yang paling umum: filter status, urut terbaru.
CREATE INDEX ix_prm_status_created ON product_read_model (status, source_created_at DESC);

-- Menopang pencarian nama yang tidak peka huruf besar-kecil (parameter ?q=).
CREATE INDEX ix_prm_name_lower ON product_read_model (LOWER(name));

CREATE INDEX ix_prm_price ON product_read_model (price);
