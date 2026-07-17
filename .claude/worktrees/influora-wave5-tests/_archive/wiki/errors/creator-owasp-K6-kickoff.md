# Creator Platform — OWASP K6 Kickoff Audit (Kabir)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~22:30 IST)  
**Task:** K-6 · P0 (Week 4) · Final OWASP audit **kickoff** (Tick #30)  
**Verdict:** ⚠️ **PASS WITH FINDINGS** — **0 Critical, 0 High, 5 Medium, 12 Low**  
**Scope:** Spec 12 + `CREATOR_EXEC_PLAN_FINAL.md` Week 4 security gate checklist; adversarial spot-checks on Week 4 shipped slices (#26 fee, #29 reviews, #34 dispute, #35 analytics) + discovery `/creators`  
**Reference:** `wiki/tech/creator/12_CREATOR_SECURITY_SPEC.md`; prior slice audits in `wiki/errors/creator-*-kabir-redteam.md`  
**Note:** This is **kickoff slice 1 of N**. Security matrix advances **30% → ~48%**. Full audit spans auth deep-dive, OAuth PKCE matrix, PII-at-rest verification, file-upload malware gate, session lifecycle, and frontend token storage — scheduled for K6 cycles 2–4.

---

## Executive Summary

Week 4 **money paths and access-control** spot-checks **pass** at the adversarial bar. Prior **H-T34-1** (dispute-freeze vs concurrent release) is **CLOSED** after Vikram hotfix + K1b re-spot. Task #26 escrow-release fee deduction, #35 creator-self analytics IDOR surface, and discovery `/creators` tenancy gating are **structurally sound**.

**No new Critical or High findings** in this kickoff. **Production deploy is not blocked** by this kickoff alone — consistent with Priya's conditional sign-offs on #26–#35.

**Systemic MEDIUM debt (rate limiting)** is the dominant cross-cutting risk: `AuthRateLimitFilter` covers auth, webhooks, deliverable writes, and contract sign — but **does not** throttle reviews, disputes, discovery search/invite, campaign apply, or HTTP-layer withdrawal. This pattern appears in **five independent slice audits** (T7, T19, T29, T34, T25 partial). Consolidated as **M-K6-1**; must land in pre-prod hardening sprint before creator platform GA.

**Full OWASP pass remains incomplete** — checklist items for PII encryption verification, common-password denylist, OTP hash-at-rest audit, virus scan on upload, and cluster-global rate limits are **NOT STARTED** (backlog below).

---

## OWASP Top 10 × Creator Surfaces — Audit Checklist

Mapped to OWASP Top 10 (2021) and `12_CREATOR_SECURITY_SPEC.md` §8 red-team list.

| OWASP | Creator surface | Kickoff status | Evidence / gap |
|-------|-----------------|----------------|----------------|
| **A01 Broken Access Control** | AuthZ on wallet, escrow, deals, reviews, disputes, analytics `/me`, discovery | ✅ **Spot-check PASS** | JWT → context service → join-scoped repos; creator `/me` eliminates foreign id; discovery `requireBrandWorkspace` + `discoverable()` filter |
| **A02 Cryptographic Failures** | OAuth tokens, JWT, passwords, PII at rest | ⚠️ **PARTIAL** | `BCryptPasswordEncoder(12)` ✅; `MetaOAuthToken.encryptedAccessToken` AES-256-GCM ✅; email/phone/bank PII encryption **not verified** this cycle |
| **A03 Injection** | JPA specs, date params, search `q` | ✅ **Spot-check PASS** | Parameterized JPA; `Instant.parse`; `nameSearch` LIKE with bound pattern — no concatenated SQL |
| **A04 Insecure Design** | Escrow fee, dispute freeze, idempotency | ✅ **PASS** (money paths) | Ledger idempotency keys server-derived; H-T34-1 remediated; fee-before-credit ordering |
| **A05 Security Misconfiguration** | `SecurityConfig`, CORS, CSP headers | ⚠️ **PARTIAL** | HSTS, frame deny, CSP on API ✅; CORS config **not adversarially tested**; CSRF disabled by design (Bearer-only) — documented |
| **A06 Vulnerable Components** | Dependency CVE scan | ⬜ **NOT STARTED** | Meera/CI dependency audit backlog |
| **A07 Auth Failures** | OTP, login brute-force, session | ⚠️ **PARTIAL** | `AuthRateLimitFilter` on `/auth/**` ✅; OTP `SecureRandom` ✅; refresh rotation / session revoke matrix **not fully probed** |
| **A08 Software/Data Integrity** | Webhooks HMAC, admin fee config | ✅ **PASS** (spot) | Razorpay/Shopify/WooCommerce signature gates; `PlatformFeeConfig` optimistic lock on admin PUT |
| **A09 Logging/Monitoring** | Audit trail on money mutations | ⚠️ **PARTIAL** | Wallet ledger traceability ✅; security event alerting **not verified** |
| **A10 SSRF** | File URL fields, webhook callbacks | ⚠️ **PARTIAL** | Deliverable upload uses server-composed R2 keys ✅; outbound URL fetch paths **not fully mapped** |

### Spec 12 Red-Team Checklist — Kickoff Coverage

| Area | Items in spec §8 | Kickoff |
|------|------------------|---------|
| Authentication | 5 probes | 2/5 (rate limit ✅, JWT manipulation spot ✅; OTP enum, reset reuse, session hijack — **cycle 2**) |
| Authorization | 4 probes | 4/4 spot ✅ (IDOR wallet/contracts/profile, role escalation on Week 4 slices) |
| OAuth | 4 probes | 1/4 (token encryption at rest ✅; CSRF/state/PKCE refresh — **cycle 2**) |
| Payments | 4 probes | 4/4 spot ✅ (over-balance, double-spend, bank-change bypass, amount manipulation — prior T10/T18/T26/T34) |
| Content | 4 probes | 2/4 (MIME sniff ✅, path traversal ✅; malware scan, SSRF URL — **cycle 3**) |

---

## Week 4 Slice Spot-Checks (Adversarial)

### #26 Platform fee at escrow release — ✅ PASS (prior K2)

| Probe | Result |
|-------|--------|
| Double fee on concurrent `release()` | **CLOSED** — ledger `release-fee:{holdId}` idempotency |
| Client-controlled payee/amount | **N/A** — gross from `hold.getAmount()`, payee from collaboration |
| Cross-workspace milestone IDOR | **404** uniform |
| Direct `Wallet.balance` mutation | **CLOSED** — `WalletLedgerService.post()` only |

**Carry-forward:** L-K2-T26-1…6 (LOW). Full report: `wiki/errors/creator-platform-fee-T26-kabir-redteam.md`.

---

### #29 Collaboration reviews — ✅ PASS WITH FINDINGS (prior K1)

| Probe | Result |
|-------|--------|
| IDOR create/flag | **CLOSED** — `findByIdAndCreatorId` / `findByIdAndWorkspaceId` |
| COMPLETED-only gate | **CLOSED** — strict equality |
| Double-review race | **CLOSED** — DB unique + DIVE handler |
| XSS text/flag reason | **CLOSED** — `TextSanitizer` |
| Rate limit review/flag | **OPEN** — **M-T29-1** |
| Duplicate flag spam | **OPEN** — **M-T29-2** |

Full report: `wiki/errors/creator-reviews-T29-kabir-redteam.md`.

---

### #34 Disputes + escrow freeze — ✅ PASS (H-T34-1 CLOSED)

| Probe | Result |
|-------|--------|
| Freeze vs concurrent release | **CLOSED** — freeze-before-save + `ESCROW_BLOCKED_BY_DISPUTE` + `PESSIMISTIC_WRITE` |
| IDOR open dispute | **CLOSED** — party-scoped collaboration resolve |
| Admin resolve gating | **CLOSED** — `ROLE_ADMIN` filter + MFA + tier allow-list |
| v1 resolve money movement | **CLOSED** — status-only stub |
| One-active-dispute TOCTOU | **OPEN** — **M-T34-1** (no DB partial unique) |
| Dispute-open rate limit | **OPEN** — **M-T34-2** |

Full report: `wiki/errors/creator-dispute-T34-kabir-redteam.md` (K1 + K1b re-spot).

---

### #35 Creator-self analytics — ✅ PASS (prior K3)

| Probe | Result |
|-------|--------|
| Cross-creator IDOR | **CLOSED** — no client `creatorId`; `/me` only |
| JWT `sub` spoof | **CLOSED** — HMAC verify in filter |
| Date-range SQL injection | **CLOSED** — typed `Instant` binding |
| Error oracle on foreign ids | **CLOSED** — no enumerable foreign surface |

**Carry-forward:** L-T35-1…5 (LOW). Full report: `wiki/errors/creator-analytics-T35-kabir-redteam.md`.

---

### Discovery `/creators` — ✅ PASS WITH FINDINGS (new this kickoff)

**Reviewed:** `CreatorController.java`, `CreatorDiscoveryService.java`, `CreatorProfileSpecifications.java`, `CreatorMapper.java`, `Collaboration.java` (invite sanitization), `BrandContextService.java`

| Probe | Result |
|-------|--------|
| Creator JWT on brand discovery routes | **BLOCKED** — `requireBrandWorkspace` → `403 WRONG_USER_TYPE` |
| Non-discoverable profile enumeration via `GET /{id}` | **BLOCKED** — `requireDiscoverableProfile` (`findByIdAndDiscoverableTrue` + userId alias only when discoverable) |
| Search leaks non-discoverable creators | **BLOCKED** — `CreatorProfileSpecifications.combine()` always ANDs `discoverable()` |
| Campaign IDOR on invite | **BLOCKED** — `findByIdAndWorkspaceId` |
| Invite message XSS | **CLOSED** — `Collaboration.invite` → `TextSanitizer.sanitizePlainText` |
| Concurrent invite TOCTOU | **CLOSED** — `UNIQUE(campaign_id, creator_id)` + DIVE → `409` |
| `sortBy` injection | **BLOCKED** — whitelist switch (`followers`, `engagement`, `rate`, `price_low`, `price_high`) |
| Pagination DoS | **Mitigated** — `limit` capped at 100 |
| Discovery search rate limit (spec §6.1: 60/min) | **OPEN** — **M-K6-5** |
| Discovery invite rate limit | **OPEN** — absorbed into **M-K6-1** |

**LOW:** `CreatorResponse` exposes internal `userId` (ULID) to brands — correlation aid, not direct PII (**L-K6-1**).

---

## Money-Path Consolidated Re-Attack (Kickoff)

| Path | AuthZ | Amount source | Idempotency | Concurrency | HTTP rate limit |
|------|-------|---------------|-------------|-------------|-----------------|
| Escrow fund | Brand workspace + role | Server milestone | `uq_escrow_idem` + ledger | Ledger keys | ⬜ not in filter |
| Escrow release + #26 fee | Brand OWNER/ADMIN | `hold.getAmount()` | `release-fee:` + `release:` keys | Pessimistic wallet lock + ledger | ⬜ |
| Withdraw (#18) | Creator JWT `sub` only | Validated min/max + balance | Header or server key | `findByOwnerIdForUpdate` | ⬜ **M-K6-4** (spec §6.1: 5/hr) |
| Dispute freeze | Party-scoped open | N/A (status transition) | N/A | `PESSIMISTIC_WRITE` on holds | ⬜ **M-T34-2** |

**Money-path integrity: PASS.** HTTP-layer abuse throttles remain the gap.

---

## Findings Register (Numbered)

### Blockers

**None.** (H-T34-1 closed in prior cycle.)

### Medium — pre-prod hardening required

| ID | Finding | Surfaces | Action |
|----|---------|----------|--------|
| **M-K6-1** | **Systemic authenticated write-path rate-limit gaps** — `AuthRateLimitFilter.bucketFor()` returns `null` for: `POST /creator/reviews`, `POST /brand/reviews`, `POST /*/reviews/{id}/flag`, `POST /deals/{id}/disputes`, `POST /creators/{id}/invite`, `POST /creator/campaigns/{id}/apply` | Reviews, disputes, discovery, campaigns | **Vikram** — extend Task #25 bucket pattern; key by JWT `sub` |
| **M-K6-2** | **In-memory per-instance rate limiter** — horizontally scaled deploy multiplies effective quota; documented debt, not cluster-global | All throttled paths | **Vikram/Meera** — Redis/bucket4j or edge WAF before GA |
| **M-K6-3** | **Review duplicate-flag moderation DoS** (carry **M-T29-2**) — unlimited `content_flags` rows per review; no `flagged_by_user_id` | Reviews → admin FlagQueue | **Vikram** — unique constraint + user id column |
| **M-K6-4** | **Withdrawal HTTP rate limit missing** — business rule caps 3/day in `WalletService` but spec §6.1 requires 5/hour at API layer; no `AuthRateLimitFilter` bucket on `POST /wallet/withdraw` | Wallet | **Vikram** — `"creator-withdraw"` bucket (5/hr per `sub`) |
| **M-K6-5** | **Discovery search unthrottled** — spec §6.1: 60/min on search/discovery; `GET /creators` not in filter | Discovery | **Vikram** — `"discovery-search"` bucket (60/min per brand `sub`) |

### Low — next sprint / audit cycles

| ID | Finding | Action |
|----|---------|--------|
| L-K6-1 | `CreatorResponse.userId` exposed to brand discovery API | Optional: omit or replace with opaque public id |
| L-K6-2 | One-active-dispute TOCTOU — no DB partial unique (carry **M-T34-1**) | Vikram migration |
| L-K6-3 | Review self-flag allowed (carry **L-T29-1**) | Optional opposition check |
| L-K6-4 | Admin `contentType` union omits `'REVIEW'` (carry **L-T29-2**) | Ananya admin cycle |
| L-K6-5 | Platform fee `effectiveAt` not honored at release (carry **L-K2-T26-5**) | V2 fee versioning |
| L-K6-6 | `AmountDerivationService` yaml fee vs DB singleton drift (carry **L-K2-T26-6**) | Config unification |
| L-K6-7 | Analytics unbounded date-range self-DoS (carry **L-T35-2**) | Shared max span |
| L-K6-8 | `getCreator*ForProfile` public seam without arch-unit guard (carry **L-T35-3**) | Arch-unit test |
| L-K6-9 | Deliverable R2 permanent public URLs (carry **M-19-4**, upload slice) | Signed URL migration |
| L-K6-10 | `file.getBytes()` heap buffer up to 1GB (carry **M-19-3**) | Streaming upload |
| L-K6-11 | Discovery / analytics / wallet — no negative-auth controller integration tests | Kv3 E2E + unit follow-up |
| L-K6-12 | Full audit cycles 2–4 backlog: PII-at-rest probe, OTP hash verify, common-password list, virus scan, session revoke matrix, dependency CVE scan | Kabir K6-2/3/4 |

---

## Top 3 Risks (Kickoff Priority)

1. **Systemic rate-limit blind spots (M-K6-1)** — Authenticated abuse can flood moderation queues (review flags), spam disputes across owned deals, and hammer discovery/apply endpoints without throttle. Highest operational impact; same class as pre-ship M-21-1 debt that blocked prod until Task #25.

2. **Money-path HTTP abuse surface (M-K6-4 + escrow paths)** — Ledger integrity is sound, but withdrawal and escrow mutations lack HTTP-layer throttles per spec §6.1. A compromised JWT could script rapid withdrawal attempts (bounded by 3/day business rule but not 5/hr API rule) or stress ledger under load.

3. **Audit coverage gap (~52% remaining)** — PII encryption, OTP storage hash, malware scan, session lifecycle, and frontend `localStorage` token XSS mitigation (CSP on SPA host) are **unverified**. Mock/demo mode on frontend (`isApiLive()` fail-closed in prod ✅) but full auth matrix untested — risk of unknown HIGH in cycle 2.

---

## Files Reviewed (Kickoff)

**Week 4 slices (re-spot + prior reports):**
- `influora-api/src/main/java/com/influora/service/PlatformFeeService.java`
- `influora-api/src/main/java/com/influora/service/EscrowService.java`
- `influora-api/src/main/java/com/influora/service/ReviewService.java`
- `influora-api/src/main/java/com/influora/service/DisputeService.java`
- `influora-api/src/main/java/com/influora/service/CreatorAnalyticsService.java`
- `influora-api/src/main/java/com/influora/web/CreatorAnalyticsController.java`
- `influora-api/src/main/java/com/influora/web/CreatorReviewController.java`
- `influora-api/src/main/java/com/influora/web/BrandReviewController.java`
- `influora-api/src/main/java/com/influora/web/DealController.java`
- `influora-api/src/main/java/com/influora/web/AdminDisputeController.java`
- `influora-api/src/main/java/com/influora/web/CreatorPlatformFeeController.java`
- `influora-api/src/main/java/com/influora/web/PlatformFeeAdminController.java`

**Discovery (new):**
- `influora-api/src/main/java/com/influora/web/CreatorController.java`
- `influora-api/src/main/java/com/influora/service/CreatorDiscoveryService.java`
- `influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java`
- `influora-api/src/main/java/com/influora/service/CreatorMapper.java`
- `influora-api/src/main/java/com/influora/domain/entity/Collaboration.java`

**Cross-cutting:**
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java`
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java`
- `influora-api/src/main/java/com/influora/security/JwtAuthenticationFilter.java`
- `influora-api/src/main/java/com/influora/service/CreatorContextService.java`
- `influora-api/src/main/java/com/influora/service/BrandContextService.java`
- `influora-api/src/main/java/com/influora/service/WalletService.java`
- `influora-api/src/main/java/com/influora/web/WalletController.java`
- `influora-api/src/main/java/com/influora/domain/entity/MetaOAuthToken.java`
- `src/lib/api.ts` (`isApiLive()` prod fail-closed check)

**Prior slice reports (incorporated):**
- `wiki/errors/creator-platform-fee-T26-kabir-redteam.md`
- `wiki/errors/creator-reviews-T29-kabir-redteam.md`
- `wiki/errors/creator-dispute-T34-kabir-redteam.md`
- `wiki/errors/creator-analytics-T35-kabir-redteam.md`
- `wiki/errors/creator-wallet-T18-kabir-redteam.md`
- `wiki/errors/creator-deliverable-upload-T19-kabir-redteam.md`
- `wiki/errors/creator-campaign-apply-T7-kabir-redteam.md`

---

## Pipeline Routing

```
K6 Kickoff (this doc) ──► PASS WITH FINDINGS (0C/0H/5M/12L)
        │
        ├──► Vikram: M-K6-1 rate-limit extension sprint (reviews + disputes + discovery + apply + withdraw)
        ├──► Kavya Kv3: E2E hostile matrix per KAVYA_QA_TEST_PLAN §18–§22
        ├──► Kabir K6-2: Auth + OTP + session deep-dive (cycle 2)
        ├──► Kabir K6-3: PII-at-rest + upload malware + OAuth PKCE (cycle 3)
        └──► Priya: Final security sign-off BLOCKED until 0 Critical/0 High AND Medium sprint closed
```

**Kickoff does not block** Week 4 integration or conditional Priya sign-offs already issued. **Creator platform GA** requires M-K6-1 through M-K6-5 closed + K6 cycles 2–4 complete.

**Security matrix:** 30% → **~48%** (OWASP checklist partially exercised; money paths + Week 4 authZ spot-checked).

---

*Kabir Singh, Offensive Security / Red-Team Lead — Sage Digital*
