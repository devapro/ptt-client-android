#!/usr/bin/env python3
"""
Query kotlin-lsp for references / definition / implementations of a Kotlin symbol.

Usage:
    lsp_query.py <op> <file> <line> <char> [--wait <seconds>] [--init-timeout <seconds>]

Where:
    op    = references | definition | implementation
    file  = absolute path to a .kt file containing the symbol
    line  = 1-indexed line number where the symbol appears
    char  = 1-indexed column number pointing INSIDE the symbol name

Notes:
    - line/char are 1-indexed for ergonomics (matches grep output and editor
      gutters). They are converted to LSP's 0-indexed positions internally.
    - The first invocation in a fresh container takes 5-15 minutes (Gradle
      project import + indexing). Every invocation pays a cold start — the
      script kills any running kotlin-lsp and spawns a fresh one — but warm
      on-disk Gradle caches make later runs faster.
    - Empty results are AMBIGUOUS: either the symbol is genuinely unused, or
      the index isn't ready. Cross-check with grep before concluding "unused".
      See .claude/rules/search-tools.md.
"""

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

LSP_BIN = "kotlin-lsp"
DEFAULT_INDEX_WAIT = 120
DEFAULT_INIT_TIMEOUT = 1500  # 25 minutes — Gradle import on a cold cache


def find_orphan_lsp() -> list[int]:
    """Find ALL running kotlin-lsp processes (orphaned or not). Any of them
    may hold the RocksDB index LOCK file, so all are killed before starting."""
    try:
        out = subprocess.check_output(["ps", "-eo", "pid,ppid,comm"], text=True)
    except Exception:
        return []
    pids = []
    for line in out.splitlines()[1:]:
        parts = line.split(None, 2)
        if len(parts) < 3:
            continue
        pid, ppid, comm = parts
        if "kotlin-lsp" in comm:
            pids.append(int(pid))
    return pids


def kill_orphans(verbose: bool) -> None:
    pids = find_orphan_lsp()
    if not pids:
        return
    if verbose:
        print(f"Found existing kotlin-lsp processes: {pids}. Killing to release RocksDB lock.", file=sys.stderr)
    for pid in pids:
        try:
            os.kill(pid, 15)
        except ProcessLookupError:
            pass
    time.sleep(3)
    # If any survived, SIGKILL
    for pid in find_orphan_lsp():
        try:
            os.kill(pid, 9)
        except ProcessLookupError:
            pass


