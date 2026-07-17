---
name: influora-threejs-performance
description: React Three Fiber performance rules for Influora — dpr, antialias, PerformanceMonitor, lazy load, particle caps. Use before adding or editing any Canvas.
---

# Influora — R3F Performance (Skills 12–14)

## Canvas defaults (every Canvas)

- `dpr={[1, 1.5]}` — never 2 on mobile
- `gl={{ antialias: false, alpha: true, powerPreference: 'high-performance' }}`
- Lazy: `React.lazy()` + `Suspense` fallback
- `PerformanceMonitor` from `@react-three/drei` — degrade on FPS drop

## Degrade strategy

On `onDecline`: reduce blob/particle count, lower distort, hide smallest meshes.
On `onIncline`: restore quality.

## Caps

- Max ~200 particles sitewide per scene
- Discover hero: ≤ 80 particles
- Sphere segments: 48 max on auth blobs; lower on mobile degrade

## Forbidden

- Canvas in BrandLayout / CreatorLayout
- Canvas on chat routes
- Multiple canvases on one page
- `useFrame` heavy math without throttling

## Reduced motion

`useReducedMotion()` → `CanvasFallback` — never mount WebGL.

Files: `src/components/3d/`, `src/components/shared/login-scene-3d.tsx`

Docs: `docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md`
