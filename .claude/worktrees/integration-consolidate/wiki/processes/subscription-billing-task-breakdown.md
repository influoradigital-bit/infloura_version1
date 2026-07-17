# Subscription Billing — Complete Task Breakdown

> **Arjun Kapoor, Engineering Lead** — 2026-07-14  
> Full work plan to close out the subscription billing feature (Tasks 11-20 from `SUBSCRIPTION-BILLING-PLAN.md` rev. 3)

---

## STATUS: Prep Batch Complete (15-18%), Functional Core Next (85%)

### ✅ COMPLETED (Prep Batch, 2026-07-14)
- **V54 migration + entities** — Plan/Subscription/Invoice/UsageCounter (Vikram → Kavya → fix → Meera, 969 tests ✅)
- **InvoicePdfService** — pure OpenPDF renderer, mirroring ContractPdfService pattern (complete, unwired)
- **AICreditResetJob** — monthly reset cron (`@Scheduled zone=UTC`, BRAND-scoped, calls `resetForNewCycle`) — **fully wired and functional** per Priya's verification
- **Frontend billing settings shell** — `/brand/settings/billing` routed, mock UI (Ananya → Kavya → Meera, tsc clean ✅)
- **Pricing copy** — Swapnil approved 7%/10% in tier table (Nisha → Tejas → Swapnil ✅), Ananya applying now

### 🔴 REMAINING (Functional Core, blocked on proper scoping per Priya's 2026-07-14 audit)

Priya's audit (§0.5 of the corrected plan) found the original plan mis-scoped 5 of 10 "already exists" items as reuse when they're actually net-new work:
1. **No per-plan fee override seam** — `PlatformFeeConfig` is a singleton; plan-aware pricing is from-scratch
2. **No seat gating** — `WorkspaceMember` exists but zero invite/add-member flow exists anywhere in the codebase
3. **Razorpay Subscriptions unbuilt** — SDK wraps only `orders.*`; subscription webhooks silently 200 today
4. **Admin billing console doesn't exist** — only fee-config CRUD; comp/override actions declared but unwired
5. **Stats data source wrong** — MeeraToolCall ledger missing the events the plan claimed

The prep batch didn't build any of these (correctly — they're scope unknowns). This breakdown re-estimates each as net-new, not reuse, per the audit findings.

---

## PHASE 1: REPOSITORIES + SERVICES (Foundation Layer)

### Task 17: Repository layer for new entities
**Owner:** Vikram  
**Blocked by:** Nothing (V54 entities already exist)  
**Estimate:** 2-3 hours

Create JPA repositories for the 4 new entities:
- `PlanRepository` (extends JpaRepository<Plan, String>)
- `SubscriptionRepository` (extends JpaRepository<Subscription, String>) — add `findByWorkspaceId`, `findByRazorpaySubscriptionId`
- `InvoiceRepository` (extends JpaRepository<Invoice, String>) — add `findBySubscriptionId`, `findByWorkspaceId`
- `UsageCounterRepository` (extends JpaRepository<UsageCounter, String>) — add `findByWorkspaceIdAndMetricAndPeriodStart`, custom upsert or save+merge logic for counter increments

**Deliverable:** 4 repository interfaces under `influora-api/src/main/java/com/influora/repository/`, following existing patterns (e.g. `BrandAiCreditRepository`, `CampaignRepository`).

---

### Task 18: Core billing services
**Owner:** Vikram  
**Blocked by:** Task 17 (repositories must exist first)  
**Estimate:** 4-6 hours

Build the service layer:
- **`PlanService`** — `getPlanByCode(PlanCode)`, `getActivePlans()` — simple reads, no complex logic yet
- **`SubscriptionService`** — `getByWorkspaceId(workspaceId)`, `updateStatus(...)`, `cancelAtPeriodEnd(...)` — orchestration layer, delegates to repo + Razorpay client
- **`UsageCounterService`** — `incrementUsage(workspaceId, metric, amount)`, `getUsageForCurrentPeriod(workspaceId, metric)` — upsert logic, period-start derived from subscription or default billing cycle
- **`InvoiceService`** — `generateInvoice(subscription, period)`, `markPaid(invoiceId)` — orchestrates InvoicePdfService + R2 upload + `Invoice.pdfR2Key` update

