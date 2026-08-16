# TASKS — Checklist Eksekusi

Detail tiap task ada di [PLAN.md](./PLAN.md) §11. File ini dipakai untuk tracking saat eksekusi.
`[P]` = bisa paralel di fase yang sama. ⭐ = jalur kritis.

---

## Fase 0 — Fondasi & Infra Lokal ✅
- [x] ⭐ **T-00** Betulkan `JAVA_HOME` (rusak: kurang `/Contents/Home`) → `mvn -v` tampil Java 21
- [x] ⭐ **T-01** Parent `pom.xml` multi-module + pin Spring Boot 3.5.16 + `.gitignore`
- [x] ⭐ **T-02** `docker-compose.yml`: postgres (2 DB), redis, kafka KRaft, minio + init, kafka-ui
- [x] **T-03** `Makefile` + `.env.example`
- [x] **T-04** Verifikasi infra: psql × 2 DB, `redis-cli PING`, MinIO health, topic Kafka (3 partisi), Kafka terjangkau dari host

## Fase 1 — Module `common` ✅
- [x] ⭐ **T-05** `EventEnvelope`, `EventType`, `Topics`, `ProductPayload` — 4 test round-trip hijau
- [x] **T-06** `ApiError`, `ErrorCode`, `ApiException`, konfigurasi Jackson bersama, `TraceIdFilter`

## Fase 2 — Write Service (:8081) ✅
- [x] **T-07** Bootstrap app + profile + actuator + springdoc
- [x] ⭐ **T-08** Flyway `writedb`: products, outbox_events, idempotency_keys
- [x] **T-09** Entity `Product` (`@Version`) + repository + `OutboxEvent`
- [x] ⭐ **T-10** `ProductWriteService` — DB + outbox dalam **satu transaksi**
- [x] **T-11** Command controller + validation + `@RestControllerAdvice`
- [x] `[P]` **T-12** Dukungan `Idempotency-Key` pada POST (`IdempotencyService` + `IdempotencyStore`)
- [x] ⭐ **T-13** `OutboxPublisher` scheduled (`SKIP LOCKED`, batch, backoff eksponensial, metrik)
- [x] `[P]` **T-14** Kafka producer (acks=all, idempotent, key = productId) + `KafkaTopicConfig`

## Fase 3 — Image / S3 ✅
- [x] **T-15** `S3Config` (SDK v2, endpointOverride, path-style, presigner, auto-create bucket)
- [x] **T-16** `ImageValidator` + `ImageStorageService`: ukuran / content-type / **magic bytes**
- [x] **T-17** `POST|DELETE /products/{id}/image` + urutan & kompensasi anti-orphan
- [x] `[P]` **T-18** `ImageUrlResolver` mode `PUBLIC`/`PRESIGNED` + event `PRODUCT_IMAGE_UPDATED`

## Fase 4 — Read Service (:8082) ✅
- [x] **T-19** Bootstrap app (port 8082, profile, actuator, springdoc)
- [x] **T-20** Flyway `readdb`: product_read_model, processed_events + index
- [x] ⭐ **T-21** Consumer + projector: upsert, dedup `event_id`, guard `aggregateVersion`
- [x] `[P]` **T-22** `DefaultErrorHandler` + backoff eksponensial + DLT + DLT listener
- [x] **T-23** Redis via Lettuce (timeout 200 ms, pool, JSON terbaca manusia, key ber-versi)
- [x] ⭐ **T-24** **`ProductQueryService` cache-aside** (HIT/MISS/isi-ulang/negative cache/TTL+jitter)
- [x] ⭐ **T-25** **Fail-open saat Redis mati** (`X-Cache: BYPASS`, bukan 500)
- [x] ⭐ **T-26** Invalidasi cache dari projector via `afterCommit`
- [x] `[P]` **T-27** Query controller: by-id, by-sku (key penunjuk), list + paging + filter
- [x] `[P]` **T-28** Single-flight lock anti-stampede (`SET NX PX`)
- [x] `[P]` **T-29** Metrik cache hit/miss/negative/bypass + endpoint admin evict

