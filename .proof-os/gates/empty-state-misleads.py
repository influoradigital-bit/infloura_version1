#!/usr/bin/env python3
# =====================================================================================
# GATE: empty-state-misleads
# LEDGER CLASS: empty-state-misleads
# RECURRENCES: F-0278, F-0349, F-0410, F-0436
#
# THE DEFECT
#   A zero / empty / null / caught-error data state renders copy or iconography that
#   reads as SUCCESS. The screen a brand sees on day one, having done nothing, is
#   pixel-identical to the screen it sees having finished everything.
#
#     F-0278  brand dashboard, zero campaigns -> a green CheckCircle2 and
#             "All caught up! No pending actions right now."
#     F-0349  deal-room deliverables panel, 0 slots -> "They appear here once the
#             creator submits", on a deal where no Submit control can ever render.
#     F-0410  discovery grid -> a client-filtered page length rendered as a total,
#             and a catch() that empties the grid into that same reassuring state.
#     F-0436  bell badge recomputed from one fetched page, so an unloaded/failed
#             fetch reads as zero unread, i.e. as "All caught up".
#
# WHY EVERY OTHER CHECK PASSES IT
#   tsc: the string is a string. eslint: the branch is a branch. A screenshot of the
#   populated state looks right, and a screenshot of the empty state looks *pleasant*,
#   which is the bug. Only the RELATIONSHIP between the reassurance and the condition
#   that produced it is wrong, so that relationship is what this file reads.
#
# WHAT THIS GATE ASSERTS
#   A success-coded render whose controlling conditions include an emptiness test
#   (empty array / zero count / null response / caught error) MUST also be qualified
#   by BOTH, and with the RIGHT POLARITY:
#     (a) a settled-data discriminator  - loading/pending asserted FALSE, or
#         status==='ready' asserted TRUE, so the reassurance cannot fire before the
#         data that would justify it has arrived;
#     (b) a never-started-or-failed discriminator - error/first-run asserted FALSE, or
#         a has-ever signal asserted TRUE, so "you finished" is distinguishable from
#         "you never began" and from "the fetch died".
#   Missing either, or carrying one INVERTED (the reassurance reachable *while*
#   loading or *after* an error), is a finding.
#
#     BAD   src/pages/brand-notifications.tsx
#           {unreadCount > 0 ? `${unreadCount} unread` : 'All caught up'}
#           - zero is also what loading and a failed fetch look like.
#
#     GOOD  src/pages/creator-dashboard.tsx
#           {!loading && !isEmptyCreator && pending.total === 0 && (
#              <CheckCircle2 className="text-success" /> All caught up - ... )}
#           - the same words, but only reachable by an account that has data and
#             finished it.
#
# HOW THE CONDITION IS RECOVERED  (this is the part an adversary attacks)
#   The 2026-09-02 refutation showed the previous version read only a flat, depth-0
#   text slice and therefore green-lit the five most ordinary spellings of the same
#   defect. What it reads now, per success site:
#     - the JSX/ternary chain, DESCENDING into parenthesised sub-expressions rather
#       than dropping them  ((!loading && !error && everHad) && empty && <ok/>);
#     - `if (...) { return <ok/> }` and `if (...) return <ok/>` early-return blocks,
#       plus the `else` side, resolved on real source ranges rather than guessed at;
#     - preceding TERMINATING guard clauses - `if (loading) return <Skeleton/>;`
#       contributes `loading === false` to everything after it, which is what keeps
#       the correct spelling of that idiom green;
#     - identifiers resolved one hop through their same-file `const` initialiser, so
#       `const settled = !loading && !error && everFetched` counts as both guards;
#     - success copy reached through a same-file binding (`const EMPTY_MSG = '...'`,
#       `const renderEmpty = () => <p>All caught up</p>`) is re-anchored at that
#       binding's use sites;
#     - copy split by inline tags ("All caught <strong>up</strong>") via a
#       tag-flattened view of the file.
#   Every condition is reduced to (atom, required-polarity) pairs - T / F, plus a weak
#   form for the operands of a disjunction - so a discriminator only counts when the
#   branch actually asserts it in the direction that makes the reassurance honest.
#
# EXITS
#   0  proved      - every success-coded render on an empty/zero/error branch is
#                    qualified on both axes, with correct polarity
#   1  broken      - at least one is not; file:line printed for each
#   2  unavailable - could not run, or scanned zero candidate files. "I checked
#                    nothing, therefore it is fine" is the failure mode this class
#                    exists for, so a vacuous pass is never a pass.
#  64  usage
#
# USAGE
#   python .proof-os/gates/empty-state-misleads.py [repo_root] [--list] [--only REGEX]
#   (repo_root defaults to the repo this file lives in; runnable from any cwd)
# =====================================================================================

import os
import re
import sys

# ------------------------------------------------------------------ toolchain floor
if sys.version_info < (3, 6):
    sys.stderr.write("empty-state-misleads: needs python >= 3.6 - unavailable\n")
    sys.exit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))  # .proof-os/gates -> repo

SCAN_DIRS = ("src/components", "src/pages")
SKIP_DIRS = {"node_modules", "__tests__", "__mocks__", ".git", "dist", "build", "coverage"}
SKIP_NAME = re.compile(r"\.(test|spec|stories)\.tsx?$")

MAX_GUARD = 4000        # characters of recovered condition text per enclosure
MAX_HOPS = 2            # binding-resolution hops for a success site
MAX_REFS = 8            # use sites followed per binding

