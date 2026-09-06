#!/usr/bin/env bash
# gates/tester-pass-findings-verified.sh — closes F-0666 and F-0667.
#
# origin: a zero-context tester pass over the whole Ananya frontend surface. Both legs ran on Opus:
# a tester told only WHICH AREAS changed (never what was built, fixed or claimed, and barred from
# reading any prior review) wrote 12 questions; a fresh-context priya answered them from source and
# real runs, citation-gated (69 citations, 69 quotations verified, exit 0).
#
# It did not sign off. Every gate it ran was green — tsc 0, npm test 179 files/1057 tests, live
# 3/16 — and it still found three defects nobody had reported, by re-deriving instead of trusting:
#
#   F-0666 (fixed here) — FABRICATED LEGAL TERMS on a document a creator signs against.
#     creator-deal-contract-tab.tsx built its client-side PDF with usageRights hardcoded to
#     '6 months', exclusivity to 'Per brief', revisionCap to 2, and a deadline invented as today
#     plus 14 days. That branch fires whenever the server PDF is unavailable — and the server only
#     serves it once BOTH parties have signed, so this is precisely what a creator got when
#     reviewing the contract BEFORE signing. Four lines above the fabrications, the same object
#     honestly passed an empty deliverables array with the comment "an honest empty array beats a
#     fabricated one": the principle was applied to one field and not the other four.
#     Fixed by making those four fields optional on ContractData and rendering an explicit
#     "Not specified" rather than a value the app was never told. The fixing agent also found the
#     fabrication was NOT confined to the pre-signature branch — mock mode built the same object
#     unconditionally — which is why the honest-optional fix was chosen over skipping the branch.
#
#   F-0667 (fixed here) — the deal room went PERMANENTLY DEAF for remember-me-off creators.
#     api.ts's SSE stream read localStorage alone for its bearer token. A creator who unchecked
#     "remember me" has that token in sessionStorage (CR-121), so the stream opened with NO
#     Authorization header; the resulting 401 is in TERMINAL_STREAM_STATUSES, so no reconnect is
#     ever attempted. Silent, permanent, and only for the users who chose not to be remembered.
#     Every other token consumer was taught the both-stores fallback when F-0459 landed; this call
#     site was missed. Falsified: reverting gives "expected undefined to be
#     'Bearer session_only_token'" — no header at all, which is the defect exactly.
#
#   F-0668 (NOT fixed, still open) — F-0466's ApiError.field/.fields plumbing has ZERO consumers
#     outside api.ts. Every server-named field error still surfaces as one generic message, which
#     is the exact symptom F-0466 described. F-0466 was promoted on the strength of the plumbing;
#     that was too generous and F-0668 is the correction, not a new problem.
#
# ALSO STILL OPEN, found while verifying this fix: F-0669 — the BRAND side
# (deal-contract-tab.tsx:152-155) fabricates the SAME four terms with different invented wording,
# so both parties can download documents with invented — and mutually inconsistent — terms for one
# agreement. contract-generator.ts now accepts these fields as optional, so that fix is to stop
# passing the literals; it was outside the fixing agent's file boundary.
#
# STANDING CAVEAT this pass established, which bounds every green result in this repo:
# vitest.config.ts:50 pins mock mode in both test.env and a transform-time define. All 1057 tests
# run against the mock API; the 16 "live" tests stub fetch against an invalid host. ZERO of the
# 1073 tests touch a real backend or a real browser.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

CREATOR_TAB=src/components/creator/deal-room/creator-deal-contract-tab.tsx
# Source-level tripwire for F-0666. The test below proves the rendered document is honest, but a
# future edit could reintroduce a literal on a path the test does not drive — mock mode built the
# same fabricated object unconditionally, which is how this shipped in the first place.
if grep -qE "usageRights: *'|exclusivity: *'|revisionCap: *[0-9]" "$CREATOR_TAB"; then
  echo "· $CREATOR_TAB passes a hardcoded contract term again"
  grep -nE "usageRights: *'|exclusivity: *'|revisionCap: *[0-9]" "$CREATOR_TAB"
  echo "VERDICT: broken — a fabricated legal term is back on the creator's contract document (F-0666)"
  exit 1
fi
echo "· no hardcoded contract terms in the creator contract tab"

if ! grep -q "sessionStorage.getItem(TOKEN_KEYS\[role\])" src/lib/api.ts; then
  echo "VERDICT: broken — the SSE stream no longer falls back to sessionStorage (F-0667)"
  exit 1
fi
echo "· stream token reads both stores"

echo "· tsc --noEmit"
out=$(npx --no-install tsc --noEmit 2>&1); rc=$?
if [ $rc -ne 0 ]; then printf '%s\n' "$out" | head -20; echo "VERDICT: broken — tsc fails"; exit 1; fi
echo "  tsc clean"

BUDGET="${PROOF_TESTER_FINDINGS_TIMEOUT:-900}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· npm test (full suite — the contract generator is shared by four callers)"
out=$($TO npm test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "FAIL|Test Files|Tests |AssertionError" | tail -30
  echo "VERDICT: broken — the full suite fails"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Test Files|Tests " | tail -2 | sed 's/^/  /'

echo "· npm run test:live"
out=$($TO npm run test:live 2>&1); rc=$?
if [ $rc -ne 0 ]; then printf '%s\n' "$out" | tail -25; echo "VERDICT: broken — live project fails"; exit 1; fi
printf '%s\n' "$out" | grep -E "Test Files|Tests " | tail -2 | sed 's/^/  /'

echo "VERDICT: aligned (proved) — F-0666 (the creator's contract document no longer contains a term"
echo "         the app was never told; unknown terms read 'Not specified') and F-0667 (the deal"
echo "         message stream authenticates for a remember-me-off creator instead of going"
echo "         permanently deaf) both hold on a typechecking tree with both vitest projects green."
echo "NOT CHECKED: F-0668 (F-0466's field-error plumbing still has no consumer) and F-0669 (the"
echo "             BRAND contract tab still fabricates the same four terms) — both open, neither"
echo "             touched here. And per the standing caveat above: no test in this repo exercises"
echo "             a real backend or a real browser, so none of this is live-proven."
exit 0
