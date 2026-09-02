#!/usr/bin/env python3
"""Deterministic frontmatter checks for a skill or sub-agent markdown file.

Mechanical checks (name pattern, description length, file size) are more
reliable run as code than as LLM judgment, so the review skill delegates them
here and spends its own reasoning budget on the qualitative checklist instead.

Usage:
    python3 scripts/validate_frontmatter.py <path-to-SKILL.md-or-agent.md>

Exit code is always 0 — findings are data for the reviewing agent, not a
pass/fail gate. Output is one PASS/WARN/FAIL line per check.
"""
import re
import sys

NAME_RE = re.compile(r"^[a-z0-9-]+$")
DESC_MAX = 250          # house guideline; docs truncate at 1,536 combined chars
FILE_MAX_LINES = 500    # progressive-disclosure ceiling


def parse_frontmatter(text):
    """Return (frontmatter_dict, total_line_count). Tolerates folded scalars."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, len(lines)
    end = None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            end = i
            break
    if end is None:
        return {}, len(lines)

    fm = {}
    key = None
    buf = []
    folded = False
    for raw in lines[1:end]:
        # New top-level key (no leading whitespace, has a colon)
        m = re.match(r"^([A-Za-z0-9_-]+):(.*)$", raw)
        if m and not raw.startswith((" ", "\t")):
            if key is not None:
                fm[key] = " ".join(s.strip() for s in buf).strip()
            key = m.group(1)
            rest = m.group(2).strip()
            if rest in (">", "|", ">-", "|-", ">+", "|+"):
                folded = True
                buf = []
            else:
                folded = False
                buf = [rest]
        elif folded and (raw.startswith((" ", "\t")) or raw.strip() == ""):
            buf.append(raw.strip())
    if key is not None:
        fm[key] = " ".join(s.strip() for s in buf).strip()
    return fm, len(lines)


def main():
    if len(sys.argv) != 2:
        print("usage: validate_frontmatter.py <file.md>", file=sys.stderr)
        return 0
    path = sys.argv[1]
    try:
        text = open(path, encoding="utf-8").read()
    except OSError as e:
        print(f"FAIL  could not read {path}: {e}")
        return 0

    fm, line_count = parse_frontmatter(text)
    print(f"# {path}")

    name = fm.get("name", "")
    if not name:
        print("WARN  name: missing (optional per docs — the directory name is the command)")
    elif not NAME_RE.match(name):
        print(f"FAIL  name: '{name}' must be lowercase letters, numbers, hyphens only")
    elif len(name) > 64:
        print(f"FAIL  name: {len(name)} chars (max 64)")
    else:
        print(f"PASS  name: '{name}' ({len(name)} chars)")

    desc = fm.get("description", "").strip("\"'")
    if not desc:
        print("FAIL  description: missing")
    elif len(desc) > DESC_MAX:
        print(f"WARN  description: {len(desc)} chars (house guideline <= {DESC_MAX}; docs truncate combined description+when_to_use at 1536)")
    else:
        print(f"PASS  description: {len(desc)} chars")

    if line_count > FILE_MAX_LINES:
        print(f"WARN  file size: {line_count} lines (recommended <= {FILE_MAX_LINES}; move detail to references/)")
    else:
        print(f"PASS  file size: {line_count} lines")

    return 0


if __name__ == "__main__":
    sys.exit(main())
