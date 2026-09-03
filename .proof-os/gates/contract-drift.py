#!/usr/bin/env python3
"""
proof-os gate for the `contract-drift` class (F-0408, F-0409, F-0431).

WHAT THE CLASS IS
    The frontend and the backend disagree about a contract. The disagreement is invisible to
    every compiler in the build: `tsc` only checks TypeScript against TypeScript, `javac` only
    checks Java against Java, and nothing checks the wire between them. The two directions fail
    differently and both are defects:

      (A) DEAD CAPABILITY  - the server declares a @RequestParam the client never sends.
          F-0408: CreatorController accepts sortBy=engagement|rate|price_low|price_high|
                  rating|relevance; creatorSearchQuery() never emits sortBy at all, so six
                  advertised sort orders are unreachable from the product.
          F-0431: CreatorController.search reads sixteen params; creatorSearchQuery emits ten.
                  categories, languages, minEngagementRate, maxEngagementRate, isVerified and
                  sortBy are implemented server-side and never sent.

      (B) IGNORED INPUT    - the client sends a query param the server does not bind. The
          request succeeds, Spring drops the value on the floor, and the user's filter simply
          has no effect. Same silence, opposite direction.

WHAT THIS GATE CHECKS
    Per-endpoint query-parameter parity. For every Spring handler it extracts the declared
    @RequestParam names; for every frontend `request(VERB, path, {query: ...})` call site it
    extracts the query keys actually emitted; it matches the two by (verb, normalised path) and
    diffs the name sets in both directions.

    A call site that sends NO query params at all is not reported unless the server declares a
    REQUIRED param (no defaultValue, not required=false, not Optional<>) - sending nothing to an
    all-optional endpoint is a legitimate use of server defaults, but omitting a required param
    is a guaranteed 400 and is reported.

WHY THIS VERSION EXISTS  (hardening after an adversarial refutation, 2026-09-02)
    A fresh-context verifier defeated the previous version. Its decisive demonstration: rewriting
    `function creatorSearchQuery(params)` to `const creatorSearchQuery = (params) =>` - a purely
    cosmetic refactor that does not touch the defect - made F-0431, this gate's own headline
    record, disappear (2 instances -> 1) while the defect remained fully present. The old gate
    keyed detection on one hand-written call/declaration syntax. Eleven of twelve constructed
    drift instances evaded it. This version:

      * resolves helpers by MEANING, not by declaration keyword: `function f(){}`,
        `const f = (p) => {}`, `const f = function(){}`, `f(p) {}` object methods, `f: (p) =>`
        object properties, and helpers imported from another module (whole-tree index);
      * accepts double-quoted verbs and paths, and verbs/paths hoisted into a `const`;
      * accepts `params:` as well as `query:` as the options key;
      * reads *Resource.java / *Api.java / *Endpoint.java handlers, not only *Controller.java -
        in fact any .java carrying a Spring mapping annotation;
      * scans .test./.spec. files too, so a filename cannot hide a call site;
      * ASSERTS COVERAGE. Non-zero counts are not enough: a parsing regression that silently
        drops a call site from the compared set now exits 2 (UNAVAILABLE), not 0. Two
        mechanisms: a committed coverage floor (COVERAGE_FLOOR) and per-record sentinels
        (SENTINEL_HANDLERS / SENTINEL_CALL_SITES) that name the exact F-0408/F-0431 handler and
        call site and the exact key each side must still be seen to resolve. If the parser stops
        seeing them, the gate reports UNAVAILABLE instead of a clean run.

    It also closes the three IGNORED-INPUT false positives the same verifier demonstrated:
    key extraction is scoped to the object the helper actually RETURNS (so `opts.signal = ...`
    in a helper body is no longer read as a query key), only top-level keys of an object literal
    are taken (so a nested object value contributes its own key, not its children's), and a
    helper shared by two different endpoints suppresses the client-sends-server-ignores
    direction for those sites, since a shared builder legitimately emits a superset.

    Anything the parser cannot fully resolve (an unresolvable spread, an opaque expression) is
    NOT guessed at: the site is excluded from the compared set, which lowers `compared`, which
    trips the committed floor, which exits 2. Opacity degrades to UNAVAILABLE, never to clean.

WHAT THIS GATE CANNOT SEE  (stated so the exit code is not over-read)
    F-0409 is in this class but is NOT of this shape: the server keeps unpriced creators by
    ORing an isNull into rateOverlap while the client's price filter drops them. Both sides
    send and accept the same param names - they disagree about the *meaning*. No name-level
    static check can reach that; it needs a test that compares server-returned ids against
    client-rendered ids for one identical filter set. This gate does not claim to cover it.

    Query keys computed at runtime (`q[name] = v` with a variable `name`, a key list built from
    an array, a spread of an imported object) are not resolvable by any grep. They are counted
    as unresolved and subtract from coverage rather than being guessed.

    Response-shape drift (a TS interface declaring a field the Java record never returns) is
    the other half of the class and is covered by ci/dto-drift-check.py, which diffs curated
    TS-interface / Java-record pairs. This gate deliberately does not duplicate it.

USAGE   python .proof-os/gates/contract-drift.py [--json] [--verbose]
EXIT    0 = every matched endpoint agrees
        1 = at least one endpoint disagrees (file:line printed per instance)
        2 = the gate could not run, scanned zero candidates, matched zero endpoints, fell below
            the committed coverage floor, or lost a sentinel.
            Exit 2 is UNAVAILABLE. It is not a pass.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

# .proof-os/gates/contract-drift.py -> repo root
ROOT = Path(__file__).resolve().parent.parent.parent

JAVA_ROOT = ROOT / "influora-api" / "src" / "main" / "java"
FE_ROOTS = [ROOT / "src"]

HTTP_VERBS = ("GET", "POST", "PUT", "PATCH", "DELETE")

MAPPING_ANN = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "PatchMapping": "PATCH",
    "DeleteMapping": "DELETE",
}

# ---------------------------------------------------------------------------
# COMMITTED COVERAGE FLOOR  (see "WHY THIS VERSION EXISTS", point: ASSERTS COVERAGE)
#
# Non-zero is not proof of coverage. These are the counts this gate is known to reach on this
# repository. Falling below any of them means the parser lost ground - a refactor it can no
# longer read, a tree it can no longer find - and that is UNAVAILABLE (2), never clean (0).
# Raise them when the repo legitimately grows; lower them ONLY with a written reason.
# ---------------------------------------------------------------------------
COVERAGE_FLOOR = {
    "java_files": 78,            # .java files carrying a Spring mapping annotation
    "fe_files": 560,             # frontend .ts/.tsx files scanned
    "endpoints_matched": 155,    # (verb, path) pairs present on BOTH sides
    "call_sites_compared": 21,   # call sites whose query keys were fully resolved
}

# Per-record sentinels. Each names a fact the parser MUST still be able to see. These survive a
# genuine fix to the defect (the handler keeps declaring the param; the call site keeps sending
# the key it always sent) but not a parsing regression, which is exactly the discrimination the
# refutation showed was missing.
SENTINEL_HANDLERS = [
    # (verb, path, param that must still be seen bound server-side, record)
    ("GET", "/creators", "sortBy", "F-0408/F-0431"),
    ("GET", "/creator/applications", "status", "F-0431-adjacent"),
]
SENTINEL_CALL_SITES = [
    # (verb, path, file, query key the FE parser must still resolve, record)
    ("GET", "/creators", "src/lib/api.ts", "minFollowers", "F-0408/F-0431"),
    ("GET", "/creator/applications", "src/lib/api.ts", "page", "F-0431-adjacent"),
]

# ---------------------------------------------------------------------------
# generic source-scanning helpers
# ---------------------------------------------------------------------------

OPEN = {"(": ")", "{": "}", "[": "]"}
CLOSE = {v: k for k, v in OPEN.items()}

JS_KEYWORDS = {
    "if", "for", "while", "switch", "catch", "function", "return", "typeof", "new", "await",
    "do", "else", "try", "finally", "throw", "case", "delete", "void", "in", "of", "const",
    "let", "var", "class", "export", "import", "default", "yield", "async",
}


def _balanced(text: str, start: int) -> int:
    """Index just past the bracket opened at `start`. -1 if unbalanced.

    String and template literals are skipped so a `)` inside a message never closes a call.
    """
    if start >= len(text) or text[start] not in OPEN:
        return -1
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c in ("'", '"', "`"):
            quote = c
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    break
                i += 1
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j == -1 else j
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i)
            i = n if j == -1 else j + 2
            continue
        if c in OPEN:
            depth += 1
        elif c in CLOSE:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def _line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def _norm_path(raw: str) -> str:
    """Normalise a URL path so a Java template and a TS template compare equal."""
    p = raw.strip()
    p = p.split("?", 1)[0]
    p = re.sub(r"\$\{[^}]*\}", "*", p)   # TS  `/deals/${id}/messages`
    p = re.sub(r"\{[^}]*\}", "*", p)     # Java "/{creatorId}"
    p = re.sub(r"/+", "/", p)
    if len(p) > 1:
        p = p.rstrip("/")
    if not p.startswith("/"):
        p = "/" + p
    return p


def _split_top_level(params: str) -> list[str]:
    """Split on commas that are not inside brackets, strings or template literals."""
    parts, depth, buf = [], 0, []
    i, n = 0, len(params)
    while i < n:
        c = params[i]
        if c in ('"', "'", "`"):
            q = c
            buf.append(c)
            i += 1
            while i < n:
                if params[i] == "\\":
                    buf.append(params[i])
                    if i + 1 < n:
                        buf.append(params[i + 1])
                    i += 2
                    continue
                buf.append(params[i])
                if params[i] == q:
                    break
                i += 1
            i += 1
            continue
        if c == "/" and i + 1 < n and params[i + 1] == "/":
            j = params.find("\n", i)
            i = n if j == -1 else j
            continue
        if c == "/" and i + 1 < n and params[i + 1] == "*":
            j = params.find("*/", i)
            i = n if j == -1 else j + 2
            continue
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(c)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf))
    return [p for p in parts if p.strip()]


# ---------------------------------------------------------------------------
# Java side: which query params does each handler actually bind?
# ---------------------------------------------------------------------------

ANN_PATH_RE = re.compile(r'^\s*\(\s*(?:(?:value|path)\s*=\s*)?"([^"]*)"')
REQ_PARAM_NAME_RE = re.compile(r'@RequestParam\s*\(\s*(?:(?:value|name)\s*=\s*)?"([^"]+)"')
IDENT_RE = re.compile(r"([A-Za-z_$][A-Za-z0-9_$]*)\s*$")
MAPPING_ANY_RE = re.compile(r"@(?:" + "|".join(MAPPING_ANN) + r"|RequestMapping)\b")


def _skip_annotations(text: str, pos: int) -> int:
    """Advance past any run of @Annotation / @Annotation(...) starting at pos."""
    n = len(text)
    while pos < n:
        while pos < n and text[pos].isspace():
            pos += 1
        if pos < n and text[pos] == "/":  # a comment between annotations
            if text.startswith("//", pos):
                j = text.find("\n", pos)
                pos = n if j == -1 else j
                continue
            if text.startswith("/*", pos):
                j = text.find("*/", pos)
                pos = n if j == -1 else j + 2
                continue
        if pos >= n or text[pos] != "@":
            return pos
        pos += 1
        while pos < n and (text[pos].isalnum() or text[pos] in "_$."):
            pos += 1
        save = pos
        while save < n and text[save].isspace():
            save += 1
        if save < n and text[save] == "(":
            end = _balanced(text, save)
            if end == -1:
                return pos
            pos = end
    return pos


def _java_candidates() -> list[Path]:
    """Every .java file that carries a Spring mapping annotation.

    The old gate globbed *Controller.java only, so moving a handler into `FooResource.java`
    hid it. Naming is not evidence; the annotation is.
    """
    if not JAVA_ROOT.is_dir():
        return []
    out = []
    for f in sorted(JAVA_ROOT.rglob("*.java")):
        try:
            head = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if MAPPING_ANY_RE.search(head):
            out.append(f)
    return out


def _param_is_required(part: str) -> bool:
    """A @RequestParam with no defaultValue, not required=false, not Optional<>."""
    if re.search(r"required\s*=\s*false", part):
        return False
    if "defaultValue" in part:
        return False
    if re.search(r"\bOptional\s*<", part):
        return False
    return True


def scan_java() -> tuple[dict, int]:
    """-> ({(verb, path): {...}}, files_scanned)"""
    endpoints: dict[tuple[str, str], dict] = {}
    files = _java_candidates()
    scanned = 0

    for f in files:
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if not re.search(r"@(?:" + "|".join(MAPPING_ANN) + r")\b", text):
            continue  # e.g. SecurityConfig, which mentions RequestMapping but declares no handler
        scanned += 1

        # class-level base path: the @RequestMapping that appears before `class X`
        base = ""
        cls = re.search(r"\b(?:public\s+)?(?:final\s+)?class\s+\w+", text)
        head = text[: cls.start()] if cls else text
        m = re.search(r'@RequestMapping\s*\(\s*(?:(?:value|path)\s*=\s*)?"([^"]*)"', head)
        if m:
            base = m.group(1)

        for am in re.finditer(r"@(" + "|".join(MAPPING_ANN) + r")\b", text):
            verb = MAPPING_ANN[am.group(1)]
            after = text[am.end():]
            sub = ""
            pm = ANN_PATH_RE.match(after)
            if pm:
                sub = pm.group(1)

            pos = _skip_annotations(text, am.start())
            # `pos` now sits on the method's return type; the first '(' is the param list.
            open_paren = -1
            i, n = pos, len(text)
            while i < n:
                if text[i] == "(":
                    open_paren = i
                    break
                if text[i] in ";{}":
                    break
                i += 1
            if open_paren == -1:
                continue
            end = _balanced(text, open_paren)
            if end == -1:
                continue
            params_text = text[open_paren + 1: end - 1]

            names: set[str] = set()
            required: set[str] = set()
            catch_all = False
            for part in _split_top_level(params_text):
                if "Pageable" in part or "@ModelAttribute" in part or "@RequestBody" in part:
                    if "@RequestParam" not in part:
                        catch_all = catch_all or ("Pageable" in part or "@ModelAttribute" in part)
                        continue
                if "@RequestParam" not in part:
                    continue
                if re.search(r"\bMap\s*<", part) or re.search(r"\bMultiValueMap\s*<", part):
                    catch_all = True  # binds everything; name-level diff is meaningless
                    continue
                nm = REQ_PARAM_NAME_RE.search(part)
                name = None
                if nm:
                    name = nm.group(1)
                else:
                    im = IDENT_RE.search(part.strip())
                    if im:
                        name = im.group(1)
                if name:
                    names.add(name)
                    if _param_is_required(part):
                        required.add(name)

            key = (verb, _norm_path(base + "/" + sub if sub else base))
            slot = endpoints.setdefault(
                key, {"params": set(), "required": set(), "catch_all": False, "sites": []}
            )
            slot["params"] |= names
            slot["required"] |= required
            slot["catch_all"] = slot["catch_all"] or catch_all
            slot["sites"].append(
                (str(f.relative_to(ROOT)).replace("\\", "/"), _line_of(text, am.start()))
            )

    return endpoints, scanned


# ---------------------------------------------------------------------------
# Frontend side: which query keys does each call site actually emit?
#
# Everything below resolves by MEANING rather than by one declaration keyword. The unit of
# resolution is `Resolved(keys, ok)`; `ok=False` means "this gate could not read it", which
# excludes the site from the compared set (and therefore lowers coverage) instead of pretending
# the key set is empty.
# ---------------------------------------------------------------------------

# arg1 = 'GET' | "GET" | IDENT ;  arg2 = `tpl` | 'str' | "str" | IDENT
_VERB_ALT = "|".join(HTTP_VERBS)
CALL_RE = re.compile(
    r"\(\s*(?P<verb>'(?:" + _VERB_ALT + r")'|\"(?:" + _VERB_ALT + r")\"|[A-Za-z_$][\w$]*)"
    r"\s*,\s*(?P<path>`[^`]*`|'[^']*'|\"[^\"]*\"|[A-Za-z_$][\w$]*)\s*[,)]"
)

STR_CONST_RE_T = r"\b(?:const|let|var)\s+{name}\s*(?::[^=;]*?)?=\s*(?P<q>['\"`])(?P<val>[^'\"`]*)(?P=q)"

MAX_RESOLVE_DEPTH = 4


class Resolved:
    __slots__ = ("keys", "ok", "helpers")

    def __init__(self, keys=None, ok=True, helpers=None):
        self.keys = set(keys or ())
        self.ok = ok
        self.helpers = set(helpers or ())

    def merge(self, other: "Resolved") -> "Resolved":
        self.keys |= other.keys
        self.ok = self.ok and other.ok
        self.helpers |= other.helpers
        return self


def _top_level_keys(obj_text: str) -> Resolved:
    """Keys declared at depth 1 of an object literal. Nested objects contribute their own key
    only - not their children's, which was one of the demonstrated false positives."""
    t = obj_text.strip()
    if not t.startswith("{"):
        return Resolved(ok=False)
    end = _balanced(t, 0)
    inner = t[1: end - 1] if end != -1 else t[1:]
    res = Resolved()
    for seg in _split_top_level(inner):
        s = seg.strip()
        if not s:
            continue
        if s.startswith("..."):
            # a spread contributes unknown keys; refuse to guess
            res.ok = False
            res.helpers.add(("spread", s[3:].strip()))
            continue
        km = re.match(r"^(?:'([A-Za-z_$][\w$]*)'|\"([A-Za-z_$][\w$]*)\"|([A-Za-z_$][\w$]*))\s*:", s)
        if km:
            res.keys.add(km.group(1) or km.group(2) or km.group(3))
            continue
        sm = re.match(r"^([A-Za-z_$][\w$]*)\s*$", s)      # shorthand { page, limit }
        if sm:
            res.keys.add(sm.group(1))
            continue
        if re.match(r"^\[", s):                            # computed key - unreadable
            res.ok = False
            continue
        # a method shorthand or anything else we do not model
        mm = re.match(r"^([A-Za-z_$][\w$]*)\s*\(", s)
        if mm:
            res.keys.add(mm.group(1))
            continue
        res.ok = False
    return res


