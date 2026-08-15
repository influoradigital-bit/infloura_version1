import type { DeliverableSlot } from '@/lib/types';

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
 * `"2x REEL - 1x STORY"` — the per-type breakdown the slot shape exists to expose, or `null` when
 * the message only carries a count (every proposal written before 2026-07-26).
 */
export function deliverableSlotsLabel(meta: ProposalMetadataLike): string | null {
  const slots = deliverableSlotsOf(meta);
  if (slots.length === 0) return null;
  return slots
    .map((slot) => (typeof slot.qty === 'number' ? `${slot.qty}x ${slot.type}` : slot.type))
    .join(' · ');
}
