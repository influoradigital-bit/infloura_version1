#!/usr/bin/env python3
"""Gate for defect class `unreachable-endpoint`.

Ledger records: F-0416, F-0443, F-0444, F-0445, F-0446, F-0447, F-0448, F-0449, F-0450.

THE SHAPE OF THE DEFECT
    A Spring controller method is real: it compiles, it is mapped, its authorization is
    correct, and a reviewer reading the Java file sees nothing wrong. It is nonetheless
    dead, because no shipped frontend code ever sends a request to that path. The canonical
    instance is `POST /wallet/escrow/refund` (EscrowController.java) -- it returns escrowed
    funds to the brand wallet and has no caller anywhere in `src/`.
    Every compile-, test- and authz-level check passes on this defect. Only a reconciliation
    of the two sides of the contract finds it.

WHY THIS GATE IS NOT A GREP
    1. There is MORE THAN ONE frontend API layer. `src/lib/api.ts` is the big one, but
       `src/admin/services/api-contracts.ts` and `src/lib/meera-api.ts` are real clients
       too. Grepping only api.ts reports the entire admin surface as dead.
    2. `src/admin/services/api-contracts.ts` mounts under `API_BASE = '/api/v1/admin'` and
       calls RELATIVE paths -- `apiRequest('/brands/123/suspend')` reaches the backend's
       `/admin/brands/{id}/suspend`. Compared raw, every admin endpoint reads as unreachable.
    3. This repo documents its endpoints in comments, often inside backticks:
       `` * `POST /wallet/topup` -- brands fund the wallet ``. A naive literal scan counts
       that doc comment as a caller and the gate false-greens on exactly the defect it
       exists to catch. Comments are therefore STRIPPED before any path is harvested, on
       BOTH sides. A path that appears only in prose is not a caller, and a `@RestController`
       named only in a javadoc is not a controller.
    4. Some endpoints are SUPPOSED to have no frontend caller -- inbound webhooks, OAuth
       callbacks the browser is redirected to, actuator/health, JWKS, and the internal
       Meera tool endpoints the model server calls. Those are allowlisted with a reason.

WHAT AN ADVERSARY DID TO THE FIRST VERSION OF THIS GATE, AND WHAT CHANGED
    An independent verifier kept the defect and merely restated it, seven ways, and the
    gate went green on every one. Each hole is now closed by construction, not by adding
    one more literal to a list:

    (a) DISCOVERY BY CONTENT, NOT BY LOCATION OR FILENAME. The old gate did a
        non-recursive `os.listdir` on `web/` filtered to `*Controller.java`. Two real
        controllers already live outside that directory today (`integration/meta/webhook/`,
        `integration/razorpay/`), so the same dead route parked in `web/wallet/` or renamed
        `EscrowResource.java` was simply never read. Now: recursive walk of the whole Java
        source root, keeping every file whose COMMENT-STRIPPED text carries an
        `@RestController` / `@Controller` annotation. Filename and directory are irrelevant.

    (b) EVERY MAPPING SHAPE, NOT FOUR ANNOTATIONS. The old METHOD_MAPPING regex knew only
        `@Get/Post/Put/Patch/DeleteMapping`, so the identical route written the ordinary
        Spring way -- `@RequestMapping(value="/refund", method=RequestMethod.POST)` --
        produced no endpoint at all. Now method-level `@RequestMapping` is parsed, verbs are
        read out of `method=` (a bare `@RequestMapping` with no verb maps ALL verbs and is
        reported as ANY), and annotation arguments are read with a balanced-paren scanner
        instead of `\\(([^)]*)\\)`, which could not survive the multi-line
        `@PostMapping(path=..., consumes={A, B})` that MetaPlatformCallbackController
        already writes today.

    (c) ARRAY-VALUED AND MULTI-VALUED MAPPINGS. `@PostMapping(path={"/refund"})` used to
        fall through to the empty string, collapsing the endpoint to its class base
        `/wallet/escrow` -- a path the frontend really does call -- so the dead route
        inherited a live route's caller. Arrays are now expanded, on both the class-level
        and the method-level annotation, and the cross product is checked.

    (d) UNRESOLVABLE PATHS FAIL LOUD. If a mapping's path argument is not a string literal
        or an array of string literals (a constant reference, a concatenation), the gate
        does NOT guess and does NOT emit a mangled path that could silently collide with a
        live route. It exits 2 naming the file and line. Unavailable is not a pass.

    (e) FRONTEND PATHS COME FROM REQUEST CALL SITES ONLY. The old harvester counted any
        "/..." string in any file under `src/` as a caller. A breadcrumb LABEL map, a
        react-router `navigate('/...')`, or a stray constant was enough to green a dead
        endpoint; and the blanket `API_BASE` prefixing meant an unrelated file that happened
        to declare `const API_BASE='/wallet/escrow'` next to a `'/refund'` string greened
        the canonical defect. Now a path counts only when it sits in the path ARGUMENT of a
        call to something that issues a request (`fetch`, `http.request`/`requestWithMeta`/
        `requestOrNull`/`upload`/`uploadForm`/`downloadBlob`, `apiRequest`, `axios.*`,
        `api.get/post/...`), the argument is a real literal, and the module base prefix is
        applied only to that module's own relative call sites.

    (f) ALLOWLIST ENTRIES NEED CONTROLLER EVIDENCE, NOT A LUCKY PREFIX. `^/internal` used
        to exempt anything named `/internal/...`, so the dead money route was exempted by
        being renamed. An exemption is now granted only if the handler ALSO shows the
        server-to-server signature: no `@AuthenticationPrincipal` in its parameter list. A
        route that authenticates a logged-in UI user is a UI route no matter what it is
        called, and its exemption is refused and printed.

WHAT THIS GATE STILL CANNOT SEE  (stated so it is not mistaken for completeness)
    * A path assembled from variables at the call site -- `apiRequest('/wallet/' + kind)`.
      Those call sites are counted and listed as OPAQUE; an endpoint they reach can still
      be reported as unreachable. This is a static reconciliation, not a runtime trace.
    * Whether a caller is itself reachable by navigation. A call inside a component that is
      never mounted counts as a caller here. Only a runtime/E2E test closes that.
    * The HTTP verb. Matching is path-shaped, so a POST endpoint whose path is only ever
      GET-ed still reads as reachable.
    * Whether an allowlisted route is genuinely server-to-server. The evidence check is
      "no authenticated UI principal", which is necessary, not sufficient.

exit 0 = clean . exit 1 = at least one unreachable endpoint . exit 2 = could not run
(exit 2 is UNAVAILABLE, which is not a pass. Scanning zero candidate files is exit 2.)
"""

