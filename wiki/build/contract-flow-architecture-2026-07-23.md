# Contract Flow — Architecture & Build Spec

**Author:** Priya (CTO) · **Date:** 2026-07-23 · **Status:** APPROVED FOR BUILD
**For:** Vikram (backend), Ananya (frontend) · **Security review:** Kabir (mandatory before ship)
**Live QA source:** http://200.141.1.6 — Deal Room shows a "signed by brand" contract that is UI-fabricated.

---

## 0. TL;DR — the real gap

The entire contract backend **already exists and is secure**. The Contract entity, table, controller,
service, PDF generation, dual-signature flow, tenant-isolation checks, and the collaboration state
machine are all built. The `DealResponse` **already surfaces a real `contractId` / `contractStatus`**
from a real DB lookup.

**Nothing is broken in the contract engine. The problem is it is never invoked, and the frontend fabricates around its absence.**

Three concrete failures:

1. **No create trigger.** A `Contract` row is only ever created by `POST /contracts`
   (`ContractGenerateRequest{collaborationId, milestones[]}`). **No step in the deal/negotiation flow
   calls it.** A deal reaches `TERMS_AGREED` and no contract is ever generated → `contractId` stays
   `null` forever.
2. **Frontend fabrication.** The creator Deal Room derives `CTR-2024-<dealid>` and "brand has signed"
   from `deal.status` via `creator-contract-store.ts`, ignoring the real `deal.contractId` /
   `deal.contractStatus` the backend already returns.
3. **`GET /deals/{id}/contract` 404** — that route was never built. The FE invented it. The real read
   paths are `GET /contracts?dealId=` and the `deal.contractId` field.

This is **~80% a wiring job, not a build-from-scratch job.**

---

## 1. Current-vs-Missing Map (file:line)

### ✅ EXISTS — backend (do not rebuild)

| Piece | File:line | Notes |
|---|---|---|
| `Contract` entity | `influora-api/src/main/java/com/influora/domain/entity/Contract.java:19` | Full: collaborationId, workspaceId, version, status, totalAmount, currency, pdfR2Key, termsJson, brandSignedAt, creatorSignedAt, effective/expiration dates. Signature logic `recordBrandSignature()`/`recordCreatorSignature()` at `:136`/`:142`; auto-advances to `ACTIVE` when both present (`:148`). |
| `ContractStatus` enum | `.../domain/enums/ContractStatus.java:3` | `DRAFT, PENDING_SIGNATURES, ACTIVE, COMPLETED, CANCELLED`. **NOT** the PENDING_BRAND/PENDING_CREATOR model the task imagined — it is **dual-timestamp derived** (see §4). |
| `contracts` + `payment_milestones` tables | `.../db/migration/V10__contracts_and_milestones.sql:1` | FKs to collaborations + workspaces; milestone→escrow FK back-filled. Table is production-shaped. |
| `ContractController` | `.../web/ContractController.java:28` | `POST /contracts` (generate, brand-only, `:38`); `GET /contracts` (role-aware list, `?dealId` filter, `:46`); `GET /contracts/unsigned` (creator, `:58`); `GET /contracts/{id}` (`:68`); `POST /contracts/{id}/sign` (role server-derived, `:78`); `GET /contracts/{id}/pdf-download-url` (`:114`). |
| `ContractService` | `.../service/ContractService.java:66` | `generate` (`:133`) computes total server-side, tenant-scopes via `campaignRepository.findByIdAndWorkspaceId` (`:159`, Kabir E1 fix), materializes deliverables + milestones, fires lifecycle + notifications. `recordSignature` (`:431`) + `recordSignatureForCreator` (`:480`) with idempotency + already-signed guard. |
| Creator-auth sign path | `ContractService.java:480` `recordSignatureForCreator` | Real creator-JWT signing exists — scoped by `findByIdAndCreatorId`. Body ignored; role cannot be forged. |
| Deal→contract join | `DealService.java:732-758` | `contractRepository.findByCollaborationIdOrderByVersionDesc` → latest contract's `id` + `status` written into `DealResponse`. **`escrowFunded` also real** (`:736`). |
| `DealResponse` carries contract | `DealDtos.java:38-40` | `String contractId, ContractStatus contractStatus, boolean escrowFunded`. Backend contract already surfaced to FE. |
| Lifecycle state machine | `CollaborationLifecycleService.java:47` | `onContractGenerated`→`CONTRACT_PENDING` (`:126`); `onContractFullySigned`→`CONTRACTED` (`:132`); `onEscrowFunded`→`IN_PROGRESS` (`:142`). Monotonic + idempotent + FROZEN-guarded. |
| Contract PDF | `.../service/ContractPdfService.java` | Rendered + stored to R2 + emailed on full-sign (`ContractService.java:589`). |
| Escrow-after-sign prompt | `ContractService.java:548` `promptEscrowFundingIfNeeded` | Fires only when both signatures present (`:533`). |

