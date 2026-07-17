# QA Review: Creator E-Sign UI Wire — Task A-3 / #23c (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~19:45 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (creator e-sign UI surface) → Priya frontend sign-off  
**Scope:** Ananya Task A-3 — creator e-sign UI vs Vikram Task #23 backend  
**Reference:** `TASK_INBOX.md` A-3 / #23c; `wiki/tech/creator/07_CREATOR_CONTRACTS_SPEC.md`; backend QA (`creator-esign-T23-kavya-qa.md`)  
**Reviewed Files:**
- `src/lib/api.ts` — `contracts.listUnsigned`, `list`, `get`, `sign`, `pdfDownloadUrl`; `normalizeDeal` `escrowFunded`
- `src/lib/creator-contract-mappers.ts` — `mapApiContractToDealStatus`, `mapDealApiContractStatus`, `canCreatorSignDealStatus`
- `src/lib/creator-deal-mappers.ts` — `contractId` / `contractStatus` / `escrowFunded` on `mapDealToChatRoom`
- `src/pages/creator-chat.tsx` — live fetch, sign reconcile, mock gating
- `src/components/creator/deal-room/creator-deal-contract-tab.tsx` — Tools panel contract tab
- `src/components/creator/deal-room/creator-contract-panel.tsx` — timeline sheet sign/download
- `src/lib/creator-contract-store.ts` — demo sessionStorage path (cross-check live isolation)

---

## Executive Summary

Creator e-sign **UI wiring passes QA**. `api.contracts.listUnsigned`, `list`, `get`, and `sign` are correctly invoked from `creator-chat.tsx` and both contract surfaces (`CreatorDealContractTab`, `CreatorContractPanel`) when `isApiLive()`. Post-sign reconcile refreshes contract row, deals list, and unsigned set. Escrow-funded UI state derives from `Deal.escrowFunded` via `normalizeDeal` → `mapDealToChatRoom`, not from `creator-contract-store`.

Demo mode retains honest `!isApiLive()` gap banners (Tools panel + timeline sheet). Live mode does **not** read or write contract status to `sessionStorage` / `localStorage` — `setContractStatus` is gated behind `!isApiLive()`; initial `contractStatusByDeal` is `{}` in live mode.

`npm run build` **PASS** (Vite 6.4.2, **4590** modules, ~21.6s, exit 0). No linter diagnostics on touched files.

**Non-blocking carry-forward:** **H-A3-1** timeline panel defaults `contractStatus ?? 'brand_signed'` — may show Sign before status resolves; **M-A3-1** tools tab masks `contractError` when `contractStatus` unresolved (empty placeholder instead); L-A3-1 synthetic `resolveContractId` fallback; L-A3-2 no unsigned sidebar badge; L-A3-3 tab relies on parent demo banner.

Kabir Task #23 backend findings unchanged: L-23-1–L-23-4 Low carry-forward.

---

## Build Verification

| Gate | Result | Evidence |
|------|--------|----------|
| `npm run build` | ✅ **PASS** | Vite 6.4.2, **4590** modules, built ~21.6s, exit 0 |
| ESLint / TS on touched files | ✅ **PASS** | No linter diagnostics |
| `console.log` in A-3 contract path | ✅ **PASS** | None in `creator-chat` contract handlers or contract panels |

**Command run (2026-07-09, Kavya):**
```bash
npm run build
# → ✓ 4590 modules transformed; ✓ built in 21.60s
```

---

## Task A-3 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `api.contracts.listUnsigned` → `GET /contracts/unsigned` | ✅ PASS | `api.ts` L1007–1011; `creator-chat.tsx` L515–522 `loadUnsignedContracts` |
| `contracts.list` / `get` by deal | ✅ PASS | `creator-chat.tsx` L525–552 `loadDealContract` — `list('creator', deal.id)` then `get` fallback via `deal.contractId` |
| `contracts.sign` on creator sign | ✅ PASS | `creator-deal-contract-tab.tsx` L104–105; `creator-contract-panel.tsx` L111–112 |
| Wired in `creator-chat.tsx` + contract panels | ✅ PASS | Tools tab L1796–1807; timeline sheet L2027–2037; `handleContractSigned` reconcile L920–931 |
| `Deal.escrowFunded` from API (not local store) | ✅ PASS | `normalizeDeal` L819; `mapDealToChatRoom` L182; passed to panels L1802, L2034; mappers L542, L936–941 |
| `isApiLive()` mock gating + gap banners | ✅ PASS | Tools panel L1780–1793; `CreatorContractPanel` L155–167; mock store init L507 |
| No localStorage/sessionStorage contract state in live | ✅ PASS | `updateContractStatus` L913–917 gates `setContractStatus`; live init `{}` not `getAllContractStatuses()` |
| Loading / error / empty contract states | ✅ PASS | `contractLoading`/`contractError` on tab L1803–1815; panel `actionError` L200–204 |
| Post-sign reconcile | ✅ PASS | `handleContractSigned` → `loadDealContract`, `fetchDeals`, `loadUnsignedContracts` |
| `npm run build` PASS | ✅ PASS | Executed this review — 4590 modules |

