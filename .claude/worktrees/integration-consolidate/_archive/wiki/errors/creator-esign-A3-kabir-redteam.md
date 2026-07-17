# Creator E-Sign UI — Task A-3 / #23c (Kabir Red-Team)

**Auditor:** Kabir Singh (Offensive Security / Red-Team Lead)  
**Date:** 2026-07-09 (~20:00 IST)  
**Verdict:** ✅ **PASS WITH FINDINGS** — sprint gate **GO**; timeline contract panel live path **pre-prod NO-GO** until **H-A3-1** fixed  
**Scope:** Ananya Task A-3 — creator e-sign UI vs Vikram Task #23 backend (Kavya **APPROVED** ~19:30 IST)  
**Reference:** Kavya `wiki/errors/creator-esign-A3-kavya-qa.md`; backend Kabir `wiki/errors/creator-esign-T23-kabir-redteam.md`; spec `wiki/tech/creator/07_CREATOR_CONTRACTS_SPEC.md`, `12_CREATOR_SECURITY_SPEC.md` §6.3  
**Reviewed Files:**
- `src/pages/creator-chat.tsx` — live contract fetch, `listUnsigned`, sign reconcile, mock gating, timeline panel mount
- `src/components/creator/deal-room/creator-deal-contract-tab.tsx` — Tools panel sign + PDF
- `src/components/creator/deal-room/creator-contract-panel.tsx` — timeline sheet sign + PDF
- `src/components/creator/deal-room/creator-contract-card.tsx` — timeline card sign CTA gating
- `src/lib/api.ts` — `contracts.listUnsigned`, `list`, `get`, `sign`, `pdfDownloadUrl`; `isApiLive` / `assertMockAuthAllowed`
- `src/lib/creator-contract-mappers.ts` — `canCreatorSignDealStatus`, status mapping
- `src/lib/creator-contract-store.ts` — demo `sessionStorage` path (live isolation cross-check)
- `influora-api/.../ContractController.java` + `ContractService.java` — authz closure cross-check (Task #23)

---

## Executive Summary

**VERDICT: ✅ PASS WITH FINDINGS**

Creator e-sign UI correctly delegates authorization, ownership, and signature persistence to the Task #23 backend. Live mode does **not** read or write contract status to `localStorage` / `sessionStorage`; `api.contracts.sign('creator', id)` sends **no body** (creator branch server-derived); presigned PDF URLs are minted only after JWT-scoped `GET /contracts/{id}/pdf-download-url`. Cross-creator IDOR, signature replay, and escrow auto-debit remain **closed on the server** — UI cannot bypass Task #23 gates by manipulating request shape.

**No Critical findings.** **No sprint-blocking High** (authorization bypass class). **H-A3-1** is a **pre-prod HIGH** integrity defect on the timeline `CreatorContractPanel` path (mirrors **H-21b-1** pattern): unresolved status defaults to signable UI and may pair with synthetic `contractId` metadata before API reconcile.

**Closed / PASS (security):**

1. **IDOR via UI — CLOSED** — Sign and PDF calls pass only `contractId` from server-enriched state or `dealContract.id`; backend `findByIdAndCreatorId` + `principal.getUserId()` blocks cross-creator read/sign/PDF (Task #23).
2. **Client-side contract status forgery — CLOSED (live)** — `setContractStatus` / `getAllContractStatuses()` gated behind `!isApiLive()`; live init `contractStatusByDeal` is `{}`; post-sign state reconciled from `api.contracts.sign` response.
3. **Mock auth in production — CLOSED** — `assertMockAuthAllowed()` throws `MockAuthDisabledError` when `import.meta.env.PROD && !isApiLive()`; demo banners on contract surfaces when `!isApiLive()`.
4. **PDF tab-nabbing — CLOSED** — `window.open(downloadUrl, '_blank', 'noopener,noreferrer')` on both contract surfaces.
5. **XSS on contract metadata egress — CLOSED** — Reviewed panels/cards use React text interpolation; no `dangerouslySetInnerHTML` in A-3 contract components.
6. **Double-submit (client) — CLOSED** — `isSigning` disables Sign buttons on both surfaces.

**Active findings:**

| ID | Severity | Area | Sprint gate | Prod gate |
|---|---|---|---|---|
| H-A3-1 | **HIGH (pre-prod)** | Timeline panel `status={contractStatus ?? 'brand_signed'}` + synthetic `resolveContractId` fallback | **GO** (use Tools contract tab) | **NO-GO** timeline Sign path |
| M-A3-1 | **MEDIUM** | Tools tab masks `contractError` when `contractStatus` falsy | Carry-forward | Fix before prod |
| M-A3-2 | **MEDIUM** | Live PDF download falls back to client-generated demo PDF on `CONTRACT_PDF_NOT_READY` | Carry-forward | Block Sign until server PDF or explicit ack |
| L-A3-1 | LOW | `loadUnsignedContracts` swallows errors → empty unsigned set | Optional | Surface load failure |
| L-A3-2 | LOW | No UI throttle on sign (server idempotency only) | Carry-forward | L-23-3 |
| L-A3-3 | LOW | `creator-inbox.tsx` still calls `setContractStatus` (out of A-3 scope; demo page) | N/A | Audit if inbox goes live |

Task #23 Low carry-forward unchanged: L-23-1–L-23-4, E2 LOW-4 brand relay-sign residual.

---

## 1. Attack Surface Map

| Surface | Component | Live API | Authz boundary | Security posture |
|---|---|---|---|---|
| Deal room — Tools contract tab | `CreatorDealContractTab` in `creator-chat` Sheet | ✅ `list`/`get`/`sign`/`pdfDownloadUrl` | Task #23 JWT + creator join | ✅ PASS when `contractStatus` + real `contractId` loaded |
| Deal room — timeline sheet | `CreatorContractPanel` via contract timeline card | ✅ `sign`/`pdfDownloadUrl` | Task #23 JWT + creator join | ⚠️ **H-A3-1** — premature Sign + synthetic ID risk |
| Deal room — timeline card | `CreatorContractCard` | N/A (opens panel) | Enriched metadata | ⚠️ inherits H-A3-1 when metadata stale |
| Unsigned discovery | `loadUnsignedContracts` | ✅ `listUnsigned` | Creator JWT only | ✅ PASS (silent fail: L-A3-1) |
| Demo / mock path | `creator-contract-store` + `signContract()` | ❌ sessionStorage | N/A in live | ✅ PASS — gated `!isApiLive()` |

---

## 2. Authentication & Authorization

### 2a. API client — creator contract calls

```1031:1058:src/lib/api.ts
  sign: async (
    role: Role,
    id: string,
    options?: { signerRole?: 'BRAND' | 'CREATOR'; name?: string; agreedAt?: string },
  ) => {
    ...
    const body =
      role === 'brand'
        ? { role: options?.signerRole ?? 'BRAND' }
        : undefined;
    const row = await http.request<ContractApiRecord>('POST', `/contracts/${id}/sign`, {
      role,
      body,
    });
```

Creator sign sends **`undefined` body** — no client-supplied `role`, `name`, or `agreedAt` forgery vector. JWT attached via `localStorage` `creator_token` (standard SPA pattern; XSS → token theft is program-level, not A-3 regression).

### 2b. UI cannot escalate privilege

| Attack | UI manipulation | Server result |
|---|---|---|
| Sign another creator's contract | DevTools: `api.contracts.sign('creator', victimUlid)` | **404 CONTRACT_NOT_FOUND** — Task #23 |
| Mint another creator's PDF | `pdfDownloadUrl('creator', victimUlid)` | **404** — scoped presign |
| Force `ACTIVE` via `onStatusChange` only | Skip API, call `updateContractStatus('active')` | **Cosmetic** — no server mutation; deliverables/payouts use server state |
| Replay sign with stale optimistic UI | Double-click Sign | **Idempotent** — `isSigning` + server `executeOnce` / already-signed guard |
| Sign with synthetic `CTR-2024-*` id | Timeline panel before `dealContract` loads | **404** — phantom action, no write |

**Authorization: PASS.** UI is not a trust boundary for signature legality.

### 2c. Live vs demo storage isolation

```913:917:src/pages/creator-chat.tsx
  const updateContractStatus = React.useCallback((dealId: string, status: DealContractStatus) => {
    if (!isApiLive()) {
      setContractStatus(dealId, status);
    }
    setContractStatusByDeal((prev) => ({ ...prev, [dealId]: status }));
```

`creator-contract-store.ts` uses **`sessionStorage`** (not `localStorage`) for demo contract status — **not touched in live mode** for persistence. In-memory `contractStatusByDeal` is a UI cache only; Kavya H-A3-1/M-A3-1 findings address cache correctness, not authz bypass.

---

## 3. H-A3-1 — Timeline Panel Status Default (Pre-Prod HIGH)

### 3a. Finding

```2026:2037:src/pages/creator-chat.tsx
      {selectedContractEvent && hasContract && (
        <CreatorContractPanel
          ...
          status={contractStatus ?? 'brand_signed'}
```

When `resolveDealContractStatus` returns `undefined` (contract still loading, load error, or deal selected before `loadDealContract` completes), the panel **assumes `brand_signed`** → `canCreatorSignDealStatus` → **Sign Now** visible.

Parallel metadata enrichment may supply a **synthetic** contract id in live mode:

```953:957:src/pages/creator-chat.tsx
      const contractId = isApiLive()
        ? (deal.id === selectedDeal?.id && dealContract?.id) ||
          deal.contractId ||
          resolveContractId(deal.id)
```

`resolveContractId` generates `CTR-2024-{paddedDealId}` — not a server ULID.

### 3b. Exploit narrative

1. Creator opens timeline contract card before `dealContract` fetch completes.
2. UI shows **Sign Now** (false `brand_signed`) with synthetic or stale `contractId`.
3. Creator clicks Sign → `POST /contracts/{syntheticId}/sign` → **404** (no signature recorded) **OR** if `deal.contractId` is present but status still unresolved, sign may succeed while UI misrepresented readiness.

Server **fail-closed** on foreign/synthetic IDs prevents IDOR. Risk class: **contract-integrity / informed-consent** (user prompted to sign before verified brand signature and before reviewing server PDF), not privilege escalation.

### 3c. Server note (pre-existing)

`ContractService.doRecordSignature` does **not** require `brandSignedAt != null` before creator sign (L-23-4). UI default exacerbates UX; backend allows out-of-order signatures. **Fix H-A3-1 on UI before prod**; consider server guard in L-23-4 hardening.

### 3d. Mitigation (Ananya, pre-prod)

- Default panel status to `'generated'` or omit panel until `contractStatus` defined.
- Disable Sign unless `dealContract?.id` (or `deal.contractId`) confirmed from API.
- Tools tab path is safer: requires `contractStatus` truthy (L1795) — prefer Tools panel until H-A3-1 fixed.

**Sprint gate: GO** (Tools tab wired correctly). **Prod gate: NO-GO** for timeline inline Sign.

---

## 4. PDF Presign Flow

### 4a. Live path

Both `CreatorDealContractTab` and `CreatorContractPanel`:

1. `GET /contracts/{id}/pdf-download-url` with creator JWT.
2. On success → `window.open(pdf.downloadUrl, '_blank', 'noopener,noreferrer')`.
3. On `CONTRACT_PDF_NOT_READY` → **falls through** to `downloadContractPDF()` with **hardcoded demo deliverables**.

### 4b. Security assessment

| Check | Result |
|---|---|
| Presign scoped to owner | ✅ **PASS** — Task #23 `getPdfDownloadUrlForCreator` |
| URL exfil via `opener` | ✅ **PASS** — `noopener,noreferrer` |
| Presigned URL in browser history | ℹ️ Acceptable — short TTL server-side; same as Task #10 |
| Demo PDF fallback in **live** mode | ⚠️ **M-A3-2** — user may review **fabricated** terms then sign real server contract |

**M-A3-2:** Before prod, either disable demo PDF fallback when `isApiLive()`, or show blocking banner: “Official PDF not ready — do not sign until available.”

---

## 5. M-A3-1 — Tools Tab Error Masking

```1795:1816:src/pages/creator-chat.tsx
                  {hasContract && contractId && contractStatus ? (
                  <CreatorDealContractTab
                    ...
                    error={contractError}
```

When `loadDealContract` fails, `contractError` is set but `contractStatus` may remain `undefined` → UI shows empty “Contract will appear…” instead of destructive Alert. **Security impact:** auth/session/403 failures hidden — user may assume no contract vs. access error. **Medium** integrity/availability; fix per Kavya before prod.

---

## 6. Business Logic & Race Conditions

| Scenario | Assessment |
|---|---|
| Sign before brand (server) | Allowed today (L-23-4); UI should not encourage via H-A3-1 default |
| Optimistic `contractStatusByDeal` after sign | Reconciled from API response in `handleSign` — **PASS** |
| `unsignedDealIds` inference → `brand_signed` | Server validates on sign — cosmetic only |
| Concurrent tab sign | Server idempotency — **PASS** |
| Escrow funded from local state | `escrowFunded` from `normalizeDeal` / API only in live — **PASS** |

---

## 7. Findings Register

| ID | Severity | Finding | Status |
|---|---|---|---|
| H-A3-1 | **HIGH (pre-prod)** | Timeline panel `contractStatus ?? 'brand_signed'` + synthetic `contractId` | **OPEN** — fix before prod timeline Sign |
| M-A3-1 | MEDIUM | Tools tab hides `contractError` when status unresolved | **OPEN** — Kavya confirmed |
| M-A3-2 | MEDIUM | Live demo PDF fallback on `CONTRACT_PDF_NOT_READY` | **OPEN** — informed-consent risk |
| L-A3-1 | LOW | `loadUnsignedContracts` silent catch | **OPEN** |
| L-A3-2 | LOW | No UI sign throttle | **OPEN** — L-23-3 server carry-forward |
| L-A3-3 | LOW | `creator-inbox.tsx` `setContractStatus` (demo page) | **OPEN** — out of A-3 slice |
| Task #23 IDOR / replay / escrow | — | Backend gates | **CLOSED** (Task #23 Kabir) |
| Live localStorage contract state | — | Mock store isolation | **CLOSED** |

---

## Go/No-Go Decision

| Sub-scope | Decision |
|---|---|
| Task A-3 authz delegation (sign/list/PDF) | **GO** |
| Live mock-store isolation | **GO** |
| Tools panel contract tab (loaded status + real id) | **GO** |
| Timeline `CreatorContractPanel` Sign in live mode | **NO-GO** until H-A3-1 |
| Sprint / Meera build gate | **GO** |
| Priya frontend sign-off | **GO** (conditional on pre-prod debt) |
| Production creator e-sign (full UX) | **CONDITIONAL** — H-A3-1 + M-A3-1 + M-A3-2 before prod |

**Pipeline position:** Task A-3 security gate **✅ PASS WITH FINDINGS** — cleared for Priya frontend sign-off. No escalation to Swapnil (no Critical; no sprint-blocking High authz defect).

---

## Kabir Sign-Off

- [x] Cross-creator IDOR via UI probed — server fail-closed (Task #23)
- [x] Live `localStorage` / `sessionStorage` contract forgery probed — gated / not persisted
- [x] Sign body injection probed — creator branch body omitted
- [x] PDF presign + `window.open` probed — scoped presign; `noopener,noreferrer` present
- [x] H-A3-1 timeline default probed — pre-prod HIGH integrity; server blocks synthetic ID sign
- [x] M-A3-2 demo PDF fallback in live probed — informed-consent risk filed
- [x] No Critical findings — pipeline **not blocked**
- [ ] H-A3-1 fix — Ananya before prod timeline Sign
- [ ] M-A3-1 / M-A3-2 — Ananya before prod

**Kabir verdict: ✅ PASS WITH FINDINGS.** Unblocks Priya A-3 frontend sign-off. Escalation to Priya/Swapnil: **none** (pre-prod debt only).

---

**Document Control:** Created 2026-07-09 by Kabir (Task A-3 / #23c). Prior: `creator-esign-A3-kavya-qa.md`, `creator-esign-T23-kabir-redteam.md`. Next: Priya frontend sign-off; Ananya H-A3-1 fix.
