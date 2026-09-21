/**
 * hirepath (2026-09-21) — the SPA's deliverable-type vocabulary must BE the backend enum.
 *
 * WHY THIS FILE EXISTS
 * ----------------------------------------------------------------------------
 * Every offer form used to keep its own list of "deliverable types", and neither list was the
 * backend's. The deal-room proposal form offered display labels ("Instagram Reel", "TikTok Video",
 * "Blog Post", "Other"); the Discover offer modal offered short codes ("REEL", "POST", "STORY",
 * "VIDEO", "SHORT"). The real vocabulary is
 * `influora-api/src/main/java/com/influora/domain/enums/DeliverableType.java`, and
 * `ContractService` matched none of those strings — it caught the parse failure and substituted
 * `INSTAGRAM_REEL`. So a brand ordered a YouTube video, the contract materialised an Instagram
 * Reel, and no screen anywhere said the order had been changed.
 *
 * The forms now all render `DELIVERABLE_TYPE_OPTIONS`, the server refuses anything that names no
 * constant, and this test is what keeps those two facts from drifting apart: it derives the
 * backend's member list from the Java source and compares it to the SPA's, so adding a type on
 * one side without the other fails here rather than in a brand's deal room.
 *
 * Run: npx vitest run src/lib/__tests__/deliverable-type-parity.test.ts
 */

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
import {
  DELIVERABLE_TYPES,
  DELIVERABLE_TYPE_LABELS,
  DELIVERABLE_TYPE_OPTIONS,
  DEFAULT_DELIVERABLE_TYPE,
  deliverableTypeLabel,
  isDeliverableTypeValue,
} from '@/lib/deliverable-slots';

const here = dirname(fileURLToPath(import.meta.url));
const DELIVERABLE_TYPE_JAVA = resolve(
  here,
  '../../../influora-api/src/main/java/com/influora/domain/enums/DeliverableType.java',
);

/**
 * The enum constants declared in the Java source.
 *
 * Each constant carries a display label — `INSTAGRAM_REEL("Instagram Reel")` — so this reads the
 * name and the label together, and the label check below is what stops the two surfaces writing
 * the same type two different ways ("YouTube Video" here, "Youtube Video" there).
 */
function backendTypes(): Array<{ name: string; label: string }> {
  const source = readFileSync(DELIVERABLE_TYPE_JAVA, 'utf8');
  const body = source.slice(
    source.indexOf('public enum DeliverableType {'),
    // The constants end at the first ';' — everything after it is methods.
    source.indexOf(';', source.indexOf('public enum DeliverableType {')),
  );
  return [...body.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\("([^"]+)"\),?/gm)].map((m) => ({
    name: m[1],
    label: m[2],
  }));
}

describe('DeliverableType parity — SPA vocabulary vs backend enum', () => {
  it('declares exactly the constants the backend enum declares', () => {
    const backend = backendTypes().map((t) => t.name);
    expect(backend.length).toBeGreaterThan(0);
    expect([...DELIVERABLE_TYPES].sort()).toEqual([...backend].sort());
  });

  it('writes every type the same way the backend writes it', () => {
    for (const { name, label } of backendTypes()) {
      expect(
        DELIVERABLE_TYPE_LABELS[name as (typeof DELIVERABLE_TYPES)[number]],
        `${name} is labelled differently on the two surfaces`,
      ).toBe(label);
    }
  });

  it('offers one option per type, valued by the WIRE NAME and not the label', () => {
    expect(DELIVERABLE_TYPE_OPTIONS.map((o) => o.value)).toEqual([...DELIVERABLE_TYPES]);
    for (const option of DELIVERABLE_TYPE_OPTIONS) {
      // The exact defect: a <SelectItem value="TikTok Video"> put a label on the wire.
      expect(option.value).toMatch(/^[A-Z][A-Z0-9_]*$/);
    }
  });

  it('starts every form on a type the backend accepts', () => {
    expect(isDeliverableTypeValue(DEFAULT_DELIVERABLE_TYPE)).toBe(true);
  });

  it('rejects the vocabularies the forms used to send, rather than treating them as valid', () => {
    for (const legacy of ['REEL', 'POST', 'STORY', 'VIDEO', 'SHORT', 'Blog Post', 'Other']) {
      expect(isDeliverableTypeValue(legacy), `${legacy} must not pass as a wire value`).toBe(false);
    }
  });

  it('shows an unrecognised stored type as itself, never as a guessed one', () => {
    // A row written before this vocabulary existed. Rendering it as "Instagram Reel" would repeat
    // the server-side bug on the client.
    expect(deliverableTypeLabel('SOME_OLD_TYPE')).toBe('SOME OLD TYPE');
    expect(deliverableTypeLabel('YOUTUBE_SHORT')).toBe('YouTube Short');
  });
});
