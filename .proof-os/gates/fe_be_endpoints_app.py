#!/usr/bin/env python3
# gates/fe_be_endpoints_app.py — FE<->BE contract oracle for the BRAND + CREATOR surface.
#
# Sibling of fe_be_endpoints.py, which scopes to src/admin/services/api-contracts.ts.
# That gate proved the admin surface; nothing covered the brand/creator surface, which is
# where the product actually lives (src/lib/api.ts).
#
# origin: same class as F-0054 (phantom-endpoint) — the frontend declares endpoints that
#         have no @Mapping anywhere in influora-api. tsc/eslint pass because a path is
#         just a string, so a dead feature ships looking green.
#
# LAW (law 2): exit 0 = every FE app call resolves to a backend mapping (proved).
#              exit 1 = REAL findings (FE paths with no BE mapping).
#              exit 2 = a source tree is missing so nothing could be compared (unavailable,
#                       never green). 64 = usage.
# LAW (F-0056, no truncation): prints EVERY missing path, never a head -N slice.
# LAW (F-0013/F-0056, no false red): FE paths are taken ONLY from real call-sites on the
#              http client (`http.get(...)`, `http.post(...)`, ...), never from arbitrary
#              string literals — a router path like "/brand/wallet" is not an API call.
# LAW (rule 5): declares its blind spot on every exit path.
#
# Usage: python .proof-os/gates/fe_be_endpoints_app.py [project_dir]
import re, os, sys

BLIND = ("NOT CHECKED: whether a matched backend mapping is actually reachable, authorized, or returns "
         "correct data; request/response body shape agreement; paths assembled at runtime "
         "from variables rather than written as a literal template; the admin surface "
         "(covered by the sibling gate fe_be_endpoints.py); and whether a backend mapping "
         "that exists is actually DEPLOYED on the live host.")


def die(code, msg):
    print(msg)
    print(BLIND)
    sys.exit(code)


root = sys.argv[1] if len(sys.argv) > 1 else "."
if len(sys.argv) > 1 and not sys.argv[1]:
    die(64, "usage: fe_be_endpoints_app.py [project_dir] — empty argument rejected")

be_dir = os.path.join(root, "influora-api", "src", "main", "java")
fe_file = os.path.join(root, "src", "lib", "api.ts")
if not os.path.isdir(be_dir):
    die(2, f"· backend java tree not found at {be_dir} — cannot compare, unavailable")
if not os.path.isfile(fe_file):
    die(2, f"· app api client not found at {fe_file} — cannot compare, unavailable")


def norm(p):
    p = p.split("?")[0]
    p = re.sub(r"\$\{[^{}]*\}", "{}", p)   # ${id}, ${encodeURIComponent(x)} -> {}
    p = re.split(r"\$\{", p)[0]            # unbalanced tail -> cut
    p = re.split(r"\s", p)[0]
    p = re.sub(r"^/api/v1", "", p)
    p = re.sub(r"^/api", "", p)
    p = re.sub(r"\{[^}]*\}", "{}", p)      # {id} path var -> {}
    p = re.sub(r"//+", "/", p)
    return p.rstrip("/") or "/"


# ---- backend: class-level @RequestMapping base + each method mapping -----------------
# BE_PATHS = every mapped path (verb-agnostic). BE_VERB = (verb, path) pairs.
BE = set()
BE_VERB = set()
for r, d, fs in os.walk(be_dir):
    for f in fs:
        if not f.endswith(".java"):
            continue
        s = open(os.path.join(r, f), encoding="utf-8", errors="replace").read()
        cls = re.findall(r'@RequestMapping\(\s*(?:value\s*=\s*)?\{?\s*"([^"]+)"', s)
        base = cls[0] if cls else ""
        for m in re.finditer(
            r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)")?', s
        ):
            p = m.group(2) or ""
            full = ((base.rstrip("/") + "/" + p.lstrip("/")) if p else base).rstrip("/") or "/"
            n = norm(full)
            BE.add(n)
            BE_VERB.add((m.group(1).upper(), n))

