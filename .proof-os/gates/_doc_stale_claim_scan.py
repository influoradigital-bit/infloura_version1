"""doc-stale-doc-claim scanner (prototype pass).

The class: a doc asserts a surface is missing / mock / unimplemented, and the code says otherwise.
18 findings, all of them "the doc, not the code, is wrong".

Method: find NEGATIVE STATUS CLAIMS in current-truth docs that name a concrete API route, then ask
whether that route actually exists as a controller mapping. A doc saying "X is mock-backed" while X
has a real mapping is the finding.

Scoped deliberately to CURRENT-TRUTH docs. Historical records (audit reports, review write-ups,
dated findings, the QA answer files) describe past states on purpose and MUST NOT be flagged — a
gate that fails on an accurate historical record teaches the team to ignore it.

Usage: python _doc_stale_claim_scan.py [--verbose]
"""
import re
import sys
import pathlib


def safe(text: str) -> str:
    """Windows consoles here are cp1252; an arrow or dash in a doc line must not crash the gate."""
    return text.encode("ascii", "replace").decode("ascii")

ROOT = pathlib.Path(".")
WEB = ROOT / "influora-api/src/main/java/com/influora/web"

# Docs that are supposed to describe the system AS IT IS.
INCLUDE_DIRS = ["wiki/tech", "wiki/processes", "deploy", "docs"]
INCLUDE_ROOT_FILES = ["README.md", "CLAUDE.md", "TECH-STACK.md"]

# Historical by construction: these record what was true on a date, and being "stale" is the point.
EXCLUDE_PARTS = (
    "/reports/", "/ai-review/", "/errors/", "/build/", "/admin-progress/",
    "graphify-out", "node_modules", "_archive", "/tasks/", "claude-skills",
    ".claude/", "/_to_delete/",
)

NEGATIVE = re.compile(
    r"\b(is|are|remains?|stays?)\s+(still\s+)?(mock|mocked|mock-backed|a mock|stubbed|"
    r"unimplemented|not implemented|not wired|missing|absent)\b"
    r"|\bno (frontend caller|endpoint|backend|API|route|UI)\b"
    r"|\breturns? (a )?501\b|\b501s\b|\b404s\b"
    r"|\bcoming soon\b|\bplaceholder only\b|\bdoes not exist\b",
    re.I,
)

ADJACENCY = 45

ROUTE = re.compile(r"`?(?:/api/v1)?(/[a-z][a-z0-9-]*(?:/[A-Za-z0-9{}_-]+)+)`?")

# Most of this class's findings name a CLASS, not a route: "the doc claims the metrics polling job
# is absent", "labels admin billing as mock-backed". A route-only scanner would miss them, so the
# same adjacency rule is applied to Java type names that really exist in the tree.
JAVA_TYPE = re.compile(r"`?\b([A-Z][A-Za-z0-9]*(?:Controller|Service|Job|Properties|Repository))\b`?")

CLASS_MAPPING = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
METHOD_MAPPING = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
BLOCK = re.compile(r"/\*.*?\*/", re.S)
LINE_C = re.compile(r"//[^\n]*")


def strip_java_comments(text: str) -> str:
    return LINE_C.sub("", BLOCK.sub("", text))


def load_java_types() -> set:
    """Every Java type name that actually exists under influora-api/src/main."""
    main = ROOT / "influora-api/src/main/java"
    if not main.is_dir():
        return set()
    return {p.stem for p in main.rglob("*.java")}


def load_routes() -> set:
    """Every route the backend really serves, normalised to a path-segment tuple."""
    routes = set()
    if not WEB.is_dir():
        return routes
    for path in WEB.rglob("*.java"):
        try:
            code = strip_java_comments(path.read_text(encoding="utf-8", errors="replace"))
        except Exception:
            continue
        base_m = CLASS_MAPPING.search(code)
        base = base_m.group(1) if base_m else ""
        for _verb, sub in METHOD_MAPPING.findall(code):
            full = (base.rstrip("/") + "/" + sub.lstrip("/")).rstrip("/")
            routes.add(normalise(full))
        if base and not METHOD_MAPPING.search(code):
            routes.add(normalise(base))
    return routes