import os
import re
import sys

# ---------------------------------------------------------------------------
# Locate the repo from this script's own position, so the gate runs from any cwd.
# .proof-os/gates/<this file>  ->  repo root is two levels up.
# ---------------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir))

# The whole Java source root, not the web/ package. Controllers are found by CONTENT.
API_DIR = os.path.join(ROOT, "influora-api", "src", "main", "java")
FE_ROOT = os.path.join(ROOT, "src")

# The three known frontend API layers. Listed so the gate can PROVE it read all of them
# rather than silently reconciling against one; if any is missing the gate is unavailable.
REQUIRED_FE_CLIENTS = [
    os.path.join(FE_ROOT, "lib", "api.ts"),
    os.path.join(FE_ROOT, "lib", "meera-api.ts"),
    os.path.join(FE_ROOT, "admin", "services", "api-contracts.ts"),
]

# ---------------------------------------------------------------------------
# Deliberately server-side-only routes. Each entry carries the reason it has no UI caller.
# An entry is a CLAIM, not a licence: it is honoured only when the handler corroborates it
# (see handler_is_machine_facing). Allowlisting is per-endpoint, so a controller that mixes
# a UI route with a callback -- MetaOAuthController has both /start and /callback -- keeps
# its UI route under test.
# ---------------------------------------------------------------------------
EXEMPT_PATH = [
    (re.compile(r"^/webhooks?(/|$)"), "inbound webhook: the caller is the vendor, not our UI"),
    (re.compile(r"(^|/)oauth/callback(/|$)"), "OAuth callback: the browser is redirected here by the provider"),
    (re.compile(r"^/internal(/|$)"), "internal service-to-service route"),
    (re.compile(r"^/actuator(/|$)"), "actuator: ops/infra probe"),
    (re.compile(r"^/health(/|$)"), "liveness probe: called by the orchestrator"),
    (re.compile(r"^/\.well-known(/|$)"), "well-known discovery document: fetched by machines"),
    (re.compile(r"^/(jwks|\.well-known/jwks\.json)(/|$)"), "JWKS: fetched by token verifiers"),
    (re.compile(r"^/client-errors?(/|$)"), "crash sink: written by the global ErrorBoundary, not a feature"),
    (re.compile(r"^/config/public(/|$)"), "bootstrap config read before the API client exists"),
    (re.compile(r"^/track/click(/|$)"), "affiliate redirect: followed by the visitor's browser"),
]
# Whole-controller claims. Same rule: the handler must corroborate, per method.
EXEMPT_FILE = {
    "ConversionWebhookController.java": "whole controller is an inbound webhook surface",
    "ShopifyWebhookController.java": "whole controller is an inbound webhook surface",
    "WooCommerceWebhookController.java": "whole controller is an inbound webhook surface",
    "HealthController.java": "infra probes only",
    "JwksController.java": "machine-read key material",
    "ClientErrorController.java": "crash sink",
    "PublicConfigController.java": "bootstrap config",
    "MeeraInternalController.java": "internal Meera tool endpoints, called by the model server",
}