def _read_body_after_params(text: str, after_params: int) -> tuple[str, str] | None:
    """Given the index just past a parameter list's ')', return (kind, body).

    kind == 'block' -> body includes the braces;  kind == 'expr' -> a concise arrow body.
    """
    i, n = after_params, len(text)
    angle = 0
    while i < n:
        c = text[i]
        if c == "<":
            angle += 1
        elif c == ">":
            angle = max(0, angle - 1)
        elif angle == 0:
            if text.startswith("=>", i):
                i += 2
                while i < n and text[i].isspace():
                    i += 1
                if i >= n:
                    return None
                if text[i] == "{":
                    end = _balanced(text, i)
                    return ("block", text[i:end] if end != -1 else text[i:])
                if text[i] == "(":
                    end = _balanced(text, i)
                    return ("expr", text[i + 1:end - 1] if end != -1 else text[i:])
                j = i
                depth = 0
                while j < n:
                    ch = text[j]
                    if ch in OPEN:
                        depth += 1
                    elif ch in CLOSE:
                        if depth == 0:
                            break
                        depth -= 1
                    elif ch in ";\n" and depth == 0:
                        break
                    j += 1
                return ("expr", text[i:j])
            if c == "{":
                end = _balanced(text, i)
                return ("block", text[i:end] if end != -1 else text[i:])
            if c in ";=":
                return None
        i += 1
    return None


