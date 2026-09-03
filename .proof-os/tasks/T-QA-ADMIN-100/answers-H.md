# BLOCK H — Money, moderation, ops (H92–H100)
Answered by priya · fresh-context · 2026-09-02

**H92. No — admin sees an aggregate summary plus the FROZEN rows only. There is no endpoint that lists the full escrow ledger row-by-row.**
Exactly two escrow reads exist across all `Admin*Controller` classes: `GET /admin/finance/escrow` → `EscrowSummaryDto` = 4 scalars only; `GET /admin/escrow/flagged` → `List<FlaggedEscrowDto>`, `EscrowStatus.FROZEN` rows only. FUNDED and RELEASED holds are never enumerated per-row anywhere in the admin surface.
Evidence: `influora-api/src/main/java/com/influora/web/AdminFinanceController.java:50` — `@GetMapping("/escrow")` returns a single `EscrowSummaryDto`, not a list.
Evidence: `influora-api/src/main/java/com/influora/service/admin/AdminFinanceService.java:277` — the summary is 4 aggregates (`sumAmountByStatusIn(FUNDED)`, `countByStatus(FUNDED)`, `countByStatus(FROZEN)`, `avgReleaseSeconds()`).
Evidence: `influora-api/src/main/java/com/influora/web/AdminEscrowController.java:41` — the only list endpoint; `AdminFinanceService.java:300` `findByStatusOrderByCreatedAtDesc(EscrowStatus.FROZEN)` hard-codes FROZEN.
Evidence: `AdminEscrowController.java:18` — class javadoc states this controller "intentionally exposes ONLY the read (`GET /flagged`)"; the `/{id}/hold|release|refund` routes `escrowApi` declares are deliberately not built.

**H93. Real, not static. The reconciliation panel is mounted and calls a live endpoint that does a per-row internal-ledger vs Razorpay/RazorpayX diff.**
Evidence: `src/admin/components/finance/ReconciliationPanel.tsx:104` — `financeApi.getReconciliation(forDate)` on form submit, into `setRows(res.data)`.
Evidence: `src/admin/components/finance/FinanceConsole.tsx:723` — `<ReconciliationPanel />` rendered in the `reconciliation` tab (trigger at `FinanceConsole.tsx:254`), reachable at `/admin/revenue` via `src/pages/admin-console.tsx:58`.
Evidence: `influora-api/src/main/java/com/influora/web/AdminFinanceController.java:60` — `GET /admin/finance/reconciliation?date=`.
Evidence: `AdminFinanceService.java:384` — iterates `payoutRepository.findByCreatedAtBetween` + `walletTopUpRepository.findByCreatedAtBetween` for the day and reconciles each.
Evidence: `AdminFinanceService.java:518` / `:540` — live `razorpayXClient.fetchPayout(...)` / `razorpayClient.fetchOrder(...)` when no stored webhook payload exists.
Caveat: the panel is read-only. `financeApi.resolveReconciliation` is a stub — `src/admin/services/api-contracts.ts:424` returns `unavailable<ReconciliationItem>(...)`, no backend route.

**H94. Yes — manual payout is wired end-to-end and does move the creator's ledger balance. Authorization is SUPER_ADMIN + satisfied MFA, plus a mandatory `Idempotency-Key` header.**
Chain: `ManualPayoutPanel` form → `financeApi.recordManualPayout` → `POST /admin/finance/payouts/manual` → `AdminFinanceService.recordManualPayout` → locked wallet debit via `WalletLedgerService.post` + terminal `Payout` row + `admin_audit_log` entry.
Evidence: `src/admin/components/finance/ManualPayoutPanel.tsx:75` — `financeApi.recordManualPayout({creatorUserId, amount, bankReference, tdsAmount, note}, key)`; key held across retries (`:70`, `:89`).
Evidence: `src/admin/components/finance/FinanceConsole.tsx:728` — `<ManualPayoutPanel />` in the `manual-payout` tab (trigger `FinanceConsole.tsx:258`), so the control is genuinely reachable.
Evidence: `influora-api/src/main/java/com/influora/web/AdminFinanceController.java:91` — `@PostMapping("/payouts/manual")` with `@RequestHeader("Idempotency-Key")` required (not optional).
Evidence: `AdminFinanceService.java:161` — `adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN)`.
Evidence (ledger effect): `AdminFinanceService.java:198` `walletRepository.findByOwnerIdForUpdate(creatorUserId)` (row lock) → `:216` `ledgerService.post(...)` → `:228` `Payout.createManualPaid(...)` → `:242` `adminAuditLogService.record(..., "PAYOUT_RECORDED_MANUAL", ...)`.
Evidence (duplicate guard): `AdminFinanceService.java:189` — `findByBankReference` present → 409 `DUPLICATE_BANK_REFERENCE`.
The sibling `POST /admin/finance/payouts/{id}/retry` is also wired (`AdminFinanceController.java:72`, UI at `ReconciliationPanel.tsx` retry buttons) and is likewise SUPER_ADMIN-only — `AdminFinanceService.java:585`.

