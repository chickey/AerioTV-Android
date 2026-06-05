#!/usr/bin/env bash
set -euo pipefail

# Publishes the AerioTV Dispatcharr plugin as a GitHub Release asset.
#
# Mirrors publish-fire-release.sh, but ALWAYS marks the release --latest=false
# so plugin releases never steal the repo's "Latest" pointer from the Fire TV
# app releases (the stable APK permalink relies on that pointer):
#   https://github.com/chickey/AerioTV-Android/releases/latest/download/AerioTV-FireTV.apk

PROJECT_DIR="/Users/colinhickey/Projects/AerioTV-Android"
PLUGIN_DIR="$PROJECT_DIR/dispatcharr-plugin"
ZIP_PATH="$PLUGIN_DIR/aeriotv.zip"
DEFAULT_REPO="chickey/AerioTV-Android"

usage() {
  cat <<USAGE
Usage: $0 <tag> [notes] [repo]

Examples:
  $0 plugin-v0.4.3
  $0 plugin-v0.4.3 "Pairing admin page fixes"
  $0 plugin-v0.4.3 "Pairing admin page fixes" chickey/AerioTV-Android

The tag should match the version in dispatcharr-plugin/aeriotv/plugin.json.
USAGE
}

if [ "${1:-}" = "" ]; then
  usage
  exit 1
fi

TAG="$1"
NOTES="${2:-AerioTV Dispatcharr plugin release}"
REPO="${3:-$DEFAULT_REPO}"

cd "$PROJECT_DIR"

echo "==> Ensuring gh default repo is set to $REPO"
gh repo set-default "$REPO"

PLUGIN_VERSION="$(grep -E '"version"' "$PLUGIN_DIR/aeriotv/plugin.json" | head -1 | sed -E 's/.*"version" *: *"([^"]+)".*/\1/')"
echo "==> plugin.json version: ${PLUGIN_VERSION:-unknown}"

echo "==> Building $ZIP_PATH"
rm -f "$ZIP_PATH"
( cd "$PLUGIN_DIR" && zip -r aeriotv.zip aeriotv -x '*.pyc' -x '*__pycache__*' >/dev/null )
[ -f "$ZIP_PATH" ] || { echo "Zip not produced at $ZIP_PATH"; exit 1; }

echo "==> Creating and pushing tag if needed"
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "Tag $TAG already exists locally"
else
  git tag -a "$TAG" -m "AerioTV Dispatcharr plugin $TAG"
fi
git push origin "$TAG"

echo "==> Creating GitHub release (--latest=false so the Fire APK keeps 'Latest')"
if gh release view "$TAG" >/dev/null 2>&1; then
  echo "Release $TAG already exists; uploading/replacing zip asset"
  gh release upload "$TAG" "$ZIP_PATH" --clobber
  gh release edit "$TAG" --latest=false
else
  gh release create "$TAG" "$ZIP_PATH" --title "$TAG" --notes "$NOTES" --latest=false
fi

echo "==> Done"
echo "Release: $TAG"
echo "Asset: $ZIP_PATH"
