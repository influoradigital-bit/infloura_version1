# Influora — Project Config (React Motion)
### Single source of truth for colors, routes, motion caps, and placeholders

> Fill/update this file when brand tokens change. All other React motion docs reference values here.

---

## Brand Identity

| Key | Value | Used in |
|-----|-------|---------|
| `BRAND_NAME` | Influora | Titles, prompts, logo |
| `BRAND_TAGLINE` | Creator Collaboration OS | Auth hero, marketing |
| `PRODUCT_TYPE` | B2B Influencer Marketing SaaS | Motion tone — professional, not playful consumer |
| `TARGET_MARKET` | India-first brands & creators | INR, GST/TDS copy |
| `PERSONA_NAME` | Influora Motion Engineer | AI session persona |
| `PERSONA_ROLE` | React 3D Motion Expert + SaaS UX Specialist |

---

## Color System (Lilac Mist)

From `src/app/globals.css`:

| Token | Hex / CSS var | Usage |
|-------|---------------|-------|
| `PRIMARY_COLOR` | `#9b8cf2` | Primary CTA, brand accent, 3D blob A |
| `PRIMARY_HOVER` | `#8b7ae8` | CTA hover (derive from primary) |
| `PRIMARY_TINT_BG` | `#ede9fe` / `--accent` | Light tint sections |
| `SECONDARY_ACCENT` | `#7ec8e8` | Charts, 3D blob C, info states |
| `TERTIARY_ACCENT` | `#c4b5fd` | 3D blob B, secondary highlights |
| `SOFT_LILAC` | `#ddd6fe` | 3D blob D, subtle fills |
| `BACKGROUND` | `#f0ebfa` | Page background (`--background`) |
| `FOREGROUND` | `#3d3852` | Body text (`--foreground`) |
| `CARD` | `#ffffff` | Cards (`--card`) |
| `BORDER_LIGHT` | `#d8d4e8` | Card borders (`--border`) |
| `MUTED_FG` | `#7a738f` | Secondary text |
| `SUCCESS` | `#2f7a54` on `#ddf5e8` | Completed deals, verified |
| `WARNING` | `#8a6b1f` on `#fff4d6` | SLA at risk |
| `DESTRUCTIVE` | `#a63a3a` on `#ffe5e5` | Errors, disputes |

### Brand portal accent (warm lilac)

Used on auth, brand dashboard, campaigns.

### Creator portal accent (same base + optional shift)

Creator pages may use slightly more `#7ec8e8` (sky) in 3D scenes to differentiate — not a full rebrand.

**Banned colors:** Pure black `#000`, cold `#08080a`, off-brand neon greens unless status-specific.

---

## Site Structure (React Router)

### Brand routes

| Key | Path |
|-----|------|
| `BRAND_LOGIN` | `/brand/login` |
| `BRAND_REGISTER` | `/brand/register` |
| `BRAND_ONBOARDING` | `/brand/onboarding` |
| `BRAND_DASHBOARD` | `/brand/dashboard` |
| `BRAND_CAMPAIGNS` | `/brand/campaigns` |
| `BRAND_DISCOVER` | `/brand/discover` |
| `BRAND_CHAT` | `/brand/chat` |
| `BRAND_WALLET` | `/brand/wallet` |
| `BRAND_SETTINGS` | `/brand/settings` |

### Creator routes

| Key | Path |
|-----|------|
| `CREATOR_LOGIN` | `/creator/login` |
| `CREATOR_DEALS` | `/creator/deals` |
| `CREATOR_CHAT` | `/creator/chat` |
| `CREATOR_PORTFOLIO_EDIT` | `/creator/portfolio` |
| `CREATOR_PORTFOLIO_PUBLIC` | `/:handle` (e.g. `/@priya_creates`) |

---

## React codebase paths

