# Review Exceptions — confirmed false positives

Every entry is a finding shape that reviewers of this repo keep producing and that is **not** a
defect here. Ids are stable and append-only: never renumber, retire an entry in place.

**Format is three parts, always.** "Still an issue" is the safeguard — an entry retires a *shape*,
not every finding that resembles it. Suppressing on a resemblance is how a real bug goes silent.

**Who applies this file.** The `pr-review` skill's synthesis phase, with every changed file in
hand — the `pr-review-*` agents deliberately do **not** pre-filter against it. `code-reviewer`
runs standalone with no synthesis downstream, so it applies the registry itself.

Most entries below exist because of something recorded in `docs/known-issues.md`. That file is the
canonical explanation; this one only records the reviewer-facing consequence.

---

### EX-001 — Cleartext permitted in the network security config

**Flagged as:** "cleartext traffic is enabled — this is a transport security weakness."

**Why it is not an issue:** `ws://` is the LAN default for this app and Android's network security
policy blocks it outright. `app/src/main/res/xml/network_security_config.xml` permits cleartext for
exactly that reason, and `wss://` is opt-in per relay rather than a global switch. Documented in
`CLAUDE.md` and `docs/known-issues.md` § Gotchas.

**Still an issue:** any *widening* of the config — a new domain exception, `usesCleartextTraffic`
added to the manifest, a debug-overrides block leaking into release, or trust anchors added. Also
still an issue: a new `http://`/`ws://` **constant** in Kotlin, which is a hardcoded-host finding
regardless of this entry.

---

### EX-002 — `PinnedTrustManager.getAcceptedIssuers()` returns an empty array

**Flagged as:** "a trust manager that advertises no accepted issuers is broken / accepts
everything."

**Why it is not an issue:** it is required. Returning issuers puts the manager on OkHttp's
chain-cleaning path, which tries to build a chain up to a known root; a self-signed relay
certificate has none, and the connection then fails with `SSLPeerUnverifiedException` even though
the fingerprint matched. `CLAUDE.md` states this as a hard rule.

**Still an issue:** a `checkServerTrusted` that does not actually compare the SHA-256 pin, a
`checkServerTrusted` that swallows its exception, an `AllowAll` hostname verifier introduced
outside the pinned path, or the pin comparison moved off `CertificatePin.matches`.

---

### EX-003 — Four TLS lint checks disabled in `shared/build.gradle.kts`

**Flagged as:** "lint checks for custom trust managers were suppressed — a security regression."

**Why it is not an issue:** `CustomX509TrustManager`, `TrustAllX509TrustManager`,
`BadHostnameVerifier` and `AllowAllHostnameVerifier` fire on the hand-written pinned trust manager,
which is the whole point of `network/tls/PinnedTrust.kt`. `@SuppressLint` is Android-only and that
file now lives in `:shared`'s `jvmCommonMain`, so the suppression had to move into the module's
lint config, with its justification recorded there. `docs/known-issues.md` § Gotchas.

**Still an issue:** a *fifth* check disabled, the justification comment removed, or the disable
widened from `:shared` to `:app`.

---

### EX-004 — `:shared` uses the deprecated `com.android.library` plugin

**Flagged as:** "this Gradle plugin is deprecated; migrate to
`com.android.kotlin.multiplatform.library`."

**Why it is not an issue:** Kotlin's own deprecation warning suggests exactly that migration and
the build stays green either way — but switching makes Compose Multiplatform resources
(`composeResources/`, `Res.string.*`) silently stop packaging into the APK, and the app crashes at
launch with `MissingResourceException` (JetBrains CMP-9547). Verified for this repo by inspecting a
release APK. `CLAUDE.md` states this as a hard rule.

**Still an issue:** the plugin actually being switched (that is the defect, not the warning), or a
new module adopting `com.android.library` where it has no Compose resources and no reason to.

---

### EX-005 — The Glance widget is a toggle, not hold-to-talk

**Flagged as:** "the widget's press behaviour is inconsistent with the app's press-and-hold
gesture."

