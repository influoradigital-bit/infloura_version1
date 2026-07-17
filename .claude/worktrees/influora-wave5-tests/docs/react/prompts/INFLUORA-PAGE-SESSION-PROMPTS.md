# Influora — Page Session Prompts (Cursor)
### Copy-paste prompts to integrate motion into existing pages

**Prerequisite:** Motion components built (MC-00 through MC-11)  
**Rules:** `INFLUORA-MASTER-PROMPT.md` · Level per page: `INFLUORA-3D-MOTION-BLUEPRINT.md`

---

## PS-01 — Brand auth pages (MagneticButton + form motion)

```
@src/pages/brand-login.tsx
@src/pages/brand-register.tsx
@src/components/shared/auth-login-shell.tsx
@src/components/motion/MagneticButton.tsx
@docs/react/INFLUORA-MASTER-PROMPT.md

Integrate motion on brand auth (Level 3 polish only — 3D already exists):

1. Replace primary submit Button with MagneticButton on login and register
2. Add Rule Set D focus styles to form fields (ring-primary/30) — use existing Input/Label components
3. Ensure submit buttons have active:scale-[0.97] transition-transform duration-150
4. Do NOT add new Canvas — LoginScene3D already in AuthLoginShell
5. Minimal diff — no refactor of auth logic or api calls

Motion level: 3 (polish). Chat rules do not apply.
```

---

## PS-02 — Brand campaigns list

```
@src/components/brand/campaigns/campaigns-list.tsx
@src/components/motion/FadeUp.tsx
@src/components/motion/StaggerContainer.tsx
@src/components/motion/TiltCard.tsx
@src/components/motion/WordReveal.tsx
@docs/react/INFLUORA-PLAN-OF-ACTION.md

Integrate Level 1 motion on campaigns list:

1. Page heading → WordReveal
2. Filter/toolbar section → FadeUp delay 0
3. Campaign card grid → StaggerContainer wrapping each card in StaggerItem > TiltCard
4. Empty state → FadeUp with existing empty copy
5. Do NOT animate on filter refetch — only initial mount stagger (use key sparingly)
6. No 3D. No changes to api.campaigns calls.

Self-check: no transition-all added to cards.
```

---

## PS-03 — Brand discover (TiltCard grid + DiscoverCanvas hero)

```
@src/components/brand/discover/creator-discovery.tsx
@src/components/3d/DiscoverCanvas.tsx
@src/components/motion/StaggerContainer.tsx
@src/components/motion/TiltCard.tsx
@src/components/motion/FadeUp.tsx
@docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md

Integrate Level 3 discover page:

1. Add hero band above search/filters — height ~320px, hidden on mobile (lg:block)
2. Lazy load DiscoverCanvasGate with React.lazy + Suspense — fallback CanvasFallback discover
3. Creator result cards: StaggerContainer + TiltCard — preserve all existing filter/search logic
4. Mobile: show static img /public/images/discover/hero-static.png if exists, else CanvasFallback
5. Max 1 WebGL on page
6. Do not break api.creators integration in live mode

Self-check: reduced motion shows fallback, not canvas.
```

---

## PS-04 — Brand dashboard

```
@src/components/brand/dashboard/dashboard-page.tsx
@src/components/motion/WordReveal.tsx
@src/components/motion/FadeUp.tsx
@src/components/motion/CountUp.tsx
@src/lib/helpers.ts

Integrate Level 1-2 on dashboard:

1. Main dashboard title → WordReveal
2. Stat numeric values (completed collabs, reach, spend, etc.) → CountUp where values are numbers
3. Pipeline / activity sections → FadeUp with staggered delays (0, 0.1, 0.2) — not StaggerContainer if sections are not uniform grid
4. Quick link cards → hover translateY(-2px) + shadow — or TiltCard if card structure allows
5. NO 3D canvas on dashboard
6. Do not animate SLA alert badges with pulse unless already critical — no new animate-pulse

Preserve all existing mock/live data logic.
```

---

## PS-05 — Brand wallet

```
@src/pages/brand-wallet.tsx
@src/components/motion/CountUp.tsx
@src/components/motion/FadeUp.tsx

Integrate Level 1 on brand wallet:

1. Available balance and in-escrow amounts → CountUp
2. Transaction list: FadeUp on initial mount only — NOT re-animate when list updates
3. No 3D
Minimal diff.
```

---

## PS-06 — Brand onboarding

