#!/usr/bin/env python3
# ---------------------------------------------------------------------------------------
# gates/unenforced-limit.py
#
#   defect class : unenforced-limit
#   ledger ids   : F-0399, F-0400, F-0417, F-0418
#
# The shape, four times over: a limit/budget/cap is a real column, it is set, it is
# serialised back to the client -- and nothing anywhere ever reads it to say NO.
#
#   F-0399  Campaign.budgetMax        compared against ONE candidate offer
#                                     (DealService#validateProposalAmount) and never summed
#                                     across the campaign's other offers, so N creators can
#                                     each be offered the full budget.
#   F-0400  Campaign.maxCollaborators persisted, echoed by two mappers, read by no guard.
#   F-0417  Deliverable.revisionCount incremented forever; its only comparison is `> 0`,
#                                     which is a "has been revised" flag, not a ceiling.
#   F-0418  Deliverable.deadline      carried on the DTO; the only readers are reporting
#                                     filters -- nothing rejects or transitions on a miss.
#
# WHAT THIS GATE ASSERTS, for every limit-bearing entity field it can see:
#
#   1. the field is READ somewhere outside the entity / mapper / DTO / repository surface;
#   2. that read is a real ENFORCEMENT -- the value (directly, or through a local alias)
#      sits in an if/while condition that ORDERS it (`>` `<` `>=` `<=` `compareTo`
#      `isBefore` `isAfter`) and whose block REJECTS: throw / `return false` / status
#      transition / reject*/fail*/deny*/abort*. A block that only NOTIFIES (`notify*`,
#      `mark*`, `warn*`, `alert*`, `log.`) and then falls through is a warning, not a
#      ceiling -- it is reported as WARN_ONLY and FAILS (H-2);
#   3. a `!= null` test is NOT enforcement, and a comparison against the literal `0` is NOT
#      enforcement -- `getRevisionCount() > 0` says "has been revised", not "may not exceed
#      N" (compareTo's three-way pivot is exempt: there the bound is compareTo's argument);
#   4. for the fields that bound a SET (AGGREGATE_REQUIRED below), the OTHER SIDE of that
#      very comparison must be aggregate-derived -- a count/sum/running total, either
#      literally or through a local bound to one. Not "the method mentions the word total
#      somewhere". This is the F-0399 half: a comparison existed and the cap still bound
#      nothing, because nothing added the other rows up first (H-1);
#   5. when the limit is handed off to a callee (`enforceX(cap)`, or `boolean ok =
#      svc.check(cap)` rejected on), the CALLEE BODY is opened and must itself satisfy
#      2/3/4 on the parameter that received the limit. A callee is no longer taken at its
#      name, so an empty `enforceX(cap) {}` no longer greens the field: it reports
#      HANDOFF_UNPROVEN and FAILS (H-3);
#   6. field discovery is brace-depth based, not line-anchored, so `@Column(name = "x")
#      private Integer webhookMax;` on ONE line, `protected`/package-private fields, and
#      multi-declarator fields (`private Integer a, b;`) are all discovered (H-4).
#
# EXITS: 0 proved clean . 1 at least one unenforced limit . 2 cannot run / would be vacuous
#        . 64 usage. Exit 2 is UNAVAILABLE, which is not a pass. Zero candidate files or
#        zero candidate fields is exit 2, never 0.
#
# `--selftest` runs the falsify harness in-process: three BAD fixtures (unused `total`
# local, empty enforce-callee, warn-only guard) that USED to read as OK must now be
# findings, and five GOOD fixtures must still read as OK. It exits 1 if any assertion
# fails, so the harness cannot rot silently.
#
# usage: unenforced-limit.py [--root DIR] [--only REGEX] [--list] [--selftest]
# ---------------------------------------------------------------------------------------
import argparse
import bisect
import os
import re
import sys

CLASS = "unenforced-limit"
IDS = "F-0399, F-0400, F-0417, F-0418"
SRC_REL = os.path.join("influora-api", "src", "main", "java", "com", "influora")
ENTITY_REL = os.path.join(SRC_REL, "domain", "entity")

# --------------------------------------------------------------------------- vocabulary
# A field is a limit candidate when a camel/snake word of its name is one of these.
# Word-level (not substring) on purpose: substring matching pulls in `caption` (cap),
# `unlimitedUntil` (limit) and `discount` -- all noise.
LIMIT_WORDS = {"max", "limit", "limits", "cap", "caps", "quota", "seat", "seats", "budget",
               "ceiling", "allowance", "maximum", "threshold"}

ORDER_OPS = (">=", "<=", ">", "<")
ORDER_CALLS = ("compareTo", "isBefore", "isAfter", "isAfterOrEqual", "isBeforeOrEqual")

# The block guarded by the comparison has to actually STOP the caller. Control flow that
# refuses: an exception, a false verdict, a status transition, an explicit reject/fail.
REJECT_RE = re.compile(
    r"\bthrow\b|Exception\s*\(|\breturn\s+false\b|\bsetStatus\s*\("
    r"|\breject\w*\s*\(|\bfail\w*\s*\(|\bdeny\w*\s*\(|\babort\w*\s*\("
    r"|\bblock[A-Z]\w*\s*\(|\brefuse\w*\s*\("
)

# ...and control flow that does NOT. The verifier's third evasion was exactly this: a
# `if (d.getRevisionCount() > 3) { notifyOpsOfHeavyRework(d); }` followed by the write
# going through anyway. That is a warning, not a ceiling. It gets its own failing verdict
# rather than being silently ignored, so the distinction is visible in the output.
WARN_RE = re.compile(
    r"\bnotify\w*\s*\(|\bmark[A-Z]\w*\s*\(|\bwarn\w*\s*\(|\balert\w*\s*\("
    r"|\blog\s*\.|\brecordMetric\w*\s*\(|\bemit\w*\s*\("
)

