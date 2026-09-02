---
name: skill-audit
description: >
  Reviews Claude Code skills and sub-agent definitions for quality and best-practice
  compliance, fetching the latest official docs each run. Use when asked to review, audit,
  or improve a skill or agent, or check if one follows Claude docs guidelines.
  Reviews the skills and agents in this repo's .claude/ directory.
disable-model-invocation: true
---

# Review Skill & Agent Quality

You review Claude Code skills and sub-agent definitions against the official documentation
and surface concrete improvements. You fetch fresh docs on every run because the platform
evolves quickly and stale advice is worse than no advice.

## Step 1 — Fetch the latest documentation

Before analyzing anything, fetch these five pages and extract their full content.
This is non-negotiable — the docs are the source of truth, not your training data.

```
WebFetch(https://code.claude.com/docs/en/skills)
  → Extract: frontmatter fields, file structure, description guidelines,
    triggering behavior, supporting files, context: fork, allowed-tools,
    string substitutions, advanced patterns, troubleshooting

WebFetch(https://code.claude.com/docs/en/sub-agents)
  → Extract: frontmatter fields (name, description, tools, disallowedTools,
    model, permissionMode, maxTurns, skills, mcpServers, hooks, memory,
    background, effort, isolation, color, initialPrompt),
    tool configuration, permission modes, hook patterns, memory scopes,
    example subagents, best practices

WebFetch(https://code.claude.com/docs/en/best-practices)
  → Extract: context management, CLAUDE.md guidelines, verification patterns,
    subagent usage patterns, common failure patterns

WebFetch(https://agentskills.io/skill-creation/best-practices)
  → Extract: spending context wisely, coherent units, moderate detail,
    progressive disclosure, calibrating control (defaults not menus,
    procedures over declarations), gotchas/templates/checklists/validation/
    plan-validate-execute patterns, bundling scripts

WebFetch(https://agentskills.io/skill-creation/evaluating-skills)
  → Extract: eval-driven iteration, evals/evals.json structure, with/without
    baseline comparison, assertions, grading, benchmarks, when a skill is
    adding value at all
```

Read these docs carefully. They — not the inline lists below — are the
authoritative checklist. The frontmatter field names and rules listed in Step 4
are illustrative reminders that may lag the live docs; **if the fetched docs
differ from anything in Step 4, the fetched docs win.**

## Step 2 — Identify what to review

Ask the user what they want reviewed if not obvious from context. Options:

- A specific skill: path to SKILL.md
- A specific agent: path to .md file in .claude/agents/
- All skills in .claude/skills/
- All agents in .claude/agents/
- A skill or agent the user pastes inline

If reviewing "all", list what you found and let the user confirm before
proceeding with the full review.

### What this repo currently has

```
.claude/agents/    code-reviewer, build-gate, mobile-devices,
                   pr-review-{architecture,multiplatform,compose,code-quality,
                              tests,performance,correctness,security}
.claude/skills/    pr-review, pr-address-comments, protocol-change,
                   docs-sync, test-fix-unit, qa-device, skill-audit
.claude/contexts/  code-review.md, review-exceptions.md
.claude/hooks/     post-edit-review-reminder.sh, pre-stop-review-check.sh
```

Note the deliberate absence of `.claude/rules/`. In this repo the canonical coding
rules live in `CLAUDE.md` and `docs/`, and the review agents carry short cited
checklists instead — see `.claude/contexts/README.md` for the reasoning. **Do not
recommend creating a `.claude/rules/` directory**; a suggestion to move a rule out
of `docs/` into `.claude/` is a FAIL against this repo's own design.

## Step 3 — Read the target files

For each skill or agent being reviewed, read the full file. Also check for:
- Supporting files in the skill directory (scripts/, references/, examples/, templates)
- Whether SKILL.md references its supporting files properly

Run the bundled mechanical checks first — name pattern, description length, and
file size are checked more reliably by code than by eyeballing:

```bash
python3 ${CLAUDE_SKILL_DIR}/scripts/validate_frontmatter.py <path-to-target.md>
```

(Run this via Bash once the target is known — the path isn't available at
skill-render time, so render-time `!` injection cannot be used here.)

Use its PASS/WARN/FAIL output as input to the Step 4 checklist; spend your own
reasoning on the qualitative items the script can't judge.

## Step 4 — Review against the documentation

Run through these checklists. For each item, mark it as PASS, WARN, or FAIL
with a brief explanation. Only flag real issues — if something is fine, say PASS
and move on. Do not invent problems.

### For Skills (SKILL.md files)

**Frontmatter**
- `name`: optional (the directory name is the command); lowercase-letters-numbers-hyphens
  only, max 64 chars — the format applies to the directory name too
- `description`: present, front-loads key use case; house guideline under 250 chars
  (docs truncate combined `description` + `when_to_use` at 1,536 chars in the listing)
- `description`: specific enough for Claude to trigger correctly (not too broad, not too narrow)
- `disable-model-invocation`: set appropriately (true for side-effect workflows like deploy/commit)
- `user-invocable`: set to false if it's background knowledge only
- `allowed-tools`: scoped appropriately (not granting unnecessary tools)
- `context`: set to `fork` if the skill should run in isolation (and body has explicit task instructions)
- Other fields (`model`, `effort`, `paths`, `hooks`, `shell`) used correctly if present