### ⚠️ EXISTS but WRONG / MISMATCHED

| Piece | File:line | Problem |
|---|---|---|
| `ContractApiRecord` type | `src/lib/api.ts:1436` | Declares `dealId`, `pdfUrl` — backend returns `collaborationId`, `pdfR2Key`, and a `milestones[]` array. **Shape does not match `ContractResponse`** (`MoneyDtos.java:155`). |
| `contracts.generate` | `src/lib/api.ts:1460` | Sends `{ dealId }`. Backend `ContractGenerateRequest` requires `{ collaborationId, milestones[] }` (`MoneyDtos.java:188`) → this call would 400 / MILESTONES_REQUIRED. |
| `contracts.list/get` | `src/lib/api.ts:1446` | Correct endpoints, but consumers map to the wrong shape (above). |

### ❌ MISSING (must build)

| Piece | Where | Why |
|---|---|---|
| **Create trigger** | Brand Deal Room → `POST /contracts` | Nothing invokes contract generation. **This is the #1 gap.** See §2. |
| **Brand "Generate & send contract" UI** wired to real endpoint | `src/components/brand/deal-room/deal-contract-tab.tsx` + `contracts-and-deliverables.tsx:451` | Currently mock-driven (`brandSigned: true` hardcoded at `contracts-and-deliverables.tsx:120/213`). |
| **Real contract fetch in creator Deal Room** | `src/pages/creator-chat.tsx:742-873` | Uses `getContractStatus`/`resolveContractId` (fabrication). Must read `deal.contractId` / `deal.contractStatus` and/or `GET /contracts?dealId=`. |
| **Honest empty-state** ("no contract yet") | creator + brand contract tabs | Today: fabricated `brand_signed`. Must show "not created yet" when `deal.contractId == null`. |
| `GET /deals/{id}/contract` | — | **Do NOT build a 404-emitting route.** Either reuse `GET /contracts?dealId=` (returns `[]`, not 404) or read `deal.contractId`. See §4. |

### 🗑️ DELETE / STOP USING (fabrication sources)

| File:line | Fabrication |
|---|---|
| `src/lib/creator-contract-store.ts:38` `resolveContractId` | Invents `CTR-2024-<roomId>`. |
| `src/lib/creator-contract-store.ts:47` `inferContractStatusFromDeal` | Derives `brand_signed` from `deal.status === 'contracted'`. |
| `src/lib/creator-contract-store.ts:7` `DEAL_CONTRACT_IDS` | Hardcoded demo CTR ids. |
| `src/pages/creator-chat.tsx:752,873` | Feeds `resolveContractId(deal.id)` into the contract tab as the "contract id". |
| `src/pages/brand-chat.tsx:93-95,340-344` | Hardcoded `CTR-2024-001`, `brandSigned: true`. |
| `src/components/creator/deal-room/creator-deal-contract-tab.tsx:40` | `canSign = status === 'brand_signed'` where `status` came from the store, not a real contract. |

> **Note for Ananya:** `creator-contract-store.ts` is the demo/mock backbone. It stays valid **only** in
> demo mode (`!isLive()`). In live mode every value must come from the real contract. Gate on `isLive()`,
> do not delete blindly (Memory: "check for live asset imports before deleting").

---

## 2. The Create Trigger (the decision)

**Chosen trigger: brand-initiated, explicit.** After terms are agreed, the brand clicks **"Review & send
contract"** in the Deal Room. That action opens a milestone/payment-schedule review, then calls
`POST /contracts`.

**Why brand-initiated and not auto-on-`TERMS_AGREED`:**
- `ContractGenerateRequest` **requires** `milestones[]` (`MoneyDtos.java:189`); the total is summed
  server-side from them (`ContractService.java:174`). A payment schedule is a brand decision that
  `TERMS_AGREED` does not capture. Auto-generation cannot invent a schedule.
- `generate` requires elevated brand membership (`OWNER/ADMIN/MANAGER`, `ContractService.java:136`) —
  a deliberate, permissioned act, not an automatic side-effect.

