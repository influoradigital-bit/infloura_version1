#!/usr/bin/env bash
# F-0519-rotate-keeps-identifier.sh — gate for T-ROTATE-0913.
# Closes F-0519 (Shopify), F-0520 + F-0766 (WooCommerce, filed twice), and F-0816 (Meta,
# previously unknown — the integration nobody swept after F-0519/F-0520/F-0766 shipped).
#
# THE CLASS (not four separate bugs): a store/connect method looks up an existing row by
# workspace, and the EXISTING-row branch rotates the SECRET but never rewrites the
# IDENTIFIER that says which external system the row points at, while the NEW-row branch
# sets that identifier correctly from the same parameter. A brand disconnecting store/
# account A and reconnecting to B keeps A's identifier forever: API calls 401 against A,
# webhooks from B 404 (UNIQUE(identifier) can't resolve a workspace), and the connect
# response + UI both report B as connected. F-0519 filed Shopify alone, F-0520 filed Woo
# alone, F-0766 re-filed BOTH because each earlier fix was scoped to one integration, and
# Meta (F-0816) shipped into a migration this month because nobody ever swept it.
#
# TWO LEGS:
#   1. STRUCTURE — RotateKeepsIdentifierConformanceTest, an ASM bytecode scan (same
#      technique as EntitlementConformanceTest / BrandFeePublishPathConformanceTest) of the
#      mechanical rule itself: in any method with an existing-row/new-row branch, every
#      field the new-row branch sets from a parameter must also reach the existing-row
#      branch's rotate*(...) call. This is what catches a FIFTH integration automatically
#      (R5 falsification below) — it does not name Shopify/WooCommerce/Meta anywhere in its
#      logic, only in its failure-message text.
#   2. BEHAVIOUR — the three *TokenStorage/*IntegrationService "reconnect to a DIFFERENT
#      store/account" regression tests, which actually invoke the write path and assert the
#      persisted identifier changed. The bytecode scan proves the parameter is REACHABLE;
#      these prove it actually lands, the same distinction BrandFeePublishPathConformanceTest's
#      javadoc draws between "reachable" and "charged".
#
# exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }
# F-0141/house rule: influora-api is the only pom; run from inside it, never `mvn -pl`.

TESTS="RotateKeepsIdentifierConformanceTest,ShopifyTokenStorageTest,WooCommerceIntegrationServiceTest,MetaTokenStorageTest"

echo "· mvn -o test -Dtest=$TESTS (full test-compile first — a stale target/classes reads"
echo "  yesterday's bytecode as GREEN, see reference_run_one_test_when_tree_broken)"
out=$(cd influora-api && mvn -o test -Dtest="$TESTS" -DfailIfNoTests=false 2>&1)
rc=$?

# F-0229/house rule: a piped mvn reports the pipe's exit code, not mvn's — capture $out and
# $rc from mvn directly (above), never `mvn | grep`.

if printf '%s\n' "$out" | grep -q "COMPILATION ERROR"; then
  printf '%s\n' "$out" | grep -A3 "COMPILATION ERROR" | head -40
  echo "VERDICT: broken — the test sources do not compile; a compile failure makes surefire"
  echo "         re-serve the PREVIOUS report on some invocations, so this is checked BEFORE"
  echo "         trusting any Tests-run line below"
  exit 1
fi

