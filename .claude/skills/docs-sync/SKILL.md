---
name: docs-sync
description: Updates docs/ to stay accurate against the code, and enforces the three-places rule for a platform capability change. Use when asked to update, sync, check or refresh the docs. Triggers on "update docs", "sync docs", "are the docs stale", "docs for PR N".
argument-hint: "[PR number, branch name, or a topic — all optional]"
disable-model-invocation: true
---

You are the documentation maintainer for `ptt-client-android`. `docs/` is not an afterthought here:
`CLAUDE.md` requires it to be updated **in the same change as the code**, and `docs/index.html` is
the published product landing page.

## Input

$ARGUMENTS

---

## The documentation map

| File | What it is | Audience |
|---|---|---|
| `README.md` | product overview and doc index; also states the platform split in its own voice | anyone browsing the repo |
| `CLAUDE.md` | hard rules and build constraints | agents and contributors |
| `docs/architecture.md` | package map, the MVI loop, the Koin graph, talk-floor flow, reconnection, settings storage, the relay address, transport security | contributors |
| `docs/platform-support.md` | the Android / desktop / iOS capability matrix | contributors |
| `docs/audio-pipeline.md` | capture, framing, transport, playback, per platform | contributors |
| `docs/ui-design.md` | what the interface is for and the rules it follows | contributors |
| `docs/conventions.md` | Kotlin style rules | contributors |
| `docs/testing.md` | the four suites, what each covers, the gaps | contributors |
| `docs/build-and-run.md` | prerequisites, tasks, the build constraints | contributors |
| `docs/known-issues.md` | fixed defects, open limitations, platform gotchas | contributors |
| `docs/features.md` | what the app does | contributors |
| `docs/fdroid.md` | the F-Droid pipeline and key handling | maintainers |
| `docs/index.html` | the **published** product landing page (GitHub Pages, from a workflow) | users |
| `docs/privacy.html` | the published privacy policy | users |
| `docs/img/` | **real device captures** — retake, never edit | the landing page |
| `../ptt-server/docs/protocol.md` | the canonical wire contract | both repos |

---

## The three rules that are specific to this repo

### 1. A platform capability change lands in three places, or it is not done

If what Android, desktop or iOS **can or cannot do** changed, all three of these must change in the
same commit:

- `docs/platform-support.md` — the matrix
- `README.md` — its own statement of the split
- `docs/index.html` — the **Platforms** section (which duplicates the matrix deliberately, for a
  reader who will not open a Markdown file) and, if hands-free behaviour is involved, the
  **Hands-free** section (labelled Android for the same reason)

Each states the split in its own voice, so **none can be regenerated from the others**. Updating
one or two is the most common docs failure in this repo — check for it explicitly, every run.

### 2. The protocol spec changes first, and it lives in the other repo

`../ptt-server/docs/protocol.md` is canonical. If the change touches the wire, stop and use the
`protocol-change` skill instead — it covers all four implementations and both test suites.

### 3. `docs/index.html` is a published page with a colour contract

- Green / amber / red / blue mean **talk-floor states** there, exactly as in `ui/PttUiStatus`. A
  second meaning for the same colour undoes the whole readout. Keep the page colourless outside
  the state readout.
- It is served by GitHub Pages **from a workflow** (`.github/workflows/pages.yml`), not from a
  branch, because the same site carries the F-Droid repository — a branch-based deployment would
  delete it. Do not "simplify" that.
- Screenshots in `docs/img/` are real device captures. If the UI changed, **retake** them (the
  `mobile-devices` agent can capture; `adb -s <serial> exec-out screencap -p > docs/img/<name>.png`
  saves to disk). Never edit an image to match new UI.

---

## Phase 1 — Scope the change

**If the user named a topic or file**, go straight to Phase 2 with it.

**If the user named a PR or branch:**

```bash
gh pr view <n> --json title,body,files          # PR
git diff "origin/main...<branch>" --stat        # branch
git diff HEAD --stat                            # working tree
```

**If the user gave nothing**, audit against the working tree and recent history:

```bash
git diff HEAD --stat
git log --oneline -15
```

Map changed paths to docs:

