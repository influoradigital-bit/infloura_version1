/**
 * paytrigger — ONE payment promise, everywhere.
 *
 * Sibling of `ev007-regulated-claims.test.ts`, deliberately a separate file: that gate answers
 * "does this copy claim a licence or a tax engine we do not have?", this one answers "does this
 * copy promise a payment that differs from the one the product makes?". They fail for different
 * reasons and are owned by different facts, so they are kept apart.
 *
 * THE OWNER'S RULING (2026-09-21) — the single promise every surface must now make:
 *   - The trigger is the LIVE POST. A creator is paid after the post is live and its link has
 *     been submitted (status POSTED, PaymentMilestone's existing ON_POSTED default). Approving a
 *     draft clears the post to go live; it pays nobody.
 *   - Influora pays the creator itself, by bank transfer (NEFT/IMPS) to the account on their
 *     profile, WITHIN 2 WORKING DAYS of that link. There is NO self-serve withdrawal, and none
 *     is coming — so no "withdraw" control and no "withdrawals open shortly".
 *   - The brand has 3 WORKING DAYS to review a submitted draft. If it does not act, the
 *     deliverable is escalated to the Influora team, who chase it as people. It is NEVER
 *     auto-approved and never auto-paid.
 *   - Revisions: up to 2 rounds, 2 working days each.
 *   - Nothing claims tax is deducted or handled for anyone.
 *   - Working days are Monday to Friday. That definition lives once, in
 *     `influora-api/src/main/java/com/influora/common/WorkingDays.java`.
 *
 * WHY A GATE AND NOT A CHECKLIST
 * ------------------------------
 * Before this, three different promises shipped at once and none of them was the product's:
 * the creator wallet said "within 24-48 hours on business days", the marketing pages said "~24
 * hours after approval", and the GENERATED CONTRACT said both "2 working days of the live link"
 * (in its financial terms) and "within 7 days of brand approval" (in clause 6) — twenty lines
 * apart, in the document both parties e-sign. A promise this diffuse does not stay fixed by
 * being fixed once.
 *
 * NEGATION IS NOT A VIOLATION
 * ---------------------------
 * The honest copy has to be able to SAY the banned thing in order to deny it — "it is never
 * approved automatically" is the sentence a brand most needs to read. So a rule can carry a
 * negator exemption: a match whose immediately preceding words are never/not/nor/neither is a
 * denial, not a promise. `NEGATED_CONTROL` proves each exemption works, and each rule's
 * `POSITIVE_CONTROL` proves the rule still catches the affirmative sentence it was written
 * against, so the exemption cannot quietly swallow the rule.
 *
 * ROUND 3 — WHY THE RULES BELOW ARE BARER THAN THEY WERE
 * --------------------------------------------
 * Round 2 shipped green with four surfaces still making the wrong promise, each found by
 * reading the OUTPUT rather than the diff. Two were withdrawal copy the proximity test could
 * not see ('Ready to withdraw' has no money noun on its line; 'Add bank/UPI on first
 * withdrawal.' kept its money noun in the previous sentence, on the far side of a full stop
 * that `[^.\n]` cannot cross). Two were tax claims in the shape no rule covered: a meta
 * description saying tax 'is handled', and four sections of the TDS policy still describing a
 * deduction that does not happen.
 *
 * The lesson taken here is that a proximity window is a guess about how a sentence will be
 * written, and copy does not co-operate. Where the word itself is the defect, the rule is now
 * bare and the EXEMPTIONS carry the precision: `otherSense` for the consent/application sense
 * of 'withdraw', `machineToken` for identifiers and routes. Both are scoped to the same
 * sentence window as the negator, and both are proved by controls.
 *
 * ANTI-SELF-MATCH
 * ---------------
 * The pattern list below is itself a document full of banned phrases. `src/__tests__` is
 * excluded from the scan (and asserted to be), so this gate cannot fail on its own rules — the
 * failure mode that made two earlier gates in this repo go red on the comment explaining the
 * thing they banned.
 */
import { describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(import.meta.url);
const REPO_ROOT = path.resolve(path.dirname(HERE), '..', '..');
// PAYTRIGGER_SCAN_ROOT points the gate at an extracted copy of the tree (e.g. `git archive
// HEAD`), which is how it is falsified against the OLD strings without ever rewriting the
// working checkout.
const ROOT = process.env.PAYTRIGGER_SCAN_ROOT
  ? path.resolve(process.env.PAYTRIGGER_SCAN_ROOT)
  : REPO_ROOT;
const SELF = path.relative(REPO_ROOT, HERE).split(path.sep).join('/');

type Rule = {
  id: string;
  re: RegExp;
  why: string;
  /** True when the match is a denial of the banned claim rather than the claim itself. */
  allowNegated?: boolean;
  /**
   * Words that put the matched term in a sense this rule does not ban: consent is withdrawn,
   * an application is withdrawn. Tested over the sentence the match sits in (`sentenceOf`),
   * so a different sentence on the same line cannot hand out the exemption.
   */
  otherSense?: RegExp;
  /** Skip matches that are identifiers, routes or enum members rather than copy. */
  machineToken?: boolean;
};

/**
 * Words that turn the claim into a denial of itself.
 *
 * ROUND 4 — `nothing` and `nobody` joined the list. `escrow-release-reason.ts` explains a
 * refunded deal with 'The work is approved, but secured funds for this deal were refunded to
 * your wallet, so nothing was paid out.' That sentence denies the banned claim as flatly as any
 * 'never' does, and `\bno\b` does not match inside 'nothing'. Without them the honest
 * explanation of the one case where approval really does pay nobody was itself a violation.
 */
const NEGATOR = /\b(?:never|not|no|nor|neither|without|nothing|nobody|none)\b/i;

/**
 * The window a NEGATOR is looked for in: 40 characters behind the match, through the end of it.
 * A negator can sit before the match ("it is never **approved automatically**") or inside it
 * ("your balance is never withdrawable"), so the window spans both, and it is cut at the last
 * sentence boundary, which is what stops it over-exempting: in "We never charge a fee. Payouts
 * are processed within 24-48 hours", the "never" belongs to the previous sentence and the payout
 * promise is still caught.
 */
function sentenceAround(line: string, index: number, matched: string): string {
  const window = line.slice(Math.max(0, index - 40), index + matched.length);
  // Strip markdown emphasis so "**never **approved" still reads as one phrase.
  return window.replace(/[*_`]/g, '').split(/[.!?;]/).pop() ?? '';
}

function isNegated(line: string, index: number, matched: string): boolean {
  return NEGATOR.test(sentenceAround(line, index, matched));
}

/**
 * The WHOLE sentence the match sits in, forwards as well as back — which `sentenceAround`
 * deliberately is not. A negator has to precede what it denies, so 40 characters behind is
 * the right scope there. The word that fixes a SENSE does not: 'Withdraw consent' and
 * 'withdrawable at any time (withdrawal doesn't affect processing already done)' both carry
 * it after the match, and the first version of this exemption flagged the privacy policy's
 * DPDP rights list because of it.
 */
export function sentenceOf(line: string, index: number, matched: string): string {
  const before = line.slice(0, index);
  const start = Math.max(...['.', '!', '?', ';'].map((c) => before.lastIndexOf(c))) + 1;
  const rest = line.slice(index + matched.length).search(/[.!?;]/);
  const end = rest === -1 ? line.length : index + matched.length + rest;
  return line.slice(start, end).replace(/[*_`]/g, ' ');
}

/**
 * A match that is code rather than copy: `'withdraw'` as a union member, `/wallet/withdraw`
 * as a route, `withdraw:` as an object key, `.withdraw(` as a call. `POST /wallet/withdraw`
 * still exists in the API client, and this gate governs what the product SAYS — banning the
 * identifier would only teach the next person to rename it.
 *
 * A quoted literal counts as machine only when the whole word is lower-case or upper-case:
 * `'withdraw'` and `'WITHDRAWAL'` are tokens, `label="Withdraw"` is a button and stays copy.
 * The object-key case additionally requires the match to start the line, so a markdown
 * heading ('Withdrawals: how it works') is not waved through as a property.
 */
export function isMachineToken(line: string, index: number, matched: string): boolean {
  const before = line[index - 1] ?? '';
  const after = line[index + matched.length] ?? '';
  const isQuote = (c: string) => c === "'" || c === '"' || c === '`';
  if (isQuote(before) && isQuote(after) && /^(?:[a-z]+|[A-Z_]+)$/.test(matched)) return true;
  if (before === '/' || before === '.') return true;
  if (after === '(' || after === '/') return true;
  // The object-key case. `(^|\.\s)` rather than `^` because `foldLines` joins a whole file into
  // one string with ' . ' between constructs, so `withdraw:` at the head of its own source line
  // is no longer at the head of the string being scanned -- and `api.ts`'s wallet method was
  // reported as copy on the first folded run for exactly that reason.
  return after === ':' && /(?:^|\.\s)[\s{(,]*$/.test(line.slice(0, index));
}

export const RULES: Rule[] = [
  {
    id: 'payment-measured-in-hours',
    re: /\b(?:payout|payouts|payment|paid|funds)\b[^.\n]{0,80}?\bwithin\s+\d+\s*(?:[-–—]\s*\d+\s*)?hours?\b/i,
    why: 'Influora pays within 2 WORKING DAYS of the live link, not in hours',
  },
  {
    id: 'payout-time-after-approval',
    re: /\bwithin\s+\d+\s*(?:[-–—]\s*\d+\s*)?hours?\s+(?:of|after)\s+approval\b|\bpayout\s+time\s+after\s+approval\b|\bpayout\s+typically\s+inside\s+\d+\s*hours?\b/i,
    why: 'approval is not the payment trigger — the live post link is',
  },
  {
    id: 'payment-in-plain-days',
    re: /\b(?:pays?|paid|payment|payout|released)\b[^.\n]{0,60}?\bwithin\s+\d+\s+days\b/i,
    why: 'every payment window is counted in WORKING days (Mon–Fri), so it must say so',
  },
  {
    id: 'self-serve-withdrawal',
    /*
     * ROUND 3. This rule and the `withdrawals-coming-soon` rule that used to follow it both
     * required 'withdraw' to sit within 40 characters of a money noun, and both missed the two
     * surfaces a creator actually reads:
     *
     *   'Ready to withdraw'                  (creator-dashboard.tsx, under Available balance)
     *   'Add bank/UPI on first withdrawal.'  (creator-onboarding.tsx, step 3)
     *
     * The first has no money noun at all — the money is the number rendered ABOVE the label, so
     * the sentence never names it. The second kept its money noun ('Payouts') in the previous
     * sentence, behind a full stop `[^.\n]` cannot cross; and the reverse-direction half of the
     * old pattern was missing `bank` and `UPI` from its noun list anyway. Three separate ways
     * for one claim to walk past the rule written against it.
     *
     * So the proximity test is gone. In customer copy the word itself is the defect: Influora
     * pays creators by bank transfer, so a creator never withdraws anything and no surface may
     * suggest otherwise. Two exemptions carry the precision instead, both narrower than a
     * distance guess and both proved by controls:
     *   - OTHER SENSES. Consent under the DPDP Act is withdrawable at any time and the privacy
     *     policy is required to say so; an application is withdrawn; tracking consent is
     *     withdrawn. `otherSense` covers those and nothing else.
     *   - MACHINE TOKENS. See `isMachineToken`. The withdraw ENDPOINT still exists in the API
     *     client; what may not exist is a sentence telling a creator to use one.
     *
     * `withdrawals-coming-soon` is deleted rather than kept: once this rule is bare, every
     * string that one matched this one matches too, so it could never be the sole catcher of
     * anything and the no-dead-weight test would say so. Its sentence ('Withdrawals open
     * shortly') moved into REGRESSION_CONTROL, where it stays pinned as must-catch.
     */
    re: /\bwithdraw(?:als?|able|ably|ing|s|n)?\b/i,
    why: 'there is no self-serve withdrawal — Influora pays creators by bank transfer',
    allowNegated: true,
    otherSense: /(?:^|[^A-Za-z])(?:consent|consents|application|applications|applicant|candidacy|nomination|tracking|opt[-\s]?in)(?:[^A-Za-z]|$)/i,
    machineToken: true,
  },
  {
    id: 'auto-approval',
    re: /\bauto[\s-]?approv(?:al|als|ed|es|ing)\b|\b(?:approved|approves)\s+automatically\b|\bautomatically\s+approv\w*\b/i,
    why: 'an unreviewed draft escalates to the Influora team; it is never auto-approved',
    allowNegated: true,
  },
  {
    id: 'auto-payment-on-approval',
    re: /\b(?:payment|payout|funds)\s+releases?\s+automatically\b|\breleases?\s+funds\s+automatically\s+upon\s+approval\b|\bpayment\s+releases\s+to\s+you\s+automatically\b/i,
    why: 'nothing pays automatically on approval; the live link triggers payment',
    allowNegated: true,
  },
  {
    id: 'tax-deducted-for-you',
    re: /\btax\s+that\s+must\s+be\s+deducted\b|\bany\s+tax\s+(?:is\s+)?deducted\b|\bTDS\s+(?:is|will\s+be)\s+deducted\s+(?:by\s+)?(?:us|Influora|the\s+platform|automatically)\b/i,
    why: 'nothing may claim tax is deducted or handled; payments are made per applicable law',
    allowNegated: true,
  },
  {
    id: 'tax-handled-for-you',
    /*
     * ROUND 3. `tax-deducted-for-you` above needs the exact words 'must be deducted', 'any tax
     * deducted' or 'TDS is deducted by us'. The /tds route's meta description said none of
     * them — 'How tax deducted at source (TDS) IS HANDLED on creator payouts on Influora' —
     * and that string is the one a crawler reads before it reads the page.
     *
     * This matches the claim SHAPE: a tax word, a passive 'is/has been <handled|withheld|
     * deducted|filed>', and then an object that makes it OUR doing or YOUR money. That last
     * clause is load-bearing: it is what keeps the blog's advice to BRANDS about their own
     * statutory duty ('TDS is deducted and documented where applicable') legal, because that
     * sentence is about the brand's obligation and not about what Influora does to a Payout.
     */
    re: /\b(?:tax(?:es)?|TDS)\b[^.\n]{0,40}?\b(?:is|are|was|were|will\s+be|gets?|being|has\s+been|have\s+been|had\s+been)\s+(?:handled|managed|taken\s+care\s+of|sorted|filed|remitted|withheld|deducted|deposited)\b[^.\n]{0,40}?\b(?:your|our|we|us|Influora|creators?'?s?|payouts?|the\s+platform|(?:Indian\s+)?(?:tax\s+)?law)\b/i,
    why: 'Influora withholds no tax from a Payout, so nothing may say tax is handled or deducted here',
    allowNegated: true,
  },
  {
    id: 'tax-rate-applies-to-you',
    /*
     * ROUND 3. The TDS policy's PAN section told creators which RATE they would be deducted
     * at — a rate nobody is applying, since nothing is withheld. Deliberately no negator
     * exemption: the worst of those two sentences IS a negative ('If you have **not furnished
     * your PAN**, a significantly higher TDS rate applies'), so an exemption keyed on 'not'
     * would have waved through the exact line this rule exists to catch.
     */
    re: /\b(?:TDS|tax|withholding)\s+rate\b[^.\n]{0,60}?\bapplies\b|\bhigher\s+(?:no-PAN\s+)?deduction\s+rate\b/i,
    why: 'no rate is applied to a Payout today; a rate is only publishable once a CA confirms one applies',
  },
  {
    id: 'tax-deducted-from-your-payout',
    /*
     * ROUND 3. The noun form, which no passive-voice rule can see: 'a TDS deduction on your
     * Payout'. Requires the deduction to be pointed at the creator's money, so a blog line
     * about who BEARS a TDS deduction in an industry contract stays sayable.
     */
    re: /\b(?:TDS|tax|withholding)\s+(?:deduction|deductions)\b[^.\n]{0,30}?\b(?:on|from)\s+(?:your|the\s+creator'?s?|each|every|a|the)\b[^.\n]{0,25}?\b(?:payout|payouts|payment|payments|earnings|balance|fee)\b/i,
    why: 'nothing is deducted from a creator Payout for tax; do not describe a deduction that does not happen',
    allowNegated: true,
  },
  /*
   * The rules above this point all need a DURATION word ('hours', 'N days') or the literal adverb
   * 'automatically'. Kavya's re-review proved that is not enough: the commonest way this product
   * mis-states its own trigger is to name approval as the payment event with no clock and no
   * adverb at all -- 'Payment releases only after you approve the work', 'Payout on approval',
   * 'Funds will be released when all deliverables are approved'. Fifteen such lines survived the
   * first pass with this gate fully green. The rules below close that hole by matching the CAUSAL
   * SHAPE rather than the timing words.
   */
  {
    id: 'release-triggered-by-approval',
    /*
     * ROUND 4. This rule used to open with a MONEY NOUN — `payment|payout|funds|money` — and
     * then look forward for a release verb. Kavya's sweep proved that prefix is a guess about
     * where the money is named, and three of the ten survivors put it somewhere the prefix
     * could not reach:
     *
     *   'Funds secured for ongoing campaigns. Released upon deliverable approval.'
     *        (brand-wallet.tsx) — money noun in the PREVIOUS sentence, on the far side of a
     *        full stop `[^.\n]` cannot cross.
     *   'Held before filming · releases on approval'
     *        (how-it-works-creators.tsx) — no money noun at all; the figure it is talking
     *        about is rendered on the line ABOVE, so the sentence never names it.
     *   '...the payout releasing after the brand approves the work.'
     *        (how-it-works-creators.tsx meta description) — money noun present, but the verb
     *        is the gerund `releasing`, and the alternation only listed `releases|released`.
     *
     * So the money-noun prefix is gone and the verb alternation now carries the bare
     * infinitive and the gerund. Same treatment round 3 gave `self-serve-withdrawal`: where
     * the claim shape itself is the defect, a proximity guess only teaches the next writer
     * which side of a full stop to put the noun on.
     *
     * What keeps it honest is the CONNECTOR: a release verb followed within a sentence by
     * on/upon/once/when/after and then an approval word. 'Payment is released once the post
     * is live' has the connector and no approval word, so it stays green — it is in
     * NEGATIVE_CONTROL precisely to hold that line.
     */
    re: /\b(?:releases\b|released\b|release\b|releasing\b|pays\s+out\b|paid\s+out\b|is\s+paid\b|are\s+paid\b|paid\b)[^.\n]{0,40}?\b(?:on|upon|once|when|after)\b[^.\n]{0,40}?\bapprov\w*/i,
    why: 'approval only clears the post to go live; the LIVE POST LINK is what releases payment',
    allowNegated: true,
  },
  {
    id: 'approval-causes-payment',
    /*
     * ROUND 4. Two changes, both from real survivors.
     *
     * WINDOW 40 -> 80. 'Once you approve the deliverables, funds are automatically released to
     * the creator.' (brand-wallet.tsx, the 'How Secure Payments Work' card) puts 43 characters
     * between the approval word and the verb. It missed by three. A window tuned to the
     * sentences you have already read is not a rule, it is a memory of them.
     *
     * BARE 'release' AND 'releasing'. Round 3's comment here said bare 'release' was
     * deliberately excluded so 'payment is ready to release' — a payment waiting on a human
     * operator, which is TRUE and must stay sayable — would not trip. That exclusion also let
     * through the two worst-shaped survivors:
     *
     *   'Approve the work here, then release ₹X to the creator from the Payments panel.'
     *   '...the brand approves, and only then does the payment release...'
     *
     * The exclusion is replaced by `otherSense`, which is narrower and provable: it exempts
     * only the operator-permission phrasing ('ready/able/eligible/free/waiting/queued to
     * release'), and the two sentences above are pinned in REGRESSION_CONTROL so the exemption
     * cannot widen back over them.
     */
    re: /\bapprov(?:e|es|ed|ing|al|als)\b[^.\n]{0,80}?\b(?:releases\b|released\b|release\b|releasing\b|pays\s+out\b|paid\s+out\b|is\s+paid\b|are\s+paid\b|triggers?\s+(?:the\s+)?payment\b)/i,
    why: 'approving a draft pays nobody -- payment follows the live post link',
    allowNegated: true,
    /*
     * Two senses of 'release' that are not this claim, both proved by NEGATIVE_CONTROL:
     *
     *   'Deliverable approved — payment is ready to release'  — a payment WAITING on a human
     *       operator. True, and the honest replacement copy depends on being able to say it.
     *   '...(delivery, approval, payment protection release) go through our Dispute
     *       Resolution Policy'  — an enumeration of dispute TOPICS in the Terms and the
     *       Grievance policy. 'payment protection release' is a compound noun naming the
     *       event; the sentence makes no claim about what causes it.
     *
     * The second exemption is safe because it only narrows THIS rule. The verb-first
     * `release-triggered-by-approval` has no exemptions, so a real claim written in that shape
     * ('payment protection release on approval', which is what escrow-and-refund-policy.md
     * actually said) is still caught by the other rule.
     */
    otherSense: /\b(?:ready|able|eligible|free|waiting|queued|cleared)\s+to\s+release\b|\bpayment\s+protection\s+release\b/i,
  },
  {
    id: 'money-held-pending-approval',
    /*
     * ROUND 4. The shape with NO verb at all, which every rule above needs one of:
     *
     *   'Funds secured pending content approval'   (timeline/event-cards/payment-card.tsx)
     *
     * It promises the same wrong thing by implication — approval is named as the only thing
     * the money is waiting on — and it is the form a status chip or a badge reaches for,
     * because a chip has no room for a verb.
     *
     * Keyed on the NOUN 'approval', never the adjective 'approved'. That one character is what
     * separates this rule from the honest replacement copy: 'Funds stay secured until the
     * approved post is live and its link has been submitted' names approval as a property of
     * the post, not as the condition on the money, and it is in NEGATIVE_CONTROL to prove the
     * distinction holds.
     */
    re: /\b(?:funds?|payment|payments|money|payout|payouts|fee|fees|amount|balance)\b[^.\n]{0,50}?\b(?:pending|awaiting|subject\s+to|conditional\s+on|contingent\s+on)\s+(?:\w+\s+){0,3}approvals?\b/i,
    why: 'the money waits on the live post link, not on approval; approval only clears the post to go live',
    allowNegated: true,
  },
  {
    id: 'paid-only-after-approval',
    // 'only after approval' is the worst of the three: it states approval as the SOLE condition,
    // which is what index.html and public/llms.txt were telling search and AI crawlers.
    //
    // ROUND 4 — the money words. 'Money moves only when you approve.' (FundEscrowButton's trust
    // line) named the sole condition with a money noun this list did not have and a verb no
    // list could anticipate: 'moves'. Chasing verbs is unbounded; the nouns are not, so the
    // nouns are what grew. `money|funds|fee|amount|transfer` join the list, and the rule stops
    // caring what the money is said to DO once 'only <connector> approv-' follows it.
    // ROUND 4b -- 50 to 80. 'the payment already exists, held in a neutral account, waiting
    // only on the brand's approval of the delivered work' puts 51 characters between the money
    // noun and the 'only'. One character outside a window that was itself tuned to the
    // sentences round 2 happened to have read.
    // ROUND 4b -- the reverse order and the verb 'follows'. The payment-protection blog said
    // 'The contract defines what "approved delivery" means; payment protection enforces that
    // payment only follows that definition.' The approval word is in the FIRST clause and the
    // money word in the second, so a rule that reads money-first sees nothing.
    re: /\b(?:pays?|paid|paying|release\b|releases|released|payout|payouts|payment|payments|money|funds?|fees?|amount|transfers?)\b[^.\n]{0,80}?\bonly\s+(?:after|on|upon|once|when|follows?)\b[^.\n]{0,40}?\bapprov\w*|\bapprov\w*[^.\n]{0,80}?\b(?:pays?|paid|payment|payments|payout|payouts|money|funds?)\b[^.\n]{0,40}?\bonly\s+(?:after|on|upon|once|when|follows?)\b/i,
    why: 'naming approval as the only condition for payment hides the live-post trigger',
    allowNegated: true,
  },
  {
    id: 'approval-named-as-trigger',
    // The reverse word order: 'release is TRIGGERED BY ... approval'. Rules that read left to
    // right from the approval word cannot see this, and it is the phrasing an explainer reaches
    // for when it is describing the mechanism rather than promising it.
    // ROUND 4b -- the REVERSE order too. The payment-protection blog's comparison table
    // asked '| **What triggers final payment** |' in one cell and answered \"Brand's approval\"
    // in the next, so the trigger word comes FIRST and every left-to-right rule walked past
    // the one row in the article that states the mechanism.
    re: /\b(?:release|releases|released|payment|payments|payout|payouts|money|funds)\b[^.\n]{0,50}?\btrigger(?:s|ed|ing)?\b[^.\n]{0,40}?\bapprov\w*|\btrigger(?:s|ed|ing)?\b[^.\n]{0,50}?\b(?:payment|payments|payout|payouts|release|money|funds)\b[^.\n]{0,70}?\bapprovals?\b/i,
    why: 'the trigger is the live post link, not the approval that closes the deliverable',
    allowNegated: true,
    /*
     * ROUND 4b. The reverse-order half needs this. The TDS policy states the mechanism
     * correctly and CONTRASTS it with the wrong one in the same sentence -- 'The live link is
     * what triggers payment -- approval on its own does not.' -- and the denial sits AFTER the
     * match, where the 40-characters-behind negator window cannot reach it. A sentence that
     * names the live link as the trigger is describing the real mechanism whichever order its
     * clauses arrive in; a sentence that only names approval is not. Both are controlled: the
     * TDS sentence in NEGATIVE_CONTROL, the blog's table row in REGRESSION_CONTROL.
     */
    otherSense: /\blive\s+(?:link|post)\b/i,
  },
  {
    id: 'payout-estimated-in-hours',
    // A bare quoted '~N hours' used as a UI value. Every LEGITIMATE hours figure in this product
    // is a defined window somebody committed to -- the 72-hour Hype blitz, the 48-hour grievance
    // acknowledgement, the 24-48h KYC SLA -- and none is written with a tilde. The tilde means
    // 'roughly, we think', which is the shape of an unbacked money promise and nothing else.
    // Without this, `tag: '~24 hours'` sat one line under a body already corrected to '2 working
    // days': a surface contradicting itself on screen, which is the bug this whole gate exists for.
    re: /['"`]\s*~\s*\d+\s*(?:[-\u2013\u2014]\s*\d+\s*)?hours?\s*['"`]/i,
    why: 'an approximate hours figure is only ever used here for payout timing; Influora pays within 2 WORKING DAYS of the live link',
  },
  {
    id: 'brand-review-in-hours',
    re: /\b(?:review|feedback|approve)\w*\b[^.\n]{0,50}?\bwithin\s+\d+\s*(?:[-–—]\s*\d+\s*)?hours\s+of\s+submission\b/i,
    why: 'the brand gets 3 WORKING DAYS to review a submitted draft, not hours',
  },
  {
    id: 'tax-certificate-promised',
    /*
     * ROUND 4. The tax rules above ban SAYING tax is deducted. None of them banned PROMISING
     * the paperwork for a deduction that does not happen: brand-wallet.tsx offered a disabled
     * Form 16A button whose tooltip read 'coming soon', on the same card as a ₹1,48,500 'Total
     * TDS Deducted' figure — while /tds said, correctly, that there is nothing to certify.
     *
     * A certificate is a statement to the Income Tax Department that a specific sum was
     * withheld. Influora withholds nothing, so this is not a feature that is late; it is a
     * feature that cannot exist. 'Coming soon' is therefore not a roadmap note, it is a claim.
     */
    re: /\b(?:Form\s*16A|TDS\s+certificate|tax\s+certificate|TDS\s+statement)\b[^.\n]{0,60}?\b(?:coming|available|arriving|issued|ready)\s+soon\b|\b(?:coming|arriving)\s+soon\b[^.\n]{0,60}?\b(?:Form\s*16A|TDS\s+certificate|tax\s+certificate)\b/i,
    why: 'Influora withholds no tax, so no TDS certificate is pending — nothing may promise one',
    allowNegated: true,
  },
  {
    id: 'tax-data-collected-for-tds',
    /*
     * ROUND 4. kyc-policy.md told every creator their PAN and Aadhaar were collected 'for TDS
     * reporting'. Influora files no TDS return, so that is a purpose the data is not used for
     * — and a stated purpose in a KYC policy is the one sentence a reader is entitled to rely
     * on. It named no deduction and no rate, so none of the round-3 tax rules could see it.
     *
     * The negator exemption is what lets the corrected sentence deny it in place ('...so none
     * of this is collected for TDS reporting'), which is more useful to a reader than silence.
     *
     * The leading verb is load-bearing and deliberately excludes 'require'/'need'. A bare
     * '...for TDS reporting' anywhere on a line also matched the blog's advice to brands about
     * their OWN books — 'a documented, invoiced payment is required for clean accounting and
     * for TDS compliance under the Income Tax Act' — which is true, is about the reader's
     * statutory duty rather than anything Influora does, and must stay sayable. What this rule
     * bans is Influora naming TDS as a PURPOSE it holds data for.
     */
    re: /\b(?:use|uses|used|using|collect|collects|collected|collecting|hold|holds|held|store|stores|stored|share|shares|shared|process|processes|processed|retain|retains|retained)\b[^.\n]{0,60}?\b(?:for|towards?)\s+TDS\s+(?:reporting|filing|returns?|compliance)\b/i,
    why: 'Influora files no TDS return, so no data is collected or held for TDS reporting',
    allowNegated: true,
  },
  /*
   * ROUND 4b -- CLOSE THE FAMILY, STOP CHASING THE LIST.
   * ---------------------------------------------------
   * Rounds 1, 2 and 3 each fixed the strings they were handed, and each time an independent
   * read of the SHIPPED BUNDLE found more of the same claim in a phrasing no rule covered.
   * Round 3 shipped with the HOMEPAGE telling every visitor 'Verified posts auto-release
   * payouts inside the 72-hour window' and 'payout auto-releases', and with /features/hype,
   * index.html and public/llms.txt telling search engines and AI crawlers that a Hype reel is
   * 'paid out automatically once the post is verified'.
   *
   * None of that was a near miss. The rules above between them need either the word 'approval'
   * or the literal 'within N hours', and not one of those sentences contains either. The
   * defect is a FAMILY -- the product making a payment by itself, on a clock it does not keep
   * -- and the rules below are written against the family rather than against the handful of
   * sentences that happened to be reported this time:
   *
   *   auto-prefixed-release        the 'auto-' prefix on any money verb, in any spelling
   *   paid-out-automatically       the adverb attached to any money verb, in either word order
   *   payment-window-in-hours      a payment promised against an hours figure of any size
   *   money-available-on-approval  money changing state on approval, with no verb and no adverb
   *
   * The truth all four are measured against: approving a draft pays NOBODY -- it clears the
   * creator to post. The creator posts and submits the live link; a workspace Owner or Admin
   * then RELEASES the payment, and Influora sends it by bank transfer within 2 WORKING DAYS of
   * that link. No step in that sequence happens by itself, and no hours figure describes any
   * of it.
   */
  {
    id: 'auto-prefixed-release',
    /*
     * The prefix form: 'auto-release', 'auto-releases', 'payout auto-releases', '48-hour
     * auto-release', 'autopay'. Deliberately bare -- the money verb after the prefix is the
     * whole claim, and no amount of surrounding wording makes it true.
     *
     * 'auto-generated' (contracts and invoices really are) and 'autoPlay' (a video attribute)
     * are outside the verb list, so they stay legal; 'auto-approval' has its own rule above.
     */
    re: /\bauto[\s-]?(?:releas|pay|payout|disburs|transferr?|credit|settl|deposit|remit)\w*\b/i,
    why: 'nothing releases or pays itself -- an Owner or Admin releases the payment after the live link',
    allowNegated: true,
  },
  {
    id: 'paid-out-automatically',
    /*
     * The adverb form, in both word orders: 'paid out automatically', 'automatically released',
     * 'automatic payout'. The existing `auto-payment-on-approval` rule already owns the one
     * shape '<money noun> releases automatically', so that shape is deliberately left out here
     * rather than duplicated -- the no-dead-weight test would otherwise report that rule as
     * covered and let it rot.
     *
     * What must stay legal, and is proved by NEGATIVE_CONTROL: funding a slot, generating a
     * contract and generating an invoice ARE automatic, and the copy says so. This rule only
     * fires when the adverb is attached to a verb that MOVES money to a creator.
     */
    re: /\b(?:paid|pays|pay|paying)\s+out\s+automatic(?:ally)?\b|\b(?:credited|disbursed|transferred|settled|remitted|released|deposited|moves?|moved|moving)\s+automatic(?:ally)?\b|\bautomatic(?:ally)?\s+(?:paid|pays|pay|releas\w*|disburs\w*|transferr?\w*|settl\w*|credit\w*|remit\w*)\b|\bautomatic\s+(?:payout|payouts|payment|payments|release|releases|transfer|transfers|settlement|settlements)\b/i,
    why: 'no payment is made or released automatically -- an Owner or Admin releases it after the live link',
    allowNegated: true,
  },
  {
    id: 'payment-window-in-hours',
    /*
     * Any payment promised against an hours figure. `payment-measured-in-hours` above only
     * catches the literal 'within N hours'; the homepage said 'inside the 72-hour window', and
     * the Stitch design notes this product was ported from are full of '48-hour auto-release'
     * and '120h auto-release'. The only true figure is 2 WORKING DAYS, so every hours figure
     * attached to a payment is wrong regardless of the preposition carrying it.
     *
     * The 72-hour POSTING window is real and must stay sayable -- it is the Hype format. That
     * is why this rule needs a money word within 40 characters: 'post inside a 72-hour window'
     * has none, and 'auto-release payouts inside the 72-hour window' has two.
     *
     * `otherSense` carries the clocks that are real and are not payment clocks: the 24-48h KYC
     * SLA (whose sentence names a 'payout verification' and so has a money word in it), the 48h
     * grievance and dispute acknowledgements, and the 24h/72h Instagram insight snapshots.
     */
    re: /\b(?:payout|payouts|payment|payments|paid|pays|release|releases|released|releasing|disburs\w*|settle(?:d|ment)?|transfer(?:red|s)?)\b[^.\n]{0,40}?\b(?:in|inside|after|of|by|under|within|over)\s+(?:the\s+|a\s+|an\s+)?\d+\s*[-–—]?\s*hours?\b|\b\d+\s*[-–—]?\s*hour\s+(?:payout|payment|release|settlement|transfer)\b|\b(?:payout|payment|release|settlement)\s+(?:window|clock|timer|SLA)\b[^.\n]{0,25}?\b\d+\s*hours?\b/i,
    why: 'Influora pays within 2 WORKING DAYS of the live link; no hours figure describes any payment',
    otherSense: /\bKYC\b|\bgrievance\b|\backnowledg\w*|\bdispute\b|\bsnapshot\b/i,
  },
  {
    id: 'money-held-until-approval',
    /*
     * ROUND 4b. 'held ... UNTIL you approve'. `money-held-pending-approval` above needs the
     * word 'pending' or 'awaiting'; nothing needed 'until', and 'until' is the word this
     * product actually reaches for at the moment of commitment:
     *
     *   'Funds will be held securely until you approve the work'        (proposal-form.tsx,
     *        rendered directly under 'Total You Pay', at the instant a brand commits)
     *   'Rs X will be held securely until deliverables are approved.'   (brand-campaign-detail)
     *   'protection that holds the brand's payment until the deliverable is approved'  (about)
     *   'Your money does not move until you approve the work'           (how-it-works/brands H2)
     *
     * Four surfaces, one preposition, and no rule in this file could see any of them. The
     * about.tsx line had never been opened by any of the three earlier rounds.
     *
     * Keyed on approval as the CONDITION -- the noun 'approval', or a clause whose subject
     * approves -- never on the adjective 'approved'. That is what lets the replacement copy
     * say 'Funds stay secured until the approved post is live and its link has been submitted':
     * there, approval is a property of the post and the condition is the post being live. Both
     * are in NEGATIVE_CONTROL, so the distinction is proved rather than asserted. No money-noun
     * prefix, because the money is named before the hold verb in two of the four and after it
     * in the other two.
     */
    re: /\b(?:held|holds|hold|stays?|sits?|locked|secured?|reserved|protected|waits?|waiting|remains?|does\s+not\s+move|doesn'?t\s+move)\b[^.\n]{0,60}?\b(?:until|till)\b[^.\n]{0,40}?(?:\bapprovals?\b|\b(?:you|the\s+brand|they|we|it)\s+approves?\b|\b(?:the\s+)?(?:work|deliverable|deliverables|content|draft|drafts)\s+(?:is|are)\s+approved\b|\bapproved\s+(?:content|work|deliverable|deliverables|draft|drafts)\b)/i,
    why: 'the money waits on the live post link and a release by an Owner or Admin, not on approval',
    allowNegated: true,
  },
  {
    id: 'approval-as-the-condition',
    /*
     * ROUND 4b. Approval named as the CONDITION the money is waiting on, with no release verb,
     * no adverb, no clock and no 'until'. The payment-protection explainer -- the blog post
     * that ranks for the thing this company sells, and a file no round had opened -- carried it
     * four times, including in its meta excerpt:
     *
     *   'waiting only on the brand's approval of the delivered work'
     *   "it's tied to an agreed, verifiable condition (approval of the deliverable)"
     *
     * The first of those was 51 characters past the money noun, and `paid-only-after-approval`
     * allowed 50. It was not nearly caught; it was caught by nothing, because a distance is a
     * memory of the sentences already read. That rule's window is widened to 80 in the same
     * pass, and this rule drops the distance test altogether and keys on the grammar instead.
     */
    re: /\b(?:waiting|waits|wait|depends|depend|depending|conditional|contingent|tied|subject|hinges?|rests?|predicated)\b[^.\n]{0,40}?\b(?:on|upon|to)\b[^.\n]{0,60}?\bapprovals?\b/i,
    why: 'the condition is the live post link, then a release by an Owner or Admin -- approval only clears the post to go live',
    allowNegated: true,
  },
  {
    id: 'release-triggered-by-verification',
    /*
     * ROUND 4b. The OTHER wrong trigger. Every rule above names approval as the false cause;
     * /features/hype named VERIFICATION instead -- 'Payouts release per creator on verification
     * -- no manual tracking across 100 people' -- which is the same claim wearing the Hype
     * campaign's vocabulary, and no approval rule could see it.
     *
     * Verifying a post is real and it is a precondition. It is not the cause: an Owner or Admin
     * still has to release the Payout, and Influora still pays it by bank transfer within 2
     * working days. 'No manual tracking' was the tell -- the sentence was selling the absence
     * of the human step that actually exists.
     *
     * Deliberately NOT folded into `release-triggered-by-approval`: adding `verif` to that
     * rule's trailing noun would make it a second catcher of the /features/hype sentence
     * 'paid out automatically once the post is verified', which is `paid-out-automatically`'s
     * sole-catcher control -- and the no-dead-weight test would then report that rule as
     * covered, which is how a rule quietly stops being load-bearing.
     */
    re: /\b(?:payout|payouts|payment|payments|funds?|fee|money)\b[^.\n]{0,30}?\b(?:release|releases|released|releasing|pays?\s+out|paid\s+out|lands?)\b[^.\n]{0,30}?\b(?:on|upon|once|when|after)\b[^.\n]{0,30}?\bverif\w*/i,
    why: 'verification is a precondition, not the trigger -- an Owner or Admin releases the Payout after the live link',
    allowNegated: true,
  },
  {
    id: 'money-available-on-approval',
    /*
     * ROUND 4b. The shape with no release verb and no adverb: money CHANGING STATE on
     * approval. `CreatorScenes.tsx` (the creator film, still shipping as a rendered mp4) said
     * 'Not withdrawable yet -- it moves to Available Balance once you deliver and it is
     * approved', and `creator-script.ts` quoted it back as its accuracy source. Every rule
     * above needs a release verb ('moves' is not one), the word 'pending', or an adverb, so
     * all of them walked past it -- while the wallet tooltip it claimed to be quoting had
     * ALREADY been corrected to 'once your post is live and the brand releases it'. A film
     * citing a string that no longer exists is how a fixed claim comes back.
     *
     * NO NEGATOR EXEMPTION, deliberately, and for the same reason `tax-rate-applies-to-you`
     * has none: the real sentence OPENS with 'Not withdrawable yet'. An exemption keyed on
     * that 'Not' would wave through the exact line this rule was written to catch. The
     * corrected sentence passes because it names the live post instead of the approval, not
     * because it negates anything, and it is in NEGATIVE_CONTROL to prove it.
     */
    re: /\b(?:moves?|moved|becomes?|became|turns?|converts?|unlocks?|opens?|clears?)\b[^.\n]{0,40}?\b(?:available|yours|withdrawable|payable|spendable|usable)\b[^.\n]{0,60}?\b(?:on|once|when|after|upon)\b[^.\n]{0,40}?\bapprov\w*/i,
    why: 'money does not become available on approval -- it follows the live post link and a release by an Owner or Admin',
  },
  {
    id: 'money-transferred-to-a-destination-on-approval',
    /*
     * ROUND 5. The shape that shipped to real creators on the screen where they SIGN, with this
     * gate fully green -- `creator-contract-panel.tsx`'s "Your Earnings" footnote:
     *
     *   'Amount will be transferred to your wallet upon final approval and deliverable
     *    completion.'
     *
     * Two false claims in eighteen words, and every rule above walked past both:
     *
     *   THE TRIGGER. Every approval rule here is keyed on a RELEASE or a PAY verb
     *   (`release|releases|releasing|pays out|paid out|is paid`), on the word `pending`, on an
     *   adverb, or on a STATE change (`moves to Available Balance`). 'transferred' is none of
     *   those. It is the verb a sentence reaches for when it describes the money ARRIVING
     *   rather than being let go of, which is exactly what a UI footnote writes -- and
     *   `paid-only-after-approval` does carry `transfers?` in its money-noun list, but fires
     *   only on the word 'only', which a footnote has no reason to use.
     *
     *   THE DESTINATION. There is no wallet payout to a creator and no self-serve withdrawal.
     *   Influora pays by BANK TRANSFER to the payout details on the creator's profile. A line
     *   naming a wallet or a balance as where the money lands is describing a product that does
     *   not exist, whatever it says about the trigger.
     *
     * So the rule is verb + DESTINATION + connector + approval, and the destination is what
     * keeps it off the honest copy. 'Influora pays you by bank transfer to the account in your
     * payout details, within 2 working days of your post going live and the link being
     * submitted' has the verb and the destination and no approval word; clause 7 of the
     * generated contract has the verb, the destination AND a connector ('to the account on the
     * Creator's profile') and still no approval word within reach. Both are in NEGATIVE_CONTROL.
     */
    re: /\b(?:transferred|transfers?|transferring|credited|credits?|deposited|deposits?|sent|sends?|added)\b[^.\n]{0,30}?\b(?:wallet|balance|account|payout\s+details|bank|UPI)\b[^.\n]{0,40}?\b(?:on|upon|once|when|after)\b[^.\n]{0,40}?\bapprov\w*/i,
    why: 'approval pays nobody, and nothing lands in a wallet -- Influora bank-transfers the payout after the live link is submitted and an Owner or Admin releases it',
    allowNegated: true,
  },
];

/**
 * The real sentence each rule was written against, taken verbatim from the tree as it stood
 * before this pass (commit 467b381). These are the strings the falsification run re-creates.
 */
const POSITIVE_CONTROL: Record<string, string> = {
  'payment-measured-in-hours':
    'Payouts are processed within 24-48 hours on business days',
  // The hero stat on /how-it-works/creators, which named approval as the thing being timed.
  'payout-time-after-approval': 'Typical payout time after approval',
  'payment-in-plain-days':
    'Payment will be released from secured funds within 7 days of brand approval of final deliverables.',
  'self-serve-withdrawal':
    'No payout methods yet. Add a UPI ID or bank account to withdraw funds.',
  'auto-approval':
    "handled per the Campaign's default approval terms (auto-approval or escalation, as agreed at Campaign setup).",
  // Was 'Once the brand approves, the payment releases to you automatically...'. That sentence is
  // now ALSO caught by approval-causes-payment, which would have cost this rule its sole-catcher
  // status and let it rot unnoticed. Swapped for the other real old string carrying the same
  // defect -- the /features/secure-payments FAQ answer -- where the approval word FOLLOWS the
  // verb, so only this rule fires on it.
  'auto-payment-on-approval':
    'The payment releases automatically the moment the brand approves the deliverable.',
  'release-triggered-by-approval': 'Payment will be released upon approval of final deliverables',
  'approval-causes-payment': 'Approving the deliverable releases the payment and generates the invoice.',
  'paid-only-after-approval':
    'Brands hire verified creators, agree scope and price in a shared Deal Room, and pay only after they approve the delivered work.',
  'payout-estimated-in-hours': "    tag: '~24 hours',",
  'approval-named-as-trigger':
    'release is triggered by the same approval action that closes the deliverable in the thread.',
  'tax-deducted-for-you':
    'the payment releases the Payout to the creator, minus the creator commission and any tax that must be deducted by law.',
  // ROUND 3 — the /tds route's meta description in App.tsx, the string crawlers read first.
  'tax-handled-for-you': 'How tax deducted at source (TDS) is handled on creator payouts on Influora.',
  // tds-policy.md section 4, verbatim. Chosen over the '...higher TDS rate applies' bullet
  // beneath it because that one carries a 'not', and a control that only passes while a rule
  // has no negator exemption proves nothing about the rule.
  'tax-rate-applies-to-you':
    'If you have **furnished your PAN** to Influora and your total payments in a financial year are within the threshold set by law, a lower TDS rate (or an exemption below the threshold) applies.',
  // tds-policy.md section 5, verbatim — the noun form of the same false claim.
  'tax-deducted-from-your-payout':
    "Don't confuse a TDS deduction on your Payout with GST charged on the Platform fee — they are separate taxes and are never combined into one figure.",
  'brand-review-in-hours':
    'Brand shall review and provide feedback within 48 hours of submission.',
  // ROUND 4 — the timeline payment card's `escrow_locked` description, verbatim. The only one
  // of the ten with no verb in it at all.
  'money-held-pending-approval': 'Funds secured pending content approval',
  // ROUND 4 — the brand wallet's Form 16A tooltip, verbatim (HTML-escaped as it ships).
  'tax-certificate-promised': 'Form 16A isn&apos;t available yet — coming soon.',
  // ROUND 4 — kyc-policy.md section 1, verbatim.
  'tax-data-collected-for-tds':
    'We also use KYC to confirm your identity for TDS reporting and to reduce fraud on the platform.',
  // ROUND 4b -- the homepage, verbatim. The second homepage claim rather than the first,
  // because the first ('...auto-release payouts inside the 72-hour window') carries an hours
  // figure too and so is caught by `payment-window-in-hours` as well; a control two rules catch
  // proves nothing about either.
  'auto-prefixed-release':
    "    body: 'One-tap accept on flat-rate remix campaigns. Post inside the window, payout auto-releases.',",
  // ROUND 4b -- the /features/hype canonical answer, verbatim. The same sentence is the JSON-LD
  // description, the meta description and the visible hero copy, and it is what index.html and
  // public/llms.txt serve to AI crawlers as fact.
  'paid-out-automatically':
    "  'accepted slot is funded up front and paid out automatically once the post is verified.';",
  /*
   * ROUND 4b. The homepage sentence with its 'auto-' REMOVED, which is the one control in this
   * file that is not verbatim, and it is deliberate. The shipped line was 'Verified posts
   * auto-release payouts inside the 72-hour window'; the obvious way to "fix" it is to delete
   * the prefix, which silences `auto-prefixed-release` and leaves the false 72-hour payment
   * window standing on the homepage. This control is that half-fix, pinned as must-catch, so
   * the hours rule is proved independently of the prefix rule.
   */
  'payment-window-in-hours': 'Verified posts release payouts inside the 72-hour window',
  // ROUND 4b -- the creator film, verbatim (CreatorScenes.tsx). Two banned claims in one
  // sentence: the withdrawal (legal here only because 'Not' denies it) and the state change on
  // approval, which is what this rule catches.
  'money-available-on-approval':
    'Not withdrawable yet — it moves to Available Balance once you deliver and it is approved.',
  // ROUND 5 -- creator-contract-panel.tsx, verbatim, as it shipped: the footnote under "You
  // Receive" in the sheet the creator reads before signing.
  'money-transferred-to-a-destination-on-approval':
    'Amount will be transferred to your wallet upon final approval and deliverable completion.',
  // ROUND 4b -- /features/hype's BRAND_NEW_WAY list, verbatim.
  'release-triggered-by-verification':
    "  'Payouts release per creator on verification — no manual tracking across 100 people',",
  // ROUND 4b -- proposal-form.tsx, verbatim: the line under 'Total You Pay', which is the last
  // thing a brand reads before committing money.
  'money-held-until-approval': 'Funds will be held securely until you approve the work',
  // ROUND 4b -- the payment-protection blog's comparison of the two models, verbatim.
  'approval-as-the-condition':
    "- **Payment protection:** Funds are committed before work starts, but neither side controls the release — it's tied to an agreed, verifiable condition (approval of the deliverable).",
};

/** The copy shipped by this pass — none of it may trip a rule. */
const NEGATIVE_CONTROL = [
  'Influora pays you by bank transfer (NEFT/IMPS) to the account in your payout details, within 2 working days of your post going live and the link being submitted.',
  'Payment is released after the post is live and its link is submitted. Influora pays the creator within 2 working days of that link.',
  'The brand has 3 working days to review your draft.',
  'Each round gives the Brand 2 working days to re-review the resubmitted draft.',
  'Approval clears the post to go live. It pays nobody yet.',
  'Released to you. Influora transfers it to the account in your payout details — you never have to request it.',
  'A transfer Influora has already started to your bank or UPI — already deducted from Available Balance, not yet confirmed as landed.',
  // ROUND 5 -- the replacement footnote in creator-contract-panel.tsx, and clause 7 of the
  // generated contract. Both carry the transfer verb AND a destination noun AND (in clause 7)
  // a connector, and both must stay sayable: what makes them honest is that they name the live
  // link, not an approval.
  'Post your content, then submit the live link. Once a workspace Owner or Admin releases the payment, Influora sends it by bank transfer to your saved payout details within 2 working days.',
  "Influora then pays the Creator by bank transfer (NEFT/IMPS) to the account on the Creator's profile within 2 working days of that link.",
  'Payments are made in accordance with applicable law.',
  'Working days are Monday to Friday.',
  // Unrelated clocks that legitimately speak in hours or plain days must stay legal.
  'We aim to verify KYC submissions within 24–48 hours of a complete, correctly submitted application.',
  'We acknowledge your request within 48 hours.',
  'All deliverables must be submitted within 14 days of contract signing.',
  // Consent and applications are withdrawn; money is not.
  'Withdraw consent — at any time, for processing based on consent.',
  'This removes the stored pixel id — use this when the sponsor withdraws tracking consent.',
  // Round 3 — the copy this pass shipped, and the honest denials already in the tree.
  'Influora sends this to your payout account',
  'Payouts land in your wallet after each completed deal. Add your bank account in Payout',
  'Settings so Influora can transfer them to you.',
  'How Indian TDS rules relate to creator payouts on Influora. Influora does not deduct tax from your payout today.',
  'Influora deducts no tax from a creator Payout.',
  'Nothing is taken out of that Payout for tax.',
  'There is nothing to certify. Influora has not withheld tax from any Payout, so no Form 16A or TDS statement is due from us, and none is available in the app.',
  '- **TDS** is a withholding a payer makes under the Income Tax Act. Influora withholds nothing from your Payout.',
  'Your PAN does not change what you are paid today, because nothing is withheld either way.',
  'Indian tax law treats payments made without a PAN on record far less kindly (Section 206AA is the usual example).',
  'PAN, Aadhaar & selfie — required before Influora can pay you',
  'Sent to HDFC ••4417',
  'Influora deducts no tax at source (TDS) from a creator Payout, and its invoices carry no TDS line.',
  'Influora doesn&apos;t take any tax out of your payouts, so there is no Form 16A to issue here.',
  'Whether any TDS provision reaches an Influora payout is a question still with its Chartered Accountant; the position is published at /tds and would change there first.',
  'Tax on your payouts',
  // The DPDP rights the privacy policy is legally required to state, verbatim.
  '- **Your consent** — given when you sign up, given clearly and specifically, and withdrawable at any time (withdrawal doesn\'t affect processing already done).',
  '4. **Withdraw consent** — at any time, for processing based on consent.',
  '                Clear (withdraw consent)',
  "  APPLICATION_WITHDRAWN: 'Withdrawn',",
  'There is no withdrawal for you to request — we send it.',
  'Creators never have to request a withdrawal.',
  // Machine tokens: the endpoint still exists, so the identifier must stay sayable in code.
  "export type MoneyOperation = 'topup' | 'withdraw' | 'escrow-fund';",
  "  const payoutsBlocked = isMoneyActionBlocked('withdraw');",
  "      ? http.request<{ payoutId: string }>('POST', '/wallet/withdraw', {",
  "    case 'WITHDRAWAL':",
  // Round 2 — the replacement copy for the approval-trigger surfaces.
  'Approving clears the post to go live. Payment follows the live link, not the approval.',
  'Payment follows the live post, not the approval',
  'Approve the draft, the creator posts, and the fee goes out within 2 working days of the live link.',
  'Payment is released once the post is live and its link has been submitted.',
  'Funds stay secured until the approved post is live and its link has been submitted.',
  'The post is already live, so the payment has gone to the creator.',
  'Deliverable approved — payment is ready to release',
  // Approval genuinely IS the gate for going live, and saying so must stay legal — otherwise the
  // honest replacement copy cannot be written at all.
  'Nothing is posted until you approve it.',
  'You approve the draft before anything goes live.',
  /*
   * ROUND 4 — the copy this pass shipped, plus the three sentences the strengthened rules must
   * NOT flag. A rule that catches all ten survivors and also every honest sentence beside them
   * has not been strengthened, it has been broken; these are what holds that line.
   */
  // Describing the real mechanism. The brand DOES release, from that panel — the gate bans
  // naming approval as the trigger, not naming the control that does it.
  "Once the post is live, release the payment from the deal's Payments panel.",
  'Approve the work here. Once the post is live, release {formatINR(dealValue)} from the Payments panel.',
  // The one case where approval really does pay nobody, explained honestly.
  'The work is approved, but secured funds for this deal were refunded to your wallet, so nothing was paid out. Check the Payments panel for this deal.',
  // An enumeration of dispute TOPICS in the Terms and the Grievance policy, not a causal claim.
  "Campaign-specific disagreements (delivery, approval, payment protection release) go through our **Dispute Resolution Policy**, not this one.",
  // The replacement copy for the ten.
  'Money stays secured until the post is live.',
  'Funds secured until the post is live',
  'Held before filming · released after your post goes live',
  'Funds secured for ongoing campaigns. Released once the post is live and its link is in.',
  'Funds lock before work starts and release once the post is live. Nobody chases payments.',
  'You approve the draft, then the creator posts. Once the live link is in, an Owner or Admin releases the payment and Influora pays the creator by bank transfer within 2 working days.',
  '- Release the **Payout** once the post is live and its link has been submitted, and pay it to the creator by bank transfer within 2 working days',
  'Approval clears the post to go live. The funds are released once that post is live and its link has been submitted.',
  // Advice to a brand about its OWN statutory duty. True, and not a claim about Influora.
  '- **For brands**: a documented, invoiced payment is required for clean accounting and for TDS compliance under the Income Tax Act.',
  "the agreed amount is held securely until the creator's post is live and its link is in. An Owner or Admin then releases it, and Influora pays the creator by bank transfer within 2 working days.",
  'and are released once the post is live and its link is submitted. Influora then pays the creator by bank transfer within 2 working days.',
  // The tax paperwork that does not exist, said plainly instead of promised.
  'Influora withholds no tax, so there is no Form 16A to issue.',
  'Influora files no TDS return, so none of this is collected for TDS reporting.',
  'Influora withholds no tax from your payments. Any TDS your business owes is yours to deduct and file — check with your CA.',
  /*
   * ROUND 4b -- the true sentences the family rules must NOT flag. Several describe things that
   * genuinely ARE automatic (funding a slot, generating a contract, generating an invoice) and
   * several describe hours clocks that are real and are not payment clocks. A rule that catches
   * the homepage and also these has not closed the family, it has broken the copy.
   */
  'Funding locks automatically each slot as it fills',
  'Each accepted slot is funded from the brand’s reserved budget automatically, so every creator who accepts knows the rate is set aside before they post.',
  'Every accepted slot is funded and payment-protected automatically',
  'Every payout generates an invoice automatically, so both sides have a clean record for their books.',
  'The Free tier includes auto-generated contracts and e-signature, payment protection on every deal.',
  'Creators film and post within the 72-hour window using the source reel or audio.',
  'A Hype Campaign is a 72-hour blitz. Drop one source reel, set a flat per-reel rate, and cap the slots.',
  '- **KYC**: creator KYC (identity + payout verification) typically completes in 24-48 hours.',
  'I have saved the 24-hour snapshot as proof, and I will send the 72-hour reach to the brand.',
  // The replacement copy this pass shipped.
  "  { step: '4', text: 'Each creator submits the live link, then you release that payout' },",
  'One-tap accept on flat-rate remix campaigns. Post inside the window, submit the live link, and Influora pays you by bank transfer within 2 working days.',
  'accepted slot is funded up front, and Influora pays each creator by bank transfer within 2 working days of their live post link.',
  'Release each payout as its post goes live — no manual tracking across 100 people',
  'Your money stays secured until the post is live',
  'stays secured until the approved post is live and its link is in.',
  "protection that holds the brand's payment until the creator's post is live and its link is in.",
  'Influora withholds no tax from your payouts, so there is no Form 16A or annual TDS statement to issue.',
  'Not yours yet — it moves to Available Balance once your post is live and the brand releases it.',
  "Funds a brand has locked for a deal that's still in progress. Not yours yet — it moves to Available Balance once your post is live and the brand releases it.",
  'and nothing is released until the post is live and its link is in.',
  "{ label: 'Released', state: 'done', sub: 'Live link submitted' },",
  'Your payment is secured before you start. Submit the draft, post once it is cleared, and send the live link — Influora pays you by bank transfer within 2 working days of that link.',
  /*
   * ROUND 4b -- 'until' and 'waiting on' in their honest forms. Every one of these names the
   * live post as the condition and carries the word 'approved' as a property of that post.
   * `money-held-until-approval` and `approval-as-the-condition` must read the difference, or
   * the corrected copy cannot be written at all.
   */
  'Funds stay secured until the approved post is live and its link has been submitted',
  'Rs 40,000 stays secured until the approved post is live and its link is in.',
  'Money stays secured until the post is live.',
  "Payment protection in influencer marketing holds a brand's payment until the creator's post is live and its link is in.",
  "The money already exists in a locked state — it's just waiting on an objective condition: the post going live, with its link submitted.",
  "the payment already exists, held in a neutral account, waiting only on the creator's post going live and its link being submitted.",
  'Neither the brand nor the creator can move reserved funds outside the release or dispute process.',
  'The contract defines what the brand is agreeing to; payment protection keeps the money set aside until the post is live and its link is in.',
  'Funds stay held until the dispute resolves',
  'Nothing is posted until you approve it.',
  // The TDS policy states the real mechanism and denies the wrong one in the same sentence.
  'The live link is what triggers payment — approval on its own does not.',
  '1. The brand approves your draft, you post within the campaign window, and you submit the live link. The live link is what triggers payment — approval on its own does not.',
  'The contract generates itself. Your money is held safely until the approved post is live and its link is in.',
  "The brand's money stays locked and refundable until the approved post is live and its link is in.",
  "When a brand funds a Campaign, the amount is reserved in the brand's Influora wallet until the approved post is live and its link has been submitted, or a dispute is resolved.",
  'Creator payments are separate: they stay in Secure Payments until the approved post is live and its link is in.',
];

/**
 * Denials of the banned claims, each paired with the affirmative version of the SAME sentence.
 *
 * Both halves are asserted: the denial must pass (or the honest copy cannot be written at all)
 * and the assertion must fail (or the negator exemption has quietly disabled the rule). Spelling
 * the pair out beats deriving the assertion by deleting "not" — that trick produces sentences
 * whose banned noun has also gone, so the rule correctly stays silent and the check passes for
 * the wrong reason.
 */
const NEGATED_CONTROL: ReadonlyArray<{ denied: string; asserted: string }> = [
  {
    denied: 'It is not approved automatically and no payment is triggered.',
    asserted: 'It is approved automatically and a payment is triggered.',
  },
  {
    denied: 'It is **never approved automatically** and it never triggers a payment.',
    asserted: 'It is **approved automatically** and it triggers a payment.',
  },
  {
    denied: 'Your balance is never withdrawable by you; Influora sends it.',
    asserted: 'Your balance is withdrawable by you.',
  },
  {
    denied: 'The Payout is not yours to withdraw to a bank account.',
    asserted: 'The Payout is yours to withdraw to a bank account.',
  },
  {
    denied: 'Approving a draft is not what releases the payment.',
    asserted: 'Approving a draft is what releases the payment.',
  },
  {
    denied: 'Payment is never released on approval alone.',
    asserted: 'Payment is released on approval alone.',
  },
  {
    denied: 'The creator is not paid only after the brand approves.',
    asserted: 'The creator is paid only after the brand approves.',
  },
];

/**
 * ROUND 2 — the strings that survived round 1 with this gate fully green.
 *
 * Round 1 added nine rules, every one of which needed a duration word or the adverb
 * 'automatically'. Fifteen surfaces stated the wrong trigger without either, so the gate passed
 * over them: five marketing/brand surfaces and a JSON-LD featureList in a file round 1 had
 * already edited 126 lines away, a `tag` still reading '~24 hours' one line under a body round 1
 * had just corrected to '2 working days', the BRAND half of a steps file whose CREATOR half was
 * fixed, three creator emails hidden behind Java string concatenation, and the two GEO surfaces
 * (index.html, public/llms.txt) that answer search and AI crawlers.
 *
 * Each string below is verbatim from the tree at that moment. They are asserted CAUGHT, so the
 * specific sentences that got through once cannot get through again — a rule may be rewritten,
 * but not in a way that re-opens this exact hole.
 */
const REGRESSION_CONTROL: ReadonlyArray<string> = [
  "  { value: 'On approval', label: 'Payment releases only after you approve the work' },",
  "    body: 'Approving the deliverable releases the payment and generates the invoice.',",
  "    label: 'Payment releases only after you approve the work',",
  '                    <li>Payment will be released upon approval of final deliverables</li>',
  '                  Funds will be released when all deliverables are approved',
  "            'Protected payments released on approval',",
  "    tag: '~24 hours',",
  "      title: 'Approve the work — the payment releases',",
  "    body: 'On approval, the payment releases to the creator automatically. They post within the campaign window and you track performance from your dashboard.',",
  "  'Funds secured before filming and released only when you approve.',",
  '                the window closes. Each approved reel pays out automatically.',
  "  '- **Brands** know funds are only released once they approve the Deliverable.'",
  "  lockCaption: (amount: string) => `${amount} secured. Released only on your approval.`,",
  "      <Caption kicker=\"Stage 7 — Approve and pay\" text=\"Approve releases the payment\" />",
  // The two GEO surfaces. These are served to crawlers, so a wrong answer here outlives the page.
  'Brands hire verified creators, agree scope and price in a shared Deal Room, and pay only after they approve the delivered work.',
  'and release to the creator only after deliverables are approved.',
  // Found by re-grepping AFTER the gate went green, which is the only reason they are here: a
  // green gate is evidence about the rules, not about the tree.
  "          reassurances={['Free to join', 'Paid after approval', 'Invoices generated for you']}",
  'and release is triggered by the same approval action that closes the deliverable in the thread.',
  // A THIRD JSON-LD description, on a page round 1 had already edited. Structured data is the
  // easiest copy in the tree to miss by eye and the worst to get wrong: crawlers quote it.
  "            'A creator builds a verified profile with a rate card, receives or applies to campaigns, agrees scope in a Deal Room, e-signs a contract, delivers the work, and is paid automatically once the brand approves.',",
  // The three creator emails, as one folded Java concatenation run each.
  '+ "- Payment is secured before you start, and released when your" + " work is approved",',
  '+ " to see the opportunity, agree terms, and get paid once the brand approves your work.",',
  /*
   * ROUND 3 — the four Meera verified on the SHIPPED OUTPUT of round 2, plus the same shapes
   * found beside them. Round 2's gate was green with every one of these in the tree.
   */
  "                        : 'Ready to withdraw'}",
  '              Payouts land in your wallet after each completed deal. Add bank/UPI on first withdrawal.',
  '              description="How tax deducted at source (TDS) is handled on creator payouts on Influora."',
  "- **TDS**, where it applies, is deducted from the **creator's Payout** and paid to the government on the creator's behalf.",
  '- If you have **not furnished your PAN**, a significantly higher TDS rate applies under provisions like Section 206AA, regardless of your payment amount.',
  'Where tax has been deducted from your Payout, a certificate will be provided as Indian tax law requires.',
  'Where TDS applies to a creator Payout, it is handled as Indian tax law requires.',
  "          description: 'PAN, Aadhaar & selfie — required before your first withdrawal',",
  '                Withdrawn to HDFC ••4417',
  // Folded in when `withdrawals-coming-soon` was deleted: the rule went, the sentence stays.
  'Withdrawals open shortly',
  /*
   * ROUND 3b — found by RUNNING the strengthened rules over the tree, not by reading the
   * four survivors' files. Three more surfaces claimed tax was handled, including the Terms
   * both parties accept and the llms.txt an AI crawler reads as fact.
   */
  'Where Indian tax law requires tax to be deducted at source (TDS) from a creator Payout, it is handled as that law requires; see our TDS Policy.',
  'TDS isn&apos;t calculated automatically in the app, and Form 16A isn&apos;t available here yet. If tax is deducted at source on a payout, write to info@influora.in for the details, and speak to your CA about your filing.',
  'Where TDS applies to a payment, it is handled as Indian tax law requires; check the details with your CA.',
  /*
   * ROUND 4 — Kavya's independent sweep of the SHIPPED round-3 tree. Ten surfaces were still
   * telling a customer that approving a draft pays the creator, which is the exact defect this
   * gate was commissioned to remove; re-running the strengthened rules over the tree turned up
   * fourteen more of the same shape, including the Terms both parties accept and the llms.txt
   * an AI crawler reads as fact.
   *
   * Every line below is verbatim from the tree as round 3 shipped it, source indentation and
   * template placeholders included (the scanner reads source lines, not rendered output). Where
   * the source line runs to a full paragraph, the clause carrying the claim is pinned instead,
   * and that is noted.
   *
   * They are pinned as must-catch so no later rewrite of a rule can re-open the specific hole
   * each one walked through.
   */
  // Verb alternation: bare 'release' and the gerund 'releasing' were missing.
  "    body: 'Funds lock before work starts and release on approval. Nobody chases payments.',",
  "            'The six steps a creator takes on Influora, from building a verified profile to the payout releasing after the brand approves the work.',",
  '            Approve the work here, then release {formatINR(dealValue)} to the creator from the Payments panel.',
  // …the brand approves, and only then does the payment release — (landing.tsx FAQ answer,
  // clause pinned: the source line is a 340-character paragraph).
  'the brand approves, and only then does the payment release',
  "  approveAndRelease: 'Approve & release',",
  // No money noun in the sentence at all — the rupee figure is rendered on the line ABOVE.
  '                    Held before filming · releases on approval',
  // Money noun stranded in the PREVIOUS sentence, behind a full stop `[^.\n]` cannot cross.
  '                  Funds secured for ongoing campaigns. Released upon deliverable approval.',
  // No verb at all: the shape a status chip reaches for, because a chip has no room for one.
  "      description: 'Funds secured pending content approval',",
  // The 40-character approval→verb window, missed by three characters.
  '                    Once you approve the deliverables, funds are automatically released to the creator.',
  // The Meera trust copy, and the same two sentences hardcoded a second time in FundEscrowButton.
  "  releaseNote: 'Money moves only when you approve.',",
  "  funding: 'Money moves only when you approve',",
  '          Money moves only when you approve.',
  'secured. Released after you approve and the post is live.',
  // The rest of the same shape, found by RUNNING the strengthened rules over the tree.
  'Secure ${formatINR(dealValue)} from the Payments panel, then release it to the creator once the work is approved.',
  '- Release the **Payout** to the creator once the brand approves the work',
  '- A creator cannot release their own Payout early — release only happens on brand approval or a dispute outcome.',
  'In short: your money is always protected by a defined process — payment protection release on approval, or a dispute-resolution outcome.',
  '| Brand delays or skips payment after posting | Creator has no leverage, chases for weeks | Funds are already reserved and release automatically on approval |',
  'featuredImageAlt: "Diagram showing a brand\'s payment moving into a protected balance and releasing to a creator after content approval"',
  'Once the brand approves the deliverable, the funds release to the creator automatically.',
  'the agreed amount is held securely and released to the creator once you have approved the draft and their post is live.',
  'and release to the creator once the approved deliverable is posted live and its link is submitted.',
  /*
   * ROUND 4b -- Kavya's independent read of the SHIPPED round-3 BUNDLE. Round 3's gate was
   * fully green with every line below in the tree, including the two on the HOMEPAGE and the
   * three GEO surfaces that answer AI crawlers. Verbatim, source indentation included, except
   * where noted.
   */
  "  { step: '4', text: 'Verified posts auto-release payouts inside the 72-hour window' },",
  "    body: 'One-tap accept on flat-rate remix campaigns. Post inside the window, payout auto-releases.',",
  "  'accepted slot is funded up front and paid out automatically once the post is verified.';",
  'description="Launch a 72-hour Hype Campaign: set a flat per-reel rate, cap the slots, and let up to 100 creators accept with one tap. Each reel is paid out automatically."',
  // Wrapped across two JSX lines in the shipped tree; pinned here as the folded sentence,
  // which is exactly what `foldLines` hands the rules.
  'post before the window closes. Each approved reel is paid out automatically.',
  "A Hype Campaign is Influora's multi-creator format: a brand posts one source reel at a single flat per-reel rate, caps the number of slots, and up to 100 creators accept with one tap and post inside a 72-hour window. Each accepted slot is funded up front and paid out automatically once the post is verified.",
  "  'Payouts release per creator on verification — no manual tracking across 100 people',",
  // Two banned claims in one sentence, in the creator film and in the script that cites it.
  'Not withdrawable yet — it moves to Available Balance once you deliver and it is approved.',
  "withdrawable yet — moves to Available Balance once you deliver and it's approved.",
  // The half-fix: delete the prefix and the false payment window is still on the homepage.
  'Verified posts release payouts inside the 72-hour window',
  /*
   * The other shapes this family takes, pinned so a later rewrite cannot re-open the hole by
   * covering only the spellings that happened to ship. These are not tree strings -- they are
   * the neighbouring phrasings of the same claim, including two lifted from the Stitch design
   * notes this product's payment pages were ported from.
   */
  '48-hour auto-release to a creator wallet',
  'Payouts are auto-released 120h after the post goes live.',
  'Your payout is credited automatically once the brand approves.',
  'Funds are automatically transferred to the creator.',
  'Automatic payout on verification.',
  'Payout inside 48 hours of approval.',
  'Expect your payment in 24 hours.',
  /*
   * ROUND 4b, SECOND SWEEP -- found by RUNNING `money-held-until-approval` and
   * `approval-as-the-condition` over the tree AFTER the four surfaces they were written
   * against were fixed and the gate was green. A green gate is evidence about the rules, not
   * about the tree. Four more surfaces, in four files nobody had named, including the
   * payment-protection POLICY's opening sentence and the pricing FAQ that answers
   * "when do I actually pay?".
   */
  "  subtitle: 'The contract generates itself. Your money is held safely until the work is approved and the post is live.',",
  "The brand's money stays locked and refundable until the deliverable is approved, so there's no risk of paying for content that never arrives or doesn't match the brief.",
  "When a brand funds a Campaign, the amount is reserved in the brand's Influora wallet until the work is approved or a dispute is resolved.",
  'Creator payments are separate: they stay in Secure Payments until you approve the work.',
  // The blog's meta excerpt and its four explainer sentences, verbatim.
  'excerpt: "Payment protection in influencer marketing holds a brand\'s payment until the creator delivers approved content \u2014 protecting both sides from non-payment and non-delivery."',
  "| **What triggers final payment** | Brand's decision, often informal | Brand's approval of the specific deliverable |",
  "the payment already exists, held in a neutral account, waiting only on the brand's approval of the delivered work.",
  'The contract defines what "approved delivery" means; payment protection enforces that payment only follows that definition.',
  'it converts "trust that the other side will do the right thing" into "money that moves automatically when a defined condition is met."',
];

const TEXT_EXT = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.md', '.mdx', '.html', '.json', '.txt', '.yml', '.yaml', '.properties', '.ftl', '.mustache']);
const CODE_EXT = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs', '.java']);
// '__tests__' and 'test' are what keeps this gate off its own pattern list.
const SKIP_DIRS = new Set(['node_modules', 'dist', '.git', '__tests__', 'test', '__snapshots__']);

function isTestFile(rel: string): boolean {
  return /\.(test|spec)\.[cm]?[jt]sx?$/.test(rel);
}

function walk(dir: string, out: string[]): void {
  if (!fs.existsSync(dir)) return;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (ent.isDirectory()) {
      if (!SKIP_DIRS.has(ent.name)) walk(path.join(dir, ent.name), out);
      continue;
    }
    const full = path.join(dir, ent.name);
    const rel = path.relative(ROOT, full).split(path.sep).join('/');
    if (!TEXT_EXT.has(path.extname(ent.name)) || isTestFile(rel)) continue;
    out.push(rel);
  }
}

/** Customer-facing Java: the email template registry and the notification copy. */
const JAVA_COPY_FILES = [
  'influora-api/src/main/java/com/influora/integration/msg91/EmailTemplateRegistry.java',
  'influora-api/src/main/java/com/influora/service/notification/NotificationListener.java',
];

export function collectFiles(): string[] {
  const files: string[] = [];
  walk(path.join(ROOT, 'src'), files);
  walk(path.join(ROOT, 'influora-api', 'src', 'main', 'resources'), files);
  for (const f of ['index.html', 'public/llms.txt', ...JAVA_COPY_FILES]) {
    if (fs.existsSync(path.join(ROOT, f))) files.push(f);
  }
  return files.filter((f) => f !== SELF && !f.endsWith('.sql'));
}

/**
 * Java string concatenation splits one sentence across several source lines:
 *
 *   + "- Payment is secured before you start, and released when your"
 *   + " work is approved",
 *
 * A line-at-a-time scanner cannot see that claim, and three creator emails in
 * EmailTemplateRegistry.java shipped the old promise behind exactly this split -- including one
 * the first pass reported as "read and clean". Continuation lines are folded onto the line that
 * starts the run, and blanked in place, so the run is scanned whole and the reported line number
 * still points at its first line.
 */
export function joinJavaConcat(lines: string[]): string[] {
  const out = [...lines];
  let runStart = -1;
  for (let i = 0; i < out.length; i += 1) {
    if (/^\s*\+\s*"/.test(out[i]) && runStart !== -1) {
      out[runStart] += ' ' + out[i].trim();
      out[i] = '';
    } else if (out[i].trim() !== '') {
      runStart = i;
    }
  }
  return out;
}

/** Blank comments while keeping every newline, so reported line numbers stay true. */
export function maskComments(text: string, ext: string): string[] {
  const blank = (s: string) => s.replace(/[^\n]/g, ' ');
  if (!CODE_EXT.has(ext)) {
    let t = text.replace(/<!--[\s\S]*?-->/g, blank);
    if (ext === '.yml' || ext === '.yaml' || ext === '.properties') t = t.replace(/^\s*#.*$/gm, blank);
    return t.split('\n');
  }
  const out: string[] = [];
  let inBlock = false;
  for (const line of text.split('\n')) {
    if (inBlock) {
      const end = line.indexOf('*/');
      if (end === -1) {
        out.push('');
        continue;
      }
      inBlock = false;
      out.push(' '.repeat(end + 2) + line.slice(end + 2));
      continue;
    }
    const t = line.trimStart();
    if (t.startsWith('//') || t.startsWith('*')) {
      out.push('');
      continue;
    }
    if (t.startsWith('/*') || t.startsWith('{/*')) {
      const end = line.indexOf('*/');
      if (end === -1) {
        inBlock = true;
        out.push('');
      } else {
        out.push(' '.repeat(end + 2) + line.slice(end + 2));
      }
      continue;
    }
    out.push(
      line
        .replace(/\{\/\*.*?\*\/\}/g, (m) => ' '.repeat(m.length))
        .replace(/\/\*.*?\*\//g, (m) => ' '.repeat(m.length))
        .replace(/\s\/\/\s.*$/, ''),
    );
  }
  return out;
}

export type Violation = { id: string; text: string; index: number };

export function violationsWithIndex(line: string): Violation[] {
  const hits: Violation[] = [];
  for (const rule of RULES) {
    // EVERY match on the line, not just the first. With a bare rule an exempt match sits in
    // front of a real one all the time — `api.wallet.withdraw()` on the same line as the label
    // that offers it — and `exec` without /g would report the token and stop.
    const re = new RegExp(rule.re.source, rule.re.flags.includes('g') ? rule.re.flags : rule.re.flags + 'g');
    let m: RegExpExecArray | null;
    while ((m = re.exec(line)) !== null) {
      if (m[0] === '') {
        re.lastIndex += 1;
        continue;
      }
      if (rule.allowNegated && isNegated(line, m.index, m[0])) continue;
      if (rule.otherSense && rule.otherSense.test(sentenceOf(line, m.index, m[0]))) continue;
      if (rule.machineToken && isMachineToken(line, m.index, m[0])) continue;
      hits.push({ id: rule.id, text: `[${rule.id}] "${m[0]}" — ${rule.why}`, index: m.index });
      break;
    }
  }
  return hits;
}

export function violationsInLine(line: string): string[] {
  return violationsWithIndex(line).map((v) => v.text);
}

/**
 * ROUND 4b — WRAPPED PROSE.
 *
 * The scanner reads one source line at a time, and a source line is not a sentence. The claim
 * that shipped to every AI crawler in the tree round 3 left behind was wrapped across two:
 *
 *     post inside a 72-hour window. Each accepted slot is funded up front and paid out
 *     automatically once the post is verified.
 *
 * Neither half contains 'paid out automatically'. The same wrap hid the visible hero copy on
 * /features/hype ('...Each approved' / 'reel is paid out automatically.') and the About page's
 * 'holds the brand's' / 'payment until the deliverable is approved'. A gate that reads lines
 * cannot see any of them, and no amount of rule-writing fixes that — it is the unit that is
 * wrong, not the pattern.
 *
 * So every file is scanned twice: once line by line, and once folded. Folding joins a line to
 * the previous one with a SPACE when the break looks like wrapped prose, and with a full stop
 * when it looks like the end of a code construct — the previous line ending in one of
 * ,;:{}[]()<>=&|? or a closing quote, or the next line opening with one. That full stop is what
 * keeps the fold honest: every rule's window is `[^.\n]`-bounded, so two adjacent object
 * entries can never be read as one sentence. The one exception is a JS string concatenation
 * ending in `' +`, which is prose split by the formatter and is joined with a space, the same
 * treatment `joinJavaConcat` gives the email templates.
 *
 * Matches are reported against the line their match STARTS on, and deduped by line + rule, so a
 * claim that fits on one line is not reported twice.
 */
const CODE_EDGE = /[,;:{}[\]()<>=&|?'"`]$/;
const CODE_START = /^[,;:{}[\]()<>=&|?]/;
/**
 * A markdown list item, table row, heading or blockquote starts a new claim, however the line
 * above it ended. Without this the Terms' own bullet list folded 'Track and approve
 * **Deliverables**' onto '- Release the **Payout** once the post is live...' and the gate
 * reported the honest sentence beneath the honest sentence above it.
 */
const PROSE_START = /^(?:[-*+>|#]\s|\d+[.)]\s)/;
const CONCAT_END = /['"`]\s*\+$/;

export function foldLines(lines: string[]): { text: string; lineAt: (index: number) => number } {
  const starts: number[] = [];
  let text = '';
  for (let i = 0; i < lines.length; i += 1) {
    let cur = lines[i].trim();
    let joinedConcat = false;
    if (i > 0) {
      const prev = lines[i - 1].trim();
      const concat = CONCAT_END.test(prev);
      const soft =
        cur !== '' &&
        prev !== '' &&
        !CODE_START.test(cur) &&
        !PROSE_START.test(cur) &&
        (concat || (!CODE_EDGE.test(prev) && !prev.endsWith('+')));
      joinedConcat = soft && concat;
      // `'...paid out ' +` / `'automatically once...'` is ONE sentence the formatter split. Drop
      // the quote and the plus, or the fold leaves "paid out ' + 'automatically" and no rule
      // written for prose can read it.
      if (joinedConcat) text = text.replace(CONCAT_END, '');
      text += soft ? ' ' : ' . ';
    }
    if (joinedConcat) cur = cur.replace(/^['"`]/, '');
    starts.push(text.length);
    text += cur;
  }
  return {
    text,
    lineAt: (index: number) => {
      let lo = 0;
      let hi = starts.length - 1;
      let ans = 0;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        if (starts[mid] <= index) {
          ans = mid;
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      return ans + 1;
    },
  };
}

export function findViolations(files: string[]): string[] {
  const hits: string[] = [];
  for (const rel of files) {
    const text = fs.readFileSync(path.join(ROOT, rel), 'utf8').replace(/\r\n/g, '\n');
    const ext = path.extname(rel);
    const lines = ext === '.java' ? joinJavaConcat(maskComments(text, ext)) : maskComments(text, ext);
    const seen = new Set<string>();
    const add = (lineNo: number, v: Violation) => {
      const key = `${lineNo}:${v.id}`;
      if (seen.has(key)) return;
      seen.add(key);
      hits.push(`${rel}:${lineNo} ${v.text}`);
    };
    lines.forEach((line, i) => {
      for (const v of violationsWithIndex(line)) add(i + 1, v);
    });
    // The same file again, with wrapped prose folded back into sentences. See `foldLines`.
    const folded = foldLines(lines);
    for (const v of violationsWithIndex(folded.text)) add(folded.lineAt(v.index), v);
  }
  return hits;
}

describe('paytrigger: one payment promise across every customer-facing surface', () => {
  const files = collectFiles();

  it('scans the real surfaces (anti-vacuity)', () => {
    expect(files.length).toBeGreaterThan(300);
    for (const must of [
      'src/pages/creator-wallet.tsx',
      'src/lib/contract-generator.ts',
      'src/components/payments/money-flow-steps.tsx',
      'src/components/payments/payments-unavailable-notice.tsx',
      'src/components/brand/deal-room/deal-payments-tab.tsx',
      'src/content/how-it-works-steps.ts',
      'src/content/legal/escrow-and-refund-policy.md',
      'src/content/legal/tds-policy.md',
      'src/content/legal/privacy-policy.md',
      'src/pages/features/secure-payments.tsx',
      'src/pages/pricing.tsx',
      'src/pages/how-it-works-creators.tsx',
      'src/content/blog/what-is-payment-protection-in-influencer-marketing.md',
      // Round 2 — every surface the first pass shipped wrong, pinned by path so a later edit
      // cannot quietly drop one out of the scan.
      'src/pages/features/deal-room.tsx',
      'src/pages/how-it-works-brands.tsx',
      'src/components/site/proof-points.ts',
      'src/components/brand/timeline/panels/contract-panel.tsx',
      'src/components/brand/dashboard/BrandFirstRunChecklist.tsx',
      'src/pages/features/hype.tsx',
      'src/pages/landing.tsx',
      // Round 3 — the four survivors' files, pinned so none can drop out of the scan again.
      'src/pages/creator-dashboard.tsx',
      'src/pages/creator-onboarding.tsx',
      'src/pages/creator-settings.tsx',
      'src/App.tsx',
      'src/remotion/campaign/creator-script.ts',
      'src/remotion/campaign/components/CreatorScenes.tsx',
      'src/content/legal/terms-of-service.md',
      'src/pages/creator-wallet.tsx',
      'index.html',
      'public/llms.txt',
      // Round 4b — the surfaces this pass corrected. `about.tsx` had never been opened by any
      // round and was carrying the claim the whole time; the three film scripts and the two
      // campaign scenes ship as pre-rendered mp4s, so a wrong line there outlives the source.
      'src/pages/about.tsx',
      'src/pages/brand-campaign-detail.tsx',
      'src/components/brand/deal-room/proposal-form.tsx',
      'src/components/creator/CreatorFirstRunChecklist.tsx',
      'src/remotion/script.ts',
      'src/remotion/script.en.ts',
      'src/remotion/script.mr.ts',
      'src/remotion/campaign/components/LifecycleScenes.tsx',
      // Round 4b second sweep -- four more files no round had named.
      'src/components/brand/dashboard/BrandFirstRunChecklist.tsx',
      'src/content/blog/how-to-pay-influencers-safely-india-2026.md',
      'src/content/legal/escrow-and-refund-policy.md',
      'src/pages/pricing.tsx',
      ...JAVA_COPY_FILES,
    ]) {
      expect(files, `${must} must be scanned`).toContain(must);
    }
  });

  it('cannot match its own pattern list', () => {
    // The gate's own text is full of the phrases it bans. If __tests__ were ever scanned, this
    // file would fail on its own rules and the fix would be to weaken the rules.
    expect(files).not.toContain(SELF);
    expect(files.filter((f) => f.includes('__tests__'))).toEqual([]);
    expect(files.filter((f) => isTestFile(f))).toEqual([]);
    // And prove it directly: the rule list, read off disk, is loud with violations.
    const ownText = fs.readFileSync(HERE, 'utf8').replace(/\r\n/g, '\n');
    const ownHits = ownText.split('\n').flatMap((l) => violationsInLine(l));
    expect(ownHits.length).toBeGreaterThan(5);
  });

  it('every rule catches the sentence it was written against (positive control)', () => {
    for (const rule of RULES) {
      const sample = POSITIVE_CONTROL[rule.id];
      expect(sample, `rule ${rule.id} has a positive control`).toBeTruthy();
      expect(violationsInLine(sample).join(' '), `rule ${rule.id} must catch: ${sample}`).toContain(
        `[${rule.id}]`,
      );
    }
  });

  it('no rule is dead weight — each is the ONLY one to catch at least one real sentence', () => {
    // Without this, a broad rule can silently cover a narrow one's control and the narrow rule
    // could be deleted or broken with every test still green.
    const soleCatcher = new Set<string>();
    for (const [id, sample] of Object.entries(POSITIVE_CONTROL)) {
      const hits = violationsInLine(sample);
      if (hits.length === 1 && hits[0].startsWith(`[${id}]`)) soleCatcher.add(id);
    }
    expect([...soleCatcher].sort()).toEqual(RULES.map((r) => r.id).sort());
  });

  it('still catches every string that survived round 1 (regression control)', () => {
    // Named individually rather than counted, so a rule rewrite that drops one is reported as the
    // sentence it stopped catching.
    for (const s of REGRESSION_CONTROL) {
      expect(violationsInLine(s), `round-2 regression, no longer caught: ${s}`).not.toEqual([]);
    }
  });

  it('the new copy passes every rule (negative control)', () => {
    for (const s of NEGATIVE_CONTROL) {
      expect(violationsInLine(s), `wrongly flagged: ${s}`).toEqual([]);
    }
  });

  it('denying a banned claim is allowed; asserting it is not', () => {
    for (const { denied, asserted } of NEGATED_CONTROL) {
      expect(violationsInLine(denied), `a denial must not be flagged: ${denied}`).toEqual([]);
      // The other half of the pair: if this passes too, the exemption has eaten the rule.
      expect(
        violationsInLine(asserted).length,
        `the affirmative version must still be caught: ${asserted}`,
      ).toBeGreaterThan(0);
    }
  });

  it('folds Java string concatenation so a split sentence is still scanned', () => {
    // The exact shape that hid three creator emails from the first pass.
    const folded = joinJavaConcat([
      '                                + "- Payment is secured before you start, and released when your"',
      '                                + " work is approved",',
    ]);
    expect(violationsInLine(folded[0]).join(' ')).toContain('[release-triggered-by-approval]');
    expect(folded[1]).toBe('');
    // And the fold must not run past the end of a concatenation into the next statement.
    const separate = joinJavaConcat(['        "a",', '', '        "paid once the brand approves",']);
    expect(separate[2]).toBe('        "paid once the brand approves",');
  });

  it('comment masking keeps code copy and hides only comments', () => {
    const masked = maskComments(
      ['/* withdraw funds */', "const a = 'auto-approval'; // withdraw funds", '  * payout within 24 hours'].join('\n'),
      '.ts',
    );
    expect(masked[0].trim()).toBe('');
    expect(masked[1]).toContain('auto-approval');
    expect(masked[1]).not.toContain('withdraw');
    expect(masked[2].trim()).toBe('');
  });

  it('folds prose wrapped across source lines, and never folds across code punctuation', () => {
    // The exact wrap that hid the Hype claim from every AI crawler in index.html.
    const wrapped = foldLines([
      '        post inside a 72-hour window. Each accepted slot is funded up front and paid out',
      '        automatically once the post is verified.',
    ]);
    expect(violationsInLine(wrapped.text).join(' ')).toContain('[paid-out-automatically]');
    // Reported against the line the match starts on, not the line the sentence started on.
    expect(wrapped.lineAt(wrapped.text.indexOf('paid out'))).toBe(1);
    expect(wrapped.lineAt(wrapped.text.indexOf('automatically'))).toBe(2);

    // Two unrelated object entries must NOT be read as one sentence — this is the false
    // positive the fold would otherwise invent, and the full stop is what prevents it.
    const code = foldLines(["  approveLabel: 'Approve the draft',", "  releaseNote: 'Release the payment',"]);
    expect(code.text).toContain(' . ');
    expect(violationsInLine(code.text)).toEqual([]);

    // A JS string concatenation is prose split by the formatter, so it folds with a space.
    const concat = foldLines(["  'a brand funds the slot up front and it is paid out ' +", "  'automatically once the post is verified.',"]);
    expect(violationsInLine(concat.text).join(' ')).toContain('[paid-out-automatically]');
  });

  it('no contradictory payment promise appears in any scanned file', () => {
    expect(findViolations(files)).toEqual([]);
  });
});
