# Build Plan — Creator "My Applications" Page
**Owner:** Priya (CTO) · **Contributors:** Vikram (BE), Ananya (FE), Kavya (QA), Kabir (Security), Meera (Build/Verify), Neha (E2E)
**Date:** 2026-07-24 · **Status:** APPROVED FOR BUILD (pending Swapnil sign-off on deploy)

## Problem
After a creator applies to a campaign, the "Applied" state shows **only** as a badge on the campaign card / detail page. There is no consolidated place to see "every campaign I applied to and where it stands." This page fills that gap.

---

## CTO arbitration (decisions that override the raw expert drafts)

1. **Source of truth = `Collaboration` rows where `source = APPLICATION`.** (Vikram + Kabir.) This excludes brand invites and — because `source` never mutates — still captures an application after it progresses to a deal. Do **not** reuse the loose `applicationStatus` mapping from the campaign browse path.
2. **There is NO `REJECTED` status.** (Kabir + Vikram — Ananya's draft assumed one; it does not exist.) Rejection/withdrawal is `CANCELLED`. We will **not** render a "Rejected" label — see the status map below (Kabir R5: never surface brand-internal triage as a decision the brand hasn't finalized).
3. **Campaigns have no cover image.** (Vikram.) The card uses the **brand logo / initial**, not a campaign cover. Don't fake a cover.
4. **Server owns the status label.** DTO returns raw `status` + a computed `statusLabel`; the FE has one shared helper as the single source of truth (no duplicated enum maps).
5. **Hard-cap pagination** (limit ≤ 50) and **allowlist the DTO** — never serialize the raw two-party `Collaboration` entity. (Kabir R2/R3.)

### Creator-facing status map (canonical)
| CollaborationStatus | Creator label | Filter bucket | Click-through |
|---|---|---|---|
| APPLIED | Applied | Applied | Campaign detail |
| SHORTLISTED | Shortlisted | Shortlisted | Campaign detail |
| IN_NEGOTIATION, TERMS_AGREED | In negotiation | In negotiation | Deal room |
| CONTRACT_PENDING | Contract pending | Active | Deal room |
| CONTRACTED, IN_PROGRESS, REVIEW_PENDING, REVISION_REQUESTED | Active | Active | Deal room |
| COMPLETED | Completed | Completed | Deal room |
| CANCELLED | Closed | Closed | Campaign detail (view-only) |
| DISPUTED | In dispute | Active | Deal room |

Filter tabs: **All · Applied · Shortlisted · In negotiation · Active · Completed · Closed.**

---

## Backend (Vikram) — no migration needed
New endpoint **`GET /api/v1/creator/applications`** (optional `?status=` + `?page=&limit=`).
- `web/CreatorApplicationController.java` (new) — `@RequestMapping("/creator/applications")`, identity from JWT only (no id param).
- `service/CreatorApplicationService.java` (new) — `requireCreatorProfile(principal).getUserId()` → `collaborationRepository.findByCreatorIdAndSource(userId, APPLICATION)`, in-memory status filter + sort (newest first) + slice; batch-load campaigns & workspaces (no N+1).
- `service/CreatorApplicationMapper.java` (new) — maps to the allowlist DTO + computes `statusLabel`.
- `web/dto/creatorcampaign/CreatorApplicationDtos.java` (new) — `CreatorApplicationListItem { campaignId, campaignTitle, brandName, brandLogoUrl, appliedAt, status, statusLabel, agreedRate?, currency?, dealId(=collaborationId) }`.
- `repository/CollaborationRepository.java` — add `findByCreatorIdAndSource(String creatorId, CollaborationSource source)` (derived query).
- **No DB migration, no index change** for launch (per-creator row counts are small; promote to composite `(creator_id, source)` index + DB-level `Pageable` only if it grows).

