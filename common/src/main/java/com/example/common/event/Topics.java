package com.example.common.event;

/** Nama topic dan header Kafka yang dipakai bersama producer &amp; consumer. */
public final class Topics {

    private Topics() {
    }

    /**
     * Topic utama event produk.
     *
     * <p>Message key = {@code aggregateId} (UUID produk). Ini yang menjamin semua event
     * untuk satu produk mendarat di partisi yang sama, sehingga urutannya terjaga
     * <i>per produk</i> — yang memang satu-satunya urutan yang kita butuhkan.
     * Urutan global antar produk tidak dijamin dan memang tidak diperlukan.
     */
    public static final String PRODUCT_EVENTS = "product.events.v1";

    /** Dead letter topic. Pesan yang gagal diproses setelah semua retry mendarat di sini. */
    public static final String PRODUCT_EVENTS_DLT = "product.events.v1.DLT";

    /** Consumer group read-service. */
    public static final String READ_MODEL_GROUP = "read-model-projector";

    /** Header Kafka: dipakai untuk routing/observability tanpa perlu deserialize body. */
    public static final class Headers {

        private Headers() {
        }

        public static final String EVENT_ID = "event-id";
        public static final String EVENT_TYPE = "event-type";
        public static final String AGGREGATE_ID = "aggregate-id";
        public static final String AGGREGATE_VERSION = "aggregate-version";
        public static final String TRACE_ID = "trace-id";
    }
}
