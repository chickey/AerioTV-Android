# AerioTV for Fire TV

This fork of [AerioTV](https://github.com/jonzey231/AerioTV) is focused on making AerioTV work well on **Android-based Amazon Fire TV / Fire TV Stick devices**.

The Google Play / Android TV build still exists, but the main reason for this fork is the Fire TV variant: sideloadable APK releases, Fire TV remote/D-pad behaviour, older Android-based Fire TV compatibility, Dispatcharr DVR integration, and Dispatcharr-backed pairing/sync without Google services.

> This does not target the newest VegaOS-based Fire TV devices. It targets Android-based Fire TV models.

## Install on Fire TV

### Option 1: Downloader app shortcode

The easiest install method is the Amazon **Downloader** app.

Open Downloader on the Fire TV and enter:

```text
1522736
```

Or enter the full short URL:

```text
http://aftv.news/1522736
```

That shortcode points at the stable latest APK permalink:

```text
https://github.com/chickey/AerioTV-Android/releases/latest/download/AerioTV-FireTV.apk
```

The APK asset is always named `AerioTV-FireTV.apk`, so reusing the same Downloader shortcode fetches the current Fire TV release.

### Option 2: Download APK directly

Download the latest Fire TV APK from GitHub:

[Download AerioTV-FireTV.apk](https://github.com/chickey/AerioTV-Android/releases/latest/download/AerioTV-FireTV.apk)

Then sideload it using your preferred Fire TV sideloading method.

### Option 3: ADB install from source build

For local testing from this repo:

```bash
./gradlew :app:assembleFireDebug
bash /Users/colinhickey/Projects/AerioTV-Android/install-firetv.sh <FIRE_TV_IP>
```

More detail is in the [Fire TV Build, Test, and Release Guide](firetv-readme.md).

## Fire TV Setup Notes

On the Fire TV:

1. Go to `Settings > My Fire TV > About`.
2. Click the device name 7 times to unlock Developer Options.
3. Enable `ADB debugging` if using ADB installs.
4. Enable `Apps from Unknown Sources` for sideload installs.
5. Install using Downloader shortcode `1522736` or the APK link above.

If the Fire TV launcher shows a stale placeholder icon after repeated sideloads, use a clean reinstall from the Fire TV guide.

## Dispatcharr Pairing and Sync

Dispatcharr is central to this Fire TV fork. AerioTV can use Dispatcharr for:

- live TV lineup and EPG data,
- channel logos,
- DVR recordings,
- VOD where exposed by Dispatcharr,
- Fire TV device pairing,
- settings/favourites/recent/watch-progress sync.

### Dispatcharr Plugin

This repo includes an AerioTV Dispatcharr plugin under:

```text
dispatcharr-plugin/aeriotv
```

Current plugin release:

[Download aeriotv.zip](https://github.com/chickey/AerioTV-Android/releases/download/plugin-v0.5.5/aeriotv.zip)

Plugin docs:

[Dispatcharr Plugin README](dispatcharr-plugin/aeriotv/README.md)

The plugin provides a web pairing page at:

```text
/api/plugins/aeriotv/admin
```

Typical flow:

1. Install/enable the AerioTV plugin in Dispatcharr.
2. Fully restart the Dispatcharr backend so plugin routes are registered.
3. Open the plugin help/docs link or `/api/plugins/aeriotv/admin`.
4. On Fire TV, choose `Find Dispatcharr automatically` in AerioTV.
5. Enter the 4-digit Fire TV code on the Dispatcharr plugin page.
6. AerioTV receives the server details and continues setup automatically.

Plugin releases are intentionally published with `--latest=false` so GitHub's `/releases/latest` permalink continues to point at the main Fire TV APK release.

## What This Fork Changes for Fire TV

Highlights compared with the original Play-oriented Android setup:

- Adds a `fire` product flavour with app id `com.aeriotv.android.fire`.
- Disables Google services in Fire builds.
- Hides Google Drive sync on Fire TV and replaces it with Dispatcharr sync.
- Lowers minimum SDK to API 25 for older Android-based Fire TV devices.
- Improves Fire TV D-pad focus/navigation behaviour.
- Adds Fire-friendly guide options for logos, channel names, channel numbers and guide anchoring.
- Adds Fire TV install/release helper scripts.
- Adds Dispatcharr pairing and sync plugin support.

The full change list and developer workflow are in [firetv-readme.md](firetv-readme.md).

## Current Status

Active Fire TV releases use tags like:

```text
v0.1.5-fire
```

The stable APK asset name is:

```text
AerioTV-FireTV.apk
```

The latest APK permalink is:

```text
https://github.com/chickey/AerioTV-Android/releases/latest/download/AerioTV-FireTV.apk
```

Downloader shortcode:

```text
1522736
```

## Build Variants

The app defines two product flavours:

- `fire`: Fire TV-oriented build. Google services are disabled and Dispatcharr sync is used.
- `play`: Google Play-oriented build. Google sign-in / Drive sync can be enabled with `GOOGLE_DRIVE_WEB_CLIENT_ID`.

Build from CLI:

```bash
./gradlew :app:assembleFireDebug
./gradlew :app:assemblePlayDebug
```

## Local Build Requirements

For users building from source:

- macOS for the provided setup scripts
- Homebrew
- JDK 21
- Android SDK 36
- Android platform tools / ADB

One-time setup and initial Fire build:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/setup-and-build-aeriotv.sh
```

Remove setup-installed tooling while leaving the project directory alone:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/uninstall-aeriotv-build-deps.sh
```

## Release Notes for Maintainers

Fire app releases should remain the GitHub `Latest` release so this permalink keeps working:

```text
https://github.com/chickey/AerioTV-Android/releases/latest/download/AerioTV-FireTV.apk
```

Dispatcharr plugin releases must be created with:

```bash
--latest=false
```

That prevents plugin releases from stealing the `latest` pointer and breaking the Downloader shortcode.

## Tech Stack

- Kotlin + Jetpack Compose
- Compose for TV / Material 3
- Room + DataStore
- Ktor + kotlinx.serialization
- Hilt, Coroutines, Flow
- AndroidX Media3 / ExoPlayer
- Coil for images/logos
- Google Drive sync only for Play-oriented builds
- Dispatcharr sync for Fire TV builds

## Module Structure

The current project is primarily a single `:app` module:

```text
app/                    Compose UI, navigation, DI wiring
core/                   data, networking, playback, sync, preferences
feature/                live TV, guide, player, DVR, settings, onboarding
dispatcharr-plugin/     AerioTV Dispatcharr pairing/sync plugin
```
