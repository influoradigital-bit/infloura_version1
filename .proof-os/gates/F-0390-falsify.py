"""Falsification harness for .proof-os/gates/F-0390-money-flags-declared-in-build.sh

Written to a FILE, not a heredoc: the previous two attempts passed these regexes through
`bash -c "python -c '...'"`, where the shell ate a level of backslash escaping and the two
Dockerfile-ordering probes silently matched nothing, reported exit 0, and would have been
read as "the gate greens this break" when in fact the break was never applied.
"""
import concurrent.futures as cf
import os, re, shutil, subprocess, sys, tempfile

# Resolved from this file's own location (.proof-os/gates/) so the harness travels with the repo.
# It was hardcoded to one machine's absolute path while it lived in a scratch directory.
_HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(_HERE))
GATE = os.path.join(_HERE, "F-0390-money-flags-declared-in-build.sh")
# Never inside the repo: the trees are deliberately broken copies, and a stray one left under
# .proof-os/ would be picked up by the next scan as though it were real project state.
WORK = os.environ.get("F0390_WORKDIR") or os.path.join(tempfile.gettempdir(), "f0390-falsify")
WF = os.path.join(".github", "workflows", "publish-images.yml")
CI = os.path.join(".github", "workflows", "frontend-checks.yml")
# Round 4: this was a closed five-entry list, and priya's point was that a closed list bounds what
# the harness can even EXPRESS — every break she found lived in a file or a mechanism not in it.
# It is now the set of files the gate reads, and `tree()` copies the whole repo-relative path for
# anything a probe names, so a probe may also create a file that is not listed at all (see the
# .env.production.local shadow probe, and `extra=` below).
FILES = [".env.production", "Dockerfile", WF, CI, os.path.join("src", "lib", "api.ts"),
         os.path.join("deploy", "utho", "generate-env.sh")]


def tree(name):
    d = os.path.join(WORK, name)
    if os.path.exists(d):
        shutil.rmtree(d)
    for f in FILES:
        dst = os.path.join(d, f)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(os.path.join(REPO, f), dst)
    return d


def edit(d, rel, fn):
    p = os.path.join(d, rel)
    s = open(p, encoding="utf-8").read()
    out = fn(s)
    assert out != s, f"probe made NO change to {rel} — the break was never applied"
    open(p, "w", encoding="utf-8", newline="").write(out)


def run(d):
    bash = shutil.which("bash") or r"C:\Program Files\Git\usr\bin\bash.exe"
    r = subprocess.run([bash, GATE, d], capture_output=True, text=True)
    return r.returncode


def sub1(pat, repl):
    def f(s):
        new, n = re.subn(pat, repl, s, count=1, flags=re.M)
        assert n == 1, f"pattern matched {n} times, expected 1: {pat!r}"
        return new
    return f


PROBES = []


def probe(name, want, rel=None, fn=None):
    PROBES.append((name, want, rel, fn))


probe("BASELINE (unmodified current tree)", 0)

# --- workflow value / shape breaks ------------------------------------------------------------
probe("H  money-IN committed fallback flipped to 'false'", 1, WF,
      sub1(r"(VITE_PAYMENTS_IN_ENABLED=.*vars\.PAYMENTS_IN_ENABLED \|\| ')true(')", r"\1false\2"))
probe("C  money-IN as a bare literal (no expression)", 1, WF,
      sub1(r"^( +VITE_PAYMENTS_IN_ENABLED=)\$\{\{.*\}\}$", r"\1false"))
probe("D  payouts bare literal 'true' vs REPLACE_ME", 1, WF,
      sub1(r"^( +VITE_PAYOUTS_ENABLED=)\$\{\{.*\}\}$", r"\1true"))
probe("I  payouts committed fallback flipped to 'true'", 1, WF,
      sub1(r"(VITE_PAYOUTS_ENABLED=.*vars\.PAYOUTS_ENABLED \|\| ')false(')", r"\1true"))
probe("B2 bare ${{ inputs.x }} — renders empty on push", 1, WF,
      sub1(r"^( +VITE_PAYMENTS_IN_ENABLED=).*$", r"\1${{ inputs.vite_payments_in_enabled }}"))
# Rewritten for the sentinel-comparison shape. The original targeted `format('{0}', …)`, which no
# longer exists, so it silently became a no-op probe — an obsolete probe reports PROBE FAILED here
# rather than passing, which is the point of asserting the edit landed.
probe("B6 toJSON() quoting hole reintroduced", 1, WF,
      sub1(r"\(inputs\.vite_payments_in_enabled != 'default' && inputs\.vite_payments_in_enabled\)",
           "toJSON(inputs.vite_payments_in_enabled)"))
