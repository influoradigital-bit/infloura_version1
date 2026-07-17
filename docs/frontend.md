# Frontend

The frontend is a **client-rendered React 19 SPA built with Vite 6** (not Next.js — the `src/app/` tree and `next.config.mjs` are dead v0/Next scaffold leftovers that `vite build` never touches, and `tsconfig.json` excludes them). It uses React Router v7, is served in production as static files by nginx, and talks to the Spring backend over REST.

Key files: routing `src/App.tsx`, entry `src/main.tsx`, API `src/lib/api.ts`, state `src/lib/store.ts`, admin app `src/admin/*`.

---

## Application shell & routing

`src/main.tsx` → `ReactDOM.createRoot` → `<App/>`. `App.tsx` wraps everything in a single `QueryClientProvider` (one shared `QueryClient`) → `BrowserRouter` → `<Routes>`. There is **no root Auth/Theme provider**; sessions come from `localStorage` tokens and theming is injected by the `useBrandTheme` hook (CSS variables).

Three guarded route areas, each keyed off a distinct localStorage token:

| Area | Guard | Token | Layout |
|---|---|---|---|
| Brand | `ProtectedRoute` → `BrandLayoutWrapper` | `brand_token` | `BrandLayout` |
| Creator | `CreatorProtectedRoute` | `creator_token` | pages self-wrap `CreatorLayout` |
| Admin | `AdminProtectedRoute` | `admin_token` | `AdminConsolePage` (nested router) |

Guards check **token presence only** (not validity) — the server is the authority. Brand/creator guards have a dev-only `?demo=true` bypass (`import.meta.env.DEV`); admin has none. Retired routes redirect (e.g. `/brand/deals*` → `/brand/chat`, `/creator/inbox` → `/creator/deals?status=new`). A trailing `/:handle` catch-all serves public creator portfolios (`@username`), then `*` → NotFound.

Route groups (see [features/brand-dashboard.md](features/brand-dashboard.md), [features/creator-dashboard.md](features/creator-dashboard.md), [features/admin-dashboard.md](features/admin-dashboard.md) for full tables):

- **Brand**: dashboard, campaigns (+new/hype/edit/detail/tracking), discover, creators/:id, wallet, chat, meera, contracts, messages, settings (+billing), analytics (+:creatorId), disputes, reviews, help.
- **Creator**: onboarding, deals, wallet, profile, settings, chat, portfolio, analytics, campaigns (+:id), disputes, reviews, coupons, affiliate, meta callback.
- **Admin**: `/admin/*` → nested pages (dashboard/users/campaigns/finance/support/moderation/disputes/billing).
- **Marketing**: `/`, `/pricing`, `/about`, `/contact`, `/how-it-works/{brands,creators}`, blog, legal, features pages, static placeholders.

---

## Pages (`src/pages/`)

~74 page files, kebab-case. **Brand pages are mostly thin re-export wrappers** around `src/components/brand/*` feature components (the route applies the layout). Creator and marketing pages are fuller. A number of surfaces still render **mock/demo data** (`src/lib/demo-data.ts`, in-file `mock*` consts) with the live endpoints noted in file headers — see [known-limitations.md](known-limitations.md).

---

## Component architecture (`src/components/`, ~193 files)

Design-system approach: shadcn-style primitives, **hand-maintained** (no `components.json` for the CLI). `cn()` = `twMerge(clsx())` in `lib/utils.ts`, which also exports `formatINR` (en-IN lakh/crore money formatting) used across the app.

Folder taxonomy:

