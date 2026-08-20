#!/usr/bin/env python3
"""Gate for ledger class `unreachable-endpoint` (F-0377, and F-0381's dead money routes).

F-0377's shape: `POST /webhook-secret/generate` was the ONLY issuer of a credential the
product depends on, and no frontend called it. Every existing gate passed -- the controller
compiled, its authorization was correct, and BrandF.md graded it "clean" (correctly, for
authz). Nothing anywhere asked the one question that mattered: can the user this endpoint
exists for actually reach it?

This gate reconciles brand/creator-facing backend endpoints against frontend callers and
fails on any that no shipped UI can reach. Server-to-server routes are exempt by path --
inbound webhooks, OAuth callbacks and public redirects are SUPPOSED to have no FE caller.

exit 0 proved . 1 broken . 2 unavailable
"""
import json
import os
import re
import sys

# --only <regex> scopes the assertion to one surface, so a fix can be PROVED green while the
# rest of the class is still being worked through. Unscoped is the full sweep and is expected
# to stay red until the backlog is cleared -- a gate nobody can ever run green guards nothing.
ONLY = None
_argv = [a for a in sys.argv[1:]]
if "--only" in _argv:
    i = _argv.index("--only")
    ONLY = re.compile(_argv[i + 1]) if i + 1 < len(_argv) else None

API_DIR = "influora-api/src/main/java/com/influora/web"
FE_DIRS = ["src"]

# Routes whose callers are external systems or browsers following a link, not our UI.
EXEMPT = re.compile(
    r"^/(webhooks?/|track/click|.*/oauth/callback|shopify/oauth/callback"
    r"|internal/|actuator|health|\.well-known|jwks|client-errors?)"
)
EXEMPT_FILE = re.compile(
    r"^(ConversionWebhookController|ShopifyWebhookController|WooCommerceWebhookController"
    r"|HealthController|JwksController|PublicConfigController|ClientErrorController"
    r"|MeeraInternalController)\.java$"
)

VERB = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?\"([^\"]*)\"|\()?")
BASE = re.compile(r"@RequestMapping\(\s*(?:value\s*=\s*)?\"([^\"]*)\"")
FE_CALL = re.compile(
    r"""(?:request|requestWithMeta|downloadBlob|postForm|uploadForm|upload|apiRequest)"""
    r"""\s*(?:<[^(]*?>)?\(\s*"""
    r"""(?:['"](GET|POST|PUT|PATCH|DELETE)['"]\s*,\s*)?[`'"]([/][^`'"]+)"""
)
# A bare path argument on its own line, as uploadForm(...) and friends are often called.
FE_PATH_ARG = re.compile(r"""^\s*[`'"](/[A-Za-z0-9_\-/${}.]*)[`'"]\s*,?\s*$""", re.M)
FE_RAW = re.compile(r"""(?:fetch|EventSource)\(\s*[`'"]([^`'"]+)""")


def norm(p):
    p = p.split("?")[0].rstrip("/")
    p = re.sub(r"\$\{[^}]*\}", "*", p)
    p = re.sub(r"\{[^}]*\}", "*", p)
    p = re.sub(r"/me(?=/|$)", "/*", p)
    if p.startswith("/api/v1"):
        p = p[7:]
    elif p.startswith("/api"):
        p = p[4:]
    return p or "/"


def backend():
    out = []
    if not os.path.isdir(API_DIR):
        return None
    for f in sorted(os.listdir(API_DIR)):
        if not f.endswith(".java") or f.startswith("Admin") or EXEMPT_FILE.search(f):
            continue
        path = os.path.join(API_DIR, f)
        src = open(path, encoding="utf-8", errors="replace").read()
        m = BASE.search(src)
        base = m.group(1) if m else ""
        for mm in VERB.finditer(src):
            full = norm(base + (mm.group(2) or ""))
            if EXEMPT.match(full):
                continue
            out.append((mm.group(1).upper(), full, f, src[: mm.start()].count("\n") + 1))
    return out


def frontend():
    seen = set()
    for root in FE_DIRS:
        for dp, dn, fn in os.walk(root):
            dn[:] = [d for d in dn if d != "node_modules"]
            for name in fn:
                if not name.endswith((".ts", ".tsx")) or ".test." in name:
                    continue
                src = open(os.path.join(dp, name), encoding="utf-8", errors="replace").read()
                for mm in FE_CALL.finditer(src):
                    seen.add(((mm.group(1) or "GET").upper(), norm(mm.group(2))))
                for mm in FE_RAW.finditer(src):
                    seen.add(("ANY", norm(mm.group(1))))
                for mm in FE_PATH_ARG.finditer(src):
                    seen.add(("ANY", norm(mm.group(1))))
    return seen


be = backend()
if be is None:
    print("* %s not found - unavailable" % API_DIR)
    print("NOT CHECKED: everything; there was no controller tree to read")
    sys.exit(2)

fe = frontend()
fe_paths = {p for _, p in fe}
orphans = [(v, p, f, ln) for v, p, f, ln in be if (v, p) not in fe and p not in fe_paths]
if ONLY is not None:
    be = [r for r in be if ONLY.search(r[1])]
    orphans = [r for r in orphans if ONLY.search(r[1])]

if ONLY is not None and not be:
    print("* --only %s matched 0 endpoints - unavailable, NOT green" % ONLY.pattern)
    print("NOT CHECKED: everything in that scope; the filter selected no endpoint to test, "
          "which is a broken assertion, not a passing one")
    sys.exit(2)

print("* endpoint reachability%s: %d user-facing endpoints, %d frontend call sites"
      % ((" [--only %s]" % ONLY.pattern) if ONLY else "", len(be), len(fe)))
for v, p, f, ln in orphans:
    print("  %-6s %-52s no shipped UI reaches this  %s:%d" % (v, p, f, ln))
if orphans:
    print("VERDICT: broken (real findings above)")
else:
    print("VERDICT: aligned (proved) - every user-facing endpoint has a frontend caller")
print(
    "NOT CHECKED: whether the frontend caller is reachable by NAVIGATION (a called endpoint in "
    "an unmounted component still counts here - see the StoreIntegrationSetup case); whether the "
    "call fires with correct arguments; authorization; whether an exempt path is genuinely "
    "server-to-server or was merely named to look like one; and any call shape not written as "
    "one of the recognised helpers (a hand-rolled fetch built from concatenated variables is "
    "invisible here and will be reported as an orphan)"
)
sys.exit(1 if orphans else 0)
