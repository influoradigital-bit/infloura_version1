#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# proof-os gate | class: dead-control
# records: F-0405, F-0411, F-0412
#
# THE CLASS
#   A rendered, enabled, clickable UI control whose value never reaches the
#   server, or whose handler does nothing.
#     F-0405  four of eight brand discovery filters (language, engagement rate,
#             verified-only, sort order) never leave the browser; they re-filter
#             only the 20 rows already fetched.
#     F-0411  discovery facets hardcoded client-side; the backend facets
#             endpoint has no consumer.
#     F-0412  creator campaign filters applied in memory AFTER the page was
#             fetched, so they prune the page rather than the base.
#
# WHY tsc / eslint / a screenshot / a human skim all miss it
#   Every one of those defects is well-typed, lint-clean, renders correctly and
#   visibly "works" — moving the slider does change the list. The bug is that
#   the change is applied to the wrong population. Nothing about the syntax is
#   wrong, so no syntactic tool can see it. This gate therefore does NOT assert
#   that a control renders. It traces:
#
#       control (JSX on*= handler)  ->  state setter  ->  state identifier
#         -> is that identifier inside a REQUEST PAYLOAD ?      (alive)
#         -> or only inside a client-side prune ?               (DEAD)
#
#   The precision lever is pagination. Client-side filtering of a fully-fetched
#   list is legitimate. Client-side filtering of a SERVER-PAGINATED list is
#   broken by construction: it prunes the current page, not the result set.
#   So a finding requires all four of:
#       1. the file issues a server call whose payload object carries a
#          pagination key (page/offset/cursor/skip/pageSize/... — matched as
#          OBJECT KEYS, not as bare identifiers)
#       2. that payload is built from SOME state (proving the wiring exists)
#       3. the state var is bound to a JSX control the user can operate
#       4. the state var is consumed only by a client-side prune and appears
#          in NO request payload in the file
#
# HARDENING (2026-09-02, after an independent verifier refuted v1)
#   v1 was a detector for one lexical shape, not for the class. Every one of
#   these silenced it while leaving the defect fully intact; all are now closed:
#     - renaming the useState setter off the `set` prefix   -> setter name is
#       no longer read at all; the tuple's 2nd element IS the setter
#     - routing the handler through `function f(){setX(v)}` -> function decls,
#       `const f = function(){}`, useCallback/useMemo wrappers and
#       expression-bodied arrows are all setter-alias sources now, closed
#       transitively
#     - filtering with a plain `if (x) rows = rows.filter(...)` outside any
#       useMemo                                             -> guard conditions
#       around a prune count as client-side consumption
#     - naming the HTTP client `sdk.` instead of api/apiClient/meeraApi
#       -> server calls are detected structurally (awaited member call, or a
#          member call carrying a pagination-shaped object literal), not from a
#          three-name whitelist
#     - pagination keys named skip/take (or first/after, offset, cursor, ...)
#     - pruning with .reduce/.flatMap instead of .filter/.sort/.slice
#     - saving the file as .jsx, or putting it one directory over in src/pages
#       -> the scan root is REPO/src and both extensions are walked. F-0412
#          lives in src/pages and was invisible to v1's default invocation.
#   Four more shapes, not on the verifier's list but equally ordinary, were
#   found by adversarial probing during this rewrite and are closed too:
#     - a payload assembled into a variable and spread in
#       (`const paging = {page, limit}; api.search({...paging, q})`) -- object
#       literals are resolved one hop, for BOTH the pagination lever and the
#       "does this value reach the server" test. The second half matters more:
#       without it, a value that IS correctly sent inside a spread object would
#       be reported as dead. That is a false positive this gate must not make.
#     - pagination carried in a URL query string
#       (`fetch(`/api/creators?page=${p}&limit=20`)`) -- strip_noise preserves
#       offsets, so the original argument text is re-read for query keys
#     - `const visible = verifiedOnly ? rows.filter(...) : rows` -- a ternary
#       condition guarding a prune, which is neither an `if` nor an argument
#     - `rows.sort(cmp)` where the comparator is a named local reading the
#       state -- prune arguments resolve to local function bodies
#   Precision added at the same time, so the wider net does not invent
#   findings: a prune whose receiver is a MODULE-LEVEL constant is ignored
#   (filtering a static option list is not a paginated-list defect), and a
#   single weak pagination key (`{limit: 20}`, `{size: 16}`) is not pagination.
#
#   Rule B (no-op handler) produced 2 verified false positives in src/pages by
#   flagging `<TaxIdentityForm onSubmitted={() => {}} />` — an optional
#   completion callback on a child component, which is idiomatic React. It now
#   fires only on a host (lowercase) element or a known interactive primitive,
#   and only for a genuine interaction prop.
#
#   NOT CLOSED, and honestly out of reach of a grep: this gate reasons about
#   one file at a time.
#     - state lifted out of the component is invisible. src/admin/pages/
#       AuditLogPage.tsx and EmailQueuePage.tsx are paginated admin tables
#       whose page/filter state lives in a `useAuditLog()` hook in another
#       module; this gate sees no useState and says nothing about either.
#       A control bound through a prop callback (`onApply={setFilters}`) is
#       likewise untraceable from the child.
#     - F-0411 (a backend endpoint with no consumer ANYWHERE) is a
#       whole-program question. It is named in the records above because the
#       class covers it, but no rule here implements it; do not read a pass
#       as evidence about F-0411.
#     - `{limit: 100}` with no offset -- a "top N" fetch -- is deliberately
#       not treated as pagination, so a client filter over a truncated list
#       is a false negative here.
#   Proving a control is truly dead end-to-end needs a runtime click test
#   against a seeded server: fetch page 1, operate the control, assert a
#   second request went out or that the result set changed beyond the page.
#   This gate is a cheap pre-filter for the shape, not a proof of liveness.
#
#   SELF-FALSIFICATION: before scanning anything, the gate runs its own
#   analyser over in-memory fixtures — a defect with renamed setters that MUST
#   be reported, and legitimate code that MUST NOT be. If either assertion
#   fails the gate exits 2 (unavailable), never 0. A detector that can no
#   longer detect must not be able to report a pass.
#
# EXIT CODES
#   0  clean
#   1  at least one dead control found (file:line printed for each)
#   2  cannot run (no source tree, unreadable files, self-test failure) OR zero
#      candidate files scanned. Exit 2 is "unavailable", NOT a pass.
# ---------------------------------------------------------------------------

