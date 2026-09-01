# Running omp in Docker

This folder runs [Oh My Pi (omp)](https://omp.sh) in a container with the **full
Android toolchain** (JDK 21 + Android SDK + Node 22 + Kotlin LSP), bind-mounted
to the repo at `../`. No Android emulator is started; reach a host-connected
device/emulator over the ADB bridge if needed (see `--with-adb`).

Files in this folder:

- `start.sh` — builds the image if needed and drops you into an interactive omp
  session. Loads `.env` and resolves arch/volumes for you.
- `Dockerfile` — omp image with JDK 21 (Temurin), Android SDK (cmdline-tools +
  platform-tools + build-tools 36.0.0 + platforms android-36), Node 22, `adb`,
  `gh`, Kotlin LSP, `tini`, and `mcp-devices` (mobile automation MCP server,
  Android platform pre-enabled). Builds for amd64 (default) or arm64; arm64
  additionally installs the x86_64 runtime libs the x86_64-only Android binaries
  need. omp itself is NOT baked in — installed at first start (see below).
- `entrypoint.sh` — default image entrypoint. Installs omp on first start if
  missing, then execs it. The compose file overrides this with an inline
  equivalent; this file exists so the image builds and `docker run` without
  compose still works.
- `gradle-init-skip-robolectric.gradle` — Gradle init script linked into
  `~/.gradle/init.d` on arm64 containers only. Excludes Robolectric/Roborazzi
  test classes (no linux/aarch64 native runtime upstream) and logs how many
  were skipped; also opts `Test` tasks out of state tracking, since `build/` is
  shared with the host and Gradle's up-to-date key ignores OS/arch.
- `docker-compose.omp.yml` — single-service stack: `omp`.
- `.env.example` — template for host-specific config (paths, tokens, arch).
  Copy to `.env` and edit.

Image size is ~2.5 GB after build (JDK + Android SDK + Node + Kotlin LSP +
mcp-devices).

---

## Mobile automation (mcp-devices)

The image bundles [`mcp-devices`](https://www.npmjs.com/package/mcp-devices)
(modular edition, v4.1) with the **Android platform pre-enabled** — the
`mcp-devices install android` step runs at build time, baking
`~/.mcp-devices/config.json` (`{"platforms":["android"]}`) into the image. The
binary is at `/usr/local/bin/mcp-devices` (on PATH).

omp discovers the server via `/workspace/.mcp.json` (committed in the repo root,
no secrets — just a command reference):

```json
{
  "mcpServers": {
    "mobile": {
      "command": "mcp-devices",
      "env": { "MOBILE_PROFILE": "android" }
    }
  }
}
```

This gives omp ~20 mobile-automation tools (screenshot, tap/swipe/type, UI
tree, app launch, shell, SharedPreferences/SQLite access, sensor/network
simulation, deep linking). Ask omp *"take a screenshot of my Android device"*
and it works.

### Prerequisites

- **`adb`** — already in the image (Android platform-tools). The entrypoint
  re-exports PATH so mcp-devices' adb calls resolve.
- **A connected device/emulator** — mcp-devices talks to the same adb server as
  the `adb` CLI, so use the ADB bridge: `./docker/start.sh --with-adb`. Without
  a device, `mcp-devices doctor android` still reports `ok (adb)` but device
  tools will list no targets.

### Verify inside the container

```bash
mcp-devices platforms          # "Enabled: android"
mcp-devices doctor android     # "android: ok (adb)"
mcp-devices --help
```

### Adding more platforms (ephemeral)

iOS/Web/Desktop/Aurora plugins are not installed (this repo is Android-only).
To try one inside a running container (lost on `--rm`):

```bash
npm i -g @mcp-devices/plugin-web
mcp-devices install web
```

To persist a platform across containers, bake it into the image: add the
package to the `npm install -g` line in `Dockerfile` and the platform name to
the `mcp-devices install` line, then `./docker/start.sh --rebuild`.

---

## Connecting to a host emulator or device

This stack starts no emulator. To use a host-connected Android emulator (AVD
started via `emulator`) or a USB device from inside the container, pass
`--with-adb`:

```bash
# 1. On the host, start your emulator (or plug in a device):
emulator -avd Pixel_8 -no-snapshot-load &

# 2. Then launch omp with the ADB bridge:
./docker/start.sh --with-adb
```

`--with-adb` bridges the host adb server into the container. The host adb
server binds `127.0.0.1` only, so on Linux `--with-adb` starts an `adb-proxy`
sidecar (socat on the host network) forwarding `0.0.0.0:5038 → 127.0.0.1:5037`,
and points the omp container at port `5038` via `host.docker.internal`. On
macOS, `adb -a` can bind `0.0.0.0` and port `5037` is used directly. The
container's entrypoint probes the bridge on startup and lists devices — you
should see something like:

```
[entrypoint] Host ADB reachable. Devices:
  List of devices attached
  emulator-5554   device
```

Inside the container, `adb` works exactly as on the host:

```bash
adb devices
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.github.devapro.pttdroid/.MainActivity
ANDROID_SERIAL=emulator-5554 ./gradlew :shared:connectedDebugAndroidTest
```

If you forget `--with-adb`, the container still starts — the entrypoint prints
a hint instead of failing, since unit tests / lint / `assembleDebug` don't need
a device. To bring up the bridge manually on Linux without `start.sh`:

```bash
adb start-server
docker compose -f docker/docker-compose.omp.yml --profile adb up -d adb-proxy
ADB_PORT=5038 docker compose -f docker/docker-compose.omp.yml run --rm omp
```

### Custom gateway

On Linux without Docker Desktop, `host.docker.internal` is mapped to
`host-gateway` by the `extra_hosts` entry in the compose file, so it works out
of the box. To point at a different host (e.g. an emulator on another machine),
set in `docker/.env`:

```bash
ADB_HOST_GATEWAY=192.168.1.50
```

---

## Quick start

```bash
cp docker/.env.example docker/.env
# edit docker/.env: set HOST_HOME to your home directory
./docker/start.sh
# …work with omp…
# exit with /exit or Ctrl-D — container is removed automatically (--rm)
```

### Flags

| Flag | Description |
|------|-------------|
| `--rebuild` | Force a rebuild of `ptt-omp:local-<arch>` before running. |
| `--yolo` | Pass `--dangerously-skip-permissions` to omp (no confirmations). |
| `--arch amd64\|arm64` | Container architecture. Default `amd64`; use `arm64` on Apple Silicon for native speed. |
| `--with-adb` | Re-launch the host adb server on `0.0.0.0:5037` so the container can reach a host-connected device via `host.docker.internal`. |

### Manual equivalent (no start.sh)

```bash
export HOST_UID="$(id -u)" HOST_GID="$(id -g)"
docker compose -f docker/docker-compose.omp.yml build
docker compose -f docker/docker-compose.omp.yml run --rm omp
```

---

## What's in the omp image

| Component | Version | Location / env |
|-----------|---------|----------------|
| Node.js | 22 (from `node:22-bookworm`) | `/usr/local/bin/node` |
| JDK | 21 (Eclipse Temurin) | `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk` |
| Android SDK cmdline-tools | latest (11076708) | `ANDROID_HOME=/opt/android-sdk` |
| Android platform-tools | latest | `$ANDROID_HOME/platform-tools` (on `PATH`) |
| Android build-tools | 36.0.0 | `$ANDROID_HOME/build-tools/36.0.0` |
| Android platform | android-36 | `$ANDROID_HOME/platforms/android-36` |
| `adb` | from platform-tools | on `PATH` |
| `gh` (GitHub CLI) | latest from cli.github.com apt repo | `/usr/bin/gh` |
| Kotlin LSP | 262.4739.0 | `/usr/local/bin/kotlin-lsp` |
| Gradle | installed per-project via `./gradlew` | cache at `GRADLE_USER_HOME=/home/node/.gradle` (named volume) |
| omp | latest via official installer (`curl -fsSL https://omp.sh/install \| sh`), at first start | `/home/node/.local/bin/omp` (on `PATH`) |
| `mcp-devices` | 4.1.0 (npm global) | `/usr/local/bin/mcp-devices` (on `PATH`); Android platform pre-enabled via `~/.mcp-devices/config.json` |

Gradle is not pre-installed globally — projects run their wrapper, which
downloads the correct version and caches it in the `omp-gradle-cache` named
volume for fast rebuilds.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ docker compose -f docker/docker-compose.omp.yml              │
│                                                              │
│  ┌────────────────────────────────┐                          │
│  │  omp                            │                          │
│  │  omp CLI + JDK 21 + Android SDK │                          │
│  │  + Node 22 + Kotlin LSP         │                          │
│  └────────────────────────────────┘                          │
│              │        │                                       │
│         /workspace   named volumes                            │
│         (repo)       (omp-home, omp-local,                    │
│                       omp-gradle-cache, omp-android-cache,    │
│                       gh-config)                              │
└──────────────────────────────────────────────────────────────┘
```

---

## Prerequisites

> **Recommended: [Docker Desktop](https://www.docker.com/products/docker-desktop/)**
> bundles the Docker daemon, the modern `docker compose` plugin, and credential
> helpers in a single installer.

- **Docker Desktop 4.x+** (recommended) — or Docker Engine 20.10+ with the
  `docker compose` plugin **or** legacy `docker-compose` v1.
- **Apple Silicon Macs (M1–M5):** `./docker/start.sh --arch arm64` runs native
  (no emulation, faster Gradle/JVM, latest omp). Robolectric/Roborazzi tests
  are excluded by the gradle init script — run those on the macOS host or in
  CI. The default `amd64` works under Rosetta but is slower.
- Network access at build time (the Dockerfile pulls the Adoptium JDK, Android
  SDK pieces, and Kotlin LSP) and at first run (omp installer).
- A provider API key in env **or** an omp subscription for interactive login.
  Pass keys via `docker/.env` (see `.env.example`); persisted OAuth/config lives
  under `/home/node/.omp` (bind-mount `OMP_DOCKER_HOME` to reuse host logins).

---

## First-time setup

### 1. Copy and edit the environment file

```bash
cp docker/.env.example docker/.env
```

Open `docker/.env` and set at minimum:

```bash
HOST_HOME=/Users/YOUR_USER        # your actual home directory
```

### 2. Provider keys / omp login

omp supports many providers. Either:

- put direct API keys in `docker/.env` (e.g. `ANTHROPIC_API_KEY=...`), or
- leave them blank and complete omp's interactive OAuth login on first run
  (persisted under `/home/node/.omp` — bind-mount `OMP_DOCKER_HOME` to your
  host `~/.omp` to reuse logins across containers).

### 3. GitHub token (required for `gh` CLI)

Generate a **GitHub Personal Access Token (PAT)** with at least the `repo`,
`read:org`, and `gist` scopes, then set it in `docker/.env`:

```bash
GH_TOKEN=ghp_your_token_here
```

Either `GH_TOKEN` or `GITHUB_TOKEN` works — `GH_TOKEN` is preferred.

### 4. Verify the configuration

Start the container and run inside:

```bash
gh auth status      # check that gh has access to GitHub
omp --help          # check that omp is installed and on PATH
```

---

## Notes for this repo

- **compileSdk is capped at 36** (see `CLAUDE.md` hard rule). The image bakes
  `build-tools;36.0.0` and `platforms;android-36`; do not raise these without
  raising the repo's `compileSdk` first, or Gradle will re-download on every
  start.
- **No emulator is started by this stack.** For instrumented tests, connect a
  host device/emulator over ADB (`./docker/start.sh --with-adb`) — see

[Showing lines 1-300 of 306. Use :301 to continue]