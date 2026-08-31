# Build and run

## Prerequisites

| | |
|---|---|
| JDK | **21** (Android Studio's bundled JBR is fine) |
| `ANDROID_HOME` | e.g. `/home/auser/Android/Sdk` |
| SDK platform | **android-36** (compileSdk 36) |
| Build tools | 36.0.0 or newer |
| Gradle | provided by the wrapper (9.7.1) |

Exact dependency versions live in [`../gradle/libs.versions.toml`](../gradle/libs.versions.toml) —
read that rather than assuming.

## Commands

```bash
./gradlew assembleDebug                       # debug APK (:app, pulling in :shared)
./gradlew testDebugUnitTest                   # 136 JVM unit tests (:shared, on the androidTarget compilation)
./gradlew :shared:desktopTest                  # the same 136 tests again, on the desktop compilation
./gradlew lintDebug                           # :app: 12 pre-existing findings; :shared: 0
./gradlew assembleRelease                     # release APK — unsigned unless PTT_KEYSTORE_PATH and friends are set
./gradlew build                               # everything :app and :shared build for Android + desktop
./gradlew :desktopApp:run                     # run the desktop app directly
./gradlew :desktopApp:packageDeb              # a .deb (packageMsi/packageExe/packageDmg only on their native OS)
./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64   # frontend-compile iOS, even on Linux
```

See [`testing.md`](testing.md) for the full test-class breakdown and
[`platform-support.md`](platform-support.md) for what each module/platform actually covers.

### Desktop installers

Compose Desktop drives `jpackage`, and **`jpackage` does not cross-compile.** Every
`TargetFormat` is bound to one operating system, and the packaging is done by that OS's own
tooling:

| Format | Task | Only on | Needs |
|---|---|---|---|
| `.deb` | `:desktopApp:packageDeb` | Linux | `dpkg-deb`, `fakeroot` |
| `.msi`, `.exe` | `:desktopApp:packageMsi`, `:desktopApp:packageExe` | Windows | WiX Toolset **3** on `PATH` |
| `.dmg` | `:desktopApp:packageDmg` | macOS | `hdiutil` (built in) |

There is no flag that produces a `.dmg` or an `.exe` from this Linux machine. **A task for the
wrong host is `SKIPPED`, not failed** — `./gradlew :desktopApp:packageDmg` here prints
`BUILD SUCCESSFUL` and writes nothing at all, so check for the file rather than the exit code.

`.github/workflows/desktop.yml` is how the other two are built: a three-way matrix
(`ubuntu-latest`, `windows-latest`, `macos-latest`), triggered by a `v*` tag, by
`workflow_dispatch`, or by a pull request touching `desktopApp/`. It uploads each installer as a
build artifact and, on a tag, attaches it to the same GitHub release `release.yml` publishes the
APK to.

Two limits worth knowing before pointing anyone at those files:

- **They are unsigned.** No Authenticode certificate on Windows, no Developer ID signature or
  notarisation on macOS, so SmartScreen and Gatekeeper both warn. Signing would need paid
  certificates held as CI secrets, which this project does not have.
- **The `.dmg` is Apple silicon only.** `macos-latest` is arm64 and the bundled runtime is the
  host's architecture; an Intel build needs a second matrix leg on `macos-13`.

The workflow runs `package<Format>`, not `packageRelease<Format>`. The release variants run
ProGuard over the distribution, and the Koin/Ktor wiring here is the reflective kind that needs
keep rules proved by launching the result — the same reason `:app` ships unminified.

Install the Android app:

```bash
adb -s <serial> install -r -g app/build/outputs/apk/debug/app-debug.apk
```

`-g` grants the manifest's runtime permissions up front, which saves clicking through dialogs.

## Build constraints — do not "helpfully" bump these

**`gradle.properties` must keep both of these:**

```properties
android.builtInKotlin=false
android.newDsl=false
```

AGP 9 ships built-in Kotlin support and a new DSL, and rejects the `org.jetbrains.kotlin.android`
plugin unless both are disabled (*"not compatible with AGP's 9.0 new DSL"*).

**`compileSdk` must stay 36.** Several current AndroidX artifacts declare `minCompileSdk=37`, which
fails the build. That is why these are pinned:

| Artifact | Pinned to | Why |
|---|---|---|
| `androidx.core:core-ktx` | 1.18.0 | 1.19.0 requires compileSdk 37 |
| `androidx.lifecycle:*` | 2.10.0 | 2.11.0's `lifecycle-viewmodel-compose` requires 37 |
| `androidx.compose:compose-bom` | 2026.06.01 | 2026.08.00 → Compose UI 1.12.0 requires 37 |

To check before bumping anything AndroidX:

```bash
curl -s https://dl.google.com/dl/android/maven2/<group/path>/<artifact>/<ver>/<artifact>-<ver>.aar \
  | unzip -p - META-INF/com/android/build/gradle/aar-metadata.properties | grep minCompileSdk
```

**`:shared` carries the same compileSdk-36 ceiling, and two constraints of its own:**

- **Compose Multiplatform 1.12.0 wants `androidx.compose.*` at versions that require compileSdk
  37.** `shared/build.gradle.kts`'s `androidMain` dependencies pin the same androidx Compose
  version as `:app` by depending on `enforcedPlatform(libs.androidx.compose.bom)` (2026.06.01, the
  same BOM `:app` uses via a plain `platform(...)`), and the root `build.gradle.kts` forces
  `androidx.lifecycle:lifecycle-{viewmodel,runtime}-compose:2.10.0` on every subproject's non-iOS
  configurations — `org.jetbrains.androidx.lifecycle`'s Compose Multiplatform artifacts publish
  their *android* target as a substitution onto those exact coordinates, and the 2.11.0 version CMP
  1.12.0 asks for needs compileSdk 37. Read the KDoc on both force sites before touching either.
- **`:shared` must keep the deprecated `com.android.library` Gradle plugin — do not switch it to
  `com.android.kotlin.multiplatform.library`.** The new plugin is what Kotlin's own deprecation
  warning suggests, and the build stays green either way, but switching makes Compose Multiplatform
  resources (`composeResources/`, `Res.string.*`/`Res.drawable.*`) silently stop packaging into the
  APK — the app then crashes at launch with a `MissingResourceException`, a known Compose
  Multiplatform issue (JetBrains CMP-9547). Verified for this repo: a release APK built with
  `com.android.library` contains `assets/composeResources/com.github.devapro.pttdroid.shared.resources/`;
  don't take that on faith after changing the plugin.
- **Do not add `iosX64()` to `:shared`'s target list.** Compose Multiplatform publishes no
  `iosX64` variant of `org.jetbrains.compose.runtime:runtime` (or any other Compose artifact), for
  any release back to 1.8.2 — declaring it fails `appleMain` dependency resolution outright, before
  a single line of Kotlin/Native even compiles. iosX64 (Intel simulator) has no real device or
  Apple Silicon simulator behind it anyway, so nothing is lost by targeting only `iosArm64` (devices)
  and `iosSimulatorArm64` (Apple Silicon simulators).

## The default relay

**Settings → Relay → Default** dials whatever `relay.properties` said at build time:

```properties
defaultRelay=ws://10.0.2.2:8000
```

It is read into `BuildConfig.DEFAULT_RELAY_HOST` / `_PORT` / `_TLS` and reaches the app through
`AppSettings.DEFAULT_HOST` / `DEFAULT_PORT` / `DEFAULT_TLS`. `wss://` also leaves a fresh install
with **Encrypted connection** already on.

Both the scheme and the port must be written out — the build infers nothing, so what a build ships
is exactly what is on that line, and a malformed value fails configuration rather than silently
falling back:

```
defaultRelay: 'relay.example.com' is not scheme://host:port, e.g. ws://10.0.2.2:8000
```

Override it for a one-off build without editing the file:

```bash
./gradlew assembleRelease -PpttDefaultRelay=wss://relay.example.com:8443
PTT_DEFAULT_RELAY=wss://relay.example.com:8443 ./gradlew assembleRelease
```

The file is tracked for the same reason `version.properties` is: F-Droid builds a plain checkout
with no Gradle properties set, so a value that only exists in CI would build as something else.

What this repository ships — `ws://10.0.2.2:8000` — is the emulator's route to the machine running
it, which is right for development and reaches nothing on a real handset. A group running its own
relay changes that one line and ships an APK that arrives already pointing at it.

## Networking: reaching the server

**An emulator reaches a server on your development machine at `10.0.2.2`, never `localhost`** —
inside the emulator, `localhost` is the emulator itself. `10.0.2.2` is the app's default host.

On a physical device, set **Settings → Relay → Custom** to the machine's LAN address (e.g.
`192.168.1.20:8000`) and make sure the port is allowed through the firewall. The address box takes
a whole URL, so what `ptt-server` prints at startup can be pasted straight in.

