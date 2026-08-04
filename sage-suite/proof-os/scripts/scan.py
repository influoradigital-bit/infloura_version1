#!/usr/bin/env python3
"""scan.py — derive graph.json from real artifacts. Covers ~/.claude/skills,
installed plugin skills, and (optionally) project file imports.
Usage: scan.py [--project DIR]"""
import json, os, re, sys, datetime
from _common import data_dir, load_registry, version

SKILLS = os.path.expanduser("~/.claude/skills")
PLUGINS = os.path.expanduser("~/.claude/plugins/synced")
PROJECT = sys.argv[sys.argv.index("--project")+1] if "--project" in sys.argv else None
REG = load_registry() or {"services": {}, "libraries": []}
NAMES = set(REG["services"]) | set(REG.get("libraries", []))

DOC = re.compile(r'\b((?:wiki|scripts|references|assets|content|src|app|prisma)/[\w.\-/]+|[A-Z][A-Z0-9_\-]{2,}\.md|sage\.py)\b')
LOCAL = re.compile(r'https?://localhost[:\d/\w\-]*')
# link types: static import / side-effect import / re-export / dynamic import / require — any quoted specifier
IMP = re.compile(r'(?:\bimport\s+[\w{},*\s]*?\bfrom\s*|\bexport\s+[\w{},*\s]*?\bfrom\s*|\bimport\s*\(\s*|\brequire\s*\(\s*|\bimport\s+)[\'"]([^\'"\n]+)[\'"]')
PYIMP = re.compile(r'^\s*from\s+(\.[\w.]*)\s+import', re.M)

def load_aliases(project):
    """tsconfig/jsconfig paths -> [(prefix, [targets])]"""
    import re as _re
    out = []
    for cfg in ("tsconfig.json", "jsconfig.json"):
        p = os.path.join(project, cfg)
        if not os.path.isfile(p): continue
        try:
            raw = open(p, encoding="utf-8", errors="replace").read()
            raw = _re.sub(r'//[^\n]*', '', raw); raw = _re.sub(r',\s*([}\]])', r'\1', raw)
            co = json.loads(raw).get("compilerOptions", {})
            base = co.get("baseUrl", ".")
            for pat, tgts in co.get("paths", {}).items():
                pre = pat[:-2] if pat.endswith("/*") else pat
                out.append((pre, [os.path.normpath(os.path.join(base, t[:-2] if t.endswith("/*") else t)) for t in tgts]))
        except Exception: pass
    return out

EXTS = ("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".py", ".css", ".scss",
        "/index.ts", "/index.tsx", "/index.js", "/index.jsx")

def skill_files():
    if os.path.isdir(SKILLS):
        for d in sorted(os.listdir(SKILLS)):
            p = os.path.join(SKILLS, d, "SKILL.md")
            if os.path.isfile(p): yield d, p, "user-skill"
    if os.path.isdir(PLUGINS):
        for pl in sorted(os.listdir(PLUGINS)):
            sk = os.path.join(PLUGINS, pl, "skills")
            if os.path.isdir(sk):
                for d in sorted(os.listdir(sk)):
                    p = os.path.join(sk, d, "SKILL.md")
                    if os.path.isfile(p): yield f"{pl}:{d}", p, "plugin-skill"

nodes, edges, seen = {}, [], set()
def N(nid, kind, exists=True):
    if nid not in nodes: nodes[nid] = {"id": nid, "kind": kind, "exists": exists}
def E(s, d, t, st, w, note=""):
    if (s, d, t) in seen: return
    seen.add((s, d, t)); edges.append({"src": s, "dst": d, "type": t, "status": st,
                                        "evidence": {"where": w, "note": note, "oracle": "regex"}})
mention = None
names_on_disk = set()
for name, path, kind in skill_files():
    names_on_disk.add(name.split(":")[-1])
if names_on_disk:
    mention = re.compile(r'\b(' + '|'.join(sorted(names_on_disk, key=len, reverse=True)) + r')\b', re.I)

for name, path, kind in skill_files():
    N(name, kind)
    for i, ln in enumerate(open(path, encoding="utf-8", errors="replace"), 1):
        w = f"{path.split('/.claude/')[-1]}:{i}"
        is_example = ('e.g.' in ln) or ('example' in ln.lower())
        for m in DOC.finditer(ln):
            ref = m.group(1).rstrip('.,)')
            if '[' in ref or '<' in ref: continue
            if is_example or ref in ('src/x.ts',): continue  # doc examples are not references (F-0024)
            local = os.path.join(os.path.dirname(path), ref)
            if os.path.exists(local):
                N(ref, "asset"); E(name, ref, "uses", "aligned", w, "exists in skill dir")
            elif ref.startswith(("scripts/", "references/", "assets/")):
                N(f"{name}/{ref}", "asset", False); E(name, f"{name}/{ref}", "uses", "missing", w, "referenced, NOT on disk")
            elif PROJECT and ref.startswith(("wiki/","content/","src/","app/","prisma/")):
                st = "aligned" if os.path.exists(os.path.join(PROJECT, ref)) else "missing"
                N(ref, "project-path"); E(name, ref, "touches", st, w)
            else:
                N(ref, "doc"); E(name, ref, "reads/writes", "partial", w, "declared; existence unverified")
        if LOCAL.search(ln):
            N("localhost", "external", False); E(name, "localhost", "calls", "broken", w, "machine-bound URL")
        if mention and any(k in ln.lower() for k in ("route","escalat","report to","approv","invoke","supervise","→","runs after","goes to")):
            for m in mention.finditer(ln):
                t = m.group(1).lower()
                if t != name.split(":")[-1] and t in names_on_disk:
                    N(t, "user-skill"); E(name, t, "routes", "aligned", w, ln.strip()[:80])

