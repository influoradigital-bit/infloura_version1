#!/usr/bin/env bash
# gates/F-0238-no-fabricated-contract-pdf.sh
# origin failure: F-0238 (fabricated-legal-document) — on any failed pdfDownloadUrl fetch the
# client generated a contract PDF from invented data (brandName 'Your Brand', invented
# deliverables, a now+14-day deadline) and toasted only "Opened a local copy". Nothing told the
# brand the document in their hands was made up on their own machine.
#
# ---------------------------------------------------------------------------------------------
# F-0329 (THIS GATE'S OWN DEFECT, repaired here). Ledger record F-0238 is CLOSED against this
# file, and until now this file proved nothing. It was two greps over the comment-stripped view:
#
#     grep -q  "Opened a local copy"   -> must be ABSENT
#     grep -q  "demoContractData"      -> must be PRESENT
#
# Both are about text, and the second is about an identifier that exists in the same file for a
# legitimate unrelated reason (the demo-mode PDF payload). Its presence therefore cannot
# distinguish a demo-scoped payload from one leaking into the live path — which is the entire
# question F-0238 asks.
#
# OBSERVED FALSIFICATION (.proof-os/tasks/T-F0329-GATES/F-0238.inject.log). The wrong fix a
# competent engineer would actually commit for "Download PDF just errors before both parties
# sign": hoist demoContractData to the top of handleDownloadPDF and, in the LIVE catch, call
#     downloadContractPDF(demoContractData, `${contractId}.pdf`)
# toasting a non-destructive "Draft contract ready". That is F-0238 verbatim with new copy. The
# forbidden string was gone (the wrong fix removes the stale comment too) and demoContractData
# was MORE present than before, so both legs were satisfied. Observed exit 0, VERDICT: aligned.
#
# No vitest suite covered handleDownloadPDF at all — as the ledger's missed_by says, this error
# path is silent by construction, and it was still untested.
#
# THE REPAIR. Whether a fabricated document reaches the brand is a fact about what the click
# DOES, so this gate mounts the panel and CLICKS Download PDF over four wirings of the API:
#   · live + the fetch rejects       -> downloadContractPDF called ZERO times, window.open never
#                                       called, and the brand gets a destructive (failure) toast
#   · live + an EMPTY downloadUrl    -> the same; api.ts documents this as the shape that used to
#                                       open about:blank and look like it worked
#   · live + a real presigned URL    -> THAT sentinel URL is what opens, and still no local
#                                       document is generated
#   · demo mode                      -> the local document IS generated (so "delete the generator"
#                                       or "disable the button" is not applauded as a fix)
#
# And because a harness rots the way those greps did, the gate runs that assertion table FIRST
# against a KNOWN-BAD panel frozen into this file — the wrong fix above — and requires the two
# fabrication legs (L1/L2), and only those, to reject it. If the table certifies the known-bad,
# the gate reports THAT IT CANNOT FAIL rather than reporting on the real code. (Device copied
# from gates/F-0273-frozen-escrow-counts-as-locked.sh.)
#
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
# SELF resolved BEFORE the cd below (F-0025/F-0026 convention) — once we cd into the target
# project, a relative $0 can no longer locate our own directory.
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

# F-0266: look at CODE, not file bytes. Only a precondition here — the proof is the click below,
# and this leg is allowed to prove nothing on its own.
. "$SELF/_code.sh" 2>/dev/null || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
code_ready || { echo "· $(code_why) — unavailable"; exit 2; }

F=src/components/brand/deal-room/deal-contract-tab.tsx
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "· $(code_why) — unavailable"; exit 2; }
if ! grep -q "export function DealContractTab" "$F_CODE"; then
  echo "· $F no longer exports DealContractTab in code — this gate cannot mount the panel it"
  echo "  exists to judge, and will not guess at a replacement — unavailable"
  exit 2
fi

