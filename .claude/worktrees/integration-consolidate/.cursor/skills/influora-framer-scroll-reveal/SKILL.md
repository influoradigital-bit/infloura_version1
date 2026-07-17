---
name: influora-framer-scroll-reveal
description: Framer Motion scroll reveals for Influora — FadeUp, whileInView, viewport once, useInViewOnce. Use when animating sections below the fold on dashboard, campaigns, wallet, or marketing bands.
---

# Influora — Framer Scroll Reveal (Skill 5)

## Use FadeUp first

Import `FadeUp` from `@/components/motion` — do not copy inline motion.div patterns.

## Rules

- `whileInView` with `viewport={{ once: true, margin: '-80px' }}` from `@/lib/motion-config`
- Default entry: opacity 0 → 1, y 24 → 0, 250ms ease-out
- Never animate from scale(0) — use scale(0.95) if scaling
- `useReducedMotion()` → static render, no animation
- Section headings: FadeUp y=24
- Page titles in hero bands: `WordReveal` instead

## When to use

| Page | What to reveal |
|------|----------------|
| Dashboard | Stat cards, activity sections |
| Campaigns | Header block, filter bar |
| Wallet | Balance section, transaction list header |
| Settings | Form sections |

## When NOT to use

- Chat message lists
- Table row refresh
- Modal content (use AnimatePresence skill instead)
- Above-the-fold hero on L3 pages (use WordReveal or 3D)

## Hook

`useInViewOnce` from `@/hooks/useInViewOnce` for non-Framer counters (CountUp).

Docs: `docs/react/INFLUORA-MASTER-PROMPT.md` Rule Set E