# A callee whose NAME says it is the check ("hand the limit to something that enforces").
# Deliberately anchored at the START of a lower-case method name: the looser version of
# this regex green-lit `new DeliverableRequirementDto(..., d.getDeadline())` -- a DTO
# constructor carrying "Require" in the middle of its name -- and hid F-0418. Since H-3
# the name only nominates a callee for inspection; the BODY is what decides.
CHECK_CALLEE_RE = re.compile(
    r"^(?:check|enforce|validate|assert|require|ensure|guard|verify|reject|deny|allow"
    r"|within|exceed|consume|reserve|record)(?:[A-Z_]\w*)?$"
)

# Evidence that the OTHER SIDE of the comparison added the other rows up first.
# `(?<!ac)count` so that `accountId` / `account` do not read as an aggregate.
AGG_RE = re.compile(
    r"(?i)(?<!ac)count|\bsum\b|\.sum\s*\(|sumOf|\btotal|usage|\bused\b|spent|allocated"
    r"|committed|\.reduce\s*\(|\bsize\s*\(\s*\)|\btally|\bconsumed\b|\bredeemed\b"
)

RESERVED = {
    "return", "new", "if", "else", "throw", "throws", "this", "super", "assert", "import",
    "package", "class", "interface", "enum", "record", "case", "break", "continue",
    "default", "instanceof", "for", "while", "do", "switch", "try", "catch", "finally",
    "yield", "void",
}
NON_METHOD_HEADS = {"if", "while", "for", "switch", "catch", "synchronized", "try",
                    "do", "else", "return", "finally"}
MODIFIERS = ("public", "protected", "private", "final", "transient", "volatile", "static",
             "abstract", "native", "synchronized", "strictfp")

# --------------------------------------------------------------------------- curation
# Two curated lists. Both are EXISTENCE-CHECKED against the tree: if a curated field has
# been renamed or deleted, this gate exits 2 (stale curation shrinking coverage silently is
# this very defect class wearing a gate's clothes), never 0.

# Limits whose name does not carry a limit word, so auto-discovery cannot see them.
# (entity, field, why)
CURATED_EXTRA = [
    ("Deliverable", "revisionCount",
     "F-0417: a rework counter with no ceiling -- the brand can demand revisions forever"),
    ("Deliverable", "deadline",
     "F-0418: a time limit -- nothing rejects, transitions or notifies when it passes"),
]

# Limit-NAMED fields that are not caps on a set and carry no enforcement obligation.
# Each is existence-checked too, so a waiver cannot outlive the field it excuses.
WAIVED = {
    ("CreatorProfile", "rateMax"):
        "a creator's own asking-price band, shown and matched in search; bounds nothing",
    ("CreatorScore", "estimatedRateMax"):
        "a model-derived estimate rendered to the brand; bounds nothing",
    ("CampaignIntent", "proposedBudget"):
        "an AI intent proposal; the enforceable copy is Campaign.budgetMax, checked below",
    ("CampaignTemplate", "budgetMin"):
        "a template default copied into a Campaign; the Campaign copy is what binds",
    ("CampaignTemplate", "budgetMax"):
        "a template default copied into a Campaign; the Campaign copy is what binds",
    ("Subscription", "seatsPurchased"):
        "a deferred, not-yet-billed add-on record; Plan.seatLimit is the enforced cap",
}

# Limits that bound a SET, so a per-item comparison is not enough (rule 4).
AGGREGATE_REQUIRED = {
    ("Campaign", "budgetMax"):
        "F-0399: one budget spread across every offer on the campaign",
    ("Campaign", "maxCollaborators"):
        "F-0400: a headcount over the campaign's collaborators",
    ("Plan", "seatLimit"): "a headcount over the workspace's members + pending invites",
    ("Plan", "trackedCreatorLimit"): "a headcount over the workspace's tracked creators",
    ("Plan", "creatorAnalyticsMonthlyLimit"): "a count over the month's lookups",
    ("CouponCode", "usageLimit"): "a count over the coupon's redemptions",
}

NOT_CHECKED = [
    "limits enforced in SQL, in a Spring Data derived query name, by a DB CHECK constraint "
    "or by a Bean Validation annotation (@Max/@Size) -- no Java read exists to find",
    "whether an enforcement that EXISTS is CORRECT: off-by-one, inverted comparison, or a "
    "guard sitting behind a disabled feature flag all read as enforced here",
    "whether an aggregate sums the RIGHT rows -- rule 4 proves the comparison's other side "
    "is count/sum-derived, not that its query scope is the right parent",
    "indirection deeper than ONE hop: the callee of a handoff is now opened and must itself "
    "order the limit, but a limit reaching its comparison through a field, a returned "
    "value, or a second-level callee is still invisible and reads as unenforced",
    "callee resolution is by simple method NAME across main/: an overload set is satisfied "
    "if ANY same-arity definition of that name enforces, so a same-named enforcing method "
    "on an unrelated class can vouch for a non-enforcing one",
    "whether a proven guard is REACHED: an enforcing method nobody calls, or one called "
    "only on a branch that never runs, reads as enforced here",
    "KNOWN FALSE-POSITIVE SHAPES, all of them deliberate fail-closed choices: (a) a guard "
    "whose only action is a bare `return;` reads as UNENFORCED, because a silent skip is "
    "indistinguishable from a no-op; (b) a soft `mark*`/`notify*` transition that IS the "
    "intended ceiling reads as WARN_ONLY; (c) a handoff whose callee has no body in main/ "
    "(interface, external library, arity mismatch) reads as HANDOFF_UNPROVEN. Each of "
    "these FAILS rather than passes -- zero instances of any of them exist in this repo",
    "front-end-only enforcement (which is not enforcement) and test-source enforcement -- "
    "only main/ is read",
    "limit-bearing fields that live somewhere other than domain/entity, and limit words "
    "this gate's vocabulary does not carry",
]


def emit_not_checked():
    print("")
    print("NOT CHECKED by this gate:")
    for line in NOT_CHECKED:
        print("  - " + line)


def die(code, msg):
    print("ERROR: " + msg)
    emit_not_checked()
    sys.exit(code)


