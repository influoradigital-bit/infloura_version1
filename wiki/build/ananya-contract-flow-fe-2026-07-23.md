# Contract Flow — Frontend Wiring (Ananya, 2026-07-23)

**Spec:** `wiki/build/contract-flow-architecture-2026-07-23.md` (Priya) — items FE-1..FE-7.
**Scope:** frontend only. No backend files touched (Vikram owns `ContractController`/`ContractService`/`EscrowService`, running in parallel — his changes are already visible in the working tree, untouched by this commit).

## Files changed

| File | What |
|---|---|
| `src/lib/api.ts:1436` (`ContractApiRecord`) | Rewritten to match backend `ContractResponse`: `collaborationId`, `milestones[]`, `brandSignedAt`/`creatorSignedAt`, `totalAmount`, `currency`, `pdfR2Key`, `status`. New `ContractMilestone` + `ContractGeneratePayload` types. |
| `src/lib/api.ts` `contracts.generate` | Now `generate(payload: {collaborationId, milestones})` → `POST /contracts` with the real body shape (was `{dealId}`, would have 400'd). Returns `ContractApiRecord`. |
| `src/lib/api.ts` `contracts.list`/`get`/`sign` | Typed to `ContractApiRecord[]`/`ContractApiRecord`/`ContractApiRecord` (were untyped `unknown`). |
| `src/lib/api.ts` `contracts.pdfDownloadUrl` (new) | `GET /contracts/:id/pdf-download-url` — mints the presigned R2 link. |
| `src/lib/api.ts:55` | Re-export `ContractStatus` from `./types` (needed by the two chat pages). |
| `src/components/brand/contracts/contracts-and-deliverables.tsx:451,483` | `as ApiContractRow[]` → `as unknown as ApiContractRow[]` — the real `ContractApiRecord` shape no longer structurally overlaps that page's separate UI-only row type, so the cast needs the explicit `unknown` hop (behavior unchanged, still a general-contracts-inbox page, out of this task's scope otherwise). |
| `src/lib/creator-contract-store.ts` | FE-7: every exported lookup (`resolveContractId`, `getContractStatus`, `dealHasContract`, `getAllContractStatuses`) now short-circuits to an honest "no contract" value (`undefined`/`false`/`{}`) when `isApiLive()` — defense-in-depth on top of removing the live-path call sites below. |
| `src/pages/creator-chat.tsx` | FE-2/FE-3: live-mode contract state now reads `deal.contractId`/`contractStatus`/`escrowFunded` and `GET /contracts/:id`; all fabrication (`resolveContractId`, `getContractStatus`, `enrichContractEvent`'s CTR-2024 synthesis) removed from the live path. |
| `src/components/creator/deal-room/creator-deal-contract-tab.tsx` | FE-3 "awaiting brand signature" honest state added; FE-6 real PDF download in live mode. |
| `src/pages/brand-chat.tsx` | FE-4/FE-5: real create-trigger (`POST /contracts`) + real brand sign already-wired path now fed honest data instead of the `CONTRACT_IDS`/`contractStatusByDeal` demo maps. |
| `src/components/brand/deal-room/deal-contract-generate.tsx` (new) | FE-4 "Review & send contract" milestone editor, pre-filled at `deal.dealValue`. |
| `src/components/brand/deal-room/deal-contract-tab.tsx` | FE-6 real PDF download in live mode (brand side). |

## The 4 honest states (creator side, FE-3)

Gated on `deal.contractId`/`deal.contractStatus`/`deal.escrowFunded` (from `DealResponse`, already real) and, once the tools panel opens, the full `GET /contracts/:id` (`brandSignedAt`/`creatorSignedAt`) — never on `deal.status`/`CTR-2024-<id>` fabrication:

1. **"No contract yet"** — `deal.contractId == null`. Rendered in `creator-chat.tsx`'s ToolsSheet contract panel and in `enrichContractEvent`'s live branch.
2. **"Awaiting brand signature"** — contract exists but `brandSignedAt` is null (`DealContractStatus === 'generated'`, i.e. neither party has signed, or the rare creator-signed-first edge case — brand's turn either way). New card added to `creator-deal-contract-tab.tsx`.
3. **"Your turn to sign"** — `brandSignedAt` set, `creatorSignedAt` null (`'brand_signed'`). `canSign = true`, existing card, now fed real data.
4. **"Fully signed"** — both timestamps set (`'creator_signed'` or `'active'`). Was previously only labeled for `'active'`; now also covers `'creator_signed'` (both signed, escrow not yet funded) since that's honestly "fully signed" too.

Derivation: `mapDealApiContractStatus` (coarse, from the deal-list row, renders immediately) → `mapApiContractToDealStatus` (precise, from the fetched `ContractApiRecord`'s two real timestamps, once `GET /contracts/:id` resolves) — both in `src/lib/creator-contract-mappers.ts`, which existed unused before this change (Task #23 built ahead of the wiring; this is the wiring).

## Where the fabrication was removed

- `creator-chat.tsx`: `hasContract`/`contractId`/`contractStatus` derivation (was `dealHasContract(...)`/`resolveContractId(...)`/`resolveDealContractStatus(...)` unconditionally) now branches on `isApiLive()` and uses `selectedDeal.contractId`/live-fetched `ContractApiRecord` in live mode.
- `creator-chat.tsx`'s `enrichContractEvent`: the live branch no longer calls `resolveContractId(deal.id)` — uses `deal.contractId` (real, possibly `undefined`) and `mapDealApiContractStatus(deal.contractStatus, deal.escrowFunded)` instead.
- `brand-chat.tsx`: mirrored — `contractStatus`/`contractId`/`hasContract` now branch on `isApiLive()`; the `CONTRACT_IDS`/`contractStatusByDeal` maps are demo-mode-only now (previously fed unconditionally to `DealContractTab`, meaning live mode was silently showing a fake `CTR-2024-*` contract with a locally-toggled status that never touched the backend).
- `creator-contract-store.ts` fabricators (`resolveContractId`, `getContractStatus`, `dealHasContract`, `getAllContractStatuses`) — kept for demo mode, now fail honest (return "no contract") if ever called live.

## FE-4/FE-5 — the create trigger + brand sign

- `brand-chat.tsx`'s `canGenerateContract` gates a new "Review & send contract" toolbar button + panel state on `isApiLive() && !selectedDeal.contractId && selectedDeal.rawStatus === 'TERMS_AGREED'` (the real, unbucketed `CollaborationStatus` — added `rawStatus` to `ChatDealRoom`, since the existing `dealStatus` bucket collapses `TERMS_AGREED`/`CONTRACT_PENDING`/`CONTRACTED` together).
- `DealContractGenerate` (new component) pre-fills one milestone at `deal.dealValue` ("Release on final approval", editable/splittable per architecture §2), submits `api.contracts.generate({collaborationId: selectedDeal.id, milestones})`.
- On success: `loadDealRooms()` refetches the deal list so `selectedDeal.contractId` becomes real (a new effect keeps the plain-state `selectedDeal` in sync with the refreshed list), and the panel flips into the existing `DealContractTab` sign step.
- Brand signing itself was **already wired correctly** (`DealContractTab.handleSign` → `signContract(contractId, 'brand', name)` → `api.contracts.sign('brand', ...)`) — the only gap was that `contractId`/`status` fed into it were fabricated. Fixed by the `contractStatus`/`contractId` live-mode derivation above. `onStatusChange` now triggers `fetchLiveContract()` + `loadDealRooms()` in live mode instead of writing into the demo map.

## FE-6 — real PDF

Both `deal-contract-tab.tsx` (brand) and `creator-deal-contract-tab.tsx` (creator): `handleDownloadPDF`/`handleDownload` now branch on `isApiLive()` — live mode calls `api.contracts.pdfDownloadUrl(role, contractId)` and opens the presigned URL; the 404 `CONTRACT_PDF_NOT_READY` (legitimate until both signed) surfaces as a toast, not a silent failure. Demo mode is untouched (`downloadContractPDF` client-side print).

## Build/tsc result

```
npx tsc --noEmit   → clean, no errors
npm run build      → clean (pre-existing baseUrl-duplicate-key warning + chunk-size warning only, unrelated)
```

## Known residual gaps (not in this task's scope)

- `contracts-and-deliverables.tsx` (the general Contracts inbox page, not the Deal Room) still has its own separate `brandSigned: true` mock fixtures — those are demo-only (`mockContracts`, gated on `!isApiLive()`), untouched per FE-7 guidance ("keep mock only behind the demo-mode guard").
- BE-1 (dup-contract guard) and BE-2 (escrow-gated-on-ACTIVE verify) are Vikram's — not verified here.
- Kabir's security review (§6 of the spec) has not run against this wiring yet.
