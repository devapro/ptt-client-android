---
name: code-find-symbol
description: Query kotlin-lsp for precise symbol navigation in this Kotlin project — find references through interface overrides and imports, jump to definition, find implementations of an interface or overrides of a method. Use this skill whenever grep can't answer the question correctly: common-named symbols (`State`, `Router`, `Mapper`, `Reducer`), references that go through type aliases or fully-qualified imports, "who implements this interface", or "who overrides this method". Also use when an LSP query has already been attempted and produced an empty or surprising result and you need to retry with the right indexing wait. Do NOT use this skill for literal-string lookups or uniquely-named symbols — grep is ~1000× faster and authoritative there (see `.claude/rules/search-tools.md`). Formerly `/kotlin-lsp` / `kotlin-lsp`.
---

# code-find-symbol

A wrapper around `kotlin-lsp --stdio` that handles the cold-start gotchas in this repo: orphan processes holding the index lock, multi-minute Gradle imports during `initialize`, and the index-readiness ambiguity in empty results.

## When to reach for this skill

Use it when the question is about **meaning**, not text:

- "Find all usages of `Reducer`" — grep returns hundreds of unrelated `Reducer` matches across modules; LSP returns only the one you clicked.
- "Who implements `NavigationHandler`?" — grep on `: NavigationHandler` misses qualified implementations and type aliases.
- "Who overrides `reduce()`?" — `override fun reduce` matches every reducer in the codebase, regardless of which interface they implement.
- "Where is `openAddEconomicAlert` called?" — even a unique-looking method can hide call sites behind interface dispatch.

Skip it for:

- Literal strings (TODO markers, log messages, resource IDs, deeplink paths).
- Uniquely-named symbols where false positives are essentially impossible (`AddEconomicAlertRouter`, `BrokerOfferEligibilityManager`). Grep is authoritative.
- Files the LSP doesn't index (`*.xml`, `*.gradle.kts`, `*.md`, generated code under `build/`).

The decision rule lives in `.claude/rules/search-tools.md` — read it if unsure.

## How to invoke

The bundled script handles the LSP protocol; you just pass the position.

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/lsp_query.py <op> <file> <line> <char> --repo ${CLAUDE_PROJECT_DIR}
```

- `<op>` is `references` | `definition` | `implementation`.
- `<file>` is an absolute path to a `.kt` file containing the symbol.
- `<line>` / `<char>` are **1-indexed** (matches grep output and editor gutters). The script converts to LSP's 0-indexing internally.
- The position must point **inside the symbol name**, not at surrounding tokens.
- `--repo` must be the repo root. The script's built-in default is `/workspace` (the Docker image's `WORKDIR`); that path does not exist on a host checkout, so always pass `--repo` (or set `LSP_REPO_ROOT`).

Output is one match per line, formatted `path:line:col` so you can chain it with editor jumps or further greps.

### Examples

Find references to the `Reducer` interface, with the cursor inside the type name in its declaration file:

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/lsp_query.py references \
    ${CLAUDE_PROJECT_DIR}/core/src/main/java/com/fusionmedia/investing/core/mvi2/Reducer.kt 5 12 \
    --repo ${CLAUDE_PROJECT_DIR}
```

Find implementations of `NavigationHandler`:

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/lsp_query.py implementation \
    ${CLAUDE_PROJECT_DIR}/services/service-navigation/src/main/java/com/fusionmedia/investing/services/navigation/core/NavigationHandler.kt 10 12 \
    --repo ${CLAUDE_PROJECT_DIR}
```

Jump to definition of a symbol at a call site:

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/lsp_query.py definition \
    ${CLAUDE_PROJECT_DIR}/features/feature-x/.../SomeFile.kt 42 25 \
    --repo ${CLAUDE_PROJECT_DIR}
```

### Picking the position

To avoid manual counting, find the line first with grep:

```bash
grep -n "interface Reducer" ${CLAUDE_PROJECT_DIR}/core/src/main/java/com/fusionmedia/investing/core/mvi2/Reducer.kt
# 5:interface Reducer<ACTION : NEXT, STATE, NEXT : Any, EVENT> {
```

