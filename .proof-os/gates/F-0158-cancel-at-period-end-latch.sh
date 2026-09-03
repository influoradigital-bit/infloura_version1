#!/usr/bin/env bash
# F-0158-cancel-at-period-end-latch.sh — gate for F-0158 (one-way-flag-latch-in-money-path).
#
# RECORD
#   influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java
#   #applySubscriptionWebhookUpdate. BL-2 made Subscription.cancelAtPeriodEnd load-bearing for
#   SubscriptionRenewalResetJob#doRun's routing (it partitions the stale-period ACTIVE batch on
#   isCancelAtPeriodEnd() alone: flag set -> finalizeLapsedCancellation, flag clear ->
#   applyRenewalSafetyNet). The flag was a one-way latch: only #cancel ever SET it and only the
#   brand-new-row builder ever cleared it, so a re-subscribed, currently-paying customer kept
#   cancelAtPeriodEnd=true forever and the next missed/delayed webhook wrongly finalized their
#   ACTIVE Pro row to CANCELLED while Razorpay kept charging them.
#
# THE SPEC IS THE RECORD'S "missed_by" FIELD, WHICH NAMES TWO MISSING CHECKS:
#   (a) "a re-subscribe -> missed-webhook regression test"
#   (b) "2 vacuous test assertions found by mutation testing (... missing period fields masking
#        the NO-SIDE-EFFECTS ASSERTION on the webhook cancel case)"
#
# So this gate does not grep for a fix — it asserts the TESTS CAN SEE the defect, by mutation.
# A text gate here would be worthless twice over: the fix's own 30-line comment quotes every
# forbidden string, and `setCancelAtPeriodEnd(false)` already appears in three unrelated builder
# sites in the same file. The only honest question is "would CI go red if this code regressed?",
# and the only way to answer it is to regress the code and watch.
#
# TWO MUTANTS, both inside applySubscriptionWebhookUpdate, both re-introducing a real money-path
# defect the record names. Each must be KILLED (turn the suite red):
#
#   M1  the latch itself — neutralise the `subscription.setCancelAtPeriodEnd(false);` clear in
#       the ACTIVE (re)activation branch. This IS F-0158's original defect. If the suite stays
#       green, clause (a) of missed_by is still missing.
#
#   M2  the no-side-effects contract on a status-only webhook — delete the
#       `if (periodStart != null && periodEnd != null)` guard so a cancelled/halted/past_due
#       delivery (which carries no period) calls Subscription#renewPeriod(null, null) and NULLS
#       currentPeriodStart/currentPeriodEnd on a live row. renewPeriod does no null-checking
#       (Subscription.java:190 assigns both straight through), and currentPeriodEnd is exactly
#       what SubscriptionRenewalResetJob#doRun dereferences to build its batch
#       (`sub.getCurrentPeriodEnd().isBefore(now)`) — the same routing F-0158 is about. If the
#       suite stays green, clause (b) of missed_by is still missing: the fixture was given period
#       fields but the assertion that they SURVIVE a status-only webhook was never written.
#
# The gate MUTATES SubscriptionService.java in place and restores it from a byte-for-byte backup
# in an EXIT/INT/TERM trap; it verifies the restore by sha and shouts (exit 2) if it ever fails.
#
#   exit 0 = proved (defect absent)  · 1 = broken (defect present) · 2 = unavailable
set -u

ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve repo root — unavailable"; echo "VERDICT: unavailable"; echo "NOT CHECKED: everything"; exit 2; }
cd "$ROOT" || { echo "· repo root unreadable — unavailable"; echo "VERDICT: unavailable"; echo "NOT CHECKED: everything"; exit 2; }

API=influora-api
SRC="$API/src/main/java/com/influora/service/billing/SubscriptionService.java"
# Every test class in the repo that exercises applySubscriptionWebhookUpdate /
# finalizeLapsedCancellation. Deliberately wider than SubscriptionServiceTest so a mutant killed
# by coverage living somewhere else still counts as killed — a gate must not go red because it
# looked in only one file.
TESTS="SubscriptionServiceTest,SubscriptionRenewalResetJobTest,RazorpayWebhookControllerTest,SubscriptionDunningJobTest"
MVN_TIMEOUT=600

