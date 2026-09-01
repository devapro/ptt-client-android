#!/usr/bin/env bash
# Start the omp Docker stack: builds the image if needed and launches an
# interactive omp session against the repo at ../.
#
# Usage: ./docker/start.sh [--rebuild] [--yolo] [--arch amd64|arm64] [--with-adb]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.omp.yml"

# --- Pick docker compose command (v2 plugin or legacy v1) ---
if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
else
    echo "error: docker compose not found (need Docker Compose v2 plugin or legacy v1)" >&2
    exit 1
fi

# --- Parse flags ---
REBUILD=0
YOLO=0
WITH_ADB=0
ARCH_FLAG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --rebuild) REBUILD=1 ;;
        --yolo)    YOLO=1 ;;
        --with-adb) WITH_ADB=1 ;;
        --arch)
            ARCH_FLAG="$2"; shift
            ;;
        --arch=*) ARCH_FLAG="${1#--arch=}" ;;
        -h|--help)
            cat <<EOF
Usage: $0 [--rebuild] [--yolo] [--arch amd64|arm64] [--with-adb]

  --rebuild     Force a rebuild of the ptt-omp image before running.
  --yolo        Pass --dangerously-skip-permissions to omp (no confirmations).
  --arch <a>    Container architecture (amd64 default, or arm64 on Apple Silicon).
  --with-adb    Re-launch the host adb server on 0.0.0.0 so the container can
                reach a host-connected device/emulator via host.docker.internal.
EOF
            exit 0
            ;;
        *)
            echo "error: unknown flag: $1" >&2
            exit 1
            ;;
    esac
    shift
done

export HOST_UID="$(id -u)"
export HOST_GID="$(id -g)"

# --- Load .env if present ---
# Bash gets ${HOME} expansion (compose's own .env loader does not), so users
# can write paths like ${HOME}/Projects/... in .env and have them work in both
# shell and compose contexts.
ENV_FILE="${SCRIPT_DIR}/.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
fi

# --- Defaults for anything not set in .env ---
GH_CONFIG_HOME_DIR="${GH_CONFIG_HOME:-$HOME/.config/gh}"
OMP_DOCKER_HOME="${OMP_DOCKER_HOME:-$HOME/.omp}"

# --- Resolve container architecture (precedence: --arch > .env > default) ---
OMP_ARCH="${ARCH_FLAG:-${OMP_ARCH:-amd64}}"
case "$OMP_ARCH" in
    amd64|arm64) ;;
    *) echo "error: OMP_ARCH must be amd64 or arm64, got: $OMP_ARCH" >&2; exit 1 ;;
esac

# Image tag, Gradle cache and omp install live per architecture so the two
# stacks never share arch-specific binaries. amd64 keeps the original volume
# names, so existing setups are untouched.
if [ "$OMP_ARCH" = "amd64" ]; then
    GRADLE_CACHE_VOLUME="omp-gradle-cache"
    LOCAL_VOLUME="omp-local"
else
    GRADLE_CACHE_VOLUME="omp-gradle-cache-arm64"
    LOCAL_VOLUME="omp-local-arm64"
fi
export OMP_ARCH GRADLE_CACHE_VOLUME LOCAL_VOLUME

# Ensure bind-mount targets exist on the host BEFORE compose runs. Otherwise
# Docker creates them as root and the non-root container user can't write.
mkdir -p "$OMP_DOCKER_HOME" "$GH_CONFIG_HOME_DIR"
export OMP_DOCKER_HOME GH_CONFIG_HOME="$GH_CONFIG_HOME_DIR"

# --- Optional: bridge host's adb server into the container ---
# The host adb server binds 127.0.0.1 only (recent platform-tools refuses
# non-loopback binds), so the container can't reach it directly. On Linux we
# run an `adb-proxy` sidecar (socat, host network) forwarding 0.0.0.0:5038 ->
# 127.0.0.1:5037, and point the omp container at port 5038. On macOS, `adb -a`
# can bind 0.0.0.0 and we use 5037 directly.
if [ "$WITH_ADB" -eq 1 ]; then
    if ! command -v adb >/dev/null 2>&1; then
        echo "error: --with-adb requested but adb not found on host PATH" >&2
        exit 1
    fi
    # Ensure the host adb server is up (on 127.0.0.1:5037).
    adb start-server >/dev/null 2>&1 || true
    if ! adb devices >/dev/null 2>&1; then
        echo "error: host adb server did not start" >&2
        exit 1
    fi
    echo "▸ Host adb devices:"
    adb devices | sed 's/^/  /'

    # Try `adb -a` (bind 0.0.0.0). Works on macOS; on Linux recent adb silently
    # keeps 127.0.0.1, so detect that and fall back to the socat proxy on 5038.
    adb kill-server >/dev/null 2>&1 || true
    adb -a nodaemon server start >/dev/null 2>&1 &
    sleep 1
    adb_start_ok=0
    if adb devices >/dev/null 2>&1; then
        if command -v ss >/dev/null 2>&1; then
            ss -ltn 2>/dev/null | grep -qE '0\.0\.0\.0:5037' && adb_start_ok=1
        elif command -v lsof >/dev/null 2>&1; then
            lsof -nP -iTCP@0.0.0.0:5037 -sTCP:LISTEN 2>/dev/null | grep -q . && adb_start_ok=1
        fi
    fi

    if [ "$adb_start_ok" -eq 1 ]; then
        echo "▸ Host adb bound 0.0.0.0:5037 (direct bridge)."
        export ADB_PORT=5037
    else
        # Fall back: restart adb on loopback, then start the socat proxy on 5038.
        adb kill-server >/dev/null 2>&1 || true
        adb start-server >/dev/null 2>&1 || true
        sleep 1
        echo "▸ Host adb won't bind 0.0.0.0 — starting adb-proxy sidecar (0.0.0.0:5038 -> 127.0.0.1:5037) ..."
        "${COMPOSE[@]}" -f "$COMPOSE_FILE" --profile adb up -d --remove-orphans adb-proxy
        # Wait for the proxy port to accept connections.
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            if (command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -qE ':5038\s') \
               || (command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:5038 -sTCP:LISTEN 2>/dev/null | grep -q .); then
                break
            fi
            sleep 1
        done
        export ADB_PORT=5038
    fi
fi


# --- Optional rebuild ---
if [ "$REBUILD" -eq 1 ]; then
    echo "▸ Rebuilding ptt-omp:local-${OMP_ARCH} ..."
    CACHEBUST="$(date +%s)" "${COMPOSE[@]}" -f "$COMPOSE_FILE" build \
        --build-arg CACHEBUST="$CACHEBUST" omp
fi

# --- Launch omp interactively ---
OMP_ARGS=()
if [ "$YOLO" -eq 1 ]; then
   OMP_ARGS+=(--dangerously-skip-permissions)
fi

echo "▸ Launching omp (exit with /exit or Ctrl-D) ..."
exec "${COMPOSE[@]}" -f "$COMPOSE_FILE" run --rm omp "${OMP_ARGS[@]+"${OMP_ARGS[@]}"}"
