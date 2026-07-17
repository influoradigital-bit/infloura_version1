# DECISION LOG — 2026-07-05 — Remaining-work packets + cost review sign-off

**Thread:** ROHAN → SWAPNIL | Cost review of docs 16/17/18/19 vs. live API pricing | CLOSED

**Decision:** APPROVED. Full verdict: `docs/AI connect/backend/21-SWAPNIL-SIGNOFF.md`
**Cost review:** `docs/AI connect/backend/20-ROHAN-COST-REVIEW.md`

**Two scope additions approved:**
1. Gemini model re-pin (`2.0-flash` → `gemini-2.5-flash-lite`) — folded into `16-VIKRAM-REMAINING-TASKS.md` Domain D, blocking, zero cost impact.
2. 500 actions/day hard cap on "unlimited while live" AI credits — added to `AICreditService` scope in doc 16, Kabir gates it per doc 17.

**Also directed:** eval harness (per `19-AI-ARCHITECT-REVIEW.md` R-1) to be built alongside Domain D, not deferred. Ananya + Vikram to resolve the doc 02/11 endpoint-path discrepancy before real SSE wiring.

**Budget impact:** none. No new subscription or budget ceiling approved.