# --------------------------------------------------------------------------- java text
def scrub(src):
    """Blank out comments, string/char literals and text blocks, preserving offsets and
    newlines, so brace matching and token search never trip over `"}"` or `// if (x > y)`."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            out[i] = out[i + 1] = " "
            i += 2
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                out[i] = " "
                if i + 1 < n:
                    out[i + 1] = " "
                i += 2
        elif c == '"' and src[i:i + 3] == '"""':
            out[i] = out[i + 1] = out[i + 2] = " "
            i += 3
            while i < n and src[i:i + 3] != '"""':
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            for k in range(i, min(i + 3, n)):
                out[k] = " "
            i += 3
        elif c in ('"', "'"):
            quote = c
            out[i] = " "
            i += 1
            while i < n and src[i] != quote:
                if src[i] == "\\":
                    out[i] = " "
                    i += 1
                    if i < n and src[i] != "\n":
                        out[i] = " "
                    i += 1
                    continue
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                out[i] = " "
            i += 1
        else:
            i += 1
    return "".join(out)


def balanced(code, open_idx):
    """index just past the `)` matching the `(` at open_idx, or -1."""
    depth = 0
    for i in range(open_idx, len(code)):
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return i + 1
    return -1


def split_top(text, seps):
    """Split `text` on any of `seps`, but only at bracket depth 0."""
    parts, depth, last, i, n = [], 0, 0, 0, len(text)
    while i < n:
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0:
            for s in seps:
                if text.startswith(s, i):
                    parts.append(text[last:i])
                    i += len(s)
                    last = i
                    break
            else:
                i += 1
                continue
            continue
        i += 1
    parts.append(text[last:])
    return parts


def strip_annotations(seg):
    """Remove leading/embedded `@Foo` and `@Foo(...)` from a declaration segment."""
    out, i, n = [], 0, len(seg)
    while i < n:
        if seg[i] == "@":
            j = i + 1
            while j < n and (seg[j].isalnum() or seg[j] in "_."):
                j += 1
            k = j
            while k < n and seg[k].isspace():
                k += 1
            if k < n and seg[k] == "(":
                close = balanced(seg, k)
                i = close if close > 0 else n
            else:
                i = j
            out.append(" ")
            continue
        out.append(seg[i])
        i += 1
    return "".join(out)


class JavaFile(object):
    def __init__(self, path, rel, src=None):
        self.path = path
        self.rel = rel
        if src is None:
            with open(path, "r", encoding="utf-8", errors="strict") as fh:
                src = fh.read()
        self.src = src
        self.code = scrub(self.src)
        self.nl = [m.start() for m in re.finditer(r"\n", self.src)]
        self.methods = self._methods()

    def line_of(self, idx):
        return bisect.bisect_right(self.nl, idx) + 1

    def _methods(self):
        """(name, body_start, body_end, head) for every real `... (...) {` declaration.

        `if`/`while`/`for`/`catch`/... headers also end in `)`, so they are excluded by
        name -- otherwise `method_at()` returns a bare if-block as the 'enclosing method'
        and every guard written above the read becomes invisible."""
        spans, stack = [], []
        code = self.code
        for m in re.finditer(r"[{}]", code):
            if m.group(0) == "{":
                stack.append(m.start())
            else:
                if not stack:
                    raise ValueError("unbalanced braces in " + self.rel)
                spans.append((stack.pop(), m.start()))
        if stack:
            raise ValueError("unbalanced braces in " + self.rel)
        out = []
        for start, end in spans:
            head = code[max(0, start - 600):start]
            cut = max(head.rfind(";"), head.rfind("{"), head.rfind("}"))
            head = head[cut + 1:]
            if not re.search(r"\)\s*(?:throws\s+[\w.,\s]+)?\s*$", head):
                continue
            names = re.findall(r"([A-Za-z_]\w*)\s*\(", head)
            name = names[-1] if names else "?"
            if name in NON_METHOD_HEADS:
                continue
            out.append((name, start, end, head))
        out.sort(key=lambda t: t[1])
        return out

    def method_at(self, idx):
        best = None
        for name, s, e, head in self.methods:
            if s <= idx <= e and (best is None or s > best[1]):
                best = (name, s, e, head)
        return best


def params_of(head):
    """['workspaceId', 'metric', 'dedupKey', 'limit'] from a method header, or None."""
    h = re.sub(r"\bthrows\s+[\w.,\s]+$", "", head.strip()).strip()
    if not h.endswith(")"):
        return None
    depth, open_idx = 0, -1
    for i in range(len(h) - 1, -1, -1):
        if h[i] == ")":
            depth += 1
        elif h[i] == "(":
            depth -= 1
            if depth == 0:
                open_idx = i
                break
    if open_idx < 0:
        return None
    inner = h[open_idx + 1:-1].strip()
    if not inner:
        return []
    names = []
    for part in split_top(inner, [","]):
        p = strip_annotations(part).strip()
        p = re.sub(r"\.\.\.", " ", p)
        toks = re.findall(r"[A-Za-z_]\w*", re.sub(r"<[^<>]*>", " ", p))
        if not toks:
            return None
        names.append(toks[-1])
    return names


def guards(code, s, e):
    """(condition_text, block_text, cond_start_idx) for each if/while inside [s, e]."""
    out = []
    for m in re.finditer(r"\b(if|while)\s*\(", code[s:e]):
        op = s + m.end() - 1
        close = balanced(code, op)
        if close < 0:
            continue
        cond = code[op:close]
        rest = code[close:min(close + 4000, e)]
        stripped = rest.lstrip()
        pad = len(rest) - len(stripped)
        if stripped.startswith("{"):
            depth, block = 0, ""
            for i in range(close + pad, min(e + 1, len(code))):
                if code[i] == "{":
                    depth += 1
                elif code[i] == "}":
                    depth -= 1
                    if depth == 0:
                        block = code[close + pad:i + 1]
                        break
            if not block:
                block = rest
        else:
            block = stripped.split(";")[0] if ";" in stripped else stripped
        out.append((cond, block, op))
    return out


