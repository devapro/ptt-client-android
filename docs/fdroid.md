# Publishing to F-Droid

**One route: the official catalogue.** F-Droid builds from source on their own infrastructure and
signs with their key, so this project ships them a recipe, never a binary.

| | Where it lands | Who signs the APK |
|---|---|---|
| **The official F-Droid catalogue** | f-droid.org | F-Droid does, after building from source |
| **GitHub releases** | the release page for each tag | You do, with the app key in CI secrets |

There used to be a second route — a self-hosted F-Droid repository published to GitHub Pages at
`https://<owner>.github.io/<repo>/fdroid/repo`. It was **removed before it ever published
anything**; see [No self-hosted repository](#no-self-hosted-repository) for why and for what
bringing it back would cost.

## What is where

| Path | Purpose |
|---|---|
| `version.properties` | The version. One line, and it is the source of truth |
| `relay.properties` | The relay **Settings → Relay → Default** dials. One line, read into `BuildConfig` at build time, so a fork ships an APK already pointing at its own relay |
| `metadata/com.github.devapro.pttdroid.yml` | The recipe: licence, categories, links, build entry. Kept byte-identical to the fdroiddata copy on `main` (the `commit` field is the one exception inside a tag — see below), and comment-free on purpose |
| `fastlane/metadata/android/en-US/` | Everything a user reads — title, summary, description, changelogs, screenshots |
| `.github/workflows/release.yml` | Tag → signed APK → GitHub release |
| `.github/workflows/pages.yml` | Publishes the landing page from `docs/` |

Localized text lives only under `fastlane/`. F-Droid reads that layout directly, and the release
workflow copies it into the repository index, so there is one copy of the description rather than
two that drift.

## Releasing

```bash
# 1. bump the version and write its changelog
$EDITOR version.properties                                    # versionName=1.1.0
$EDITOR fastlane/metadata/android/en-US/changelogs/10100.txt   # major*10000 + minor*100 + patch

# 2. update the recipe's CurrentVersion / CurrentVersionCode, and add a Builds entry whose
#    commit is the tag name (v1.1.0) — the commit being tagged cannot contain its own hash.
#    CurrentVersionCode is what F-Droid reads the new version code out of — see UpdateCheckData
#    below. The release workflow fails the release if this file disagrees, because getting it
#    wrong otherwise makes the release invisible to f-droid.org with no error to show for it.
$EDITOR metadata/com.github.devapro.pttdroid.yml

# 3. tag it
git commit -am "Release 1.1.0"
git tag v1.1.0
git push origin main v1.1.0

# 4. now that the tag resolves, pin the hash in both copies of the recipe: this one and the
#    fdroiddata merge request's. fdroiddata rejects a tag or branch name in `commit`.
git rev-parse v1.1.0^{commit}
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

### The signing key

One key, and it **may never change**: Android refuses an update signed with a different app key,
so losing it means every user reinstalls by hand. It signs the APK attached to each GitHub
release. It is *not* used for f-droid.org — F-Droid signs their own build with their own key.

```bash
keytool -genkeypair -v -keystore release.jks -alias pttdroid \
  -keyalg RSA -keysize 4096 -validity 10000

base64 -w0 < release.jks   # -> ANDROID_KEYSTORE_BASE64
```

Back the file up somewhere that is not this repository, then add these under
**Settings → Secrets and variables → Actions**. All four are required — the workflow checks for
all of them up front, because an unset secret arrives as an empty string and the build otherwise
fails a minute and a half later inside `:app:packageRelease` with a message about the keystore
password rather than about the secret that is missing:

| Secret | |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of `release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | |
| `ANDROID_KEY_ALIAS` | `pttdroid` |
| `ANDROID_KEY_PASSWORD` | |

`.gitignore` covers `*.jks`, `*.p12` and `*.keystore`. Keep it that way.

### Pages

**Settings → Pages → Source** must be **GitHub Actions**, not "Deploy from a branch". While it is
set to a branch, the workflow's deployments are ignored and the landing page never updates.

## No self-hosted repository

This project publishes **no F-Droid repository of its own**, deliberately. The workflow that did
so was removed while the `fdroid-repo` branch did not yet exist and no release had ever been
published through it, so nobody was ever on that channel.

The reason is signatures. Because reproducible builds are declined (see below), an APK from
f-droid.org is signed by F-Droid and an APK from a self-hosted repository is signed by the app
key here. Android treats those as unrelated apps: a user on one **cannot** upgrade to the other
without uninstalling and losing their settings. Running both channels means permanently splitting
the user base, and the split only gets more expensive to undo the longer it runs — whereas
standing the repository up later costs nothing, because until someone adds the URL it has no
users to strand.

Bringing it back, should the catalogue route ever fall through, means: an `fdroid` job in
`release.yml` that runs `fdroid update` over an orphan `fdroid-repo` branch, the F-Droid half of
`pages.yml` that merges that branch into the site deployment, a second keystore
(`keytool -genkeypair -keystore fdroid.p12 -storetype PKCS12 -alias pttrepo …`) to sign the
index, and four more secrets — `FDROID_KEYSTORE_BASE64`, `FDROID_KEYSTORE_PASSWORD`,
`FDROID_KEY_ALIAS`, `FDROID_KEY_PASSWORD`. Both were deleted rather than left disabled, on the
grounds that a dormant publishing pipeline nobody runs is a pipeline nobody notices has rotted;
`git log -- .github/workflows/release.yml` has the working version if it is wanted back.

## Installing

From the [GitHub release](https://github.com/devapro/ptt-client-android/releases) for the tag:
each one carries a signed APK, plus the desktop installers. Once the catalogue merge request
lands, f-droid.org is the better route for phones — it updates automatically.

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
pip install fdroidserver 'ruamel.yaml==0.18.10'
fdroid lint com.github.devapro.pttdroid
fdroid rewritemeta com.github.devapro.pttdroid && git diff --exit-code   # must be empty
fdroid checkupdates --auto -v com.github.devapro.pttdroid && git diff --exit-code
```

**Pin `ruamel.yaml` to 0.18.x, or `rewritemeta` will pass here and fail on their CI.**
`write_yaml()` in `fdroidserver/metadata.py` builds `ruamel.yaml.YAML(typ='rt')` without setting
`width`, so it inherits ruamel's default of 80 columns. `UpdateCheckData`'s value is about 120
characters with no spaces in it, which ruamel 0.18 cannot fold — it breaks after the key instead
and puts the value on its own indented line, while ruamel 0.17 leaves it alone. Their CI image
has 0.18; a plain `pip install fdroidserver` may not. This cost a review round.

F-Droid then builds from source on their own infrastructure and signs with their key, so an app
installed from f-droid.org and the APK attached to a GitHub release here have different signatures
and **cannot** upgrade to each other. This is the same constraint that argues against a
self-hosted repository, above; for the GitHub release APK it is accepted, because a sideloaded
build is a one-off rather than a subscribed update channel.

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
`Builds` entry for this version that is not `disable`d, whose `versionName` matches, and whose
`commit` is either the tag name `v<version>` or **the full 40-character hash that tag points at** —
if it is a hash, the check resolves the tag and fails when the two disagree, because F-Droid builds
the pinned hash and would otherwise ship a different commit than the one released.

### Why the two copies of the recipe differ on `commit`, briefly

fdroiddata review requires a full commit hash rather than a tag or branch name. A commit cannot
contain its own hash, so the copy **inside the tagged commit** can only carry `commit: v<version>`.
The sequence is therefore: tag with the tag name in place, then update both copies — this one on
`main` and fdroiddata's — to the hash the tag resolved to. That is why the release workflow accepts
either form, and why the two copies are byte-identical on `main` but not inside the tag.

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