## Security (Kabir) — block PR on either anti-pattern
- **R1 IDOR:** scope by `principal` user id via `requireCreatorProfile`; the `creator_id` predicate must be in SQL. **Reject** any `findAll()`/`findByCampaignId()` + in-memory `.filter(creatorId==)`.
- **R2 minimization:** hand-written DTO allowlist; **never** `return collaboration;`. Exclude brand notes, budget internals, other applicants, applicant counts.
- **R3 pagination cap:** clamp `limit` to ≤50; normalize page ≥1.
- **R5 status disclosure:** use the creator-facing map above; `CANCELLED → "Closed"`, never "Rejected". Confirm the rejection→CANCELLED modeling with Vikram before wiring.

## Frontend (Ananya)
- Route `/creator/applications` in `src/App.tsx` (guarded by `CreatorProtectedRoute`).
- Nav item in `creator-layout.tsx` Main group, after Campaigns (icon `ClipboardList`).
- `src/pages/creator-applications.tsx` (new) — fetch `api.creatorApplications.list()`, status-tab filter, loading/empty/error states (reuse `creator-campaigns.tsx` patterns), `aria-live="polite"` on the swap region.
- `src/components/creator/CreatorApplicationCard.tsx` (new) — brand logo/initial, campaign title, applied date (+ absolute date in `title`), status badge, single click-through button.
- `src/lib/application-status.ts` (new) — `getApplicationStatusLabel()` + `getApplicationStatusBadgeProps()`; **retrofit** `CreatorBrowseCampaignCard.tsx` to use it (single source of truth).
- `src/lib/api.ts` — add `CreatorApplicationRow` + `creatorApplications.list()` (live/mock pattern), register on the `api` object.
- **A11y:** filter tabs are real `<button>` with `aria-pressed` (don't copy the existing badge-as-span gap); labels always visible; confirm `bg-success/text-success-foreground` meets WCAG AA (per the `text-destructive-foreground` gotcha in memory).
- Click-through: Applied/Shortlisted/Closed → `/creator/campaigns/{campaignId}`; progressed (has `dealId`) → deal room (confirm exact route param in `creator-deals.tsx` before wiring).

## QA (Kavya) — must-pass before merge
Given/When/Then for happy path, filters, ordering (newest first), empty state (0 applications → "Explore Campaigns" CTA). Isolation: creator A can't see creator B (CRITICAL); 401 unauth; 403 brand. Data correctness: label == enum via the map; click-through target per status. Regression: campaign-card "Applied" badge still works; no double-count. Full checklist in the agent output — Kavya reviews both PRs against it.

## Build & Verify (Meera)
1. `docker compose up -d` (mysql healthy) → build/run backend via Docker (native mvn hits the JDK21 loopback bug); frontend `npm run dev` with `VITE_API_MODE=live`.
2. After code lands: `mvn -o compile`; `npx tsc --noEmit` (vite build skips typecheck); `npm run lint`.
3. Contract check: creator token → `curl /api/v1/creator/applications` expect 200 array; page renders, network tab shows the real GET (not mock).
4. No migration; sanity-check real `collaborations` rows exist.
5. Deploy (needs Swapnil): images pre-built via GH Actions + pulled by Hostinger MCP; `api` image publishable anytime; restart re-pulls; new `VITE_*` var ⇒ rebuild `web`.

## E2E (Neha) — live script on 200.141.1.6
1. Log in creator (demo.creator@influora.com) → open **Applications** in nav.
2. **Pass:** the Diwali Skincare application shows with status **Active** (demo deal is CONTRACTED), brand "Demo Brand Co", applied date; clicking it opens the **deal room** (not re-apply).
3. Cross-check: UI matches `GET /api/v1/creator/applications` payload (status, brand, dealId).
4. Browser: no console errors; renders mobile + desktop; live (non-mock) build.
5. Regression: campaigns "Applied" badge still correct; deals page unaffected.

---

## Sequence
1. Vikram: endpoint + DTO + repo (BE). → 2. Kabir: review scope + DTO allowlist. → 3. Ananya: page + card + shared status helper + api client (FE). → 4. Kavya: QA both PRs. → 5. Meera: build/verify. → 6. Neha: live E2E. → 7. Priya: sign-off. → 8. Swapnil: approve deploy.

**Effort:** BE ~0.5 day, FE ~1 day, QA/E2E ~0.5 day. No new deps, no migration, no schema change.
