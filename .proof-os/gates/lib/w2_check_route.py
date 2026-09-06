#!/usr/bin/env python3
"""
Helper for .proof-os/gates/W2-prerender-artifact-integrity.sh.

Checks ONE prerendered route file against the W2 assertions:
  1. TEXT signal   - the ErrorBoundary's fallback heading text (DERIVED from
                      src/components/ErrorBoundary.tsx at run time, never
                      hardcoded here) does not appear anywhere in the snapshot
  2. H1 signal     - a non-empty <h1> exists whose de-tagged text is not that
                      derived heading text
  3. STRUCTURAL    - the ErrorBoundary's fallback also renders a recognisable
     signal           pair of controls ("Try again" / "Reload page"). Their
                      joint presence is checked independently of signals 1-2,
                      so a copy edit to the heading ALONE cannot blind the
                      gate to a crashed route — the buttons still give it away.
  4. LD+JSON floor - the route's <script type="application/ld+json"> block
                      count must be >= this route's baseline (see
                      w2_ldjson_baseline.json next to this file). A decrease
                      from baseline is a regression (FAIL); an increase is
                      fine (PASS) — schema being added is not a defect.

Any ONE of checks 1-3 failing is sufficient to fail the route (independent
signals, not required to agree) — see w2_ldjson_baseline.json's header
comment for why floors instead of exact counts, and why check 3 exists
alongside check 1/2.

WHY THE ERROR SIGNATURE IS DERIVED, NOT HARDCODED
--------------------------------------------------
An earlier version of this gate hardcoded ERROR_STRING = "Something went
wrong". Kavya's review (wiki/errors/W2-prerender-artifact-integrity-review.md)
demonstrated that rewording ErrorBoundary.tsx's fallback heading — a UX copy
change, not an API — silently defanged both the text check and the h1 check,
while the gate kept reporting PASS on a crashed route. Reading the actual
heading out of ErrorBoundary.tsx at gate-run-time converts a future rename
from a silent pass into a loud, unmissable GATE UNAVAILABLE (exit 2): the
gate cannot know what to check for, so it refuses to guess and refuses to
skip. That is the whole point of this rework — see requirement 1 in the
rejection review.

This is intentionally a real .py file rather than a bash heredoc: this
project has previously lost a level of backslash escaping to a heredoc
(see reference_bash_heredoc_eats_backslashes.md) and turned a regex gate
silently inert. A real file is inspectable and does not pass through a
shell string first.

Usage:
    w2_check_route.py <path-to-index.html> <route-label-no-leading-slash>

<route-label-no-leading-slash> is the route WITHOUT its leading "/" (e.g.
"about", "blog/some-post", or "" for the homepage). The leading slash is
added back here rather than passed in, because MSYS bash on this Windows box
silently rewrites a bare argument that looks like an absolute POSIX path
(e.g. "/about") into a Windows path when it crosses into a native,
non-MSYS executable like this one; stripping it on the bash side sidesteps
that instead of fighting it. The same route label doubles as the baseline
lookup key (with the leading "/" restored) into w2_ldjson_baseline.json.

Environment variables (falsification harness only — real invocations set
none of these):
    W2_SELFTEST            - must be exactly "1" for either override below to
                             take effect. Without it, a set override is a
                             GATE UNAVAILABLE (exit 2), not a silently
                             honoured path swap. A stray or malicious env var
                             in a real run must stop this gate loudly, never
                             weaken it silently.
    W2_ERRORBOUNDARY_PATH  - overrides where the ErrorBoundary source is read
                             from (default: src/components/ErrorBoundary.tsx,
                             resolved relative to the current working
                             directory, which the calling gate script has
                             already cd'd to the project root). Lets the
                             fixture harness prove the exit-2 paths (missing
                             file / heading not found) WITHOUT ever touching
                             the real ErrorBoundary.tsx, which is out of
                             scope for this gate to modify. Requires
                             W2_SELFTEST=1 (see above).
    W2_BASELINE_PATH       - overrides the baseline JSON path (default:
                             w2_ldjson_baseline.json next to this script).
                             Requires W2_SELFTEST=1 (see above).

The baseline path actually loaded (default or override) is always printed
to stdout, so a self-test or a poisoned-override run is visible in CI
output rather than indistinguishable from an ordinary run.

Exit codes:
    0 - route passes all checks. NEVER returned while W2_SELFTEST=1 is set
        (see below) - a self-test run cannot certify anything, so an
        outcome that would otherwise be 0 is reported as 2 instead.
    1 - route fails at least one check (reasons printed to stdout). This is
        UNCHANGED by W2_SELFTEST - a self-test run that finds a real
        violation must still report it, which is what the falsification
        suite depends on.
    2 - could not run at all (bad args, unreadable file, ErrorBoundary
        source unreadable or its fallback heading/buttons not locatable,
        baseline data missing/malformed/holding a non-integer or <1 entry,
        this route absent from the baseline table, an override path set
        without W2_SELFTEST=1, OR every check passed while W2_SELFTEST=1
        was set - see above) - NEVER a silent pass.
"""
import html
import json
import os
import re
import sys
from pathlib import Path

