"""stale-runtime-copy scanner.

The class: this project keeps its own copies of the plugin's gates, and those copies drift. A gate
that has drifted BEHIND runs weaker checks than the canonical one while still reporting green, so
the trust layer reports proved on evidence it never actually gathered.

Rule: every gate file present in BOTH trees must be byte-identical, UNLESS the project copy declares
itself a fork with `PROJECT-LOCAL FORK:` and a reason. A declared fork is reviewable; silent drift
is not.

Second rule: a gate the registry names must EXIST. F-0469 recorded five gates with run records whose
files were gone - the project ran gates whose copies no longer exist, so nothing can re-run them.

The plugin root is discovered from the environment rather than hardcoded: PROOF_OS_PLUGIN, else the
newest rpm/plugin_* directory under the local-agent-mode-sessions tree. If it cannot be found the
scanner exits 2 (unavailable) - never 0, because "I could not compare" must not read as "identical".
"""
import hashlib
import json
import os
import pathlib
import sys

PROJECT_GATES = pathlib.Path(".proof-os/gates")
FORK_MARKER = "PROJECT-LOCAL FORK:"


def find_plugin_root():
    env = os.environ.get("PROOF_OS_PLUGIN")
    if env and pathlib.Path(env, "gates").is_dir():
        return pathlib.Path(env)
    base = pathlib.Path(os.environ.get("APPDATA", "")) / "Claude" / "local-agent-mode-sessions"
    if not base.is_dir():
        return None
    candidates = [p for p in base.glob("*/*/rpm/plugin_*") if (p / "gates").is_dir()]
    if not candidates:
        return None
    return max(candidates, key=lambda p: p.stat().st_mtime)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if not PROJECT_GATES.is_dir():
        print("- .proof-os/gates missing - unavailable")
        return 2
    plugin = find_plugin_root()
    if plugin is None:
        print("- cannot locate the proof-os plugin to compare against - UNAVAILABLE, not a pass.")
        print("  Set PROOF_OS_PLUGIN to the plugin root if it lives somewhere else.")
        return 2
    print(f"- comparing against {plugin.name}")

    shared, diverged, declared = 0, [], []
    for canonical in sorted((plugin / "gates").iterdir()):
        if not canonical.is_file():
            continue
        local = PROJECT_GATES / canonical.name
        if not local.is_file():
            continue
        shared += 1
        if sha(canonical) == sha(local):
            continue
        try:
            head = local.read_text(encoding="utf-8", errors="replace")[:2000]
        except Exception:
            head = ""
        if FORK_MARKER in head:
            declared.append(canonical.name)
        else:
            diverged.append(canonical.name)

    print(f"- {shared} gate(s) shared with the plugin; {len(declared)} declared fork(s)")
    for name in declared:
        print(f"    declared fork: {name}")

    if shared == 0:
        print("- ZERO shared gates, which cannot be right - broken instrument, not a pass")
        return 2

    ok = True
    if diverged:
        for name in diverged:
            print(f"    UNDECLARED DRIFT: {name}")
        print("VERDICT: broken - the project runs a gate that differs from the canonical copy with no")
        print("         stated reason. A copy that has drifted behind runs weaker checks and still")
        print("         reports green, which is how this class produced four findings.")
        ok = False

    # --- registry references must resolve ---------------------------------------------------
    registry = pathlib.Path(".proof-os/registry.json")
    if registry.is_file():
        try:
            text = registry.read_text(encoding="utf-8", errors="replace")
            data = json.loads(text)
        except Exception:
            data = None
        if data is not None:
            missing = []
            for named in sorted(set(_iter_gate_names(data))):
                # The registry writes references as "gates/build.sh"; the gates dir is already
                # .proof-os/gates, so joining naively looked for gates/gates/build.sh and reported
                # three files that were sitting right there. Resolve on the basename.
                leaf = named.split("/")[-1]
                cand = [PROJECT_GATES / leaf,
                        PROJECT_GATES / f"{leaf}.py",
                        PROJECT_GATES / f"{leaf}.sh"]
                if not any(c.is_file() for c in cand):
                    missing.append(named)
            if missing:
                for name in missing:
                    print(f"    REGISTRY NAMES A MISSING GATE: {name}")
                print("VERDICT: broken - the registry names a gate whose file is absent, so nothing")
                print("         can re-run it and any past verdict from it is unreproducible (F-0469)")
                ok = False
            else:
                print("- every gate the registry names resolves to a file")
    else:
        print("- no registry.json - REGISTRY REFERENCES NOT CHECKED")

    if not ok:
        return 1
    print("- no undeclared drift, and every named gate resolves")
    return 0


def _iter_gate_names(node):
    """Yield anything that looks like a gate reference anywhere in the registry."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key in ("gate", "gates", "gate_id") and isinstance(value, str):
                yield value
            elif key in ("gate", "gates") and isinstance(value, list):
                for v in value:
                    if isinstance(v, str):
                        yield v
            else:
                yield from _iter_gate_names(value)
    elif isinstance(node, list):
        for item in node:
            yield from _iter_gate_names(item)


if __name__ == "__main__":
    sys.exit(main())
