#!/usr/bin/env python3
"""gates/unenforced_limit.py - ledger class `unenforced-limit` (F-0399, F-0400, F-0417, F-0418).

The shape, four times over: a limit is COLUMN, it is SET, it is SERIALISED back to the
client, and no code path anywhere ever reads it to say no.

    F-0399  Campaign.budgetMax   compared against ONE offer, never summed across the
                                 campaign -- N creators may each be offered the full max
    F-0400  Campaign.maxCollaborators  persisted, echoed by two mappers, read by nothing
    F-0417  Deliverable.revisionCount  incremented forever; the only comparison is `> 0`,
                                 which is a "has been revised" flag, not a ceiling
    F-0418  Deliverable.deadline the DTO carries it; the only readers are reporting
                                 filters and an on-time boolean - nothing rejects,
                                 transitions or notifies on a miss

Every prior gate was green through all four. The column existed, the entity compiled, the
DTO field was populated, the endpoint was reachable, the FE rendered it. "The number is
there" was proved over and over; "the number bounds anything" was never asked.

WHAT THIS ASSERTS, per curated field:

  1. the field still EXISTS on the entity it was curated against  (else 2 - stale curation
     silently shrinks coverage, which is this ledger class wearing a gate's clothes)
  2. at least one read of it lives OUTSIDE a getter / mapper / DTO / repository
  3. that read is a real ENFORCEMENT: an if/while guard (directly, or through a local
     alias, or handed to a check-shaped callee) whose condition ORDERS the value against
     something -- `>`, `<`, `compareTo`, `isBefore`, `isAfter` -- and whose guarded block
     rejects or acts (throw / exception / status transition / notification)
  4. the comparison is against a BOUND, not the literal 0. F-0417's only comparison is
     `getRevisionCount() > 0`, which says "has been revised", not "may not exceed N".
     (compareTo/signum three-way idioms are exempt: there the 0 is the pivot, and the
     bound is compareTo's argument.)
  5. shape-specific extras, because F-0399 had a real comparison and was still unenforced:
       aggregate-guard  the enforcing method must also SUM across siblings (F-0399)
       ceiling-guard    documents WHY a field is curated; rule 4 is what does the work

A `!= null` test is explicitly NOT enforcement. It proves the limit is present; the whole
class is limits that are present.

LAW (false-red / false-green): no source tree, a file that will not decode, unbalanced
braces, a curated field that has vanished, or `--only` matching nothing => exit 2, never
green. Zero fields checked is never a pass. exit 1 = a limit that bounds nothing.

usage: gates/unenforced_limit.py [--root DIR] [--only REGEX]
exit 0 proved . 1 real findings . 2 unavailable . 64 usage
"""
import os as _o
import sys as _s

_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init

    rc_init("unenforced_limit")  # F-0026: liveness is read, not inferred
except Exception:
    pass

import os
import re
import sys

USAGE = "usage: gates/unenforced_limit.py [--root DIR] [--only REGEX]"

SRC_ROOT = os.path.join("influora-api", "src", "main", "java", "com", "influora")

# ---------------------------------------------------------------- law 5: blind spots
BLIND = [
    "enforcement written in SQL or a Spring Data derived query name "
    "(countBy...AndExpiresAtAfter enforces a TTL with no getter read at all), which is why "
    "no *ExpiresAt / TTL field is curated here - this gate cannot see those and will not "
    "pretend to",
    "enforcement performed by the DATABASE (a CHECK constraint, a trigger, a NOT NULL) or by "
    "a Bean Validation annotation (@Max/@Size) rather than by Java statements",
    "whether an enforcement that EXISTS is CORRECT - the boundary could be off by one, the "
    "comparison inverted, or the guard unreachable behind a feature flag; only its presence "
    "and its shape are proved",
    "a validator that returns a boolean instead of throwing: `return used > limit;` reads as "
    "reporting here, so a caller that throws on the false is invisible and reports red",
    "a limit that reaches its comparison through a FIELD or a returned value rather than a "
    "local declaration or a check-shaped argument - only those two indirections are followed, "
    "and only one hop deep",
    "limits enforced in the FRONTEND only (which is not enforcement, but this gate would not "
    "see it either way) and limits enforced in test sources - only main/ is read",
    "every limit-bearing field NOT on the curated list below; this gate proves the curated "
    "set and makes no claim about any other column",
    "whether a summing method actually sums the RIGHT rows (aggregate-guard proves a sum is "
    "present in the enforcing method, not that its query scope is correct)",
]


