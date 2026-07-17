# Influora — Color System Reference

**Theme name:** Lilac Mist  
**Source file:** `src/app/globals.css`  
**Default mode:** Light (dark class optional)

---

## 1. Core semantic tokens (light mode)

| Token | Hex | Tailwind class | Usage |
|-------|-----|----------------|-------|
| `--background` | `#f0ebfa` | `bg-background` | Page background — soft lavender |
| `--foreground` | `#3d3852` | `text-foreground` | Primary body text — warm gray-purple |
| `--card` | `#ffffff` | `bg-card` | Cards, modals, frosted panels |
| `--card-foreground` | `#3d3852` | `text-card-foreground` | Text on cards |
| `--primary` | `#9b8cf2` | `bg-primary` / `text-primary` | CTAs, links, active nav, rings |
| `--primary-foreground` | `#ffffff` | `text-primary-foreground` | Text on primary buttons |
| `--secondary` | `#ebe4f8` | `bg-secondary` | Secondary surfaces |
| `--secondary-foreground` | `#3d3852` | `text-secondary-foreground` | Text on secondary |
| `--muted` | `#ebe4f8` | `bg-muted` | Subtle backgrounds, inputs idle |
| `--muted-foreground` | `#7a738f` | `text-muted-foreground` | Secondary text, placeholders |
| `--accent` | `#ede9fe` | `bg-accent` | Hover states, highlights |
| `--accent-foreground` | `#5b4db3` | `text-accent-foreground` | Text on accent |
| `--border` | `#d8d4e8` | `border-border` | Card borders, dividers |
| `--input` | `#d8d4e8` | `border-input` | Input borders |
| `--ring` | `#9b8cf2` | `ring-ring` | Focus rings |
| `--radius` | `0.625rem` | `rounded-lg` | Base border radius (10px) |

---

## 2. Status & feedback colors

| Token | Background | Foreground | Usage |
|-------|--------------|------------|-------|
| `--success` | `#ddf5e8` | `#2f7a54` | Completed, verified, approved deals |
| `--warning` | `#fff4d6` | `#8a6b1f` | SLA at risk, pending attention |
| `--destructive` | `#ffe5e5` | `#a63a3a` | Errors, disputes, delete actions |
| `--info` | `#e3f0ff` | `#3e6fae` | Informational banners |

---

## 3. Sidebar tokens

| Token | Hex | Usage |
|-------|-----|-------|
| `--sidebar` | `#ebe4f8` | Brand/creator sidebar background |
| `--sidebar-foreground` | `#3d3852` | Sidebar text |
| `--sidebar-primary` | `#9b8cf2` | Active nav indicator |
| `--sidebar-accent` | `#ede9fe` | Nav hover |
| `--sidebar-border` | `#d8d4e8` | Sidebar dividers |

---

## 4. Chart palette

| Token | Hex | Typical use |
|-------|-----|-------------|
| `--chart-1` | `#9b8cf2` | Primary metric — lilac |
| `--chart-2` | `#7ec8e8` | Secondary — sky blue |
| `--chart-3` | `#f5b89a` | Tertiary — peach |
| `--chart-4` | `#2e7d7d` | Teal |
| `--chart-5` | `#a85a36` | Warm brown |

---

## 5. Deal stage palette (badges & pipeline)

Defined in `globals.css`, mapped via `src/lib/stage-colors.ts`:

| Stage key | Background | Text | Border | Meaning |
|-----------|------------|------|--------|---------|
| `draft` | `#e8e6ed` | `#625d70` | `#d4d1dc` | Draft / queued |
| `outreach` | `#e3f0ff` | `#3e6fae` | `#c5dcf5` | Outreach, new, pending |
| `negotiating` | `#fff4d6` | `#8a6b1f` | `#f0e0a8` | Negotiation, counter |
| `contracted` | `#ede9fe` | `#5b4db3` | `#d4ccf0` | Contract signed |
| `in_progress` | `#e0f5f5` | `#2e7d7d` | `#b8e0e0` | Production / active |
| `review` | `#ffe8dd` | `#a85a36` | `#f5d0c0` | Under review |
| `approved` | `#ddf5e8` | `#2f7a54` | `#b8e6cf` | Approved / completed |
| `disputed` | `#ffe5e5` | `#a63a3a` | `#f0c4c4` | Dispute |
| `paused` | `#f0ede8` | `#7a6f5c` | `#e0dcd4` | Paused |
| `cancelled` | `#eeeef0` | `#5c5a66` | `#dcdce0` | Cancelled |
| `expired` | `#f5e8ee` | `#8b4a62` | `#e8d0dc` | Expired |

