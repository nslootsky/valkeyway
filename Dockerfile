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

# jemalloc, linked to one path for every architecture (it installs under /usr/lib/<arch>-linux-gnu).
RUN apt-get update \
    && apt-get install -y --no-install-recommends libjemalloc2 \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s "$(ls /usr/lib/*-linux-gnu/libjemalloc.so.2)" /usr/local/lib/libjemalloc.so.2
RUN useradd --system --no-create-home --shell /usr/sbin/nologin valkeyway
WORKDIR /app
COPY --from=build /app/target/valkeyway-*.jar app.jar
USER valkeyway

# Memory, measured under heavy load (docs/OPERATIONS.md#sizing): sized for a 2 GB container.
# - G1, 70% heap: G1's frequent young collections let Glide free the native memory behind large
#   responses (>16 KB) promptly. Avoid ZGC: with it that native memory ran to several GB.
# - jemalloc, which returns memory after load spikes.
# - Compact object headers (Java 25); exit on heap exhaustion so the container restarts.
# Override any of these variables to change them.
ENV LD_PRELOAD=/usr/local/lib/libjemalloc.so.2 \
    MALLOC_CONF=metadata_thp:auto,background_thread:true \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError"

EXPOSE 6379 6380

# No curl or wget in the image: ask the health endpoint over bash's /dev/tcp.
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/6380 && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-jar", "app.jar"]