def emit(extra=()):
    """The NOT CHECKED line. Printed on EVERY exit path, success included."""
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ---------------------------------------------------------------- the curated list
# (entity file, field, getter, shape, ledger id, why it is on the list)
#
# shape:
#   guard            one enforcing guard anywhere outside getter/mapper/DTO is enough
#   aggregate-guard  the enforcing method must ALSO sum across siblings   (F-0399)
#   ceiling-guard    the comparison must be against a bound, not literal 0 (F-0417)
FIELDS = [
    # -- the four the ledger opened this class on ---------------------------------
    ("domain/entity/Campaign.java", "budgetMax", "getBudgetMax", "aggregate-guard",
     "F-0399",
     "one offer is compared to the max; nothing sums what the campaign is already "
     "committed to, so N creators can each be offered the whole budget"),
    ("domain/entity/Campaign.java", "maxCollaborators", "getMaxCollaborators", "guard",
     "F-0400",
     "persisted and echoed to the client; no path counts collaborators against it"),
    ("domain/entity/Deliverable.java", "revisionCount", "getRevisionCount", "ceiling-guard",
     "F-0417",
     "incremented by requestRevision with no ceiling; unpaid rework is unbounded"),
    ("domain/entity/Deliverable.java", "deadline", "getDeadline", "guard",
     "F-0418",
     "returned on the creator DTO; nothing auto-fails, penalises or notifies on a miss"),
    # -- same shape, found by sweeping the entity tree for limit-bearing columns ---
    ("domain/entity/Plan.java", "trackedCreatorLimit", "getTrackedCreatorLimit", "guard",
     "-",
     "the plan's tracked-creator cap; UsageMetric.TRACKED_CREATOR is counted and the "
     "limit is rendered next to the count, but the two are never compared"),
    # -- controls: limits believed to BE enforced. If one of these ever reports, the
    #    finding is real and the enforcement was lost, not a gate bug. They also keep
    #    this gate falsifiable - a check nothing can pass proves nothing. ------------
    ("domain/entity/Campaign.java", "budgetMin", "getBudgetMin", "guard", "-",
     "control: the floor beside F-0399's ceiling, enforced per offer"),
    ("domain/entity/Campaign.java", "applicationDeadline", "getApplicationDeadline",
     "guard", "-",
     "control: a date limit that IS acted on, unlike F-0418's deliverable deadline"),
    ("domain/entity/Plan.java", "seatLimit", "getSeatLimit", "guard", "-",
     "control: seats counted and compared before an invite is issued"),
    ("domain/entity/Plan.java", "creatorAnalyticsMonthlyLimit",
     "getCreatorAnalyticsMonthlyLimit", "guard", "-",
     "control: a quota handed to a check-shaped callee rather than compared inline"),
    ("domain/entity/CouponCode.java", "usageLimit", "getUsageLimit", "guard", "-",
     "control: redemption count compared to the limit before writing"),
    ("domain/entity/PlatformFeeConfig.java", "minFeeBps", "getMinFeeBps", "guard", "-",
     "control: a floor compared directly in a validator"),
    ("domain/entity/PlatformFeeConfig.java", "maxFeeBps", "getMaxFeeBps", "guard", "-",
     "control: a ceiling reached through a local alias"),
]

# ---------------------------------------------------------------- surfaces that only echo
# A read here is a getter, a mapper, a DTO assembly or a persistence query - never the
# place a limit is enforced. This is the "outside a getter/mapper/DTO" of the contract.
ECHO_PATH = re.compile(r"(?:^|[/\\])(?:domain[/\\]entity|domain[/\\]enums|web[/\\]dto|repository)[/\\]")
ECHO_FILE = re.compile(r"(?:Mapper|Dtos?|Request|Response|Specifications|Assembler)\.java$")

