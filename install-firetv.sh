#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/Users/colinhickey/Projects/AerioTV-Android"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/fire/debug/app-fire-debug.apk"
APP_ID="com.aeriotv.android.fire"

usage() {
  echo "Usage: $0 <FIRE_TV_IP> [PORT] [--clean]"
  echo "Example: $0 192.168.1.50"
  echo "Example: $0 192.168.1.50 5555"
  echo "Example: $0 192.168.1.50 5555 --clean"
}

if [ "${1:-}" = "" ]; then
  usage
  exit 1
fi

FIRE_TV_IP="$1"
PORT="${2:-5555}"
EXTRA="${3:-}"
TARGET="$FIRE_TV_IP:$PORT"

if [ ! -f "$APK_PATH" ]; then
  echo "APK not found at: $APK_PATH"
  echo "Build it first with: ./gradlew :app:assembleFireDebug"
  exit 1
fi

RUN_TS_LOCAL="$(date '+%Y-%m-%d %H:%M:%S %Z')"
RUN_TS_UTC="$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
echo "==> install-firetv.sh run timestamp: $RUN_TS_LOCAL ($RUN_TS_UTC)"

echo "==> Connecting to Fire TV at $TARGET"
adb connect "$TARGET"

echo "==> Verifying ADB device connection"
adb devices

echo "==> Installing APK"
if [ "$EXTRA" = "--clean" ]; then
  echo "==> Clean install requested: uninstalling existing app first"
  adb -s "$TARGET" uninstall "$APP_ID" || true
fi
adb -s "$TARGET" install -r "$APK_PATH"

echo "==> Launching app ($APP_ID)"
adb -s "$TARGET" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1

echo "==> Done"
echo "Installed: $APK_PATH"
echo "Target: $TARGET"