def _find_helper_def(name: str, filetext: str) -> tuple[str, str] | None:
    """Locate a helper's body by MEANING, across every declaration form we can read.

    This is the heart of the hardening: the previous gate recognised only `function NAME(`,
    so `const NAME = (p) => {...}` - a no-op refactor - silently zeroed the finding.
    """
    if name in JS_KEYWORDS:
        return None
    esc = re.escape(name)

    #  function NAME(...)  /  async function NAME(...)  /  export function NAME(...)
    for m in re.finditer(r"\bfunction\s*\*?\s*" + esc + r"\s*(?:<[^<>]*>)?\s*\(", filetext):
        op = filetext.rfind("(", m.start(), m.end())
        end = _balanced(filetext, op)
        if end == -1:
            continue
        body = _read_body_after_params(filetext, end)
        if body:
            return body

    #  const NAME = ... / let / var  and  NAME: ...  (object property)
    rhs_starts = []
    for m in re.finditer(r"\b(?:const|let|var)\s+" + esc + r"\s*(?::[^=;]*?)?=\s*", filetext):
        rhs_starts.append(m.end())
    for m in re.finditer(r"(?:^|[{,;])\s*" + esc + r"\s*:\s*", filetext, re.M):
        rhs_starts.append(m.end())
    for start in rhs_starts:
        body = _read_rhs_function(filetext, start)
        if body:
            return body

    #  NAME(...) { ... }   object-method / class-method shorthand
    for m in re.finditer(r"(?:^|[{,;\n])\s*(?:async\s+)?" + esc + r"\s*(?:<[^<>]*>)?\s*\(", filetext, re.M):
        op = filetext.rfind("(", m.start(), m.end())
        end = _balanced(filetext, op)
        if end == -1:
            continue
        j = end
        while j < len(filetext) and filetext[j].isspace():
            j += 1
        if j < len(filetext) and filetext[j] == "{":
            close = _balanced(filetext, j)
            return ("block", filetext[j:close] if close != -1 else filetext[j:])
    return None


