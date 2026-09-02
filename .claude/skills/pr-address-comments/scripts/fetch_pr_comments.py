#!/usr/bin/env python3
"""
Fetch unresolved review comments and review summaries for a GitHub pull request.

Uses `gh api graphql` for review threads (needed to know is_resolved) and
`gh api` REST for the numeric comment IDs required to post threaded replies later.

Output JSON schema (see SKILL.md for details):
{
  "pr": { "url": ..., "number": ..., "title": ..., "head_ref": ... },
  "comments": [
    {
      "id": "<GraphQL node id>",
      "rest_id": <int>,           # REST numeric id, needed for /pulls/N/comments/<id>/replies
      "thread_id": "<GraphQL id>",
      "kind": "inline" | "review_summary",
      "author": "login",
      "body": "...",
      "path": "..." | null,
      "line": <int> | null,
      "url": "https://...",
      "is_resolved": false,
      "in_reply_to_id": <int|null>
    }
  ]
}
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


THREADS_QUERY = """
query($owner: String!, $repo: String!, $number: Int!, $cursor: String) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      url
      title
      headRefName
      reviewThreads(first: 100, after: $cursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          id
          isResolved
          isOutdated
          comments(first: 100) {
            nodes {
              id
              databaseId
              body
              path
              line
              originalLine
              url
              author { login }
              replyTo { databaseId }
            }
          }
        }
      }
    }
  }
}
"""


REVIEWS_QUERY = """
query($owner: String!, $repo: String!, $number: Int!, $cursor: String) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      reviews(first: 50, after: $cursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          id
          databaseId
          body
          state
          url
          author { login }
        }
      }
    }
  }
}
"""


def gh_graphql(query: str, variables: dict) -> dict:
    """Run a GraphQL query via `gh api graphql` and return the parsed JSON `data` block."""
    cmd = ["gh", "api", "graphql", "-f", f"query={query}"]
    for k, v in variables.items():
        if v is None:
            continue
        if isinstance(v, int):
            cmd.extend(["-F", f"{k}={v}"])
        else:
            cmd.extend(["-f", f"{k}={v}"])

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"gh graphql failed: {result.stderr.strip()}", file=sys.stderr)
        sys.exit(2)
    payload = json.loads(result.stdout)
    if "errors" in payload:
        print(f"GraphQL errors: {payload['errors']}", file=sys.stderr)
        sys.exit(2)
    return payload["data"]


def collect_threads(owner: str, repo: str, number: int) -> tuple[dict, list]:
    """Return (pr_info, threads) with all pages fetched."""
    pr_info = None
    threads = []
    cursor = None
    while True:
        data = gh_graphql(
            THREADS_QUERY,
            {"owner": owner, "repo": repo, "number": number, "cursor": cursor},
        )
        pr = data["repository"]["pullRequest"]
        if pr_info is None:
            pr_info = {
                "url": pr["url"],
                "number": number,
                "title": pr["title"],
                "head_ref": pr["headRefName"],
            }
        rt = pr["reviewThreads"]
        threads.extend(rt["nodes"])
        if not rt["pageInfo"]["hasNextPage"]:
            break
        cursor = rt["pageInfo"]["endCursor"]
    return pr_info, threads


def collect_review_summaries(owner: str, repo: str, number: int) -> list:
    """Return review-level bodies with non-empty text."""
    reviews = []
    cursor = None
    while True:
        data = gh_graphql(
            REVIEWS_QUERY,
            {"owner": owner, "repo": repo, "number": number, "cursor": cursor},
        )
        rs = data["repository"]["pullRequest"]["reviews"]
        reviews.extend(rs["nodes"])
        if not rs["pageInfo"]["hasNextPage"]:
            break
        cursor = rs["pageInfo"]["endCursor"]
    # Keep only reviews that have body text; approvals with empty bodies are noise.
    return [r for r in reviews if (r.get("body") or "").strip()]


def build_output(pr_info: dict, threads: list, reviews: list) -> dict:
    comments = []

    for thread in threads:
        if thread["isResolved"]:
            continue
        for c in thread["comments"]["nodes"]:
            reply_to = c.get("replyTo")
            comments.append(
                {
                    "id": c["id"],
                    "rest_id": c["databaseId"],
                    "thread_id": thread["id"],
                    "kind": "inline",
                    "author": (c["author"] or {}).get("login", "unknown"),
                    "body": c["body"] or "",
                    "path": c.get("path"),
                    "line": c.get("line") or c.get("originalLine"),
                    "url": c["url"],
                    "is_resolved": False,
                    "is_outdated": thread.get("isOutdated", False),
                    "in_reply_to_id": (reply_to or {}).get("databaseId"),
                }
            )

    for r in reviews:
        # Review summaries don't have a resolved concept; include all non-empty.
        # State can be APPROVED / CHANGES_REQUESTED / COMMENTED / DISMISSED / PENDING.
        if r.get("state") in {"DISMISSED", "PENDING"}:
            continue
        comments.append(
            {
                "id": r["id"],
                "rest_id": r["databaseId"],
                "thread_id": None,
                "kind": "review_summary",
                "author": (r["author"] or {}).get("login", "unknown"),
                "body": r["body"],
                "path": None,
                "line": None,
                "url": r["url"],
                "is_resolved": False,
                "is_outdated": False,
                "in_reply_to_id": None,
                "state": r.get("state"),
            }
        )

    return {"pr": pr_info, "comments": comments}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr", required=True, type=int, help="PR number")
    parser.add_argument("--output", required=True, help="Path to write JSON")
    args = parser.parse_args()

    pr_info, threads = collect_threads(args.owner, args.repo, args.pr)
    reviews = collect_review_summaries(args.owner, args.repo, args.pr)
    output = build_output(pr_info, threads, reviews)

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(output, indent=2, ensure_ascii=False))

    total = len(output["comments"])
    unresolved_inline = sum(1 for c in output["comments"] if c["kind"] == "inline")
    review_summaries = sum(1 for c in output["comments"] if c["kind"] == "review_summary")
    print(
        f"Fetched {total} comments "
        f"({unresolved_inline} unresolved inline, {review_summaries} review summaries) "
        f"→ {out_path}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
