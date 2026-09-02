---
name: pr-address-comments
description: Fetches a GitHub PR's unresolved review comments, weighs each against this repo's rules, implements the ones that make sense, runs the build gate, and helps you reply. Use on a PR URL plus "address the review comments".
argument-hint: "[pr-url] [--current | branch-name]"
disable-model-invocation: true
---

You are addressing review comments on a GitHub pull request end to end: fetch, decide, implement,
validate, and help the user close the loop with replies.

## Why this needs judgement

Reviewer feedback is a mix of sharp catches, style nits, questions, and occasional suggestions
that would make the code worse. "Do everything the reviewer said" produces churn and sometimes
regressions. Be a thoughtful teammate: implement what genuinely improves the code, skip what does
not, and explain the reasoning back.

**This repo's rules are the tiebreaker** when a suggestion conflicts with them. They live in
`CLAUDE.md` (hard rules) and `docs/` — `conventions.md`, `architecture.md`, `audio-pipeline.md`,
`ui-design.md`, `platform-support.md`, `testing.md`, `build-and-run.md`, `known-issues.md` — plus
`.claude/contexts/review-exceptions.md` for shapes this project has already decided are not
defects. Several of this codebase's deliberate choices look wrong to a reader who has not read
those docs: the empty `getAcceptedIssuers()`, the permitted cleartext, the deprecated
`com.android.library` plugin, the dormant ProGuard rules, the widget being a toggle. A reviewer
asking you to "fix" one of those is asking you to break the app.

## Inputs

Raw args: $ARGUMENTS

1. **PR URL** (required) — a full GitHub URL. Extract `owner`, `repo`, `pr_number`. If it is
   malformed or missing, stop and ask.
2. **Branch flag** (optional):
   - omitted → `gh pr checkout <pr_number>`
   - `--current` / `current` → stay on the current branch; verify with `git branch --show-current`
     and print it back
   - anything else → treat as a branch name and `git checkout <name>`

Before switching branches, run `git status --porcelain`. If it is non-empty, **stop** and ask
(stash / commit / abort). Never discard uncommitted work silently.

---

## Phase 1 — Set up

1. `gh auth status`. Non-zero → stop, tell the user to run `gh auth login`.
2. Handle the branch per the rules above.
3. Print `git branch --show-current` and compare it with
   `gh pr view <pr_number> --json headRefName -q .headRefName`. If they differ, warn and ask.

---

## Phase 2 — Fetch comments

Fetch inline review comments and review summaries, **unresolved threads only**. The helper script
handles the GraphQL query for thread-resolution status, which the REST API does not expose:

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/fetch_pr_comments.py \
    --owner <owner> --repo <repo> --pr <pr_number> \
    --output .claude/data/pr-address-comments/pr-<pr_number>-comments.json