The transport is plain `ws://`, which Android blocks by default. `res/xml/network_security_config.xml`
permits cleartext and is referenced from the manifest. Without it every connection fails with
`UnknownServiceException: CLEARTEXT communication to … not permitted by network security policy`.
(The old Java-WebSocket client bypassed this policy entirely; OkHttp honours it.)

## Running the server

Setting one up for real, rather than for a build-and-test loop:
[`../../ptt-server/docs/running-your-own.md`](../../ptt-server/docs/running-your-own.md).

For development, either start the standalone relay from the sibling repo:

```bash
cd ../ptt-server && ./gradlew run          # listens on 0.0.0.0:8000
curl -s localhost:8000/health
```

…or enable **Host a relay on this device** in the app's Settings and set **Relay → Custom** to
`127.0.0.1`.

## Two-emulator setup

```bash
$ANDROID_HOME/emulator/emulator -list-avds
adb devices -l
```

Install on both, set both to the same channel and leave the relay on **Default** (`10.0.2.2`), then
hold PTT on one and watch the other. Put them on different channels to confirm they are isolated.
Details in [`testing.md`](testing.md).

## iOS

`:shared`'s `iosArm64()`/`iosSimulatorArm64()` targets only exist when the build can
actually compile them — a real Mac, or `-PenableIosTargets=true` — see the guard at the top of
`kotlin { }` in `shared/build.gradle.kts`. On a Linux machine (this one, and this repo's regular
`ci.yml`) they are absent from a plain `./gradlew build`/`./gradlew projects`, but Linux *can*
frontend-compile them (klib, not link) with the opt-in flag:

