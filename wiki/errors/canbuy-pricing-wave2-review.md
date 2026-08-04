# QA Review: CanBuy Pricing Wave 2 (P2-19, P2-01, P2-15)
Date: 2026-07-30
Reviewer: Kavya (QA Lead)
Developer: Vikram
Status: **PASS** — cleared for Meera's unit-test run

---

## Summary

Three-ticket money-path implementation (P2-19 small-order fee, P2-01 volumetric freight, P2-15 FX buffer/lock/alerts) has been reviewed against Rohan's FIRM spec (`wiki/finance/CanBuy_pricing_inputs_2026-07-30.md`). All critical checks PASS. One known persistence gap confirmed (not a blocker — addressed in findings below).

**Overall verdict:** PASS. Code is money-safe, boundary-correct, and spec-aligned. Ready for unit-test verification.

---

## P2-19 — Small-Order Fee: ✅ PASS

### Critical Requirements (from spec D5)
- ✅ Fee keyed off **buyer-facing HKD subtotal** (`subtotalHkd`), NOT raw CAD
- ✅ Boundary operator: `< 400` triggers fee, `>= 400` does NOT (exclusive threshold)
- ✅ Fee is ADDITIVE (not a block) — cart totals returned regardless
- ✅ Hard minimum (`orderMinHkd`/`minOrderValueHkd`) NOT enabled (stays 0 permanently)
- ✅ Existing `minOrderCad=30` hard floor UNTOUCHED

### Code Evidence

**Boundary logic** (`PricingService.java:305-307`):
```java
BigDecimal smallOrderFeeHkd = subtotalHkd.compareTo(smallOrderFeeThresholdHkd) < 0
        ? smallOrderFeeAmountHkd.setScale(2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
```
✅ **CORRECT.** Uses `< 0` (strictly less than), so HK$400.00 exactly does NOT trigger the fee.

**Test boundary verification** (`PricingServiceSmallOrderFeeTest.java`):
- Line 117: `quoteAtSubtotalHkd("400.00")` → expects `smallOrderFeeHkd = 0.00` ✅
- Line 125: `quoteAtSubtotalHkd("399.99")` → expects `smallOrderFeeHkd = 50.00` ✅
- Line 131: `quoteAtSubtotalHkd("400.01")` → expects `smallOrderFeeHkd = 0.00` ✅

Boundary lands on the correct side: $399.99 charges the fee, $400.00 does not.

**Fee fold into totalHkd** (`PricingService.java:391`):
```java
BigDecimal totalHkd = subtotalHkd.add(serviceFeeHkd).add(shippingHkd).add(smallOrderFeeHkd).subtract(cappedDiscountHkd)
```
✅ Additive term, same treatment as `serviceFeeHkd`.

**Config separation** (`application.yml:184-185`):
```yaml
small-order-fee-threshold-hkd: 400
small-order-fee-hkd: 50
```
✅ New keys, distinct from `min-order-cad` and the disabled `orderMinHkd`/`minOrderValueHkd` levers.

**Hard minimum NOT enabled:**
- `PricingService.java:73,102` — `minOrderCad=30` unchanged ✅
- No code touches `orderMinHkd` or `minOrderValueHkd` ✅

---

## P2-01 — Volumetric Freight: ✅ PASS

### Critical Requirements (from spec D4)
- ✅ Formula: `chargeableKg = max(actualKg, volume_m3 × factor)`
- ✅ Factor is **config value** (not hardcoded constant)
- ✅ Factor carries PLACEHOLDER comment
- ✅ Null volume falls back to actual weight (never zero)
- ✅ `chargeableKg` fed into BOTH `freightForWeight` AND `computeFreightHeadroom`
- ✅ `totalWeightKg` on DTO still reports TRUE physical weight

### Code Evidence

**Chargeable weight computation** (`PricingService.java:342-344`):
```java
BigDecimal chargeableKg = totalVolumeM3 == null
        ? totalWeightKg
        : totalWeightKg.max(totalVolumeM3.multiply(seaDimFactorKgPerM3).setScale(3, RoundingMode.HALF_UP));
```
✅ **CORRECT.** 
- Uses `max()` — whichever is greater.
- Null-safe: `totalVolumeM3 == null` → falls back to `totalWeightKg` only.
- Never treats null as zero (the money-safety point).

**Factor is config-backed** (`PricingService.java:300,311`):
```java
private final BigDecimal seaDimFactorKgPerM3;
...
this.seaDimFactorKgPerM3 = new BigDecimal(env.getProperty("canbuy.pricing.sea-dim-factor-kg-per-m3", "1000"));
```
✅ Named constant from `application.yml`, not inlined.

