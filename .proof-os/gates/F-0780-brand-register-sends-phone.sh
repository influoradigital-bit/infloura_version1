#!/usr/bin/env bash
# gates/F-0780-brand-register-sends-phone.sh
# Origin: F-0780 (dto-drift, src/pages/brand-register.tsx), F-0779 (the one-caller gate hole)
# and F-0788 (this gate's own first draft reproducing F-0779 — see the block at line 20).
#
# F-0780. F-0392 made phone a REQUIRED component of BrandRegisterRequest (143ca1e) and
# AuthService.brandRegister:134 now throws PHONE_REQUIRED/400 on a blank one. The DTO has TWO
# frontend callers. Only the onboarding wizard was updated. src/pages/brand-register.tsx — the
# signup CTA linked from the landing page, every /features page, both how-it-works pages and the
# blog — has no phone state, no phone input, and omits phone from its api.auth.brandRegister
# payload at :124. `tsc` is structurally blind to it: BrandRegisterPayload declares
# `phone?: string` (src/lib/api.ts:994) while CreatorRegisterPayload declares `phone: string`
# (:1016), so the omission is a legal call. On this branch every brand signup submission 400s.
#
# F-0779. The gate that closed F-0392
# (.proof-os/gates/F-0392-F-0397-onboarding-field-persistence.sh) asserts brand phone
# persistence through ONE caller: its FE_SUITE at line 40 is the onboarding wizard suite.
# brand-onboarding.tsx does send phone, so that gate was green for the entire window the register
# page was broken. Covering BOTH callers of the DTO is the whole reason this gate exists.
#
# F-0779 REPRODUCED INSIDE ITS OWN REMEDIATION (2026-09-12, found by kavya, repaired here).
# The first version of THIS gate ran, as its caller-2 leg,
# src/components/brand/onboarding/__tests__/onboarding-steps.persistence.test.tsx. That suite's
# three describes are F-0394 (Terms checkbox), F-0395 (company-size vocabulary) and F-0396 (logo
# upload). It contains zero references to `brandRegister`, never renders brand-onboarding.tsx,
# and its only `phone` is a fixture literal — so the leg asserted NOTHING about caller 2's
# payload while the banner below printed "BOTH callers" as fact. kavya proved it by mutation:
# with brand-onboarding.tsx:94 changed from `phone: normalizePhone(data.phone)` to `phone: ''`,
# that gate still exited 0 and still printed the banner (`tsc --noEmit` stays green too, because
# '' is a legal string for the now-required field). Caller 2's leg is now
# src/pages/__tests__/brand-onboarding-sends-phone.test.tsx, which renders the page itself on the
# cold-entry path and asserts the value on the wire.
#
# WHY BEHAVIOUR, NOT GREP. A grep for `normalizePhone(phone)` or `id="phone"` is name-locked to
# one spelling and — per the 2026-09-07 record — has been satisfied in this repo by the comment
# explaining the banned pattern. Both suites assert on the value that reaches the mocked client:
# phone must arrive as /^\d{10}$/, and a blank must cost zero round trips. A differently-spelled
# reintroduction still turns this red.
#
# FALSIFIED, not assumed — stated per caller, because the previous version's three falsifications
# were ALL against caller 1 and that is exactly how the caller-2 hole survived review.
#
#   CALLER 1 (src/pages/brand-register.tsx), 3 falsifications, 2026-09-12:
#     (1a) unmodified pre-fix tree: exit 1, all 3 cases red on the defect itself ("F-0780: no
#          mobile/phone input is rendered in Step 2 of brand-register.tsx");
#     (1b) a deliberately WRONG fix (field added but the raw '+91 98765 43210' forwarded, no
#          client validation, error left in the generic banner): still exit 1, each case failing
#          on its own assertion — "expected '+91 98765 43210' to match /^\d{10}$/", "expected spy
#          to not be called at all, but actually been called 1 times", "Unable to find an element
#          with the text: /already registered/i";
#     (1c) green demonstrated once against a correct candidate fix, then reverted — so the leg is
#          passable, not merely unsatisfiable.
#
#   CALLER 2 (src/pages/brand-onboarding.tsx), 1 falsification, 2026-09-12 (this repair):
#     (2a) mutate brand-onboarding.tsx:94 `phone: normalizePhone(data.phone)` -> `phone: ''` —
#          the exact mutation the previous version of this gate slept through — run this gate:
#          exit 1 on the caller-2 leg, red on
#          "expected '' to match /^\d{10}$/". Mutation reverted; `git diff --quiet` clean.
#     Green on the unmutated tree is demonstrated by every clean run, so this leg is passable.
#
# RECEIPT INTEGRITY. vitest 3.2.7 writes ANSI colour escapes between the label and the count
# (`\x1b[2m      Tests \x1b[22m \x1b[1m\x1b[32m3 passed`), so the previous version's
# `grep -E "Tests +[0-9]+ passed"` never matched and every PASS printed a BLANK receipt — a suite
# silently shrinking from 3 cases to 1 still read as a clean pass. Escapes are stripped before
# grepping, the count is printed, and a leg whose count falls below the case count it was written
# with is RED, not green.
#
# Exit: 0 proved · 1 broken · 2 unavailable (toolchain or subject missing — never green).

set -uo pipefail
cd "$(dirname "$0")/../.." || { echo "GATE UNAVAILABLE: cannot reach project root"; exit 2; }

