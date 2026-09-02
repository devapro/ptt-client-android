# Code Review Guidelines — ptt-client-android

Reviewer's digest: the size gate, the severity scale, the enumerated issue lists, and the closing
checklist. Read on demand by `code-reviewer` and by the `pr-review` skill's synthesis phase.

**Canonical rules are not here.** They are in `CLAUDE.md` (hard rules) and `docs/` —
`conventions.md`, `architecture.md`, `audio-pipeline.md`, `ui-design.md`, `platform-support.md`,
`testing.md`, `build-and-run.md`, `known-issues.md`, and `../ptt-server/docs/protocol.md` for the
wire contract. This file summarises them for a reviewer deciding what to report. **When this file
and a canonical doc disagree, the canonical doc wins.** See `.claude/contexts/README.md` for why
there is no `.claude/rules/` directory in this repo.

---

## Diff Size

**Do not review diffs larger than 2500 lines changed.** This repo is ~106 Kotlin files; a change
that large is a restructure, not a reviewable unit. Respond with:

> ⛔ This diff is too large for automated review (NNN lines changed, limit is 2500). Please split
> it into smaller, focused changes.

A phase-sized restructure (the kind `docs/known-issues.md` records) is the one legitimate
exception — review it doc-by-doc and module-by-module instead, and say so.

---

## Issue Priority

| Priority | When to use |
|---|---|
| **High** | Blocker — a hardcoded host/port, a `PttEndpoint` bypass, a second source of truth for connection or floor state, logging on a per-frame path, a lost talk-floor release, a protocol change landed in fewer than three implementations, a weakened trust manager or network security config, a build-constraint regression (`compileSdk`, AGP opt-out flags, `com.android.library` on `:shared`), key material in the tree |
| **Medium** | Should fix before merge — wrong KMP source set, colour or wording invented outside `ui/PttUiStatus`, missing unit test for new domain behaviour, `docs/` not updated in the same change, persistence done from a Compose callback, a magic wire/audio constant inline |
| **Low** | Optional — naming, comment density, a suggestion to extract. **Dropped entirely by `pr-review` synthesis**; `code-reviewer` may report them. |

---

## Common Issues to Flag

### High Priority

**Configuration and transport**
- **Hardcoded host or port** anywhere — Kotlin, tests, workflows, docs examples that look like
  configuration. They are user settings in `data/settings/AppSettings.kt`; the build-time default
  lives in `relay.properties`. A hardcoded LAN IP is what made the previous client unusable off
  one network.
- **Reading `customHost`/`customPort` where `serverHost`/`serverPort` was meant.** The typed pair
  is what the user entered; the derived pair is what everything downstream dials. Default has to
  be able to ignore the typed pair without losing it.
- **`ServerMode.restore` no longer treating a stored address with no stored mode as Custom** —
  silently moves an existing install off the relay it was configured for.
- **A fourth connection parameter that bypasses `PttEndpoint`** — url, pin and token travel
  together. A bare url `String` is what let `wss://` be switched on without its matching pin.
- **`PinnedTrustManager.getAcceptedIssuers()` returning issuers** — puts it on OkHttp's
  chain-cleaning path, which needs a root a self-signed certificate does not have. See
  `docs/known-issues.md`.
- **iOS pinning switched to Ktor Darwin's `CertificatePinner`** — that pins an SPKI hash, not the
  whole-certificate DER the rest of the app compares. `handleChallenge` must keep using the shared
  `CertificatePin.matches`.
- **`network_security_config.xml` weakened** beyond the existing cleartext permission, or
  `usesCleartextTraffic` widened. Cleartext is permitted because `ws://` is still the LAN default;
  that is not licence to relax anything else.
- **A secret compared with `==`, or carried in a URL.** Constant-time compare; the access token is
  a header.
- **Key material in the tree** — `*.jks`, `*.p12`, `*.keystore`, or a signing/index key inlined
  into a workflow or properties file.