LD_JSON_OPEN_TAG = re.compile(
    r'<script[^>]+type=["\']application/ld\+json["\']', re.IGNORECASE
)
H1_RE = re.compile(r"<h1[^>]*>(.*?)</h1>", re.DOTALL | re.IGNORECASE)
TAG_RE = re.compile(r"<[^>]+>")
COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)

# Structural signal (check 3): the ErrorBoundary fallback's two controls.
# Deliberately literal, not derived — per Kavya's review "Option A", these
# button labels serve no other purpose on any real marketing page and are
# far less likely to be reworded in a copy pass than the heading is. They
# are still validated against the live ErrorBoundary.tsx source below
# (load_error_signature) so that if BOTH labels ever also change, the gate
# fails loudly (GATE UNAVAILABLE) instead of silently checking for stale
# text nobody renders anymore.
RETRY_BUTTON_TEXT = "Try again"
RELOAD_BUTTON_TEXT = "Reload page"

DEFAULT_ERRORBOUNDARY_PATH = "src/components/ErrorBoundary.tsx"
DEFAULT_BASELINE_PATH = Path(__file__).resolve().parent / "w2_ldjson_baseline.json"


class GateUnavailable(Exception):
    """Raised for any condition that must produce exit 2, never exit 0/1."""


def resolve_override(env_var: str, default: str) -> str:
    """Resolve a path that MAY be overridden by env_var, but only when the
    W2_SELFTEST=1 flag is also set.

    A prior falsification round showed that W2_BASELINE_PATH alone (no flag
    required) let a poisoned or all-zeros baseline silently disable the
    ld+json check — the gate obeyed the override without a trace. The fix is
    not "validate the override's contents harder" (that was tried and is
    insufficient on its own — a baseline whose values all look sane can still
    hide a real regression); it is that the override must never take effect
    quietly. A stray or malicious env var in a real invocation must stop this
    gate loudly (exit 2), never weaken it in silence.
    """
    val = os.environ.get(env_var)
    if val is None:
        return default
    if os.environ.get("W2_SELFTEST") != "1":
        raise GateUnavailable(
            f"{env_var} is set (to {val!r}) but W2_SELFTEST=1 is not. "
            f"Overriding this gate's data/source paths only takes effect "
            f"under the falsification harness, which must also set "
            f"W2_SELFTEST=1 explicitly. Unset {env_var} for a real run."
        )
    return val


def load_error_signature() -> str:
    """Read ErrorBoundary.tsx and derive its fallback heading text.

    Also cross-checks that the structural button labels (RETRY_BUTTON_TEXT /
    RELOAD_BUTTON_TEXT) still appear in the same source file, so check 3
    stays honest about what it is actually detecting. Raises
    GateUnavailable — never returns a guess — if the file is missing or the
    heading cannot be located, per requirement 1: a future rename must be a
    loud failure, not a silent skip.
    """
    path = resolve_override("W2_ERRORBOUNDARY_PATH", DEFAULT_ERRORBOUNDARY_PATH)

    try:
        with open(path, "r", encoding="utf-8", errors="strict") as fh:
            source = fh.read()
    except OSError as exc:
        raise GateUnavailable(f"cannot read ErrorBoundary source at {path!r}: {exc}")

    match = H1_RE.search(source)
    if match is None:
        raise GateUnavailable(
            f"no <h1>...</h1> found in {path!r} - cannot derive the fallback "
            "heading text this gate must check for. This file is expected to "
            "render the crash fallback's heading; if it moved or changed "
            "shape, this gate needs to be re-pointed, not silently skipped."
        )

    heading = html.unescape(TAG_RE.sub("", match.group(1))).strip()
    if not heading:
        raise GateUnavailable(
            f"<h1> found in {path!r} but its de-tagged text is empty - cannot "
            "derive a non-empty fallback heading to check for."
        )

    missing_buttons = [
        label
        for label in (RETRY_BUTTON_TEXT, RELOAD_BUTTON_TEXT)
        if label not in source
    ]
    if missing_buttons:
        raise GateUnavailable(
            f"{path!r} no longer contains the expected fallback control "
            f"label(s) {missing_buttons!r} - the structural check (signal 3) "
            "would otherwise be testing for stale text nobody renders. "
            "Update RETRY_BUTTON_TEXT/RELOAD_BUTTON_TEXT in this script to "
            "match the current ErrorBoundary fallback, citing the new labels."
        )

    return heading


