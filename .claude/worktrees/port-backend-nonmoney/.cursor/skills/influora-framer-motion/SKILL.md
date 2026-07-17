---
name: influora-framer-motion
description: Framer Motion patterns for Influora — FadeUp, Stagger, WordReveal, TiltCard, AnimatePresence. Use when adding scroll reveals, page transitions, onboarding steps, or importing from @/components/motion.
---

# Influora — Framer Motion

Parent skill — see also granular skills: `influora-framer-scroll-reveal`, `influora-framer-stagger`, `influora-framer-animate-presence`, `influora-framer-layout`.

Test page: http://localhost:3000/dev/motion-skills (dev only)

## Reuse before raw Framer

Import from `@/components/motion`:

- `FadeUp` — sections below fold
- `StaggerContainer` + `StaggerItem` — card grids
- `WordReveal` — page titles
- `TiltCard` — interactive cards
- `CountUp` — wallet/dashboard stats
- `MagneticButton` — auth CTAs only

Constants: `@/lib/motion-config.ts`

## Patterns

- Scroll: `whileInView` + `viewport={{ once: true, margin: '-80px' }}`
- Steps: `AnimatePresence mode="wait"`
- Never stagger full chat history or table refresh
- Never nest stagger-spam

## Motion levels (SaaS)

| Level | Where |
|-------|-------|
| 0–1 | Chat, forms — minimal |
| 1 | Campaigns, wallet, deals lists |
| 2 | Onboarding, campaign detail |
| 3 | Auth, discover hero, public portfolio |

## Chat forbidden

- No stagger on message list load
- New messages: opacity fade 150ms only

Docs: `docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md`
