#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
AVD_NAME="${AVD_NAME:-AerioTV_FireTV_API36}"
API_LEVEL="${API_LEVEL:-36}"

echo "==> Removing AerioTV emulator-only components"
echo "==> Keeping core Android build setup intact (JDK, platform-tools, build-tools, platforms)."

if [ ! -d "$ANDROID_SDK_ROOT" ]; then
  echo "SDK root not found: $ANDROID_SDK_ROOT"
  exit 0
fi

# Resolve an avdmanager binary from SDK first, then PATH.
AVDMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
if [ ! -x "$AVDMANAGER_BIN" ] && [ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/latest/bin/avdmanager" ]; then
  AVDMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/latest/bin/avdmanager"
fi
if [ ! -x "$AVDMANAGER_BIN" ]; then
  for d in "$ANDROID_SDK_ROOT"/cmdline-tools/latest-*; do
    [ -d "$d" ] || continue
    if [ -x "$d/bin/avdmanager" ]; then
      AVDMANAGER_BIN="$d/bin/avdmanager"
      break
    fi
  done
fi
if [ ! -x "$AVDMANAGER_BIN" ]; then
  AVDMANAGER_BIN="$(command -v avdmanager || true)"
fi

echo "==> Removing AVD ($AVD_NAME) if present"
if [ -x "${AVDMANAGER_BIN:-}" ]; then
  "$AVDMANAGER_BIN" delete avd -n "$AVD_NAME" >/dev/null 2>&1 || true
fi
rm -f "$HOME/.android/avd/${AVD_NAME}.ini" || true
rm -rf "$HOME/.android/avd/${AVD_NAME}.avd" || true

echo "==> Removing emulator package files"
rm -rf "$ANDROID_SDK_ROOT/emulator" || true

echo "==> Removing Android TV system images for API $API_LEVEL"
rm -rf "$ANDROID_SDK_ROOT/system-images/android-${API_LEVEL}/android-tv" || true

# Optional: trim empty system-images API directory if it has no children left.
if [ -d "$ANDROID_SDK_ROOT/system-images/android-${API_LEVEL}" ]; then
  if [ -z "$(ls -A "$ANDROID_SDK_ROOT/system-images/android-${API_LEVEL}" 2>/dev/null || true)" ]; then
    rmdir "$ANDROID_SDK_ROOT/system-images/android-${API_LEVEL}" || true
  fi
fi

# Remove emulator path export line if present, keep other env lines intact.
ZSHRC="$HOME/.zshrc"
if [ -f "$ZSHRC" ]; then
  TMP_FILE="$(mktemp)"
  grep -Fvx 'export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator' "$ZSHRC" > "$TMP_FILE" || true
  mv "$TMP_FILE" "$ZSHRC"
fi

echo "==> Done"
echo "Removed: AVD + emulator binaries + android-tv system images"
echo "Kept: cmdline-tools, platform-tools, build-tools, platforms, JDK"
