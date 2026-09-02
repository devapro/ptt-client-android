#!/usr/bin/env python3
"""
post_replies.py — Post the drafted review-comment replies produced by the
pr-address-comments skill's CI run to a GitHub PR.

In interactive use the skill posts replies itself (SKILL.md Phase 9). The CI
workflow (pr-address-comments.yml) instead has the model *draft* the replies
into a JSON file and commit+push the fixes first, so the fix lands before the
"done" reply appears. This script then posts those drafts, in order.

Reads a JSON array of reply objects:
    [
      { "kind": "inline",         "rest_id": 123456789, "reviewer": "login", "body": "done" },
      { "kind": "review_summary",                        "reviewer": "login", "body": "Re: @login ...\n- ..." }
    ]

Posting rules (mirror SKILL.md Phase 9):
  - inline          → POST /repos/{owner}/{repo}/pulls/{pr}/comments/{rest_id}/replies
  - review_summary  → POST /repos/{owner}/{repo}/issues/{pr}/comments

Empty bodies are skipped (the skill emits none for obsolete/no-op comments).
A single failed post is logged and does not abort the rest — the exit code is
non-zero only if at least one post failed, so the workflow can warn without
losing the successful ones.

Usage:
    python3 post_replies.py <pr_number> <replies_json>

Requires GH_TOKEN in the environment.
"""
import json
import subprocess
import sys


def run(cmd, input_data=None):
    result = subprocess.run(cmd, input=input_data, capture_output=True, text=True)
    if result.returncode != 0:
        raise subprocess.CalledProcessError(
            result.returncode, cmd, result.stdout, result.stderr
        )
    return result.stdout.strip()


def get_owner_repo():
    return run(['gh', 'repo', 'view', '--json', 'nameWithOwner', '-q', '.nameWithOwner'])


def post_inline_reply(owner_repo, pr_number, rest_id, body):
    return run([
        'gh', 'api', '-X', 'POST',
        f'/repos/{owner_repo}/pulls/{pr_number}/comments/{rest_id}/replies',
        '-f', f'body={body}',
        '--jq', '.html_url',
    ])


def post_issue_comment(owner_repo, pr_number, body):
    return run([
        'gh', 'api', '-X', 'POST',
        f'/repos/{owner_repo}/issues/{pr_number}/comments',
        '-f', f'body={body}',
        '--jq', '.html_url',
    ])


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <pr_number> <replies_json>", file=sys.stderr)
        sys.exit(2)

    pr_number = sys.argv[1]
    replies_path = sys.argv[2]

    try:
        with open(replies_path, encoding='utf-8') as fh:
            replies = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"No postable replies ({exc}) — nothing to do.", file=sys.stderr)
        return

    if not isinstance(replies, list) or not replies:
        print("Replies file is empty — nothing to post.")
        return

    owner_repo = get_owner_repo()
    posted = 0
    failed = 0

    for i, reply in enumerate(replies):
        kind = (reply.get('kind') or 'inline').strip()
        body = (reply.get('body') or '').strip()
        reviewer = reply.get('reviewer') or 'reviewer'

        if not body:
            print(f"[{i}] skipped — empty body (@{reviewer}).")
            continue

        try:
            if kind == 'review_summary':
                url = post_issue_comment(owner_repo, pr_number, body)
            else:
                rest_id = reply.get('rest_id')
                if not rest_id:
                    print(f"[{i}] skipped — inline reply has no rest_id (@{reviewer}).",
                          file=sys.stderr)
                    failed += 1
                    continue
                url = post_inline_reply(owner_repo, pr_number, rest_id, body)
            posted += 1
            print(f"[{i}] posted {kind} reply to @{reviewer}: {url}")
        except subprocess.CalledProcessError as exc:
            failed += 1
            err = (exc.stderr or exc.stdout or '').strip().splitlines()
            print(f"[{i}] FAILED to post {kind} reply to @{reviewer}: "
                  f"{err[-1] if err else 'unknown error'}", file=sys.stderr)

    print(f"Posted {posted} repl{'y' if posted == 1 else 'ies'}, {failed} failed.")
    if failed:
        sys.exit(1)


if __name__ == '__main__':
    main()