# --------------------------------------------------------------------- the analysis
def camel_words(name):
    parts = re.split(r"[_\W]+", name)
    words = []
    for p in parts:
        words += re.findall(r"[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+", p)
    return [w.lower() for w in words]


def _parse_field_segment(seg):
    """['webhookMax'] for a field declaration segment, [] for anything else.

    No `^[ \\t]*private` anchor (H-4): the segment is already known to be a member
    declaration by brace depth, so annotations may share the line, the modifier may be
    protected/package-private, and `private Integer a, b;` yields both names."""
    seg = strip_annotations(seg).strip()
    if not seg or "(" in seg.split("=")[0]:
        return []
    mods = set()
    while True:
        m = re.match(r"(" + "|".join(MODIFIERS) + r")\b\s*", seg)
        if not m:
            break
        mods.add(m.group(1))
        seg = seg[m.end():]
    if "static" in mods or "abstract" in mods:
        return []
    m = re.match(r"([A-Za-z_][\w.]*)\s*", seg)
    if not m or m.group(1) in RESERVED:
        return []
    rest = seg[m.end():]
    if rest.startswith("<"):
        depth = 0
        for i, ch in enumerate(rest):
            if ch == "<":
                depth += 1
            elif ch == ">":
                depth -= 1
                if depth == 0:
                    rest = rest[i + 1:]
                    break
        else:
            return []
    rest = re.sub(r"^\s*(?:\[\s*\]\s*)*", "", rest)
    if not rest or not (rest[0].isalpha() or rest[0] == "_"):
        return []
    names = []
    for part in split_top(rest, [","]):
        pm = re.match(r"\s*([A-Za-z_]\w*)\s*(?:\[\s*\])*\s*(?:=|$)", part)
        if not pm:
            return []
        names.append(pm.group(1))
    return names


def entity_fields(jf):
    """(field_name, line) for every instance field of every type in the file.

    Brace-depth walk instead of a line-anchored regex: a member declaration is a `;` that
    closes at a depth whose enclosing block is a TYPE body (a `{` whose header does not end
    in `)`), so method bodies and local variables are excluded structurally."""
    code = jf.code
    out, seen = [], set()
    kinds = []          # True == this open block is a type/initialiser body
    seg_start = 0
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if c == "{":
            head = code[max(0, seg_start):i]
            kinds.append(not re.search(r"\)\s*(?:throws\s+[\w.,\s]+)?\s*$", head.strip()))
            i += 1
            seg_start = i
        elif c == "}":
            if kinds:
                kinds.pop()
            i += 1
            seg_start = i
        elif c == ";":
            if kinds and kinds[-1]:
                seg = code[seg_start:i]
                for name in _parse_field_segment(seg):
                    m = re.search(r"\b" + re.escape(name) + r"\b", seg)
                    idx = seg_start + (m.start() if m else 0)
                    if name not in seen:
                        seen.add(name)
                        out.append((name, jf.line_of(idx)))
            i += 1
            seg_start = i
        else:
            i += 1
    return out


def getter_names(field):
    cap = field[0].upper() + field[1:]
    return ["get" + cap, "is" + cap, "has" + cap]


ZERO = ("0", "0L", "0.0", "0f", "0d", "BigDecimal.ZERO")


def expr_before(text, dot_idx):
    """The receiver expression that ends just before `text[dot_idx] == '.'`."""
    i = dot_idx - 1
    while i >= 0 and text[i].isspace():
        i -= 1
    end = i + 1
    while i >= 0:
        c = text[i]
        if c in ")]":
            opener = "(" if c == ")" else "["
            depth = 0
            while i >= 0:
                if text[i] == c:
                    depth += 1
                elif text[i] == opener:
                    depth -= 1
                    if depth == 0:
                        break
                i -= 1
            i -= 1
        elif c.isalnum() or c in "_.$":
            i -= 1
        else:
            break
        while i >= 0 and text[i] == " ":
            if i > 0 and (text[i - 1].isalnum() or text[i - 1] in "_)]$"):
                i -= 1
            else:
                break
    return text[i + 1:end].strip()


def comparison_sides(cond, token):
    """[(other_side_text, kind)] for every ordering comparison in `cond` where `token`
    sits on one side. kind is "op" for `>`/`<`/`>=`/`<=` and "call" for compareTo-family.

    This replaces the old token-shaped COMPARE_RE: the WHOLE opposite expression is
    returned, which is what rule 4 needs -- `alreadyCommitted.add(thisOffer)` rather than
    the literal `0` that the outer `> 0` would have handed back."""
    bare = token.rstrip("(")
    inner = cond
    if inner.startswith("("):
        inner = inner[1:]
        if inner.endswith(")"):
            inner = inner[:-1]
    sides = []
    for conj in split_top(inner, ["&&", "||"]):
        if bare not in conj:
            continue
        # compareTo / isBefore / isAfter: the bound is the other operand of the CALL.
        for call in ORDER_CALLS:
            for m in re.finditer(r"\.\s*" + call + r"\s*\(", conj):
                close = balanced(conj, conj.index("(", m.end() - 1))
                if close <= 0:
                    continue
                args = conj[conj.index("(", m.end() - 1) + 1:close - 1]
                recv = expr_before(conj, m.start())
                if bare in args:
                    sides.append((recv, "call"))
                elif bare in recv:
                    sides.append((args, "call"))
        # plain ordering operators at depth 0
        depth, i, n = 0, 0, len(conj)
        while i < n:
            c = conj[i]
            if c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
            elif depth == 0:
                if c in "<>" and i + 1 < n and conj[i + 1] in "<>=" and conj[i + 1] != "=":
                    i += 2          # >> / <<
                    continue
                if c == ">" and i > 0 and conj[i - 1] in "-=":
                    i += 1          # -> / >=  (>= handled by the branch below)
                    continue
                if c in "<>":
                    width = 2 if (i + 1 < n and conj[i + 1] == "=") else 1
                    lhs, rhs = conj[:i], conj[i + width:]
                    if bare in lhs and bare not in rhs:
                        sides.append((rhs.strip(), "op"))
                    elif bare in rhs and bare not in lhs:
                        sides.append((lhs.strip(), "op"))
                    i += width
                    continue
            i += 1
    return sides


