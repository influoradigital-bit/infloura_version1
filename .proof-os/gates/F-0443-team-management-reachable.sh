#!/usr/bin/env bash
# gates/F-0443-team-management-reachable.sh — instance gate, closes F-0443.
#
# Also closes F-0622 (false-red-tool-error): that row recorded DELETE /workspace/members/{memberId}
# as having genuinely no frontend caller. It now has one - api.workspaceMembers.removeMember - and
# CHECK A below fails if it disappears again.
#
# origin: F-0443 (unreachable-endpoint) — "Workspace team management is backend-complete and has no
# UI at all: accept invite, remove member, list invites, revoke invite and switch workspace are
# five routes no shipped frontend reaches. A brand cannot invite a colleague through the product."
#
# WHAT SHIPPED: a Team tab in brand settings (list members, invite with a role, list outstanding
# invites, revoke, remove) plus /brand/invite for redemption. Invite WITHOUT accept would have been
# half a feature — a brand could send something nobody could act on — so the finding is not
# satisfied by the tab alone.
#
# ONE ROUTE IS DELIBERATELY STILL UNREACHED: POST /workspace/members/switch. A switcher needs the
# set of workspaces the caller belongs to and NOTHING returns it (/workspaces/me returns exactly
# one; there is no list-my-workspaces route), so its options could only be fabricated. That is
# F-0685, and CHECK D below asserts the exception stays EXACTLY that narrow — if a second route
# ever falls off, this gate fails rather than quietly widening the carve-out.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

PANEL=src/components/brand/settings/team-members-panel.tsx
PAGE=src/pages/brand-accept-invite.tsx
SETTINGS=src/pages/brand-settings.tsx
APP=src/App.tsx
PANEL_TEST=src/components/brand/settings/__tests__/team-members-panel.test.tsx
PAGE_TEST=src/pages/__tests__/brand-accept-invite.test.tsx
for f in "$PANEL" "$PAGE" "$SETTINGS" "$APP" "$PANEL_TEST" "$PAGE_TEST"; do
  [ -f "$f" ] || { echo "VERDICT: broken — $f is missing (F-0443)"; exit 1; }
done

# --- CHECK A: the client actually calls each route ----------------------------------------------
for m in listInvites revokeInvite removeMember acceptInvite; do
  grep -q "  $m: " src/lib/api.ts || {
    echo "VERDICT: broken — api.workspaceMembers.$m is gone; that route is unreachable again (F-0443)"
    exit 1; }
done
echo "· api client exposes listInvites, revokeInvite, removeMember, acceptInvite"

# --- CHECK B: the surfaces are MOUNTED / ROUTED -------------------------------------------------
# A panel nothing renders and a page nothing routes leave the defect exactly where it was — that is
# the entire shape of this finding, not a detail.
grep -q "team-members-panel" "$SETTINGS" || {
  echo "VERDICT: broken — brand settings no longer imports the team panel (F-0443)"; exit 1; }
grep -q "<TeamMembersPanel />" "$SETTINGS" || {
  echo "VERDICT: broken — the team panel is imported but never rendered (F-0443)"; exit 1; }
grep -q 'path="/brand/invite"' "$APP" || {
  echo "VERDICT: broken — /brand/invite is not routed, so an invite cannot be redeemed (F-0443)"
  exit 1; }
echo "· the panel is mounted in brand settings and /brand/invite is routed"

# --- CHECK C: no fabricated controls -------------------------------------------------------------
# Guarding the two things a good-faith author would most plausibly add next, both of which the
# backend cannot serve: a role dropdown (no PATCH .../role mapping exists — F-0686) and a workspace
# switcher (no endpoint lists a user's workspaces — F-0685).
# Matched on CALL-SITE IDENTIFIERS, not the URL string. The first version of this check grepped
# for "members/switch" and failed on the panel's own javadoc explaining why the switcher was
# deliberately NOT built — a comment, not a control. That is the same false positive the F-0669
# gate shipped with, reproduced here within the hour, which is why it is written down.
if grep -qE "switchWorkspace|workspaceMembers\.switch|'/workspace/members/switch'" "$PANEL" "$PAGE" 2>/dev/null; then
  echo "VERDICT: broken — a workspace switcher was wired, but nothing enumerates the workspaces a"
  echo "         user belongs to, so its options can only be fabricated (F-0685)"
  exit 1
