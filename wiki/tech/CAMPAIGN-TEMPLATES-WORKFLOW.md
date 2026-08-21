# Campaign Templates — Workflow & Employee Task Loop

> **Owners:** Priya (CTO, architecture) · Arjun (Eng Lead, routing) · Tejas (goal presets)
> **Date:** 2026-07-14 · **Closes gap B5** from `FEATURE_GAP_ANALYSIS.md` · Pro-tier feature per `SUBSCRIPTION-BILLING-PLAN.md` §2
> **Method:** grounded in real code (`Campaign` entity, `/campaigns/{id}/duplicate`, `brand-new-campaign.tsx`).

---

## 0. Why this is small (aligns to existing code)

Templates don't need new campaign machinery — they pre-fill fields the `Campaign` entity **already has**:
`campaignType`, `budgetMin/Max`, `platformsJson`, `contentTypesJson`, `objectivesJson`, `requirementsJson`, `hashtagsJson`, `targetAudienceJson`, `brandGuidelines`.

Two existing hooks to reuse, not reinvent:
- **`POST /campaigns/{campaignId}/duplicate`** already clones a campaign's field set → the same copy logic backs "save as template" and "apply template."
- **`brand-new-campaign.tsx`** already has a `selectedType` step before the form → the template picker slots in at that same point; applying a template just pre-fills the existing `CampaignWriteRequest` form. **No new create path.**

**Scope decision (Priya):** two template kinds —
1. **SYSTEM templates** — 4 curated goal presets (Awareness / Sales / UGC / Affiliate), seeded by us, read-only, **free to all tiers** (funnel win — lifts campaign-creation conversion).
2. **CUSTOM templates** — a brand saves its own campaign as a reusable template. **This is the Pro-gated action** (feeds the Pro value prop; system presets stay free).

---

## 1. Architecture (Priya)

**Entity `CampaignTemplate`** — mirrors `Campaign`'s writable fields (no dates/status), plus: `name`, `description`, `category` (enum: AWARENESS/SALES/UGC/AFFILIATE/CUSTOM), `scope` (SYSTEM/CUSTOM), `workspaceId` (null for SYSTEM), `createdBy`.

**Migration** — new Flyway migration; claim the next free version (V54 is taken by subscription billing; confirm the Vxx-vs-timestamp convention with the latest files before naming). Seed the 4 SYSTEM rows.

**API (`/campaign-templates`)**
| Method | Path | Purpose | Gate |
|---|---|---|---|
| GET | `/campaign-templates` | list SYSTEM + own CUSTOM | brand member |
| GET | `/campaign-templates/{id}` | fetch one (to prefill form) | brand member |
| POST | `/campaign-templates` | save current campaign as CUSTOM | **Pro** (`@RequiresPlan`) |
| DELETE | `/campaign-templates/{id}` | delete own CUSTOM only | owner of the template |

**Frontend** — picker on `/brand/campaigns/new` (before the blank form); "Save as template" on `brand-campaign-detail`; manage-my-templates in settings; new `api.campaignTemplates` group in `src/lib/api.ts`.

**Pro-gate note:** the `@RequiresPlan` hook on save-custom no-ops until the plan-gate filter (billing Task 15) lands; until then it's feature-flagged. System presets never gated.

---

## 2. Employee task list (Arjun routing)

| # | Task | Owner | Blocked by |
|---|---|---|---|
| 21 | Lock template spec + field-set + gating + API contract (GATE) | **Priya + Arjun** | — |
| 22 | `CampaignTemplate` entity + repo + migration + seed 4 presets | Vikram | 21 |
| 23 | `CampaignTemplateController` + service (list/get/save/delete) | Vikram | 22 |
| 24 | Template picker + apply-to-form (prefill existing create) | Ananya | 21 |
| 25 | Save-as-template + manage + Pro-gate CTA | Ananya | 24 |
| 26 | VERIFY LOOP → sign-off | Kavya / Meera / Priya | 22–25 |

Tasks 22 and 24 start **in parallel** the moment Task 21's gate clears.

---

## 3. The workflow loop

```
Arjun reads TASK_INBOX → routes
        │
        ▼
Priya signs the spec (Task 21) ── gate ──┐
        │                                 │
        ▼                                 │
Vikram (BE) / Ananya (FE) build ◄──────┐  │
        │                              │  │
        ▼                              │  │
Kavya — QA (standards, delete authz = own-custom-only)  ──fail──┘
        │ pass                         ▲
        ▼                              │
Meera — verify: mvn verify + tsc + Playwright apply-template e2e  ──fail──┘
        │ pass
        ▼
Priya — sign-off → mark B5 done in billing plan §2 + REMAINING-FEATURES
```
Security note: low-risk feature (no money/PII), so no dedicated Kabir gate — Kavya covers the one authz check that matters (a brand can only delete/edit its **own** CUSTOM templates, never SYSTEM or another workspace's).

---

## 4. The 4 seed presets (Tejas owns the content)

| Preset | campaignType | Pre-fills (objectives / content / defaults) |
|---|---|---|
| **Awareness** | reach-oriented | objectives: reach/impressions; content: Reels + Stories; broad audience; brand-guideline placeholder |
| **Sales** | conversion | objectives: conversions/clicks; content: Reels + link-in-bio; coupon/UTM prompt; tighter audience |
| **UGC** | content | objectives: asset generation; content: raw video/photo; usage-rights note; lower budget band |
| **Affiliate** | performance | objectives: revenue-share; content: review/demo; commissionRate prompt; coupon-code prompt |

Exact copy/defaults finalized in Task 21 by Tejas + Priya.

---

## 5. Acceptance criteria (Task 26)

- [ ] 4 SYSTEM presets seeded and listable by any brand tier.
- [ ] Selecting a preset pre-fills the existing new-campaign form; "start from scratch" still works.
- [ ] A brand can save an existing campaign as a CUSTOM template and re-apply it.
- [ ] Save-custom is Pro-gated (upgrade CTA on Free); using SYSTEM presets is free.
- [ ] A brand can delete only its **own** CUSTOM templates — SYSTEM and other-workspace deletes are 403.
- [ ] `mvn verify` green + `npx tsc --noEmit` clean + Playwright apply-template→create e2e green.
- [ ] On sign-off, B5 marked done in `SUBSCRIPTION-BILLING-PLAN.md` §2 and `REMAINING-FEATURES-2026-07-13.md`.

---

## 6. Dependency note

The template mechanism (22–25 core) is **independent** and can ship now — it doesn't wait on Swapnil's §6 pricing sign-off. Only the **Pro-gate enforcement** on save-custom depends on billing Task 15 (plan-gate filter); until that lands, save-custom is feature-flagged on so the feature is usable and the gate flips on later with one line.
