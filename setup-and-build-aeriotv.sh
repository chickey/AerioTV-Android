#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/Users/colinhickey/Projects/AerioTV-Android"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ZSHRC="$HOME/.zshrc"

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
append_if_missing 'export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools'

# shellcheck disable=SC1090
source "$ZSHRC" || true

# Ensure tools exist in expected location for sdkmanager path
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
if [ -d "/opt/homebrew/share/android-commandlinetools/cmdline-tools" ]; then
  # Apple Silicon default brew path
  if [ ! -e "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
    ln -s /opt/homebrew/share/android-commandlinetools/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  fi
elif [ -d "/usr/local/share/android-commandlinetools/cmdline-tools" ]; then
  # Intel default brew path
  if [ ! -e "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
    ln -s /usr/local/share/android-commandlinetools/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  fi
fi

echo "==> Accepting Android licenses and installing SDK packages"
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0"

echo "==> Writing local.properties"
cd "$PROJECT_DIR"
printf "sdk.dir=%s\n" "$ANDROID_SDK_ROOT" > local.properties

echo "==> Building debug APK"
./gradlew :app:assembleDebug

echo "==> Done"
echo "APK output:"
echo "$PROJECT_DIR/app/build/outputs/apk/debug/"
