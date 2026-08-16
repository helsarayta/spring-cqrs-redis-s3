# Build dijalankan dari akar repo (bukan dari folder ini), karena ini project multi-module
# dan write-service membutuhkan module `common`:
#   docker build -f write-service/Dockerfile -t product-cqrs/write-service .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# POM disalin lebih dulu dan dependensinya diunduh terpisah dari kode sumber.
# Dengan begitu, perubahan kode tidak membatalkan lapisan cache berisi dependensi —
# dan build ulang tidak perlu mengunduh seluruh isi Maven Central lagi.
COPY pom.xml .
COPY common/pom.xml common/
COPY write-service/pom.xml write-service/
COPY read-service/pom.xml read-service/
RUN mvn -B -q -pl common,write-service -am dependency:go-offline -DskipTests

COPY common/src common/src
COPY write-service/src write-service/src
RUN mvn -B -pl common,write-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Berjalan sebagai pengguna biasa, bukan root. Kalau ada celah yang memungkinkan eksekusi
# perintah, pembatasan ini menentukan seberapa jauh dampaknya bisa menyebar.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build --chown=app:app /workspace/write-service/target/write-service-*.jar app.jar

EXPOSE 8081

# MaxRAMPercentage, bukan -Xmx tetap: heap menyesuaikan batas memori container, sehingga
# nilainya tidak perlu diubah setiap kali batas container disesuaikan.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Fase startup diberi kelonggaran karena migrasi Flyway berjalan sebelum port dibuka.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
