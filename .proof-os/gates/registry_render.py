#!/usr/bin/env python3
"""gates/registry_render.py — origin: F-0027 null-may-claim-crash, extended to F-0023.

work.py:49 threw TypeError on a service whose may_claim was null — i.e. `kind: root`,
exactly as PROOFOS.md §4 specifies. The --html path tolerated it, so the crash was
invisible to anyone who only opened the map.

The 0.3.3 gate then made the F-0023 mistake in the check written to prevent it: check 3
tested that `gates` was NON-EMPTY, never that the named gate EXISTED. So

    {"kind":"oracle","may_claim":"proved","gates":["gates/does_not_exist.py"]}

was granted the `proved` ceiling by naming a file that is not there — a silent oracle,
the exact class the check cites. `gates: [""]` and `gates: [null]` are also truthy lists
and passed too. Gate paths are now RESOLVED, through `_oracles.is_green_oracle`.

0.4.0 (ISOLATION-SPEC §3) — A CEILING IS DERIVED, AND A GRANT MAY ONLY LOWER IT.
Through 0.3.4 `may_claim` was GRANTED: a human wrote `believed` next to a judgment
service and that was the whole of its independence. But a judgment service in the
producer's own context is the same model holding the same assumptions — labelling it
`believed` is honest labelling, not a second opinion. It is the same evidence counted
twice.

    effective_ceiling = min( derived_from_isolation , registry_may_claim )

so the registry can only ever LOWER what the isolation supports:

    oracle          -> proved          (a tool produced an exit code)
    fresh-context   -> believed        (a model that saw the artifact and nothing else)
    shared-context  -> echo            (it saw the producer's reasoning)
    none            -> echo            (nothing was recorded)

A registry that grants MORE than the isolation supports is a REGISTRY error — exit 1
naming the service, the grant, the isolation and the derived ceiling.

AND THE ISOLATION IN THIS FILE IS DOCUMENTATION, NOT EVIDENCE. `isolation` is not a
field a service declares about itself — that moves the self-assertion up one level
rather than removing it, and VERDICT.md already forbids self-asserted trust. The value
used for scoring is the one RECORDED AT DISPATCH, by the dispatcher, into the journal:
the dispatcher knows whether it opened a fresh context, because it opened it. What this
gate checks is that the registry's own grant is not already impossible.

LAW (false-red): registry absent/unreadable/wrong shape => exit 2 (unavailable).
                 exit 1 = a real finding in the registry.
Usage: gates/registry_render.py [.proof-os/registry.json] [--plugin-root DIR]
"""
import os as _o, sys as _s
_s.path.insert(0, _o.path.dirname(_o.path.abspath(__file__)))
try:
    from _rc import rc_init; rc_init("registry_render")   # F-0026: liveness is read, not inferred
except Exception:
    pass

import json, os, sys
from _oracles import (is_green_oracle, ISOLATION_LEVELS, normalise_isolation,
                      ceiling_for_isolation, effective_ceiling)

USAGE = "usage: gates/registry_render.py [registry.json] [--plugin-root DIR]"

BLIND = [
    "whether each service's gate actually TESTS its jurisdiction — only that the gate "
    "file exists and is a permitted oracle",
    "whether the jurisdiction globs match any real file in this project",
    "runtime dispatch: this renders the registry, it does not exercise work.py",
    "the isolation each service ACTUALLY gets at dispatch — the `isolation` in this "
    "file is documentation of intent; the scoring value is the one the dispatcher "
    "records into the journal, and nothing here reads the journal",
]


_emitted = []


def emit(extra=()):
    if _emitted:
        return
    _emitted.append(1)
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


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


# ---------------------------------------------------------------- arguments
here = os.path.dirname(os.path.abspath(__file__))
plugin_root = os.path.dirname(here)
positional = []
argv = sys.argv[1:]
i = 0
while i < len(argv):
    a = argv[i]
    if a == "--plugin-root":
        if i + 1 >= len(argv) or argv[i + 1].startswith("--"):
            die(64, f"· --plugin-root needs a directory\n{USAGE}",
                ["everything: the gate never ran"])
        plugin_root = argv[i + 1]
        i += 2
        continue
    if a.startswith("--"):
        die(64, f"· unknown option {a}\n{USAGE}", ["everything: the gate never ran"])
    positional.append(a)
    i += 1