**Deliverable:** 4 service classes under `influora-api/src/main/java/com/influora/service/`, `@Service` + `@Transactional` where needed, following patterns from `CampaignService`, `EscrowService`.

**Note:** These services will initially have stubs for Razorpay calls (e.g. `subscriptionService.createSubscription(...)` throws `NOT_YET_IMPLEMENTED`) since the Razorpay client is Task 19. That's fine — wire the shape first, plumbing second.

---

## PHASE 2: RAZORPAY SUBSCRIPTIONS INTEGRATION (Net-New Build)

### Task 19: Razorpay Subscriptions client + checkout
**Owner:** Vikram  
**Blocked by:** Task 18 (SubscriptionService must exist to call this)  
**Estimate:** 6-8 hours (audit flagged this as net-new, not reuse)

**Audit note:** `RazorpayClient` today only wraps `orders.create`/`orders.fetch`. The SDK's `plans.*` and `subscriptions.*` endpoints have zero wrappers. This is from-scratch integration work, not a config tweak.

Build:
- **Extend `RazorpayClient.java`** (or create a new `RazorpaySubscriptionClient` if cleaner) with:
  - `createPlan(planCode, name, priceInr, billingCycle)` → Razorpay Plan ID (only for Pro; Free has no Razorpay plan)
  - `createSubscription(workspaceId, razorpayPlanId, customerDetails)` → Razorpay Subscription ID + hosted checkout URL
  - `fetchSubscription(razorpaySubscriptionId)` → subscription status (for polling/sync)
  - `cancelSubscription(razorpaySubscriptionId, cancelAtPeriodEnd)` → cancellation confirmation
- **Seed Free + Pro plans** — migration or service-layer init (V54 creates the table, but rows must be inserted): Free (code=FREE, priceInr=0, razorpayPlanId=null, feeBps=1000, ...), Pro (code=PRO, priceInr=499900, razorpayPlanId=[from Razorpay API], feeBps=700, ...). Razorpay Plan creation for Pro is one-time setup, can be manual or scripted.
- **Hosted checkout flow** — `SubscriptionService.initiateCheckout(workspaceId, planCode)` returns a Razorpay hosted URL; brand navigates there, completes payment, webhook fires `subscription.charged` to finalize.

**Deliverable:** Extended Razorpay client, seeded Plan rows, checkout initiation endpoint wired.

---

### Task 20: Subscription webhook handler + idempotency
**Owner:** Vikram  
**Blocked by:** Task 19 (subscription creation must exist to trigger webhooks)  
**Estimate:** 4-5 hours

**Audit note:** `RazorpayWebhookController` today handles `order.paid`/`payment.captured`/`payout.*` but **silently 200s `subscription.*` events** (falls to default arm, no action). This is net-new switch-case logic.

Build:
- **Extend `RazorpayWebhookController.java`** switch statement (lines ~59-62) with:
  - `subscription.charged` → `SubscriptionService.handleCharged(...)` → status=ACTIVE, set `currentPeriodStart`/`currentPeriodEnd`, idempotency key=`razorpaySubscriptionId`
  - `subscription.activated` → same as `charged` (first successful payment)
  - `subscription.pending` → status=PAST_DUE (dunning grace period)
  - `subscription.halted` → status=HALTED (payment failed after retries)
  - `subscription.cancelled` → status=CANCELLED
  - `invoice.paid` → `InvoiceService.markPaid(razorpayInvoiceId)`
- **Extend `WebhookEvent.parse()`** to extract subscription fields (currently only parses order/payment/payout JSON)
- **Reuse existing infra:** `WebhookSignatureVerifier` (HMAC-SHA256, already wired) + `IdempotencyService.executeOnce` (already used for order.paid)

**Deliverable:** Webhook handler routes all 6 subscription events to service layer, signature-verified + idempotent, no double-status-writes.

---

## PHASE 3: FEE + GATING LOGIC (Net-New Seams)