**Default milestone (so the brand isn't forced to hand-build a schedule):** the FE pre-fills a single
milestone = full `deal.dealValue` (`collaboration.agreedRate`), "Release on final approval", editable.
Brand can split into multiple milestones. Server still re-sums (never trust a client total).

**Sequence:**
```
Terms agreed (TERMS_AGREED)
  → Brand clicks "Review & send contract"
  → POST /contracts {collaborationId, milestones[]}   [brand OWNER/ADMIN/MANAGER only]
  → Contract row created, status=DRAFT, collaboration → CONTRACT_PENDING (lifecycle :126)
  → Creator notified (ContractPendingSignatureEvent)
  → Brand signs:   POST /contracts/{id}/sign  (brand JWT)   → brandSignedAt set, status=PENDING_SIGNATURES
  → Creator signs: POST /contracts/{id}/sign  (creator JWT) → creatorSignedAt set, status=ACTIVE
  → Full-sign side effects: PDF generated+emailed, collaboration → CONTRACTED, escrow-funding prompt
  → Brand funds escrow → EscrowService.confirmFunded → collaboration → IN_PROGRESS
```

**Signing order is not enforced by the backend** (either party may sign first — `recordSignature`
handles either timestamp). Product convention is brand-first; the FE should present it that way but must
not *assume* it — read real `brandSignedAt`/`creatorSignedAt`.

---

## 3. The Model (already built — reference, not new work)

`Contract` (`Contract.java`) / `contracts` table (`V10`):

| Field | Type | Source |
|---|---|---|
| `id` | ULID(26) | server |
| `collaborationId` | FK collaborations | request |
| `workspaceId` | FK workspaces (denormalized, tenant scope) | derived from brand JWT |
| `version` | int (default 1) | server |
| `status` | enum (§4) | server-derived |
| `totalAmount` | DECIMAL(12,2) | **server-summed from milestones** — never client-supplied |
| `currency` | 3-char (default INR) | server |
| `pdfR2Key` | R2 object key | set on full-sign |
| `terms` (`termsJson`) | JSON | currently a SHA-256 tamper-hash of the request (`ContractService.java:789`) |
| `brandSignedAt` / `creatorSignedAt` | timestamp | set by respective sign call |
| `effectiveDate` / `expirationDate` | date | optional |

Parties: **not stored as columns on `Contract`.** They are resolved through the collaboration —
`collaboration.creatorId` (creator) and `workspace` owner (brand). Amount/terms snapshot = milestones
(`payment_milestones` table, `V10:24`) + `agreedRate`. Deliverables are materialized as `Deliverable`
rows at generate time (`ContractService.java:269`).

**Relationship to escrow:** funding happens **after** both sign. `promptEscrowFundingIfNeeded`
(`:548`) only fires when `brandSignedAt != null && creatorSignedAt != null` (`:533`). Actual debit stays
brand-initiated (`EscrowService#initiateFund`). **Kabir must confirm escrow cannot be funded before
`ACTIVE`** (§6).

**Terms snapshot gap (flag for Vikram, low priority):** `termsJson` currently stores *only* a tamper-hash,
not a human-readable snapshot of usage rights / exclusivity / deliverable summary (the column comment in
`V10:10` promises those). The signed PDF renders correctly, but the API record carries no readable terms.
Acceptable for v1; note as tech-debt.

---

## 4. State Machine

### Contract status (dual-timestamp derived — do NOT re-model)
```
DRAFT                         (created, no signatures)
  │  brand OR creator signs
  ▼
PENDING_SIGNATURES            (exactly one of brandSignedAt / creatorSignedAt set)
  │  the other party signs
  ▼
ACTIVE                        (both brandSignedAt AND creatorSignedAt set)  → escrow may fund
  │
  ▼
COMPLETED / CANCELLED         (terminal)
```
Derivation lives in `Contract.advanceIfFullySigned()` (`Contract.java:148`). **The FE must read the two
timestamps, not just the enum**, to distinguish "awaiting brand" vs "awaiting creator" (both are
`PENDING_SIGNATURES`).

### Collaboration status (the "deal stage" the Deal Room shows)
```
INVITED/APPLIED/SHORTLISTED/IN_NEGOTIATION/TERMS_AGREED
  │  onContractGenerated
  ▼
CONTRACT_PENDING     (contract exists, awaiting signatures)
  │  onContractFullySigned
  ▼
CONTRACTED           (both signed)
  │  onEscrowFunded
  ▼
IN_PROGRESS → REVIEW_PENDING ⇄ REVISION_REQUESTED → COMPLETED
```
Driven by `CollaborationLifecycleService`. **This means: the Deal Room "Contract" stage should be gated
on `collaboration.status >= CONTRACT_PENDING` AND a non-null `contractId` — never on `TERMS_AGREED`
alone.**

---

## 5. Endpoint Specs

All under `/api/v1`. All already implemented except where marked **[VERIFY]**/**[FE change]**.

### 5.1 Create — `POST /contracts`  *(exists)*
- **Auth:** brand JWT, `OWNER/ADMIN/MANAGER` of the collaboration's workspace.
- **Body:** `{ collaborationId, milestones: [{ sequenceNo, description, amount, dueDate }] }`.
- **Server:** verifies the collaboration's campaign belongs to the caller's workspace
  (`ContractService.java:159`); sums `totalAmount` server-side; creates `Contract(DRAFT)` + milestones;
  advances collaboration → `CONTRACT_PENDING`; notifies creator.
- **Returns:** `201` + `ContractResponse` (`MoneyDtos.java:155`).
- **FE change:** `api.ts:1460` must send `{ collaborationId, milestones }` (not `{ dealId }`) and return
  the full `ContractResponse`.

### 5.2 Read by deal — **use `GET /contracts?dealId={collaborationId}`**  *(exists)*
- **Auth:** creator OR brand; role-aware (`ContractController.java:46`).
- Creator branch scopes by `findByCollaborationIdAndCreatorId` — **cannot read another creator's contract.**
- **Returns:** `200` + `ContractResponse[]` — **empty array when no contract exists (NOT 404).** This is
  the honest "not created yet" signal.
- **Preferred even simpler path:** the FE already receives `deal.contractId` / `deal.contractStatus` /
  `deal.escrowFunded` on every `DealResponse`. For the Deal Room, **read those first**; only call
  `GET /contracts/{id}` when it needs milestones / signature timestamps / PDF.
- **Do NOT build `GET /deals/{id}/contract`.** It is redundant and the 404 it returned is the bug.

### 5.3 Read one — `GET /contracts/{id}`  *(exists)*
- Role-aware, tenant/owner scoped (`ContractController.java:68`). Returns full `ContractResponse` incl.
  `milestones`, `brandSignedAt`, `creatorSignedAt`.

### 5.4 Sign — `POST /contracts/{id}/sign`  *(exists — role server-derived)*
- **Auth:** the deal's brand **or** creator, resolved from JWT `userType`.
  - Creator JWT → `recordSignatureForCreator` (`ContractService.java:480`), scoped by
    `findByIdAndCreatorId`. **Body ignored; role cannot be client-supplied.**
  - Brand JWT → `recordSignature`, role defaults to `BRAND` (`ContractController.java:102`).
- **Body:** `{ name, agreedAt }` (self-attestation; `role` optional and normally omitted).
- Idempotent (already-signed → no-op, `ContractService.java:508`); concurrency-guarded via
  `IdempotencyService` keyed per-contract-per-role (`:459`).
- **FE (creator) must sign with the creator JWT path** — `api.contracts.sign('creator', id, {...})`
  already targets this. Confirm it does **not** route through the brand relay.

### 5.5 PDF — `GET /contracts/{id}/pdf-download-url`  *(exists)*
- Mints a fresh presigned R2 GET link. `404 CONTRACT_PDF_NOT_READY` until both signed + PDF generated
  (`ContractService.java:754`). **This 404 is legitimate** — FE shows "PDF available after both sign".
- **Replace the client-side HTML print** (`contract-generator.ts:189` `downloadContractPDF`) with this
  real presigned URL in live mode.

---

## 6. What Kabir Must Review (signing = legal/financial)

The existing engine is already hardened (E1/E2 findings fixed). Kabir's job is the **new wiring** + a
few genuine residual gaps:

1. **Tenant isolation on the new create trigger.** Confirm the brand "Generate contract" UI passes only
   `collaborationId` and that `ContractService.java:159` (campaign↔workspace check) still gates it. A
   brand must never generate a contract against another workspace's collaboration.
2. **Signer-role server-derivation.** Re-confirm no FE call sends a client `role` that reaches
   `recordSignature`. Creator signing MUST go through `recordSignatureForCreator` (creator JWT), never
   the brand `role=CREATOR` relay. **Residual known risk:** a brand `OWNER/ADMIN/MANAGER` can still relay
   a `role=CREATOR` signature (`ContractService.java:440`) — documented, product-level, but Kabir should
   confirm the FE never exposes that path to the brand UI.
3. **No signing on another party's behalf via the FE.** The creator Deal Room must sign only the
   creator's own contract (scoped by `findByIdAndCreatorId`). Verify no `contractId` is trusted from
   client state that could point at a stranger's contract.
4. **Immutability of a signed contract — REAL GAP.** `generate` does **not** guard against creating a
   *second* contract for a collaboration that already has an `ACTIVE`/signed one. All rows are
   `version=1`, so `findByCollaborationIdOrderByVersionDesc` (`DealService.java:733`) has unstable
   ordering if two exist. **Kabir + Vikram:** add a guard — reject `POST /contracts` when a
   non-`CANCELLED` contract already exists for the collaboration (or implement real versioning). Must not
   be able to overwrite/supersede a signed contract silently.
5. **Escrow gated on `ACTIVE`.** Verify `EscrowService#initiateFund` refuses to fund unless the
   collaboration's contract is `ACTIVE` (both signed). The prompt fires post-sign, but Kabir must confirm
   the *fund endpoint itself* enforces it — not just the notification.
6. **Amount integrity.** Confirm `totalAmount` is server-summed (`:174`) and no FE path can inject a
   contract total or milestone amount that bypasses it.
7. **PDF link exposure.** Presigned URLs are short-lived and minted per-request (`:754`) — confirm the FE
   never caches/logs them and that creator scoping (`getPdfDownloadUrlForCreator`, `:747`) is intact.

---

## 7. Task Breakdown

### Vikram (backend) — mostly verification + one real guard
- **BE-1 [GUARD]** Reject `POST /contracts` when a non-`CANCELLED` contract already exists for the
  collaboration (immutability gap, §6.4). New `CONTRACT_ALREADY_EXISTS` error.
- **BE-2 [VERIFY]** Confirm `EscrowService#initiateFund` enforces contract `ACTIVE` before funding (§6.5).
  If not, add the gate.
- **BE-3 [NICE-TO-HAVE]** Store a readable terms snapshot in `termsJson` (usage rights, exclusivity,
  deliverable summary) alongside the tamper-hash (§3). Low priority.
- **BE-4** Confirm `GET /contracts?dealId=` returns `[]` (not 404) for a collaboration with no contract —
  verify the honest empty-state contract. (Should already be true.)
- **No new entity, table, controller, or service is required.**

### Ananya (frontend) — the bulk of the work
- **FE-1** Fix `api.ts` contract layer: `ContractApiRecord` → match `ContractResponse`
  (`collaborationId`, `pdfR2Key`, `milestones[]`, `brandSignedAt`, `creatorSignedAt`, `totalAmount`);
  `contracts.generate` sends `{ collaborationId, milestones }` (`api.ts:1436,1460`).
- **FE-2** Creator Deal Room: stop fabricating. Read `deal.contractId` / `deal.contractStatus` /
  `deal.escrowFunded` (already on `DealResponse`); fetch full contract via `GET /contracts/{id}` only when
  the tab opens. Remove `resolveContractId` / `getContractStatus` from the live path
  (`creator-chat.tsx:742,752,873`).
- **FE-3** Honest gating states in `creator-deal-contract-tab.tsx` — derive from real data:
  - `deal.contractId == null` → **"No contract yet"** (brand hasn't sent one).
  - contract `DRAFT` / neither signed → **"Contract drafted, awaiting signatures."**
  - `brandSignedAt` set, `creatorSignedAt` null → **"Your turn to sign."** (`canSign = true`)
  - `creatorSignedAt` set, `brandSignedAt` null → **"Awaiting brand signature."**
  - both set (`ACTIVE`) → **"Fully signed."** + real PDF link (§5.5).
  - Gate `canSign` on **real `brandSignedAt`**, not `status === 'brand_signed'` from the store.
- **FE-4** Brand Deal Room: build the **"Review & send contract"** action (§2) — milestone pre-fill from
  `deal.dealValue`, editable schedule, `POST /contracts`. Replace hardcoded `brandSigned: true`
  (`contracts-and-deliverables.tsx:120,213`, `brand-chat.tsx:340`).
- **FE-5** Brand signing: wire a real brand `POST /contracts/{id}/sign` (brand JWT) after generation;
  stop assuming brand-signed.
- **FE-6** Replace `downloadContractPDF` (client HTML print, `contract-generator.ts:189`) with the real
  presigned URL from `GET /contracts/{id}/pdf-download-url` in live mode.
- **FE-7** Keep `creator-contract-store.ts` fabrication **only** behind `!isLive()` (demo mode).

### Kabir (security) — review gate before ship
- KAB-1..7 = §6 items 1–7. Blocks Priya sign-off.

---

## 8. Sequencing
1. **FE-1** (api shape) — unblocks everything.
2. **FE-4 + FE-5** (brand generate + sign) — makes a real contract exist.
3. **FE-2 + FE-3** (creator read + honest gating) — stops fabrication.
4. **BE-1** (dup-contract guard) — before any real money flows.
5. **BE-2** (escrow-gated-on-ACTIVE verify) + **FE-6** (real PDF).
6. **Kabir review** → Meera live E2E on 200.141.1.6 → Priya sign-off.
