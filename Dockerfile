# Build stage
FROM docker.io/eclipse-temurin:25-jdk-alpine AS build

RUN apk add --no-cache curl

WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN ./mvnw dependency:go-offline -q

COPY src src
RUN ./mvnw package -DskipTests -q

# Run stage
FROM docker.io/eclipse-temurin:25-jre-alpine

# Non-root user for security
RUN addgroup -S proxy && adduser -S proxy -G proxy

WORKDIR /app

# Copy jar from build stage (final name without SNAPSHOT)
COPY --from=build /app/target/valkeyway*.jar app.jar

USER proxy

EXPOSE 6379 6380

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://localhost:6380/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
