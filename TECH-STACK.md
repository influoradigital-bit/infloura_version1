# TECH STACK — Influora Platform
**Owner:** Priya (CTO)  
**Last verified:** 2026-09-07 — versions re-read from `package.json`, `influora-api/pom.xml`,
`influora-ai/requirements.txt` and `application.yml`, not from memory.

---

## Frontend
- **Framework:** React **19** + Vite **6.4.3** (NOT Next.js)
- **Language:** TypeScript **5.7.3**
- **Routing:** React Router **7.18**
- **Styling:** Tailwind **4.2** + shadcn/ui components
- **State:** Zustand **5.0** (lightweight stores), React Query **5.100** (server state)
- **Animation:** Framer Motion **12.38** — import from `framer-motion`
- **Test:** Vitest **3.2**
- **Build:** Vite (ESM-first, fast HMR)
- **Package manager:** npm

### ⚠️ Tailwind 4 is CSS-first — there is no config file
There is **no `tailwind.config.js`/`.ts` in this repo, and adding one does nothing.**
All design tokens live in `@theme` inside `src/app/globals.css`. That file is the single
source of truth for colour, radius and the `--stage-*` / `--chart-*` / `--sidebar-*` scales.

This matters right now: any HTML imported from an external design tool (Stitch, v0,
Lovable) will be **Tailwind v3** and will ship a `tailwind.config` block. That block is
inert here. Port the tokens into `@theme`; do not create a config file to accommodate it.

**Key directories:**
- `src/pages/brand-*.tsx` — brand-facing pages  
- `src/pages/creator-*.tsx` — creator-facing pages  
- `src/components/` — reusable UI (brand/, creator/, shared/)  
- `src/lib/api.ts` — centralized API client  
- `src/hooks/` — custom React hooks  

---

## Backend (Java + Spring Boot)
- **Language:** Java **21**
- **Framework:** Spring Boot **3.3.5**
- **Database:** MySQL 8 — `mysql-connector-j`, `org.hibernate.dialect.MySQLDialect`.
  **There is no Prisma anywhere in this project.** Schema is owned by Flyway migrations.
- **ORM:** JPA + Hibernate
- **Migrations:** Flyway (`db/migration/V*.sql`)
- **Build:** Maven
- **Package:** `influora-api/`  

**Key paths:**
- `influora-api/src/main/java/com/influora/web/` — REST controllers  
- `influora-api/src/main/java/com/influora/service/` — business logic  
- `influora-api/src/main/resources/application.yml` — config  
- `influora-api/src/main/resources/db/migration/` — Flyway SQL migrations  

---

## AI Service (Python + FastAPI)
- **Framework:** FastAPI **0.115.6** on uvicorn **0.34.0**
- **Location:** `influora-ai/`
- **Models in the tree:** `claude-sonnet-4-5-20250929`, `claude-opus-4-1-20250805`,
  `claude-haiku-4-5-20251001`, `claude-3-5-haiku-20241022`, `gemini-2.5-flash`,
  `gemini-2.5-flash-lite`, `gemini-2.0-flash`
- **Routes:** `/chat`, `/analyze_site`, `/brand_safety`  

---

## Infrastructure
- **Dev:** localhost (FE:**3000** Vite, BE:8080 Spring, AI:8000 FastAPI)

  > ⚠️ The frontend dev port is **3000** — `vite.config.ts:68` (`PORT` env overrides it),
  > and `.claude/launch.json` agrees. But `application.yml:133` still defaults
  > `INFLUORA_WEB_BASE_URL` to `http://localhost:5173`. Anything the backend generates
  > against that default — verification links, password-reset links, OAuth redirects —
  > points at a port nothing is serving. Set `INFLUORA_WEB_BASE_URL` explicitly in local
  > env, or fix the default. Unowned as of 2026-09-07.
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
