#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/Users/colinhickey/Projects/AerioTV-Android"
FIRE_RELEASE_DIR="$PROJECT_DIR/app/build/outputs/apk/fire/release"
FINAL_APK="$FIRE_RELEASE_DIR/AerioTV-FireTV.apk"
DEFAULT_REPO="chickey/AerioTV-Android"

usage() {
  cat <<USAGE
Usage: $0 <tag> [notes] [repo]

Examples:
  $0 v0.1.1-fire
  $0 v0.1.1-fire "Fire TV release build"
  $0 v0.1.1-fire "Fire TV release build" chickey/AerioTV-Android
USAGE
}

if [ "${1:-}" = "" ]; then
  usage
  exit 1
fi

TAG="$1"
NOTES="${2:-Fire TV release build}"
REPO="${3:-$DEFAULT_REPO}"

cd "$PROJECT_DIR"

echo "==> Ensuring gh default repo is set to $REPO"
gh repo set-default "$REPO"

echo "==> Building Fire release APK"
./gradlew :app:assembleFireRelease

SRC_APK=""
if [ -f "$FIRE_RELEASE_DIR/app-fire-release.apk" ]; then
  SRC_APK="$FIRE_RELEASE_DIR/app-fire-release.apk"
elif [ -f "$FIRE_RELEASE_DIR/app-fire-release-unsigned.apk" ]; then
  SRC_APK="$FIRE_RELEASE_DIR/app-fire-release-unsigned.apk"
else
  echo "Could not find a Fire release APK in: $FIRE_RELEASE_DIR"
  ls -la "$FIRE_RELEASE_DIR" || true
  exit 1
fi

echo "==> Copying release APK to stable asset name"
cp "$SRC_APK" "$FINAL_APK"

echo "==> Creating and pushing tag if needed"
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "Tag $TAG already exists locally"
else
  git tag -a "$TAG" -m "Fire TV release $TAG"
fi

git push origin "$TAG"

echo "==> Creating GitHub release"
if gh release view "$TAG" >/dev/null 2>&1; then
  echo "Release $TAG already exists; uploading/replacing APK asset"
  gh release upload "$TAG" "$FINAL_APK" --clobber
else
  gh release create "$TAG" "$FINAL_APK" --title "$TAG" --notes "$NOTES"
fi

echo "==> Done"
echo "Release: $TAG"
echo "Asset: $FINAL_APK"
