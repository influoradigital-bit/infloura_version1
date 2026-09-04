#!/usr/bin/env python3
"""gates/class_regression_tests.py — origin: promoting F-0521/F-0523/F-0530/F-0580/F-0619/F-0535.

Extended to promote F-0623/F-0630/F-0628 (creator unsigned-contracts query): all three are pinned
by ContractRepositoryUnsignedByCreatorTest, a REAL @DataJpaTest + H2 test, not a Mockito one — its
own SQL log shows the actual JPQL executing against real tables. F-0628 in particular was a wrong
claim ("no repository test in this codebase runs real SQL") corrected once this test was written;
promoting it here means what it originally should have named: findUnsignedByCreatorId specifically
now has real execution coverage, which is the concrete symptom the finding was about.

Each of those was closed by ONE targeted JUnit class, run with `mvn -Dtest=<Class> test`, never
the full `mvn test`. That was deliberate all day: this repo has a concurrent session mid-refactor,
and a full-suite run keeps failing to test-compile on files nobody touched for these fixes
(EmailWorkerTest, CreatorAgentPreferencesServiceTest, MeeraContextService — none of them related
to any of the five classes below). A gate that requires the WHOLE suite to be green would be
undeployable for weeks through no fault of the fixes it is meant to guard, and would give a false
red on every promotion attempt for a reason that has nothing to do with the defect.

So this gate runs ONLY the target class(es) per ledger class — narrow, fast, and immune to an
unrelated file elsewhere in the tree being mid-edit. It compiles src/main ALONE first (no test
sources) specifically to distinguish "my fix doesn't compile" (a real finding) from "someone
else's unrelated test source is broken right now" (unavailable, not this gate's business).

LAW (false-red): main-only compile failure or missing test class => exit 2, never 1 — a defect in
                  code this gate is not about must not read as a failure of code it IS about.
LAW (rule 5):     declares its blind spot on every exit path.
LAW (no truncation): prints every class's real result, never a head -N slice.

Usage: gates/class_regression_tests.py [class1 [class2 ...]]   (default: all five below)
Exit:  0 every requested class's tests pass . 1 a real test failure . 2 unavailable . 64 usage
"""
import os
import subprocess
import sys

API_DIR = "influora-api"

# ledger class -> the JUnit class(es) that pin it. idempotency-key-double-count covers TWO
# findings (F-0521 WooCommerce, F-0619 Shopify) because it is the same defect on two store
# integrations — one gate, two subjects, exactly what "one gate per class" means.
CLASSES = {
    "idempotency-key-double-count": [
        "com.influora.web.WooCommerceWebhookIdempotencyTest",
        "com.influora.web.ShopifyWebhookIdempotencyTest",
    ],
    "wrong-dedup-key": ["com.influora.service.tracking.ConversionTrackingDedupTest"],
    "missing-authz-gate": ["com.influora.service.CampaignAuthzTest"],
    "missing-optimistic-lock": ["com.influora.service.BrandDeliverableConcurrentApprovalTest"],
    "missing-create-path": ["com.influora.service.SupportServiceTest"],
    # F-0623, F-0630 and F-0628 all pinned by the same real @DataJpaTest — see file docstring.
    "false-copy-not-backed-by-query": ["com.influora.repository.ContractRepositoryUnsignedByCreatorTest"],
    "orphaned-row-on-parent-cancel": ["com.influora.repository.ContractRepositoryUnsignedByCreatorTest"],
    "untested-jpql-query": ["com.influora.repository.ContractRepositoryUnsignedByCreatorTest"],
}

BLIND = [
    "whether these tests would still pass against a version of the tree different from the one "
    "just compiled — this gate proves the CURRENT tree, not history",
    "runtime/e2e behaviour — every assertion here is JUnit/Mockito, none exercises a real HTTP "
    "call, a real database, or a real webhook delivery",
    "any ledger class not named on the command line or in the default set above",
    "whether the target test itself is well-designed — only that it exists, compiles, and passes",
]


def emit(extra=()):
    print("NOT CHECKED: " + " | ".join(list(extra) + BLIND))


def die(code, msg, extra=()):
    print(msg)
    emit(extra)
    sys.exit(code)