**State ownership and the audio path**
- **A second source of truth for connection, floor or channel state.** `domain/PttController` owns
  the connection, microphone and speaker. The UI, the Android bubble and the widget are observers
  of its single `StateFlow<PttState>`.
- **Logging on a per-audio-frame path** — `VoiceRecorder`'s read loop, `VoicePlayer.play()`,
  `DesktopVoiceRecorder`/`DesktopVoicePlayer`, `IosAudio.kt`'s tap/render callbacks, or the relay
  frame path. 25 frames/second per direction floods output and costs battery. Allocation on those
  paths is the same class of finding.
- **A reducer touching the socket or the audio devices.** Reducers delegate to `PttController` /
  `PttSessionLauncher`.
- **A suspending reducer that returns `state.copy(...)` built from its inbound `state`.**
  `reduce(action, state)` receives the snapshot taken before it ran, and
  `MainActivityViewModel.onAction` launches one coroutine per action then assigns
  `_state.value = result.state` outright — so the return value of a reducer that suspended on I/O
  overwrites anything that changed meanwhile, including the `ptt` field written by the controller
  mirror in the ViewModel's `init`. Today only `SaveSettingsReducer` suspends — every
  `PttController` and `PttSessionLauncher` method is non-suspend, so the other nine reducers run
  without yielding and are not exposed. This is a guard against the next suspending reducer. The
  fix belongs at the assignment (`_state.update { … }`, or serialised dispatch), not in each
  reducer.
- **A PTT gesture whose `pointerInput` is keyed on something that changes mid-press, or whose
  release is not in a `finally`.** A disabled Compose button drops its gesture detector; losing
  the release strands the talk floor with the microphone open. `docs/known-issues.md` #20.

**Protocol and build**
- **A protocol change in fewer than three implementations.** `../ptt-server/docs/protocol.md` is
  canonical and changes first, then the server's `Messages.kt`, the client's
  `shared/src/commonMain/.../network/protocol/Messages.kt`, and
  `shared/src/jvmCommonMain/.../internalserver/InternalPttServer.kt`, then the serialization tests
  on both sides. There is no shared artefact, so nothing catches drift for you.
- **`android.builtInKotlin=false` or `android.newDsl=false` removed** from `gradle.properties` —
  AGP 9 then rejects the Kotlin Android plugin.
- **`compileSdk` raised above 36, or an AndroidX version bumped blindly.** API 37 is not installed
  here and several current versions require it. Check `minCompileSdk` in the AAR's
  `aar-metadata.properties` first.
- **`:shared` switched off `com.android.library`** to `com.android.kotlin.multiplatform.library` —
  the build stays green and Compose resources silently stop packaging; the app then crashes at
  launch with `MissingResourceException` (CMP-9547).
- **`enforcedPlatform(libs.androidx.compose.bom)` dropped from `:shared`'s `androidMain`, or the
  root `androidx.lifecycle:*-compose:2.10.0` force removed** — those two forces are what keep
  `:shared` and `:app` on compileSdk-36-safe versions.
- **`iosX64()` added to `:shared`'s targets** — Compose Multiplatform publishes no `iosX64`
  variant; `appleMain` dependency resolution fails outright.
- **A dependency that is not on Maven Central under an OSI licence**, in any module `:app` links
  including transitively through `:shared`. One Play-services transitive disqualifies the app from
  F-Droid.
- **The version changed in a git tag instead of `version.properties`**, or the default relay moved
  out of `relay.properties` into Kotlin. F-Droid builds a plain checkout with no Gradle properties.

### Medium Priority

**Kotlin and KMP**
- **`!!`** — use `?:`, `requireNotNull` with a message, or restructure (`docs/conventions.md`).
- **`CancellationException` swallowed** by a broad `catch (e: Exception)` inside a coroutine —
  catch and rethrow it explicitly first.
