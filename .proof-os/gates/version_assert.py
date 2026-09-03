#!/usr/bin/env python3
"""gates/version_assert.py — origin: F-0018 (packaging lied) + F-0024 (stale runtime copy).

F-0018: a sed version-bump chained after a failing compile silently never ran, and three
        releases shipped a stale manifest. Labels are not evidence.
F-0024: a session measured a project with a pre-0.1.5 copy of these scripts, reported the
        numbers as fact, and was wrong by 1,892 edges. Nothing had asserted the version.

This gate refuses to trust the label. It recomputes every shipped file's hash and compares
against MANIFEST.sha256, which is written at package time and read back out of the archive.

Four holes closed in 0.3.4, each of which let the gate certify a build it had not seen:

  * the untracked walk skipped EVERY dot-directory, so `.claude-plugin/` — which is
    shipped and partly tracked — was invisible. A file dropped at
    `.claude-plugin/hooks/backdoor.py` reported `untracked 0`, exit 0. Directories are
    skipped BY NAME now; the list is `SKIP_DIRS` below (five of them), and 0.3.4 also
    skipped every `*.pyc` anywhere. That comment claimed "four", the code said six
    things, and NONE of them were declared: a payload in any of them still reported
    `untracked 0`, exit 0, while NOT CHECKED claimed skipped files "are reported as
    untracked". The blanket .pyc skip is gone (a stray .pyc is now a finding) and every
    skipped directory that exists is counted and named on every exit path.
  * the MANIFEST's own version header (`# proof-os 0.3.3`) was discarded as a comment
    and never compared to plugin.json — F-0018 verbatim, in the file written to close it.
  * a garbage or empty MANIFEST parsed to zero records and produced 46 fabricated
    "absent from the build" findings at exit 1. No parseable record is now exit 2.
  * MANIFEST.sha256 was self-certifying: excluded from the walk and never hashed, even
    though MASTER.sha256 records it. It is now verified against the master.

LAW (false-red): missing manifest / unparseable manifest / unreadable plugin => exit 2.
                 exit 1 = the build does not match what it claims to be.
Usage: gates/version_assert.py [plugin_root]
"""
import os as _o, sys as _s
_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init; rc_init("version_assert")   # F-0026: liveness is read, not inferred
except Exception:
    pass

import hashlib, json, os, py_compile, re, shutil, subprocess, sys, tempfile

BLIND_BASE = [
    "whether the shipped code is CORRECT — this asserts identity and syntax, never behaviour",
    # This line used to read "files excluded from MANIFEST.sha256 on purpose: they are
    # reported as untracked, not judged". Five whole directories were skipped by the walk
    # and were therefore NOT reported as untracked and not reported at all — so the
    # declared blind spot was actively misleading, which is a law-5 violation on top of
    # the law-1 one. What is skipped is now COUNTED and NAMED, per run, below.
    "files listed in MANIFEST.sha256 are checked for identity only — their contents are "
    "never judged",
    "MASTER.sha256 itself is not verified against anything above it",
]


_emitted = []


def emit(extra=()):
    if _emitted:
        return
    _emitted.append(1)
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND_BASE))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


# law 5 ON THE SIGNAL PATH. Shell gates carry `trap _nc EXIT`; the python gates carried
# nothing, so `kill -TERM` gave rc=143 and ZERO NOT CHECKED lines.
#
# LAW 5 MUST NOT BE BOUGHT WITH LAW 7. The first version restored SIG_DFL and re-killed,
# which OVERWROTE the recorder rc_init() installed above, so `_write(128+signo)` never
# ran and a killed gate recorded `running pid=… started=…` — F-0026's exact shape,
# reintroduced by the fix for law 5. FIX-CONTRACT §6: the recorder must record the code
# the process actually produced. This handler therefore CHAINS to the handler that was
# already installed (_rc.py's), which writes `exit=128+signo observed=signal` and exits.
# Both laws hold. The SIG_DFL re-kill is only the fallback for when no python-level
# handler was there to chain to.
_prev_handlers = {}


