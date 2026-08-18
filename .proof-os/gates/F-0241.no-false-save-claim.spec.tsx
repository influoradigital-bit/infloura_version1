/**
 * .proof-os/gates/F-0241.no-false-save-claim.spec.tsx
 *
 * Execution leg for gates/F-0241-no-false-draft-reassurance.sh.
 *
 * origin failure F-0241 (false-save-reassurance): on WORKSPACE_NOT_VERIFIED the create call had
 * THROWN and nothing was persisted, but the box asserted "this campaign is saved as a draft, so
 * nothing is lost". The brand navigated away to verify and lost the whole wizard.
 *
 * WHY THIS FILE EXISTS. The gate used to forbid that one sentence and require the phrase
 * "been saved yet". Both are snapshots of a particular wording, and a reworded false claim
 * satisfies both at once:
 *
 *   "This campaign hasn’t been saved yet as a live campaign — we’ve kept it as a draft, so
 *    nothing is lost."
 *
 * The required phrase is still there; the forbidden sentence is not literally there; the claim
 * is just as false. The pre-repair gate exited 0 on exactly that copy
 * (.proof-os/tasks/T-F0329-GATES/F-0241.inject.log).
 *
 * WHAT IS ASSERTED INSTEAD. The box is RENDERED and its prose (everything outside the buttons
 * and links) is put through a claim audit, which is about meaning rather than wording:
 *   · no un-negated claim that this campaign is already saved / stored / kept / safe, and
 *   · at least one negated one, i.e. the box does say the work is not saved yet.
 * A rewrite of the sentence keeps passing; a reintroduced false claim does not.
 *
 * The audit is itself run over a frozen table of known-bad copy (including F-0241 verbatim and
 * the observed wrong fix above) and one frozen known-good, so every run proves the audit can
 * both fail and pass before its verdict on the real component is believed.
 */
import * as React from 'react';
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { VerificationRequiredBox } from '@/components/brand/VerificationRequiredBox';

/**
 * State claims — "this thing IS/HAS BEEN preserved". Deliberately excludes the base forms
 * "save"/"keep": "Save it as a draft now" is an OFFER of a future action, which is the correct
 * thing for this box to say, not a claim that anything has happened.
 */
const SAVE_CLAIM = /\b(saved|stored|kept|preserved|retained|safe|secure|autosaved|auto-saved|recoverable)\b/gi;
const NEGATION = /\b(not|never|no|nothing|none)\b|n[’'`]t\b/i;

/** Sentence-ish fragments. Em/en dashes split too — this copy uses them as clause breaks. */
function fragments(text: string): string[] {
  return text
    .split(/[.!?;\n—–]+/)
    .map((s) => s.replace(/\s+/g, ' ').trim())
    .filter(Boolean);
}

export type CopyAudit = { affirmed: string[]; denied: string[] };

/**
 * affirmed — save-state claims with no negation ahead of them in their own fragment. Each one
 *            is the box telling the brand their work is already kept somewhere.
 * denied    — save-state claims that ARE negated. At least one is required: the box has to say
 *            the campaign is not saved, not merely avoid saying that it is.
 */
export function auditCopy(text: string): CopyAudit {
  const affirmed: string[] = [];
  const denied: string[] = [];
  for (const frag of fragments(text)) {
    SAVE_CLAIM.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = SAVE_CLAIM.exec(frag)) !== null) {
      const before = frag.slice(0, m.index);
      (NEGATION.test(before) ? denied : affirmed).push(`"${frag}" [${m[0]}]`);
    }
  }
  return { affirmed, denied };
}

/** The prose the brand reads — buttons and links are actions, not claims. */
function proseOf(container: HTMLElement): string {
  const clone = container.cloneNode(true) as HTMLElement;
  clone.querySelectorAll('button, a').forEach((n) => n.remove());
  return clone.textContent ?? '';
}

function renderBox(props: Partial<React.ComponentProps<typeof VerificationRequiredBox>> = {}) {
  const { container } = render(
    <MemoryRouter>
      <VerificationRequiredBox canVerify onSaveDraft={() => {}} {...props} />
    </MemoryRouter>,
  );
  return proseOf(container);
}