# ---------------------------------------------------------------- statement grammar
GUARD_HEAD = re.compile(r"(?:^|[^A-Za-z0-9_])(if|while)\s*\(\s*$")
# `>` / `<` / `>=` / `<=` but never `->`, `==`, `!=`, `<=` read as `<` `=`.
ORDER = re.compile(r"(?<![-=!<>])[<>]=?(?![=])|\.compareTo\s*\(|\.is(?:Before|After)\s*\(")
ZERO_CMP = re.compile(
    r"(?<![-=!<>])[<>]=?\s*(?:0|0L|BigDecimal\.ZERO)\b"
    r"|\b(?:0|0L|BigDecimal\.ZERO)\s*(?<![-=!<>])[<>]=?(?![=])"
)
# A guarded block that actually says no, or changes the world. `return false` is
# deliberately absent - see BLIND.
REJECT = re.compile(
    r"\bthrow\b|\w*Exception\s*\(|\.setStatus\s*\(|\.markAs[A-Z]\w*\s*\("
    r"|\bnotif\w*|\breject\w*\s*\(|\bdeny\w*\s*\(|\bsuspend\w*\s*\(|\bblock[A-Z]\w*\s*\("
)
# A callee whose NAME says it performs a check; the limit is its argument.
ENFORCER = re.compile(
    r"\b(?:record|check|assert|validate|enforce|require|ensure|consume|reserve|"
    r"tryAcquire|guard|can|is|has|within|exceed)[A-Z]\w*\s*\("
)
# Summing across siblings, the thing F-0399's per-offer comparison never does.
AGGREGATE = re.compile(r"\b(?:sum|Sum|total|Total|aggregate|Aggregate)\w*|\.reduce\s*\(|summing")
# A local declaration at the head of the statement: `Integer monthlyLimit = plan.getX();`,
# `int cap = flag ? 10_000 : config.getY();`. Anchored at the statement head rather than
# immediately before the read, because the read is almost never the first token after `=`
# (a receiver, a ternary or a null-coalesce sits in between).
DECL = re.compile(
    r"^\s*(?:final\s+)?[A-Za-z_][\w.]*(?:\s*<[^;{}]*>)?(?:\s*\[\s*\])?\s+([A-Za-z_]\w*)\s*=\s*[^=]"
)


def strip_noise(src):
    """Blank comments and string/char literals, preserving every offset and newline.

    Offsets must survive so a finding can name the real line. Returns None if the file
    ends inside an unterminated construct - that is a file that does not parse.
    """
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            if j < 0:
                return None
            for k in range(i, j + 2):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 2
            continue
        if src.startswith('"""', i):
            # Java text block. Multi-line by definition, so it must be handled BEFORE the
            # single-line string branch below - which rejects an embedded newline and would
            # report the whole file as unparseable (FeaturedCreatorRepository.java).
            j = i + 3
            while True:
                j = src.find('"""', j)
                if j < 0:
                    return None
                if src[j - 1] != "\\":
                    break
                j += 3
            for k in range(i, j + 3):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 3
            continue
        if c in "\"'":
            q, j = c, i + 1
            out[i] = " "
            while j < n:
                if src[j] == "\\":
                    out[j] = " "
                    if j + 1 < n:
                        out[j + 1] = " "
                    j += 2
                    continue
                if src[j] == "\n":
                    return None
                if src[j] == q:
                    out[j] = " "
                    j += 1
                    break
                out[j] = " "
                j += 1
            else:
                return None
            i = j
            continue
        i += 1
    return "".join(out)


def brace_spans(code):
    """[(start, end, level)] for every {...}, or None if the braces do not balance."""
    stack, spans = [], []
    for i, c in enumerate(code):
        if c == "{":
            stack.append(i)
        elif c == "}":
            if not stack:
                return None
            s = stack.pop()
            spans.append((s, i, len(stack) + 1))
    return None if stack else spans


def line_of(code, i):
    return code.count("\n", 0, i) + 1


def method_body(code, spans, i):
    """The enclosing method body span, or the tightest block that contains i."""
    best = None
    for s, e, lvl in spans:
        if s < i < e and lvl == 2:
            if best is None or (e - s) < (best[1] - best[0]):
                best = (s, e)
    if best:
        return best
    for s, e, lvl in spans:
        if s < i < e and lvl >= 2:
            if best is None or (e - s) < (best[1] - best[0]):
                best = (s, e)
    return best or (0, len(code))


def statement_of(code, i):
    """(start, end) of the statement or clause head containing offset i."""
    s = i
    while s > 0 and code[s - 1] not in ";{}":
        s -= 1
    e, depth = i, 0
    while e < len(code):
        c = code[e]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == ";" and depth <= 0:
            break
        elif c == "{" and depth <= 0:
            break
        e += 1
    return s, min(e + 1, len(code))


def split_clauses(cond):
    """Top-level && / || operands of a condition."""
    parts, depth, cur = [], 0, []
    k = 0
    while k < len(cond):
        c = cond[k]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        if depth == 0 and cond[k:k + 2] in ("&&", "||"):
            parts.append("".join(cur))
            cur = []
            k += 2
            continue
        cur.append(c)
        k += 1
    parts.append("".join(cur))
    return [p for p in parts if p.strip()]