## Fase 5 — Cross-cutting ✅
- [x] **T-30** Correlation id: HTTP → MDC → header Kafka → consumer → response — **ditemukan bocor, diperbaiki**
- [x] `[P]` **T-31** Health indicator kustom (`outbox`, `readCache`) + log konfigurasi efektif saat startup
- [x] `[P]` **T-32** Eksternalisasi config & secret; rahasia disamarkan di log
- [x] `[P]` **T-33** Rate limit (opsional, mati default) + CORS (mati default) + graceful shutdown

## Fase 6 — Testing ✅
- [x] **T-34** Unit test: `ImageValidatorTest`, `ProductWriteServiceTest`, `ProductQueryServiceTest`, `EventEnvelopeSerializationTest`
- [x] **T-35** `WriteServiceIT` (Testcontainers: Postgres + Kafka + MinIO)
- [x] **T-36** `ReadServiceIT` (Testcontainers: Postgres + Redis + Kafka)
- [x] ⭐ **T-37** **E2E**: `scripts/smoke-test.sh` — POST → event → projection → GET(MISS) → GET(HIT) → gambar
- [x] `[P]` **T-38** Test resiliensi: Redis di-*pause* di dalam IT; Kafka & S3 diuji manual

## Fase 7 — Delivery ✅
- [x] **T-39** `Dockerfile` multi-stage × 2 (non-root, healthcheck, heap mengikuti batas container)
- [x] **T-40** `docker-compose.app.yml` — seluruh stack sekali `make up-all`
- [x] **T-41** `README.md` lengkap
- [x] `[P]` **T-42** `requests.http` + `scripts/smoke-test.sh`

---

## Checkpoint Lapor Balik
Saya akan berhenti dan lapor di 3 titik ini (kecuali Anda minta lain):
1. **Setelah Fase 2 + 3** — write-service jalan, data masuk DB & S3, event terbit ke Kafka
2. **Setelah Fase 4** — read-service jalan, cache Redis terbukti HIT/MISS, sinkron dari Kafka
3. **Setelah Fase 7** — selesai, semua item Definition of Done hijau

## Progres
`43 / 43 selesai` — **CHECKPOINT 3 tercapai. Seluruh rencana tuntas.**

### Bukti verifikasi Checkpoint 3 (cross-cutting, test, delivery)
| Yang diuji | Hasil |
|---|---|
| **Trace id lintas service** | sebelum diperbaiki: tercatat 5× di write-service, **0× di read-service**. Sesudah: satu trace id yang sama muncul di kedua log |
| Health `outbox` (write) | `UP` dengan detail `pending`/`failed`; hanya `DOWN` kalau database tidak terbaca |
| Health `readCache` (read) | `UP` saat Redis hidup; **tetap `UP`** saat Redis mati, dengan detail "TIDAK terjangkau" |
| Log konfigurasi saat startup | tampil di kedua service; `s3 access key` tersamar jadi `min*******` |
| Unit test | 28 test hijau (`common` 4, write 16, read 8) |
| `WriteServiceIT` (Testcontainers) | 8 test hijau — Postgres + Kafka + MinIO sungguhan |
| `ReadServiceIT` (Testcontainers) | 8 test hijau — termasuk Redis di-*pause* di tengah test |
| **Total test otomatis** | **44 hijau**, `mvn verify` BUILD SUCCESS |
| `scripts/smoke-test.sh` | 11/11 pemeriksaan lulus end-to-end; sinkronisasi ~3 detik |
| `docker compose build` | kedua image ter-build dari akar repo |

### Bug yang ditemukan dan diperbaiki selama fase ini
| Bug | Dampak kalau tidak ketahuan |
|---|---|
| `OutboxPublisher` tidak menyalin header `trace-id` ke pesan Kafka | Penelusuran log terputus tepat di batas antar service — fiturnya ada di kode tapi tidak pernah bekerja |
| Presigned URL ditandatangani untuk host internal `minio:9000` | Semua URL gambar gagal dibuka begitu aplikasi dijalankan sebagai container, padahal berkasnya baik-baik saja |
| Bean `CacheHealthIndicator` bernama sama dengan `ProductCache` | read-service gagal start sama sekali |
| Header offset asal di log DLT dibaca sebagai teks | Nilainya tampak kosong di log, menyulitkan penelusuran pesan yang gagal |
| Key cache daftar pakai CRC32 (32-bit) | Dua kombinasi filter yang bertabrakan membuat klien menerima **daftar milik query lain**, dijawab 200 tanpa error |
| Test memeriksa Redis lewat `RedisTemplate` aplikasi (timeout 200 ms) | `mvn clean verify` gagal saat mesin sibuk. Yang rusak perkakas testnya, tapi laporannya terbaca seolah aplikasi yang bermasalah — sumber "flaky test" yang menyesatkan |

