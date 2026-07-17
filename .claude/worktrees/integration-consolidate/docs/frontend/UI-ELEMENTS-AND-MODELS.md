# Influora — UI Elements & Data Models

Inventory of all **design system components**, **custom components**, and **TypeScript models** used across frontend pages.

---

## 1. Design system (`src/components/ui/`)

57 shadcn/Radix components available. Usage count reflects active routed pages.

| Component | File | Used on pages | Purpose |
|-----------|------|---------------|---------|
| **Button** | `button.tsx` | All 32 | Primary actions, nav, submit |
| **Input** | `input.tsx` | 18 | Text, email, password, search, amounts |
| **Label** | `label.tsx` | 18 | Form field labels |
| **Textarea** | `textarea.tsx` | 10 | Messages, bio, proposals, feedback |
| **Select** | `select.tsx` | 10 | Dropdowns, filters, campaign pickers |
| **Card** | `card.tsx` | 24 | Content containers (+ Header, Title, Description, Content, Footer) |
| **Badge** | `badge.tsx` | 22 | Status, counts, tags, platform chips |
| **Avatar** | `avatar.tsx` | 16 | User, brand, creator photos (+ Fallback, Image) |
| **Tabs** | `tabs.tsx` | 12 | Settings, wallet, campaigns, campaign detail |
| **Dialog** | `dialog.tsx` | 14 | Modals — accept bid, invite, sign, withdraw |
| **Sheet** | `sheet.tsx` | 8 | Mobile nav, filters, deal tools panel |
| **ScrollArea** | `scroll-area.tsx` | 10 | Chat lists, messages, contracts |
| **Progress** | `progress.tsx` | 9 | Pipeline, deliverables, runway, onboarding |
| **Separator** | `separator.tsx` | 8 | Section dividers |
| **Switch** | `switch.tsx` | 4 | Settings toggles, campaign private flag |
| **Slider** | `slider.tsx` | 3 | Budget range, follower filters |
| **Checkbox** | `checkbox.tsx` | 3 | Register terms, discover filters |
| **Calendar** | `calendar.tsx` | 1 | Campaign date pickers |
| **Popover** | `popover.tsx` | 2 | Date picker triggers |
| **DropdownMenu** | `dropdown-menu.tsx` | 8 | Campaign actions, user menu, message actions |
| **Tooltip** | `tooltip.tsx` | 6 | Icon hints, truncated text |
| **AlertDialog** | `alert-dialog.tsx` | 2 | Logout confirm (layouts) |
| **Alert** | `alert.tsx` | Rare | Inline alerts |
| **Accordion** | `accordion.tsx` | Rare | Collapsible sections |
| **Table** | `table.tsx` | **0** | Not used — lists are card-based |
| **Chart** | `chart.tsx` | **0** | recharts wrapper unused on pages |
| **Form** | `form.tsx` | Partial | react-hook-form integration available |
| **Input OTP** | `input-otp.tsx` | Via onboarding | 6-digit email OTP |
| **Skeleton** | `skeleton.tsx` | Loading states | Async page loads |
| **Sonner** | `sonner.tsx` | Global toasts | Success/error feedback |
| **Sidebar** | `sidebar.tsx` | Available | Brand uses custom sidebar in brand-layout |
| **Drawer** | `drawer.tsx` | Available | Mobile patterns |
| **Command** | `command.tsx` | Via CommandBar | Global search palette |
| **Breadcrumb** | `breadcrumb.tsx` | Rare | Navigation |
| **Pagination** | `pagination.tsx` | Rare | List pagination |
| **Radio Group** | `radio-group.tsx` | Rare | Single choice |
| **Toggle** | `toggle.tsx` | Rare | View toggles |
| **Carousel** | `carousel.tsx` | Rare | Image carousels |
| **Resizable** | `resizable.tsx` | Rare | Split panels |
| **Hover Card** | `hover-card.tsx` | Rare | Preview on hover |
| **Context Menu** | `context-menu.tsx` | Rare | Right-click |
| **Menubar** | `menubar.tsx` | Rare | — |
| **Navigation Menu** | `navigation-menu.tsx` | Rare | — |
| **Collapsible** | `collapsible.tsx` | Rare | — |
| **Aspect Ratio** | `aspect-ratio.tsx` | Portfolio | Media frames |
| **Spinner** | `spinner.tsx` | Loading | Button loading states |
| **Empty** | `empty.tsx` | Available | Empty state primitive |
| **Field** | `field.tsx` | Available | Form field wrapper |
| **Item** | `item.tsx` | Available | List item primitive |
| **Kbd** | `kbd.tsx` | CommandBar | Keyboard shortcuts |
| **Button Group** | `button-group.tsx` | Available | — |