- **`ui/`** — Radix/shadcn primitives + project-specific "T1–T9" money/trust primitives (`escrow-pill`, `fee-breakdown`, `pay-button`, `stat-pair`, `verified-badge`, `slot-progress-bar`, `hype-live-indicator`, voice controls, skeletons).
- **`3d/`** — three.js + @react-three/fiber + drei canvases (`HeroGlobe`, `DiscoverCanvas`, `PortfolioCanvas`) each paired with a `*Gate` + `CanvasFallback` for reduced-motion / no-WebGL.
- **`motion/`** — framer-motion components (`FadeUp`, `WordReveal`, `Stagger*`, `CountUp`, `EscrowFlowAnimation`, `EscrowLockSequence`), each with a static `useReducedMotion` branch. Tokens in `lib/motion-config.ts` + `data/motion-tokens.ts`.
- **`brand/`** & **`creator/`** — role dashboards and the mirrored **`deal-room/`** and **`timeline/`** component sets (bid/proposal/contract/deliverable/payment cards, tabs, modals, FSM views).
- **`feature/meera/`** — the conversational AI workspace (`MeeraWorkspace`, `MeeraChatPanel`, `Composer`, `LivingCanvas`, 5 stage components, `CreditMeter`, `FundEscrowButton`).
- **`shared/`**, **`analytics/`**, **`campaigns/tracking/`**, **`trendspark/`**, **`site/`** — cross-cutting UI.

Motion/3D discipline is uniform: every 3D canvas degrades gracefully (drei `PerformanceMonitor` + fallback), every motion component early-returns a static branch under reduced motion. GSAP + Lenis are used **only** for marketing scroll (two hooks), not in app components.

---

## State management

**Zustand** (`src/lib/store.ts`, 6 stores): `useAuthStore` (persist middleware present but `partialize` persists nothing — session lives in localStorage tokens), `useCampaignStore`, `useCollaborationStore`, `useNotificationStore`, `useDiscoveryStore` (client-side filtering), `useUIStore` (sidebar/modal/product-tour).

**TanStack Query** — the provider is at the root, but by team convention **most data uses a hand-rolled `{ data, loading, error, refresh }` hook shape** instead. React-query is used only in billing (`useBilling`), `useDeliverableDetail`, TrendSpark (`useTrendSparkNudge`), and some admin hooks. Query keys are domain-namespaced tuple arrays exported as constants.

**Hooks** (`src/hooks/`): root hooks (`useAuth`, `useBrandTheme`, `useEscrowFund` state machine, `useWalletTopUp`, `useMeeraStream` — the real SSE/EventSource client, `useVoiceInput/Output`, `useScrollPin`/`useSmoothScroll`), plus grouped folders `analytics/`, `brand/`, `creator/`, `trendspark/`.

---

## API layer (`src/lib/api.ts`)

A single `HttpClient` instance (`http`) with per-resource objects on a default `api` facade.

- **Base URL** from `VITE_API_BASE_URL` (fallback `http://localhost:8080/api/v1`). **Mode** from `VITE_API_MODE` (`live` vs `mock`); every method branches `isLive() ? http.request(...) : mockOr(...)`.
- **Auth**: Bearer token from localStorage (`brand_token`/`creator_token`), attached per role; `credentials: 'include'` on every call to carry the HttpOnly refresh cookie (the refresh token is never held in JS). Supports `Idempotency-Key` on mutations.
- **Envelope**: `ApiEnvelope<T> = { success, data?, error?, meta? }`; `ApiError(code, message, status)`. `request` unwraps `.data`; `requestWithMeta` keeps pagination; `requestOrNull` treats 204 as null (TrendSpark); `upload` (FormData); `downloadBlob` (PDF/binary).
- **Fail-closed mock guard**: `assertMockAuthAllowed()` throws in a prod build if mode isn't live — a misconfigured prod bundle cannot mint a mock token.
- **Resource objects**: `auth, workspaces, onboarding, campaigns, creators, deals, messages, contracts, deliverables, wallet, creatorProfile, me, payments, dashboard, notifications, billing, uploads, portfolio, analytics, creatorAnalytics, contentPerformance, campaignTracking, storeIntegrations, creatorReviews, brandReviews, metaOAuth, creatorCoupons, affiliateEarnings, creatorCampaigns, creatorDeliverables, creatorDisputes, brandDisputes, trendspark`.
- **Session mapping** (`src/lib/auth-session.ts`): `persistBrandSession`/`clearBrandSession`/`hasBrandToken` write/read the access token + user metadata; the refresh token is deliberately not persisted.
- **Mock layers**: `src/lib/upload.ts` is a mock R2 upload; `demo-data.ts`, `mock-user.ts`, `creator-contract-store.ts`, `creator-deal-messages.ts` hold demo state; `lib/meera-api.ts` is a separate Meera client (Spring public + Python SSE edge).