### Task 21: Per-plan fee override (net-new seam)
**Owner:** Vikram + Kabir (money-path, requires red-team)  
**Blocked by:** Task 18 (SubscriptionService must exist to resolve plan)  
**Estimate:** 5-7 hours

**Audit note:** No per-plan override seam exists today. `BrandCampaignFeeService.resolveBrandFeeBps()` and `PlatformFeeService.resolveCreatorFeeBps()` take **no** workspace/plan argument and read a single global singleton row (`PlatformFeeConfig id='default'`). This is from-scratch architectural plumbing, not a config update.

Build:
- **New method:** `SubscriptionService.getActivePlanForWorkspace(workspaceId)` → `Plan` (or null if Free/no subscription)
- **Modify `BrandCampaignFeeService.resolveBrandFeeBps()`** signature → add `workspaceId` parameter, call `subscriptionService.getActivePlanForWorkspace(workspaceId)`, return `plan.getFeeBps()` if plan exists + status=ACTIVE, else fall back to global `PlatformFeeConfig.getBrandFeeBps()` (the existing 1000 bps = 10%)
- **Wire the new signature** at all `chargeOnPublish` call sites (currently only `CampaignService.java:228`) — pass `workspaceId` through
- **Leave creator fee untouched** — `PlatformFeeService.resolveCreatorFeeBps()` stays as-is (singleton global row, no plan dependency per plan §1.3)
- **Pro tier AI-credit allotment** — extend `AICreditService.resetForNewCycle` to check `subscriptionService.getActivePlanForWorkspace(workspaceId).getAiMonthlyAllotment()` if a Pro plan exists, else fall back to the existing 100/150 loyalty logic. The reset job already exists (Task 6 ✅), this just wires the plan-aware resolver into it.

**Kabir gate required:** Any money-path change (fee calculation) is a mandatory red-team check per the pipeline. Kabir audits: can a brand spoof a Pro fee by manipulating any client input? Does the fee fallback logic handle null/expired subscriptions correctly? Is the global 10% singleton still the authority when no plan exists?

**Deliverable:** Fee resolution reads active plan first, falls back to global; AI-credit reset picks up Pro 400/mo when plan exists.

---

### Task 22: Plan-gate filter + @RequiresPlan annotation
**Owner:** Vikram  
**Blocked by:** Task 18 (SubscriptionService must exist)  
**Estimate:** 4-5 hours

Build:
- **`PlanGateFilter`** — servlet filter (runs after `JwtAuthenticationFilter`), reads workspace from `AuthPrincipal`, calls `subscriptionService.getActivePlanForWorkspace`, checks limits (seats, tracked creators, analytics views/month, export, templates)
- **`@RequiresPlan(feature = PlanFeature.EXPORT)`** — annotation + filter logic to return `402 UPGRADE_REQUIRED` or `403` when a Free-tier workspace hits a Pro-only feature
- **Analytics-specific gate** — `AnalyticsController` (4 endpoints: `/analytics/creators/{id}/metrics|scores|demographics|media`) + `CreatorAnalyticsController` (`/creator/analytics/me/*`) both get a `UsageCounterService.incrementUsage(workspaceId, CREATOR_ANALYTICS_VIEW, 1)` call at the top of each method, throw `402` if `getUsageForCurrentPeriod(...) >= plan.getCreatorAnalyticsMonthlyLimit()` (Free=1, Pro=null=unlimited)
- **Seat gate** — not in this task's scope yet, since the member-add flow doesn't exist (see Task 23)

**Deliverable:** Export/template pickers, analytics controllers gated with per-month counter + 402 upgrade prompt.

---

### Task 23: Seat invite/add-member flow (net-new, zero code exists)
**Owner:** Vikram  
**Blocked by:** Task 22 (seat-limit check must exist)  
**Estimate:** 6-8 hours

**Audit note:** Worse than expected — `WorkspaceMember` entity exists but **zero invite/add-member write path exists anywhere in the codebase**. `AuthService.java:148`'s `owner()` at signup is the **only** place a `WorkspaceMember` row is ever created. This is not "gate an existing flow" — it's "build the flow, then gate it."

