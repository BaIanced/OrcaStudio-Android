#!/usr/bin/env python3
"""Keep identifying details out of this public repository: printer serials, LAN addresses,
personal e-mail addresses, sign-in tokens and private keys.

  privacy_scan.py tree  PATHSPEC...        scan the tracked files
  privacy_scan.py range REVS PATHSPEC...   scan what the commits in REVS ("A..B" or
                                           "B --not C") add, and their messages
  privacy_scan.py hook  PATHSPEC...        Claude Code PreToolUse hook (event JSON on stdin)

A line containing "privacy-ok" is skipped. Literal examples that look private (such as a
placeholder address in the UI) go in privacy_allow.txt next to this script.
"""
import fnmatch
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ALLOW_FILE = "privacy_allow.txt"

PATTERNS = [
    # Bambu Lab serials: 15 upper-case letters/digits, starting with a digit (e.g. 01S..., 039...).
    ("printer serial", re.compile(r"(?<![0-9A-Za-z])(?=[0-9A-Z]*[A-Z])[0-9][0-9A-Z]{14}(?![0-9A-Za-z])")),
    ("LAN address", re.compile(r"(?<!\d)(?<!\d\.)(?:192\.168|10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}(?!\.?\d)")),
    ("personal e-mail", re.compile(r"[\w.+-]+@(?:gmail|googlemail|outlook|hotmail|live|yahoo|icloud|me|aol|gmx"
                                   r"|proton|protonmail|pm)\.[a-z.]{2,}", re.I)),
    ("sign-in token", re.compile(r"eyJ[\w-]{8,}\.eyJ[\w-]{8,}")),
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
]


def load_allow():
    try:
        with open(os.path.join(HERE, ALLOW_FILE), encoding="utf-8") as f:
            return {l.strip() for l in f if l.strip() and not l.startswith("#")}
    except OSError:
        return set()


ALLOW = load_allow()


def scan_text(text, where, numbered=True):
    found = []
    for n, line in enumerate(text.splitlines(), 1):
        if "privacy-ok" in line:
            continue
        for label, rx in PATTERNS:
            for m in rx.finditer(line):
                if m.group(0) not in ALLOW:
                    found.append(f"{where}{f':{n}' if numbered else ''}: {label} ({m.group(0)[:3]}...)")
    return found


def git(repo, *args):
    r = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True, errors="replace")
    if r.returncode:
        sys.exit(f"privacy scan: git {' '.join(args[:2])} failed: {r.stderr.strip()}")
    return r.stdout


def owned(rel, pathspecs):
    def hit(p):
        p = p.rstrip("/")
        return p == "." or rel == p or rel.startswith(p + "/") or fnmatch.fnmatch(rel, p)
    inc = [p for p in pathspecs if not p.startswith(":(exclude)")]
    exc = [p[len(":(exclude)"):] for p in pathspecs if p.startswith(":(exclude)")]
    return any(map(hit, inc)) and not any(map(hit, exc))


def scan_tree(repo, pathspecs):
    found = []
    for rel in git(repo, "ls-files", "-z", "--", *pathspecs).split("\0"):
        if not rel or os.path.basename(rel) == ALLOW_FILE:
            continue
        try:
            with open(os.path.join(repo, rel), "rb") as f:
                data = f.read()
        except OSError:
            continue
        if b"\0" not in data:
            found += scan_text(data.decode("utf-8", "replace"), rel)
    return found


def scan_range(repo, revs, pathspecs):
    revs = revs.split()
    found = []
    # One commit per record: hash, message, then its diff (merges show no diff).
    out = git(repo, "log", "-p", "-U0", "--no-color", "--no-ext-diff", "--format=%x01%h%n%B%x02",
              *revs, "--", *pathspecs)
    for rec in out.split("\x01")[1:]:
        head, _, diff = rec.partition("\x02")
        sha, _, msg = head.partition("\n")
        found += scan_text(msg, f"commit {sha} message")
        path = None
        for line in diff.splitlines():
            if line.startswith("+++ "):
                path = line[6:] if line.startswith("+++ b/") else None
            elif line.startswith("+") and path and os.path.basename(path) != ALLOW_FILE:
                found += scan_text(line[1:], f"commit {sha} {path}", numbered=False)
    return found


def hook(pathspecs):
    event = json.load(sys.stdin)
    tool, inp = event.get("tool_name", ""), event.get("tool_input") or {}
    repo = os.environ.get("CLAUDE_PROJECT_DIR") or event.get("cwd") or "."
    found = []
    if tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        path = os.path.abspath(inp.get("file_path") or inp.get("notebook_path") or "")
        rel = os.path.relpath(path, repo)
        if not rel.startswith("..") and owned(rel, pathspecs) and os.path.basename(rel) != ALLOW_FILE:
            parts = [inp.get("content"), inp.get("new_string"), inp.get("new_source")]
            parts += [e.get("new_string") for e in inp.get("edits") or []]
            found = scan_text("\n".join(p for p in parts if p), rel)
    elif tool == "Bash":
        cmd = inp.get("command", "")
        if re.search(r"\bgit\b[^|;&]*\b(commit|push)\b", cmd):
            found = scan_text(cmd, "command")
            if re.search(r"\bgit\b[^|;&]*\bpush\b", cmd):
                found += scan_range(repo, "HEAD --not --remotes", pathspecs)
    elif tool.startswith("mcp__github__"):
        found = scan_text(json.dumps(inp, ensure_ascii=False).replace("\\n", "\n"), tool)
    if found:
        print("Blocked: this would publish identifying details to a public repository.\n"
              + "\n".join(found[:20])
              + "\nRemove them (use a placeholder such as <serial> or <printer-ip>). For a false"
                f" positive, add 'privacy-ok' to the line or the literal to .claude/hooks/{ALLOW_FILE}.",
              file=sys.stderr)
        sys.exit(2)


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("tree", "range", "hook"):
        sys.exit(__doc__)
    mode, args = sys.argv[1], sys.argv[2:]
    if mode == "hook":
        return hook(args)
    repo = git(".", "rev-parse", "--show-toplevel").strip() or "."
    found = scan_tree(repo, args) if mode == "tree" else scan_range(repo, args[0], args[1:])
    print("\n".join(found) if found else "privacy scan: clean")
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
