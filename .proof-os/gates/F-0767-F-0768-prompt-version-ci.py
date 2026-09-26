#!/usr/bin/env python
"""gates/F-0767-F-0768-prompt-version-ci.py

Origin: F-0767 (ci-gate-inert) and F-0768 (prompt-version-not-bumped), both found by priya on
2026-09-17 in the CI-1 last call (.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/PRIYA-LASTCALL-CI1-0917.md),
fixed by vikram in CI-1 round 3, proved fresh-context by meera 2026-09-18
(MEERA-CI1R3-PROOF-0918.md, all 10 done_when clauses MET).

THE DEFECTS.
  F-0767  On a force-push CI passes the OLD tip as `--since`; a fresh clone cannot resolve it,
          and ci/stale-comment-check.py fell back to HEAD~1, checking only the newest commit.
          An unbumped prompt change earlier in the rewritten branch passed with exit 0.
  F-0768  Four historical commits on feat/meera-creator-phase-b0 changed PROMPT_SOURCES
          without bumping PROMPT_VERSION, so this branch's first push would be red. Resolved
          by an explicit, reviewed exemption list naming exactly those four SHAs.

WHAT THIS GATE DECIDES.
  A  Rebuild the force-push scenario in a throwaway repo (real bare origin, a --no-local clone
     so the old tip is genuinely unreachable) against the CURRENT script. It must exit
     non-zero and name the unbumped commit. CONTROL: a normal push whose commit does bump
     must exit 0. Without the control, a script that fails everything would pass this gate.
  B  Run rule 3 on THIS repo's real history back to origin/main. It must report 0 findings
     WITH the exemption file, and exactly the four exempted SHAs WITHOUT it. The second run
     proves the exemptions are doing the work, so a rule 3 that finds nothing at all cannot
     pass.

NOT CHECKED: rules 1, 2 and 4 of the same script, including the untracked-document citation
meera found (the workflow cites two task docs that must be committed with Wave U); a duplicate
SHA in the exemption file (accepted silently, meera finding 2); whether GitHub really sends the
old tip on a force-push (taken from the Actions docs, not observed); cross-branch reuse (covered
by meera's proof, not re-run here).

Exit 0 proved · 1 broken · 2 unavailable (never green).
"""
import importlib.util
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "ci" / "stale-comment-check.py"
EXEMPT = ROOT / ".proof-os" / "gates" / "f0150-prompt-version-exempt.txt"
EXPECTED_EXEMPT_PREFIXES = ("1792c37", "8c7b18b", "a33f07e", "df20091")


