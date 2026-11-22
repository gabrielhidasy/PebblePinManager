#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
TARGET="${1:-all}"

mkdir -p "${DIST_DIR}"

build_android() {
  echo "==> Building Android builder image..."
  docker build -f "${ROOT_DIR}/docker/Dockerfile.android" -t pebblepinmanager-android-builder "${ROOT_DIR}/docker"

  echo "==> Running Android unit tests & building debug APK..."
  docker run --rm \
    -v "${ROOT_DIR}:/work" \
    -w /work/android \
    pebblepinmanager-android-builder \
    ./gradlew --no-daemon testDebugUnitTest assembleDebug

  cp "${ROOT_DIR}/android/app/build/outputs/apk/debug/app-debug.apk" \
     "${DIST_DIR}/pebblepinmanager-android-debug.apk"
  cp "${ROOT_DIR}/android/emulator-bridge/build/outputs/apk/debug/emulator-bridge-debug.apk" \
     "${DIST_DIR}/pebblepinmanager-emulator-bridge-debug.apk"
  echo "==> Android APKs ready: ${DIST_DIR}/pebblepinmanager-android-debug.apk, ${DIST_DIR}/pebblepinmanager-emulator-bridge-debug.apk"
}

build_watchapp() {
  echo "==> Building Pebble watchapp builder image..."
  docker build -f "${ROOT_DIR}/docker/Dockerfile.watchapp" -t pebblepinmanager-watchapp-builder "${ROOT_DIR}/docker"

  echo "==> Building Pebble watchapp (.pbw) for basalt & emery..."
  docker run --rm \
    -v "${ROOT_DIR}:/work" \
    -w /work/watchapp \
    pebblepinmanager-watchapp-builder \
    pebble build

  cp "${ROOT_DIR}/watchapp/build/watchapp.pbw" \
     "${DIST_DIR}/pebblepinmanager-watchapp.pbw"
  echo "==> Pebble watchapp ready: ${DIST_DIR}/pebblepinmanager-watchapp.pbw"
}

case "${TARGET}" in
  android)
    build_android
    ;;
  watchapp)
    build_watchapp
    ;;
  all)
    build_watchapp
    build_android
    ;;
  *)
    echo "Usage: $0 [all|android|watchapp]" >&2
    exit 1
    ;;
esac

ls -lh "${DIST_DIR}"
