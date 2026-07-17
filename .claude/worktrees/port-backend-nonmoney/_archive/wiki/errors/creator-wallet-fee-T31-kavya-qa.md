# QA Review: Wallet Fee Transparency UI — Task #31 A2 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~20:50 IST)  
**Verdict:** ✅ **APPROVED** — routed to **Kabir SKIPPED** (no new backend surface; fee auth covered by Task #27 K3) → **Meera** `npm run build` verify → Priya sign-off on creator fee-transparency slice  
**Scope:** Ananya Task #31 A2 — `wallet.platformFee()` + creator wallet transparency card and dynamic payout labels  
**Reference:** `TASK_INBOX.md` Task #31; Vikram Task #27 V2 QA (`wiki/errors/creator-platform-fee-T27-kavya-qa.md`); `10_CREATOR_PAYMENTS_SPEC.md` §7A  
**Reviewed Files:**
- `src/lib/api.ts` — `PlatformFeeResponse`, `mapPlatformFee()`, `wallet.platformFee()`
- `src/pages/creator-wallet.tsx` — transparency card, `fetchPlatformFee`, payout breakdown label

**Backend delta:** None — frontend consumes existing `GET /api/v1/creator/platform-fee` (Task #27 V2).

---

## Executive Summary

Task #31 A2 **passes QA**. The creator wallet page sources the platform take rate from `api.wallet.platformFee()` (live: `GET /creator/platform-fee` with `role: 'creator'`; mock: `mockOr` stub aligned to V2 contract). The transparency card and payout-detail label use `formatFeePercentLabel(platformFee.feePercent)` — **no hardcoded `15%` (or any %) string in `creator-wallet.tsx` JSX**. Loading skeleton, destructive error alert with **Retry**, and live/mock branching match established wallet-page patterns from Tasks #16/#18.

**`npm run build`:** ✅ **PASS** (Kavya re-run, ~31s, 2026-07-09 ~20:50 IST).

**Kabir:** **SKIPPED** for this slice — no new HTTP surface, auth, or mutation. Creator-only gate and response shape were reviewed under Task #27 K3.

Three **low-severity, non-blocking** findings (L-31-1–L-31-3): demo mock payout rupee amounts still imply 10% while the API mock returns 15%; fee card is gated behind wallet summary skeleton; payout dialog can briefly show `0%` if opened before fee fetch completes.

---

## Task #31 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| Fee from API, not hardcoded in UI | ✅ PASS | `grep` on `creator-wallet.tsx`: no `15%`, `0.15`, or literal percent strings; labels use `platformFee.feePercent` via `formatFeePercentLabel()` |
| `wallet.platformFee()` → `GET /creator/platform-fee` | ✅ PASS | `api.ts` L1667–1681: live path `http.request('GET', '/creator/platform-fee', { role: 'creator' })` |
| Response contract matches V2 DTO | ✅ PASS | `PlatformFeeResponse { feeBps, feePercent, source }` + `mapPlatformFee()` finite guards |
| Transparency card (live) | ✅ PASS | Shield card L431–446: rate + escrow-release copy; clarifies withdrawals have no extra fee |
| Dynamic fee label in payout breakdown | ✅ PASS | Dialog L747: `Platform Fee ({formatFeePercentLabel(platformFee?.feePercent ?? 0)})` |
| Loading state | ✅ PASS | `platformFeeLoading` → `Skeleton` L418–419 |
| Error state + retry (live) | ✅ PASS | Destructive `Alert` + `fetchPlatformFee()` retry L420–428 |
| Mock mode behavior | ✅ PASS (see L-31-1) | `!isLive()` returns mock `{ feeBps: 1500, feePercent: 15.0, source: 'GLOBAL_DEFAULT' }`; card renders after `mockOr` delay |
| No new backend beyond V2 | ✅ PASS | Git diff scope: only `src/lib/api.ts`, `src/pages/creator-wallet.tsx` |
| No `console.log` / debug code | ✅ PASS | None in reviewed files |
| TECH-STACK.md compliance | ✅ PASS | Vite SPA, hand-rolled `api.ts`, envelope client, mock fails closed in prod (`assertMockAuthAllowed` on auth paths; fee mock is read-only) |

---

## Loading / Error / Retry Matrix

| Mode | Fetch trigger | Loading UI | Error UI | Retry | Card visible when |
|------|---------------|------------|----------|-------|-------------------|
| **Live** (`VITE_API_MODE=live`) | `useEffect` on mount | Skeleton below earnings card | Destructive alert + Retry button | `onClick={() => void fetchPlatformFee()}` | `platformFee` set after 200 |
| **Mock** | Same | Same (brief ~400ms `mockOr` delay) | Suppressed (`platformFeeError` only set when `isApiLive()`) | N/A in mock | After mock resolves |

**Live error path** mirrors wallet/transactions patterns: `ApiError.message` surfaced; generic fallback string otherwise.

```237:251:src/pages/creator-wallet.tsx
  const fetchPlatformFee = React.useCallback(async () => {
    setPlatformFeeLoading(true);
    setPlatformFeeError(null);
    try {
      const fee = await api.wallet.platformFee();
      setPlatformFee(fee);
    } catch (e) {
      if (isApiLive()) {
        setPlatformFeeError(
          e instanceof ApiError ? e.message : 'Could not load platform fee. Try again.',
        );
      }
    } finally {
      setPlatformFeeLoading(false);
    }
  }, []);
```

**Note (L-31-2):** Fee skeleton/card sits inside the `walletLoading ? … : <>` branch (L359–447). Until wallet summary resolves, the fee transparency block is not painted even if `fetchPlatformFee` already completed. Live UX only — mock wallet resolves synchronously.

---

## API Contract Cross-Check (Task #27 V2)

| V2 backend (`CreatorPlatformFeeDtos`) | Frontend (`api.ts`) | Match |
|--------------------------------------|---------------------|-------|
| `feeBps` (int) | `feeBps: number` | ✅ |
| `feePercent` (double) | `feePercent: number` | ✅ |
| `source` (`GLOBAL_DEFAULT`) | `source: string`, default `'GLOBAL_DEFAULT'` in mapper | ✅ |
| Creator JWT | `{ role: 'creator' }` on `http.request` | ✅ |
| `ApiResponse` envelope | `http.request` unwraps `envelope.data` | ✅ |

```1667:1682:src/lib/api.ts
  /** GET /creator/platform-fee — read-only creator take rate (Task #27). */
  platformFee: async (): Promise<PlatformFeeResponse> => {
    if (!isLive()) {
      return mapPlatformFee(
        await mockOr({
          feeBps: 1500,
          feePercent: 15.0,
          source: 'GLOBAL_DEFAULT',
        }),
      );
    }
    const row = await http.request<PlatformFeeResponse>('GET', '/creator/platform-fee', {
      role: 'creator',
    });
    return mapPlatformFee(row);
  },
```

Mock stub values live in the **API mock layer** (aligned to V41 seed + V2 spec example), not in page JSX — satisfies “no hardcoded 15% in UI.”

---

## `formatFeePercentLabel` — Edge Cases

| Input | Output | Verified |
|-------|--------|----------|
| `15` | `15%` | ✅ integer path |
| `15.0` | `15%` | ✅ |
| `12.55` | `12.55%` | ✅ code trace |
| `NaN` / non-finite | `—` | ✅ guard L173–174 |

---

## Mock Mode Behavior

| Check | Result |
|-------|--------|
| `platformFee()` called on mount in mock | ✅ same `useEffect` as live |
| Mock returns V2-shaped payload | ✅ 1500 bps / 15.0% / `GLOBAL_DEFAULT` |
| Transparency card renders | ✅ after `mockOr` ~400ms delay |
| Demo payout tab still uses `mockPayouts` | ✅ `showDemoPayouts = !isApiLive()` unchanged |
| Live payout list | ✅ honest “Per-deal payout rows coming soon” banner — no mock rows |

---

## Findings (Non-Blocking)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| **L-31-1** | Low | Demo `mockPayouts` rupee `platformFee` values imply **10%** of gross (e.g. ₹5,000 on ₹50,000), while the transparency card and dialog **label** show **15%** from `wallet.platformFee()` mock. Label is correct per API; demo amounts are stale. | Optional polish: recompute mock payout `platformFee`/`gst`/`netAmount` at 15% or derive label from gross when displaying demo rows only. **Does not affect live path.** |
| **L-31-2** | Low | Fee transparency UI hidden until `walletLoading` clears (nested inside wallet skeleton branch). | Consider rendering fee block outside wallet loading gate for parallel perceived load. |
| **L-31-3** | Low | Payout detail dialog uses `platformFee?.feePercent ?? 0` — if user opens a demo payout before fee fetch completes, label briefly shows `0%`. | Gate dialog breakdown on `platformFee` or show skeleton in label until loaded. |

None of the above block merge or live fee transparency; live creators see API-sourced rate on the card independent of demo payout math.

---

## Security (Basic — Kabir Escalation)

| Check | Result |
|-------|--------|
| Hardcoded secrets | ✅ None |
| Client-trusted fee for deductions | ✅ N/A — display only; V1 release math is server-side (Task #26) |
| Auth on live fetch | ✅ Creator token via `role: 'creator'` |
| Sensitive data logged | ✅ None |
| New attack surface | ✅ None — read-only GET already gated in Task #27 |

**Kabir:** Deep review deferred to Task #27 K3 (batch). This frontend slice adds no new endpoints.

---

## Test Coverage

| Layer | Tests | Kavya |
|-------|-------|-------|
| Frontend unit/E2E for fee card | ❌ None authored | Acceptable for P1 UI slice; matches Task #30 pattern |
| Backend Task #27 | 3 unit tests authored | Covered in `creator-platform-fee-T27-kavya-qa.md` |
| Build gate | `npm run build` | ✅ PASS (this run) |

**Meera command:**
```bash
npm run build
```

---

## Gate Routing

| Gate | Status | Notes |
|------|--------|-------|
| **Kavya Kv1 (Task #31)** | ✅ **APPROVED** | This document |
| **Kabir** | ⏭️ **SKIPPED** | Frontend-only; Task #27 K3 owns fee endpoint security |
| **Meera M2** | ⏳ **NEXT** | `npm run build` (Kavya pre-pass ✅) |
| **Priya** | ⏳ QUEUED | Fee-transparency slice sign-off with #27 backend |

---

## Sign-Off

**Kavya Patel — QA Lead**  
Task #31 A2 wallet fee transparency UI: **APPROVED** for Kabir skip → Meera build → Priya.