def _read_rhs_function(text: str, i: int) -> tuple[str, str] | None:
    """Parse the right-hand side of `= ` / `: ` as a function, arrow or object literal."""
    n = len(text)
    while i < n and text[i].isspace():
        i += 1
    if text.startswith("async", i):
        i += 5
        while i < n and text[i].isspace():
            i += 1
    if text.startswith("function", i):
        i += 8
        while i < n and (text[i].isspace() or text[i] == "*"):
            i += 1
        while i < n and (text[i].isalnum() or text[i] in "_$"):
            i += 1
        while i < n and text[i].isspace():
            i += 1
        if i < n and text[i] == "(":
            end = _balanced(text, i)
            if end != -1:
                return _read_body_after_params(text, end)
        return None
    if i < n and text[i] == "(":
        end = _balanced(text, i)
        if end == -1:
            return None
        return _read_body_after_params(text, end)
    if i < n and text[i] == "{":
        end = _balanced(text, i)
        return ("objlit", text[i:end] if end != -1 else text[i:])
    m = re.match(r"([A-Za-z_$][\w$]*)\s*=>", text[i:])          # p => ...
    if m:
        return _read_body_after_params(text, i + m.end() - 2)
    return None


def _return_exprs(block: str) -> list[str]:
    """Every `return <expr>` at any depth inside a block body."""
    out = []
    for m in re.finditer(r"\breturn\b", block):
        i = m.end()
        n = len(block)
        while i < n and block[i] in " \t":
            i += 1
        if i < n and block[i] in ";\n":
            continue
        depth = 0
        j = i
        while j < n:
            c = block[j]
            if c in ("'", '"', "`"):
                q = c
                j += 1
                while j < n:
                    if block[j] == "\\":
                        j += 2
                        continue
                    if block[j] == q:
                        break
                    j += 1
                j += 1
                continue
            if c in OPEN:
                depth += 1
            elif c in CLOSE:
                if depth == 0:
                    break
                depth -= 1
            elif c == ";" and depth == 0:
                break
            j += 1
        out.append(block[i:j])
    return out


