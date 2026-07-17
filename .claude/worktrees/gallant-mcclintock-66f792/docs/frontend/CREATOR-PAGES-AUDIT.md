# Creator Portal — Page-by-Page UI Audit

Every creator page: route, layout, UI elements, forms, models, colors, data source.

---

## Index

| # | Route | Page file | Status |
|---|-------|-----------|--------|
| 1 | `/creator/login` | `creator-login.tsx` | Active |
| 2 | `/creator/register` | `creator-register.tsx` | Active |
| 3 | `/creator/onboarding` | `creator-onboarding.tsx` | Active |
| 4 | `/creator/deals` | `creator-deals.tsx` | Active (primary hub) |
| 5 | `/creator/chat` | `creator-chat.tsx` | Active |
| 6 | `/creator/wallet` | `creator-wallet.tsx` | Active |
| 7 | `/creator/profile` | `creator-profile.tsx` | Active |
| 8 | `/creator/portfolio` | `creator-portfolio-editor.tsx` | Active |
| 9 | `/creator/settings` | `creator-settings.tsx` | Active |
| 10 | `/:handle` | `creator-portfolio-public.tsx` | Active (public) |
| 11 | `/creator/inbox` | `creator-inbox.tsx` | **Retired** → `/creator/deals?status=new` |
| 12 | `/creator/active` | `creator-active.tsx` | **Retired** → `/creator/deals?status=in_progress` |

---

## 1. Creator Login — `/creator/login`

| Attribute | Detail |
|-----------|--------|
| **Layout** | `AuthLoginShell` |
| **File** | `src/pages/creator-login.tsx` |

### UI elements
Button, Input, Label, password visibility toggle, remember-me checkbox

### Form fields
email, password

### Models / data
`createMockCreatorUser()`, `useAuthStore.login()`, `localStorage.creator_token`

### Colors
Theme tokens + `bg-destructive/10` errors

---

## 2. Creator Register — `/creator/register`

| Attribute | Detail |
|-----------|--------|
| **Layout** | Standalone centered Card on `auth-gradient` |

### UI elements
Button, Input, Label, Card, Separator, Checkbox, InfluoraLogo, social register buttons (Instagram/YouTube)

### Form flow
Phone OTP: info step → OTP step; social OAuth buttons (mock)

### Form fields
phone, otp, terms checkbox

### Data
Mock OTP delays → `createMockCreatorUser()` → `/creator/onboarding`

### Colors
`bg-stage-contracted`, `text-pink-500`/`text-red-500` social icons, `bg-primary`

---

## 3. Creator Onboarding — `/creator/onboarding`

| Attribute | Detail |
|-----------|--------|
| **Layout** | Custom sticky header + `max-w-lg` container |
| **Steps** | 3 visual step pills |

### UI elements
Button, Input, Label, Card, Badge, Progress, Textarea

### Step 1 — Connect platforms
Instagram / YouTube OAuth connect cards (mock)

### Step 2 — Profile
displayName, bio, city, verticals (max 3), languages, rateMin, rateMax

### Step 3 — Complete
CTA to deals

### API
`api.onboarding.connectCreatorSocial`, `saveCreatorProfile`, `completeCreator`

### Colors
Instagram gradient purple/pink/orange, YouTube `bg-red-500`, connected `border-green-500 bg-green-50`, `text-stage-approved-fg`

---

## 4. Creator Deals — `/creator/deals`

| Attribute | Detail |
|-----------|--------|
| **Layout** | `CreatorLayout` → `max-w-3xl` |
| **Primary hub** | Replaces inbox + active |

### UI elements
Avatar, Badge, Button, Card, Input (search), filter chips (not Tabs component)

### Filter chips
All | New | Negotiating | Active | Completed

### Card content
Brand name, campaign, budget (formatINR), status pill, deliverable summary, Open chat CTA

### Models
`Deal`, `DealStatusFilter` from api.ts

### Data
Hybrid: `mockDeals` default; `api.deals.list`, `accept`, `reject`, `counter`