path = positional[0] if positional else os.path.join(
    os.environ.get("PROOF_OS_DIR", ".proof-os"), "registry.json")

if not os.path.isfile(path):
    die(2, f"· {path} not found — cannot render (unavailable)",
        ["every service: there was no registry to read"])
try:
    reg = json.load(open(path, encoding="utf-8"))
except Exception as e:
    die(2, f"· registry unparseable: {e} — unavailable",
        ["every service: the registry could not be parsed"])

if not isinstance(reg, dict):
    die(2, f"· registry is a {type(reg).__name__}, not an object — wrong shape, unavailable",
        ["every service: the top level of the registry is not an object"])

raw_services = reg.get("services", {})
if not isinstance(raw_services, dict):
    die(2, f"· registry.services is a {type(raw_services).__name__}, not an object — "
           f"wrong shape, unavailable",
        ["every service: registry.services is not a service map"])

services = {k: v for k, v in raw_services.items() if not str(k).startswith("_")}
if not services:
    die(2, "· registry has no real services — nothing to render (unavailable)",
        ["every service: the registry declares none"])

if not os.path.isdir(plugin_root):
    die(2, f"· --plugin-root {plugin_root} is not a directory — gate paths cannot be "
           f"resolved, so the proved ceiling cannot be checked (unavailable)",
        ["whether any service's gates exist: there was no plugin tree to resolve against"])

VALID_KINDS = {"root", "scheduler", "oracle", "judgment", "producer",
               "governor", "syslog", "diagnostic", "dispatcher"}
# 0.4.0: `echo` is a new rung BELOW believed. A registry may lower a service to it;
# a service whose reviews carry no isolation is already there whether it says so or not.
VALID_CLAIMS = {"proved", "believed", "echo", "inherits", None}
CEILING_WORDS = {"proved", "believed", "echo"}

# The kinds whose `may_claim` is a claim ABOUT SOMEONE ELSE'S WORK, i.e. a review. Those
# are the ceilings isolation derives, because isolation is a property of a review. A
# producer's `believed` is not a second opinion about anything, so there is nothing for
# an isolation level to lower — the 0.3.4 rules (a proved ceiling needs a real gate)
# still govern it. A non-judgment service that DOES declare an isolation is held to it.
JUDGES_KINDS = {"judgment"}

# THE FOOTER NAMED A ROOT IT HAD NOT USED. It printed "(against <plugin_root>)" for the
# whole run, but _oracles.is_green_oracle tries several roots in order — the argument,
# then the plugin directory this file lives in, then $PROOF_OS_DIR — and returns on the
# first that holds the file. `--plugin-root /tmp/empty` therefore printed
# "resolved 1 (against /tmp/empty)" over a gate that had in fact resolved from the
# plugin tree. (The cwd is no longer a root at all — _oracles.py, frozen — so an audited
# project can no longer mint its own oracle; that half is verified, not restated.)
# This mirrors the frozen module's root ORDER for REPORTING only. The verdict is still
# is_green_oracle's alone; nothing here can turn a red into a green.
def resolving_root(rel):
    """Which root actually holds `rel`, or None. Reporting only — never a decision."""
    pod = os.environ.get("PROOF_OS_DIR")
    for root in [r for r in (plugin_root, here_root, pod) if r]:
        cand = os.path.realpath(os.path.join(root, rel))
        if os.path.isfile(cand) and \
                os.path.dirname(cand) == os.path.realpath(os.path.join(root, "gates")):
            return root
    return None


here_root = os.path.dirname(here)

