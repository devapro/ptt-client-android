#!/bin/bash
# PostToolUse hook — fires after Write or Edit tool calls.
# Tracks modified Kotlin/Swift source files in a session-scoped queue file so the
# Stop hook can block Claude from finishing until the code-reviewer agent has run.
#
# Adapted for ptt-client-android: this repo is four source trees (:shared with six
# source sets, :app, :desktopApp, iosApp/), so the filter is by extension plus an
# exclusion list rather than by module path.

set -e

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // empty')

if [[ -z "$FILE_PATH" ]]; then
  exit 0
fi

# Only source files gate the review.
case "$FILE_PATH" in
  *.kt|*.kts|*.swift) ;;
  *) exit 0 ;;
esac

# Never gate on generated output, tooling config, or the agent definitions themselves.
case "$FILE_PATH" in
  */build/*|*/.gradle/*|*/.kotlin/*|*/.claude/*) exit 0 ;;
esac

# Skip test sources — the reviewer reads them as part of the production file review.
BASENAME=$(basename "$FILE_PATH")
if [[ "$BASENAME" == *Test.kt || "$BASENAME" == *Tests.kt ]]; then
  exit 0
fi
case "$FILE_PATH" in
  */commonTest/*|*/jvmCommonTest/*|*/androidInstrumentedTest/*|*/desktopTest/*) exit 0 ;;
esac

# Enqueue this file so the Stop hook can enforce the review (session-scoped)
QUEUE_FILE="${TMPDIR:-/tmp}/.ptt-review-queue-${SESSION_ID}"
echo "$FILE_PATH" >> "$QUEUE_FILE"