# Law: every exit path, including 0, names what it did not look at.
BLIND = [
    "anything outside %s - a misleading empty state in a hook, a layout, a lib "
    "helper or the admin tree is invisible here" % " and ".join(SCAN_DIRS),
    "reassurance assembled at runtime (i18n catalogues, server-sent strings) or in "
    "ANOTHER module - a copy constant or an <EmptyState/> body is followed only when "
    "it is declared in the same file",
    "whether the discriminators a branch carries are wired to the right state or ever "
    "become true - polarity is checked, truth is not; a `const settled = true` passes",
    "identifier resolution deeper than one same-file const hop, and any value produced "
    "by a call (useMemo, a selector, a hook) - those are opaque",
    "the numeric half of this ledger class (F-0410/F-0436 count provenance: a client "
    "array length rendered as a server total) - that is gates/empty_state_misleads.py",
    "runtime behaviour: nothing is mounted, fetched or rendered",
    "success signalled by a component name alone (<SuccessCard/>, <EmptyState "
    "variant='done'/>) whose copy and colour live in another file",
    "the F-0349 sub-shape - an empty state that PROMISES resolution ('they appear "
    "here once the creator submits') via a control that can never exist. That copy "
    "is correct on six other surfaces in this tree, so it is not decidable by the "
    "settled/never-started rule above; it needs a rendered test that the promised "
    "control is reachable, not a static scan",
    "a recovered condition longer than %d characters, or nested deeper than %d "
    "enclosures, which is skipped rather than guessed at" % (MAX_GUARD, 12),
    "switch/case entirely - `case 'empty': return <p>All caught up</p>` recovers no "
    "condition at all and is not reported; likewise a bare early `return` outside an "
    "`if`, and control flow through a try/catch",
]


def emit(extra=()):
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# ------------------------------------------------------------------------- vocabulary

# Copy that asserts finished work. Deliberately narrow: each phrase is a claim that
# the user has COMPLETED something, not merely a description of an empty list.
_NOUNS = (r"(?:actions?|items?|tasks?|requests?|approvals?|submissions?|reviews?"
          r"|invites?|invitations?|notifications?|messages?|deliverables?|payouts?"
          r"|invoices?|updates?|alerts?|applications?|offers?|deals?|work)")
SUCCESS_COPY = re.compile(
    r"all\s+caught\s+up"
    r"|caught\s+up\b"
    r"|you\s*['’]?\s*re\s+all\s+set|you\s+are\s+all\s+set|\byou\s*['’]?\s*re\s+set\b"
    r"|nothing\s+(?:more\s+)?to\s+do|nothing\s+left\s+to\s+do"
    r"|nothing\s+(?:more\s+)?to\s+(?:review|approve|sign|action)"
    r"|no\s+pending\s+" + _NOUNS +
    r"|no\s+outstanding\s+" + _NOUNS +
    r"|nothing\s+(?:needs|requires)\s+your\s+attention"
    r"|no\s+actions?\s+(?:needed|required)"
    r"|you\s*['’]?\s*re\s+(?:all\s+)?done\b|\ball\s+done\b"
    r"|\ball\s+clear\b"
    r"|good\s+to\s+go\b"
    r"|up\s+to\s+date\b"
    r"|great\s+job|well\s+done|nice\s+work|way\s+to\s+go|keep\s+it\s+up"
    r"|congratulations|congrats"
    r"|everything\s+(?:is\s+|looks\s+)?(?:good|fine|in\s+order|on\s+track)"
    r"|no\s+issues?\b|looking\s+good\b",
    re.I,
)

# Iconography reads as success only when it is *coloured* as success; a bare <Check/>
# is used as a list bullet all over this tree.
SUCCESS_ICON = re.compile(
    r"<\s*(?:CheckCircle2?|CircleCheck(?:Big)?|BadgeCheck|CheckCheck|ShieldCheck|Check|"
    r"PartyPopper|Trophy|ThumbsUp|Award)\b"
)
SUCCESS_COLOR = re.compile(
    r"text-success|bg-success|border-success"
    r"|text-green-\d|bg-green-\d|border-green-\d"
    r"|text-emerald-\d|bg-emerald-\d|border-emerald-\d"
)

# --- the condition that makes the branch an EMPTY/ZERO/NULL/ERROR branch ------------
# Atoms are normalised (leading `!` folded into the polarity), so `!items.length`
# arrives here as ("items.length", F) and is matched by the FALSE table.
_CNT = r"[\w$]*(?:[Cc]ount|[Tt]otal|[Nn]um|[Ll]en)[\w$]*"
EMPTY_WHEN_TRUE = [
    (re.compile(r"\.length\s*(?:===?|==)\s*0"), "an empty array (.length === 0)"),
    (re.compile(r"\.length\s*<\s*1|\.length\s*<=\s*0"), "an empty array (.length < 1)"),
    (re.compile(r"\.(?:size|count)\s*(?:===?|==)\s*0|\.(?:size|count)\s*<\s*1"),
     "an empty collection (.size === 0)"),
    (re.compile(r"\b" + _CNT + r"\s*(?:===?|==)\s*0|\b" + _CNT + r"\s*<\s*1"),
     "a zero count"),
    (re.compile(r"\bisEmpty\b|\bis[A-Z]\w*Empty\w*\b|\b\w*Empty\b"), "an isEmpty predicate"),
    (re.compile(r"\bhasNo[A-Z]\w*\b|\bnone\b"), "a has-no predicate"),
    (re.compile(r"(?:===?|==)\s*(?:null|undefined)\b"), "a null/undefined response"),
    (re.compile(r"\b(?:error|isError|hasError|loadError|fetchError|failed|isFailed)\b"),
     "an error state"),
]
EMPTY_WHEN_FALSE = [
    (re.compile(r"\.length\s*>\s*0|\.length\s*>=\s*1|\.length\s*!==?\s*0"),
     "an empty array (else of .length > 0)"),
    (re.compile(r"\b" + _CNT + r"\s*>\s*0|\b" + _CNT + r"\s*>=\s*1|\b" + _CNT + r"\s*!==?\s*0"),
     "a zero count (else of count > 0)"),
    (re.compile(r"\.(?:size|count)\s*(?:>\s*0|>=\s*1|!==?\s*0)"),
     "an empty collection (else of .size > 0)"),
    (re.compile(r"\.length\s*$|\.length\s*\?"), "an empty array (else of x.length)"),
    (re.compile(r"^\s*(?:[\w$]+\.)*(?:data|items|list|rows|results|records|entries|"
                r"response|payload|notifications)\s*$"),
     "a null/empty response (else of data ?)"),
    (re.compile(r"\bhasAny\w*|\bhasItems\b|\bhasRows\b|\bhasResults\b"),
     "a has-any predicate asserted false"),
]

