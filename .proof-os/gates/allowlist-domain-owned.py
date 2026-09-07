#!/usr/bin/env python
"""Gate — every host this product configures is under a domain this product owns.

Origin: F-0722. The live AI container's origin allowlist named a host under the
wrong top-level domain. The product's registrable domain is influora.in; the
configured host was under influora.com, which resolves to third-party
infrastructure nobody on this project controls. Meera's allowlist therefore
contained no origin the product ever serves from, so the browser would be
rejected even after its vhost existed.

Related, same class: F-0721, where a build-time API base URL named a host under
the same wrong domain, and earlier fixes in this repo record the same slip for a
help host and an object-storage host. This is a recurring one-character defect
that reads correctly to a human at every review.

Deliberately OFFLINE and deterministic. It does NOT resolve DNS: a wrong domain
that happens to resolve is still wrong, and a right domain is not less right
because a resolver is unreachable. Ownership is declared in
.proof-os/gates/owned-domains.txt, which is the only place a new domain is
blessed — that file is the review surface.

Exit 0 every host owned · 1 a foreign host · 2 nothing to check.

Usage:
  python .proof-os/gates/allowlist-domain-owned.py            # scan the repo
  python .proof-os/gates/allowlist-domain-owned.py FILE...    # scan given files
"""
import os
import re
import sys

KEYS = (
    "CORS_ALLOWED_ORIGINS", "MEERA_ALLOWED_ORIGINS", "ALLOWED_ORIGINS",
    "allowed-origins", "allowed_origins",
    "VITE_API_BASE_URL", "VITE_MEERA_STREAM_URL",
    "MEERA_PUBLIC_CHAT_URL", "INFLUORA_MEERA_STREAM_PUBLICCHATURL",
    "public-chat-url", "FRONTEND_URL",
    "APP_DOMAIN", "API_DOMAIN", "ROOT_DOMAIN", "AI_DOMAIN",
)

ASSIGN_RE = re.compile(
    r"(?:^|[^A-Za-z0-9_-])(" + "|".join(re.escape(k) for k in KEYS) + r")\s*[:=]\s*(.+?)\s*$"
)
PLACEHOLDER_RE = re.compile(r"^\$\{[A-Za-z_][A-Za-z0-9_]*:-?(.*)\}$")
HOST_RE = re.compile(r"^(?:https?://)?([A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9])(?::[0-9]{1,5})?(?:/.*)?$")

# Hosts that are legitimately not under an owned domain: local development.
LOCAL_OK = re.compile(r"^(localhost|127\.0\.0\.1|0\.0\.0\.0|host\.docker\.internal|\[?::1\]?)$")

# A bare IP literal has no registrable domain, so domain ownership is not a
# question that can be asked of it. Out of remit, not silently approved -- it is
# reported in the NOT CHECKED line so the gap stays visible.
IP_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")

# Reserved / special-use suffixes (RFC 6761, RFC 8375). These are by definition
# not public names, so they cannot be "owned" and are not a wrong-TLD defect.
RESERVED_SUFFIXES = (".internal", ".local", ".localhost", ".test", ".invalid", ".example", ".home.arpa")

SKIP_DIRS = {
    ".git", "node_modules", "target", "dist", "build", "__pycache__",
    ".venv", "venv", ".mypy_cache", ".pytest_cache", "coverage",
}
SKIP_PATH_PARTS = (
    os.path.join(".proof-os", "gates"),
    os.path.join(".proof-os", "_archive"),
    os.path.join(".proof-os", "tasks"),
    os.path.join(".claude", "worktrees"),
)
SCAN_EXT = {".yml", ".yaml", ".sh", ".env", ".example", ".properties", ".conf"}


def owned_domains(repo):
    path = os.path.join(repo, ".proof-os", "gates", "owned-domains.txt")
    if not os.path.exists(path):
        return None, path
    out = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip().lower()
            if line:
                out.append(line)
    return out, path


def scannable(path):
    for part in SKIP_PATH_PARTS:
        if part in path:
            return False
    if os.path.basename(path).startswith(".env"):
        return True
    return os.path.splitext(path)[1] in SCAN_EXT


def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            p = os.path.join(dirpath, name)
            if scannable(p):
                yield p


def is_owned(host, domains):
    host = host.lower().rstrip(".")
    if LOCAL_OK.match(host):
        return True
    for d in domains:
        if host == d or host.endswith("." + d):
            return True
    return False


def check_file(path, domains, problems, checked, out_of_remit):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
    except OSError:
        return
    for n, line in enumerate(lines, 1):
        if line.lstrip().startswith("#"):
            continue
        m = ASSIGN_RE.search(line.rstrip("\n"))
        if not m:
            continue
        key, raw = m.group(1), m.group(2).strip().strip('"').strip("'")
        ph = PLACEHOLDER_RE.match(raw)
        if ph:
            raw = ph.group(1)
        if not raw or raw.startswith("${"):
            continue
        for entry in raw.split(","):
            entry = entry.strip()
            if not entry or entry == "*":
                continue
            hm = HOST_RE.match(entry)
            if not hm:
                continue
            host = hm.group(1)
            if IP_RE.match(host) or host.lower().endswith(RESERVED_SUFFIXES):
                out_of_remit.append((path, n, key, host))
                continue
            checked.append((path, n, key, host))
            if not is_owned(host, domains):
                problems.append((path, n, key, host))


def main(argv):
    repo = os.environ.get("PROOF_OS_REPO", ".")
    domains, decl = owned_domains(repo)
    if domains is None:
        print("allowlist-domain-owned: UNAVAILABLE — no ownership declaration at {}".format(decl))
        print("NOT CHECKED: everything; without a declared owned set there is nothing to compare against")
        return 2
    if not domains:
        print("allowlist-domain-owned: UNAVAILABLE — {} declares no domains".format(decl))
        return 2

    problems, checked, out_of_remit = [], [], []
    targets = argv[1:]
    if targets:
        for t in targets:
            check_file(t, domains, problems, checked, out_of_remit)
    else:
        for p in walk(repo):
            check_file(p, domains, problems, checked, out_of_remit)

    if not checked:
        print("allowlist-domain-owned: UNAVAILABLE — no host-valued assignment found")
        print("NOT CHECKED: everything; an empty denominator is not a pass")
        return 2

    for path, n, key, host in problems:
        print("BROKEN  {}:{}  {}  host {!r} is not under any owned domain ({})".format(
            path, n, key, host, ", ".join(domains)))

    print("allowlist-domain-owned: {} host(s) checked against {}, {} foreign".format(
        len(checked), decl, len(problems)))
    for path, n, key, host in checked:
        print("  checked {}:{}  {} -> {}".format(path, n, key, host))
    if out_of_remit:
        print("  out of remit ({} host(s) - an IP literal or a reserved suffix has no registrable domain):".format(len(out_of_remit)))
        for path, n, key, host in out_of_remit:
            print("    {}:{}  {} -> {}".format(path, n, key, host))
    print("NOT CHECKED: whether an owned host actually resolves, has a certificate, "
          "or is served by this deployment - ownership of the name is not reachability "
          "of the host (see api-base-url-served.py); and whether owned-domains.txt is honest")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