def normalise(route: str) -> tuple:
    """Path segments with {placeholders} collapsed, so /a/{id}/b == /a/{x}/b."""
    out = []
    for seg in route.strip("/").split("/"):
        out.append("{}" if seg.startswith("{") else seg.lower())
    return tuple(out)


def in_scope(path: pathlib.Path) -> bool:
    p = path.as_posix()
    if any(part in p for part in EXCLUDE_PARTS):
        return False
    if p in INCLUDE_ROOT_FILES:
        return True
    return any(p.startswith(d + "/") for d in INCLUDE_DIRS)


# --- feature-doc ratchet -------------------------------------------------------------------------
# The route/type rules above only catch a claim that NAMES a route or a Java type. Most of this
# class's real findings were prose: "affiliate earnings placeholder", "mock surfaces (wallet)",
# "Not implemented: escalate, getStats". No regex reliably resolves those to code.
#
# So the second rule is evidential rather than semantic: in the feature docs, a negative status
# claim should CITE the code that justifies it. 23 such lines predate this gate and are NOT
# retro-fixed here - verifying each needs a code read per claim, and rubber-stamping them would be
# the generous close this project keeps rejecting. Instead the count is ratcheted: it may fall
# freely, it may not rise. New unevidenced claims fail; the existing debt stays visible.
FEATURE_DOCS = ROOT / "docs/docs/features"
BASELINE_FILE = pathlib.Path(".proof-os/gates/_doc_claim_baseline.json")

CLAIM = re.compile(
    r"\b(is|are)\s+(still\s+)?(mock|mocked|mock-backed|a mock|stubbed|dead code|a no-op)\b"
    r"|\bmock surfaces?\b|\bmock-backed\b|\bnot implemented\b|\bnot wired\b"
    r"|\bno listener\b|\bplaceholder\b|\bcoming soon\b|\bdead code\b|\bno-op\b",
    re.I,
)
CODE_ANCHOR = re.compile(r"[A-Za-z0-9_/.-]+\.(?:java|ts|tsx|sql|yml|py):?\d*")


def count_uncited_claims():
    uncited = []
    if not FEATURE_DOCS.is_dir():
        return None
    for doc in sorted(FEATURE_DOCS.glob("*.md")):
        try:
            lines = doc.read_text(encoding="utf-8", errors="replace").split("\n")
        except Exception:
            continue
        for lineno, line in enumerate(lines, 1):
            if not CLAIM.search(line):
                continue
            if CODE_ANCHOR.search(line) or "doc-claim-ok" in line:
                continue
            uncited.append((doc.name, lineno))
    return uncited