def _on_signal(signo, frame):
    import signal as _sig
    emit([f"everything not already printed above — the gate was killed by signal "
          f"{_sig.Signals(signo).name} before it could finish"])
    sys.stdout.flush()
    prev = _prev_handlers.get(signo)
    if callable(prev):
        prev(signo, frame)      # _rc.py's recorder: records 128+signo, then exits
        return                  # (unreachable in practice — prev exits)
    _sig.signal(signo, _sig.SIG_DFL)
    os.kill(os.getpid(), signo)


def _install_signal_handlers():
    import signal as _sig
    for _name in ("SIGTERM", "SIGINT", "SIGHUP"):
        signum = getattr(_sig, _name, None)
        if signum is None:
            continue
        try:
            _prev_handlers[signum] = _sig.getsignal(signum)
            _sig.signal(signum, _on_signal)
        except (ValueError, OSError, AttributeError):
            pass


_install_signal_handlers()


root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
root = os.path.abspath(root)
manifest_path = os.path.join(root, ".claude-plugin", "plugin.json")
hashes_path = os.path.join(root, "MANIFEST.sha256")

if not os.path.isdir(root):
    die(2, f"· {root} is not a directory — unavailable",
        ["every shipped file: there was no plugin tree"])
if not os.path.isfile(manifest_path):
    die(2, f"· no .claude-plugin/plugin.json under {root} — unavailable",
        ["every shipped file: the plugin declares no identity to check against"])
try:
    declared = json.load(open(manifest_path, encoding="utf-8")).get("version")
except Exception as e:
    die(2, f"· manifest unparseable: {e} — unavailable",
        ["every shipped file: plugin.json could not be parsed"])
if not declared:
    die(2, "· manifest declares no version — unavailable",
        ["every shipped file: plugin.json states no version"])

fails, extra = [], []

# ---------------------------------------------------------------- 0 · the walk
# BY NAME. `.claude-plugin/` is shipped content; skipping every dot-directory made the
# most security-relevant directory in the package the one place a file could hide.
#
# F-0036 MOVED, NOT CLOSED. Five directories were still skipped silently, plus every
# *.pyc anywhere, and the NOT CHECKED line claimed skipped files "are reported as
# untracked" — they were not reported at all. A payload at node_modules/backdoor.py,
# .proof-os/x.py, .ruff_cache/x.py, __pycache__/x.py or gates/evil.pyc gave
# `untracked 0`, exit 0, and a blind-spot declaration that said the opposite.
#
# Two changes:
#   * the blanket ".pyc anywhere" skip is gone. Compiled bytecode inside __pycache__ is
#     covered by the directory skip below; a .pyc sitting anywhere ELSE is a file in the
#     shipped tree that no hash covers, and it is now reported as untracked (a finding).
#   * every skipped directory that ACTUALLY EXISTS is counted and named in NOT CHECKED,
#     with the number of files hidden behind it, so the declaration is true rather than
#     reassuring.
SKIP_DIRS = {".proof-os", ".git", ".ruff_cache", "__pycache__", "node_modules"}
all_files = []
skipped_counts = {}
for r, ds, fs in os.walk(root):
    for d in list(ds):
        if d in SKIP_DIRS:
            rel_d = os.path.relpath(os.path.join(r, d), root).replace(os.sep, "/")
            n = 0
            for _sr, _sd, _sf in os.walk(os.path.join(r, d)):
                n += len(_sf)
            skipped_counts[rel_d] = skipped_counts.get(rel_d, 0) + n
    ds[:] = [x for x in ds if x not in SKIP_DIRS]
    for f in fs:
        rel = os.path.relpath(os.path.join(r, f), root).replace(os.sep, "/")
        all_files.append(rel)
all_files.sort()

if skipped_counts:
    total_skipped = sum(skipped_counts.values())
    detail = ", ".join(f"{d}/ ({n} file{'s' if n != 1 else ''})"
                       for d, n in sorted(skipped_counts.items()))
    extra.append(f"{total_skipped} file(s) under {detail}: this gate never walks those "
                 f"directories, so they are NOT hashed and NOT reported as untracked — "
                 f"a payload placed in any of them would not be seen by this check")
