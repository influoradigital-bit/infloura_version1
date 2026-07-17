# Brand Portal — Page-by-Page UI Audit

Every brand page: route, layout, UI elements, forms, models, colors, data source.

**Layout default:** `BrandLayout` + `ProtectedRoute` unless noted.

---

## Index

| # | Route | Page file | Status |
|---|-------|-----------|--------|
| 1 | `/brand/login` | `brand-login.tsx` | Active |
| 2 | `/brand/register` | `brand-register.tsx` | Active |
| 3 | `/brand/forgot-password` | `brand-forgot-password.tsx` | Active |
| 4 | `/brand/onboarding` | `brand-onboarding.tsx` | Active |
| 5 | `/brand/dashboard` | `brand-dashboard.tsx` | Active |
| 6 | `/brand/campaigns` | `brand-campaigns.tsx` | Active |
| 7 | `/brand/campaigns/new` | `brand-new-campaign.tsx` | Active |
| 8 | `/brand/campaigns/:id` | `brand-campaign-detail.tsx` | Active |
| 9 | `/brand/campaigns/:id/edit` | `brand-edit-campaign.tsx` | Active |
| 10 | `/brand/discover` | `brand-discover.tsx` | Active |
| 11 | `/brand/creators/:id` | `brand-creator-profile.tsx` | Active |
| 12 | `/brand/chat` | `brand-chat.tsx` | Active |
| 13 | `/brand/wallet` | `brand-wallet.tsx` | Active |
| 14 | `/brand/settings` | `brand-settings.tsx` | Active |
| 15 | `/brand/contracts` | `brand-contracts.tsx` | Active |
| 16 | `/brand/messages` | `brand-messages.tsx` | Active |
| 17 | `/brand/deals` | `brand-deals.tsx` | **Retired** → redirects to chat |
| 18 | `/brand/pipeline` | `brand-pipeline.tsx` | **Retired** → redirects to chat |

---

## 1. Brand Login — `/brand/login`

| Attribute | Detail |
|-----------|--------|
| **File** | `src/pages/brand-login.tsx` |
| **Layout** | `AuthLoginShell` (3D hero + frosted card) |
| **Main component** | Inline page |

### UI elements
| Element | Components |
|---------|------------|
| Buttons | Button (submit, show password toggle) |
| Form | Input (email, password), Label |
| Motion | AuthLoginShell fade, AuroraBackground, LoginScene3D |
| Logo | InfluoraLogo in shell header |

### Form fields
- email, password

### Models / API
- `api.auth.brandLogin`
- `getBrandOnboardingComplete()` from auth-session

### Colors
- Theme tokens: `text-foreground`, `text-muted-foreground`, `bg-primary`, `text-primary`
- Error: `bg-destructive`, `border-stage-disputed-border`

---

## 2. Brand Register — `/brand/register`

| Attribute | Detail |
|-----------|--------|
| **Layout** | Standalone — `auth-gradient` + InfluoraLogo header |
| **Steps** | 2-step wizard |

### UI elements
Button, Input, Label, Select (industry, team size), Checkbox (terms), step progress bars

### Form fields — Step 1
companyName, industry, teamSize

### Form fields — Step 2
email, password, confirmPassword, agreeToTerms

### Models / API
- `api.auth.brandRegister`
- `ApiError`

### Colors
- Theme: `auth-gradient`, `bg-card`, `border-border`, `bg-primary`
- **Hardcoded:** `bg-blue-500` step bars, `text-red-400` validation errors

---

## 3. Brand Forgot Password — `/brand/forgot-password`

| Attribute | Detail |
|-----------|--------|
| **Layout** | Standalone auth-gradient |

### UI elements
Button, Input, Label, InfluoraLogo, success state card

### Form fields
email

### Data
Mock only — `setTimeout` 800ms, no API

### Colors
`auth-gradient`, `bg-card`, `text-primary`, hardcoded `border-slate-600`

---

## 4. Brand Onboarding — `/brand/onboarding`

| Attribute | Detail |
|-----------|--------|
| **Layout** | `OnboardingLayout` (step sidebar + framer-motion) |
| **Components** | `AccountSetupStep`, `CompanyDetailsStep`, inline `YoureInStep` |

### UI elements
Button, motion step sidebar (OnboardingLayout), progress bar

### Form fields — Step 1 (AccountSetupStep)
firstName, lastName, email, phone (+91), password, confirmPassword, emailOtpCode (6-digit)

### Form fields — Step 2 (CompanyDetailsStep)
logo upload, companyName, companySlug, workspaceType, industry, companySize, websiteUrl, description

### Form fields — Step 3
Completion CTA only

### Models
- `OnboardingData`, `WorkspaceType`, `MemberRole`
- `UploadResult` from upload.ts

### API
`brandRegister`, `sendBrandEmailOtp`, `verifyBrandEmail`, `saveBrandCompany`, `completeBrand`

### Colors
`bg-success/10`, `text-destructive`, `border-primary/30`, `text-amber-500` (Sparkles icon)

---

## 5. Brand Dashboard — `/brand/dashboard`

