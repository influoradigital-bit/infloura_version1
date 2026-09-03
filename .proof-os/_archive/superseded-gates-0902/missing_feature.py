#!/usr/bin/env python3
"""gates/missing_feature.py -- gate for ledger class `missing-feature` (F-0403, F-0413, F-0414).

The shape that recurred three times: an entity DECLARES a capability that no code path
can ever exercise, and every existing gate passes because the declaration itself is
well-formed.

    F-0403  Contract.setStatus(..) has zero non-test call sites, so ContractStatus
            .CANCELLED and .COMPLETED can never be reached -- no contract can be
            cancelled or completed. CANCELLED *appears* in main source, but only inside
            `existsByCollaborationIdAndStatusNot(.., ContractStatus.CANCELLED)`: a READ.
            A gate that greps for the constant greens this. A gate that asks who WRITES
            it does not.
    F-0413  Contract.Builder#expirationDate(..) exists, the column exists, and nobody
            ever calls the builder method -- expiration_date is NULL for every row.
    F-0414  ContractController exposes no amend route, so Contract#version and the
            DRAFT status are decoration.

javac is happy with all three. The entity compiles, the enum compiles, the tests that
construct the entity by hand compile. The product is missing the feature anyway.

WHAT IS ASSERTED, for a curated list of lifecycle/money entities:

  1. every constant of every status enum held by those entities is WRITTEN
     (assigned, or passed to a setter/builder) by at least one NON-TEST call site;
  2. every public mutator declared on those entities -- including the nested Builder's
     methods, which is where F-0413 lived -- has at least one NON-TEST caller.

A write site inside a test is reported as a finding, not as proof: a status only a test
can produce is a status the product cannot produce.

LAW (false-red / false-green):
  exit 0  proved
  exit 1  real findings, every one printed
  exit 2  unavailable -- missing tree, unreadable/unparseable file, or ZERO inputs.
          "I checked nothing, therefore it passed" is the bug this class exists for,
          so an empty curated set, an enum with no constants, and an --only filter
          that selects nothing are all 2, never 0.
  exit 64 usage error

Usage: gates/missing_feature.py [repo_root] [--only REGEX] [--list]
       --only REGEX   scope to matching subjects ("Contract.CANCELLED", "Contract#setStatus")
       --list         print every subject examined, not only the failures
"""
import os as _o
import sys as _s

_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init

    rc_init("missing_feature")  # F-0026: liveness is read, not inferred
except Exception:
    pass

import os
import re
import sys

USAGE = ("usage: gates/missing_feature.py [repo_root] [--only REGEX] [--list]")

ENTITY_DIR = "influora-api/src/main/java/com/influora/domain/entity"
ENUM_DIR = "influora-api/src/main/java/com/influora/domain/enums"
MAIN_ROOT = "influora-api/src/main/java"
TEST_ROOT = "influora-api/src/test/java"

# The curated list. Lifecycle + money entities, where a status nobody can write or a
# mutator nobody can call is a missing product feature rather than dead ornament.
# Deliberately NOT "every entity": a curated list that is actually verified beats a
# sweep that gets muted.
CURATED = [
    "Campaign",
    "Collaboration",
    "Contract",
    "Deliverable",
    "Dispute",
    "EscrowHold",
    "Invoice",
    "PaymentMilestone",
    "Payout",
    "Shipment",
    "Subscription",
    "SupportTicket",
    "WalletTopUp",
]

# Object plumbing and read accessors are not mutators.
NOT_A_MUTATOR = {"equals", "hashCode", "toString", "compareTo", "clone", "builder"}
GETTER = re.compile(r"^(get|is|has)[A-Z0-9_]")

# A call whose argument is a status constant WRITES it only if the callee is a
# setter/builder/transition. `existsByStatusNot(ContractStatus.CANCELLED)` is a query --
# counting that as a write is exactly how F-0403 stayed invisible for three cycles.
WRITE_CALLEE = re.compile(
    r"^(set[A-Z0-9_]\w*|with[A-Z0-9_]\w*|mark[A-Z0-9_]\w*|record[A-Z0-9_]\w*"
    r"|transition\w*|advance\w*|status|state|updateStatus|changeStatus|moveTo)$"
)