# --- the two qualifications a success-coded empty branch must carry ------------------
# Each is a PAIR: the token that must be asserted FALSE, or the token that must be
# asserted TRUE. Presence alone is never enough - that was the v5 hole.
LOADING_RX = re.compile(
    r"(?<!['\"])\b(?:loading|isLoading|isPending|isFetching|isValidating|isRefetching"
    r"|fetching|pending|busy|isBusy)\b(?!\s*['\"[.])"
    r"|[\w$]*[Ss]tat(?:us|e)\s*(?:===?|==)\s*['\"](?:loading|pending|fetching|"
    r"refreshing)['\"]"
    r"|\bskeleton\b",
    re.I,
)
READY_RX = re.compile(
    r"[\w$]*[Ss]tat(?:us|e)\s*(?:===?|==)\s*['\"](?:ready|success|settled|loaded|done|"
    r"idle|resolved)['\"]"
    r"|\b(?:isReady|hasLoaded|isLoaded|isSettled|isSuccess|isFetched|hasResolved"
    r"|hasSettled|hasFetched)\b"
    r"|(?<!['\"])\b(?:ready|loaded|settled)\b(?!\s*['\"[.])",
    re.I,
)
FAILED_RX = re.compile(
    r"(?<!['\"])\b(?:error|isError|hasError|loadError|fetchError|failed|isFailed"
    r"|didFail|errored)\b(?!\s*['\"[.])"
    r"|[\w$]*[Ss]tat(?:us|e)\s*(?:===?|==)\s*['\"](?:error|failed|rejected)['\"]",
    re.I,
)
UNSTARTED_RX = re.compile(
    r"(?<!['\"])\b\w*[Ee]mpty\w*\b"
    r"|\b(?:neverStarted|notStarted|neverFunded|firstRun|isFirstRun|isNewAccount"
    r"|isNew|isBrandNew|noHistory|isOnboarding|needsOnboarding)\b",
    re.I,
)
# NOT case-insensitive on purpose: `\bever[A-Z]` under re.I also matches "everything".
EVER_RX = re.compile(
    r"\bhasEver\w*|\bever(?:Had|Loaded|Fetched|Created|Funded|Started|Synced)\w*"
    r"|\bhasAny\w*|\bhasData\b|\bhasHistory\b|\bhasRecords?\b|\btotalEver\b"
    r"|\blifetime\w*|\bhasItems\b|\bhasEverLoaded\b|\bonboard(?:ed|ingComplete)\w*"
)

# `status !== 'ready'` asserted FALSE says the same thing as `status === 'ready'`
# asserted TRUE. A reviewer writes both; a gate that only reads one punishes the other.
NOT_READY_RX = re.compile(
    r"[\w$]*[Ss]tat(?:us|e)\s*!==?\s*['\"](?:ready|success|settled|loaded|done|"
    r"resolved)['\"]", re.I)
NOT_LOADING_RX = re.compile(
    r"[\w$]*[Ss]tat(?:us|e)\s*!==?\s*['\"](?:loading|pending|fetching|refreshing)"
    r"['\"]", re.I)
NOT_FAILED_RX = re.compile(
    r"[\w$]*[Ss]tat(?:us|e)\s*!==?\s*['\"](?:error|failed|rejected)['\"]", re.I)

KEYWORDS = {"if", "while", "for", "switch", "return", "typeof", "new", "in", "of",
            "do", "else", "catch", "await", "yield", "delete", "void", "case",
            "instanceof"}


# ------------------------------------------------------------------------ tiny lexer
# A full JS/TSX parse is not available here, but the only things needed are: which
# brace/paren openers enclose position P, which characters are code rather than
# string/comment, and where each opener's partner sits.
#
# The one non-obvious rule: a quote preceded immediately by a word character or `>`
# is NOT a string opener. That is a contraction or possessive in JSX text
# ("your brand's influencer partnerships", "Don't worry"), which a naive lexer treats
# as an unterminated string and thereby corrupts every enclosure below it.

def lex(src):
    """Return (delims, in_code): delims is [(index, char)] for code-level {}()[] only,
    in_code[i] is True when src[i] is code (not string/template/comment/regex)."""
    n = len(src)
    in_code = bytearray([1]) * n
    delims = []
    i = 0
    prev_sig = ""  # last significant code char, for regex/quote disambiguation
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""

        if c == "/" and nxt == "/":
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                in_code[k] = 0
            i = j
            continue
        if c == "/" and nxt == "*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                in_code[k] = 0
            i = j
            continue
        # `/` after `}` is almost always the self-close of a JSX tag whose last
        # attribute was an expression - `<List items={items} />`. Reading it as a regex
        # literal swallowed the rest of the line, taking its braces out of `delims` and
        # silently hiding every `else` branch on that line.
        if (c == "/" and prev_sig in "(,=:[!&|?{};\n+*%<>~^"
                and nxt not in ("", "/", "*", ">")):
            # plausible regex literal: consume to an unescaped closing slash on the line
            j = i + 1
            ok = False
            while j < n and src[j] != "\n":
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "[":
                    while j < n and src[j] not in "]\n":
                        j += 2 if src[j] == "\\" else 1
                if j < n and src[j] == "/":
                    ok = True
                    j += 1
                    break
                j += 1
            if ok:
                for k in range(i, j):
                    in_code[k] = 0
                prev_sig = "/"
                i = j
                continue

        if c in "'\"":
            prev_ch = src[i - 1] if i else ""
            if prev_ch.isalnum() or prev_ch == "_" or prev_ch == ">":
                # contraction / possessive inside JSX text - not a string
                i += 1
                continue
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == c or src[j] == "\n":
                    break
                j += 1
            j = min(j + 1, n)
            for k in range(i, j):
                in_code[k] = 0
            prev_sig = "x"
            i = j
            continue

        if c == "`":
            j = i + 1
            depth = 0
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "$" and j + 1 < n and src[j + 1] == "{":
                    depth += 1
                    j += 2
                    continue
                if src[j] == "}" and depth:
                    depth -= 1
                    j += 1
                    continue
                if src[j] == "`" and not depth:
                    j += 1
                    break
                j += 1
            for k in range(i, min(j, n)):
                in_code[k] = 0
            prev_sig = "x"
            i = min(j, n)
            continue

        if c in "{}()[]":
            delims.append((i, c))
        if not c.isspace():
            prev_sig = c
        i += 1
    return delims, in_code


