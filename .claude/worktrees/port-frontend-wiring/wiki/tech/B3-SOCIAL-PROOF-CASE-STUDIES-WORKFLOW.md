# B3 · Social Proof / Case Studies — Workflow & Build Spec

> **Owners:** Priya (CTO) · Arjun (routing) · Nisha/Ishaan (content) · **Status:** 🔴 ~5% (hardcoded quotes only)
> **Date:** 2026-07-14 · Closes gap **B3** · Grounded in real code.
> **BLOCKED ON A DECISION** — see §6.

---

## 1. What it is
A managed surface for testimonials, case studies, and a "brands who found creators here" logo wall. B2B brands buy on proof; today there's none that content can update without a deploy.

## 2. Current state (verified)
- `landing.tsx:106` `TESTIMONIALS` array — **hardcoded in the component**, explicitly commented "anonymized, no fake logos."
- A **duplicate** hardcoded testimonial lives in `creator-portfolio-public.tsx`.
- A hardcoded, unverified **"Trusted by 500+ Indian brands"** claim at `landing.tsx:331` — inventory + verify regardless of B3's timeline.
- No `CaseStudy`/`Testimonial` entity, migration, or admin CRUD.

## 3. Build with the current system
| Need | Already exists | How to use it |
|---|---|---|
| Admin CRUD pattern | `PlatformFeeAdminController` / admin console (`src/admin`) | Mirror the existing admin CRUD shape — SUPER_ADMIN/ADMIN + `AdminAuditLog` |
| Content source | `Review` entity (A1, shipped) | A 5-star review is a testimonial **candidate** — but reviews are private/collaboration-scoped with **no public-consent field** today (see §6) |
| Public render | `landing.tsx` TESTIMONIALS map, `Seo.tsx`/`schema.ts` (JSON-LD) | Replace the hardcoded array with a fetch; add `schema.org/Review` structured data for SEO (pairs with B2) |

## 4. Architecture
- **New entity:** `CaseStudy` (title, quote, attribution, meta, logoUrl nullable, published, sortOrder, sourceReviewId nullable). Keep it light — this is not a full CMS.
- **Endpoints:** `GET /case-studies` (public, published only), `POST/PUT/DELETE /admin/case-studies` (admin CRUD → `AdminAuditLog`).
- **Frontend:** `landing.tsx` + `creator-portfolio-public.tsx` fetch from the endpoint (kill both hardcoded copies); admin management screen; logo wall component (behind the logo-permission gate, §6).
- **Seed:** migrate the existing 3 anonymized quotes into the table so nothing visually regresses on day one.
- **Migration:** timestamp-named (`V<timestamp>__case_studies.sql`).

## 5. Task loop (Arjun routing)
| # | Task | Owner | Blocked by |
|---|---|---|---|
| C0 | **Logo policy + review-consent decision** | Swapnil | — (decision) |
| C1 | Spec: entity, public vs admin API, consent flow (GATE) | Priya + Arjun | C0 |
| C2 | `CaseStudy` entity + migration + seed existing 3 quotes | Vikram | C1 |
| C3 | Public `GET /case-studies` + admin CRUD + `AdminAuditLog` | Vikram | C2 |
| C4 | FE: replace both hardcoded arrays with fetch + JSON-LD `schema.org/Review` | Ananya | C2 |
| C5 | Admin management screen + (gated) logo wall | Ananya | C3 |
| C6 | Content: write/curate the real case studies | Nisha + Ishaan | C1 |
| C7 | VERIFY: QA → mvn verify + tsc → Priya sign-off | Kavya/Meera/Priya | C2–C6 |

## 6. Decisions needed from Swapnil (the blockers)
1. **Client logos** — `landing.tsx:324` cites `CEO-DECISIONS.md #4`: **no client logos without written permission.** The logo wall is a legal/product gate, not engineering. Approve a permission process or drop logos from scope.
2. **Promoting A1 reviews to public testimonials** — reviews are private with no public-consent field. Approve adding an **opt-in consent mechanism** before any private review is surfaced publicly.

**Safe slice available now** (no decision needed): the table + admin CRUD + public endpoint, seeded with the existing 3 anonymized quotes, **no logos, no auto-import of private reviews.** That already removes the hardcoded-in-component debt and is ~M-sized. The logo wall + review-promotion wait on §6.