- **New JVM-only code duplicated into `androidMain` and `desktopMain`** instead of living in
  `jvmCommonMain`. That source set exists precisely for `javax.ssl` and Ktor CIO code that iOS
  cannot compile.
- **Android-only or desktop-only code placed in `commonMain`**, or an `expect` added without every
  `actual` (androidMain, desktopMain — or jvmCommonMain for both — and iosMain).
- **An Objective-C category member used in `iosMain` without its own import** —
  `platform.Foundation.serverTrust`/`credentialForTrust`, `platform.AVFAudio.setActive`,
  `kotlinx.cinterop.get`/`set`/`plus`. They look like unresolved members otherwise, and this cost
  real debugging time once already.
- **A magic wire-format or audio-format number inline** instead of in `AudioConfig` or the
  protocol types.
- **Raw `Dispatchers.*` where `CoroutineContextProvider` is injectable** (see
  `CoroutineContextProvider.jvm.kt` / `.ios.kt`).
- **A service locator or `GlobalContext.get()` in application code.** Constructor injection only;
  the single sanctioned exception is `PttWidget.provideGlance`, where Glance gives no injection
  point.

**UI**
- **A colour or a state word invented outside `ui/PttUiStatus`.** The app screen, the Android
  bubble, the Glance widget and the notification all read that one mapping; a colour has to mean
  the same thing on every surface.
- **A new palette entry outside `ui/theme/Color.kt`, or dynamic colour anywhere.** Colour is the
  readout here.
- **Colour as the only signal** — every state also changes a word and a glyph.
- **Persistence from a Compose callback** — settings are saved by a reducer.
- **Business logic or mapping in a Composable** — they render state and emit actions.
- **A missing `contentDescription`, or a press-and-hold control with no semantics `onClick`** —
  TalkBack cannot express a hold.
- **A leftover-space size hardcoded** instead of computed from `BoxWithConstraints`.
- **`Res.string.*` bypassed** for a user-visible string, or a raw literal in the UI.

**Android platform**
- **A runtime permission requested through `onRequestPermissionsResult`** instead of the Activity
  Result API.
- **A `WindowManager` call (`addView`, `updateViewLayout`, `removeView`) off the main thread.**
  The session scope is on `Dispatchers.IO`; `OverlayController` keeps its own main-thread scope.
- **`NotificationManagerCompat.notify` without a POST_NOTIFICATIONS check and
  `areNotificationsEnabled()`** — the permission is revocable from API 33.
- **A microphone foreground service started from the background** — Android 14+ forbids it; start
  it from a visible Activity or an exempt gesture.
- **`window.statusBarColor` set** — deprecated and a no-op under targetSdk 36's enforced
  edge-to-edge; use `enableEdgeToEdge()` plus inset modifiers.

**Tests and docs**
- **New domain behaviour with no JVM unit test.** If it is untestable because it touches a
  platform class directly, extract an interface (`audio/AudioContracts.kt` is the pattern).
- **A protocol change without the literal expected JSON in `ProtocolSerializationTest`**, and its
  counterpart in the server repo.
- **A test placed in the wrong source set** — `commonTest` runs on both `androidTarget` and
  `desktop`; JVM-only tests belong in `jvmCommonTest`.
- **`docs/` not updated in the same change** as architecture, the action/reducer set, the audio
  pipeline, the wire protocol or DI wiring.
- **A platform capability change landed in fewer than all three of** `docs/platform-support.md`,
  `README.md` and `docs/index.html`. Each states the split in its own voice; none can be
  regenerated from the others.
- **`docs/index.html` colour used for something other than a talk-floor state**, or its
  Platforms/Hands-free sections left disagreeing with `platform-support.md`.
- **User-facing release text added outside `fastlane/metadata/android/en-US/`.**
- **A `docs/img/` screenshot edited rather than retaken** — they are real device captures.

### Low Priority