---

## 2. Button variants & patterns

From `button.tsx` (shadcn cva):

| Variant | Visual | Typical use |
|---------|--------|-------------|
| `default` | `bg-primary text-primary-foreground` | Submit, Create campaign, Send proposal |
| `destructive` | Red tint | Delete, Decline, Reject |
| `outline` | Border only | Secondary actions, Cancel |
| `secondary` | `bg-secondary` | Filter chips inactive |
| `ghost` | Transparent hover | Icon buttons, nav items |
| `link` | Underline text | Forgot password links |

**Sizes:** `default`, `sm`, `lg`, `icon`

**Common combinations:**
- Hero CTA: `size="lg"` + ArrowRight icon
- Icon-only: `size="icon"` + Lucide icon
- Destructive outline: decline bid, delete account

---

## 3. Form elements inventory

### Field types used across app

| Field type | Component | Pages |
|------------|-----------|-------|
| Email | Input | login, register, onboarding, settings |
| Password | Input + Eye toggle | login, register, onboarding, settings |
| Phone (+91) | Input with prefix | brand onboarding, creator register |
| OTP 6-digit | Input OTP / manual Input | brand onboarding email verify |
| Text | Input | names, company, slug, city, campaign title |
| URL | Input | website, portfolio links |
| Long text | Textarea | bio, description, messages, proposals |
| Number / currency | Input | budget, rates, withdraw amount |
| Date | Calendar + Popover | campaign start/end |
| Single select | Select | industry, team size, campaign, reject reason |
| Multi-select chips | Button toggles + Badge | platforms, content types, objectives |
| Range | Slider | budget min/max, follower range |
| Boolean | Switch | notifications, private campaign, 2FA |
| Checkbox | Checkbox | terms acceptance, filter options |
| File upload | native input + upload lib | logo, deliverables, cover image |
| Signature | HTML Canvas | contract signing |
| Radio-style | Button group | payment method (wallet) |

### Multi-step forms

| Form | Steps | Page / component |
|------|-------|------------------|
| Brand register | 2 | `brand-register.tsx` |
| Brand onboarding | 3 | `brand-onboarding.tsx` + `onboarding-steps.tsx` |
| Campaign create | 5 | `campaign-form.tsx` |
| Creator onboarding | 3 | `creator-onboarding.tsx` |
| Creator register | 2 (phone OTP) | `creator-register.tsx` |
| Proposal (brand) | 5 | `proposal-form.tsx` |
| Counter-proposal (creator) | 5 | `counter-proposal-form.tsx` |
| Discover invite | 3 | `creator-discovery.tsx` dialog |

---

## 4. Tables

| Type | Location | Notes |
|------|----------|-------|
| shadcn `Table` | **Not used** | — |
| HTML `<table>` | `brand-pipeline.tsx` (retired) | List view mode |
| Card rows | campaigns, deals, wallet, discover | Primary list pattern |
| Timeline rows | `collaboration-timeline.tsx` | Event stream, not tabular |

---

## 5. Dialogs, sheets & modals

| Pattern | Component | Example pages |
|---------|-----------|---------------|
| Center modal | Dialog | Accept bid, invite creator, sign contract, withdraw |
| Side panel | Sheet | Discover filters, deal tools (contract/deliverables/payments), mobile nav |
| Full overlay custom | ProposalForm, ShipmentForm | brand-chat |
| Confirm destructive | AlertDialog | Logout, delete account |
| Contact | Dialog | portfolio public |

---

## 6. Navigation & layout components

| Component | File | Role |
|-----------|------|------|
| **BrandLayout** | `brand/brand-layout.tsx` | Sidebar (5 nav items), header, CommandBar, notifications |
| **CreatorLayout** | `creator/creator-layout.tsx` | Sidebar (2 nav + avatar menu), mobile sheet |
| **OnboardingLayout** | `brand/onboarding/onboarding-layout.tsx` | Step sidebar + progress |
| **AuthLoginShell** | `shared/auth-login-shell.tsx` | Split auth with 3D hero |
| **CommandBar** | `brand/command-bar.tsx` | ⌘K global search |
| **InfluoraLogo** | `shared/influora-logo.tsx` | Brand mark |
| **IconBadge** | `shared/icon-badge.tsx` | Pastel nav icons |
| **AuroraBackground** | `shared/aurora-background.tsx` | Auth animated bg |
| **LoginScene3D** | `shared/login-scene-3d.tsx` | R3F blob scene |

