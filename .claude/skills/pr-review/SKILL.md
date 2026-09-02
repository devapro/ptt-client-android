---
name: pr-review
description: Deep parallel review of a PR, branch or working tree by 8 specialist agents (architecture, multiplatform, Compose, code quality, tests, performance, correctness, security). Use for anything beyond a couple of files, or any change to the protocol, the audio path, transport security or the build files.
argument-hint: "[pr-number | branch-name] [check-tests]"
---

You are performing a code review for `ptt-client-android`. The canonical rules are in `CLAUDE.md`
and `docs/`; the reviewer's digest is `.claude/contexts/code-review.md`.

## Input

Target to review: $ARGUMENTS

**Strip the flags first.** `check-tests` may appear anywhere in the arguments; remove it before
resolving the target. What remains is the target: a PR number (`42`) or a branch name (`cmp`). If
nothing remains, the target is the **working tree** — `check-tests` alone is a valid, complete
argument list.

`check-tests` enables the test-review agent. It is **off by default**.

---

## Phase 1 — Get the Diff

### 1a. A PR number was provided
```bash
gh pr diff <pr>
gh pr view <pr> --json title,body,additions,deletions,changedFiles
gh api "repos/$(gh repo view --json nameWithOwner -q .nameWithOwner)/pulls/<pr>/files" \
  --jq '.[] | [.filename, .status] | @tsv'
```

### 1b. A branch name was provided