```
@src/components/brand/onboarding/onboarding-layout.tsx
@src/components/brand/onboarding/onboarding-steps.tsx
@src/components/motion/FadeUp.tsx
@docs/react/INFLUORA-MASTER-PROMPT.md

Integrate Level 2 onboarding:

1. Extend existing framer-motion in onboarding-layout — wrap step content in AnimatePresence mode="wait" with slide+fade between steps (x: 20 → 0, opacity)
2. Sidebar step completion: spring scale on check icon when step completes
3. Form field groups in onboarding-steps: stagger 60ms using StaggerContainer
4. Step 3 success: optional checkmark spring animation
5. NO 3D
6. Do not break api.onboarding calls

Entry 250ms exit 150ms per master prompt.
```

---

## PS-07 — Brand chat (minimal motion)

```
@src/pages/brand-chat.tsx
@docs/react/INFLUORA-3D-MOTION-BLUEPRINT.md

Apply Level 0-1 rules to brand chat ONLY:

1. Audit existing animations — remove any stagger on full message list load if present
2. New messages may fade in opacity 150ms ease-out — implement only if message list identifies new ids
3. FORBIDDEN: add Canvas, MagneticButton, WordReveal, GSAP, stagger container on messages
4. Deal list mobile sheet: max 200ms slide transition ok
5. Do not slow down send button or input focus

This is a restraint prompt — prefer deleting heavy motion over adding.
```

---

## PS-08 — Creator deals list

```
@src/pages/creator-deals.tsx
@src/components/motion/StaggerContainer.tsx
@src/components/motion/TiltCard.tsx
@src/components/motion/FadeUp.tsx

Integrate Level 1 on creator deals/inbox:

1. Opportunity/deal cards → StaggerContainer + TiltCard
2. Tab headers (new / in progress): instant switch or 150ms opacity — no layout animation
3. Empty inbox → FadeUp
4. NO 3D
```

---

## PS-09 — Creator wallet + profile

```
@src/pages/creator-wallet.tsx
@src/pages/creator-profile.tsx
@src/components/motion/CountUp.tsx
@src/components/motion/FadeUp.tsx

Level 1:
- creator-wallet: CountUp on earnings/balance, FadeUp sections
- creator-profile: FadeUp on profile sections, no 3D
Minimal diff both files.
```

---

## PS-10 — Public portfolio (PortfolioCanvas + lightbox)

```
@src/pages/creator-portfolio-public.tsx
@docs/CREATOR-PORTFOLIO-PAGE.md
@src/components/3d/PortfolioCanvas.tsx
@src/components/motion/FadeUp.tsx
@src/components/motion/WordReveal.tsx

Integrate Level 3 public portfolio:

1. Hero section: lazy PortfolioCanvasGate with creator avatar + stats from page data
2. Mobile / reduced motion: hero-static.png fallback
3. Creator display name → WordReveal
4. Pinned posts grid: StaggerContainer + TiltCard on cards
5. Gallery lightbox: Framer layoutId shared transition between thumbnail and expanded image — Escape closes instantly without animation (a11y rule)
6. Route `/:handle` must still work for @username format handled in page

Do not break public no-auth access.
```

---

## PS-11 — Global button press audit

```
@src/components/ui/button.tsx
@src/components/brand/brand-layout.tsx
@docs/react/INFLUORA-MASTER-PROMPT.md

Global Emil pass on Button component:

1. Add active:scale-[0.97] and transition-transform duration-150 ease-out to button variants if not present
2. Audit brand-layout nav buttons — ensure no transition-all
3. Do NOT add MagneticButton globally
4. Single file preferred: extend button.tsx cva variants rather than editing 50 pages

List files changed in commit message context only — minimal scope.
```

---

## PS-12 — Creator auth (sky variant)

```
@src/pages/creator-login.tsx
@src/pages/creator-register.tsx
@src/components/shared/auth-login-shell.tsx

Reuse AuthLoginShell for creator auth:
1. Pass optional prop heroVariant="creator" if needed for subtitle/copy only
2. If LoginScene3DGate supports creator color variant, enable sky-tinted blobs
3. MagneticButton on primary CTA
4. Same reduced motion rules as brand auth

Do not duplicate auth shell.
```

---

## Session order (recommended)

| Week | Prompts | Pages |
|------|---------|-------|
| 1 | MC-00 → MC-11 | Foundation + components |
| 2 | PS-11, PS-01, PS-06 | Buttons, auth, onboarding |
| 3 | PS-02, PS-04, PS-05 | Campaigns, dashboard, wallet |
| 4 | PS-03, PS-10 | Discover 3D, portfolio 3D |
| 5 | PS-07, PS-08, PS-09, PS-12 | Chat restraint, creator pages |

After each session: update checkboxes in `INFLUORA-PLAN-OF-ACTION.md`.

---

*Page prompts v1.0 — attach @files in Cursor before paste*
