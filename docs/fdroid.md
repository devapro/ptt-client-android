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
| `metadata/com.github.devapro.pttdroid.yml` | The recipe: licence, categories, links, build entry |
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

# 2. update the recipe's CurrentVersion / CurrentVersionCode, and add a Builds entry
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
`metadata/com.github.devapro.pttdroid.yml` — the file in this repository is already in their
format. F-Droid then builds from source on their own infrastructure and signs with their key, so
an app installed from f-droid.org and one installed from this project's repository have different
signatures and **cannot** upgrade to each other. Pick one and tell users which.

Their build requires the tagged commit to build with no network access beyond declared
dependencies and no non-free libraries. This app qualifies: everything it links is on Maven
Central under an OSI licence, and there are no Google Play services.

`AutoUpdateMode: Version` and `UpdateCheckMode: Tags` mean new tags are picked up without another
merge request, as long as `version.properties` and the tag agree.

## What is not automated

- Bumping `version.properties`, writing the changelog, and adding the `Builds` entry to the
  recipe. All three are release decisions, not mechanical steps.
- The fdroiddata merge request.
- Reproducible builds. F-Droid can verify that its own build of a tag matches a signed APK, which
  would let users install this project's build and still get official-repo updates. It needs the
  build to be byte-identical, which has not been checked here.