def comment_spans(src):
    spans = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            spans.append((i, j))
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            spans.append((i, j))
            i = j
        elif src[i] in "'\"`":
            q = src[i]
            prev_ch = src[i - 1] if i else ""
            if q != "`" and (prev_ch.isalnum() or prev_ch in "_>"):
                i += 1
                continue
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == q or (q != "`" and src[j] == "\n"):
                    break
                j += 1
            i = min(j + 1, n)
        else:
            i += 1
    return spans


def in_comment(spans, pos):
    for a, b in spans:
        if a <= pos < b:
            return True
        if a > pos:
            break
    return False


# `masked` is src with comments blanked and with every STRUCTURAL character that sits
# inside a string or template blanked too. Word characters and quotes survive, so
# `status === 'ready'` still matches, while a template's `${...}` braces can no longer
# corrupt depth arithmetic. All condition reading happens on `masked`; all copy
# matching happens on the raw source.
MASK_CHARS = set("(){}[]<>?:;&|!=,`$")


def mask_source(src, in_code, cspans):
    buf = list(src)
    n = len(src)
    for i in range(n):
        if not in_code[i] and src[i] in MASK_CHARS:
            buf[i] = " "
    for a, b in cspans:
        for i in range(a, min(b, n)):
            buf[i] = " "
    return "".join(buf)


def match_map(delims):
    stack, out = [], {}
    for idx, ch in delims:
        if ch in "([{":
            stack.append(idx)
        elif stack:
            o = stack.pop()
            out[o] = idx
            out[idx] = o
    return out


def enclosing_openers(delims, pos, limit=12):
    """Indices of the unclosed { / ( openers that contain pos, innermost first."""
    stack = []
    for idx, ch in delims:
        if idx >= pos:
            break
        if ch in "{([":
            stack.append((idx, ch))
        elif stack:
            stack.pop()
    return [i for i, ch in reversed(stack) if ch in "{("][:limit]


def enclosing_braces(delims, pos):
    stack = []
    for idx, ch in delims:
        if idx >= pos:
            break
        if ch in "{([":
            stack.append((idx, ch))
        elif stack:
            stack.pop()
    return frozenset(i for i, ch in stack if ch == "{")


def paren_is_call(masked, idx):
    """True when `(` at idx is a call/args paren rather than a grouping paren."""
    j = idx - 1
    while j >= 0 and masked[j].isspace():
        j -= 1
    if j < 0:
        return False
    c = masked[j]
    if c in ")]":
        return True
    if c.isalnum() or c in "_$":
        k = j
        while k >= 0 and (masked[k].isalnum() or masked[k] in "_$"):
            k -= 1
        return masked[k + 1:j + 1] not in KEYWORDS
    return False


def guard_spans(masked, delims, opener, pos):
    """Source ranges between `opener` and `pos` that CONTROL pos.

    Nested {...} and [...] groups, and call-argument parens, are dropped - a JSX
    subtree or a call argument is not a condition. GROUPING parens are descended into
    instead of dropped, which is the v9 fix: `(!loading && !error && ever) && empty &&`
    used to reduce to `&& empty &&`.
    """
    spans = []
    keep = set()
    last = opener + 1
    skip = 0
    inc = 0
    for idx, ch in delims:
        if idx <= opener:
            continue
        if idx >= pos:
            break
        if ch in "([{":
            if skip:
                skip += 1
                continue
            if ch == "(" and not paren_is_call(masked, idx):
                inc += 1
                keep.add(idx)
                continue
            spans.append((last, idx))
            skip = 1
        else:
            if skip:
                skip -= 1
                if skip == 0:
                    last = idx + 1
                continue
            if inc and ch == ")":
                inc -= 1
                keep.add(idx)
                continue
            spans.append((last, idx))
            return spans, keep    # the enclosure closed before pos: malformed, stop
    if skip == 0:
        spans.append((last, pos))
    # skip > 0 means pos sits inside a container this enclosure dropped (a nested JSX
    # expression block, a call argument). That container is its own enclosure and is
    # read as one; re-appending the tail here would smuggle its text back in as if it
    # were this enclosure's condition.
    return spans, keep