# ---------------------------------------------------------------------------
# Backend parsing
# ---------------------------------------------------------------------------
CONTROLLER_ANN = re.compile(r"@(?:RestController|Controller)\b")
VERB_ANN = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping\b")
REQ_ANN = re.compile(r"@RequestMapping\b")
ANY_MAPPING = re.compile(r"@(?:(?:Get|Post|Put|Patch|Delete)Mapping|RequestMapping)\b")
JAVA_STR = re.compile(r'^"((?:[^"\\]|\\.)*)"$')
REQUEST_METHOD = re.compile(r"RequestMethod\s*\.\s*([A-Z]+)")
TYPE_DECL = re.compile(r"^\s*(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\s+)*(class|interface|enum|record|@interface)\b")

IDENT_CH = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_$")

# ---------------------------------------------------------------------------
# Frontend parsing -- request CALL SITES only.
# ---------------------------------------------------------------------------
# Anything whose last dotted segment names a request-issuing helper.
REQ_LAST = {
    "fetch", "request", "requestwithmeta", "requestornull",
    "apirequest", "apifetch", "sendrequest",
    "downloadblob", "upload", "uploadform", "sendbeacon",
}
# Anything of the shape <known client>.<http verb>.
REQ_RECEIVER = {"http", "axios", "api", "client", "apiclient", "httpclient", "xhr", "instance"}
HTTP_VERBS = {"get", "post", "put", "patch", "delete", "del", "head", "options"}
VERB_LITERALS = {"get", "post", "put", "patch", "delete", "head", "options"}

# `const API_BASE = '/api/v1/admin';` -- the module's own mount point.
FE_BASE_NAME = re.compile(r"^(?:API_BASE|BASE_PATH|API_PREFIX|API_ROOT|BASE_URL|API_URL)\w*$")
FE_CONST_DECL = re.compile(
    r"""(?:^|[;{}\n])\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*"""
    r"""(?::\s*[A-Za-z_$][A-Za-z0-9_$<>\[\]. ]*\s*)?=\s*(['"])((?:[^'"\\\n]|\\.)*)\2\s*;"""
)

SKIP_DIRS = {"node_modules", "dist", "build", "coverage", ".git", "__tests__", "__mocks__"}
SKIP_FILE = re.compile(r"\.(test|spec|stories|d)\.[tj]sx?$")

PLACEHOLDER = "\x00"