def _keys_for_var(var: str, scope: str, index: dict, depth: int) -> Resolved:
    """Keys written onto a local object variable, scoped to THAT variable.

    Scoping to the returned variable is what removes the `opts.signal = ...` false positive:
    assignments to any other object in the helper body are no longer read as query keys.
    """
    esc = re.escape(var)
    res = Resolved()
    m = re.search(r"\b(?:const|let|var)\s+" + esc + r"\s*(?::[^=;]*?)?=\s*", scope)
    if m:
        i = m.end()
        while i < len(scope) and scope[i].isspace():
            i += 1
        if i < len(scope) and scope[i] == "{":
            end = _balanced(scope, i)
            res.merge(_top_level_keys(scope[i:end] if end != -1 else scope[i:]))
        elif re.match(r"new\s+URLSearchParams\s*\(", scope[i:]):
            am = re.search(r"\(", scope[i:])
            if am:
                op = i + am.start()
                end = _balanced(scope, op)
                inner = scope[op + 1:end - 1].strip() if end != -1 else ""
                if inner.startswith("{"):
                    res.merge(_top_level_keys(inner))
        else:
            sub = _keys_from_expression(scope[i:i + 4000], scope, index, depth + 1)
            res.merge(sub)
    res.keys |= set(re.findall(r"\b" + esc + r"\.([A-Za-z_$][\w$]*)\s*(?<![=!<>+\-*/%&|^])=(?!=)", scope))
    res.keys |= set(re.findall(r"\b" + esc + r"\[\s*['\"]([A-Za-z_$][\w$]*)['\"]\s*\]\s*(?<![=!<>])=(?!=)", scope))
    res.keys |= set(re.findall(r"\b" + esc + r"\.(?:set|append)\(\s*['\"]([A-Za-z_$][\w$]*)['\"]", scope))
    res.keys.discard("length")
    return res


def _keys_from_helper_body(kind: str, body: str, filetext: str, index: dict, depth: int) -> Resolved:
    if depth > MAX_RESOLVE_DEPTH:
        return Resolved(ok=False)
    if kind in ("expr", "objlit"):
        return _keys_from_expression(body, filetext, index, depth + 1)
    rets = _return_exprs(body)
    if not rets:
        return Resolved(ok=False)
    res = Resolved()
    for r in rets:
        res.merge(_keys_from_expression(r, body, index, depth + 1, filetext=filetext))
    return res