| Key | Path |
|-----|------|
| `APP_ENTRY` | `src/main.tsx` |
| `ROUTES` | `src/App.tsx` |
| `GLOBAL_CSS` | `src/app/globals.css` |
| `API_CLIENT` | `src/lib/api.ts` |
| `TYPES` | `src/lib/types.ts` |
| `AUTH_3D` | `src/components/shared/login-scene-3d.tsx` |
| `AUTH_SHELL` | `src/components/shared/auth-login-shell.tsx` |
| `AURORA` | `src/components/shared/aurora-background.tsx` |
| `BRAND_LAYOUT` | `src/components/brand/brand-layout.tsx` |
| `CREATOR_LAYOUT` | `src/components/creator/creator-layout.tsx` |
| `MOTION_FOLDER` | `src/components/motion/` (to create) |
| `CANVAS_FOLDER` | `src/components/3d/` (to create) |
| `MOTION_CONFIG` | `src/lib/motion-config.ts` (to create) |

---

## Motion caps (SaaS rules)

| Rule | Value |
|------|-------|
| Max full R3F hero canvases | **3** sitewide |
| Max active WebGL per viewport | **1** |
| Canvas DPR | `[1, 1.5]` |
| Chat / deal room max level | **Level 1** |
| App shell (sidebar) 3D | **None** |

### Canvas inventory

| Canvas | File | Route | Status |
|--------|------|-------|--------|
| Auth blobs | `login-scene-3d.tsx` | brand/creator auth | ✅ Exists — polish |
| Discover network | `3d/DiscoverCanvas.tsx` | `/brand/discover` | ⬜ Planned |
| Portfolio orbit | `3d/PortfolioCanvas.tsx` | `/:handle` | ⬜ Planned |

---

## npm dependencies (motion)

| Package | Status | Phase |
|---------|--------|-------|
| `framer-motion` | ✅ Installed | 1 |
| `@react-three/fiber` | ✅ Installed | 2–5 |
| `@react-three/drei` | ✅ Installed | 2–5 |
| `three` | ✅ Installed | 2–5 |
| `gsap` + `@gsap/react` | ⬜ Optional | 4 |
| `lenis` | ⬜ Optional | 4 (marketing landing only) |

---

## Environment (frontend)

| Variable | Default | Purpose |
|----------|---------|---------|
| `VITE_API_MODE` | `mock` | `live` = Spring Boot |
| `VITE_API_BASE_URL` | `http://localhost:8080/api/v1` | API base |

---

## Gemini / visual assets (optional)

| Key | Value |
|-----|-------|
| `GEMINI_API_KEY` | `.env.local` only — never commit |
| `IMAGE_MODEL` | `gemini-2.5-flash-image` |
| `VIDEO_MODEL` | `veo-3.1` |
| `API_DELAY_MS` | 3000 |

Asset output (if generated):

```
public/
├── images/
│   ├── auth/           # optional hero stills for reduced-motion
│   ├── discover/       # creator discovery banner
│   ├── campaigns/      # campaign empty states
│   └── portfolio/      # public portfolio backgrounds
└── videos/
    └── loops/          # subtle bg loops (auth, discover)
```

---

## Build status (update each session)

| Phase | Status | Notes |
|-------|--------|-------|
| Config filled | ✅ | This file |
| Motion primitives | ⬜ | `components/motion/` |
| Auth 3D polish | 🔄 | LoginScene3D exists |
| Level 1 brand pages | ⬜ | campaigns, discover grid, wallet |
| Level 1 creator pages | ⬜ | deals, wallet, profile |
| Level 2 onboarding | 🔄 | partial framer in onboarding-layout |
| DiscoverCanvas | ⬜ | Level 3 |
| PortfolioCanvas | ⬜ | Level 3 |
| Gemini assets | ⬜ | Optional |

**Legend:** ✅ Done · 🔄 In progress · ⬜ Not started

---

## Placeholder map for prompts

When writing Cursor/Gemini prompts, replace:

| Placeholder | Value |
|-------------|-------|
| `[BRAND_NAME]` | Influora |
| `[PRIMARY_COLOR]` | #9b8cf2 |
| `[BACKGROUND]` | #f0ebfa |
| `[FRAMEWORK]` | React 19 + Vite 6 + TypeScript |
| `[MOTION_LIB]` | Framer Motion 12 |
| `[3D_LIB]` | React Three Fiber + drei |