fails, extra, checked, gates_resolved = [], [], 0, 0
roots_used = {}          # root -> [gate paths it actually resolved]
table = []               # one rendered row per service
for name, meta in sorted(services.items()):
    checked += 1
    if not isinstance(meta, dict):
        # WRONG SHAPE, not a finding: there is no service here to check the ceiling,
        # the kind or the jurisdiction of, so this gate verified nothing about it
        # (FIX-CONTRACT §1 — unavailable, never a defect in the subject).
        die(2, f"· registry.services[{name!r}] is a {type(meta).__name__}, not an "
               f"object — wrong shape, unavailable",
            [f"{name} and every service after it: the entry is not an object, so no "
             f"ceiling, kind, gate or jurisdiction could be read"])

    kind = meta.get("kind")
    claim = meta.get("may_claim")
    # a dict/list kind is unhashable: `kind in JUDGES_KINDS` would be a TypeError, i.e.
    # a traceback out of the gate written to prevent tracebacks. Check 2 below reports it.
    kind_s = kind if isinstance(kind, str) else ""

    # 1. the exact crash: every field the map formats must survive f-string width specs
    for field, val in (("kind", kind), ("may_claim", claim)):
        rendered = val if val is not None else "—"
        try:
            f"{rendered:<12}"
        except (TypeError, ValueError):
            fails.append(f"{name}: {field}={val!r} cannot be width-formatted (this is F-0027)")

    # 2. vocabulary — a typo here renders as a plausible-looking column.
    #    Guard on type first: an unhashable value (dict/list) would crash the `in` test
    #    and exit 1, i.e. a false red from the gate meant to prevent false reds.
    if not isinstance(kind, str) or kind not in VALID_KINDS:
        fails.append(f"{name}: kind {kind!r} not in {sorted(VALID_KINDS)}")
    if not (claim is None or isinstance(claim, str)):
        fails.append(f"{name}: may_claim {claim!r} is {type(claim).__name__}, must be a string or null")
    elif claim not in VALID_CLAIMS:
        fails.append(f"{name}: may_claim {claim!r} not in "
                     f"proved|believed|echo|inherits|null")

    # 3. trust law: a proved ceiling with no RESOLVABLE gate is a silent oracle (F-0023).
    #    Truthiness is not existence — `[""]`, `[null]` and `["gates/nope.py"]` are all
    #    truthy lists and all attest exactly nothing.
    gates = meta.get("gates")
    if gates is not None and not isinstance(gates, list):
        fails.append(f"{name}: gates is {type(gates).__name__}, must be a list")
        gates = None
    usable = []
    for g in (gates or []):
        if not isinstance(g, str) or not g.strip():
            fails.append(f"{name}: gates entry {g!r} is not a gate name — an empty or null "
                         f"entry makes the list truthy while attesting nothing (F-0023)")
            continue
        ok, why = is_green_oracle(g, plugin_root)
        gates_resolved += 1
        if ok:
            usable.append(g)
            r = resolving_root(g.strip().lower().lstrip("./")) if "/" in g else None
            roots_used.setdefault(r or "the deterministic tool allowlist (no path to "
                                       "resolve)", []).append(g)
        else:
            fails.append(f"{name}: gates entry {g!r} cannot render this service green — {why}")
    if claim == "proved" and not usable:
        fails.append(f"{name}: may_claim=proved but no gate that exists can prove it "
                     f"(declared {gates if gates is not None else '[]'}) — the ceiling is "
                     f"granted by a name, not by a check (F-0023)")

    # 3b. 0.4.0 · THE CEILING IS DERIVED FROM ISOLATION AND THE GRANT MAY ONLY LOWER IT.
    #     `isolation` here is INTENDED isolation — documentation. The scoring value is
    #     the one the dispatcher records into the journal. What is checkable on paper is
    #     that the grant is not already impossible for the isolation this row describes.
    raw_iso = meta.get("isolation")
    iso, iso_note = None, ""
    if raw_iso is not None:
        if (not isinstance(raw_iso, str)
                or normalise_isolation(raw_iso) != raw_iso.strip().lower()):
            fails.append(f"{name}: isolation {raw_iso!r} is not one of "
                         f"{'|'.join(ISOLATION_LEVELS)} — an unreadable level reads as "
                         f"`none` (→ echo), so a typo silently buys nothing and hides "
                         f"that it bought nothing")
            iso, iso_note = "none", "unreadable → none"
        else:
            iso, iso_note = normalise_isolation(raw_iso), "declared (intent)"
            if iso == "oracle" and (kind_s in JUDGES_KINDS or not usable):
                # `oracle` means NOT A MODEL AT ALL — a tool produced an exit code.
                # A judgment service is a model however many gates run beside it, and
                # a service with no resolvable gate has no exit code to point at.
                fails.append(f"{name}: isolation=oracle but "
                             + (f"kind={kind!r} is a model, not a tool — a judgment "
                                f"service does not become an oracle by standing next "
                                f"to one; register the gate as the oracle"
                                if kind_s in JUDGES_KINDS else
                                "it names no gate that exists — `oracle` isolation is "
                                "produced by a tool exit code, not by declaring it")
                             + " (reads as `none` → echo)")
                iso, iso_note = "none", "unsupported oracle → none"
    elif kind_s in JUDGES_KINDS:
        # A review with no isolation recorded is `none` → `echo`. Failing closed here
        # is the correct default: an unrecorded review has produced no evidence.
        iso, iso_note = "none", "none recorded"
    elif usable:
        iso, iso_note = "oracle", "derived: a gate that exists"
    elif kind_s == "oracle":
        # An oracle kind is "not a model at all: a tool produced an exit code". With no
        # gate that resolves, no tool produced anything, so the row derives nothing.
        iso, iso_note = "none", "no gate resolves — no exit code was produced"

    ceiling, derived = "—", "—"
    if iso is not None:
        derived = ceiling_for_isolation(iso)
        granted = claim if (isinstance(claim, str) and claim in CEILING_WORDS) else None
        ceiling, over, why = effective_ceiling(iso, granted)
        if over:
            fails.append(f"{name}: may_claim={claim!r} but isolation={iso!r} derives a "
                         f"ceiling of {ceiling!r} — {why}. A grant may only LOWER the "
                         f"derived ceiling, never raise it (ISOLATION-SPEC §3)")
    elif isinstance(claim, str) and claim in CEILING_WORDS:
        ceiling = claim
        iso_note = "not isolation-scored (may_claim is not a review ceiling)"
    table.append((name, kind_s or str(kind), "—" if claim is None else str(claim),
                  iso or "—", derived, ceiling, iso_note, len(usable)))

    # 4. jurisdiction must exist AND be a list, or dispatch has nothing to match on
    if kind != "root":
        j = meta.get("jurisdiction")
        if not j:
            fails.append(f"{name}: no jurisdiction — cannot be dispatched to deterministically")
        elif not isinstance(j, list):
            fails.append(f"{name}: jurisdiction is {type(j).__name__} {j!r}, must be a list "
                         f"of globs — a bare string iterates as characters when matched")
        elif not [x for x in j if isinstance(x, str) and x.strip()]:
            fails.append(f"{name}: jurisdiction {j!r} holds no usable glob")

