#!/usr/bin/env bash
#
# release.sh — build, package, and publish a Fire TV GitHub release.
#
# This is the ONLY supported way to cut a Fire TV release. It guarantees the
# release asset is named exactly what the in-app self-updater downloads
# (BuildConfig.GITHUB_APK_ASSET, i.e. "AerioTV-FireTV.apk"). Uploading the
# raw gradle output (app-fire-release.apk) — or relying on `gh`'s `file#label`
# syntax, which only sets a cosmetic label and NOT the filename — leaves
# releases/latest/download/AerioTV-FireTV.apk returning 404, which breaks
# self-update (the app saves a 404 page instead of an APK).
#
# Usage:
#   scripts/release.sh                 # release the version in app/build.gradle.kts
#   scripts/release.sh --notes "text"  # custom release notes (default: commit subject)
#   scripts/release.sh --notes-file F  # release notes from a file
#   scripts/release.sh --draft         # create the release as a draft
#   scripts/release.sh --dry-run       # build + verify locally, do not publish
#
# Requirements: a configured release keystore (keystore.properties) so the
# APK is release-signed, plus the `gh` CLI authenticated against the repo.
#
set -euo pipefail

# --- locate repo root ------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

GRADLE="$ROOT/app/build.gradle.kts"

# --- args ------------------------------------------------------------------
NOTES=""
NOTES_FILE=""
DRAFT=0
DRY_RUN=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --notes)      NOTES="${2:-}"; shift 2 ;;
    --notes-file) NOTES_FILE="${2:-}"; shift 2 ;;
    --draft)      DRAFT=1; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    -h|--help)    grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

# --- derive version + config straight from build.gradle.kts ----------------
# Single source of truth: never hard-code these here.
VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
REPO="$(sed -n 's/.*GITHUB_REPO", *"\\\?"\([^"\\]*\)\\\?"".*/\1/p' "$GRADLE" | head -1)"
ASSET="$(sed -n 's/.*GITHUB_APK_ASSET", *"\\\?"\([^"\\]*\)\\\?"".*/\1/p' "$GRADLE" | head -1)"

# Fallbacks in case the regex above misses (kept defensive, not authoritative).
REPO="${REPO:-chickey/AerioTV-Android}"
ASSET="${ASSET:-AerioTV-FireTV.apk}"

if [[ -z "$VERSION_NAME" ]]; then
  echo "ERROR: could not read versionName from $GRADLE" >&2
  exit 1
fi

TAG="v${VERSION_NAME}-fire"
BUILT_APK="$ROOT/app/build/outputs/apk/fire/release/app-fire-release.apk"
STAGE_DIR="$(mktemp -d)"
STAGED_APK="$STAGE_DIR/$ASSET"
trap 'rm -rf "$STAGE_DIR"' EXIT

echo "==> Version : $VERSION_NAME"
echo "==> Tag     : $TAG"
echo "==> Repo    : $REPO"
echo "==> Asset   : $ASSET   (must match BuildConfig.GITHUB_APK_ASSET)"
echo

# --- preflight -------------------------------------------------------------
if [[ ! -f "$ROOT/keystore.properties" ]]; then
  echo "ERROR: keystore.properties missing — release build would be unsigned and" >&2
  echo "       cannot be installed over an existing signed install." >&2
  exit 1
fi

if [[ "$DRY_RUN" -eq 0 ]]; then
  if ! command -v gh >/dev/null 2>&1; then
    echo "ERROR: gh CLI not found." >&2; exit 1
  fi
  if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "ERROR: release $TAG already exists. Bump versionName in build.gradle.kts" >&2
    echo "       (and versionCode) before cutting a new release." >&2
    exit 1
  fi
fi

# --- resolve release notes -------------------------------------------------
if [[ -n "$NOTES_FILE" ]]; then
  [[ -f "$NOTES_FILE" ]] || { echo "ERROR: notes file not found: $NOTES_FILE" >&2; exit 1; }
  NOTES="$(cat "$NOTES_FILE")"
fi
if [[ -z "$NOTES" ]]; then
  NOTES="$(git log -1 --pretty=%s)"
fi

# --- build -----------------------------------------------------------------
echo "==> Building :app:assembleFireRelease ..."
./gradlew :app:assembleFireRelease --quiet

[[ -f "$BUILT_APK" ]] || { echo "ERROR: expected APK not found at $BUILT_APK" >&2; exit 1; }

# --- stage under the self-updater's expected filename ----------------------
cp "$BUILT_APK" "$STAGED_APK"
BUILT_SHA="$(shasum -a256 "$STAGED_APK" | awk '{print $1}')"
echo "==> Staged  : $STAGED_APK"
echo "==> SHA-256 : $BUILT_SHA"

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo
  echo "DRY RUN complete — built and staged '$ASSET'. Nothing published."
  exit 0
fi

# --- publish ---------------------------------------------------------------
echo
echo "==> Creating release $TAG ..."
DRAFT_FLAG=()
[[ "$DRAFT" -eq 1 ]] && DRAFT_FLAG=(--draft)

gh release create "$TAG" \
  --repo "$REPO" \
  --title "AerioTV $VERSION_NAME (Fire TV)" \
  --notes "$NOTES" \
  --target main \
  "${DRAFT_FLAG[@]}" \
  "$STAGED_APK"

# --- verify ----------------------------------------------------------------
# Confirm the asset is named correctly. (Skip download check for drafts, which
# have no public latest/download URL.)
echo
echo "==> Verifying asset name on $TAG ..."
ASSET_NAMES="$(gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name')"
echo "    assets: $ASSET_NAMES"
if ! grep -qx "$ASSET" <<<"$ASSET_NAMES"; then
  echo "ERROR: release asset is not named '$ASSET' — self-update will 404." >&2
  exit 1
fi

if [[ "$DRAFT" -eq 0 ]]; then
  echo "==> Verifying self-update download URL (allowing for CDN propagation) ..."
  URL="https://github.com/$REPO/releases/latest/download/$ASSET"
  OK=0
  for i in 1 2 3 4 5 6; do
    CODE="$(curl -s -o /tmp/.aerio-rel-check -L -w '%{http_code}' "$URL" || true)"
    if [[ "$CODE" == "200" ]]; then
      DL_SHA="$(shasum -a256 /tmp/.aerio-rel-check | awk '{print $1}')"
      if [[ "$DL_SHA" == "$BUILT_SHA" ]]; then
        echo "    ✅ $URL -> 200, checksum matches built APK"
        OK=1; break
      else
        echo "    download 200 but checksum differs (propagating?), retrying..."
      fi
    else
      echo "    attempt $i: HTTP $CODE (CDN propagating), retrying in 5s..."
    fi
    sleep 5
  done
  rm -f /tmp/.aerio-rel-check
  if [[ "$OK" -ne 1 ]]; then
    echo "WARNING: could not confirm latest/download URL yet. It usually settles" >&2
    echo "         within a minute. Re-check: $URL" >&2
  fi
fi

echo
echo "Done. Released $TAG with asset '$ASSET'."
