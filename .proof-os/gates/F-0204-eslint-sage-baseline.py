#!/usr/bin/env python3
# gates/F-0204-eslint-sage-baseline.py — origin: F-0204 (frontend-lint-violations),
# also the exit test for F-0209 (frontend-lint-violations-repair: baseline now empty).
# Runs kavya's sage eslint ruleset over src/ and FAILS on any error that is neither
# (a) the F-0208 gate-config class ("Definition for rule ... was not found"), nor
# (b) a baselined pending-ruling site listed below (each with its recorded reason).
# The baseline is (file, rule) pairs — line numbers shift. A NEW violation anywhere,
# or a new instance of a baselined rule in a NON-baselined file, is exit 1.
# exit 2 = eslint could not run (never a pass).
import json, subprocess, sys, os, collections
ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CFG = r"C:/Users/Sage world/AppData/Roaming/Claude/local-agent-mode-sessions/3a613ffb-5d5c-4472-8b3e-6aa41ae4204d/b66976d0-d58d-4e93-84e6-9997f7df1500/rpm/plugin_01BnEF97nKc8pyi8gL7qpsSM/gates/eslint.sage.mjs"
# Baseline EMPTY as of the 2026-08-15 F-0209 ruling (Swapnil): motion.* elements are
# exempted in the rule itself, so the last 4 baselined sites are clean. Also closes
# F-0209 (frontend-lint-violations-repair). Any error this gate reports is NEW.
BASELINE = {}
if not os.path.isfile(CFG):
    print("· sage eslint config not found — unavailable"); sys.exit(2)
try:
    r = subprocess.run(["npx.cmd", "--no-install", "eslint", "--config", CFG,
                        "--format", "json", "src"],
                       capture_output=True, cwd=ROOT, timeout=560)
except Exception as e:
    print(f"· eslint could not run: {e} — unavailable"); sys.exit(2)
out = (r.stdout or b"").decode("utf-8", errors="replace")
try:
    data = json.loads(out)
except Exception:
    print("· eslint produced no parseable JSON — unavailable"); print(out[:400]); sys.exit(2)
seen = collections.Counter(); new = []
for f in data:
    rel = os.path.relpath(f["filePath"], ROOT).replace("\\", "/")
    for m in f["messages"]:
        if m.get("severity") != 2: continue
        if "was not found" in (m.get("message") or ""): continue  # F-0208 class, gated separately
        key = (rel, m.get("ruleId") or "?")
        seen[key] += 1
        if seen[key] > BASELINE.get(key, 0):
            new.append(f'{rel}:{m.get("line")} [{key[1]}] {m.get("message","")[:80]}')
if new:
    print(f"NEW violations beyond the pending-ruling baseline ({len(new)}):")
    for n in new: print("  " + n)
retired = [k for k in BASELINE if seen.get(k, 0) < BASELINE[k]]
if retired:
    print("baseline sites now CLEAN (shrink the baseline):")
    for k in retired: print(f"  {k[0]} [{k[1]}]")
print("NOT CHECKED: warnings (exhaustive-deps runs at warn per project policy); style props on motion.* elements (exempted by the F-0209 ruling — gates/F-0208-rules-resolvable.sh proves the exemption stays scoped); 'was not found' errors are skipped here — F-0208's gate owns that class")
sys.exit(1 if new else 0)
