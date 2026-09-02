# `.claude/` — agent tooling for ptt-client-android

Adapted from the setup in `../../AndroidRepo/.claude`. That repo is a large multi-module
single-platform Android app with Jira, Figma, Xray, JaCoCo, Roborazzi, Kaspresso, detekt and
in-app editions. This one is ~106 Kotlin files across one Compose Multiplatform codebase, with a
sibling server repo and none of that tooling — so what came over was ported and rewritten, not
copied.

```
.claude/
├── settings.json              permissions + the two review hooks
├── agents/                    11 subagent definitions
├── skills/                    7 skills
├── contexts/                  reviewer-facing reference, read by explicit path
├── hooks/                     the review gate
└── data/                      generated artefacts (gitignored)
```

**There is no `.claude/rules/` directory, deliberately.** See
[`contexts/README.md`](contexts/README.md) for the reasoning: the canonical coding rules already
live in `CLAUDE.md` and `docs/`, which this project requires to be updated in the same change as
the code. A second copy under `.claude/` would be exactly the drift `CLAUDE.md` forbids everywhere
else. The review agents carry short **cited** checklists instead, because a subagent loads nothing
automatically — its prompt is its context.

---

## Agents

| Agent | What it does |
|---|---|
| `code-reviewer` | post-change safety net; the Stop hook requires it. Applies the exception registry itself |
| `build-gate` | runs the repo's gates and reports failures compactly. Keeps Gradle output out of context |
| `mobile-devices` | device/emulator automation. Keeps screenshots and UI trees out of context |
| `pr-review-architecture` | MVI layering, `PttController` as sole state owner, the `PttEndpoint` contract, Koin wiring |
| `pr-review-multiplatform` | source-set placement, `expect`/`actual` completeness, cinterop imports, build constraints, the three platform docs |
| `pr-review-compose` | the `PttUiStatus` colour/wording contract, the press-and-hold gesture, recomposition, accessibility |
| `pr-review-code-quality` | null safety, immutability, constants, comment density and staleness |
| `pr-review-tests` | coverage of new behaviour, test source-set placement, protocol serialization tests |
| `pr-review-performance` | the per-frame audio rule, coroutines, device and socket lifecycle |
| `pr-review-correctness` | bug hunt — protocol drift, the talk floor, reconnection, exception paths, concurrency |
| `pr-review-security` | pinning on both paths, the access token, hardcoded relays, cleartext policy, key material |

The eight `pr-review-*` agents are read-only, return JSON, and are launched in parallel by the
`pr-review` skill. They are deliberately told **not** to filter against
`contexts/review-exceptions.md` — synthesis is the single place that applies it, with every changed
file in hand.

## Skills

Names are prefix-grouped so `/pr`, `/docs`, `/test`, `/qa` cluster in autocomplete.

| Skill | What it does |
|---|---|
| `/pr-review [pr\|branch] [check-tests]` | parallel review by the 8 specialists, with synthesis, suppression log and inline-comment file |
| `/pr-address-comments <pr-url> [--current\|branch]` | fetch unresolved review comments, weigh them against this repo's rules, implement, validate, draft replies |
| `/protocol-change [what changed]` | **repo-specific.** Lands a wire change across all four implementations in two repos plus both test suites |
| `/docs-sync [pr\|branch\|topic]` | keeps `docs/` accurate, and enforces the three-places rule for a platform capability change |
| `/test-fix-unit [class\|package]` | fixes broken tests and covers what a change introduced, on **both** compilation targets |
| `/qa-device <test plan>` | runs a plan on device(s), including the two-device talk-floor cases and held presses |
| `/skill-audit [path]` | reviews the skills and agents here against the current Claude docs |

## Contexts

| File | What it is |
|---|---|
| `code-review.md` | reviewer's digest — the size gate, the severity scale, the enumerated issue lists, the closing checklist |
| `review-exceptions.md` | `EX-001`…`EX-015`, each with a "Still an issue" boundary. Fifteen shapes that look like defects here and are not |

## Hooks

`post-edit-review-reminder.sh` queues edited `.kt` / `.kts` / `.swift` files;
`pre-stop-review-check.sh` blocks the turn until `code-reviewer` has run. Escape hatch:
`export PTT_SKIP_REVIEW_GATE=1`. See [`hooks/README.md`](hooks/README.md).

---

## What was ported, and what changed in the port