def guard_condition(code, stmt_start, i):
    """If offset i sits inside an if/while condition, return (condition, block_text)."""
    depth, k = 0, i
    open_paren = None
    while k > stmt_start:
        k -= 1
        c = code[k]
        if c == ")":
            depth += 1
        elif c == "(":
            if depth == 0:
                open_paren = k
                break
            depth -= 1
    if open_paren is None or not GUARD_HEAD.search(code[stmt_start:open_paren + 1]):
        return None
    depth, k = 1, open_paren + 1
    while k < len(code) and depth:
        if code[k] == "(":
            depth += 1
        elif code[k] == ")":
            depth -= 1
        k += 1
    if depth:
        return None
    cond = code[open_paren + 1:k - 1]
    rest = code[k:]
    m = re.match(r"\s*\{", rest)
    if m:
        d, j = 0, k + m.end() - 1
        while j < len(code):
            if code[j] == "{":
                d += 1
            elif code[j] == "}":
                d -= 1
                if d == 0:
                    break
            j += 1
        block = code[k:j + 1]
    else:
        block = rest[:rest.find(";") + 1] if ";" in rest[:400] else rest[:400]
    return cond, block


def enforcing_guard(code, i, token_re, shape):
    """('yes'|'no', note) for a single read/alias occurrence at offset i."""
    s, _ = statement_of(code, i)
    g = guard_condition(code, s, i)
    if not g:
        return None, "not a guard"
    cond, block = g
    clauses = [c for c in split_clauses(cond) if token_re.search(c)]
    if not clauses:
        clauses = [cond]
    ordered = [c for c in clauses if ORDER.search(c)]
    if not ordered:
        return None, "compared only for null/equality, which proves presence not a bound"
    # A comparison against the literal 0 is a presence/positivity test, not a bound -- F-0417's
    # `getRevisionCount() > 0` and BrandCampaignFeeService's `budget.signum() <= 0` are both
    # this shape. The exception is compareTo/signum used as a THREE-WAY comparison against
    # another value (`amount.compareTo(max) > 0`), where the 0 is the idiom's pivot and the
    # real bound is compareTo's argument.
    bounded = [c for c in ordered if ".compareTo(" in c.replace(" ", "") or not ZERO_CMP.search(c)]
    if not bounded:
        return None, "compared against the literal 0 - a set/positive flag, not a bound"
    if not REJECT.search(block):
        return None, "the guarded block neither rejects nor changes state (reporting only)"
    return "yes", bounded[0].strip()


def scan_file(path, code, spans, getter, shape):
    """(reads, enforcements) for one file. reads/enforcements are (line, kind, note)."""
    token_re = re.compile(r"\b" + re.escape(getter) + r"\s*\(\s*\)")
    ref_re = re.compile(r"::\s*" + re.escape(getter) + r"\b")
    reads, enforced = [], []
    echo = bool(ECHO_PATH.search(path) or ECHO_FILE.search(os.path.basename(path)))

    for m in ref_re.finditer(code):
        reads.append((line_of(code, m.start()), "echo" if echo else "map",
                      "method reference - never a comparison"))

    for m in token_re.finditer(code):
        ln = line_of(code, m.start())
        if echo:
            reads.append((ln, "echo", "getter/mapper/DTO/repository surface"))
            continue
        ok, note = enforcing_guard(code, m.start(), token_re, shape)
        if ok:
            reads.append((ln, "enforce", note))
            enforced.append((ln, m.start(), note))
            continue
        direct = note
        # alias: `Type x = <read>;` - follow x inside the enclosing method.
        s, e = statement_of(code, m.start())
        dm = DECL.search(code[s:m.start()])
        alias_hit = False
        if dm:
            alias = dm.group(1)
            ms, me = method_body(code, spans, m.start())
            arx = re.compile(r"\b" + re.escape(alias) + r"\b")
            for am in arx.finditer(code[ms:me]):
                ai = ms + am.start()
                if s <= ai < e:
                    continue
                aok, anote = enforcing_guard(code, ai, arx, shape)
                if aok:
                    ln2 = line_of(code, ai)
                    reads.append((ln2, "enforce", "via local `%s`: %s" % (alias, anote)))
                    enforced.append((ln2, ai, anote))
                    alias_hit = True
                    break
                a_s, a_e = statement_of(code, ai)
                if _handed_to_enforcer(code, spans, a_s, a_e, ai):
                    ln2 = line_of(code, ai)
                    reads.append((ln2, "enforce",
                                  "via local `%s`, handed to a check-shaped callee" % alias))
                    enforced.append((ln2, ai, "handed to a check-shaped callee"))
                    alias_hit = True
                    break
        if alias_hit:
            continue
        if _handed_to_enforcer(code, spans, s, e, m.start()):
            reads.append((ln, "enforce", "handed to a check-shaped callee"))
            enforced.append((ln, m.start(), "handed to a check-shaped callee"))
            continue
        reads.append((ln, "map", direct))
    return reads, enforced


