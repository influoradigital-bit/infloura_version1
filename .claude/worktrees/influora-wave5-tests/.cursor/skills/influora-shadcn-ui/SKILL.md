---
name: influora-shadcn-ui
description: shadcn/Radix UI conventions for Influora — use components from src/components/ui, Lilac Mist tokens, form patterns. Use when building forms, dialogs, cards, or any UI in src/pages or src/components.
---

# Influora — shadcn UI (Skill 24)

## Import from design system

```tsx
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
```

Never raw `<input>` on auth pages — use shadcn Input + Label.

## Theme tokens (Lilac Mist)

Use Tailwind semantic classes, not hardcoded hex:

- `bg-primary`, `text-foreground`, `bg-card`, `border-border`
- `bg-muted`, `text-muted-foreground`
- Stage badges: `bg-stage-negotiating`, `text-stage-negotiating-fg`

See `docs/frontend/COLOR-SYSTEM.md`

## Lists

- Card grids, not `@/components/ui/table` (table unused in Influora)
- Empty states: Card + muted text + optional FadeUp

## Dialogs & sheets

- `Dialog` for modals (accept bid, withdraw, sign)
- `Sheet` for filters and mobile nav
- Deal Room proposal uses custom overlay — don't force Dialog if pattern exists

## Forms

- react-hook-form + zod where multi-step (campaign-form)
- Rule Set D: focus ring, error shake, success state

Location: `src/components/ui/` (57 components)
