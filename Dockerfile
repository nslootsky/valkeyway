# Builds the proxy from source and packages it. Releases use Dockerfile.release instead, which
# packages a prebuilt jar for several platforms.

# Build stage
FROM docker.io/eclipse-temurin:25-jdk AS build

WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN ./mvnw dependency:go-offline -q

COPY src src
RUN ./mvnw package -DskipTests -q

# Run stage: glibc-based, like the release image (Glide has no aarch64 musl library)
FROM docker.io/eclipse-temurin:25-jre

RUN useradd --system --no-create-home --shell /usr/sbin/nologin valkeyway
WORKDIR /app
COPY --from=build /app/target/valkeyway-*.jar app.jar
USER valkeyway

EXPOSE 6379 6380

# No curl or wget in the image: ask the health endpoint over bash's /dev/tcp.
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/6380 && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-jar", "app.jar"]