argv = sys.argv[1:]
if any(a.startswith("--") for a in argv):
    die(64, "usage: gates/class_regression_tests.py [ledger-class ...]",
        ["everything: the gate never ran"])

requested = argv or list(CLASSES.keys())
unknown = [c for c in requested if c not in CLASSES]
if unknown:
    die(64, "· unknown class(es): %s — known: %s" % (", ".join(unknown), ", ".join(CLASSES)),
        ["everything: the gate never ran"])

if not os.path.isdir(API_DIR):
    die(2, "· %s is not a directory — the Java oracle has no subject" % API_DIR,
        ["everything: there was no module to build"])
if not os.path.isfile(os.path.join(API_DIR, "pom.xml")):
    die(2, "· no pom.xml under %s — unavailable" % API_DIR,
        ["everything: there was no module to build"])

MVN = None
for cand in ("mvn", "mvn.cmd"):
    try:
        subprocess.run([cand, "-v"], capture_output=True, timeout=15)
        MVN = cand
        break
    except Exception:
        continue
if MVN is None:
    die(2, "· mvn not found on PATH — unavailable", ["everything: mvn could not be invoked"])


def run(args, timeout=600):
    try:
        return subprocess.run(
            [MVN, "-o", "-q"] + args, cwd=API_DIR, capture_output=True, text=True,
            errors="replace", timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return None


# ---- step 1: does src/main compile ALONE, with no test source in the picture? ----------------
main_compile = run(["-DskipTests", "-Dmaven.test.skip=true", "compile"])
if main_compile is None:
    die(2, "· mvn compile timed out — unavailable, not a finding",
        ["everything: the compile step never finished"])
if main_compile.returncode != 0:
    print("* class_regression_tests: src/main does not compile")
    print(main_compile.stdout[-4000:])
    print(main_compile.stderr[-2000:])
    print("VERDICT: broken — this is a real finding about production code")
    emit()
    sys.exit(1)

print("* class_regression_tests: src/main compiles cleanly")

# ---- step 2: run ONLY the requested target classes, one mvn invocation per class -------------
results = []
for ledger_class in requested:
    for junit_class in CLASSES[ledger_class]:
        r = run(["-Dtest=" + junit_class, "test"])
        if r is None:
            results.append((ledger_class, junit_class, None, "timed out"))
            continue
        out = r.stdout + r.stderr
        if "Tests run:" not in out:
            # The test class itself failed to compile/load — distinguish "not found" (unavailable
            # for THIS gate, likely a typo or moved class) from a genuine assertion failure.
            if "No tests were executed" in out or "does not exist" in out or "cannot find symbol" in out:
                results.append((ledger_class, junit_class, "unavailable", out[-1500:]))
            else:
                results.append((ledger_class, junit_class, r.returncode, out[-1500:]))
            continue
        candidates = [ln for ln in out.splitlines() if "Tests run:" in ln]
        line = candidates[-1][candidates[-1].index("Tests run:"):] if candidates else "(no summary line)"
        results.append((ledger_class, junit_class, r.returncode, line))

print()
broken = [x for x in results if x[2] not in (0, "unavailable")]
missing = [x for x in results if x[2] == "unavailable"]
ok = [x for x in results if x[2] == 0]

for ledger_class, junit_class, rc, detail in results:
    tag = "PASS" if rc == 0 else ("MISSING" if rc == "unavailable" else "FAIL")
    print("  %-8s %-32s %-56s %s" % (tag, ledger_class, junit_class, detail if rc != 0 else detail))

if missing:
    print("\n* %d target class(es) could not be found/compiled — unavailable for those, not a pass"
          % len(missing))
if broken:
    print("\nVERDICT: broken — %d target class(es) show a real test failure" % len(broken))
    emit()
    sys.exit(1)
if missing and not ok:
    die(2, "· every requested target class was unavailable — nothing was proved",
        ["everything requested: no target class could be run"])
if missing:
    print("\nVERDICT: broken — %d class(es) passed but %d target class(es) are missing; a class "
          "with a missing subject is not proved" % (len(ok), len(missing)))
    emit()
    sys.exit(1)

print("VERDICT: aligned (proved) — every requested class's target test(s) pass on the current tree")
emit()
sys.exit(0)
