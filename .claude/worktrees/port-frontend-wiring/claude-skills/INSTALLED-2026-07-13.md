# Design / Frontend Skills — staged 2026-07-13

Pulled from GitHub into `claude-skills/` to fill the gaps referenced by `3d-cinematic-web`.

## Sources
- **Impeccable** — https://github.com/pbakaus/impeccable (evolved from Anthropic's `frontend-design`)
- **claudedesignskills** — https://github.com/freshtechbro/claudedesignskills

## Staged skills (24 new)

**Impeccable (1)** — `impeccable/` (full SKILL.md + reference/ + scripts/, 102 files)
Commands: audit, polish, critique, distill, animate, bolder, quieter, etc.

**3D & WebGL (7)** — `threejs-webgl`, `react-three-fiber`, `babylonjs-engine`, `playcanvas-engine`, `aframe-webxr`, `lightweight-3d-effects`, `pixijs-2d`

**Motion & scroll (6)** — `gsap-scrolltrigger`, `motion-framer`, `react-spring-physics`, `locomotive-scroll`, `barba-js`, `scroll-reveal-libraries`

**Animation assets (3)** — `animejs`, `lottie-animations`, `animated-component-libraries`

**3D authoring (4)** — `blender-web-pipeline`, `spline-interactive`, `rive-interactive`, `substance-3d-texturing`

**Meta (2)** — `web3d-integration-patterns`, `modern-web-design`

## npm packages installed (into package.json)
- `postprocessing` ^6.39.2
- `@react-three/postprocessing` ^3.0.4
- `react-parallax-tilt` ^1.7.333

(the other 7 from the 3d-cinematic-web install block — three, @react-three/fiber, @react-three/drei, gsap, @gsap/react, framer-motion, lenis — were already present.)

## To make these skills usable by Claude
These files are staged in the project, not yet registered with Claude's skill system.
Register via **Settings → Capabilities**, or install as plugins from the source repos.
`freshtechbro/claudedesignskills` ships plugin bundles under `plugins/bundles/` if you
prefer marketplace-style install over individual skill folders.

## Note on naming vs the 3d-cinematic-web decision table
The decision table uses names like `threejs-fundamentals`, `framer-motion-react`, `lenis`.
These repos use different names for the same coverage (e.g. `threejs-webgl`,
`react-three-fiber`, `motion-framer`, `gsap-scrolltrigger`). Functionality overlaps;
exact skill names differ.
