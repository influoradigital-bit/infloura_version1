# Priya — All Pages & Elements (Revamp Reference)
### Every route · UI elements · API calls to keep · motion level · skills

> Use with [`PRIYA-FULL-REVAMP-MASTER-PROMPT.md`](./PRIYA-FULL-REVAMP-MASTER-PROMPT.md).  
> **Revamp = UI + motion only.** All `api.*` calls in the **API freeze** column must stay identical.

**Deep audits:** [`../frontend/BRAND-PAGES-AUDIT.md`](../frontend/BRAND-PAGES-AUDIT.md) · [`../frontend/CREATOR-PAGES-AUDIT.md`](../frontend/CREATOR-PAGES-AUDIT.md) · [`../frontend/UI-ELEMENTS-AND-MODELS.md`](../frontend/UI-ELEMENTS-AND-MODELS.md)

---

## Master index — all active pages

| # | Route | Page file | Layout | Level | Prompt | Status |
|---|-------|-----------|--------|-------|--------|--------|
| **Brand auth & onboarding** |
| B1 | `/brand/login` | `brand-login.tsx` | AuthLoginShell | 3 | PS-01 | ✅ |
| B2 | `/brand/register` | `brand-register.tsx` | Standalone | 3 | PS-01 | ⬜ |
| B3 | `/brand/forgot-password` | `brand-forgot-password.tsx` | Standalone | 1 | PS-01 | ⬜ |
| B4 | `/brand/onboarding` | `brand-onboarding.tsx` | OnboardingLayout | 2 | PS-06 | ⬜ |
| **Brand app shell** |
| B5 | `/brand/dashboard` | `brand-dashboard.tsx` → `dashboard-page.tsx` | BrandLayout | 1–2 | PS-04 | ⬜ |
| B6 | `/brand/campaigns` | `brand-campaigns.tsx` → `campaigns-list.tsx` | BrandLayout | 1 | PS-02 | ⬜ |
| B7 | `/brand/campaigns/new` | `brand-new-campaign.tsx` → `campaign-form.tsx` | BrandLayout | 1–2 | — | ⬜ |
| B8 | `/brand/campaigns/:id` | `brand-campaign-detail.tsx` | BrandLayout | 1–2 | — | ⬜ |
| B9 | `/brand/campaigns/:id/edit` | `brand-edit-campaign.tsx` → `campaign-form.tsx` | BrandLayout | 1–2 | — | ⬜ |
| B10 | `/brand/discover` | `brand-discover.tsx` → `creator-discovery.tsx` | BrandLayout | 3 | PS-03 | 🔄 |
| B11 | `/brand/creators/:id` | `brand-creator-profile.tsx` | BrandLayout | 2 | — | ⬜ |
| B12 | `/brand/chat` | `brand-chat.tsx` | BrandLayout | 0–1 | PS-07 | ⬜ |
| B13 | `/brand/wallet` | `brand-wallet.tsx` | BrandLayout | 1 | PS-05 | ⬜ |
| B14 | `/brand/settings` | `brand-settings.tsx` | BrandLayout | 1 | — | ⬜ |
| B15 | `/brand/contracts` | `brand-contracts.tsx` | BrandLayout | 1 | — | ⬜ |
| B16 | `/brand/messages` | `brand-messages.tsx` | BrandLayout | 0–1 | — | ⬜ |
| **Creator auth & onboarding** |
| C1 | `/creator/login` | `creator-login.tsx` | AuthLoginShell | 3 | PS-12 | ⬜ |
| C2 | `/creator/register` | `creator-register.tsx` | Standalone | 3 | PS-12 | ⬜ |
| C3 | `/creator/onboarding` | `creator-onboarding.tsx` | Custom header | 2 | PS-06 | ⬜ |
| **Creator app shell** |
| C4 | `/creator/deals` | `creator-deals.tsx` | CreatorLayout | 1 | PS-08 | ⬜ |
| C5 | `/creator/chat` | `creator-chat.tsx` | CreatorLayout | 0–1 | PS-07 | ⬜ |
| C6 | `/creator/wallet` | `creator-wallet.tsx` | CreatorLayout | 1 | PS-09 | ⬜ |
| C7 | `/creator/profile` | `creator-profile.tsx` | CreatorLayout | 1–2 | PS-09 | ⬜ |
| C8 | `/creator/portfolio` | `creator-portfolio-editor.tsx` | CreatorLayout | 1–2 | — | ⬜ |
| C9 | `/creator/settings` | `creator-settings.tsx` | CreatorLayout | 1 | — | ⬜ |
| **Public & misc** |
| P1 | `/:handle` (`@username`) | `creator-portfolio-public.tsx` | None | 3 | PS-10 | 🔄 |
| P2 | `/terms`, `/privacy`, `/support` | `static-page.tsx` | None | 0 | Lenis | ⬜ |
| P3 | `*` | `not-found.tsx` | None | 0 | FadeUp | ⬜ |
| **Dev** |
| D1 | `/dev/motion-skills` | `dev-motion-skills.tsx` | None | test | — | ✅ |
| **Layouts (revamp last)** |
| L1 | — | `brand-layout.tsx` | Shell | 1 | PS-11 | ⬜ |
| L2 | — | `creator-layout.tsx` | Shell | 1 | PS-11 | ⬜ |
| L3 | — | `auth-login-shell.tsx` | Auth | 3 | PS-01 | ✅ |
| L4 | — | `onboarding-layout.tsx` | Onboarding | 2 | PS-06 | ⬜ |

