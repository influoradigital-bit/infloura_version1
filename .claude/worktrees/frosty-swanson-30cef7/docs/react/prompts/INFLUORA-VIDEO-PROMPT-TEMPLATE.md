# Influora — Video Prompt Template (Gemini Veo)
### Optional looping videos for auth/discover backgrounds

> Requires hero still first for frame-locked videos.  
> Style: `INFLUORA-BRAND-STYLE-RULES.md`

---

## File output map

```
public/videos/
├── auth/
│   └── blobs-loop.mp4           # 5s seamless loop, auth fallback video
├── discover/
│   └── network-loop.mp4         # 6s slow orbit, discover hero optional
└── portfolio/
    └── spotlight-loop.mp4       # 5s subtle light sweep
```

---

## Global video rules (Influora SaaS)

```
Duration:    5–6 seconds, designed as SEAMLESS LOOP (not scroll-scrubbed — SaaS has no long marketing scroll)
FPS:         24
Audio:       muted always
Motion:      slow drift, soft morph — NO jump cuts, NO handheld, NO fast zoom
Text:        NONE on screen
Usage:       Optional <video> behind reduced-motion users who prefer motion but not WebGL
             OR ambient bg on auth (autoplay muted loop, playsInline)
Fallback:    static PNG from IMAGE-PROMPT-TEMPLATE when video fails or prefers-reduced-motion
Performance: pause video when tab hidden (Page Visibility API in React wrapper)
```

**Note:** The generic FrontendDesign kit uses scroll-scrubbed video for product pages. Influora is SPA dashboard-first — we use **loops**, not scroll-scrub, unless a future marketing landing page is added.

---

## Video type 1 — auth blobs-loop.mp4

```markdown
**blobs-loop.mp4**

First frame: Must match public/images/auth/hero-static.png composition

[Zone A block]

Motion: Soft spheres slowly float and morph — same aesthetic as LoginScene3D MeshDistortMaterial but photographic/3D render
Duration: 5s seamless loop (end frame = start frame)
Camera: static, subtle internal motion only
Colors: #9b8cf2, #c4b5fd, #7ec8e8 on #f0ebfa
Output: public/videos/auth/blobs-loop.mp4
React: optional AuthVideoBackground component — only if WebGL unavailable
```

---

## Video type 2 — discover network-loop.mp4

```markdown
**network-loop.mp4**

First frame: Match public/images/discover/hero-static.png

[Zone C block]

Motion: Network nodes pulse gently, connection lines shimmer slowly, 6s loop
Camera: very slow pan right 2% total — imperceptible
Output: public/videos/discover/network-loop.mp4
Usage: discover hero band alternative to DiscoverCanvas on low-end devices
```

---

## Video type 3 — portfolio spotlight-loop.mp4

```markdown
**spotlight-loop.mp4**

First frame: Match public/images/portfolio/hero-default.png

[Zone D block]

Motion: Soft spotlight sweeps left to right across abstract stage, 5s loop
Output: public/videos/portfolio/spotlight-loop.mp4
Usage: public portfolio hero when Canvas disabled
```

---

## Future: marketing landing scroll-scrub (if built)

If Influora adds a public `/landing` marketing page:

```markdown
**landing-hero-motion.mp4** — 8s, NOT looped

First frame: landing hero PNG
Motion: Slow dolly through abstract creator-brand collaboration scene
Frontend: GSAP ScrollTrigger scrub → video.currentTime (Phase 7+)
Reduced motion: static hero PNG only
Output: public/videos/landing-hero-motion.mp4
```

Defer until landing page exists.

---

## React integration pattern (conceptual)

```
AuthVideoBackground:
  - if prefers-reduced-motion → show hero-static.png
  - else if WebGL supported && !lowPowerMode → LoginScene3D
  - else → <video src="/videos/auth/blobs-loop.mp4" autoPlay muted loop playsInline />
```

Priority: **WebGL first** → **video loop** → **static PNG**

---

## Generation checklist

- [ ] Generate PNG first (IMAGE-PROMPT-TEMPLATE)
- [ ] Use PNG as Veo first frame
- [ ] Verify loop seam (frame 0 ≈ frame end)
- [ ] Compress for web (&lt; 2MB per loop)
- [ ] Test autoplay policy on mobile Safari

---

*Video template v1.0 — Influora SaaS loops*
