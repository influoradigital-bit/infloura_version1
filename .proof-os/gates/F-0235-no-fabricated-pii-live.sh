#!/usr/bin/env bash
# gates/F-0235-no-fabricated-pii-live.sh
# origin failure: F-0235 (fabricated-pii-live) — a hardcoded person's name, phone and street
# address sat in useState with no isApiLive() guard and was POSTed to the live shipment endpoint.
#
# F-0329 (this gate's OWN defect, repaired here). Ledger record F-0235 is CLOSED against this
# file, and this file could not fail. Its three assertions were:
#     1. no `useState.*(Sea View|Carter Road|9876543210)` on one line
#     2. if the literal exists, a MOCK_-prefixed binding must exist somewhere
#     3. `api.shipments.get|fetchLiveShipment` must appear somewhere
# Assertion 1 became UNSATISFIABLE the moment the fix moved the literal into a module constant:
# no useState line can ever carry the literal again, so it can never fire. Assertions 2 and 3 are
# satisfied BY the fix and stay satisfied no matter what the code does with the fixture.
# Reintroducing F-0235 — making the LIVE-mode effect that reflects GET /deals/:id/shipment fall
# back to `setShippingAddress(MOCK_SHIPPING_ADDRESS)` when no record exists — left this gate at
# exit 0, VERDICT: aligned. Reproduced at .proof-os/tasks/T-F0329-GATES/F-0235.inject.log.
#
# THE CLASS. All three old assertions ask WHERE A TOKEN APPEARS. F-0235 is not about where a
# token appears — the fixture is supposed to exist. It is about WHICH CODE PATHS CAN READ IT.
# That is a reachability property, and no grep can express one. So this gate no longer greps:
# it parses brand-chat.tsx with the project's own TypeScript compiler and proves, for every read
# of the fixture binding, that the read is DOMINATED by a not-live guard (`isApiLive() ? … : HERE`,
# `if (!isApiLive()) { HERE }`, `!isApiLive() && HERE`, or an `if (isApiLive()) return;` earlier
# in the same block). A read whose guard cannot be PROVED is reported as live-reachable — an
# unprovable guard is not a passing guard.
#
# And because a harness can rot the way those greps did, the gate first runs the very same
# analyser against three KNOWN-BAD implementations frozen into this file — the F-0235 defect
# verbatim, the F-0329 injection, and an unguarded fixture read — plus one KNOWN-GOOD one, and
# REFUSES TO CERTIFY unless it rejects all three bad and accepts the good. A gate that cannot
# fail is worse than no gate; one that cannot pass is a false-red machine. This proves both, on
# every run, before it believes itself.
#
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory: ${1:-.} — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }
F=src/pages/brand-chat.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }

ANALYSER="$SELF/_pii_guard.js"
[ -f "$ANALYSER" ] || { echo "· gates/_pii_guard.js missing — unavailable"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
TSDIR=node_modules/typescript
[ -f "$TSDIR/package.json" ] || {
  echo "· $TSDIR absent — this gate parses the component, it does not grep it — unavailable"; exit 2; }

WORK="$(mktemp -d 2>/dev/null)" || { echo "· cannot create a scratch dir — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT
TSABS="$(cd "$TSDIR" && pwd)"

# ---------------------------------------------------------------------------
# 1 · SELF-FALSIFICATION. Prove the analyser can both fail and pass, against
#     implementations frozen into this gate, before it is allowed an opinion
#     about the real file.
# ---------------------------------------------------------------------------
FIXTURE_LINES='const MOCK_SHIPPING_ADDRESS = {
  fullName: '"'"'Priya Sharma'"'"', phone: '"'"'9876543210'"'"',
  addressLine1: '"'"'402, Sea View Apartments, Carter Road'"'"', addressLine2: '"'"'Bandra West'"'"',
  city: '"'"'Mumbai'"'"', state: '"'"'Maharashtra'"'"', pincode: '"'"'400050'"'"', landmark: '"'"'Opposite Joggers Park'"'"',
};'

# BAD 1 — the F-0329 injection: the LIVE-only effect falls back to the fixture when the real
# GET /deals/:id/shipment record is absent or still AWAITING_ADDRESS. This is what the old
# gate certified as aligned.
{ printf '%s\n' "$FIXTURE_LINES"; cat <<'TSX'
export default function Page() {
  const [shippingAddress, setShippingAddress] = React.useState(isApiLive() ? null : MOCK_SHIPPING_ADDRESS);
  const [liveShipment, setLiveShipment] = React.useState(null);
  React.useEffect(() => {
    if (!isApiLive()) return;
    void api.shipments.get('brand', dealId).then(setLiveShipment);
  }, [dealId]);
  React.useEffect(() => {
    if (!isApiLive()) return;
    if (!liveShipment || liveShipment.status === 'AWAITING_ADDRESS') {
      setShippingAddress(MOCK_SHIPPING_ADDRESS);
      return;
    }
    setShippingAddress({ fullName: liveShipment.recipientName });
  }, [liveShipment]);
  return <ShipmentForm address={shippingAddress} />;
}
TSX
} > "$WORK/bad1.tsx"

# BAD 2 — F-0235 verbatim: the address inline in useState with no guard and no MOCK_ binding.
cat > "$WORK/bad2.tsx" <<'TSX'
export default function Page() {
  const [shippingAddress] = React.useState({
    fullName: 'Priya Sharma', phone: '9876543210',
    addressLine1: '402, Sea View Apartments, Carter Road', addressLine2: 'Bandra West',
    city: 'Mumbai', state: 'Maharashtra', pincode: '400050', landmark: 'Opposite Joggers Park',
  });
  React.useEffect(() => { void api.shipments.get('brand', dealId); }, []);
  return <ShipmentForm address={shippingAddress} />;
}
TSX

# BAD 3 — the halfway "fix": the literal is moved into a MOCK_ constant (satisfying the old
# gate's assertions 1 and 2 in full) but the seed is not guarded at all.
{ printf '%s\n' "$FIXTURE_LINES"; cat <<'TSX'
export default function Page() {
  const [shippingAddress] = React.useState(MOCK_SHIPPING_ADDRESS);
  React.useEffect(() => { void api.shipments.get('brand', dealId); }, []);
  return <ShipmentForm address={shippingAddress} />;
}
TSX
} > "$WORK/bad3.tsx"

# GOOD — the shape the fix actually has: seeded only on the demo branch, live mode seeded null
# and only ever written from a real record.
{ printf '%s\n' "$FIXTURE_LINES"; cat <<'TSX'
export default function Page() {
  const [shippingAddress, setShippingAddress] = React.useState(isApiLive() ? null : MOCK_SHIPPING_ADDRESS);
  React.useEffect(() => {
    if (!isApiLive()) return;
    void api.shipments.get('brand', dealId).then((r) => setShippingAddress(r ? { fullName: r.recipientName } : null));
  }, [dealId]);
  return <ShipmentForm address={shippingAddress} />;
}
TSX
} > "$WORK/good.tsx"

echo "· self-check: the reachability analyser rejects three known-bad shapes and accepts one known-good"
selfbroken=0
for b in bad1 bad2 bad3; do
  out=$(node "$ANALYSER" "$WORK/$b.tsx" "$TSABS" 2>&1); rc=$?
  case "$b" in
    bad1) label="F-0329 injection — live-mode fallback to the fixture" ;;
    bad2) label="F-0235 verbatim — fixture inline in useState, unguarded" ;;
    bad3) label="halfway fix — MOCK_ constant exists but the read is unguarded" ;;
  esac
  if [ $rc -eq 2 ]; then
    printf '%s\n' "$out" | sed 's/^/    /'
    echo "· the analyser could not run on its own frozen $b — unavailable"; exit 2
  fi
  if [ $rc -eq 0 ]; then
    echo "  ACCEPTED a known-bad: $label"
    printf '%s\n' "$out" | sed 's/^/    /'
    selfbroken=1
  fi