**H95. There is NO fee-history table. History is derived by replaying `admin_audit_log` rows for `entity_type = 'PLATFORM_FEE_CONFIG'`. It IS displayed in the UI.**
Evidence (no table): `influora-api/src/main/resources/db/migration/V41__platform_fee_config.sql:11` — `platform_fee_config` is a singleton row with `updated_at`/`updated_by` only; no history table. Grep for `fee_config_history`/`fee_history` across the migration dir returns nothing.
Evidence (derived): `influora-api/src/main/java/com/influora/service/admin/PlatformFeeAdminService.java:144` — `adminAuditLogService.getByEntity(principal, ENTITY_TYPE, PlatformFeeConfig.SINGLETON_ID)`, with `ENTITY_TYPE = "PLATFORM_FEE_CONFIG"` at `:64` and the write side at `:129`.
Evidence (reshaping): `PlatformFeeAdminService.java:241` — `toHistoryEntryDto(AuditLogEntryDto entry)` maps each audit entry's before/after JSON snapshot into a `PlatformFeeHistoryEntryDto`.
Evidence (backing table): `influora-api/src/main/resources/db/migration/V34__admin_tables.sql:48` — `admin_audit_log` with `old_value`/`new_value` JSON, `reason`, `admin_email`, `created_at`.
Evidence (endpoint): `influora-api/src/main/java/com/influora/web/PlatformFeeAdminController.java:91` — `GET /admin/finance/fee-config/history`.
Evidence (UI): `src/admin/components/finance/FeeControlPanel.tsx:631` — "Change History" section rendering `history.map(...)`, fed by `useFeeConfig()` at `FeeControlPanel.tsx:230`; the hook fetches it at `src/admin/hooks/useFeeConfig.ts:91`. Reachable at `/admin/finance` per `src/pages/admin-console.tsx:57`.
Consequence: history is only as complete as `admin_audit_log`. Any fee change not made through `PlatformFeeAdminService.update` (e.g. direct SQL) leaves no history row.

**H96. The queue reads `content_flags`. In production today the ONLY writer of new rows is `ReviewService` — user-submitted review flags. No AI classifier and no admin-initiated flag creation exists.**
Evidence (table): `influora-api/src/main/resources/db/migration/V34__admin_tables.sql:102` — `CREATE TABLE content_flags` with `flagged_by ENUM('AI','USER','ADMIN')` and `status ENUM('PENDING','REVIEWED','ACTIONED')` (ESCALATED added by `V20260715210000__content_flag_escalated_status.sql`).
Evidence (read path): `influora-api/src/main/java/com/influora/service/admin/AdminModerationService.java:140` — `contentFlagRepository.findPendingReviewQueue(pageRequest)`; JPQL at `influora-api/src/main/java/com/influora/repository/ContentFlagRepository.java:53` selects `status IN (PENDING, ESCALATED)`, ESCALATED sorted first.
Evidence (sole writer): `influora-api/src/main/java/com/influora/service/ReviewService.java:195` — `ContentFlag.userFlag(...)` then `contentFlagRepository.save(flag)`. Only `new ContentFlag` construction site in the codebase (`influora-api/src/main/java/com/influora/domain/entity/ContentFlag.java:83`, reached only via the single factory `userFlag` at `:76`).
Evidence (who triggers it): `influora-api/src/main/java/com/influora/web/BrandReviewController.java:51` and `influora-api/src/main/java/com/influora/web/CreatorReviewController.java:51` — `POST /{reviewId}/flag`.
Evidence (hard-coded source): `ContentFlag.java:89` — `flag.flaggedBy = ContentFlagSource.USER`. `AI` and `ADMIN` are declared enum values with zero producers.
Evidence (UI): `src/admin/hooks/useFlagQueue.ts:77` → `moderationApi.getContentFlags(filters.status)` → `GET /admin/moderation/flags` (`influora-api/src/main/java/com/influora/web/AdminModerationController.java:58`), rendered by `src/admin/components/moderation/FlagQueue.tsx` inside `src/admin/pages/ModerationPage.tsx`.
Practical read: the DELIVERABLE / PROFILE / MESSAGE content types in the schema can never appear — only `REVIEW` flags reach this queue.

