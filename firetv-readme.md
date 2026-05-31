# Fire TV Build and Test Guide

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

5. Gradle daemon JDK compatibility fix
- `/gradle/gradle-daemon-jvm.properties` updated to use `toolchainVendor=adoptium` with Java 21
  instead of JetBrains vendor-only toolchain resolution.

6. Lower minimum SDK for older Fire TV devices
- `/app/build.gradle.kts` `minSdk` lowered from 26 to 25 so Android API 25 Fire TV devices can install the app.

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