def load_baseline() -> dict:
    """Load the per-route ld+json floor table. Raises GateUnavailable if the
    file is missing, unreadable, malformed, or holds any non-route entry
    whose value is not an integer >= 1 - a baseline-driven check with no
    trustworthy baseline data is not a check, and must not silently pass.

    There is deliberately NO fallback/default entry here (no DEFAULT_MIN).
    A route that is not a key in this table is unprotected until someone
    derives its real floor and adds it explicitly - see main()'s lookup.
    """
    path = resolve_override("W2_BASELINE_PATH", str(DEFAULT_BASELINE_PATH))
    print(f"[w2] ld+json baseline loaded from: {path}")

    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except OSError as exc:
        raise GateUnavailable(f"cannot read ld+json baseline data at {path!r}: {exc}")
    except json.JSONDecodeError as exc:
        raise GateUnavailable(f"ld+json baseline data at {path!r} is not valid JSON: {exc}")

    if not isinstance(data, dict):
        raise GateUnavailable(
            f"ld+json baseline data at {path!r} must be a JSON object, got {type(data).__name__}"
        )

    # Value validation (Kavya's proposal): every route entry must be a
    # positive integer. Correct but NOT sufficient on its own — see
    # resolve_override()'s docstring for why the override is also gated on
    # W2_SELFTEST — a table full of innocent-looking 1s can still hide a
    # real schema regression, which is what the W2_SELFTEST gate and the
    # "no default fallback" rule in main() are there to contain.
    for key, value in data.items():
        if key == "_comment":
            continue
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            raise GateUnavailable(
                f"ld+json baseline entry {key!r} at {path!r} must be an "
                f"integer >= 1, got {value!r}"
            )

    return data


def strip_html_comments(text: str) -> str:
    """Remove <!-- ... --> blocks before any check runs.

    Caught by this gate's own fixture authoring: a fixture's explanatory
    HTML comment describing "the hero's <h1> stays empty" contains a bare
    `<h1>` with no matching `</h1>` on the same line. Because H1_RE is
    DOTALL + non-greedy, it happily matched from that comment's `<h1>`
    all the way to the REAL closing `</h1>` far below, captured everything
    between as "h1 text" (comment prose + markup), and false-passed a route
    whose actual rendered `<h1></h1>` is empty. A crawler never sees a
    comment's text either, so stripping comments first is not just a
    workaround for this gate's own fixtures — it is what "what the
    snapshot contains" should have meant from the start. Applied before
    all checks (error text, h1, structural buttons, ld+json count) for the
    same reason.
    """
    return COMMENT_RE.sub("", text)


