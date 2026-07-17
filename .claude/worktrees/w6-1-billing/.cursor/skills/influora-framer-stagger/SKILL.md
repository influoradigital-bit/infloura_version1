---
name: influora-framer-stagger
description: Framer Motion stagger patterns for Influora — StaggerContainer, StaggerItem, list reveals. Use on card grids for campaigns, deals, discover results. Never on chat or tables.
---

# Influora — Framer Stagger (Skill 6)

## Components

- `StaggerContainer` — parent with staggerChildren 50ms
- `StaggerItem` — child fade + y slide

Import from `@/components/motion`.

## Rules

- Stagger gap: 30–80ms (default 50ms via `STAGGER_DEFAULT`)
- One stagger layer per grid — no nested stagger-spam
- `viewport once: true` on container
- Reduced motion: instant show, no stagger
- Pair grid cards with `TiltCard` for hover depth

## Good targets

- `/brand/campaigns` campaign cards
- `/creator/deals` deal cards
- Discover creator grid (after hero)
- Dashboard quick-action cards

## Forbidden

- Chat history load
- Deal room message thread
- Data table refresh
- Nested StaggerContainer inside StaggerItem children

## Pattern

Wrap grid → map items each in StaggerItem → card content inside TiltCard optional.

Docs: `docs/react/prompts/INFLUORA-PAGE-SESSION-PROMPTS.md` PS-02, PS-08