# ---------------------------------------------------------------------------
# Shared lexing helpers
# ---------------------------------------------------------------------------
def strip_comments(src, backtick=True):
    """Remove // and /* */ comments without eating them out of string literals.

    A doc comment that names an endpoint is documentation, not a caller; a javadoc that
    writes {@code @RestController} is not a controller. Two files in this repo do exactly
    that (security/RequiresPlan.java, service/OnboardingService.java), so stripping runs
    before discovery as well as before harvesting.
    """
    quotes = "'\"`" if backtick else "'\""
    out = []
    i, n = 0, len(src)
    quote = None
    while i < n:
        c = src[i]
        if quote:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(src[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in quotes:
            quote = c
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            end = n if j == -1 else j + 2
            # keep the line count stable so file:line stays truthful
            out.append("\n" * src.count("\n", i, end))
            i = end
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read_parens(src, open_idx, backtick=False):
    """src[open_idx] must be '('. Return (inner_text, index_after_close) or (None, None).

    Balanced over () [] {} and quote-aware, so `consumes = {A, B}` spread over three lines
    -- which MetaPlatformCallbackController writes today -- is read whole. The old
    `\\(([^)]*)\\)` stopped at the first ')' and mangled every such annotation.
    """
    quotes = "'\"`" if backtick else "'\""
    depth = 0
    i, n = open_idx, len(src)
    quote = None
    while i < n:
        c = src[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in quotes:
            quote = c
            i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return src[open_idx + 1:i], i + 1
        i += 1
    return None, None


def split_top(text, backtick=False):
    """Split on top-level commas, respecting nesting and quotes."""
    quotes = "'\"`" if backtick else "'\""
    parts, buf = [], []
    depth = 0
    i, n = 0, len(text)
    quote = None
    while i < n:
        c = text[i]
        if quote:
            buf.append(c)
            if c == "\\" and i + 1 < n:
                buf.append(text[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in quotes:
            quote = c
            buf.append(c)
            i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    parts.append("".join(buf))
    return [p.strip() for p in parts]


def norm(p):
    """Normalise a path from either side into a comparable canonical form."""
    if not p:
        return ""
    p = p.split("?")[0].split("#")[0]
    p = p.replace(PLACEHOLDER, "*")
    p = re.sub(r"\{[^}]*\}", "*", p)              # Spring {id}
    p = re.sub(r"(?<=/):[A-Za-z0-9_]+", "*", p)   # react-router style :id
    p = re.sub(r"/{2,}", "/", p)
    if not p.startswith("/"):
        p = "/" + p
    if p.startswith("/api/v1"):
        p = p[len("/api/v1"):]
    elif p.startswith("/api/"):
        p = p[len("/api"):]
    p = p.rstrip("/")
    # A "*" glued to the end of a segment (".../pending*") is a truncated template, not a
    # path parameter. A real parameter is always its own segment.
    p = re.sub(r"(?<=[^/*])\*+$", "", p).rstrip("/")
    return p or "/"


def seg(p):
    return [s for s in p.split("/") if s != ""]


def matches(be_path, fe_path):
    """Segment-wise compare. The asymmetry here is the whole point.

    A backend "*" is a declared path parameter, so any concrete frontend segment reaches it
    (`GET /users/{id}` is reached by "/users/42").

    A frontend "*" is an interpolated variable, and it may only line up with a backend "*".
    Letting it match a backend LITERAL is what hides the canonical defect: the frontend calls
    `/wallet/escrow/${escrowHoldId}` (the detail read), and if that "*" were allowed to cover
    a literal segment it would silently mark `/wallet/escrow/refund`, `/wallet/escrow/payout`
    and `/wallet/escrow/release` as reached -- three money routes, one of which is the
    original record for this class. It does not reach them; Spring routes a literal segment
    to the literal handler.
    """
    a, b = seg(be_path), seg(fe_path)
    if len(a) != len(b):
        return False
    for x, y in zip(a, b):
        if x == "*":
            continue          # backend path parameter: any frontend segment reaches it
        if y == "*":
            return False      # frontend variable cannot stand in for a fixed route segment
        if x != y:
            return False
    return True


# ---------------------------------------------------------------------------
# Backend: discovery by content
# ---------------------------------------------------------------------------
def find_controllers():
    """Recursively collect every .java file that really carries a controller annotation."""
    if not os.path.isdir(API_DIR):
        return None
    found = []
    for dirpath, dirnames, filenames in os.walk(API_DIR):
        dirnames[:] = [d for d in dirnames if d not in (".git", "target", "build")]
        for fn in sorted(filenames):
            if not fn.endswith(".java"):
                continue
            full = os.path.join(dirpath, fn)
            try:
                with open(full, encoding="utf-8", errors="replace") as fh:
                    raw = fh.read()
            except OSError:
                continue
            src = strip_comments(raw, backtick=False)
            if CONTROLLER_ANN.search(src):
                found.append((full, src))
    return found


def skip_annotations(src, i):
    """From i, skip whitespace and any run of annotations; return the next real index."""
    n = len(src)
    while True:
        while i < n and src[i].isspace():
            i += 1
        if i < n and src[i] == "@":
            j = i + 1
            while j < n and (src[j] in IDENT_CH or src[j] == "."):
                j += 1
            k = j
            while k < n and src[k].isspace():
                k += 1
            if k < n and src[k] == "(":
                _, after = read_parens(src, k)
                i = after if after else j
            else:
                i = j
            continue
        return i


def annotation_target(src, after_ann):
    """'type' if this annotation decorates a class/interface/enum/record, else 'member'."""
    i = skip_annotations(src, after_ann)
    return "type" if TYPE_DECL.match(src[i:i + 200]) else "member"


def handler_params(src, after_ann):
    """The parameter list of the method this annotation decorates ('' if not found).

    Used as the EVIDENCE for an allowlist claim. `@PreAuthorize("...")` and friends sit
    between the mapping and the signature, so annotations are skipped first rather than
    grabbing the next '(' blindly.
    """
    i = skip_annotations(src, after_ann)
    n = len(src)
    # signature: modifiers, return type, name, '('
    depth = 0
    while i < n:
        c = src[i]
        if c == "(" and depth == 0:
            inner, _ = read_parens(src, i)
            return inner or ""
        if c in "<":
            depth += 1
        elif c == ">":
            depth = max(0, depth - 1)
        elif c in "{};":
            return ""
        i += 1
    return ""


def ann_paths(args):
    """-> (list_of_paths, None) or (None, offending_expression).

    ('',)  means "no path attribute": the mapping inherits the class base verbatim.
    Anything that is not a string literal or an array of string literals is REFUSED, not
    guessed. Guessing is how `@PostMapping(path={"/refund"})` used to collapse onto the
    live `/wallet/escrow` and disappear.
    """
    args = (args or "").strip()
    if not args:
        return [""], None
    parts = split_top(args)
    rhs = None
    for p in parts:
        m = re.match(r"^(value|path)\s*=\s*(.*)$", p, re.S)
        if m:
            rhs = m.group(2).strip()
            break
    if rhs is None:
        first = parts[0]
        # a positional value has no top-level '='
        if first and not re.match(r"^[A-Za-z_][A-Za-z0-9_]*\s*=[^=]", first):
            rhs = first
        else:
            return [""], None
    if not rhs:
        return [""], None
    if rhs.startswith("{") and rhs.endswith("}"):
        items = [x for x in split_top(rhs[1:-1].strip()) if x]
        if not items:
            return [""], None
        out = []
        for it in items:
            m = JAVA_STR.match(it)
            if not m:
                return None, rhs
            out.append(m.group(1))
        return out, None
    m = JAVA_STR.match(rhs)
    if m:
        return [m.group(1)], None
    return None, rhs


def ann_verbs(args):
    """Verbs declared on a method-level @RequestMapping. Empty list => all verbs."""
    args = (args or "").strip()
    if not args:
        return []
    for p in split_top(args):
        m = re.match(r"^method\s*=\s*(.*)$", p, re.S)
        if m:
            return sorted(set(REQUEST_METHOD.findall(m.group(1)))) or []
    return []


def handler_is_machine_facing(params):
    """Evidence that an allowlist claim is true: no authenticated UI principal.

    A route that resolves a logged-in user out of the security context is a UI route,
    whatever it is named. Without this, `/internal/...` was a magic word that exempted any
    endpoint from the check -- the verifier used exactly that to hide the money route.
    """
    return "@AuthenticationPrincipal" not in params


def collect_backend():
    """-> (endpoints, files, exempted, refused, unresolved) or None if the tree is absent."""
    files = find_controllers()
    if files is None:
        return None
    endpoints, exempted, refused, unresolved = [], [], [], []
    for full, src in files:
        rel = os.path.relpath(full, ROOT).replace("\\", "/")
        fname = os.path.basename(full)

        # --- class-level bases (may be several) ---
        bases = [""]
        for m in REQ_ANN.finditer(src):
            k = m.end()
            while k < len(src) and src[k].isspace():
                k += 1
            args, after = ("", m.end()) if (k >= len(src) or src[k] != "(") else read_parens(src, k)
            if after is None:
                continue
            if annotation_target(src, after) != "type":
                continue
            paths, bad = ann_paths(args)
            line = src[: m.start()].count("\n") + 1
            if paths is None:
                unresolved.append((rel, line, "class @RequestMapping", bad))
                bases = None
                break
            bases = paths
            break
        if bases is None:
            continue

        # --- method-level mappings ---
        for m in ANY_MAPPING.finditer(src):
            k = m.end()
            while k < len(src) and src[k].isspace():
                k += 1
            if k < len(src) and src[k] == "(":
                args, after = read_parens(src, k)
                if after is None:
                    continue
            else:
                args, after = "", m.end()
            if annotation_target(src, after) != "member":
                continue
            line = src[: m.start()].count("\n") + 1

            vm = VERB_ANN.match(src, m.start())
            if vm:
                verbs = [vm.group(1).upper()]
            else:
                verbs = ann_verbs(args) or ["ANY"]

            paths, bad = ann_paths(args)
            if paths is None:
                unresolved.append((rel, line, "method mapping", bad))
                continue

            params = handler_params(src, after)
            machine = handler_is_machine_facing(params)

            for base in bases:
                for sub in paths:
                    joined = base + ("" if not sub else ("/" + sub.lstrip("/")))
                    path = norm(joined)
                    why = None
                    if fname in EXEMPT_FILE:
                        why = EXEMPT_FILE[fname]
                    else:
                        for rx, reason in EXEMPT_PATH:
                            if rx.search(path):
                                why = reason
                                break
                    if why:
                        if machine:
                            exempted.append((path, why))
                            continue
                        refused.append((rel, line, path, why))
                    for verb in verbs:
                        endpoints.append((verb, path, rel, line))
    return endpoints, len(files), exempted, refused, unresolved


# ---------------------------------------------------------------------------
# Frontend: harvest from request call sites only
# ---------------------------------------------------------------------------
def read_template(src, i):
    """src[i] is a quote. Return (text_with_PLACEHOLDER_for_interpolations, holes, end).

    end is the index after the closing quote, or None if unterminated. Nested templates
    inside `${...}` are consumed whole -- api-contracts.ts writes
    apiRequest(`/moderation/approvals/pending${type ? `?type=${type}` : ''}`) and losing
    that call site invents a false unreachable.
    """
    q = src[i]
    out, holes = [], []
    j, n = i + 1, len(src)
    while j < n:
        c = src[j]
        if c == "\\":
            out.append(src[j:j + 2])
            j += 2
            continue
        if c == q:
            return "".join(out), holes, j + 1
        if q == "`" and c == "$" and j + 1 < n and src[j + 1] == "{":
            _, after = read_parens(src, j + 1, backtick=True)
            if after is None:
                return None, None, None
            holes.append(src[j + 2:after - 1].strip())
            out.append(PLACEHOLDER)
            j = after
            continue
        if q != "`" and c == "\n":
            return None, None, None
        out.append(c)
        j += 1
    return None, None, None


def literal_arg(arg, consts):
    """Resolve one call argument to a path, or say why it cannot be resolved.

    -> ("path", value, had_leading_hole) | ("verb", VALUE, False) | ("opaque", text, False)
    """
    arg = arg.strip()
    if not arg or arg[0] not in "'\"`":
        return "opaque", arg, False
    body, holes, end = read_template(arg, 0)
    if body is None or end != len(arg):
        # a concatenation ('/a' + x) or an unterminated literal: not a resolvable path
        return "opaque", arg, False
    if body.lower() in VERB_LITERALS and PLACEHOLDER not in body:
        return "verb", body.upper(), False
    # substitute the interpolations we can actually resolve
    parts = body.split(PLACEHOLDER)
    rebuilt = [parts[0]]
    for hole, tail in zip(holes, parts[1:]):
        val = consts.get(hole)
        rebuilt.append(val if val is not None else PLACEHOLDER)
        rebuilt.append(tail)
    body = "".join(rebuilt)
    leading_hole = False
    if body.startswith(PLACEHOLDER):
        # a leading interpolation is the base URL -- meera-api.ts writes
        # fetch(`${API_BASE_URL}/meera/voice/speak`) and bypasses the shared helper.
        body = body.lstrip(PLACEHOLDER)
        leading_hole = True
    if not body.startswith("/"):
        return "opaque", arg, False
    return "path", body, leading_hole


# Words that, sitting immediately before the name, mean this '(' opens a DECLARATION's
# parameter list rather than a call. `async request<T>(method, path, ...)` in api.ts is the
# definition of the transport, not a call to it; counting it as an opaque call site would
# make the gate report uncertainty it does not have.
DECL_KEYWORDS = {"function", "async", "private", "public", "protected", "static",
                 "readonly", "abstract", "declare", "constructor", "new", "class"}


# A dotted identifier chain, not itself preceded by an identifier char or a dot.
FE_CALLEE = re.compile(r"(?<![A-Za-z0-9_$.])((?:[A-Za-z_$][A-Za-z0-9_$]*\s*\.\s*)*[A-Za-z_$][A-Za-z0-9_$]*)")


def mask_strings(src):
    """Blank the INTERIOR of every string/template literal, preserving length and newlines.

    Callee names are looked for in this masked copy so that a string cannot forge a call
    site. Without it, dropping the text `"http.request('POST','/wallet/escrow/refund')"`
    into any UI label would register the dead money route as called -- the same family of
    trick as the breadcrumb-label evasion, one level up.
    """
    out = list(src)
    i, n = 0, len(src)
    quote = None
    while i < n:
        c = src[i]
        if quote:
            if c == "\\" and i + 1 < n:
                out[i] = " "
                out[i + 1] = " " if src[i + 1] != "\n" else "\n"
                i += 2
                continue
            if c == quote:
                quote = None
            elif c != "\n":
                out[i] = " "
            i += 1
            continue
        if c in "'\"`":
            quote = c
        i += 1
    return "".join(out)


def skip_type_args(src, i, cap=1200):
    """src[i] == '<'. Return the index after the balancing '>', or None.

    Scanned FORWARD from a name already known to be a request helper, and balancing only
    '<'/'>' -- so `<PaginatedResponse<Brand>>` and
    `<{ featured: (CreatorProfile & { location?: string })[] }>` both survive. An earlier
    backward scanner stopped at the ')' and the '>>' in those and silently discarded the
    call sites, which is how thirteen live admin and dashboard routes briefly read as dead.
    """
    depth = 0
    n = min(len(src), i + cap)
    while i < n:
        c = src[i]
        if c == "<":
            depth += 1
        elif c == ">":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return None


def preceded_by_decl_keyword(src, start):
    """True when the name at `start` is a declaration, not a call."""
    k = start - 1
    while k >= 0 and src[k].isspace():
        k -= 1
    end = k + 1
    while k >= 0 and src[k] in IDENT_CH:
        k -= 1
    return src[k + 1:end] in DECL_KEYWORDS


def find_call_sites(src):
    """Yield (name, index_of_open_paren) for every request-issuing call in `src`."""
    masked = mask_strings(src)
    n = len(masked)
    for m in FE_CALLEE.finditer(masked):
        name = re.sub(r"\s+", "", m.group(1))
        if not is_request_callee(name):
            continue
        if preceded_by_decl_keyword(masked, m.start()):
            continue
        k = m.end()
        while k < n and masked[k].isspace():
            k += 1
        if k < n and masked[k] == "<":
            k = skip_type_args(masked, k)
            if k is None:
                continue
            while k < n and masked[k].isspace():
                k += 1
        if k >= n or masked[k] != "(":
            continue
        yield name, k


def is_request_callee(name):
    segs = name.split(".")
    last = segs[-1].lower()
    if last in REQ_LAST:
        return True
    if len(segs) >= 2 and segs[-2].lower() in REQ_RECEIVER and last in (HTTP_VERBS | REQ_LAST):
        return True
    if name.lower() == "axios":
        return True
    return False


def module_consts(src):
    """Module-level string constants, so `${API_BASE}${AUDIT_ENDPOINT}` resolves."""
    out = {}
    for m in FE_CONST_DECL.finditer(src):
        out[m.group(1)] = m.group(3)
    return out


def collect_frontend():
    """-> (paths, files, callsites, opaque_sites)."""
    paths, opaque = set(), []
    files = callsites = 0
    if not os.path.isdir(FE_ROOT):
        return paths, 0, 0, opaque
    for dirpath, dirnames, filenames in os.walk(FE_ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in sorted(filenames):
            if not fn.endswith((".ts", ".tsx", ".js", ".jsx")):
                continue
            if SKIP_FILE.search(fn):
                continue
            files += 1
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, ROOT).replace("\\", "/")
            with open(full, encoding="utf-8", errors="replace") as fh:
                src = strip_comments(fh.read())
            consts = module_consts(src)
            # This module's own mount point, if it declares one. Only a RELATIVE base
            # counts; `http://host/api/v1` normalises away to nothing.
            prefix = ""
            for name, val in consts.items():
                if FE_BASE_NAME.match(name) and val.startswith("/"):
                    cand = norm(val)
                    if cand != "/":
                        prefix = cand
                    break

            for name, popen in find_call_sites(src):
                inner, after = read_parens(src, popen, backtick=True)
                if inner is None:
                    continue
                callsites += 1
                line = src[:popen].count("\n") + 1
                args = split_top(inner, backtick=True)
                idx = 0
                if args:
                    kind, val, _ = literal_arg(args[0], consts)
                    if kind == "verb":
                        idx = 1
                if idx >= len(args):
                    opaque.append((rel, line, name, "(no path argument)"))
                    continue
                kind, val, leading_hole = literal_arg(args[idx], consts)
                if kind != "path":
                    opaque.append((rel, line, name, val[:60].replace("\n", " ")))
                    continue
                if leading_hole or not prefix:
                    paths.add(norm(val))
                else:
                    # a bare relative path in a module that mounts under a base:
                    # register ONLY the mounted form. Registering the raw form too is how
                    # a stray `const API_BASE` could green an endpoint nobody calls.
                    paths.add(norm(prefix + "/" + val.lstrip("/")))
    return paths, files, callsites, opaque


def die_unavailable(msg):
    print("unreachable-endpoint: UNAVAILABLE -- %s" % msg)
    print("exit 2 is not a pass. Nothing about this defect class was proved.")
    sys.exit(2)


def main():
    be = collect_backend()
    if be is None:
        die_unavailable("java source tree not found at %s" % API_DIR)
    endpoints, be_files, be_exempted, be_refused, be_unresolved = be

    if be_files == 0:
        die_unavailable(
            "found 0 files carrying @RestController/@Controller under %s -- there is "
            "nothing to reconcile, which is a broken detector, not a clean repo" % API_DIR
        )
    if be_unresolved:
        lines = "; ".join(
            "%s:%d %s -> %s" % (f, l, what, (expr or "")[:60]) for f, l, what, expr in be_unresolved[:8]
        )
        die_unavailable(
            "%d mapping(s) whose path is not a string literal: %s -- refusing to guess a "
            "path, because a guessed path can silently collide with a live route and green "
            "a dead one" % (len(be_unresolved), lines)
        )
    if not endpoints:
        die_unavailable(
            "parsed 0 mapped endpoints from %d controller files -- the annotation parser "
            "matched nothing, which is a broken detector, not a clean repo" % be_files
        )

    missing_clients = [c for c in REQUIRED_FE_CLIENTS if not os.path.isfile(c)]
    if missing_clients:
        die_unavailable(
            "expected frontend API client(s) absent: %s -- reconciling against the "
            "remaining layers would manufacture false unreachables"
            % ", ".join(os.path.relpath(c, ROOT).replace("\\", "/") for c in missing_clients)
        )

    fe_paths, fe_files, fe_calls, fe_opaque = collect_frontend()
    if fe_files == 0:
        die_unavailable("scanned 0 frontend source files under %s" % FE_ROOT)
    if fe_calls == 0:
        die_unavailable(
            "found 0 request call sites in %d frontend files -- every endpoint would read "
            "as unreachable, so this is a broken detector, not a finding" % fe_files
        )
    if not fe_paths:
        die_unavailable(
            "resolved 0 request paths from %d call sites in %d frontend files -- every "
            "endpoint would read as unreachable, so this is a broken detector, not a "
            "finding" % (fe_calls, fe_files)
        )

    # Exact hits are cheap. Given the asymmetry in matches(), a backend path with no "*"
    # can only ever be reached by an exact literal, so the walk is needed only for the
    # parameterised routes.
    by_len = {}
    for f in fe_paths:
        by_len.setdefault(len(seg(f)), []).append(f)
    seen, orphans = set(), []
    for verb, path, rel, line in endpoints:
        if path in fe_paths:
            continue
        if "*" in path and any(matches(path, f) for f in by_len.get(len(seg(path)), ())):
            continue
        key = (verb, path, rel, line)
        if key in seen:
            continue
        seen.add(key)
        orphans.append(key)
    orphans.sort(key=lambda o: (o[2], o[3], o[0]))

    print("unreachable-endpoint gate (F-0416, F-0443..F-0450)")
    print("  backend : %d controller files found by annotation (recursive), "
          "%d user-facing endpoints, %d server-side-only routes allowlisted"
          % (be_files, len(endpoints), len(be_exempted)))
    if be_refused:
        print("            %d allowlist claim(s) REFUSED -- the handler takes an "
              "@AuthenticationPrincipal, so it is a UI route however it is named:" % len(be_refused))
        for rel, line, path, why in be_refused:
            print("              %s:%d %s (claimed: %s)" % (rel, line, path, why))
    print("  frontend: %d source files, %d request call sites, %d distinct paths "
          "(comments stripped; only call-site path arguments count)"
          % (fe_files, fe_calls, len(fe_paths)))
    if fe_opaque:
        print("            %d call site(s) whose path is not a literal -- an endpoint "
              "reached only from these can still be reported below:" % len(fe_opaque))
        for rel, line, name, txt in fe_opaque[:12]:
            print("              %s:%d %s(%s)" % (rel, line, name, txt))
        if len(fe_opaque) > 12:
            print("              ... and %d more" % (len(fe_opaque) - 12))
    print("  findings: %d endpoint(s) that no frontend code can reach" % len(orphans))
    if orphans:
        print("")
        for verb, path, rel, line in orphans:
            print("  %s:%d: %-6s %-50s no frontend caller" % (rel, line, verb, path))
        print("")
        print("VERDICT: broken -- %d unreachable endpoint(s)" % len(orphans))
    else:
        print("VERDICT: clean -- every user-facing endpoint has at least one frontend caller")

    print("")
    print("NOT CHECKED: whether a frontend caller is itself reachable by navigation (a call "
          "inside a component that is never mounted still counts as a caller here); whether "
          "the call sends correct arguments or the right HTTP verb (matching is path-shaped, "
          "so a POST endpoint whose path is only ever GET-ed reads as reachable); "
          "authorization; whether an allowlisted route is genuinely server-to-server (the "
          "evidence tested is only that no authenticated UI principal is injected); and any "
          "call whose path is assembled from variables rather than written as a literal -- "
          "those call sites are counted and listed above rather than silently trusted.")
    sys.exit(1 if orphans else 0)


if __name__ == "__main__":
    main()
