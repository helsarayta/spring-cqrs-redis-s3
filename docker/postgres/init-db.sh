#!/bin/bash
# Dijalankan sekali saat volume Postgres masih kosong.
# writedb sudah dibuat oleh image lewat POSTGRES_DB; di sini kita tambahkan readdb.
#
# Kedua database sengaja terpisah (bukan dua schema) supaya pemisahan write/read
# tegas: read-service tidak punya jalan untuk menyentuh tabel source-of-truth.
set -euo pipefail

READ_DB="${READ_DB_NAME:-readdb}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    SELECT 'CREATE DATABASE ${READ_DB}'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${READ_DB}')\gexec

    GRANT ALL PRIVILEGES ON DATABASE ${READ_DB} TO ${POSTGRES_USER};
EOSQL

echo "[init-db] databases siap: ${POSTGRES_DB} (write), ${READ_DB} (read)"