Build from scratch:
- **`POST /workspace/members/invite`** — `WorkspaceController` new endpoint, takes `{email, role}`, checks active-member count vs `plan.getSeatLimit()` (Free=1, Pro=5), throws `402 UPGRADE_REQUIRED` if at limit, else creates `WorkspaceMemberInvite` row (new entity: inviteToken, email, role, invitedBy, expiresAt, acceptedAt)
- **`POST /workspace/members/accept`** — public endpoint (no auth required), takes `{inviteToken}`, validates token not expired/already accepted, creates `WorkspaceMember` row (active=true), updates invite.acceptedAt
- **`DELETE /workspace/members/{memberId}`** — deactivate (set active=false), decrements the seat count
- **New entities:** `WorkspaceMemberInvite` (needs V55 migration) + extend `WorkspaceMember` if needed (or reuse as-is if the existing `active` field is sufficient)

**Deliverable:** Full invite/accept/deactivate flow, seat-limit-gated at invite time, routed + tested.

---

## PHASE 4: LIFECYCLE + ADMIN

### Task 24: Dunning + renewal jobs + billing emails
**Owner:** Vikram + Meera (email templates)  
**Blocked by:** Task 20 (webhook handler must exist)  
**Estimate:** 5-6 hours (+ the reconciliation item below)

**Priya's Phase 3 sign-off flagged a real cross-cutting gap this task must close (2026-07-14):** seats (`WorkspaceMemberService`) and the brand fee (`BrandCampaignFeeService`) both derive LIVE from `SubscriptionService.getActivePlanForWorkspace` on every read — no staleness possible. **AI-credit allotment does NOT** — `BrandAiCredit.monthlyAllotment` is a *snapshotted* value written once (at signup=100, at first funded launch=150, or by Phase 3a's `AICreditResetJob`/`applyPlanAllotment` sync at the monthly reset). Between resets, if a workspace upgrades Free→Pro or downgrades Pro→Free, the snapshot doesn't move until the next monthly reset fires — a brand who just upgraded won't see their 400 credits until next month, and a brand who just downgraded keeps 400 until next month too. **This task's `SubscriptionRenewalResetJob` (or a new dedicated reconciliation step) must re-sync `BrandAiCredit.monthlyAllotment` to the CURRENT active plan on every subscription lifecycle transition** (webhook-driven activation/cancellation/plan-change), not just on the monthly cron boundary — otherwise this drift is a standing, silent bug every time someone changes tiers mid-cycle.