def _keys_from_expression(expr: str, scope: str, index: dict, depth: int,
                          filetext: str | None = None) -> Resolved:
    """Resolve an expression that is supposed to evaluate to a query object."""
    if depth > MAX_RESOLVE_DEPTH:
        return Resolved(ok=False)
    e = expr.strip()
    if not e:
        return Resolved(ok=False)
    lookup = filetext if filetext is not None else scope

    # ternary: union both branches
    tm = _split_ternary(e)
    if tm:
        res = Resolved()
        for branch in tm:
            res.merge(_keys_from_expression(branch, scope, index, depth + 1, filetext))
        return res

    if e.startswith("{"):
        res = _top_level_keys(e)
        for kind_, spread in list(res.helpers):
            if kind_ == "spread":
                sub = _keys_from_expression(spread, scope, index, depth + 1, filetext)
                if sub.ok:
                    res.keys |= sub.keys
                    res.ok = True
        res.helpers = {h for h in res.helpers if not (isinstance(h, tuple) and h[0] == "spread")}
        return res

    if e.startswith("("):
        end = _balanced(e, 0)
        if end != -1:
            return _keys_from_expression(e[1:end - 1], scope, index, depth + 1, filetext)

    m = re.match(r"^new\s+URLSearchParams\s*\(", e)
    if m:
        end = _balanced(e, e.index("(", m.end() - 1))
        inner = e[e.index("(") + 1:end - 1].strip() if end != -1 else ""
        if inner.startswith("{"):
            return _top_level_keys(inner)
        return Resolved(ok=False)

    # helper call:  buildQuery(params)  /  filters.toQuery(x)
    cm = re.match(r"^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(", e)
    if cm:
        name = cm.group(1).split(".")[-1]
        found = _find_helper_def(name, lookup)
        source = lookup
        if not found:
            for other_text in index.get(name, ()):     # cross-module helper
                found = _find_helper_def(name, other_text)
                if found:
                    source = other_text
                    break
        if not found:
            return Resolved(ok=False)
        sub = _keys_from_helper_body(found[0], found[1], source, index, depth + 1)
        sub.helpers.add(name)
        return sub

    # bare identifier: a local object / URLSearchParams builder
    im = re.match(r"^([A-Za-z_$][\w$]*)$", e)
    if im:
        return _keys_for_var(im.group(1), scope, index, depth)

    return Resolved(ok=False)


def _split_ternary(e: str) -> list[str] | None:
    depth = 0
    qpos = -1
    i, n = 0, len(e)
    while i < n:
        c = e[i]
        if c in ("'", '"', "`"):
            q = c
            i += 1
            while i < n:
                if e[i] == "\\":
                    i += 2
                    continue
                if e[i] == q:
                    break
                i += 1
            i += 1
            continue
        if c in OPEN:
            depth += 1
        elif c in CLOSE:
            depth -= 1
        elif c == "?" and depth == 0 and not e.startswith("?.", i) and not e.startswith("??", i):
            qpos = i
            break
        i += 1
    if qpos == -1:
        return None
    depth = 0
    j = qpos + 1
    while j < n:
        c = e[j]
        if c in OPEN:
            depth += 1
        elif c in CLOSE:
            depth -= 1
        elif c == ":" and depth == 0:
            return [e[qpos + 1:j], e[j + 1:]]
        j += 1
    return None


def _query_key_in_object(obj_text: str, depth: int = 0) -> str | None:
    """The value of a depth-1 `query:` / `params:` key in an options object literal.

    Reading only depth-1 keys means a nested `query:` (a query object used as a *value*)
    cannot be mistaken for the real option. A conditional spread -
    `{ role, ...(x ? { query: {...} } : {}) }` - is a real and common shape in this codebase,
    so spread segments are descended into rather than dropped; dropping them silently cost a
    call site of coverage.
    """
    t = obj_text.strip()
    if not t.startswith("{") or depth > 3:
        return None
    end = _balanced(t, 0)
    inner = t[1:end - 1] if end != -1 else t[1:]
    spreads: list[str] = []
    for seg in _split_top_level(inner):
        s = seg.strip()
        if s.startswith("..."):
            spreads.append(s[3:])
            continue
        km = re.match(r"^(?:'(query|params)'|\"(query|params)\"|(query|params))\s*:", s)
        if km:
            return s[km.end():].strip()
    for sp in spreads:
        i = 0
        while True:
            i = sp.find("{", i)
            if i == -1:
                break
            close = _balanced(sp, i)
            if close == -1:
                break
            got = _query_key_in_object(sp[i:close], depth + 1)
            if got is not None:
                return got
            i = close
    return None


def _options_query_value(args_inner: str) -> tuple[str | None, bool]:
    """(query-value text, options-object-seen) for a `request(VERB, path, {...})` call."""
    parts = _split_top_level(args_inner)
    if len(parts) < 3:
        return None, False
    opts = parts[2].strip()
    if not opts.startswith("{"):
        return None, False
    return _query_key_in_object(opts), True


def _resolve_string_const(name: str, filetext: str) -> str | None:
    m = re.search(STR_CONST_RE_T.format(name=re.escape(name)), filetext)
    return m.group("val") if m else None


