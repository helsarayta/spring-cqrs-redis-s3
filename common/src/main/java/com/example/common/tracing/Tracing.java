package com.example.common.tracing;

/** Nama-nama yang dipakai untuk menyambung satu request melintasi kedua service. */
public final class Tracing {

    private Tracing() {
    }

    /** Header HTTP masuk/keluar. Kalau klien mengirimnya, nilai itu yang dipakai. */
    public static final String HEADER = "X-Trace-Id";

    /** Kunci di MDC, dirujuk dari pola logging di application.yml. */
    public static final String MDC_KEY = "traceId";
}