BLIND = [
    "an enum constant referenced WITHOUT its type qualifier -- a static import, or a "
    "bare `case DRAFT:` label -- is invisible to this gate; a value written only that "
    "way is reported as unwritten (false finding), and one READ only that way is not "
    "counted as a read",
    "callers are matched by METHOD NAME within files that MENTION the entity type -- "
    "never by a resolved receiver type -- so a same-named method on another class used "
    "in one of those files still satisfies the caller check; this gate UNDER-reports "
    "dead mutators, and conversely a caller reaching the entity only through `var` plus "
    "an inferred generic, with the type name never written, would be missed",
    "a caller that is itself unreachable still counts as a caller -- reachability is "
    "checked one hop deep, not transitively from an HTTP route (F-0414's 'no amend "
    "route at all' is NOT detected here; see endpoint_reachability.py)",
    "`return E.CONST` is counted as a write without proving the returned value is ever "
    "persisted",
    "a constant written through Enum.valueOf(), JSON/JPA deserialisation of a request "
    "DTO, a positional constructor argument, reflection, or raw SQL/Flyway is not seen; "
    "occurrences of valueOf() are named on the affected findings so a reviewer can judge",
    "whether a status that IS written is written on the CORRECT transition, and whether "
    "the entity's column is actually persisted by a repository save",
]


def emit(extra=()):
    """The NOT CHECKED line. Printed on EVERY exit path, success included."""
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ------------------------------------------------------------------ arguments
root = None
only = None
show_all = False
argv = sys.argv[1:]
i = 0
while i < len(argv):
    a = argv[i]
    if a == "--only":
        if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
            die(64, "* --only needs a regex\n" + USAGE, ["everything: the gate never ran"])
        try:
            only = re.compile(argv[i + 1])
        except re.error as e:
            die(64, "* --only %r is not a valid regex: %s\n%s" % (argv[i + 1], e, USAGE),
                ["everything: the gate never ran"])
        i += 2
        continue
    if a == "--list":
        show_all = True
        i += 1
        continue
    if a.startswith("--"):
        die(64, "* unknown option %s\n%s" % (a, USAGE), ["everything: the gate never ran"])
    if root is not None:
        die(64, "* at most one repo root\n" + USAGE, ["everything: the gate never ran"])
    root = a
    i += 1
root = root or "."

if not os.path.isdir(root):
    die(2, "* repo root %s does not exist -- unavailable" % root,
        ["every entity: there was no tree to read"])

ENTITY_DIR = os.path.join(root, ENTITY_DIR)
ENUM_DIR = os.path.join(root, ENUM_DIR)
MAIN_ROOT = os.path.join(root, MAIN_ROOT)
TEST_ROOT = os.path.join(root, TEST_ROOT)

for label, d in (("entity", ENTITY_DIR), ("enum", ENUM_DIR), ("main source", MAIN_ROOT)):
    if not os.path.isdir(d):
        die(2, "* %s tree %s not found -- unavailable" % (label, d),
            ["every entity and every status value: there was no %s tree to read" % label])


# ------------------------------------------------------------------ java lexing
def strip(src):
    """Blank out comments and string/char literals, preserving offsets and newlines.

    Without this, a javadoc line naming ContractStatus.CANCELLED counts as a write and
    the gate certifies a dead status. Prose is not a call site.
    """
    out = list(src)
    n = len(src)
    i = 0
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
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
            continue
        if c in "\"'":
            q = c
            j = i + 1
            # Java text blocks: \"\"\" ... \"\"\"
            if q == '"' and src[i:i + 3] == '"""':
                out[i] = out[i + 1] = out[i + 2] = " "
                j = i + 3
                while j < n and src[j:j + 3] != '"""':
                    if src[j] != "\n":
                        out[j] = " "
                    j += 1
                for k in range(j, min(j + 3, n)):
                    out[k] = " "
                i = j + 3
                continue
            out[i] = " "
            while j < n and src[j] != q:
                if src[j] == "\\":
                    if src[j] != "\n":
                        out[j] = " "
                    j += 1
                    if j < n and src[j] != "\n":
                        out[j] = " "
                    j += 1
                    continue
                if src[j] != "\n":
                    out[j] = " "
                j += 1
            if j < n:
                out[j] = " "
            i = j + 1
            continue
        i += 1
    return "".join(out)