def guard_code(masked, spans, keep=frozenset()):
    """Split the controlling ranges into JS and JSX and return only the JS.

    This separation is the whole precision of the gate. `{sel.status === 'signed' && (
    <div>Deliverables {n}/{total} ... </div> )}` has JSX *text* at depth 0 whenever the
    branch is written without wrapping parens, and that text ("Deliverables", "Expires",
    "Signed on") is not a condition. Reading it as one made an earlier version report
    two genuine `status === 'signed'` success cards as empty-state defects.

    An earlier version tracked no JSX nesting, so the first `>` it met put it in "text"
    mode until the next `<`. That deleted the operators BETWEEN sibling branches:
    `loading ? <Spinner /> : error ? <Err /> : <div>All caught up</div>` reduced to
    `loading ?`, inverting the polarity of a correctly guarded render. Element depth is
    therefore tracked: a self-closing tag at depth 0 returns to JS, and only the
    children of an open element are dropped as prose.
    """
    n = len(masked)
    out = []
    mode = "js"          # js | tag | text
    depth = 0            # JSX element nesting
    closing = False      # the tag being scanned is `</...>`
    lastch = ""          # last non-space char inside the tag, to spot `/>`
    prev_js = ""         # last non-space char emitted as JS, to spot `Foo<T>` generics
    for a, b in spans:
        i = a
        while i < b:
            c = masked[i]
            if depth == 0 and i in keep:
                # a grouping paren we deliberately descended into: it is structure, not
                # JSX, and must survive so the recovered expression stays balanced.
                out.append(c)
                prev_js = c
                mode = "js"
                i += 1
                continue
            nxt = masked[i + 1] if i + 1 < n else ""
            if mode == "js":
                # `useState<Item[]>(...)` is a type argument, not an element: a `<` that
                # follows an identifier, `)` or `]` never opens JSX.
                if (c == "<" and (nxt.isalpha() or nxt in "/>")
                        and not (prev_js.isalnum() or prev_js in "_$)]")):
                    mode = "tag"
                    closing = nxt == "/"
                    lastch = ""
                    i += 1
                    continue
                out.append(c)
                if not c.isspace():
                    prev_js = c
            elif mode == "tag":
                if c == ">":
                    if closing:
                        depth = max(0, depth - 1)
                    elif lastch != "/":
                        depth += 1
                    mode = "text" if depth else "js"
                elif not c.isspace():
                    lastch = c
            else:  # children of an open element: prose, not condition
                if c == "<" and (nxt.isalpha() or nxt in "/>"):
                    mode = "tag"
                    closing = nxt == "/"
                    lastch = ""
            i += 1
        # mode/depth carry ACROSS the gap left by a dropped container: `<p>{a}/{b} left`
        # is still JSX text after `{a}`, and resetting to JS there put the `/` and the
        # word `left` into the condition.
    return "".join(out)


# --------------------------------------------------------------- expression polarity
# Everything below reduces a recovered condition to (atom, polarity) pairs.
#   'T'  the branch cannot render unless this atom is truthy
#   'F'  ... unless it is falsy
#   'wT' / 'wF'  operand of a disjunction: may be true / may be false, not guaranteed
# Presence without polarity was the v5 hole: `(loading || error) && empty && <ok/>`
# contains the word "loading" and used to count as a settled-data guard.

FLIP = {"T": "F", "F": "T", "wT": "wF", "wF": "wT"}
TERNARY_Q = re.compile(r"(?<![?\w])\?(?![?.:])")
TERNARY_C = re.compile(r"(?<![:?\w]):(?![:=])")
AND_RX = re.compile(r"&&")
OR_RX = re.compile(r"\|\||\?\?")
IDENT_RX = re.compile(r"^[A-Za-z_$][\w$]*$")


def depth_array(code):
    d = [0] * len(code)
    k = 0
    for i, c in enumerate(code):
        if c in "([{":
            d[i] = k
            k += 1
        elif c in ")]}":
            k = max(0, k - 1)
            d[i] = k
        else:
            d[i] = k
    return d


def top_positions(code, rx, start=0):
    d = depth_array(code)
    return [m.start() for m in rx.finditer(code, start) if d[m.start()] == 0]


def first_unclosed(code):
    stack = []
    for i, c in enumerate(code):
        if c in "([{":
            stack.append(i)
        elif c in ")]}" and stack:
            stack.pop()
    return stack[0] if stack else None


def tail_statement(code):
    """Everything after the last top-level `;` - the statement pos actually sits in."""
    ps = top_positions(code, re.compile(r";"))
    return code[ps[-1] + 1:] if ps else code


LEAD_DECL = re.compile(r"^\s*(?:export\s+)?(?:const|let|var)\s+[\w${},:\s\[\]]{0,80}?=\s*")
LEAD_RETURN = re.compile(r"^\s*return\b")


def split_top(code, rx):
    parts, last = [], 0
    for p in top_positions(code, rx):
        parts.append(code[last:p])
        last = p + 2
    parts.append(code[last:])
    return parts


def strip_group(t):
    while t.startswith("(") and t.endswith(")"):
        inner = t[1:-1]
        k = 0
        ok = True
        for c in inner:
            if c in "([{":
                k += 1
            elif c in ")]}":
                k -= 1
                if k < 0:
                    ok = False
                    break
        if not ok or k != 0:
            break
        t = inner.strip()
    return t


def bool_atoms(text, pol, out, consts, depth=0):
    """Decompose a COMPLETE boolean expression of known required polarity."""
    if depth > 12:
        return
    t = strip_group(text.strip())
    if not t:
        return
    # Operators bind looser than `!`, so the splits MUST happen before the unary strip.
    # Reading `!loading && deals.length === 0` as `!(loading && ...)` inverted every
    # atom of the repo's own correct `isEmptyCreator` and reported it as a defect.
    if pol in ("T", "wT"):
        parts = split_top(t, AND_RX)
        if len(parts) > 1:
            for p in parts:
                bool_atoms(p, pol, out, consts, depth + 1)
            return
        parts = split_top(t, OR_RX)
        if len(parts) > 1:
            for p in parts:
                bool_atoms(p, "wT", out, consts, depth + 1)
            return
    else:
        parts = split_top(t, OR_RX)
        if len(parts) > 1:
            for p in parts:
                bool_atoms(p, pol, out, consts, depth + 1)
            return
        parts = split_top(t, AND_RX)
        if len(parts) > 1:
            for p in parts:
                bool_atoms(p, "wF", out, consts, depth + 1)
            return
    if t.startswith("!") and not t.startswith("!="):
        bool_atoms(t[1:], FLIP[pol], out, consts, depth + 1)
        return
    t = re.sub(r"\s+", " ", t).strip()
    if not t:
        return
    if (t, pol) not in out:
        out.append((t, pol))
    # one hop through a same-file `const` initialiser, so the standard refactor
    # `const settled = !loading && !error && everFetched` is not punished (v10)
    if consts and depth < 6 and IDENT_RX.match(t) and t in consts:
        bool_atoms(consts[t], pol, out, consts, depth + 1)