**Tailwind usage:** `bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border`

---

## 6. Auth & 3D accent colors (hardcoded)

Used in `login-scene-3d.tsx`, `aurora-background.tsx`, `auth-login-shell.tsx`:

| Hex | Element |
|-----|---------|
| `#9b8cf2` | Primary 3D blob, aurora blob A |
| `#c4b5fd` | Blob B, aurora |
| `#7ec8e8` | Blob C, sky accent |
| `#ddd6fe` | Blob D, soft lilac |
| `#f5f0ff` | Directional light tint |
| `#b8d4f0` | Secondary light |
| `#e9d5ff` | Point light |

**Auth utilities:**
- `.auth-gradient` — diagonal lavender gradient
- `.bg-auth-mesh` — radial mesh overlay on auth pages

---

## 7. Dark mode tokens (optional `.dark` class)

| Token | Hex |
|-------|-----|
| `--background` | `#2a2838` |
| `--foreground` | `#f0eef5` |
| `--card` | `#353347` |
| `--primary` | `#b0a3f5` |
| `--muted-foreground` | `#b5b0c4` |

Not all pages tested in dark mode — theme variables exist but app defaults to light.

---

## 8. Platform brand colors (hardcoded in pages)

Used for Instagram/YouTube/Twitter icons — **not** theme tokens:

| Platform | Typical classes / hex |
|----------|----------------------|
| Instagram | `text-pink-500`, `#E4405F` |
| YouTube | `text-red-500`, `#FF0000`, `bg-red-500` |
| Twitter/X | `text-sky-500`, `#1DA1F2` |
| LinkedIn | `text-blue-500` |
| TikTok | `text-foreground` or custom |

---

## 9. Common hardcoded Tailwind (legacy — prefer tokens)

Found across pages — candidates for migration to design tokens:

| Classes | Where used |
|---------|------------|
| `text-green-500` / `bg-green-500` | Online indicator, verified, success icons |
| `text-red-400/500` | Errors, YouTube, wallet debits |
| `text-amber-500/700` | Warnings, SLA, processing |
| `text-blue-400/500` | Info, contract messages |
| `text-purple-400/600` | Deal/chat accents |
| `from-violet-100 to-purple-100` | Avatar fallback gradients |
| `bg-blue-500` | Register step progress (brand-register) |

---

## 10. Color combination recipes

### Primary CTA button
```
bg-primary text-primary-foreground hover:bg-primary/90
ring-ring focus-visible:ring-2
```

### Card on page background
```
bg-card text-card-foreground border border-border rounded-xl shadow-sm
```

### Auth frosted panel
```
bg-card/75 border border-white/50 backdrop-blur-xl
shadow-[0_8px_40px_-12px_rgba(155,140,242,0.35)]
```

### Status badge (negotiating example)
```
border bg-stage-negotiating text-stage-negotiating-fg border-stage-negotiating-border
```

### Creator chat bubble (creator sent)
```
bg-primary text-primary-foreground
```

### Brand chat bubble (brand sent)
```
bg-muted text-foreground
```

### Wallet hero gradient (creator)
```
bg-gradient-to-br from-primary to-accent text-white
```

### Error alert
```
bg-destructive/10 border border-destructive/30 text-destructive
```

### Success alert
```
bg-success/10 text-success border border-success/30
```

---

## 11. Icon badge variants (`IconBadge`)

Mapped in `src/lib/icon-theme.ts` to stage colors:

| Variant | Nav route example |
|---------|-------------------|
| `primary` | Brand dashboard |
| `outreach` | Campaigns, creator inbox |
| `negotiating` | Deals / chat |
| `contracted` | Discover, creator profile |
| `review` | Contracts |
| `approved` | Wallet |
| `progress` | Creator active |
| `muted` | Settings |

---

*Reference only — always prefer CSS variables from `globals.css` for new UI.*
