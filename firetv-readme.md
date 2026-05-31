# Fire TV Build, Test, and Release Guide

This guide is for installing and testing AerioTV on Android-based Fire TV devices (not VegaOS models).

## Project differences from original Play-oriented setup

The following changes were added in this repository to support Fire TV builds cleanly:

1. Product flavors for store targets in `/app/build.gradle.kts`
- Added `play` flavor for Google Play-oriented builds.
- Added `fire` flavor for Fire TV-oriented builds.
- `fire` flavor uses:
  - `applicationIdSuffix = ".fire"` (final app id: `com.aeriotv.android.fire`)
  - `versionNameSuffix = "-fire"`
  - `BuildConfig.GOOGLE_SERVICES_AVAILABLE = false`
  - empty `GOOGLE_DRIVE_WEB_CLIENT_ID`

2. Build-time sync gating in `/app/src/main/java/com/aeriotv/android/core/sync/SyncConfig.kt`
- `SyncConfig.isConfigured()` now requires both:
  - `GOOGLE_SERVICES_AVAILABLE == true`
  - non-empty `GOOGLE_DRIVE_WEB_CLIENT_ID`
- This keeps Google sign-in/Drive sync disabled on Fire builds by design.

3. Separate Fire build/install docs
- This file (`firetv-readme.md`) documents Fire-specific setup and deploy flow.
- Main README remains general project documentation.

4. Helper scripts added for Fire workflow
- `/setup-and-build-aeriotv.sh`: one-time macOS setup + build (JDK 21, SDK 36, licenses, local.properties).
- `/install-firetv.sh`: install and launch Fire APK using Fire TV IP passed as parameter.
- `/uninstall-aeriotv-build-deps.sh`: removes setup-installed toolchain/env items without touching project files.
- `/publish-fire-release.sh`: builds Fire release APK, copies stable asset name, pushes tag, and creates/updates GitHub release.

5. Gradle daemon JDK compatibility fix
- `/gradle/gradle-daemon-jvm.properties` updated to use `toolchainVendor=adoptium` with Java 21
  instead of JetBrains vendor-only toolchain resolution.

6. Lower minimum SDK for older Fire TV devices
- `/app/build.gradle.kts` `minSdk` lowered from 26 to 25 so Android API 25 Fire TV devices can install the app.

7. Fire TV guide UX and logo behavior improvements
- New `Settings > Guide Options` section with:
  - Show/hide channel name
  - Show/hide channel number
  - Transparent logo background toggle
  - Logo scale mode (`Fit`, `Fill`, `Crop`)
- Fire TV D-pad behavior improvements:
  - Appearance sliders now use Left/Right for value changes and Up/Down for focus navigation.
- Dispatcharr logo URL normalization:
  - Uppercase schemes like `HTTP://...` are normalized so channel logos load reliably on Fire TV.

## Prerequisites

- macOS
- Homebrew
- Fire TV on same network as your Mac
- ADB debugging enabled on Fire TV

## 1) One-time local setup + initial build

Run:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/setup-and-build-aeriotv.sh
```

What this script does:

- Installs `temurin@21`
- Installs Android command line tools
- Configures `JAVA_HOME`, `ANDROID_SDK_ROOT`, and SDK tool paths in `~/.zshrc`
- Accepts Android SDK licenses
- Installs SDK components for this project (`platforms;android-36`, `build-tools;36.0.0`, `platform-tools`)
- Writes `local.properties`
- Builds the project

## 2) Build the Fire TV flavor APK

From the project directory:

```bash
cd /Users/colinhickey/Projects/AerioTV-Android
./gradlew :app:assembleFireDebug
```

APK output:

`/Users/colinhickey/Projects/AerioTV-Android/app/build/outputs/apk/fire/debug/app-fire-debug.apk`

## 3) Fire TV device preparation

On the Fire TV:

1. Go to `Settings > My Fire TV > About`
2. Click the device name 7 times to unlock Developer Options
3. Enable `ADB debugging`
4. Enable `Apps from Unknown Sources`
5. Find device IP under `Settings > Network`

## 4) Install and launch on Fire TV

Use the helper script with IP as a parameter:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/install-firetv.sh <FIRE_TV_IP>
```

