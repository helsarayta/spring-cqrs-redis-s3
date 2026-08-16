-- Source of truth produk. Hanya write-service yang menyentuh tabel ini.

CREATE TABLE products
(
    id                 UUID PRIMARY KEY,
    sku                VARCHAR(64)    NOT NULL,
    name               VARCHAR(255)   NOT NULL,
    description        TEXT,
    price              NUMERIC(19, 2) NOT NULL,
    currency           CHAR(3)        NOT NULL DEFAULT 'IDR',
    stock              INTEGER        NOT NULL DEFAULT 0,

    -- Yang disimpan adalah KEY objek di S3, bukan URL.
    -- Endpoint/CDN/bucket boleh berubah tanpa perlu migrasi data.
    image_object_key   VARCHAR(512),
    image_content_type VARCHAR(100),
    image_size_bytes   BIGINT,

    status             VARCHAR(20)    NOT NULL,

    -- Dipakai dua peran sekaligus: optimistic locking Hibernate (@Version) DAN
    -- aggregate_version di event, yang dipakai read-service untuk membuang event basi.
    version            BIGINT         NOT NULL DEFAULT 0,

    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT products_price_non_negative CHECK (price >= 0),
    CONSTRAINT products_stock_non_negative CHECK (stock >= 0),
    CONSTRAINT products_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED'))
);

-- SKU unik untuk SEMUA baris, termasuk yang berstatus DELETED.
-- Konsekuensi yang disengaja: SKU milik produk terhapus tidak bisa dipakai ulang.
-- Untuk katalog, memakai ulang SKU lama hampir selalu kesalahan input, bukan niat.
CREATE UNIQUE INDEX ux_products_sku ON products (sku);

CREATE INDEX ix_products_status_created_at ON products (status, created_at DESC);
