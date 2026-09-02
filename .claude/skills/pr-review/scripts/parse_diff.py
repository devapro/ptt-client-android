#!/usr/bin/env python3
"""
parse_diff.py — Convert a raw `gh pr diff` / `git diff` output into readable per-file content.

Adapted for ptt-client-android from the AndroidRepo original. Differences: the KMP source-set
layout replaces src/test|androidTest, the risk-factor signals name this repo's actual danger
zones (the audio frame paths, the protocol triple, the pinned-TLS files, the build constraints
and the three platform docs) instead of a generic auth/billing/webview keyword list.

Usage:
    python3 parse_diff.py <diff_file> <output_file> [--test-signals]

--test-signals: include test-coverage lines (test-vs-production volume, NO TEST CHANGES /
LOW TEST RATIO warnings) in the [RISK FACTORS] block. Off by default — the review skill's
test review is opt-in ("check-tests"), and emitting these signals when test review is
disabled would prompt agents to flag missing tests anyway.

For each file in the diff:
  - ADDED files: full content extracted (leading `+` stripped)
  - MODIFIED files: raw diff hunks (so reviewers see what changed)
  - DELETED files: marked as [DELETED] with filename only

The output starts with a [RISK FACTORS] block — deterministic signals (test coverage of the
change, security-sensitive paths, build/config changes) computed from the diff so agents and
the synthesizer don't have to re-derive them.

The output file is ready to be read and pasted into agent prompts.
"""

import re
import sys

# KMP test source sets, not src/test.
TEST_PATH_RE = re.compile(
    r"src/(commonTest|jvmCommonTest|androidTest|androidInstrumentedTest|desktopTest|iosTest)/"
    r"|Tests?\.kt$"
)

# Transport security and the shared secret. `internalserver` is here on purpose: it is client
# code that implements a server, and it checks the access token.
SECURITY_PATH_RE = re.compile(
    r"network/tls/|PinnedTrust|CertificatePin|PttHttpClient|PttEndpoint|AppSettings"
    r"|internalserver/|network_security_config|token|secret|credential",
    re.IGNORECASE,
)

# The per-audio-frame paths. A single log line or allocation added here is this repo's
# highest-frequency real defect (CLAUDE.md; docs/known-issues.md #10).
AUDIO_PATH_RE = re.compile(
    r"audio/|VoiceRecorder|VoicePlayer|DesktopAudio|IosAudio|FrameAccumulator"
    r"|KtorPttConnection|internalserver/"
)

# The protocol triple. No shared artefact exists between client and server, so nothing but a
# reviewer catches drift.
PROTOCOL_PATH_RE = re.compile(r"network/protocol/|Messages\.kt$|internalserver/InternalPttServer")

# State ownership: the one place connection/floor/channel state may live, and the one place
# PttState becomes a colour and a word.
STATE_OWNER_RE = re.compile(r"domain/PttController|domain/PttState|ui/PttUiStatus")

# Build constraints that are documented as load-bearing and easy to break.
CONFIG_FILE_RE = re.compile(
    r"(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|libs\.versions\.toml|"
    r"gradle\.properties|relay\.properties|version\.properties|proguard[^/]*|"
    r"AndroidManifest\.xml)$|^\.github/workflows/|^gradle/relay-defaults\.gradle\.kts$"
)

# A capability change has to land in all three of these, in the same commit.
PLATFORM_DOC_RE = re.compile(r"^docs/platform-support\.md$|^README\.md$|^docs/index\.html$")

IOS_SRC_RE = re.compile(r"src/iosMain/|^iosApp/")
SHARED_SRC_RE = re.compile(r"^shared/src/")


def count_changed_lines(diff_section: str) -> int:
    """Count added + removed lines in one file's diff section."""
    count = 0
    for line in diff_section.split("\n"):
        if (line.startswith("+") and not line.startswith("+++")) or (
            line.startswith("-") and not line.startswith("---")
        ):
            count += 1
    return count


