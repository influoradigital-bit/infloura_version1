---
name: influora-lenis-scroll
description: Lenis smooth scroll for Influora marketing and static pages. Synced with GSAP ScrollTrigger. Use via useSmoothScroll hook — not on chat or dashboard app shell.
---

# Influora — Lenis Smooth Scroll (Skill 11)

## Installed

- `lenis` + CSS imported in `src/main.tsx`

## Hook

```tsx
import { useSmoothScroll } from '@/hooks/useSmoothScroll'

useSmoothScroll(true) // pass false to disable on a page
```

Auto-disabled when `prefers-reduced-motion` is on.

## Where enabled

- `src/pages/static-page.tsx` — terms, privacy, support
- Future marketing landing pages
- Dev test page (optional)

## Where forbidden

- Brand/Creator app shell (dashboard, chat, deals, wallet)
- Any page requiring instant scroll for productivity

## GSAP sync

Lenis raf runs on `gsap.ticker` — required before ScrollTrigger pins.

Init: `initSmoothScroll()` from `@/lib/scroll/smooth-scroll`
Cleanup: `destroySmoothScroll()` on route leave

Docs: `docs/react/PRIYA-INSTALL-AND-RUN-SKILLS.md` §8
