---
name: influora-r3f-canvas
description: React Three Fiber rules for Influora — Canvas performance, lazy load, reduced motion, no layout canvases. Use when editing login-scene-3d, DiscoverCanvas, PortfolioCanvas, or any @react-three/fiber code.
---

# Influora — R3F / Three.js

Parent skill — see also: `influora-threejs-performance`, `influora-threejs-materials`, `influora-threejs-scenes`.

Test: `/dev/motion-skills` + `/brand/login`

## Every Canvas

```tsx
dpr={[1, 1.5]}
gl={{ antialias: false, alpha: true, powerPreference: 'high-performance' }}
```

- Lazy: `React.lazy()` + `Suspense` (Vite — not next/dynamic)
- `PerformanceMonitor` from `@react-three/drei` — simplify on decline
- Max ~200 particles; Discover ≤ 80
- No hooks inside `.map()` — named sub-components

## Reduced motion

- `useReducedMotion()` → render `CanvasFallback` from `@/components/3d`
- Never mount Canvas when user prefers reduced motion

## Where 3D is allowed (max 3 sitewide)

1. `login-scene-3d.tsx` — auth
2. `DiscoverCanvas.tsx` — `/brand/discover` desktop hero
3. `PortfolioCanvas.tsx` — public `/:handle`

## Forbidden

- Canvas in `BrandLayout`, `CreatorLayout`
- Canvas on `/brand/chat`, `/creator/chat`
- Global OceanCanvas in layout
- New `@splinetool/react-spline` usage

## Files

- 3D: `src/components/3d/`
- Auth: `src/components/shared/login-scene-3d.tsx`

Docs: `docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md`
