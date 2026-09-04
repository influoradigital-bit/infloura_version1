# Meera for Creators — Credit Sheet

For Swapnil. Full workbook with live formulas: `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/finance/meera-credit-sheet.xlsx` (sheets: Assumptions, UnitCosts, Personas, OptionA, OptionB, OptionC, Sensitivity — every number downstream of Assumptions is a formula, not a paste).

Sources verified against HEAD: `influora-ai/app/costs/pricing.py` (PRICING_TABLE, Sarvam constants), `influora-ai/app/config.py` (model pins, `ai_creator_monthly_cap_usd=0.75`), `influora-api/.../AICreditService.java` + `MeeraSessionService.java` (`TURN_CREDIT_COST=1`, `DEFAULT_MONTHLY_ALLOTMENT=100`), `application.yml` (`platform-fee-percent: ${PLATFORM_FEE_PERCENT:15.00}`), `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/SPEC.md` §3.1 (nine creator tools) and §7.5 (brief-extraction route, Haiku-class `BRIEF_EXTRACT_MODEL`).

## 1. Unit cost model (USD→INR at 84)

| Unit | Model | Assumption | Cost |
|---|---|---|---|
| Chat turn | Sonnet (`claude-sonnet-4-5-20250929`, $3/$15 per MTok) | 2,500 in (90% cacheable, 70% cache-hit) / 200 out, +40% chance of one tool round trip (300 in / 100 out) | **$0.007714 = ₹0.648/turn** |
| Brief extraction | Haiku (`claude-haiku-4-5-20251001`, $1/$5 per MTok, `BRIEF_EXTRACT_MODEL` default) | 1,500 in / 400 out | **$0.0035 = ₹0.294/extraction** |
| Voice turn | Sarvam STT (flat $0.006) + TTS (₹30/10k chars, code's internal ₹83/$1) | 300 TTS chars; additive on top of one chat turn, not a separate turn (matches config.py's "voice is additive" note) | **$0.01684 = ₹1.415/voice turn** |
| Weekly digest | Haiku | 1,200 in / 300 out, 4.33 digests/month | **$0.0027/call = ₹0.98/creator/month** |

## 2. Persona monthly cost

| Persona | Turns | Briefs | Voice | Cost/month | % of current $0.75 cap |
|---|--:|--:|--:|--:|--:|
| Light | 20 | 2 | 0 | ₹14.53 | 2.6% |
| Typical | 60 | 6 | 10 | ₹55.77 | **88.5%** |
| Heavy | 150 | 15 | 40 | ₹159.18 | **253%** (already over cap) |
| Manager seat (5× Typical) | 300 | 30 | 50 | ₹278.86 | — |

**Finding:** at these assumptions a Typical creator already sits at 88.5% of the current $0.75/month cap, and a Heavy creator is 2.5x over it — meaning Heavy usage as specified would get throttled mid-month under today's cap, independent of any pricing decision.

## 3. Option A — included in the 15% fee

Fee revenue: 1 deal = ₹750, 2 deals = ₹1,500, 4 deals = ₹3,000 (₹5,000 deal × 15%).

| Persona | 1 deal/mo | 2 deals/mo | 4 deals/mo |
|---|--:|--:|--:|
| Light — AI cost % of fee | 1.9% | 1.0% | 0.5% |
| Typical — AI cost % of fee | 7.4% | 3.7% | 1.9% |
| Heavy — AI cost % of fee | 21.2% | 10.6% | 5.3% |

Gross margin stays above 79% in every cell, above 92% for Typical at 2+ deals. AI cost is not the problem for Option A — it's nearly free to absorb. The real constraint is deal cadence: a Heavy creator closing only 1 deal that month gives up a visible fifth of that one fee to AI.

## 4. Option B — subscription with credits

Credit definition: 1 turn = 1 credit, 1 brief = 3 credits, 1 voice = 2 credits, digest free. Credits needed: Light 26, Typical 98, Heavy 275/month. Blended cost/credit (Typical mix) = ₹0.559 — but this hides a wide spread: a voice credit actually costs ~7x a turn credit and ~14x a brief credit, so flat pricing quietly subsidizes voice-heavy creators.

| Tier | Price | Included credits | Cost to serve | Margin |
|---|--:|--:|--:|--:|
| Starter | ₹299/mo | 100 | ₹55.91 | 81.3% |
| Pro | ₹599/mo | 300 | ₹167.72 | 72.0% |
| Max | ₹999/mo | 600 | ₹335.45 | 66.4% |

