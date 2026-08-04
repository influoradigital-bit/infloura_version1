#!/usr/bin/env python3
"""Deterministic frontend<->backend API contract differ.
Extracts frontend call sites (method+path) from src/ and backend endpoints
(verb+full path) from influora-api Spring controllers, normalizes path params,
and reports frontend calls with no matching backend route.
"""
import os, re, json, sys, glob

ROOT = os.getcwd()
FE_DIR = os.path.join(ROOT, "src")
BE_DIR = os.path.join(ROOT, "influora-api", "src", "main", "java")

# ---------------- normalize a path: strip query, collapse params ------------
def norm(p):
    p = p.strip()
    # cut querystring
    p = p.split("?")[0]
    # template literal ${...} -> {}
    p = re.sub(r"\$\{[^}]*\}", "{}", p)
    # spring {name} -> {}
    p = re.sub(r"\{[^}]*\}", "{}", p)
    # collapse trailing slash
    if len(p) > 1 and p.endswith("/"):
        p = p[:-1]
    return p

# ---------------- BACKEND -----------------------------------------------------
VERB_ANNO = {
    "GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
    "DeleteMapping": "DELETE", "PatchMapping": "PATCH",
}

def anno_path(anno_args):
    # find value= or path= or first string literal
    m = re.search(r'(?:value|path)\s*=\s*"([^"]*)"', anno_args)
    if m: return m.group(1)
    m = re.search(r'"([^"]*)"', anno_args)
    if m: return m.group(1)
    return ""

backend = set()          # (VERB, normpath)
backend_paths = set()    # normpath only (verb-agnostic fallback)
be_files = glob.glob(os.path.join(BE_DIR, "**", "*.java"), recursive=True)
for f in be_files:
    txt = open(f, encoding="utf-8", errors="replace").read()
    if "@RestController" not in txt and "@Controller" not in txt:
        continue
    # class-level base
    base = ""
    cm = re.search(r'@RequestMapping\(\s*(.*?)\)', txt, re.S)
    if cm:
        base = anno_path(cm.group(1))
    # method-level mappings. Annotation args contain no nested parens, so
    # ([^)]*) up to the first ) is a robust, unambiguous capture. Bare
    # @GetMapping (no parens) -> path is the class base.
    for m in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\b(?:\s*\(([^)]*)\))?', txt):
        verb = VERB_ANNO[m.group(1)+"Mapping"]
        args = m.group(2) or ""
        sub = anno_path(args)
        full = base + sub if sub else base
        np = norm(full)
        backend.add((verb, np))
        backend_paths.add(np)
    # method-level @RequestMapping with method= (skip the class-level one)
    for m in re.finditer(r'@RequestMapping\s*\(([^)]*)\)', txt):
        args = m.group(1)
        if "RequestMethod" not in args:
            continue  # class-level or no verb -> handled as base
        sub = anno_path(args)
        vm = re.findall(r'RequestMethod\.(\w+)', args)
        verbs = vm if vm else ["GET","POST","PUT","DELETE","PATCH"]
        full = base + sub if sub else base
        np = norm(full)
        for v in verbs:
            backend.add((v, np))
        backend_paths.add(np)

# ---------------- FRONTEND ----------------------------------------------------
fe_calls = []  # (verb, rawpath, file, line)
fe_files = glob.glob(os.path.join(FE_DIR, "**", "*.ts"), recursive=True) + \
           glob.glob(os.path.join(FE_DIR, "**", "*.tsx"), recursive=True)

# request('GET', '/path', ...) / requestWithMeta / requestOrNull / stream
CALL_RE = re.compile(
    r'\.(?:request|requestWithMeta|requestOrNull|requestVoid|stream)\s*(?:<[^>]*>)?\s*\(\s*'
    r'''['"](GET|POST|PUT|PATCH|DELETE)['"]\s*,\s*'''
    r'''[`'"]([^`'"]+)[`'"]''')
# direct fetch(`${API_BASE_URL}/path` ...) — verb in options; capture path
FETCH_RE = re.compile(r'fetch\(\s*`\$\{API_BASE_URL\}([^`]+)`')

for f in fe_files:
    for i, line_txt in enumerate(open(f, encoding="utf-8", errors="replace"), 1):
        for m in CALL_RE.finditer(line_txt):
            fe_calls.append((m.group(1), m.group(2), f, i))
        for m in FETCH_RE.finditer(line_txt):
            fe_calls.append(("*", m.group(1), f, i))