**Why it is not an issue:** RemoteViews deliver only discrete clicks. A home-screen widget cannot
observe press and release, so hold-to-talk is not expressible there. Real press-and-hold lives in
the app and the floating bubble. `docs/known-issues.md` § Gotchas; stated per-platform in
`docs/platform-support.md`.

**Still an issue:** the widget failing to *release* the floor on its second tap, the widget
inventing its own colour or wording instead of reading `ui/PttUiStatus`, or the app screen or the
bubble being converted to a toggle.

---

### EX-006 — `GlobalContext.get()` inside `PttWidget.provideGlance`

**Flagged as:** "service locator in application code — use constructor injection."

**Why it is not an issue:** it is the single documented exception. Glance gives no injection point
for `provideGlance`, and the call is wrapped in `runCatching`. `docs/conventions.md` § Kotlin names
it explicitly.

**Still an issue:** a second `GlobalContext.get()` anywhere else, the `runCatching` wrapper being
removed, or the pattern spreading to another Glance callback without the same justification.

---

### EX-007 — Desktop audio opens JavaSound's `"default"` line

**Flagged as:** "the desktop recorder should select a specific mixer rather than the default
line" / "add a fallback when capture returns silence."

**Why it is not an issue:** `AudioSystem.getLine(info)` is the only portable choice across a
user's actual machine. On a box where PulseAudio/PipeWire owns ALSA's `"default"`, that name
follows the audio server's default *source*, which may not be a microphone —
`isLineSupported` returns true, `open()`/`start()` succeed and `read()` returns a normal byte
count, so nothing in the API distinguishes it from a working device. The fix is in the audio
server's routing, not in this class. `docs/known-issues.md` § Gotchas and
`docs/audio-pipeline.md#desktop-capture--playback`.

**Still an issue:** a *hardcoded* mixer name introduced (that breaks portability), the line not
being closed on the failure path, or logging added inside the read/write loop — the per-frame rule
applies to `DesktopVoiceRecorder`/`DesktopVoicePlayer` like everywhere else.

---

### EX-008 — `app/proguard-rules.pro` keep rules are unreachable

**Flagged as:** "dead ProGuard configuration — `isMinifyEnabled = false`, so delete these rules."

**Why it is not an issue:** `:app`'s release build ships unminified (matching what F-Droid has
always distributed), so R8 does not run and the rules are dormant *by design*. They stay because a
minified `:app:assembleRelease` needs them and nothing in this repo's own code would suggest
either one: Ktor's `IntellijIdeaDebugDetector` references JVM-only
`java.lang.management` classes, and `androidx.work`'s `WorkDatabase` is instantiated reflectively.
Both were found by installing and launching a minified release build. `CLAUDE.md` and
`docs/known-issues.md` § Gotchas.

**Still an issue:** `isMinifyEnabled` actually being flipped to `true` without re-running the
install-and-launch check, or a keep rule being deleted.

---

### EX-009 — The default relay is an emulator address

**Flagged as:** "`relay.properties` points at `10.0.2.2`, which is not reachable from a real
device."

**Why it is not an issue:** there is no public test server for this project, and `10.0.2.2:8000`
is the emulator's route to the development machine — the useful default for the only environment
that exists today. It is a tracked *build setting*, not a constant, precisely so a fork with a
relay ships a working Default. `docs/known-issues.md` § Still open.

**Still an issue:** that address appearing anywhere *other* than `relay.properties` — in Kotlin, in
a test, or in a workflow — which is a hardcoded-host finding. Also still an issue: the file no
longer parsing strictly as `scheme://host:port`, or the value moving out of the tracked file where
F-Droid's property-free build cannot see it.

---

### EX-010 — `OverlayController` owns a second `CoroutineScope`

**Flagged as:** "a component-local coroutine scope duplicates the session scope."

**Why it is not an issue:** `WindowManager.addView`/`updateViewLayout`/`removeView` must run on the
main thread, and the session scope is on `Dispatchers.IO`. The main-thread scope is the mechanism,
not an oversight. `docs/conventions.md` § Android specifics and `docs/known-issues.md` § Gotchas.