import os
import re
import sys

# ---- resolve paths from the script's own location, not the cwd -------------
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir))
SCAN_ROOT = os.path.join(REPO, "src")

# Allow an override so the gate can be pointed at a fixture tree.
if len(sys.argv) > 1:
    SCAN_ROOT = os.path.abspath(sys.argv[1])
    try:
        inside = os.path.commonpath([SCAN_ROOT, REPO]) == REPO
    except ValueError:  # different drive
        inside = False
    if not inside:
        REPO = SCAN_ROOT

EXTS = (".tsx", ".jsx")
SKIP_DIRS = {"node_modules", "__tests__", "__mocks__", "__snapshots__", ".git", "dist", "build"}
SKIP_SUFFIX = (".test.tsx", ".test.jsx", ".spec.tsx", ".spec.jsx", ".stories.tsx", ".stories.jsx")

# Pagination, as OBJECT KEYS in a request payload. A "strong" key means the
# call is unambiguously fetching one window of a larger result set; two "weak"
# keys together mean the same (`{first, after}`, `{size, start}`). One weak key
# alone is not enough -- `{size: 16}` is an icon, not a page.
PAG_STRONG = {
    "page", "pagenumber", "pageno", "pageindex", "offset", "skip", "cursor",
    "pagesize", "perpage", "per_page", "page_size", "pagetoken", "page_token",
}
PAG_WEAK = {"limit", "take", "size", "first", "start", "after", "before", "count", "top"}

# Roots/leaves that are language or DOM machinery, never a server round-trip.
CALL_ROOT_DENY = {
    "Promise", "JSON", "Object", "Array", "Math", "Number", "String", "Boolean",
    "Date", "console", "window", "document", "navigator", "localStorage",
    "sessionStorage", "React", "Intl", "URL", "URLSearchParams", "crypto",
    "process", "Error", "Symbol", "Map", "Set", "WeakMap", "Reflect", "globalThis",
    "z", "Zod", "clsx", "cn", "dayjs", "moment", "Number", "e", "event", "ev",
}
CALL_LEAF_DENY = {
    "json", "text", "blob", "formData", "arrayBuffer", "then", "catch", "finally",
    "all", "allSettled", "race", "resolve", "reject", "map", "filter", "forEach",
    "reduce", "sort", "slice", "splice", "join", "push", "pop", "shift", "unshift",
    "includes", "indexOf", "lastIndexOf", "find", "findIndex", "some", "every",
    "flat", "flatMap", "concat", "reverse", "fill", "keys", "values", "entries",
    "from", "of", "isArray", "stringify", "parse", "now", "floor", "ceil", "round",
    "min", "max", "abs", "random", "pow", "sqrt", "log", "error", "warn", "info",
    "debug", "trace", "getItem", "setItem", "removeItem", "clear",
    "preventDefault", "stopPropagation", "focus", "blur", "scrollTo", "scrollIntoView",
    "addEventListener", "removeEventListener", "setTimeout", "clearTimeout",
    "setInterval", "clearInterval", "toFixed", "toString", "toLowerCase",
    "toUpperCase", "trim", "split", "replace", "replaceAll", "match", "matchAll",
    "test", "startsWith", "endsWith", "padStart", "padEnd", "repeat", "charAt",
    "toLocaleString", "toLocaleDateString", "toLocaleTimeString", "toISOString",
    "getTime", "valueOf", "bind", "call", "apply", "current", "sort",
}
# Roots that read as an HTTP client even when the call is not awaited.
RE_APIISH_ROOT = re.compile(
    r"(?i)^(?:api|apis|apiclient|client|http|https|axios|sdk|service|services|"
    r"backend|gql|graphql|supabase|trpc|rest|fetcher|meeraapi)$|"
    r"(?:Api|API|Client|Service|Sdk|SDK|Gateway|Repo|Repository)$"
)