Resolve the base branch — **do not assume it**. In order of preference:
1. a base stated in the invoking prompt;
2. `gh pr view <branch> --json baseRefName -q .baseRefName` if a PR exists;
3. `main` as the fallback (this repo's default branch is `main`).

```bash
git diff "origin/$BASE...$BRANCH"
git log "origin/$BASE..$BRANCH" --oneline
```

### 1c. Nothing remains — review the working tree
```bash
# -N registers untracked files with the index so `git diff` shows their content.
# Without it every newly added file is invisible to the review.
git add -N .
git diff HEAD
git status
```

---

## Phase 2 — Size Check

Count additions + deletions.

**If the total exceeds 2500 lines**, stop and respond with the marked block below. This repo is
~106 Kotlin files; a diff that large is a restructure, not a reviewable unit.

```
<<<PR_REVIEW>>>
⛔ This diff is too large for automated review (NNN lines changed, limit is 2500).

Please split it into smaller, focused changes. Automated review was skipped.
<<<END_PR_REVIEW>>>
```

The one legitimate exception is a **phase-sized restructure** of the kind `docs/known-issues.md`
records (the Compose Multiplatform split, for instance). If the user has explicitly asked for that,
say the limit is being waived and review it module by module and doc by doc instead of
file by file. Do not waive it on your own judgement.

---

## Phase 3 — Read Changed Files

Save the diff to a temp file — **from the source Phase 1 actually resolved**, not always
`gh pr diff`:

```bash
SP="$SCRATCHPAD"   # or /tmp if none is set
gh pr diff <pr>                     > "$SP/pr_diff.txt"   # 1a
git diff "origin/$BASE...$BRANCH"   > "$SP/pr_diff.txt"   # 1b
git diff HEAD                       > "$SP/pr_diff.txt"   # 1c (after `git add -N .`)
```

Then parse it. Add `--test-signals` **only** when the args contain `check-tests`:

```bash
python3 .claude/skills/pr-review/scripts/parse_diff.py "$SP/pr_diff.txt" "$SP/pr_review_content.txt"
```

Read the output. Every file is labelled `[ADDED]`, `[MODIFIED]` or `[DELETED]`, and the payloads
differ: `[ADDED]` carries **full content**, `[MODIFIED]` carries only **diff hunks**, `[DELETED]`
carries the filename alone. **Read every `[MODIFIED]` file from disk as well** — the hunks alone do
not let an agent reason about a file as a whole, and the agent prompt must contain the full
content.

The output opens with a `[RISK FACTORS]` block: deterministic signals computed from the paths.
**Include it verbatim in every agent prompt**, right after the change description, and factor it
into synthesis. In this repo it is doing real work:

| Signal | What it tells you |
|---|---|
| `PER-FRAME AUDIO PATHS touched` | the performance agent's headline check; a single log line here is the finding |
| `PROTOCOL touched` | a wire change must reach four files across two repos plus both test suites — the correctness agent verifies it, and you check the sibling repo yourself |
| `STATE OWNERSHIP touched` | `PttController` / `PttUiStatus`; back the architecture and compose agents' findings |
| `TRANSPORT/SECRET PATHS touched` | tells the security agent where to look first |
| `BUILD/CONFIG CHANGES` | the documented constraints; back the multiplatform agent |
| `iOS SOURCES touched` | the Objective-C category import trap |
| `PARTIAL PLATFORM DOC UPDATE` | a capability change must land in all three docs |
| `NO TEST CHANGES` (only with `check-tests`) | backs the tests agent |

**If `PROTOCOL touched` fired**, read the sibling repo before launching the agents:

```bash
git -C ../ptt-server log --oneline -5
sed -n '1,80p' ../ptt-server/docs/protocol.md
```

and include what you find in the correctness agent's prompt. The client diff alone cannot show you
drift.

### File status handling

| Status | What to do |
|---|---|
| `modified` | read the full file from disk; note what the diff changes |
| `added` | not on disk yet for a PR review — take the full content from the diff hunk |
| `removed` | do **not** read it, do **not** flag its content. Record `[DELETED]` and credit it as cleanup |
| `renamed` | read from the new path |

Focus on `.kt`, `.kts`, `.swift`, `.properties`, `AndroidManifest.xml`, `res/xml/*`, the
`docs/*.md` files and `docs/index.html`.

---

## Phase 4 — Parallel Specialist Review

**Check whether `$ARGUMENTS` contains `check-tests`.**

- **absent (default)** — launch **7** agents, skipping Agent 5. Test coverage is entirely out of
  scope: do not evaluate whether tests exist, and drop any incidental "this has no tests" remark
  from another agent (see the discard rule in Phase 5).
- **present** — strip it from the args and launch all **8**.

Launch every agent **simultaneously**, in a single step. Do not wait for one before starting the
next.

Each prompt contains, in order:
1. a brief description of what the change does;
2. the `[RISK FACTORS]` block verbatim;
3. the full content of every changed file, status-marked `[ADDED]` / `[MODIFIED]` / `[DELETED]`;
4. this instruction verbatim: *"Only flag issues in code this change adds or modifies. In
   `[MODIFIED]` files, unprefixed diff-context lines and anything read from the repo are
   pre-existing code — do not report issues there, even if they violate a rule."*
5. *"Return only a JSON object with keys: section, high, medium, low, questions, good_patterns."*

| # | `subagent_type` | Brief |
|---|---|---|
| 1 | `pr-review-architecture` | MVI layering, `PttController` as sole state owner, the `PttEndpoint` contract, settings derivation, Koin wiring across the platform modules |
| 2 | `pr-review-multiplatform` | source-set placement (`commonMain` / `jvmCommonMain` / `androidMain` / `desktopMain` / `iosMain`), `expect`/`actual` completeness, cinterop imports, build constraints, the three platform docs |
| 3 | `pr-review-compose` | the `PttUiStatus` colour/wording contract, the press-and-hold gesture invariants, recomposition and allocation, effects, accessibility for a hold control |
| 4 | `pr-review-code-quality` | null safety, immutability, constants, comment density and staleness, premature abstraction, copy-paste slips |
| 5 | `pr-review-tests` | **only with `check-tests`** — coverage of new domain behaviour, test source-set placement, protocol serialization tests |
| 6 | `pr-review-performance` | the per-frame rule, coroutines and dispatchers, device and socket lifecycle, allocation on hot paths |
| 7 | `pr-review-correctness` | bug hunt — protocol drift, the talk-floor state machine, reconnection, exception paths, concurrency |
| 8 | `pr-review-security` | pinning on both the JVM and iOS paths, the access token, hardcoded relay addresses, cleartext policy, key material |

For agent 2, add: *"Use Glob/Grep to check the sibling source sets for a missing `actual` — a
missing file cannot appear in a diff."* For agent 7, add whatever you read from `../ptt-server`.
For agent 5 (when it runs), split the file list into `## Changed Files — Production` and
`## Changed Files — Tests`.

---

## Phase 5 — Synthesize

Collect the JSON from every launched agent and merge.

**Deduplicate.** Same issue, same file and line → one item, merged description, the more specific
wording kept. Note that file naming is claimed by both the multiplatform and code-quality agents,
and `PttUiStatus` by both architecture and compose — expect overlap there.

**Fold `questions`.** They get no section of their own: promote the important ones into the
priority sections as ❓ items, drop the minor ones.

**Discard `good_patterns`.** The review contains only issues.

**Discard every `low` finding.** The posted review has only 🔴 High and 🟡 Medium. Drop each
agent's whole `low` array — do not promote low findings to medium to keep them, and do not append
them as prose or a "minor notes" list.

**Discard known false positives.** Read `.claude/contexts/review-exceptions.md` **in full**. This
is the one place in the pipeline that applies it: the specialist agents are deliberately told not
to pre-filter, so every registry match in this run is still in the set you just merged and nothing
else will remove it. Drop any finding matching an entry, whichever agent produced it and whatever
severity it carries.

Also apply the unregistered never-flag items in `.claude/contexts/code-review.md` § "Not an Issue"
(missing trailing newline, `internal` on test classes, `runCatching` preference, the maintained
test counts, `docs/index.html` being hand-written). Log those with the code `LOCAL`.

**Respect each entry's "Still an issue" boundary.** It is the whole safeguard of this step, and
since the agents no longer pre-filter, a boundary you misread here is the only thing between a real
bug and silence. An entry retires a *code shape*, not everything that mentions it:
`EX-001` does not cover a *widening* of the network security config; `EX-002` does not cover a
`checkServerTrusted` that skips the pin comparison; `EX-013` does not cover a change that makes the
iOS path weaker; `EX-015` does not cover a copy-paste slip among the look-alike blocks, nor a
cross-platform *contract* diverging. When you cannot tell from the finding text alone, re-read the
code in the Phase 3 content block — you have every changed file. **Do not suppress on a
resemblance.**

**Discard out-of-diff findings**, whichever agent produced them. Every posted issue must point at a
line the change added or modified. Verify against the hunks: the referenced line must appear as a
`+` line, or be directly broken by one. One deliberate exception, from Phase 4: a **missing
`actual` for an `expect` the change added**, and **missing coverage for behaviour the change
added** (with `check-tests`), are in scope even though nothing appears in the diff for them. A
pre-existing problem that genuinely interacts with the change may survive only as a ❓ question,
never as an asserted issue.

**When `check-tests` is off, discard every test-coverage finding**, whichever agent produced it:
missing tests, untested logic, test-to-code ratio. Test review was not requested; posting such
findings anyway breaks the contract. Drop them — do not downgrade them.

**Write a suppression log**, after every discard rule above has been applied. This phase is the
only place suppression happens, so the log is a complete record of what the pipeline dropped, and
drops-per-id is a valid measure of which entries earn their place.

One line per discarded finding, to `.claude/data/pr-review/suppressions.txt` (`mkdir -p` it first;
`.claude/data/` is gitignored):

```
<agent> | <file>:<line> | <code> | <the finding's one-line summary>
```

`<code>` is the `EX-NNN` id for a registry match, or a reason code: `LOW`, `OUT-OF-DIFF`,
`TESTS-OFF`, `DUP`, `LOCAL`. For example:

```
pr-review-security | shared/src/jvmCommonMain/.../PinnedTrust.kt:71 | EX-002 | trust manager advertises no issuers
pr-review-code-quality | shared/src/iosMain/.../IosAudio.kt:88 | EX-015 | duplicated across the three audio backends
pr-review-tests | shared/src/commonMain/.../SetChannelReducer.kt:14 | TESTS-OFF | reducer has no unit test
```

Then one accounting line — this is what makes over-suppression visible without bloating the
comment:

```
TOTALS | received <N> | posted <M> | suppressed <K>
```

`received` counts every finding across all agent payloads (high + medium + low). Write the file
even when nothing was discarded; the TOTALS line alone is the signal that the filter ran.

The log is a diagnostic artefact and **never appears in the posted review** — no suppressed
finding, no counts, no "dropped N false positives" note.

---

## Phase 6 — Write the Review

Emit the review as your final message, wrapped **exactly** between `<<<PR_REVIEW>>>` and
`<<<END_PR_REVIEW>>>`:

- each marker on its **own line**, as literal text, **not** inside a code fence;
- **nothing before** the opening marker and **nothing after** the closing one — no preamble, no
  phase narration, no closing remarks;
- only the text between the markers is posted, so narration outside is lost and narration inside
  pollutes the comment.

```
<<<PR_REVIEW>>>
## Code Review

#### 🔴 High Priority
- **[File:Line]** What is wrong, the rule it breaks and where that rule is written. Suggested fix.

#### 🟡 Medium Priority
- **[File:Line]** Same format.
<<<END_PR_REVIEW>>>
```

There are **only these two sections**. No Low/Suggestions section — low findings were dropped in
Phase 5 and must never appear, neither as their own section nor folded into these two.

Omit a section with no items. If there are no issues at all, the body is:

```
<<<PR_REVIEW>>>
## Code Review

✅ No issues found.
<<<END_PR_REVIEW>>>
```

### Rules for writing it

1. **Be specific** — exact file and approximate line, every time.
2. **Cite where the rule lives** — `CLAUDE.md`, `docs/conventions.md`, `docs/known-issues.md` #20,
   `../ptt-server/docs/protocol.md`. A reviewer's assertion carries more weight when the reader can
   check it.
3. **Prioritise honestly.** 🔴 is for a genuine blocker: a stranded talk floor, a hardcoded host, a
   `PttEndpoint` bypass, logging on a frame path, protocol drift, a weakened trust manager, a build
   constraint regression, a second source of truth for state. Everything else is 🟡.
4. **Don't invent violations.** Findings normally come from the agents; if while synthesising you
   spot a real bug they missed, verify it against the diff (trace the path; for a cross-file issue
   confirm both sides) and include it as your own finding. Never post something you cannot ground
   in the changed code.
5. **Never flag a deleted file.** Its removal *is* the change. Treat deletions of dead code or
   rule-violating patterns as cleanup.
6. **Phrase uncertain findings as questions.** If a finding depends on something you could not
   verify — how the relay behaves, whether a follow-up change is coming, whether a platform
   difference is intentional — make it a ❓ item under the right priority, or omit it.
7. **Stay inside the diff.** Pre-existing violations in context lines or in files read for
   background are out of scope, however severe. The review comments on the change, not the
   codebase.
8. **Mention the gate when the change needs one the author may not have run.** A `:shared` change
   needs `:shared:desktopTest` and the iOS frontend compile; a build-file change needs
   `:app:assembleRelease`. Put it as a ❓ item, not an asserted finding.

---

## Phase 7 — Inline Comments File

**Skip this phase entirely unless the target was a PR number (Phase 1a).** Inline comments attach
to a PR's head commit; for a branch or working-tree review there is nothing to attach them to.

Scan the 🔴 and 🟡 items for a `[File:Line]` reference. Skip ❓ items — only asserted issues become
inline comments. For each, produce:

```json
{
  "path": "shared/src/commonMain/kotlin/com/github/devapro/pttdroid/reducer/StartSpeakReducer.kt",
  "line": 42,
  "body": "🔴 **High Priority** — Description, the rule, and the suggested fix."
}
```

- `path` is relative to the repository root, no leading `/`.
- `line` is the **new-file** line number (the right-hand side of the diff).
- `body` opens with the severity marker matching its section.

Write the array to `.claude/data/pr-review/inline_comments.json`; write `[]` if there are none.
**Do not post them** — hand the file to the user, or let a workflow post it. This repo has no
`pr-review.yml` workflow today (`.github/workflows/` holds ci, desktop, ios, pages, release), so
absent one, tell the user the file is there and what is in it.
