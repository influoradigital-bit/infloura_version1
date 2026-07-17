---
name: influora-threejs-materials
description: Three.js materials and lighting for Influora Lilac Mist 3D — MeshDistortMaterial, blob colors, soft lighting. Use when styling auth blobs, DiscoverCanvas, PortfolioCanvas.
---

# Influora — R3F Materials & Lighting (Skill 15)

## Lilac Mist 3D palette

| Role | Hex | Usage |
|------|-----|-------|
| Primary blob | `#9b8cf2` | Main auth blob |
| Soft lilac | `#c4b5fd`, `#ddd6fe` | Secondary blobs |
| Sky accent | `#7ec8e8` | Creator / portfolio accent |
| Light tint | `#f5f0ff`, `#e9d5ff` | Directional / point lights |

Use CSS vars where possible in UI; 3D materials may use hex directly.

## Auth blobs (existing pattern)

- `@react-three/drei` `MeshDistortMaterial` + `Float`
- `distort` 0.28–0.5, `speed` 1.6–2.4
- `roughness` ~0.15, low `metalness`, light `clearcoat`

## Lighting

- Ambient ~0.85
- Two directional lights (warm + cool)
- One point light for depth
- No harsh shadows on auth hero

## Discover / Portfolio (planned)

- Discover: network nodes, thin lines, slow orbit — not heavy PBR
- Portfolio: subtle orbit ring around stats — minimal geometry

## Avoid

- `@splinetool/react-spline` new usage
- HDRI environment maps (overkill for SaaS)
- Real-time shadows on UI-adjacent canvases

Reference: `src/components/shared/login-scene-3d.tsx`