def build_risk_factors(file_stats: list, test_signals: bool) -> str:
    """file_stats: list of (fname, changed_lines, is_deleted) tuples."""
    live = [(f, n) for f, n, deleted in file_stats if not deleted]
    security_files = sorted({f for f, _ in live if SECURITY_PATH_RE.search(f)})
    config_files = sorted({f for f, _ in live if CONFIG_FILE_RE.search(f)})

    audio_files = sorted({f for f, _ in live if AUDIO_PATH_RE.search(f)})
    protocol_files = sorted({f for f, _ in live if PROTOCOL_PATH_RE.search(f)})
    state_files = sorted({f for f, _ in live if STATE_OWNER_RE.search(f)})
    platform_docs = sorted({f for f, _ in live if PLATFORM_DOC_RE.search(f)})
    ios_files = sorted({f for f, _ in live if IOS_SRC_RE.search(f)})
    shared_files = [f for f, _ in live if SHARED_SRC_RE.search(f)]

    lines = ["[RISK FACTORS]"]
    if test_signals:
        test_lines = sum(n for f, n in live if TEST_PATH_RE.search(f))
        prod_lines = sum(n for f, n in live if not TEST_PATH_RE.search(f))
        lines.append(
            f"- Production lines changed: {prod_lines}; test lines changed: {test_lines}"
        )
        if prod_lines > 50 and test_lines == 0:
            lines.append(
                "- ⚠️ NO TEST CHANGES: production code changed with zero test files touched"
            )
        elif prod_lines > 0 and test_lines * 5 < prod_lines:
            lines.append(
                "- ⚠️ LOW TEST RATIO: test changes cover < 20% of production change volume"
            )
    if audio_files:
        lines.append(
            "- ⚠️ PER-FRAME AUDIO PATHS touched (no logging, no allocation in the loop; "
            "25 frames/s per direction): " + ", ".join(audio_files)
        )
    if protocol_files:
        lines.append(
            "- ⚠️ PROTOCOL touched: " + ", ".join(protocol_files) + ". A wire change must land in "
            "../ptt-server/docs/protocol.md (first), the server's Messages.kt, the client's "
            "Messages.kt, and internalserver/InternalPttServer.kt — plus the serialization tests "
            "on both sides. There is no shared artefact; nothing else catches drift."
        )
    if state_files:
        lines.append(
            "- ⚠️ STATE OWNERSHIP touched: " + ", ".join(state_files) + ". PttController is the "
            "sole owner of connection/floor/channel state; PttUiStatus is the sole PttState → "
            "colour/wording mapping, read by four surfaces."
        )
    if security_files:
        lines.append(
            "- ⚠️ TRANSPORT/SECRET PATHS touched: " + ", ".join(security_files)
        )
    if config_files:
        lines.append(
            "- ⚠️ BUILD/CONFIG CHANGES: " + ", ".join(config_files) + ". Constraints: compileSdk "
            "stays 36, android.builtInKotlin=false and android.newDsl=false stay, :shared keeps "
            "com.android.library, both version forces stay, no iosX64, version lives in "
            "version.properties and the default relay in relay.properties."
        )
    if ios_files:
        lines.append(
            "- ⚠️ iOS SOURCES touched: " + ", ".join(ios_files) + ". Objective-C categories need "
            "their own imports in Kotlin/Native (serverTrust/credentialForTrust, "
            "AVFAudio.setActive, cinterop get/set/plus)."
        )
    if shared_files and not platform_docs:
        lines.append(
            f"- NOTE: {len(shared_files)} file(s) under shared/src changed with no update to "
            "docs/platform-support.md, README.md or docs/index.html. Only a finding if what a "
            "platform can DO changed — a capability change must land in all three."
        )
    if platform_docs and len(platform_docs) < 3:
        lines.append(
            "- ⚠️ PARTIAL PLATFORM DOC UPDATE: " + ", ".join(platform_docs) + ". A capability "
            "change must land in docs/platform-support.md AND README.md AND docs/index.html."
        )
    if len(lines) == 1:
        lines.append("- No elevated risk factors detected")
    return "\n".join(lines) + "\n"


def extract_filename(d: str) -> str:
    r"""Best-effort NEW path for a single-file diff section.

    Prefers `+++ b/<path>` and `rename to <path>` over `diff --git a/<old>`: the old path is
    wrong for renames, and `\S+` truncates any path containing a space. Falls back to the
    `--- a/` path for deletions (where `+++` is /dev/null), then to the git header.
    """
    m = re.search(r"^rename to (.+)$", d, re.MULTILINE)
    if m:
        return m.group(1).strip()
    m = re.search(r"^\+\+\+ b/(.+)$", d, re.MULTILINE)
    if m and m.group(1).strip() != "/dev/null":
        return m.group(1).strip()
    m = re.search(r"^--- a/(.+)$", d, re.MULTILINE)
    if m and m.group(1).strip() != "/dev/null":
        return m.group(1).strip()
    m = re.search(r"diff --git a/(\S+)", d)
    return m.group(1) if m else "unknown"


def parse_diff(diff_path: str, output_path: str, test_signals: bool = False) -> None:
    with open(diff_path, "r") as f:
        content = f.read()

    # Split on each file boundary, keeping the delimiter
    diffs = re.split(r"(?=diff --git )", content)

    sections = []
    file_stats = []
    for d in diffs:
        if not d.strip():
            continue

        fname = extract_filename(d)

        if "deleted file mode" in d:
            sections.append(f"[DELETED] {fname}\n")
            file_stats.append((fname, count_changed_lines(d), True))
            continue

        file_stats.append((fname, count_changed_lines(d), False))

        is_new = "new file mode" in d
        label = "ADDED" if is_new else "MODIFIED"

        if is_new:
            # Extract only the added lines (strip the leading `+`)
            added_lines = []
            for line in d.split("\n"):
                if line.startswith("+") and not line.startswith("+++"):
                    added_lines.append(line[1:])
            file_content = "\n".join(added_lines)
        else:
            # For modified files, keep the raw diff so reviewers see what changed
            file_content = d

        sections.append(f"[{label}] {fname}\n{file_content}\n")

    output = build_risk_factors(file_stats, test_signals) + "\n" + "\n".join(sections)
    with open(output_path, "w") as f:
        f.write(output)

    # Print a summary to stdout
    total = len(sections)
    added = sum(1 for s in sections if s.startswith("[ADDED]"))
    modified = sum(1 for s in sections if s.startswith("[MODIFIED]"))
    deleted = sum(1 for s in sections if s.startswith("[DELETED]"))
    print(
        f"Parsed {total} files: {added} added, {modified} modified, {deleted} deleted"
    )
    print(f"Output written to: {output_path}")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--test-signals"]
    test_signals = "--test-signals" in sys.argv[1:]
    if len(args) != 2:
        print(f"Usage: python3 {sys.argv[0]} <diff_file> <output_file> [--test-signals]")
        sys.exit(1)
    parse_diff(args[0], args[1], test_signals)
