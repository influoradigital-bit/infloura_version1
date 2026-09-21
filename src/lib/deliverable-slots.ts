import type { DeliverableSlot } from '@/lib/types';

/**
 * The only deliverable types the platform can actually create a submission slot for — one for one
 * with the backend `DeliverableType` enum
 * (influora-api/src/main/java/com/influora/domain/enums/DeliverableType.java).
 *
 * Every offer form must send one of THESE strings as `DeliverableSlot.type`. Until 2026-09-21 the
 * two brand offer forms sent their own vocabularies instead — `proposal-form.tsx` sent display
 * labels ("TikTok Video", "YouTube Video", "Blog Post"), `creator-discovery.tsx` sent short codes
 * ("REEL", "POST", "VIDEO", "SHORT") — and neither matched the enum. `ContractService` then fell
 * back to `INSTAGRAM_REEL` for anything it could not parse, so a brand that ordered a YouTube
 * video got a creator looking at an Instagram Reel slot. The fallback is gone; an unparseable
 * type is now refused at the offer route, so this list is the contract, not a suggestion.
 */
export const DELIVERABLE_TYPES = [
  'INSTAGRAM_REEL',
  'INSTAGRAM_POST',
  'INSTAGRAM_STORY',
  'INSTAGRAM_CAROUSEL',
  'YOUTUBE_VIDEO',
  'YOUTUBE_SHORT',
  'FACEBOOK_POST',
  'FACEBOOK_REEL',
  'TIKTOK_VIDEO',
] as const;

export type DeliverableTypeValue = (typeof DELIVERABLE_TYPES)[number];

/** How each type is written for a human. Never sent on the wire — `DELIVERABLE_TYPES` is. */
export const DELIVERABLE_TYPE_LABELS: Record<DeliverableTypeValue, string> = {
  INSTAGRAM_REEL: 'Instagram Reel',
  INSTAGRAM_POST: 'Instagram Post',
  INSTAGRAM_STORY: 'Instagram Story',
  INSTAGRAM_CAROUSEL: 'Instagram Carousel',
  YOUTUBE_VIDEO: 'YouTube Video',
  YOUTUBE_SHORT: 'YouTube Short',
  FACEBOOK_POST: 'Facebook Post',
  FACEBOOK_REEL: 'Facebook Reel',
  TIKTOK_VIDEO: 'TikTok Video',
};

/** Ready-made `<SelectItem value={value}>{label}</SelectItem>` rows for every offer form. */
export const DELIVERABLE_TYPE_OPTIONS: ReadonlyArray<{ value: DeliverableTypeValue; label: string }> =
  DELIVERABLE_TYPES.map((value) => ({ value, label: DELIVERABLE_TYPE_LABELS[value] }));

/** The type every form starts a fresh row on. */
export const DEFAULT_DELIVERABLE_TYPE: DeliverableTypeValue = 'INSTAGRAM_REEL';

export function isDeliverableTypeValue(value: unknown): value is DeliverableTypeValue {
  return typeof value === 'string' && (DELIVERABLE_TYPES as readonly string[]).includes(value);
}

/**
 * Display text for a type read back off the wire.
 *
 * Known values get their real label. Anything else is a row written before this vocabulary
 * existed, and is shown as the raw stored value tidied up ("TIKTOK_VIDEO" → "TIKTOK VIDEO") rather
 * than guessed at — the whole defect this module closes was code deciding, on its own, that an
 * unrecognised type meant "Instagram Reel". Read-only: never feed this back into a request.
 */
export function deliverableTypeLabel(raw: string): string {
  return isDeliverableTypeValue(raw) ? DELIVERABLE_TYPE_LABELS[raw] : raw.replace(/_/g, ' ');
}

/**
 * Loose view of a proposal/contract message's `metadata` — deliberately `unknown`-typed so this
 * works for both the typed `TimelineEventMetadata` and the `Record<string, unknown>` metadata the
 * chat pages carry, and so a shape change on the wire can never silently type-check its way into
 * a render.
 */
type ProposalMetadataLike =
  | { deliverables?: unknown; deliverableCount?: unknown }
  | null
  | undefined;

function isDeliverableSlot(value: unknown): value is DeliverableSlot {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { type?: unknown }).type === 'string'
  );
}

/**
 * The proposal's actual deliverable slots, or `[]` when the message predates the slot shape.
 *
 * `metadata.deliverables` was a plain count until 2026-07-26, when
 * `DealService.persistProposalMessage` (DealService.java:1132) started storing the
 * `DealDtos.DeliverableSlot` list itself so the deal room could show what was actually offered.
 * Old messages still hold the number, so both shapes are live in the same table.
 */
export function deliverableSlotsOf(meta: ProposalMetadataLike): DeliverableSlot[] {
  const raw = meta?.deliverables;
  return Array.isArray(raw) ? raw.filter(isDeliverableSlot) : [];
}

/**
 * How many pieces the proposal covers, or `undefined` when the message carries no deliverable
 * information at all. Callers must render the absence honestly rather than defaulting to a
 * plausible-looking number (TECH-STACK.md rule 7).
 *
 * Prefers the backend's own `deliverableCount`, falls back to the slot-list length, and finally
 * accepts the pre-2026-07-26 plain-number shape.
 */
export function deliverableCountOf(meta: ProposalMetadataLike): number | undefined {
  const count = meta?.deliverableCount;
  if (typeof count === 'number') return count;

  const raw = meta?.deliverables;
  if (Array.isArray(raw)) return raw.length;
  if (typeof raw === 'number') return raw;
  return undefined;
}

/**
 * `"3 pieces"` / `"1 piece"`, or `null` when the proposal carries no deliverable information.
 * `unit` is pluralised by suffixing "s" — pass a noun where that holds ("piece", "item").
 */
export function deliverableCountLabel(
  meta: ProposalMetadataLike,
  unit: 'piece' | 'item' = 'piece',
): string | null {
  const count = deliverableCountOf(meta);
  if (count == null) return null;
  return `${count} ${count === 1 ? unit : `${unit}s`}`;
}

/**
 * `"2x Instagram Reel · 1x Instagram Story"` — the per-type breakdown the slot shape exists to
 * expose, or `null` when the message only carries a count (every proposal written before
 * 2026-07-26).
 *
 * Types are rendered through {@link deliverableTypeLabel}, so a card shows "Instagram Reel"
 * rather than the raw `INSTAGRAM_REEL` the wire carries.
 */
export function deliverableSlotsLabel(meta: ProposalMetadataLike): string | null {
  const slots = deliverableSlotsOf(meta);
  if (slots.length === 0) return null;
  return slots
    .map((slot) =>
      typeof slot.qty === 'number'
        ? `${slot.qty}x ${deliverableTypeLabel(slot.type)}`
        : deliverableTypeLabel(slot.type),
    )
    .join(' · ');
}