**Config carries PLACEHOLDER warning** (`application.yml:186-199`):
```yaml
# P2-01 (Rohan: PLACEHOLDER-PENDING-CONTRACT — see wiki/finance/
# CanBuy_pricing_inputs_2026-07-30.md D4). Sea LCL "weight or measurement"
# (W/M) dim factor: ... PLACEHOLDER pending forwarder rate card (D4) — 
# the real forwarder invoice could bill closer to 167 kg/m3 (~6x lower 
# than this placeholder). Do not treat as production-billing-final until 
# confirmed FIRM; this is a single named constant specifically so it is 
# a one-line change when the real number lands, never inline it elsewhere.
sea-dim-factor-kg-per-m3: 1000
```
✅ PLACEHOLDER status clearly flagged.

**Headroom consistency** (`PricingService.java:356,372`):
```java
BigDecimal shippingHkd = freightForWeight(chargeableKg, freightTiers);
...
FreightHeadroomDto freightHeadroom = computeFreightHeadroom(chargeableKg, freeShippingApplied, freightTiers);
```
✅ **CORRECT.** Both calls receive `chargeableKg`, not `totalWeightKg`. The headroom nudge is priced on the same basis as the actual charge.

**Physical weight preserved on DTO** (`PricingService.java:400`):
```java
CartTotalsDto totals = new CartTotalsDto(
        subtotalCad, subtotalHkd, serviceFeeHkd, smallOrderFeeHkd, shippingHkd, cappedDiscountHkd, totalHkd,
        totalWeightKg, totalVolumeM3, fxCadToHkd, ...
```
✅ `totalWeightKg` passed unchanged — not overwritten with `chargeableKg`.

---

## P2-15 — FX Buffer/Lock/Alerts: ✅ PASS

### Critical Requirements (from spec D6)
- ✅ +2% buffer applied correctly
- ✅ Lock-days key SEPARATE from `quote.ttl-minutes`
- ✅ Alert classifier returns correct level at exact thresholds
- ✅ HARD_STOP (absolute) takes priority over RED (%-move)

### Code Evidence

**Buffer application** (`FxRateService.java:198`):
```java
BigDecimal buffered = spotRate.multiply(BigDecimal.ONE.add(fxBufferRate)).setScale(4, RoundingMode.HALF_UP);
```
✅ Applies `spotRate × (1 + 0.02)` = +2% buffer.

**Test verification** (`FxRateServiceTest.java:46-49`):
```java
FxRateService.FxRateStamp stamp = fxRateService.stampRate(new BigDecimal("5.78"), new BigDecimal("5.78"));
assertMoneyEquals("5.8956", stamp.bufferedRate()); // 5.78 * 1.02
```
✅ Test confirms 2% buffer math.

**Lock-days vs quote TTL separation:**
- `application.yml:157` — `quote.ttl-minutes: 15` (per-checkout quote TTL)
- `application.yml:210` — `fx-rate-lock-days: 7` (operational re-quote cadence)
- Comment at line 204-207 explicitly states these are DISTINCT and must not be conflated ✅

**Alert threshold boundaries** (`FxRateService.java:226-238`):
```java
if (currentSpotRate.compareTo(fxAlertHardStopHkd) >= 0) {
    return FxAlertLevel.HARD_STOP;
}
BigDecimal pctMove = currentSpotRate.subtract(lastStampedRate)
        .divide(lastStampedRate, 6, RoundingMode.HALF_UP)
        .abs();
if (pctMove.compareTo(fxAlertRedPct) >= 0) {
    return FxAlertLevel.RED;
}
if (pctMove.compareTo(fxAlertWarnPct) >= 0) {
    return FxAlertLevel.WARN;
}
return FxAlertLevel.NONE;
```
✅ **CORRECT ordering:** HARD_STOP checked FIRST (line 226), before %-move checks.

**Test boundary verification** (`FxRateServiceTest.java`):
- Line 69-75: `5.78 × 1.015` → WARN (exactly 1.5%) ✅
- Line 79-84: `5.78 × 1.0149` → NONE (just below 1.5%) ✅
- Line 88-93: `5.78 × 1.03` → RED (exactly 3%) ✅
- Line 97-102: `5.78 × 1.0299` → WARN (just below 3%, still in WARN band) ✅
- Line 106-108: `6.10` → HARD_STOP (at absolute threshold) ✅
- Line 112-117: `6.50` → HARD_STOP (takes priority over RED, even though 6.50 vs 5.78 is +5.5% = past RED too) ✅

All boundaries land on the correct side.

---

## Collateral Fixes Assessment

**PaymentIntentService.java** (line 274):
```java
return new CartTotalsDto(
        order.getSubtotalCad(), order.getSubtotalHkd(), order.getServiceFeeHkd(), BigDecimal.ZERO, order.getShippingHkd(), ...
```
✅ **MECHANICAL COMPILE FIX.** `CartTotalsDto` constructor gained a new positional parameter (`smallOrderFeeHkd`). The `BigDecimal.ZERO` placeholder is correct here because:
1. The fee was already baked into `order.getTotalHkd()` at checkout time.
2. Re-deriving it here would double-count it.
3. This DTO never leaves the server (PaymentProvider reads only `totalHkd`).

