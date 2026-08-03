#!/usr/bin/env python3
# gates/fe_be_endpoints.py — the FE<->BE contract oracle for the admin surface.
# origin: F-0054 (phantom-endpoint) — the frontend declares admin endpoints that have no
#         @Mapping anywhere in influora-api; tsc/eslint pass because a path is just a string.
# also verifies: F-0063 (phantom-endpoint-repair) — exit 0 proves no phantom FE call remains
#         (the 11 parked/blocked endpoints were routed through unavailable() in api-contracts.ts).
# LAW (law 2): exit 0 = every FE admin call resolves to a backend mapping (proved).
#              exit 1 = REAL findings (FE paths with no BE mapping). exit 2 = a source tree
#              is missing so nothing could be compared (unavailable, never green). 64 = usage.
# LAW (F-0056, no truncation): prints EVERY missing path, never a head -N slice.
# LAW (F-0013/F-0056, no false red): FE paths are taken ONLY from real call-sites
#              (apiRequest(...) in the admin client), not from arbitrary string literals —
#              route strings like a footer "/kyc" are not API calls and must not be flagged.
# LAW (rule 5): declares its blind spot on every exit path.
# Usage: python .proof-os/gates/fe_be_endpoints.py [project_dir]
import re, os, sys, json

BLIND = ("NOT CHECKED: HTTP verb agreement (only the path is compared, not GET-vs-POST); "
         "dynamic paths built by string concatenation at runtime; whether a matched backend "
         "mapping is actually reachable/authorized; non-admin API surfaces (this gate scopes "
         "to src/admin/services/api-contracts.ts by design).")

def die(code, msg):
    print(msg)
    print(BLIND)
    sys.exit(code)

root = sys.argv[1] if len(sys.argv) > 1 else "."
if len(sys.argv) > 1 and not sys.argv[1]:
    die(64, "usage: fe_be_endpoints.py [project_dir] — empty argument rejected")
be_dir = os.path.join(root, "influora-api", "src", "main", "java")
fe_file = os.path.join(root, "src", "admin", "services", "api-contracts.ts")
if not os.path.isdir(be_dir):
    die(2, f"· backend java tree not found at {be_dir} — cannot compare, unavailable")
if not os.path.isfile(fe_file):
    die(2, f"· admin api client not found at {fe_file} — cannot compare, unavailable")

def norm(p):
    p = p.split("?")[0].split("`")[0]
    p = re.sub(r"\$\{[^}]*\}", "{}", p)   # balanced ${id} template var -> {}
    p = re.split(r"\$\{", p)[0]           # unbalanced "${status ..." querystring tail -> cut
    p = re.split(r"\s", p)[0]             # any stray whitespace tail -> cut
    p = re.sub(r"^/api/v1", "", p)
    p = re.sub(r"^/api", "", p)
    p = re.sub(r"\{[^}]*\}", "{}", p)     # {id} path var / RR param
    p = re.sub(r"//+", "/", p)
    return p.rstrip("/") or "/"

# ---- backend: class-level @RequestMapping base + each method mapping -----------------
BE = set()
for r, d, fs in os.walk(be_dir):
    for f in fs:
        if not f.endswith(".java"):
            continue
        s = open(os.path.join(r, f), encoding="utf-8", errors="replace").read()
        cls = re.findall(r'@RequestMapping\(\s*(?:value\s*=\s*)?\{?\s*"([^"]+)"', s)
        base = cls[0] if cls else ""
        for m in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)")?', s):
            p = m.group(2) or ""
            full = ((base.rstrip("/") + "/" + p.lstrip("/")) if p else base).rstrip("/") or "/"
            BE.add(norm(full))

# ---- frontend: ONLY real apiRequest(...) call-sites in the admin client --------------
s = open(fe_file, encoding="utf-8", errors="replace").read()
FE = {}
def add(path):
    n = norm("/admin" + path if not path.startswith("/admin") else path)
    if n and n != "/":
        FE[n] = FE.get(n, 0) + 1
# apiRequest<...>(`/path`, ...)  and  apiRequest(`/path`, ...)
for m in re.finditer(r'apiRequest\s*(?:<[^>]*>)?\s*\(\s*[`\'"]([^`\'"]+)', s):
    add(m.group(1))

missing = sorted(p for p in FE if p not in BE)
print(f"· backend mapped paths: {len(BE)}  |  admin FE call paths: {len(FE)}  |  matched: {len(FE)-len(missing)}")
if not missing:
    die(0, "VERDICT: aligned (proved) — every admin FE call path resolves to a backend @Mapping")
print(f"· NO BACKEND MAPPING: {len(missing)}")
for p in missing:
    print(f"    {p}")
die(1, f"VERDICT: broken — {len(missing)} admin endpoint(s) the frontend calls have no backend @Mapping (F-0054)")
