# spring-cqrs-redis-s3

REST API Spring Boot dengan **jalur tulis dan jalur baca yang benar-benar terpisah** (CQRS),
disinkronkan lewat Kafka, dengan cache Redis di depan database baca dan penyimpanan gambar
di object storage S3-compatible.

---

## Bentuk sistemnya

| | write-service | read-service |
|---|---|---|
| Port | 8081 | 8082 |
| Database | `writedb` — source of truth | `readdb` — read model denormalized |
| HTTP | POST / PUT / PATCH / DELETE | GET saja |
| Cache | — | Redis, cache-aside |
| S3 | unggah gambar | hanya membentuk URL |
| Kafka | producer (lewat outbox) | consumer (projector) |

```
                    ┌──────────────────┐                        ┌─────────────────┐
   POST/PUT/DELETE  │  write-service   │      product.events    │  read-service   │  GET
   ───────────────► │      :8081       │ ─────────────────────► │     :8082       │ ◄──────
                    └────────┬─────────┘        (Kafka)         └────┬───────┬────┘
                             │                                       │       │
                    ┌────────┴────────┐                        ┌─────┴──┐  ┌─┴──────┐
                    │ writedb         │                        │ Redis  │  │ readdb │
                    │ + outbox_events │                        └────────┘  └────────┘
                    └────────┬────────┘                         dicek dulu   kalau cache
                             │                                               tidak tahu
                    ┌────────┴────────┐
                    │  S3 / MinIO     │
                    └─────────────────┘
```

**Alur baca:** `Client → read-service → Redis → (kalau tidak ada) → readdb → isi Redis → response`

**Alur tulis:** `Client → write-service → writedb + outbox (satu transaksi) → Kafka → read-service → readdb → buang cache`

**Alur gambar:** `Client → write-service → S3/MinIO → writedb (simpan object key) → Kafka → read model`

Rancangan lengkap beserta alasan tiap keputusan ada di **[PLAN.md](./PLAN.md)**;
posisi pengerjaan dan bukti pengujian tiap perilaku ada di **[TASKS.md](./TASKS.md)**.

---

## Empat hal yang membedakan ini dari implementasi CQRS ala kadarnya

**1. Transactional Outbox, bukan publish langsung.**
Menyimpan ke database dan mengirim ke Kafka adalah dua sistem berbeda tanpa transaksi
bersama. Kalau aplikasi mati di antara keduanya, read model berbeda dari write DB selamanya
dan tidak ada cara otomatis mendeteksinya. Di sini event ditulis ke tabel `outbox_events`
dalam transaksi yang sama dengan perubahan data; pengiriman ke Kafka jadi proses terpisah
yang boleh gagal dan diulang.

**2. Redis fail-open.**
Kalau Redis mati, endpoint baca tetap menjawab `200` lewat database dengan header
`X-Cache: BYPASS`, dan `/actuator/health` tetap `UP`. Cache-aside yang ditulis tanpa ini
justru menjadikan Redis titik kegagalan tunggal — persis kebalikan dari alasan ia dipasang.

**3. Event duplikat dan event basi ditolak.**
Kafka menjamin pengiriman *at-least-once*, dan event bisa datang tidak berurutan. Ada dua
penjagaan: deduplikasi lewat tabel `processed_events`, dan penolakan event yang versinya
lebih tua dari data tersimpan. Tanpa yang kedua, data lama menimpa data baru **tanpa satu
pun error yang menandainya**.

**4. Tipe gambar ditentukan dari isi berkas.**
`Content-Type` dan ekstensi dikirim klien dan bisa diisi apa saja. Yang diperiksa adalah
magic bytes; berkas PDF yang dinamai `.png` ditolak dengan `415`.

---

## Menjalankan

Butuh **JDK 21**, **Maven 3.9+**, dan **Docker**.

```bash
cp .env.example .env
make up          # postgres, redis, kafka, minio — tunggu sampai semua healthy
make build
make run-write   # write-service di :8081  (terminal 1)
make run-read    # read-service  di :8082  (terminal 2)
```

Atau jalankan semuanya sebagai container sekaligus:

```bash
make up-all
```

`make help` menampilkan seluruh target yang tersedia.