Build:
- **`SubscriptionDunningJob`** — `@Scheduled` (daily or similar), scans `Subscription` rows where `status=PAST_DUE` + `currentPeriodEnd < now() - gracePeriod`, transitions to `HALTED`, logs to `AdminAuditLog`
- **`SubscriptionRenewalResetJob`** — `@Scheduled` (daily), scans `Subscription` rows where `currentPeriodEnd < now()` + status=ACTIVE, resets `UsageCounter` rows for that workspace (tracked creators, analytics views, exports — counters tied to billing cycle), calls `AICreditService.resetForNewCycle(workspaceId)` (or the reset job already handles this monthly — check for overlap/dedup)
- **AI-credit reconciliation (new, per Priya's finding above)** — on subscription activate/cancel/plan-change (webhook-driven, not just cron), re-sync `BrandAiCredit.monthlyAllotment` to `getActivePlanForWorkspace(workspaceId).getAiMonthlyAllotment()` immediately, not deferred to the next monthly boundary.
- **Invoice PDF generation** — `InvoiceService.generateInvoice(...)` calls `InvoicePdfService.render(...)`, uploads bytes to R2 (`r2StorageService.putBytes(key, bytes, "application/pdf")`), sets `Invoice.pdfR2Key`, presigns download URL — mirrors `ContractService.generateAndDeliverContractPdf()` pattern
- **Billing emails** — `subscription.charged` webhook triggers "Invoice ready" email with PDF download link; `subscription.pending` triggers "Payment failed, retrying" email; `subscription.halted` triggers "Subscription halted" email. Email templates live in `influora-api/src/main/resources/email-templates/` per existing pattern.

**Deliverable:** Dunning → HALTED, renewal resets counters, AI-credit allotment reconciled live on every plan transition (not just monthly), invoice PDF stored + emailed, all lifecycle emails wired.

---

### Task 25: Admin billing console
**Owner:** Ananya (FE) + Vikram (BE)  
**Blocked by:** Task 18 (services must exist), Task 20 (subscriptions must be operational)  
**Estimate:** 8-10 hours (audit flagged: no console exists, only fee-config CRUD)

**Audit note:** Only `PlatformFeeAdminController` (fee-config CRUD) exists today. The broader finance API (`getRevenue`/`getEscrowSummary`/`getPayoutQueue`) is Phase-2/mock per its own javadoc. Comp/override audit actions (`BUDGET_OVERRIDE`/`TIER_ADJUST`/`PAYOUT_RETRY`/`WALLET`) are declared in the allow-list but have **zero call sites**. This is net-new UI + backend.

Build:
- **Backend:** `AdminBillingController` (new) at `/admin/billing`
  - `GET /admin/billing/subscriptions` → list all subscriptions (workspace name, plan, status, MRR)
  - `GET /admin/billing/metrics` → MRR, ARR, churn % (simple aggregations over `Subscription` + `Invoice` rows)
  - `POST /admin/billing/comp` → grant complimentary Pro (create a `Subscription` row with status=ACTIVE, razorpaySubscriptionId=null, mark as comp in a flag or audit log) — logs to `AdminAuditLog` with action=`TIER_ADJUST`
  - `POST /admin/billing/override` → manually adjust a workspace's fee or AI-credit allotment — logs to `AdminAuditLog` with action=`BUDGET_OVERRIDE`
- **Frontend:** new admin page `/admin/billing` (inside `AdminLayout`, registered in `App.tsx` admin routes)
  - Subscriptions table (filter by status, search by workspace)
  - MRR/ARR/churn cards
  - "Comp Pro" modal (workspace picker, reason, expiry date)
  - Override modal (workspace picker, fee % or AI-credit allotment override, reason)
- **Wire the dead audit actions** — `AdminAuditLogService.ALLOWED_ACTIONS` already declares `BUDGET_OVERRIDE`/`TIER_ADJUST`/`PAYOUT_RETRY`; this task adds the first real callers for the billing-specific ones

**Deliverable:** Admin billing console (FE + BE), MRR/ARR visible, comp/override flows functional + audit-logged.

---

## PHASE 5: FRONTEND WIRING (Live Data)

### Task 26: Frontend api.billing client group + live wiring
**Owner:** Ananya  
**Blocked by:** Task 18 (services/controllers must exist to wire against)  
**Estimate:** 4-5 hours

**Audit note:** Zero `api.billing` client group exists today. `brand-billing-settings.tsx` uses `mockCurrentPlan`/`mockInvoices`/`mockUsageCounters`. This is net-new API-client wiring, not just swapping a flag.

Build:
- **`src/lib/api.ts`** — add `billing` facade group:
  - `billing.getCurrentPlan(workspaceId)` → `GET /billing/plan` → Plan + Subscription
  - `billing.getInvoices(workspaceId)` → `GET /billing/invoices` → Invoice[]
  - `billing.getUsage(workspaceId)` → `GET /billing/usage` → UsageCounter[] (or summary DTO)
  - `billing.initiateCheckout(planCode)` → `POST /billing/checkout` → `{checkoutUrl}` (Razorpay hosted)
  - `billing.cancelSubscription()` → `POST /billing/cancel`
- **`src/pages/brand-billing-settings.tsx`** — replace all `mock*` constants with `useQuery` calls to the new `api.billing.*` methods, show loading/error states, wire "Upgrade to Pro" button to `billing.initiateCheckout('PRO')` → redirect to Razorpay hosted page
- **Backend:** `BillingController` (new) at `/billing/*` — delegates to `SubscriptionService`/`InvoiceService`/`UsageCounterService`, brand-workspace-scoped via `BrandContextService.requireBrandWorkspace(principal)`

**Deliverable:** `/brand/settings/billing` shows real plan/usage/invoices, "Upgrade to Pro" navigates to live Razorpay checkout.

---

### Task 27: Pricing page + gated UI updates
**Owner:** Ananya  
**Blocked by:** Task 9 (copy approved, Ananya applying now)  
**Estimate:** 3-4 hours

**Status:** Swapnil approved the copy 2026-07-14 (7%/10% in tier table, number-free hero/CTA). Ananya is applying it now across `pricing.tsx`, `landing.tsx`, `how-it-works-brands.tsx`, `public/llms.txt`. Once that's done, this task adds the gated-UI upgrade prompts.

Build:
- **Plan-gated upgrade CTAs** — at the exact moment a Free-tier brand hits a limit (6th tracked creator, 2nd analytics view this month, export button click, template picker), show an inline "Upgrade to Pro" prompt with the tier-table comparison + link to `/brand/settings/billing`
- **Usage meters in nav/dashboard** (optional polish) — small badge or progress indicator showing "3/5 tracked creators" so brands see the cap before hitting it, not just at the error moment

**Deliverable:** Pricing page copy live (7%/10% visible), upgrade CTAs at all limit-hit points, Pro value prop clear.

---

## PHASE 6: VERIFICATION + CLOSE

### Task 28: Unit tests for subscription core
**Owner:** Vikram  
**Blocked by:** Tasks 17-25 (services/controllers must exist)  
**Estimate:** 6-8 hours

**Audit note:** Meera flagged zero unit test coverage on the V54 prep batch (Plan/Subscription/Invoice/UsageCounter/InvoicePdfService/AICreditResetJob). That's tracked as fast-follow Task 16, but it applies to the whole feature. This task writes the real regression tests before calling it done.

Build:
- **Entity tests:** `PlanTest`, `SubscriptionTest`, `InvoiceTest`, `UsageCounterTest` — basic builder pattern, touch() mutators, validation
- **Service tests:** `SubscriptionServiceTest`, `UsageCounterServiceTest`, `InvoiceServiceTest` — mock repositories, test happy path + edge cases (expired subscription, null plan, counter overflow, invoice PDF generation)
- **Controller tests:** `BillingControllerTest`, `AdminBillingControllerTest` — mock services, test auth gates, 402 responses on Free-tier limit hits
- **Job tests:** `AICreditResetJobTest` (add to the existing job, covering the BRAND-scoping + Pro-allotment logic), `SubscriptionDunningJobTest`, `SubscriptionRenewalResetJobTest` — mock services, test per-workspace iteration + failure isolation
- **Razorpay client tests:** mock the SDK's underlying HTTP layer (or use Razorpay's test-mode keys if available), test `createPlan`/`createSubscription`/`cancelSubscription` happy path + error handling