# ---- frontend: ONLY real http-client call-sites in src/lib/api.ts --------------------
s = open(fe_file, encoding="utf-8", errors="replace").read()
FE = {}          # path -> count
FE_VERB = {}     # (verb, path) -> count


def add(verb, raw):
    n = norm(raw)
    if not n or n == "/":
        return
    FE[n] = FE.get(n, 0) + 1
    FE_VERB[(verb, n)] = FE_VERB.get((verb, n), 0) + 1


# form A (dominant): http.request<T>('GET', '/path', ...) / requestWithMeta / requestNullable
for m in re.finditer(
    r'\b(?:http|client|this)\s*\.\s*request\w*\s*(?:<[^;{]*?>)?\s*\(\s*'
    r'[\'"`](GET|POST|PUT|PATCH|DELETE)[\'"`]\s*,\s*[`\'"](/[^`\'"]*)', s
):
    add(m.group(1).upper(), m.group(2))

# form B: path-first helpers — http.upload/uploadForm (POST) and http.downloadBlob (GET)
for m in re.finditer(
    r'\b(?:http|client|this)\s*\.\s*(upload|uploadForm|downloadBlob)\s*'
    r'(?:<[^;{]*?>)?\s*\(\s*[`\'"](/[^`\'"]*)', s
):
    add("GET" if m.group(1) == "downloadBlob" else "POST", m.group(2))

# form C: raw fetch(`${API_BASE_URL}/path`) — the SSE stream + client-error routes
# bypass the client entirely, so they are invisible to forms A and B.
for m in re.finditer(r'fetch\(\s*`\$\{API_BASE_URL\}(/[^`]*)', s):
    add("GET", m.group(1))   # verb unknown here; recorded as GET, excluded from verb check

RAW_FETCH = {norm(m.group(1)) for m in
             re.finditer(r'fetch\(\s*`\$\{API_BASE_URL\}(/[^`]*)', s)}

# A client this large cannot have a handful of call-sites. A low count means the client
# shape changed and the matcher silently stopped seeing it — that is a false green, which
# is exactly the failure this gate exists to prevent. Refuse rather than pass.
FLOOR = 100
if len(FE) < FLOOR:
    die(2, f"· only {len(FE)} FE call-sites matched in src/lib/api.ts (floor {FLOOR}) — the "
           f"client shape changed and this matcher no longer sees it; refusing to report green")

missing = sorted(p for p in FE if p not in BE)
# verb mismatch: the path exists on the backend, but not under the verb the FE uses.
verb_bad = sorted(
    (v, p) for (v, p) in FE_VERB
    if p in BE and (v, p) not in BE_VERB and p not in RAW_FETCH
)

print(f"· backend mapped paths: {len(BE)}  |  app FE call paths: {len(FE)}  |  "
      f"matched: {len(FE) - len(missing)}  |  verb mismatches: {len(verb_bad)}")

if not missing and not verb_bad:
    die(0, "VERDICT: aligned (proved) — every brand/creator FE call path resolves to a "
           "backend @Mapping under the same HTTP verb")

if missing:
    print(f"· NO BACKEND MAPPING: {len(missing)}")
    for p in missing:
        verbs = ",".join(sorted({v for (v, q) in FE_VERB if q == p}))
        print(f"    {verbs:6} {p}   (called {FE[p]}x)")
if verb_bad:
    print(f"· VERB MISMATCH (path exists, that verb does not): {len(verb_bad)}")
    for v, p in verb_bad:
        have = ",".join(sorted({bv for (bv, bp) in BE_VERB if bp == p}))
        print(f"    FE uses {v:6} {p}   — backend has {have}")

die(1, f"VERDICT: broken — {len(missing)} brand/creator endpoint(s) with no backend "
       f"@Mapping, {len(verb_bad)} verb mismatch(es)")