```bash
./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64
./gradlew -PenableIosTargets=true :shared:compileKotlinIosArm64
```

This is a genuinely useful, cheap gate even without a Mac: it catches a bad `expect`/`actual` pair
or a cinterop signature error in well under a minute, and it is part of both this repo's normal
build gate and `ci.yml`. It cannot *link or run* iOS code, though — Kotlin/Native's Apple linker and
the simulator/device runtime both need a real Apple toolchain.

On a Mac, with Xcode installed:

```bash
./gradlew :shared:embedAndSignAppleFrameworkForXcode   # or let Xcode's build phase do it
open iosApp/iosApp.xcodeproj                            # build/run the "iosApp" scheme
```

`.github/workflows/ios.yml` is where linking and running are actually exercised — a `macos-latest`
job that compiles `:shared` for `iosSimulatorArm64`, links the framework, then builds
`iosApp.xcodeproj` with `xcodebuild`. Treat anything under `iosMain`/`iosApp/` as compile-verified
but link/runtime-verified only when that job is green — see
[`platform-support.md`](platform-support.md).

Two non-obvious things that cost real debugging time getting `iosMain` to compile, worth knowing
before touching it again: some Apple APIs are Objective-C *categories*, which Kotlin/Native exposes
as top-level extension functions/properties needing their own explicit imports rather than being
visible as members of the type they appear to extend — `platform.Foundation.serverTrust` (an
`NSURLProtectionSpace` extension) and `platform.AVFAudio.setActive` (an `AVAudioSession` extension)
both look like plain unresolved members until the import is added. Second,
`kotlinx.cinterop.get`/`set`/`plus` (used for `CPointer`/`CValuesRef` indexing in
`PinnedTrust.ios.kt` and `IosAudio.kt`) are themselves extension functions in `kotlinx.cinterop`,
not operators the compiler provides for free.

iOS audio (`audio/IosAudio.kt`) is a real `AVAudioEngine`-backed implementation — see
[`audio-pipeline.md`](audio-pipeline.md#ios-capture--playback) for the capture/playback pipeline,
and `docs/known-issues.md` for what's still unverified without a device.
