# Influora — Motion Component Prompts (Cursor)
### Copy-paste prompts to build `src/components/motion/` and `src/components/3d/`

**Before each prompt:** Read `INFLUORA-MASTER-PROMPT.md`  
**Attach files** with `@filename` in Cursor Composer (`Ctrl+I`)

---

## MC-00 — Motion config + hooks (run first)

```
@docs/react/INFLUORA-MASTER-PROMPT.md
@docs/react/INFLUORA-PROJECT-CONFIG.md
@src/app/globals.css

Create foundation files for Influora motion system (React 19, Vite, TypeScript, Framer Motion 12):

1. src/lib/motion-config.ts
   - Export constants: DURATION_FAST=0.15, DURATION_NORMAL=0.25, DURATION_SLOW=0.45
   - EASE_OUT = [0.23, 1, 0.32, 1]
   - STAGGER_DEFAULT = 0.05 (50ms)
   - SPRING_MAGNETIC = { type: 'spring', duration: 0.5, bounce: 0.2 }
   - VIEWPORT_ONCE = { once: true, margin: '-80px' }

2. src/hooks/useInViewOnce.ts
   - Wrapper around framer-motion useInView returning { ref, isInView }
   - Respects reduced motion — if reduced, isInView true immediately

Do not create motion components yet. Match existing code style (named exports, @/ alias).
```

---

## MC-01 — FadeUp

```
@docs/react/INFLUORA-MASTER-PROMPT.md
@src/lib/motion-config.ts

Create src/components/motion/FadeUp.tsx

Requirements:
- Props: children, className?, delay?: number, y?: number (default 24), as?: keyof JSX.IntrinsicElements
- Uses framer-motion motion.div (or motion[as])
- initial: { opacity: 0, y }
- animate when in view: { opacity: 1, y: 0 }
- viewport from motion-config VIEWPORT_ONCE
- transition: duration DURATION_NORMAL, ease EASE_OUT, delay
- useReducedMotion: skip animation — render static div with opacity 1
- Export FadeUp from src/components/motion/index.ts

No changes to other files.
```

---

## MC-02 — StaggerContainer + StaggerItem

```
@docs/react/INFLUORA-MASTER-PROMPT.md
@src/components/motion/FadeUp.tsx
@src/lib/motion-config.ts

Create src/components/motion/StaggerContainer.tsx

Requirements:
- StaggerContainer: variants container with staggerChildren STAGGER_DEFAULT, delayChildren optional prop
- StaggerItem: child variant — opacity 0→1, y 16→0, same easing as FadeUp
- Both respect useReducedMotion (no stagger, instant show)
- Export both from motion/index.ts
- TypeScript: React.PropsWithChildren + className optional

Do not integrate into pages yet.
```

---

## MC-03 — WordReveal

```
@docs/react/INFLUORA-MASTER-PROMPT.md
@src/lib/motion-config.ts

Create src/components/motion/WordReveal.tsx

Requirements:
- Props: text: string, className?, as?: 'h1'|'h2'|'h3'|'p' (default h1)
- Split text by spaces — each word wrapped in motion.span inline-block
- Stagger words 40ms when parent in view (viewport once)
- reduced motion: plain text, no split animation
- Preserve single space between words
- Export from motion/index.ts
```

---

## MC-04 — TiltCard

```
@docs/react/INFLUORA-MASTER-PROMPT.md
@src/components/ui/card.tsx

Create src/components/motion/TiltCard.tsx

Requirements:
- Wraps children in motion.div with 3D perspective (perspective: 1000px)
- onMouseMove: rotateX/rotateY based on pointer position, maxAngle default 6 degrees
- onMouseLeave: spring back to 0
- Optional subtle glare overlay div (opacity ~8%) following pointer
- Only enable tilt @media (hover: hover) — touch devices: no tilt, optional hover shadow only
- useReducedMotion: static card, no transform
- className prop merged with cn()
- Do NOT use react-parallax-tilt package — implement with Framer Motion
- Export from motion/index.ts
```

---

## MC-05 — CountUp

```
@docs/react/INFLUORA-MASTER-PROMPT.md
@src/lib/helpers.ts
@src/hooks/useInViewOnce.ts

Create src/components/motion/CountUp.tsx

Requirements:
- Props: value: number, duration?: number (default 1.2s), prefix?: string, suffix?: string, className?, formatFn?: (n:number)=>string
- Default formatFn: use formatINR from helpers if prefix is ₹ or undefined currency context — else locale string
- Animate from 0 to value when inView (useInViewOnce)
- reduced motion: show final value immediately
- Export from motion/index.ts
```

