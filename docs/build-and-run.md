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
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # 29 JVM unit tests
./gradlew lintDebug              # Android lint
./gradlew assembleRelease        # unsigned release APK
./gradlew build                  # everything
```

Install:

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

## Networking: reaching the server

**An emulator reaches a server on your development machine at `10.0.2.2`, never `localhost`** —
inside the emulator, `localhost` is the emulator itself. `10.0.2.2` is the app's default host.

On a physical device, use the machine's LAN address (e.g. `192.168.1.20`) and make sure the port is
allowed through the firewall.

The transport is plain `ws://`, which Android blocks by default. `res/xml/network_security_config.xml`
permits cleartext and is referenced from the manifest. Without it every connection fails with
`UnknownServiceException: CLEARTEXT communication to … not permitted by network security policy`.
(The old Java-WebSocket client bypassed this policy entirely; OkHttp honours it.)

## Running the server

Either start the standalone relay from the sibling repo:

```bash
cd ../ptt-server && ./gradlew run          # listens on 0.0.0.0:8000
curl -s localhost:8000/health
```

…or enable **Host a relay on this device** in the app's Settings and point the host field at
`127.0.0.1`.

## Two-emulator setup

```bash
$ANDROID_HOME/emulator/emulator -list-avds
adb devices -l
```

Install on both, set both to the same channel and host `10.0.2.2`, then hold PTT on one and watch
the other. Put them on different channels to confirm they are isolated. Details in
[`testing.md`](testing.md).
