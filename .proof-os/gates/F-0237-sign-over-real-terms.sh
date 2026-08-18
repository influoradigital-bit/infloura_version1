#!/usr/bin/env bash
# gates/F-0237-sign-over-real-terms.sh
# origin failure: F-0237 (signed-terms-not-contract) — a hardcoded clause list rendered under
# "Terms (read-only)" with the Sign button beneath it, while the real contract was never passed
# in. The brand typed their legal name under five clauses no contract had ever contained.
#
# ---------------------------------------------------------------------------------------------
# F-0329 (THIS GATE'S OWN DEFECT, repaired here). Ledger record F-0237 is CLOSED against this
# file, and until now this file proved nothing. Its whole terms leg was three greps:
#
#     grep -q "contractRecord"                       # 8 occurrences in the subject file
#     grep -q "contractRecord.milestones"            # 2 occurrences
#     grep -qE "disabled=\{[^}]*!contractRecord"     # the Sign gate
#
# plus a fourth leg that could never fire at all:
#
#     grep -qE "Brand retains usage rights for 6 months" && ! grep -q "demoContractData\|isApiLive\|liveApi"
#
# — AND-ed against the absence of `demoContractData`, which is present in the same file for a
# completely unrelated reason (the demo-mode PDF payload). A dead conjunct is not a check.
#
# OBSERVED FALSIFICATION (.proof-os/tasks/T-F0329-GATES/F-0237.inject.log). The empty-terms
# branch, which honestly reads "No terms are on file for this contract.", had five clauses
# appended to it — "Influora's standard engagement terms apply: 6 months of usage rights, 2
# rounds of revisions, 30-day category exclusivity, 7-day payment, Mumbai arbitration" — under
# the "Terms (from contract, read-only)" heading with Sign directly beneath. That IS F-0237.
# The old gate exited 0, VERDICT: aligned. So did the existing behavioural suite
# (src/components/brand/deal-room/__tests__/signed-terms-rendered.test.tsx, 2 passed), because
# its negative controls were literal-shaped — /usage rights for 6 months/ against "6 months of
# usage rights", /2 revision rounds/ against "2 rounds of revisions" — and its positive control
# was a SUBSTRING match that five appended clauses cannot disturb.
#
# That second observation is why this gate does not simply shell out to that suite. An execution
# leg is not immunity (F-0264 had one and F-0296 walked through it); an execution leg asserting
# the wrong property just makes a false-green look expensive.
#
# THE REPAIR. Provenance is not a property of the source text, it is a property of the render,
# so this gate RENDERS the panel — over a contract whose terms are a sentinel value nothing in
# the codebase could have guessed, and again over a contract that genuinely has none — and
# requires:
#   · terms present  -> the terms region equals the record's terms EXACTLY (equality, not
#                       containment: containment is precisely what the injection defeated)
#   · a different contract -> different terms on screen (the panel follows the record)
#   · terms absent   -> the region states absence and invents nothing: no list markup, no
#                       digits, one sentence, under 160 characters, and none of the words that
#                       only appear when someone is stating contract substance
#   · no record      -> Sign stays disabled even with a name typed
#   · a real record  -> Sign is reachable (so "disable everything" is not applauded as a fix)
#
# And because a harness rots the way those greps did, the gate runs that assertion table FIRST
# against a KNOWN-BAD panel frozen into this file — the sharpest form of the defect: it fetches
# the real record, gates Sign on it, renders the real milestones, and still shows an invented
# clause list. If the table certifies that, or if the three provenance legs are not the ones
# that reject it, this gate reports THAT IT CANNOT FAIL rather than reporting on the real code.
# (Device copied from gates/F-0273-frozen-escrow-counts-as-locked.sh.)
#
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory: ${1:-.} — unavailable"; exit 2; }

