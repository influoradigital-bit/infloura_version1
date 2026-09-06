/**
 * F-0661 — PortfolioItem type fidelity (Ananya).
 *
 * Backend investigation (verbatim, no server-side change made): `CreatorDtos.PortfolioItemResponse`
 * (influora-api/.../web/dto/creator/CreatorDtos.java:49-55) is
 * `record PortfolioItemResponse(id, title, description, thumbnailUrl, mediaUrl, platform)` — no
 * `metrics` field exists, and never has. `CreatorMapper.toPortfolioItem` (the only producer) always
 * passes a literal `null` for `description` — the server's only free-text source, `caption`, is
 * already mapped into `title`.
 *
 * This file makes `src/lib/types.ts#PortfolioItem` honest against that:
 *   1. `metrics` removed — no producer of this DTO has ever sent it, and the closest real data
 *      (`PortfolioPinnedPost.views`/`likes`) is self-reported, not verified analytics.
 *   2. `description` changed from `description?: string` to `description: string | null` —
 *      `CreatorDtos.PortfolioItemResponse` has no `@JsonInclude(NON_NULL)` (class- or field-level)
 *      and no global `spring.jackson.default-property-inclusion` is set anywhere in this repo, so
 *      the key is always present on the wire; the value is always literally `null` today. `string
 *      | undefined` (optional) is the wrong shape for an always-present, sometimes-null key.
 *
 * grep confirms zero current consumers of `PortfolioItem.metrics` or `.description` in src/ — every
 * `portfolioItems` array in the codebase today is a literal `[]` (creator-discovery.tsx,
 * demo-data.ts, brand-creator-profile.tsx's TODO(vikram) stub, api.ts's `row.portfolioItems ?? []`
 * passthrough). So this is a type-only correction with no live consumer to fix — verified by
 * `npx tsc --noEmit`, not by a behavioral assertion.
 *
 * This is a COMPILE-TIME regression test, not a runtime one — vitest transforms TS via esbuild and
 * does not typecheck (`@ts-expect-error` below is inert to `npx vitest run`; only `npx tsc --noEmit`
 * validates it). Falsified by reverting the type change and running tsc directly:
 *   - OLD (`metrics?: {...}` present): `item.metrics = {...}` is a valid assignment, so the
 *     `@ts-expect-error` directive suppresses no error → TS2578 "Unused '@ts-expect-error'
 *     directive" → tsc fails, for the right reason.
 *   - NEW (`metrics` removed): the assignment errors (no such property) → the directive correctly
 *     suppresses it → tsc passes.
 * The `description: null` literal is the same kind of falsifiable assertion for the second fix:
 * it only compiles because the field is `string | null`, not `string | undefined`.
 *
 * Run: npx tsc --noEmit
 */

import { describe, it, expect } from 'vitest';
import type { PortfolioItem } from '@/lib/types';

describe('PortfolioItem type fidelity (F-0661)', () => {
  it('has no `metrics` field — CreatorDtos.PortfolioItemResponse has never sent one', () => {
    const item: PortfolioItem = {
      id: 'p1',
      title: 'Reel with a brand',
      description: null,
      thumbnailUrl: 'https://cdn.example.com/thumb.jpg',
      mediaUrl: 'https://cdn.example.com/reel.mp4',
      platform: 'INSTAGRAM',
    };

    // Real server payloads (JSON -> object) never carry this key either — assert that BEFORE the
    // deliberate @ts-expect-error assignment below, which would otherwise add the key itself and
    // make this assertion vacuous.
    expect(Object.prototype.hasOwnProperty.call(item, 'metrics')).toBe(false);

    // @ts-expect-error — F-0661: `metrics` was removed from PortfolioItem. The server has never
    // had a `metrics` field on this DTO (confirmed in CreatorDtos.java/CreatorMapper.java), so
    // reassigning it here must fail to typecheck; see the file header for how this falsifies.
    item.metrics = { views: 100, likes: 5 };
  });

  it('`description` is present-but-nullable, not optional — the key is always on the wire', () => {
    // Compiles only because `description` is typed `string | null`. Under this project's
    // `strict: true` tsconfig (strictNullChecks on), assigning a literal `null` here would be a
    // type error against the old `description?: string` (`string | undefined`) — `null` and
    // `undefined` are distinct types in strict mode.
    const item: PortfolioItem = {
      id: 'p1',
      title: 'Reel with a brand',
      description: null,
      thumbnailUrl: 'https://cdn.example.com/thumb.jpg',
    };

    expect(item.description).toBeNull();
  });
});
