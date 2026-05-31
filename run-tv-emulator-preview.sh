#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/Users/colinhickey/Projects/AerioTV-Android"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/fire/debug/app-fire-debug.apk"
APP_ID="com.aeriotv.android.fire"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
AVD_NAME="${AVD_NAME:-AerioTV_FireTV_API36}"
API_LEVEL="${API_LEVEL:-36}"
TAG="${TAG:-android-tv}"
HOST_ARCH="$(uname -m)"
if [ "$HOST_ARCH" = "arm64" ]; then
  DEFAULT_ABI="arm64-v8a"
else
  DEFAULT_ABI="x86_64"
fi
ABI="${ABI:-$DEFAULT_ABI}"
IMAGE="system-images;android-${API_LEVEL};${TAG};${ABI}"

EMULATOR_BIN="$ANDROID_SDK_ROOT/emulator/emulator"
ADB_BIN="$ANDROID_SDK_ROOT/platform-tools/adb"
SDKMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
# Prefer SDK-managed cmdline-tools installs (latest-2, latest-3, ...)
# over a Homebrew-provided symlink wrapper. They resolve package paths
# consistently for AVD creation.
for d in "$ANDROID_SDK_ROOT"/cmdline-tools/latest-*; do
  [ -d "$d" ] || continue
  if [ -x "$d/bin/sdkmanager" ] && [ -x "$d/bin/avdmanager" ]; then
    SDKMANAGER_BIN="$d/bin/sdkmanager"
    AVDMANAGER_BIN="$d/bin/avdmanager"
  fi
