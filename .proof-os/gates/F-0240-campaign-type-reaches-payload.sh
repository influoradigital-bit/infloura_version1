#!/usr/bin/env bash
# gates/F-0240-campaign-type-reaches-payload.sh
# origin failure: F-0240 (silent-type-discard) — the picker captured selectedType but never
# passed it to CampaignForm, so "Direct Deal" silently created a STANDARD campaign, immutably.
#
# F-0329 (this gate's OWN defect, repaired here). Ledger record F-0240 is CLOSED against this
# file, and until now this file could not fail. Its page leg was
#     grep -q "campaignType: selectedType" "$P_CODE"
# — a free-floating literal search. The most ordinary wrong fix there is keeps that literal and
# severs the wire anyway:
#     const pickedTypeValues = selectedType ? { campaignType: selectedType } : undefined;
#     <CampaignForm initialValues={templateInitialValues ?? undefined} />
# The local is dead, Direct Deal creates an OPEN campaign again, and the pre-repair gate said
# "VERDICT: aligned (proved)". Observed at exit 0, recorded in
# .proof-os/tasks/T-F0329-GATES/F-0240.inject.log. That is the F-0319 shape exactly: a single-site
# literal is a snapshot of today's text, not a property, and an unrelated later edit can make it
# permanently true.
#
# THE REPAIR, two legs.
#  1. EXECUTION. .proof-os/gates/F-0240.reaches-payload.spec.tsx renders the real picker, clicks
#     "Direct Deal", and asserts the type the brand picked is what CampaignForm is actually
#     handed. A detached literal cannot satisfy a render. That spec carries its own
#     self-falsification test (a frozen known-bad picker the same assertion must reject).
#  2. STRUCTURE. _f0240_chain.py checks the three links of the chain the execution leg only
#     covers the first of — the initialValues attribute VALUE, the formData useState ARGUMENT,
#     and the payload's campaignType VALUE. Each is extracted from its syntactic position, so a
#     token elsewhere in the file satisfies none of them. And, per F-0273, that assertion table
#     is run against FROZEN KNOWN-BAD sources first: if it certifies any of them, this gate says
#     it cannot fail and refuses to report a verdict.
#
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

P=src/pages/brand-new-campaign.tsx
C=src/components/brand/campaigns/campaign-form.tsx
CHAIN="$SELF/_f0240_chain.py"
SPEC="$SELF/F-0240.reaches-payload.spec.tsx"
[ -f "$P" ] && [ -f "$C" ] || { echo "· source files missing — unavailable"; exit 2; }
[ -f "$CHAIN" ] || { echo "· $CHAIN missing — the assertion table is gone — unavailable"; exit 2; }
C_CODE=$(code_view "$C") || { echo "$(code_why) - unavailable"; exit 2; }
P_CODE=$(code_view "$P") || { echo "$(code_why) - unavailable"; exit 2; }

PY=""
for c in "${PROOF_PYTHON:-}" python3 python py; do
  [ -n "$c" ] || continue
  command -v "$c" >/dev/null 2>&1 || continue
  "$c" -c "import sys" >/dev/null 2>&1 && { PY="$c"; break; }
done
[ -n "$PY" ] || { echo "· no working python interpreter — unavailable"; exit 2; }