# Anchored on the build-arg line specifically. Written unanchored first, it matched the
# verify-money-flags step higher in the file, left the build-arg untouched, and reported a hole
# that was not one — the same comment-only miss that produced a phantom finding in review. A probe
# must change the line whose behaviour it claims to test, not merely change something.
probe("J  vars. layer removed from the build-arg (literal-only fallback)", 1, WF,
      sub1(r"^( +VITE_PAYMENTS_IN_ENABLED=.*?)\|\| vars\.PAYMENTS_IN_ENABLED \|\|", r"\1||"))
probe("J2 verify-step expression drifts from the build-arg", 1, WF,
      sub1(r"^( +IN: .*?)\|\| vars\.PAYMENTS_IN_ENABLED \|\|", r"\1||"))
probe("B3 both build-args deleted from the web job", 1, WF,
      lambda s: re.sub(r"^ +VITE_PAY(?:MENTS_IN|OUTS)_ENABLED=.*\n", "", s, flags=re.M))


def decoy(s):
    blk = ("        with:\n"
           "          build-args: |\n"
           "            VITE_API_MODE=live\n"
           "            VITE_PAYMENTS_IN_ENABLED=${{ vars.PAYMENTS_IN_ENABLED || 'true' }}\n"
           "            VITE_PAYOUTS_ENABLED=${{ vars.PAYOUTS_ENABLED || 'false' }}\n")
    out = s.replace("  ai:\n", blk + "  ai:\n", 1)
    # and make the REAL web job unsafe, so a gate reading the wrong block certifies a bad tree
    return re.sub(r"(VITE_PAYOUTS_ENABLED=.*vars\.PAYOUTS_ENABLED \|\| ')false(')",
                  r"\1true\2", out, count=1)


probe("G  decoy build-args block on the api job", 1, WF, decoy)

# --- Dockerfile ordering breaks (the two the shell-quoted probes never actually applied) ------
ARG_RE = r"ARG VITE_API_MODE=live(?:.|\n)*?ARG VITE_PAYOUTS_ENABLED=false\n"
ENV_RE = r"ENV VITE_API_MODE(?:.|\n)*?VITE_PAYOUTS_ENABLED=\$VITE_PAYOUTS_ENABLED\n"


def moved_below_build(s):
    m = re.search(ARG_RE + ENV_RE, s)
    assert m, "could not locate the ARG+ENV block"
    b = m.group(0)
    return s.replace(b, "").replace("RUN npx vite build", "RUN npx vite build\n" + b, 1)


def env_before_arg(s):
    a = re.search(ARG_RE, s).group(0)
    e = re.search(ENV_RE, s).group(0)
    return s.replace(a + e, e + a, 1)


def env_to_runtime(s):
    e = re.search(ENV_RE, s).group(0)
    return s.replace(e, "").replace("FROM nginx:1.27-alpine AS runtime",
                                    "FROM nginx:1.27-alpine AS runtime\n" + e, 1)


probe("E  ARG/ENV moved BELOW 'RUN npx vite build'", 1, "Dockerfile", moved_below_build)
probe("F  ENV placed BEFORE its ARG", 1, "Dockerfile", env_before_arg)
probe("B1 ENV moved into the RUNTIME stage", 1, "Dockerfile", env_to_runtime)
probe("B7 .env.production line deleted", 1, ".env.production",
      sub1(r"^VITE_PAYMENTS_IN_ENABLED=true$", ""))
probe("B5 api.ts read deleted, name kept in a comment", 2, os.path.join("src", "lib", "api.ts"),
      sub1(r"import\.meta\.env\?\.VITE_PAYMENTS_IN_ENABLED === 'true'",
           "false /* VITE_PAYMENTS_IN_ENABLED */"))

# ----------------------------------------------------------------------------------------------
# Round-3 additions. priya's standing criticism of this file was that every probe above is a 1:1
# inverse of a leg the gate already had — "the gate reproduces its own changelog". These are
# written from the THREAT instead: what would have to be true for money to move wrongly, without
# caring which leg (if any) notices. Several had no corresponding leg when they were written.
# ----------------------------------------------------------------------------------------------

# Crossed wiring. One token, and the documented incident procedure ("set PAYMENTS_IN_ENABLED to
# hold collection off, then set it back") then turns creator withdrawals ON. Note this edits the
# build-args LINE: priya's own version of this probe matched the prose comment first and silently
# tested nothing, which is why her harness reported a hole that did not exist.
probe("X  payouts driven by the PAYMENTS repository variable", 1, WF,
      sub1(r"(VITE_PAYOUTS_ENABLED=.*)vars\.PAYOUTS_ENABLED", r"\1vars.PAYMENTS_IN_ENABLED"))
