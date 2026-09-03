#!/usr/bin/env python3
"""gates/citations.py — origin: design decision "citations must be verifiable".

This gate is the ONLY thing standing behind `q-answerer: may_claim proved` in the
registry, and in 0.3.3 it proved nothing:

    a file with zero citations              -> "checked: 0, failed: 0", exit 0
    an empty file / 3KB of /dev/urandom     -> exit 0
    `real.py:1` says "TOTALLY MADE UP TEXT" -> exit 0   (the quote was discarded)
    a citation to /etc/hostname             -> exit 0   (absolute path joined through)
    a citation to config.yaml:12            -> dropped silently (extension whitelist)
    a missing input file                    -> exit 1 with a traceback

So it certified that a document containing no claims, or containing fabricated ones,
was fully sourced. What it now asserts:

  1. the document contains at least one parseable citation      (else 2, nothing attested)
  2. every cited path stays inside the repo root                (absolute / .. / symlink escape = 1)
  3. every cited file exists and has the cited line             (line 0 is not a line)
  4. every quoted span on a citing line actually occurs in the cited file
  5. everything it could NOT check is named on stdout           (law 5)

LAW (false-red): unreadable input / missing root / nothing to check => exit 2.
                 exit 1 = the document makes a claim its citation does not support.
Usage: gates/citations.py <answers.md> [repo_root] [--min N]
"""
import os as _o, sys as _s
_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init; rc_init("citations")   # F-0026: liveness is read, not inferred
except Exception:
    pass

import re, sys, os

USAGE = "usage: gates/citations.py <answers.md> [repo_root] [--min N]"

# Law 5 · every exit path declares its blind spot.
BLIND = [
    "whether a cited line that exists and is quoted correctly actually SUPPORTS the claim "
    "(only textual presence is verified, never entailment)",
    "citations written in prose without a file:line token",
    "single-quoted spans (apostrophes make them undecidable) and quotes under 4 characters",
    "quote position: a quote is matched within +/-2 lines of the cited line, so a "
    "small line-number error is tolerated",
]


def emit(extra=()):
    """Print the NOT CHECKED line. Called on EVERY exit path."""
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ---------------------------------------------------------------- arguments
args, min_cites = [], 0
argv = sys.argv[1:]
i = 0
while i < len(argv):
    a = argv[i]
    if a == "--min":
        if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
            die(64, f"· --min needs a number\n{USAGE}", ["everything: the gate never ran"])
        try:
            min_cites = int(argv[i + 1])
        except ValueError:
            die(64, f"· --min {argv[i+1]!r} is not an integer\n{USAGE}",
                ["everything: the gate never ran"])
        i += 2
        continue
    if a.startswith("--"):
        die(64, f"· unknown option {a}\n{USAGE}", ["everything: the gate never ran"])
    args.append(a)
    i += 1

if not args:
    die(64, f"· no document given\n{USAGE}", ["everything: the gate never ran"])

md = args[0]
root = args[1] if len(args) > 1 else "."

if os.path.isdir(md):
    die(2, f"· {md} is a directory, not a document — unavailable",
        ["every claim in every file: the input was not a document"])
if not os.path.isfile(md):
    die(2, f"· {md} not found — nothing to attest (unavailable)",
        ["every claim: the document could not be read"])
try:
    doc_lines = open(md, encoding="utf-8", errors="replace").read().splitlines()
except OSError as e:
    die(2, f"· {md} unreadable: {e} — unavailable",
        ["every claim: the document could not be read"])

if not os.path.isdir(root):
    die(2, f"· repo root {root} does not exist — citations cannot be resolved (unavailable)",
        ["every citation: there was no tree to resolve them against"])
root_real = os.path.realpath(root)

# ---------------------------------------------------------------- parsing
# 0.3.3 whitelisted py|ts|tsx|js|md|json only, so a citation to config.yaml, deploy.sh,
# notes.txt or Dockerfile was DROPPED — not checked, not counted, and the document still
# passed on it. Anything this table cannot classify is now reported, never dropped.
KNOWN_EXT = {
    "py", "pyi", "pyx", "ipynb", "ts", "tsx", "js", "jsx", "mjs", "cjs", "vue", "svelte",
    "md", "mdx", "rst", "txt", "adoc", "json", "jsonl", "json5", "yaml", "yml", "toml",
    "ini", "cfg", "conf", "properties", "env", "sh", "bash", "zsh", "fish", "ps1", "bat",
    "cmd", "html", "htm", "xml", "svg", "css", "scss", "sass", "less", "sql", "go", "rs",
    "java", "kt", "kts", "rb", "php", "pl", "lua", "r", "swift", "m", "mm", "c", "h",
    "cpp", "cc", "hpp", "cs", "scala", "clj", "ex", "exs", "erl", "hs", "dart", "gradle",
    "tf", "tfvars", "proto", "graphql", "gql", "csv", "tsv", "lock", "mk", "make", "cmake",
    "dockerfile", "gitignore", "editorconfig", "npmrc", "nvmrc", "sage", "log",
}
NO_EXT_NAMES = {
    "dockerfile", "makefile", "procfile", "jenkinsfile", "rakefile", "gemfile", "brewfile",
    "license", "licence", "readme", "changelog", "notice", "authors", "codeowners",
    "vagrantfile", "justfile", "caddyfile",
}

# The leading `~?/?` is deliberate: an absolute citation must be SEEN so it can be
# rejected. In 0.3.3 it was invisible to the pattern and joined straight onto the root.
CAND = re.compile(r'(?<![\w:.\-/])(~?/?[A-Za-z0-9_][A-Za-z0-9_./\-]*):(\d+)(?![\w.])')
# Quotes: double, curly, and backtick. Single quotes are NOT parsed — an apostrophe in
# prose ("don't") would manufacture a span that no file contains, i.e. a false red.
QUOTE = re.compile(r'"([^"\n]{1,500})"|\u201c([^\u201d\n]{1,500})\u201d|`([^`\n]{1,500})`')
SELF_REF = re.compile(r'^[A-Za-z0-9_./\-]+:\d+$')


