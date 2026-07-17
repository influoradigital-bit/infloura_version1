# Shared Layouts, Auth, 3D & Miscellaneous Pages

Documentation for layout shells, shared components, static pages, 404, and retired routes.

---

## 1. BrandLayout — `src/components/brand/brand-layout.tsx`

**Wraps:** All protected brand routes via `BrandLayoutWrapper` in `App.tsx`

### Structure
```
┌─────────────────────────────────────────────────────────┐
│ [Mobile] Sheet menu          [Desktop] Sidebar │ Header │
│                                                │ Content│
│  Logo + nav (5 items)                          │ {children}│
│  CommandBar trigger                            │        │
│  Avatar dropdown                               │        │
└─────────────────────────────────────────────────────────┘
```

### UI elements used
Button, Avatar, Badge, DropdownMenu, Sheet, Tooltip, AlertDialog, Popover, ScrollArea, CommandBar, InfluoraLogo, IconBadge

### Navigation items
| Label | Route | IconBadge variant |
|-------|-------|-------------------|
| Home | `/brand/dashboard` | primary |
| Campaigns | `/brand/campaigns` | outreach |
| Creators | `/brand/discover` | contracted |
| Deals | `/brand/chat` | negotiating |
| Wallet | `/brand/wallet` | approved |

### Avatar menu
Settings → `/brand/settings`  
Help → external URL  
Log out → AlertDialog confirm

### Header features
- CommandBar (⌘K search)
- Notification popover (useNotificationStore)
- Mobile hamburger → Sheet

### Stores
`useAuthStore`, `useNotificationStore`, `useUIStore`

### Colors
`bg-sidebar`, `border-sidebar-border`, active nav `bg-sidebar-accent`, `IconBadge` stage variants

---

## 2. CreatorLayout — `src/components/creator/creator-layout.tsx`

**Wraps:** Protected creator routes (not onboarding/register/public portfolio)

### Navigation (sidebar)
| Label | Route |
|-------|-------|
| Deals | `/creator/deals` |
| Wallet | `/creator/wallet` |

Deals nav active for: `/creator/deals`, `/creator/inbox`, `/creator/active`, `/creator/chat`

### Avatar menu
| Item | Target |
|------|--------|
| Profile | `/creator/profile` |
| Public Page | `/creator/portfolio` |
| Settings | `/creator/settings` |
| Help | https://help.influora.com |
| Log out | AlertDialog |

### UI elements
Avatar, Button, Badge, DropdownMenu, AlertDialog, Tooltip, Sheet, InfluoraLogo, IconBadge

### Colors
Same sidebar tokens as brand; unread badge on deals `variant="secondary"`

---

## 3. AuthLoginShell — `src/components/shared/auth-login-shell.tsx`

**Used by:** `brand-login.tsx`, `creator-login.tsx`

### Layout (desktop lg+)
```
┌──────────────────┬──────────────────┐
│  Form column     │  3D hero column   │
│  frosted card    │  LoginScene3D     │
│  {children}      │  title + bullets  │
└──────────────────┴──────────────────┘
```

### UI / motion
- AuroraBackground (full viewport)
- InfluoraLogo header
- Framer motion fade-in (form + hero)
- Lazy LoginScene3D with gradient fallback

### Props
`heroTitle`, `heroSubtitle`, `heroBullets[]`, `children`

### Colors
`bg-card/75 backdrop-blur-xl border-white/50`, shadow `rgba(155,140,242,0.35)`

---

## 4. LoginScene3D — `src/components/shared/login-scene-3d.tsx`

| Attribute | Value |
|-----------|-------|
| Tech | React Three Fiber + drei |
| Scene | 4 MeshDistortMaterial spheres |
| Blob colors | `#9b8cf2`, `#c4b5fd`, `#7ec8e8`, `#ddd6fe` |
| Camera | fov 42, position [0,0,5.5] |
| dpr | [1, 1.75] |

---

## 5. AuroraBackground — `src/components/shared/aurora-background.tsx`

| Attribute | Value |
|-----------|-------|
| Tech | Framer Motion infinite animate |
| Blobs | 4 radial gradients with lilac/sky |
| Reduced motion | Static gradient fallback |
| Utility | `.bg-auth-mesh` overlay |

---

## 6. OnboardingLayout — brand onboarding only

**File:** `src/components/brand/onboarding/onboarding-layout.tsx`

### Structure
- Left sidebar (desktop): logo, 3 step list with completion states
- Right: animated step content (framer-motion)
- Progress bar top

### Steps
1. Account  
2. Company  
3. You're in  

### UI
Button, motion transitions, CheckCircle2 icons

### Colors
Completed step `text-primary`, future step `text-muted-foreground`

