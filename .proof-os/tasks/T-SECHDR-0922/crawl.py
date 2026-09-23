"""Download the live influora.in bundle to closure (read-only public GETs)."""
import os, re, sys, urllib.request

BASE = "https://influora.in"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "live-bundle")
os.makedirs(os.path.join(OUT, "assets"), exist_ok=True)

def get(path):
    req = urllib.request.Request(BASE + path, headers={"User-Agent": "Mozilla/5.0 (inventory; read-only)"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status, r.headers.get("Content-Type", ""), r.read()

html = open(os.path.join(OUT, "index.html"), "rb").read().decode("utf-8")
queue = []
for m in re.finditer(r'(?:src|href)="(/[^"]+\.(?:js|css|mjs))"', html):
    queue.append(m.group(1))

seen = set()
log = []
# asset reference patterns inside JS/CSS
pat = re.compile(r'(?:"|\'|`|\(|/)((?:\.{1,2}/|/)?(?:assets/)?[A-Za-z0-9_\-.$@~]+-[A-Za-z0-9_\-]{6,12}\.(?:js|css|mjs))')
while queue:
    p = queue.pop(0)
    if p in seen:
        continue
    seen.add(p)
    try:
        st, ct, body = get(p)
    except Exception as e:
        log.append(f"FAIL {p} {e}")
        continue
    if "text/html" in ct:
        log.append(f"HTMLFALLBACK {p} (not a real asset)")
        continue
    local = os.path.join(OUT, p.lstrip("/").replace("/", os.sep))
    os.makedirs(os.path.dirname(local), exist_ok=True)
    open(local, "wb").write(body)
    log.append(f"OK {st} {len(body):>9} {ct} {p}")
    if p.endswith((".js", ".mjs", ".css")):
        text = body.decode("utf-8", "replace")
        for m in pat.finditer(text):
            ref = m.group(1)
            name = ref.split("/")[-1]
            if p.startswith("/assets/") or "assets/" in ref:
                queue.append("/assets/" + name)
            else:
                queue.append("/" + name)

open(os.path.join(OUT, "..", "crawl-log.txt"), "w").write("\n".join(log) + "\n")
print("\n".join(log))
print("files:", len([l for l in log if l.startswith("OK")]))