**H97. Yes, wired end-to-end with a persisted outcome, and it settles the escrow inside the same transaction.**
Columns: `status` (`RESOLVED_BRAND` / `RESOLVED_CREATOR` / `RESOLVED_SPLIT`), `resolved_by_admin_id`, `resolution_notes`, `resolved_at`.
Evidence (schema): `influora-api/src/main/resources/db/migration/V45__disputes.sql:11` — the `status` ENUM plus `resolved_by_admin_id VARCHAR(26) NULL`, `resolution_notes TEXT NULL`, `resolved_at TIMESTAMP NULL`.
Evidence (write): `influora-api/src/main/java/com/influora/domain/entity/Dispute.java:110` — `resolve(resolution, adminId, notes)` sets all four.
Evidence (endpoint): `influora-api/src/main/java/com/influora/web/AdminDisputeController.java:73` — `POST /admin/disputes/{disputeId}/resolve`.
Evidence (money actually moves): `influora-api/src/main/java/com/influora/service/DisputeService.java:248` — `RESOLVED_CREATOR → escrowService.adminReleaseForDispute`, `RESOLVED_BRAND → adminRefundForDispute`, `RESOLVED_SPLIT → adminSplitForDispute`, each with a matching `ESCROW_RELEASE`/`ESCROW_REFUND` audit action.
Evidence (settlement invariant): `DisputeService.java:240` counts frozen holds before settling, and `:295` throws `DISPUTE_SETTLEMENT_EMPTY` (409) if fewer holds moved than were frozen — a dispute cannot be marked resolved on a movement that did not happen.
Evidence (authorization): `DisputeService.java:200` — SUPER_ADMIN + ADMIN, MFA-satisfied.
Evidence (UI): `src/admin/hooks/useDisputeResolve.ts:48` — `disputeApi.resolve(disputeId, resolution)`; control in `src/admin/components/disputes/DisputeList.tsx`'s row-click dialog, mounted by `src/admin/pages/DisputesPage.tsx:32`, routed at `src/pages/admin-console.tsx:61`.

**H98. Server only. The admin error-log console shows nothing from the client-side crash reporter — the two write paths do not converge.**
Path A (server, persisted): uncaught exception → `GlobalExceptionHandler.handleGeneric` → `ErrorLogService.record` → `error_log` table → `AdminErrorLogController` reads.
Evidence: `influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java:201` — `errorLogService.record(ErrorLogSeverity.ERROR, ex, requestURI, method, 500, currentUserId())`.
Evidence: `influora-api/src/main/java/com/influora/service/ErrorLogService.java:50` — `@Transactional(REQUIRES_NEW)`, builds a redacted+truncated `ErrorLog` and calls `errorLogRepository.save(entry)`.
Evidence (500s only): `GlobalExceptionHandler.java:194` — "Only this catch-all 500 path is captured; the 4xx/409 handlers above are expected/validation outcomes."
Path B (client, NOT persisted): React crash → `POST /api/v1/client-errors` → `ClientErrorController` → slf4j logfile only.
Evidence: `src/components/ErrorBoundary.tsx:69` and `src/lib/api.ts:5756` — `fetch(...'/client-errors'...)`.
Evidence: `influora-api/src/main/java/com/influora/web/ClientErrorController.java:80` — the class's only injected dependency is `ObjectMapper`; no repository field, no `save(...)` anywhere in the file.
Evidence: `ClientErrorController.java:134` — terminal action is `log.warn("[CLIENT_ERROR_REPORT] pathname={} ... ", ...)`, returning `202 Accepted` (`:97`).
Evidence (schema intent): `influora-api/src/main/resources/db/migration/V20260718170000__admin_error_log.sql:2` — "Backing store for handled **server** errors."
A browser crash is retrievable only by reading the VPS logs for `[CLIENT_ERROR_REPORT]`; it never appears at `/admin/errors`.
Secondary gap: `critical24h` is structurally always 0 — `influora-api/src/main/java/com/influora/service/admin/AdminErrorLogService.java:70` explains the only producer always writes `ErrorLogSeverity.ERROR`, so no `CRITICAL` row can exist.

