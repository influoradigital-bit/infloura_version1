import { useCallback, useMemo, useState, type ReactElement } from 'react';
import { motion, useReducedMotion } from 'framer-motion';

import { Button } from '@/components/ui/button';
import { DURATION_NORMAL, EASE_OUT } from '@/lib/motion-config';

/**
 * Page-scoped tracking consent bar (T-FESTIVALBOX-0905 phase 6).
 *
 * WHY THIS EXISTS
 * India's DPDP Act 2023 requires notice and consent before processing personal data; EU visitors
 * bring GDPR, which requires consent BEFORE a tracking tag fires. So no sponsor pixel on the
 * Festival Box page may load until a visitor actively accepts — see festival-box-edition.tsx,
 * which gates its Meta Pixel injection on `consent === 'accepted'` from `usePageConsent` below.
 *
 * Deliberately NOT sitewide: render this only on the one page that actually has third-party
 * pixels (Festival Box), not in SiteFooter/SiteHeader. Most of the site has nothing to consent to.
 *
 * DESIGN RULES (do not relax these on a future edit)
 * - Accept and Decline are the same size, same component, same visual weight. A consent bar that
 *   visually demotes Decline (tiny gray link vs. a giant colored button) is a dark pattern and is
 *   worse than no consent bar at all.
 * - No stored choice = NOT consented. Absence is never treated as consent — see
 *   `readStoredConsent` below, which returns `null` (not 'accepted') for anything it can't read.
 * - Every localStorage read AND write is wrapped in try/catch. It throws outright in some
 *   contexts (private browsing in some browsers, storage disabled by policy, prerendering) and a
 *   thrown read/write must never crash the page or silently grant consent.
 * - [Kabir H-6] One consent record per page-and-audience, never one global record. The caller
 *   passes a `storageKey` that identifies the specific page (Festival Box scopes it by edition
 *   slug) and a `scope` naming the specific third parties. "There's only one of these today" is
 *   never a reason to share a key — it is the condition that hides the bug until the second one
 *   launches, at which point returning visitors get no bar at all.
 * - [Kabir H-6] Consent is stored as a versioned record with a timestamp and a scope, not as a
 *   bare choice string. A decision with no record of when it was made and what it covered is not
 *   demonstrable consent under DPDP Act 2023 §6.
 * - [Kabir H-6] Withdrawal is only real if the tag actually stops. This hook clears the record;
 *   the CALLER is responsible for tearing down whatever it started — and for a Meta Pixel that
 *   means a page reload, because `fbevents.js` cannot be unloaded once it has run. See
 *   `teardownMetaPixel` in festival-box-edition.tsx. A consent UI that records a withdrawal while
 *   the tag keeps firing is worse than having no withdrawal control, because it tells the visitor
 *   something untrue.
 */

export type ConsentChoice = 'accepted' | 'declined';

/**
 * Bumped whenever what is being consented to changes materially (new categories of processing,
 * new wording about what sponsors receive). A stored record from an older version is treated as
 * NO record — the visitor is asked again — because consent to the old terms is not consent to the
 * new ones.
 */
const CONSENT_RECORD_VERSION = 1;

/**
 * [Kabir H-6] What is actually persisted. This used to be the bare string `'accepted'`, which
 * recorded a decision while recording nothing that makes it defensible or scopable:
 *
 * - `at` — DPDP Act 2023 §6 requires consent to be demonstrable. "This browser has the word
 *   accepted in it" cannot answer *when*, and a consent with no timestamp cannot expire or be
 *   audited.
 * - `v` — see {@link CONSENT_RECORD_VERSION}.
 * - `scope` — the identifiers the visitor actually agreed to (for Festival Box, the sponsor Meta
 *   Pixel ids that were on the page when they said yes). Consent under DPDP runs to specific
 *   purposes and recipients, not to a page. Without this, adding a fourth sponsor to a live
 *   edition silently hands a brand-new company a visitor's traffic under a yes given before that
 *   company existed on the page — no bar shown, nothing logged.
 *
 * An unparseable/older/foreign-shaped value reads back as `null`, i.e. NOT consented. Every
 * failure mode in this file resolves to "ask again", never to "assume yes".
 */
interface StoredConsentRecord {
  v: number;
  choice: ConsentChoice;
  at: string;
  scope: string[];
}

function isConsentChoice(value: unknown): value is ConsentChoice {
  return value === 'accepted' || value === 'declined';
}

/** Order-independent, so a re-ordered sponsor list is not mistaken for a changed sponsor list. */
function normalizeScope(scope: readonly string[]): string[] {
  return [...new Set(scope)].sort();
}

function parseRecord(raw: string | null): StoredConsentRecord | null {
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const record = parsed as Partial<StoredConsentRecord>;
    if (record.v !== CONSENT_RECORD_VERSION) return null;
    if (!isConsentChoice(record.choice)) return null;
    if (typeof record.at !== 'string' || record.at === '') return null;
    if (!Array.isArray(record.scope) || record.scope.some((s) => typeof s !== 'string')) return null;
    return { v: record.v, choice: record.choice, at: record.at, scope: record.scope };
  } catch {
    // Includes the pre-H-6 format: the bare string 'accepted' is not valid JSON, so an existing
    // stored consent from before this change reads as null and the visitor is asked once more.
    // That is the correct direction to fail — those records have no timestamp and no scope, so
    // there is nothing to honour them with.
    return null;
  }
}