🔄 = partial (3D or components wired) · ✅ = test page done · ⬜ = revamp pending

**Retired (do not revamp):** `/brand/deals`, `/brand/pipeline`, `/creator/inbox`, `/creator/active` — redirects only.

---

## Global UI elements (use on every revamp)

### shadcn components (`@/components/ui/`)

| Element | Revamp rule | Skill |
|---------|-------------|-------|
| **Button** | `active:scale-[0.97]`, no `transition-all` | emil, shadcn |
| **Input / Label** | Focus `ring-primary/30`, error shake | emil |
| **Card** | Hover lift or wrap in `TiltCard` for grids | framer-stagger |
| **Badge** | No pulse on decor | emil |
| **Dialog** | AnimatePresence optional — keep fast | framer-animate-presence |
| **Sheet** | 200ms slide max on mobile filters | emil |
| **Tabs** | Instant or 150ms opacity — no layout shift | framer-animate-presence |
| **Avatar** | Static — no bounce | — |
| **ScrollArea** | No stagger on scroll content | framer-stagger |
| **Select / Checkbox / Switch** | Focus rings only | emil |
| **Slider** | No animation on drag value | — |
| **Progress** | CSS width transition ok (300ms max) | emil |
| **Table / Chart** | Not used — keep card lists | — |

### Motion components (`@/components/motion/`)

| Component | Wrap what |
|-----------|-----------|
| `WordReveal` | Page `<h1>` titles |
| `FadeUp` | Sections, empty states, filter bars |
| `StaggerContainer` + `StaggerItem` | Card grids (campaigns, deals, discover) |
| `TiltCard` | Interactive cards in grids |
| `CountUp` | Wallet balances, dashboard stats |
| `MagneticButton` | Auth submit only — nowhere else |

### 3D gates (`@/components/3d/`)

| Gate | Page | Desktop only |
|------|------|--------------|
| `LoginScene3DGate` | AuthLoginShell | lg+ column |
| `DiscoverCanvasGate` | Discover | lg+ hero |
| `PortfolioCanvasGate` | Public portfolio | lg+ hero |

---

## Brand pages — detail

### B1 — Brand Login `/brand/login`

| | |
|---|---|
| **File** | `src/pages/brand-login.tsx` |
| **Layout** | `AuthLoginShell` |
| **UI elements** | Button, Input, Label, InfluoraLogo, AuroraBackground, LoginScene3DGate |
| **API freeze** | `api.auth.brandLogin`, `getBrandOnboardingComplete()` |
| **Stores** | `useAuthStore.login`, `localStorage.brand_token` |
| **Skills** | emil, framer, r3f, shadcn, motion-components |
| **Revamp** | MagneticButton on submit; Rule Set D focus; keep 3D in shell |

---

### B2 — Brand Register `/brand/register`

| | |
|---|---|
| **File** | `src/pages/brand-register.tsx` |
| **Layout** | Standalone `auth-gradient` |
| **UI elements** | Button, Input, Label, Select, Checkbox, step progress |
| **API freeze** | `api.auth.brandRegister` |
| **Skills** | emil, framer-animate-presence (2 steps), shadcn |
| **Revamp** | AnimatePresence between steps; stagger fields 60ms |

---

### B3 — Brand Forgot Password `/brand/forgot-password`