done
out=$(node "$ANALYSER" "$WORK/good.tsx" "$TSABS" 2>&1); rc=$?
if [ $rc -eq 2 ]; then
  printf '%s\n' "$out" | sed 's/^/    /'
  echo "· the analyser could not run on its own frozen good shape — unavailable"; exit 2
fi
if [ $rc -ne 0 ]; then
  echo "  REJECTED the known-good shape — this analyser would fail a correct fix (false red)"
  printf '%s\n' "$out" | sed 's/^/    /'
  selfbroken=1
fi
if [ $selfbroken -eq 1 ]; then
  echo "· THIS GATE CANNOT BE TRUSTED: its own analyser did not separate the F-0235 defect from"
  echo "  the F-0235 fix. Refusing to report a verdict about the real code from a check that has"
  echo "  just proved itself blind."
  echo "VERDICT: broken — the F-0235 gate's analyser no longer detects F-0235 (F-0329)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
echo "  good — all three known-bad rejected, known-good accepted, so a green below means something"

# ---------------------------------------------------------------------------
# 2 · the real file. Same analyser, no exceptions.
# ---------------------------------------------------------------------------
echo "· $F — every read of the fabricated address fixture, checked for a provable not-live guard"
out=$(node "$ANALYSER" "$F_CODE" "$TSABS" 2>&1); rc=$?
printf '%s\n' "$out" | sed "s|$(printf '%s' "$F_CODE" | sed 's/[][\.*^$/]/\\&/g')|$F|g"
if [ $rc -eq 2 ]; then
  echo "· the analyser could not analyse $F — unavailable"; exit 2
fi
if [ $rc -ne 0 ]; then
  echo "VERDICT: broken (F-0235 regressed) — a live-mode path can read the fabricated shipping"
  echo "         address, so the brand can be shown a stranger's name, phone and street address"
  echo "         as their creator's delivery details and POST them to the real shipment endpoint"
  echo "NOT CHECKED: whether the fetched address is the RIGHT one, runtime behaviour, or PII in"
  echo "             files other than $F"
  exit 1
fi

echo "VERDICT: aligned (proved) — brand-chat.tsx was PARSED, not grepped: the fabricated address"
echo "         exists only inside a MOCK_-prefixed module constant, every read of that constant is"
echo "         dominated by a provable not-live guard, its phone/street values are not re-inlined"
echo "         anywhere else in the file, and the live path fetches a real shipment record. The"
echo "         analyser was proved able to reject the F-0235 defect, the F-0329 injection and an"
echo "         unguarded fixture read — and to accept a correct fix — before any of that was believed."
echo "NOT CHECKED: whether the fetched address is the RIGHT one (that it belongs to THIS deal's"
echo "             creator); runtime behaviour, including whether a guard this analyser proved"
echo "             statically actually holds at render time; PII in files other than $F, including"
echo "             ShipmentForm itself; guard shapes the analyser cannot prove and therefore treats"
echo "             as unguarded (a correct but exotic guard would read here as a finding); the"
echo "             fullName/city/state/pincode fixture values, which are excluded from the"
echo "             re-inlining check because they legitimately recur in unrelated demo data."
exit 0