RE_STATE = re.compile(
    # The setter is the tuple's SECOND ELEMENT, whatever it is called. v1
    # hard-required a `set` prefix; a pure rename then silenced the gate.
    r"\bconst\s*\[\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\]\s*=\s*"
    r"(?:React\.)?useState\b"
)
RE_HANDLER_ATTR = re.compile(r"\bon[A-Z][A-Za-z]*\s*=\s*\{")
RE_MEMBER_CALL = re.compile(
    r"(?P<aw>\bawait\s+)?\b(?P<root>[A-Za-z_$][\w$]*)"
    r"(?P<mid>(?:\s*\.\s*[A-Za-z_$][\w$]*)+)\s*\("
)
RE_BARE_CALL = re.compile(r"\b(?:fetch|mutate|mutateAsync|refetch|useSWR|useQuery)\s*\(")
RE_PRUNE = re.compile(
    r"\.\s*(?:filter|sort|slice|reduce|flatMap|toSorted|toSpliced|orderBy|sortBy)\s*\("
)
RE_USEMEMO = re.compile(r"\b(?:React\.)?useMemo\s*\(")
RE_SWITCH = re.compile(r"\bswitch\s*\(\s*([A-Za-z_$][\w$]*)\s*\)\s*\{")
RE_IF = re.compile(r"\bif\s*\(")
RE_IDENT = re.compile(r"[A-Za-z_$][\w$]*")
RE_OBJ_KEY = re.compile(r"[{,]\s*([A-Za-z_$][\w$]*)\s*[:,}]")
RE_URL_PAGINATION = re.compile(
    r"[?&](?:page|pageNumber|pageNo|pageIndex|offset|skip|cursor|pageSize|"
    r"per_page|perPage|page_size|limit|take)\s*="
)
RE_NOOP_HANDLER = re.compile(
    r"\b(?P<prop>on[A-Z][A-Za-z]*)\s*=\s*\{\s*(?:\(\s*[^)]*\)|[A-Za-z_$][\w$]*)?\s*=>\s*"
    r"(?:\{\s*\}|undefined|null|void\s+0)\s*\}"
)
RE_TAG_NAME = re.compile(r"<\s*([A-Za-z][\w.$]*)")

# --- function headers whose body is a balanced block ------------------------
RE_FN_DECL = re.compile(r"\bfunction\s+(?P<name>[A-Za-z_$][\w$]*)\s*(?:<[^<>{(]*>)?\s*\(")
RE_CONST_FN = re.compile(
    r"\bconst\s+(?P<name>[A-Za-z_$][\w$]*)\s*(?::\s*[^=;]{0,160})?=\s*"
    r"(?:(?:React\.)?use(?:Callback|Memo)\s*\(\s*)?(?:async\s+)?"
    r"(?:function\s*[A-Za-z_$][\w$]*\s*|function\s*)?(?:<[^<>{(]*>)?\s*\("
)
RE_CONST_ARROW1 = re.compile(
    r"\bconst\s+(?P<name>[A-Za-z_$][\w$]*)\s*=\s*(?:async\s+)?[A-Za-z_$][\w$]*\s*=>\s*\{"
)
# expression-bodied arrow: `const toggle = (l) => setLangs(...)`
RE_CONST_EXPR_FN = re.compile(
    r"\bconst\s+(?P<name>[A-Za-z_$][\w$]*)\s*(?::\s*[^=;]{0,160})?=\s*(?:async\s+)?"
    r"(?:\([^()]{0,200}\)|[A-Za-z_$][\w$]*)\s*(?::[^=;{]{0,120})?=>\s*(?P<tail>[^;\n]{0,400})"
)
RE_BODY_START = re.compile(r"\A[^{;()]{0,200}?\{")
RE_HAS_JSX = re.compile(r"</[A-Za-z]|/>|<[A-Za-z][\w.]*\s+[A-Za-z-]+\s*=")

