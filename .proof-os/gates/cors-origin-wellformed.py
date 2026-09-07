#!/usr/bin/env python
"""Gate — every configured CORS / allowed-origin entry is a bare origin.

Origin: F-0723. The live API shipped an allowed-origin whose value carried a
trailing path separator. Spring's CorsConfig (influora-api/src/main/java/com/
influora/config/CorsConfig.java:22-23) splits on comma, trims, and hands the
result to setAllowedOrigins, which is an EXACT string comparison. A browser's
Origin header never carries a trailing separator, so that entry could never
match anything. influora-ai does the same thing at app/config.py:514.

Why no test caught it: the SPA is same-origin today, so no preflight is ever
issued and the allowlist is never consulted. It fails only once a second host
is introduced — which is a planned deploy step. A defect that is invisible
until the exact moment you change infrastructure is the kind a gate has to
carry, because no runtime check will fire before then.

Exit 0 every entry well-formed · 1 a malformed entry · 2 nothing to check.

Usage:
  python .proof-os/gates/cors-origin-wellformed.py            # scan the repo
  python .proof-os/gates/cors-origin-wellformed.py FILE...    # scan given files
"""
import os
import re
import sys

# Keys whose value is a comma-separated origin list.
KEYS = (
    "CORS_ALLOWED_ORIGINS",
    "MEERA_ALLOWED_ORIGINS",
    "ALLOWED_ORIGINS",
    "allowed-origins",
    "allowed_origins",
)

# A legal Origin: scheme, host, optional port. Nothing else. RFC 6454 serializes
# an origin with no path and no trailing separator, which is precisely the rule
# the defect broke.
ORIGIN_RE = re.compile(r"^https?://[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::[0-9]{1,5})?$")

ASSIGN_RE = re.compile(
    r"(?:^|[^A-Za-z0-9_-])(" + "|".join(re.escape(k) for k in KEYS) + r")\s*[:=]\s*(.+?)\s*$"
)

# ${VAR:default} (Spring) and ${VAR:-default} (shell / compose).
PLACEHOLDER_RE = re.compile(r"^\$\{[A-Za-z_][A-Za-z0-9_]*:-?(.*)\}$")

# An embedded interpolation, e.g. https://${APP_DOMAIN} in a compose file.
INTERP_RE = re.compile(r"\$\{[^}]*\}")

SKIP_DIRS = {
    ".git", "node_modules", "target", "dist", "build", "__pycache__",
    ".venv", "venv", ".mypy_cache", ".pytest_cache", "coverage",
}
# The gate directory is excluded so this file's own prose can never be scanned.
# A gate that reports itself is the failure mode from the 2026-09-07 grep gates.
SKIP_PATH_PARTS = (
    os.path.join(".proof-os", "gates"),
    os.path.join(".proof-os", "_archive"),
    os.path.join(".proof-os", "tasks"),
    os.path.join(".claude", "worktrees"),
)
SCAN_EXT = {".yml", ".yaml", ".sh", ".env", ".example", ".properties", ".conf"}


def scannable(path):
    for part in SKIP_PATH_PARTS:
        if part in path:
            return False
    base = os.path.basename(path)
    if base.startswith(".env"):
        return True
    return os.path.splitext(path)[1] in SCAN_EXT


def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            p = os.path.join(dirpath, name)
            if scannable(p):
                yield p


def is_comment(line, path):
    s = line.lstrip()
    if s.startswith("#"):
        return True
    # YAML/properties inline comment markers only; JS-style not expected here.
    return False


def check_file(path, problems, checked):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
    except OSError:
        return
    for n, line in enumerate(lines, 1):
        if is_comment(line, path):
            continue
        m = ASSIGN_RE.search(line.rstrip("\n"))
        if not m:
            continue
        key, raw = m.group(1), m.group(2).strip()
        raw = raw.strip('"').strip("'")
        ph = PLACEHOLDER_RE.match(raw)
        if ph:
            raw = ph.group(1)
        if not raw or raw.startswith("${"):
            # Forwarded with no default — nothing declared here to validate.
            continue
        checked.append((path, n, key))
        seen = set()
        for entry in raw.split(","):
            entry = entry.strip()
            if not entry:
                problems.append((path, n, key, "<empty>", "empty entry between commas"))
                continue
            if entry == "*":
                problems.append((path, n, key, entry, "wildcard is rejected by Spring when allowCredentials is true"))
                continue
            if entry in seen:
                problems.append((path, n, key, entry, "duplicate entry"))
            seen.add(entry)
            # A ${VAR} interpolation cannot be resolved here and is not this
            # gate's subject. Substitute a legal label so the SHAPE is still
            # judged: https://${APP_DOMAIN} passes, https://${APP_DOMAIN}/ does
            # not. Skipping such entries outright would have missed the very
            # defect this gate exists for, had it been written with a variable.
            shape = INTERP_RE.sub("x", entry)
            if not ORIGIN_RE.match(shape):
                entry = shape if shape != entry else entry
                if shape.rstrip("/") != shape:
                    why = "has a trailing separator; an Origin header never carries one, and the comparison is exact"
                elif re.match(r"^https?://[^/]+/", shape):
                    why = "carries a path; an origin is scheme, host and optional port only"
                elif not re.match(r"^https?://", shape):
                    why = "has no scheme"
                elif " " in shape:
                    why = "contains whitespace"
                else:
                    why = "is not a well-formed origin"
                problems.append((path, n, key, entry, why))


def main(argv):
    targets = argv[1:]
    problems, checked = [], []
    if targets:
        for t in targets:
            check_file(t, problems, checked)
    else:
        root = os.environ.get("PROOF_OS_REPO", ".")
        for p in walk(root):
            check_file(p, problems, checked)

    if not checked:
        print("cors-origin-wellformed: UNAVAILABLE — no origin-list assignment found to check")
        print("NOT CHECKED: everything; an empty denominator is not a pass")
        return 2

    for path, n, key, entry, why in problems:
        print("BROKEN  {}:{}  {}  entry {!r} {}".format(path, n, key, entry, why))

    print("cors-origin-wellformed: {} assignment(s) checked, {} malformed".format(
        len(checked), len(problems)))
    for path, n, key in checked:
        print("  checked {}:{}  {}".format(path, n, key))
    print("NOT CHECKED: whether a well-formed origin is an origin this deployment "
          "actually serves from (see allowlist-domain-owned.py), whether the running "
          "process holds the value in the file, or whether the allowlist is complete")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
