# ADR: Build the brand-facing Analytics Read API before BrandSafetyScoreService

> **Decision by:** Priya (CTO) — final authority on technical sequencing
> **Date:** 2026-07-06
> **Status:** LOCKED
> **Requested by:** Swapnil (CEO) — "Priya's answer is final"

---

## Context

Phase 3's Java-only scoring work is done and signed off: `FakeFollowerDetectionService`, `QualityScoreService`, `RateEstimationService`, `ScoreCalculationJob`, migration V22 (`creator_scores`), all with `MetricsAuthorizationService` as the workspace-isolation gate. 183 backend tests green, verified live against MySQL.

**Verified gap (grounded, not assumed):** grepped all 15 existing controllers in `influora-api/.../web/` — **zero** reference any metric, score, or scoring service. The entire analytics engine (Phase 2 metrics + Phase 3 scores) has NO REST surface. Nothing brand-facing can read any of it. `MetricsAuthorizationService` still has no caller (Kabir flagged this twice as "groundwork, not active protection").

Two candidate next steps were on the table: (a) `BrandSafetyScoreService` (the 4th scoring service, cross-repo `influora-ai` integration + LLM prompt design), or (b) Ananya's analytics dashboard UI.

## Decision

**Build the brand-facing Analytics Read API first** — a new `AnalyticsController` (+ DTOs) exposing creator metrics + scores, with every read routed through `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId`. Neither (a) nor (b) proceeds until this exists.

## Rationale

1. **It's the actual blocking gap.** A scoring engine nothing can read is not a shippable feature. This is the missing seam between the backend work and any user-visible value.
2. **It unblocks the dashboard for real.** Ananya's UI (`ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md` §1-2) needs live endpoints; they don't exist. Correcting an earlier imprecise claim that the dashboard was already unblocked — it was not.
3. **It closes a standing security item by construction.** `MetricsAuthorizationService` was built as groundwork with no caller. The first brand-facing read endpoint IS that caller — wiring it now exercises the isolation gate instead of leaving it as an untested contract. This is the highest-leverage moment to prove that gate works end to end.
4. **BrandSafetyScoreService is correctly the LAST Phase-3 piece, not the next.** It's higher-risk (cross-repo, new Python endpoint, LLM prompt/GARM design) and depends on the `influora-ai` service. Building a 4th score column before anything reads even the existing 3 scores is premature infrastructure — same anti-pattern as the TimescaleDB call. It proceeds AFTER the read API, and can run in parallel with the dashboard UI at that point.

## Sequencing (locked)

1. **NOW:** `AnalyticsController` + DTOs — brand-facing read endpoints for creator metrics + latest scores, every finder gated by `MetricsAuthorizationService`. Full review pipeline (Kavya + Kabir — Kabir's review here is load-bearing since this is the first real exercise of the isolation gate).
2. **THEN, in parallel:** Ananya's analytics dashboard UI (consumes the new endpoints) + `BrandSafetyScoreService` (cross-repo AI, its own properly-scoped task — read `influora-ai`'s actual `app/routes/`/`app/main.py` structure before dispatching).
3. Phase 4 (UTM/Coupons, currently 0%) after Phase 3 fully closes.

## Constraints in force

- Read endpoints MUST call `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId(workspaceId, creatorProfileId)` before returning any per-creator data — no raw `creatorProfileId` passthrough.
- DTOs must not leak the internal `debug`/`factors` maps or raw entity fields beyond what the frontend spec needs.
- MySQL / existing conventions per the Phase 2 datastore ADR — unchanged.