def _handed_to_enforcer(code, spans, s, e, i):
    """The value is an ARGUMENT to a check-shaped call in a method that rejects.

    The argument test has to be positional, not "the name appears somewhere earlier in the
    statement": `int cap = config.isAllowHighFee() ? 10_000 : config.getMaxFeeBps();` has a
    check-shaped call in it and the read is nowhere inside its parentheses. So walk back to
    the innermost UNCLOSED paren and require the callee to sit immediately before it.
    """
    depth, k, open_paren = 0, i, None
    while k > s:
        k -= 1
        c = code[k]
        if c == ")":
            depth += 1
        elif c == "(":
            if depth == 0:
                open_paren = k
                break
            depth -= 1
    if open_paren is None:
        return False
    prefix = code[s:open_paren + 1]
    m = ENFORCER.search(prefix)
    if not m or m.end() != len(prefix):
        return False
    ms, me = method_body(code, spans, i)
    return bool(REJECT.search(code[ms:me]))


# ---------------------------------------------------------------- arguments
root, only = ".", None
argv = sys.argv[1:]
k = 0
while k < len(argv):
    a = argv[k]
    if a in ("-h", "--help"):
        die(64, USAGE, ["everything: the gate never ran"])
    if a == "--root":
        if k + 1 >= len(argv):
            die(64, "* --root needs a directory\n" + USAGE, ["everything: the gate never ran"])
        root = argv[k + 1]
        k += 2
        continue
    if a == "--only":
        if k + 1 >= len(argv):
            die(64, "* --only needs a regex\n" + USAGE, ["everything: the gate never ran"])
        try:
            only = re.compile(argv[k + 1])
        except re.error as exc:
            die(64, "* --only %r is not a regex: %s\n%s" % (argv[k + 1], exc, USAGE),
                ["everything: the gate never ran"])
        k += 2
        continue
    die(64, "* unknown option %s\n%s" % (a, USAGE), ["everything: the gate never ran"])

src = os.path.join(root, SRC_ROOT)
if not os.path.isdir(src):
    die(2, "* %s not found - there is no backend tree to read (unavailable)" % src,
        ["every curated limit: no source tree existed to check them against"])

# ---------------------------------------------------------------- load the tree
files, code_by_path, spans_by_path = [], {}, {}
for dp, dn, fn in os.walk(src):
    dn[:] = [d for d in dn if d not in ("target", "build", ".git")]
    for name in sorted(fn):
        if not name.endswith(".java"):
            continue
        p = os.path.join(dp, name)
        try:
            raw = open(p, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError) as exc:
            die(2, "* %s could not be read as UTF-8 java (%s) - unavailable, NOT green" % (p, exc),
                ["every curated limit: one source file in the tree would not decode, so no "
                 "sweep of that tree is complete"])
        c = strip_noise(raw)
        if c is None:
            die(2, "* %s does not parse: unterminated comment or string literal - "
                   "unavailable, NOT green" % p,
                ["every curated limit: a file in the tree could not be tokenised"])
        sp = brace_spans(c)
        if sp is None:
            die(2, "* %s does not parse: braces do not balance - unavailable, NOT green" % p,
                ["every curated limit: a file in the tree could not be tokenised"])
        files.append(p)
        code_by_path[p] = c
        spans_by_path[p] = sp

if not files:
    die(2, "* %s contains no .java files - nothing to check (unavailable, never green)" % src,
        ["every curated limit: the source tree was empty"])

# ---------------------------------------------------------------- the curation must be live
selected = FIELDS
if only is not None:
    selected = [f for f in FIELDS
                if only.search("%s.%s" % (os.path.basename(f[0])[:-5], f[1]))
                or only.search(f[4])]
    if not selected:
        die(2, "* --only %s matched 0 curated fields - a filter that selects nothing is a "
               "broken assertion, not a passing one (unavailable)" % only.pattern,
            ["every curated limit: the scope filter selected none of them"])

