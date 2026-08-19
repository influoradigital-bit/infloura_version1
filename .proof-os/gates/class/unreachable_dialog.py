"""Class detector: a visibility state whose setter is never called with true.

Refined after a false negative on creator-chat.tsx `showRevisionHandler`.

The subtlety: when JSX is `{state && <Dialog onOpenChange={setter} />}`, the setter IS
passed as a prop, but the component is NOT MOUNTED while state is false — so that prop
can never open it. Only a setter reference OUTSIDE the state-guarded subtree, or an
explicit `setter(true)`, is a real open path.
"""
import re, os, json, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "src"
hits, scanned = [], 0

STATE_RE = re.compile(
    r"const \[(\w+), (set\w+)\] = (?:React\.)?useState(?:<[^>]*>)?\(\s*false\s*\)"
)
NAMEY = re.compile(r"show|open|visible|modal|dialog|drawer|sheet|panel", re.I)


def guarded_spans(src, var):
    """Character spans of `{var && ... }` blocks, by brace matching."""
    spans = []
    for m in re.finditer(r"\{\s*" + re.escape(var) + r"\s*&&", src):
        depth, i = 0, m.start()
        while i < len(src):
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
                if depth == 0:
                    spans.append((m.start(), i))
                    break
            i += 1
    return spans


for root, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in ("__tests__", "node_modules")]
    for fn in files:
        if not fn.endswith((".tsx", ".ts")) or ".test." in fn:
            continue
        p = os.path.join(root, fn).replace(os.sep, "/")
        scanned += 1
        s = open(p, encoding="utf-8", errors="replace").read()
        for m in STATE_RE.finditer(s):
            var, setter = m.group(1), m.group(2)
            if not NAMEY.search(var):
                continue
            e = re.escape(setter)
            mounted_guard = guarded_spans(s, var)
            mounted_open = re.search(r"open=\{\s*" + re.escape(var), s)
            if not mounted_guard and not mounted_open:
                continue

            def outside(pos):
                return not any(a <= pos <= b for a, b in mounted_guard)

            real_open = False
            for mm in re.finditer(e + r"\(\s*true\s*\)", s):
                if outside(mm.start()):
                    real_open = True
            # setter(expr) with a computed/unknown value, outside the guarded subtree
            for mm in re.finditer(e + r"\(\s*(?!true\s*\)|false\s*\))", s):
                if outside(mm.start()):
                    real_open = True
            # setter handed to something outside the guarded subtree (a button, a hook)
            for mm in re.finditer(r"[=\{]\s*" + e + r"\b(?!\()", s):
                if outside(mm.start()):
                    real_open = True

            if not real_open:
                hits.append({
                    "file": p,
                    "line": s[:m.start()].count("\n") + 1,
                    "state": var,
                    "setter": setter,
                    "closes_only": len(re.findall(e + r"\(\s*false\s*\)", s)),
                })

print(json.dumps({"scanned": scanned, "hits": hits}, indent=1))
# Gate contract: 1 = the class is present (defect unfixed), 0 = clean.
sys.exit(1 if hits else 0)