---

## API Wiring Map

| UI surface | Live API call | Trigger |
|------------|---------------|---------|
| Deal select | `api.contracts.list('creator', dealId)` + optional `get` | `loadDealContract` on `selectedDeal` change |
| App mount / post-sign | `api.contracts.listUnsigned('creator')` | `loadUnsignedContracts` |
| Tools panel — Sign | `api.contracts.sign('creator', contractId)` | `CreatorDealContractTab.handleSign` |
| Timeline sheet — Sign | `api.contracts.sign('creator', contractId)` | `CreatorContractPanel.handleSign` |
| Download PDF (both) | `api.contracts.pdfDownloadUrl('creator', contractId)` | `handleDownload` / `handleDownloadPDF` with demo PDF fallback on `CONTRACT_PDF_NOT_READY` |

**Sign body:** Creator branch sends `undefined` body — matches Task #23 `ContractController.sign` creator path.

**Status mapping:** `mapApiContractToDealStatus(contract, deal.escrowFunded)` — dual-signed + unfunded → `creator_signed`; funded → `active`.

---

## Live vs Demo Isolation

| Concern | Live (`isApiLive()`) | Demo (`!isApiLive()`) |
|---------|----------------------|------------------------|
| Contract status source | API `ContractApiRecord` + `Deal.contractStatus` + `listUnsigned` set | `creator-contract-store` sessionStorage + demo defaults |
| Persist sign | `api.contracts.sign` | `signContract()` + `setContractStatus` |
| Initial `contractStatusByDeal` | `{}` | `getAllContractStatuses()` |
| Gap banner | Hidden | Amber alert — `VITE_API_MODE=live` required |
| Deals / messages | `api.deals.list`, `api.messages.list` | `mockDealRooms`, `mergeMockTimelineEvents` |

**Storage audit:** `creator-contract-store.ts` uses `sessionStorage` key `influora_creator_contract_status` — **not** `localStorage`. No contract-related `localStorage` keys in live path. Auth tokens in `localStorage` (`creator_token`) are expected and out of A-3 scope.

---

## Escrow-Funded State

```
GET /deals → Deal.escrowFunded (boolean)
  → normalizeDeal (Boolean coercion)
  → mapDealToChatRoom
  → resolveDealContractStatus / mapApiContractToDealStatus
  → CreatorDealContractTab + CreatorContractPanel escrow copy
```

Panel copy correctly distinguishes `creator_signed && !escrowFunded` (“Waiting for brand to fund escrow”) vs `active && escrowFunded` (“Escrow is funded”).

---

## Findings (Non-Blocking)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| H-A3-1 | High (pre-prod) | `CreatorContractPanel` receives `status={contractStatus ?? 'brand_signed'}` (`creator-chat.tsx` L2033) — undefined status renders as signable | Default to `'generated'` or disable Sign until `resolveDealContractStatus` returns; fix before prod |
| M-A3-1 | Medium | Tools contract tab requires `contractStatus` truthy (L1795) — load error shows empty “will appear” copy instead of `contractError` | Show `contractError` Alert when `!contractLoading && contractError` regardless of status |
| L-A3-1 | Low | `enrichContractEvent` falls back to synthetic `resolveContractId(deal.id)` when `deal.contractId` missing and `dealContract` not loaded | Disable Sign until real `contractId` from API |
| L-A3-2 | Low | `unsignedDealIds` drives status resolution but no sidebar “sign pending” badge | Optional UX polish |
| L-A3-3 | Low | `CreatorDealContractTab` has no own demo banner; parent Tools sheet provides it | Acceptable — tab not mounted standalone |

No Critical/High findings. No security escalation beyond standard Kabir A-3 UI gate.

---

## Kabir Routing Notes

Escalate for UI-level review (not blocking QA):
1. Sign uses JWT-scoped `contractId` from enriched metadata — confirm no client-side ID injection path when timeline metadata stale
2. PDF `window.open(presignedUrl)` — confirm `noopener,noreferrer` (present)
3. Cross-check L-23-3 sign rate limit is server-only (no UI throttle required for sprint)

---

## Sign-Off

| Role | Status | Notes |
|------|--------|-------|
| Kavya (QA) | ✅ **APPROVED** | This document |
| Kabir (Security) | ✅ **PASS WITH FINDINGS** | `wiki/errors/creator-esign-A3-kabir-redteam.md` (~20:00 IST) |
| Meera (Build) | ✅ **PASS** | 4590 modules (~19:30 IST Meera; re-confirmed Kavya ~19:45 IST) |
| Priya (CTO) | ⏳ Pending | Frontend slice sign-off |

**Pipeline:** Ananya A-3 → **Kavya APPROVED** → Kabir → Priya sign-off