def main() -> int:
    verbose = "--verbose" in sys.argv
    routes = load_routes()
    if not routes:
        print("- found ZERO backend routes, which cannot be right - broken instrument, not a pass")
        return 2
    print(f"- backend serves {len(routes)} route(s)")

    types = load_java_types()
    if not types:
        print("- found ZERO java types, which cannot be right - broken instrument, not a pass")
        return 2
    print(f"- backend defines {len(types)} type(s)")

    docs = [p for p in ROOT.rglob("*.md") if p.is_file() and in_scope(p)]
    if not docs:
        print("- no current-truth docs matched the scope - unavailable, not a pass")
        return 2
    print(f"- scanning {len(docs)} current-truth doc(s)")

    findings = []
    exempted = []
    for doc in docs:
        try:
            text = doc.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        doc_lines = text.split("\n")
        for lineno, line in enumerate(doc_lines, 1):
            if not NEGATIVE.search(line):
                continue
            # Auditable exemption. Regex cannot always tell WHAT a negative claim attaches to:
            # "wiring in `RedemptionService` that does not exist" names a type that exists while
            # denying something INSIDE it, which is a true statement. Rather than contort the
            # pattern until it is unreadable, a line may be exempted in the doc itself with a
            # stated reason. The marker is greppable, so exemptions stay reviewable instead of
            # silently widening the way a loosened regex would.
            prev = doc_lines[lineno - 2] if lineno >= 2 else ""
            if "doc-claim-ok" in line or "doc-claim-ok" in prev:
                exempted.append((doc.as_posix(), lineno))
                continue
            # ADJACENCY, not co-occurrence. The first version of this scanner flagged any line
            # holding a negative phrase AND a route anywhere on it, which matched
            # "5 events have no listener ... /notifications/read-all" - two unrelated statements
            # sharing a bullet. A claim is only about a route if it sits next to it.
            for m in ROUTE.finditer(line):
                route = m.group(1)
                key = normalise(route)
                if len(key) < 2 or key not in routes:
                    continue
                window = line[max(0, m.start() - ADJACENCY) : m.end() + ADJACENCY]
                if NEGATIVE.search(window):
                    findings.append((doc.as_posix(), lineno, route, window.strip()[:160]))

            for m in JAVA_TYPE.finditer(line):
                name = m.group(1)
                if name not in types:
                    continue
                # A type inside a file citation (`EscrowService.java:120`) is the EVIDENCE for the
                # claim, not its subject. Without this, citing code to justify a limitation trips
                # the gate - punishing precisely the behaviour the ratchet below asks for.
                if line[m.end():m.end() + 5].startswith(".java"):
                    continue
                window = line[max(0, m.start() - ADJACENCY) : m.end() + ADJACENCY]
                if NEGATIVE.search(window):
                    findings.append((doc.as_posix(), lineno, name, window.strip()[:160]))

    if verbose:
        for d, n, r, s in findings:
            print(safe(f"    {d}:{n}  claims {r} is absent/mock, but it IS served"))
            print(safe(f"        > {s}"))

    if exempted:
        print(f"- {len(exempted)} line(s) exempted via an in-doc doc-claim-ok marker")

    if findings:
        print(f"VERDICT: broken - {len(findings)} doc claim(s) assert a route is missing, mock or")
        print("         unimplemented while the backend actually ships it. The doc, not the code,")
        print("         is wrong - re-run with --verbose for each line.")
        return 1

    print("- no current-truth doc claims a served route or an existing type is missing/mock")

    uncited = count_uncited_claims()
    if uncited is None:
        print("- docs/docs/features is absent - FEATURE-DOC RATCHET NOT CHECKED")
        return 0
    import json
    if not BASELINE_FILE.exists():
        print("- no ratchet baseline recorded - unavailable, not a pass")
        return 2
    data = json.loads(BASELINE_FILE.read_text(encoding="utf-8"))
    baseline = data["uncited_claims"]
    per_file = data.get("per_file", {})
    n = len(uncited)
    print(f"- feature-doc claims lacking a code citation: {n} (ratchet baseline {baseline})")
    if n > baseline:
        # Attributed PER FILE. The first version sliced the tail of a sorted list and named the
        # wrong doc - a diagnostic that sends the reader to a file they never touched is worse
        # than none, because it looks authoritative.
        now = {}
        for name, _lineno in uncited:
            now[name] = now.get(name, 0) + 1
        for name in sorted(now):
            was = per_file.get(name, 0)
            if now[name] > was:
                print(safe(f"    {name}: {was} -> {now[name]} unevidenced claim(s)"))
        print("VERDICT: broken - a negative status claim was added to a feature doc without citing")
        print("         the code that justifies it. That is how all 18 findings in this class")
        print("         started: an assertion nobody could check, which the code then outgrew.")
        return 1
    if n < baseline:
        print(f"- ratchet improved ({baseline} -> {n}); lower the baseline in {BASELINE_FILE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
