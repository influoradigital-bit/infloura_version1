# Influora Frontend — Page & UI Documentation

Complete audit of **every frontend page**, UI elements, data models, and color system.  
**No code** — reference documentation for designers, developers, and QA.

**Stack:** React 19 · Vite 6 · TypeScript · Tailwind CSS 4 · shadcn/Radix UI · Lilac Mist theme

---

## Documentation files

| File | Contents |
|------|----------|
| [**COLOR-SYSTEM.md**](./COLOR-SYSTEM.md) | All CSS variables, stage palette, hardcoded accents, auth 3D colors |
| [**UI-ELEMENTS-AND-MODELS.md**](./UI-ELEMENTS-AND-MODELS.md) | Buttons, forms, tables, dialogs, 57 UI components, TypeScript models |
| [**BRAND-PAGES-AUDIT.md**](./BRAND-PAGES-AUDIT.md) | All 20 brand routes + page files — elements per page |
| [**CREATOR-PAGES-AUDIT.md**](./CREATOR-PAGES-AUDIT.md) | All 12 creator routes + page files — elements per page |
| [**SHARED-LAYOUTS-AND-MISC.md**](./SHARED-LAYOUTS-AND-MISC.md) | Layouts, auth shell, 3D, static/404, retired pages |

**For UI revamp with motion skills:** [`../react/PRIYA-ALL-PAGES-AND-ELEMENTS.md`](../react/PRIYA-ALL-PAGES-AND-ELEMENTS.md) (pages + elements + API freeze)

---

## Page count summary

| Category | Active routes | Page files |
|----------|---------------|------------|
| Brand portal | 16 | 18 (`brand-deals`, `brand-pipeline` retired) |
| Creator portal | 10 | 12 (`creator-inbox`, `creator-active` retired) |
| Public / misc | 4 | 4 (`static`, `not-found`, portfolio public, `/:handle`) |
| **Total page files** | — | **32** |

---

## Layout zones

```
┌─────────────────────────────────────────────────────────┐
│  AUTH ZONE — AuthLoginShell / auth-gradient             │
│  /brand/login, /brand/register, /creator/login, etc.    │
└─────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────┐
│  ONBOARDING ZONE — OnboardingLayout / custom steps       │
│  /brand/onboarding, /creator/onboarding                 │
└─────────────────────────────────────────────────────────┘
┌──────────┬──────────────────────────────────────────────┐
│ BrandLayout │  Dashboard, campaigns, discover, chat…    │
│ or          │                                           │
│ CreatorLayout│  deals, wallet, profile, settings…      │
└──────────┴──────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────┐
│  PUBLIC ZONE — no layout shell                          │
│  /:handle, /terms, /privacy, /support, 404              │
└─────────────────────────────────────────────────────────┘
```

---

## Quick reference — which UI elements appear most

| Element | Used on (approx.) |
|---------|-------------------|
| **Button** | All 32 pages |
| **Card** | 24 pages |
| **Badge** | 22 pages |
| **Input + Label** | 18 pages |
| **Avatar** | 16 pages |
| **Tabs** | 12 pages |
| **Dialog** | 14 pages |
| **ScrollArea** | 10 pages |
| **Textarea** | 10 pages |
| **Select** | 10 pages |
| **Progress** | 9 pages |
| **Sheet** | 8 pages |
| **Switch** | 4 pages |
| **Slider** | 3 pages |
| **Calendar** | 1 page (campaign form) |
| **Table (shadcn)** | **0 pages** (HTML `<table>` only on retired pipeline) |
| **Chart (recharts)** | **0 pages** (custom bars/progress only) |

---

## Data source split (all pages)

| Source | Pages |
|--------|-------|
| **Mock data only** | campaigns list, campaign detail, creator profile, wallet (both), messages, settings, chat (both), most deal flows |
| **Live API** | brand login/register/onboarding, campaign form, creator discovery (hybrid), portfolio editor/public, creator deals (hybrid) |
| **Zustand** | `useAuthStore`, `useCampaignStore`, `useNotificationStore`, `useUIStore` |
| **localStorage** | `brand_token`, `creator_token`, onboarding flags, deal messages, contract store |

---

## Related docs

- `docs/PROJECT-OVERVIEW.md` — full project structure
- `docs/react/` — motion & 3D implementation docs
- `src/lib/types.ts` — source of truth for domain types
- `src/app/globals.css` — theme tokens

---

*Frontend audit v1.0 — June 2026*
