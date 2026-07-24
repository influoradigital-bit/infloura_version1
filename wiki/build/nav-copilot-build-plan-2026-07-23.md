# Arjun — Task Assignment: Nav + Co-pilot + Disputes (2026-07-23)

Goal: expose the built-but-orphaned pages in nav (brand + creator), give the creator a real AI Co-pilot page, and close the one backend gap (creator disputes list). Build → menu → verify step by step.

## Assignments

| # | Owner | Task | Files (owned) | Status |
|---|-------|------|---------------|--------|
| A1 | Ananya (FE) | Creator sidebar nav 2→6 (Home, Deals, Campaigns, Co-pilot, Analytics, Wallet); fix stale "3-item" comment | src/components/creator/creator-layout.tsx | assigned |
| A2 | Ananya (FE) | New `/creator/copilot` page hosting the daily idea (promote DailySuggestionSection) + **pre-connect preview**; register route | src/pages/creator-copilot.tsx, src/App.tsx, src/pages/creator-deals.tsx | assigned |
| A3 | Ananya (FE) | Brand nav wiring for working orphaned pages (pending N1 brand live check) | brand sidebar, src/App.tsx | pending N1 |
| V1 | Vikram (BE) | `GET /creator/disputes` list endpoint (+ DTO, service, controller, test); wire FE api client + page fetch | influora-api creator disputes controller/service, src/lib/api.ts, src/pages/creator-disputes.tsx | assigned |
| N1 | Neha (QA) | Live brand orphaned-route check (contracts, messages, analytics, disputes, reviews, pipeline) → feeds A3 | (read-only) | assigned |
| Q1 | Kavya (QA) | Review all FE+BE diffs before build | — | after A/V |
| M1 | Meera (build) | npm build + tsc + offline mvn tests; GO/NO-GO | — | after Q1 |
| D1 | (deploy) | commit → push → GHCR → updateProjectV1 (BLOCKED on perm) | — | after M1 |
| T1 | Neha + Tester | Live verify each new menu item + copilot + disputes | — | after deploy |

## Verify order (step by step)
1. Creator: Home/Deals/Campaigns/Co-pilot/Analytics/Wallet all reachable from sidebar and render.
2. Co-pilot page shows a preview pre-IG-connect, real idea post-connect.
3. Creator disputes shows full list (no "partial data" banner).
4. Brand: newly-linked pages reachable + render.