if [ ! -x node_modules/.bin/vitest ] && [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — this gate is a behaviour gate and has nothing"
  echo "  static to fall back to (falling back to greps is the defect being repaired) — unavailable"
  exit 2
fi

PY=""
for _c in "${PROOF_PYTHON:-}" python3 python py; do
  [ -n "$_c" ] || continue
  command -v "$_c" >/dev/null 2>&1 || continue
  # `command -v` only proves a name is on PATH. A Windows App Execution Alias resolves and then
  # fails at exec time, so every candidate is probed with a real invocation (_code.sh, same trap).
  "$_c" -c "import sys" >/dev/null 2>&1 && { PY="$_c"; break; }
done
[ -n "$PY" ] || { echo "· no working python interpreter to read the vitest JSON report — unavailable"; exit 2; }

# ---------------------------------------------------------------------------
# 0 · a scratch directory INSIDE the project. Outside it the '@' alias and src/test/setup.ts do
#     not apply and the specs would fail for reasons unrelated to the product (a fixture living
#     outside the project is how a gate false-greens; T-FIXWAVE-0815). It lives under gates/,
#     which this gate owns. An EXIT trap removes it, and every run reaps leftovers whose owning
#     PID is gone — a stray *.test.tsx under .proof-os/ WOULD be collected by a repo-wide
#     `vitest run` (vitest.config.ts excludes node_modules/dist/e2e/.claude, not .proof-os).
# ---------------------------------------------------------------------------
for _old in "$SELF"/_work-F0238.*; do
  [ -d "$_old" ] || continue
  _pid=${_old##*_work-F0238.}
  case "$_pid" in ''|*[!0-9]*) continue ;; esac
  kill -0 "$_pid" 2>/dev/null || rm -rf "$_old" 2>/dev/null
done
WORK="$SELF/_work-F0238.$$"
rm -rf "$WORK" 2>/dev/null
mkdir -p "$WORK" || { echo "· cannot create a scratch dir under gates/ — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT
WORK_REL=".proof-os/gates/_work-F0238.$$"

# ---------------------------------------------------------------------------
# 1 · THE ASSERTION TABLE. One file, applied unchanged to both implementations.
# ---------------------------------------------------------------------------
cat > "$WORK/_table.tsx" <<'TSX'
/* Written by .proof-os/gates/F-0238-no-fabricated-contract-pdf.sh; deleted on exit.
 * Applied UNCHANGED to the real panel and to a known-bad panel frozen from the F-0238 defect.
 * It counts what the CLICK actually does — how many locally generated documents were handed to
 * the brand — which no arrangement of identifiers in the source can fake. */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as React from 'react';

export type Scenario = { live: boolean; pdf: 'reject' | 'empty' | 'ok' };
export type Mount = (s: Scenario) => Promise<void>;

export const SENTINEL_URL = 'https://r2.example.invalid/SENTINEL-PRESIGNED-CONTRACT.pdf';

export const downloadSpy = vi.fn();
export const toastSpy = vi.fn();
const openHolder: { spy: ReturnType<typeof vi.fn> } = { spy: vi.fn() };
export const openSpy = () => openHolder.spy;

const baseProps = {
  dealId: 'deal-1',
  creatorName: 'Priya Sharma',
  campaignName: 'Summer Launch',
  dealValue: 50000,
  contractId: 'CTR-1',
  status: 'brand_signed' as const,
  onStatusChange: () => {},
};

const RECORD = {
  id: 'CTR-1',
  collaborationId: 'collab-1',
  status: 'PENDING_SIGNATURES',
  totalAmount: 50000,
  currency: 'INR',
  brandSignedAt: '2026-08-01T00:00:00Z',
  creatorSignedAt: null,
  terms: 'SENTINEL-TERMS on file.',
  milestones: [],
};

/** Identical plumbing for both implementations, so the self-check runs the same path the real
 *  run does and not a friendlier one. */
export function makeMount(importPanel: () => Promise<React.ComponentType<never>>): Mount {
  return async (s: Scenario) => {
    vi.resetModules();
    downloadSpy.mockReset();
    toastSpy.mockReset();
    openHolder.spy = vi.fn(() => null);
    vi.spyOn(window, 'open').mockImplementation(openHolder.spy as never);

    vi.doMock('@/lib/contract-generator', async () => {
      const actual = await vi.importActual<typeof import('@/lib/contract-generator')>(
        '@/lib/contract-generator',
      );
      return { ...actual, downloadContractPDF: downloadSpy };
    });
    vi.doMock('@/hooks/use-toast', () => ({
      useToast: () => ({ toast: toastSpy, dismiss: vi.fn(), toasts: [] }),
      toast: toastSpy,
    }));
    vi.doMock('@/lib/api', async () => {
      const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
      return {
        ...actual,
        isApiLive: () => s.live,
        api: {
          ...actual.api,
          contracts: {
            ...actual.api.contracts,
            get: vi.fn(async () => RECORD),
            pdfDownloadUrl: vi.fn(async () => {
              if (s.pdf === 'reject') {
                throw new actual.ApiError(
                  'CONTRACT_PDF_NOT_READY', 'The contract PDF is not ready yet.', 404,
                );
              }
              if (s.pdf === 'empty') return { downloadUrl: '', expiresAt: '' };
              return { downloadUrl: SENTINEL_URL, expiresAt: '2026-01-01T00:00:00Z' };
            }),
          },
        },
      };
    });

    const Panel = await importPanel();
    render(<Panel {...(baseProps as never)} />);
  };
}

async function clickDownload() {
  const btn = await screen.findByRole('button', { name: /download pdf/i });
  await userEvent.click(btn);
  await waitFor(() =>
    expect(toastSpy.mock.calls.length + openSpy().mock.calls.length).toBeGreaterThan(0),
  );
}

const lastToast = () => (toastSpy.mock.calls.at(-1)?.[0] ?? {}) as Record<string, unknown>;

export function fabricatedPdfSuite(subject: string, mount: Mount) {
  describe('F-0238 no fabricated contract PDF - ' + subject, () => {
    beforeEach(() => { vi.restoreAllMocks(); });

    it('L1 - live, the PDF fetch FAILS: no locally generated document, and the brand is told', async () => {
      await mount({ live: true, pdf: 'reject' });
      await clickDownload();
      expect({
        localDocumentsHandedOver: downloadSpy.mock.calls.length,
        windowOpens: openSpy().mock.calls.length,
      }).toEqual({ localDocumentsHandedOver: 0, windowOpens: 0 });
      expect({ toasted: toastSpy.mock.calls.length > 0 }).toEqual({ toasted: true });
      // A failure has to LOOK like one. The F-0238 symptom was a reassuring message over a
      // fabricated document; with the document gone, the message is what is left to get right.
      expect({ variant: lastToast().variant, titleShown: lastToast().title })
        .toEqual({ variant: 'destructive', titleShown: lastToast().title });
    });

    it('L2 - live, the fetch RESOLVES AN EMPTY URL: same, not a silent no-op', async () => {
      await mount({ live: true, pdf: 'empty' });
      await clickDownload();
      expect({
        localDocumentsHandedOver: downloadSpy.mock.calls.length,
        windowOpens: openSpy().mock.calls.length,
      }).toEqual({ localDocumentsHandedOver: 0, windowOpens: 0 });
      expect({ variant: lastToast().variant }).toEqual({ variant: 'destructive' });
    });

    it('L3 - live and the real presigned URL exists: THAT is what opens', async () => {
      await mount({ live: true, pdf: 'ok' });
      await clickDownload();
      expect(openSpy()).toHaveBeenCalled();
      expect(String(openSpy().mock.calls.at(-1)?.[0])).toBe(SENTINEL_URL);
      expect({ localDocumentsHandedOver: downloadSpy.mock.calls.length })
        .toEqual({ localDocumentsHandedOver: 0 });
    });

    it('L4 - demo mode still produces its local document (no over-correction)', async () => {
      await mount({ live: false, pdf: 'ok' });
      await clickDownload();
      expect({ localDocumentsHandedOver: downloadSpy.mock.calls.length })
        .toEqual({ localDocumentsHandedOver: 1 });
    });
  });
}
TSX

# ---------------------------------------------------------------------------
# 2 · THE KNOWN-BAD PANEL, frozen: the wrong fix from F-0238.inject.log, verbatim.
# ---------------------------------------------------------------------------
cat > "$WORK/bad-panel.tsx" <<'TSX'
import * as React from 'react';
import { api, ApiError, isApiLive } from '@/lib/api';
import { downloadContractPDF } from '@/lib/contract-generator';
import { useToast } from '@/hooks/use-toast';

interface Props {
  contractId: string;
  creatorName: string;
  campaignName: string;
  dealValue: number;
}

export default function BadDealContractTab({
  contractId, creatorName, campaignName, dealValue,
}: Props) {
  const { toast } = useToast();
  const liveApi = isApiLive();
  const [busy, setBusy] = React.useState(false);

  const handleDownloadPDF = async () => {
    const demoContractData = {
      contractId,
      brandName: 'Your Brand',
      creatorName,
      campaignName,
      amount: dealValue,
      deliverables: [
        { title: 'Instagram Reel', description: 'Campaign content', quantity: 2 },
        { title: 'Instagram Story', description: 'Story series', quantity: 3 },
      ],
      deadline: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
      usageRights: '6 months on social media platforms',
      exclusivity: 'As per campaign brief',
      revisionCap: 2,
      customClauses: [],
      createdAt: new Date(),
    };

    if (liveApi) {
      setBusy(true);
      try {
        const { downloadUrl } = await api.contracts.pdfDownloadUrl('brand', contractId);
        if (!downloadUrl) throw new Error('No download URL returned');
        window.open(downloadUrl, '_blank', 'noopener,noreferrer');
      } catch (err) {
        void (err instanceof ApiError);
        downloadContractPDF(demoContractData, `${contractId}.pdf`);
        toast({
          title: 'Draft contract ready',
          description: 'Review the draft while the signed copy is being prepared.',
        });
      } finally {
        setBusy(false);
      }
      return;
    }

    downloadContractPDF(demoContractData, `${contractId}.pdf`);
    toast({ title: 'PDF downloaded', description: 'Review the contract before signing.' });
  };

  return (
    <button type="button" onClick={handleDownloadPDF} disabled={busy}>
      Download PDF
    </button>
  );
}
TSX

cat > "$WORK/bad.test.tsx" <<'TSX'
import { makeMount, fabricatedPdfSuite } from './_table';
fabricatedPdfSuite('the KNOWN-BAD panel (F-0238 frozen)', makeMount(async () =>
  (await import('./bad-panel')).default as never));
TSX

cat > "$WORK/real.test.tsx" <<'TSX'
import { makeMount, fabricatedPdfSuite } from './_table';
fabricatedPdfSuite('the real panel', makeMount(async () =>
  (await import('@/components/brand/deal-room/deal-contract-tab')).DealContractTab as never));
TSX

BUDGET="${PROOF_F0238_VITEST_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

# F-0334: this gate materialises its specs INSIDE .proof-os/gates/, and vitest.config.ts now
# excludes '**/.proof-os/**' so gate fixtures (and any temp spec a killed gate leaves behind)
# are not swept into the product suite or into build.sh's `npm test` leg. vitest applies
# `exclude` even to an explicitly-passed path, so these runs need the gates config — the project
# config with that one exclusion removed. Measured: without this, every run here reports
# "No test files found" and the gate goes unavailable.
GATES_CFG="$SELF/vitest.gates.config.ts"
[ -f "$GATES_CFG" ] || { echo "· $GATES_CFG missing — gate specs cannot be collected — unavailable"; exit 2; }

run_spec() {
  # shellcheck disable=SC2086
  $TO node_modules/.bin/vitest run --config "$GATES_CFG" --root . "$1" --reporter=json --outputFile="$2" >/dev/null 2>&1
  SPEC_RC=$?
}

# statuses <jsonfile> — one "<L1-L4> <passed|failed>" line per test, in declaration order.
statuses() {
  "$PY" - "$1" <<'PYEOF'
import json, io, sys
try:
    d = json.load(io.open(sys.argv[1], encoding='utf-8'))
except Exception as e:
    print('PARSE-ERROR ' + str(e)); raise SystemExit(3)
rs = d.get('testResults') or []
if not rs:
    print('PARSE-ERROR no testResults'); raise SystemExit(3)
for t in rs[0].get('assertionResults') or []:
    title = (t.get('title') or '?').strip()
    print(title.split(' ')[0] + ' ' + t.get('status', '?'))
PYEOF
}

# ---------------------------------------------------------------------------
# 3 · SELF-FALSIFICATION. Prove the table can fail, and that the FABRICATION legs are what
#     reject the known-bad rather than some incidental difference.
# ---------------------------------------------------------------------------
echo "· self-check: this assertion table rejects a panel that hands over a locally built contract"
run_spec "$WORK_REL/bad.test.tsx" "$WORK/bad.json"; bad_rc=$SPEC_RC
if [ $bad_rc -eq 124 ] || [ $bad_rc -eq 137 ]; then
  echo "  self-check exceeded ${BUDGET}s — unavailable, NOT a finding"; exit 2
fi
[ -f "$WORK/bad.json" ] || { echo "· vitest produced no JSON report for the self-check — unavailable"; exit 2; }
bad_status=$(statuses "$WORK/bad.json") || {
  printf '%s\n' "$bad_status" | sed 's/^/  /'
  echo "· cannot read the self-check report — unavailable"; exit 2; }
printf '%s\n' "$bad_status" | sed 's/^/    /'
bad_bite=$(printf '%s\n' "$bad_status" | grep -cE '^L[12] failed')
bad_over=$(printf '%s\n' "$bad_status" | grep -cE '^L[34] failed')
if [ "$bad_bite" -ne 2 ]; then
  echo "· THIS GATE CANNOT FAIL as intended: of the two FABRICATION assertions (L1/L2), only"
  echo "  $bad_bite reject a panel that generates a contract from invented values whenever the"
  echo "  server copy is missing. F-0238 is exactly that panel. Refusing to report a verdict"
  echo "  about the real code from a check that has just proved itself blind."
  echo "VERDICT: broken — the F-0238 gate's assertions no longer detect F-0238 (F-0329)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
if [ "$bad_over" -ne 0 ]; then
  echo "· this gate is mis-calibrated: $bad_over of the no-over-correction legs (L3/L4) also"
  echo "  reject the known-bad, which opens the real presigned URL when there is one and still"
  echo "  serves demo mode. Those legs are supposed to hold there; a red from them on the real"
  echo "  code would not mean what this gate says it means — unavailable"
  exit 2
fi
echo "  good — L1/L2 reject it and L3/L4 hold, so a green below means the fabrication is gone"

# ---------------------------------------------------------------------------
# 4 · the real panel: Download PDF, actually CLICKED, over four API wirings.
# ---------------------------------------------------------------------------
echo "· the real panel: Download PDF clicked against a failing, an empty and a real presigned URL"
run_spec "$WORK_REL/real.test.tsx" "$WORK/real.json"; real_rc=$SPEC_RC
if [ $real_rc -eq 124 ] || [ $real_rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything; the self-check above DID pass"
  exit 2
fi
[ -f "$WORK/real.json" ] || { echo "· vitest produced no JSON report for the real panel — unavailable"; exit 2; }
real_status=$(statuses "$WORK/real.json") || {
  printf '%s\n' "$real_status" | sed 's/^/  /'
  echo "· cannot read the real-panel report — unavailable"; exit 2; }
printf '%s\n' "$real_status" | sed 's/^/    /'

if [ $real_rc -ne 0 ]; then
  # shellcheck disable=SC2086
  $TO node_modules/.bin/vitest run --config "$GATES_CFG" --root . "$WORK_REL/real.test.tsx" --reporter=default 2>&1 \
    | grep -aE 'AssertionError|Error:|→|✕|×' | head -30 | sed 's/^/  /'
  echo "VERDICT: broken — a failed contract-PDF fetch still puts a document in the brand's hands"
  echo "         that no server ever produced, or fails to tell them it failed (F-0238)"
  echo "NOT CHECKED: live behaviour against a real R2 presigned URL"
  exit 1
fi

echo "VERDICT: aligned (proved) — Download PDF was CLICKED. With the live fetch rejecting, and"
echo "         again with it resolving an empty downloadUrl, downloadContractPDF was called ZERO"
echo "         times, window.open ZERO times, and the brand got a destructive failure toast."
echo "         With a real presigned URL, that exact sentinel URL is what opened and still no"
echo "         local document was built. Demo mode still produces its own copy, so this is not a"
echo "         gate that would applaud deleting the generator. The assertion table was proved"
echo "         falsifiable, on the two fabrication legs specifically, against a frozen F-0238"
echo "         panel before any of that was believed."
echo "NOT CHECKED: whether the failure message is understandable to a brand (it is destructive"
echo "             and it fires; its wording is not judged here); whether the R2 presigned URL"
echo "             the backend mints actually resolves to the signed contract; the creator-side"
echo "             download control; whether some OTHER surface in the app still generates a"
echo "             contract PDF client-side in live mode — this gate judges this panel's click,"
echo "             not the whole app's use of downloadContractPDF."
exit 0
