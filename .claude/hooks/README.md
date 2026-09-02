# Claude Code Hooks

Shell scripts Claude Code runs at lifecycle points. Registered in `.claude/settings.json`
under `"hooks"`.

## post-edit-review-reminder.sh

**Trigger**: after every `Write` or `Edit`.

**What it does**: appends the edited path to a session-scoped queue
(`$TMPDIR/.ptt-review-queue-<session>`) when it is a `.kt`, `.kts` or `.swift` file. Skips
anything under `build/`, `.gradle/`, `.kotlin/` or `.claude/`, and skips test sources
(`*Test.kt`, `*Tests.kt`, and everything under `commonTest/`, `jvmCommonTest/`,
`androidInstrumentedTest/`, `desktopTest/`) — the reviewer reads tests as part of the
production file review.

## pre-stop-review-check.sh

**Trigger**: before Claude finishes its turn.

**What it does**: if the queue is non-empty it **blocks** the stop and tells Claude to run the
`code-reviewer` agent, show the report, and fix High findings first. It also restates the repo
build gate from `CLAUDE.md`, because a source change here usually needs
`:shared:desktopTest` and the iOS frontend compile as well as the Android tasks. The queue is
cleared on each block so the gate cannot loop.

## Turning the gate off

- Per session: `export PTT_SKIP_REVIEW_GATE=1`
- One-off: empty the queue — `: > "${TMPDIR:-/tmp}/.ptt-review-queue-<session-id>"`
- Permanently: delete the `Stop` block from `.claude/settings.json`

## Differences from the AndroidRepo original

- Queue filename is `.ptt-review-queue-*`, so the two repos cannot block each other.
- `.swift` is tracked (`iosApp/`); `build/`, `.gradle/`, `.kotlin/` and `.claude/` are excluded.
- KMP test source sets are excluded by directory, not only by filename suffix.
- The block message names this repo's build gate and the `pr-review` skill.
- `PTT_SKIP_REVIEW_GATE` escape hatch added.