# Caller 1 of BrandRegisterPayload — the /brand/register page (F-0780's subject).
REGISTER_SUITE="src/pages/__tests__/brand-register-sends-phone.test.tsx"
REGISTER_MIN_CASES=3
# Caller 2 — the onboarding wizard, driven through src/pages/brand-onboarding.tsx itself on the
# cold-entry path (no brand token => hasBrandToken() false => step 1 => the branch at :72 that
# actually calls brandRegister). NOT the field-persistence suite: see the F-0779-reproduced note.
ONBOARDING_SUITE="src/pages/__tests__/brand-onboarding-sends-phone.test.tsx"
ONBOARDING_MIN_CASES=3

LOG_DIR="${TMPDIR:-/tmp}"
fail=0

if ! command -v npx >/dev/null 2>&1; then
  echo "GATE UNAVAILABLE: npx not on PATH — neither caller was exercised, this is not green"
  exit 2
fi

for suite in "$REGISTER_SUITE" "$ONBOARDING_SUITE"; do
  if [ ! -f "$suite" ]; then
    echo "GATE UNAVAILABLE: $suite is missing — a subject of this gate is gone, so a pass here"
    echo "would mean nothing. Restore it or amend the gate deliberately."
    exit 2
  fi
done

# vitest 3.2.7 colours its summary; every read of a log goes through this first.
strip_ansi() { sed 's/\x1b\[[0-9;]*m//g' "$1"; }

run_suite() {
  local label="$1" suite="$2" log="$3" min_cases="$4"
  echo "── $label: $suite"
  if ! npx vitest run "$suite" >"$log" 2>&1; then
    echo "   FAIL — assertions are red:"
    strip_ansi "$log" | grep -E "FAIL|AssertionError|Error:|→|×" | head -14 | sed 's/^/     /'
    return 1
  fi

  local passed
  passed=$(strip_ansi "$log" | grep -oE "Tests +[0-9]+ passed" | tail -1 | grep -oE "[0-9]+")
  if [ -z "$passed" ]; then
    echo "   NO RECEIPT — vitest exited 0 but no 'Tests N passed' line could be read from $log."
    echo "   A pass with no countable receipt is not a pass; this is the blank-PASS bug the"
    echo "   previous version of this gate shipped. Treating as red."
    return 1
  fi
  if [ "$passed" -lt "$min_cases" ]; then
    echo "   SHRUNK — only $passed case(s) ran; this leg was written with $min_cases."
    echo "   Cases were deleted or skipped, so the green covers less than the gate claims."
    return 1
  fi
  echo "   Tests $passed passed (leg requires >= $min_cases)"
  echo "   PASS"
  return 0
}

# ── Caller 1: /brand/register (F-0780) ────────────────────────────────────────────────
run_suite "register page" "$REGISTER_SUITE" "$LOG_DIR/f0780.register.log" "$REGISTER_MIN_CASES" || fail=1

# ── Caller 2: /brand/onboarding (F-0779 — the caller the old leg only appeared to cover) ─
run_suite "onboarding wizard" "$ONBOARDING_SUITE" "$LOG_DIR/f0780.onboarding.log" "$ONBOARDING_MIN_CASES" || fail=1

if [ "$fail" -ne 0 ]; then
  echo
  echo "GATE RED — at least one caller of BrandRegisterPayload does not put a usable phone on the"
  echo "wire. AuthService.brandRegister answers PHONE_REQUIRED/400 to a blank and INVALID_PHONE/400"
  echo "to an unnormalized one, so this is a dead signup funnel, not a cosmetic gap."
  exit 1
fi

echo
echo "GATE GREEN — both callers of BrandRegisterPayload were RENDERED and DRIVEN to submission,"
echo "and the object each one passed to api.auth.brandRegister carries a phone matching"
echo "/^\\d{10}\$/ derived from src/lib/phone.ts:"
echo "  · caller 1 — src/pages/brand-register.tsx, step 2 submit; a blank phone is refused"
echo "    client-side with no network call, and a PHONE_ALREADY_EXISTS 409 lands on the field;"
echo "  · caller 2 — src/pages/brand-onboarding.tsx on the COLD-ENTRY path (no brand token, so"
echo "    hasBrandToken() is false, the wizard starts at step 1 and :72 actually registers);"
echo "    a blank phone is refused at step 1 and never reaches brandRegister."
echo "NOT CHECKED: (1) that a real POST /auth/brand/register succeeds — api.auth.brandRegister is"
echo "mocked in both suites, so the server's IndianPhoneUtils rule is mirrored, never executed;"
echo "(2) the WARM onboarding entry (brand token present, wizard opens at step 2) — that path"
echo "deliberately skips brandRegister, so it has no payload to prove;"
echo "(3) any THIRD caller of BrandRegisterPayload added after 2026-09-12 — this gate is a fixed"
echo "list of two suites and will stay green if a new page omits phone, which is exactly the"
echo "F-0779 failure mode one caller further on;"
echo "(4) the backend half — AuthServiceTest is run by"
echo "gates/F-0392-F-0397-onboarding-field-persistence.sh, not here;"
echo "(5) F-0394/F-0395/F-0396 field persistence — that is"
echo "onboarding-steps.persistence.test.tsx's subject and it is NOT run by this gate; it was"
echo "mis-wired here as the caller-2 leg and proved nothing about the payload;"
echo "(6) whether a phone accepted here is a REACHABLE number — no OTP is sent to it."
exit 0
