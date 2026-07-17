# Trend-Spark AI Business Sign-Off (Swapnil, CEO)

**Date:** 2026-07-13
**Task:** T13 from `trendspark/INDEX.md`
**Input gates (all passed):**
- Kavya T9 QA: Conditional PASS (no blockers)
- Meera T10 Verify: PASS (897/11F/8E, 0 new TS failures; py 25/25+78/78; tsc/vite exit 0)
- Kabir T11 Security: CLEARED (no Critical/High; 4 M/L follow-ups)
- Ash T12 AI Review: PASS (no P0; 1 P1 request.json, fails closed)

---

## DECISION: GO-WITH-CONDITIONS

Ship the v1 Trend-Spark MVP to the feature branch, with a **mandatory PP-1 live-verification gate** before any real brand traffic. This mirrors how we handled P1-5 (Meera E2E), P2-14 (content-performance), and P2-12 (Razorpay payout) — code is correct and safe per all audits, but sandbox limitations prevent a live curl/boot test, so final confirmation happens on a networked host.

### Conditions (all must be met before brand traffic):

1. **PP-1 Live Gate (MANDATORY):** Spring Boot must boot successfully on a real host with MySQL/Docker available, and a real `curl GET /api/v1/brand/trendspark/nudge` must return a well-formed response (204 silent or 200 with correct shape). This is not negotiable.

2. **Anthropic Cap LIVE (MANDATORY):** Rohan's policy cap (Rs1,500/mo, 80% alert at Rs1,200) must be configured as an actual billing limit in the Anthropic Console by the account owner. Right now it's policy-only, not enforced. Do this before brands touch it.

3. **Kabir M1 (Rate-Limit Throttle) — FAST-FOLLOW, not ship-blocker:** The self-spend / row-bloat issue is Medium severity, bounded by the daily spend ceiling, and self-scoped (no cross-tenant impact). Not blocking ship. Must be fixed in the first week of production — Vikram owns. Deadline: 2026-07-20.

4. **Ash P1 (request.json 500) — FAST-FOLLOW, not ship-blocker:** Fails closed at the system level (Java treats non-200 as fallback, brand never sees error). Fix is trivial (wrap in try/except, return 400). Must be merged before PP-1. Ash owns.

---

## SPEC SECTION 11 DECISIONS (CEO Ruling)

| # | Decision | Ruling |
|---|----------|--------|
| 1 | Trigger point for v1 | **ON-OPEN** (recommendation accepted). Idle-timer adds complexity; on-open proves the value first. |
| 2 | Target audience | **BRANDS FIRST** (recommendation accepted). Creators in Phase 3 if brands convert. |
| 3 | Persona name | **"Meera"** (keep the placeholder as the real name). It's warm, Indian, memorable. If we need to change it later, it's one config constant. Ship with Meera. |

---

## FOLLOW-UPS: SHIP-BLOCKER vs. FAST-FOLLOW

| Item | Severity | Ship-Blocker? | Owner | Deadline |
|------|----------|---------------|-------|----------|
| PP-1 live boot + curl | — | YES | Meera/Arjun | Before brand traffic |
| Anthropic billing cap live | — | YES | Account owner | Before brand traffic |
| Kabir M1 (rate-limit/dedupe) | Medium | NO | Vikram | 2026-07-20 |
| Ash P1 (request.json 500) | P1 | NO (fix before PP-1) | Ash | Before PP-1 |
| Kabir L1 (purchasedVideoId validate) | Low | NO | Vikram | Backlog |
| Kabir L2/L3 (folds into M1) | Low | NO | Vikram | 2026-07-20 |
| Ash P2-1/2/3/4 | P2 | NO | Ash/Rohan/Nisha | Backlog |

---

## THE BRAND-PROMISE SAFEGUARD

The entire Trend-Spark product is built on one promise: **"gap-filler, not always-on spam."** The anti-spam gate (Spec Section 5b) is the whole product — if it leaks, we're just another pushy ad platform.

**Confirmed enforced:**
- `ContentGapService.decide()` defaults to `OWN_CONTENT` on null/unavailable/recent-post signals (Meera verified code path, lines 57-60, 74).
- `OWN_CONTENT` mode never mentions Snapsby, videos, or buy (enforced by `_OWN_CONTENT_FORBIDDEN_RE` in Python + structurally absent from the OWN_CONTENT template).
- Frontend `TrendSparkNudgeCard.tsx` renders "Plan a campaign" CTA with no marketplace mention when `mode=OWN_CONTENT`.
- Fail-closed throughout: missing profile, below-threshold match, Meta unavailable, AI failure — all resolve to OWN_CONTENT or silent 204, never to a Snapsby push.

This is the safeguard that protects the brand promise. It's enforced in code at three layers (Java gap-check, Python prompt guardrails, React UI). I'm satisfied it's real, not just documented.

---

## FINAL NOTE

I am rendering this decision as the CEO persona (Swapnil). The final production release — actual deployment to a host with real brand traffic — still requires:
1. The human owner's explicit confirmation that PP-1 passed.
2. The Anthropic billing cap configured.
3. A quick manual check of the Meera persona copy to confirm it sounds right (read a few sample nudges, make sure they sound like a helpful colleague, not a pushy salesperson).

This sign-off authorizes merging the feature branch and setting up PP-1. It does not authorize production traffic without the above.

---

**Swapnil (CEO persona) sign-off:** 2026-07-13

**INDEX row 13:** `GO-WITH-CONDITIONS | Swapnil * 2026-07-13 * wiki/decisions/2026-07-13-trendspark-swapnil-signoff.md`
