FROM node:22-bookworm

# Base OS deps + Adoptium repo for JDK 21 + GitHub CLI + common tools.
# tini provides a proper PID 1 so Ctrl-C works inside the container.
RUN apt-get update && apt-get install -y --no-install-recommends \
      git \
      curl \
      wget \
      unzip \
      zip \
      ca-certificates \
      openssh-client \
      ripgrep \
      jq \
      less \
      tini \
      gnupg \
  && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
       | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
  && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
       > /etc/apt/sources.list.d/adoptium.list \
  && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
       | gpg --dearmor -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
  && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
       > /etc/apt/sources.list.d/github-cli.list \
  && apt-get update && apt-get install -y --no-install-recommends \
      temurin-21-jdk \
      gh \
  && ln -sf "/usr/lib/jvm/temurin-21-jdk-$(dpkg --print-architecture)" /usr/lib/jvm/temurin-21-jdk \
  && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk

# ----- Android SDK -----
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
# Must match compileSdkVersion / targetSdkVersion in the root build.gradle.kts
# (currently 36 — see CLAUDE.md hard rule: do not raise above 36). When these lag
# behind, Gradle downloads the missing build-tools and platform on every container
# start — /opt/android-sdk is baked into the image and `run --rm` throws the
# container away, so the download never persists.
ARG ANDROID_BUILD_TOOLS_VERSION=36.0.0
ARG ANDROID_PLATFORM=android-36

RUN set -eux; \
    mkdir -p ${ANDROID_HOME}/cmdline-tools; \
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" \
        -o /tmp/cmdline.zip; \
    unzip -q /tmp/cmdline.zip -d ${ANDROID_HOME}/cmdline-tools/; \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest; \
    rm /tmp/cmdline.zip; \
    yes | sdkmanager --licenses >/dev/null 2>&1 || true; \
    sdkmanager \
        "platform-tools" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
        "platforms;${ANDROID_PLATFORM}" >/dev/null

# ----- Kotlin LSP -----
# Official Kotlin Language Server (https://github.com/Kotlin/kotlin-lsp).
# Ships a bundled JBR, so it does not depend on the container JDK.
# Pinned to match the Homebrew cask version used on macOS hosts.
ARG KOTLIN_LSP_VERSION=262.4739.0
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        amd64) suffix="" ;; \
        arm64) suffix="-aarch64" ;; \
        *) echo "Unsupported arch for kotlin-lsp: $arch" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://download-cdn.jetbrains.com/kotlin-lsp/${KOTLIN_LSP_VERSION}/kotlin-server-${KOTLIN_LSP_VERSION}${suffix}.tar.gz" \
        -o /tmp/kotlin-lsp.tar.gz; \
    mkdir -p /opt/kotlin-lsp; \
    tar -xzf /tmp/kotlin-lsp.tar.gz -C /opt/kotlin-lsp --strip-components=1; \
    ln -s /opt/kotlin-lsp/bin/intellij-server /usr/local/bin/kotlin-lsp; \
    rm /tmp/kotlin-lsp.tar.gz

# node:22-bookworm already ships a `node` user with UID/GID 1000.
# Re-map it to the host UID/GID so bind-mounted files keep correct ownership.
ARG HOST_UID=1000
ARG HOST_GID=1000

RUN set -eux; \
    mkdir -p /home/node/.android /home/node/.gradle /home/node/.config/gh /home/node/.local/bin /home/node/.omp; \
    # UID/GID 0 (root) already exists — usermod/groupmod cannot reuse it.
    # When the host user is root, skip remapping and keep /home/node owned by
    # the `node` account (UID 1000) so that `USER node` can write to it.
    if [ "${HOST_UID}" != "0" ] && [ "${HOST_GID}" != "0" ]; then \
        if [ "$(getent group node | cut -d: -f3)" != "${HOST_GID}" ]; then \
            if getent group "${HOST_GID}" >/dev/null; then \
                existing_group="$(getent group "${HOST_GID}" | cut -d: -f1)"; \
                usermod -g "${existing_group}" node; \
            else \
                groupmod -g "${HOST_GID}" node; \
            fi; \
        fi; \
        if [ "$(id -u node)" != "${HOST_UID}" ]; then \
            usermod -u "${HOST_UID}" node; \
        fi; \
        chown -R "${HOST_UID}":"${HOST_GID}" /home/node ${ANDROID_HOME}; \
    else \
        chown -R node:node /home/node ${ANDROID_HOME}; \
    fi

