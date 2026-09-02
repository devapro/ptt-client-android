# Publishing to F-Droid

Two routes, and they are not alternatives — the first ships today, the second takes review time.

| | Where it lands | Who signs the APK |
|---|---|---|
| **This project's own repository** | `https://<owner>.github.io/<repo>/fdroid/repo` | You do, with a key in CI secrets |
| **The official F-Droid catalogue** | f-droid.org | F-Droid does, after building from source |

Both read the same metadata, so nothing is written twice.

## What is where

| Path | Purpose |
|---|---|
| `version.properties` | The version. One line, and it is the source of truth |
| `relay.properties` | The relay **Settings → Relay → Default** dials. One line, read into `BuildConfig` at build time, so a fork ships an APK already pointing at its own relay |
| `metadata/com.github.devapro.pttdroid.yml` | The recipe: licence, categories, links, build entry. Kept byte-identical to the fdroiddata copy, and comment-free on purpose — see below |
| `fastlane/metadata/android/en-US/` | Everything a user reads — title, summary, description, changelogs, screenshots |
| `.github/workflows/release.yml` | Tag → signed APK → GitHub release → F-Droid repo → site |
| `.github/workflows/pages.yml` | Assembles the landing page and the F-Droid repo into one site |

Localized text lives only under `fastlane/`. F-Droid reads that layout directly, and the release
workflow copies it into the repository index, so there is one copy of the description rather than
two that drift.

## Releasing

```bash
# 1. bump the version and write its changelog
$EDITOR version.properties                                    # versionName=1.1.0
$EDITOR fastlane/metadata/android/en-US/changelogs/10100.txt   # major*10000 + minor*100 + patch

# 2. update the recipe's CurrentVersion / CurrentVersionCode, and add a Builds entry.
#    CurrentVersionCode is what F-Droid reads the new version code out of — see UpdateCheckData
#    below. The release workflow fails the release if this file disagrees, because getting it
#    wrong otherwise makes the release invisible to f-droid.org with no error to show for it.
$EDITOR metadata/com.github.devapro.pttdroid.yml

# 3. tag it
git commit -am "Release 1.1.0"
git tag v1.1.0
git push origin main v1.1.0
```

The workflow refuses the release if the tag and `version.properties` disagree, or if the
changelog for that versionCode is missing. Both checks exist because F-Droid builds a **plain
checkout of the tagged commit** with no Gradle properties passed in — a version that only exists
in CI would build as something else entirely, and the mismatch would surface as a broken update
on someone's phone rather than as a failed build.

versionCode is derived, never written down twice: `major*10000 + minor*100 + patch`, in both
`app/build.gradle.kts` and the workflow. It only has to increase, and this guarantees that for
any version below 100.100.100.

## One-time setup

### Keys

Two keys, and **neither may ever change**. Android refuses an update signed with a different app
key; F-Droid refuses an index signed with a different repo key. Losing either means every user
reinstalls by hand.

```bash
# the app signing key
keytool -genkeypair -v -keystore release.jks -alias pttdroid \
  -keyalg RSA -keysize 4096 -validity 10000

# the key that signs the repository index
keytool -genkeypair -v -keystore fdroid.p12 -storetype PKCS12 -alias pttrepo \
  -keyalg RSA -keysize 4096 -validity 10000

base64 -w0 < release.jks   # -> ANDROID_KEYSTORE_BASE64
base64 -w0 < fdroid.p12    # -> FDROID_KEYSTORE_BASE64
```

Back both files up somewhere that is not this repository, then add these under
**Settings → Secrets and variables → Actions**:

