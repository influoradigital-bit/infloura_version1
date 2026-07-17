---
name: influora-motion-audit
description: AUDIT-before-animate workflow for Influora pages — motion levels, self-check, anti-AI-slop. Use before adding motion to any page or when reviewing UI polish.
---

# Influora — Motion Audit (impeccable workflow)

## Before coding any page

1. Read `docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md` — assign Level 0–3
2. Read `docs/frontend/BRAND-PAGES-AUDIT.md` or `CREATOR-PAGES-AUDIT.md`
3. AUDIT existing UI — then CREATE motion wrappers

## Decision tree

- Chat/deals? → Level 0–1 only, NO 3D
- Forms/settings? → Level 1, focus + success animation
- Card grid? → Stagger + TiltCard
- Onboarding? → Level 2 AnimatePresence
- Discover/portfolio hero? → Level 3 canvas allowed

## Self-check (after every page)

```
EMIL: scale(0.97), no transition-all, no ease-in, reduced-motion
MOTION: AnimatePresence on steps, no chat stagger-spam
COLOURS: Lilac Mist CSS vars, no pure black
R3F: dpr [1,1.5], lazy, PerformanceMonitor, fallback
SAAS: chat instant, no canvas in layout, api/store unchanged
```

## Anti-AI-slop

- No pulse on decorative elements
- No bouncy springs on utility UI
- No stagger on every nested div
- No plain static h1 on hero bands — WordReveal or FadeUp

## Update status

Tick checkbox in `docs/react/INFLUORA-PLAN-OF-ACTION.md`
