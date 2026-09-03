#!/usr/bin/env python3
"""Gate for ledger class `empty-state-misleads` — the COUNT/ERROR half.

Recurrences this gate exists for:

  F-0278  a brand with zero campaigns reads "All caught up!" on day one
  F-0349  a 0-slot deliverables panel tells the creator to submit content
  F-0410  `Showing ${filteredCreators.length} creators` — the length of the
          CLIENT-FILTERED CURRENT PAGE rendered as the size of the creator base,
          while `CreatorSearchResult.meta.total` sat unread in the same result
  F-0436  the notifications bell badge recomputed from the fetched page while the
          server's authoritative `unreadCount` arrived on the envelope and was
          dropped by `const { items } = await notificationsApi.list(role)`

gates/empty_state_honesty.py already reads the CLAIM half (an affirmative string
with no evidence behind it). It does not read either shape above: a count is a
number, not a claim string, and a fetch failure that falls into the empty branch
renders no string of its own at all — that is the whole defect.

So this gate reads the two remaining shapes, over a CURATED list of list-rendering
surfaces (each row cites the ledger id that put it there):

  1. COUNT PROVENANCE — a displayed total must come from server pagination meta,
     not from a client array's `.length`. Enforced only where the API layer is
     PROVEN to expose such a total; where it does not (creatorCampaigns.browse
     throws the total away at the client type), the surface is UNAVAILABLE, not
     passing. A count whose copy discloses its own locality ("N loaded campaigns")
     is not misleading and is recorded as disclosed.
  2. ERROR != EMPTY — a failed fetch must render something a genuine empty result
     does not. A `catch` that empties the list and raises only a toast is the
     F-0410 shape: the toast is transient, the empty state is what stays on screen.

tsc, eslint, vitest and a screenshot pass every one of these. `.length` is a
number, the empty branch is a valid branch, and the toast fires. Only the
RELATIONSHIP between the rendered number and its source, and between the failure
path and the empty path, is wrong — so that relationship is what is read here.

exit 0 proved . 1 real findings . 2 unavailable, never green . 64 usage

Usage: .proof-os/gates/empty_state_misleads.py [repo_root] [--only <regex>]
"""
import os as _o
import sys as _s

_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init  # F-0026: liveness is read, not inferred
    rc_init("empty_state_misleads")
except Exception:
    pass

import os
import re
import sys

USAGE = "usage: .proof-os/gates/empty_state_misleads.py [repo_root] [--only <regex>]"

# Law 5 · every exit path — including exit 0 — names what it did not check.
BLIND = [
    "every list surface NOT in the curated table below; this gate proves nothing "
    "about the rest of src/, and a new list page is invisible here until someone "
    "curates it",
    "whether the server total the component reads is itself correct — only that the "
    "component reads a server value instead of a client array length",
    "runtime behaviour: nothing is rendered, mounted or fetched; a count wired "
    "correctly in source but overwritten at runtime still passes",
    "a count rendered through a helper or a variable assigned across several lines "
    "(only `{x.length}` / `${x.length}` interpolations and single-line "
    "`const nCount = ....length;` assignments are recognised)",
    "whether a rendered error branch is actually REACHABLE, legible, or says "
    "anything useful — only that a branch keyed on an error state exists and is "
    "distinct from the empty branch",
    "the claim half of this ledger class (an affirmative string with no evidence "
    "behind it) — that is gates/empty_state_honesty.py, which must be run too",
]


def emit(extra=()):
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ───────────────────────────────────────────────────────────── curated surfaces
# `asserts` is per-surface on purpose. A hook renders no JSX, so ERROR != EMPTY
# cannot be asked of it; a page with no displayed total cannot be asked for count
# provenance. Curating the assertion alongside the file keeps a surface from being
# silently exempted from a check it should have had.
#
#   api_evidence : proof, in the API layer, that a server total EXISTS for this
#                  surface. No match => the count assertion is unavailable, never
#                  a pass — demanding a total the client type does not carry would
#                  be a false red, and assuming one is the false green.
#   consumes     : proof, in the component, that the server total is actually read
#                  off the API result.
API_FILES = ("src/lib/api.ts", "src/lib/meera-api.ts")