| Secret | |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of `release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | |
| `ANDROID_KEY_ALIAS` | `pttdroid` |
| `ANDROID_KEY_PASSWORD` | |
| `FDROID_KEYSTORE_BASE64` | base64 of `fdroid.p12` |
| `FDROID_KEYSTORE_PASSWORD` | |
| `FDROID_KEY_ALIAS` | `pttrepo` |
| `FDROID_KEY_PASSWORD` | |

`.gitignore` covers `*.jks`, `*.p12` and `*.keystore`. Keep it that way.

### Pages

**Settings → Pages → Source** must be **GitHub Actions**, not "Deploy from a branch". While it is
set to a branch, the workflow's deployments are ignored and the F-Droid repository never appears.

The site is assembled from two places every deploy — `docs/` for the landing page, and the
`fdroid-repo` branch for the repository — because a Pages deployment replaces the whole site.
Publishing the landing page alone would delete the repository out from under everyone who had
added it.

### The fdroid-repo branch

Created by the first release. It is an orphan branch holding published binaries and the signed
index; it has no source history and is not meant to be merged anywhere. It grows by one APK per
release, which is the point — an F-Droid repository is cumulative, and removing an old version
breaks anyone still on it.

## Installing from the repository

In the F-Droid app: **Settings → Repositories → +**, then the URL the release workflow prints in
its job summary. Check the fingerprint it shows against the `Creating signed index with this key`
line in the same run's log; that is the only thing tying the repository to this project.

## Submitting to the official catalogue

Open a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding
`metadata/com.github.devapro.pttdroid.yml`. That file is a **verbatim copy** of the one in this
repository — copy it across, do not retype it — and it is kept in the exact shape their CI
demands, which is why it carries no YAML comments and why its fields are in an order that looks
arbitrary:

- `fdroid rewritemeta` runs on every merge request and fails the pipeline on any diff. It strips
  YAML comments and reorders fields into `fdroidserver`'s canonical order, so a comment or a
  field out of place is a red pipeline. Anything that needs explaining goes in `MaintainerNotes`,
  which is a metadata field and survives, or in this document.
- `fdroid lint` and `fdroid checkupdates --auto` also run, and `checkupdates` fails the pipeline
  if it would change the file at all. `AutoName` therefore has to match what `fetch_real_name`
  reads out of `app/src/main/res/values/strings.xml` (`PTTdroid`), or checkupdates rewrites it.

Check all three before opening the merge request, from a checkout of your fdroiddata fork:

```bash
pip install fdroidserver
fdroid lint com.github.devapro.pttdroid
fdroid rewritemeta com.github.devapro.pttdroid && git diff --exit-code   # must be empty
fdroid checkupdates --auto -v com.github.devapro.pttdroid && git diff --exit-code
```

F-Droid then builds from source on their own infrastructure and signs with their key, so an app
installed from f-droid.org and one installed from this project's repository have different
signatures and **cannot** upgrade to each other. Pick one and tell users which.

Their build requires the tagged commit to build with no network access beyond declared
dependencies and no non-free libraries. This app qualifies: everything it links is on Maven
Central under an OSI licence, and there are no Google Play services.

### Why the recipe needs `UpdateCheckData`

`AutoUpdateMode: Version` and `UpdateCheckMode: Tags` mean new tags are picked up without another
merge request — but not on their own here. `fdroidserver` finds an app's version by grepping the
Gradle files for literals, and this repo has none to find: `versionCode` and `versionName` both
come from `version.properties` through `gradle/relay-defaults.gradle.kts`. Checked directly
against fdroidserver 2.4.5, `parse_androidmanifests(['app/build.gradle.kts'])` returns
`('Unknown', None, …)`, and `checkupdates` then dies with "Couldn't find any version information"
— a red pipeline on a tag that builds perfectly well.

`UpdateCheckData` is what closes that gap. It names two files in the *tagged app checkout* and a
regex for each:

```
UpdateCheckData: metadata/com.github.devapro.pttdroid.yml|CurrentVersionCode:\s*(\d+)|version.properties|versionName=([0-9.]+)
```

so `versionName` comes from `version.properties` and `versionCode` from this repository's own
copy of the recipe. **That makes step 2 of the release above load-bearing rather than tidy:** if
`CurrentVersionCode` is not bumped in the commit the tag points at, F-Droid reads the old number,
decides nothing has changed, and the release never appears — a failure with no error anywhere,
since the tag builds and ships perfectly well.

Because that failure is silent, the release workflow checks it (`Check the F-Droid recipe against
version.properties`, alongside the tag check) and refuses the release on a disagreement. It
compares `CurrentVersion` and `CurrentVersionCode` against `version.properties`, and requires a
`Builds` entry for this version whose `versionName` and `commit` match and which is not
`disable`d — the whole set F-Droid needs to see the release, not just the one field.

The `:\s*` in that regex is not decoration either. A literal `": "` inside an unquoted YAML
value ends the scalar, so the plain-`:` form fails to parse and `fdroid lint` rejects the file.

## Verifying the plain-checkout build still works (Compose Multiplatform migration)

Splitting the app into `:app`/`:shared`/`:desktopApp` (see `docs/architecture.md`) is exactly the
kind of change that could quietly break F-Droid's plain-checkout build even while every other gate
stays green, since F-Droid builds with **no Gradle properties and no environment variables set**.
Checked directly, not assumed:

```bash
env -u PTT_KEYSTORE_PATH -u PTT_DEFAULT_RELAY ./gradlew clean :app:assembleRelease
```

- Produces an unsigned APK (expected — no keystore env vars).
- `BuildConfig.DEFAULT_RELAY_*` and versionName/versionCode in that APK match `relay.properties`
  and `version.properties` exactly (checked with `aapt dump badging` and by grepping the dex for
  the relay host string).
- `:shared`'s classes and its `composeResources/` assets are both present in the APK
  (`assets/composeResources/com.github.devapro.pttdroid.shared.resources/…`) — the packaging
  failure mode a wrong Gradle plugin choice on `:shared` produces (see `docs/build-and-run.md`)
  is release-build-shaped risk, not just a debug-build one, so this was checked on the actual
  release APK, not just `assembleDebug`.
- The signed release APK installs and **launches** on a device/emulator without crashing. This was
  also checked with `isMinifyEnabled = true` during an earlier phase, before that setting was
  reverted to match what `main` (and F-Droid) has always shipped — see `docs/known-issues.md`'s
  R8/WorkManager gotcha for the one real issue that surfaced and how it was fixed, kept in
  `app/proguard-rules.pro` in case minification is turned back on.

## What is not automated

- Bumping `version.properties`, writing the changelog, and adding the `Builds` entry to the
  recipe. All three are release decisions, not mechanical steps — the workflow verifies that they
  agree with each other, but it will not write them for you.
- The fdroiddata merge request.
- Reproducible builds. F-Droid can verify that its own build of a tag matches a signed APK, which
  would let users install this project's build and still get official-repo updates. It needs the
  build to be byte-identical, which has not been checked here.