| Attribute | Detail |
|-----------|--------|
| **Component** | `DashboardPage` |
| **Layout** | BrandLayout |

### UI elements
Card, CardHeader, CardTitle, CardContent, Button, Badge, Avatar, AvatarFallback, Progress, Link cards

### Sections
- Greeting + quick stats
- Pipeline overview (stage counts + progress bars)
- SLA / action items list
- Wallet health summary
- Quick links (Campaigns, Discover, Chat, Settings)

### Models
- `useAuthStore` (user name)
- Local `ActionItem` interface

### Data
Hybrid: mock fallback → `api.dashboard.actions`, `api.wallet.get`, `api.dashboard.pipeline`

### Colors
Stage tokens for pipeline, `bg-destructive/15` urgent, `bg-warning/15` medium priority

---

## 6. Brand Campaigns List — `/brand/campaigns`

| Attribute | Detail |
|-----------|--------|
| **Component** | `CampaignsList` |

### UI elements
Button, Input (search), Badge, Progress, Card grid, DropdownMenu (actions), Select (sort), Tabs (status filter), IconBadge

### Tabs
ALL | ACTIVE | DRAFT | COMPLETED | PAUSED

### Card content per campaign
Title, status badge, platforms, budget, progress, creator count, actions menu (edit, duplicate, delete)

### Models
`Campaign`, `CampaignStatus`, `Platform` from types.ts

### Data
**Mock only** — `mockCampaigns` in component

### Colors
Stage badges per `CampaignStatus`, `text-primary` links

---

## 7. New Campaign — `/brand/campaigns/new`

| Attribute | Detail |
|-----------|--------|
| **Component** | `CampaignForm` (5-step wizard) |

### UI elements
Button, Input, Label, Textarea, Badge (chip selects), Switch, Slider, Calendar, Popover, Card, Select, Tooltip, Separator, Progress step indicator

### Form fields (all steps)
title, description, objectives[], isPrivate, platforms[], contentTypes[], startDate, endDate, currency, budgetMin, budgetMax, maxCollaborators, requirements[], hashtags[], brandGuidelines

### Models
`Platform`, `ContentType`, `CampaignStatus`, `CampaignFormData`

### Data
`api.campaigns.create`, `useCampaignStore.addCampaign`

---

## 8. Edit Campaign — `/brand/campaigns/:id/edit`

Same as **New Campaign** with `campaignId` param — `api.campaigns.update`

---

## 9. Campaign Detail — `/brand/campaigns/:id`

| Attribute | Detail |
|-----------|--------|
| **Layout** | BrandLayout — large inline page |

### UI elements
Button, Badge, Progress, Avatar, Input, Textarea, Separator, Card, Tabs, Dialog (×3), Sheet, Select, Tooltip, DropdownMenu, CampaignStateMachine, CollaborationTimeline

### Tabs (active campaign)
bids | collaborators | deliverables

### Tabs (completed)
report | collaborators | analytics

### Dialogs
Accept Bid, Counter Proposal (amount, message), Decline Bid (reason Select)

### Sheet
Collaboration Timeline

### Models
`CampaignState`, local `BidStatus`, `CampaignBid`

### Data
**Mock** — MOCK_CAMPAIGNS, mockBids, mockCollaborators

### Colors
Stage tokens + hardcoded platform icon colors (pink, red, sky), analytics bar colors

---

## 10. Discover Creators — `/brand/discover`

| Attribute | Detail |
|-----------|--------|
| **Component** | `CreatorDiscovery` |

### UI elements
Button, Input, Label, Badge, Avatar, Card, Checkbox, Slider, Sheet (filters), Select, DropdownMenu, Tooltip, Separator, ScrollArea, Textarea, Dialog (invite 3-step), grid/list toggle

### Filter sheet fields
platforms, categories, cities, follower range, engagement range, verified, available, price range

### Invite dialog fields
campaign, deliverables, budget, deadline, usageRights, exclusivity, message

### Models
`CreatorProfile`, `Platform`, `ApiError`

### Data
Hybrid: mockCreators default; live: `api.creators.search`, `invite`, `toggleSaved`, `api.campaigns.list`

---

## 11. Creator Profile (brand view) — `/brand/creators/:id`

### UI elements
Button, Badge, Avatar, Tabs, Progress, Dialog, Select, Textarea, Label, Separator

### Tabs
overview | audience | portfolio | rates | reviews

### Dialog
Invite to Campaign (campaign Select, message Textarea)

### Charts (custom, not recharts)
Age group Progress bars, gender split bars, authenticity SVG ring

### Data
**Mock** — ignores URL `:id` param

