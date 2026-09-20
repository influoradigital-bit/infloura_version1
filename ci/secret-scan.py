#!/usr/bin/env python3
"""
secret-scan gate -- EV-006.

Live third-party credentials (an Anthropic key, a Sarvam key, a Cloudflare R2 access-key pair and
the MSG91 auth keys) sat in the tracked env.example files and reached origin. They got there even
though .gitleaks.toml existed, because that config allow-listed every env.example path as
"placeholders by definition" and no workflow ever ran it. This gate runs on every push and PR with
no paths filter and has no path-wide exemption for example files.

What it scans: every file git knows about in the working tree -- tracked files plus untracked
files that are not ignored (so a key about to be `git add`-ed is caught locally too). Binary files
and files over 2 MB are skipped.

What fails it:
  1. provider-shaped keys anywhere (Anthropic, Google/Gemini, Sarvam, Razorpay live key-id,
     AWS/R2-style access key ids, GitHub, Slack, OpenAI project keys, private-key PEM blocks);
  2. an UPPER_CASE secret-named variable (..._SECRET, ..._PASSWORD, ..._API_KEY, ..._AUTH_KEY,
     ..._ACCESS_KEY..., ..._TOKEN_AUTH) assigned a high-entropy literal with `=` or `:`;
  3. a Spring `${SECRET_NAME:default}` placeholder whose default is a high-entropy literal.
Rules 2 and 3 skip test source trees (TEST_PATH); rule 1 applies everywhere.

What never fails it: values carrying a placeholder marker (the REPLACE_WITH_ convention the
repo already uses, your-key, change-me, example, dummy, and similar -- see PLACEHOLDER_MARKERS),
values that are variable references, and entries listed in ci/secret-scan-allowlist.txt. An
allow-list entry pins ONE value in ONE file by a sha256 fingerprint of the value, so the allow
list never contains a secret and a new value in the same file is still caught.

Output never prints a matched value: only path:line, the rule, and a 16-hex fingerprint.

The pattern definitions below are written so they cannot match themselves (each regex's source
text has metacharacters where a real key has key characters); the gate scans its own file on
every run, so a pattern that matched its own definition would turn this gate red immediately.

Usage:  python ci/secret-scan.py [--root DIR] [--list-fingerprints]
Exit:   0 clean -- 1 findings -- 2 could not enumerate files
"""

from __future__ import annotations

import argparse
import hashlib
import math
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

MAX_BYTES = 2 * 1024 * 1024