**Target:** 80%+ line coverage on the new service/controller/job classes. The 4 bugs Kavya caught in the prep batch (timezone, workspace-scoping, PDF null-handling, crash logging) all would have been caught by tests — don't ship the functional core without them.

**Deliverable:** `mvn test` includes 50-80 new tests covering the subscription lifecycle end-to-end.

---

### Task 29: End-to-end verification (Kavya + Meera + Kabir)
**Owner:** Kavya → Meera → Kabir → Priya  
**Blocked by:** Tasks 17-27 (all code must exist)  
**Estimate:** 8-12 hours (full pipeline: QA → local verify → red-team → CTO sign-off)

**Kavya QA:**
- Standards review (TECH-STACK.md compliance, no `any`, WCAG AA, idempotency on all webhooks)
- Subscription lifecycle correctness (Free → Pro upgrade, Pro → cancelled downgrade, dunning → HALTED)
- Fee override correctness (Pro brand charged 7%, Free brand charged 10%, creator always 15%)
- Gating correctness (Free brand blocked at 6th tracked creator, 2nd analytics view, export/template buttons)
- Admin console correctness (comp grants work, MRR accurate, audit logs fire)

**Meera local verify:**
- `mvn -o test` — full suite green (new tests + existing 969)
- `npm run build` — frontend clean
- Smoke test: brand registers Free → sees limits → hits analytics cap (gets 402) → upgrades to Pro (via mock Razorpay or test-mode keys) → analytics unlimited → sees invoice in billing settings
- Smoke test: admin comps a Pro subscription → workspace sees Pro features