Optional custom port:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/install-firetv.sh <FIRE_TV_IP> <PORT>
```

Example:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/install-firetv.sh 192.168.1.50
```

What the install script does:

- `adb connect <ip>:<port>`
- `adb install -r` with the Fire debug APK
- Launches `com.aeriotv.android.fire`

## 5) Fast rebuild/reinstall loop

Rebuild:

```bash
cd /Users/colinhickey/Projects/AerioTV-Android
./gradlew :app:assembleFireDebug
```

Reinstall and relaunch:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/install-firetv.sh <FIRE_TV_IP>
```

## 5b) Local TV emulator preview (no physical Fire TV required)

After building the Fire debug APK, you can run:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/run-tv-emulator-preview.sh
```

What this script does:

- ensures Android Emulator + Android TV system image exist
- creates an AVD if missing (`AerioTV_FireTV_API36`)
- boots emulator
- installs `app-fire-debug.apk`
- launches `com.aeriotv.android.fire`

Optional fresh boot:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/run-tv-emulator-preview.sh --fresh
```

## 6) Remove the setup environment (optional)

If you want to remove dependencies installed by the setup script while leaving the project directory untouched:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/uninstall-aeriotv-build-deps.sh
```

This uninstall script:

- Removes Homebrew casks: `temurin@21`, `android-commandlinetools` (and old `temurin@17` if present)
- Removes setup-added Java/Android env lines from `~/.zshrc`
- Removes `$ANDROID_SDK_ROOT` (default: `~/Library/Android/sdk`)
- Does not delete project files in `/Users/colinhickey/Projects/AerioTV-Android`

## 7) Build and publish a GitHub release

### Release prerequisites

- A release keystore (for this repo: `fire-release.keystore`)
- `keystore.properties` in project root (already gitignored) with:

```properties
storeFile=/absolute/path/to/fire-release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=fireupload
keyPassword=YOUR_KEY_PASSWORD
```

- GitHub CLI authenticated (`gh auth status`)
- Push access to your fork/repo

### Versioning/tag scheme

Use tags like:

- `v0.1.1-fire`
- `v0.1.2-fire`
- `v0.1.3-fire`

This replaces older `v0.1.0-fire.2` style tags.

### Bump app version before release

Update in `/app/build.gradle.kts`:

- increment `versionCode` by 1 each release
- set `versionName` to the matching base version (for example `0.1.2`)

### Publish command

From project root:

```bash
./publish-fire-release.sh v0.1.2-fire "Fire TV release build"
```

What this script does:

- sets gh default repo
- builds `:app:assembleFireRelease`
- copies APK to stable filename:
  - `/Users/colinhickey/Projects/AerioTV-Android/app/build/outputs/apk/fire/release/AerioTV-FireTV.apk`
- creates/pushes git tag
- creates GitHub release (or updates asset if release already exists)

Result on GitHub Releases:

- Release title/tag: `v0.1.2-fire`
- Asset filename: `AerioTV-FireTV.apk`

## Troubleshooting

If licenses fail during build, run:

```bash
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools"
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Then retry:

```bash
cd /Users/colinhickey/Projects/AerioTV-Android
./gradlew --stop
./gradlew :app:assembleFireDebug
```

If `gh` reports no default repo:

```bash
gh repo set-default chickey/AerioTV-Android
```

If Fire TV shows a placeholder/blank app icon after repeated sideload updates:

1. Use a clean reinstall so Fire OS refreshes launcher metadata:

```bash
bash /Users/colinhickey/Projects/AerioTV-Android/install-firetv.sh <FIRE_TV_IP> 5555 --clean
```

2. Restart Fire TV once after reinstall if the old cached tile remains.