def git(cwd, *args, check=True):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=check)


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def part_a() -> list[str]:
    errors = []
    tmp = Path(tempfile.mkdtemp(prefix="f0767-"))
    try:
        origin = tmp / "origin.git"
        dev = tmp / "dev"
        git(tmp, "init", "-q", "--bare", str(origin))
        git(tmp, "clone", "-q", origin.as_uri(), str(dev))
        for k, v in (("user.email", "t@t"), ("user.name", "t"), ("core.autocrlf", "false")):
            git(dev, "config", k, v)
        (dev / "ci").mkdir()
        shutil.copy2(SCRIPT, dev / "ci" / "stale-comment-check.py")
        write(dev / "influora-ai/app/config.py", 'PROMPT_VERSION = "v1"\n')
        write(dev / "influora-ai/app/prompt/persona.py", "persona one\n")
        git(dev, "add", "-A"); git(dev, "commit", "-qm", "c0")
        git(dev, "branch", "-M", "main"); git(dev, "push", "-q", "origin", "main")
        git(dev, "checkout", "-qb", "feat/x")
        write(dev / "influora-ai/app/prompt/persona.py", "persona two\n")
        write(dev / "influora-ai/app/config.py", 'PROMPT_VERSION = "v2"\n')
        git(dev, "commit", "-qam", "c1 bumped")
        before_normal = git(dev, "rev-parse", "main").stdout.strip()
        git(dev, "push", "-q", "origin", "feat/x")
        old_tip = git(dev, "rev-parse", "HEAD").stdout.strip()

        # CONTROL: a normal push of a correctly bumped commit must be green.
        ok = subprocess.run([sys.executable, "ci/stale-comment-check.py", "--since", before_normal,
                             "--event", "push"], cwd=dev, capture_output=True, text=True)
        if ok.returncode != 0:
            errors.append(f"A-control: a normal bumped push exited {ok.returncode}, expected 0 "
                          f"(the gate would pass on a script that fails everything): {ok.stdout[-400:]}")

        # Rewrite: c1' changes the prompt WITHOUT a bump, c2' is an unrelated tip; force-push.
        git(dev, "reset", "-q", "--hard", "main")
        write(dev / "influora-ai/app/prompt/persona.py", "persona THREE unbumped\n")
        git(dev, "commit", "-qam", "c1' prompt change, no bump")
        bad = git(dev, "rev-parse", "HEAD").stdout.strip()
        write(dev / "NOTES.txt", "notes\n")
        git(dev, "add", "NOTES.txt"); git(dev, "commit", "-qm", "c2' unrelated tip")
        git(dev, "push", "-q", "--force", "origin", "feat/x")

        clone = tmp / "ci-clone"
        git(tmp, "clone", "-q", "--no-local", origin.as_uri(), str(clone))
        git(clone, "checkout", "-q", "feat/x")
        git(clone, "config", "core.autocrlf", "false")
        present = git(clone, "cat-file", "-e", f"{old_tip}^{{commit}}", check=False).returncode == 0
        if present:
            errors.append("A-setup: the old tip is still reachable in the CI clone; the scenario is not a real force-push")
        r = subprocess.run([sys.executable, "ci/stale-comment-check.py", "--since", old_tip,
                            "--event", "push"], cwd=clone, capture_output=True, text=True)
        out = r.stdout + r.stderr
        if r.returncode == 0:
            errors.append(f"A: force-push with an unreachable old tip exited 0 (F-0767 regressed): {out[-400:]}")
        elif bad[:10] not in out:
            errors.append(f"A: force-push exited {r.returncode} but did not name the unbumped commit {bad[:10]}: {out[-400:]}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return errors


def part_b() -> list[str]:
    errors = []
    if git(ROOT, "rev-parse", "--verify", "-q", "origin/main", check=False).returncode != 0:
        print("UNAVAILABLE: origin/main is not present in this checkout")
        sys.exit(2)
    if not EXEMPT.is_file():
        return [f"B: exemption file {EXEMPT.relative_to(ROOT).as_posix()} is missing"]
    spec = importlib.util.spec_from_file_location("stale_comment_check_gate", SCRIPT)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)

    with_ex = m.rule3_prompt_version("origin/main", "push")
    committed = [f for f in with_ex if "working tree" not in f]
    if committed:
        errors.append(f"B: rule 3 on origin/main..HEAD WITH exemptions reported {len(committed)} commit finding(s): "
                      + " | ".join(f[:160] for f in committed))

    original = m._load_exemptions
    m._load_exemptions = lambda *a, **k: ({}, [])
    try:
        without = m.rule3_prompt_version("origin/main", "push")
    finally:
        m._load_exemptions = original
    named = {p for p in EXPECTED_EXEMPT_PREFIXES if any(p in f for f in without)}
    missing = set(EXPECTED_EXEMPT_PREFIXES) - named
    if missing:
        errors.append(f"B: WITHOUT exemptions rule 3 did not name {sorted(missing)}; the exemptions are not what "
                      f"makes it green, or history changed. Findings: " + " | ".join(f[:120] for f in without))
    return errors


def main() -> int:
    if shutil.which("git") is None:
        print("UNAVAILABLE: git not on PATH")
        return 2
    if not SCRIPT.is_file():
        print(f"BROKEN: {SCRIPT} is missing")
        return 1
    errors = part_a() + part_b()
    if errors:
        for e in errors:
            print("BROKEN:", e)
        return 1
    print("PROVED: force-push with an unreachable old tip is red and names the unbumped commit; a normal "
          "bumped push is green; origin/main..HEAD is clean with the exemptions and names exactly the four "
          "exempted SHAs without them")
    return 0


if __name__ == "__main__":
    sys.exit(main())
