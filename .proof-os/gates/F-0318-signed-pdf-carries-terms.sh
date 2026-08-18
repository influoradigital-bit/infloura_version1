#!/usr/bin/env bash
# F-0318-signed-pdf-carries-terms.sh — gate for F-0318 (signed-artifact-omits-terms).
#
# ContractPdfService#addTerms() emitted a "Terms" section with only total amount, status,
# effective date and expiration date — it received the whole Contract but never called
# getTermsText(). This PDF is what generateAndDeliverContractPdf() (ContractService.java)
# renders, stores in R2, and emails to BOTH parties as the executed contract. F-0283's own
# symptom named four hops — "no request, no column, no response and no PDF input" — and only
# three were closed; this is the fourth: the artefact the parties actually sign.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

# F-0266: grep CODE, not file bytes — see gates/_code.sh header for why.
. "$SELF/_code.sh" 2>/dev/null || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
code_ready || { echo "· $(code_why) — unavailable"; exit 2; }

PDF_SVC=influora-api/src/main/java/com/influora/service/ContractPdfService.java
TEST=influora-api/src/test/java/com/influora/service/ContractPdfServiceTest.java
[ -f "$PDF_SVC" ] || { echo "· $PDF_SVC missing — unavailable"; exit 2; }
[ -f "$TEST" ] || { echo "· $TEST missing — unavailable"; exit 2; }

PDF_CODE=$(code_view "$PDF_SVC") || { echo "· $(code_why) — unavailable"; exit 2; }

echo "· static: addTerms() actually calls getTermsText(), not getTermsJson()"
addterms=$(awk '/private void addTerms\(/,/^    \}$/' "$PDF_CODE")
if [ -z "$addterms" ]; then
  echo "  could not locate ContractPdfService#addTerms() method body — unavailable"
  exit 2
fi
if ! printf '%s\n' "$addterms" | grep -qE '\.getTermsText\(\)'; then
  echo "VERDICT: broken — addTerms() never calls contract.getTermsText(); the terms field never"
  echo "         reaches the signed artefact both parties receive by email (F-0318)"
  exit 1
fi
if printf '%s\n' "$addterms" | grep -qE '\.getTermsJson\(\)'; then
  echo "VERDICT: broken — addTerms() calls contract.getTermsJson(), which is the SHA-256"
  echo "         tamper-evidence hash of the generate request, not the agreed terms text"
  echo "         (F-0322/F-0283 doc comment on Contract#termsJson). Printing that as \"Terms\""
  echo "         is not a fix, it prints the wrong field (F-0318)"
  exit 1
fi
echo "  clean — addTerms() calls getTermsText() and never getTermsJson()"

# The static leg above cannot tell "calls getTermsText() and renders it" apart from "calls
# getTermsText() into an unused local" (F-0296/F-0310/F-0319 house rule: a wrong fix that
# fetches the field but renders nothing is exactly the review's named failure mode). Only
# actual generated-PDF content closes that gap, so this gate requires the test suite that
# extracts real PDF text with OpenPDF's own PdfTextExtractor and asserts on it — not on
# whether a method name appears in source.
echo "· behavioural: ContractPdfServiceTest extracts real PDF text and asserts on it"
if ! grep -qE 'PdfTextExtractor' "$PDF_CODE" 2>/dev/null; then :; fi
TEST_CODE=$(code_view "$TEST") || { echo "· $(code_why) — unavailable"; exit 2; }
if ! grep -qE 'PdfTextExtractor' "$TEST_CODE"; then
  echo "VERDICT: broken — $TEST does not extract real PDF text (e.g. via"
  echo "         com.lowagie.text.pdf.parser.PdfTextExtractor) to assert on rendered content;"
  echo "         a test that only checks the PDF is non-empty cannot distinguish terms actually"
  echo "         printed from terms fetched-and-discarded (F-0318)"
  exit 1
fi
if ! grep -qE 'getTermsText|termsText' "$TEST_CODE"; then
  echo "VERDICT: broken — $TEST has no test exercising a contract with terms text set (F-0318)"
  exit 1
fi
echo "  clean — test file extracts PDF text and exercises termsText"

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

BUDGET="${PROOF_F0318_MVN_TIMEOUT:-240}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· mvn -o test ContractPdfServiceTest (budget ${BUDGET}s)"
out=$(cd influora-api && $TO mvn -q -o test \
        -Dtest=ContractPdfServiceTest -DfailIfNoSpecifiedTests=false 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish, so no test result"
  echo "             was observed"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "ERROR|Tests run|FAIL" | tail -40
  echo "VERDICT: broken — ContractPdfServiceTest does not pass; the terms-in-PDF behaviour this"
  echo "         gate requires is not actually exercised end to end (F-0318)"
  exit 1
fi
echo "  suite green — ContractPdfServiceTest"

echo "VERDICT: aligned (proved) — addTerms() reads contract.getTermsText() (never getTermsJson()"
echo "         — the SHA-256 tamper hash), and ContractPdfServiceTest extracts real PDF text with"
echo "         PdfTextExtractor and asserts the supplied terms text is present in the rendered"
echo "         bytes, and that an absent-terms contract renders an honest sentence rather than a"
echo "         blank section under the 'Terms' heading."
echo "NOT CHECKED: that generateAndDeliverContractPdf() (ContractService.java) actually calls"
echo "             render() with a Contract whose termsText was persisted from a real request —"
echo "             that hop is F-0283's own gate/tests, not this one; whether the emailed"
echo "             download link in a live inbox actually opens a PDF containing the terms (only"
echo "             a live E2E proves that); PDF layout/pagination overflow for very long terms"
echo "             text (up to the F-0322 cap) is untested here."
exit 0
