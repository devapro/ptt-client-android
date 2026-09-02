# `.claude/contexts/` — On-Demand Reference Context

Documents that **an agent or skill reads by explicit path when it needs them**, and that are
never auto-attached to the main conversation.

## Why there is no `.claude/rules/` here

The AndroidRepo setup this was adapted from keeps a `.claude/rules/` directory of ~8000 lines of
canonical coding rules. **This repo deliberately does not**, because it already has a canonical
home for them: `CLAUDE.md` (hard rules) and `docs/` — `conventions.md`, `architecture.md`,
`audio-pipeline.md`, `ui-design.md`, `platform-support.md`, `testing.md`, `known-issues.md`, and
`../ptt-server/docs/protocol.md` for the wire contract.

Copying those into `.claude/rules/` would create the exact second source of truth that
`CLAUDE.md` forbids everywhere else in this project, and `docs/` is already required to be
updated in the same change as the code. So:

- **Canonical rules live in `docs/` and `CLAUDE.md`.** Cite them; do not restate them.
- **Review agents carry short checklists** in their own prompt files, because subagents load
  nothing automatically — their prompt *is* their context. Each names its canonical doc and
  repeats the canonical-wins rule.
- **This directory holds only reviewer-facing reference material**, read on demand.

## Contents

| File | What it is | Read by |
|---|---|---|
| `code-review.md` | Reviewer's digest: diff size gate, severity scale, the enumerated PTT issue lists, closing checklist | `code-reviewer`, `pr-review` |
| `review-exceptions.md` | `EX-NNN` registry of confirmed review false positives, each with a "Still an issue" boundary | `pr-review` synthesis, `code-reviewer` |

## What belongs here

All four must hold:

1. **Reference material, not a rule.** It informs a reviewer's decision; it does not tell an
   author how to write code. A rule belongs in `docs/` (and, if it is a hard constraint, in
   `CLAUDE.md`).
2. **Its audience is a specific agent or skill**, not everyone editing code.
3. **It is not something a reader of `docs/` needs.** `docs/` is published — it is read by
   contributors and, via `docs/index.html`, by users. Review plumbing is not.
4. **Something reads it by explicit path.** A document nothing loads is dead weight.

## What does not belong here

- **A coding rule** → `docs/`, plus a line in `CLAUDE.md` if it is a hard constraint.
- **A platform capability difference** → `docs/platform-support.md`, `README.md` and
  `docs/index.html`, all three (`CLAUDE.md` requires it).
- **An already-fixed defect or a platform gotcha** → `docs/known-issues.md`. A review false
  positive that exists *because* of a gotcha gets an `EX-NNN` here that cites that entry.
- **An agent's own checklist** → the agent file.
- **Generated artefacts** (fetched PR comments, review reports) → `.claude/data/`, gitignored.