| | |
|---|---|
| Swagger write-service | <http://localhost:8081/swagger-ui.html> |
| Swagger read-service | <http://localhost:8082/swagger-ui.html> |
| MinIO console | <http://localhost:9001> (`minioadmin` / `minioadmin`) |
| Kafka UI | `make tools` lalu <http://localhost:8090> |

### Coba cepat

```bash
# Buat produk
curl -X POST http://localhost:8081/api/v1/products \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: coba-1' \
  -d '{"sku":"SKU-001","name":"Kopi Gayo 200g","price":85000,"stock":12}'

# Unggah gambar
curl -X POST http://localhost:8081/api/v1/products/<id>/image -F "file=@foto.png"

# Baca — perhatikan header X-Cache
curl -i http://localhost:8082/api/v1/products/<id>   # X-Cache: MISS
curl -i http://localhost:8082/api/v1/products/<id>   # X-Cache: HIT
```

Koleksi request yang lebih lengkap ada di [`requests.http`](./requests.http).
Untuk memeriksa seluruh alur sekaligus: `make smoke`.

---

## Membuktikan sendiri klaim di atas

```bash
# Redis mati -> tetap 200, X-Cache: BYPASS, health tetap UP
docker compose stop redis
curl -i http://localhost:8082/api/v1/products/<id>
curl -s http://localhost:8082/actuator/health
docker compose start redis

# Kafka mati -> tulis tetap 201, event menyusul sendiri saat Kafka hidup lagi
docker compose stop kafka
curl -X POST http://localhost:8081/api/v1/products -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-002","name":"Uji","price":1000,"stock":1}'
make psql-write   # select status, count(*) from outbox_events group by status;  -> ada PENDING
docker compose start kafka
```

Semua perilaku ini juga terkunci sebagai test otomatis — lihat bagian berikutnya.

---

## Test

```bash
make test     # unit test saja, cepat, tanpa Docker
make verify   # unit + integration test (Testcontainers: Postgres, Kafka, Redis, MinIO)
```

Integration test menjalankan container sungguhan, termasuk skenario yang paling sulit
diyakinkan lewat kode saja: Redis di-*pause* di tengah test untuk memastikan pembacaan tetap
dilayani, dan event basi serta event duplikat dikirim langsung ke Kafka untuk memastikan
read model tidak rusak karenanya.

---

## Yang perlu Anda ketahui sebelum memakai ini

- **Belum ada autentikasi.** Semua endpoint terbuka, termasuk `/api/v1/admin/cache/**` di
  read-service. Ini keputusan sadar untuk fase awal (tercatat di `PLAN.md §16`), bukan
  kelalaian — tapi wajib ditangani sebelum menghadap ke luar.
- **Hasil tulis tidak langsung terlihat di read-service.** Biasanya di bawah beberapa detik.
  Response tulis membawa header `X-Read-Consistency: eventual` supaya ini tidak jadi kejutan.
- **Cache daftar (`GET /products`) tidak di-invalidasi** saat ada perubahan, hanya
  mengandalkan TTL pendek (default 60 detik). Melacak kombinasi filter dan halaman mana yang
  terdampak satu perubahan biayanya jauh melebihi manfaatnya. Pembacaan per-id selalu mutakhir.
- **SKU produk yang sudah dihapus tidak bisa dipakai ulang.** Unique index berlaku juga untuk
  baris berstatus `DELETED`.
- **Rate limit yang tersedia bersifat per-instance** dan dimatikan secara default. Baca
  `RateLimitFilter` sebelum menyalakannya.
- Kredensial di `.env.example` adalah **default docker lokal**, bukan rahasia. `.env` tidak
  ikut ter-commit.

---

## Struktur

```
├── common/          kontrak bersama: event, model error, trace id, pembentuk URL gambar
├── write-service/   command side  — writedb, outbox, S3, producer Kafka
├── read-service/    query side    — readdb, Redis, consumer Kafka
├── docker/          skrip inisialisasi Postgres, Kafka, MinIO
├── scripts/         smoke test end-to-end
├── PLAN.md          rancangan, keputusan arsitektur, risiko, penyimpangan saat eksekusi
└── TASKS.md         daftar task dan bukti pengujian tiap perilaku
```