def classify(tok):
    """('ok', path) | ('skip', why) | ('unparseable', why)"""
    if "://" in tok or tok.startswith("//"):
        return "skip", "url"
    base = os.path.basename(tok)
    if "." in base and not base.startswith("."):
        ext = base.rsplit(".", 1)[1].lower()
        if ext in KNOWN_EXT:
            return "ok", tok
        return "unparseable", f"{tok} (unrecognised extension .{ext})"
    if base.lower().lstrip(".") in NO_EXT_NAMES:
        return "ok", tok
    if "/" in tok:
        return "unparseable", f"{tok} (path-like but no recognised file type)"
    return "skip", "not a path"


def norm(s):
    return re.sub(r"\s+", " ", s).strip().lower()


def quotes_on(line, cite_tokens):
    out = []
    for m in QUOTE.finditer(line):
        q = next(g for g in m.groups() if g is not None)
        if SELF_REF.match(q.strip()) or q.strip() in cite_tokens:
            continue                      # the backticked citation itself, not a claim
        if len(norm(q)) < 4:
            continue                      # too short to distinguish from noise
        out.append(q)
    return out


# ---------------------------------------------------------------- checking
fails, unparseable, skipped_quotes = [], [], []
checks = 0
quoted_checks = 0
uncited_lines = []           # citations with no quotation on their line
file_cache = {}


def lines_of(p):
    if p not in file_cache:
        try:
            file_cache[p] = open(p, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            file_cache[p] = None
    return file_cache[p]


for i, ln in enumerate(doc_lines, 1):
    raw = [(m.group(1), m.group(2)) for m in CAND.finditer(ln)]
    resolved = []                        # (token, lineno, abspath) for this doc line
    for tok, n in raw:
        # absoluteness is decided BEFORE file-type classification: an absolute citation
        # is rejected as a citation, whatever it points at.
        if os.path.isabs(tok) or tok.startswith("~"):
            checks += 1
            fails.append(f"{md}:{i} cites {tok}:{n} — ABSOLUTE PATH REJECTED: a citation "
                         f"that leaves the repo cannot be reviewed by anyone reading the "
                         f"repo, and os.path.join() would have honoured it verbatim")
            continue
        kind, why = classify(tok)
        if kind == "skip":
            continue
        if kind == "unparseable":
            unparseable.append(f"{md}:{i} cites {why}")
            continue

        checks += 1
        p = os.path.join(root, tok)
        real = os.path.realpath(p)
        try:
            inside = os.path.commonpath([real, root_real]) == root_real
        except ValueError:
            inside = False
        if not inside:
            fails.append(f"{md}:{i} cites {tok}:{n} — RESOLVES OUTSIDE THE REPO "
                         f"({real}); a symlink or ../ escape is not a citation")
            continue
        if not os.path.isfile(real):
            fails.append(f"{md}:{i} cites {tok}:{n} — FILE MISSING")
            continue
        body = lines_of(real)
        if body is None:
            fails.append(f"{md}:{i} cites {tok}:{n} — file unreadable")
            continue
        want = int(n)
        if want < 1:
            fails.append(f"{md}:{i} cites {tok}:{n} — line {n} exists in no file "
                         f"(line numbers start at 1)")
            continue
        if want > len(body):
            fails.append(f"{md}:{i} cites {tok}:{n} — file has {len(body)} lines")
            continue
        resolved.append((tok, want, body))

    if not resolved:
        continue

    cite_tokens = {f"{t}:{n}" for t, n, _ in resolved}
    qs = quotes_on(ln, cite_tokens)
    if not qs:
        uncited_lines.append(f"{md}:{i} ({', '.join(sorted(cite_tokens))})")
        continue
    for q in qs:
        quoted_checks += 1
        nq = norm(q)
        hit = False
        for tok, want, body in resolved:
            lo, hi = max(0, want - 3), min(len(body), want + 2)
            window = norm(" ".join(body[lo:hi]))
            if nq in window:
                hit = True
                break
        if not hit:
            where = ", ".join(f"{t}:{n}" for t, n, _ in resolved)
            fails.append(f'{md}:{i} quotes "{q[:70]}" but that text is not at {where} — '
                         f"FABRICATED QUOTATION: the claim is attached to a real line "
                         f"that does not say it")

# ---------------------------------------------------------------- verdict
extra = []
if unparseable:
    extra.append(f"{len(unparseable)} citation-shaped token(s) whose file type this gate "
                 f"cannot classify: " + "; ".join(unparseable[:5]) +
                 (" ..." if len(unparseable) > 5 else ""))
if uncited_lines:
    extra.append(f"{len(uncited_lines)} citation(s) carry NO quoted span, so only the "
                 f"existence of file and line was verified, not the assertion: " +
                 "; ".join(uncited_lines[:5]) + (" ..." if len(uncited_lines) > 5 else ""))

if checks == 0:
    print(f"citations checked: 0 — {md} attests nothing")
    die(2, "· a document with no citations has not been sourced; there is nothing for "
           "this gate to prove (unavailable, never green)", extra)

if min_cites and checks < min_cites:
    fails.append(f"{md} carries {checks} citation(s), fewer than the required --min "
                 f"{min_cites}")
print(f"citations checked: {checks}, quotations verified: {quoted_checks}, "
      f"failed: {len(fails)}")
for x in fails:
    print("  ", x)
emit(extra)
sys.exit(1 if fails else 0)