Then point `<char>` at any column inside the word `Reducer` — the symbol spans columns 11–17 (`interface ` is 10 chars), so column 12 is safe. Off-by-one errors here cause LSP to return `null` or an unrelated symbol's data, not a useful error message, so when results look wrong the position is the first thing to double-check.

## Why this is slow on the first call

Two things dominate wall-clock time:

1. **Gradle project import during `initialize`** — kotlin-lsp re-imports the whole multi-module Gradle build on every cold start. In this repo that takes 5–15 minutes on a fresh container with no on-disk caches, less afterwards. The script's default `--init-timeout` is 25 minutes for safety.
2. **Post-`didOpen` indexing wait** — after the file is opened, the LSP needs time to resolve the project's symbols before reference queries return correct results. The script waits 120s by default. Bump it (`--wait 300`) if the symbol is in a module with a lot of dependencies, or if you got an empty result you don't trust.

For follow-up queries within the same Claude session, every invocation still pays the cold start because the script starts a fresh `kotlin-lsp` process each time. A single long-running query is much cheaper than several short ones.

## Interpreting empty results

**An empty result is ambiguous.** It means either "the symbol is genuinely unused" or "the index wasn't ready when we queried." LSP gives the same answer for both.

To disambiguate:

1. Run grep for the same name. If grep also returns nothing, the symbol is truly unused — trust the LSP result.
2. If grep returns matches but LSP returned nothing, the index wasn't ready. Re-run with `--wait 300` (or longer for a heavily-depended-on symbol).

This caveat is the single biggest footgun with LSP-based navigation. The bundled script reminds you of it on every empty result; do not ignore the reminder.

## Cleanup and orphan processes

`kotlin-lsp` keeps a RocksDB index lock under `~/.config/JetBrains/analyzer/workspaces/.../rocks/v492/LOCK`. If a previous run died without sending `shutdown` (e.g., the parent Python process timed out and got killed), the kotlin-lsp child is reparented to PID 1 and keeps holding the lock — the next run will fail to open the index with `Resource temporarily unavailable`.

The script handles this automatically: before starting its own LSP, it kills any existing `kotlin-lsp` processes. If you ever see "Resource temporarily unavailable" or a RocksDB error in `/tmp/kotlin_lsp_stderr.log`, kill any leftover processes manually:

```bash
pkill -f kotlin-lsp ; sleep 3 ; ps aux | grep kotlin-lsp | grep -v grep
```

## Knobs worth knowing

| Flag | Default | When to change it |
| --- | --- | --- |
| `--wait` | 120 | Increase to 300+ when an empty result is suspect or the symbol is in a module with many dependents. |
| `--init-timeout` | 1500 | Usually fine; bump only if you see "initialize timed out" in stderr. |
| `--repo` | `/workspace` | Always pass the actual repo root on a host checkout — `/workspace` only exists inside the Docker container. The `LSP_REPO_ROOT` env var overrides the default. |
| `--stderr-log` | `/tmp/kotlin_lsp_stderr.log` | Inspect this file when something goes wrong — the LSP's own logs are referenced from there. |
| `--quiet` | off | Suppress progress messages; useful in scripted pipelines. |

## When the result still looks wrong

In rough order of likelihood:

1. **Position is off.** Double-check that `<line>:<char>` points inside the symbol name. Adjacent whitespace or punctuation gives empty results.
2. **Index not ready.** Re-run with `--wait 300`.
3. **Project import failed.** Read `/tmp/kotlin_lsp_stderr.log` — Gradle errors land there and the LSP gives up silently on the query side.
4. **Symbol resolves through a generated source set.** kotlin-lsp does not index `build/generated/...`. If the symbol you're chasing exists only in generated code (KSP-generated `*FooterBannerIdProvider`, Hilt/Dagger stubs, etc.), fall back to grep on the generated sources.

If you've checked all four and it still looks wrong, fall back to grep and note the LSP gap.