function readStoredConsent(storageKey: string, requiredScope: readonly string[]): ConsentChoice | null {
  try {
    const record = parseRecord(window.localStorage.getItem(storageKey));
    if (!record) return null;

    // A DECLINE is honoured regardless of scope. Widening the sponsor list is a reason to ask
    // someone who said yes again; it is not a reason to re-prompt someone who said no, which
    // would turn "add a sponsor" into a way to nag a visitor back through the bar.
    if (record.choice === 'declined') return 'declined';

    const consented = new Set(record.scope);
    const covers = normalizeScope(requiredScope).every((id) => consented.has(id));
    return covers ? 'accepted' : null;
  } catch {
    return null;
  }
}

function writeStoredConsent(
  storageKey: string,
  choice: ConsentChoice | null,
  scope: readonly string[],
): void {
  try {
    if (choice === null) {
      window.localStorage.removeItem(storageKey);
      return;
    }
    const record: StoredConsentRecord = {
      v: CONSENT_RECORD_VERSION,
      choice,
      at: new Date().toISOString(),
      scope: normalizeScope(scope),
    };
    window.localStorage.setItem(storageKey, JSON.stringify(record));
  } catch {
    // Storage unavailable — the choice just won't survive a reload. Never treat this as consent.
  }
}

/**
 * Reads/writes one page's consent choice, keyed by `storageKey` so different pages (or a second
 * sponsored edition) don't share state. `consent` is `null` until the visitor answers — that
 * `null` state is what gates pixel injection, and it is what "not yet consented" means.
 *
 * @param scope the identifiers this page needs consent for right now (Festival Box passes its
 *   sponsors' Meta Pixel ids). A stored `'accepted'` only counts while it covers every id in
 *   `scope`; introduce one the visitor never saw and this returns `null`, so the bar comes back
 *   and the pixel stays down until they answer for the new set. Pass a stable array — a fresh
 *   literal every render will re-run the read on every render. Defaults to `[]`, which means
 *   "unscoped: any stored acceptance counts" and is right for a page with a fixed, unchanging
 *   set of third parties.
 */
export function usePageConsent(
  storageKey: string,
  scope: readonly string[] = [],
): {
  consent: ConsentChoice | null;
  accept: () => void;
  decline: () => void;
  reset: () => void;
} {
  // localStorage is the single source of truth; `consent` is DERIVED from it, never a second copy
  // that could disagree. `revision` exists only to force a re-read after this hook writes.
  //
  // Written this way rather than as `useState` + a sync `useEffect` for two reasons. The obvious
  // one is that setState-in-an-effect causes a cascading render (and the repo's lint says so). The
  // one that matters: the key and the scope can both change while mounted — navigating between
  // editions, a sponsor list arriving late — and a duplicated state copy shows the PREVIOUS
  // edition's answer for one render before the effect corrects it. For a consent gate, one render
  // holding a stale 'accepted' is exactly long enough for the pixel effect to fire.
  const scopeSignature = normalizeScope(scope).join('|');
  const [revision, setRevision] = useState(0);

  const consent = useMemo(
    () => (typeof window === 'undefined' ? null : readStoredConsent(storageKey, scope)),
    // `scope` is intentionally not a dependency: it is a new array identity on most renders, and
    // `scopeSignature` is its stable value-equality stand-in.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [storageKey, scopeSignature, revision],
  );

  const accept = useCallback(() => {
    writeStoredConsent(storageKey, 'accepted', scope);
    setRevision((r) => r + 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey, scopeSignature]);

  const decline = useCallback(() => {
    writeStoredConsent(storageKey, 'declined', scope);
    setRevision((r) => r + 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey, scopeSignature]);

  const reset = useCallback(() => {
    writeStoredConsent(storageKey, null, scope);
    setRevision((r) => r + 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey, scopeSignature]);

  return { consent, accept, decline, reset };
}

interface PageConsentBarProps {
  /** What the sponsors would like to measure, in plain language — no legalese. */
  message?: string;
  onAccept: () => void;
  onDecline: () => void;
}

/**
 * The bar itself. Controlled — the caller decides whether to render it at all (typically:
 * `{consent === null && <PageConsentBar ... />}`), which is also how "does not reappear once
 * answered" is enforced: once `consent` is non-null, the caller stops rendering this.
 */
export function PageConsentBar({
  message = "Sponsors of this edition would like to measure visits to this page. Declining changes nothing about how the page works for you.",
  onAccept,
  onDecline,
}: PageConsentBarProps): ReactElement {
  const shouldReduceMotion = useReducedMotion();

  return (
    <motion.div
      role="region"
      aria-label="Sponsor tracking consent"
      aria-live="polite"
      className="fixed inset-x-0 bottom-0 z-50 border-t border-border bg-background/95 px-6 py-4 shadow-[0_-4px_16px_rgba(0,0,0,0.08)] backdrop-blur supports-[backdrop-filter]:bg-background/80"
      initial={shouldReduceMotion ? undefined : { y: 24, opacity: 0 }}
      animate={shouldReduceMotion ? undefined : { y: 0, opacity: 1 }}
      transition={shouldReduceMotion ? undefined : { duration: DURATION_NORMAL, ease: EASE_OUT }}
    >
      <div className="mx-auto flex max-w-4xl flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
        <p className="text-sm text-muted-foreground">{message}</p>
        <div className="flex w-full shrink-0 gap-3 sm:w-auto">
          <Button
            type="button"
            variant="outline"
            size="default"
            onClick={onDecline}
            className="flex-1 border-2 border-border font-semibold sm:flex-none"
          >
            Decline
          </Button>
          <Button
            type="button"
            variant="default"
            size="default"
            onClick={onAccept}
            className="flex-1 bg-accent-foreground font-semibold text-white hover:bg-accent-foreground/90 sm:flex-none"
          >
            Accept
          </Button>
        </div>
      </div>
    </motion.div>
  );
}
