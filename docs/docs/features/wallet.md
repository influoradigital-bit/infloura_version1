# Feature: Wallet

**Business Purpose** — A **double-entry wallet ledger** is the single source of truth for all money movement on Influora. Brands fund a workspace wallet (Razorpay top-ups) to pay for campaigns; creators receive escrow releases and withdraw them. Two hidden platform wallets (clearing + revenue) are the counterparty for every escrow/fee posting.

**Who uses it** — Brands (top-up, fund escrow), creators (receive, withdraw), and every money feature internally.

## User Roles
Brand (workspace wallet; top-up = OWNER/ADMIN), Creator (user wallet; withdraw).

## Permissions
Top-up → OWNER/ADMIN. Withdraw → creator only. Transactions list → creator (own ledger).

## Business Flow
```
Brand top-up → Razorpay order → webhook order.paid → ledger: clearing → brand wallet (DEPOSIT)
Brand fund escrow → brand → clearing (ESCROW_HOLD)   [see escrow]
Escrow release → clearing → creator (net)            [see escrow]
Creator withdraw → creator → clearing (queues RazorpayX payout)
```

## Frontend
- **Pages**: `brand-wallet` (mock escrow), `creator-wallet`.
- **Hooks**: `useWalletTopUp`, `useEscrowFund`.
- **API**: `api.wallet.*`.

## Backend
- **Controller**: `WalletController` (`/wallet`).
- **Services**: `WalletService`, `WalletLedgerService` (**the sole ledger writer**), `WalletTopUpService`.

## Database
`wallets` (V2; `owner_type` USER/WORKSPACE, `balance`, `escrow_balance`, unique per owner), `wallet_transactions` (V8; append-only DEBIT/CREDIT legs sharing `group_id`, per-leg `idempotency_key` unique), `wallet_topups` (V20260709155921). See [../database.md](../database.md).

## APIs
`GET /wallet/balance`, `GET /wallet` (summary), `POST /wallet/topup` (Idempotency-Key), `POST /wallet/withdraw`, `GET /wallet/transactions`.

## AI
Meera never touches the wallet; `request_payment` only stages a human confirmation.

## Notifications
`WalletLowBalanceEvent` → brand low-balance notification.

## Dependencies
- **Depends on**: Razorpay (top-up orders + webhooks), idempotency.
- **Depended on by**: escrow, platform fees, payouts, billing.

## Connected Files
`WalletController`, `WalletService`, `WalletLedgerService`, `WalletTopUpService`, `domain/entity/{Wallet,WalletTransaction,WalletTopUp}`, `RazorpayWebhookController`.

## Execution Flow
```
Ledger post (WalletLedgerService.post): validate amount>0 + key → replay fast-path (key:D/key:C)
  → lock both wallets findByIdForUpdate (ascending id) → currency match + balance check
  → applyBalanceDelta(−amount)/(+amount) → write two COMPLETED legs (group_id, balance_after)
  → DB unique constraint is the true serialization point (DataIntegrityViolation → replay-match)
Top-up: POST /wallet/topup (server-derived key "topup:"+id) → Razorpay order (PENDING) → webhook → confirmCredited (amount cross-check) → DEPOSIT → CREDITED
```

## Error Handling
`INVALID_LEDGER_AMOUNT`, `INSUFFICIENT_BALANCE`, `CURRENCY_MISMATCH`, `LEDGER_POSTING_CONFLICT`/`INCONSISTENT` (409), `LEDGER_IDEMPOTENCY_KEY_COLLISION` (409), `TOPUP_AMOUNT_MISMATCH` (409), withdraw `WITHDRAWAL_RATE_LIMIT` (429).

## Security
Server-derived amounts only (top-up amount reconciled at webhook); ledger keys derived from server ids, never client headers (OWASP fix); withdraw floor ₹500 / ceiling ₹100,000, max 3/UTC-day. All amounts positive; sign via `TxnDirection`.

## Performance
Balances are a denormalized projection updated in the same transaction (no running-sum recompute); pessimistic wallet locks scoped to the posting.

## Testing
Ledger idempotency/locking tests. Regression risks: replay-match, deadlock ordering, currency check.

## Production Readiness
- **Health**: 8/10 · **Completion**: ~85% (core ledger solid)
- **Known issues**: **`wallets.escrow_balance` is never written** — brand dashboard `escrowLocked` always 0.00 (funds actually live in the clearing wallet). See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
