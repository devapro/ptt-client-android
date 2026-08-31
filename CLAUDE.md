# CLAUDE.md — ptt-client-android

A push-to-talk client for Android, desktop and iOS, built on one Compose Multiplatform codebase.
Self-rolled MVI (`mvi/`) + Koin + Jetpack Compose, with an Android foreground service, a Glance
widget and a floating overlay on Android only. Talks to the WebSocket relay in the sibling
`../ptt-server` repo, or to the relay the Android/desktop builds can host themselves
(`internalserver/`).

## Modules

| Module | What it is | Namespace |
|---|---|---|
| `:shared` | Kotlin Multiplatform: `androidTarget` + `jvm("desktop")` + `iosArm64`/`iosSimulatorArm64` (the last two behind a build guard, see below). Everything platform-independent: `domain/`, `mvi/`, `model/`, `data/settings/`, `network/`, the Compose UI (`ui/`), the ten `reducer/`s, `MainActivityViewModel`, the platform-independent half of Koin (`di/SharedDi.kt`) | `com.github.devapro.pttdroid.shared` |
| `:app` | The Android application launcher: `MainActivity`, `service/`, `overlay/`, `widget/`, the Android-only half of Koin (`di/AppDi.kt`), and everything genuinely Android-only — 13 Kotlin files | `com.github.devapro.pttdroid` |
| `:desktopApp` | Hosts the shared Compose UI in a `Window {}`, starts Koin itself (`Main.kt`) | — |
| `iosApp/` | An Xcode project. `ComposeUIViewController` hosts `:shared`'s `App()`, called from `iosApp/iosApp/ContentView.swift` | — |

**The one thing to know before changing `:shared`'s source-set layout:** below `commonMain`,
`androidMain` and `desktopMain` both `dependsOn` an intermediate source set, **`jvmCommonMain`**,
that exists for one reason: `KtorPttConnection`'s OkHttp-backed `createPttHttpClient`
(`PttHttpClient.jvm.kt`), `network/tls/PinnedTrust.kt` (`javax.net.ssl`), and
`internalserver/InternalPttServer` (the on-device relay, a Ktor CIO server) are all JVM-only APIs —
`javax.net.ssl` and Ktor's CIO engine do not exist on iOS. `jvmCommonMain` is what lets Android and
desktop share this code without forcing iOS to provide a stub for any of it. New code that is
genuinely JVM-only (not Android-only, not desktop-only) belongs there, not duplicated into both
`androidMain` and `desktopMain`. `jvmCommonTest` is the equivalent for tests.

**The one thing to know before changing anything else:** `domain/PttController` is the sole owner
of the connection, microphone and speaker. On Android it is hosted by `service/PttForegroundService`
— not by the Activity. On desktop and iOS there is no foreground-service concept, so
`PttSessionLauncher` calls `PttController.start()`/`stop()` directly (`DesktopPttSessionLauncher`,
`IosPttSessionLauncher`). The UI, the Android floating bubble and the Android widget are all
observers of its single `StateFlow<PttState>`. Do not introduce a second source of truth for
connection or floor state.