# multi-line request calls: join file and search across newlines
for f in fe_files:
    txt = open(f, encoding="utf-8", errors="replace").read()
    for m in re.finditer(
        r'\.(?:request|requestWithMeta|requestOrNull|requestVoid)\s*(?:<[^>]*>)?\s*\(\s*'
        r'''['"](GET|POST|PUT|PATCH|DELETE)['"]\s*,\s*[`'"]([^`'"]+)[`'"]''', txt, re.S):
        key = (m.group(1), m.group(2), f, -1)
        # avoid dup with single-line matches (approx by path+verb+file)
        if not any(c[0]==m.group(1) and c[1]==m.group(2) and c[2]==f for c in fe_calls):
            fe_calls.append(key)

# ---------------- ADMIN surface (src/admin/services/*.ts, API_BASE=/api/v1/admin)
# apiRequest<T>('/endpoint', { method:'VERB' ... })  -> backend /admin/endpoint
admin_files = glob.glob(os.path.join(FE_DIR, "admin", "**", "*.ts"), recursive=True)
ADMIN_RE = re.compile(
    r'''apiRequest\s*(?:<[^>]*>)?\s*\(\s*[`'"]([^`'"]+)[`'"]\s*(?:,\s*\{(.*?)\})?''',
    re.S)
for f in admin_files:
    txt = open(f, encoding="utf-8", errors="replace").read()
    # admin base can be /api/v1/admin OR /api/v1 + explicit path; detect the const
    bm = re.search(r"API_BASE\s*=\s*['\"]([^'\"]+)['\"]", txt)
    fbase = bm.group(1) if bm else "/api/v1/admin"
    fbase = fbase.replace("/api/v1", "")  # strip context path
    for m in ADMIN_RE.finditer(txt):
        ep = m.group(1)
        opts = m.group(2) or ""
        vm = re.search(r"method\s*:\s*['\"](GET|POST|PUT|PATCH|DELETE)['\"]", opts)
        verb = vm.group(1) if vm else "GET"
        full = fbase + ep
        fe_calls.append((verb, full, f, -1))
    # explicit fetch(`${API_BASE}/x`) with method
    for m in re.finditer(r'fetch\(\s*`\$\{API_BASE\}([^`]+)`\s*,\s*\{(.*?)\}', txt, re.S):
        vm = re.search(r"method\s*:\s*['\"](GET|POST|PUT|PATCH|DELETE)['\"]", m.group(2))
        verb = vm.group(1) if vm else "GET"
        fe_calls.append((verb, fbase + m.group(1), f, -1))

# ---------------- AI/MEERA surface (src/lib/meera-api.ts -> Spring /meera etc.)
meera_files = [os.path.join(FE_DIR, "lib", "meera-api.ts")]
for f in meera_files:
    if not os.path.exists(f): continue
    txt = open(f, encoding="utf-8", errors="replace").read()
    for m in re.finditer(
        r'''\.(?:request|requestWithMeta|requestOrNull)\s*(?:<[^>]*>)?\s*\(\s*'''
        r'''['"](GET|POST|PUT|PATCH|DELETE)['"]\s*,\s*[`'"]([^`'"]+)[`'"]''', txt, re.S):
        fe_calls.append((m.group(1), m.group(2), f, -1))

# ---------------- DIFF --------------------------------------------------------
def match(verb, path):
    np = norm(path)
    if (verb, np) in backend: return "exact"
    if verb == "*" and np in backend_paths: return "path-only"
    if np in backend_paths: return "verb-mismatch"  # path exists, wrong verb
    return "missing"

results = {"exact":[], "path-only":[], "verb-mismatch":[], "missing":[]}
seen = set()
for verb, path, f, ln in fe_calls:
    np = norm(path)
    k = (verb, np)
    if k in seen: continue
    seen.add(k)
    status = match(verb, path)
    rel = os.path.relpath(f, ROOT).replace("\\","/")
    results[status].append({"verb":verb,"path":path,"norm":np,"file":rel,"line":ln})

print(f"BACKEND endpoints parsed: {len(backend)} (verb,path) / {len(backend_paths)} distinct paths")
print(f"FRONTEND distinct calls:  {len(seen)}")
print(f"  exact match:    {len(results['exact'])}")
print(f"  path-only(*):   {len(results['path-only'])}")
print(f"  VERB MISMATCH:  {len(results['verb-mismatch'])}")
print(f"  MISSING route:  {len(results['missing'])}")
json.dump({"backend":sorted(f"{v} {p}" for v,p in backend),
           "results":results}, open(".proof-os/tasks/api-conn-audit/diff.json","w"), indent=1)
print("\n=== VERB MISMATCH (path exists, method differs) ===")
for r in sorted(results["verb-mismatch"], key=lambda x:x["path"]):
    print(f"  {r['verb']:6} {r['path']}   [{r['file']}]")
print("\n=== MISSING backend route (frontend calls, no controller match) ===")
for r in sorted(results["missing"], key=lambda x:x["path"]):
    print(f"  {r['verb']:6} {r['path']}   [{r['file']}]")
