import * as React from 'react';
import type { RiskFlag } from '@/lib/api';

/**
 * U-3 — session-scoped dismissal of deal-risk flags, for every screen that renders
 * `DealRiskCard` (`components/shared/deal-risk-card.tsx`).
 *
 * ## Why "for the session", and what that means here
 * There is no endpoint that stores a dismissed flag. `POST /creator/briefs/:id/dismiss` dismisses a
 * whole BRIEF, not one flag, and `/deals/:id/risks` is read-only. So a dismissal lives in
 * `sessionStorage`: it survives a reload in the same tab and ends when the tab closes. The screens
 * say so in words ("hidden for this session") and always offer "Show", because a dismissal that
 * looked permanent would be a control representing state it does not persist (TECH-STACK.md,
 * UI Honesty).
 *
 * ## Non-dismissible flags are read off the flag, never off a list
 * `RiskFlag.dismissible` is a Java primitive on `CreatorToolDtos.RiskFlag`, set per rule
 * (`HideDisclosureRule`, `OffPlatformPaymentRule`, `RegulatedCategoryRule` pass `false`). This hook
 * never hides a flag whose own `dismissible` is false — even if its code is somehow already in
 * storage (a rule that turned non-dismissible after a creator hid it) — and `dismiss` refuses such a
 * flag outright. No code list is hard-coded anywhere.
 *
 * ## Scope
 * Dismissals are keyed by a caller-chosen scope plus `flag.code` (codes are unique within one
 * evaluation — `DealRiskCard` already keys its rows by code). Scopes in use:
 * `DEAL:{dealId}` (deal room, and Meera's `check_deal_risks` card for a DEAL target) and
 * `BRIEF:{briefId}` (the paste card, and `check_deal_risks` for a BRIEF target) — so hiding a flag
 * on a deal in Meera's chat also hides it in that deal's room. An `undefined` scope disables
 * dismissal entirely rather than sharing one bucket across unrelated deals.
 *
 * ## One store, many readers
 * A `useSyncExternalStore` store rather than per-component state, so two cards for the same deal
 * on one screen (the proposal card and the counter card in the deal room, or two tool results in
 * one chat) update together.
 */

const STORAGE_KEY = 'influora.riskFlagDismissals.v1';

type DismissalMap = Readonly<Record<string, readonly string[]>>;

const EMPTY: DismissalMap = Object.freeze({});

const listeners = new Set<() => void>();

/** Set once `sessionStorage` refuses a read or write (private mode, blocked site data). */
let storageUnavailable = false;
/** The in-memory copy used only when storage is unavailable. */
let memoryFallback: DismissalMap = EMPTY;
/** `getSnapshot` must return the same reference while nothing changed. */
let cachedRaw: string | null | undefined;
let cachedMap: DismissalMap = EMPTY;

function parse(raw: string | null): DismissalMap {
  if (!raw) return EMPTY;
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== 'object' || Array.isArray(value)) return EMPTY;
    const out: Record<string, readonly string[]> = {};
    for (const [scope, codes] of Object.entries(value as Record<string, unknown>)) {
      if (Array.isArray(codes)) {
        out[scope] = codes.filter((c): c is string => typeof c === 'string');
      }
    }
    return out;
  } catch {
    return EMPTY;
  }
}

function getSnapshot(): DismissalMap {
  if (storageUnavailable) return memoryFallback;
  let raw: string | null;
  try {
    raw = window.sessionStorage.getItem(STORAGE_KEY);
  } catch {
    storageUnavailable = true;
    return memoryFallback;
  }
  if (raw !== cachedRaw) {
    cachedRaw = raw;
    cachedMap = parse(raw);
  }
  return cachedMap;
}

function write(next: DismissalMap): void {
  if (!storageUnavailable) {
    try {
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      storageUnavailable = true;
    }
  }
  if (storageUnavailable) memoryFallback = next;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export interface RiskFlagDismissals {
  /** The flags to render: every non-dismissible flag, plus the dismissible ones not hidden. */
  visibleFlags: RiskFlag[];
  /** How many of THIS evaluation's flags are currently hidden. Codes not in `flags` do not count. */
  hiddenCount: number;
  /** `undefined` when there is no scope — pass straight to `DealRiskCard`'s `onDismiss`. */
  dismiss: ((flag: RiskFlag) => void) | undefined;
  /** Un-hide every flag in this scope. `undefined` when there is no scope. */
  restore: (() => void) | undefined;
}

export function useRiskFlagDismissals(
  scope: string | undefined,
  flags: RiskFlag[] | undefined,
): RiskFlagDismissals {
  const map = React.useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const hiddenCodes = scope ? map[scope] : undefined;

  const { visibleFlags, hiddenCount } = React.useMemo(() => {
    const list = flags ?? [];
    if (!hiddenCodes || hiddenCodes.length === 0) {
      return { visibleFlags: list, hiddenCount: 0 };
    }
    const hidden = new Set(hiddenCodes);
    const visible = list.filter((flag) => !(flag.dismissible && hidden.has(flag.code)));
    return { visibleFlags: visible, hiddenCount: list.length - visible.length };
  }, [flags, hiddenCodes]);

  const dismiss = React.useMemo(() => {
    if (!scope) return undefined;
    return (flag: RiskFlag) => {
      if (!flag.dismissible) return;
      const current = getSnapshot();
      const codes = current[scope] ?? [];
      if (codes.includes(flag.code)) return;
      write({ ...current, [scope]: [...codes, flag.code] });
    };
  }, [scope]);

  const restore = React.useMemo(() => {
    if (!scope) return undefined;
    return () => {
      const current = getSnapshot();
      if (!current[scope]) return;
      const next: Record<string, readonly string[]> = { ...current };
      delete next[scope];
      write(next);
    };
  }, [scope]);

  return { visibleFlags, hiddenCount, dismiss, restore };
}
