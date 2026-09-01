#!/usr/bin/env bash
# Entry point for the omp container.
#
# Default entrypoint baked into the image (Dockerfile ENTRYPOINT). The omp
# compose file (docker-compose.omp.yml) overrides this with an inline script
# that installs omp on first start; this file exists so the image builds
# (Dockerfile COPYs it) and so `docker run` without compose still does
# something sensible — install omp if missing, then exec it.

set -eu

# Re-export PATH with the Android SDK dirs. The Dockerfile sets these via ENV
# PATH, but if this script is invoked through a login shell (bash -l) Debian's
# /etc/profile resets PATH and wipes them — adb lives at $ANDROID_HOME/platform-tools.
export PATH="${HOME}/.local/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# --- Gradle heap cap ---------------------------------------------------------
# The project's gradle.properties asks for -Xmx4096m for the Gradle daemon.
# With Docker Desktop's default VM that is fine, but on a constrained host the
# daemon can be OOM-killed. _JAVA_OPTIONS is appended AFTER explicit -Xmx flags,
# so the last value wins for every forked JVM (launcher, daemon, Kotlin daemon,
# test workers). Same approach as the screenshots stack.
: "${OMP_JVM_HEAP:=4g}"
: "${OMP_JVM_METASPACE:=768m}"
if [ -z "${_JAVA_OPTIONS:-}" ]; then
    export _JAVA_OPTIONS="-Xmx${OMP_JVM_HEAP} -XX:MaxMetaspaceSize=${OMP_JVM_METASPACE}"
fi

# --- Gradle worker cap -------------------------------------------------------
# Unset means "use whatever the project asks for" (no workers.max in this
# repo's gradle.properties, so Gradle picks based on CPU count). Set it together
# with OMP_CPUS when the container is CPU-capped, to avoid oversubscribing.
if [ -n "${OMP_GRADLE_WORKERS:-}" ]; then
    export GRADLE_OPTS="${GRADLE_OPTS:+${GRADLE_OPTS} }-Dorg.gradle.workers.max=${OMP_GRADLE_WORKERS}"
fi

# --- Robolectric skip list (arm64 only) --------------------------------------
# Robolectric publishes no native runtime for linux/aarch64, so on an arm64
# container its View-touching tests cannot run. Link the init script that
# excludes them (and logs how many were skipped) so `testDebugUnitTest` reports
# a truthful result instead of a platform failure. No-op on amd64.
if [ "$(uname -m)" = "aarch64" ] || [ "$(uname -m)" = "arm64" ]; then
    if [ -f /opt/gradle-init/skip-robolectric.gradle ]; then
        mkdir -p "${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
        cp /opt/gradle-init/skip-robolectric.gradle \
            "${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/skip-robolectric.gradle"
    fi
fi

# --- omp install on first start ---------------------------------------------
# omp is not baked into the image. The native installer drops the binary at
# /home/node/.local/bin/omp, which lives on a persistent named volume, so this
# only downloads once. Run `omp update` inside the container to upgrade.
if ! command -v omp >/dev/null 2>&1; then
    curl -fsSL https://omp.sh/install | sh
fi

exec omp "$@"
