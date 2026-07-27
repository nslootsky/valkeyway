FROM eclipse-temurin:25-jre-alpine

WORKDIR /app
COPY target/valkey-cluster-proxy-0.1.0-SNAPSHOT.jar .

EXPOSE 6379
ENTRYPOINT ["java", "-jar", "valkey-cluster-proxy-0.1.0-SNAPSHOT.jar"]
