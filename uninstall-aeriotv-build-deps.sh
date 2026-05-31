#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/Users/colinhickey/Projects/AerioTV-Android"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ZSHRC="$HOME/.zshrc"
AVD_NAME="${AVD_NAME:-AerioTV_FireTV_API36}"

echo "==> This script removes dependencies installed by setup-and-build-aeriotv.sh"
echo "==> Project files under $PROJECT_DIR are left untouched."

remove_line_if_present() {
  local line="$1"
  local file="$2"
  if [ -f "$file" ]; then
    if grep -Fqx "$line" "$file"; then
      local tmp
      tmp="$(mktemp)"
      grep -Fvx "$line" "$file" > "$tmp" || true
      mv "$tmp" "$file"
      echo "Removed from $file: $line"
    fi
  fi
}

echo "==> Removing shell environment lines from $ZSHRC"
remove_line_if_present 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' "$ZSHRC"
remove_line_if_present 'export JAVA_HOME=$(/usr/libexec/java_home -v 17)' "$ZSHRC"
remove_line_if_present "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" "$ZSHRC"
remove_line_if_present 'export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools' "$ZSHRC"
remove_line_if_present 'export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator' "$ZSHRC"

echo "==> Removing Android TV AVD ($AVD_NAME) if present"
if command -v avdmanager >/dev/null 2>&1; then
  avdmanager delete avd -n "$AVD_NAME" >/dev/null 2>&1 || true
fi
rm -f "$HOME/.android/avd/${AVD_NAME}.ini" || true
rm -rf "$HOME/.android/avd/${AVD_NAME}.avd" || true

echo "==> Removing SDK-managed cmdline-tools variants (latest-*)"
if [ -d "$ANDROID_SDK_ROOT/cmdline-tools" ]; then
  find "$ANDROID_SDK_ROOT/cmdline-tools" -maxdepth 1 -type d -name "latest-*" -exec rm -rf {} + || true
  if [ -L "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
    rm -f "$ANDROID_SDK_ROOT/cmdline-tools/latest" || true
  fi
fi

echo "==> Uninstalling Homebrew casks"
if command -v brew >/dev/null 2>&1; then
  brew uninstall --cask android-commandlinetools || true
  brew uninstall --cask temurin@21 || true
  brew uninstall --cask temurin@17 || true
else
  echo "Homebrew not found; skipping brew uninstall steps."
fi

echo "==> Removing Android SDK root at $ANDROID_SDK_ROOT"
if [ -d "$ANDROID_SDK_ROOT" ]; then
  rm -rf "$ANDROID_SDK_ROOT"
  echo "Removed $ANDROID_SDK_ROOT"
else
  echo "SDK directory not found; nothing to remove."
fi

echo "==> Done"
echo "Open a new shell or run: source $ZSHRC"
