# syntax=docker/dockerfile:1

FROM golang:1.24-alpine AS builder

WORKDIR /src

COPY go.mod ./
COPY cmd/ ./cmd/
COPY internal/ ./internal/

RUN CGO_ENABLED=0 GOOS=linux go build \
    -trimpath \
    -ldflags="-s -w" \
    -o /out/pin-server \
    ./cmd/pin-server \
    && mkdir -p /out/data \
    && chmod 0700 /out/data

FROM scratch

COPY --from=builder /out/pin-server /pin-server
COPY --from=builder --chown=65532:65532 /out/data /data

ENV PIN_SERVER_ADDR=":8080" \
    PIN_SERVER_DATA_FILE="/data/pins.json" \
    PIN_SERVER_RETENTION="48h"

VOLUME ["/data"]
EXPOSE 8080

USER 65532:65532

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/pin-server", "-healthcheck"]

ENTRYPOINT ["/pin-server"]