---

## MC-06 — MagneticButton

```
@docs/react/INFLUORA-MASTER-PROMPT.md
@src/components/ui/button.tsx
@src/lib/motion-config.ts

Create src/components/motion/MagneticButton.tsx

Requirements:
- Wraps shadcn Button asChild or children slot
- Props: strength?: number (default 0.35), className?, ...Button props
- Mouse move within bounding box translates button slightly toward cursor (spring)
- Disabled on touch and reduced motion — renders plain Button
- active: scale(0.97) per Emil rules
- Export from motion/index.ts
- Document in file comment: AUTH HERO ONLY — do not use in app shell
```

---

## MC-07 — CanvasFallback

```
@docs/react/INFLUORA-PROJECT-CONFIG.md
@src/components/shared/aurora-background.tsx

Create src/components/3d/CanvasFallback.tsx

Requirements:
- Props: variant?: 'auth' | 'discover' | 'portfolio', className?
- Static gradient mesh matching Lilac Mist — reuse aesthetic from aurora-background but NO infinite animation (or very subtle CSS only if not reduced motion)
- Optional img: if public/images/{variant}/hero-static.png exists, show as object-cover with gradient overlay
- Full width/height of parent min-h inherited
- Export from src/components/3d/index.ts
- Used when useReducedMotion or WebGL fails
```

---

## MC-08 — Polish LoginScene3D

```
@docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md
@src/components/shared/login-scene-3d.tsx
@src/components/3d/CanvasFallback.tsx

Polish existing LoginScene3D to match blueprint:

1. dpr={[1, 1.5]} (was 1.75)
2. gl={{ antialias: false, alpha: true, powerPreference: 'high-performance' }}
3. Add PerformanceMonitor from drei — onDecline reduce blob count or hide smallest blobs
4. Export wrapper LoginScene3DGate that checks useReducedMotion — if true render CanvasFallback variant="auth"
5. Keep existing Scene blob logic — minimal diff
6. Do not change auth-login-shell lazy import path unless necessary
```

---

## MC-09 — DiscoverCanvas

```
@docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md
@src/components/3d/CanvasFallback.tsx
@src/lib/icon-theme.ts

Create src/components/3d/DiscoverCanvas.tsx per blueprint spec:

- Central sphere PRIMARY #9b8cf2
- 4 satellite spheres with Float, colors from brand palette
- Slow useFrame rotation, subtle mouse parallax on camera (max 0.3 offset)
- Particle count ≤ 80 if using Points
- dpr, gl, PerformanceMonitor per master prompt
- Export DiscoverCanvasGate with reduced motion → CanvasFallback variant="discover"
- Lazy-load friendly — no side effects on import

Do not wire into creator-discovery.tsx yet — canvas file only.
```

---

## MC-10 — PortfolioCanvas

```
@docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md
@docs/CREATOR-PORTFOLIO-PAGE.md
@src/components/3d/CanvasFallback.tsx

Create src/components/3d/PortfolioCanvas.tsx

Props: avatarUrl?: string, stats?: { followers?: number, engagement?: number, collabs?: number }

- Center ring for avatar (texture if avatarUrl loaded, else placeholder circle)
- 3 orbiting Html labels from drei for stats (format numbers compact e.g. 125K)
- Slow auto-rotate
- PortfolioCanvasGate with reduced motion fallback variant="portfolio"
- Export from 3d/index.ts
```

---

## MC-11 — Motion barrel + verify

```
@src/components/motion/
@src/components/3d/

Ensure src/components/motion/index.ts exports:
FadeUp, StaggerContainer, StaggerItem, WordReveal, TiltCard, CountUp, MagneticButton

Ensure src/components/3d/index.ts exports:
CanvasFallback, DiscoverCanvasGate, PortfolioCanvasGate
(Re-export polished LoginScene3DGate if created)

Run TypeScript check mentally — no any types, strict props.
List any missing peer dependencies (none expected).
Do not integrate pages in this prompt.
```

---

## Prompt order summary

| Order | ID | Output |
|-------|-----|--------|
| 1 | MC-00 | motion-config.ts, useInViewOnce |
| 2 | MC-01–06 | motion components |
| 3 | MC-07 | CanvasFallback |
| 4 | MC-08 | polish auth 3D |
| 5 | MC-09–10 | new canvases |
| 6 | MC-11 | barrel exports |

Then proceed to **INFLUORA-PAGE-SESSION-PROMPTS.md** for page integration.

---

*Component prompts v1.0*
