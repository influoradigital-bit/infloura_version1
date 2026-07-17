---
name: influora-motion-test
description: How to verify Influora Framer Motion and 3D skills work — dev test page, npm checks, browser checklist. Use after installing skills or building motion components.
---

# Influora — Motion Skills Test

## Quick npm verify

Run in project root:

1. `npm install`
2. `npm list framer-motion three @react-three/fiber @react-three/drei --depth=0`
3. `npm run build` — must exit 0

## Dev test page

**URL:** http://localhost:3000/dev/motion-skills (development only)

Shows all motion primitives + 3D gate + Emil button check.

Start app: `npm run dev`

## Browser checklist

### Framer (Skills 5–8)

- [ ] FadeUp — sections animate once on scroll
- [ ] StaggerContainer — grid items cascade ~50ms apart
- [ ] WordReveal — title words appear in sequence
- [ ] TiltCard — desktop mouse tilt + glare
- [ ] CountUp — number counts when scrolled into view
- [ ] MagneticButton — subtle pull toward cursor (auth-style)

### 3D (Skills 12–21)

- [ ] LoginScene3DGate — lilac blobs render (desktop panel)
- [ ] CanvasFallback — static gradient when reduced motion ON
- [ ] No WebGL errors in browser console

### Emil (Skill 2)

- [ ] Buttons scale down slightly on click
- [ ] No janky `transition-all` feel

### Reduced motion test

Windows: Settings → Accessibility → Visual effects → Animation effects **Off**

Reload test page — animations should be static/instant; 3D shows fallback.

## Production pages to spot-check

| URL | Skills tested |
|-----|---------------|
| `/brand/login` | 3D + aurora + Emil buttons |
| `/brand/onboarding` | AnimatePresence (after PS-06) |
| `/dev/motion-skills` | All components |

## If something fails

| Issue | Fix |
|-------|-----|
| Blank 3D | Check console WebGL; verify drei/fiber installed |
| No animation | Check `prefers-reduced-motion` |
| Build error | Run `npm run build`, read TypeScript error |
| Skills not in Cursor | Reload project; skills in `.cursor/skills/` |

Docs: `docs/react/PRIYA-INSTALL-AND-RUN-SKILLS.md` §10
