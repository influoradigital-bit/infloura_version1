#!/usr/bin/env bash
# gates/F-0392-F-0397-onboarding-field-persistence.sh
# Origin: F-0392, F-0393, F-0394, F-0395, F-0396, F-0397 (2026-09-01 field-persistence audit).
#
# THE DEFECT CLASS. Six records, one shape: a field the brand-onboarding wizard appears to
# collect never reaches a database column, and every static gate stays green while it happens.
# `tsc` cannot see it because the Java DTO is not a TypeScript type, and the build cannot see it
# because Jackson's FAIL_ON_UNKNOWN_PROPERTIES is unset — Spring Boot's default of `false`
# discards an unrecognised key with no 400 and no log line. The only thing that catches this
# class is an assertion on the value that actually lands.
#
#   F-0392  brand-onboarding.tsx sent `phone`; BrandRegisterRequest had no phone component.
#   F-0393  AdminCreatorDtos carried no phone, so the admin panel showed "Not provided" for
#           every creator regardless of users.phone_number.
#   F-0394  no Terms checkbox was rendered anywhere in the wizard while the page hardcoded
#           `acceptedTerms: true`, making the server's @AssertTrue permanently unfailable.
#   F-0395  the size dropdown wrote display strings ('1-10 employees') that
#           AdminBrandService.KNOWN_SIZES rejects with INVALID_BRAND_SIZE.
#   F-0396  Continue stayed live during the R2 logo upload, so logoUrl went out undefined.
#   F-0397  the suite was green throughout — no test asserted any of the above.
#
# WHY THIS GATE RUNS THE TESTS RATHER THAN GREPPING FOR THE FIX. A grep for
# `disabled={isUploadingLogo}` is name-locked in exactly the way F-0335 documents: it passes for
# the one spelling the record happened to name and is blind to the next instance of the class.
# These suites assert on observable behaviour — an OTP that is not sent, a button that carries
# `disabled`, a DTO field that round-trips a value — so a differently-spelled reintroduction of
# the same defect still turns this red.
#
# FALSIFIED, not assumed. The frontend suite was executed against deliberately reverted source
# on 2026-09-01: 4 of 7 failed, each on the exact pre-fix symptom
# ("expected spy to not be called", "expected ['STARTUP','SMB','ENTERPRISE'] to include
# '1-10 employees'", "expected false to be true"). A gate that has never been seen red is not
# known to be a gate.
#
# Exit: 0 proved · 1 broken · 2 unavailable (toolchain missing — never green).

set -uo pipefail
cd "$(dirname "$0")/../.." || { echo "GATE UNAVAILABLE: cannot reach project root"; exit 2; }

FE_SUITE="src/components/brand/onboarding/__tests__/onboarding-steps.persistence.test.tsx"
BE_TESTS="AuthServiceTest,AdminCreatorServiceTest,CreatorProfileServiceTest"

fail=0

# ── Frontend: F-0394 / F-0395 / F-0396 ────────────────────────────────────────────────
if ! command -v npx >/dev/null 2>&1; then
  echo "GATE UNAVAILABLE: npx not on PATH — frontend half never ran, this is not green"
  exit 2
fi
if [ ! -f "$FE_SUITE" ]; then
  echo "GATE UNAVAILABLE: $FE_SUITE is missing — the gate's own subject is gone"
  exit 2
fi

echo "── frontend: $FE_SUITE"
if npx vitest run "$FE_SUITE" >/tmp/f0392.fe.log 2>&1; then
  echo "   PASS"
else
  echo "   FAIL — onboarding field-persistence assertions are red:"
  grep -E "FAIL|AssertionError|×" /tmp/f0392.fe.log | head -12
  fail=1
fi

# ── Backend: F-0392 / F-0393 ──────────────────────────────────────────────────────────
if ! command -v mvn >/dev/null 2>&1; then
  echo "GATE UNAVAILABLE: mvn not on PATH — backend half never ran, this is not green"
  exit 2
fi

echo "── backend: $BE_TESTS"
if (cd influora-api && mvn -o test -Dtest="$BE_TESTS" -DfailIfNoTests=false) >/tmp/f0392.be.log 2>&1; then
  grep -E "^\[INFO\] Tests run:" /tmp/f0392.be.log | tail -1 | sed 's/^/   /'
else
  echo "   FAIL — brand/admin phone persistence assertions are red:"
  grep -E "Tests run:|ERROR|FAIL" /tmp/f0392.be.log | head -12
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "GATE RED — a field the onboarding wizard collects is not reaching its column."
  exit 1
fi

echo
echo "GATE GREEN — brand phone, admin creator phone, terms consent, company-size vocabulary"
echo "and the logo upload race all assert on the value that actually lands."
echo "NOT CHECKED: whether a real signup writes a real row — persistence is proved through"
echo "mocked repositories and entity mutators, not an actual INSERT against a live database;"
echo "and whether Terms consent is PERSISTED (it is collected and validated, but no column"
echo "stores accepted_at or a policy version — that gap is still open)."
exit 0