def expr_at_end(code, out, consts, depth=0):
    """Atoms for an expression whose FINAL, missing operand is the success render."""
    code = tail_statement(code)
    code = LEAD_DECL.sub("", code, count=1)
    code = LEAD_RETURN.sub("", code, count=1)
    if len(code) > MAX_GUARD:
        return
    for _ in range(24):
        qs = top_positions(code, TERNARY_Q)
        if not qs:
            break
        q = qs[0]
        cs = top_positions(code, TERNARY_C, q + 1)
        cond = code[:q]
        if cs:
            bool_atoms(cond, "F", out, consts)
            code = code[cs[0] + 1:]
        else:
            bool_atoms(cond, "T", out, consts)
            code = code[q + 1:]
    parts = split_top(code, OR_RX)
    if len(parts) > 1:
        for p in parts[:-1]:
            bool_atoms(p, "F", out, consts)
        code = parts[-1]
    parts = split_top(code, AND_RX)
    for p in parts[:-1]:
        bool_atoms(p, "T", out, consts)


def atoms_at_end(code, out, consts, depth=0):
    if depth > 12 or not code.strip():
        return
    idx = first_unclosed(code)
    if idx is not None:
        expr_at_end(code[:idx], out, consts, depth)
        atoms_at_end(code[idx + 1:], out, consts, depth + 1)
        return
    expr_at_end(code, out, consts, depth)


# ------------------------------------------------------------------ statement control
# `if (...) { return <ok/> }`, `if (...) return <ok/>`, the `else` side, and - the part
# that keeps correct code green - preceding TERMINATING guard clauses.

IF_RX = re.compile(r"\bif\s*\(")


def skip_ws(masked, i, n):
    while i < n and masked[i].isspace():
        i += 1
    return i


def statement_end(masked, mmap, i, n):
    """End of the simple statement starting at i (exclusive)."""
    depth = 0
    while i < n:
        c = masked[i]
        if c in "([{":
            j = mmap.get(i)
            if j is None:
                return n
            i = j + 1
            continue
        if c == ";" and depth == 0:
            return i + 1
        if c in ")]}":
            return i
        i += 1
    return n


def if_ranges(masked, delims, mmap):
    """[(cond_text, start, end, terminates, is_else)] for every `if` in the file."""
    n = len(masked)
    out = []
    for m in IF_RX.finditer(masked):
        op = m.end() - 1
        cl = mmap.get(op)
        if cl is None or cl <= op:
            continue
        cond = masked[op + 1:cl]
        if not cond.strip() or len(cond) > 600:
            continue
        j = skip_ws(masked, cl + 1, n)
        if j >= n:
            continue
        if masked[j] == "{":
            end = mmap.get(j)
            if end is None:
                continue
            end += 1
            body = masked[j + 1:end - 1]
        else:
            end = statement_end(masked, mmap, j, n)
            body = masked[j:end]
        terminates = bool(re.match(r"\s*(?:return|throw)\b", body))
        out.append((cond, m.start(), j, end, terminates, False))
        # else / else if
        k = skip_ws(masked, end, n)
        if masked.startswith("else", k) and (k + 4 >= n or not (masked[k + 4].isalnum()
                                                               or masked[k + 4] in "_$")):
            e = skip_ws(masked, k + 4, n)
            if e < n:
                if masked[e] == "{":
                    ee = mmap.get(e)
                    if ee is not None:
                        out.append((cond, m.start(), e, ee + 1, False, True))
                else:
                    ee = statement_end(masked, mmap, e, n)
                    out.append((cond, m.start(), e, ee, False, True))
    return out


def flow_atoms(masked, delims, mmap, ifs, pos, out, consts):
    pos_braces = enclosing_braces(delims, pos)
    for cond, if_start, body_start, body_end, terminates, is_else in ifs:
        if body_start <= pos < body_end:
            bool_atoms(cond, "F" if is_else else "T", out, consts)
        elif terminates and body_end <= pos and not is_else:
            # `if (loading) return <Skeleton/>;` above pos, in a block that also
            # encloses pos -> at pos, loading is false.
            if enclosing_braces(delims, if_start) <= pos_braces:
                bool_atoms(cond, "F", out, consts)


# --------------------------------------------------------------- same-file bindings

CONST_DECL = re.compile(r"\bconst\s+([A-Za-z_$][\w$]*)\s*(?::\s*[^=;]{0,60})?=\s*")
DECL_ANY = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(?::\s*[^=;]{0,60})?=\s*"
                      r"|\bfunction\s+([A-Za-z_$][\w$]*)\s*\(")
BOOLISH = re.compile(r"&&|\|\||!|==|>|<|\?")
JSXISH = re.compile(r"<\s*[A-Za-z/]")


def const_map(masked, mmap):
    """name -> boolean-ish initialiser text, for one-hop identifier resolution."""
    n = len(masked)
    out = {}
    for m in CONST_DECL.finditer(masked):
        name = m.group(1)
        if name in out:
            continue
        end = statement_end(masked, mmap, m.end(), n)
        val = masked[m.end():end].rstrip(";").strip()
        if not val or len(val) > 240:
            continue
        if "=>" in val or JSXISH.search(val) or "function" in val:
            continue
        if not BOOLISH.search(val) and not re.match(r"^[\w$.]+$", val):
            continue
        out[name] = val
    return out