def _fe_files() -> list[Path]:
    seen: set[Path] = set()
    out: list[Path] = []
    for root in FE_ROOTS:
        if not root.is_dir():
            continue
        for f in sorted(list(root.rglob("*.ts")) + list(root.rglob("*.tsx"))):
            if f in seen or f.name.endswith(".d.ts"):
                continue
            seen.add(f)
            out.append(f)
    return out


def scan_frontend() -> tuple[dict, int, int]:
    """-> ({(verb, path): [call sites]}, files_scanned, unresolved_sites)

    Two passes: build a whole-tree index of helper-bearing files first, so a helper imported
    from another module resolves; then walk the call sites.
    """
    calls: dict[tuple[str, str], list] = {}
    scanned = 0
    unresolved = 0
    files = _fe_files()

    texts: dict[Path, str] = {}
    for f in files:
        try:
            texts[f] = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        scanned += 1

    # name -> [file text, ...] for every exported/declared symbol that could be a query builder
    index: dict[str, list[str]] = {}
    decl_re = re.compile(
        r"\b(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\("
        r"|\b(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(?::[^=;]*?)?=\s*"
        r"(?:async\s*)?(?:\(|function\b|[A-Za-z_$][\w$]*\s*=>)"
    )
    for f, text in texts.items():
        for m in decl_re.finditer(text):
            nm = m.group(1) or m.group(2)
            if nm and nm not in JS_KEYWORDS:
                index.setdefault(nm, []).append(text)

    for f, text in texts.items():
        if "request" not in text and "fetch" not in text:
            continue

        for m in CALL_RE.finditer(text):
            lead = text[max(0, m.start() - 90): m.start()]
            if "request" not in lead and "fetch" not in lead:
                continue

            raw_verb = m.group("verb")
            if raw_verb[0] in "'\"":
                verb = raw_verb[1:-1]
            else:
                resolved = _resolve_string_const(raw_verb, text)
                if resolved is None or resolved.upper() not in HTTP_VERBS:
                    continue
                verb = resolved.upper()
            if verb not in HTTP_VERBS:
                continue

            raw_path_tok = m.group("path")
            if raw_path_tok[0] in "'\"`":
                raw_path = raw_path_tok[1:-1]
            else:
                resolved = _resolve_string_const(raw_path_tok, text)
                if resolved is None or not resolved.startswith("/"):
                    continue
                raw_path = resolved

            end = _balanced(text, m.start())
            if end == -1:
                continue
            args_inner = text[m.start() + 1: end - 1]

            res = Resolved()
            qs = raw_path.split("?", 1)
            if len(qs) == 2:
                res.keys |= {
                    k for k in re.findall(r"[?&]([A-Za-z_$][\w$]*)=", "?" + qs[1])
                }
            qv, opts_seen = _options_query_value(args_inner)
            if qv is not None:
                res.merge(_keys_from_expression(qv, text, index, 0))

            if not res.ok:
                unresolved += 1
                continue  # opaque -> lowers coverage, never silently "clean"

            key = (verb, _norm_path(raw_path))
            calls.setdefault(key, []).append(
                {
                    "file": str(f.relative_to(ROOT)).replace("\\", "/"),
                    "line": _line_of(text, m.start()),
                    "keys": res.keys,
                    "helpers": {h for h in res.helpers if isinstance(h, str)},
                }
            )
    return calls, scanned, unresolved


# ---------------------------------------------------------------------------