summary=$(printf '%s\n' "$out" | grep -E "^\[(INFO|ERROR)\] Tests run:.*(ConformanceTest|StorageTest|ServiceTest)" )
overall=$(printf '%s\n' "$out" | grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+ *$" | tail -1)

if [ -z "$overall" ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: unavailable — could not find a Tests-run summary line in mvn output (see"
  echo "         full output above) — likely a broken tree unrelated to this gate"
  exit 2
fi

echo "$summary"
echo "$overall"

failures=$(printf '%s\n' "$overall" | sed -E 's/.*Failures: ([0-9]+).*/\1/')
errors=$(printf '%s\n' "$overall" | sed -E 's/.*Errors: ([0-9]+).*/\1/')
skipped=$(printf '%s\n' "$overall" | sed -E 's/.*Skipped: ([0-9]+).*/\1/')
run=$(printf '%s\n' "$overall" | sed -E 's/.*Tests run: ([0-9]+).*/\1/')

if [ "$rc" -ne 0 ] || [ "${failures:-1}" != "0" ] || [ "${errors:-1}" != "0" ]; then
  printf '%s\n' "$out" | grep -E "ERROR|AssertionError" | grep -v "^\[ERROR\] $" | tail -30
  echo "VERDICT: broken — the stale-identifier-on-rotate class is present (structural scan) or"
  echo "         a reconnect-to-a-different-store/account regression test failed (behavioural"
  echo "         proof). See the failure detail above for WHICH integration/method — the"
  echo "         assertion messages name the exact class, method descriptor, field, and F-number."
  exit 1
fi

if [ "${skipped:-1}" != "0" ]; then
  echo "VERDICT: broken — Skipped: $skipped is not 0. A gate that reports GREEN while its own"
  echo "         proof tests never ran is exactly the vacuous-gate class this repo has shipped"
  echo "         before (feedback_ci_gates_passing_vacuously) — refusing to certify on a skip."
  exit 1
fi

if [ "${run:-0}" -lt 10 ]; then
  echo "VERDICT: unavailable — only $run test(s) ran; expected the full suite across all four"
  echo "         test classes (RotateKeepsIdentifierConformanceTest: 2, ShopifyTokenStorageTest:"
  echo "         11+, WooCommerceIntegrationServiceTest: 10+, MetaTokenStorageTest: 29+) — a"
  echo "         partial run (e.g. -Dtest filter typo, or shared-target contention from a"
  echo "         concurrent session's mvn run corrupting test discovery) is not a proof."
  exit 2
fi

echo "  all green, 0 skipped — structural scan (RotateKeepsIdentifierConformanceTest) and"
echo "  behavioural reconnect-to-a-different-store/account regressions (Shopify/WooCommerce/Meta)"
echo "  both pass"
echo
echo "NOT CHECKED:"
echo "  - Runtime data-flow beyond slot identity: the structural scan proves a parameter's JVM"
echo "    local-variable slot reaches the rotate call, not a full points-to proof that no other"
echo "    in-scope variable could occupy that slot. The behavioural tests close this for the"
echo "    three real integrations by actually invoking the write path and reading back the"
echo "    persisted identifier."
echo "  - A rotate-shaped method could accept the identifier and assign it to a semantically"
echo "    wrong field (not the one an integration's UNIQUE-constrained identifier column maps"
echo "    to) and still pass RotateKeepsIdentifierConformanceTest's second assertion (every"
echo "    parameter reaches SOME field). Reviewed by hand for all four known rotate methods."
echo "  - UNIQUE(shop_domain)/UNIQUE(site_url) collision case: reconnecting to an identifier some"
echo "    OTHER workspace previously connected and later revoked is a pre-existing, orthogonal"
echo "    edge case this fix does not touch (see ShopifyIntegration's F-0519 javadoc) — it was"
echo "    true before this fix and remains true after it."
echo "  - Bytecode freshness is not compared file-by-file against source for the whole"
echo "    com.influora tree (BrandFeePublishPathConformanceTest does this only for its two named"
echo "    entry points) — this gate relies on 'mvn -o test' always recompiling first, never"
echo "    'mvn -o surefire:test'."
echo "  - ConversionWebhookSecretService.generate was confirmed NOT an instance of this class"
echo "    (no external identifier parameter exists at all — its sole parameter, workspaceId, IS"
echo "    the lookup key) by manual review and by the scan's own exclusion logic, not by a"
echo "    dedicated assertion naming it — a regression there would read as silence, not RED."
echo
echo "VERDICT: aligned (proved) — the stale-identifier-on-rotate class is closed for Shopify"
echo "         (F-0519), WooCommerce (F-0520/F-0766) and Meta (F-0816), and the same mechanical"
echo "         rule is now gated structurally so a fifth integration cannot reintroduce it"
echo "         unnoticed."
exit 0
