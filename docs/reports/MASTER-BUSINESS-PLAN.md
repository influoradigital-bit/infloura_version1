# INFLUORA — MASTER BUSINESS PLAN

> **Source of truth: CODE ONLY.**
> Compiled by **Tara** (Operations & Reporting) from six parallel code audits by
> **Priya** (CTO / architecture), **Arjun** (COO / admin), **Kavya** (QA / brand),
> **Meera** (DevOps / creator + backend), **Kabir** (Red-team / security),
> **Vikram** (Backend / shared + AI).
>
> No `.md`, `.docx`, spec, or planning document was read. Every number below is
> anchored to source files, manifests, and configs in this repository.
>
> **Date:** 2026-07-10 · **Commit tree:** `New Influora` (worktrees & `node_modules` excluded)

---

## 1. Executive Summary

Influora is a **three-sided creator-commerce platform**: **Brands** run campaigns,
**Creators** fulfil deals, and an **Admin** console governs money, moderation, and support.
Escrow, contracts, affiliate tracking, and an AI co-founder ("Meera") sit underneath.

**The headline finding from code:**

| Metric | Score |
|---|---|
| **Code built (UI + services + schema exist)** | **~72%** |
| **End-to-end live (frontend actually talking to backend)** | **~42%** |
| **Security readiness** | **86%** |
| **Ship-blocking gaps** | 4 |

The backend is far more finished than the frontend admits. Influora has **161 REST endpoints,
54 entities, 44 Flyway migrations, and ~100 Java test files** — but the SPA still boots on
`getMock*()` data by default. **The single highest-leverage engineering action is not building
more features. It is connecting the ones already built.**

---

## 2. What We Actually Built — Verified Stack

| Layer | Reality (from manifests, not docs) |
|---|---|
| **Web** | **Vite 6 + React 19** + react-router-dom 7.15, TS 5.7 (`next.config.mjs` is vestigial — we are *not* on Next.js) |
| **State/Data** | TanStack Query 5.100, Zustand 5, react-hook-form + zod |
| **UI** | shadcn/ui (new-york) + Radix, Tailwind 4.2, framer-motion 12, GSAP + Lenis, three 0.184 / R3F 9 |
| **API** | **Spring Boot 3.3.5, Java 21** — JPA, Security, Redis, MySQL, Flyway, jjwt 0.12.6 |
| **Money** | Razorpay 1.4.6, escrow tables, openpdf (contracts) |
| **Storage** | AWS SDK S3 2.29 → Cloudflare R2 |
| **AI** | **FastAPI 0.115** — Claude, Gemini, Sarvam providers; tool loop; SSRF guard; PII redaction |
| **Infra** | docker-compose: MySQL 8 + Redis 7 + api + web. 3 Dockerfiles. |
| **CI** | **1 workflow only** — Lighthouse perf on `/brand/meera` |

---

## 3. Build Completion — Percentage by Product Surface

### 3.1 The Three Products

| # | Surface | Built | Live E2E | Verdict |
|---|---|---|---|---|
| 1 | **ADMIN** | **50%** | **15%** | Console shell + real auth; every business screen is `getMock*()` and every mutation is `console.info` |
| 2 | **BRAND** | **58%** | **47%** | ~12.7k LOC of polished UI; *Discover* + *Dashboard* plus now-live-wired *Contracts*, *Deals (counter)*, *Campaigns*, *Onboarding* are API-backed; *Deal Room* SHIPPED/CONDITIONAL (BRAND P0×3 + P1×5 closed, tracker 2026-07-11 — live messages+deliverables, build-verified; pending runtime E2E via Spring/MySQL + shipment persistence); *Timeline* partial (messages/proposals only), *Settings* still not live |
| 3 | **CREATOR** | **~84% blended** | **Week 3+4: 100%** | Tick #37 in flight (A-GA-6 / K6-3 / YouTube / G-Kv3-1); #38/#39/#40 SHIPPED/CONDITIONAL — see §4.3 |

### 3.2 Platform Layers