/** Frozen. Each of these must be REJECTED by the audit; the reason says which leg must catch it. */
const KNOWN_BAD: { why: string; copy: string; leg: 'affirmed' | 'denied' }[] = [
  {
    why: 'F-0241 verbatim',
    copy:
      'Your workspace needs to be verified to publish. This campaign is saved as a draft, so ' +
      'nothing is lost.',
    leg: 'affirmed',
  },
  {
    why: 'the observed wrong fix — reworded, keeps "been saved yet", claim just as false',
    copy:
      'This campaign hasn’t been saved yet as a live campaign — we’ve kept it as a draft, so ' +
      'nothing is lost. Verify your workspace to publish it.',
    leg: 'affirmed',
  },
  {
    why: 'reassurance by adjective',
    copy: 'This campaign hasn’t been saved yet. Don’t worry — your work is safe with us.',
    leg: 'affirmed',
  },
  {
    why: 'a draft that does not exist, asserted plainly',
    copy: 'Your draft is stored. Verify your workspace to publish it.',
    leg: 'affirmed',
  },
  {
    why: 'silence is not honesty — the box must SAY the work is unsaved, not just avoid lying',
    copy: 'Publishing needs a verified workspace. Verify to publish this campaign.',
    leg: 'denied',
  },
];

/** Frozen known-good: an audit that rejects this is a false-red machine, not a proof. */
const KNOWN_GOOD =
  'Your workspace needs to be verified to publish. This campaign hasn’t been saved yet — ' +
  'publishing needs a verified workspace. Save it as a draft now so your work isn’t lost, ' +
  'then verify to publish it.';

describe('F-0241 — the box does not claim work is saved when nothing was persisted', () => {
  it('SELF-CHECK: the audit rejects every frozen known-bad copy', () => {
    const escaped: string[] = [];
    for (const kb of KNOWN_BAD) {
      const a = auditCopy(kb.copy);
      const caught = kb.leg === 'affirmed' ? a.affirmed.length > 0 : a.denied.length === 0;
      if (!caught) escaped.push(`${kb.why} :: ${kb.copy}`);
    }
    expect(
      escaped,
      'THIS TEST CANNOT FAIL: the copy audit accepted copy that reintroduces F-0241 —\n' +
        escaped.join('\n'),
    ).toEqual([]);
  });

  it('SELF-CHECK: the audit accepts the frozen known-good copy', () => {
    const a = auditCopy(KNOWN_GOOD);
    expect(a.affirmed, 'the audit flags honest copy — it is a false-red machine').toEqual([]);
    expect(a.denied.length, 'the audit cannot see an explicit not-saved statement').toBeGreaterThan(0);
  });

  it('makes no un-negated claim that the campaign is already saved', () => {
    for (const props of [
      { canVerify: true },
      { canVerify: false },
      { canVerify: true, savingDraft: true },
    ]) {
      const a = auditCopy(renderBox(props));
      expect(
        a.affirmed,
        `with props ${JSON.stringify(props)} the box tells the brand their work is already ` +
          `kept somewhere, at a moment when the create call threw and nothing was persisted ` +
          `(F-0241)`,
      ).toEqual([]);
    }
  });

  it('states outright that the campaign is not saved yet', () => {
    const a = auditCopy(renderBox());
    expect(
      a.denied.length,
      'the box never says the work is unsaved. Silence still leaves the brand to assume a ' +
        'draft exists and navigate away from the wizard (F-0241).',
    ).toBeGreaterThan(0);
  });

  it('offers the save-as-draft action rather than asserting it already happened', () => {
    const { getByRole } = render(
      <MemoryRouter>
        <VerificationRequiredBox canVerify onSaveDraft={() => {}} />
      </MemoryRouter>,
    );
    // The escape hatch has to be present and actionable; a box that only says "not saved" and
    // offers nothing is honest and useless.
    expect(getByRole('button', { name: /save.*draft/i })).toBeEnabled();
  });
});