# Controls Rule B is willing to speak about.
INTERACTIVE_PRIMITIVES = {
    "Button", "IconButton", "SubmitButton", "Checkbox", "Switch", "Toggle",
    "ToggleGroup", "ToggleGroupItem", "Select", "SelectTrigger", "SelectItem",
    "Slider", "Input", "TextInput", "Textarea", "RadioGroup", "RadioGroupItem",
    "MenuItem", "DropdownMenuItem", "DropdownMenuCheckboxItem",
    "DropdownMenuRadioItem", "TabsTrigger", "CommandItem", "Combobox",
    "DatePicker", "Calendar", "Link", "NavLink", "Pressable", "TouchableOpacity",
}
INTERACTION_PROPS = {
    "onClick", "onChange", "onInput", "onSubmit", "onSelect", "onKeyDown",
    "onKeyUp", "onKeyPress", "onDoubleClick", "onMouseDown", "onMouseUp",
    "onToggle", "onDrop", "onValueChange", "onCheckedChange", "onPressedChange",
    "onPress", "onSearch", "onSortChange",
}

# Identifiers that are never a filter/query value even when they look like one.
NEVER_A_QUERY = {
    "isOpen", "open", "setOpen", "viewMode", "step", "activeTab", "tab",
    "expanded", "collapsed", "hovered", "focused", "isSubmitting", "loading",
    "isLoading", "error", "isError", "submitting", "saving", "isSaving",
    "isFilterOpen", "showFilters", "drawerOpen", "menuOpen", "sidebarOpen",
    "dialogOpen", "isEditing", "editing", "copied", "theme", "density",
}


