#!/bin/bash
# Stop hook — fires before Claude finishes its turn.
# If source files were modified during this session (tracked via the queue file
# written by post-edit-review-reminder.sh), blocks Claude from stopping and
# requires the code-reviewer agent to be invoked first.
#
# Escape hatch: export PTT_SKIP_REVIEW_GATE=1 to disable the gate for a session
# (useful for docs-only work that still touches a .kts build file).

set -e

INPUT=$(cat)

if [[ "${PTT_SKIP_REVIEW_GATE:-0}" == "1" ]]; then
  exit 0
fi

# Guard against infinite loops — if the stop hook already triggered once, let Claude stop
STOP_ACTIVE=$(echo "$INPUT" | jq -r '.stop_hook_active // false')
if [[ "$STOP_ACTIVE" == "true" ]]; then
  exit 0
fi

SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // empty')
QUEUE_FILE="${TMPDIR:-/tmp}/.ptt-review-queue-${SESSION_ID}"

if [[ ! -f "$QUEUE_FILE" ]] || [[ ! -s "$QUEUE_FILE" ]]; then
  exit 0
fi

# Read all queued files, deduplicate, then clear the queue atomically.
FILES=$(sort -u "$QUEUE_FILE")
: > "$QUEUE_FILE"

FILE_LIST=$(echo "$FILES" | tr '\n' ',' | sed 's/,$//')

jq -n --arg files "$FILE_LIST" '{
  decision: "block",
  reason: ("Source files were modified this session but the code-reviewer has not run yet. Before presenting results you MUST: 1) invoke the code-reviewer agent on these files: " + $files + " 2) show the review report to the user 3) fix every High finding. Only then present the final summary. For a whole branch or PR use the pr-review skill instead. Remember the repo gate: ./gradlew assembleDebug testDebugUnitTest lintDebug, plus :shared:desktopTest and -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64 if :shared changed.")
}'
