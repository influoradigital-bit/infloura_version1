# QA Review: Creator Wallet Live API Wiring — Task #16 (Kavya)

**Reviewer:** Kavya Patel (QA Lead)  
**Date:** 2026-07-09 (~15:00 IST)  
**Verdict:** ✅ **APPROVED** — routed to Kabir (no new security surface) → Meera build confirm → Priya sign-off on wallet slice  
**Scope:** Ananya Task #16 — `creator-wallet.tsx` live API wiring vs Vikram Task #10 `WalletController` creator branch  
**Reference:** `TASK_INBOX.md` Task #16; Kabir Task #10 PASS (`wiki/errors/creator-wallet-contract-T10-kabir-redteam.md`)  
**Reviewed Files:**
- `src/pages/creator-wallet.tsx`
- `src/lib/api.ts` (`wallet` group, `WalletSummaryResponse`, `isApiLive`)
- `influora-api/src/main/java/com/influora/web/WalletController.java` (contract cross-check)
- `influora-api/src/main/java/com/influora/service/WalletService.java` (`getSummaryForUser` cross-check)
- `influora-api/src/main/java/com/influora/web/dto/money/MoneyDtos.java` (`WalletSummaryResponse` fields)

---

## Executive Summary

Creator wallet live API wiring **passes QA**. `npm run build` **PASS** (Vite 6.4.2, 4587 modules, zero errors). `api.wallet.get('creator')` correctly calls `GET /wallet` with creator JWT, maps backend `WalletSummaryResponse` fields to the earnings hero and sub-cards, and gates mock payouts/history/tax behind `!isApiLive()`. Loading skeleton, destructive error Alert with retry, and honest gap banners for withdraw, transaction history, and payout rows in live mode all match the established pattern from `creator-campaigns.tsx` / `creator-chat.tsx`.

Non-blocking polish: Payout Settings dialog still shows demo payment methods in live mode (M-1); failed fetch still renders a zero-balance hero below the error banner (M-2). Neither blocks sprint gate — Vikram withdrawal/transaction endpoints are correctly deferred with fail-closed `NOT_IMPLEMENTED` in `api.ts`.

---

## Build Verification

| Gate | Result | Evidence |
|------|--------|----------|
| `npm run build` | ✅ **PASS** | Vite 6.4.2, 4587 modules, built ~58s, zero errors (non-blocking `baseUrl` duplicate + chunk-size warnings) |
| `console.log` / debug code | ✅ **PASS** | None in `creator-wallet.tsx` |
| TECH-STACK.md alignment | ✅ **PASS** | Vite SPA, shadcn/Radix components, `isApiLive()` mock gating, envelope client via `http.request` |

**Command run (2026-07-09):**
```bash
npm run build
# → ✓ 4587 modules transformed; ✓ built in 58.48s
```

---

## Task #16 Definition of Done — Verification

| DoD Item | Result | Evidence |
|----------|--------|----------|
| `api.wallet.get('creator')` wired | ✅ PASS | `fetchWallet()` L195 → `api.wallet.get('creator')`; `api.ts` L1064 `GET /wallet` with `{ role: 'creator' }` |
| Field mapping to UI | ✅ PASS | `summaryToEarningsView()` L147–160: `availableBalance` → hero (live); `escrowLocked` → In Escrow; `pendingPayouts` → Awaiting Release; `totalPosition` = sum |
| Loading state | ✅ PASS | `walletLoading` initial `isApiLive()`; Skeleton L281–286 during fetch |
| Error + retry | ✅ PASS | `walletError` Alert destructive L269–278; Retry calls `fetchWallet()`; `ApiError.message` surfaced |
| Mock fallback only when `!isApiLive()` | ✅ PASS | `fetchWallet` early-return L186–189; `showDemoPayouts` / `showDemoTaxDocs` gated L208–209 |
| Honest gap — withdraw | ✅ PASS | `withdrawLiveBlocked = isApiLive()` L210; Alert L733–741; submit disabled L837 |
| Honest gap — payouts tab | ✅ PASS | Alert L350–358 when `isApiLive()`; mock payout cards hidden |
| Honest gap — history tab | ✅ PASS | Alert L453–461 references `GET /wallet/transactions` not built |
| Honest gap — tax tab | ✅ PASS | Alert L519–526; mock tax docs hidden in live |
| `api.ts` fail-closed for unbuilt endpoints | ✅ PASS | `wallet.withdraw` / `wallet.transactions` / `wallet.recharge` reject with `NOT_IMPLEMENTED` in live mode L1093–1128 |

---

## API Contract Cross-Check (WalletController #10)

| Frontend call | Backend route | Backend handler | Match |
|---------------|---------------|-----------------|-------|
| `api.wallet.get('creator')` | `GET /wallet` | `getSummary()` → `UserType.CREATOR` → `walletService.getSummaryForUser(principal.getUserId())` | ✅ |
| `api.wallet.getBalance('creator')` | `GET /wallet/balance` | `getBalance()` → creator branch → `getBalanceForUser(principal.getUserId())` | ✅ (not used by page yet — OK) |
| `api.wallet.withdraw(amount)` | `POST /wallet/withdraw` | Not implemented | ✅ fail-closed client-side |
| `api.wallet.transactions('creator')` | `GET /wallet/transactions` | Not implemented | ✅ fail-closed client-side |

