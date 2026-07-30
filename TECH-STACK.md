# TECH STACK — Influora Platform
**Owner:** Priya (CTO)  
**Last verified:** 2026-07-30

---

## Frontend
- **Framework:** React 18 + Vite (NOT Next.js)
- **Routing:** React Router v6  
- **Styling:** Tailwind CSS + shadcn/ui components  
- **State:** Zustand (lightweight stores), React Query (server state)  
- **Build:** Vite (ESM-first, fast HMR)  
- **Package manager:** npm  

**Key directories:**
- `src/pages/brand-*.tsx` — brand-facing pages  
- `src/pages/creator-*.tsx` — creator-facing pages  
- `src/components/` — reusable UI (brand/, creator/, shared/)  
- `src/lib/api.ts` — centralized API client  
- `src/hooks/` — custom React hooks  

---

## Backend (Java + Spring Boot)
- **Language:** Java 21  
- **Framework:** Spring Boot 3.x  
- **Database:** MySQL 8 (Prisma-managed schema, but this is Spring not Prisma — mistake in old doc)  
- **ORM:** JPA + Hibernate  
- **Build:** Maven  
- **Package:** `influora-api/`  

**Key paths:**
- `influora-api/src/main/java/com/influora/web/` — REST controllers  
- `influora-api/src/main/java/com/influora/service/` — business logic  
- `influora-api/src/main/resources/application.yml` — config  
- `influora-api/src/main/resources/db/migration/` — Flyway SQL migrations  

---

## AI Service (Python + FastAPI)
- **Framework:** FastAPI  
- **Location:** `influora-ai/`  
- **Models:** Claude Sonnet 4.5 (chat), Gemini 2.5 Flash (site analysis)  
- **Routes:** `/chat`, `/analyze_site`, `/brand_safety`  

---

## Infrastructure
- **Dev:** localhost (FE:5173 Vite, BE:8080 Spring, AI:8000 FastAPI)  
- **Prod:** Hostinger VPS (Docker Compose)  
- **CI:** GitHub Actions (build + publish Docker images)  
- **Payments:** Razorpay (Order API + RazorpayX payout) — keys NOT provisioned yet  

---

## Standards
- **Branching:** feature branches → PR → main  
- **Code style:** Prettier (FE), spotless (Java BE)  
- **Testing:** Vitest (FE unit), JUnit (BE unit), Playwright (E2E — optional)  
- **QA gate:** Kavya reviews ALL code before Meera local-run verify  
- **Security gate:** Kabir OWASP red-team after QA, before Priya sign-off  

---

## UI Honesty (LOCKED — Priya, 2026-07-30)

**Rule: a control may not represent state it does not persist.**

Every interactive control is in exactly one of three states. There is no fourth.

1. **Wired** — calls a real endpoint, persists, reflects server truth on reload.
2. **Absent** — not rendered. Default for anything without a backend.
3. **Disabled + captioned** — rendered `disabled` with a visible reason. Allowed **only** when the control is a placeholder for work already scheduled.

Explicitly banned:
- A `Switch` or input that holds local state and persists nothing. A toggle that flips and survives no reload is a lie, and on a security control (2FA, permissions, privacy) it is a **security defect**, not a cosmetic one.
- Hardcoded sample data rendered as if it were the user's own (fake cards, fake members, fake invoices).
- A numeric fallback of `0` for "no data". `0%` under a caption like "Excellent" is worse than an empty state. Missing data renders as an explicit *not available / not yet scored* state.

Kavya rejects any diff that adds a control in none of the three states.

## Score Exposure (LOCKED — Priya, 2026-07-30)

Creator scores live in `creator_scores` (append-only, one row per creator per run). Reading them is a **denormalized read, not an analytics computation** — surfacing them outside the analytics module is not a layering violation.

Constraints on any endpoint that exposes them:
- **One DTO shape.** `DiscoveryDtos.CreatorScores(quality, authenticity, brandSafety)` is the canonical projection. Do not add loose score fields to sibling DTOs and do not invent a second shape.
- **Batch or don't ship.** Because the table is append-only, a batch read is a greatest-n-per-group query over `idx_creator_scores_creator_time`. A per-row `findFirstBy...OrderByTimeDesc` inside a `.map()` over a result page is an automatic QA reject.
- **Null is a value.** Absent scores are `null` and must render as "not yet scored". Never coerce to `0`.
- `/analytics/creators/{id}/scores` is **not** reusable for discovery — it requires a Meta OAuth relationship between the workspace and the creator and 403s otherwise.

## AI Cost Gates (LOCKED — Priya, 2026-07-30)

- The global AI spend ceiling (`AI_DAILY_SPEND_CEILING_USD`) is **shared across every AI feature**. Any new scheduled AI workload must be costed against the remaining headroom, not against zero — starving Meera is a production outage.
- No AI feature flag flips to `true` in prod without a measured per-run cost from a real dry run. Estimates do not count.
- A model may not be pinned to a larger tier than the task needs. Bounded, schema-validated classification defaults to the cheapest model that passes eval.
- Do not bill for output nothing reads. If a required response field has no consumer, remove it from the schema.

---

## Communication
- SHARED_CONTEXT.md = active task bus  
- wiki/ = completed work archive  
- Pointers not payloads (file paths, never paste full files)  