probe("X  payouts driven by the PAYMENTS dispatch input", 1, WF,
      sub1(r"(VITE_PAYOUTS_ENABLED=.*)inputs\.vite_payouts_enabled != 'default' && inputs\.vite_payouts_enabled",
           r"\1inputs.vite_payments_in_enabled != 'default' && inputs.vite_payments_in_enabled"))

# Duplicate declarations. Docker takes the LAST ENV and an ENV shadows a same-named ARG, so a
# second line quietly wins while a head -1 gate reads the first, well-formed one.
probe("X  second ENV VITE_PAYOUTS_ENABLED=true later in the build stage", 1, "Dockerfile",
      sub1(r"^(RUN npx vite build)$", "ENV VITE_PAYOUTS_ENABLED=true\n\\1"))
probe("X  duplicate VITE_PAYOUTS_ENABLED in the same build-args block", 1, WF,
      sub1(r"^( +)(VITE_PAYOUTS_ENABLED=.*)$", r"\1\2\n\1VITE_PAYOUTS_ENABLED=true"))

# ARG defaults are the shipped value for a hand-run `docker build` that passes only the URL.
probe("X  Dockerfile ARG payouts default flipped to true", 1, "Dockerfile",
      sub1(r"^ARG VITE_PAYOUTS_ENABLED=false$", "ARG VITE_PAYOUTS_ENABLED=true"))
probe("X  Dockerfile ARG money-IN default flipped to false", 1, "Dockerfile",
      sub1(r"^ARG VITE_PAYMENTS_IN_ENABLED=true$", "ARG VITE_PAYMENTS_IN_ENABLED=false"))

# .env.production VALUES, not just presence — this file is the only input for local and CI builds.
probe("X  .env.production money-IN set to false", 1, ".env.production",
      sub1(r"^VITE_PAYMENTS_IN_ENABLED=true$", "VITE_PAYMENTS_IN_ENABLED=false"))
probe("X  .env.production payouts set to true", 1, ".env.production",
      sub1(r"^VITE_PAYOUTS_ENABLED=false$", "VITE_PAYOUTS_ENABLED=true"))

# The dispatch lever itself. A declared flag whose per-publish override is dead is still F-0390.
probe("X  dispatch inputs deleted (per-publish override gone)", 1, WF,
      lambda s: re.sub(r"^      vite_pay(?:ments_in|outs)_enabled:\n(?:^        .*\n)+", "",
                       s, flags=re.M))

# A job that never runs cannot ship anything, however well its build-args are declared.
probe("X  web job disabled with if: false", 1, WF,
      sub1(r"^    if: \$\{\{ github\.event_name == 'push' \|\| inputs\.publish_web \}\}$",
           "    if: false"))
probe("X  web job no longer runs on push", 1, WF,
      sub1(r"^    if: \$\{\{ github\.event_name == 'push' \|\| inputs\.publish_web \}\}$",
           "    if: ${{ inputs.publish_web }}"))


# Structural blind spot priya named: FILES fixes the set of files a probe may touch, so a break
# introduced through a file not in that list cannot be expressed at all. Vite loads
# `.env.production.local` AFTER `.env.production`, so it silently overrides it for any local or CI
# production build. This probe writes a SIXTH file rather than editing one of the five.
def _shadow(d):
    p = os.path.join(d, ".env.production.local")
    open(p, "w", encoding="utf-8").write("VITE_PAYMENTS_IN_ENABLED=false\n")


PROBES.append(("X  .env.production.local shadows the money-IN value", 1, None, None))
_EXTRA_SETUP = {len(PROBES) - 1: _shadow}


def _extra(name, want, fn):
    """A probe that mutates the tree directly, for breaks no single-file edit can express."""
    PROBES.append((name, want, None, None))
    _EXTRA_SETUP[len(PROBES) - 1] = fn


# ----------------------------------------------------------------------------------------------
# Round-4 additions. Every one of these is a tree priya greened against v4, plus the leg that had
# never executed. They are grouped by MECHANISM — "the thing that ships can be disarmed without
# touching any declaration" — rather than by which leg catches them.
# ----------------------------------------------------------------------------------------------

def _rw(d, rel, fn):
    p = os.path.join(d, rel)
    s = open(p, encoding="utf-8").read()
    out = fn(s)
    assert out != s, f"probe made NO change to {rel}"
    open(p, "w", encoding="utf-8", newline="").write(out)


# The consumer: all build wiring intact, and the predicate ignores it.
_extra("R4 isMoneyActionBlocked hard-coded to return true", 1,
       lambda d: _rw(d, os.path.join("src", "lib", "api.ts"),
                     lambda s: re.sub(
                         r"(export function isMoneyActionBlocked\(operation: MoneyOperation\): boolean \{)"
                         r"(?:.|\n)*?\n\}",
                         r"\1\n  return true;\n}", s, count=1)))