**The one thing to know before changing the transport:** `network/PttEndpoint` bundles the url,
the pinned certificate fingerprint and the access token, and `AppSettings.endpoint()` is the only
thing that builds one. Transport security is documented in
[`docs/architecture.md`](docs/architecture.md#transport-security).

**The one thing to know before changing the UI:** `ui/PttUiStatus` is the single mapping from
`PttState` to colour and wording, and the app screen, the Android floating bubble, the Android
Glance widget and the Android notification all read it. Do not let a surface invent its own
colours or labels — a colour has to mean the same thing everywhere it appears. What the interface
is for, and the rules it follows: [`docs/ui-design.md`](docs/ui-design.md).

Package map and the MVI loop: [`docs/architecture.md`](docs/architecture.md). What each platform
has and does not have: [`docs/platform-support.md`](docs/platform-support.md). Docs index:
[`docs/`](docs).

## Build / test / run

```bash
./gradlew assembleDebug                        # debug APK (:app)
./gradlew testDebugUnitTest                    # 136 unit tests (:shared, androidTarget compilation)
./gradlew :shared:desktopTest                   # the same 136 tests again, desktop compilation
./gradlew lintDebug                            # :app: 12 pre-existing findings; :shared: 0
ANDROID_SERIAL=<serial> ./gradlew :shared:connectedDebugAndroidTest   # 43 instrumented (39 UI + 1 migration + 3 opt-in TLS, skipped without a relay)
./gradlew build                                # full build, Android + desktop
./gradlew assembleRelease                      # unsigned unless PTT_KEYSTORE_PATH and friends are set
./gradlew :desktopApp:packageDeb               # a .deb (also packageMsi, packageDmg on their native OS)
./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64   # frontend-compile iOS, even on Linux — cheap, catches most cinterop mistakes, part of the normal gate
adb -s <serial> install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Prerequisites: **JDK 21**, `ANDROID_HOME` set, SDK platform **android-36**. Exact dependency
versions live in `gradle/libs.versions.toml` — read it rather than assuming.
Details: [`docs/build-and-run.md`](docs/build-and-run.md).

## Hard rules

- Never `git commit` or `git push` unless the user explicitly asks.
- **Do not remove `android.builtInKotlin=false` or `android.newDsl=false` from
  `gradle.properties`.** AGP 9 rejects the `org.jetbrains.kotlin.android` plugin without both.
- **Do not raise `compileSdk` above 36, and do not bump AndroidX versions blindly**, in `:app` or
  `:shared`. API 37 is not installed here, and `core-ktx` > 1.18.0, `lifecycle` > 2.10.0 and
  Compose BOM > 2026.06.01 all require it. Check `minCompileSdk` in the AAR's
  `aar-metadata.properties` first — see [`docs/build-and-run.md`](docs/build-and-run.md).
- **`:shared` must keep the deprecated `com.android.library` Gradle plugin.** Do not switch it to
  `com.android.kotlin.multiplatform.library` — Kotlin's own deprecation warning suggests exactly
  that, and the build stays green either way, but switching makes Compose Multiplatform resources
  (`composeResources/`, `Res.string.*`) silently stop packaging into the APK: the app then crashes
  at launch with `MissingResourceException` (JetBrains CMP-9547). Verified for this repo by
  inspecting a release APK for `assets/composeResources/com.github.devapro.pttdroid.shared.resources/`
  — don't take it on faith after touching the plugin.
- **`:shared`'s `androidMain` must keep `enforcedPlatform(libs.androidx.compose.bom)`, and the root
  `build.gradle.kts` must keep forcing `androidx.lifecycle:*-compose:2.10.0`.** Compose
  Multiplatform 1.12.0 and `org.jetbrains.androidx.lifecycle` 2.11.0 both publish artifacts that
  require compileSdk 37; these two forces are what keeps `:shared` and `:app` resolving the same
  compileSdk-36-safe versions instead of Gradle's default "highest wins". Read the KDoc on both
  force sites (`shared/build.gradle.kts`, root `build.gradle.kts`) before touching either.
- **Do not add `iosX64()` to `:shared`'s target list.** Compose Multiplatform publishes no
  `iosX64` variant of any Compose artifact (checked back to 1.8.2) — declaring it fails
  `appleMain` dependency resolution outright. Only `iosArm64`/`iosSimulatorArm64` are declared, and
  only when `HostManager.hostIsMac || -PenableIosTargets=true`.
- **iOS Objective-C *categories* need their own imports in Kotlin/Native**, not just the type they
  appear to extend: `platform.Foundation.serverTrust`/`credentialForTrust`
  (`NSURLProtectionSpace`/`NSURLCredential` extensions, `PttHttpClient.ios.kt`),
  `platform.AVFAudio.setActive` (`AVAudioSession` extension, `IosAudio.kt`), and
  `kotlinx.cinterop.get`/`set`/`plus` (`CPointer`/`CValuesRef` indexing, used in both
  `PinnedTrust.ios.kt` and `IosAudio.kt`) all look like unresolved members without the explicit
  import. This cost real debugging time getting `iosMain` to compile — don't rediscover it.
- **`-PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64`/`compileKotlinIosArm64` work
  on Linux and are part of the normal gate.** This machine cannot link or run Kotlin/Native Apple
  binaries, but it can frontend-compile them, which catches a bad `expect`/`actual` pair or cinterop
  signature error in well under a minute — run it after touching anything under `shared/src/iosMain`
  even without a Mac.
- **`:app`'s release build type currently ships with `isMinifyEnabled = false`** (matching what
  F-Droid has always distributed) **and `app/proguard-rules.pro` is dormant as a result.** It
  already carries the two keep rules a minified build needs (Ktor's JVM-only debugger-detector
  references, and the WorkManager/Room reflective-instantiation gap — full explanation in
  `docs/known-issues.md`), found by actually installing and launching a minified release build
  during an earlier, reverted phase. If minification is ever turned back on, re-run that check
  (`env -u PTT_KEYSTORE_PATH ./gradlew :app:assembleRelease` with `isMinifyEnabled = true`, sign
  with a throwaway key, install, launch) rather than assuming the existing two rules are still
  sufficient.
- **Never hardcode a server host or port.** They are user settings in `data/settings/AppSettings.kt`.
  `customHost`/`customPort` are what the user typed; `serverHost`/`serverPort` are derived from
  `serverMode` and are what everything downstream dials. Read the derived pair, and do not make the
  typed pair authoritative — Default has to be able to ignore it without losing it.
- **`ServerMode.restore` must keep treating a stored address with no stored mode as Custom.**
  Anything else silently moves an existing install off the relay it was configured for.
- **The transport's url, pin and token travel together as `PttEndpoint`.** Do not add a fourth
  connection parameter that bypasses it — a bare url `String` is what let `wss://` be switched on
  without its matching pin.
- **`PinnedTrustManager.getAcceptedIssuers()` must stay empty.** Returning issuers puts it on
  OkHttp's chain-cleaning path, which needs a root a self-signed certificate does not have, and
  the connection then fails despite a matching fingerprint. The iOS pinning path
  (`PttHttpClient.ios.kt`'s `handleChallenge`) has the equivalent constraint: it must keep comparing
  only the SHA-256 pin via the shared `CertificatePin.matches`, not switch to Ktor Darwin's
  `CertificatePinner`, which pins a different value (SPKI hash, not whole-certificate DER). See
  [`docs/known-issues.md`](docs/known-issues.md).
- **Compare secrets in constant time** and keep them out of URLs — the access token is a header.
- **Do not weaken `network_security_config.xml`.** Cleartext is permitted because `ws://` is
  still the LAN default; that is not licence to relax anything else.
- **Emulators reach the host machine at `10.0.2.2`, never `localhost`** (this is the default host).
- **No logging on a per-audio-frame path** — `VoiceRecorder`'s read loop, `VoicePlayer.play()`
  (`:app`), `DesktopVoiceRecorder`/`DesktopVoicePlayer` (`:shared` desktopMain), and `IosAudio.kt`'s
  tap/render callbacks (`:shared` iosMain). See [`docs/known-issues.md`](docs/known-issues.md) #10.
- Reducers must not touch the socket or the audio devices; they delegate to `PttController` /
  `PttSessionLauncher`. Persistence goes through a reducer too, not from a Compose callback.
- **Never key the PTT gesture's `pointerInput` on anything that changes mid-press**, and keep the
  release in a `finally`. A disabled Compose button drops its gesture detector; losing the release
  strands the talk floor with the microphone open. See [`docs/known-issues.md`](docs/known-issues.md) #20.
- **No dynamic colour.** Colour is the readout here, and it has to match on every surface that
  reads `ui/PttUiStatus`.
- `docs/index.html` is the product landing page. It is served by GitHub Pages **from a workflow**
  (`.github/workflows/pages.yml`), not from a branch, because the same site also carries the
  F-Droid repository — a branch-based deployment would delete it. Screenshots in `docs/img/` are
  real device captures; retake them rather than editing them if the UI changes.
- **The default relay lives in `relay.properties`, not in Kotlin.** `defaultRelay` is read at
  build time (via `gradle/relay-defaults.gradle.kts`, shared by `:app`, `:shared` and
  `:desktopApp`) into `:app`'s `BuildConfig.DEFAULT_RELAY_*` and `:shared`'s generated
  `RelayDefaults` object, and is what **Settings → Relay → Default** dials; a fork with its own
  relay changes that one line. It is a tracked file for the same reason the version is — F-Droid
  builds a plain checkout with no Gradle properties — and it is parsed strictly
  (`scheme://host:port`, both required) so a build ships exactly what is written.
  `-PpttDefaultRelay=` or `PTT_DEFAULT_RELAY=` override it for a one-off build.
- **The version lives in `version.properties`, not in a git tag.** F-Droid builds a plain checkout
  of the tagged commit with no Gradle properties, so the file has to be right in the commit. The
  release workflow fails a tag that disagrees with it.
- **Never commit key material.** `*.jks`, `*.p12`, `*.keystore` are gitignored; the app signing
  key and the F-Droid index key live in CI secrets and can never be rotated without breaking
  every installed copy. See [`docs/fdroid.md`](docs/fdroid.md).
- **User-facing release text belongs in `fastlane/metadata/android/en-US/` only.** F-Droid reads
  that layout directly and the release workflow copies it into the repository index; a second
  copy in `metadata/*.yml` would drift.
- **Do not add a dependency that is not on Maven Central under an OSI licence.** F-Droid builds
  from source with no proprietary blobs, and one Play-services transitive would disqualify the app
  from the official catalogue. This applies to `:shared` and `:desktopApp` too, not just `:app` —
  F-Droid only packages `:app`'s APK, but everything `:app` links, including transitively through
  `:shared`, is in scope.
- Keep `docs/` in sync when changing architecture, the action/reducer set, the audio pipeline, the
  wire protocol or DI wiring. `../ptt-server/docs/protocol.md` is the canonical protocol spec —
  change it first.
- Run the gates before declaring work done:
  ```
  ./gradlew assembleDebug testDebugUnitTest lintDebug
  ./gradlew :shared:desktopTest
  ./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64
  env -u PTT_KEYSTORE_PATH ./gradlew :app:assembleRelease
  ```
  and, if the change touches release packaging, DI, or anything R8 might see differently in a
  minified build: sign the resulting release APK with a throwaway key, install it, and actually
  launch it — a debug build and an unminified release build both pass through cases R8 does not.

## Conventions

Full Kotlin style rules are in [`docs/conventions.md`](docs/conventions.md) — read it before writing
Kotlin here.

## Before "fixing" something that looks broken

[`docs/known-issues.md`](docs/known-issues.md) lists defects already fixed (with the mechanism
that replaced each), what remains open per platform, and the non-obvious platform gotchas: cleartext
being blocked for `ws://`, a pinned trust manager needing to advertise no issuers, a masked field
still reporting its raw value to the accessibility tree, HTTP stripping whitespace from header
values, microphone foreground services not being startable from the background, `WindowManager`
needing the main thread, the widget being unable to do hold-to-talk, emulator microphones capturing
silence, and the R8/WorkManager keep-rule gap described above. What each of the three platforms
does and does not have (backgrounded operation, overlay, widget, notification, on-device relay):
[`docs/platform-support.md`](docs/platform-support.md).