| | |
|---|---|
| **File** | `src/pages/brand-forgot-password.tsx` |
| **UI elements** | Button, Input, Label, Card |
| **API freeze** | `api.auth.forgotPassword` (if wired) / mock flow |
| **Skills** | emil, FadeUp, shadcn |
| **Revamp** | FadeUp form; success state spring — minimal |

---

### B4 — Brand Onboarding `/brand/onboarding`

| | |
|---|---|
| **Files** | `brand-onboarding.tsx`, `onboarding-layout.tsx`, `onboarding-steps.tsx` |
| **UI elements** | Button, Input, Label, Progress, Card, Input OTP, upload |
| **API freeze** | `api.onboarding.*`, `upload.ts` presign |
| **Skills** | framer-animate-presence, framer-stagger, emil |
| **Revamp** | Step AnimatePresence `mode="wait"`; checkmark spring step 3; **no 3D** |

---

### B5 — Brand Dashboard `/brand/dashboard`

| | |
|---|---|
| **File** | `src/components/brand/dashboard/dashboard-page.tsx` |
| **UI elements** | Card, Button, Badge, Progress, Avatar |
| **API freeze** | `api.dashboard.actions('brand')`, `api.wallet.get('brand')`, `api.dashboard.pipeline('brand')` |
| **Skills** | WordReveal, FadeUp, CountUp, emil; optional gsap-scroll for pipeline pin |
| **Revamp** | CountUp on stats; FadeUp sections; **no 3D**; optional GSAP pin on pipeline card |

---

### B6 — Brand Campaigns `/brand/campaigns`

| | |
|---|---|
| **File** | `src/components/brand/campaigns/campaigns-list.tsx` |
| **UI elements** | Card, Badge, Button, DropdownMenu, Input (search) |
| **API freeze** | `api.campaigns.list`, filters params |
| **Skills** | WordReveal, StaggerContainer, TiltCard, framer-stagger |
| **Revamp** | Grid stagger on mount only — not on filter refetch |

---

### B7–B9 — Campaign form & detail

| Route | File | API freeze | Revamp |
|-------|------|------------|--------|
| `/new` | `campaign-form.tsx` | `api.campaigns.create`, `useCampaignStore` | FadeUp steps; focus rings; AnimatePresence on 5 steps |
| `/:id/edit` | same | `api.campaigns.update` | Same as new |
| `/:id` | `brand-campaign-detail.tsx`, `campaign-state-machine.tsx` | `api.campaigns.get` | WordReveal title; tab FadeUp; timeline static |

---

### B10 — Discover `/brand/discover`

| | |
|---|---|
| **File** | `src/components/brand/discover/creator-discovery.tsx` |
| **UI elements** | Input, Sheet, Slider, Badge, Card, Avatar, Dialog (invite), Select |
| **API freeze** | `api.creators.search`, `invite`, `toggleSaved`, `api.campaigns.list` |
| **Skills** | DiscoverCanvasGate, StaggerContainer, TiltCard, threejs-* |
| **Revamp** | 3D hero lg+ ✅; TiltCard on creator cards; preserve all filter state |

---

### B11 — Creator Profile (brand view) `/brand/creators/:id`

| | |
|---|---|
| **File** | `brand-creator-profile.tsx` |
| **UI elements** | Avatar, Card, Badge, Button, Tabs |
| **API freeze** | `api.creators.get`, mock hybrid |
| **Skills** | FadeUp, WordReveal, emil |
| **Revamp** | Parallax header optional; FadeUp stats; **no 3D** |

---

### B12 — Brand Chat `/brand/chat`

| | |
|---|---|
| **File** | `brand-chat.tsx` + `deal-room/*`, `proposal-form.tsx` |
| **UI elements** | ScrollArea, Textarea, Button, Sheet, Dialog, Tabs, custom overlays |
| **API freeze** | All deal/message/proposal API calls — **do not touch** |
| **Skills** | motion-audit only |
| **Revamp** | **RESTRAINT:** new message fade 150ms only; no stagger, no 3D, no MagneticButton |

---

### B13 — Brand Wallet `/brand/wallet`

| | |
|---|---|
| **File** | `brand-wallet.tsx` |
| **UI elements** | Card, Tabs, Button, Input, Dialog (top-up/withdraw) |
| **API freeze** | `api.wallet.*`, transaction list fetch |
| **Skills** | CountUp, FadeUp |
| **Revamp** | CountUp balances; FadeUp on mount — not on tx refresh |

