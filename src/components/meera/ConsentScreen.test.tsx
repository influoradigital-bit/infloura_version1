/**
 * U-6 (RULINGS-U-0917.md R-U2, NISHA-CONSENT-0917.md "Final — Case A (v2)") — the consent notice
 * grows a third paragraph covering pasted briefs, in both languages, without touching the
 * existing two. Ships as Case A under consent `v2`: no delete control exists yet, so the
 * paragraph states a fact ("deleting your conversations does not delete it") and promises
 * nothing about ever deleting a brief. Case B (the "can be deleted" wording, held for U-7 / v3)
 * must NOT appear under v2 — PRIYA-LASTCALL-U1R-U6-0917.md (UF6-1): a Case B/v3 switch landed
 * here briefly and was reverted, because it overwrote this already-reviewed build while the tree
 * is still uncommitted and promised a delete route that does not exist yet. That edit is saved
 * as `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/U7-caseB-v3.patch` for U-7 to re-apply later, on
 * its own, after Wave U is committed.
 *
 * Run: npx vitest run src/components/meera/ConsentScreen.test.tsx
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { CONSENT_TEXT_VERSION, ConsentScreen } from './ConsentScreen';

const EXISTING_PARAGRAPH_1_EN = 'Meera is your AI manager. She helps you understand your deals, payments, and metrics.';
const EXISTING_PARAGRAPH_2_EN =
  'Meera will access your profile data, deal history, payment status, and metrics. You can export or delete your conversations anytime from Settings.';
const NEW_PARAGRAPH_EN =
  "When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it. Influora saves a copy, and deleting your conversations does not delete it. Before you paste, remove anything you don't want Meera to read.";

const EXISTING_PARAGRAPH_1_HI = 'Meera आपकी AI मैनेजर है। वह आपके डील्स, पेमेंट, और मेट्रिक्स को समझने में मदद करती है।';
const EXISTING_PARAGRAPH_2_HI =
  'Meera आपके प्रोफाइल डेटा, डील हिस्ट्री, पेमेंट स्टेटस और मेट्रिक्स को access करेगी। आप कभी भी अपनी conversations को Settings में जाकर export या delete कर सकते हैं।';
const NEW_PARAGRAPH_HI =
  'जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है। Influora उसकी एक copy save करता है, और conversations delete करने से वो copy delete नहीं होती। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।';

/**
 * Case B's exact banned phrasings (NISHA-CONSENT-0917.md "Final — Case B", not for v2):
 *   en: "Influora saves a copy UNTIL YOU DELETE IT." / "...are DELETED SEPARATELY."
 *   hi: "...जब तक आप उसे delete नहीं करते।" / "...अलग-अलग delete होते हैं।"
 * These patterns cover exactly those two phrasings in each language — a differently-worded
 * future promise would need its own pattern added here, this is not a general semantic check.
 * They are checked to NOT match the ALLOWED, negated sentence ("deleting your conversations does
 * not delete it" / "conversations delete करने से वो copy delete नहीं होती") by construction: the
 * allowed sentence has no "until"/"जब तक" and no "separately"/"अलग-अलग".
 */
const DELETE_PROMISE_BANS: Array<[string, RegExp]> = [
  ['English: "Influora saves a copy until you delete it"', /until you delete it/i],
  ['English: "...are deleted separately"', /deleted separately/i],
  ['Hindi: "...जब तक आप उसे delete नहीं करते"', /जब तक आप उसे delete नहीं करते/],
  ['Hindi: "...अलग-अलग delete होते हैं"', /अलग-अलग delete होते हैं/],
];