**Content quality**
- Instructions are clear and actionable
- Under 500 lines (move excess to supporting files)
- Uses `$ARGUMENTS` or `$N` correctly if it takes arguments
- Uses `!`command`` syntax correctly for dynamic context injection (if applicable)
- Explains the *why* behind important instructions, not just the *what*
- Avoids excessive MUST/ALWAYS/NEVER — prefers explaining reasoning
- References supporting files with clear guidance on when to read them
- **Does not duplicate a rule whose canonical home is `CLAUDE.md` or `docs/`** — it
  cites it instead. The one sanctioned exception is a review agent's checklist:
  subagents load nothing automatically, so their prompt is their context. Those must
  name their canonical doc and repeat the canonical-wins rule; a checklist that
  restates a rule without citing where it lives is a WARN.

**Structure**
- Skill directory has SKILL.md as entrypoint
- Supporting files are organized logically (scripts/, references/, etc.)
- Large reference material is in separate files, not inline in SKILL.md
  (progressive disclosure: tell the agent *when* to read each one, not just "see references/")
- Scripts are present for deterministic/repetitive tasks the skill needs

**Triggering**
- Description covers the main use case and likely user phrasings
- Description is specific enough to avoid false triggers on adjacent topics
- If `disable-model-invocation: true`, is this intentional and appropriate?

### For Sub-agents (.claude/agents/ files)

**Frontmatter**
- `name`: present, unique identifier with lowercase and hyphens
- `description`: present, clearly states when Claude should delegate to this agent
- `tools`: restricted to what the agent actually needs (principle of least privilege)
- `disallowedTools`: used if the agent should inherit most tools but exclude a few
- `model`: chosen appropriately for the task (haiku for fast reads, sonnet for analysis, opus for complex work, inherit if no special need)
- `permissionMode`: set appropriately (plan for read-only, dontAsk for background)
- `maxTurns`: set if the agent should be bounded
- `skills`: lists skills the agent needs, if any
- `mcpServers`: scoped correctly (inline for agent-only, string ref for shared)
- `hooks`: used for conditional validation (e.g., PreToolUse for command filtering)
- `memory`: scope chosen appropriately (user/project/local)
- `background`: true if this agent typically runs concurrently
- `effort`: set if different from session default
- `isolation`: worktree if the agent needs an isolated repo copy

**Prompt quality (the markdown body)**
- Focused on one specific task (not a kitchen-sink agent)
- Clear step-by-step workflow when applicable
- Specifies output format so Claude knows what to return
- Does not repeat Claude Code's built-in system prompt
- Provides checklist or criteria for the agent to follow
- Explains the *why* behind constraints

**Architecture**
- Read-only agents don't have Edit/Write tools — every `pr-review-*` agent in this
  repo is read-only by design and returns JSON; one that gained Edit/Write is a FAIL
- Agents that modify code include verification steps (run tests, lint). In this repo
  that means naming the right gate: `assembleDebug testDebugUnitTest lintDebug`, plus
  `:shared:desktopTest` and the iOS frontend compile when `:shared` changed. An agent
  or skill that treats `assembleDebug` alone as verification is a WARN
- Description includes "proactively" if the agent should auto-trigger
- Agent doesn't assume it can spawn sub-agents (nesting is off by default; requires
  `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH` to be enabled)

## Step 5 — Present findings

Organize your report as:

```
## Review: <skill-or-agent-name>

### Summary
One paragraph: overall quality assessment and the most impactful change.

### Findings

| # | Severity | Area | Finding | Recommendation |
|---|----------|------|---------|----------------|
| 1 | FAIL     | ...  | ...     | ...            |
| 2 | WARN     | ...  | ...     | ...            |
| 3 | PASS     | ...  | ...     | (n/a)          |

### Suggested rewrite (if FAIL items exist)
Show the corrected version of the problematic sections only.
Do not rewrite things that are already fine.
```

Severity guide:
- **FAIL**: Violates documented best practice. Will cause incorrect behavior or poor triggering.
- **WARN**: Suboptimal but functional. Worth improving when convenient.
- **PASS**: Follows best practices. No action needed.

## Step 6 — Offer to apply fixes

After presenting findings, ask the user if they want you to apply the recommended
changes. Only modify files the user approves. For description optimization,
mention that the `/skill-audit` skill reviewed it but the user should
test triggering with real prompts to confirm.

## Step 7 — Repo-specific expectations

Beyond the generic checklists, a skill or agent in this repo should hold up against
its own subject matter. Flag these as FAIL when they are wrong, because each one has
already caused a real defect here:

- **A checklist that omits the per-frame audio rule** while claiming to review the
  audio pipeline or performance.
- **A protocol workflow that names fewer than four implementations** — the spec, the
  server, the client, and the on-device relay — or that omits the serialization tests
  on both sides.
- **A build gate that stops at `assembleDebug`** when `:shared` is in scope.
- **A capability claim about a platform** that is not backed by
  `docs/platform-support.md`, or a docs workflow that does not enforce the
  three-places rule (`platform-support.md` + `README.md` + `docs/index.html`).
- **A device workflow that treats a tap as exercising the PTT control**, or that
  concludes anything about audibility on an emulator.
- **A `git commit` or `git push`** anywhere in a skill body without an explicit
  "only when the user asks" guard — `CLAUDE.md` forbids it, and two repos means two
  histories to pollute.
- **A hardcoded relay host or port** in an example, including in a code fence.

And check the review pipeline's own invariant: the `pr-review-*` agents must be told
**not** to pre-filter against `.claude/contexts/review-exceptions.md`, and the
`pr-review` skill's synthesis phase must be the single place that applies it. An
agent that reads the registry itself, or a synthesis phase that assumes the agents
already filtered, silently drops real findings. `code-reviewer` is the deliberate
exception — it runs standalone with nothing downstream, so it applies the registry
itself.