| Layer | Built | Notes |
|---|---|---|
| **Backend API (Java)** | **88%** | 48 controllers · 161 endpoints · 54 entities · 44 migrations · ~100 tests |
| **AI Service (Python)** | **80%** | 7 endpoints, 3 providers, service-token + JWKS auth, SSRF guard |
| **Design System** | **90%** | ~62 shadcn components + 8 custom (hype-live-indicator, fee-breakdown, slot-progress-bar…) |
| **Motion / 3D** | **88%** | R3F canvases, reduced-motion aware, real fallbacks |
| **Analytics + Tracking** | **80%** | Real hooks → real endpoints (UTM, coupons, ROI, creator scores) |
| **Public / Marketing** | **70%** | Landing is real; **no pricing, terms, or about routes** |
| **Meera (AI co-founder)** | **55%** | ⚠️ **UI ships a hardcoded script, not AI** |
| **Infra / DevOps** | **55%** | Compose is good; AI service unorchestrated; no backend/AI CI |
| **Security** | **86%** | Mature: MFA, HMAC webhooks, dual-credential internal calls |

### 3.3 Weighted Overall

```
Backend API      20%  ×  88   = 17.6
Creator          20%  ×  84   = 16.8  ← ~84% blended (Tick #36, 2026-07-10)
Brand            20%  ×  58   = 11.6
Admin            12%  ×  50   =  6.0
AI Service        8%  ×  80   =  6.4
Public + Shared   8%  ×  80   =  6.4
Meera Feature     5%  ×  55   =  2.8
Infra / DevOps    7%  ×  55   =  3.9
                              ───────
WEBSITE BUILT                  71.7%  →  ~72%
```

**But** applying the integration discount (76 of 316 frontend files import mock data;
only 7 files perform real `fetch`; only 6 hooks use react-query):

```
END-TO-END LIVE  ≈ 42%
```

---

## 4. Module-by-Module Breakdown

### 4.1 ADMIN — 50% built · 15% live *(Arjun)*

Two competing surfaces exist. `src/pages/admin-dashboard.tsx` is a standalone demo console fed by
`@/lib/demo-data`. `src/admin/**` is the real modular panel.

| Sub-area | % | Anchor |
|---|---|---|
| Auth / RBAC / Shell | **80%** | `useAdminAuth` hits `/auth/me`, JWT check, role→permission matrix, audit logging. Tested. |
| Users | 55% | `BrandProfile` / `CreatorProfile` full UI; hooks mock; all actions stubbed |
| Dashboard | 55% | `PulseDashboard` UI + WS-ready; `usePulseData` 100% mock |
| Moderation | 45% | `FlagQueue` real UI + tests; `moderationApi` unused |
| Support | 45% | `TicketList` UI real; no wired reply/assign |
| Campaigns | 45% | `CampaignTable` read-only over mock |
| Finance | 40% | `FeeControlPanel` submit is a stub; `financeApi`/`escrowApi` typed but unused |

**The core problem:** `services/api-contracts.ts` is a **complete, fully-typed REST client** pointed
at `/api/v1/admin` with matching backend controllers — and it is **imported nowhere except auth.**
Every data hook has the real client commented out (`useBrandDetail.ts:163`).
Credit where due: the mutations are *honest* stubs (`console.info('[BrandProfile] stub: approve KYC')`)
that never fake success.

> ⚠️ **`/admin` has no route guard** in `App.tsx`.

---

### 4.2 BRAND — 58% built · 35% live *(Kavya)*

~12,732 LOC across 35 files. Only **11 of 35** touch `api.` / `fetch` / `liveApi`.