### Field semantics (backend → frontend)

| `MoneyDtos.WalletSummaryResponse` | `WalletService` source | `creator-wallet.tsx` display (live) |
|-----------------------------------|------------------------|-------------------------------------|
| `availableBalance` | `wallet.balance` | Hero — "Available Balance" |
| `escrowLocked` | `wallet.escrowBalance` | Sub-card — "In Escrow" |
| `pendingPayouts` | `sumAmountByCreatorIdAndStatus(FUNDED)` | Sub-card — "Awaiting Release" (`pendingRelease`) |
| `runwayDays` | `computeRunwayDays(wallet)` | Not displayed (creator has no runway UI — OK) |

`parseWalletAmount()` in `api.ts` safely coerces `BigDecimal` JSON strings to numbers — consistent with `normalizeDeal` pattern from Task #14.

---

## Functional Review

### Live mode data flow

1. Mount → `fetchWallet()` if `isApiLive()`.
2. `setWalletLoading(true)` → `GET /wallet` with creator bearer token.
3. Success → `summaryToEarningsView(summary)` updates hero + sub-cards.
4. Failure → destructive Alert + message; earnings remain at initial zeros (see M-2).
5. Tabs: payouts/history/tax show gap Alerts only; no mock rows leak.
6. Withdraw dialog: gap Alert + disabled submit; `handleWithdraw` path to `api.wallet.withdraw` unreachable while `withdrawLiveBlocked`.

### Mock mode

- `fetchWallet` sets `mockEarningsView()` immediately.
- Full demo UX: payout cards, transaction history, tax docs, withdraw simulation (2s delay).
- Label semantics differ intentionally (hero shows "Total Earned" vs live "Available Balance").

### Edge cases

| Scenario | Behavior | Verdict |
|----------|----------|---------|
| Network/API failure | Error Alert + Retry; skeleton clears | ✅ |
| Empty wallet (no row) | Backend returns zeros + pending milestones; UI shows ₹0 | ✅ |
| `BigDecimal` as string in JSON | `parseWalletAmount` coerces | ✅ |
| User opens withdraw in live | Button disabled; gap banner visible | ✅ |
| User opens Payout Settings in live | Demo UPI/bank shown — **M-1** | ⚠️ non-blocking |

---

## Code Quality Checklist

| Check | Result |
|-------|--------|
| Follows TECH-STACK.md | ✅ |
| No console.logs | ✅ |
| Error handling (`ApiError` instanceof) | ✅ |
| TypeScript types strict (`WalletEarningsView`, `WalletSummaryResponse`) | ✅ |
| Comments explain WHY (mapping semantics) | ✅ — `summaryToEarningsView` + api.ts header |
| Reuses established UI patterns | ✅ — Alert, Skeleton, `isApiLive()` |

---

## Security Review (Basic — escalate deep review to Kabir)

| Check | Result | Notes |
|-------|--------|-------|
| No hardcoded secrets | ✅ | |
| No client-supplied creator id | ✅ | Identity from JWT via `{ role: 'creator' }` token selection |
| Backend uses `principal.getUserId()` only | ✅ | Kabir Task #10 already PASS |
| Sensitive data not logged | ✅ | |
| Mock data hidden in live mode (money) | ✅ | Summary from API only |
| **Escalation to Kabir** | N/A | No new HTTP surface; Task #10 re-review covers creator wallet path |

---

## Findings

### Non-blocking (carry-forward)

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| M-1 | MEDIUM | **Payout Settings** dialog (`showPayoutSettings`) renders hardcoded demo UPI `priya@okaxis` and bank `HDFC ****4532` when `isApiLive()` — no gap banner | Add honest gap Alert or disable Settings button in live until payout-method API ships |
| M-2 | LOW | On fetch error, zero-balance earnings hero still renders below error Alert | Hide hero when `walletError` set, or keep last successful fetch |
| L-1 | LOW | No frontend unit tests for `summaryToEarningsView` / `parseWalletAmount` | Acceptable per project debt; optional Vitest later |

### Blockers

None.

---

## Test Coverage

| Area | Coverage | Notes |
|------|----------|-------|
| Backend `WalletServiceTest` creator paths | ✅ 13/13 (Meera Task #10) | `getSummaryForUser` isolation verified |
| Frontend unit tests | ❌ None | Project-wide gap — not blocking this slice |
| Manual QA (code review) | ✅ | All DoD paths traced |

---

## Routing

| Next gate | Owner | Action |
|-----------|-------|--------|
| Security | Kabir | Awareness only — no new review required unless Vikram adds withdraw/transactions |
| Build confirm | Meera | Re-run `npm run build` if not already on this commit |
| Sign-off | Priya | Wallet summary slice ready for sprint credit |
| Follow-up | Vikram + Ananya | `POST /wallet/withdraw`, `GET /wallet/transactions`, payout settings API |

---

**Kavya sign-off:** ✅ **APPROVED** for Kabir awareness → Meera → Priya pipeline.
