# syntax=docker/dockerfile:1

FROM golang:1.27.1-alpine AS build
WORKDIR /build
# Module files first so source edits do not invalidate the dependency layer.
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
# A static binary needs no libc in the runtime image.
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/collector .

FROM alpine:3.20
RUN apk add --no-cache wget \
    && addgroup -S eventwatch && adduser -S eventwatch -G eventwatch
WORKDIR /app
COPY --from=build /out/collector ./collector

# The pending queue must outlive the container or a backend outage loses events.
RUN mkdir -p /queue && chown -R eventwatch:eventwatch /app /queue
VOLUME ["/queue"]
USER eventwatch

# A published container port is reachable, so the agent binds to every interface and the
# security model then requires CAPTURE_API_KEY to be supplied.
ENV COLLECTOR_PORT=8082 \
    COLLECTOR_BIND=0.0.0.0 \
    PENDING_EVENTS_DIR=/queue \
    HOST_ID_FILE=/queue/host-id \
    JAVA_BACKEND_URL=http://analytics:8080/receive \
    LOG_FORMAT=json

EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    # A GET, not --spider: that sends HEAD, which /health answers with 405. GNU wget hides
    # this by retrying with GET; the collector's busybox wget does not, so its container
    # could never become healthy.
    CMD wget --quiet --output-document=/dev/null http://127.0.0.1:8082/health || exit 1

ENTRYPOINT ["/app/collector"]