- Non-descriptive test names.
- A repeated or non-obvious magic number with no named constant.
- A comment that paraphrases the line below it, narrates a function's own steps, or restates a
  signature. The default is no comment; a labelled block should become a named `private fun`.
- A comment the change makes stale — describes removed behaviour, an old default, a renamed field.
  Update or delete it in the same change.
- A suggestion to split a long function or file.

---

## Not an Issue

Cross-cutting false positives live in `review-exceptions.md`, each with a stable `EX-NNN` id and a
**"Still an issue"** boundary. **Read the boundary before dismissing anything** — an entry retires
a *shape*, not every finding that resembles it.

| Id | Never flag |
|---|---|
| `EX-001` | Cleartext permitted in `network_security_config.xml` |
| `EX-002` | `PinnedTrustManager.getAcceptedIssuers()` returning an empty array |
| `EX-003` | The four disabled TLS lint checks in `shared/build.gradle.kts` |
| `EX-004` | `:shared` using the deprecated `com.android.library` plugin |
| `EX-005` | The Glance widget being a toggle rather than hold-to-talk |
| `EX-006` | `GlobalContext.get()` inside `PttWidget.provideGlance` |
| `EX-007` | `AudioSystem.getLine(info)` resolving JavaSound's `"default"` line on desktop |
| `EX-008` | Dormant keep rules in `app/proguard-rules.pro` while `isMinifyEnabled = false` |
| `EX-009` | The default relay in `relay.properties` being an emulator address |
| `EX-010` | `OverlayController` owning a second, main-thread `CoroutineScope` |
| `EX-011` | Duplication between `docs/platform-support.md`, `README.md` and `docs/index.html` |
| `EX-012` | An `expect`/`actual` pair that looks like an unnecessary indirection |
| `EX-013` | iOS pinning not checking the certificate's validity window |
| `EX-014` | Absent comments or KDoc on self-evident code |
| `EX-015` | Look-alike code across `:app`, `:desktopApp` and the three platform source sets |

Not registered, but still never a finding:

- **Missing trailing newline** at end of file.
- **`internal` absent on test classes** — there is no visibility requirement for tests.
- **A `runCatching { … }.onFailure { … }` where a `try/catch` would also work** — that is the
  documented preference for non-fatal failures.
- **The 39 Compose UI tests and 136 unit tests being counted in docs** — those counts are
  maintained deliberately; flag them only when the change makes them wrong.
- **`docs/index.html` being a hand-written page rather than generated.**

---

## Questions to Ask

**Functional** — Does this solve the stated problem? What happens when the relay is unreachable
mid-transmission? What happens on the platform this change did not touch?

**Design** — Is this the simplest thing that works? Does state stay in `PttController`? Does the
UI still read `PttUiStatus` for its wording and colour?

**Platform** — Does this compile on all three targets? Does it change what a platform can do, and
if so are all three docs updated? Is there an iOS `actual` for every new `expect`?

**Migration** — Does this change stored settings? Will an existing install keep dialling the relay
it was configured for? Does a protocol change need a version bump?

---

## Final Checklist

- [ ] No hardcoded host, port, or secret; `PttEndpoint` still carries url + pin + token together
- [ ] `PttController` is still the only owner of connection, floor and channel state
- [ ] No logging or allocation added to a per-frame audio path
- [ ] Every talk-floor grab has a matching release in a `finally`
- [ ] A protocol change touched the spec, all three implementations, and both test suites
- [ ] Code in the right KMP source set; every `expect` has all its `actual`s
- [ ] `docs/` updated in the same change; a platform capability change in all three places
- [ ] Build constraints untouched (`compileSdk 36`, AGP opt-out flags, `com.android.library`,
      the two version forces, no `iosX64`)
- [ ] The gate ran: `./gradlew assembleDebug testDebugUnitTest lintDebug`,
      `./gradlew :shared:desktopTest`, and
      `./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64`
      if `:shared` changed
