#!/bin/sh
# Membuat bucket dan menyetel policy-nya mengikuti S3_URL_MODE.
set -eu

BUCKET="${S3_BUCKET:-product-images}"
MODE="${S3_URL_MODE:-PRESIGNED}"

echo "[minio-init] menyambung ke MinIO..."
mc alias set local http://minio:9000 "${S3_ACCESS_KEY:-minioadmin}" "${S3_SECRET_KEY:-minioadmin}"

mc mb --ignore-existing "local/${BUCKET}"

if [ "$MODE" = "PUBLIC" ]; then
    # Mode PUBLIC: siapa pun yang tahu URL bisa mengunduh objek. Dipilih sadar lewat config.
    echo "[minio-init] mode PUBLIC -> anonymous download diizinkan"
    mc anonymous set download "local/${BUCKET}"
else
    # Mode PRESIGNED (default): bucket tertutup, akses hanya lewat URL bertanda tangan.
    echo "[minio-init] mode PRESIGNED -> bucket private"
    mc anonymous set none "local/${BUCKET}"
fi

echo "[minio-init] isi bucket sekarang:"
mc ls local
echo "[minio-init] selesai."
