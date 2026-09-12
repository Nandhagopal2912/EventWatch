# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Resolve dependencies first so source edits do not invalidate the download layer.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S eventwatch && adduser -S eventwatch -G eventwatch
WORKDIR /app
COPY --from=build /build/target/eventwatch-analytics.jar ./analytics.jar

# Telemetry lives on a volume so a container restart keeps its history.
RUN mkdir -p /data && chown -R eventwatch:eventwatch /app /data
VOLUME ["/data"]
USER eventwatch

ENV HTTP_PORT=8080 \
    DATABASE_PATH=/data/events.db \
    LOG_FORMAT=json \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    # A GET, not --spider: that sends HEAD, which /health answers with 405. GNU wget hides
    # this by retrying with GET; the collector's busybox wget does not, so its container
    # could never become healthy.
    CMD wget --quiet --output-document=/dev/null http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/analytics.jar"]
