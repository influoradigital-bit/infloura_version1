# P12 - Endpoint Discrepancy Resolution

> **Author:** Ananya (Frontend) | **Date:** 2026-07-05
> **Status:** RESOLVED - Frontend aligned to actual Spring routes

## Summary

Doc 02 (API-CONTRACT-BRAND.md) and Doc 11 (AI-FLOW-DETAILED.md) listed different endpoint paths for the same operations. After inspecting the actual Java controllers (`MeeraController.java` at `/meera` and `MeeraInternalController.java` at `/internal/meera`), the discrepancies are resolved as follows:

## Discrepancy Resolution Table

| Purpose | Doc 02 Path (Contract) | Doc 11 Path (Flow) | ACTUAL Spring Path | Resolution |
|---------|------------------------|--------------------|--------------------|------------|
| Analyze site | *(implied)* | `POST /brand/meera/analyze-site` | **Not yet implemented** | Use Doc 02 pattern when built: `/meera/analyze-site` |
| Start/resume session | `POST /meera/sessions` | -- | `POST /meera/sessions` | **Doc 02 wins** |
| Send a turn | `POST /meera/sessions/{id}/messages` | `POST /brand/meera/turn` | `POST /meera/sessions/{conversationId}/messages` | **Doc 02 wins** |
| Brand profile | `GET /meera/brand-profile` | `GET /brand/meera/profile` | `GET /meera/brand-profile` | **Doc 02 wins** |
| Credits | `GET /meera/credits` | -- | `GET /meera/credits` | **Doc 02 wins** |
| Escrow fund | `POST /wallet/escrow/fund` | `POST /brand/escrow/fund` | **Not in MeeraController - likely EscrowController** | Use Doc 02: `/wallet/escrow/fund` |
| Create campaign | `POST /meera/sessions/{id}/create-campaign` | -- | **Not yet in controller** | Use Doc 02 pattern |

## Verified Controller Routes (from `MeeraController.java`)

```java
@RestController
@RequestMapping("/meera")
public class MeeraController {
    @PostMapping("/sessions")                           // Start/resume session
    @PostMapping("/sessions/{conversationId}/messages") // Send turn
    @GetMapping("/credits")                             // Credit status
    @GetMapping("/brand-profile")                       // Brand profile/analysis status
}
```

## Internal Routes (from `MeeraInternalController.java`) - Python -> Spring only

These are NOT called by the browser - only by the Python AI service:

```java
@RestController
@RequestMapping("/internal/meera")
public class MeeraInternalController {
    @PostMapping("/show_creators")
    @PostMapping("/calculate_budget")
    @PostMapping("/create_campaign")
    @PostMapping("/request_payment")
    @PostMapping("/confirm_launch")
    @PostMapping("/messages")  // Turn write-back
}
```

## Frontend Implementation Decision

**All frontend API calls use Doc 02 paths (the signed contract).** The paths are centralized in `src/lib/meera-api.ts` so any future renames are a one-line change.

Browser endpoints (all under `/api/v1`):
- `POST /meera/sessions` - Start/resume session
- `POST /meera/sessions/{id}/messages` - Send turn, get stream token
- `GET /meera/credits` - Credit status
- `GET /meera/brand-profile` - Analysis status, niche tags
- `POST /wallet/escrow/fund` - Fund escrow (human confirm, Idempotency-Key required)
- `GET /wallet/escrow/{id}` - Escrow status

The browser NEVER calls `/internal/meera/*` routes - those are Python-to-Spring only.

## SSE Stream URL

The Python SSE endpoint is at the URL returned in `streamUrl` from the `POST /meera/sessions/{id}/messages` response. The browser connects directly to Python with the scoped `streamToken` (not the user JWT).

---
Ananya