describe('ConsentScreen — U-6 pasted-brief paragraph (Case A, v2)', () => {
  // Kabir's G-2 (KABIR-CONSENT-0917.md "Last call — U-6 words") — pinned so a future wording
  // change is forced to touch this constant in the same diff, not drift quietly.
  it('pins the consent text version', () => {
    expect(CONSENT_TEXT_VERSION).toBe('v2');
  });

  it('en-IN: the body has all three paragraphs, in order, the first two unchanged', () => {
    render(<ConsentScreen open language="en-IN" onAccept={() => {}} onDecline={() => {}} />);
    const body = screen.getByTestId('consent-body').textContent ?? '';
    expect(body).toContain(EXISTING_PARAGRAPH_1_EN);
    expect(body).toContain(EXISTING_PARAGRAPH_2_EN);
    expect(body).toContain(NEW_PARAGRAPH_EN);
    // Order: the new paragraph comes LAST, after both existing ones.
    expect(body.indexOf(EXISTING_PARAGRAPH_1_EN)).toBeLessThan(body.indexOf(EXISTING_PARAGRAPH_2_EN));
    expect(body.indexOf(EXISTING_PARAGRAPH_2_EN)).toBeLessThan(body.indexOf(NEW_PARAGRAPH_EN));
    // G-2 (required) — the three `toContain` checks above would still pass if a line were
    // silently ADDED (e.g. a fourth "You can delete saved briefs in Settings." paragraph). This
    // exact-equality check catches that: the body must be EXACTLY these three paragraphs, joined
    // by a blank line, nothing more. Expected string built from this file's own constants, not
    // read back from the component.
    expect(body).toBe([EXISTING_PARAGRAPH_1_EN, EXISTING_PARAGRAPH_2_EN, NEW_PARAGRAPH_EN].join('\n\n'));
  });

  it('hi-IN: the body has all three paragraphs, in order, the first two unchanged', () => {
    render(<ConsentScreen open language="hi-IN" onAccept={() => {}} onDecline={() => {}} />);
    const body = screen.getByTestId('consent-body').textContent ?? '';
    expect(body).toContain(EXISTING_PARAGRAPH_1_HI);
    expect(body).toContain(EXISTING_PARAGRAPH_2_HI);
    expect(body).toContain(NEW_PARAGRAPH_HI);
    expect(body.indexOf(EXISTING_PARAGRAPH_1_HI)).toBeLessThan(body.indexOf(EXISTING_PARAGRAPH_2_HI));
    expect(body.indexOf(EXISTING_PARAGRAPH_2_HI)).toBeLessThan(body.indexOf(NEW_PARAGRAPH_HI));
    // G-2 (required) — same exact-equality guard as en-IN above.
    expect(body).toBe([EXISTING_PARAGRAPH_1_HI, EXISTING_PARAGRAPH_2_HI, NEW_PARAGRAPH_HI].join('\n\n'));
  });

  it('bans any Case B delete-promise wording, in either language, without tripping on the allowed negated sentence', () => {
    const en = render(<ConsentScreen open language="en-IN" onAccept={() => {}} onDecline={() => {}} />);
    const enBody = en.getByTestId('consent-body').textContent ?? '';
    en.unmount(); // Dialog content portals to document.body — unmount before rendering the next one.
    const hi = render(<ConsentScreen open language="hi-IN" onAccept={() => {}} onDecline={() => {}} />);
    const hiBody = hi.getByTestId('consent-body').textContent ?? '';

    for (const [name, pattern] of DELETE_PROMISE_BANS) {
      expect(pattern.test(enBody), `en-IN body matched the "${name}" ban`).toBe(false);
      expect(pattern.test(hiBody), `hi-IN body matched the "${name}" ban`).toBe(false);
    }
    // Guard the guard: the allowed sentence really is present (so an empty/broken body would not
    // pass this test vacuously).
    expect(enBody).toContain('deleting your conversations does not delete it');
    expect(hiBody).toContain('conversations delete करने से वो copy delete नहीं होती');
  });
});

describe('ConsentScreen — G-3 (KABIR-CONSENT-0917.md MEDIUM): dialog scrolls instead of clipping', () => {
  it('the dialog content has a viewport-capped max-height and its own scrollbar', () => {
    // jsdom does not lay out real pixels, paint, or scroll — it cannot prove the Hindi notice no
    // longer clips off a 375px-tall viewport, or that the Accept/Not now buttons stay reachable.
    // This only pins that the fix's class is present; the visual check is a real-browser check
    // (Kabir: 375×553), not this test.
    render(<ConsentScreen open language="en-IN" onAccept={() => {}} onDecline={() => {}} />);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveClass('max-h-[calc(100dvh-2rem)]');
    expect(dialog).toHaveClass('overflow-y-auto');
    // Doesn't break the close button or footer: both are still rendered and reachable in the DOM
    // (real off-screen/clipping behaviour is exactly what jsdom cannot check).
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Accept' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Not now' })).toBeInTheDocument();
  });
});