class LspClient:
    def __init__(self, repo_root: str, stderr_path: str | None):
        self.repo_root = repo_root
        stderr = open(stderr_path, "w") if stderr_path else subprocess.DEVNULL
        self.stderr_file = stderr
        self.proc = subprocess.Popen(
            [LSP_BIN, "--stdio"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=stderr,
            bufsize=0,
        )
        self._next_id = 0
        self._responses: dict[int, dict] = {}
        self._lock = threading.Lock()
        self._reader_thread = threading.Thread(target=self._reader, daemon=True)
        self._reader_thread.start()

    def _new_id(self) -> int:
        self._next_id += 1
        return self._next_id

    def _send(self, msg: dict) -> None:
        body = json.dumps(msg).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        assert self.proc.stdin is not None
        self.proc.stdin.write(header + body)
        self.proc.stdin.flush()

    def _reader(self) -> None:
        assert self.proc.stdout is not None
        buf = b""
        while True:
            chunk = self.proc.stdout.read(1)
            if not chunk:
                return
            buf += chunk
            if b"\r\n\r\n" not in buf:
                continue
            header, rest = buf.split(b"\r\n\r\n", 1)
            length = 0
            for line in header.split(b"\r\n"):
                if line.lower().startswith(b"content-length:"):
                    length = int(line.split(b":")[1].strip())
            while len(rest) < length:
                more = self.proc.stdout.read(length - len(rest))
                if not more:
                    return
                rest += more
            body = rest[:length]
            buf = rest[length:]
            try:
                msg = json.loads(body.decode("utf-8"))
            except json.JSONDecodeError:
                continue
            with self._lock:
                if "id" in msg and ("result" in msg or "error" in msg):
                    self._responses[msg["id"]] = msg

    def request(self, method: str, params: dict | None, timeout: float) -> dict:
        rid = self._new_id()
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        start = time.time()
        while time.time() - start < timeout:
            with self._lock:
                if rid in self._responses:
                    return self._responses.pop(rid)
            if self.proc.poll() is not None:
                raise RuntimeError(f"kotlin-lsp exited with code {self.proc.returncode} while waiting for {method}")
            time.sleep(0.1)
        raise TimeoutError(f"{method} timed out after {timeout}s")

    def notify(self, method: str, params: dict | None) -> None:
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def shutdown(self) -> None:
        try:
            self.request("shutdown", None, timeout=5)
            self.notify("exit", None)
        except Exception:
            pass
        try:
            self.proc.terminate()
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()
        if self.stderr_file not in (None, subprocess.DEVNULL):
            try:
                self.stderr_file.close()
            except Exception:
                pass


METHOD_MAP = {
    "references": "textDocument/references",
    "definition": "textDocument/definition",
    "implementation": "textDocument/implementation",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("op", choices=METHOD_MAP.keys())
    parser.add_argument("file", help="absolute path to a .kt file")
    parser.add_argument("line", type=int, help="1-indexed line number")
    parser.add_argument("char", type=int, help="1-indexed column inside the symbol name")
    parser.add_argument("--wait", type=int, default=DEFAULT_INDEX_WAIT,
                        help=f"seconds to wait after didOpen for indexing (default {DEFAULT_INDEX_WAIT})")
    parser.add_argument("--init-timeout", type=int, default=DEFAULT_INIT_TIMEOUT,
                        help=f"seconds to wait for initialize (default {DEFAULT_INIT_TIMEOUT})")
    parser.add_argument("--repo", default=os.environ.get("LSP_REPO_ROOT", "/workspace"),
                        help="repo root (default /workspace)")
    parser.add_argument("--stderr-log", default="/tmp/kotlin_lsp_stderr.log",
                        help="path to capture LSP stderr (default /tmp/kotlin_lsp_stderr.log)")
    parser.add_argument("--quiet", action="store_true", help="suppress progress messages on stderr")
    args = parser.parse_args()

    target = Path(args.file).resolve()
    if not target.is_file():
        print(f"error: file not found: {target}", file=sys.stderr)
        return 2
    if not args.line >= 1 or not args.char >= 1:
        print("error: line and char must be 1-indexed (>= 1)", file=sys.stderr)
        return 2

    verbose = not args.quiet
    log = (lambda *a: print(*a, file=sys.stderr)) if verbose else (lambda *a: None)

    log("Killing any orphan kotlin-lsp processes (they hold the RocksDB LOCK)...")
    kill_orphans(verbose)

    log(f"Starting kotlin-lsp (stderr -> {args.stderr_log})...")
    client = LspClient(args.repo, args.stderr_log)
    try:
        log(f"Sending initialize (timeout {args.init_timeout}s — cold Gradle import is slow)...")
        client.request("initialize", {
            "processId": None,
            "rootUri": f"file://{args.repo}",
            "capabilities": {
                "textDocument": {
                    "references": {"dynamicRegistration": False},
                    "definition": {"dynamicRegistration": False},
                    "implementation": {"dynamicRegistration": False},
                    "synchronization": {"didOpen": True},
                },
                "workspace": {"workspaceFolders": True},
            },
            "workspaceFolders": [{"uri": f"file://{args.repo}", "name": "workspace"}],
        }, timeout=args.init_timeout)
        client.notify("initialized", {})
        log("Initialized.")

        content = target.read_text()
        client.notify("textDocument/didOpen", {
            "textDocument": {
                "uri": f"file://{target}",
                "languageId": "kotlin",
                "version": 1,
                "text": content,
            },
        })
        log(f"Opened {target}. Waiting {args.wait}s for indexing...")
        time.sleep(args.wait)

        method = METHOD_MAP[args.op]
        params = {
            "textDocument": {"uri": f"file://{target}"},
            "position": {"line": args.line - 1, "character": args.char - 1},
        }
        if args.op == "references":
            params["context"] = {"includeDeclaration": False}

        log(f"Sending {method}...")
        resp = client.request(method, params, timeout=120)
        result = resp.get("result") or []
        if isinstance(result, dict):
            result = [result]

        log(f"Got {len(result)} result(s).")
        if not result:
            log("NOTE: empty result is ambiguous — could mean 'unused' OR 'index not ready'.")
            log("      Cross-check with grep before drawing conclusions.")
        for ref in result:
            uri = ref["uri"].replace("file://", "")
            start = ref["range"]["start"]
            print(f"{uri}:{start['line'] + 1}:{start['character'] + 1}")
        return 0
    finally:
        client.shutdown()


if __name__ == "__main__":
    sys.exit(main())