else:
    extra.append(f"nothing beyond the list below: none of "
                 f"{', '.join(sorted(SKIP_DIRS))} exists under {root}, so the walk "
                 f"covered every file in the tree")

# ---------------------------------------------------------------- 1 · syntax
# F-0018 shipped because a compile failure was swallowed and the chained step never ran.
# 0.3.3 compile-checked .py only, while printing "26 scripts compile" over a build that
# also ships 8 .sh gates and an .mjs config that nothing had ever parsed.
checked_py = checked_sh = checked_js = 0
BASH = shutil.which("bash")
NODE = shutil.which("node")

for rel in all_files:
    p = os.path.join(root, rel)
    if rel.endswith(".py"):
        try:
            py_compile.compile(p, cfile=tempfile.mktemp(), doraise=True)
            checked_py += 1
        except py_compile.PyCompileError as e:
            fails.append(f"{rel} does not compile: {str(e).splitlines()[0]}")
        except Exception as e:
            extra.append(f"python syntax of {rel} ({e})")
    elif rel.endswith((".sh", ".bash")):
        if not BASH:
            continue
        r2 = subprocess.run([BASH, "-n", p], capture_output=True, text=True)
        checked_sh += 1
        if r2.returncode != 0:
            fails.append(f"{rel} is not valid bash: "
                         f"{(r2.stderr or r2.stdout).strip().splitlines()[0] if (r2.stderr or r2.stdout).strip() else 'bash -n failed'}")
    elif rel.endswith((".mjs", ".cjs", ".js")):
        if not NODE:
            continue
        r2 = subprocess.run([NODE, "--check", p], capture_output=True, text=True)
        checked_js += 1
        if r2.returncode != 0:
            fails.append(f"{rel} is not valid javascript: "
                         f"{(r2.stderr or r2.stdout).strip().splitlines()[0] if (r2.stderr or r2.stdout).strip() else 'node --check failed'}")

n_sh = sum(1 for f in all_files if f.endswith((".sh", ".bash")))
n_js = sum(1 for f in all_files if f.endswith((".mjs", ".cjs", ".js")))
if n_sh and not BASH:
    extra.append(f"the syntax of {n_sh} shell script(s): bash is not on PATH")
if n_js and not NODE:
    extra.append(f"the syntax of {n_js} javascript file(s): node is not on PATH")

# ---------------------------------------------------------------- 2 · the manifest
if not os.path.isfile(hashes_path):
    print(f"· proof-os {declared} · {checked_py} python + {checked_sh} shell + "
          f"{checked_js} js file(s) parse")
    for x in fails:
        print("  ", x)
    if fails:
        emit(extra + ["file hashes: there is no MANIFEST.sha256 to compare against"])
        sys.exit(1)
    die(2, "· no MANIFEST.sha256 — running from a working tree, not a packaged build "
           "(unavailable)",
        extra + ["every file's identity: there is no MANIFEST.sha256 in this tree"])

HASH_LINE = re.compile(r"^([0-9a-fA-F]{64})\s\s(.+)$")
recorded, junk_lines, header_ver = {}, [], None
try:
    manifest_text = open(hashes_path, encoding="utf-8", errors="replace").read()
except OSError as e:
    die(2, f"· MANIFEST.sha256 unreadable: {e} — unavailable",
        extra + ["every file's identity: the manifest could not be read"])

for lineno, line in enumerate(manifest_text.splitlines(), 1):
    s = line.strip()
    if not s:
        continue
    if s.startswith("#"):
        # The header is EVIDENCE, not decoration. F-0018 is a stale label; a manifest
        # stamped 0.3.3 sitting beside a plugin.json saying 0.3.4 is that same lie.
        m = re.match(r"#\s*proof-os\s+v?(\d+\.\d+\.\d+(?:[-+][\w.]+)?)", s, re.I)
        if m and header_ver is None:
            header_ver = m.group(1)
        continue
    m = HASH_LINE.match(line.rstrip("\n"))
    if not m:
        junk_lines.append(lineno)
        continue
    recorded[m.group(2).strip()] = m.group(1).lower()