def strip_noise(src):
    """Blank out comments and string/template bodies so identifier scans do not
    match prose or class names. Length is preserved so offsets stay valid."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
        elif c == "/" and nxt == "*":
            out[i] = out[i + 1] = " "
            i += 2
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            i += 2
        elif c in "\"'`":
            quote = c
            i += 1
            while i < n:
                if src[i] == "\\":
                    out[i] = " "
                    if i + 1 < n and src[i + 1] != "\n":
                        out[i + 1] = " "
                    i += 2
                    continue
                if src[i] == quote:
                    break
                # keep ${...} interpolations visible: identifiers there are real
                if quote == "`" and src[i] == "$" and i + 1 < n and src[i + 1] == "{":
                    depth = 0
                    while i < n:
                        if src[i] == "{":
                            depth += 1
                        elif src[i] == "}":
                            depth -= 1
                            if depth == 0:
                                break
                        i += 1
                    i += 1
                    continue
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            i += 1
        else:
            i += 1
    return "".join(out)


def balanced(src, start, opener, closer):
    """Return (text, end_index) for the balanced region beginning at src[start]
    == opener. Returns (None, start) if unbalanced."""
    if start >= len(src) or src[start] != opener:
        return None, start
    depth, i, n = 0, start, len(src)
    while i < n:
        if src[i] == opener:
            depth += 1
        elif src[i] == closer:
            depth -= 1
            if depth == 0:
                return src[start:i + 1], i + 1
        i += 1
    return None, start


def idents(text):
    return set(RE_IDENT.findall(text or ""))


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def pagination_shape(args):
    """'strong' | 'weak' | None for a call's argument list. Keys, not bare
    identifiers: `(page - 1) * 20` passed positionally is arithmetic,
    `{ skip: ..., take: 20 }` is a window into a result set. One weak key alone
    is not pagination -- `{ size: 16 }` is an icon."""
    keys = {k.lower() for k in RE_OBJ_KEY.findall(args or "")}
    if keys & PAG_STRONG:
        return "strong"
    if len(keys & PAG_WEAK) >= 2:
        return "weak"
    return None


def module_level_consts(src):
    """Names declared at brace depth 0 -- module constants. Filtering a static
    option list (`languages.filter(...)`) is not a paginated-list defect, and
    excluding these keeps the widened scan from inventing that finding."""
    names = set()
    depth = 0
    for m in re.finditer(r"[{}]|\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)", src):
        tok = m.group(0)
        if tok == "{":
            depth += 1
        elif tok == "}":
            depth = max(0, depth - 1)
        elif depth == 0 and m.group(1):
            names.add(m.group(1))
    return names


def receiver_root(src, dot_idx):
    """Root identifier of the member expression whose `.` sits at dot_idx."""
    i = dot_idx
    while i > 0 and src[i - 1] in " \t\n\r?":
        i -= 1
    if i == 0 or src[i - 1] in ")]":
        return None  # a call/index result: receiver unknown, do not exclude
    j = i
    while j > 0 and (src[j - 1].isalnum() or src[j - 1] in "_$."):
        j -= 1
    chain = src[j:i]
    return chain.split(".")[0] if chain else None


def prune_hits(src, module_consts):
    """Prune calls (.filter/.sort/.slice/.reduce/.flatMap/...) that operate on
    something other than a module-level constant."""
    out = []
    for m in RE_PRUNE.finditer(src):
        root = receiver_root(src, m.start())
        if root and root in module_consts:
            continue
        out.append(m)
    return out


def iter_fn_bodies(src):
    """Yield (name, body_text, body_start) for every named function-ish body:
    declarations, const arrows, const function-expressions, and
    useCallback/useMemo-wrapped arrows. v1 saw only `const f = (...) => {`, so
    moving a handler into a `function` declaration erased the whole alias
    table."""
    seen = set()
    for rx in (RE_FN_DECL, RE_CONST_FN):
        for m in rx.finditer(src):
            popen = m.end() - 1
            _args, after = balanced(src, popen, "(", ")")
            if after == popen:
                continue
            mb = RE_BODY_START.match(src[after:after + 220])
            if not mb:
                continue
            bstart = after + mb.end() - 1
            body, _ = balanced(src, bstart, "{", "}")
            if body is None or (m.group("name"), bstart) in seen:
                continue
            seen.add((m.group("name"), bstart))
            yield m.group("name"), body, bstart
    for m in RE_CONST_ARROW1.finditer(src):
        bstart = src.index("{", m.end() - 1)
        body, _ = balanced(src, bstart, "{", "}")
        if body is not None and (m.group("name"), bstart) not in seen:
            seen.add((m.group("name"), bstart))
            yield m.group("name"), body, bstart


def object_literals(src):
    """`const params = { page, limit, q }` -> {'params': '{ page, limit, q }'}.
    A payload assembled into a variable and spread into the call
    (`api.search({ ...params, q })`) is ordinary house style; without this the
    pagination key and every state field in it are invisible."""
    objs = {}
    for m in re.finditer(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(?::[^=;{]{0,160})?=\s*\{", src):
        body, _ = balanced(src, m.end() - 1, "{", "}")
        if body:
            objs[m.group(1)] = body
    return objs


def server_calls(src, raw=None, objs=None, pag_objs=frozenset()):
    """Yield (kind, args_text, call_index, paginated) for calls that plausibly
    hit a server. Structural, not a client-name whitelist: an awaited member
    call, a member call on an api-shaped object, a member call whose payload is
    pagination-shaped, or fetch/axios/mutate."""
    def pag(args, args_span, awaited, apiish):
        shape = pagination_shape(args)
        if shape == "strong":
            return True
        if shape == "weak" and (awaited or apiish):
            return True
        if pag_objs and (idents(args) & pag_objs):
            return True
        # `fetch('/api/creators?page=1&limit=20')` -- strip_noise preserves
        # offsets, so the ORIGINAL text of the argument list is recoverable.
        if raw is not None and args_span:
            if RE_URL_PAGINATION.search(raw[args_span[0]:args_span[1]]):
                return True
        return False

    for m in RE_MEMBER_CALL.finditer(src):
        root = m.group("root")
        leaf = m.group("mid").split(".")[-1].strip()
        if root in CALL_ROOT_DENY or leaf in CALL_LEAF_DENY:
            continue
        astart = m.end() - 1
        args, aend = balanced(src, astart, "(", ")")
        if args is None:
            continue
        awaited = bool(m.group("aw"))
        apiish = bool(RE_APIISH_ROOT.search(root))
        paginated = pag(args, (astart, aend), awaited, apiish)
        if not (awaited or apiish or paginated):
            continue
        yield ("member", args, m.start(), paginated)
    for m in RE_BARE_CALL.finditer(src):
        astart = m.end() - 1
        args, aend = balanced(src, astart, "(", ")")
        if args is None:
            continue
        yield ("bare", args, m.start(), pag(args, (astart, aend), True, True))


def guard_conditions_around_prunes(src, module_consts):
    """Identifiers in an `if (...)` whose consequent prunes a list. F-0405's
    verified-only filter and the `if (x) rows = rows.filter(...)` idiom both
    live here -- neither is a filter ARGUMENT and neither needs a useMemo."""
    out = set()
    for m in RE_IF.finditer(src):
        cond, after = balanced(src, m.end() - 1, "(", ")")
        if cond is None:
            continue
        rest = src[after:after + 2000]
        stripped = rest.lstrip()
        if stripped.startswith("{"):
            region, _ = balanced(src, after + (len(rest) - len(stripped)), "{", "}")
            region = region or rest
        else:
            region = stripped.split(";")[0][:400]
        if prune_hits(region, module_consts):
            out |= idents(cond)
    return out


def ternary_conditions_around_prunes(src, module_consts):
    """`const visible = verifiedOnly ? rows.filter(...) : rows` -- the state is
    neither a prune argument nor inside an `if`, but it is unmistakably being
    applied to the fetched page."""
    out = set()
    for m in prune_hits(src, module_consts):
        p = m.start()
        window = src[max(0, p - 400):p]
        q = -1
        for i in range(len(window) - 1, -1, -1):
            if window[i] != "?":
                continue
            nxt = window[i + 1] if i + 1 < len(window) else ""
            prv = window[i - 1] if i > 0 else ""
            if nxt in ".?" or prv == "?":
                continue  # optional chaining / nullish coalescing
            q = i
            break
        if q < 0:
            continue
        # a ternary branch contains no statement boundary; if one intervenes,
        # that `?` belonged to some earlier expression.
        if re.search(r"[;{}]", window[q:]):
            continue
        pre = window[:q]
        cut = 0
        for mm in re.finditer(r"(?<![=!<>+\-*/&|])=(?![=>])|[;,(){}\n]|\breturn\b", pre):
            cut = mm.end()
        out |= idents(pre[cut:])
    return out


def analyse(path, raw):
    """Return a list of (line, message) findings for one file."""
    src = strip_noise(raw)
    findings = []
    try:
        rel = os.path.relpath(path, REPO).replace("\\", "/")
    except ValueError:
        rel = path.replace("\\", "/")

    def fmt(items):
        return [(ln, "%s:%d: %s" % (rel, ln, msg)) for ln, msg in items]

    # -- rule B: a handler that provably does nothing --------------------
    # Only on something the user can actually operate. A `() => {}` passed for
    # an optional completion callback on a child component (onSubmitted,
    # onDone, onSuccess) is idiomatic React, not a dead control -- v1 reported
    # two of those in src/pages/creator-settings.tsx and both were false.
    for m in RE_NOOP_HANDLER.finditer(src):
        prop = m.group("prop")
        if prop not in INTERACTION_PROPS:
            continue
        tag = None
        i = m.start()
        while i > 0:
            i -= 1
            c = src[i]
            if c == ">" and src[i - 1] not in "=/" :
                break
            if c == "<":
                mt = RE_TAG_NAME.match(src[i:])
                tag = mt.group(1) if mt else None
                break
        if not tag:
            continue
        if not (tag[0].islower() or tag in INTERACTIVE_PRIMITIVES):
            continue
        findings.append(
            (line_of(src, m.start()),
             "no-op handler: <%s %s={() => {}}> is wired to an empty function" % (tag, prop))
        )

    # -- rule A: control state that never reaches the server -------------
    states = {}  # var -> (setter, line)
    for m in RE_STATE.finditer(src):
        states[m.group(1)] = (m.group(2), line_of(src, m.start()))
    if not states:
        return fmt(findings)
    setters = {setter: var for var, (setter, _ln) in states.items()}

    # request payloads: identifiers that DO reach the server
    objs = object_literals(src)
    pag_objs = {n for n, txt in objs.items() if pagination_shape(txt)}
    sent = set()
    paginated = False
    for _kind, args, _idx, is_pag in server_calls(src, raw, objs, pag_objs):
        names = idents(args)
        sent |= names
        # a payload assembled into a variable counts as sent, twice removed
        for _hop in range(2):
            for nm in list(names & set(objs)):
                extra = idents(objs[nm])
                names |= extra
                sent |= extra
        if is_pag:
            paginated = True

    # Precision lever. Without a paginated server call in this file, a
    # client-side filter is legitimate and we say nothing.
    if not paginated:
        return fmt(findings)
    # And the payload must be built from at least one piece of state, else
    # there is no evidence this component was ever meant to filter serverside.
    if not (sent & set(states)):
        return fmt(findings)

    bodies = list(iter_fn_bodies(src))

    # Server-acknowledged state: if the setter fires AFTER a server call in the
    # setter's OWN (innermost) function body, the state is the CONSEQUENCE of a
    # round-trip (optimistic delete/rollback bookkeeping), not an unsent query
    # parameter. Without this, `hiddenIds` in campaigns-list.tsx -- set only
    # after `await api.campaigns.delete(...)` resolves -- reads as a dead
    # filter. It must be the INNERMOST body: the component function itself
    # contains every server call and every setter, so scoping this any wider
    # marks the whole file server-acked and silences the gate.
    # A render body (one containing JSX) is never the scope that acknowledges a
    # round-trip -- it contains every fetch and every handler in the component.
    spans = [(b, b + len(body), body) for _n, body, b in bodies
             if not RE_HAS_JSX.search(body)]
    server_acked = set()
    for setter, var in setters.items():
        for sm in re.finditer(r"\b%s\s*\(" % re.escape(setter), src):
            pos = sm.start()
            enclosing = [s for s in spans if s[0] <= pos < s[1]]
            if not enclosing:
                continue
            lo, _hi, body = min(enclosing, key=lambda s: s[1] - s[0])
            calls = [lo + idx for _k, _a, idx, _p in server_calls(body)]
            if calls and min(calls) < pos:
                server_acked.add(var)
                break

    # Setter aliases: a local helper (`const toggleLanguage = (l) => {...}`, or
    # `function handleVerifiedChange(v) { setVerifiedOnly(v) }`) that calls a
    # setter is, for our purposes, the same thing as the setter. Closed
    # transitively, so a handler two hops from the setter is still a control.
    fn_texts = {}  # local fn name -> its body/expression text
    for name, body, _bstart in bodies:
        fn_texts.setdefault(name, "")
        fn_texts[name] += body

    alias = {}  # local fn name -> state vars it mutates
    for name, body, _bstart in bodies:
        names = idents(body)
        hit = {setters[s] for s in setters if s in names}
        if hit:
            alias.setdefault(name, set()).update(hit)
    for m in RE_CONST_EXPR_FN.finditer(src):
        tail = m.group("tail")
        fn_texts[m.group("name")] = fn_texts.get(m.group("name"), "") + tail
        names = idents(tail)
        hit = {setters[s] for s in setters if s in names}
        if hit:
            alias.setdefault(m.group("name"), set()).update(hit)
    for _ in range(3):  # transitive closure, bounded
        grew = False
        for name, body, _bstart in bodies:
            names = idents(body)
            for other, vars_ in list(alias.items()):
                if other != name and other in names:
                    before = len(alias.get(name, set()))
                    alias.setdefault(name, set()).update(vars_)
                    grew = grew or len(alias[name]) != before
        if not grew:
            break

    # controls: state whose setter (or a setter alias) is invoked from a JSX
    # event handler
    control_bound = set()
    for m in RE_HANDLER_ATTR.finditer(src):
        body, _ = balanced(src, m.end() - 1, "{", "}")
        if not body:
            continue
        names = idents(body)
        control_bound |= {setters[s] for s in setters if s in names}
        for fn, vars_ in alias.items():
            if fn in names:
                control_bound |= vars_

    # client-side consumption: the derived-list pipeline. Prunes of a
    # module-level constant (a static option list) are excluded throughout --
    # those are not the fetched population.
    module_consts = module_level_consts(src)
    client_only = set()
    #  (a) a useMemo whose body prunes rows -- any state read inside it is
    #      being applied client-side.
    for m in RE_USEMEMO.finditer(src):
        body, _ = balanced(src, m.end() - 1, "(", ")")
        if body and prune_hits(body, module_consts):
            client_only |= idents(body)
    #  (b) arguments of any prune call (.filter/.sort/.slice/.reduce/.flatMap),
    #      resolving a named predicate/comparator (`rows.sort(cmp)`) to its body
    for m in prune_hits(src, module_consts):
        args, _ = balanced(src, m.end() - 1, "(", ")")
        if not args:
            continue
        names = idents(args)
        for nm in list(names & set(fn_texts)):
            names |= idents(fn_texts[nm])
        client_only |= names
    #  (c) the discriminant of a switch whose body prunes
    for m in RE_SWITCH.finditer(src):
        body, _ = balanced(src, m.end() - 1, "{", "}")
        if body and prune_hits(body, module_consts):
            client_only.add(m.group(1))
    #  (d) the condition of any `if` guarding a prune, useMemo or not
    client_only |= guard_conditions_around_prunes(src, module_consts)
    #  (e) the condition of a ternary whose branch prunes
    client_only |= ternary_conditions_around_prunes(src, module_consts)

    for var in sorted(control_bound & client_only):
        if var in NEVER_A_QUERY or var in sent or var in server_acked:
            continue
        _setter, ln = states[var]
        findings.append(
            (
                ln,
                "dead control: `%s` is bound to a control and prunes the fetched rows, "
                "but never appears in any request payload -- it filters the current page only"
                % var,
            )
        )
    return fmt(findings)


# ---------------------------------------------------------------------------
# Self-falsification. The gate must prove it can still fail before it is
# allowed to pass. Each fixture is the SAME defect the verifier used to evade
# v1, written in a different house style, plus legitimate code that must stay
# silent.
# ---------------------------------------------------------------------------
_SELF_MUST_FAIL = [
    # renamed setter + function-declaration handler + prune outside any useMemo
    ("renamed-setter", """
