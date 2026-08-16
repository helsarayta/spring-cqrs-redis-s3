#!/usr/bin/env bash
# Smoke test end-to-end.
#
# Menjalankan satu putaran penuh melalui kedua service dan memeriksa perilaku yang menjadi
# alasan arsitektur ini dipilih — bukan sekadar "endpoint-nya menjawab 200".
#
# Syarat: infra menyala (make up) dan kedua service berjalan.
#   ./scripts/smoke-test.sh

set -uo pipefail

WRITE_URL="${WRITE_URL:-http://localhost:8081}"
READ_URL="${READ_URL:-http://localhost:8082}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; RESET=$'\033[0m'
PASSED=0
FAILED=0

check() {
    local label="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        printf '  %s✓%s %-52s %s\n' "$GREEN" "$RESET" "$label" "$actual"
        PASSED=$((PASSED + 1))
    else
        printf '  %s✗%s %-52s diharapkan=%s didapat=%s\n' "$RED" "$RESET" "$label" "$expected" "$actual"
        FAILED=$((FAILED + 1))
    fi
}

section() { printf '\n%s%s%s\n' "$YELLOW" "$1" "$RESET"; }

require_service() {
    local name="$1" url="$2"
    if ! curl -sf -m 5 "$url/actuator/health" > /dev/null 2>&1; then
        printf '%sTidak bisa menghubungi %s di %s.%s\n' "$RED" "$name" "$url" "$RESET"
        printf 'Jalankan dulu: make up && make run-write (dan make run-read di terminal lain)\n'
        exit 1
    fi
}

require_service "write-service" "$WRITE_URL"
require_service "read-service" "$READ_URL"

SKU="SMOKE-$(date +%s)"
IDEM="smoke-$(date +%s)"

section "1. Tulis ke write-service"

# Nama dibuat unik per-eksekusi. Kalau namanya tetap, pencarian di langkah terakhir akan
# menemukan produk sisa eksekusi sebelumnya dan hasilnya bergantung pada riwayat database.
PRODUCT_NAME="Kopi Uji Asap $SKU"
CREATE_BODY=$(printf '{"sku":"%s","name":"%s","description":"produk smoke test","price":12345.00,"currency":"IDR","stock":7}' "$SKU" "$PRODUCT_NAME")

RESPONSE=$(curl -s -w '\n%{http_code}' -X POST "$WRITE_URL/api/v1/products" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM" -d "$CREATE_BODY")
STATUS=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
check "POST /products" "201" "$STATUS"

PRODUCT_ID=$(echo "$BODY" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
printf '  %sid produk: %s%s\n' "$DIM" "$PRODUCT_ID" "$RESET"

REPLAY=$(curl -s -o /dev/null -D - -X POST "$WRITE_URL/api/v1/products" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM" -d "$CREATE_BODY" \
    | grep -i '^Idempotent-Replay' | tr -d '\r' | awk '{print $2}')
check "POST ulang dengan Idempotency-Key sama diputar ulang" "true" "$REPLAY"

section "2. Unggah gambar ke object storage"

TMP_PNG=$(mktemp -t smoke).png
printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' \
    | base64 -d > "$TMP_PNG" 2>/dev/null || printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' | base64 -D > "$TMP_PNG"

UPLOAD_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$WRITE_URL/api/v1/products/$PRODUCT_ID/image" -F "file=@$TMP_PNG;type=image/png")
check "POST gambar PNG asli" "200" "$UPLOAD_STATUS"

TMP_FAKE=$(mktemp -t smokefake).png
printf '%%PDF-1.4 ini bukan gambar' > "$TMP_FAKE"
FAKE_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$WRITE_URL/api/v1/products/$PRODUCT_ID/image" -F "file=@$TMP_FAKE;type=image/png")
# Tipe ditentukan dari isi berkas, bukan dari apa yang diklaim klien.
check "PDF menyamar sebagai .png ditolak" "415" "$FAKE_STATUS"

rm -f "$TMP_PNG" "$TMP_FAKE"

section "3. Menunggu event menyeberang ke read-service"

SYNCED=""
for i in $(seq 1 30); do
    if curl -sf -m 5 "$READ_URL/api/v1/products/$PRODUCT_ID" > /dev/null 2>&1; then
        SYNCED="ya"
        printf '  %stersinkron setelah ~%s detik%s\n' "$DIM" "$i" "$RESET"
        break
    fi
    sleep 1
done
check "produk muncul di read-service" "ya" "${SYNCED:-tidak}"

if [ -z "$SYNCED" ]; then
    printf '\n%sProduk tidak pernah sampai ke read model. Periksa apakah read-service berjalan\n' "$RED"
    printf 'dan apakah outbox terkirim: make psql-write lalu "select status, count(*) from outbox_events group by status;"%s\n' "$RESET"
    exit 1
fi

section "4. Alur cache: Redis dulu, database kemudian"

curl -s -o /dev/null "$READ_URL/api/v1/admin/cache/products/$PRODUCT_ID" -X DELETE

FIRST=$(curl -s -o /dev/null -D - "$READ_URL/api/v1/products/$PRODUCT_ID" | grep -i '^X-Cache' | tr -d '\r' | awk '{print $2}')
SECOND=$(curl -s -o /dev/null -D - "$READ_URL/api/v1/products/$PRODUCT_ID" | grep -i '^X-Cache' | tr -d '\r' | awk '{print $2}')
check "pembacaan pertama mengambil dari database" "MISS" "$FIRST"
check "pembacaan kedua dijawab cache" "HIT" "$SECOND"

GHOST="00000000-0000-0000-0000-0000000$(printf '%05d' $((RANDOM % 99999)))"
curl -s -o /dev/null "$READ_URL/api/v1/products/$GHOST"
NEG=$(curl -s -o /dev/null -D - "$READ_URL/api/v1/products/$GHOST" | grep -i '^X-Cache' | tr -d '\r' | awk '{print $2}')
check "id tidak dikenal dijawab dari negative cache" "NEGATIVE_HIT" "$NEG"

section "5. Gambar terbaca dari sisi baca"

IMAGE_URL=$(curl -s "$READ_URL/api/v1/products/$PRODUCT_ID" | sed -n 's/.*"imageUrl":"\([^"]*\)".*/\1/p')
if [ -n "$IMAGE_URL" ]; then
    IMG_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$IMAGE_URL")
    check "URL gambar bisa diunduh" "200" "$IMG_STATUS"
else
    check "URL gambar ada di response" "ada" "kosong"
fi

section "6. Daftar dan pencarian"

QUERY=$(printf '%s' "$SKU")
TOTAL=$(curl -s --get --data-urlencode "q=$QUERY" "$READ_URL/api/v1/products" \
    | sed -n 's/.*"totalElements":\([0-9]*\).*/\1/p')
check "pencarian nama menemukan tepat produk ini" "1" "${TOTAL:-0}"

BY_SKU=$(curl -s -o /dev/null -w '%{http_code}' "$READ_URL/api/v1/products/by-sku/$SKU")
check "pencarian berdasarkan SKU" "200" "$BY_SKU"

section "Hasil"
printf '  lulus: %s%d%s   gagal: %s%d%s\n' "$GREEN" "$PASSED" "$RESET" \
    "$([ "$FAILED" -eq 0 ] && echo "$GREEN" || echo "$RED")" "$FAILED" "$RESET"

[ "$FAILED" -eq 0 ] || exit 1
printf '\n%sSemua pemeriksaan lulus.%s\n' "$GREEN" "$RESET"