| Source | Here | Change |
|---|---|---|
| `agents/pr-review-package-structure` | `pr-review-multiplatform` | rewritten around the KMP source-set map, `expect`/`actual`, cinterop imports and the build constraints — the highest-value review dimension in this repo, and one the original had no equivalent for |
| `agents/pr-review-{architecture,compose,code-quality,tests,performance,correctness,security}` | same names | checklists rewritten against `CLAUDE.md` and `docs/`. Compose gained the readout contract and the press-and-hold invariants; performance gained the per-frame table; correctness gained the protocol triple and the talk-floor state machine; security gained both pinning paths |
| `agents/code-reviewer` | same | same rewrite; the Gate line is new |
| `agents/unit-test-runner` + `jacoco-coverage-analyzer` | `build-gate` | merged and rewritten. There is no JaCoCo here; the interesting failure is a `commonTest` that passes on one target and fails on the other, so the agent must always name the target |
| `agents/mobile-devices` | same | the relay requirement, `10.0.2.2`, two devices, held presses, the four surfaces, "never conclude audibility" |
| `skills/pr-review` | same | 2500-line gate (was 4000); 8th agent swapped; PTT risk factors; suppression log under `.claude/data/`; no CI workflow to post to, so the inline-comment file is handed to the user |
| `skills/pr-review/scripts/parse_diff.py` | same | risk-factor regexes rewritten: the audio frame paths, the protocol triple, the state owners, the pinned-TLS files, the build constraints, iOS sources, and a partial-platform-doc-update check |
| `skills/docs-sync` | same | rewritten for this repo's flat `docs/`; the three-places rule and the `docs/index.html` colour contract are the substance |
| `skills/test-fix-unit` | same | both compilation targets; literal-JSON protocol tests; the masked-field and audibility traps; no JaCoCo step |
| `skills/qa-device` | same | two-device floor protocol; held presses; the four surfaces; relay setup; no editions, no CJK IME |
| `skills/pr-address-comments` | same | scripts copied unchanged (they are project-agnostic); the rules step reads `CLAUDE.md`/`docs/`/the registry, with a table of suggestions that would break this app |
| `skills/skill-audit` | same | near-verbatim; gained the repo inventory, the no-`rules/` rule and a repo-specific expectations step |
| `contexts/code-review.md` | same | rewritten; ~200 lines of Investing-specific findings replaced with this repo's |
| `contexts/review-exceptions.md` | same | rewritten from scratch; the 15 entries are seeded from `docs/known-issues.md` |
| `hooks/*` | same | `.swift` tracked, `build/`/`.gradle/`/`.kotlin/`/`.claude/` excluded, KMP test sets excluded by directory, PTT queue name, an escape hatch, and the gate restated in the block message |
| `settings.json` | same | `git commit`/`git push` moved from **allow** to **deny** (`CLAUDE.md` forbids them without an explicit ask, so the permission prompt *is* the ask); keystores and `local.properties` denied to Read; PTT gradle/adb/gh entries |

### Deliberately not ported

| Source | Why not |
|---|---|
| `rules/` (31 files, ~8000 lines) | see the audit below |
| `maintain-rules`, `rules-codify` | they exist to validate and grow `.claude/rules/`, which this repo does not have |
| `jira-create`, `jira-implement`, `test-sync-xray` | no Jira or Xray |
| `qa-compare-figma` | no Figma refs, no `@FigmaRef` annotations |
| `test-write-roborazzi`, `test-write-kaspresso` | no Roborazzi, no Kaspresso — UI tests are Compose instrumented tests |
| `test-raise-coverage`, `test-sync-limits`, `jacoco-coverage-analyzer` | no JaCoCo, no coverage thresholds |
| `qa-capture-lqa`, `lqa-auth`, `lqa-screen-capture` | no in-app editions, no accounts (one shared token) |
| `qa-regression`, `test-audit-cases`, `test-write-qa-cases` | no `test-cases/` corpus to run, audit or generate into |
| `feature-plan`, `feature-scaffold`, `feature-implement` | built around `api-*` + `feature-*` module scaffolding that does not exist here |
| `docs-draft-to-srs`, `docs-export-overview`, `docs-review` | no SRS workflow; `docs/` is hand-written prose |
| `compose-add-previews` | previews already exist on the components that need them, and there is no `@InvestingPreview` equivalent |
| `pr-split`, `pr-mine-comments` | would work, but there is no PR-review history here to mine and no PR large enough to need splitting yet. Easy to port later if that changes |
| `code-find-symbol`, `rules/search-tools.md`, `rules/ast-index.md` | depend on the `kotlin-lsp` and `ast-index` plugins, which are not enabled here. 106 files is well inside grep's range |
| `skill-create` | large (scripts, eval harness, viewer); `skill-audit` covers the review half, which is what was asked for |
| `permissions.json` | superseded by `settings.json`'s `permissions` block |

### The `rules/` audit

`.claude/rules/` was reviewed file by file, not skipped wholesale. Three categories came out:

