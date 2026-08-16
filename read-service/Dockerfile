# Build dijalankan dari akar repo (bukan dari folder ini):
#   docker build -f read-service/Dockerfile -t product-cqrs/read-service .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Lihat catatan di write-service/Dockerfile: POM lebih dulu supaya lapisan dependensi
# tetap ter-cache ketika hanya kode yang berubah.
COPY pom.xml .
COPY common/pom.xml common/
COPY write-service/pom.xml write-service/
COPY read-service/pom.xml read-service/
RUN mvn -B -q -pl common,read-service -am dependency:go-offline -DskipTests

COPY common/src common/src
COPY read-service/src read-service/src
RUN mvn -B -pl common,read-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build --chown=app:app /workspace/read-service/target/read-service-*.jar app.jar

EXPOSE 8082

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:8082/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