### Bukti verifikasi Checkpoint 2 (read-service + cache)

### Bukti verifikasi Checkpoint 2 (read-service + cache)
| Yang diuji | Hasil |
|---|---|
| Read model dibangun dari nol | read-service memutar ulang seluruh topic saat start, 4 event terproyeksi |
| `GET` pertama setelah `FLUSHALL` | `X-Cache: MISS`, key `product:v1:{id}` + `product:sku:v1:{sku}` muncul di Redis |
| `GET` kedua & ketiga | `X-Cache: HIT` |
| TTL key | `619` detik = 600 dasar + 19 jitter (jitter aktif) |
| `GET` id tidak ada, pertama | `404` + `X-Cache: MISS`, key penanda ` ABSENT` TTL `30` |
| `GET` id tidak ada, kedua | `404` + `X-Cache: NEGATIVE_HIT` — **database tidak disentuh** |
| **Redis di-stop, 3× GET** | ketiganya `200` + `X-Cache: BYPASS`, bukan `500` |
| Daftar produk saat Redis mati | `200` dalam 0,53 detik |
| `/actuator/health` saat Redis mati | tetap `UP` (Redis sengaja dikecualikan dari health) |
| Log saat Redis mati | 1 baris WARN, bukan banjir — throttle 10 detik bekerja |
| Redis dihidupkan lagi | pulih sendiri: `MISS` lalu `HIT`, tanpa restart aplikasi |
| Ubah nama+harga via `:8081` | tersinkron ke `:8082` dalam ~4 detik, cache ter-invalidasi lalu terisi ulang |
| **Event basi** (versi 0 vs tersimpan 3) | ditolak, read model tidak berubah, tercatat "sudah usang" di log |
| **Event duplikat** (`eventId` sama, versi 99) | ditolak oleh dedup, read model tidak berubah |
| **JSON rusak** | masuk DLT tanpa retry, **lag semua partisi tetap 0** (antrean tidak tertahan) |
| Metrik cache | `hit`/`miss`/`negative_hit`/`bypass` tercatat benar di `/actuator/metrics` |
| Daftar + filter `q`/`minPrice` | benar; presigned image URL ikut terbentuk di hasil daftar |
| `by-sku` | `MISS` lalu `HIT` lewat key penunjuk, produk hanya disimpan sekali di cache |

### Bukti verifikasi Checkpoint 1
| Yang diuji | Hasil |
|---|---|
| Flyway `writedb` dari nol | 3 migrasi jalan, `ddl-auto: validate` lolos |
| `POST /api/v1/products` | `201`, baris masuk `products`, event masuk `outbox_events` |
| Outbox → Kafka | event terbit ke `product.events.v1`, key = productId, `traceId` terbawa |
| `Idempotency-Key` diulang | 1 baris di DB, response identik, `Idempotent-Replay: true` |
| Upload PNG asli | `200`, objek ada di MinIO, presigned URL bisa diunduh (`200`) |
| Upload PDF menyamar `.png` | `415 UNSUPPORTED_IMAGE_TYPE` — ditolak oleh cek magic bytes |
| Bucket private | akses tanpa tanda tangan → `403`; dengan presigned URL → `200` |
| Ganti gambar | objek lama terhapus, bucket hanya menyisakan objek terbaru |
| **Kafka dimatikan lalu POST** | tetap `201`, produk masuk DB, outbox `PENDING` |
| **Kafka dihidupkan lagi** | outbox terkirim sendiri tanpa restart aplikasi, `PENDING` → 0 |
