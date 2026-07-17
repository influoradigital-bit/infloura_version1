---
name: influora-framer-layout
description: Framer Motion layout animations for Influora — layoutId lightbox, shared element transitions. Use sparingly on portfolio gallery and modal expansions only.
---

# Influora — Framer Layout (Skill 8)

## Allowed uses (Influora SaaS)

- Public portfolio `/:handle` — gallery thumb → lightbox via `layoutId`
- Campaign detail image preview (optional)
- Deal room attachment preview (optional, Level 0–1 — keep subtle)

## Rules

- `layoutId` must be unique per item (include id in string)
- Wrap shared elements in `motion.div layoutId="..."`
- Reduced motion: skip layout animation, instant open
- Never layoutId on chat bubbles or table rows
- Prefer opacity crossfade over complex layout on dashboard

## Performance

- Layout animations trigger layout measurement — max ~20 items with layoutId
- Disable layout on mobile if janky

## Alternative

Most Influora pages use FadeUp + TiltCard — layout skill is **optional**, not default.

Docs: `docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md` Portfolio public row
