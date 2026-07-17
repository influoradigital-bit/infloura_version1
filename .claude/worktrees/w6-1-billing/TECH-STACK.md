# INFLUORA — TECH STACK

> **Owner:** Priya (CTO) — **LOCKED.** No agent may change this without Priya's sign-off, per company policy.
> **Date:** 2026-07-09
> **Method:** Derived from the actual, running codebase (not aspirational) — `package.json`, `influora-api/pom.xml`, `influora-ai/requirements.txt` read directly.

This file did not exist before this pass. It is now the single source of truth for "what we're actually built on." Where any `wiki/tech/creator/0X_*.md` spec disagrees with this file (e.g. assumes Next.js, Prisma, or Postgres), **this file wins.**

---

## Frontend — `src/` (Vite SPA, NOT Next.js)

| Layer | Choice | Notes |
|---|---|---|
| Framework | **Vite 6 + React 19** | `react-router-dom` v7 for routing — **no** `src/app/`, no `page.tsx`/`loading.tsx` App Router conventions. Any spec assuming Next.js routes is wrong for this repo. |
| Language | TypeScript 5.7, strict | No `any` in new code. |
| Styling | Tailwind CSS v4 (`@tailwindcss/postcss`) | Utility-first, tokens in `tailwind.config`/CSS vars ("Lilac Mist" palette). |
| Components | Radix UI primitives + local `src/components/ui/*` (shadcn-style) | Reuse before inventing new primitives. |
| Animation | Framer Motion 12, GSAP 3 (`@gsap/react`), Lenis (smooth scroll) | `useReducedMotion()` bypass required on every animation. |
| 3D | React Three Fiber 9 + drei | Max 1 WebGL context per page; lazy-loaded. |
| Data fetching | `@tanstack/react-query` v5 + a hand-rolled `src/lib/api.ts` HTTP client | Envelope contract: `{ success, data?, error?, meta? }`. Mock mode (`VITE_API_MODE!=live`) ships mock/demo data; **fails closed** in prod builds (`MockAuthDisabledError`). |
| Forms | `react-hook-form` + `zod` + `@hookform/resolvers` | |
| State | `zustand` | Local/session UI state; server state via react-query. |
| Charts | `recharts` | |

## Backend #1 — `influora-api/` (primary REST API)

| Layer | Choice | Notes |
|---|---|---|
| Framework | **Spring Boot 3.3.5**, Java 21 | REST controllers under `com.influora.web`, services under `com.influora.service`. |
| Auth | Custom JWT (`io.jsonwebtoken` 0.12.6) | Access token (short-lived) + rotating refresh token in **HttpOnly cookie** (never in JS — Kabir A1). `AuthPrincipal` carries `userId`, `userType` (`BRAND`/`CREATOR`/`ADMIN`), `workspaceId`. |
| Database | **MySQL** via Spring Data JPA/Hibernate | **Locked decision** (`wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md`): MySQL only, never Postgres/TimescaleDB, even for time-series (`creator_metrics`/`media_metrics` are plain InnoDB tables). IDs are ULIDs (`com.github.f4b6a3:ulid-creator`), `VARCHAR(26)`. |
| Migrations | **Flyway** (`V1__...sql`, `V2__...`, sequential, never renumbered) | Check the latest `V*` before adding one. |
| File storage | Cloudflare R2 via AWS S3 SDK (`software.amazon.awssdk:s3`) | Presigned URLs for uploads/downloads. |
| Payments | **Razorpay** (`razorpay-java` 1.4.6) + RazorpayX for payouts | Money amounts are always server-derived, never trusted from the client (Guardrail 1). |
| PDF | OpenPDF (contract PDFs) | Approved dep, logged in `wiki/tech/approved-deps.md`. |
| Idempotency | Shared `IdempotencyService.executeOnce` pattern | Mandatory on any mutation reachable by retry/webhook. |
| Testing | JUnit + Mockito; Testcontainers (MySQL) pending CTO sign-off | No integration-test infra yet beyond unit tests — flagged debt. |

## Backend #2 — `influora-ai/` (AI microservice — Meera reasoner, brand safety, site analysis)

| Layer | Choice | Notes |
|---|---|---|
| Framework | **FastAPI 0.115** + Uvicorn | Routes: `app/routes/chat.py` (Meera), `brand_safety.py`, `analyze_site.py`, `voice.py`. |
| LLM providers | Anthropic (`anthropic` 0.42.0), Google Gemini (`google-genai` 0.8.0) | |
| Auth (service-to-service) | JWKS-based JWT verification (`pyjwt[crypto]`) | Cross-repo auth gap tracked in `wiki/decisions/2026-07-07-spring-python-service-auth-jwks-gap.md` — treat as fail-closed until resolved. |
| Scraping | Playwright | Used by `analyze_site.py`. |

**Do not confuse the two backends.** `influora-api` is the system of record (users, money, campaigns, collaborations). `influora-ai` is a stateless reasoning/analysis sidecar the Java backend calls out to — it owns no business data.

## Cross-cutting rules (non-negotiable, enforced in review)

1. **API keys/secrets:** `.env` only, never `VITE_*`/`NEXT_PUBLIC_*`-style client-exposed vars for secrets.
2. **Workspace/creator isolation:** every brand read/write of per-creator or per-workspace data resolves the row **then** checks ownership (`BrandContextService.requireBrandWorkspace` / `requireMember`, or the equivalent `MetricsAuthorizationService` pattern) — never trust an ID path param alone.
3. **Idempotency:** any money-moving or webhook-triggered mutation goes through `IdempotencyService.executeOnce`.
4. **Money amounts are always server-derived** from persisted state (campaign budget, milestone amount) — never accepted from a request body. Money-path engineering standards (wiring tests, fail-open discipline) are LOCKED in `wiki/tech/security.md` — **MP-1** (a money-moving path needs a test asserting the charge *call fires* from every state transition, not just a unit test of the amount; a javadoc is never coverage) and **MP-2** (a discount/entitlement lookup may fail open to the safe global rate but must be observable, never fail-silent).
5. **Every animation** has a `useReducedMotion()` bypass; WCAG AA on all components.
6. **New dependency?** Log it in `wiki/tech/approved-deps.md` first — Maven or npm, no exceptions, CTO sign-off required before merge.
7. **No fabricated backend contracts.** If an endpoint doesn't exist yet, the frontend client throws a typed `NOT_IMPLEMENTED` error in live mode and shows an honest empty/gap state — never silent mock data in a production build. (See `src/lib/api.ts` — this discipline is already established and must be followed by every new creator endpoint too.)

---
*Superseded/clarified specs: any doc under `wiki/tech/creator/` written before this pass describing Next.js `src/app/` routes, Prisma, or Postgres is describing an aspirational architecture that was never built this way — treat the endpoint/entity *shapes* in those specs as a feature reference, not literal implementation instructions. Follow this file and the existing `influora-api`/`src/lib/api.ts` patterns instead.*