def declarations(masked, mmap):
    """[(name, value_start, value_end)] for every same-file const/let/var/function."""
    n = len(masked)
    out = []
    for m in DECL_ANY.finditer(masked):
        if m.group(1):
            out.append((m.group(1), m.end(), statement_end(masked, mmap, m.end(), n)))
        else:
            op = masked.find("(", m.start())
            cl = mmap.get(op) if op >= 0 else None
            if cl is None:
                continue
            b = skip_ws(masked, cl + 1, n)
            if b < n and masked[b] == "{" and mmap.get(b) is not None:
                out.append((m.group(2), b, mmap[b] + 1))
    return out


# ---------------------------------------------------------------------- success sites

def flatten_tags(masked, src):
    """A view of the file with JSX tags replaced by one space, so copy split across
    inline elements ("All caught <strong>up</strong>") is still one phrase."""
    n = len(src)
    buf = []
    imap = []
    i = 0
    while i < n:
        if masked[i] == "<" and i + 1 < n and (masked[i + 1].isalpha() or masked[i + 1] in "/>"):
            j = masked.find(">", i + 1)
            if j < 0:
                j = n - 1
            buf.append(" ")
            imap.append(i)
            i = j + 1
            continue
        buf.append(src[i])
        imap.append(i)
        i += 1
    return "".join(buf), imap


def success_sites(src, masked, in_code, cspans):
    """[(pos, what)] for every literal success signal in the file.

    Comments are excluded. This is not cosmetic: dashboard-page.tsx carries a block
    comment explaining the F-0278 fix, quoting the very words the fix removed
    ("the same 'you're all set' signal as the green badge"). A gate that reads its own
    post-mortem as a live defect is one that can never go green.
    """
    seen = {}
    for m in SUCCESS_COPY.finditer(src):
        if in_comment(cspans, m.start()):
            continue
        seen[m.start()] = 'copy "%s"' % m.group(0).strip()[:48]
    flat, imap = flatten_tags(masked, src)
    for m in SUCCESS_COPY.finditer(flat):
        p = imap[m.start()] if m.start() < len(imap) else None
        if p is None or in_comment(cspans, p):
            continue
        if any(abs(p - q) < 6 for q in seen):
            continue
        seen[p] = 'copy "%s" (split across tags)' % re.sub(r"\s+", " ",
                                                           m.group(0).strip())[:48]
    for m in SUCCESS_ICON.finditer(src):
        if not in_code[m.start()] or in_comment(cspans, m.start()):
            continue
        col = SUCCESS_COLOR.search(src[m.start(): m.start() + 240])
        if col:
            seen[m.start()] = "%s coloured %s" % (m.group(0).strip(), col.group(0))
    return sorted(seen.items())


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


# ------------------------------------------------------------------------------ scan

def atoms_for(masked, delims, mmap, ifs, consts, pos):
    atoms = []
    for op in enclosing_openers(delims, pos):
        sp, keep = guard_spans(masked, delims, op, pos)
        code = guard_code(masked, sp, keep).strip()
        if not code or len(code) > MAX_GUARD:
            continue
        atoms_at_end(code, atoms, consts)
    flow_atoms(masked, delims, mmap, ifs, pos, atoms, consts)
    return atoms


def trigger_for(atoms):
    for t, pol in atoms:
        table = EMPTY_WHEN_TRUE if pol in ("T", "wT") else EMPTY_WHEN_FALSE
        for rx, label in table:
            if rx.search(t):
                return label, t
    return None, None


def analyse(rel, src):
    findings, oks = [], []
    delims, in_code = lex(src)
    cspans = comment_spans(src)
    masked = mask_source(src, in_code, cspans)
    mmap = match_map(delims)
    ifs = if_ranges(masked, delims, mmap)
    consts = const_map(masked, mmap)
    decls = declarations(masked, mmap)

    for pos, what in success_sites(src, masked, in_code, cspans):
        atoms = atoms_for(masked, delims, mmap, ifs, consts, pos)
        trig, trig_atom = trigger_for(atoms)
        via = ""
        hops = 0
        cur = pos
        # If nothing here controls the render, the copy may have been hoisted into a
        # same-file binding (a message constant, a local render helper, a local
        # component). Follow that binding to its use sites - v2 and v7.
        while trig is None and hops < MAX_HOPS:
            hops += 1
            inner = None
            for name, a, b in decls:
                if a <= cur < b and (inner is None or a > inner[1]):
                    inner = (name, a, b)
            if inner is None:
                break
            name, a, b = inner
            refs = [m.start() for m in re.finditer(r"\b%s\b" % re.escape(name), masked)
                    if not (a <= m.start() < b) and in_code[m.start()]
                    and not in_comment(cspans, m.start())][:MAX_REFS]
            if not refs:
                break
            best = None
            for r in refs:
                ra = atoms_for(masked, delims, mmap, ifs, consts, r)
                t, ta = trigger_for(ra)
                if t is not None:
                    best = (r, ra, t, ta)
                    break
            if best is None:
                cur = a
                continue
            cur, atoms, trig, trig_atom = best
            via = " via `%s` used at line %d" % (name, line_of(src, cur))

        if trig is None:
            continue  # not rendered on an empty/zero/null/error branch

        def first(pol, *rxs):
            return next((t for t, p in atoms if p == pol
                         and any(rx.search(t) for rx in rxs)), None)

        settled = (first("F", LOADING_RX, NOT_READY_RX)
                   or first("T", READY_RX, NOT_LOADING_RX))
        started = (first("F", FAILED_RX, UNSTARTED_RX)
                   or first("T", EVER_RX, NOT_FAILED_RX))
        # Inversion is a REASON attached to a missing guard, never a verdict on its own.
        # A branch that asserts `!loading` and also mentions `loading` inside a weakly
        # polarised sub-expression (the negation of a compound `isEmpty` helper, say) is
        # still correctly guarded; only an unqualified branch that reaches the
        # reassurance *through* a loading/error signal is inverted.
        inverted = []
        if not settled:
            inverted += [t for t, p in atoms if p in ("T", "wT") and LOADING_RX.search(t)]
        if not started:
            inverted += [t for t, p in atoms if p in ("T", "wT") and FAILED_RX.search(t)]

        loc = "%s:%d" % (rel, line_of(src, pos))
        chain = ", ".join("%s%s" % ("" if p in ("T", "wT") else "NOT ", t)
                          + ("?" if p.startswith("w") else "")
                          for t, p in atoms[:10])
        if settled and started and not inverted:
            oks.append("%s  %s on %s%s, qualified by NOT/ready `%s` + NOT/ever `%s`"
                       % (loc, what, trig, via, settled[:40], started[:40]))
            continue
        missing = []
        if not settled:
            missing.append("no settled-data guard asserted (needs loading/pending FALSE "
                           "or status==='ready' TRUE), so this fires before the data "
                           "that would justify it has arrived")
        if not started:
            missing.append("no never-started-or-failed guard asserted (needs error / "
                           "first-run / isEmpty FALSE, or a has-ever signal TRUE), so "
                           "'you finished' is indistinguishable from 'you never began' "
                           "and from 'the fetch died'")
        if inverted:
            missing.append("INVERTED guard: %s is asserted TRUE (or merely possible) on "
                           "the branch that renders the reassurance, so the success copy "
                           "is reachable while loading or after a failure"
                           % ", ".join(sorted(set(inverted))[:3]))
        findings.append((loc, what + via, trig, missing, chain[:220]))
    return findings, oks


