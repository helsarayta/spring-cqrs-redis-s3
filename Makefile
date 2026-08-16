# Makefile — shortcut development.

SHELL := /bin/bash

# JAVA_HOME dicari otomatis, dengan urutan: nilai JDK21_HOME yang Anda set > java_home -v 21
# (macOS) > JAVA_HOME yang sudah ada. Override kapan saja: `make build JDK21_HOME=/path/ke/jdk21`.
#
# Catatan: di macOS, /usr/libexec/java_home adalah cara yang benar untuk menemukan JDK.
# Menebak path secara manual mudah salah — mis. lupa akhiran /Contents/Home, yang membuat
# Maven gagal dengan pesan "JAVA_HOME is not defined correctly".
JDK21_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo "$$JAVA_HOME")

# Pakai ./mvnw kalau ada, kalau tidak mvn dari PATH.
MVN_BIN := $(shell if [ -x ./mvnw ]; then echo "./mvnw"; else echo "mvn"; fi)
MVN     := JAVA_HOME=$(JDK21_HOME) $(MVN_BIN)

# Docker Compose v2 (`docker compose`) kalau ada, kalau tidak jatuh ke v1 (`docker-compose`).
COMPOSE := $(shell if docker compose version >/dev/null 2>&1; then echo "docker compose"; else echo "docker-compose"; fi)

.DEFAULT_GOAL := help

.PHONY: help
help: ## Tampilkan daftar target
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- infra

.PHONY: up
up: ## Nyalakan infra (postgres, redis, kafka, minio) dan tunggu sampai healthy
	$(COMPOSE) up -d postgres redis kafka minio
	@$(MAKE) --no-print-directory wait
	$(COMPOSE) up kafka-init minio-init
	@echo ""
	@echo "Infra siap:"
	@echo "  Postgres      localhost:5432   (writedb, readdb)"
	@echo "  Redis         localhost:6379"
	@echo "  Kafka         localhost:29092"
	@echo "  MinIO API     http://localhost:9000"
	@echo "  MinIO Console http://localhost:9001  (minioadmin / minioadmin)"

.PHONY: up-all
up-all: ## Nyalakan SEMUANYA termasuk kedua aplikasi sebagai container
	$(COMPOSE) -f docker-compose.yml -f docker-compose.app.yml up -d --build
	@echo ""
	@echo "  write-service http://localhost:8081/swagger-ui.html"
	@echo "  read-service  http://localhost:8082/swagger-ui.html"

.PHONY: down-all
down-all: ## Matikan semuanya termasuk aplikasi
	$(COMPOSE) -f docker-compose.yml -f docker-compose.app.yml --profile tools down

.PHONY: tools
tools: ## Nyalakan kafka-ui di http://localhost:8090
	$(COMPOSE) --profile tools up -d kafka-ui

.PHONY: wait
wait: ## Tunggu semua container infra berstatus healthy
	@echo "Menunggu container healthy..."
	@for i in $$(seq 1 60); do \
		unhealthy=$$($(COMPOSE) ps --format '{{.Name}} {{.Health}}' 2>/dev/null \
			| grep -E 'pc-(postgres|redis|kafka|minio) ' | grep -v 'healthy' || true); \
		if [ -z "$$unhealthy" ]; then echo "Semua healthy."; exit 0; fi; \
		sleep 2; \
	done; \
	echo "TIMEOUT menunggu healthy. Status sekarang:"; $(COMPOSE) ps; exit 1

.PHONY: down
down: ## Matikan semua container (data tetap ada)
	$(COMPOSE) --profile tools down

.PHONY: clean
clean: ## Matikan container DAN hapus volume (semua data hilang)
	$(COMPOSE) --profile tools down -v

.PHONY: ps
ps: ## Status container
	$(COMPOSE) ps

.PHONY: logs
logs: ## Ikuti log infra
	$(COMPOSE) logs -f

# ---------------------------------------------------------------- build & run

.PHONY: build
build: ## Compile + package semua module (tanpa test)
	$(MVN) -B clean install -DskipTests

.PHONY: test
test: ## Unit test saja
	$(MVN) -B test

.PHONY: verify
verify: ## Unit + integration test (butuh Docker jalan untuk Testcontainers)
	$(MVN) -B clean verify

.PHONY: run-write
run-write: ## Jalankan write-service di :8081
	$(MVN) -B -pl write-service -am spring-boot:run -Dspring-boot.run.profiles=local

.PHONY: run-read
run-read: ## Jalankan read-service di :8082
	$(MVN) -B -pl read-service -am spring-boot:run -Dspring-boot.run.profiles=local

# ---------------------------------------------------------------- utilitas

.PHONY: psql-write
psql-write: ## Buka psql ke writedb
	docker exec -it pc-postgres psql -U appuser -d writedb

.PHONY: psql-read
psql-read: ## Buka psql ke readdb
	docker exec -it pc-postgres psql -U appuser -d readdb

.PHONY: redis-cli
redis-cli: ## Buka redis-cli
	docker exec -it pc-redis redis-cli

.PHONY: cache-keys
cache-keys: ## Lihat isi cache produk di Redis
	@docker exec -it pc-redis redis-cli --scan --pattern 'product:*'

.PHONY: topics
topics: ## Daftar topic Kafka
	docker exec -it pc-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

.PHONY: consume
consume: ## Ikuti event di topic product.events.v1
	docker exec -it pc-kafka /opt/kafka/bin/kafka-console-consumer.sh \
		--bootstrap-server localhost:9092 --topic product.events.v1 \
		--from-beginning --property print.key=true

.PHONY: smoke
smoke: ## Jalankan smoke test end-to-end (butuh kedua service jalan)
	./scripts/smoke-test.sh