---

### B14 — Brand Settings `/brand/settings`

| | |
|---|---|
| **File** | `brand-settings.tsx` |
| **UI elements** | Tabs, Input, Switch, Button, Avatar upload |
| **API freeze** | `api.settings.*`, profile patch |
| **Skills** | emil (forms), FadeUp sections |
| **Revamp** | Rule Set D on fields; success spring on save |

---

### B15 — Brand Contracts `/brand/contracts`

| | |
|---|---|
| **File** | `brand-contracts.tsx`, `contracts-and-deliverables.tsx` |
| **UI elements** | Card, Badge, Button, signature canvas |
| **API freeze** | Contract list/sign API |
| **Skills** | FadeUp only — signature flow instant |
| **Revamp** | Minimal motion; no stagger on contract list |

---

### B16 — Brand Messages `/brand/messages`

| | |
|---|---|
| **File** | `brand-messages.tsx` |
| **UI elements** | ScrollArea, Avatar, Input, Card |
| **API freeze** | Conversation/message mocks or API |
| **Skills** | motion-audit (Level 0–1) |
| **Revamp** | Same restraint as chat |

---

## Creator pages — detail

### C1–C2 — Creator auth

| Route | File | API freeze | Revamp |
|-------|------|------------|--------|
| `/creator/login` | `creator-login.tsx` | `useAuthStore`, `creator_token` | Reuse AuthLoginShell; MagneticButton; sky blob tint optional |
| `/creator/register` | `creator-register.tsx` | Mock OTP / social | AnimatePresence steps; emil buttons |

---

### C3 — Creator Onboarding `/creator/onboarding`

| | |
|---|---|
| **API freeze** | `api.onboarding.connectCreatorSocial`, `saveCreatorProfile`, `completeCreator` |
| **Skills** | framer-animate-presence, framer-stagger |
| **Revamp** | Match brand onboarding motion pattern |

---

### C4 — Creator Deals `/creator/deals`

| | |
|---|---|
| **File** | `creator-deals.tsx` |
| **UI elements** | Card, Badge, Tabs, Button |
| **API freeze** | `api.deals.list`, `accept`, `reject`, `counter` |
| **Skills** | StaggerContainer, TiltCard, FadeUp |
| **Revamp** | Card grid stagger; tab switch instant |

---

### C5 — Creator Chat `/creator/chat`

| | |
|---|---|
| **File** | `creator-chat.tsx` + creator `deal-room/*` |
| **API freeze** | Deal messages, counter-proposal, deliverable APIs |
| **Skills** | motion-audit only |
| **Revamp** | **Same as B12 — minimal motion** |

---

### C6 — Creator Wallet `/creator/wallet`

| | |
|---|---|
| **API freeze** | Mock earnings / `api.wallet` when live |
| **Skills** | CountUp, FadeUp |
| **Revamp** | CountUp on earnings hero; FadeUp sections |

---

### C7 — Creator Profile `/creator/profile`

| | |
|---|---|
| **API freeze** | Profile mock / `api.creators` me |
| **Skills** | FadeUp, emil |
| **Revamp** | Section FadeUp; no 3D |

---

### C8 — Portfolio Editor `/creator/portfolio`

| | |
|---|---|
| **API freeze** | `api.portfolio.getMine`, `update`, `syncPlatforms`, `uploadCover`, `analytics` |
| **Skills** | FadeUp, StaggerContainer on sections |
| **Revamp** | Preview FadeUp; **no PortfolioCanvas** (public page only) |

---

### C9 — Creator Settings `/creator/settings`

| | |
|---|---|
| **API freeze** | Settings patch API |
| **Skills** | emil, FadeUp |
| **Revamp** | Same as brand settings |

---

### P1 — Public Portfolio `/@username`

| | |
|---|---|
| **File** | `creator-portfolio-public.tsx` |
| **UI elements** | Avatar, Card, Badge, Button, Dialog (contact), grid |
| **API freeze** | `api.portfolio.getPublic`, `contact`, `mediaKitUrl` |
| **Skills** | PortfolioCanvasGate, WordReveal, StaggerContainer, TiltCard, framer-layout (lightbox) |
| **Revamp** | 3D hero lg+ ✅; WordReveal name; gallery layoutId optional |

---

### P2 — Static pages `/terms`, `/privacy`, `/support`

| | |
|---|---|
| **File** | `static-page.tsx` |
| **Skills** | FadeUp, lenis-scroll |
| **Revamp** | Lenis ✅; FadeUp title — no API |