def orders(cond, token):
    """The value must sit ON ONE SIDE of an ordering comparison against something other
    than the literal 0. `if (a > b && limit != null)` is NOT the limit being ordered, and
    `getRevisionCount() > 0` (F-0417) is a has-been-revised flag, not a ceiling."""
    for other, kind in comparison_sides(cond, token):
        if kind == "call":
            return True
        if other.strip() not in ZERO:
            return True
    return False


def contains_token(text, name):
    """`getFoo(` is matched as a substring; an alias identifier on word boundaries only,
    so a local called `cap` does not match `capacity`."""
    if name.endswith("("):
        return name in text
    return re.search(r"\b" + re.escape(name) + r"\b", text) is not None


def any_token(text, names):
    return any(contains_token(text, n) for n in names)


LOCAL_DECL_RE = re.compile(
    r"(?:^|[;{}])\s*(?:final\s+)?(?:[A-Za-z_][\w.<>\[\],]*(?:\s*<[^;{}]*>)?)\s+"
    r"([A-Za-z_]\w*)\s*=\s*([^;]*?);",
    re.S,
)


def local_bindings(body):
    """{local_name: rhs_text} for every simple local declaration in `body`."""
    out = {}
    for m in LOCAL_DECL_RE.finditer(body):
        out.setdefault(m.group(1), m.group(2))
    return out


def aliases_of(body, tokens):
    """local names bound to an expression that reads one of `tokens` (not as an argument)."""
    found = set()
    for name, rhs in local_bindings(body).items():
        if not any_token(rhs, tokens):
            continue
        if _read_is_an_argument(rhs, tokens):
            continue
        found.add(name)
    return found


def _read_is_an_argument(rhs, tokens):
    """True when the read sits INSIDE another call's parens: `svc.check(x, limit)` rather
    than `int limit = p.getSeatLimit();`."""
    for t in tokens:
        pat = re.escape(t) if t.endswith("(") else r"\b" + re.escape(t) + r"\b"
        for m in re.finditer(pat, rhs):
            before = rhs[:m.start()]
            if before.count("(") - before.count(")") > 0:
                return True
    return False


def verdict_locals(body, tokens):
    """`boolean ok = svc.check(..., limit);` -> {"ok"}  (delegated enforcement)."""
    found = set()
    for name, rhs in local_bindings(body).items():
        if any_token(rhs, tokens) and _read_is_an_argument(rhs, tokens):
            found.add(name)
    return found


def aggregate_backed(other, body):
    """Rule 4, tied to THIS comparison: the non-limit side must be count/sum-derived,
    either literally (`coupon.getUsageCount()`, `alreadyCommitted`) or through a local
    bound to one (`long activeMembers = repo.countByWorkspaceId(...)`).

    The old gate matched AGG_RE anywhere in the enclosing method body, so an unused
    `BigDecimal totalOffered = amount;` was enough to certify a per-item comparison as
    aggregate. It is not enough any more."""
    if not other:
        return False
    if AGG_RE.search(other):
        return True
    binds = local_bindings(body)
    for ident in set(re.findall(r"[A-Za-z_]\w*", other)):
        rhs = binds.get(ident)
        if rhs and AGG_RE.search(rhs):
            return True
    return False


def guard_site(jf, s, e, names, aggregate_required):
    """Rules 2/3/4 inside one method body.

    -> (kind, cond_idx, aggregated) where kind is "reject" (a real ceiling), "warn"
       (ordered, but the block only notifies/marks/logs and falls through) or None."""
    body = jf.code[s:e]
    best = None
    for cond, block, cidx in guards(jf.code, s, e):
        hit = [nm for nm in names if contains_token(cond, nm)]
        if not hit:
            continue
        ordered = [nm for nm in hit if orders(cond, nm)]
        if not ordered:
            continue
        agg = False
        if aggregate_required:
            for nm in ordered:
                for other, _kind in comparison_sides(cond, nm):
                    if aggregate_backed(other, body):
                        agg = True
                        break
                if agg:
                    break
        if REJECT_RE.search(block):
            if agg or not aggregate_required:
                return ("reject", cidx, agg)
            best = best or ("reject", cidx, False)
        elif WARN_RE.search(block) and best is None:
            best = ("warn", cidx, agg)
    return best if best else (None, None, False)


def handoff_calls(body, names):
    """[(callee_simple_name, args_text, call_idx, why)] -- calls that receive the limit and
    are plausibly the check: either the name says so, or the returned verdict is rejected
    on in this method. Constructors are excluded: a DTO whose class name merely contains a
    check verb (`DeliverableRequirementDto`) is serialisation, not enforcement."""
    verdicts = verdict_locals(body, names)
    out = []
    for m in re.finditer(r"(?:\bnew\s+)?([A-Za-z_][\w.]*)\s*\(", body):
        if m.group(0).lstrip().startswith("new"):
            continue
        simple = m.group(1).split(".")[-1]
        if not simple or not simple[0].islower() or simple in NON_METHOD_HEADS:
            continue
        close = balanced(body, m.end() - 1)
        if close < 0:
            continue
        args = body[m.end():close - 1]
        if not any_token(args, names):
            continue
        why = None
        if CHECK_CALLEE_RE.match(simple):
            why = "name"
        else:
            head = body[:m.start()]
            dm = re.search(r"([A-Za-z_]\w*)\s*=\s*$", head.rstrip())
            if dm and dm.group(1) in verdicts:
                why = "verdict"
            else:
                for v in verdicts:
                    if re.search(r"\b" + re.escape(v) + r"\b\s*=\s*[^;]*$", head, re.S):
                        why = "verdict"
                        break
        if why:
            out.append((simple, args, m.start(), why))
    return out