fi
if grep -qE "/role'|/role\"|changeRole" "$PANEL" 2>/dev/null; then
  echo "VERDICT: broken — a role-change control was wired, but WorkspaceMemberController has no"
  echo "         PATCH /workspace/members/{id}/role mapping; it can only 404 (F-0686)"
  exit 1
fi
echo "· no control is wired to an endpoint the backend does not serve"

# --- CHECK D: the canonical scanner, scoped to THIS controller -----------------------------------
CLASS_GATE=.proof-os/gates/unreachable-endpoint.py
if [ -f "$CLASS_GATE" ] && command -v python >/dev/null 2>&1; then
  out=$(python "$CLASS_GATE" 2>&1) || true
  if ! printf '%s' "$out" | grep -q "no frontend caller"; then
    echo "· the scanner reported no unreachable endpoints at all, which contradicts the known open"
    echo "  findings — treating it as a broken instrument, not a pass"
    exit 2
  fi
  wm=$(printf '%s' "$out" | grep "WorkspaceMemberController" || true)
  n=$(printf '%s' "$wm" | grep -c "no frontend caller" || true)
  printf '%s\n' "$wm" | sed 's/^/    /'
  if [ "$n" -gt 1 ]; then
    echo "VERDICT: broken — $n WorkspaceMemberController routes have no frontend caller. Only"
    echo "         /switch is a permitted exception (F-0685); anything else is F-0443 returning"
    exit 1
  fi
  if [ "$n" -eq 1 ] && ! printf '%s' "$wm" | grep -q "members/switch"; then
    echo "VERDICT: broken — the one unreachable route is NOT /switch, so a route that used to work"
    echo "         has regressed (F-0443)"
    exit 1
  fi
  echo "· canonical scanner: only /workspace/members/switch remains, as documented"
else
  echo "· scanner unavailable — REACHABILITY NOT INDEPENDENTLY CHECKED"
fi

# --- CHECK E: tests tracked and green ------------------------------------------------------------
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  for t in "$PANEL_TEST" "$PAGE_TEST"; do
    git ls-files --error-unmatch "$t" >/dev/null 2>&1 || {
      echo "VERDICT: broken — $t is not git-tracked; it proves nothing on a fresh clone (F-0324)"
      exit 1; }
  done
  echo "· both test files are git-tracked"
fi
command -v npx >/dev/null 2>&1 || { echo "· npx unavailable — BEHAVIOUR NOT PROVED"; exit 2; }
[ -f package.json ] || { echo "· no package.json here — BEHAVIOUR NOT PROVED"; exit 2; }
log=$(mktemp 2>/dev/null || echo "/tmp/f0443.$$")
npx vitest run --reporter=basic "$PANEL_TEST" "$PAGE_TEST" >"$log" 2>&1; trc=$?
if grep -qE "Cannot find module|Failed to load|ERR_MODULE_NOT_FOUND" "$log"; then
  echo "· the suites could not load — unavailable, NOT a pass"; tail -12 "$log"; exit 2; fi
if [ "$trc" -ne 0 ]; then
  echo "VERDICT: broken — the F-0443 regression tests do not pass"
  sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "×|Tests " | head -12; exit 1; fi
sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "Tests |Test Files " | head -2

echo "VERDICT: aligned (proved) — a brand can now add a colleague through the product: the Team tab"
echo "         lists members and invites, sends an invite, revokes one and removes a member, and"
echo "         /brand/invite redeems the token. Both surfaces are mounted/routed rather than merely"
echo "         written, the canonical scanner confirms every route but /switch now has a caller,"
echo "         and no control is wired to an endpoint the backend cannot serve."
echo "NOT CHECKED: this gate proves the calls are WIRED, not that the server accepts them — every"
echo "             test stubs the transport, and no run of this suite has ever reached a live"
echo "             backend (vitest pins mock mode repo-wide). The flow has NOT been exercised"
echo "             end-to-end against Spring, and browser verification was blocked: .env.local"
echo "             pins VITE_API_MODE=live against localhost:8080, so with no backend running the"
echo "             auth guard redirects /brand/settings to login. Also unchecked: whether the"
echo "             invite EMAIL is actually delivered (NotificationService's territory), and the"
echo "             role gating here is UX only — the server remains the authority."
exit 0