### Colors
Active chip `bg-primary text-primary-foreground`; status pills blue/amber/violet/emerald/orange/gray

---

## 5. Creator Chat (Deal Room) — `/creator/chat`

| Attribute | Detail |
|-----------|--------|
| **Layout** | CreatorLayout — full-height split |
| **URL** | `?deal={id}&tab=contract|deliverables|payments` |

### UI elements
Button, Input, Avatar, Badge, Card, ScrollArea, Textarea, Progress, Sheet, Dialog, Label

### Creator components
CounterProposalForm, CounterProposalCard, CreatorContractPanel, CreatorContractCard, CreatorDealContractTab, DeliverableSubmission, DeliverableCard, RevisionHandler, ShippingAddressForm, ReceiptConfirmation

### Shared with brand
DealRoomStepProgress, DealDeliverablesTab, DealPaymentsTab, ShipmentCard

### Forms
Messages; counter proposal (5-step); shipping address; receipt confirm; deliverable upload

### Models
`TimelineEvent`, `TimelineEventMetadata`, `DealContractStatus`, `CounterProposalFormData`, `DeliverableSubmissionData`, `ShippingAddressData`, `ReceiptData`

### Client stores
`creator-deal-messages`, `creator-contract-store`

### Data
Mock deals + timeline; persisted messages localStorage

### Colors
Creator sent: `bg-primary text-primary-foreground`; brand: `bg-muted`; timeline stage borders (blue/amber/contracted/approved/disputed)

---

## 6. Creator Wallet — `/creator/wallet`

| Attribute | Detail |
|-----------|--------|
| **Layout** | CreatorLayout → `max-w-2xl` |

### UI elements
Card, Button, Badge, Avatar, Tabs, Separator, Dialog (×3), Select, Input, Label

### Tabs
Payouts | History | Tax Docs

### Dialogs
Payout detail, Payout settings, Withdraw funds (amount Input)

### Data
**Mock** — mockEarningsData, mockPayouts, mockTransactions, mockTaxDocs

### Colors
Hero `bg-gradient-to-br from-primary to-accent text-white`; payout status via stage-* tokens; `bg-amber-500` processing bar

---

## 7. Creator Profile — `/creator/profile`

| Attribute | Detail |
|-----------|--------|
| **Layout** | CreatorLayout → `max-w-2xl` |

### UI elements
Card, Button, Badge, Avatar, Input, Label, Textarea, Separator, Progress, Dialog

### Dialog — Edit Profile
displayName, bio, city, rateMin, rateMax

### Sections
Header stats, platform links, verticals, languages, ratings breakdown, completion Progress

### Data
**Mock** mockProfile

### Colors
`bg-gradient-to-br from-primary to-accent` header, platform brand colors, `fill-amber-500` stars

---

## 8. Portfolio Editor — `/creator/portfolio`

| Attribute | Detail |
|-----------|--------|
| **Layout** | CreatorLayout → `max-w-3xl` |
| **Menu label** | "Public Page" |

### UI elements
Button, Card, Input, Label, Switch, Badge, Separator, Select

### Form sections
Bio, niches, cover upload, visibility toggles (Switch per section), custom links editor, collab display mode Select, rate card visibility

### Models (api.ts)
`PortfolioPage`, `PortfolioAnalytics`, `PortfolioCustomLink`, `PortfolioVisibility`

### API
`api.portfolio.getMine`, `analytics`, `update`, `syncPlatforms`, `uploadCover`

### Colors
Default cover `from-primary/30 via-purple-300/40 to-pink-300/30`, `text-emerald-600` analytics delta

---

## 9. Creator Settings — `/creator/settings`

| Attribute | Detail |
|-----------|--------|
| **Layout** | CreatorLayout → `max-w-2xl` |

### UI elements
Card, Button, Input, Label, Switch, Separator, Dialog, AlertDialog

### Sections
Notifications (6× Switch), Security (change password Dialog — 3 fields), Danger zone (logout + delete AlertDialog)