```

Each entry carries `id` (GraphQL node), `rest_id` (numeric, needed for threaded replies),
`thread_id`, `kind` (`inline` / `review_summary`), `author`, `body`, `path`, `line`, `url`,
`is_resolved`, `is_outdated`, `in_reply_to_id`.

If the script exits non-zero, print the last stderr line and stop.

---

## Phase 3 — Read the rules

Read before analysing anything:

```bash
cat CLAUDE.md
cat docs/conventions.md
cat .claude/contexts/review-exceptions.md
```

Plus whichever of `docs/architecture.md`, `docs/audio-pipeline.md`, `docs/ui-design.md`,
`docs/platform-support.md`, `docs/testing.md`, `docs/build-and-run.md`, `docs/known-issues.md` the
comments actually touch.

---

## Phase 4 — Categorise each comment

- **implement** — actionable, improves the code, contradicts no rule, and does not add complexity
  out of proportion to the value.
- **skip** — any of:
  - **contradicts a rule** — cite the file and section (`CLAUDE.md § Hard rules`,
    `docs/conventions.md § UI`, …)
  - **matches a registry entry** — cite the `EX-NNN` from `.claude/contexts/review-exceptions.md`,
    and **check its "Still an issue" boundary first**; an entry retires a shape, not everything
    that resembles it
  - **it is a question, not a change request** — no code change, but draft an explanatory reply
  - **disproportionate complexity** — a new abstraction, module boundary or wide refactor with no
    clear payoff on this PR; explain the trade-off
  - **obsolete** — the code changed since the review (`is_outdated`)
  - **no action needed** — a compliment, or a reply inside a settled thread
- **ask** — the right call genuinely is not clear from the code plus the docs. Use sparingly.

### Comments that need extra care in this repo

| The reviewer says | Before agreeing, check |
|---|---|
| "this trust manager returns no issuers" | `EX-002` — it must stay empty |
| "cleartext is enabled" | `EX-001` — deliberate; only a *widening* is a defect |
| "this Gradle plugin is deprecated" | `EX-004` — switching ships a crashing APK |
| "these ProGuard rules are dead" | `EX-008` — dormant by design |
| "extract a shared base for the three audio backends" | `EX-015` — never propose one |
| "these three docs duplicate each other" | `EX-011` — deliberate, in three voices |
| "add a log here" (in an audio path) | the per-frame rule — **never** |
| "add KDoc to this class" | `EX-014` — the default is no comment |
| anything touching the wire format | the **`protocol-change`** skill — four implementations across two repos |

### Two more rules

**Review summaries are not atomic.** A `review_summary` often holds several distinct sub-items.
Split them, categorise each, and list them as `<summary URL> — item N/M — <one line>`.

**Minimum vs ideal.** Reviewers often write "at minimum X, ideally Y". Default to the minimum
unless there is concrete evidence the ideal is expected — a referenced follow-up, a rule mandating
it, or the reviewer explicitly asking. The reviewer set the floor deliberately, and exceeding it
usually pulls in scope. If the ideal genuinely fits, do it and flag the choice in the reply.

---

## Phase 5 — Present the plan, then wait

One line per comment:

```
[implement]        StartSpeakReducer.kt:42 (@reviewer) — "release the floor in a finally"
[skip:EX-002]      PinnedTrust.kt:71 (@reviewer) — "trust manager should return its issuers"
[skip:rule]        VoiceRecorder.kt:88 (@reviewer) — "add a log per frame" contradicts CLAUDE.md
[skip:question]    PttController.kt:7 (@reviewer) — "why is this a StateFlow?" (drafting reply)
[skip:complexity]  AppSettings.kt:99 (@reviewer) — a new abstraction for one caller
[ask]              IosAudio.kt:55 (@reviewer) — "consider X here"
```

Ask: **"Does this plan look right? Any decisions to flip before I implement?"** Wait.

---

## Phase 6 — Implement

One comment at a time. Read the surrounding code, not just the flagged line. Note every file
touched.

If a comment turns out harder or worse than it looked, stop, flip it to `skip:complexity` with a
note, and continue. Report the flip in the summary.

**If a change touches the wire protocol, stop and use the `protocol-change` skill instead** — it
covers all four implementations across both repos and both test suites. Landing the client half
alone is worse than not landing it.

---

## Phase 7 — Validate

Run what the change needs (use the `build-gate` agent to keep Gradle output out of the
conversation):

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew :shared:desktopTest                                    # if shared/ changed
./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64
env -u PTT_KEYSTORE_PATH ./gradlew :app:assembleRelease           # if build/packaging changed
```

