---
name: influora-motion-components
description: Reusable Influora motion component library in src/components/motion and src/components/3d. Use when building pages, integrating scroll reveals, or extending the motion system (Skill 32).
---

# Influora — Motion Components (Skill 32)

## Import map

```tsx
import { FadeUp, StaggerContainer, StaggerItem, WordReveal, TiltCard, CountUp, MagneticButton } from '@/components/motion'
import { CanvasFallback } from '@/components/3d'
import { DURATION_NORMAL, EASE_OUT, VIEWPORT_ONCE } from '@/lib/motion-config'
import { useInViewOnce } from '@/hooks/useInViewOnce'
```

## When to use

| Component | Use on |
|-----------|--------|
| FadeUp | Section blocks below fold |
| StaggerContainer + StaggerItem | Card grids (campaigns, deals) |
| WordReveal | Page/hero titles |
| TiltCard | Discover cards, portfolio tiles |
| CountUp | Wallet, dashboard stats |
| MagneticButton | Auth CTAs only |
| CanvasFallback | Reduced motion / WebGL off |

## Build prompts

New components: `docs/react/prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` (MC-00 → MC-11)

Page integration: `docs/react/prompts/INFLUORA-PAGE-SESSION-PROMPTS.md` (PS-01 → PS-12)

## Do not

- Duplicate FadeUp logic inline on pages
- Add components to `src/components/motion/` without Emil + reduced-motion rules
- Use MagneticButton outside auth hero
