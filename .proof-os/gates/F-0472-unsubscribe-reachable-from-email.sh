#!/usr/bin/env bash
# gates/F-0472-unsubscribe-reachable-from-email.sh — instance gate, closes F-0472.
#
# origin: F-0472 (partial-fix-narrows-defect) — "F-0444 was partially remediated but not for the
# case the finding names: an email recipient still has no way to stop email. The in-app path
# improved; the unsubscribe link is still not reachable from a delivered message."
#
# REACHABILITY HERE IS A CHAIN, NOT A FEATURE. It holds only if ALL of these are true at once:
#
#   1. NotificationService stores event.userId() on the EmailOutbox row
#   2. EmailWorker passes that userId to the 4-arg sendTemplateEmail overload
#   3. Msg91EmailClient mints a signed token and builds the /notifications/unsubscribe-link URL
#   4. EmailTemplateRegistry actually puts that URL in the rendered body
#   5. SecurityConfig permits GET on that route WITHOUT a session
#   6. the endpoint verifies the token and flips an EmailPreference
#
# Break any one and the other five still look correct in isolation. Link 2 is the quiet one: the
# 3-arg overload compiles fine and silently produces an email with no footer, which is precisely
# how "partially remediated" happens. Link 5 is the other: a link in an email that redirects to a
# login page is not an unsubscribe.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

API=influora-api/src/main/java/com/influora
SVC=$API/service/notification/NotificationService.java
WORKER=$API/service/notification/EmailWorker.java
CLIENT=$API/integration/msg91/Msg91EmailClient.java
REGISTRY=$API/integration/msg91/EmailTemplateRegistry.java
SEC=$API/config/SecurityConfig.java
CTRL=$API/web/NotificationController.java
TEST=influora-api/src/test/java/com/influora/integration/msg91/UnsubscribeLinkReachableFromEmailTest.java
for f in "$SVC" "$WORKER" "$CLIENT" "$REGISTRY" "$SEC" "$CTRL"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

# --- LINK 1+2: the userId survives from the event to the send call ------------------------------
grep -q "\.userId(event.userId())" "$SVC" || {
  echo "VERDICT: broken — the queued EmailOutbox row no longer carries event.userId(), so the"
  echo "         worker has no user to mint an unsubscribe token for (F-0472)"; exit 1; }
grep -q "sendTemplateEmail(toEmail, templateKey, templateData, userId)" "$WORKER" || {
  echo "VERDICT: broken — EmailWorker no longer passes userId to sendTemplateEmail. The 3-arg"
  echo "         overload compiles fine and silently sends mail with NO unsubscribe footer —"
  echo "         exactly the partial remediation F-0472 describes"; exit 1; }
echo "· the userId survives from the event through the outbox to the send call"

# --- LINK 3+4: the URL is built and rendered ----------------------------------------------------
grep -q "/notifications/unsubscribe-link?token=" "$CLIENT" || {
  echo "VERDICT: broken — the client no longer builds the unsubscribe URL (F-0472)"; exit 1; }
grep -q "unsubscribeFooterText" "$REGISTRY" || {
  echo "VERDICT: broken — the registry no longer renders an unsubscribe footer, so the URL is"
  echo "         built and then dropped before it reaches the recipient (F-0472)"; exit 1; }
echo "· the URL is minted by the client and rendered into the body"

# --- LINK 5: the route is reachable WITHOUT a session -------------------------------------------
# A link in an email that lands on a login page is not an unsubscribe. This is the check most
# likely to rot, because tightening security config is a routine, well-intentioned change.
grep -q '"/notifications/unsubscribe-link"' "$SEC" || {
  echo "VERDICT: broken — SecurityConfig no longer permits GET /notifications/unsubscribe-link"
  echo "         unauthenticated. The recipient is reading email, not holding a session: the link"
  echo "         would bounce to login and the unsubscribe becomes unreachable again (F-0472)"
  exit 1; }