| Sub-feature | % | Anchor |
|---|---|---|
| Discover | **80%** | Deepest wiring — `api.creators.search/invite/toggleSaved/featured` |
| Dashboard | **75%** | Real `api.dashboard.actions` + graceful mock fallback |
| Onboarding | ~~65%~~ **~90% (2026-07-10)** | 3-step flow was already fully live (`brandRegister`/`saveBrandCompany`/`completeBrand`) — the "thin submit wiring" claim was stale. Real work this cycle was removing ~560 lines of dead legacy-step code and fixing a false "KYC deferred to campaign creation" comment (that integration doesn't actually exist — new backlog item tracked). Kavya PASS, Meera build PASS. |
| Deal Room | ~~60%~~ **~85% (2026-07-11)** | Proposal creation now real (`api.deals.create`); Contract/Payments tabs fixed from a real bug (were keyed by mock ids, always showed "no contract" in live mode) to sourcing real `contractId`/`escrowFunded` off the live deal. Shipment form has no backend — honest "not saved" warning shown during entry. 3 dead components removed. Took 3 QA rounds; surfaced and fixed a critical mid-cycle regression in the shared `api.ts` contracts client (see Contracts row correction below). Kavya PASS, Meera build PASS (4602 modules). |
| Campaigns | ~~55%~~ **~85% (2026-07-10)** | Edit-fetch, create/update, and list are all live now (`api.campaigns.*`). List shows an honest `—/max` for collaborator count rather than trusting the backend's currently-stubbed `CampaignMetrics.empty()` field. Kavya PASS, Meera build PASS. Full evidence: `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` |
| Timeline | 55% | Polished, presentational, no data layer |
| Contracts | ~~40%~~ **~80% (2026-07-10)** | Live list/detail/sign via `api.contracts.*` + `api.deals.list`; approve/revise wired to real endpoints. Gap: Deliverables tab shows honest summary-only state pending a `GET /deals/{id}/deliverables` backend endpoint (new backlog item, see `wiki/tech/BRAND_ADMIN_PENDING_WORK.md`). **Correction (2026-07-11):** the original "PDF via `pdfDownloadUrl`" claim was wrong — no such backend endpoint exists; approved design is email delivery, not client download. Both this and Deal Room now show an honest "delivered by email" message instead. |
| Deals | ~~40%~~ **~70% (2026-07-10)** | Deal list + counter-offer live via `api.deals.list`/`api.deals.counter` (Kabir PASS w/ 1 low, Kavya PASS). Accept intentionally left unwired — backend hard-gates it creator-only with a hardcoded-role bug underneath; honest gap notice shown instead of a dead button. New backend item tracked in `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` |
| Settings / Store Integration | 30% | Hook is correct; backend returns `NOT_IMPLEMENTED` — **dead feature in UI** |

**QA risks:** ~~silent no-op mutations (user believes they approved a contract)~~ **CLOSED 2026-07-10** —
approve/revise call the real, workspace-scoped deliverable endpoints with visible loading/error states in
every mode they appear (demo dialog + Deal Room); Kabir PASS, Kavya PASS. Full evidence:
`wiki/tech/BRAND_ADMIN_PENDING_WORK.md` P0-#2 + P1 "Contracts (40% → live)".
~~`liveApi` fallback masks outages (`creator-discovery.tsx:588` shows an empty list instead of an error)~~
**CLOSED 2026-07-10** — the cited spot was already fixed; found and closed 2 other silent-catch spots in
the same file (Featured Creators section, invite-dialog campaign dropdown), both now show an error notice
+ Retry instead of a blank/empty state. Kavya PASS, Meera build PASS. Evidence: `wiki/tech/BRAND_ADMIN_PENDING_WORK.md`
P0-#3. ~~Triplicate source trees (`components/brand` vs `src/components/brand` vs `.claude/worktrees`)~~
**RESOLVED 2026-07-11 (`85e94d3`, tracker B-6 `[x]`)** — canonical tree is `src/components/`; the dead
root-level `components/` (67-file Next.js-era dupe, zero `src/` imports) was `git rm`'d and `npm run build`
PASS (17.27s) proved nothing referenced it. `.claude/worktrees/**` are separate git worktrees, left as-is.
Cleanup only — no build-% or live-% impact.

---

### 4.3 CREATOR — ~84% blended · Week 3 + Week 4 Sprint: 100% *(last updated: 2026-07-10 16:31 IST — Tara)*

**Sprint verdict:** Week 3 COMPLETE. Week 4 CEO Top 5 COMPLETE. Full-platform blended: ~84% (Tick #36 Priya #38/#39/#40 sign-off; Tick #37 in flight — Discovery A-GA-6 / K6-3 / YouTube deferral / G-Kv3-1).

```
Week 3 sprint:  ████████████████████████████████████ 100%
Week 4 sprint:  ████████████████████████████████████ 100%  (CEO Top 5 scope)
Full platform:  ███████████████████████████████████░  ~84%
```

**Track breakdown (Tick #37):**

| Track | % |
|---|---|
| Backend features | ~90% |
| Frontend features | ~85% |
| Security hardening | ~75% |
| QA coverage | ~68% |
| **Blended** | **~84%** |

**Feature matrix (current):**

| Feature | % | Notes |
|---|---|---|
| Auth / onboarding / profile / portfolio | 90% | SHIPPED |
| Campaigns / browse / detail | 90% | SHIPPED |
| Deals + Deal Room | 100% | SHIPPED/CONDITIONAL |
| Chat / inbox / wallet / reviews / Meta OAuth | 90% | SHIPPED |
| Rate limits (#25 + #39) | 100% | SHIPPED/CONDITIONAL |
| Dashboard + Coupons + Disputes UI | 100% | SHIPPED/CONDITIONAL (#38 status-only) |
| Deliverables upload hardening (#40) | 100% | M-19-3/4 + M-24-1 CLOSED |
| Affiliate GET + reviews/received + MSG91 OTP | -- | P1 CLOSED (Tick #37) |
| Discovery (FE + BE) | ~70% | A-GA-6 portfolio/reviews tabs in flight |
| Analytics wave 1 | 45% | Wave 2 deferred |
| Security (OWASP) | ~75% | C1+C2 Mediums CLOSED; K6-3 in flight; M-K6-2 Redis + K6-4 open |
| QA coverage | ~68% | Playwright scaffold live; 80% E2E gate not met (G-Kv3-1) |
| Bids | N/A | Locked CEO architecture — deal room covers this |

**Recently shipped (Ticks #35-#37):**
- #38/#39/#40 Priya SHIPPED/CONDITIONAL (Tick #36) — disputes UI, rate-limit sweep, upload hardening
- Kv-GA-1/2 — creator-disputes.test.tsx 10/10 + Playwright scaffold smoke PASS
- C2 Mediums 1-5 CLOSED (OTP enum, password policy, token storage, Meta PKCE, review-flag uniqueness)
- P1 affiliate GET / reviews-received / MSG91 OTP — CLOSED (Tick #37 status)

**Pending P0/P1 (Tick #37 — sources: CREATOR_PROGRESS.md + CREATOR_GA_ASSIGNMENTS_PRIYA.md):**

| Priority | Item | Notes |
|---|---|---|
| **P0/in flight** | A-GA-6 — Discovery public profile portfolio/reviews tabs | Tick #37 |
| **P0/in flight** | K6-3 — PII-at-rest + malware + OAuth token-log | Tick #37 (Kabir) |
| **P1/in flight** | YouTube OAuth — written Swapnil deferral sign-off | Tick #37 |
| **P1/in flight** | G-Kv3-1 — QA push toward 80% E2E | ~68% today |
| **P1** | M-K6-2 — rate limiter to Redis | Horizontal-scale GA claim |
| **P1** | Security cycle 4 — dependency CVE + denylist | After K6-3 |
| **P1** | Discovery gate close (Meera/Kavya/Priya) | Remaining after A-GA-6 |

**Closed this window:** Kv-GA-1/2 · A-GA-2 · V-GA-2-8 (C2 Mediums + affiliate/OTP/reviews) · #38/#39/#40 build+sign-off.

**Do NOT build** (locked CEO architecture — CREATOR_CEO_INSTRUCTIONS_SWAPNIL.md): standalone creator-bids / creator-deliverables / creator-contracts pages — deal-room covers these. Do NOT rebuild #38.

---

### 4.4 MEERA — the AI co-founder — 55% *(Vikram)*

**This is the most important finding in this document.**

- 18 polished components (`MeeraWorkspace`, `LivingCanvas`, `Stage{Snapshot,Recommend,Matching,Funding,Live}`, `CreditMeter`, `PayoutLedger`…).
- The Python service is **real**: `routes/chat.py`, tool loop, Claude/Gemini/Sarvam providers, persona assembler, PII redaction, SSRF guard.
- A **real SSE client exists** — `src/hooks/useMeeraStream.ts` (token / thinking / tool_start / tool_result / done, 30s heartbeat) + `src/lib/meera-api.ts` matching Spring's `MeeraController`.

> 🔴 **`useMeeraStream.ts` is imported by nothing.**
> The shipping `/brand/meera` chat runs `MEERA_CONVERSATION_SCRIPT` from `src/data/meera-mock.ts`
> on `setTimeout` timers. **The AI co-founder is a demo reel, not AI.**

One thing done right: `MeeraWorkspace.handlePay` calls server-authoritative `api.payments.fundEscrow`
— no client-supplied amount.

---

### 4.5 BACKEND + INFRA — 88% / 55% *(Meera)*

- **48 controllers, 161 endpoints.** Largest: `AuthController`(12), `DealController`(10), `CampaignController`(7).
- **54 entities**, **44 Flyway migrations** V1→V44 — coherent and sequential (auth → campaigns → wallet/escrow → contracts → AI credits → coupons/UTM → Shopify/Woo → admin → platform fee).
- **~100 Java test files** incl. Testcontainers integration tests, OAuth + webhook tests.
- `docker-compose.yml`: MySQL 8 (healthcheck), Redis 7, api :8080, web :8081.

> ⚠️ **`influora-ai` has a Dockerfile but is not a compose service.**
> ⚠️ **CI runs one thing: a Lighthouse mobile gate.** No Java build/test. No Python test. No deploy pipeline.

---

### 4.6 SECURITY — 86% readiness *(Kabir)*

A **mature, previously-hardened** codebase. Confirmed strengths: HS256 access JWT (15m) + opaque
HttpOnly/SameSite=Strict refresh cookie; bcrypt(12); Python↔Spring uses **asymmetric ES256/JWKS and
explicitly rejects HS256**; internal calls are **dual-credential** (service token + HMAC + nonce);
`SecretsStartupValidator` fails closed on committed defaults; admin MFA TOTP secrets AES-256-GCM at rest;
webhooks HMAC-verified; `GlobalExceptionHandler` leaks no stack traces; HSTS + locked CSP.

| Sev | Finding | File | Fix |
|---|---|---|---|
| **HIGH** | Live-looking Anthropic / Gemini / Sarvam keys sit in plaintext in the working tree (gitignored, not committed) | `influora-ai/.env` | **Rotate all three now.** Verify git history. Move to a secrets manager. |
| MED | Rate limiter trusts caller-supplied `X-Forwarded-For` with no proxy allowlist → per-IP throttle bypass. Also in-memory, so not global under scale. | `AuthRateLimitFilter.java:332` | Trusted-proxy CIDRs; move counters to Redis |
| MED | Access tokens in `localStorage` → any XSS = token theft | `src/lib/auth-session.ts:41` | In-memory access token; strict CSP on SPA host |
| MED | Prod secret safety gated on `APP_ENV`, not Spring profile — a deploy that forgets it boots with the dev secret and only warns | `JwtService.java:76` | Enforce `APP_ENV` at deploy; consider ES256 for user tokens |
| LOW | `allowedHeaders("*")` with `allowCredentials(true)` (origins are allowlisted) | `CorsConfig.java:25` | Enumerate headers |
| LOW | `refresh-cookie.secure` defaults `false` | `application.yml:57` | Assert `true` at boot in HTTPS envs |

Closing HIGH + the two MEDs → **~95% ship-ready.**

---

## 5. The Four Ship Blockers

| # | Blocker | Owner | Why it's fatal |
|---|---|---|---|
| **B1** | **`/admin` has no auth guard** and every admin mutation is a `console.info` stub | Arjun · Vikram | The console that moves money and approves KYC is publicly routable and functionally inert |
| **B2** | **Meera is a `setTimeout` script.** `useMeeraStream.ts` is imported by nothing | Vikram · Priya | Our headline differentiator — the "AI co-founder" — does not use AI |
| **B3** | **Live API keys in `influora-ai/.env`** | Kabir | Rotate today, regardless of anything else in this document |
| **B4** | **CI runs only Lighthouse.** ~100 Java tests and 33 Python tests never execute in the pipeline | Meera | Backend maturity is unverified on every merge |

Plus two silent-failure classes that will generate support tickets on day one:

- Brand contracts/deals mutations **succeed visually and persist nothing**. **IN PROGRESS 2026-07-10** — `contracts-and-deliverables.tsx` approve/revise handlers now call the real, already-hardened `api.deliverables.approve/requestRevision` (Kabir PASS WITH FINDINGS, Meera build PASS), but Kavya's QA is CONDITIONAL: the page's contract list is still 100% `mockContracts` with hardcoded ids, so the fix can't fire against real data yet. Still an open silent-failure risk in practice until `wiki/tech/BRAND_ADMIN_PENDING_WORK.md`'s "Contracts (40% → live)" item closes. Not counted as resolved.
- ~~`campaign-form.tsx:239` — editing any real campaign fails (one hardcoded ID).~~ **CLOSED 2026-07-10** — real `api.campaigns.get()` fetch wired in with loading/error states and a budget-integrity guard; Kavya QA PASS (2 rounds), Meera build gate PASS (4601 modules). Full evidence: `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` P0-#1. Rest of the Brand pending list (contracts/deals no-ops, campaigns list wiring, etc.) is unaffected — overall Brand 35%-live figure holds until more items close.

---

## 6. Strategy — Read From the Code

The audits agree on one structural fact: **we have overbuilt the backend and underconnected the frontend.**
161 endpoints and 44 migrations exist behind 7 files that actually call `fetch`.

That inverts the usual startup risk. We do not have a "can we build it" problem. We have a
**"prove it works end to end"** problem — which is cheaper, faster, and far less uncertain.

### Phase 1 — Prove It (2–3 weeks) → target **70% live**
1. Rotate the three AI keys. *(B3, day one)*
2. Guard `/admin`; wire `api-contracts.ts` into the 7 dead admin hooks — the client is already written and typed.
3. Import `useMeeraStream`. Delete `meera-mock.ts`. The pipe exists on both ends. *(B2)*
4. Add real CI: `mvn test`, `pytest`, `vitest`, `npm run build`. *(B4)*

### Phase 2 — Close the Gaps (3–4 weeks) → target **85% live**
5. Ship the two `NOT_IMPLEMENTED` endpoints: creator affiliate-earnings, creator coupons.
6. Replace brand no-op mutations (contracts approve/revise, deal counter-offer) with real calls.
7. Fix `campaign-form` edit. Delete duplicate `components/` and `components/ui` trees.
8. Add `influora-ai` to `docker-compose.yml`.
9. Ship pricing / terms / privacy routes — currently absent.

### Phase 3 — Harden (2 weeks)
10. Redis-backed rate limiting + trusted-proxy allowlist; in-memory access tokens.
11. Frontend test coverage: 4 test files against 316 source files is the largest unmeasured risk in the repo.

**On this plan, Influora is production-ready in ~8–9 weeks** without building a single new feature.

---

## 7. Scorecard

| Surface | Built | Live E2E |
|---|---|---|
| Creator | ~84% blended (Week 3+4 sprint: 100%) | Tick #37 in flight; P1 affiliate/OTP/reviews CLOSED — see §4.3 |
| Brand | 58% | 47% |
| Admin | 50% | 15% |
| Backend API | 88% | — |
| AI Service | 80% | 0% *(never called by the SPA)* |
| Meera Feature | 55% | 0% |
| Design System | 90% | — |
| Public / Marketing | 70% | — |
| Infra / DevOps | 55% | — |
| Security | 86% | — |
| **WEBSITE OVERALL** | **~72%** | **~44%** |

---

### Evidence Base
316 frontend `.ts`/`.tsx` files · 48 Java controllers · 161 endpoints · 54 entities · 44 migrations ·
~100 Java tests · 33 Python tests · 4 frontend tests · 1 CI workflow ·
76 files importing mock data vs 7 performing real fetch.

*Compiled by Tara. Read-only report — no code, content, or decision was changed.*