SURFACES = (
    {
        "file": "src/components/brand/discover/creator-discovery.tsx",
        "label": "brand creator discovery grid",
        "ledger": "F-0410",
        "asserts": ("count", "error"),
        "api_evidence": r"interface\s+CreatorSearchResult\b[\s\S]{0,400}?\btotal\??\s*:",
        "consumes": r"\bmeta\s*\.\s*total\b|\bserverTotal\b|\bapiTotal\b|\btotalCreators\b",
    },
    {
        "file": "src/hooks/useNotifications.ts",
        "label": "notification bell unread badge",
        "ledger": "F-0436",
        "asserts": ("count",),
        "api_evidence": r"list\s*:\s*async[\s\S]{0,300}?unreadCount\s*:\s*number",
        "consumes": r"\{[^}\n]*\bunreadCount\b[^}\n]*\}\s*=\s*await\s+notificationsApi\.list",
    },
    {
        "file": "src/components/brand/campaigns/campaigns-list.tsx",
        "label": "brand campaigns list",
        "ledger": "F-0410 (same shape, opposite verdict — this one reads meta.total)",
        "asserts": ("count", "error"),
        "api_evidence": r"interface\s+CampaignListResult\b[\s\S]{0,400}?\btotal\??\s*:",
        "consumes": r"\bmeta\s*\.\s*total\b|\bapiTotal\b",
    },
    {
        "file": "src/pages/creator-campaigns.tsx",
        "label": "creator campaign browse",
        "ledger": "F-0410",
        "asserts": ("count", "error"),
        "api_evidence": r"browse\s*:\s*async[\s\S]{0,900}?meta\s*:\s*\{[^}]*\btotal\b",
        "consumes": r"\bmeta\s*\.\s*total\b|\btotalCampaigns\b",
    },
    {
        "file": "src/pages/creator-applications.tsx",
        "label": "creator applications list",
        "ledger": "F-0278",
        "asserts": ("error",),
        "api_evidence": None,
        "consumes": None,
    },
    {
        "file": "src/pages/brand-notifications.tsx",
        "label": "brand notifications page",
        "ledger": "F-0278 / F-0436",
        "asserts": ("error",),
        "api_evidence": None,
        "consumes": None,
    },
    {
        "file": "src/pages/creator-notifications.tsx",
        "label": "creator notifications page",
        "ledger": "F-0278 / F-0436",
        "asserts": ("error",),
        "api_evidence": None,
        "consumes": None,
    },
)

# ─────────────────────────────────────────────────────────────────── patterns
# `{x.length}` is a JSX expression container and `${x.length}` a template
# interpolation — the `${` ends in `{`, so one pattern reads both. Neither form is
# legal anywhere a value is not being RENDERED or stringified: `{ x.length }` is
# not a valid object literal (a member expression cannot be shorthand), so a match
# here is a number on the screen.
DISPLAY_INTERP = re.compile(r"\{\s*([A-Za-z_$][\w.$]*)\.length\s*\}")
# ...and a single-line `const unreadCount = notifications.filter(...).length;`,
# which is F-0436 exactly. The name must READ as a total; a bare `const n = xs.length`
# used as a predicate is not a rendered claim.
COUNT_ASSIGN = re.compile(
    r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*(?:[Cc]ount|[Tt]otal))\s*"
    r"(?::[^=;\n]{0,120})?=\s*([^;\n]{0,200}\.length)\s*;"
)
# Copy that discloses its own locality is not misleading. "12 loaded campaigns" is
# a true statement about the client array; "Showing 12 creators" is not.
DISCLOSED = re.compile(r"\bloaded\b|\bon this page\b|\bthis page\b|\bso far\b|\bshown\b")

ERR_USESTATE = re.compile(
    r"\b(?:const|let)\s*\[\s*([A-Za-z_$][\w$]*)\s*,\s*set[A-Za-z_$][\w$]*\s*\]\s*=\s*"
    r"(?:React\.)?useState"
)
ERR_DESTRUCTURE = re.compile(r"\bconst\s*\{([^}]{0,300})\}\s*=\s*use[A-Z][\w$]*\s*\(")
SET_TO_EMPTY = re.compile(r"\bset[A-Z][\w$]*\(\s*(?:\[\s*\]|prev\s*=>\s*\[\s*\])\s*\)")
# FALSIFIED BEFORE SHIPPING: written first as `set[A-Za-z_$][\w$]*[Ee]rror`, the leading
# character class ate the `E` of `setError(` and no amount of backtracking could put it
# back, so the ONE spelling every file in this tree actually uses was the one spelling
# that did not match — creator-applications.tsx:51 and creator-campaigns.tsx:96 were both
# reported as swallowing their failure while both call setError(message) three lines in.
# A gate whose red is wrong is worse than no gate.
SET_ERROR = re.compile(r"\bset[A-Za-z_$]*[Ee]rror[\w$]*\s*\(")
CATCH = re.compile(r"\bcatch\s*(?:\([^)]*\))?\s*\{")