def prove_callee(callee, args, defs, names, getter_tokens, aggregate_required):
    """H-3: open the callee and require IT to order the limit and reject.

    -> (rel, line, method_name, aggregated) or None. Taking `enforceX(cap)` at its name is
    exactly how an EMPTY `private void enforceTrackedCreatorCap(Integer cap) {}` greened a
    field, so a name alone proves nothing now."""
    arg_parts = split_top(args, [","])
    idx = None
    for i, part in enumerate(arg_parts):
        if any_token(part, names):
            idx = i
            break
    if idx is None:
        return None
    for jf, mname, s, e, head in defs.get(callee, []):
        params = params_of(head)
        if params is None or idx >= len(params):
            continue
        if len(params) != len(arg_parts):
            continue
        tokens = [params[idx]] + [t + "(" for t in getter_tokens]
        body = jf.code[s:e]
        tokens = sorted(set(tokens) | aliases_of(body, [params[idx]]))
        kind, cidx, agg = guard_site(jf, s, e, tokens, aggregate_required)
        if kind == "reject" and (agg or not aggregate_required):
            return (jf.rel, jf.line_of(cidx), mname, agg)
    return None


IGNORE_READ_DIRS = (
    os.path.join("web", "dto"),
    os.path.join("domain", "entity"),
    "repository",
)


def is_serialisation_only(rel):
    base = os.path.basename(rel)
    if base.endswith("Mapper.java") or base.endswith("Dto.java") or base.endswith("Dtos.java"):
        return True
    return any(d.replace("\\", "/") in rel.replace("\\", "/") for d in IGNORE_READ_DIRS)


def build_defs(files):
    """simple method name -> [(JavaFile, name, body_start, body_end, head)]"""
    defs = {}
    for jf in files:
        for name, s, e, head in jf.methods:
            defs.setdefault(name, []).append((jf, name, s, e, head))
    return defs


def analyse(field_key, decl, tokens, files, defs):
    """-> (verdict, evidence[list of str])

    EVERY enforcement site is collected, not just the first: judging the aggregate rule on
    the first site alone made a summing guard added in a second service invisible, and the
    gate kept reporting a fixed field as PER_ITEM_ONLY."""
    aggregate_required = field_key in AGGREGATE_REQUIRED
    reads = []            # (rel, line, serialisation_only)
    sites = []            # (rel, line, method, how, aggregated)
    warns = []            # (rel, line, method)
    unproven = []         # (rel, line, method, callee)
    for jf in files:
        if os.path.basename(jf.rel) == decl["entity_file"]:
            continue
        hit_idxs = []
        for t in tokens:
            for m in re.finditer(r"\b" + re.escape(t) + r"\s*\(\s*\)", jf.code):
                hit_idxs.append(m.start())
        if not hit_idxs:
            continue
        for ln in sorted(set(jf.line_of(i) for i in hit_idxs)):
            reads.append((jf.rel, ln, is_serialisation_only(jf.rel)))
        if is_serialisation_only(jf.rel):
            continue
        for idx in sorted(set(hit_idxs)):
            meth = jf.method_at(idx)
            if meth is None:
                continue
            name, s, e, _head = meth
            body = jf.code[s:e]
            call_tokens = [t + "(" for t in tokens]
            names = sorted(set(call_tokens) | aliases_of(body, call_tokens))
            found = None
            # E1 -- the value, or a local alias of it, orders inside a REJECTING guard
            kind, cidx, agg = guard_site(jf, s, e, names, aggregate_required)
            if kind == "reject":
                found = (jf.rel, jf.line_of(cidx), name, "guard", agg)
            elif kind == "warn":
                warns.append((jf.rel, jf.line_of(cidx), name))
            # E2/E3 -- handed to a callee; the CALLEE BODY has to do the ordering
            if found is None:
                for callee, args, cidx2, why in handoff_calls(body, names):
                    proof = prove_callee(callee, args, defs, names, tokens,
                                         aggregate_required)
                    if proof:
                        found = (proof[0], proof[1], proof[2],
                                 "handoff:%s(%s)" % (callee, why), proof[3])
                        break
                    unproven.append((jf.rel, jf.line_of(cidx2), name, callee))
            if found and not any(s2[:3] == found[:3] for s2 in sites):
                sites.append(found)

    real_reads = [r for r in reads if not r[2]]
    if not reads:
        return "UNREAD", ["no read of " + "/".join(t + "()" for t in tokens) + " anywhere in main/"]
    if not real_reads:
        return "ECHO_ONLY", [
            "read only by the serialisation surface: "
            + ", ".join("%s:%d" % (r[0], r[1]) for r in reads[:6])
        ]

    def where(site):
        return "%s:%d in %s() [%s]" % (site[0], site[1], site[2], site[3])

    if not sites:
        if warns:
            return "WARN_ONLY", [
                "ordered, but the guarded block only notifies/marks/logs and falls "
                "through -- a warning is not a ceiling: "
                + ", ".join("%s:%d in %s()" % w for w in warns[:5]),
                "read %d time(s) outside mapper/DTO/repository" % len(real_reads),
            ]
        if unproven:
            return "HANDOFF_UNPROVEN", [
                "handed to a check-shaped callee whose BODY never orders the limit "
                "(an empty or non-comparing enforce* proves nothing): "
                + ", ".join("%s:%d in %s() -> %s()" % u for u in unproven[:5]),
                "read %d time(s) outside mapper/DTO/repository" % len(real_reads),
            ]
        return "UNENFORCED", [
            "read %d time(s) outside mapper/DTO/repository, none of them a rejecting "
            "comparison: " % len(real_reads)
            + ", ".join("%s:%d" % (r[0], r[1]) for r in real_reads[:6])
        ]

    if aggregate_required:
        summing = [s2 for s2 in sites if s2[4]]
        if summing:
            return "OK", ["enforced at " + where(summing[0]),
                          "the comparison's other side is count/sum-derived inside %s()"
                          % summing[0][2]]
        return "PER_ITEM_ONLY", [
            "enforced at " + where(sites[0]),
            "%s() compares ONE candidate value against the cap and the other side of that "
            "comparison is not count/sum-derived -- %s"
            % (sites[0][2], AGGREGATE_REQUIRED[field_key]),
        ] + (["other comparison sites, none of them summing: "
              + ", ".join(where(s2) for s2 in sites[1:5])] if len(sites) > 1 else [])
    return "OK", ["enforced at " + where(sites[0])]