---

### P3 — 404 `not-found.tsx`

| | |
|---|---|
| **Skills** | FadeUp |
| **Revamp** | FadeUp message; Button emil press |

---

## Layout shells — revamp (week 8)

### L1 — BrandLayout

| | |
|---|---|
| **File** | `src/components/brand/brand-layout.tsx` |
| **API freeze** | `useAuthStore`, `useNotificationStore` |
| **Revamp** | Nav hover emil; mobile sheet 200ms; **no Canvas in layout** |
| **Forbidden** | 3D, MagneticButton, global stagger |

### L2 — CreatorLayout

Same rules as BrandLayout — sidebar polish only.

### L3 — AuthLoginShell

| | |
|---|---|
| **API freeze** | None — presentational |
| **Revamp** | LoginScene3DGate ✅; form column FadeUp; lazy 3D |

### L4 — OnboardingLayout

| | |
|---|---|
| **Revamp** | Step sidebar spring on active; AnimatePresence content |

---

## API freeze — full list (never change during revamp)

### Auth
- `api.auth.brandLogin`, `brandRegister`, `forgotPassword`
- `createMockCreatorUser()`, OTP mock flows
- `getBrandOnboardingComplete()`, `auth-session.ts`

### Onboarding
- `api.onboarding.*` (brand + creator)
- `upload.ts` presign for logo

### Dashboard & campaigns
- `api.dashboard.actions`, `api.dashboard.pipeline`
- `api.campaigns.list`, `get`, `create`, `update`
- `useCampaignStore`

### Discover & creators
- `api.creators.search`, `get`, `invite`, `toggleSaved`

### Deals & chat
- `api.deals.*`, message send/receive
- `proposal-form`, `counter-proposal-form` submit handlers
- `creator-deal-messages.ts`, `creator-contract-store.ts`

### Wallet
- `api.wallet.get`, transactions, withdraw, top-up

### Portfolio
- `api.portfolio.getPublic`, `getMine`, `update`, `contact`, `syncPlatforms`, `uploadCover`

### Settings
- Profile/settings patch endpoints

### Stores (shape frozen)
- `useAuthStore`, `useCampaignStore`, `useNotificationStore`, `useUIStore`

---

## TypeScript models (reference only — do not rename)

From `@/lib/types.ts`: `Campaign`, `CreatorProfile`, `Collaboration`, `Proposal`, `Wallet`, enums  
From `@/lib/api.ts`: `Deal`, `DealMessage`, `PortfolioPage`, `LoginPayload`, `ApiError`  
See [`UI-ELEMENTS-AND-MODELS.md`](../frontend/UI-ELEMENTS-AND-MODELS.md) §10–12

---

## Per-page Cursor attach list (template)

When revamping page **B6 Campaigns**, attach:

```
@docs/react/PRIYA-FULL-REVAMP-MASTER-PROMPT.md
@docs/react/PRIYA-ALL-PAGES-AND-ELEMENTS.md
@src/components/brand/campaigns/campaigns-list.tsx
@src/components/motion/index.ts
@docs/react/prompts/INFLUORA-PAGE-SESSION-PROMPTS.md
```

Then paste PS-02 block or Master session prompt.

---

## Element → skill quick map

| UI pattern | Skill(s) |
|------------|----------|
| Page title | framer-scroll-reveal, WordReveal |
| Card grid | framer-stagger, TiltCard |
| Multi-step form | framer-animate-presence, emil |
| Stat numbers | motion-components, CountUp |
| Auth CTA | emil, MagneticButton |
| 3D hero | r3f-canvas, threejs-scenes |
| Chat message | motion-audit (minimal) |
| Long static page scroll | lenis-scroll |
| Pinned dashboard section | gsap-scroll |
| shadcn form | shadcn-ui, emil |

---

## Revamp completion tracker

Copy to your notes — tick when UI + motion done and APIs verified:

```
Brand:  B1 B2 B3 B4 B5 B6 B7 B8 B9 B10 B11 B12 B13 B14 B15 B16
Creator: C1 C2 C3 C4 C5 C6 C7 C8 C9
Public:  P1 P2 P3
Layouts: L1 L2 L3 L4
Global:  PS-11 buttons ✅ (partial)
```

---

*Pages & elements v1.0 — companion to PRIYA-FULL-REVAMP-MASTER-PROMPT.md*