KEEP_LOGS=0
bail2() { echo "$1"; [ "${KEEP_LOGS:-0}" = "1" ] && [ -f "${TMPD:-}/last.log" ] && { echo "--- last 25 lines of the maven run ---"; tail -25 "$TMPD/last.log"; echo "--- (full log kept at $TMPD/last.log) ---"; }; echo "VERDICT: unavailable — $2"; echo "NOT CHECKED: whether SubscriptionServiceTest can see a cancelAtPeriodEnd / billing-period regression (the mutation run never completed)"; exit 2; }

command -v mvn  >/dev/null 2>&1 || bail2 "· mvn not on PATH" "maven missing"
command -v java >/dev/null 2>&1 || bail2 "· java not on PATH" "jdk missing"
[ -f "$API/pom.xml" ] || bail2 "· $API/pom.xml missing" "backend module absent"
[ -f "$SRC" ] || bail2 "· $SRC missing" "target source absent"

if command -v sha256sum >/dev/null 2>&1; then SHA() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum   >/dev/null 2>&1; then SHA() { shasum -a 256 "$1" | cut -d' ' -f1; }
else SHA() { wc -c < "$1"; }; fi

TMPD=$(mktemp -d 2>/dev/null) || bail2 "· cannot create a temp dir" "no writable scratch space"
BACKUP="$TMPD/SubscriptionService.java.orig"
cp "$SRC" "$BACKUP" || bail2 "· cannot back up $SRC" "backup failed — refusing to mutate a file I cannot restore"
ORIG_SHA=$(SHA "$BACKUP")

restore() {
  cp "$BACKUP" "$SRC" 2>/dev/null
  if [ "$(SHA "$SRC")" != "$ORIG_SHA" ]; then
    echo "!! RESTORE FAILED — $SRC does not match its pre-gate bytes."
    echo "!! The pristine copy is at: $BACKUP   (copy it back by hand before committing anything)"
  elif [ "${KEEP_LOGS:-0}" != "1" ]; then
    rm -rf "$TMPD" 2>/dev/null
  fi
}
trap restore EXIT INT TERM

# ---------------------------------------------------------------------------------------------
# Anchor both mutation sites to real CODE lines INSIDE applySubscriptionWebhookUpdate. Anything
# ambiguous (0 or >1 match, or a match outside the method) means the file was restructured and
# this gate can no longer mutate it safely — that is "unavailable", never "broken".
# ---------------------------------------------------------------------------------------------
method_line=$(grep -n '^\s*public void applySubscriptionWebhookUpdate($' "$SRC" | head -1 | cut -d: -f1)
[ -n "${method_line:-}" ] || bail2 "· applySubscriptionWebhookUpdate(...) signature not found in $SRC" "method renamed or moved"

# A code line, not a comment: must not start with // or * and must end in the statement itself.
m1_hits=$(grep -n '^[[:space:]]*subscription\.setCancelAtPeriodEnd(false);[[:space:]]*$' "$SRC")
m2_hits=$(grep -n '^[[:space:]]*if (periodStart != null \&\& periodEnd != null) {[[:space:]]*$' "$SRC")
m1_n=$(printf '%s' "$m1_hits" | grep -c . )
m2_n=$(printf '%s' "$m2_hits" | grep -c . )
[ "$m1_n" = "1" ] || bail2 "· expected exactly 1 code line 'subscription.setCancelAtPeriodEnd(false);' in $SRC, found $m1_n" "mutation site M1 ambiguous"
[ "$m2_n" = "1" ] || bail2 "· expected exactly 1 code line 'if (periodStart != null && periodEnd != null) {' in $SRC, found $m2_n" "mutation site M2 ambiguous"
M1_LINE=$(printf '%s' "$m1_hits" | cut -d: -f1)
M2_LINE=$(printf '%s' "$m2_hits" | cut -d: -f1)
[ "$M1_LINE" -gt "$method_line" ] || bail2 "· M1 site (L$M1_LINE) is not inside applySubscriptionWebhookUpdate (L$method_line)" "mutation site M1 mislocated"
[ "$M2_LINE" -gt "$method_line" ] || bail2 "· M2 site (L$M2_LINE) is not inside applySubscriptionWebhookUpdate (L$method_line)" "mutation site M2 mislocated"
echo "· mutation sites anchored inside applySubscriptionWebhookUpdate (L$method_line): M1=L$M1_LINE  M2=L$M2_LINE"