Lint: compare **by file**, not by count. The totals drift on their own — most `:app` findings are
"a newer version is available" warnings. A finding in a file this change touched is the signal.
(Measured 2026-09-02: `:app` 15, `:shared` 1; `CLAUDE.md`'s "12 / 0" is stale.)

Fix what you broke. Never bypass with `--no-verify`. If a failure is caused by a comment you
implemented, consider reverting that one and flipping it to `skip:complexity` with a note.

---

## Phase 8 — Report

```markdown
# PR review response — <title> (#<n>)

<PR URL>

## Summary
- Implemented: N
- Skipped: M (rules: X, registry: R, questions: Y, complexity: Z, no-op: W)
- Asked: K

## Files changed
- path/to/File.kt

## Gate
- assembleDebug ✅ · testDebugUnitTest ✅ · :shared:desktopTest ✅ · iOS compile ✅
- lintDebug — :app 12 (unchanged), :shared 0

## Comments

### 1. StartSpeakReducer.kt:42 — @reviewer — implemented
<url>
> Quoted comment (first ~200 chars).

**Decision:** implemented.
**What I did:** <brief>

### 2. PinnedTrust.kt:71 — @reviewer — skipped (registry EX-002)
<url>
> Quoted comment.

**Decision:** skipped.
**Reason:** `getAcceptedIssuers()` must stay empty — returning issuers puts the manager on
OkHttp's chain-cleaning path, which needs a root a self-signed certificate does not have, and the
connection then fails despite a matching fingerprint (`docs/known-issues.md`; `EX-002`).
```

Print it inline before asking about saving or posting.

---

## Phase 9 — Save and post

Ask both in one message:

1. **Save the report?** → `.claude/data/pr-address-comments/pr-<n>-report.md` (gitignored).
2. **Post replies?** If yes, draft them all first.

**Replies must be short.** A reply is not a PR description; the reviewer knows what they asked.

- **Implemented** → `done`. Add one clause after an em-dash only if the change would surprise them
  (max ~15 words). Never more than one line.
- **Skipped (rule / registry)** → one sentence naming the source:
  `` Skipped — `getAcceptedIssuers()` must stay empty; returning issuers breaks pinned connections (docs/known-issues.md). ``
- **Skipped (complexity)** → one sentence: `Skipped — <impact> outweighs <cost>; can revisit as a follow-up.`
- **Skipped (question)** → answer in 1–2 sentences. If it needs a paragraph, offer a follow-up
  thread instead of writing the paragraph.
- **Skipped (obsolete / no-op)** → post nothing.
- **Ask** → skip, or use the user's decision.

For a `review_summary`, post one batched issue comment:

```
Re: @<reviewer> — thanks for the review.
- <path>: done
- <path>: done — <one clause>
- <path>: skipped — <one-clause reason>
- <path>: question — <one-sentence answer>
```

**Calibration:**

```
✅ done
✅ done — moved the release into a finally so cancellation also frees the floor.
✅ Skipped — the per-frame rule in CLAUDE.md forbids logging in the recorder's read loop.
❌ done — added a `try { … } finally { … }` around the pointerInput block, keyed the
   pointerInput on Unit so the detector survives a state change mid-press, and… [+3 sentences]
```

If a reply genuinely needs more than a line — a real design proposal, a trade-off the reviewer must
decide — that belongs as a **new top-level PR comment**, with the thread reply reduced to
`See top-level comment.`

Show the **full batch of drafts** in one message and wait for a single confirmation. Then post:

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/post_replies.py --help    # preferred
```

or directly:

```bash
gh api -X POST /repos/<owner>/<repo>/pulls/<n>/comments/<rest_id>/replies -f body='<text>'
gh api -X POST /repos/<owner>/<repo>/issues/<n>/comments -f body='Re: @<reviewer> review — <text>'
```

Print a one-line confirmation with each resulting URL.

---

## Failure handling

- `gh` not authenticated → stop.
- Malformed PR URL → stop, ask.
- Uncommitted changes before a branch switch → stop, ask.
- A gate step fails → fix it. Never bypass.
- A reply fails to post → print the error, continue with the rest, list the failures at the end.

## Anti-patterns

- **Do not implement on autopilot.** Read `CLAUDE.md`, `docs/` and the registry first — several
  reasonable-sounding suggestions here would ship a crashing APK or a broken pinned connection.
- **Do not post replies without showing drafts.** Bulk-posting is noisy and irreversible.
- **Do not `git commit` or `git push`.** `CLAUDE.md` forbids it unless the user explicitly asks;
  leave the working tree dirty for them to inspect.
- **Do not skip the gate** because the change was small. `:shared:desktopTest` and the iOS compile
  catch things `assembleDebug` never will.
- **Do not paraphrase the reviewer.** Quote them (trimmed) so the report stands alone.