def line_of(src, idx):
    return src[:idx].count("\n") + 1


def err_names(src):
    names = set()
    for m in ERR_USESTATE.finditer(src):
        if "error" in m.group(1).lower():
            names.add(m.group(1))
    for m in ERR_DESTRUCTURE.finditer(src):
        for part in m.group(1).split(","):
            ident = part.split(":")[-1].strip()
            if re.fullmatch(r"[A-Za-z_$][\w$]*", ident) and "error" in ident.lower():
                names.add(ident)
    return names


def renders_error(src, names):
    """A branch keyed on an error state that puts something on the screen.

    FALSIFIED BEFORE SHIPPING: the first version required the identifier to sit
    directly after `{`, `(`, `:` or `?`, and campaigns-list.tsx:694 guards its error
    banner as `{liveApi && loadError && (` — a second condition in front. The gate
    called a rendered, working error banner missing. A leading `&&`/`||` is accepted
    now; the shape it must still NOT accept is creator-discovery.tsx, which holds no
    error identifier at all.
    """
    for n in names:
        pat = re.compile(r"(?:[{(:?]|&&|\|\|)\s*!?\s*" + re.escape(n) + r"\s*(?:&&|\?|\|\|)")
        if pat.search(src):
            return n
    return None


# ─────────────────────────────────────────────────────────────────── arguments
root, only = None, None
argv = sys.argv[1:]
i = 0
while i < len(argv):
    a = argv[i]
    if a == "--only":
        if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
            die(64, "· --only needs a regex\n" + USAGE, ["everything: the gate never ran"])
        try:
            only = re.compile(argv[i + 1])
        except re.error as e:
            die(64, "· --only %r is not a regex: %s\n%s" % (argv[i + 1], e, USAGE),
                ["everything: the gate never ran"])
        i += 2
        continue
    if a.startswith("--"):
        die(64, "· unknown option %s\n%s" % (a, USAGE), ["everything: the gate never ran"])
    if root is not None:
        die(64, "· at most one repo_root\n" + USAGE, ["everything: the gate never ran"])
    root = a
    i += 1
root = root or "."

if not os.path.isdir(root):
    die(2, "· repo root %s does not exist — unavailable" % root,
        ["every curated surface: there was no tree to read"])
if not os.path.isdir(os.path.join(root, "src")):
    die(2, "· %s/src not found — this is not the frontend tree (unavailable)" % root,
        ["every curated surface: there was no frontend tree to read"])


def read(rel):
    p = os.path.join(root, rel)
    if not os.path.isfile(p):
        return None
    try:
        return open(p, encoding="utf-8", errors="replace").read()
    except OSError:
        return None


api_src = ""
api_seen = []
for rel in API_FILES:
    body = read(rel)
    if body is not None:
        api_src += "\n" + body
        api_seen.append(rel)
if not api_seen:
    die(2, "· none of %s could be read — a server total cannot be proved to exist "
           "or not exist, so no count assertion is decidable (unavailable)"
           % ", ".join(API_FILES),
        ["every count assertion: the API layer was unreadable"])

# ──────────────────────────────────────────────────────────────────── checking
surfaces = list(SURFACES)
if only is not None:
    surfaces = [s for s in surfaces if only.search(s["file"]) or only.search(s["label"])]
    if not surfaces:
        die(2, "· --only %s matched 0 curated surfaces — the filter selected nothing "
               "to test, which is a broken assertion, not a passing one (unavailable)"
               % only.pattern,
            ["everything in that scope: the filter selected no surface"])

findings = []       # real defects -> exit 1
unavailable = []    # could not decide -> exit 2, never green
notes = []          # things proved, printed so the pass is legible
checked = 0         # assertions actually DECIDED