export function L() {
  const [rows, applyRows] = React.useState([]);
  const [cursorIndex, applyCursor] = React.useState(1);
  const [verifiedOnly, applyVerified] = React.useState(false);
  React.useEffect(() => {
    const load = async () => {
      const res = await sdk.creators.search({ q: '', skip: cursorIndex * 20, take: 20 });
      applyRows(res.items);
    };
    void load();
  }, [cursorIndex]);
  function handleVerified(v) { applyVerified(v); }
  let visible = rows;
  if (verifiedOnly) visible = visible.filter((r) => r.verified);
  return (<div>
    <input type="checkbox" onChange={(e) => handleVerified(e.target.checked)} />
    <button onClick={() => applyCursor(cursorIndex + 1)}>More</button>
  </div>);
}
"""),
    # no-op handler on a real control
    ("noop-control", """
export function B() {
  return <button onClick={() => {}}>Save</button>;
}
"""),
]
_SELF_MUST_PASS = [
    # the control's value IS in the payload -- the correct fix must go green
    ("value-is-sent", """
export function L() {
  const [rows, setRows] = React.useState([]);
  const [page, setPage] = React.useState(1);
  const [verifiedOnly, setVerifiedOnly] = React.useState(false);
  React.useEffect(() => {
    const load = async () => {
      const res = await api.creators.search({ verifiedOnly: verifiedOnly, page: page, limit: 20 });
      setRows(res.items);
    };
    void load();
  }, [page, verifiedOnly]);
  return (<div>
    <input type="checkbox" onChange={(e) => setVerifiedOnly(e.target.checked)} />
    {rows.map((r) => <div key={r.id}>{r.name}</div>)}
    <button onClick={() => setPage(page + 1)}>More</button>
  </div>);
}
"""),
    # client-side filtering of a NON-paginated, fully fetched list is legitimate
    ("not-paginated", """