# project file→file imports
if PROJECT:
    ALIASES = load_aliases(PROJECT)
    def resolve(spec, reldir):
        cands = []
        if spec.startswith("."):
            cands.append(os.path.normpath(os.path.join(reldir, spec)))
        else:
            for pre, tgts in ALIASES:
                if spec == pre or spec.startswith(pre + "/"):
                    rest = spec[len(pre):].lstrip("/")
                    cands += [os.path.normpath(os.path.join(t, rest)) for t in tgts]
        for base in cands:
            for ext in EXTS:
                if os.path.exists(os.path.join(PROJECT, base + ext)):
                    return base + ext, True
        return (cands[0], False) if cands else (None, False)
    for root, dirs, files in os.walk(PROJECT):
        dirs[:] = [d for d in dirs if d not in (".git","node_modules",".next","dist",".proof-os",".claude","worktrees","__pycache__",".venv","venv","build","out")]
        for f in files:
            if f.endswith((".ts",".tsx",".js",".jsx",".mjs",".cjs",".py")):
                p = os.path.join(root, f); rel = os.path.relpath(p, PROJECT)
                N(rel, "file")
                try: txt = open(p, encoding="utf-8", errors="replace").read()
                except: continue
                reldir = os.path.dirname(rel)
                specs = {m.group(1) for m in IMP.finditer(txt)} if not f.endswith(".py") else set()
                if f.endswith(".py"):
                    specs = {m.group(1) for m in PYIMP.finditer(txt)}
                for spec in specs:
                    if not spec or spec.startswith(("http", "node:", "data:")): continue
                    hit, ok = resolve(spec.replace(".", "/", 1) if f.endswith(".py") and spec.startswith(".") else spec, reldir)
                    if hit is None: continue   # bare package (node_modules) — out of scope
                    if ok: N(hit, "file"); E(rel, hit, "imports", "aligned", rel)
                    else:  N(hit, "file", False); E(rel, hit, "imports", "broken", rel, f"unresolved: '{spec}'")
            elif f == "schema.prisma":
                p = os.path.join(root, f); rel = os.path.relpath(p, PROJECT)
                N(rel, "file")
                txt = open(p, encoding="utf-8", errors="replace").read()
                models = re.findall(r'^\s*model\s+(\w+)\s*\{', txt, re.M)
                mset = set(models); cur = None
                for i, ln in enumerate(txt.splitlines(), 1):
                    mm = re.match(r'\s*model\s+(\w+)\s*\{', ln)
                    if mm: cur = mm.group(1); continue
                    if cur and ln.strip() == '}': cur = None; continue
                    if cur:
                        fm = re.match(r'\s*\w+\s+(\w+)', ln)
                        if fm and fm.group(1).rstrip('?').rstrip('[]') in mset and fm.group(1).rstrip('?').rstrip('[]') != cur:
                            t = fm.group(1).rstrip('?').rstrip('[]')
                            N(f"prisma:{cur}", "model"); N(f"prisma:{t}", "model")
                            E(f"prisma:{cur}", f"prisma:{t}", "relation", "aligned", f"{rel}:{i}")

order = {"missing":0,"broken":1,"partial":2,"aligned":3}
edges.sort(key=lambda e: (order[e["status"]], e["src"]))
sc = {"aligned":1.0,"partial":0.5,"broken":0.0,"missing":0.0}
pct = round(sum(sc[e["status"]] for e in edges)/max(len(edges),1)*100, 1)
cnt = {k: sum(1 for e in edges if e["status"]==k) for k in order}
out = {"generated": str(datetime.date.today()), "project": PROJECT,
       "engine": "scan.py-regex",   # F-0024's law applied to the data: say who derived it
       "alignment_pct": pct,
       "counts": cnt, "nodes": list(nodes.values()), "edges": edges}
json.dump(out, open(os.path.join(data_dir(), "graph.json"), "w"), indent=1)
print(f"graph.json: {len(nodes)} nodes · {len(edges)} edges · alignment {pct}% · {cnt} · proof-os {version()}")