# (rule id, provider, compiled regex). Group 0 is the value that is fingerprinted.
PROVIDER_RULES = [
    ("anthropic-api-key", "Anthropic", re.compile(r"sk-ant-(?:api|admin)\d{2}-[A-Za-z0-9_\-]{40,}")),
    ("openai-project-key", "OpenAI", re.compile(r"sk-proj-[A-Za-z0-9_\-]{40,}")),
    ("sarvam-api-key", "Sarvam", re.compile(r"(?<![A-Za-z0-9])sk_[a-z0-9]{8}_[A-Za-z0-9]{20,}")),
    ("google-api-key", "Google (Gemini/Maps)", re.compile(r"AIza[0-9A-Za-z_\-]{35}")),
    ("razorpay-live-key-id", "Razorpay", re.compile(r"rzp_live_[A-Za-z0-9]{14,}")),
    ("aws-access-key-id", "AWS", re.compile(r"(?<![A-Z0-9])(?:AKIA|ASIA)[0-9A-Z]{16}(?![A-Z0-9])")),
    ("github-token", "GitHub", re.compile(r"(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{36,}")),
    ("slack-token", "Slack", re.compile(r"xox[abprs]-[A-Za-z0-9\-]{20,}")),
    ("private-key-pem", "PEM private key",
     re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
]

# Secret-bearing variable names. UPPER_CASE only, so camelCase code identifiers do not trip it.
_SECRET_NAME = r"[A-Z0-9_]*(?:SECRET|PASSWORD|PASSWD|API_KEY|APIKEY|AUTH_KEY|AUTHKEY|ACCESS_KEY|TOKEN_AUTH|PRIVATE_KEY)[A-Z0-9_]*"

ASSIGNMENT_RULE = (
    "secret-assignment",
    re.compile(r"(?<![A-Za-z0-9_])(" + _SECRET_NAME + r")[\"']?\s*[=:]\s*[\"']?([^\s\"'#,;}{)(]+)"),
)
SPRING_DEFAULT_RULE = (
    "spring-secret-default",
    re.compile(r"\$\{(" + _SECRET_NAME + r"):([^}]+)\}"),
)

# Lowercase substrings. A value containing any of these is a placeholder, not a credential.
PLACEHOLDER_MARKERS = (
    "replace_with", "replace_me", "replace-me", "replaceme",
    "your_", "your-", "yourkey", "<your",
    "change-me", "change_me", "changeme", "change-in-production",
    "placeholder", "example", "dummy", "fake", "sample", "redacted", "xxxx", "****",
    "not-a-real", "notreal", "mock",
)

MIN_ASSIGNED_LEN = 16
MIN_ENTROPY = 3.3


def fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def entropy(value: str) -> float:
    counts = Counter(value)
    n = len(value)
    return -sum(c / n * math.log2(c / n) for c in counts.values())


def is_placeholder(value: str) -> bool:
    lower = value.lower()
    return any(marker in lower for marker in PLACEHOLDER_MARKERS)


def looks_like_literal_secret(value: str) -> bool:
    value = value.strip().strip("\"'")
    if len(value) < MIN_ASSIGNED_LEN:
        return False
    if value[0] in "$<{%" or value.startswith(("http://", "https://", "jdbc:", "file:")):
        return False
    if is_placeholder(value):
        return False
    # Identifiers / property paths (a.b.c, SOME_CONSTANT_NAME) are references, not secrets.
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+(?:\(\))?", value):
        return False
    if re.fullmatch(r"[A-Z][A-Z0-9_]*", value):
        return False
    return entropy(value) >= MIN_ENTROPY


def load_allowlist(root: Path) -> set[tuple[str, str, str]]:
    path = root / "ci" / "secret-scan-allowlist.txt"
    entries: set[tuple[str, str, str]] = set()
    if not path.exists():
        return entries
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 3:
            raise SystemExit(f"secret-scan: malformed allowlist line: {raw!r}")
        entries.add((parts[0], parts[1], parts[2]))
    return entries


def list_files(root: Path) -> list[str]:
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            check=True, capture_output=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        print(f"secret-scan: could not list files: {exc}", file=sys.stderr)
        sys.exit(2)
    return sorted({p for p in out.decode("utf-8", "surrogateescape").split("\0") if p})


# Test source trees are exempt from the two NAME-based rules only: they are full of constants
# such as `SECRET = "whsec-test-..."` that exist to be signed with. Provider-shaped keys are still
# caught in tests -- a real key pasted into a fixture is as leaked as one in env.example.
TEST_PATH = re.compile(
    r"(^|/)(src/test|tests?|__tests__|e2e)/|\.(test|spec)\.[jt]sx?$|(^|/)test_[^/]*\.py$"
)


def scan_text(rel: str, text: str):
    """Yield (line_no, rule, provider, value) for every candidate in one file."""
    lines = text.splitlines()
    name_rules = not TEST_PATH.search(rel)
    for line_no, line in enumerate(lines, start=1):
        for rule, provider, rx in PROVIDER_RULES:
            for m in rx.finditer(line):
                value = m.group(0)
                if rule == "private-key-pem":
                    # The header is identical for every key; fingerprint the first body line so
                    # an allow-list entry pins one specific key, not every PEM in the file.
                    body = lines[line_no].strip() if line_no < len(lines) else ""
                    value = value + "\n" + body
                elif is_placeholder(value):
                    continue
                yield line_no, rule, provider, value
        if not name_rules:
            continue
        for m in SPRING_DEFAULT_RULE[1].finditer(line):
            if looks_like_literal_secret(m.group(2)):
                yield line_no, SPRING_DEFAULT_RULE[0], m.group(1), m.group(2)
        for m in ASSIGNMENT_RULE[1].finditer(line):
            if line[m.start(2) - 1:m.start(2)] == "{" or m.group(2).startswith("${"):
                continue
            if looks_like_literal_secret(m.group(2)):
                yield line_no, ASSIGNMENT_RULE[0], m.group(1), m.group(2)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--list-fingerprints", action="store_true",
                    help="print allowlist-ready lines for every finding (still no values)")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    allow = load_allowlist(root)

    findings = []
    allowed_hits = 0
    for rel in list_files(root):
        path = root / rel
        try:
            if not path.is_file() or path.stat().st_size > MAX_BYTES:
                continue
            raw = path.read_bytes()
        except OSError:
            continue
        if b"\0" in raw[:8192]:
            continue
        text = raw.decode("utf-8", "replace")
        seen: set[tuple[int, str, str]] = set()
        for line_no, rule, what, value in scan_text(rel, text):
            fp = fingerprint(value)
            if (line_no, rule, fp) in seen:
                continue
            seen.add((line_no, rule, fp))
            if (rel, rule, fp) in allow or (rel, rule, "*") in allow:
                allowed_hits += 1
                continue
            findings.append((rel, line_no, rule, what, fp))

    if args.list_fingerprints:
        for rel, line_no, rule, what, fp in findings:
            print(f"{rel}\t{rule}\t{fp}\t# line {line_no}: {what}")
        return 1 if findings else 0

    if findings:
        print(f"secret-scan: {len(findings)} live-format secret(s) found (values not shown):")
        for rel, line_no, rule, what, fp in findings:
            print(f"  {rel}:{line_no}  {rule}  [{what}]  fp={fp}")
        print(
            "\nReplace the value with a REPLACE_WITH_YOUR_<NAME> placeholder and rotate the credential"
            "\nat the provider -- it is already in git history. If this is a deliberate, audited"
            "\nfixture, add '<path> <rule> <fp>  # reason' to ci/secret-scan-allowlist.txt."
        )
        return 1
    print(f"secret-scan: clean ({allowed_hits} allow-listed fixture hit(s)).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
