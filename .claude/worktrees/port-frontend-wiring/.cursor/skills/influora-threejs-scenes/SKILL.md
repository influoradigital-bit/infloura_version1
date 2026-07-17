---
name: influora-threejs-scenes
description: Influora 3D scene inventory and build rules — LoginScene3D, DiscoverCanvas, PortfolioCanvas, CanvasFallback. Use when adding new 3D hero scenes or wiring page-level canvases.
---

# Influora — 3D Scene Inventory (Skills 16–21)

## Max 3 canvases sitewide

| # | Component | Route | Status |
|---|-----------|-------|--------|
| 1 | `LoginScene3DGate` | `/brand/login`, `/creator/login`, register | ✅ Done |
| 2 | `DiscoverCanvas` | `/brand/discover` desktop hero | ✅ Done |
| 3 | `PortfolioCanvas` | `/:handle` public portfolio | ✅ Done |

## Shared fallback

`CanvasFallback` — variants: `auth` | `discover` | `portfolio`

Used when: reduced motion, WebGL unavailable, lazy load placeholder.

## Scene structure rules

- Named sub-components for each mesh (no hooks in `.map()`)
- `Suspense` inside Canvas for async assets
- Transparent background — page CSS shows through
- Camera: fov ~42, position tuned per scene
- No global canvas in layout shells

## Page wiring

- Auth: `auth-login-shell.tsx` lazy loads `LoginScene3DGate`
- Discover: hero column desktop-only (`hidden lg:block`)
- Portfolio: hero band behind public profile header

## Rejected patterns

- OceanCanvas in layout (FrontendDesign marketing pattern)
- ExportGlobe, ProductShapeViewer
- Spline embeds alongside R3F

Build prompts: `docs/react/prompts/INFLUORA-MOTION-COMPONENT-PROMPTS.md` MC-08 → MC-10 ✅
