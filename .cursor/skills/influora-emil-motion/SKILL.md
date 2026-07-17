---
name: influora-emil-motion
description: Enforces Emil Kowalski animation standards for Influora React UI — button press scale, easing, no transition-all, reduced motion. Use when editing buttons, hovers, transitions, cards, or any CSS/Framer animation in src/.
---

# Influora — Emil Motion Standards

Stack: React 19 · Vite · Tailwind 4 · Lilac Mist theme.

## Button rules

- `active:scale-[0.97]` on every button, `160ms ease-out`
- Never `transition-all` — use `transition-[transform,box-shadow,opacity,border-color]`
- Never `ease-in` — use `ease-out` or `cubic-bezier(0.23, 1, 0.32, 1)`
- Hover only with `@media (hover: hover) and (pointer: fine)`
- Icon buttons: hover `scale(1.08)`, active `scale(0.94)`

## Timing

- UI animations ≤ 300ms (aurora/3D ambient excepted)
- Entry 250ms, exit 150ms (asymmetric)
- Stagger list items: 30–80ms — no stagger-spam

## Entry/exit

- Never `scale(0)` — use `scale(0.95)` + `opacity: 0`
- Spring `{ type: 'spring', duration: 0.5, bounce: 0.2 }` for MagneticButton only

## Accessibility

- `prefers-reduced-motion`: disable or simplify all motion
- No animation on keyboard-triggered actions
- No blocking animation on focus-visible

## Forms (Rule Set D)

- Focus: `ring-2 ring-primary/30`, 150ms ease-out
- Error: border-destructive + shake ±4px, 300ms
- Success: AnimatePresence + checkmark spring

## Anti-patterns

| Wrong | Fix |
|-------|-----|
| `transition-all` | Specific properties |
| `hover:scale-105` on every card | TiltCard |
| `animate-pulse` on decor | Remove |
| MagneticButton on Send/Save | Auth hero only |

Docs: `docs/react/INFLUORA-MASTER-PROMPT.md`