echo "· the endpoint is permitted without a session"

# --- LINK 6: clicking it actually changes something ---------------------------------------------
grep -q "setUnsubscribed(true)" "$CTRL" || {
  echo "VERDICT: broken — the endpoint no longer flips EmailPreference.unsubscribed, so the link"
  echo "         renders, is clicked, and does nothing (F-0472)"; exit 1; }
grep -q "TEMPLATE_KEY_TO_EVENT_TYPE" "$CTRL" || {
  echo "VERDICT: broken — the templateKey→eventType translation is gone. The token carries a"
  echo "         TEMPLATE key; EmailPreference is keyed by EVENT type, so without this the write"
  echo "         lands on a row nothing reads and the unsubscribe silently no-ops (F-0444)"
  exit 1; }
echo "· the endpoint persists the preference, translating templateKey to eventType"

# --- behaviour ----------------------------------------------------------------------------------
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1 && [ -f "$TEST" ]; then
  git ls-files --error-unmatch "$TEST" >/dev/null 2>&1 || {
    echo "VERDICT: broken — $TEST is not git-tracked; it proves nothing on a fresh clone (F-0324)"
    exit 1; }
  echo "· the regression test is git-tracked"
fi
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — chain checked, BEHAVIOUR NOT PROVED"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — BEHAVIOUR NOT PROVED"; exit 2; }
[ -f "$TEST" ] || { echo "VERDICT: broken — the F-0472 regression test is gone"; exit 1; }

log=$(mktemp 2>/dev/null || echo "/tmp/f0472.$$")
( cd influora-api && mvn -o test -Dtest=UnsubscribeLinkReachableFromEmailTest -DfailIfNoTests=true ) >"$log" 2>&1
rc=$?
if grep -qE "Failed to delete .*target|Failed to clean project" "$log"; then
  echo "· maven could not clear influora-api/target — a JVM holds it. UNAVAILABLE, not a finding."
  exit 2; fi
# A test-compile failure in a file that is NOT this finding's is someone else's in-flight edit, not
# evidence about F-0472. Reporting it as broken would blame this finding for another's work — the
# false-red trap. Distinguish by checking whether OUR file is among the errors.
if grep -q "COMPILATION ERROR" "$log"; then
  if grep -q "UnsubscribeLinkReachableFromEmailTest.java" "$log"; then
    echo "VERDICT: broken — this finding's own test does not compile"
    grep -E "ERROR.*UnsubscribeLink" "$log" | head -5
    exit 1
  fi
  echo "· the module's test sources do not compile, and the errors are in OTHER files:"
  grep -oE "[A-Za-z0-9_]+\.java" "$log" | sort -u | head -5 | sed 's/^/      /'
  echo "  UNAVAILABLE — that is someone else's in-flight edit, not evidence about F-0472."
  exit 2
fi
if [ "$rc" -ne 0 ]; then
  echo "VERDICT: broken — the F-0472 regression test does not pass"
  grep -E "Tests run:|FAILURE!|expected" "$log" | head -10
  exit 1; fi
grep -E "Tests run:.*UnsubscribeLinkReachableFromEmail" "$log" | head -2

echo "VERDICT: aligned (proved) — an unsubscribe link is reachable from a delivered message: the"
echo "         userId reaches the send call, the client mints a signed token, the registry renders"
echo "         it into both body parts, the route is served without a session, and clicking it"
echo "         flips the preference under the right event type."
echo "NOT CHECKED: that mail is actually DELIVERED — no SMTP is exercised here, and MSG91's relay"
echo "             could drop the message with every link above intact. The transactional carve-out"
echo "             (auth.otp / otpman / auth.password_reset get no footer) is asserted as a control"
echo "             in the test, not as policy: whether that SET is correct is a product question."
echo "             Nothing here covers the admin custom-email path or the pre-account overload,"
echo "             which by design have no userId to unsubscribe by."
exit 0
