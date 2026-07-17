# A7 · Content Usage Rights in Contracts — Workflow & Build Spec

> **Owners:** Priya (CTO) · Arjun (routing) · **Status:** 🔴 ~5% (type exists, data silently dropped) · Closes gap **A7**
> **Date:** 2026-07-14 · Grounded in real code.
> ⚠️ **Contains a live legal-risk bug** — see §1. Worth fixing independently of the full feature.

---

## 1. The live bug (fix first, regardless of the feature timeline)
Verified in code: the `usageRights` string the frontend submits (`DealDtos.CreateDealRequest.usageRights`) is **accepted by `DealService.createProposal` and never persisted** (`grep .usageRights( across influora-api` = zero matches). It's discarded at the API boundary. Separately, `ContractService` writes a **SHA-256 tamper hash** into `termsJson`, not the actual terms — so no contract in the system stores usage-rights data today.

**Brands believe they're setting repost/duration/paid-ads terms, and the value reaches storage nowhere.** That's a legal-risk data-loss bug independent of any "structured rights" build. Fix the drop first (persist *something*), then structure.

## 2. Build with the current system
| Need | Already exists | How to use it |
|---|---|---|
| Rights shape (FE) | `UsageRights` interface (`types.ts:390`: `duration`, `territories`, `channels`, `canSublicense`) | Adopt this as the canonical shape — but note the UI currently has **3+ incompatible ad-hoc shapes** (`usageRightsDuration`+`usageRightsAddOns`, `'3_MONTHS'` enums, free text) that must be consolidated onto one |
| Storage | `Contract` entity (has `termsJson`) | Add structured columns **or** a `ContractUsageRights` child row — decide in spec; `termsJson` alone is not queryable for disputes |
| Persistence seam | `DealService.createProposal` → `ContractService` | The exact two methods that must start reading + writing the field |
| Dispute reviewer | `AdminDisputeController` / `Dispute` | Structured rights become queryable evidence when a usage dispute is opened |

## 3. Architecture
- **Phase 1 (bug fix, small):** make `DealService.createProposal` persist the submitted `usageRights` (even as JSON on the contract) so it stops vanishing. Ship this alone.
- **Phase 2 (structure, after decision):** canonical `UsageRights` shape → structured `Contract` fields or `ContractUsageRights` row; real form fields in the deal/contract UI (not a string); FE consolidation onto the one shape; surface in contract PDF (`ContractPdfService`) and to dispute reviewers.
- **Migration:** timestamp-named (`V<timestamp>__contract_usage_rights.sql`).

## 4. Task loop (Arjun routing)
| # | Task | Owner | Blocked by |
|---|---|---|---|
| U0 | **Canonical usage-rights shape** (product/legal decision) | Swapnil/Legal + Priya | — (decision) |
| U1 | **Bug fix:** persist submitted `usageRights` in `DealService` (stop the drop) | Vikram | — (do now) |
| U2 | Spec: structured schema + FE shape to standardize on (GATE) | Priya + Arjun | U0 |
| U3 | `ContractUsageRights` (or columns) + migration + persist in Deal/Contract | Vikram | U2 |
| U4 | FE: real rights form fields + consolidate the 3+ shapes | Ananya | U2 |
| U5 | Show rights in contract PDF + dispute-reviewer view | Vikram + Ananya | U3 |
| U6 | VERIFY: QA → mvn verify → Priya sign-off | Kavya/Meera/Priya | U1,U3–U5 |

**U1 is not blocked** — the drop is a bug, fix it immediately. Everything else waits on the U0 shape decision.

## 5. Acceptance criteria
- [ ] Submitted usage rights are persisted (U1) — no silent drop; regression test proves a submitted value round-trips.
- [ ] Structured rights (duration/territories/channels/sublicense) stored queryably and shown on the contract + PDF.
- [ ] FE uses one canonical shape; the 3+ ad-hoc variants are gone.
- [ ] A dispute reviewer can read the agreed rights for any contract.
- [ ] `mvn verify` green.

## 6. Flag to Swapnil
The **silent data-drop (U1) is live today** and is a legal exposure independent of scheduling A7 — recommend fixing U1 this week regardless. Separately, decide the canonical usage-rights shape (U0) before the structured build starts.