def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return None


def lineno(src, pos):
    return src.count("\n", 0, pos) + 1


def rel(p):
    try:
        return os.path.relpath(p, root).replace("\\", "/")
    except ValueError:
        return p.replace("\\", "/")


# ------------------------------------------------------------------ enum parsing
def enum_constants(name):
    """['DRAFT', ...] | None if the file is missing or yields nothing parseable."""
    path = os.path.join(ENUM_DIR, name + ".java")
    src = read(path)
    if src is None:
        return None
    s = strip(src)
    m = re.search(r"\benum\s+" + re.escape(name) + r"\b[^{]*\{", s)
    if not m:
        return None
    body = s[m.end():]
    consts, buf, depth = [], [], 0
    for ch in body:
        if ch in "({[":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif ch == "}":
            if depth == 0:
                break
            depth -= 1
        if depth == 0 and ch in ",;":
            consts.append("".join(buf))
            if ch == ";":
                buf = []
                break
            buf = []
            continue
        buf.append(ch)
    else:
        consts.append("".join(buf))
    if buf:
        consts.append("".join(buf))
    out = []
    for chunk in consts:
        mm = re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*([A-Z][A-Za-z0-9_]*)\s*$", chunk.split("(")[0])
        if mm and mm.group(1) not in out:
            out.append(mm.group(1))
    return out or None


# ------------------------------------------------------------------ entity parsing
FIELD = re.compile(r"private\s+(?:final\s+)?([A-Z][A-Za-z0-9_]*)\s+([a-z][A-Za-z0-9_]*)\s*[;=]")
METHOD = re.compile(
    r"^[ \t]*public\s+(?!class\b|interface\b|enum\b|record\b)"
    r"(?:(static)\s+)?(?:final\s+)?"
    r"(?:<[^>]{0,200}>\s*)?"
    r"([A-Za-z_][A-Za-z0-9_.<>,\[\] ]*?)\s+"
    r"([a-zA-Z_]\w*)\s*\(([^)]*)\)",
    re.M,
)


def entity_facts(name):
    """(enum_fields, mutators) | 'absent' | None when present but unparseable."""
    path = os.path.join(ENTITY_DIR, name + ".java")
    if not os.path.isfile(path):
        return "absent"
    src = read(path)
    if src is None:
        return None
    s = strip(src)
    fields = []
    for m in FIELD.finditer(s):
        fields.append((m.group(1), m.group(2)))
    mutators = []
    for m in METHOD.finditer(s):
        static, ret, meth, args = m.group(1), m.group(2).strip(), m.group(3), m.group(4)
        if static:
            continue
        if meth in NOT_A_MUTATOR:
            continue
        if GETTER.match(meth) and not args.strip():
            continue
        if ret in ("class", "interface", "enum", "record"):
            continue
        mutators.append((meth, lineno(s, m.start())))
    if not fields and not mutators:
        return None
    return fields, mutators


# ------------------------------------------------------------------ source corpus
def walk(rootdir):
    out = []
    for dp, dn, fn in os.walk(rootdir):
        dn[:] = [d for d in dn if d not in ("target", "build", "node_modules", ".git")]
        for f in fn:
            if f.endswith(".java"):
                out.append(os.path.join(dp, f))
    return sorted(out)


main_files = walk(MAIN_ROOT)
test_files = walk(TEST_ROOT) if os.path.isdir(TEST_ROOT) else []
if not main_files:
    die(2, "* no .java files under %s -- unavailable" % MAIN_ROOT,
        ["every entity and every status value: the main source tree was empty"])

CORPUS = {}  # path -> stripped source
for p in main_files + test_files:
    src = read(p)
    if src is None:
        die(2, "* %s could not be read -- unavailable, refusing to certify a tree this "
               "gate could not fully see" % rel(p),
            ["every entity and every status value: one source file was unreadable"])
    CORPUS[p] = strip(src)

MAIN_SET = set(main_files)


# ------------------------------------------------------------------ site classification
ASSIGN = re.compile(r"(?<![=!<>+\-*/%&|^])=(?!=)")


def classify(text, pos, endpos, const):
    """'write' | 'read' -- how the occurrence at [pos,endpos) is used.

    Order matters: a comparison must be recognised BEFORE an assignment, or
    `boolean ok = s == E.CONST;` reads as a write.
    """
    before = text[max(0, pos - 400):pos]
    after = text[endpos:endpos + 40]
    tail = before.rstrip()

    # --- reads, decided first ------------------------------------------------
    if tail.endswith(("==", "!=")):
        return "read"
    if re.match(r"\s*(==|!=)", after):
        return "read"
    if re.search(r"\bcase\s*$", tail):
        return "read"

    # --- direct writes -------------------------------------------------------
    if tail.endswith("=") and not tail.endswith(("<=", ">=")):
        return "write"
    if re.search(r"\breturn\s*$", tail):
        return "write"

    # --- ternary branches ----------------------------------------------------
    # Found by running this gate: Shipment.java:169 assigns BOTH RECEIVED and DAMAGED
    #   this.status = condition == ShipmentCondition.DAMAGED
    #                 ? ShipmentStatus.DAMAGED : ShipmentStatus.RECEIVED;
    # and CreatorDeliverableService.java:351 assigns BOTH SUBMITTED and RESUBMITTED the
    # same way, across three lines. Looking only at the token immediately before the
    # constant sees "?" / ":" and called all four dead -- four false findings, in the
    # one gate whose credibility depends on its findings being real. The enclosing
    # STATEMENT decides instead: a ternary arm inherits the disposition of the
    # assignment, return, or setter call it sits inside.
    if tail.endswith("?") or (tail.endswith(":") and "?" in tail):
        stmt_start = max(before.rfind(";"), before.rfind("{"), before.rfind("}"))
        stmt = before[stmt_start + 1:]
        if ASSIGN.search(stmt) or re.search(r"\breturn\b", stmt):
            return "write"
        mm = re.search(r"\.\s*([A-Za-z_]\w*)\s*\(", stmt)
        if mm and WRITE_CALLEE.match(mm.group(1)):
            return "write"
        return "read"

    # --- method call:  .name( ... E.CONST ... ) -- only a setter/builder writes -----
    # `existsByCollaborationIdAndStatusNot(.., ContractStatus.CANCELLED)` is a QUERY.
    # Counting every call argument as a write is precisely how F-0403 stayed green.
    m = re.search(r"\.\s*([A-Za-z_]\w*)\s*\(\s*(?:[A-Za-z0-9_.\s,]{0,80})?$", before)
    if m and WRITE_CALLEE.match(m.group(1)):
        return "write"
    return "read"


def scan_constant(enum_name, const):
    """(main_writes, test_writes, main_reads) as [(path, line)]."""
    pat = re.compile(r"\b" + re.escape(enum_name) + r"\s*\.\s*" + re.escape(const) + r"\b")
    mw, tw, mr = [], [], []
    for p, text in CORPUS.items():
        for m in pat.finditer(text):
            site = (rel(p), lineno(text, m.start()))
            kind = classify(text, m.start(), m.end(), const)
            if kind == "write":
                (mw if p in MAIN_SET else tw).append(site)
            elif p in MAIN_SET:
                mr.append(site)
    return mw, tw, mr


def dynamic_writes(enum_name):
    """Sites where an arbitrary constant of this enum can be produced from a string.

    Two spellings, both live in this tree: `CampaignStatus.valueOf(raw)` and
    `parseEnum(TicketStatus.class, status, "status")` (AdminSupportService.java:112).
    Neither can be resolved to a particular constant without dataflow, and neither can
    be told apart from a query FILTER built the same way, so this gate does not treat
    them as writes -- it names them on the affected finding so the reviewer knows which
    line to read before believing or disbelieving it.
    """
    pat = re.compile(r"\b" + re.escape(enum_name) + r"\s*\.\s*(valueOf\s*\(|class\b)")
    out = []
    for p in main_files:
        for m in pat.finditer(CORPUS[p]):
            out.append((rel(p), lineno(CORPUS[p], m.start())))
    return out


def callers(meth, entity, decl_path):
    """(main_callers, test_callers) -- name-matched call sites, declaration excluded.

    Two corrections found by running this gate against the tree it was written for:

    1. the first pattern was `(?<![.\\w])(?:\\w+\\s*\\.\\s*)?NAME\\(`, which cannot match a
       CHAINED call written on its own line -- `.termsText(normalizeTerms(..))` at
       ContractService.java:296. The optional receiver group matched nothing, so the
       lookbehind landed on the leading '.' and rejected it. Contract#termsText, which
       has a real production caller, was reported as dead. A bare '.' before the name
       is the normal builder-chain form, so only a WORD character may precede it.

    2. matching on the name alone across all 933 files made `response.setStatus(429)`
       in AuthRateLimitFilter and `subscription.setStatus(..)` in SubscriptionDunningJob
       satisfy Contract#setStatus -- i.e. it silently greened F-0403, the exact defect
       this gate exists for. A file that cannot NAME the entity type cannot hold a
       reference to it, so candidate files are narrowed to those mentioning it. The
       token test is a prefix (`\\bContract`) rather than a whole word so that a file
       which only ever says ContractRepository / ContractService / ContractDtos still
       counts -- narrowing further would trade this gate's false greens for false reds.
    """
    pat = re.compile(r"(?<![\w$])" + re.escape(meth) + r"\s*\(")
    decl = re.compile(r"\b(?:public|protected|private|static|final)\b[^;{]*\b"
                      + re.escape(meth) + r"\s*\(")
    names = re.compile(r"\b" + re.escape(entity))
    mc, tc = [], []
    for p, text in CORPUS.items():
        if not names.search(text):
            continue  # this file cannot even name the entity; not a call on it
        for m in pat.finditer(text):
            ls = text.rfind("\n", 0, m.start()) + 1
            le = text.find("\n", m.end())
            line = text[ls:le if le != -1 else len(text)]
            if decl.search(line):
                continue  # the declaration is not a call site
            if re.search(r"\bnew\s+$", text[max(0, m.start() - 8):m.start()]):
                continue
            (mc if p in MAIN_SET else tc).append((rel(p), lineno(text, m.start())))
    return mc, tc


# ------------------------------------------------------------------ run
findings = []
examined = []
missing_entities = []
unparseable = []      # present but this gate could not read a declaration out of it
subjects = 0

for ent in CURATED:
    facts = entity_facts(ent)
    if facts == "absent":
        missing_entities.append(ent)
        continue
    if facts is None:
        unparseable.append("%s.java (no field or method declaration could be parsed)" % ent)
        continue
    fields, mutators = facts
    epath = os.path.join(ENTITY_DIR, ent + ".java")

    # --- 1. status enum constants -------------------------------------------
    for etype, fname in fields:
        if not os.path.isfile(os.path.join(ENUM_DIR, etype + ".java")):
            continue
        if not (etype.endswith("Status") or "status" in fname.lower()):
            continue
        consts = enum_constants(etype)
        if consts is None:
            unparseable.append("%s.java (held by %s#%s -- no enum constants could be "
                               "parsed, so NONE of its values were checked)"
                               % (etype, ent, fname))
            continue
        dyn = dynamic_writes(etype)
        for c in consts:
            subject = "%s.%s" % (ent, c)
            if only is not None and not only.search(subject):
                continue
            subjects += 1
            mw, tw, mr = scan_constant(etype, c)
            examined.append(("status", subject, len(mw)))
            if mw:
                continue
            note = ""
            if tw:
                note = " -- written ONLY by tests (%s)" % ", ".join(
                    "%s:%d" % s for s in tw)
            elif mr:
                note = " -- appears in main source ONLY at read sites (%s)" % ", ".join(
                    "%s:%d" % s for s in mr)
            else:
                note = " -- the constant appears nowhere outside its own enum declaration"
            if dyn:
                note += ("; UNDECIDED-BY-THIS-GATE: %s is also constructed from a string "
                         "at %s, which may write this value dynamically (or may only "
                         "build a query filter) -- read those lines before acting"
                         % (etype, ", ".join("%s:%d" % s for s in dyn)))
            findings.append(
                "%-38s NO PRODUCTION WRITE SITE: %s.%s is declared on %s.%s but no "
                "non-test code assigns it%s"
                % (subject, etype, c, ent, fname, note))

    # --- 2. public mutators --------------------------------------------------
    for meth, ln in mutators:
        subject = "%s#%s" % (ent, meth)
        if only is not None and not only.search(subject):
            continue
        subjects += 1
        mc, tc = callers(meth, ent, epath)
        examined.append(("mutator", subject, len(mc)))
        if mc:
            continue
        if tc:
            findings.append(
                "%-38s NO PRODUCTION CALLER: declared at %s:%d, called ONLY by tests "
                "(%s) -- the product cannot perform what this mutator exists to do"
                % (subject, rel(epath), ln, ", ".join("%s:%d" % s for s in tc)))
        else:
            findings.append(
                "%-38s NO CALLER AT ALL: declared at %s:%d and invoked from nowhere in "
                "main or test source" % (subject, rel(epath), ln))

# ------------------------------------------------------------------ verdict
extra = []
if missing_entities:
    extra.append("%d curated entit%s absent from %s and therefore unverified: %s"
                 % (len(missing_entities), "y" if len(missing_entities) == 1 else "ies",
                    rel(ENTITY_DIR), ", ".join(missing_entities)))
if unparseable:
    extra.append("%d file(s) this gate could not parse: %s"
                 % (len(unparseable), "; ".join(unparseable)))

# Zero inputs is 2. A curated list that resolved to nothing has proved nothing.
if subjects == 0:
    if only is not None:
        die(2, "* --only %s matched 0 subjects -- unavailable, NOT green; a filter that "
               "selects nothing is a broken assertion, not a passing one" % only.pattern,
            extra)
    die(2, "* 0 subjects resolved from the curated entity list -- unavailable; this gate "
           "checked nothing and must not report green", extra)

if missing_entities and len(missing_entities) == len(CURATED):
    die(2, "* none of the %d curated entities exist under %s -- unavailable"
        % (len(CURATED), rel(ENTITY_DIR)), extra)

print("* missing-feature%s: %d subjects across %d curated entities "
      "(%d status values, %d public mutators); %d main + %d test java files scanned"
      % ((" [--only %s]" % only.pattern) if only else "",
         subjects, len(CURATED) - len(missing_entities),
         sum(1 for k, _, _ in examined if k == "status"),
         sum(1 for k, _, _ in examined if k == "mutator"),
         len(main_files), len(test_files)))

if show_all:
    for kind, subject, n in examined:
        print("  %-7s %-38s %d production site(s)" % (kind, subject, n))

for f in findings:  # every finding, never a head -N slice
    print("  " + f)

# A file the gate could not parse is exit 2, even when the rest of the run found things
# and even when the rest of the run found nothing. Reporting "aligned (proved)" over a
# declaration this gate never managed to read is the same vacuous green as a zero-input
# pass -- the findings above are printed in full, but they are NOT the whole answer.
if unparseable:
    die(2, "* %d curated file(s) could not be parsed -- unavailable, NOT green; the %d "
           "finding(s) above are real but incomplete"
        % (len(unparseable), len(findings)), extra)

if findings:
    print("VERDICT: broken -- %d declared capabilit%s no production code path can reach"
          % (len(findings), "y" if len(findings) == 1 else "ies"))
else:
    print("VERDICT: aligned (proved) -- every curated status value is written and every "
          "curated public mutator is called by non-test code")
emit(extra)
sys.exit(1 if findings else 0)
