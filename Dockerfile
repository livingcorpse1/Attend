# Build stage. The models are downloaded and verified here so the runtime image stays
# small and the digests are checked exactly once, during the build.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B clean package -DskipTests

# Runtime stage.
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system veriqra && useradd --system --gid veriqra --home /app veriqra

COPY --from=build /build/target/veriqra-*.jar /app/app.jar
COPY --from=build /build/models /app/models

RUN mkdir -p /app/data/storage && chown -R veriqra:veriqra /app
USER veriqra

ENV PORT=8080 \
    VERIQRA_MODELS_DIR=/app/models \
    STORAGE_PROVIDER=local \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -q -O - http://127.0.0.1:8080/api/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