# ---------------------------------------------------------------------------------------------
# run_tests -> sets the global RESULT to GREEN | RED | INFRA (mvn output kept in $TMPD/last.log).
# Sets a GLOBAL rather than echoing, deliberately: $(run_tests) would run it in a subshell, where
# the KEEP_LOGS flag it needs to raise on failure would be discarded and any diagnostic it printed
# would be swallowed into the return value.
#
# This backend's surefire fork intermittently dies inside ByteBuddy ("Could not modify all
# classes" / "Mockito cannot mock this class") on this machine — an infrastructure flake, NOT a
# killed mutant. It is retried (up to 3 attempts) and then escalated to exit 2. A flake must never
# be reported as a red gate, and an unkillable mutant must never be excused as a flake.
# ---------------------------------------------------------------------------------------------
RESULT=""
run_tests() {
  RESULT=""
  local attempt out summary rc
  out="$TMPD/last.log"
  for attempt in 1 2 3; do
    rc=0
    if command -v timeout >/dev/null 2>&1; then
      ( cd "$API" && timeout "$MVN_TIMEOUT" mvn -o -B -Dtest="$TESTS" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test ) >"$out" 2>&1 || rc=$?
      if [ "$rc" = "124" ]; then
        echo "  attempt $attempt: maven exceeded ${MVN_TIMEOUT}s"
        KEEP_LOGS=1; RESULT=INFRA; return
      fi
    else
      ( cd "$API" && mvn -o -B -Dtest="$TESTS" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test ) >"$out" 2>&1 || rc=$?
    fi
    if grep -qE 'Could not modify all classes|Mockito cannot mock this class|Byte Buddy could not instrument' "$out"; then
      echo "  attempt $attempt: ByteBuddy/Mockito instrumentation flake — retrying"
      continue
    fi
    if grep -qE 'COMPILATION ERROR' "$out" || grep -qE '^\[ERROR\].*\.java:\[[0-9]+,[0-9]+\]' "$out"; then
      echo "  attempt $attempt: the module did not compile"
      KEEP_LOGS=1; RESULT=INFRA; return
    fi
    summary=$(grep -E '^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$out" | tail -1)
    if [ -z "$summary" ]; then
      echo "  attempt $attempt: maven printed no test summary"
      KEEP_LOGS=1; RESULT=INFRA; return
    fi
    if echo "$summary" | grep -qE 'Failures: 0, Errors: 0'; then RESULT=GREEN; else RESULT=RED; fi
    return
  done
  echo "  all 3 attempts hit the instrumentation flake"
  KEEP_LOGS=1; RESULT=INFRA
}

# Put the pristine file back after a mutant run and PROVE it went back. A gate that leaves a
# mutated money-path file on disk is worse than no gate at all.
unmutate() {
  cp "$BACKUP" "$SRC" 2>/dev/null
  [ "$(SHA "$SRC")" = "$ORIG_SHA" ] || bail2 "· could not restore $SRC after a mutation (pristine copy: $BACKUP)" "restore failed — restore it by hand"
}

summary_of() { grep -E '^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$TMPD/last.log" | tail -1; }

# ---------------------------------------------------------------------------------------------
# Leg 0 — baseline. An already-red suite makes every mutant indistinguishable from the noise.
# ---------------------------------------------------------------------------------------------
echo "· baseline: $TESTS on the tree as it stands (this takes ~40s)"
run_tests; r="$RESULT"
case "$r" in
  INFRA) bail2 "· baseline run did not produce a usable result (see $TMPD/last.log)" "the backend test harness could not run" ;;
  RED)   echo "  $(summary_of)"
         echo "VERDICT: unavailable — the baseline suite is ALREADY red, so a mutant that also goes red proves nothing."
         echo "         Fix the failing tests first, then re-run this gate."
         echo "NOT CHECKED: whether the cancelAtPeriodEnd latch and the status-only-webhook period contract are covered"
         exit 2 ;;
esac
echo "  $(summary_of) — baseline green"

