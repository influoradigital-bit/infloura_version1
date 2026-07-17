# Feature: Brand Dashboard

**Business Purpose** — The brand's home base: a single authenticated area where a brand discovers creators, runs campaigns, negotiates deals, funds escrow, reviews deliverables, tracks analytics, and manages billing. It ties the whole brand-side journey together behind one layout and navigation.

**Who uses it** — Brand workspace members (all roles, with privileged actions gated to OWNER/ADMIN/MANAGER).

## User Roles
Brand (workspace members). Guarded by `ProtectedRoute` (localStorage `brand_token`).

## Permissions
Read access for all members; money and destructive actions require workspace roles (OWNER/ADMIN/MANAGER) enforced server-side.

## Business Flow
```
Brand login → dashboard (KPIs, at-a-glance) → navigate to:
  Discover → Campaigns → Deals/Chat → Contracts → Deliverables → Analytics → Wallet/Billing → Meera AI
```

## Frontend
- **Layout**: `components/brand/brand-layout.tsx` (sidebar, command bar ⌘K, notification bell), applied via `BrandLayoutWrapper` in `App.tsx`.
- **Routes** (guarded): `/brand/dashboard`, `/campaigns` (+new/hype/edit/:id/tracking), `/discover`, `/creators/:id`, `/wallet`, `/chat`, `/meera`, `/contracts`, `/messages`, `/settings` (+/billing), `/analytics` (+/:creatorId), `/disputes`, `/reviews`, `/help`.
- **Pages**: `brand-dashboard` → `components/brand/dashboard/DashboardPage`, plus the feature pages listed above (mostly thin wrappers).
- **State/hooks**: `useAuth`, `useBrandTheme`, `useUIStore`, plus per-feature hooks.

## Backend
Aggregates many controllers — primarily `DashboardController` for the landing KPIs, then the per-feature controllers (campaigns, deals, wallet, analytics, etc.). See each feature doc.

## Database
Reads across `campaigns`, `collaborations`, `wallets`, `deliverables`, `notifications`, etc. No dedicated table.

## APIs
`GET /dashboard/*` (KPIs) plus the per-feature endpoints in [../api.md](../api.md).

## AI
The Meera workspace (`/brand/meera`) is embedded here; see [meera-ai.md](meera-ai.md).

## Notifications
In-app bell (poll-based) + email; the brand receives `brand.new_application`, `brand.deliverable_ready`, `WalletLowBalanceEvent`, etc.

## Dependencies
- **Depends on**: auth/workspaces, and essentially every brand-side feature.
- **Depended on by**: nothing (it's the top-level shell).

## Connected Files
`components/brand/brand-layout.tsx`, `components/brand/command-bar.tsx`, `pages/brand-*`, `DashboardController`, per-feature controllers/services.

## Execution Flow
```
Route render → BrandLayoutWrapper (ProtectedRoute + BrandLayout) → page → hook → api → controller → service → DB
```

## Error Handling
Session-expiry currently breaks (no auto-refresh — see [../known-limitations.md](../known-limitations.md)); most surfaces degrade to mock data if `VITE_API_MODE!=live`.

## Security
Guard checks token presence only; server enforces workspace scope and roles on every call.

## Performance
Lazy 3D on marketing-ish surfaces; per-feature pagination; some surfaces still mock.

## Testing
Some page tests exist (several stale). Regression risks: layout/nav, guard behavior.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~80% (several sub-pages still mock-backed)
- **Known issues**: session refresh half-wired; mock surfaces (campaign detail, wallet, messages).
- **Last verified**: 2026-07-15
