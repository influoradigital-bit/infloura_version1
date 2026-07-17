---
name: influora-framer-animate-presence
description: Framer Motion AnimatePresence for Influora — onboarding steps, tabs, modals, form success states. Use when UI mounts/unmounts with enter/exit animation.
---

# Influora — Framer AnimatePresence (Skill 7)

## When to use

- Onboarding multi-step flows (`/brand/onboarding`, `/creator/onboarding`)
- Tab panel swaps with exit animation
- Form success → checkmark swap (Rule Set D)
- Conditional empty states vs content
- Mobile nav sheet content (optional)

## Rules

- `mode="wait"` for step flows — one step exits before next enters
- Exit faster than enter: ~150ms out, ~250ms in (asymmetric)
- Always wrap conditional children: `{show && <motion.div key={step} />}`
- Unique `key` per step/tab required
- `useReducedMotion()` → skip exit, instant swap

## Onboarding pattern

Each step is a keyed motion.div inside AnimatePresence. Progress bar animates separately (CSS or motion width).

## Chat exception

- Do NOT AnimatePresence entire message list on load
- New single message: opacity fade 150ms only, no layout shift

## Form success

Error state → success checkmark: AnimatePresence + spring on icon only.

Docs: `docs/react/INFLUORA-MASTER-PROMPT.md` Rule Set D