# ---------------------------------------------------------------------------------------------
# Leg 1 — M1: the latch. Neutralise the clear-on-(re)activation write.
# ---------------------------------------------------------------------------------------------
echo "· M1: neutralising the cancelAtPeriodEnd clear on (re)activation (L$M1_LINE) — the suite MUST go red"
sed -i "${M1_LINE}s/.*/                ;\/\/__MUT1__/" "$SRC" || bail2 "· could not apply M1" "sed failed"
grep -q '__MUT1__' "$SRC" || bail2 "· M1 did not apply" "sed no-op"
run_tests; r="$RESULT"
unmutate
case "$r" in
  INFRA) bail2 "· M1 run did not produce a usable result (see $TMPD/last.log)" "the backend test harness could not run" ;;
  GREEN)
    echo "  $(summary_of) — mutant SURVIVED"
    echo "VERDICT: broken — F-0158's own defect can be re-introduced with the whole billing suite staying"
    echo "         green. Deleting 'subscription.setCancelAtPeriodEnd(false);' from the ACTIVE branch of"
    echo "         applySubscriptionWebhookUpdate re-latches the flag on a re-subscribed customer, and"
    echo "         nothing in $TESTS notices. missed_by clause (a) — the re-subscribe -> missed-webhook"
    echo "         regression test — is still absent."
    echo "NOT CHECKED: M2 (the status-only-webhook period contract) — the run stopped at M1"
    exit 1 ;;
esac
echo "  $(summary_of) — mutant KILLED (the latch is covered)"

# ---------------------------------------------------------------------------------------------
# Leg 2 — M2: the no-side-effects contract on a status-only (cancel/halt/past_due) webhook.
# ---------------------------------------------------------------------------------------------
echo "· M2: deleting the periodStart/periodEnd null guard (L$M2_LINE) so a cancel webhook nulls the"
echo "      live billing period — the suite MUST go red"
sed -i "${M2_LINE}s/.*/        if (true) { \/\/__MUT2__/" "$SRC" || bail2 "· could not apply M2" "sed failed"
grep -q '__MUT2__' "$SRC" || bail2 "· M2 did not apply" "sed no-op"
run_tests; r="$RESULT"
unmutate
case "$r" in
  INFRA) bail2 "· M2 run did not produce a usable result (see $TMPD/last.log)" "the backend test harness could not run" ;;
  GREEN)
    echo "  $(summary_of) — mutant SURVIVED"
    echo "VERDICT: broken — a subscription.cancelled/halted webhook (which carries no period) can be made to"
    echo "         call Subscription#renewPeriod(null, null) and NULL currentPeriodStart/currentPeriodEnd on"
    echo "         a live paying row, and the whole billing suite stays green. currentPeriodEnd is what"
    echo "         SubscriptionRenewalResetJob#doRun dereferences to build its stale batch, so this is the"
    echo "         same money-path routing F-0158 is about. missed_by clause (b) — the no-side-effects"
    echo "         assertion on the webhook cancel case — is still absent: the fixture (proSubscriptionRow)"
    echo "         was given period fields, but no test ever asserts they SURVIVE a status-only delivery."
    echo "NOT CHECKED: nothing further — both mutants were run"
    exit 1 ;;
esac
echo "  $(summary_of) — mutant KILLED (the status-only period contract is covered)"

echo "VERDICT: proved — both money-path mutants in applySubscriptionWebhookUpdate are killed by the"
echo "         existing suite: the cancelAtPeriodEnd clear-on-reactivation is regression-covered, and a"
echo "         status-only webhook is asserted to leave the billing period untouched."
echo "NOT CHECKED: (1) only these two mutants — other one-way latches on this row (e.g. grantAdminPlan's"
echo "         existing-row branch never clears cancelAtPeriodEnd; it is safe today only because #cancel"
echo "         requires a non-null razorpaySubscriptionId and grantAdminPlan rejects those, an implicit"
echo "         invariant no test pins) are outside this gate. (2) unit tests only — no live Razorpay"
echo "         delivery, no DB NOT-NULL constraint, no real scheduler run. (3) the gate edits"
echo "         SubscriptionService.java in place for ~2 min; a concurrent writer to that file during the"
echo "         run would be clobbered by the restore (the pre-gate bytes are what get put back)."
exit 0