# --------------------------------------------------------------------------- selftest
SELFTEST_ENTITY = """
package com.influora.domain.entity;
public class Plan {
    @Column(name = "tracked_creator_limit") private Integer trackedCreatorLimit;
    protected Integer protectedSeatLimit;
    Integer packagePrivateQuota;
    private Integer inlineCapA, inlineCapB;
    private static final int NOT_A_FIELD = 3;
    public Integer getTrackedCreatorLimit() { return trackedCreatorLimit; }
    public void unrelated() { int localMax = 7; if (localMax > 3) { throw new X(); } }
}
"""
SELFTEST_DISCOVERY_WANT = ["trackedCreatorLimit", "protectedSeatLimit", "packagePrivateQuota",
                           "inlineCapA", "inlineCapB"]
SELFTEST_DISCOVERY_NOT = ["NOT_A_FIELD", "localMax"]

# BAD1 -- the verifier's evasion #1: an unused local whose NAME contains "total" used to
# satisfy AGG_RE anywhere-in-the-body, certifying a per-item comparison as an aggregate.
BAD1 = """
package com.influora.service;
public class DealService {
    private void validateProposalAmount(Campaign campaign, BigDecimal amount) {
        BigDecimal totalOffered = amount;
        if (campaign.getTrackedCreatorLimit() != null
                && amount.compareTo(campaign.getTrackedCreatorLimit()) > 0) {
            throw new ApiException("AMOUNT_EXCEEDS_BUDGET", "nope", HttpStatus.BAD_REQUEST);
        }
    }
}
"""

# BAD2 -- the verifier's evasion #2: an EMPTY check-shaped callee.
BAD2 = """
package com.influora.service;
public class TrackingService {
    public void track(Plan plan) {
        long totalTracked = 0;
        enforceTrackedCreatorCap(plan.getTrackedCreatorLimit());
    }
    private void enforceTrackedCreatorCap(Integer cap) {}
}
"""

# BAD3 -- the verifier's evasion #3: a warn-only guard that falls through.
BAD3 = """
package com.influora.service;
public class ReworkService {
    public void apply(Plan plan, Deliverable d) {
        if (plan.getTrackedCreatorLimit() > 3) {
            notifyOpsOfHeavyRework(d);
        }
        d.applyRevision();
    }
}
"""

# GOOD1 -- a real aggregate ceiling.
GOOD1 = """
package com.influora.service;
public class SeatService {
    private void enforce(Plan plan, String workspaceId) {
        long activeMembers = memberRepository.countByWorkspaceIdAndActiveTrue(workspaceId);
        if (activeMembers >= plan.getTrackedCreatorLimit()) {
            throw new ApiException("UPGRADE_REQUIRED", "full", HttpStatus.PAYMENT_REQUIRED);
        }
    }
}
"""

# GOOD2 -- a real handoff: the callee body orders the parameter and returns false.
GOOD2 = """
package com.influora.service;
public class UsageService {
    public boolean gate(Plan plan, String workspaceId) {
        boolean allowed = recordCreatorLookup(workspaceId, plan.getTrackedCreatorLimit());
        if (!allowed) {
            throw new ApiException("UPGRADE_REQUIRED", "over", HttpStatus.PAYMENT_REQUIRED);
        }
        return allowed;
    }
    public boolean recordCreatorLookup(String workspaceId, int limit) {
        int used = getUsageForPeriod(workspaceId);
        if (used >= limit) {
            return false;
        }
        return true;
    }
}
"""

# GOOD3 -- compareTo with an aggregate receiver (the shape of the real budgetMax fix).
GOOD3 = """
package com.influora.service;
public class BudgetService {
    private void requireWithinRemainingBudget(Plan plan, BigDecimal thisOffer) {
        BigDecimal alreadyCommitted = rows.stream().map(Row::getRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (alreadyCommitted.add(thisOffer).compareTo(plan.getTrackedCreatorLimit()) > 0) {
            throw new ApiException("AMOUNT_EXCEEDS_BUDGET", "nope", HttpStatus.BAD_REQUEST);
        }
    }
}
"""


def _mk(name, src):
    return JavaFile(name, "selftest/" + name, src)


def selftest():
    ent = _mk("Plan.java", SELFTEST_ENTITY)
    key = ("Plan", "trackedCreatorLimit")
    toks = getter_names("trackedCreatorLimit")
    decl = {"entity_file": "Plan.java"}
    cases = [
        ("BAD1 unused-total local certifies a per-item compare", BAD1, "PER_ITEM_ONLY"),
        ("BAD2 empty enforce* callee", BAD2, "HANDOFF_UNPROVEN"),
        ("BAD3 warn-only guard falls through", BAD3, "WARN_ONLY"),
        ("GOOD1 counted aggregate + throw", GOOD1, "OK"),
        ("GOOD2 handoff whose callee orders + returns false", GOOD2, "OK"),
        ("GOOD3 compareTo against a reduced total", GOOD3, "OK"),
    ]
    # H-4 discovery: same-line @Column, protected, package-private, multi-declarator --
    # and NOT a static constant or a method-local.
    names = [f for f, _ln in entity_fields(ent)]
    failures = []
    print("selftest: field discovery (same-line annotation / protected / package-private /")
    print("          multi-declarator, without picking up statics or method locals)")
    for want in SELFTEST_DISCOVERY_WANT:
        if want not in names:
            failures.append("H-4 discovery: %s NOT found" % want)
    for nope in SELFTEST_DISCOVERY_NOT:
        if nope in names:
            failures.append("H-4 discovery: %s wrongly reported as a field" % nope)
    print("  %s discovered %s" % ("ok  " if not failures else "FAIL", names))
    for label, src, expect in cases:
        f = _mk("Case.java", src)
        verdict, ev = analyse(key, decl, toks, [ent, f], build_defs([ent, f]))
        ok = verdict == expect
        print("  %s %-52s expect %-16s got %s"
              % ("ok  " if ok else "FAIL", label, expect, verdict))
        if not ok:
            failures.append("%s: expected %s, got %s (%s)" % (label, expect, verdict, ev))
    print("")
    if failures:
        for f in failures:
            print("SELFTEST FAILURE: " + f)
        print("RESULT: selftest FAILED (%d)" % len(failures))
        emit_not_checked()
        sys.exit(1)
    print("RESULT: selftest passed -- 3 known evasions fail, 3 real enforcements pass")
    emit_not_checked()
    sys.exit(0)


