# spring-cqrs-redis-s3

REST API Spring Boot dengan **jalur tulis dan jalur baca yang benar-benar terpisah** (CQRS),
disinkronkan lewat Kafka, dengan cache Redis di depan database baca dan penyimpanan gambar
di object storage S3-compatible.

> **Status: sedang dibangun.** write-service sudah berjalan dan terverifikasi.
> read-service, cache Redis, dan test suite belum dikerjakan — lihat [TASKS.md](./TASKS.md)
> untuk posisi persisnya (19 dari 43 task).

---

## Bentuk sistemnya

| | write-service | read-service |
|---|---|---|
| Port | 8081 | 8082 |
| Database | `writedb` — source of truth | `readdb` — read model denormalized |
| HTTP | POST / PUT / PATCH / DELETE | GET saja |
| Cache | — | Redis, cache-aside |
| S3 | upload gambar | hanya membentuk URL |
| Kafka | producer (lewat outbox) | consumer (projector) |

**Alur baca:** `Client → read-service → Redis → (kalau miss) → readdb → isi Redis → response`

**Alur tulis:** `Client → write-service → writedb + outbox (satu transaksi) → Kafka → read-service → readdb → invalidasi Redis`

**Alur gambar:** `Client → write-service → S3/MinIO → writedb (simpan object key) → Kafka → read model`

Penjelasan lengkap beserta alasan tiap keputusan ada di **[PLAN.md](./PLAN.md)**.

---

## Tiga hal yang membedakan ini dari implementasi CQRS ala kadarnya

**1. Transactional Outbox, bukan publish langsung.**
Menyimpan ke database dan mengirim ke Kafka adalah dua sistem berbeda tanpa transaksi bersama.
Kalau aplikasi mati di antara keduanya, read model berbeda dari write DB selamanya dan tidak
ada cara otomatis mendeteksinya. Di sini event ditulis ke tabel `outbox_events` dalam transaksi
yang sama dengan perubahan data; pengiriman ke Kafka adalah proses terpisah yang boleh gagal
dan diulang. Sudah diuji: **Kafka dimatikan → POST tetap `201` → Kafka dihidupkan → event
terkirim sendiri tanpa restart.**

**2. Redis fail-open.**
Kalau Redis mati, endpoint baca tetap menjawab `200` lewat database dengan header
`X-Cache: BYPASS`. Implementasi cache-aside yang naif justru menjadikan Redis sebagai
single point of failure — kebalikan dari tujuannya dipasang.

**3. Tipe gambar ditentukan dari isi file.**
`Content-Type` dan ekstensi dikirim klien dan bisa diisi apa saja. Yang diperiksa adalah
magic bytes. File PDF yang dinamai `.png` ditolak dengan `415`.

---

## Menjalankan

Butuh: **JDK 21**, **Maven 3.9+**, **Docker**.

```bash
cp .env.example .env
make up          # postgres, redis, kafka, minio — tunggu sampai semua healthy
make build
make run-write   # write-service di :8081
```

`make help` menampilkan seluruh target yang tersedia.

Swagger UI: <http://localhost:8081/swagger-ui.html>
MinIO console: <http://localhost:9001> (`minioadmin` / `minioadmin`)

### Contoh

```bash
# Buat produk
curl -X POST http://localhost:8081/api/v1/products \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: coba-1' \
  -d '{"sku":"SKU-001","name":"Kopi Gayo 200g","price":85000,"stock":12}'

# Unggah gambar
curl -X POST http://localhost:8081/api/v1/products/<id>/image -F "file=@foto.png"

# Lihat event yang terbit
make consume
```

---

## Catatan

- Kredensial di `.env.example` adalah **default docker lokal**, bukan rahasia. `.env` tidak ikut ter-commit.
- **Belum ada autentikasi** — semua endpoint terbuka. Ini keputusan sadar untuk fase awal, tercatat di `PLAN.md §16`.
- Hasil tulis **tidak langsung** terlihat di read-service (eventual consistency). Response tulis
  membawa header `X-Read-Consistency: eventual` supaya hal ini tidak jadi kejutan.