### Brand sidebar nav

| Label | Route |
|-------|-------|
| Home | `/brand/dashboard` |
| Campaigns | `/brand/campaigns` |
| Creators | `/brand/discover` |
| Deals | `/brand/chat` |
| Wallet | `/brand/wallet` |

Settings + logout in avatar dropdown. Contracts/messages accessible via routes but not primary nav.

### Creator sidebar nav

| Label | Route |
|-------|-------|
| Deals | `/creator/deals` |
| Wallet | `/creator/wallet` |

Profile, Portfolio, Settings in avatar menu.

---

## 7. Brand-specific feature components

| Component | Folder | Used on |
|-----------|--------|---------|
| DashboardPage | `dashboard/` | brand dashboard |
| CampaignsList | `campaigns/` | campaigns list |
| CampaignForm | `campaigns/` | new/edit campaign |
| CampaignStateMachine | `campaigns/` | campaign detail |
| CreatorDiscovery | `discover/` | discover |
| ProposalForm | `deal-room/` | brand chat |
| ProposalCard | `deal-room/` | timeline |
| ShipmentForm | `deal-room/` | brand chat |
| DealRoomStepProgress | `deal-room/` | chat (both sides) |
| DealContractTab | `deal-room/` | chat |
| DealDeliverablesTab | `deal-room/` | chat |
| DealPaymentsTab | `deal-room/` | chat |
| CollaborationTimeline | `timeline/` | campaign detail, inbox |
| ContractsAndDeliverables | `contracts/` | contracts page |
| OnboardingSteps | `onboarding/` | brand onboarding |

---

## 8. Creator-specific feature components

| Component | Folder | Used on |
|-----------|--------|---------|
| CounterProposalForm | `deal-room/` | creator chat |
| CounterProposalCard | `deal-room/` | creator chat |
| CreatorContractPanel | `deal-room/` | creator chat |
| CreatorContractCard | `deal-room/` | creator chat |
| CreatorDealContractTab | `deal-room/` | creator chat |
| DeliverableSubmission | `deal-room/` | creator chat |
| DeliverableCard | `deal-room/` | creator chat |
| RevisionHandler | `deal-room/` | creator chat |
| ShippingAddressForm | `deal-room/` | creator chat |
| ReceiptConfirmation | `deal-room/` | creator chat |

---

## 9. Shared cross-portal components

| Component | Used by |
|-----------|---------|
| ShipmentCard | brand-chat, creator-chat |
| AuthLoginShell | brand-login, creator-login |
| CollaborationTimeline | brand + creator legacy pages |

---

## 10. TypeScript models — `@/lib/types.ts`

### Enums (domain statuses)

| Enum | Values (summary) |
|------|------------------|
| `UserType` | BRAND, CREATOR, ADMIN |
| `UserStatus` | PENDING_VERIFICATION, ACTIVE, SUSPENDED, DEACTIVATED |
| `VerificationStatus` | UNVERIFIED, PENDING, VERIFIED, REJECTED |
| `WorkspaceType` | BRAND, AGENCY |
| `MemberRole` | OWNER, ADMIN, MANAGER, MEMBER, VIEWER |
| `CampaignStatus` | DRAFT, PENDING_APPROVAL, ACTIVE, PAUSED, COMPLETED, CANCELLED |
| `CollaborationStatus` | 14 states from INVITED → DISPUTED |
| `ProposalStatus` | DRAFT → EXPIRED |
| `ContractStatus` | DRAFT → DISPUTED |
| `DeliverableStatus` | PENDING → REJECTED |
| `ContentType` | IMAGE, VIDEO, STORY, REEL, POST, etc. |
| `Platform` | INSTAGRAM, YOUTUBE, TIKTOK, etc. |
| `WalletTransactionType` | DEPOSIT, ESCROW_HOLD, PAYMENT, etc. |
| `TransactionStatus` | PENDING, COMPLETED, FAILED, CANCELLED |
| `DisputeStatus` | OPEN → CLOSED |
| `NotificationType` | CAMPAIGN_INVITE, PROPOSAL_RECEIVED, etc. |