**Still an issue:** that scope not being cancelled on teardown, a *third* scope appearing, or a
`WindowManager` call made from the IO scope after all.

---

### EX-011 — The same platform matrix appears in three documents

**Flagged as:** "`docs/platform-support.md`, `README.md` and `docs/index.html` duplicate the same
table — generate them from one source."

**Why it is not an issue:** deliberate. Each states the split in its own voice for a different
reader: the docs table for a contributor, the README for someone browsing the repo, the landing
page for a reader who will not open a Markdown file. `CLAUDE.md` requires a capability change to
land in all three in the same commit.

**Still an issue:** the three *disagreeing* — that is always a finding, and a change that updates
fewer than three of them is a Medium.

---

### EX-012 — An `expect`/`actual` pair looks like unnecessary indirection

**Flagged as:** "this `expect` has near-identical `actual`s — collapse it into a single common
implementation."

**Why it is not an issue:** two `actual`s can be textually similar and still be pinned to
different platform APIs (`javax.net.ssl` vs Security.framework, `AudioRecord` vs
`AVAudioEngine` vs `javax.sound.sampled`). The `jvmCommonMain` source set already collapses the
genuinely-shared JVM half; what is left below it is platform-specific by necessity. See
`CLAUDE.md` § Modules and `docs/architecture.md`.

**Still an issue:** an `expect` with a *missing* `actual` for a declared target, an `actual` in
`androidMain` and `desktopMain` that are byte-for-byte identical and use no Android/desktop-only
API (that belongs in `jvmCommonMain`), or an `expect` introduced for something `commonMain` can
already express.

---

### EX-013 — iOS pinning does not check the certificate's validity window

**Flagged as:** "the iOS trust callback accepts an expired certificate — security bug."

**Why it is not an issue as a review finding:** it is a **known, documented, open gap**, not
something this change introduced. `PttHttpClient.ios.kt`'s `handleChallenge` compares the SHA-256
of the presented DER against the stored pin and otherwise defers to
`NSURLCredential.credentialForTrust`; the JVM path does reject an expired-but-pinned certificate.
Closing it needs a DER `Validity` parser. Recorded in `docs/known-issues.md` § Still open.

**Still an issue:** a change that makes the iOS path *weaker* — comparing something other than the
whole-certificate DER, dropping the pin comparison, or trusting the challenge when
`leafCertificateDer` is null. And it is a legitimate finding again the moment a change claims to
close this gap but does not.

---

### EX-014 — Missing comments or KDoc on self-evident code

**Flagged as:** "add KDoc to this class / document this parameter."

**Why it is not an issue:** the default in this repo is no comment. Comments exist for genuinely
non-obvious *why* — a business rule, a framework workaround, the origin of a magic value, order
dependence a future reader could silently break. The dense KDoc that does exist (the version
forces, `PttLog`, `IosPttSessionLauncher`) is there because each records a constraint that would
otherwise be re-broken.

**Still an issue:** a comment the change makes **stale** — describing removed behaviour, an old
default, a renamed field. And a *missing* comment is still a finding on the four cases above,
particularly a new build-constraint force or a platform workaround.

---

### EX-015 — Look-alike code across platforms and modules

**Flagged as:** "`VoiceRecorder`, `DesktopVoiceRecorder` and `IosVoiceRecorder` share structure —
extract a shared base" / "`:app` and `:desktopApp` both do X."

**Why it is not an issue:** the platform implementations are deliberately independent so each can
follow its own platform API's lifecycle without a shared abstraction dictating it. The
platform-independent half already lives in `:shared`'s `commonMain`, and the shared JVM half in
`jvmCommonMain`; what remains below those is meant to look similar and evolve separately. Never
propose a cross-platform base class to centralise it.

**Still an issue:** a **copy-paste slip** — one implementation referencing the wrong constant,
field, or format where its siblings are right. Genuine duplication *within one source set*.
And any cross-platform **contract** that must agree — the protocol types, the `PttUiStatus`
mapping, `AudioConfig`, settings keys — where divergence is always a finding.
