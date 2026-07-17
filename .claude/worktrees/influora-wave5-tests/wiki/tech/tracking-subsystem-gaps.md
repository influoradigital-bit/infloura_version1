# Tracking Subsystem — Identified Gaps & Architectural Issues

> **Discovered by:** Arjun (Engineering Lead) — full trace of click/conversion/coupon attribution flow  
> **Date:** 2026-07-13  
> **Status:** 🔴 FINDINGS — escalated to Priya (CTO) for architectural ruling  
> **Related:** `service/tracking/*`, `integration/tracking/webhook/*`, `job/AffiliateEarningReconciliationJob.java`

---

## 0. Context

CEO question: "if creator add that campaign reel or post URL so we can track that for that campaign correct?"

The answer split into **two independent tracking systems**:
1. **Engagement tracking** (reach, likes, views) → via the posted Instagram/YouTube URL, pulls from platform API (DPF-6) ✅
2. **Click/sales tracking** (clicks, conversions, revenue) → via trackable redirect links + coupon codes, signed webhooks from brand's store

This trace covered the **second system** — the money-bearing attribution subsystem (clicks → conversions → coupon redemptions → creator commissions). Found **5 gaps**, with one being a **silent payment failure** that violates the documented contract.

---

## 1. The two attribution paths (independent, don't join)

| Path | Creator uses | Customer action | Result | Creator paid? |
|------|-------------|-----------------|--------|---------------|
| **UTM link** | Trackable redirect URL | Clicks → later buys | Counts clicks + revenue on link | ❌ NO — reporting counter only |
| **Coupon code** | Unique promo code | Enters code at checkout | Records redemption → **creates earning** | ✅ YES — 10% commission (hardcoded) |

**Finding:** UTM conversions never pay creators. Only coupons do. The two paths are siloed — a click+conversion attributed to a link produces zero earnings.

---

## 2. Identified gaps (5 total, 1 CRITICAL)

### 🔴 GAP #3 — CRITICAL: Earnings aren't created at redemption time (silent payment failure)