if not recorded:
    die(2, f"· MANIFEST.sha256 holds no well-formed '<64hex>  <path>' record "
           f"({len(junk_lines)} unparseable line(s)) — a corrupt manifest is unavailable, "
           f"not {len(all_files)} findings",
        extra + ["every file's identity: the manifest could not be parsed"])

if junk_lines:
    fails.append(f"MANIFEST.sha256 has {len(junk_lines)} unparseable line(s) "
                 f"(first at line {junk_lines[0]}) — whatever they name is not verified "
                 f"by anything, silently")

if header_ver is None:
    extra.append("the MANIFEST.sha256 version header: it states no 'proof-os <version>', "
                 "so it cannot be compared with plugin.json")
elif header_ver != declared:
    fails.append(f"MANIFEST.sha256 header says proof-os {header_ver} but "
                 f".claude-plugin/plugin.json says {declared} — the manifest was stamped "
                 f"for a different release than the one it is shipping (F-0018)")

# ---------------------------------------------------------------- 3 · contents
drifted, missing = [], []
for rel, want in recorded.items():
    p = os.path.join(root, rel)
    if not os.path.isfile(p):
        missing.append(rel); continue
    got = hashlib.sha256(open(p, "rb").read()).hexdigest()
    if got != want:
        drifted.append(rel)

# ---------------------------------------------------------------- 4 · untracked
# F-0036: the checks above only look at files the manifest already knows about. A file
# ADDED after the version was stamped is invisible to both — no drift, no missing, exit 0.
untracked = [rel for rel in all_files
             if rel != "MANIFEST.sha256" and rel not in recorded]

# ---------------------------------------------------------------- 5 · the manifest itself
# MANIFEST.sha256 excluded itself from every check it ran. MASTER.sha256 records it.
master_state = "no MASTER.sha256 above this plugin"
here = root
for _ in range(4):
    here = os.path.dirname(here)
    cand = os.path.join(here, "MASTER.sha256")
    if os.path.isfile(cand):
        rel_manifest = os.path.relpath(hashes_path, here).replace(os.sep, "/")
        master = {}
        for line in open(cand, encoding="utf-8", errors="replace").read().splitlines():
            m = HASH_LINE.match(line.rstrip("\n"))
            if m:
                master[m.group(2).strip()] = m.group(1).lower()
        want = master.get(rel_manifest)
        if want is None:
            master_state = (f"{os.path.basename(cand)} records no hash for "
                            f"{rel_manifest} — the manifest certifies itself")
            extra.append(f"MANIFEST.sha256's own integrity: {master_state}")
        else:
            got = hashlib.sha256(open(hashes_path, "rb").read()).hexdigest()
            if got != want:
                fails.append(f"MANIFEST.sha256 does not match its hash in "
                             f"{os.path.relpath(cand, root)} — the list of hashes has "
                             f"itself been edited since the release was stamped")
                master_state = "MISMATCH"
            else:
                master_state = f"verified against {os.path.relpath(cand, root)}"
        break
else:
    extra.append("MANIFEST.sha256's own integrity: no MASTER.sha256 was found above "
                 f"{root}, so the manifest is self-certifying")

fails += [f"{r} listed in MANIFEST.sha256 but absent from the build" for r in missing]
fails += [f"{r} content does not match its recorded hash — the label is stale" for r in drifted]
fails += [f"{r} present but never hashed — added after the version was stamped, so every "
          f"'all files verified' claim about this build excluded it (F-0036)" for r in untracked]

print(f"proof-os {declared} · parsed {checked_py} py + {checked_sh} sh + {checked_js} js · "
      f"{len(recorded)} files hashed · drifted {len(drifted)} · missing {len(missing)} · "
      f"untracked {len(untracked)} · manifest header {header_ver or 'ABSENT'} · "
      f"manifest self: {master_state}")
for x in fails:
    print("  ", x)
emit(extra)
sys.exit(1 if fails else 0)