Top-up: 100 credits for ₹249 → ₹55.91 cost, **77.5% margin**. Starter comfortably covers Light (26 needed vs 100 included); Pro comfortably covers Typical (98 vs 300); Max comfortably covers Heavy (275 vs 600).

## 5. Option C — hybrid (40 free credits + top-ups + manager seat)

Included 40 credits/creator/month absorbed cost = ₹22.36 (pure cost, no matching revenue).

| Persona | Credits needed | Free covers | Must purchase | Top-up revenue | Top-up cost | Net (rev − all cost) |
|---|--:|--:|--:|--:|--:|--:|
| Light | 26 | all | 0 | ₹0 | ₹0 | **−₹14.54** |
| Typical | 98 | 40 | 58 | ₹144.42 | ₹32.43 | **+₹89.63** |
| Heavy | 275 | 40 | 235 | ₹585.15 | ₹131.38 | **+₹431.40** |

Manager seat: ₹1,999/mo for up to 5 creators vs. ₹278.86 cost to serve 5 Typical creators at full usage → **86% margin**, generous headroom even if manager-linked creators run hot.

Light creators are a pure cost center under Option C (₹14.54/month each, no offsetting revenue) — acceptable only if Light creators are valued for platform stickiness/deal-flow, not AI-line profitability.

## 6. Sensitivity

| Scenario | Effect |
|---|---|
| Cache hit rate 70%→30% | Cost/turn +40.3% (₹0.648→₹0.909). Typical persona jumps to 113% of the current $0.75 cap (already over); Heavy to 315% of current cap, **118% of a raised $2 cap**. |
| Extraction moved Sonnet↔Haiku | Cost/extraction 3x (₹0.294→₹0.882). Typical persona rises to 94% of the current cap on this change alone; Heavy to 267%. |
| Cap raised $0.75→$2.00 | Typical falls to 33% of cap (comfortable). Heavy falls to 95% of cap (tight but fits) — **unless** combined with degraded caching or Sonnet extraction. |
| Combined worst case (30% cache hit + Sonnet extraction, Heavy persona) | **₹207/month, 329% of the current cap and 123% of even the raised $2 cap.** A cap raise alone does not cover this combination — Haiku for extraction and healthy cache hits are load-bearing assumptions, not nice-to-haves. |

## 7. GST

18% on a creator subscription. At the same list prices, inclusive vs. exclusive reads as:

| Tier | List price | If inclusive: base / GST | If exclusive: customer pays |
|---|--:|--:|--:|
| Starter | ₹299 | ₹253.39 / ₹45.61 | ₹352.82 |
| Pro | ₹599 | ₹507.63 / ₹91.37 | ₹706.82 |
| Max | ₹999 | ₹846.61 / ₹152.39 | ₹1,178.82 |
| Manager seat | ₹1,999 | ₹1,694.07 / ₹304.93 | ₹2,358.82 |

Recommend displaying **inclusive** prices (299/599/999/1,999) — standard Indian B2C practice, no checkout surprise. GST registration and filing mechanics for an AI-credit line are a compliance call for Swapnil and counsel, not something this sheet decides.

## 8. Recommendation

1. Ship **Option C (hybrid)** at these price points: 40 free credits/creator/month absorbed inside the existing cost base, ₹249/100-credit top-up pack, ₹1,999/month manager seat for up to 5 creators.
2. It keeps Meera free at the point of use for every creator (matches "no flat monthly fee" competitive pressure from Snippet/Managr/Seneca) while monetizing the Heavy tail that actually costs money — Typical and Heavy creators net **+₹90 to +₹431/month** each after their own AI cost, Light creators cost **₹14.54/month** each as a retention/stickiness expense.
3. Before this ships, raise the per-creator cap from $0.75 to at least $2.00 — Typical creators already sit at 88.5% of today's cap and Heavy creators are already 2.5x over it, independent of any pricing choice; this is a code-config change (`AI_CREATOR_MONTHLY_CAP_USD`), not a pricing one.
4. Treat "Haiku for brief extraction" and "≥70% cache hit rate" as load-bearing engineering commitments, not tuning knobs — losing either one singly pushes Typical over even a raised cap, and losing both together on a Heavy creator blows past a $2 cap by 23%.
5. Option A (pure commission) is the safest fallback if Option C's billing/credit-ledger work can't land in time — AI cost never exceeds ~21% of one fee and drops under 2% at higher deal cadence — but it forfeits the manager-seat and top-up revenue lines Option C opens up.
