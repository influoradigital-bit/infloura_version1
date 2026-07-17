# Creator GA v1 — YouTube OAuth Deferral (CEO Sign-Off)

| Field | Value |
|---|---|
| **Status** | **DEFERRED** from Creator GA v1 |
| **Authority** | Swapnil Maruti, CEO, Sage Digital |
| **Date** | 2026-07-10 |
| **Canonical ruling** | `CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md` §1.5 |
| **Closes** | Priya reconciliation — informal tracker deferral without written CEO sign-off |

---

## Decision

**YouTube OAuth is explicitly deferred from Creator GA v1.** Do not build YouTube OAuth code (controller, API client, connect UI, channel metrics) for GA. Tracker **0%** is accepted scope, not a silent drop.

## What remains in GA v1

- **Instagram / Facebook Meta OAuth** — in scope and **shipped** (`MetaOAuthController`, PKCE). This is the GA v1 social-connect surface.

## Revisit triggers

Reopen YouTube OAuth when **any** of the following is true:

1. **Post-GA** — Creator GA v1 shipped and the P0/P1 GA queue is clear; or  
2. **Partner / brand demand** — a signed partner or paying brand requires YouTube-verified creators as a deal-blocker; or  
3. **Escalation** — Priya/Arjun bring a scoped build estimate with capacity.

Until then: **no YouTube OAuth build.** TikTok remains separately "Future" per OAuth spec.

## Sign-off

**Approved / deferred by:** Swapnil Maruti (CEO)  
**Signed:** 2026-07-10  
**Handoff:** `SWAPNIL → ARJUN/PRIYA | YouTube OAuth | DEFERRED with written sign-off`