done
# Some setups accidentally symlink `latest` one level too high:
#   cmdline-tools/latest -> .../cmdline-tools
# where binaries then live under latest/latest/bin/.
if [ ! -x "$SDKMANAGER_BIN" ] && [ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/latest/bin/sdkmanager" ]; then
  SDKMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/latest/bin/sdkmanager"
fi
if [ ! -x "$AVDMANAGER_BIN" ] && [ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/latest/bin/avdmanager" ]; then
  AVDMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/latest/bin/avdmanager"
fi
if [ ! -x "$SDKMANAGER_BIN" ]; then
  SDKMANAGER_BIN="$(command -v sdkmanager || true)"
fi
if [ ! -x "$AVDMANAGER_BIN" ]; then
  AVDMANAGER_BIN="$(command -v avdmanager || true)"
fi

usage() {
  cat <<USAGE
Usage: $0 [--fresh]

Options:
  --fresh   Kill running emulators and cold-boot this AVD.

Environment overrides:
  AVD_NAME=<name>       (default: $AVD_NAME)
  API_LEVEL=<level>     (default: $API_LEVEL)
  TAG=<tag>             (default: $TAG)
  ABI=<abi>             (default: $ABI)
USAGE
}

FRESH=0
if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  usage
  exit 0
fi
if [ "${1:-}" = "--fresh" ]; then
  FRESH=1
elif [ "${1:-}" != "" ]; then
  usage
  exit 1
fi

if [ ! -x "$EMULATOR_BIN" ]; then
  echo "Android emulator binary not found: $EMULATOR_BIN"
  echo "Install with: sdkmanager --sdk_root=\"$ANDROID_SDK_ROOT\" \"emulator\""
  exit 1
fi
if [ ! -x "$ADB_BIN" ]; then
  echo "adb not found at: $ADB_BIN"
  echo "Install with: sdkmanager --sdk_root=\"$ANDROID_SDK_ROOT\" \"platform-tools\""
  exit 1
fi
if [ -z "$SDKMANAGER_BIN" ] || [ -z "$AVDMANAGER_BIN" ]; then
  echo "sdkmanager/avdmanager not found in PATH"
  echo "Ensure cmdline-tools are installed and on PATH"
  exit 1
fi
if [ ! -f "$APK_PATH" ]; then
  echo "APK not found at: $APK_PATH"
  echo "Build it first: ./gradlew :app:assembleFireDebug"
  exit 1
fi

echo "==> Tooling:"
echo "sdkmanager: $SDKMANAGER_BIN"
echo "avdmanager: $AVDMANAGER_BIN"

echo "==> Ensuring system image is installed: $IMAGE"
if ! "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" "$IMAGE" >/dev/null 2>&1; then
  echo "Primary image unavailable ($IMAGE), probing alternatives..."
  CANDIDATES=(
    "system-images;android-${API_LEVEL};android-tv;arm64-v8a"
    "system-images;android-${API_LEVEL};android-tv;x86_64"
    "system-images;android-${API_LEVEL};google_apis;arm64-v8a"
    "system-images;android-${API_LEVEL};google_apis;x86_64"
    "system-images;android-${API_LEVEL};google_apis_playstore;arm64-v8a"
    "system-images;android-${API_LEVEL};google_apis_playstore;x86_64"
  )
  FOUND=""
  for candidate in "${CANDIDATES[@]}"; do
    if "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" "$candidate" >/dev/null 2>&1; then
      FOUND="$candidate"
      break
    fi
  done
  if [ -z "$FOUND" ]; then
    echo "Could not install any Android ${API_LEVEL} system image candidate."
    echo "Try listing available images with:"
    echo "  sdkmanager --sdk_root=\"$ANDROID_SDK_ROOT\" --list | rg \"system-images;android-${API_LEVEL}\""
    exit 1
  fi
  IMAGE="$FOUND"
fi
echo "==> Using system image: $IMAGE"

echo "==> Ensuring AVD exists: $AVD_NAME"
if ! "$AVDMANAGER_BIN" list avd | rg -q "Name: $AVD_NAME$"; then
  PKG_ABI="${IMAGE##*;}"
  PKG_TAG="$(echo "$IMAGE" | awk -F';' '{print $(NF-1)}')"
  echo "no" | "$AVDMANAGER_BIN" create avd \
    --name "$AVD_NAME" \
    --package "$IMAGE" \
    --tag "$PKG_TAG" \
    --abi "$PKG_ABI" \
    --device "tv_1080p" >/dev/null || \
  echo "no" | "$AVDMANAGER_BIN" create avd \
    --name "$AVD_NAME" \
    --package "$IMAGE" \
    --tag "$PKG_TAG" \
    --abi "$PKG_ABI" >/dev/null
fi

if [ "$FRESH" -eq 1 ]; then
  echo "==> Killing any running emulators"
  "$ADB_BIN" devices | awk '/emulator-/{print $1}' | while read -r serial; do
    "$ADB_BIN" -s "$serial" emu kill || true
  done
fi

if "$ADB_BIN" devices | awk '/emulator-/{print $1}' | grep -q .; then
  EMU_SERIAL=$("$ADB_BIN" devices | awk '/emulator-/{print $1; exit}')
  echo "==> Reusing running emulator: $EMU_SERIAL"
else
  echo "==> Launching emulator: $AVD_NAME"
  nohup "$EMULATOR_BIN" -avd "$AVD_NAME" -netdelay none -netspeed full >/tmp/aeriotv-emulator.log 2>&1 &

  echo "==> Waiting for emulator to come online"
  "$ADB_BIN" wait-for-device
  EMU_SERIAL=$(
    for _ in $(seq 1 120); do
      s=$("$ADB_BIN" devices | awk '/emulator-/{print $1; exit}')
      if [ -n "$s" ]; then
        echo "$s"
        break
      fi
      sleep 1
    done
  )
  if [ -z "${EMU_SERIAL:-}" ]; then
    echo "Failed to find running emulator serial"
    echo "Check logs: /tmp/aeriotv-emulator.log"
    exit 1
  fi
fi

echo "==> Waiting for Android boot completion"
for _ in $(seq 1 180); do
  BOOTED=$("$ADB_BIN" -s "$EMU_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  if [ "$BOOTED" = "1" ]; then
    break
  fi
  sleep 1
done

BOOTED=$("$ADB_BIN" -s "$EMU_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
if [ "$BOOTED" != "1" ]; then
  echo "Emulator did not finish booting in time"
  echo "Check logs: /tmp/aeriotv-emulator.log"
  exit 1
fi

echo "==> Installing APK"
"$ADB_BIN" -s "$EMU_SERIAL" install -r "$APK_PATH"

echo "==> Launching app ($APP_ID)"
"$ADB_BIN" -s "$EMU_SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null

echo "==> Done"
echo "Emulator: $EMU_SERIAL"
echo "APK: $APK_PATH"
echo "Log: /tmp/aeriotv-emulator.log"