for s in surfaces:
    rel, label = s["file"], s["label"]
    src = read(rel)
    if src is None:
        unavailable.append("%s (%s) — curated but not on disk or unreadable; a "
                           "surface that cannot be opened has not been checked"
                           % (rel, label))
        continue
    if len(src.strip()) < 40:
        unavailable.append("%s (%s) — file is effectively empty; nothing to read"
                           % (rel, label))
        continue

    # ── 1. COUNT PROVENANCE
    if "count" in s["asserts"]:
        sites = []
        for m in DISPLAY_INTERP.finditer(src):
            lo, hi = max(0, m.start() - 220), min(len(src), m.end() + 220)
            sites.append((line_of(src, m.start()), "%s.length rendered" % m.group(1),
                          src[lo:hi]))
        for m in COUNT_ASSIGN.finditer(src):
            lo, hi = max(0, m.start() - 220), min(len(src), m.end() + 220)
            sites.append((line_of(src, m.start()),
                          "const %s = %s" % (m.group(1), m.group(2).strip()),
                          src[lo:hi]))

        has_total = re.search(s["api_evidence"], api_src) if s["api_evidence"] else None
        if not sites:
            unavailable.append(
                "%s (%s) — curated for count provenance but no displayed count was "
                "found; either the curation is stale or the count moved somewhere "
                "this gate cannot read. Not a pass." % (rel, label))
        elif not has_total:
            unavailable.append(
                "%s (%s) — %d displayed count(s) at line(s) %s, but %s exposes no "
                "server total for this surface (api_evidence /%s/ does not match), so "
                "there is nothing to require the component to read. The API layer is "
                "what needs fixing; this assertion is undecidable until it is."
                % (rel, label, len(sites), ", ".join(str(l) for l, _, _ in sites),
                   " or ".join(api_seen), s["api_evidence"]))
        else:
            checked += 1
            if re.search(s["consumes"], src):
                notes.append("%s:%s reads the server total (/%s/) — count provenance "
                             "proved" % (rel, label, s["consumes"]))
            else:
                disclosed = [ln for ln, _, ctx in sites if DISCLOSED.search(ctx.lower())]
                misleading = [(ln, what) for ln, what, ctx in sites
                              if not DISCLOSED.search(ctx.lower())]
                if disclosed:
                    notes.append("%s — line(s) %s render a client length but the copy "
                                 "discloses it ('loaded'/'on this page'), so the number "
                                 "is true as written"
                                 % (rel, ", ".join(str(l) for l in disclosed)))
                for ln, what in misleading:
                    findings.append(
                        "%s:%d  [%s]  %s — displayed as a total while %s exposes "
                        "meta.total for this surface and this file never reads it "
                        "(no match for /%s/). A client-filtered page length rendered "
                        "as the population size is %s."
                        % (rel, ln, label, what, " or ".join(api_seen),
                           s["consumes"], s["ledger"]))

    # ── 2. ERROR != EMPTY
    if "error" in s["asserts"]:
        checked += 1
        names = err_names(src)
        rendered = renders_error(src, names) if names else None
        if not names:
            findings.append(
                "%s  [%s]  no error state exists in this file (no error-named "
                "useState and no error destructured from a hook), so a failed fetch "
                "has nothing to render with — it can only fall into the empty branch "
                "and read as a genuine empty result. %s."
                % (rel, label, s["ledger"]))
        elif not rendered:
            findings.append(
                "%s  [%s]  error state %s is held but never rendered in a branch of "
                "its own; a failure and an empty result put the same pixels on the "
                "screen. %s." % (rel, label, "/".join(sorted(names)), s["ledger"]))

        for m in CATCH.finditer(src):
            body = src[m.end():m.end() + 900]
            if SET_TO_EMPTY.search(body) and not SET_ERROR.search(body):
                findings.append(
                    "%s:%d  [%s]  a catch block empties the list without setting any "
                    "error state — whatever else it does (a toast is transient and "
                    "gone on the next render), what STAYS on the screen is the empty "
                    "state, so a failed fetch reads as 'nothing here'. %s."
                    % (rel, line_of(src, m.start()), label, s["ledger"]))

# ──────────────────────────────────────────────────────────────────── verdict
print("* empty-state-misleads%s: %d curated surface(s), %d assertion(s) decided"
      % ((" [--only %s]" % only.pattern) if only else "", len(surfaces), checked))
for n in notes:
    print("  ok   %s" % n)
for f in findings:
    print("  FAIL %s" % f)
for u in unavailable:
    print("  ??   %s" % u)

extra = []
if unavailable:
    extra.append("%d curated surface/assertion(s) could NOT be decided and are listed "
                 "above with '??' — they are neither proved nor disproved"
                 % len(unavailable))

if checked == 0:
    print("VERDICT: unavailable — 0 assertions were decided")
    die(2, "· a run that decided nothing has proved nothing; 'I checked no surfaces, "
           "therefore they are correct' is the exact bug this ledger class exists for",
        extra)

if findings:
    print("VERDICT: broken — %d real finding(s) above" % len(findings))
    emit(extra)
    sys.exit(1)

if unavailable:
    print("VERDICT: unavailable — %d assertion(s) undecidable; nothing here is green"
          % len(unavailable))
    emit(extra)
    sys.exit(2)

print("VERDICT: proved — every curated surface renders its total from server "
      "pagination meta (or discloses that it is not one), and renders a failed "
      "fetch differently from a genuine empty result")
emit(extra)
sys.exit(0)
