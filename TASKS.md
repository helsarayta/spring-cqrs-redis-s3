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

## Fase 4 — Read Service (:8082)
- [ ] **T-19** Bootstrap app
- [ ] **T-20** Flyway `readdb`: product_read_model, processed_events + index
- [ ] ⭐ **T-21** Consumer + projector: upsert, dedup `event_id`, guard `aggregateVersion`
- [ ] `[P]` **T-22** `DefaultErrorHandler` + backoff + DLT + DLT listener
- [ ] **T-23** `RedisConfig` (Lettuce, timeout 200ms, serializer JSON, prefix)
- [ ] ⭐ **T-24** **`ProductQueryService` cache-aside** (HIT/MISS/isi-ulang/negative cache/TTL+jitter)
- [ ] ⭐ **T-25** **Fail-open saat Redis mati** (`X-Cache: BYPASS`, bukan 500)
- [ ] ⭐ **T-26** Invalidasi cache dari projector via `afterCommit`
- [ ] `[P]` **T-27** Query controller: by-id, by-sku, list + paging + filter
- [ ] `[P]` **T-28** Single-flight lock anti-stampede
- [ ] `[P]` **T-29** Metrik cache hit/miss/bypass + admin evict

## Fase 5 — Cross-cutting
- [ ] **T-30** Correlation id: HTTP → MDC → header Kafka → consumer → response
- [ ] `[P]` **T-31** Health indicator kustom + logging terstruktur
- [ ] `[P]` **T-32** Eksternalisasi config & secret
- [ ] `[P]` **T-33** Rate limit sederhana + CORS + graceful shutdown

## Fase 6 — Testing
- [ ] **T-34** Unit test service layer (Mockito)
- [ ] **T-35** IT write-service (Testcontainers: Postgres + Kafka + MinIO)
- [ ] **T-36** IT read-service (Testcontainers: Postgres + Redis + Kafka)
- [ ] ⭐ **T-37** **E2E**: POST → event → projection → GET(MISS) → GET(HIT) → upload image → GET
- [ ] `[P]` **T-38** Test resiliensi: Redis / Kafka / S3 mati

## Fase 7 — Delivery
- [ ] **T-39** `Dockerfile` multi-stage + layered jar × 2
- [ ] **T-40** `docker-compose.app.yml` — full stack sekali `up`
- [ ] **T-41** `README.md` lengkap
- [ ] `[P]` **T-42** File `.http` + `scripts/smoke-test.sh`

---

## Checkpoint Lapor Balik
Saya akan berhenti dan lapor di 3 titik ini (kecuali Anda minta lain):
1. **Setelah Fase 2 + 3** — write-service jalan, data masuk DB & S3, event terbit ke Kafka
2. **Setelah Fase 4** — read-service jalan, cache Redis terbukti HIT/MISS, sinkron dari Kafka
3. **Setelah Fase 7** — selesai, semua item Definition of Done hijau

## Progres
`19 / 43 selesai` — **CHECKPOINT 1 tercapai.** Fase 0–3 tuntas dan terverifikasi berjalan.

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
