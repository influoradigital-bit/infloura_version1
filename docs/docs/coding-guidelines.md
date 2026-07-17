# Coding Guidelines

Conventions **discovered in the codebase** (not aspirational). Follow these when extending Influora so new code matches existing patterns.

---

## Backend (Java / Spring)

### Layering
- Keep controllers thin: parse/validate input, call one service, wrap in `ApiResponse.ok(...)`. No business logic in controllers.
- Put business rules, transactions, and authorization in `@Service` classes. Own the `@Transactional` boundary at the service method.
- Access data through repositories; use JPA Specifications for faceted queries.

### Authorization & tenancy
- Never trust path-param ids. Resolve the caller's tenant with `BrandContextService.requireBrandWorkspace` / `CreatorContextService.requireCreatorProfile`, then load with tenant-scoped finders (`findByIdAndWorkspaceId`, `findByIdAndCreatorId`).
- Return a generic `*_NOT_FOUND` for foreign/cross-tenant ids (no enumeration oracle).
- Enforce workspace roles with `requireRole(member, OWNER, ADMIN, ...)`. Gate plan features with `@RequiresPlan`.

### Money (strict)
- **Never read a money amount from a request body.** Derive it server-side (milestone amount, budget, fee config). The single documented exception (wallet top-up) is reconciled against the webhook.
- All internal money is `BigDecimal` `DECIMAL(14,2)` in rupees. Convert to paise only at the Razorpay boundary via `movePointRight(2).longValueExact()`.
- Move money only through `WalletLedgerService.post(...)` — never mutate `wallets.balance` directly except via `Wallet.applyBalanceDelta`.
- Wrap money mutations in `IdempotencyService.executeOnce(key, workspaceId, scope, action)` and back them with a DB unique constraint (insert-first-wins). Derive keys from server-side ids, not client headers.
- Lock wallets in ascending id order to avoid deadlocks.

### Entities
- Keep entities rich: encode state transitions as methods that guard invalid states (e.g. `deliverable.applyApprove()`, `hold.markFunded()`), not as external setters.
- Do not expose setters for encrypted/sensitive fields or balances.
- Use `@Version` for concurrency-sensitive rows (singletons, disputes, subscriptions) and `saveAndFlush` when you need the optimistic check to run synchronously.

### IDs, errors, sanitization
- Primary keys are 26-char ULIDs (`ulid-creator`).
- Throw `ApiException(code, message, httpStatus)`; the global handler maps it to the error envelope. Reuse existing codes.
- Sanitize free text via `TextSanitizer`. Never log token bytes, message bodies, captions, or provider error bodies.

### Idempotency & webhooks
- Verify webhook signatures **before** parsing the body; fail closed on missing signature/secret; use constant-time comparison.
- Make webhook handlers idempotent at the domain layer (status no-ops, unique keys).

### Migrations
- Add a Flyway `V*.sql`; never modify an applied migration. Numeric or timestamp version — remember `out-of-order: true` is on.
- Keep the entity and DDL in sync (`ddl-auto: validate` fails startup on drift). If you add a column to a table, map it on the entity (several existing drifts are documented as bugs — don't add more).

### Jobs
- Guard `@Scheduled` methods with an `AtomicBoolean` overlap flag, per-item try/catch, and (for external calls) a pre-flight rate-limit check.

---

## Frontend (React / TypeScript)

### Structure & naming
- Import alias `@/` for `src/`.
- File naming: brand/creator/page components and `lib/*` are **kebab-case**; admin/analytics/Meera/custom-ui components are **PascalCase**. Match the neighbours in the folder you're editing.
- Data flow: **page → hook → `api.<resource>.<method>()` → HttpClient**. Put API↔UI translation in `lib/*-mappers.ts`, not in components.

### Data fetching
- Default to the hand-rolled `{ data, loading, error, refresh }` hook shape (team convention). Use TanStack Query only where the codebase already does (billing, deliverable detail, TrendSpark, some admin) and keep query keys as domain-namespaced tuple constants.
- Add new endpoints as methods on the relevant `api` resource object; branch `isLive() ? http.request(...) : mockOr(...)`. Respect the fail-closed mock guard for auth.

### Forms, toasts, money
- Forms: react-hook-form + zod resolver + shadcn `ui/form.tsx`.
- Toasts: sonner. Destructive/admin actions: reason-required AlertDialog.
- Money: always render with `formatINR`; use the shared T1–T9 primitives (`escrow-pill`, `fee-breakdown`, `pay-button`, `stat-pair`, `verified-badge`) for money/trust UI.

### Motion & 3D
- Every `motion/` component must early-return a static branch under `useReducedMotion`.
- Every `3d/` canvas must pair with a `*Gate` + `CanvasFallback` and degrade via drei `PerformanceMonitor`. GSAP/Lenis are for marketing scroll only.

### Security posture
- Never persist the refresh token to JS (it's the HttpOnly cookie). Keep `credentials:'include'` on API calls.
- Treat client-side RBAC/JWT-exp checks as UX affordances only — the server is the authority.
- Put `Idempotency-Key` on money mutations. Only expose `displayMask` for payment instruments.

---

## Cross-cutting

- **Honesty over fabrication**: return empty/typed-empty shapes (`hasData=false`, `NULL` scores) rather than synthetic data. Preserve this in analytics/scoring code.
- **Mirror enums**: many backend enums have exact frontend union counterparts — change both together and keep casing consistent (some are lowercase to match the frontend, e.g. deal message kinds).
- **Document gaps in code**: the codebase annotates known stubs and risks inline (the "Kabir/Meera/Priya/D14" tags). If you leave something half-wired, log it and note it — and update [known-limitations.md](known-limitations.md).
