#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/Users/colinhickey/Projects/AerioTV-Android"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ZSHRC="$HOME/.zshrc"
AVD_NAME="${AVD_NAME:-AerioTV_FireTV_API36}"
API_LEVEL="${API_LEVEL:-36}"
INSTALL_EMULATOR=0

usage() {
  cat <<USAGE
Usage: $0 [--with-emulator]

Options:
  --with-emulator   Also install Android Emulator + TV system image and create AVD.
USAGE
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  usage
  exit 0
fi
if [ "${1:-}" = "--with-emulator" ]; then
  INSTALL_EMULATOR=1
elif [ "${1:-}" != "" ]; then
  usage
  exit 1
fi

if [ "$(uname -m)" = "arm64" ]; then
  TV_ABI="arm64-v8a"
else
  TV_ABI="x86_64"
fi
TV_TAG="android-tv"
TV_IMAGE="system-images;android-${API_LEVEL};${TV_TAG};${TV_ABI}"

echo "==> Checking Homebrew"
if ! command -v brew >/dev/null 2>&1; then
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

echo "==> Installing JDK 21 + Android CLI tools"
brew install --cask temurin@21
brew install --cask android-commandlinetools

echo "==> Configuring shell environment in $ZSHRC"
touch "$ZSHRC"

append_if_missing() {
  local line="$1"
  grep -Fqx "$line" "$ZSHRC" || echo "$line" >> "$ZSHRC"
}

append_if_missing 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)'
append_if_missing "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
append_if_missing 'export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator'

# shellcheck disable=SC1090
source "$ZSHRC" || true

# Ensure tools exist in expected location for sdkmanager path
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
if [ -L "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
  TARGET="$(readlink "$ANDROID_SDK_ROOT/cmdline-tools/latest" || true)"
  # Repair older mis-link that pointed to .../cmdline-tools (one level too high).
  if [ "$TARGET" = "/opt/homebrew/share/android-commandlinetools/cmdline-tools" ] || \
     [ "$TARGET" = "/usr/local/share/android-commandlinetools/cmdline-tools" ]; then
    rm "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  fi
fi
if [ ! -e "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
  if [ -d "/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest" ]; then
    ln -s /opt/homebrew/share/android-commandlinetools/cmdline-tools/latest "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  elif [ -d "/usr/local/share/android-commandlinetools/cmdline-tools/latest" ]; then
    ln -s /usr/local/share/android-commandlinetools/cmdline-tools/latest "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  fi
fi

echo "==> Accepting Android licenses and installing SDK packages"
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
  "cmdline-tools;latest" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0"

# Prefer SDK-managed cmdline-tools (latest-2, latest-3, ...) over a Homebrew
# symlink wrapper for consistent avdmanager behavior.
LATEST_SDK_TOOLS_DIR="$(ls -d "$ANDROID_SDK_ROOT"/cmdline-tools/latest-* 2>/dev/null | sort -V | tail -n 1 || true)"
if [ -n "$LATEST_SDK_TOOLS_DIR" ] && [ -x "$LATEST_SDK_TOOLS_DIR/bin/avdmanager" ]; then
  # Normalize on-disk layout to the canonical expected path:
  #   cmdline-tools/latest
  # This avoids warnings like:
  # "Observed package id 'cmdline-tools;latest' in inconsistent location ... latest-2"
  if [ -d "$ANDROID_SDK_ROOT/cmdline-tools/latest" ] && [ ! -L "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  fi
  if [ -L "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
    rm "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  fi
  mv "$LATEST_SDK_TOOLS_DIR" "$ANDROID_SDK_ROOT/cmdline-tools/latest" 2>/dev/null || \
    ln -s "$LATEST_SDK_TOOLS_DIR" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
fi

if [ "$INSTALL_EMULATOR" -eq 1 ]; then
  echo "==> Installing emulator + TV system image"
  sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
    "emulator" \
    "$TV_IMAGE"

  SDK_AVDMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
  if [ ! -x "$SDK_AVDMANAGER" ] && [ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/latest/bin/avdmanager" ]; then
    SDK_AVDMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/latest/bin/avdmanager"
  fi

  echo "==> Creating Android TV AVD ($AVD_NAME) if missing"
  if [ ! -x "$SDK_AVDMANAGER" ]; then
    SDK_AVDMANAGER="$(command -v avdmanager)"
  fi
  if ! "$SDK_AVDMANAGER" list avd | grep -q "Name: $AVD_NAME$"; then
    echo "no" | "$SDK_AVDMANAGER" create avd -n "$AVD_NAME" -k "$TV_IMAGE" -d "tv_1080p" >/dev/null || \
    echo "no" | "$SDK_AVDMANAGER" create avd -n "$AVD_NAME" -k "$TV_IMAGE" >/dev/null
  fi
fi

echo "==> Writing local.properties"
cd "$PROJECT_DIR"
printf "sdk.dir=%s\n" "$ANDROID_SDK_ROOT" > local.properties

echo "==> Building debug APK"
./gradlew :app:assembleDebug

echo "==> Done"
echo "APK output:"
echo "$PROJECT_DIR/app/build/outputs/apk/debug/"
if [ "$INSTALL_EMULATOR" -eq 1 ]; then
  echo "TV emulator AVD:"
  echo "$AVD_NAME ($TV_IMAGE)"
else
  echo "Emulator setup skipped (pass --with-emulator to install it)."
fi