**The documented contract** ([AffiliateEarningsService.java:45](influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java#L45), [AffiliateEarning.java entity javadoc](influora-api/src/main/java/com/influora/domain/entity/AffiliateEarning.java)):
> "`recordEarning` is called by `RedemptionService#doRedeem` **immediately after** the redemption row is saved."

**The actual code:**  
`RedemptionService` ([service/tracking/RedemptionService.java](influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java)) neither injects nor calls `AffiliateEarningsService` anywhere. Grep-confirmed: **zero references** in the entire file. `performRedemption` ([L265](influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java#L265)) ends at the audit log — no earning is created.

**The ONLY production path:**  
[AffiliateEarningReconciliationJob.java:103](influora-api/src/main/java/com/influora/job/AffiliateEarningReconciliationJob.java#L103) — an **hourly cron** (`:81`) that sweeps redemptions with no matching earning older than a **30-minute grace window** (`:63`, `findOrphanedWithoutAffiliateEarning`). The cron's javadoc claims it's a "belt-and-suspenders floor" for a synchronous path that **does not exist** — so in practice it **is** the primary path.

**Impact:**
- Every creator commission is **delayed up to ~1.5 hours** (hourly cron + 30-min grace).
- **If the cron is disabled** (job off, exception in the sweep), **no earnings are ever created** — creators redeem coupons but never get paid.
- This is **not** a race-condition edge case — it's the documented behavior being violated 100% of the time.

**Severity:** **HIGH** — silent payment failure. The documented contract says "immediate," the code does "maybe in 1.5 hours if a cron runs."

---

### ⚠️ GAP #1 — Trackable link not enforced (clicks silently not counted)

**The pattern:**  
[CampaignLinkService.java:186](influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java#L186) builds `fullTrackingUrl` as the brand's site + UTM params: `brandsite.com?utm_source=instagram&utm_medium=influencer&...`. This URL is stored and returned to the creator.

**The problem:**  
The stored `fullTrackingUrl` points **directly** at the brand's site, **not** the `/track/click/{id}` redirect. So unless the creator posts the **redirect** URL specifically, clicks are **never counted**. Nothing forces the tracking indirection — the creator can copy the direct link and all tracking is silently bypassed.

**Note in code:**  
[CampaignLinkService.java:22-23](influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java#L22) javadoc: "no shortener integration; `short_url` always null."

**Severity:** **MEDIUM** — tracking silently doesn't happen if creator posts the wrong URL.

---

### ⚠️ GAP #2 — UTM conversions never create earnings

**The flow:**  
[ConversionTrackingService.java:155](influora-api/src/main/java/com/influora/service/tracking/ConversionTrackingService.java#L155) — a sale attributed to a UTM link bumps `UtmCampaign.revenueAttributed` but produces **no `AffiliateEarning`**. `AffiliateEarningsService` only consumes coupon redemptions ([recordEarning signature](influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java#L282): takes `CouponRedemption`, not `UtmCampaign`).

**Impact:**  
Link-attributed revenue is a reporting counter only. A creator driving $10k in sales via a trackable link earns **$0**. Only coupon redemptions pay.

**Note:** This may be intentional (links are for attribution reporting, coupons for commission), but it's worth confirming with Priya/Swapnil whether link-conversions *should* pay or not. If yes, it's a missing feature; if no, it's a documented gap.

**Severity:** **MEDIUM** — creators may expect link-driven sales to pay.

---

### ⚠️ GAP #4 — No click→purchase stitching

**The mechanism:**  
A click records to `UtmCampaign.clickCount` ([CampaignLinkService.java:217](influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java#L217)). A later conversion arrives via a **separate webhook** ([ConversionWebhookController.java POST /webhooks/conversion](influora-api/src/main/java/com/influora/web/ConversionWebhookController.java)) that must name the `utmCampaignId` explicitly.

**What's missing:**  
No click ID, no cookie, no UTM correlation is persisted or matched. Attribution relies **100%** on the brand's backend echoing the correct `utmCampaignId`/coupon `code` in the webhook.

**Impact:**  
If the brand's integration is wrong (echoes the wrong link ID, or none), the conversion is never attributed. No server-side stitching fallback.

**Severity:** **LOW** — depends on brand integration correctness; we document the webhook contract.

---

### ℹ️ GAP #5 — Documented simplifications (minor)

- **Commission rate:** hardcoded `DEFAULT_COMMISSION_RATE = 0.10` (10%, [AffiliateEarningsService.java:102](influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java#L102)) — placeholder TODO.
- **Currency:** hardcoded `"INR"` ([L379](influora-api/src/main/java/com/influora/service/AffiliateEarningsService.java#L379)).
- **Unique-visitor dedup:** naive counter ([CampaignLinkService.java:209](influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java#L209)) — every `visitorId` counts as unique, no actual dedup.
- **URL shortener:** never wired; `short_url` always null ([L22](influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java#L22)).
- **Per-user coupon limits:** not implemented (no `max_uses_per_user` column, [RedemptionService.java:277](influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java#L277)).
- **Per-coupon revenue rollup:** not implemented (no column; raw amounts live on `CouponRedemption`).

**Severity:** **LOW** — documented placeholders.

---

## 3. What works well (the solid parts)

✅ **Webhook security:** HMAC-SHA256 signed with per-brand secrets (AES-encrypted at rest, shown once), fail-closed, constant-time compare ([ConversionWebhookSignatureVerifier.java:43](influora-api/src/main/java/com/influora/integration/tracking/webhook/ConversionWebhookSignatureVerifier.java#L43)).  
✅ **Idempotency:** both webhooks require an idempotency key; duplicate deliveries are no-ops (conversion via `IdempotencyService.executeOnce`, redemption via `UNIQUE(idempotency_key)` + `executeOnce` race guard).  
✅ **Workspace isolation:** coupon redemption is workspace-scoped ([RedemptionService.java:197](influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java#L197)); cross-workspace rejects with `INVALID_CODE` 404.  
✅ **Discount calculation:** supports `"percentage"` (round half-up 2dp) and `"fixed"` (clamped to orderAmount) ([L370](influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java#L370)).

---

## 4. Architectural questions for Priya

1. **GAP #3 (earnings-cron):** Is the synchronous `RedemptionService → recordEarning` call the correct design, and the current code is a bug? Or is the hourly reconciliation the intended design, and the javadoc is stale? If synchronous is correct, where should the call live?

2. **GAP #2 (UTM no earnings):** Should link-attributed conversions create earnings, or are they intentionally reporting-only? If reporting-only, should we document that clearly in the UI so creators know coupons are the only commission path?

3. **GAP #1 (link not enforced):** Should we (a) auto-wrap the link in a shortener that forces the redirect, (b) add a UI warning "post this exact URL to track clicks," or (c) leave as-is (creator responsibility)?

4. **Broader question:** Is the two-path split (UTM links for reporting, coupons for earnings) the final design, or should they converge (e.g. link conversions also pay)?

---

## 5. Proposed fixes (pending Priya ruling)

**For GAP #3 (CRITICAL — earnings-cron):**  
- **Option A (synchronous, doc-compliant):** Add `AffiliateEarningsService` injection to `RedemptionService`, call `recordEarning(redemption)` at the end of `performRedemption` (after the audit log, before returning). Keep the cron as a true belt-and-suspenders sweep for any missed (unlikely) case.
- **Option B (async-first, honest doc):** Update the javadoc to say "earnings are created by an hourly reconciliation job" (make the doc match the code). Add monitoring so we know if the cron is off.
- **Recommendation:** **Option A** — the synchronous path is the correct money-path contract (creators expect immediate commission visibility, brands expect instant accrual). The cron becomes the safety net it was documented to be.

**For GAP #2 (UTM no earnings):**  
- **If link conversions should pay:** add a `recordEarningFromConversion(UtmCampaign, orderAmount)` path in `AffiliateEarningsService`, called from `ConversionTrackingService.doRecordConversion`. Resolve creator via `utm.creatorProfileId`.
- **If reporting-only is intentional:** add a UI label "Links track attribution; coupons pay commission" so creators know.

**For GAP #1 (link not enforced):**  
- **Short-term:** UI copy: "Share this exact link to track clicks: {redirectUrl}" + warn if they copy the direct URL.
- **Long-term:** integrate a shortener (Bitly, Rebrandly, or self-hosted) so the stored `fullTrackingUrl` is automatically wrapped.

---

## 6. Files involved

| Path | Role |
|------|------|
| `service/tracking/CampaignLinkService.java` | UTM link generation, click counting |
| `service/tracking/ConversionTrackingService.java` | Conversion recording (link-attributed) |
| `service/tracking/CouponCodeService.java` | Coupon generation |
| `service/tracking/RedemptionService.java` | Coupon redemption (GAP #3: missing earnings call) |
| `service/AffiliateEarningsService.java` | Earnings creation (only called by reconciliation job) |
| `job/AffiliateEarningReconciliationJob.java` | The ONLY live path for earnings (hourly cron) |
| `web/ConversionWebhookController.java` | Conversion + redemption webhook endpoints |
| `integration/tracking/webhook/ConversionWebhookSignatureVerifier.java` | HMAC verification |

---

## 7. Next steps

1. **Priya:** architectural ruling on the 4 questions in §4.
2. **If synchronous earnings (Option A):** Vikram implements the `RedemptionService → recordEarning` call → Kabir (money path) → Kavya → Meera → Priya.
3. **If any earnings-related fix:** add a regression test proving a redemption creates an earning without waiting for the cron.
4. **Documentation:** update javadoc to match the final design (synchronous vs async).

---

_Findings documented 2026-07-13 by Arjun (Engineering Lead). Escalated to Priya (CTO) for architectural decision on fixes._
