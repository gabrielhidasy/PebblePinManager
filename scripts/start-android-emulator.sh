#!/usr/bin/env bash
set -euo pipefail

# Provision and boot a headless Android Emulator AVD for E2E testing.
#
# Automatically uses KVM acceleration when /dev/kvm is accessible, and falls back
# cleanly to software TCG emulation (`ANDROID_I_WANT_MY_TCG=yes -accel off`) when
# running inside containers without /dev/kvm passed through.
#
# Environment variables (all optional):
#   ANDROID_HOME          Path to Android SDK (required unless --use-existing-adb)
#   ANDROID_API_LEVEL     API level for system image (default: 30)
#   ANDROID_IMAGE_TAG     System image tag: aosp_atd or default (default: aosp_atd)
#   ANDROID_ABI           ABI: x86_64 or x86 (default: x86_64)
#   AVD_NAME              AVD name (default: pebblepin-e2e)
#   EMULATOR_MEMORY_MB    RAM in MB (default: 2048)
#   EMULATOR_CORES        vCPUs (default: 2)
#   EMULATOR_LOG          Log file path (default: /tmp/android-emulator.log)
#   EMULATOR_PID_FILE     PID file path (default: /tmp/android-emulator.pid)
#   BOOT_TIMEOUT_SECONDS  Override boot wait timeout (default: 240 with KVM, 720 without KVM)
#   USE_EXISTING_ADB      If "true", skip starting an AVD and wait for an existing ADB device

ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-30}"
ANDROID_IMAGE_TAG="${ANDROID_IMAGE_TAG:-aosp_atd}"
ANDROID_ABI="${ANDROID_ABI:-x86_64}"
AVD_NAME="${AVD_NAME:-pebblepin-e2e}"
EMULATOR_MEMORY_MB="${EMULATOR_MEMORY_MB:-2048}"
EMULATOR_CORES="${EMULATOR_CORES:-2}"
EMULATOR_LOG="${EMULATOR_LOG:-/tmp/android-emulator.log}"
EMULATOR_PID_FILE="${EMULATOR_PID_FILE:-/tmp/android-emulator.pid}"
USE_EXISTING_ADB="${USE_EXISTING_ADB:-false}"

wait_for_boot() {
  local timeout_sec="$1"
  local emu_pid="${2:-}"
  local start_ts
  start_ts="$(date +%s)"

  echo "==> Waiting for Android device via ADB (timeout: ${timeout_sec}s)..."
  while true; do
    if [ -n "${emu_pid}" ] && ! kill -0 "${emu_pid}" 2>/dev/null; then
      echo "::error::Android emulator process (PID ${emu_pid}) exited prematurely during boot." >&2
      if [ -f "${EMULATOR_LOG}" ]; then
        echo "--- Last 80 lines of ${EMULATOR_LOG} ---" >&2
        tail -n 80 "${EMULATOR_LOG}" >&2
      fi
      exit 1
    fi

    local now_ts elapsed
    now_ts="$(date +%s)"
    elapsed=$((now_ts - start_ts))
    if [ "${elapsed}" -ge "${timeout_sec}" ]; then
      echo "::error::Timed out after ${timeout_sec}s waiting for Android emulator to complete boot." >&2
      adb devices -l >&2 || true
      if [ -f "${EMULATOR_LOG}" ]; then
        echo "--- Last 80 lines of ${EMULATOR_LOG} ---" >&2
        tail -n 80 "${EMULATOR_LOG}" >&2
      fi
      exit 1
    fi

    if adb get-state >/dev/null 2>&1; then
      local boot_completed dev_boot pm_ready
      boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)"
      dev_boot="$(adb shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r\n' || true)"
      if [ "${boot_completed}" = "1" ] || [ "${dev_boot}" = "1" ]; then
        pm_ready="$(adb shell pm path android 2>/dev/null | tr -d '\r\n' || true)"
        if [[ "${pm_ready}" == package:* ]]; then
          echo "==> Android booted and PackageManager is ready (${elapsed}s)."
          return 0
        fi
      fi
    fi

    if [ $((elapsed % 20)) -eq 0 ] && [ "${elapsed}" -gt 0 ]; then
      echo "    Still booting Android... (${elapsed}s / ${timeout_sec}s)"
    fi
    sleep 3
  done
}

if [ "${USE_EXISTING_ADB}" = "true" ]; then
  wait_for_boot "${BOOT_TIMEOUT_SECONDS:-120}" ""
  exit 0
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "::error::ANDROID_HOME must be set to start the Android emulator." >&2
  exit 1
fi

export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export PATH="${ANDROID_HOME}/emulator:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"

SYSTEM_IMAGE="system-images;android-${ANDROID_API_LEVEL};${ANDROID_IMAGE_TAG};${ANDROID_ABI}"
echo "==> Ensuring Android emulator & system image (${SYSTEM_IMAGE}) are installed..."
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "emulator" "platform-tools" "${SYSTEM_IMAGE}"

echo "==> Creating AVD '${AVD_NAME}' (${SYSTEM_IMAGE})..."
echo "no" | avdmanager create avd \
  --force \
  --name "${AVD_NAME}" \
  --package "${SYSTEM_IMAGE}" \
  --abi "${ANDROID_ABI}" \
  --device "pixel_6" >/dev/null

# Tune AVD hardware config for crisp portrait phone resolution and reliable headless E2E
AVD_INI="${HOME}/.android/avd/${AVD_NAME}.avd/config.ini"
if [ -f "${AVD_INI}" ]; then
  {
    echo "hw.keyboard=yes"
    echo "hw.lcd.width=1080"
    echo "hw.lcd.height=2160"
    echo "hw.lcd.density=420"
    echo "hw.ramSize=${EMULATOR_MEMORY_MB}"
    echo "hw.cpu.ncore=${EMULATOR_CORES}"
    echo "disk.dataPartition.size=2048M"
    echo "hw.audioInput=no"
    echo "hw.audioOutput=no"
  } >> "${AVD_INI}"
fi

ACCEL_ARGS=()
if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
  echo "==> KVM acceleration detected (/dev/kvm is readable & writable)."
  ACCEL_ARGS=(-accel on)
  DEFAULT_TIMEOUT=240
else
  echo "::warning::/dev/kvm is not accessible inside this runner container; falling back to software TCG emulation (slower boot). To enable fast ~25s KVM boots on self-hosted act_runner, expose --device /dev/kvm in runner container options."
  export ANDROID_I_WANT_MY_TCG=yes
  ACCEL_ARGS=(-no-accel)
  DEFAULT_TIMEOUT=720
fi

BOOT_TIMEOUT="${BOOT_TIMEOUT_SECONDS:-${DEFAULT_TIMEOUT}}"

adb start-server >/dev/null 2>&1 || true

echo "==> Launching Android emulator '${AVD_NAME}' (log: ${EMULATOR_LOG})..."
nohup emulator \
  -avd "${AVD_NAME}" \
  "${ACCEL_ARGS[@]}" \
  -gpu swiftshader_indirect \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -memory "${EMULATOR_MEMORY_MB}" \
  -cores "${EMULATOR_CORES}" \
  -wipe-data \
  >"${EMULATOR_LOG}" 2>&1 &

EMU_PID=$!
echo "${EMU_PID}" > "${EMULATOR_PID_FILE}"
echo "==> Android emulator started (PID ${EMU_PID})."

wait_for_boot "${BOOT_TIMEOUT}" "${EMU_PID}"

# Disable system animations and keep screen awake for deterministic UI Automator runs
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 600000 >/dev/null 2>&1 || true
adb shell input keyevent 224 >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb devices -l