**Investing infrastructure — nothing to port** (21 files): `analytics`, `navigation`, `app-result`,
`error-ui`, `snackbar`, `webview`, `table-components`, `text-input-components`, `main-tabs-api`,
`http-cache`, `footer-banner`, `ads-sdk-integration`, `date-formatting`, `number-formatting`,
`debug-translations`, `realtime`, `remote-config-experiments`, `viewmodel-patterns`, `ast-index`,
`search-tools` (needs the `kotlin-lsp` plugin, not enabled here), and most of `naming-conventions`
(`Response`/`Request`/`Dto`/`Model` suffixes are that project's wire and presentation
conventions).

**Generic engineering content this repo already states** — ported into the review agents as short
cited checklists, which is where a subagent can actually use them, rather than duplicated as rule
files: the Compose modifier-chain order and modifier-parameter convention, `Modifier.weight` sums,
effects placement, `remember` for expensive allocations, accessibility `contentDescription`,
comment density and staleness, `!!`, swallowed `CancellationException`, detached coroutines,
`withContext` inside `flow { }`, unbounded caches, listener unregistration, secrets in logs, host
allowlists, `SecureRandom`, weak crypto.

**Genuinely missing here, and applicable** — one rule, now added to `pr-review-architecture`,
`pr-review-correctness`, `code-reviewer` and `contexts/code-review.md`:

> A reducer that **suspends** must not write back the `state` it was called with.
> `Reducer.reduce(action, state)` receives the snapshot taken before it ran, and
> `MainActivityViewModel.onAction` launches **one coroutine per action** then assigns
> `_state.value = result.state` outright. While a reducer is suspended on I/O, another action — or
> the `controller.state.collect` mirror in the ViewModel's `init` — can update `_state`, and the
> reducer's return value silently overwrites it.

The source repo's version of this rule (`mvi-architecture.md` § "Reducer State Snapshots") is
written for a `getState()` lambda, where the failure mode is calling it twice. This repo passes
`state` by value, so that half does not apply — but the stale-write half applies more directly,
because nothing here serialises action processing.

**Scope, checked rather than assumed:** exactly **one** of the ten reducers suspends today.
`PttController.start`/`stop`/`restart`/`setChannel`/`requestTalk`/`releaseTalk`/`clearError` and
both `PttSessionLauncher` methods are plain non-suspend functions, so nine reducers run to
completion without yielding and are not exposed at all.

The one that is, is `SaveSettingsReducer`: it suspends on `settingsRepository.save(...)` — a
DataStore write — then returns `state.copy(screen = Main)` built from the pre-suspend snapshot. A
`ptt` update landing during that write is discarded, and a settings save is immediately followed by
`MainAction.Reconnect`, which is exactly when the controller emits. The symptom is a briefly stale
talk-floor readout after saving settings; the next controller emission heals it. Real and
reachable, but transient and self-healing — which is why it has not shown up as a reported bug.

So the rule earns its place mainly as a **guard against the next suspending reducer**, not as a
description of widespread breakage. The agents are told this explicitly, so they do not flag the
nine reducers that cannot hit it.

**This is a latent defect in production code, not a tooling gap**, so it has not been changed. The
fix belongs at the assignment in `MainActivityViewModel.onAction` — merge with `_state.update { }`
rather than replacing, or serialise dispatch — not in each reducer. If it is fixed, the rule also
belongs in `docs/conventions.md` § Architecture, since `docs/` is this repo's canonical home for
conventions and contributors do not read `.claude/`.

Two more source rules were considered and rejected on inspection: **"No logic in init blocks"**
would fire on `MainActivityViewModel.init`'s controller mirror, which is correct code here; and
**"Time Handling"** is built on `TimeUnit` and a `DateTimeProvider`, neither of which exists in
`commonMain` (`kotlin.time.Duration` is the multiplatform equivalent) — and this repo does almost
no time arithmetic.

### Worth adding later

- **`pr-split` / `pr-mine-comments`** once this repo has PR history.
- **`code-find-symbol`** if the `kotlin-lsp` plugin gets enabled — the multi-source-set layout is
  where grep is weakest here (`expect`/`actual` pairs and same-named platform classes).
- **A `pr-review.yml` workflow** — the `pr-review` skill already writes
  `.claude/data/pr-review/inline_comments.json` in the shape a poster script wants; there is just
  no workflow reading it. `../../AndroidRepo/.claude/skills/pr-review/scripts/post_review.py` is
  the reference.
- **A server-side counterpart** in `../ptt-server/.claude/` — `protocol-change` reaches across the
  boundary, but the server repo has no agent tooling of its own.