**H99. Cached. Redis, 45-second TTL, set in `RedisCacheConfig`. The RBAC check is not cached.**
Evidence (TTL): `influora-api/src/main/java/com/influora/config/RedisCacheConfig.java:50` — `private static final Duration ADMIN_PULSE_TTL = Duration.ofSeconds(45);` applied at `:56` and registered at `:57` for cache name `adminPulse` (`:45`).
Evidence (annotation): `influora-api/src/main/java/com/influora/service/admin/AdminDashboardStatsCache.java:71` — `@Cacheable(cacheNames = RedisCacheConfig.ADMIN_PULSE_CACHE, key = "'pulse'")`; fixed literal key, so all admins share one entry.
Evidence (auth not cached): `influora-api/src/main/java/com/influora/service/admin/AdminDashboardService.java:100` — `requireRoleWithMfaSatisfied(...)` runs uncached on every call, then `return statsCache.pulseStats();`.
Evidence (endpoint): `influora-api/src/main/java/com/influora/web/AdminDashboardController.java:45` — `GET /admin/dashboard/pulse`.
Honesty notes in the same code: change-deltas are deliberately `null`, not `0.0` — `AdminDashboardStatsCache.java:99` ("A fabricated 0 would read as 'measured, and flat'"), rendering `--` in the UI; revenue is `SUM(wallet_transactions.amount) WHERE type = PLATFORM_FEE` per `AdminDashboardStatsCache.java:88`.

**H100. Six admin endpoints are live on the backend with no reachable UI control or display.**
(Verified by enumerating every `@*Mapping` in `Admin*Controller` + `ApprovalWorkflowController` + `AuditLogController`, then grepping every `src/admin/services/api-contracts.ts` function for call sites outside that file and outside tests.)

1. `POST /admin/auth/mfa/setup` — no UI. `influora-api/src/main/java/com/influora/web/AdminAuthController.java:113`; wrapper `src/admin/services/api-contracts.ts:142` has zero callers. Admins cannot enrol MFA from the console, yet every finance/moderation service calls `requireRoleWithMfaSatisfied`.
2. `POST /admin/auth/mfa/verify` — no UI. `AdminAuthController.java:119`; `api-contracts.ts:145`, zero callers.
3. `POST /admin/auth/refresh` — no UI. `AdminAuthController.java:82`; `api-contracts.ts:133`, zero callers. `src/admin/hooks/useAdminAuth.ts` calls only `getCurrentUser` (`:229`) and `logout` (`:271`) — the admin session is never refreshed.
4. `GET /admin/errors/{id}` — no UI. `influora-api/src/main/java/com/influora/web/AdminErrorLogController.java:49`; `api-contracts.ts:725`, zero callers. `src/admin/hooks/useErrorLog.ts:65` uses only `getRecent` + `getStats`, and `:104` `resolve` — no single-error detail view.
5. `PUT /admin/support/tickets/{id}` — no UI. `influora-api/src/main/java/com/influora/web/AdminSupportController.java:87`; `api-contracts.ts:570`, zero callers. `src/admin/components/support/TicketList.tsx` wires only `reply` (`:244`), `assign` (`:263`) and `escalate` (`:282`) — an admin cannot change a ticket's status or priority from the console.
6. `POST /admin/emails/send-bulk` — no UI, and deliberately inert. `influora-api/src/main/java/com/influora/web/AdminEmailController.java:75` — gated SUPER_ADMIN+MFA then returns `501 BULK_SEND_DISABLED`; `src/admin/pages/EmailQueuePage.tsx:220` confirms "Never wired to emailApi.sendBulk()". Intentional, not an oversight.

Partial gap (endpoint reachable, capability not): `GET /admin/campaigns` accepts `page`/`pageSize`/`status`/`search` filters (`influora-api/src/main/java/com/influora/web/AdminCampaignController.java:54`), but the UI only ever calls the unfiltered variant — `src/admin/hooks/useCampaignList.ts:60` `campaignApi.listAll()`; the filtered wrapper at `api-contracts.ts:322` has zero callers. Server-side campaign filtering and pagination are unusable from the console.

Not counted (declared client-side but no backend route exists — the inverse problem): `financeApi.resolveReconciliation` and `getTdsReport` (`api-contracts.ts:424`, `:429`), `escrowApi.release`/`hold`/`refund` (`:529`, `:534`, `:539`), `moderationApi.reviewAppeal` (`:640`), `dashboardApi.getMarketingSummary` (`:172`), `marketingApi.getAcquisition` (`:780`) — all `unavailable()` stubs.