# The verifying step: present by name, gutted in body; or removed with a decoy env pair elsewhere.
_extra("R4 verify-money-flags body replaced with a no-op", 1,
       lambda d: _rw(d, WF, lambda s: re.sub(
           r"(- name: verify money flags\n(?:.*\n)*?[ ]+run: \|\n)(?:[ ]{10}.*\n)+",
           r"\1          echo skipping\n", s, count=1)))
_extra("R4 payouts-vs-RAZORPAYX check deleted from the verify step", 1,
       lambda d: _rw(d, WF, lambda s: re.sub(
           r"[ ]+if \[ \"\$OUT\" = \"true\" \].*\n(?:.*\n)*?[ ]+fi\n", "", s, count=1)))

# The job/trigger: everything declared, nothing ever runs.
_extra("R4 web job condition always-false without the literal 'false'", 1,
       lambda d: _rw(d, WF, lambda s: s.replace(
           "if: ${{ github.event_name == 'push' || inputs.publish_web }}",
           "if: ${{ github.event_name == 'push' && github.ref == 'refs/heads/no-such-branch' }}", 1)))
_extra("R4 on.push trigger deleted", 1,
       lambda d: _rw(d, WF, lambda s: re.sub(
           r"\n  push:\n(?:    .*\n|      .*\n|      #.*\n)+?(?=\nenv:)", "\n", s, count=1)))
_extra("R4 push paths: drops src/** and Dockerfile", 1,
       lambda d: _rw(d, WF, lambda s: s.replace("      - 'src/**'\n", "")
                                        .replace("      - 'Dockerfile'\n", "")))

# The CI wiring: the gate is invoked by a file the gate itself never reads.
_extra("R4 gate step removed from frontend-checks.yml", 1,
       lambda d: _rw(d, CI, lambda s: re.sub(
           r"[ ]+- name: Money-flag build wiring \(F-0390\).*\n(?:[ ]{8,}.*\n)+", "", s, count=1)))

# Leg 6 (payouts-on vs unprovisioned RazorpayX) is DOMINATED by leg 5's pin: every payouts-true
# probe trips the pin first, so leg 6 has never once decided an exit code — "28/28" said nothing
# about it. This probe relaxes the pin AND flips payouts, so leg 6 is the only thing left to fire.
def _leg6(d):
    _rw(d, WF, lambda s: s.replace("vars.PAYOUTS_ENABLED || 'false' }}",
                                   "vars.PAYOUTS_ENABLED || 'true' }}", 1))
    _rw(d, ".env.production", lambda s: s.replace("VITE_PAYOUTS_ENABLED=false",
                                                  "VITE_PAYOUTS_ENABLED=true", 1))
    _rw(d, "Dockerfile", lambda s: s.replace("ARG VITE_PAYOUTS_ENABLED=false",
                                             "ARG VITE_PAYOUTS_ENABLED=true", 1))


_extra("R4 payouts on everywhere vs REPLACE_ME (reaches leg 6)", 1, _leg6)

def _one(idx):
    """Build one tree, apply its break, run the gate. Returns (idx, label, detail)."""
    i, (name, want, rel, fn) = idx, PROBES[idx]
    d = tree(f"t{i}")
    if i in _EXTRA_SETUP:
        try:
            _EXTRA_SETUP[i](d)
        except AssertionError as e:
            return i, name, ("PROBE", f"PROBE ITSELF FAILED — {e}")
    if fn:
        try:
            edit(d, rel, fn)
        except AssertionError as e:
            return i, name, ("PROBE", f"PROBE ITSELF FAILED — {e}")
    got = run(d)
    return i, name, ("ok " if got == want else "FAIL", f"expect {want} -> {got}")


if __name__ == "__main__":
    os.makedirs(WORK, exist_ok=True)
    # Each probe owns its own tree and touches nothing shared, so they parallelise cleanly. Serial,
    # this took over two minutes — and it runs on every PR that touches the build wiring, so the
    # cost was a standing argument for deleting it, which is how gates die. Ordered output is
    # preserved so a diff between runs stays readable.
    workers = min(8, (os.cpu_count() or 4))
    with cf.ThreadPoolExecutor(max_workers=workers) as pool:
        results = sorted(pool.map(_one, range(len(PROBES))), key=lambda r: r[0])

    bad = 0
    for _, name, (label, detail) in results:
        if label != "ok ":
            bad += 1
        if label == "PROBE":
            print(f"  !! {name}: {detail}")
        else:
            print(f"  [{label}] {name:<52} {detail}")
    print(f"\n{len(PROBES) - bad}/{len(PROBES)} probes behaved as specified")
    sys.exit(1 if bad else 0)