# Gradle user home — keeps build caches on a named volume.
ENV GRADLE_USER_HOME=/home/node/.gradle
# omp's native install drops the binary here; keep it on PATH so `omp` resolves
# without a full path.
ENV PATH=${PATH}:/home/node/.local/bin

# ----- x86_64 runtime libs (arm64 images only) -----
# Parts of the Android toolchain ship as x86_64-only ELF binaries — aapt2, aapt,
# aidl, zipalign, split-select, dexdump — and AGP downloads its own aapt2 from
# Maven (com.android.tools.build:aapt2, `linux` classifier = x86_64) regardless
# of the SDK installed here. Everything expensive (Gradle, the JVM, the Kotlin
# compiler, d8/r8) is arch-agnostic, so an arm64 image only needs to be able to
# exec those few binaries.
#
# Docker already registers a qemu-x86_64 binfmt handler inside the container, so
# the only thing missing is the x86_64 loader and its shared libraries. Adding
# them lets an arm64 image run the toolchain natively and emulate just aapt2 —
# instead of emulating the whole container.
#
# No-op on amd64, where these binaries are native.
#
# Deliberately placed late, just above the COPY layers: nothing between the base
# image and here needs these libs (they are only exec'd at *run* time, by Gradle),
# and on amd64 this instruction does nothing at all. Keeping it below the Android
# SDK and kotlin-lsp downloads means editing it never invalidates those expensive
# cached layers for amd64 users.
RUN set -eux; \
    if [ "$(dpkg --print-architecture)" = "arm64" ]; then \
        dpkg --add-architecture amd64; \
        apt-get update; \
        apt-get install -y --no-install-recommends \
            libc6:amd64 \
            libstdc++6:amd64 \
            zlib1g:amd64; \
        rm -rf /var/lib/apt/lists/*; \
    fi

# ----- mcp-devices (mobile automation MCP server) -----
# Global npm install of the modular base + the Android platform plugin. The
# binary lands at /usr/local/bin/mcp-devices (on PATH). `adb` (already in the
# image from platform-tools) is the only external prerequisite for the Android
# plugin; iOS/Web/Desktop plugins are not installed (this repo is Android-only).
# Pinned to 4.1.0 for reproducible builds; bump and --rebuild to upgrade.
ARG MCP_DEVICES_VERSION=4.1.0
ARG MCP_DEVICES_ANDROID_VERSION=4.1.0
RUN npm install -g \
        mcp-devices@${MCP_DEVICES_VERSION} \
        @mcp-devices/plugin-android@${MCP_DEVICES_ANDROID_VERSION}

COPY --chown=node:node entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Gradle init script for arm64 containers. Kept at a fixed image path rather than
# written straight into $GRADLE_USER_HOME/init.d, because that directory is a
# named volume: a fresh volume is seeded from the image, but an existing one is
# not, so a baked copy would go stale. entrypoint.sh links it in at start.
COPY --chown=node:node gradle-init-skip-robolectric.gradle /opt/gradle-init/skip-robolectric.gradle

USER node
WORKDIR /workspace

# Enable the Android platform in mcp-devices. Writes ~/.mcp-devices/config.json
# ({"platforms":["android"]}), baked into the image so the server starts with
# Android enabled on every container. Run `mcp-devices install <name>` inside
# the container to add more platforms (ephemeral under `--rm`; to persist,
# bake a new image or mount ~/.mcp-devices as a volume).
RUN mcp-devices install android

# omp is installed at first container start by the compose entrypoint (see
# docker-compose.omp.yml), so the binary lands on the persistent .local volume
# and survives `run --rm`. No build-time install step here — that avoids the
# Bun-under-QEMU segfault a native installer can hit on Apple Silicon amd64
# builds, and keeps the image smaller.

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
CMD []