def main() -> int:
    if len(sys.argv) != 3:
        print("USAGE: w2_check_route.py <file> <route-label>")
        return 2

    path, route_arg = sys.argv[1], sys.argv[2]
    route_label = "/" + route_arg

    # --- checks 1-3 first and unconditionally, for every route, listed or --
    # not in the ld+json baseline table. None of these three depend on
    # baseline data (only check 4 does), so they must run and be diagnosed
    # BEFORE this route is ever looked up in that table. A route that is
    # not a baseline key is "floor not yet derived" (exit 2) - it is NOT
    # "assume nothing can be said about it until it is added", and a crash
    # is a crash whether or not the route is listed. Getting this backwards
    # (baseline lookup before the crash checks) was itself a defect: it
    # made a crashed-and-unlisted route exit 2 with a "go add a baseline
    # entry" message, sending the developer to fix the wrong thing and
    # only discover the real crash on the NEXT run, after wasting a cycle
    # on a baseline entry the crash made irrelevant.
    try:
        error_heading = load_error_signature()
    except GateUnavailable as exc:
        print(f"GATE UNAVAILABLE: {exc}")
        return 2

    try:
        with open(path, "r", encoding="utf-8", errors="strict") as fh:
            content = fh.read()
    except OSError as exc:
        print(f"UNREADABLE {path}: {exc}")
        return 2

    content = strip_html_comments(content)

    reasons = []

    # --- 1. TEXT signal: derived ErrorBoundary heading must not appear -----
    if error_heading in content:
        reasons.append(
            f"snapshot contains the ErrorBoundary fallback heading text "
            f"({error_heading!r}, derived from ErrorBoundary.tsx)"
        )

    # --- 2. H1 signal: non-empty <h1>, and its text is not the error heading
    h1_match = H1_RE.search(content)
    if h1_match is None:
        reasons.append("no <h1>...</h1> element found in the snapshot")
    else:
        inner = TAG_RE.sub("", h1_match.group(1))
        inner = html.unescape(inner).strip()
        if not inner:
            reasons.append("<h1> element is present but empty")
        elif error_heading in inner:
            reasons.append(f"<h1> text is the ErrorBoundary fallback ({inner!r})")

    # --- 3. STRUCTURAL signal: independent of 1/2, catches a copy-only edit -
    if RETRY_BUTTON_TEXT in content and RELOAD_BUTTON_TEXT in content:
        reasons.append(
            f"snapshot contains both ErrorBoundary fallback controls "
            f"({RETRY_BUTTON_TEXT!r} and {RELOAD_BUTTON_TEXT!r}) - this is the "
            "crash fallback structurally, regardless of what heading text it "
            "carries"
        )

    if reasons:
        # A crash (or missing/empty <h1>) is diagnosed and reported here,
        # in full, without ever touching the baseline table - checks 1-3
        # alone are sufficient to fail a route, per this gate's header.
        print(f"FAIL {route_label} ({path}):")
        for r in reasons:
            print(f"        - {r}")
        return 1

    # --- 4. ld+json floor: only reached once checks 1-3 have ALL passed ----
    # The baseline table backs this check alone. Only now - route confirmed
    # not crashed - does a lookup miss mean anything, and it means exactly
    # what it always has: this route's floor has not been derived yet, so
    # the build must not ship it unreviewed (exit 2, not a pass).
    try:
        baseline = load_baseline()
        if route_label not in baseline:
            raise GateUnavailable(
                f"{route_label!r} has no entry in the ld+json baseline table "
                f"({DEFAULT_BASELINE_PATH}). There is no default fallback: "
                "this route's floor must be derived (grep '<JsonLd' on its "
                "page component, add 1 for the static head block — see "
                "w2_ldjson_baseline.json's header comment for the method) "
                "and added to that table explicitly before this route can "
                "be considered protected."
            )
        expected_min = baseline[route_label]
    except GateUnavailable as exc:
        print(f"GATE UNAVAILABLE: {exc}")
        return 2

    ld_count = len(LD_JSON_OPEN_TAG.findall(content))
    if ld_count < expected_min:
        print(f"FAIL {route_label} ({path}):")
        print(
            f"        - expected at least {expected_min} application/ld+json "
            f"block(s) (this route's baseline), found {ld_count}"
        )
        return 1

    # A self-test run must NEVER emit the production PASS signal. Kavya's
    # round-3 review: W2_SELFTEST=1 was required to unlock the two path
    # overrides (see resolve_override), which correctly blocked an
    # ACCIDENTAL stray env var from silently weakening a real run - but it
    # did nothing to stop an INTENTIONAL run (both env vars set on purpose,
    # e.g. a poisoned W2_BASELINE_PATH) from reaching this point and
    # reporting exit 0. A self-test is part of the falsification harness,
    # not a production gate invocation, and a poisoned baseline that
    # nonetheless satisfies every check must not be indistinguishable from
    # a real, trustworthy PASS. So: exit 1 (a check actually failed) still
    # means exactly what it always has - the falsification suite depends on
    # self-test runs being able to report a real violation - but the one
    # path that would otherwise exit 0 is converted to exit 2 whenever
    # W2_SELFTEST=1, with a message loud enough that nobody mistakes it for
    # a malfunction.
    if os.environ.get("W2_SELFTEST") == "1":
        print(
            "SELF-TEST MODE: refusing to report PASS. Overrides were active "
            "(or could have been) under W2_SELFTEST=1; this run cannot "
            "certify anything. A self-test run must never emit the "
            "production PASS signal - rerun with W2_SELFTEST unset and no "
            "W2_BASELINE_PATH/W2_ERRORBOUNDARY_PATH overrides to get a real "
            "PASS."
        )
        return 2

    print(f"PASS {route_label} ({path}) - ld+json={ld_count} (baseline {expected_min})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
