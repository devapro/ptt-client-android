# Skills

Invoked as `/skill-name [args]`. Names are **prefix-grouped** so autocomplete clusters by domain —
type `/pr`, `/docs`, `/test`, `/qa`.

```text
/pr-        review | address-comments
/docs-      sync
/test-      fix-unit
/qa-        device
/protocol-  change
/skill-     audit
```

---

## `/pr-review [pr-number | branch] [check-tests]`

Parallel review by 8 specialist agents, then synthesis with deduplication, false-positive
suppression and a suppression log. Test review is opt-in.

```
/pr-review 42
/pr-review cmp check-tests
/pr-review                    # the working tree
```

Diffs over 2500 lines are refused — split them. Writes
`.claude/data/pr-review/suppressions.txt` and, for a PR target,
`.claude/data/pr-review/inline_comments.json`.

## `/pr-address-comments <pr-url> [--current | branch]`

Fetches unresolved review comments, weighs each against `CLAUDE.md`, `docs/` and
`.claude/contexts/review-exceptions.md`, implements what holds up, runs the gate, and drafts short
replies for you to approve before anything is posted.

```
/pr-address-comments https://github.com/owner/repo/pull/42
/pr-address-comments https://github.com/owner/repo/pull/42 --current
```

## `/protocol-change [what changed]`

The one skill written specifically for this project. A wire change has to land in **four**
implementations across **two repos** — the canonical spec, the server, the client, and the client's
own on-device relay — plus the serialization tests on both sides. There is no shared artefact, so
nothing but this catches drift.

```
/protocol-change add a channel-list message
/protocol-change make the audio frame's sequence number required
```

Stops if `../ptt-server` is not present rather than landing a client-only half.

## `/docs-sync [pr | branch | topic]`

Keeps `docs/` accurate, and enforces the rule that a platform capability change lands in
`docs/platform-support.md`, `README.md` **and** `docs/index.html` — all three, each in its own
voice. Also guards `docs/index.html`'s colour contract and the "retake, never edit" rule for
`docs/img/`.

```
/docs-sync
/docs-sync 42
/docs-sync the iOS background audio change
```

## `/test-fix-unit [class | package]`

Fixes broken tests and covers what a production change introduced, without touching production
code. Always runs **both** compilation targets — `commonTest` compiles for `androidTarget` and
`desktop`, and a pass on one is half a result.

```
/test-fix-unit
/test-fix-unit ProtocolSerializationTest
/test-fix-unit domain
```

## `/qa-device <test plan>`

Runs a plan on connected devices. Handles the parts generic device QA gets wrong here: the relay
must be running, the talk floor needs two devices, the PTT control needs a *held* press, and
emulator microphones capture silence so audibility is never the assertion.

```
/qa-device docs/my-plan.md
/qa-device hold to talk on A, verify B shows a remote speaker, release, verify B can take the floor
```

Reports land in `.claude/data/qa-reports/<timestamp>-<slug>/`.

## `/skill-audit [path]`

Reviews the skills and agents in `.claude/` against the current Claude Code docs, which it fetches
fresh each run. Also checks this repo's own invariants — including that no skill recommends
creating a `.claude/rules/` directory.

```
/skill-audit
/skill-audit .claude/agents/pr-review-compose.md
```

---

## Agents you can call directly

Not skills, but often what you actually want:

- **`build-gate`** — runs the gates and reports failures compactly, without the Gradle output.
- **`code-reviewer`** — the post-change safety net the Stop hook requires.
- **`mobile-devices`** — device automation without screenshots reaching the conversation.