if gates_resolved == 0:
    extra.append("gate existence: no service in this registry names a gate at all, so "
                 "nothing was resolved against any root")

if roots_used:
    where = " · ".join(f"{len(v)} from {k}" for k, v in sorted(roots_used.items(),
                                                              key=lambda kv: str(kv[0])))
else:
    where = f"none resolved to a gate file (roots offered: {plugin_root})"
print(f"registry services rendered: {checked}, gate references resolved: {gates_resolved} "
      f"({where}), failed: {len(fails)}")

# ── the rendered table (0.4.0: isolation and the ceiling it derives) ─────────────
print(f" {'SERVICE':<30}{'KIND':<11}{'GRANT':<10}{'ISOLATION':<15}"
      f"{'DERIVED':<9}{'CEILING':<9}WHERE THE ISOLATION COMES FROM")
for n, k, c, iso, drv, ceil, note, ngates in table:
    n = n if len(n) <= 29 else n[:28] + "…"
    print(f" {n:<30}{k[:10]:<11}{c[:9]:<10}{iso[:14]:<15}{drv[:8]:<9}{ceil[:8]:<9}"
          f"{note}{f' · {ngates} gate(s) resolve' if ngates else ''}")
print("  DERIVED is what the isolation alone supports; CEILING is "
      "min(derived, may_claim) — a grant may only LOWER it.")
print("  The `isolation` column is this registry's DOCUMENTED INTENT and is NEVER the "
      "value used for scoring: the")
print("  scoring value is the one the dispatcher RECORDS INTO THE JOURNAL at dispatch "
      "(ISOLATION-SPEC §3).")

for x in fails:
    print("  ", x)
emit(extra)
sys.exit(1 if fails else 0)
