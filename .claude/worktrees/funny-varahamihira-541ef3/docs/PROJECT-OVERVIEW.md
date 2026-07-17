# Influora — Complete Project Overview

**Product:** Influora — B2B Influencer Marketing SaaS (Creator Collaboration OS)  
**Last updated:** 2026-06-14  
**Repository:** `New Influora/` (monorepo)

This document is the **single master reference** for what exists in the codebase today: languages, architecture, folder layout, features, routes, backend status, and how the pieces connect.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Languages & Technologies](#2-languages--technologies)
3. [Monorepo Structure](#3-monorepo-structure)
4. [Frontend (React SPA)](#4-frontend-react-spa)
5. [Backend (Spring Boot API)](#5-backend-spring-boot-api)
6. [Database & Migrations](#6-database--migrations)
7. [API Client & Integration Modes](#7-api-client--integration-modes)
8. [Authentication & Security](#8-authentication--security)
9. [External Services & Infrastructure](#9-external-services--infrastructure)
10. [Brand Portal — Features & Routes](#10-brand-portal--features--routes)
11. [Creator Portal — Features & Routes](#11-creator-portal--features--routes)
12. [Domain Model & Type System](#12-domain-model--type-system)
13. [Implementation Status Matrix](#13-implementation-status-matrix)
14. [Local Development Guide](#14-local-development-guide)
15. [Environment Variables](#15-environment-variables)
16. [Related Documentation Index](#16-related-documentation-index)

---

## 1. Executive Summary

Influora is a **dual-sided platform** connecting **brands/agencies** with **content creators** for influencer marketing campaigns. Brands create campaigns, discover creators, negotiate deals in a chat-first Deal Room, manage contracts, review deliverables, and handle payments via an escrow wallet. Creators receive opportunities, counter-propose, sign contracts, submit deliverables, and track earnings.

The project is a **monorepo** with:

| Layer | Stack | Location |
|-------|-------|----------|
| **Frontend** | React 19, TypeScript, Vite 6, Tailwind CSS 4 | `src/` |
| **Backend** | Java 21, Spring Boot 3.3, JPA, Flyway | `influora-api/` |
| **Database** | MySQL 8.0 | Docker (`docker-compose.yml`) |
| **File storage** | Cloudflare R2 (S3-compatible) | Configured in backend |
| **Messaging** | MSG91 (Email OTP + SMS OTP) | Backend integration |

**Current maturity:**

- **Brand UI:** Largely complete with rich Deal Room, campaigns, discover, wallet, settings (mostly mock/local data).
- **Creator UI:** Substantial progress — deals, chat, portfolio, onboarding pages exist; some flows still use mock data.
- **Backend API:** Phases 1–4 implemented (auth, onboarding, campaigns, creator discovery). Deal room, contracts, deliverables, wallet APIs are specified but not yet fully built.

---

## 2. Languages & Technologies

### 2.1 Primary Languages

| Language | Where used | Version / notes |
|----------|------------|-----------------|
| **TypeScript** | Frontend (`src/**/*.ts`, `src/**/*.tsx`) | 5.7.3 — strict mode |
| **TSX (React JSX)** | UI components and pages | React 19 |
| **Java** | Backend REST API | Java 21 LTS |
| **SQL** | Flyway migrations | MySQL 8 dialect |
| **YAML** | Spring Boot config | `application.yml`, `application-dev.yml` |
| **CSS** | Global styles + Tailwind | Tailwind CSS 4 |
| **HTML** | SPA shell | `index.html` |

### 2.2 Frontend Stack (detailed)

| Category | Technology |
|----------|------------|
| Build tool | **Vite 6** |
| UI framework | **React 19** |
| Routing | **React Router DOM 7** |
| Styling | **Tailwind CSS 4** + PostCSS |
| Component library | **Radix UI** (full primitive set) + shadcn-style `src/components/ui/` |
| Forms | **React Hook Form** + **Zod** validation |
| Server state | **TanStack React Query 5** |
| Client state | **Zustand 5** |
| Charts | **Recharts** |
| Animation | **Framer Motion** |
| 3D login scenes | **Three.js**, **@react-three/fiber**, **@react-three/drei**, **Spline** |
| Icons | **Lucide React** |
| Toasts | **Sonner** |
| Analytics | **@vercel/analytics** |
| Date utilities | **date-fns** |

**Approximate frontend source count:** ~166 TypeScript/TSX files under `src/`.

### 2.3 Backend Stack (detailed)

| Category | Technology |
|----------|------------|
| Framework | **Spring Boot 3.3.5** |
| Web | Spring Web (REST) |
| Persistence | Spring Data JPA + Hibernate |
| Security | Spring Security + **JWT** (jjwt 0.12) |
| Migrations | **Flyway** |
| Database driver | **mysql-connector-j** |
| Object storage | **AWS SDK v2 S3** (Cloudflare R2) |
| ID generation | **ULID** (`ulid-creator`) |
| Build | **Maven** (`pom.xml`) |

**Approximate backend source count:** ~82 Java files + 7 SQL migrations + YAML config.

### 2.4 DevOps & Tooling

| Tool | Purpose |
|------|---------|
| **Docker Compose** | Local MySQL 8 container |
| **npm** | Frontend dependency management |
| **Maven** | Backend build and run |
| **ESLint** | Frontend linting (`npm run lint`) |

---

## 3. Monorepo Structure

```
New Influora/
├── index.html                 # SPA entry HTML
├── package.json               # Frontend npm manifest
├── vite.config.ts             # Vite config (port 3000, @ alias)
├── tsconfig.json              # TypeScript config
├── docker-compose.yml         # MySQL 8 for local dev
├── .env.local.example         # Frontend env template
│
├── src/                       # ← FRONTEND (React + TypeScript)
│   ├── main.tsx               # React bootstrap
│   ├── App.tsx                # Route definitions
│   ├── app/
│   │   └── globals.css        # Tailwind + global styles
│   ├── pages/                 # Route-level page components (~32 pages)
│   ├── components/
│   │   ├── brand/             # Brand-specific UI
│   │   ├── creator/           # Creator-specific UI
│   │   ├── shared/            # Cross-portal components
│   │   └── ui/                # Reusable design system (~50 components)
│   ├── hooks/                 # Custom React hooks
│   └── lib/                   # API client, types, utilities, stores
│
├── influora-api/              # ← BACKEND (Spring Boot + Java)
│   ├── pom.xml
│   ├── .env.example
│   ├── README.md
│   └── src/main/
│       ├── java/com/influora/
│       │   ├── InfluoraApiApplication.java
│       │   ├── config/        # Security, JWT, CORS, R2
│       │   ├── security/      # JWT filter, AuthPrincipal
│       │   ├── domain/
│       │   │   ├── entity/    # JPA entities (12 tables mapped)
│       │   │   └── enums/     # Status enums
│       │   ├── repository/    # Spring Data repositories
│       │   ├── service/       # Business logic
│       │   ├── web/           # REST controllers + DTOs
│       │   └── common/        # Exception handler, utilities
│       └── resources/
│           ├── application.yml
│           ├── application-dev.yml
│           └── db/migration/  # Flyway V1–V7
│
└── docs/                      # Project documentation (~15 files)
    ├── PROJECT-OVERVIEW.md    # ← This file
    ├── BACKEND-API-SPEC.md    # Full API contract (4,300+ lines)
    ├── BACKEND-STACK.md       # Backend architecture decisions
    └── …                      # Feature specs, guides, plans
```

---

## 4. Frontend (React SPA)

### 4.1 Entry & Routing

- **Bootstrap:** `src/main.tsx` mounts `<App />` into `#root`.
- **Router:** `src/App.tsx` uses `BrowserRouter` with nested protected routes.
- **Path alias:** `@/` → `src/` (configured in `vite.config.ts` and `tsconfig.json`).

### 4.2 Page Inventory (`src/pages/`)

#### Brand pages

| File | Route | Purpose |
|------|-------|---------|
| `brand-login.tsx` | `/brand/login` | Brand sign-in |
| `brand-register.tsx` | `/brand/register` | Brand registration |
| `brand-forgot-password.tsx` | `/brand/forgot-password` | Password reset flow |
| `brand-onboarding.tsx` | `/brand/onboarding` | Multi-step brand onboarding |
| `brand-dashboard.tsx` | `/brand/dashboard` | Home / pipeline overview |
| `brand-campaigns.tsx` | `/brand/campaigns` | Campaign list |
| `brand-new-campaign.tsx` | `/brand/campaigns/new` | Create campaign |
| `brand-campaign-detail.tsx` | `/brand/campaigns/:id` | Campaign detail |
| `brand-edit-campaign.tsx` | `/brand/campaigns/:id/edit` | Edit campaign |
| `brand-discover.tsx` | `/brand/discover` | Creator discovery |
| `brand-creator-profile.tsx` | `/brand/creators/:id` | Creator profile view |
| `brand-chat.tsx` | `/brand/chat` | **Deal Room** (chat-first negotiations) |
| `brand-contracts.tsx` | `/brand/contracts` | Contracts overview |
| `brand-messages.tsx` | `/brand/messages` | Messages |
| `brand-wallet.tsx` | `/brand/wallet` | Wallet & escrow |
| `brand-settings.tsx` | `/brand/settings` | Account & team settings |

#### Creator pages

| File | Route | Purpose |
|------|-------|---------|
| `creator-login.tsx` | `/creator/login` | Creator sign-in |
| `creator-register.tsx` | `/creator/register` | Creator registration |
| `creator-onboarding.tsx` | `/creator/onboarding` | Creator profile setup |
| `creator-deals.tsx` | `/creator/deals` | Unified inbox + active deals |
| `creator-chat.tsx` | `/creator/chat` | Creator Deal Room |
| `creator-wallet.tsx` | `/creator/wallet` | Earnings & withdrawals |
| `creator-profile.tsx` | `/creator/profile` | Profile management |
| `creator-settings.tsx` | `/creator/settings` | Account settings |
| `creator-portfolio-editor.tsx` | `/creator/portfolio` | Edit public portfolio |
| `creator-portfolio-public.tsx` | `/:handle` | Public portfolio (`/@username`) |

#### Shared / utility pages

| File | Route | Purpose |
|------|-------|---------|
| `static-page.tsx` | `/terms`, `/privacy`, `/support` | Legal & support placeholders |
| `not-found.tsx` | `*` | 404 page |

#### Legacy redirects (still in router)

| Old route | Redirects to |
|-----------|--------------|
| `/brand/deals`, `/brand/deals/:id`, `/brand/pipeline` | `/brand/chat` |
| `/creator/inbox` | `/creator/deals?status=new` |
| `/creator/active` | `/creator/deals?status=in_progress` |
| `/` | `/brand/login` |

### 4.3 Component Architecture (`src/components/`)

```
components/
├── brand/
│   ├── brand-layout.tsx          # Sidebar shell for brand portal
│   ├── command-bar.tsx           # Global search / command palette
│   ├── dashboard/                # Dashboard widgets
│   ├── campaigns/                # Campaign list, form, state machine
│   ├── discover/                 # Creator search & cards
│   ├── deal-room/                # Proposal, contract, bid, payment tabs
│   ├── deals/                    # Legacy deal room dashboard
│   ├── contracts/                # Contracts & deliverables view
│   ├── timeline/                 # Collaboration timeline + event cards
│   └── onboarding/               # Brand onboarding steps
│
├── creator/
│   ├── creator-layout.tsx        # Sidebar shell for creator portal
│   └── deal-room/                # Counter-proposal, deliverables, contracts
│
├── shared/
│   ├── auth-login-shell.tsx      # Shared login page layout
│   ├── login-scene-3d.tsx        # Three.js / Spline 3D background
│   ├── aurora-background.tsx     # Animated gradient background
│   ├── influora-logo.tsx         # Brand logo component
│   ├── shipment-card.tsx         # Physical product shipment UI
│   └── icon-badge.tsx            # Status icon badges
│
└── ui/                           # Design system (shadcn/Radix)
    ├── button.tsx, input.tsx, dialog.tsx, …
    ├── chart.tsx, calendar.tsx, sidebar.tsx
    └── index.ts                  # Barrel export
```

### 4.4 Core Libraries (`src/lib/`)

| File | Role |
|------|------|
| **`types.ts`** | **Source of truth** for all domain types, enums, and interfaces (~590 lines) |
| **`api.ts`** | HTTP client + resource modules (`auth`, `campaigns`, `creators`, `deals`, etc.) |
| **`auth-session.ts`** | JWT session persistence for brand/creator tokens |
| **`store.ts`** | Zustand global store |
| **`creator-contract-store.ts`** | Creator-side contract state |
| **`creator-deal-messages.ts`** | Deal message helpers / mock data |
| **`contract-generator.ts`** | Client-side contract PDF generation |
| **`upload.ts`** | File upload helpers (presigned R2 flow) |
| **`helpers.ts`** | Currency formatting (`formatINR`), date helpers |
| **`utils.ts`** | `cn()` classname merge utility |
| **`stage-colors.ts`** | Deal stage color mapping |
| **`icon-theme.ts`** | Icon theming constants |
| **`mock-user.ts`** | Mock user data for dev/demo |

### 4.5 Hooks (`src/hooks/`)

| Hook | Purpose |
|------|---------|
| `useAuth.ts` | Authentication state and actions |
| `use-toast.ts` | Toast notification hook |
| `use-mobile.ts` | Responsive breakpoint detection |

### 4.6 Auth & Route Protection

- Brand routes wrapped in `ProtectedRoute` — checks `localStorage.brand_token`.
- Creator routes wrapped in `CreatorProtectedRoute` — checks `localStorage.creator_token`.
- **Demo mode:** In development or with `?demo=true`, protected routes bypass auth.

---

## 5. Backend (Spring Boot API)

### 5.1 Server Configuration

| Setting | Value |
|---------|-------|
| Port | **8080** |
| Context path | **`/api/v1`** |
| Full base URL | `http://localhost:8080/api/v1` |
| Health check | `GET /api/v1/health` |

### 5.2 Package Layout

```
com.influora/
├── InfluoraApiApplication.java
├── config/
│   ├── SecurityConfig.java       # JWT filter chain, route permissions
│   ├── CorsConfig.java           # CORS for frontend origins
│   ├── JwtProperties.java        # JWT secrets & expiry
│   └── (R2 config planned)
├── security/
│   ├── JwtService.java           # Token issue & validate
│   ├── JwtAuthenticationFilter.java
│   └── AuthPrincipal.java        # Authenticated user context
├── domain/
│   ├── entity/                   # 12 JPA entities
│   └── enums/                    # CampaignStatus, CollaborationStatus, etc.
├── repository/                   # Spring Data JPA repos
├── service/                      # Business logic layer
├── web/
│   ├── AuthController.java
│   ├── UserController.java
│   ├── OnboardingController.java
│   ├── WorkspaceController.java
│   ├── CampaignController.java
│   ├── CreatorController.java
│   ├── HealthController.java
│   └── dto/                      # Request/response DTOs per domain
└── common/
    ├── GlobalExceptionHandler.java
    ├── JsonLists.java            # JSON column helpers
    └── SlugUtils.java
```

### 5.3 REST Controllers (implemented)

#### Auth — `AuthController` (`/auth`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/brand/send-email-otp` | Send email OTP (MSG91) |
| POST | `/auth/brand/verify-email` | Verify email OTP |
| POST | `/auth/brand/register` | Brand registration |
| POST | `/auth/brand/login` | Brand login → JWT |
| POST | `/auth/refresh` | Refresh access token |
| POST | `/auth/logout` | Invalidate refresh token |
| POST | `/auth/forgot-password` | Request password reset |
| POST | `/auth/reset-password` | Reset password with token |

#### User — `UserController` (`/users`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/users/me` | Current user profile |

#### Onboarding — `OnboardingController` (`/onboarding/brand`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/onboarding/brand/company` | Save company details |
| POST | `/onboarding/brand/complete` | Mark onboarding complete |
| POST | `/onboarding/brand/kyc` | Submit KYC documents |

#### Workspace — `WorkspaceController` (`/workspaces`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/workspaces/slug-check?slug=` | Check slug availability |

#### Campaigns — `CampaignController` (`/campaigns`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/campaigns` | List campaigns (filter, paginate) |
| GET | `/campaigns/:id` | Get campaign detail |
| POST | `/campaigns` | Create campaign |
| PATCH | `/campaigns/:id` | Update campaign |
| DELETE | `/campaigns/:id` | Delete draft campaign |
| POST | `/campaigns/:id/duplicate` | Duplicate campaign |

#### Creators — `CreatorController` (`/creators`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/creators` | Search/filter creators |
| GET | `/creators/:id` | Creator profile detail |
| POST | `/creators/:id/save` | Save/unsave creator |
| POST | `/creators/:id/invite` | Invite creator to campaign |

#### Health — `HealthController`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Service health check |

### 5.4 JPA Entities (database-mapped)

| Entity | Table | Domain |
|--------|-------|--------|
| `User` | `users` | Core identity (brand/creator/admin) |
| `Workspace` | `workspaces` | Brand/agency account |
| `WorkspaceMember` | `workspace_members` | User ↔ workspace roles |
| `Campaign` | `campaigns` | Brand campaign definitions |
| `CreatorProfile` | `creator_profiles` | Creator public profile |
| `PlatformStat` | `platform_stats` | Per-platform follower/engagement |
| `Collaboration` | `collaborations` | Brand ↔ creator deal link |
| `SavedCreator` | `saved_creators` | Brand bookmarked creators |
| `Wallet` | `wallets` | Balance per user/workspace |
| `RefreshToken` | `refresh_tokens` | JWT refresh tokens |
| `PasswordResetToken` | `password_reset_tokens` | Password reset flow |
| `EmailOtpChallenge` | `email_otp_challenges` | Email OTP verification |

### 5.5 API Not Yet Implemented (specified in `BACKEND-API-SPEC.md`)

The full spec defines 33 sections including:

- Collaborations & proposals (deal negotiation)
- Contracts & digital signatures
- Deliverables & revisions
- Timeline & messaging (Deal Room backend)
- Wallet transactions & escrow
- Disputes & notifications
- File uploads (R2 presigned)
- Creator onboarding & platform connections
- Campaign bids, shipments, payment methods
- WebSocket / SSE real-time events
- Admin APIs

---

## 6. Database & Migrations

**Engine:** MySQL 8.0 · **Charset:** `utf8mb4_unicode_ci` · **IDs:** ULID (26-char VARCHAR)

### Flyway Migrations

| Version | File | Contents |
|---------|------|----------|
| V1 | `V1__file_uploads.sql` | `file_uploads` table for R2 metadata |
| V2 | `V2__core_auth.sql` | `users`, `workspaces`, `workspace_members`, `refresh_tokens`, `password_reset_tokens`, `wallets` |
| V3 | `V3__workspace_kyc_docs.sql` | KYC document fields on workspaces |
| V4 | `V4__campaigns.sql` | `campaigns` table with JSON columns |
| V5 | `V5__email_otp.sql` | `email_otp_challenges` table |
| V6 | `V6__creators_collaborations.sql` | `creator_profiles`, `platform_stats`, `collaborations`, `saved_creators` |
| V7 | `V7__seed_discoverable_creators.sql` | Dev seed: 5 demo creators |

### Planned Tables (in API spec, not yet migrated)

`proposals`, `contracts`, `contract_deliverables`, `deliverables`, `deliverable_revisions`, `timeline_events`, `wallet_transactions`, `escrow_holds`, `payment_milestones`, `disputes`, `dispute_evidence`, `notifications`, `audit_logs`, `campaign_invites`, and more.

---

## 7. API Client & Integration Modes

**File:** `src/lib/api.ts` (~1,180 lines)

### Dual mode operation

| Mode | Trigger | Behavior |
|------|---------|----------|
| **Mock** (default) | `VITE_API_MODE` unset or ≠ `live` | Returns in-memory mock data; no backend required |
| **Live** | `VITE_API_MODE=live` in `.env.local` | Calls Spring Boot at `VITE_API_BASE_URL` |

### API resource modules

```typescript
api.auth          // Login, register, OTP, password reset
api.workspaces    // Slug check
api.onboarding    // Brand & creator onboarding steps
api.campaigns     // CRUD + duplicate
api.creators      // Search, save, invite
api.deals         // Deal list & detail (mock-heavy)
api.messages      // Deal room messages (mock-heavy)
api.contracts     // Contract CRUD (mock-heavy)
api.deliverables  // Submission & review (mock-heavy)
api.wallet        // Balance & transactions (mock-heavy)
api.payments      // Payment milestones (mock-heavy)
api.dashboard     // Dashboard stats (mock-heavy)
api.notifications // In-app alerts (mock-heavy)
api.uploads       // R2 presigned upload (mock-heavy)
api.portfolio     // Public creator portfolio (mock-heavy)
```

### Response envelope

All API responses follow:

```json
{
  "success": true,
  "data": { },
  "meta": { "page": 1, "limit": 20, "total": 100, "hasMore": true },
  "error": { "code": "ERROR_CODE", "message": "Human-readable message" }
}
```

---

## 8. Authentication & Security

### Frontend

- JWT stored in `localStorage`:
  - Brand: `brand_token`
  - Creator: `creator_token`
- `Authorization: Bearer <token>` header on all authenticated requests.
- Session helpers in `src/lib/auth-session.ts`.

### Backend

- **Spring Security** with JWT filter (`JwtAuthenticationFilter`).
- Access token expiry: 900s (15 min) · Refresh token: 30 days.
- Role-based access on campaign mutations (OWNER, ADMIN, MANAGER).
- Email OTP via MSG91 before registration (configurable).
- Dev profile (`application-dev.yml`) can disable email verification for testing.

---

## 9. External Services & Infrastructure

| Service | Purpose | Config location |
|---------|---------|-----------------|
| **MySQL 8** | Primary relational database | `docker-compose.yml`, `application.yml` |
| **Cloudflare R2** | Object storage (avatars, videos, PDFs, KYC) | `influora.r2.*` in `application.yml` |
| **MSG91 Email** | Brand email OTP & transactional email | `influora.msg91.email.*` |
| **MSG91 SMS** | Creator phone OTP (India) | `influora.msg91.auth-key`, `sender-id` |
| **Vercel** | Frontend hosting (analytics enabled) | `@vercel/analytics` dependency |

### R2 object key convention (planned)

```
avatars/users/{userId}/{ulid}.webp
logos/workspaces/{workspaceId}/{ulid}.png
deliverables/{workspaceId}/{collaborationId}/{ulid}.mp4
thumbnails/deliverables/{fileId}.jpg
documents/kyc/{workspaceId}/{ulid}.pdf
contracts/{collaborationId}/{contractId}.pdf
```

---

## 10. Brand Portal — Features & Routes

### Navigation (sidebar)

```
Home → Campaigns → Discover → Chat (Deal Room) → Wallet → Settings
```

### Feature summary

| Feature | Status | Notes |
|---------|--------|-------|
| Registration & login | UI ✅ · API ✅ | Email OTP integrated |
| Onboarding (company, KYC) | UI ✅ · API ✅ | Multi-step wizard |
| Dashboard | UI ✅ · API ⏳ | Pipeline funnel, SLA alerts, stats |
| Campaigns (CRUD) | UI ✅ · API ✅ | Create, edit, duplicate, filter |
| Creator discovery | UI ✅ · API ✅ | Search, filter, save, invite |
| Deal Room (chat) | UI ✅ · API ⏳ | Proposals, counters, contracts in timeline |
| Contracts | UI ✅ · API ⏳ | Auto-generate, sign, PDF download |
| Deliverable review | UI ✅ · API ⏳ | Approve/revision in timeline |
| Wallet & escrow | UI ✅ · API ⏳ | Balance, transactions, add funds |
| Settings | UI ✅ · API partial | Profile, team, notifications |
| Physical shipments | UI partial | `shipment-card.tsx` component exists |

### Deal lifecycle (brand perspective)

```
Discover Creator → Send Proposal → Negotiate (Counter) → Accept Terms
    → Generate Contract → Sign → Fund Escrow → Review Deliverables
    → Approve → Release Payment → Rate Creator
```

---

## 11. Creator Portal — Features & Routes

### Navigation (sidebar)

```
Deals (Inbox + Active) → Chat (Deal Room) → Wallet → Profile → Portfolio → Settings
```

### Feature summary

| Feature | Status | Notes |
|---------|--------|-------|
| Registration & login | UI ✅ · API ⏳ | Creator auth endpoints planned |
| Onboarding | UI ✅ · API ⏳ | Platform connect, rates, KYC |
| Deals / Inbox | UI ✅ · API ⏳ | Unified at `/creator/deals` |
| Deal Room (chat) | UI ✅ · API ⏳ | Mirror of brand chat |
| Counter-proposals | UI ✅ · API ⏳ | 5-step form with earnings breakdown |
| Contract signing | UI ✅ · API ⏳ | Digital signature modal |
| Deliverable submission | UI ✅ · API ⏳ | File upload, revision tracking |
| Wallet & earnings | UI ✅ · API ⏳ | Gross − fees − TDS = net |
| Public portfolio | UI ✅ · API ⏳ | `/@username` public page |
| Profile & ratings | UI partial | Profile page exists |

### Deal lifecycle (creator perspective)

```
Receive Proposal → Review → Accept / Counter → Sign Contract
    → Submit Deliverables → Handle Revisions → Confirm Payment → Rate Brand
```

---

## 12. Domain Model & Type System

**Primary file:** `src/lib/types.ts`

### Core enums

| Enum | Values (summary) |
|------|------------------|
| `UserType` | BRAND, CREATOR, ADMIN |
| `CampaignStatus` | DRAFT, PENDING_APPROVAL, ACTIVE, PAUSED, COMPLETED, CANCELLED |
| `CollaborationStatus` | INVITED → APPLIED → … → COMPLETED / DISPUTED (14 states) |
| `ProposalStatus` | DRAFT, SENT, VIEWED, COUNTERED, ACCEPTED, REJECTED, EXPIRED |
| `ContractStatus` | DRAFT, PENDING_SIGNATURES, ACTIVE, COMPLETED, TERMINATED, DISPUTED |
| `DeliverableStatus` | PENDING → SUBMITTED → UNDER_REVIEW → APPROVED / REVISION_REQUESTED |
| `Platform` | INSTAGRAM, YOUTUBE, TIKTOK, TWITTER, LINKEDIN, FACEBOOK, TWITCH, OTHER |
| `ContentType` | IMAGE, VIDEO, STORY, REEL, POST, ARTICLE, PODCAST, LIVE_STREAM |
| `WalletTransactionType` | DEPOSIT, WITHDRAWAL, ESCROW_HOLD, ESCROW_RELEASE, PAYMENT, REFUND, FEE |

### Core interfaces

`User`, `Workspace`, `WorkspaceMember`, `Campaign`, `CreatorProfile`, `Collaboration`, `Proposal`, `Contract`, `Deliverable`, `TimelineEvent`, `Wallet`, `WalletTransaction`, `Dispute`, `Notification`, and portfolio-specific types.

**Rule:** When adding a new field to the domain, update `types.ts` first, then `BACKEND-API-SPEC.md`, then backend entity + DTO, then frontend UI.

---

## 13. Implementation Status Matrix

| Area | Frontend | Backend | Wired (live API) |
|------|----------|---------|------------------|
| Brand auth (register/login/OTP) | ✅ | ✅ | ✅ |
| Brand onboarding & KYC | ✅ | ✅ | ✅ |
| Campaigns CRUD | ✅ | ✅ | ✅ |
| Creator discovery | ✅ | ✅ | ✅ |
| Creator auth | ✅ | ⏳ | ❌ |
| Creator onboarding | ✅ | ⏳ | ❌ |
| Deal Room / messaging | ✅ | ⏳ | ❌ (mock) |
| Proposals & counters | ✅ | ⏳ | ❌ (mock) |
| Contracts & signatures | ✅ | ⏳ | ❌ (mock) |
| Deliverables | ✅ | ⏳ | ❌ (mock) |
| Wallet & payments | ✅ | partial entity | ❌ (mock) |
| File uploads (R2) | partial | ⏳ | ❌ |
| Notifications | partial | ⏳ | ❌ (mock) |
| Public portfolio | ✅ | ⏳ | ❌ (mock) |
| Real-time (WebSocket/SSE) | ⏳ | ⏳ | ❌ |
| Admin panel | ⏳ | ⏳ | ❌ |

**Legend:** ✅ Done · ⏳ In progress / specified · ❌ Not started

### Backend implementation phases (from `influora-api/README.md`)

| Phase | Scope | Status |
|-------|-------|--------|
| Phase 1 | Auth (brand register/login/JWT/OTP) | ✅ Complete |
| Phase 2 | Onboarding & workspace | ✅ Complete |
| Phase 3 | Campaigns | ✅ Complete |
| Phase 4 | Creator discovery | ✅ Complete |
| Phase 5+ | Deals, contracts, deliverables, wallet, uploads | ⏳ Planned |

---

## 14. Local Development Guide

### Prerequisites

- **Node.js** 18+ and npm
- **Java 21** and Maven 3.9+
- **Docker Desktop** (for MySQL)

### Start everything

```bash
# 1. MySQL
docker compose up -d

# 2. Backend (from influora-api/)
cd influora-api
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Frontend (from repo root)
npm install
npm run dev
```

### URLs

| Service | URL |
|---------|-----|
| Frontend (Vite) | http://localhost:3000 |
| Backend API | http://localhost:8080/api/v1 |
| Health check | http://localhost:8080/api/v1/health |
| MySQL | localhost:3306 (user: `influora`, pass: `influora`, db: `influora`) |

### Connect frontend to live backend

Copy `.env.local.example` → `.env.local`:

```env
VITE_API_MODE=live
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

Without this, the frontend runs in **mock mode** and does not call the backend.

### Demo credentials (seed data)

5 discoverable creators seeded in V7 migration (e.g. `priya.creates@demo.influora.com`).  
Password for demo accounts: `Password@123`

---

## 15. Environment Variables

### Frontend (`.env.local`)

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_MODE` | `mock` | Set to `live` to use Spring Boot |
| `VITE_API_BASE_URL` | `http://localhost:8080/api/v1` | Backend base URL |

### Backend (`influora-api/.env` or IDE run config)

| Variable | Description |
|----------|-------------|
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | DB credentials |
| `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET` | JWT signing keys (min 32 chars) |
| `CORS_ALLOWED_ORIGINS` | Frontend origins (comma-separated) |
| `MSG91_AUTH_KEY` | MSG91 SMS auth key |
| `MSG91_TOKEN_AUTH` | MSG91 Email API token |
| `MSG91_EMAIL_DOMAIN` / `MSG91_FROM_EMAIL` | Email sender config |
| `R2_ACCOUNT_ID` / `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | Cloudflare R2 |
| `R2_BUCKET_NAME` / `R2_PUBLIC_URL` | R2 bucket & CDN URL |

Full list: `docs/BACKEND-API-SPEC.md` §21 and `influora-api/.env.example`.

---

## 16. Related Documentation Index

| Document | Lines | Purpose |
|----------|-------|---------|
| **`PROJECT-OVERVIEW.md`** | — | **This file — master project reference** |
| **`docs/react/README.md`** | — | **React motion & 3D pack + Cursor/Gemini prompts** |
| **`docs/frontend/README.md`** | — | **Frontend UI audit — all pages, elements, colors, models** |
| **`docs/REACT-MOTION-FLOW.md`** | — | React integration flow (folder order, zones) |
| `BACKEND-API-SPEC.md` | 4,300+ | Complete REST API contract (33 sections) |
| `BACKEND-STACK.md` | ~210 | MySQL + R2 + Spring Boot decisions |
| `MSG91-EMAIL-OTP.md` | — | Email OTP integration guide |
| `CREATOR-PORTFOLIO-PAGE.md` | — | Public portfolio spec (`/@username`) |
| `brand-features.md` | ~890 | Brand feature specification |
| `creator-features.md` | ~640 | Creator feature specification |
| `brand-implementation-plan.md` | ~410 | Brand build phases (completed) |
| `creator-implementation-plan.md` | ~410 | Creator build phases (planned) |
| `brand-vs-creator-comparison.md` | ~340 | Side-by-side flow comparison |
| `PROJECT-STATUS.md` | ~335 | Status snapshot & timeline |
| `VISUAL-FLOWCHARTS.md` | ~525 | Architecture diagrams & flows |
| `QUICK-REFERENCE.md` | ~236 | Developer quick lookup |
| `DOCUMENTATION-INDEX.md` | ~288 | Index of all docs by role |
| `IMPLEMENTATION-CHECKLIST.md` | — | Task checklist |
| `UI-UX-IMPROVEMENT-PLAN.md` | — | UI/UX enhancement plan |
| `BRAND_GUIDE.md` / `CREATOR_GUIDE.md` | — | User-facing guides |
| `influora-api/README.md` | — | Backend quick start & endpoint list |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        BROWSER (SPA)                            │
│  React 19 + TypeScript + Vite + Tailwind + Radix UI             │
│  src/pages · src/components · src/lib/api.ts                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │ HTTPS / REST JSON
                           │ Authorization: Bearer JWT
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              SPRING BOOT API (Java 21)                          │
│  Port 8080 · Context /api/v1                                    │
│  Controllers → Services → Repositories → JPA                    │
│  Security: JWT + Spring Security                                │
└──────┬──────────────────┬──────────────────┬──────────────────┘
       │                  │                  │
       ▼                  ▼                  ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────────┐
│   MySQL 8    │  │ Cloudflare   │  │     MSG91        │
│  (Docker)    │  │     R2       │  │  Email + SMS OTP │
│  Flyway DDL  │  │  S3-compat   │  │                  │
│  ULID PKs    │  │  Videos/PDFs │  │                  │
└──────────────┘  └──────────────┘  └──────────────────┘
```

---

## Key Design Decisions

1. **Monorepo** — Frontend and backend live in one repo; API contract in `docs/` keeps them aligned.
2. **TypeScript types first** — `src/lib/types.ts` is the domain source of truth; backend entities mirror it.
3. **Mock-first frontend** — UI can be developed and demoed without a running backend (`VITE_API_MODE=mock`).
4. **Chat-first Deal Room** — Negotiations, contracts, and deliverables unified in `/brand/chat` and `/creator/chat` (legacy `/deals` routes redirect).
5. **ULID everywhere** — 26-character sortable IDs in DB and API (not UUID, not auto-increment).
6. **India-first** — INR currency, GST/TDS fee breakdowns, MSG91 for OTP, `Asia/Kolkata` default timezone.
7. **Presigned uploads** — Large videos go direct to R2; backend stores metadata only.
8. **No file bytes through JVM** — Except small direct uploads (< 10 MB) for avatars/logos.

---

*For API endpoint details, see `docs/BACKEND-API-SPEC.md`. For backend setup, see `docs/BACKEND-STACK.md` and `influora-api/README.md`.*