**Kabir red-team:**
- Money-path audit: can a Free brand bypass the 10% fee? Can a Pro brand's 7% fee be spoofed? Is the fallback to global `PlatformFeeConfig` safe when subscription is null/expired?
- Webhook security: signature verification still works on subscription events? Idempotency prevents double-status-writes?
- Gating bypasses: can a Free brand call Pro-only endpoints directly (e.g. `POST /export`) by skipping the FE? Is the plan-gate filter correctly wired at the servlet level, not just UI-side?
- Admin console: can a non-SUPER_ADMIN reach `/admin/billing`? Are comp/override audit logs unforgeable?

**Priya CTO sign-off:**
- Architecture review: does the per-plan fee seam integrate cleanly with the existing `PlatformFeeConfig` singleton? Does the subscription lifecycle match the plan's §3 release-gate contract (or at least not conflict with it for when DPF-5 ships)?
- Tech debt check: are the new entities/services/controllers following existing patterns (Ulid PKs, builder pattern, `@Transactional` boundaries, touch() mutators, `BrandContextService` tenant isolation)?
- Sign-off: merge to main (or flag blockers)

**Deliverable:** Subscription billing fully verified, no Critical/High findings, CTO-approved for production.

---

### Task 30: Archive + handoff (Arjun)
**Owner:** Arjun (me)  
**Blocked by:** Task 29 (Priya sign-off)  
**Estimate:** 1 hour

- Archive the full task thread to `wiki/processes/subscription-billing-complete-2026-07-14.md`
- Clear `SHARED_CONTEXT.md` active-task section (follows the company-wide protocol: bus holds active task only, done → archive)
- Update `SUBSCRIPTION-BILLING-PLAN.md` with final "CLOSED 2026-07-XX, all tasks complete" status
- n8n → WhatsApp notification to Swapnil: "Subscription billing feature complete — Free + Pro tiers live, Razorpay checkout wired, admin console operational."

**Deliverable:** Feature closed, Swapnil notified.

---

## SUMMARY: PHASE-GATED ROUTING

| Phase | Tasks | Estimate | Depends On | Owner(s) |
|---|---|---|---|---|
| **PHASE 1: Foundation** | 17-18 | 6-9 hours | Nothing (V54 done) | Vikram |
| **PHASE 2: Razorpay** | 19-20 | 10-13 hours | Phase 1 | Vikram |
| **PHASE 3: Gating** | 21-23 | 15-20 hours | Phase 1, partially Phase 2 | Vikram + Kabir (Task 21) |
| **PHASE 4: Lifecycle** | 24-25 | 13-16 hours | Phase 2 | Vikram + Ananya (Task 25 FE) |
| **PHASE 5: FE Wiring** | 26-27 | 7-9 hours | Phase 1-2 (API must exist) | Ananya |
| **PHASE 6: Verify** | 28-30 | 15-21 hours | All phases | Vikram (tests) + Kavya + Meera + Kabir + Priya + Arjun |
| **TOTAL** | 14 tasks | **66-88 hours** (~2-3 weeks, 1 FTE) | Prep batch ✅ | Full team |

**Critical path:** Phase 1 → Phase 2 → Phase 3 (Task 21 Kabir gate) → Phase 4 → Phase 5 → Phase 6. Tasks within a phase can run in parallel where dependencies allow (e.g. Task 19 Razorpay client + Task 20 webhook handler can overlap once SubscriptionService exists).

**Next action (Arjun):** Route Phase 1 (Tasks 17-18, repositories + services) to Vikram once Task 9 (Ananya's pricing copy) clears the pipeline — that's the last prep-batch item. Do not start Phase 2 (Razorpay) or Phase 3 (fee override, gating) until Phase 1's foundation exists and is Kavya/Meera-verified.

---

**Approved by:** Arjun Kapoor, Engineering Lead  
**Date:** 2026-07-14  
**References:** `SUBSCRIPTION-BILLING-PLAN.md` (rev. 3, audit-corrected), Priya's 2026-07-14 verification (`project_influora_verification_gap` memory entry)
