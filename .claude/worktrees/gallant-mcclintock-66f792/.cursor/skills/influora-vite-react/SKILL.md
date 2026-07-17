---
name: influora-vite-react
description: Vite + React Router conventions for Influora (not Next.js). Use when lazy loading, routing, env vars, or replacing Next.js patterns in this repo.
---

# Influora — Vite + React (not Next.js)

## Replacements

| Next.js | Influora Vite |
|---------|---------------|
| `next/dynamic(..., { ssr: false })` | `React.lazy()` + `<Suspense fallback={...}>` |
| `usePathname()` | `useLocation()` from `react-router-dom` |
| `app/layout.tsx` global 3D | Page-level only — no layout canvas |
| `next/image` | `<img>` or lazy + `public/` |
| `app/page.tsx` | `src/pages/*.tsx` + routes in `App.tsx` |
| Metadata API | `document.title` + meta tags |

## Env vars

- `VITE_API_MODE=live` | `mock`
- `VITE_API_BASE_URL=http://localhost:8080/api/v1`
- File: `.env.local` (never commit secrets)

## Paths

- Alias: `@/` → `src/`
- Entry: `src/main.tsx`
- Routes: `src/App.tsx`
- Theme: `src/app/globals.css`

## Commands

```powershell
npm run dev      # http://localhost:3000
npm run build
npm run preview
```

## Protected routes

- Brand: `localStorage.brand_token`
- Creator: `localStorage.creator_token`
- Demo: `?demo=true` or dev mode

Docs: `docs/react/INFLUORA-FLOW-MASTER.md`