def candidates(root):
    files = []
    for rel in SCAN_DIRS:
        base = os.path.join(root, rel)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for name in sorted(filenames):
                if not name.endswith(".tsx") or SKIP_NAME.search(name):
                    continue
                p = os.path.join(dirpath, name)
                files.append((os.path.relpath(p, root).replace("\\", "/"), p))
    return sorted(set(files))


# ------------------------------------------------------------------------- arguments

def main(argv):
    root, only, listing = None, None, False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("-h", "--help"):
            print(__doc__ or "see header comment")
            print("usage: empty-state-misleads.py [repo_root] [--list] [--only REGEX]")
            return 64
        if a == "--list":
            listing = True
            i += 1
            continue
        if a == "--only":
            if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
                die(64, "- --only needs a regex", ["everything: the gate never ran"])
            try:
                only = re.compile(argv[i + 1])
            except re.error as e:
                die(64, "- --only %r is not a regex: %s" % (argv[i + 1], e),
                    ["everything: the gate never ran"])
            i += 2
            continue
        if a.startswith("--"):
            die(64, "- unknown option %s" % a, ["everything: the gate never ran"])
        if root is not None:
            die(64, "- at most one repo_root", ["everything: the gate never ran"])
        root = os.path.abspath(a)
        i += 1
    root = root or DEFAULT_ROOT

    if not os.path.isdir(root):
        die(2, "- repo root %s does not exist - unavailable" % root,
            ["every candidate file: there was no tree to read"])

    files = candidates(root)
    if only is not None:
        files = [f for f in files if only.search(f[0])]

    # Law 4: a vacuous pass is not a pass.
    if not files:
        die(2, "- 0 candidate files under %s in %s%s - a detector that scanned nothing "
               "cannot report clean; this is unavailable, not green"
               % (" / ".join(SCAN_DIRS), root,
                  (" matching /%s/" % only.pattern) if only else ""),
            ["every candidate file: none were found to open"])

    findings, oks, unreadable = [], [], []
    scanned = 0
    for rel, path in files:
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                src = fh.read()
        except OSError as e:
            unreadable.append("%s (%s)" % (rel, e))
            continue
        scanned += 1
        try:
            f, o = analyse(rel, src)
        except RecursionError:
            unreadable.append("%s (expression nested too deep to reduce)" % rel)
            continue
        findings.extend(f)
        oks.extend(o)

    if scanned == 0:
        die(2, "- %d candidate file(s) found but none could be opened - unavailable"
            % len(files), ["every candidate file: all reads failed"])

    print("* empty-state-misleads [F-0278 F-0349 F-0410 F-0436]")
    print("  scanned %d file(s) under %s%s"
          % (scanned, " / ".join(SCAN_DIRS),
             (" filtered by /%s/" % only.pattern) if only else ""))
    print("  %d success-coded empty branch(es) qualified, %d unqualified"
          % (len(oks), len(findings)))
    if listing:
        for rel, _ in files:
            print("  file %s" % rel)
    for line in oks:
        print("  ok   %s" % line)
    for loc, what, trig, missing, chain in findings:
        print("  FAIL %s" % loc)
        print("       renders %s on a branch controlled by %s" % (what, trig))
        print("       asserted: %s" % chain)
        for m in missing:
            print("       -> %s" % m)

    extra = []
    if unreadable:
        extra.append("%d file(s) could not be opened or reduced and were skipped: %s"
                     % (len(unreadable), ", ".join(unreadable[:6])))

    if findings:
        print("VERDICT: broken - %d unqualified success-coded empty state(s)"
              % len(findings))
        emit(extra)
        return 1

    if unreadable:
        print("VERDICT: unavailable - %d candidate file(s) unreadable; nothing here "
              "is green" % len(unreadable))
        emit(extra)
        return 2

    print("VERDICT: proved - every success-coded render reachable from an empty, zero, "
          "null or error branch is qualified by both a settled-data guard and a "
          "never-started-or-failed guard, each asserted in the direction that makes "
          "the reassurance honest")
    emit(extra)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