def main() -> int:
    as_json = "--json" in sys.argv
    verbose = "--verbose" in sys.argv

    if not JAVA_ROOT.is_dir():
        print(f"UNAVAILABLE: no Java source tree at {JAVA_ROOT}", file=sys.stderr)
        return 2
    if not any(r.is_dir() for r in FE_ROOTS):
        print(f"UNAVAILABLE: no frontend source tree at {FE_ROOTS[0]}", file=sys.stderr)
        return 2

    try:
        endpoints, java_files = scan_java()
        calls, fe_files, unresolved = scan_frontend()
    except Exception as exc:  # a crashing detector must never read as a pass
        print(f"UNAVAILABLE: contract-drift scan failed: {exc!r}", file=sys.stderr)
        return 2

    if java_files == 0:
        print("UNAVAILABLE: scanned 0 Spring handler .java files - refusing a vacuous pass",
              file=sys.stderr)
        return 2
    if fe_files == 0:
        print("UNAVAILABLE: scanned 0 frontend .ts/.tsx files - refusing a vacuous pass",
              file=sys.stderr)
        return 2

    # helper -> the distinct endpoints it builds queries for; a shared builder legitimately
    # emits a superset, so it must not drive the client-sends-server-ignores direction.
    helper_endpoints: dict[str, set] = {}
    for key, sites in calls.items():
        for site in sites:
            for h in site["helpers"]:
                helper_endpoints.setdefault(h, set()).add(key)

    matched = 0
    compared = 0
    findings: list[dict] = []
    compared_index: dict[tuple, list] = {}

    for key, sites in sorted(calls.items()):
        ep = endpoints.get(key)
        if ep is None:
            continue
        matched += 1
        server = ep["params"]
        for site in sites:
            client = site["keys"]
            if not client:
                # No query params at all. Server defaults are a legitimate choice UNLESS the
                # handler declares a required param, in which case this call cannot succeed.
                needed = sorted(ep["required"] - client)
                if needed and not ep["catch_all"]:
                    compared += 1
                    compared_index.setdefault(key, []).append(site)
                    findings.append({
                        "direction": "server-requires-client-never-sends",
                        "verb": key[0], "path": key[1],
                        "file": site["file"], "line": site["line"],
                        "params": needed,
                        "handler": ep["sites"][0],
                    })
                continue
            compared += 1
            compared_index.setdefault(key, []).append(site)
            missing = sorted(server - client)
            shared = any(len(helper_endpoints.get(h, ())) > 1 for h in site["helpers"])
            extra = sorted(client - server) if (server and not ep["catch_all"] and not shared) else []
            if missing:
                findings.append({
                    "direction": "server-accepts-client-never-sends",
                    "verb": key[0], "path": key[1],
                    "file": site["file"], "line": site["line"],
                    "params": missing,
                    "handler": ep["sites"][0],
                })
            if extra:
                findings.append({
                    "direction": "client-sends-server-ignores",
                    "verb": key[0], "path": key[1],
                    "file": site["file"], "line": site["line"],
                    "params": extra,
                    "handler": ep["sites"][0],
                })

    if matched == 0:
        print("UNAVAILABLE: 0 endpoints matched between the Java handlers and the frontend "
              "client - the matcher is broken, refusing a vacuous pass", file=sys.stderr)
        return 2
    if compared == 0:
        print(f"UNAVAILABLE: matched {matched} endpoints but compared 0 query-param sets - "
              "refusing a vacuous pass", file=sys.stderr)
        return 2

    # ---- coverage assertions: a parsing regression must not read as clean ----
    actual = {
        "java_files": java_files,
        "fe_files": fe_files,
        "endpoints_matched": matched,
        "call_sites_compared": compared,
    }
    shortfalls = [
        f"{k}={actual[k]} < committed floor {v}"
        for k, v in COVERAGE_FLOOR.items() if actual[k] < v
    ]

    lost = []
    for verb, path, param, record in SENTINEL_HANDLERS:
        ep = endpoints.get((verb, path))
        if ep is None:
            lost.append(f"handler {verb} {path} ({record}) is no longer parsed at all")
        elif param not in ep["params"]:
            lost.append(f"handler {verb} {path} ({record}) no longer shows @RequestParam {param}")
    for verb, path, fname, keyname, record in SENTINEL_CALL_SITES:
        hits = [s for s in compared_index.get((verb, path), []) if s["file"] == fname]
        if not hits:
            lost.append(f"call site {fname} for {verb} {path} ({record}) dropped out of the "
                        "compared set")
        elif not any(keyname in s["keys"] for s in hits):
            lost.append(f"call site {fname} for {verb} {path} ({record}) no longer resolves "
                        f"query key {keyname}")

    if shortfalls or lost:
        print("UNAVAILABLE: contract-drift lost coverage - this is NOT a pass.", file=sys.stderr)
        for s in shortfalls:
            print(f"  coverage floor: {s}", file=sys.stderr)
        for s in lost:
            print(f"  sentinel lost : {s}", file=sys.stderr)
        print("  A refactor the parser cannot read looks identical to a repo with no defect. "
              "Fix the parser (or update the committed floor with a reason) before trusting "
              "this gate.", file=sys.stderr)
        if as_json:
            print(json.dumps({
                "class": "contract-drift", "status": "UNAVAILABLE",
                "shortfalls": shortfalls, "sentinels_lost": lost,
                "counts": actual, "instances": len(findings),
            }, indent=2))
        return 2

    if as_json:
        print(json.dumps({
            "class": "contract-drift",
            "records": ["F-0408", "F-0409", "F-0431"],
            "java_files": java_files, "fe_files": fe_files,
            "endpoints_matched": matched, "call_sites_compared": compared,
            "unresolved_sites": unresolved,
            "instances": len(findings), "findings": findings,
        }, indent=2))
    else:
        print("contract-drift gate (F-0408, F-0409, F-0431)")
        print(f"  scanned      : {java_files} Spring handler .java, {fe_files} frontend .ts/.tsx")
        print(f"  matched      : {matched} endpoints, {compared} call sites with resolved query keys")
        print(f"  unresolved   : {unresolved} call sites the parser refused to guess at")
        print(f"  instances    : {len(findings)}")
        if verbose and not findings:
            for key in sorted(set(calls) & set(endpoints)):
                print(f"    ok {key[0]} {key[1]}")
        for fnd in findings:
            print()
            if fnd["direction"] == "server-accepts-client-never-sends":
                print(f"{fnd['file']}:{fnd['line']}: DEAD CAPABILITY - "
                      f"{fnd['verb']} {fnd['path']} accepts params the client never sends")
            elif fnd["direction"] == "server-requires-client-never-sends":
                print(f"{fnd['file']}:{fnd['line']}: MISSING REQUIRED - "
                      f"{fnd['verb']} {fnd['path']} requires params this call omits entirely")
            else:
                print(f"{fnd['file']}:{fnd['line']}: IGNORED INPUT - "
                      f"{fnd['verb']} {fnd['path']} is sent params the server does not bind")
            print(f"    params : {', '.join(fnd['params'])}")
            print(f"    handler: {fnd['handler'][0]}:{fnd['handler'][1]}")

    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