export function L() {
  const [rows, setRows] = React.useState([]);
  const [q, setQ] = React.useState('');
  React.useEffect(() => {
    const load = async () => { setRows((await api.creators.all({ q: q })).items); };
    void load();
  }, [q]);
  const visible = React.useMemo(() => rows.filter((r) => r.n.includes(q)), [rows, q]);
  return (<div><input onChange={(e) => setQ(e.target.value)} />{visible.length}</div>);
}
"""),
    # an optional completion callback is not a control
    ("optional-callback", """
export function S() {
  return (<div>
    <TaxIdentityForm onSubmitted={() => {}} />
    <KycIdentityForm onSubmitted={() => {}} />
  </div>);
}
"""),
]


def self_test():
    """Return a list of failure strings; empty means the detector is honest."""
    bad = []
    for name, code in _SELF_MUST_FAIL:
        got = analyse(os.path.join(REPO, "__selftest_%s.tsx" % name), code)
        if not got:
            bad.append("fixture '%s' MUST be reported and was not" % name)
    for name, code in _SELF_MUST_PASS:
        got = analyse(os.path.join(REPO, "__selftest_%s.tsx" % name), code)
        if got:
            bad.append("fixture '%s' MUST NOT be reported, got: %s"
                       % (name, "; ".join(m for _l, m in got)))
    return bad


def main():
    broken = self_test()
    if broken:
        print("dead-control: FATAL -- self-test failed, the detector cannot be trusted:",
              file=sys.stderr)
        for b in broken:
            print("  - %s" % b, file=sys.stderr)
        return 2

    if not os.path.isdir(SCAN_ROOT):
        print("dead-control: FATAL -- scan root not found: %s" % SCAN_ROOT, file=sys.stderr)
        return 2

    files = []
    for dirpath, dirnames, filenames in os.walk(SCAN_ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn.endswith(EXTS) and not fn.endswith(SKIP_SUFFIX):
                files.append(os.path.join(dirpath, fn))

    if not files:
        print(
            "dead-control: FATAL -- scanned 0 candidate files under %s. "
            "A detector that matches nothing is the failure mode this gate guards against."
            % SCAN_ROOT,
            file=sys.stderr,
        )
        return 2

    all_findings = []
    unreadable = 0
    for path in sorted(files):
        try:
            with open(path, "r", encoding="utf-8", errors="strict") as fh:
                raw = fh.read()
        except (OSError, UnicodeDecodeError):
            unreadable += 1
            continue
        all_findings.extend(analyse(path, raw))

    if unreadable and unreadable == len(files):
        print("dead-control: FATAL -- every candidate file was unreadable.", file=sys.stderr)
        return 2

    print("dead-control gate | class: dead-control | records: F-0405, F-0411, F-0412")
    print("self-test: %d must-fail + %d must-pass fixtures held"
          % (len(_SELF_MUST_FAIL), len(_SELF_MUST_PASS)))
    print("scanned %d candidate .tsx/.jsx files under %s" % (len(files), SCAN_ROOT))
    if unreadable:
        print("warning: %d file(s) unreadable and skipped" % unreadable)

    if all_findings:
        print("")
        for _ln, msg in sorted(all_findings, key=lambda f: f[1]):
            print(msg)
        print("")
        print("FAIL: %d dead control(s) found" % len(all_findings))
        return 1

    print("PASS: 0 dead controls found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