Some frontend calls target endpoints the backend does not (yet) expose (e.g. notification `read-all`/`preferences`, generic `POST /uploads`, support `escalate`/`getStats`) — these will 404 at runtime. See [known-limitations.md](known-limitations.md).

---

## Admin console (`src/admin/*`)

A self-contained mini-app mounted at `/admin/*`. It does **not** use `src/lib/api.ts`. Instead:

- Its own client `admin/services/api-contracts.ts` (base `/api/v1/admin`, `admin_token`, a `{success,data|error}` shape).
- Client-side RBAC in `hooks/useAdminAuth.ts`: decodes the JWT for an `exp` sanity check and applies a `ROLE_PERMISSIONS` matrix (SUPER_ADMIN = all; ADMIN = ops minus admin-management + finance-reconcile; SUPPORT = read-heavy). This is UX-only — the server enforces authority.
- Pages: `PulseDashboard`, `UsersPage` (brand/creator KYC/suspend), `CampaignTable`, `FeeControlPanel`, `TicketList`, `FlagQueue`, `DisputesPage`, `BillingPage` (mock).
- A native-`WebSocket` realtime layer (`services/websocket.ts` + `useAdminSocket`) with token-in-query, exponential-backoff reconnect, and app-level heartbeat (advisory transport only).
- Best-effort client audit trail (`utils/auditLogger.ts`) posted to `/admin/audit`.

Documented as a feature in [features/admin-dashboard.md](features/admin-dashboard.md).

---

## Types, content & config

- `src/lib/types.ts` — the frontend domain model (mirrors backend enums).
- `src/content/` — static markdown for `blog/` (3 posts) and `legal/` (8 policies), loaded via `import.meta.glob(..., {as:'raw'})` and rendered by a hand-rolled markdown renderer (no `marked` dependency). SEO via `lib/seo/`.
- `vite.config.ts` — React plugin, `@ → ./src` alias, dev port 3000, proxy `/api/v1` → `localhost:8080`, and a **build-time guard** that fails `vite build` if a production build isn't in `live` mode with a non-localhost API URL.

---

## Testing (frontend)

Testing is **not runnable as currently configured**: `src/test/setup.ts` imports vitest/testing-library, but there is no root `vitest.config.ts` wired to a `test` script and neither dependency is in `package.json`; there is **no Playwright/e2e** despite a `playwright.config.ts` at the repo root. 16 `*.test.*` files exist (several stale). CI runs only a Lighthouse script (`ci/lighthouse-meera.mjs`). See [known-limitations.md](known-limitations.md).

---

## Conventions (frontend)

- File naming: brand/creator/page components kebab-case; admin/analytics/Meera/custom-ui components PascalCase. Import alias `@/` everywhere.
- Data flow: **page → hook → `api.<resource>.<method>()` → HttpClient (mock/live)**, with `lib/*-mappers.ts` translating API↔UI types.
- Forms: react-hook-form + zod + shadcn `ui/form.tsx`. Destructive admin actions use a reason-required AlertDialog.
- Toasts: sonner (a legacy shadcn `use-toast` reducer also coexists).
- Money: always `formatINR`; escrow/fee UI uses the shared T1–T9 primitives.
- Security posture in comments ("Kabir" tags): fail-closed mocks, HttpOnly refresh cookie, client RBAC/JWT-exp as UX-only, encrypted payout instruments (displayMask only), Idempotency-Key on money mutations.

See [coding-guidelines.md](coding-guidelines.md).