missing = []
for ent, field, getter, shape, fid, why in selected:
    ep = os.path.join(src, ent.replace("/", os.sep))
    if ep not in code_by_path:
        missing.append("%s (entity file %s is not in the tree)" % (field, ent))
        continue
    decl = re.compile(r"private\s+[\w<>,.\[\]\s]+\s" + re.escape(field) + r"\s*[;=]")
    acc = re.compile(r"\b(?:public|protected)\s+[\w<>,.\[\]\s]+\s"
                     + re.escape(getter) + r"\s*\(\s*\)")
    if not decl.search(code_by_path[ep]):
        missing.append("%s.%s (field declaration gone from %s)"
                       % (os.path.basename(ent)[:-5], field, ent))
    elif not acc.search(code_by_path[ep]):
        missing.append("%s.%s (accessor %s() gone from %s)"
                       % (os.path.basename(ent)[:-5], field, getter, ent))

if missing:
    print("* the curated list no longer matches the code:")
    for x in missing:
        print("    %s" % x)
    die(2, "* a stale curation silently shrinks coverage, which is this ledger class wearing "
           "a gate's clothes - re-curate before trusting any verdict (unavailable, NOT green)",
        ["every curated limit: the list could not be resolved against the tree, so no field "
         "was actually proved"])

# ---------------------------------------------------------------- check
findings, proved = [], []
print("* unenforced-limit%s: %d curated limit field(s) across %d java files"
      % ((" [--only %s]" % only.pattern) if only else "", len(selected), len(files)))

for ent, field, getter, shape, fid, why in selected:
    label = "%s.%s" % (os.path.basename(ent)[:-5], field)
    all_reads, all_enf, agg_ok = [], [], []
    for p in files:
        reads, enf = scan_file(p, code_by_path[p], spans_by_path[p], getter, shape)
        rel = os.path.relpath(p, root).replace(os.sep, "/")
        for ln, kind, note in reads:
            all_reads.append((rel, ln, kind, note))
        for ln, off, note in enf:
            ms, me = method_body(code_by_path[p], spans_by_path[p], off)
            has_sum = bool(AGGREGATE.search(code_by_path[p][ms:me]))
            all_enf.append((rel, ln, note, has_sum))
            if has_sum:
                agg_ok.append((rel, ln))

    outside = [r for r in all_reads if r[2] != "echo"]
    if not all_reads:
        findings.append(
            (label, fid, "NO READ AT ALL: %s() is called nowhere in %s. The column is "
                         "written and serialised; nothing consumes it. [%s]"
             % (getter, SRC_ROOT.replace(os.sep, "/"), why), []))
        continue
    if not outside:
        findings.append(
            (label, fid, "ECHO ONLY: all %d read(s) of %s() are getter/mapper/DTO/repository "
                         "surface. The limit is serialised, never consulted. [%s]"
             % (len(all_reads), getter, why), all_reads))
        continue
    if not all_enf:
        findings.append(
            (label, fid, "NO ENFORCEMENT: %d read(s) outside the echo surface, none of them a "
                         "guard that orders the value and rejects on it. [%s]"
             % (len(outside), why), all_reads))
        continue
    if shape == "aggregate-guard" and not agg_ok:
        findings.append(
            (label, fid, "PER-ITEM ONLY: enforced at %s, but no enforcing method sums across "
                         "siblings, so the limit binds one row at a time and never the total. "
                         "[%s]" % (", ".join("%s:%d" % (r, l) for r, l, _, _ in all_enf), why),
             all_reads))
        continue
    where = ", ".join("%s:%d" % (r, l) for r, l, _, _ in all_enf)
    proved.append((label, fid, shape, where, len(all_reads)))

for label, fid, shape, where, nreads in proved:
    print("  OK    %-42s %-16s %d read(s); enforced at %s" % (label, "[%s]" % fid, nreads, where))
for label, fid, msg, reads in findings:
    print("  BROKE %-42s %-16s %s" % (label, "[%s]" % fid, msg))
    for rel, ln, kind, note in reads:
        print("          %-5s %s:%d  %s" % (kind, rel, ln, note))

if findings:
    print("VERDICT: broken - %d of %d curated limit(s) bound nothing (real findings above)"
          % (len(findings), len(selected)))
else:
    print("VERDICT: proved - all %d curated limit(s) are read by a guard that orders the "
          "value and rejects on it" % len(selected))
emit()
sys.exit(1 if findings else 0)