Comment at line 264-270 explains the reasoning. Not a silent behavior change.

**Test file updates** (3 files):
- `AdminManualOrderServiceTest.java:478`
- `CheckoutServiceTest.java:492`
- `QuoteServiceRedeemTest.java:532`

All three insert `BigDecimal.ZERO` at the new `smallOrderFeeHkd` position. ✅ Mechanical fix — test setup constructing a DTO, not asserting a wrong fee.

**PricingService test stubs** (2 files):
- `PricingServiceBundleExpansionTest.java:503-505`
- `PricingServiceFreightHeadroomTest.java:517-519`

Both add `lenient().when(env.getProperty(...))` stubs for the three new config keys (`small-order-fee-threshold-hkd`, `small-order-fee-hkd`, `sea-dim-factor-kg-per-m3`). ✅ Mechanical — existing tests' `@BeforeEach` now need to stub the new env properties to avoid NPE. No behavior assertions changed.

---

## Persistence Gap Characterization (not a blocker — flagged for Gate 2)

**Claim (from Vikram):** `Order` entity has no `small_order_fee_hkd` column, so the fee is baked into `totalHkd` but not separately persisted — an itemized invoice (P2-13) can't show the fee as its own line.

**Verified:** ✅ CONFIRMED. Checked `Order.java` (lines 0-80) — no `smallOrderFeeHkd` field exists. The entity has:
- `subtotalCad`, `subtotalHkd`, `serviceFeeHkd`, `shippingHkd`, `discountHkd`, `refundedHkd`, `totalHkd`
- No `smallOrderFeeHkd` ❌

**Impact:** P2-13 (itemized receipt/invoice) already breaks down `serviceFeeHkd` and `shippingHkd` as separate line items. The small-order fee is now a real charge component (same weight as service fee), but it's invisible on the receipt — the buyer sees a `totalHkd` that's HK$50 higher than `subtotalHkd + serviceFeeHkd + shippingHkd - discountHkd` would suggest, with no label for the gap.

**Rohan's note (from spec):** Tejas already drafted buyer copy: *"Small-order fee · HKD 50 — covers minimum handling for lighter batches."* This copy can't be shown on the invoice unless the fee has its own persisted column.

**Mitigation:** Arjun is folding an `Order.smallOrderFeeHkd` column into the Gate 2 migration wave per the escalation thread in `SHARED_CONTEXT.md`. Not this wave's blocker — Vikram correctly noted the gap and flagged it. The fee COMPUTATION is money-correct (baked into `totalHkd` at checkout); only the DISPLAY breakdown is missing.

---

## Money Honesty Check

✅ All new fields null-and-labelled when inputs missing:
- `smallOrderFeeHkd`: Never null — always computable from `subtotalHkd` (a required field). Returns `BigDecimal.ZERO` when threshold not met. ✅
- `chargeableKg`: Degrades gracefully — null `totalVolumeM3` → falls back to `totalWeightKg`, never a silent zero. ✅
- FX alert classification: `classifyAlert(null, ...)` → returns `NONE`, not an error. ✅

No silent zeros that would understate charges.

---

## Architecture Compliance

✅ **TECH-STACK non-negotiable #1 (all money server-authoritative):**
- Fee computed in `PricingService.recomputeCart()` (server-side).
- Volumetric weight computed server-side, fed into `freightForWeight()`.
- FX buffer applied server-side in `FxRateService.stampRate()`.
- Client receives stamped values on `CartTotalsDto`, display-only.

✅ **No `any` TypeScript types** (N/A — this is Java)
✅ **No hardcoded constants** — all three use named config values
✅ **BigDecimal for all money** — no floats
✅ **Null-safety** — volumetric weight null-check proven

---

## Security Check

✅ No API keys in code
✅ No credentials hardcoded
✅ Input validation: all three tickets compute from existing validated inputs (`subtotalHkd`, `totalVolumeM3`, `spotRate`)
✅ SQL: all Prisma-backed (N/A — this is JPA, but no raw SQL strings found)

---

## Next Steps

1. ✅ **Cleared for Meera** — run `mvn test` to verify unit suite passes.
2. Route to Arjun — confirm `Order.smallOrderFeeHkd` column is in the Gate 2 schema batch.
3. Once schema lands, update `PaymentIntentService.totalsOf()` to read the persisted field instead of the `BigDecimal.ZERO` placeholder.

---

**Final Verdict:** ✅ **PASS** — code is money-safe, boundary-correct, and spec-compliant. The persistence gap is known, documented, and gated appropriately. Cleared for unit-test verification.

*— Kavya (QA Lead), 2026-07-30*