# --------------------------------------------------------------------------- driver
def find_root(explicit):
    if explicit:
        return os.path.abspath(explicit)
    here = os.path.dirname(os.path.abspath(__file__))
    cur = here
    while True:
        if os.path.isdir(os.path.join(cur, SRC_REL)):
            return cur
        nxt = os.path.dirname(cur)
        if nxt == cur:
            return os.path.abspath(os.path.join(here, "..", ".."))
        cur = nxt


def main():
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--root")
    ap.add_argument("--only")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("-h", "--help", action="store_true")
    try:
        args, extra = ap.parse_known_args()
    except SystemExit:
        print("usage: unenforced-limit.py [--root DIR] [--only REGEX] [--list] [--selftest]")
        sys.exit(64)
    if args.help or extra:
        print("usage: unenforced-limit.py [--root DIR] [--only REGEX] [--list] [--selftest]")
        if extra:
            print("unknown option(s): " + " ".join(extra))
            sys.exit(64)
        sys.exit(0)

    print("gate: %s  (class `%s`, ledger %s)" % (os.path.basename(__file__), CLASS, IDS))
    if args.selftest:
        selftest()

    root = find_root(args.root)
    src = os.path.join(root, SRC_REL)
    ent = os.path.join(root, ENTITY_REL)
    print("root: " + root)
    if not os.path.isdir(src):
        die(2, "no java source tree at " + src + " -- cannot check, which is not a pass")
    if not os.path.isdir(ent):
        die(2, "no entity package at " + ent + " -- cannot discover limit fields")

    files, entities = [], {}
    for dirpath, _dirs, names in os.walk(src):
        for n in names:
            if not n.endswith(".java"):
                continue
            p = os.path.join(dirpath, n)
            rel = os.path.relpath(p, root).replace("\\", "/")
            try:
                jf = JavaFile(p, rel)
            except (UnicodeDecodeError, ValueError, OSError) as exc:
                die(2, "cannot parse %s (%s) -- refusing to report a partial tree as clean"
                    % (rel, exc))
            files.append(jf)
            if os.path.dirname(p) == ent:
                entities[n[:-5]] = jf
    if not files:
        die(2, "scanned ZERO java files -- vacuous, refusing to pass")
    if not entities:
        die(2, "scanned ZERO entity files -- vacuous, refusing to pass")

    # ---- candidate set: auto-discovery + curated extras - waivers
    field_index = {}
    for ename, jf in entities.items():
        for fname, line in entity_fields(jf):
            field_index[(ename, fname)] = (jf, line)

    for key in [(e, f) for e, f, _ in CURATED_EXTRA] + list(WAIVED):
        if key not in field_index:
            die(2, "curated field %s.%s no longer exists -- stale curation silently shrinks "
                   "coverage, which is this defect class wearing a gate's clothes"
                % key)
    for key in AGGREGATE_REQUIRED:
        if key not in field_index:
            die(2, "aggregate-required field %s.%s no longer exists -- stale curation" % key)

    candidates = []
    auto = 0
    for (ename, fname), (jf, line) in sorted(field_index.items()):
        if (ename, fname) in WAIVED:
            continue
        if set(camel_words(fname)) & LIMIT_WORDS:
            candidates.append((ename, fname, jf, line, "auto"))
            auto += 1
    for ename, fname, _why in CURATED_EXTRA:
        jf, line = field_index[(ename, fname)]
        candidates.append((ename, fname, jf, line, "curated"))
    candidates.sort()

    if args.only:
        rx = re.compile(args.only)
        candidates = [c for c in candidates if rx.search(c[0] + "." + c[1])]

    print("scanned: %d java files, %d entities, %d entity fields"
          % (len(files), len(entities), len(field_index)))
    print("candidates: %d limit-bearing fields (%d auto-discovered, %d curated, %d waived)"
          % (len(candidates), auto, len(CURATED_EXTRA), len(WAIVED)))
    if not candidates:
        die(2, "ZERO candidate limit fields -- a detector that matches nothing is the "
               "failure mode this gate exists to prevent, so this is unavailable, not clean")

    if args.list:
        for ename, fname, _jf, line, kind in candidates:
            print("  %-12s %s.%s (entity line %d)" % (kind, ename, fname, line))
        emit_not_checked()
        sys.exit(0)

    defs = build_defs(files)
    findings, oks = [], []
    for ename, fname, jf, line, kind in candidates:
        decl = {"entity_file": os.path.basename(jf.path)}
        verdict, ev = analyse((ename, fname), decl, getter_names(fname), files, defs)
        rel_line = "%s:%d" % (jf.rel, line)
        if verdict == "OK":
            oks.append((ename, fname, rel_line, ev))
        else:
            findings.append((verdict, ename, fname, rel_line, ev))

    print("")
    for verdict, ename, fname, where, ev in findings:
        print("FINDING [%s] %s  %s.%s" % (verdict, where, ename, fname))
        for e in ev:
            print("         " + e)
    if oks:
        print("")
        print("enforced (%d):" % len(oks))
        for ename, fname, where, ev in oks:
            print("  OK  %s.%s  %s -- %s" % (ename, fname, where, ev[0]))

    print("")
    print("RESULT: %d unenforced limit(s) across %d candidate field(s) in %d files"
          % (len(findings), len(candidates), len(files)))
    emit_not_checked()
    sys.exit(1 if findings else 0)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 - never green on a crash
        print("ERROR: gate crashed: %r" % (exc,))
        emit_not_checked()
        sys.exit(2)