### Core interfaces

| Interface | Key fields |
|-----------|------------|
| `User` | id, email, userType, status, displayName, avatarUrl |
| `Workspace` | id, name, slug, type, verificationStatus, industry |
| `WorkspaceMember` | workspaceId, userId, role, permissions |
| `Campaign` | title, status, budget, platforms, contentTypes, timeline |
| `CreatorProfile` | handle, bio, verticals, platformStats, rates |
| `Collaboration` | campaignId, creatorId, status, agreedAmount |
| `Proposal` | deliverables, amount, terms, status |
| `Contract` | clauses, signatures, escrowAmount |
| `Deliverable` | type, status, revisions, dueDate |
| `TimelineEvent` | type, metadata, createdAt |
| `Wallet` | balance, currency, escrowHeld |
| `WalletTransaction` | type, amount, status |
| `Notification` | type, read, payload |

---

## 11. API types — `@/lib/api.ts`

| Type | Used on |
|------|---------|
| `Deal`, `DealStatusFilter` | creator-deals, chat |
| `DealMessage`, `MessageKind` | chat pages |
| `LoginPayload`, `BrandRegisterPayload` | auth |
| `CampaignListParams` | campaigns API |
| `CreatorSearchParams` | discover |
| `PortfolioPage`, `PortfolioAnalytics` | portfolio editor/public |
| `PortfolioBadge`, `PortfolioVisibility` | portfolio |
| `ApiEnvelope`, `ApiError` | all API calls |

---

## 12. Local / page-specific interfaces

Defined inline (not in types.ts):

| Interface | Page / component |
|-----------|------------------|
| `ProposalFormData` | proposal-form |
| `CounterProposalFormData` | counter-proposal-form |
| `ShipmentData` | shipment-form |
| `ShippingAddressData` | shipping-address-form |
| `DeliverableSubmissionData` | deliverable-submission |
| `CampaignFormData` | campaign-form |
| `OnboardingData` | onboarding-steps |
| `DealContractStatus` | deal-contract-tab |
| `CampaignState` | campaign-state-machine |
| `Conversation`, `Message` | brand-messages |
| `Transaction`, `EscrowItem` | brand-wallet |

---

## 13. Client stores — `@/lib/store.ts`

| Store | State | Pages |
|-------|-------|-------|
| `useAuthStore` | user, login, logout | login, layouts, dashboard |
| `useCampaignStore` | campaigns cache, addCampaign | campaign form |
| `useNotificationStore` | notifications, unread | brand layout |
| `useUIStore` | sidebar collapsed, mobile menu | layouts |

---

## 14. Helper libraries

| Module | Purpose | Pages |
|--------|---------|-------|
| `formatINR()` | Indian currency | wallet, chat, deals, discover |
| `stage-colors.ts` | Status → badge classes | pipeline, deals, campaigns |
| `icon-theme.ts` | Nav icon colors | layouts |
| `creator-deal-messages.ts` | Persist chat messages | creator chat/inbox |
| `creator-contract-store.ts` | Contract status local | creator inbox/chat |
| `contract-generator.ts` | PDF generation | contracts |
| `upload.ts` | R2 presign upload | onboarding logo |
| `auth-session.ts` | JWT session helpers | auth pages |

---

## 15. Icons

**Library:** Lucide React (`lucide-react`) — 100+ icons across pages

Common icons: Home, Megaphone, Users2, Wallet, Settings, MessageCircle, Bell, Search, ChevronDown, LogOut, Menu, Plus, Eye, EyeOff, ArrowRight, CheckCircle2, Sparkles, Truck, MapPin

---

## 16. Motion & 3D (presentational)

| Component | Technology | Pages |
|-----------|------------|-------|
| AuroraBackground | Framer Motion | auth shell |
| LoginScene3D | React Three Fiber | auth shell |
| AuthLoginShell | Framer fade-in | brand/creator login |
| OnboardingLayout | Framer AnimatePresence | brand onboarding |

See `docs/react/` for motion documentation.

---

*UI inventory v1.0 — cross-reference with per-page audits in BRAND-PAGES-AUDIT.md and CREATOR-PAGES-AUDIT.md*
