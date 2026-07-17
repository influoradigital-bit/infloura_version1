---
name: influora-gsap-scroll
description: GSAP ScrollTrigger pinned scroll for Influora Phase 7 — dashboard pipeline, marketing sections. Use with useScrollPin hook. Never on chat or deal room pages.
---

# Influora — GSAP Scroll (Skill 10)

## Installed packages

- `gsap`
- `@gsap/react`

## Utilities

- `src/lib/scroll/smooth-scroll.ts` — registers ScrollTrigger, syncs with Lenis
- `src/hooks/useScrollPin.ts` — pin sections with reduced-motion skip

## Rules

- **Never** on `/brand/chat`, `/creator/chat`, forms mid-submit
- **Always** check `useReducedMotion()` — static layout when off
- Pin only one section per viewport height
- Kill ScrollTriggers on unmount (`ScrollTrigger.getAll().forEach(kill)`)

## Allowed targets

- Brand dashboard pipeline timeline (optional)
- Marketing landing sections (future)
- Static pages with long content (terms/privacy) — prefer Lenis only

## Usage pattern

1. Enable `useSmoothScroll(true)` on the page
2. Pass container + pin target refs to `useScrollPin`
3. Test with Windows Animation effects OFF

Docs: `docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md` Phase 7
