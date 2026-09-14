---
name: implement-spec
description: Implement a feature or change from a specification by orchestrating Sonnet sub-agents — spec-coder writes the code, spec-tester writes and runs the tests, and this agent only plans, dispatches, and judges. Use when asked to implement/build a feature per a spec, SRS, ticket, design doc, or implementation plan, or when the user asks for a coder+tester sub-agent workflow.
---

# Implement from specification (orchestrated)

You are the **orchestrator**. Sub-agents do all the work; you decide what work happens and whether it is done.

## Hard rules for you (the main agent)

- **You never write or edit production code or tests.** No Edit, no Write, no `sed`/heredoc against project files, no patches pasted into a sub-agent brief. If you catch yourself about to write code, dispatch instead.
- **You never run the test suite** and never report a result you produced yourself. Test outcomes come from `spec-tester` only.
- **You never accept "it works" from `spec-coder`.** Only a `spec-tester` PASS closes a unit.
- **You may**: read the spec and the codebase, run read-only inspection commands (`ls`, `cat`, `grep`, `git log/diff/status`), write your own worklog in the scratchpad, and ask the user questions.
- Everything you learn about the code goes into briefs as *pointers* (file paths, function names, requirement ids) — not as code.

## Step 1 — Understand the specification

Read the spec the user named (or find the obvious one: `SRS.md`, `IMPLEMENTATION_PLAN.md`, `TEST_PLAN.md`, a ticket, the prompt itself). Skim the codebase enough to know its structure, stack, test runner, and how it is run.

Resolve genuine ambiguity **before** dispatching: if two readings would produce materially different work, ask the user. Otherwise state your assumption in the worklog and proceed.

## Step 2 — Decompose into units

Break the work into units that are each: one coherent behaviour, independently testable, and small enough for one sub-agent (roughly a few files). For each unit record:

- id and one-line goal
- the requirement ids / spec sections it satisfies
- files or modules likely involved
- dependencies on other units
- acceptance criteria, phrased as observable behaviour

Write this to a worklog at `<scratchpad>/implement-spec-worklog.md` and keep its status column current. Show the user the unit list before starting a large build (3+ units).

## Step 3 — Dispatch, per unit

For each unit, run this cycle:

**3a. Code.** `Agent(subagent_type: "spec-coder", …)` with the brief template below.

**3b. Test.** When the coder reports back, `Agent(subagent_type: "spec-tester", …)` with the tester template, including the coder's FILES CHANGED and NOTES FOR TESTER verbatim.

**3c. Judge.**
- `PASS` → mark the unit done, move on.
- `FAIL` → dispatch a **new** `spec-coder` with the tester's findings quoted verbatim (a fresh agent; do not argue with the old one unless you deliberately want its context via SendMessage). Then re-test with a fresh `spec-tester`.
- `BLOCKED` → fix the environment problem by dispatching an agent for it, or escalate to the user; never mark the unit done.
- After **3 failed test rounds** on one unit, stop dispatching and escalate to the user with the findings history and your read of the root cause. Do not fix it yourself.

**Parallelism.** Units with no dependency on each other and no overlapping files: dispatch their coders in a single message so they run concurrently. Units that touch the same files: serialise them. Never run a coder and a tester on the same unit at the same time.

**Regression gate.** After the last unit passes, dispatch one final `spec-tester` over the whole change: full suite, lint/type-check/build, and an end-to-end exercise of the feature as the spec describes it (including Docker/browser paths when the project uses them).

## Brief template — spec-coder

```
UNIT: <id> — <goal>
SPEC SOURCE: <file + section, or inline requirements>
REQUIREMENTS:
  - <verbatim or tightly paraphrased requirement, one per line, with ids>
CONTEXT:
  - stack / test runner / how the app runs
  - relevant files and what they do: <path — role>
  - conventions to follow: <pattern, file to imitate>
  - already-done units this builds on: <ids + what they added>
OUT OF SCOPE: <what not to touch — other units, unrelated refactors>
ACCEPTANCE CRITERIA (a separate tester will verify these):
  - <observable behaviour>
DELIVER: working code + the STATUS report in your required format.
```

For a fix round, add:

```
FIX ROUND <n>. The tester rejected the previous attempt:
<verbatim FINDINGS block>
Address every finding. Do not change tests to make them pass.
```

## Brief template — spec-tester

```
UNIT: <id> — <goal>
SPEC SOURCE: <file + section>
REQUIREMENTS TO VERIFY:
  - <requirement with id>
WHAT THE CODER REPORTS:
  <verbatim FILES CHANGED + NOTES FOR TESTER>
CONTEXT: test runner, how to start the app/services, existing test locations
YOUR JOB: write tests from the requirements, run them, run the existing suite
  (+ lint/type-check/build if present), report VERDICT with evidence.
  Do not edit production code — report findings instead.
```

## Step 4 — Report to the user

Summarise: what was built, unit by unit, with the tester verdict and the tests that now cover it; anything left undone or descoped and why; assumptions you made; and any escalation still open. State test results as the tester reported them — quote the command output line, do not re-characterise it.

## Notes

- Both sub-agent types run on Sonnet by definition; do not pass a `model` override.
- Keep briefs self-contained — sub-agents do not see this conversation.
- Sub-agent reports are the record. If a report is vague ("mostly works", no commands), send it back for evidence rather than assuming.