# F-0266: look at CODE, not file bytes. Only a precondition here — the proof is the render
# below, and this leg is allowed to prove nothing on its own.
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
  echo "· node_modules/.bin/vitest not found — this gate is a render gate and has nothing"
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
# 0 · a scratch directory INSIDE the project.
#     Outside it, the '@' alias and src/test/setup.ts do not apply and the specs would fail for
#     reasons that have nothing to do with the product (a fixture living outside the project is
#     how a gate false-greens; see the T-FIXWAVE-0815 note). It therefore lives under
#     gates/, which this gate owns. Two hygiene facts follow from that:
#       · an EXIT trap removes it, and
#       · every run first reaps leftovers whose owning PID is gone, because a stray *.test.tsx
#         under .proof-os/ WOULD be collected by a repo-wide `vitest run` (vitest.config.ts
#         excludes node_modules/dist/e2e/.claude — not .proof-os).
# ---------------------------------------------------------------------------
for _old in "$SELF"/_work-F0237.*; do
  [ -d "$_old" ] || continue
  _pid=${_old##*_work-F0237.}
  case "$_pid" in ''|*[!0-9]*) continue ;; esac
  kill -0 "$_pid" 2>/dev/null || rm -rf "$_old" 2>/dev/null
done
WORK="$SELF/_work-F0237.$$"
rm -rf "$WORK" 2>/dev/null
mkdir -p "$WORK" || { echo "· cannot create a scratch dir under gates/ — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT
# path relative to the project root, which is where vitest is invoked from
WORK_REL=".proof-os/gates/_work-F0237.$$"

# ---------------------------------------------------------------------------
# 1 · THE ASSERTION TABLE. One file, applied unchanged to both implementations.
# ---------------------------------------------------------------------------
cat > "$WORK/_table.tsx" <<'TSX'
/* Written by .proof-os/gates/F-0237-sign-over-real-terms.sh; deleted on exit.
 * Applied UNCHANGED to the real panel and to a known-bad panel frozen from the F-0237 defect.
 * It asserts PROVENANCE — that what sits above the Sign button came out of the contract —
 * which is a property of the render and cannot be satisfied by an identifier in the source. */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as React from 'react';

export type Milestone = {
  id: string;
  sequenceNo: number;
  description: string;
  amount: number;
  dueDate?: string | null;
};

export type Scenario =
  | { kind: 'record'; terms: string | null; milestones: Milestone[]; totalAmount: number }
  | { kind: 'absent' };

export type Mount = (s: Scenario) => Promise<void>;

const baseProps = {
  dealId: 'deal-1',
  creatorName: 'Priya Sharma',
  campaignName: 'Summer Launch',
  dealValue: 50000,
  contractId: 'CTR-1',
  status: 'generated' as const,
  onStatusChange: () => {},
};

/* Words that appear only when someone is stating CONTRACT SUBSTANCE. Applied ONLY to the
 * rendered empty-terms state — a scenario where the contract supplied no substance at all, so
 * any substance on screen was invented by the client. This is not a source grep: it reads what
 * a brand about to sign would actually see. */
const CLAUSE_WORDS = [
  'usage right', 'revision', 'exclusiv', 'arbitrat', 'indemn', 'licen', 'warrant',
  'terminat', 'liabilit', 'governing law', 'jurisdiction', 'confidential', 'month',
  'week', 'payable', 'deliverable', 'clause',
];

const norm = (t: string | null | undefined) => (t ?? '').replace(/\s+/g, ' ').trim();

/** The region introduced by a "<something> (..., read-only)" label, minus the label itself. */
function regionBy(labelRe: RegExp, what: string): { el: HTMLElement; text: string } {
  const labels = screen.queryAllByText(
    (_c, el) => !!el && el.children.length === 0 && labelRe.test(norm(el.textContent)),
  );
  if (labels.length === 0) {
    throw new Error(
      'no ' + what + ' label is rendered at all - the panel no longer identifies what it is ' +
      'putting in front of the brand as coming from the contract',
    );
  }
  if (labels.length > 1) {
    throw new Error(
      labels.length + ' ' + what + ' labels are rendered - cannot tell which region holds it',
    );
  }
  const label = labels[0];
  const el = label.parentElement;
  if (!el) throw new Error('the ' + what + ' label has no container element');
  const full = norm(el.textContent);
  const lab = norm(label.textContent);
  return {
    el,
    text: norm(full.startsWith(lab) ? full.slice(lab.length) : full.split(lab).join(' ')),
  };
}

const termsRegion = () => regionBy(/^terms\b/i, 'Terms');
const scheduleRegion = () => regionBy(/^payment schedule\b/i, 'Payment schedule');

async function settled() {
  await waitFor(() =>
    expect(screen.queryByText(/loading contract terms/i)).not.toBeInTheDocument(),
  );
}

/** Identical plumbing for both implementations, so the self-check runs the same path the real
 *  run does and not a friendlier one. */
export function makeMount(importPanel: () => Promise<React.ComponentType<never>>): Mount {
  return async (s: Scenario) => {
    vi.resetModules();
    vi.doMock('@/lib/api', async () => {
      const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
      return {
        ...actual,
        isApiLive: () => true,
        api: {
          ...actual.api,
          contracts: {
            ...actual.api.contracts,
            get: vi.fn(async () =>
              s.kind === 'absent'
                ? null
                : {
                    id: 'CTR-1',
                    collaborationId: 'collab-1',
                    status: 'GENERATED',
                    totalAmount: s.totalAmount,
                    currency: 'INR',
                    brandSignedAt: null,
                    creatorSignedAt: null,
                    terms: s.terms,
                    milestones: s.milestones,
                  },
            ),
          },
        },
      };
    });
    const Panel = await importPanel();
    render(<Panel {...(baseProps as never)} />);
  };
}

export function termsProvenanceSuite(subject: string, mount: Mount) {
  describe('F-0237 terms provenance - ' + subject, () => {
    it('A - shows EXACTLY the terms this contract carries, and nothing beside them', async () => {
      const ALPHA =
        'SENTINEL-ALPHA Creator grants Brand a limited licence to the delivered Reels on owned channels.';
      await mount({ kind: 'record', terms: ALPHA, milestones: [], totalAmount: 50000 });
      await settled();
      // EQUALITY, not containment. Containment is what let five clauses be appended to the
      // honest sentence and still satisfy every check that existed before F-0329.
      expect(termsRegion().text).toBe(ALPHA);
    });

    it('B - a DIFFERENT contract shows DIFFERENT terms (the panel follows the record)', async () => {
      const BRAVO = 'SENTINEL-BRAVO Brand may not resell or sublicense the footage.';
      await mount({ kind: 'record', terms: BRAVO, milestones: [], totalAmount: 50000 });
      await settled();
      expect(termsRegion().text).toBe(BRAVO);
    });

    it('C - when the contract carries NO terms, the panel invents none (the F-0237 defect)', async () => {
      await mount({ kind: 'record', terms: null, milestones: [], totalAmount: 50000 });
      await settled();
      const { el, text } = termsRegion();

      // It must still SAY something - silence under a Sign button is its own lie.
      expect(text.length).toBeGreaterThan(0);

      // ...but a statement of absence, not a document. Every one of these is a property of what
      // the brand SEES, so none can be satisfied by an identifier appearing somewhere in source.
      expect({ tooLong: text.length > 160, text }).toEqual({ tooLong: false, text });
      expect({ listItems: el.querySelectorAll('li, ol, ul').length, text })
        .toEqual({ listItems: 0, text });
      expect({ digits: /\d/.test(text), text }).toEqual({ digits: false, text });
      expect({ clauseWords: CLAUSE_WORDS.filter((w) => text.toLowerCase().includes(w)), text })
        .toEqual({ clauseWords: [], text });
      expect({ sentences: (text.match(/[.!?](\s|$)/g) ?? []).length, text })
        .toEqual({ sentences: 1, text });
    });

    it('D - the payment schedule is the contract milestones, in the contract order', async () => {
      await mount({
        kind: 'record',
        terms: null,
        milestones: [
          { id: 'm2', sequenceNo: 2, description: 'SENTINEL-MILESTONE-BRAVO', amount: 30000 },
          { id: 'm1', sequenceNo: 1, description: 'SENTINEL-MILESTONE-ALPHA', amount: 20000 },
        ],
        totalAmount: 50000,
      });
      await settled();
      const { text } = scheduleRegion();
      const a = text.indexOf('SENTINEL-MILESTONE-ALPHA');
      const b = text.indexOf('SENTINEL-MILESTONE-BRAVO');
      expect({ alphaShown: a >= 0, bravoShown: b >= 0, text })
        .toEqual({ alphaShown: true, bravoShown: true, text });
      expect({ sequenceRespected: a < b, text }).toEqual({ sequenceRespected: true, text });
      expect(text).toContain('50,000');
    });

    it('E - with NO contract record, Sign stays disabled even with a name typed', async () => {
      await mount({ kind: 'absent' });
      await settled();
      const sign = screen.getByRole('button', { name: /sign & send to creator/i });
      const input = screen.queryByLabelText(/type your full legal name to sign/i);
      if (input) await userEvent.type(input, 'Founder Name');
      expect(sign).toBeDisabled();
    });

    it('F - with a real record and a name typed, Sign is reachable (no over-correction)', async () => {
      await mount({
        kind: 'record', terms: 'SENTINEL-CHARLIE anything at all', milestones: [], totalAmount: 1,
      });
      await settled();
      const input = await screen.findByLabelText(/type your full legal name to sign/i);
      await userEvent.type(input, 'Founder Name');
      const sign = screen.getByRole('button', { name: /sign & send to creator/i });
      await waitFor(() => expect(sign).not.toBeDisabled());
    });
  });
}
TSX

# ---------------------------------------------------------------------------
# 2 · THE KNOWN-BAD PANEL, frozen. Not the pre-fix file — the sharpest form of the defect:
#     everything F-0237 asked for EXCEPT provenance.
# ---------------------------------------------------------------------------
cat > "$WORK/bad-panel.tsx" <<'TSX'
import * as React from 'react';
import { api, type ContractApiRecord } from '@/lib/api';

interface Props { contractId: string }

const INVENTED_CLAUSES = [
  'Brand receives 6 months of usage rights across owned social channels.',
  'Up to 2 rounds of revisions are included per deliverable.',
  'Category exclusivity applies for 30 days from the final post.',
  'Payment releases within 7 working days of deliverable approval.',
  'Disputes are resolved by arbitration seated in Mumbai.',
];

export default function BadDealContractTab({ contractId }: Props) {
  const [record, setRecord] = React.useState<ContractApiRecord | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [signerName, setSignerName] = React.useState('');

  React.useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.contracts
      .get('brand', contractId)
      .then((r) => { if (!cancelled) setRecord(r); })
      .catch(() => { if (!cancelled) setRecord(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [contractId]);

  return (
    <div>
      {loading ? (
        <div>Loading contract terms...</div>
      ) : !record ? (
        <div>Contract terms are not available yet. Signing is disabled until the real terms load.</div>
      ) : (
        <>
          <div>
            <p>Terms (from contract, read-only)</p>
            <ul>{INVENTED_CLAUSES.map((c) => (<li key={c}>{c}</li>))}</ul>
          </div>
          <div>
            <p>Payment schedule (from contract, read-only)</p>
            <div>
              {record.milestones.length === 0 ? (
                <p>No milestones on this contract.</p>
              ) : (
                <ol>
                  {record.milestones.slice().sort((a, b) => a.sequenceNo - b.sequenceNo).map((m) => (
                    <li key={m.id ?? m.sequenceNo}>
                      {m.description} - INR {m.amount.toLocaleString('en-IN')}
                    </li>
                  ))}
                </ol>
              )}
              <p>Total: INR {record.totalAmount.toLocaleString('en-IN')} {record.currency}</p>
            </div>
          </div>
        </>
      )}
      <label htmlFor="brand-signer-name">Type your full legal name to sign</label>
      <input id="brand-signer-name" value={signerName}
             onChange={(e) => setSignerName(e.target.value)} />
      <button type="button" disabled={!signerName.trim() || !record}>
        Sign &amp; send to creator
      </button>
    </div>
  );
}
TSX

cat > "$WORK/bad.test.tsx" <<'TSX'
import { makeMount, termsProvenanceSuite } from './_table';
termsProvenanceSuite(
  'the KNOWN-BAD panel (F-0237 frozen)',
  makeMount(async () => (await import('./bad-panel')).default as never),
);
TSX

cat > "$WORK/real.test.tsx" <<'TSX'
import { makeMount, termsProvenanceSuite } from './_table';
termsProvenanceSuite(
  'the real panel',
  makeMount(async () =>
    (await import('@/components/brand/deal-room/deal-contract-tab')).DealContractTab as never),
);
TSX

BUDGET="${PROOF_F0237_VITEST_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

# F-0334: this gate materialises its specs INSIDE .proof-os/gates/, and vitest.config.ts now
# excludes '**/.proof-os/**' so gate fixtures (and any temp spec a killed gate leaves behind)
# are not swept into the product suite or into build.sh's `npm test` leg. vitest applies
# `exclude` even to an explicitly-passed path, so these runs need the gates config — the project
# config with that one exclusion removed. Measured: without this, every run here reports
# "No test files found" and the gate goes unavailable.
GATES_CFG="$SELF/vitest.gates.config.ts"
[ -f "$GATES_CFG" ] || { echo "· $GATES_CFG missing — gate specs cannot be collected — unavailable"; exit 2; }

# run_spec <relpath> <jsonname> — prints nothing; sets SPEC_RC, and writes the JSON report.
run_spec() {
  # shellcheck disable=SC2086
  $TO node_modules/.bin/vitest run --config "$GATES_CFG" --root . "$1" --reporter=json --outputFile="$2" >/dev/null 2>&1
  SPEC_RC=$?
}

# statuses <jsonfile> — one "<A-F> <passed|failed>" line per test, in declaration order.
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
# 3 · SELF-FALSIFICATION. Before trusting the table, prove it can fail — and prove that the
#     THREE PROVENANCE LEGS are what reject the known-bad, not some incidental difference.
# ---------------------------------------------------------------------------
echo "· self-check: this assertion table rejects a panel that renders an invented clause list"
run_spec "$WORK_REL/bad.test.tsx" "$WORK/bad.json"; bad_rc=$SPEC_RC
if [ $bad_rc -eq 124 ] || [ $bad_rc -eq 137 ]; then
  echo "  self-check exceeded ${BUDGET}s — unavailable, NOT a finding"; exit 2
fi
[ -f "$WORK/bad.json" ] || { echo "· vitest produced no JSON report for the self-check — unavailable"; exit 2; }
bad_status=$(statuses "$WORK/bad.json") || {
  printf '%s\n' "$bad_status" | sed 's/^/  /'
  echo "· cannot read the self-check report — unavailable"; exit 2; }
printf '%s\n' "$bad_status" | sed 's/^/    /'
bad_bite=$(printf '%s\n' "$bad_status" | grep -cE '^[ABC] failed')
bad_over=$(printf '%s\n' "$bad_status" | grep -cE '^[DEF] failed')
if [ "$bad_bite" -ne 3 ]; then
  echo "· THIS GATE CANNOT FAIL as intended: of the three PROVENANCE assertions (A/B/C), only"
  echo "  $bad_bite reject a panel showing five clauses no contract ever contained. F-0237 is"
  echo "  exactly that panel. Refusing to report a verdict about the real code from a check that"
  echo "  has just proved itself blind."
  echo "VERDICT: broken — the F-0237 gate's assertions no longer detect F-0237 (F-0329)"
  echo "NOT CHECKED: the real product code — this run never got that far"
  exit 1
fi
if [ "$bad_over" -ne 0 ]; then
  echo "· this gate is mis-calibrated: $bad_over of the no-over-correction legs (D/E/F) also"
  echo "  reject the known-bad, which fetches the real record, gates Sign on it and renders the"
  echo "  real milestones. Those legs are supposed to hold there; a red from them on the real"
  echo "  code would not mean what this gate says it means — unavailable"
  exit 2
fi
echo "  good — A/B/C reject it and D/E/F hold, so a green below means provenance specifically"

# ---------------------------------------------------------------------------
# 4 · the real panel, RENDERED.
# ---------------------------------------------------------------------------
echo "· the real panel, mounted over a sentinel contract and over a contract with no terms"
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
  echo "VERDICT: broken — what the panel puts under the Sign control did not come from the"
  echo "         fetched contract (F-0237)"
  echo "NOT CHECKED: live rendering against a real backend contract"
  exit 1
fi

echo "VERDICT: aligned (proved) — the panel was RENDERED over a contract whose terms were a"
echo "         sentinel value: the terms region equalled that value exactly, a second contract"
echo "         moved it, and over a contract with genuinely no terms the region stated absence"
echo "         and invented nothing (no list markup, no digits, one sentence, no clause"
echo "         vocabulary). Sign stayed disabled with no record and reachable with one. The"
echo "         assertion table was proved falsifiable, on the three provenance legs"
echo "         specifically, against a frozen F-0237 panel before any of that was believed."
echo "NOT CHECKED: whether the terms text the BACKEND stores is itself the text both parties"
echo "             negotiated, or matches the signed PDF — this gate proves the panel is faithful"
echo "             to ContractResponse.terms, not that ContractResponse.terms is faithful to the"
echo "             deal (that is F-0271/F-0283 territory); whether any FE surface lets a brand"
echo "             enter terms before generation (none does, so the empty-terms path is the"
echo "             common one in production); the creator-side contract panel; live rendering"
echo "             against a running backend; and whether contractRecord is the ONLY thing"
echo "             feeding this region — the render proves the wired path, not exclusivity."
exit 0