### Data
Local state; `useAuthStore.logout()`; mock delete delay

### Colors
`text-green-500` verified, `text-stage-disputed-fg`, `bg-red-600` destructive, `hover:bg-red-50`

---

## 10. Public Portfolio — `/:handle`

| Attribute | Detail |
|-----------|--------|
| **Layout** | **None** — standalone public page |
| **Example** | `/@priyacreates` |
| **Auth** | Not required |

### UI elements
Avatar, Badge, Button, Card, Input, Label, Textarea, Dialog, Separator

### Inline sections
TrustStat, BadgeCard, PlatformPill, PlatformStatCard, CollabsSection, PinnedPostCard, CustomLinkRow, ContactDialog

### Contact dialog fields
name, email, message

### Models
`PortfolioPage`, `PortfolioBadge`, `PortfolioCustomLink`, `PortfolioPlatformStats`

### API
`api.portfolio.getPublic`, `contact`, `mediaKitUrl`

### SEO
Dynamic `document.title` + meta description

### Colors
Verified `bg-blue-100 text-blue-700`, badge meta colors (amber/emerald/blue/rose/violet), CTA gradient `from-primary/5 via-purple-50 to-pink-50`

---

## 11. Creator Inbox — RETIRED

| Attribute | Detail |
|-----------|--------|
| **File** | `creator-inbox.tsx` |
| **Redirect** | `/creator/deals?status=new` |

### Was included
Tabs (Proposals | Opportunities), many Dialogs, CollaborationTimeline, mock proposals

### Models
`Collaboration` from types.ts

Kept in repo for reference / potential port of UI patterns.

---

## 12. Creator Active — RETIRED

| Attribute | Detail |
|-----------|--------|
| **File** | `creator-active.tsx` |
| **Redirect** | `/creator/deals?status=in_progress` |

### Was included
Tabs (Active | Completed), deliverable upload Dialog, full stage-* badge palette

---

## Creator pages — element matrix

| Page | Btn | Input | Form | Tabs | Dialog | Sheet | Badge | Avatar | Progress | API |
|------|-----|-------|------|------|--------|-------|-------|--------|----------|-----|
| login | ✓ | ✓ | ✓ | — | — | — | — | — | — | mock |
| register | ✓ | ✓ | ✓ | — | — | — | — | — | — | mock |
| onboarding | ✓ | ✓ | ✓ | pills | — | — | ✓ | — | ✓ | api |
| deals | ✓ | ✓ | — | chips | — | — | ✓ | ✓ | — | hybrid |
| chat | ✓ | ✓ | ✓ | tools† | ✓ | ✓ | ✓ | ✓ | ✓ | mock |
| wallet | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ | ✓ | — | mock |
| profile | ✓ | ✓ | ✓ | — | ✓ | — | ✓ | ✓ | ✓ | mock |
| portfolio edit | ✓ | ✓ | ✓ | — | — | — | ✓ | — | — | api |
| settings | ✓ | ✓ | ✓ | — | ✓‡ | — | — | — | — | local |
| public portfolio | ✓ | ✓ | ✓ | — | ✓ | — | ✓ | ✓ | — | api |

† Tools panel via Sheet (Contract | Deliverables | Payments)  
‡ AlertDialog for logout/delete

---

## Creator vs Brand — shared deal room elements

| Element | Brand chat | Creator chat |
|---------|------------|--------------|
| Deal list sidebar | ✓ | ✓ |
| Message timeline | ✓ | ✓ |
| Proposal flow | Send (ProposalForm) | Receive + Counter |
| Contract tab | DealContractTab | CreatorDealContractTab |
| Deliverables tab | DealDeliverablesTab | Same + submission |
| Payments tab | DealPaymentsTab | Same (read-focused) |
| Shipment | ShipmentForm + ShipmentCard | ShipmentCard + address form |
| Step progress | DealRoomStepProgress | Same |

---

*Creator audit v1.0*