WORK="$(mktemp -d 2>/dev/null)" || { echo "· cannot create a scratch dir — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT

# ---------------------------------------------------------------------------
# 1 · SELF-FALSIFICATION. Frozen known-bad sources, run through the SAME assertion
#     table that is about to judge the real tree. Two of these are F-0240 verbatim
#     at different links; one is the wrong fix that defeated the old gate.
# ---------------------------------------------------------------------------
echo "· self-check: the chain assertions reject frozen known-bad sources"

# KB-A — the wrong fix that greened the OLD gate: literal present, wire cut.
cat > "$WORK/kb-a-page.tsx" <<'EOF'
export default function Page() {
  const [selectedType, setSelectedType] = React.useState<CampaignType | null>(null);
  if (selectedType || templateInitialValues) {
    const pickedTypeValues = selectedType ? { campaignType: selectedType } : undefined;
    return <CampaignForm initialValues={templateInitialValues ?? undefined} />;
  }
  return null;
}
EOF
# KB-B — F-0240 verbatim at link 1: nothing is handed to the form at all.
cat > "$WORK/kb-b-page.tsx" <<'EOF'
export default function Page() {
  const [selectedType, setSelectedType] = React.useState<CampaignType | null>(null);
  if (selectedType) {
    return <CampaignForm />;
  }
  return null;
}
EOF
# KB-C — link 2 severed: the form is handed the type and drops it on arrival.
cat > "$WORK/kb-c-form.tsx" <<'EOF'
export interface CampaignFormData { campaignType?: CampaignType; title: string; }
export function CampaignForm({ initialValues }: { initialValues?: Partial<CampaignFormData> }) {
  const [formData, setFormData] = React.useState<CampaignFormData>(() => initialFormData);
  const handleSubmit = async () => {
    const payload = {
      title: formData.title,
      campaignType: isEditing ? undefined : formData.campaignType,
    };
  };
}
EOF
# KB-D — link 3 severed: state holds the type, the payload never sends it.
cat > "$WORK/kb-d-form.tsx" <<'EOF'
export interface CampaignFormData { campaignType?: CampaignType; title: string; }
export function CampaignForm({ initialValues }: { initialValues?: Partial<CampaignFormData> }) {
  const [formData, setFormData] = React.useState<CampaignFormData>(() =>
    initialValues ? { ...initialFormData, ...initialValues } : initialFormData,
  );
  const handleSubmit = async () => {
    const payload = {
      title: formData.title,
      status,
    };
  };
}
EOF
# KB-GOOD — a minimal INTACT chain. If the table rejects this, the table is broken
# (a gate that can only say "broken" is a false-red machine, not a proof).
cat > "$WORK/kb-good-page.tsx" <<'EOF'
export default function Page() {
  if (selectedType || templateInitialValues) {
    return (
      <CampaignForm
        initialValues={templateInitialValues ?? (selectedType ? { campaignType: selectedType } : undefined)}
      />
    );
  }
  return null;
}
EOF
cat > "$WORK/kb-good-form.tsx" <<'EOF'
export interface CampaignFormData { campaignType?: CampaignType; title: string; }
export function CampaignForm({ initialValues }: { initialValues?: Partial<CampaignFormData> }) {
  const [formData, setFormData] = React.useState<CampaignFormData>(() =>
    initialValues ? { ...initialFormData, ...initialValues } : initialFormData,
  );
  const handleSubmit = async () => {
    const payload = {
      title: formData.title,
      campaignType: isEditing ? undefined : formData.campaignType,
    };
  };
}
EOF

kb_view() { code_view "$1"; }
selfcheck_fail=0
run_chain() { "$PY" "$CHAIN" "$1" "$2" 2>&1; }

GOOD_P=$(kb_view "$WORK/kb-good-page.tsx") || { echo "$(code_why) - unavailable"; exit 2; }
GOOD_F=$(kb_view "$WORK/kb-good-form.tsx") || { echo "$(code_why) - unavailable"; exit 2; }
for kb in "kb-a-page.tsx:the OLD gate's wrong fix — literal kept, wire cut" \
          "kb-b-page.tsx:F-0240 verbatim — no initialValues at all"; do
  f="${kb%%:*}"; why="${kb#*:}"
  v=$(kb_view "$WORK/$f") || { echo "$(code_why) - unavailable"; exit 2; }
  out=$(run_chain "$v" "$GOOD_F"); rc=$?
  if [ $rc -eq 0 ]; then
    echo "  ACCEPTED known-bad [$f] ($why)"; selfcheck_fail=1
  fi
done
for kb in "kb-c-form.tsx:initialValues dropped on arrival" \
          "kb-d-form.tsx:campaignType never put in the payload"; do
  f="${kb%%:*}"; why="${kb#*:}"
  v=$(kb_view "$WORK/$f") || { echo "$(code_why) - unavailable"; exit 2; }
  out=$(run_chain "$GOOD_P" "$v"); rc=$?
  if [ $rc -eq 0 ]; then
    echo "  ACCEPTED known-bad [$f] ($why)"; selfcheck_fail=1
  fi
done
if [ $selfcheck_fail -eq 1 ]; then
  echo "· THIS GATE CANNOT FAIL: its own assertion table certified a source that reintroduces"
  echo "  F-0240. Refusing to report a verdict about the real code from a check that has just"
  echo "  proved itself blind."
  echo "VERDICT: broken — the F-0240 gate's assertions no longer detect F-0240 (F-0329)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
good_out=$(run_chain "$GOOD_P" "$GOOD_F"); good_rc=$?
if [ $good_rc -ne 0 ]; then
  printf '%s\n' "$good_out" | sed 's/^/  /'
  echo "· the assertion table rejects a KNOWN-GOOD intact chain — the table itself is broken,"
  echo "  so nothing it says about the real tree can be trusted — unavailable"
  exit 2
fi
echo "  good — 4 known-bad sources rejected, 1 known-good accepted; a green below means something"

# ---------------------------------------------------------------------------
# 2 · the chain, over the real tree.
# ---------------------------------------------------------------------------
echo "· chain: picker → CampaignForm initialValues → formData state → create payload"
chain_out=$("$PY" "$CHAIN" "$P_CODE" "$C_CODE" 2>&1); chain_rc=$?
if [ $chain_rc -eq 2 ]; then
  printf '%s\n' "$chain_out" | sed 's/^/  /'
  echo "· the chain checker could not read the code views — unavailable"; exit 2
fi
if [ $chain_rc -ne 0 ]; then
  printf '%s\n' "$chain_out" | sed 's/^/  /'
  echo "VERDICT: broken (F-0240 regressed) — the campaign type the brand picks does not reach"
  echo "         POST /campaigns, so the backend default silently replaces their choice"
  echo "NOT CHECKED: the render leg below was not reached; the template-apply path (F-0251, still"
  echo "             open); the backend enum mapping"
  exit 1
fi
echo "  intact"

# ---------------------------------------------------------------------------
# 3 · EXECUTION. The link the old gate got wrong, proved by rendering it.
# ---------------------------------------------------------------------------
echo "· vitest: F-0240.reaches-payload.spec.tsx — click Direct Deal, see what the form is handed"
[ -f "$SPEC" ] || { echo "· $SPEC missing — the execution leg is gone — unavailable"; exit 2; }
if [ ! -x node_modules/.bin/vitest ] && [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: the rendered handoff. The chain legs above DID pass; this run cannot prove"
  echo "             what the picker actually hands over at runtime."
  exit 2
fi
# F-0334: gate fixtures live under .proof-os/gates/, which vitest.config.ts now EXCLUDES so
# they are not swept into the product suite or into build.sh's `npm test` leg. vitest applies
# `exclude` even to an explicitly-passed path, so the spec is run under gates/vitest.gates.config.ts
# — the project config with that single exclusion removed, derived from it at load time.
GATES_CFG="$SELF/vitest.gates.config.ts"
[ -f vitest.config.ts ] || { echo "· vitest.config.ts missing — unavailable"; exit 2; }
[ -f "$GATES_CFG" ] || { echo "· $GATES_CFG missing — the gate fixture cannot be collected — unavailable"; exit 2; }
BUDGET="${PROOF_F0240_VITEST_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
out=$($TO node_modules/.bin/vitest run --config "$GATES_CFG" --root . "$SPEC" --reporter=basic 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: the rendered handoff; the chain legs above DID pass"
  exit 2
fi
if printf '%s' "$out" | grep -q "No test files found"; then
  echo "  vitest collected no test file for $SPEC — unavailable"
  echo "NOT CHECKED: the rendered handoff; the chain legs above DID pass"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken (F-0240 regressed) — the type the brand picks is not what CampaignForm"
  echo "         is handed when the picker is actually clicked"
  echo "NOT CHECKED: the template-apply path (F-0251, still open); the backend enum mapping"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — the picker was RENDERED and clicked: choosing Direct Deal hands"
echo "         CampaignForm initialValues.campaignType='DIRECT' (and Open Campaign 'OPEN'), and"
echo "         the three links of the chain — the initialValues attribute value, the formData"
echo "         useState argument, and the payload's campaignType value — each reference the"
echo "         previous link from their own syntactic position, so no stray literal can stand in"
echo "         for any of them. Both the assertion table and the spec were proved falsifiable"
echo "         against frozen known-bad sources before any of this was believed."
echo "NOT CHECKED: the template-apply path (F-0251, still open) — a template-applied campaign"
echo "             never goes through the picker and this gate does not assert what type it gets;"
echo "             the backend CAMPAIGN_TYPE_TO_API mapping and CampaignService.create's own"
echo "             null default; that campaignType is correctly withheld on an edit PATCH (the"
echo "             immutability guard is asserted nowhere here); the HYPE branch, which navigates"
echo "             away to its own flow; runtime behaviour against a live backend."
exit 0
