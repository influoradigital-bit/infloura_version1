# Performance & Scalability

Performance-relevant patterns in the codebase: caching, query strategy, background jobs, external-call throttling, and the known scaling constraints.

---

## Caching

- **Redis** is a dependency (`spring-boot-starter-data-redis`) and available, but runtime use is limited today — most hot state (rate-limit windows, nonce cache, OAuth state stores) is currently **in-memory `ConcurrentHashMap`**, which is a scaling constraint (see below).
- **`@Cacheable`** is used for slow-changing lookups such as `HsnSacCodeService.resolveCode` (GST HSN/SAC codes).
- **Balances** are a denormalized projection of the append-only ledger, avoiding a running-sum recompute on every read.
- **Presigned R2 URLs** (15-min TTL) offload media delivery entirely to Cloudflare — media bytes never pass through the API.

---

## Query patterns

- **Tenant-scoped finders** (`findByIdAndWorkspaceId`, `findByIdAndCreatorId`) keep result sets small and prevent cross-tenant scans; indexes exist on `workspace_id`, `status`, and common filter columns.
- **JPA Specifications** compose faceted search (creator discovery, campaigns, support tickets) into a single query; discovery facets are computed over a bounded set (≤5000 discoverable profiles).
- **Pessimistic locks** (`findByIdForUpdate`) are scoped to single rows (wallets, invoice sequences, the campaign row during publish-fee charge) and held only within a transaction; wallets are locked in ascending id order to avoid deadlocks.
- **Atomic updates** avoid read-modify-write races: `tryDecrement` for AI credits (`UPDATE ... WHERE credits_remaining >= :cost`), `incrementUsageCount`, `revokeAllForUser`.
- **Pagination** everywhere on list endpoints (limits clamped, e.g. discovery ≤100, admin ≤200); deal messages use a `before` cursor.
- **`open-in-view: false`** — no lazy-loading outside the service transaction, forcing explicit fetch boundaries.
- **Time-series tables** (`creator_metrics`, `media_metrics`, `creator_scores`, `audience_demographics`) are append-only snapshots with `(entity, time)` indexes; "latest" reads use ordered limits.

Known query caveats: some in-memory post-filters (creator-campaign niche/platform filters) mean total/hasMore reflect only the current page when active; discovery facets are bounded.

---

## Background jobs

All jobs use an `AtomicBoolean` overlap guard (no re-entrancy), per-item try/catch (one failure doesn't abort the batch), and Meta jobs add a pre-flight rate-limit check.

| Job | Schedule (UTC) | Load characteristic |
|---|---|---|
| `MetricsPollingJob` | every 6h | 1 Graph call per connected creator |
| `DeliverableVerificationJob` | every 6h at :30 | Graph calls for POSTED/METRICS_REPORTED deliverables |
| `AudienceDemographicsJob` | Sun 03:30 | weekly Graph calls |
| `ScoreCalculationJob` | daily 04:00 | in-memory scoring (no external calls; brand-safety not wired) |
| `MetaTokenRefreshService` / `StaleTokenCleanupJob` | daily 02:30 / 04:00 | token maintenance |
| `DeliverableCleanupJob` | daily 02:00 / 02:30 | R2 deletes (dry-run default) |
| `Subscription*Job` / `AICreditResetJob` | daily/monthly | DB-only |
| `AffiliateSettlementJob` / `AffiliateEarningReconciliationJob` | monthly / hourly | DB-only |
| `EmailWorker` | every 30s (fixedDelay) | batch of 50 emails via MSG91 |

The email outbox worker batches 50/cycle with exponential backoff (30/90/270/810s, cap 5 retries). Verification/polling jobs offset their `:30` schedules to avoid piling Graph calls at the same minute.

---

## External-call throttling

- **Meta**: `MetaRateLimitTracker` parses the `X-Business-Use-Case-Usage` header; requests are pre-flight-deferred at ≥90% usage (`MetaRateLimitException`), warned at ≥80%, and marked limited on 429. Jobs skip creators near the cap.
- **AI clients**: single synchronous round-trip, no retries; brand-safety batches capped at 25 items/call; fail-closed (NULL scores) or fail-open (templated copy) rather than blocking.
- **Razorpay**: idempotent order/payout creation; webhook processing is idempotent at the domain layer.

---

## Frontend performance

- Static bundle served by nginx; 3D scenes lazy-loaded and degraded via drei `PerformanceMonitor` + reduced-motion/no-WebGL fallbacks (every `3d/` canvas has a `*Gate`).
- Motion components early-return static branches under `useReducedMotion`.
- Lighthouse budget enforced in CI (`ci/lighthouse-meera.mjs`).
- Money formatting (`formatINR`) and presigned-URL retry-on-403 are handled client-side to avoid extra round-trips.

---

## Scaling constraints (must fix before horizontal scale-out)

These are explicitly flagged in code as per-instance / in-memory:

1. **`AuthRateLimitFilter`** — per-node fixed-window limits; global enforcement needs Redis or an edge WAF.
2. **`NonceCache`** (mesh replay protection) — in-memory; per-node only.
3. **OAuth state stores** (Meta/Shopify) — in-memory `ConcurrentHashMap`; move to Redis for multi-instance.
4. **`idempotency_keys`** — no TTL/reaper, so the table grows unbounded (correctness is fine; add a cleanup job).
5. **Idempotency correctness** relies on DB unique constraints (insert-first-wins), which is scale-safe; the in-memory pieces above are the gap.

Redis is already a dependency, so migrating these is a wiring exercise, not a new infrastructure decision. See [known-limitations.md](known-limitations.md).
