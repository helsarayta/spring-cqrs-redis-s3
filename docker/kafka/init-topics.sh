#!/bin/sh
# Membuat topic secara eksplisit. Auto-create sengaja dimatikan di broker supaya
# jumlah partisi tidak "kebetulan 1" — dengan 1 partisi, throughput consumer tidak bisa
# ditingkatkan sama sekali, dan itu baru ketahuan saat sudah di produksi.
set -eu

BOOTSTRAP="${KAFKA_BOOTSTRAP_INTERNAL:-kafka:9092}"
KT=/opt/kafka/bin/kafka-topics.sh

echo "[kafka-init] menunggu broker di ${BOOTSTRAP}..."
i=0
until "$KT" --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
        echo "[kafka-init] broker tidak merespons setelah 60 percobaan" >&2
        exit 1
    fi
    sleep 2
done

echo "[kafka-init] membuat topic..."

# retention 7 hari: cukup untuk replay read model kalau projector sempat mati semalaman,
# tanpa menyimpan riwayat selamanya.
"$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic product.events.v1 \
    --partitions 3 --replication-factor 1 \
    --config retention.ms=604800000 \
    --config cleanup.policy=delete

# DLT disimpan jauh lebih lama (30 hari): pesan di sini adalah bug yang perlu diselidiki
# manusia, dan kita tidak mau buktinya hilang sebelum sempat dilihat.
"$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic product.events.v1.DLT \
    --partitions 3 --replication-factor 1 \
    --config retention.ms=2592000000

echo "[kafka-init] topic yang tersedia:"
"$KT" --bootstrap-server "$BOOTSTRAP" --list
echo "[kafka-init] selesai."