| Changed path | Check |
|---|---|
| `shared/src/*/domain/`, `mvi/`, `reducer/`, `viewmodel/` | `docs/architecture.md` (package map, MVI loop, talk-floor flow) |
| `shared/src/*/di/`, `app/src/*/di/` | `docs/architecture.md` § Koin graph |
| `shared/src/*/audio/`, `app/src/*/audio/` | `docs/audio-pipeline.md` |
| `shared/src/*/network/` | `docs/architecture.md` § Transport security; the protocol → **`protocol-change` skill** |
| `shared/src/*/ui/`, `ui/theme/` | `docs/ui-design.md`; `docs/img/` if the UI visibly changed |
| `shared/src/*/data/settings/` | `docs/architecture.md` § Settings storage / § The relay address |
| `app/src/*/service/`, `overlay/`, `widget/` | `docs/architecture.md` § Foreground service; `docs/platform-support.md` |
| `shared/src/iosMain/`, `iosApp/` | `docs/platform-support.md` **+ README.md + docs/index.html** |
| `shared/src/desktopMain/`, `desktopApp/` | same three |
| a new source set, target or `dependsOn` | `docs/build-and-run.md`; `CLAUDE.md` § Modules |
| `*.gradle.kts`, `gradle.properties`, `libs.versions.toml` | `docs/build-and-run.md` § build constraints |
| `relay.properties`, `version.properties` | `docs/build-and-run.md`, `docs/fdroid.md` |
| tests added or removed | `docs/testing.md` counts, and the counts in `CLAUDE.md` |
| a defect fixed | `docs/known-issues.md` — move it from "Still open" or add it under "Fixed in the refactor" with the mechanism that replaced it |

---

## Phase 2 — Read before deciding

Read the docs you identified, in full. These files are dense and cross-referential; a paragraph
edited without its surroundings usually contradicts something two sections down.

Always also read:
- `docs/known-issues.md` — a "stale" doc is often documenting a deliberate limitation
- `CLAUDE.md` — it restates several doc rules as hard rules, and both must stay true

---

## Phase 3 — Present findings, then wait

```
## Documentation Impact

**Scope**: <PR / branch / topic>

### Needs updating
- `docs/<file>` § <section> — <what changed in the code and what the doc now says wrongly>

### Three-places check
Platform capability changed: yes / no
  - docs/platform-support.md — <needed / already correct / N/A>
  - README.md              — <needed / already correct / N/A>
  - docs/index.html        — <needed / already correct / N/A>

### Screenshots
<`docs/img/<name>.png` shows the old UI and needs retaking — or "no visible UI change">

### Checked and still accurate
- `docs/<file>` — <why it survives the change>

### Proposed edits
<per file, what you would write>
```

**Wait for confirmation before editing.** These are published documents.

---

## Phase 4 — Edit

- **Minimal changes.** Update the sentence that is now wrong. Do not rewrite a document because one
  paragraph aged.
- **Match each document's voice.** `docs/platform-support.md` is a table; `README.md` is prose for
  a browser; `docs/index.html` is a marketing page for a user who will not read Markdown. Do not
  paste the same sentence into all three — that is what the "own voice" rule means.
- **Document what the code does**, not what it should do. If the code and the intent differ, say so
  in your report rather than documenting the intent.
- **Keep cross-links working.** These docs link to each other heavily and to
  `../ptt-server/docs/protocol.md`.
- **Never invent a limitation or a capability.** If you cannot tell from the code whether iOS can
  do something, read `domain/PlatformCapabilities` and the relevant `iosMain` source, or ask.

---

## Phase 5 — Verify

```bash
# Every relative Markdown link resolves
grep -ohrE '\]\([^)#][^)]*\.md[^)]*\)' README.md CLAUDE.md docs/*.md \
  | sed -E 's/.*\((.*)\)/\1/' | cut -d'#' -f1 | sort -u \
  | while read -r f; do [ -e "$f" ] || [ -e "docs/$f" ] || echo "BROKEN: $f"; done

# The three platform docs agree — read them side by side, there is no script for this
sed -n '1,60p' docs/platform-support.md
grep -n -iA30 'platform' README.md | head -60
grep -n -iA30 'Platforms' docs/index.html | head -60
```

If code changed too, run the build gate (or the `build-gate` agent) — a docs change that
accompanies a code change is not done until the code passes.

---

## Phase 6 — Report

```
## Docs Synced

### Updated
- `docs/<file>` § <section> — <what changed>

### Three-places check
<all three updated / not a capability change, so N/A>

### Screenshots
<retaken / not needed>

### Verification
- links: ✅ / <broken ones>
- the three platform docs agree: ✅ / <where they diverge>
- build gate: <result, or "no code changed">

### Not done
<anything deliberately left, and why>
```

---

## Rules

1. **Never `git commit` or `git push`** unless the user explicitly asks.
2. **Three places or it is not done** for a platform capability change.
3. **The protocol spec is in the other repo and changes first** — use `protocol-change`.
4. **Retake screenshots; never edit them.**
5. **`docs/index.html` stays colourless outside the state readout.**
6. **Don't fabricate.** Ambiguity goes into the report as a question, not into a document as a
   claim.
7. **Release text only in `fastlane/metadata/android/en-US/`** — F-Droid reads that layout directly
   and a second copy in `metadata/*.yml` would drift.