### Colors
Hardcoded platform hex (#E4405F, #FF0000), `fill-primary`, gradient overlays

---

## 12. Deal Room Chat — `/brand/chat`

| Attribute | Detail |
|-----------|--------|
| **Layout** | BrandLayout — full-height split pane |

### UI elements
Button, Input, Avatar, Badge, Card, ScrollArea, Textarea, Progress, Sheet (tools), custom modals

### Feature components
ProposalForm, ShipmentForm, DealRoomStepProgress, DealContractTab, DealDeliverablesTab, DealPaymentsTab, ShipmentCard, CollaborationTimeline cards

### Forms
Chat message Textarea; ProposalForm (5-step); ShipmentForm (courier, tracking, items)

### Sheet tabs
Contract | Deliverables | Payments

### Models
`ProposalFormData`, `ShipmentData`, `ShipmentStatus`, `DealPhase`, `DealContractStatus`, `DealDeliverableItem`

### Data
Mock deal rooms + timeline; URL params `?deal=` `?creator=` `?tab=`

### Colors
Creator bubbles `bg-primary`; brand bubbles `bg-muted`; timeline event stage colors

---

## 13. Brand Wallet — `/brand/wallet`

### UI elements
Button, Input, Badge, Card, Tabs, ScrollArea, Avatar, Progress, Separator, Label, Select, Dialog (Add Funds), DropdownMenu, Tooltip

### Tabs
transactions | escrow | payouts

### Add Funds form
amount (presets + custom), paymentMethod (upi/card/netbanking)

### Models
Local `Transaction`, `EscrowItem`

### Data
**Mock only**

### Colors
Runway Progress (green/amber/red thresholds), transaction type icons (green/red/amber/blue/purple)

---

## 14. Brand Settings — `/brand/settings`

### UI elements
Button, Card, Input, Badge, Tabs, Label, Switch, Avatar

### Tabs & fields
| Tab | Fields |
|-----|--------|
| general | workspaceName, email, phone, website |
| notifications | 5× Switch (email, push, campaign, bid, digest) |
| payments | autoRecharge Switch, autoRechargeAmount |
| security | twoFactor Switch, change password button |

### Data
Hardcoded useState + mock members/payment methods; `alert()` on save

---

## 15. Brand Contracts — `/brand/contracts`

| Attribute | Detail |
|-----------|--------|
| **Component** | `ContractsAndDeliverables` |

### UI elements
Card, Button, Badge, Tabs, Avatar, Progress, Textarea, ScrollArea, Separator, Dialog (×2)

### Tabs (detail)
overview | contract | deliverables | payments

### Dialogs
Sign Contract (text + canvas signature), Review Deliverable (feedback Textarea)

### Models
Local `Contract`, `ContractDeliverable`, `ContractClause`

### Data
Mock contracts; links to `/brand/chat?deal=`

---

## 16. Brand Messages — `/brand/messages`

### UI elements
Button, Input, Avatar, Badge, ScrollArea, Textarea, DropdownMenu, Tooltip, Separator

### Form
Message compose Textarea

### Models
Local `Conversation`, `Message`

### Data
Mock conversations; optional `?creator=` URL param

### Colors
`bg-green-500` online dot, `text-blue-500` contract icon

---

## 17–18. Retired pages

| File | Was | Now |
|------|-----|-----|
| `brand-deals.tsx` | DealRoomDashboard | Redirect → `/brand/chat` |
| `brand-pipeline.tsx` | Kanban + HTML table list | Redirect → `/brand/chat` |

Still in codebase for reference; not routed in `App.tsx`.

---

## Brand pages — element matrix

| Page | Btn | Input | Form | Table | Tabs | Dialog | Sheet | Badge | Avatar | Progress | Chart |
|------|-----|-------|------|-------|------|--------|-------|-------|--------|----------|-------|
| login | ✓ | ✓ | ✓ | — | — | — | — | — | — | — | — |
| register | ✓ | ✓ | ✓ | — | — | — | — | — | — | — | — |
| forgot-pw | ✓ | ✓ | ✓ | — | — | — | — | — | — | — | — |
| onboarding | ✓ | ✓ | ✓ | — | — | — | — | — | — | ✓ | — |
| dashboard | ✓ | — | — | — | — | — | — | ✓ | ✓ | ✓ | bars |
| campaigns | ✓ | ✓ | — | — | ✓ | — | — | ✓ | — | ✓ | — |
| campaign new | ✓ | ✓ | ✓ | — | — | — | — | ✓ | — | ✓ | — |
| campaign detail | ✓ | ✓ | ✓ | — | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | bars |
| discover | ✓ | ✓ | ✓ | — | — | ✓ | ✓ | ✓ | ✓ | — | — |
| creator profile | ✓ | — | ✓ | — | ✓ | ✓ | — | ✓ | ✓ | ✓ | ring |
| chat | ✓ | ✓ | ✓ | — | — | ✓* | ✓ | ✓ | ✓ | ✓ | — |
| wallet | ✓ | ✓ | ✓ | — | ✓ | ✓ | — | ✓ | ✓ | ✓ | — |
| settings | ✓ | ✓ | ✓ | — | ✓ | — | — | ✓ | ✓ | — | — |
| contracts | ✓ | — | ✓ | — | ✓ | ✓ | — | ✓ | ✓ | ✓ | — |
| messages | ✓ | ✓ | ✓ | — | — | — | — | ✓ | ✓ | — | — |

*ProposalForm uses custom overlay, not Dialog component

---

*Brand audit v1.0*