---

## 7. Static pages — `/terms`, `/privacy`, `/support`

| Attribute | Detail |
|-----------|--------|
| **File** | `src/pages/static-page.tsx` |
| **Layout** | None |
| **Props** | `title`, `description` from App.tsx routes |

### UI elements
Button (back/home), InfluoraLogo, centered text

### Colors
`bg-background`, `text-foreground`, `text-muted-foreground`

### Content
Placeholder copy — legal pages not finalized

---

## 8. Not Found — `*`

| Attribute | Detail |
|-----------|--------|
| **File** | `src/pages/not-found.tsx` |
| **Layout** | None |

### UI elements
Button (Go to dashboard, Go back), InfluoraLogo, IconBadge, SearchX icon

### Actions
Navigate `/brand/dashboard` or `history.back()`

---

## 9. Public portfolio route — `/:handle`

See **CREATOR-PAGES-AUDIT.md §10** for full detail.

**Router note:** Must be last before `*` catch-all. Strips `@` prefix from handle in page logic.

---

## 10. App.tsx route map (all frontend routes)

| Route | Page | Layout |
|-------|------|--------|
| `/brand/login` | brand-login | AuthLoginShell |
| `/brand/register` | brand-register | standalone |
| `/brand/forgot-password` | brand-forgot-password | standalone |
| `/brand/onboarding` | brand-onboarding | OnboardingLayout |
| `/brand/dashboard` | brand-dashboard | BrandLayout |
| `/brand/campaigns` | brand-campaigns | BrandLayout |
| `/brand/campaigns/new` | brand-new-campaign | BrandLayout |
| `/brand/campaigns/:id` | brand-campaign-detail | BrandLayout |
| `/brand/campaigns/:id/edit` | brand-edit-campaign | BrandLayout |
| `/brand/discover` | brand-discover | BrandLayout |
| `/brand/creators/:id` | brand-creator-profile | BrandLayout |
| `/brand/wallet` | brand-wallet | BrandLayout |
| `/brand/chat` | brand-chat | BrandLayout |
| `/brand/contracts` | brand-contracts | BrandLayout |
| `/brand/messages` | brand-messages | BrandLayout |
| `/brand/settings` | brand-settings | BrandLayout |
| `/brand/deals` | redirect → chat | — |
| `/brand/deals/:id` | redirect → chat?deal= | — |
| `/brand/pipeline` | redirect → chat | — |
| `/creator/login` | creator-login | AuthLoginShell |
| `/creator/register` | creator-register | standalone |
| `/creator/onboarding` | creator-onboarding | standalone |
| `/creator/deals` | creator-deals | CreatorLayout |
| `/creator/inbox` | redirect → deals?status=new | — |
| `/creator/active` | redirect → deals?status=in_progress | — |
| `/creator/wallet` | creator-wallet | CreatorLayout |
| `/creator/profile` | creator-profile | CreatorLayout |
| `/creator/settings` | creator-settings | CreatorLayout |
| `/creator/chat` | creator-chat | CreatorLayout |
| `/creator/portfolio` | creator-portfolio-editor | CreatorLayout |
| `/` | redirect → /brand/login | — |
| `/terms` | static-page | none |
| `/privacy` | static-page | none |
| `/support` | static-page | none |
| `/:handle` | creator-portfolio-public | none |
| `*` | not-found | none |

---

## 11. Protected route logic

| Portal | Token key | Demo bypass |
|--------|-----------|-------------|
| Brand | `localStorage.brand_token` | `?demo=true` or dev mode |
| Creator | `localStorage.creator_token` | same |

---

## 12. Global providers (entry)

**File:** `src/main.tsx`

```
React.StrictMode
  └── App (BrowserRouter)
        └── Routes...
```

**Global CSS:** `src/app/globals.css` — Lilac Mist tokens

**Toasts:** Sonner via `@/components/ui/sonner` (if mounted in app — check App)

---

## 13. Retired page files (still in repo)

| File | Original purpose | Replacement |
|------|------------------|-------------|
| `brand-deals.tsx` | Deal room dashboard | `/brand/chat` |
| `brand-pipeline.tsx` | Kanban pipeline | `/brand/chat` |
| `creator-inbox.tsx` | Proposal inbox | `/creator/deals?status=new` |
| `creator-active.tsx` | Active collabs | `/creator/deals?status=in_progress` |

These contain substantial UI that may be merged or deleted in future refactors.

---

## 14. CommandBar — global search

**File:** `src/components/brand/command-bar.tsx`  
**Trigger:** BrandLayout header  
**UI:** Command dialog (cmdk), Kbd shortcuts  
**Scope:** Brand portal navigation + actions

---

*Shared & misc v1.0*
