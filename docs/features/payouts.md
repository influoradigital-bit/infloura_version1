# Feature: Payouts

**Business Purpose** — Moves a creator's released earnings from the internal wallet out to a real bank account / UPI via **RazorpayX Payouts**, out-of-band from the internal ledger release. This is the "cash out" step.

**Who uses it** — Creators (withdraw), brands (queue a milestone payout), admins (finance ops), and the payout webhook.

## User Roles
Creator (withdraw), Brand member (queue payout of a released milestone).

## Permissions
Queue payout → brand member (imperatively checked). Withdraw → creator.

## Business Flow
```
Escrow release → creator wallet credited (net)
Creator withdraw OR brand queue-payout (RELEASED milestone) → RazorpayX payout initiated (queued)
Razorpay payout.processed/reversed webhook → confirm (intended)
```

## Frontend
- **Components**: `feature/meera/PayoutLedger`, creator wallet payout views, `creator/connected-accounts` / bank forms.
- **API**: `api.wallet.payout`, `api.wallet.withdraw`.

## Backend
- **Controller**: `EscrowController.POST /wallet/escrow/payout`; withdraw on `WalletController`.
- **Services**: `service/payout/PayoutService`, `RazorpayFundAccountService` (orphaned), `CreatorBankAccountService` (orphaned).
- **Client**: `integration/razorpay/RazorpayXClient` (raw HttpClient, HTTP Basic, `POST /payouts`, IMPS).

## Database
`payment_milestones` (holds payout state — `idempotency_key`, `markPayoutQueued`), `creator_bank_accounts` (V47/V49/V62, encrypted, 24h cool-down), `payouts` (V48 — **dead code**). See [../database.md](../database.md).

## APIs
`POST /wallet/escrow/payout` (Idempotency-Key, no amount), `POST /wallet/withdraw`, `POST /webhooks/razorpay` (`payout.processed`/`reversed`).

## AI
Not involved (no AI route for payout config — structurally forbidden).

## Notifications
`PayoutReleasedEvent` → creator notification.

## Dependencies
- **Depends on**: escrow (milestone must be RELEASED), wallet, RazorpayX.
- **Depended on by**: creator cash-out UX.

## Connected Files
`PayoutService`, `RazorpayXClient`, `EscrowController`, `domain/entity/{PaymentMilestone,CreatorBankAccount,Payout}`, `RazorpayWebhookController`.

## Execution Flow
```
Queue: POST /wallet/escrow/payout → PayoutService.queuePayout (requireMember, key "payout:"+milestoneId)
  → validateForPayout (milestone exists, escrowHold RELEASED, ownership) → executeOnce
  → RazorpayXClient.initiatePayout(creatorId, milestone.amount, currency, key) → markPayoutQueued
```

## Error Handling
`MILESTONE_NOT_FOUND` (404, cross-tenant), validation ordering (ownership before state). RazorpayX `requireSuccessStatus` fails closed on non-2xx; error bodies never logged (PII).

## Security
Amount server-derived; bank instruments AES-GCM encrypted, only `display_mask` returned; 24h cool-down on new instruments; `X-Payout-Idempotency`; paise conversion `movePointRight(2).longValueExact()`.

## Performance
Idempotent; single external call per milestone.

## Testing
Payout validation tests; `CreatorBankAccountService` is tested but unrouted.

## Production Readiness
- **Health**: 4/10 · **Completion**: ~55% (**most incomplete money area**)
- **Known issues**: `payouts` table/entity/repository are **dead code** (state on milestones); `confirmExecuted` is a **no-op** (payouts never leave `queued`, reversals invisible); live payout passes the **internal user id as `fund_account_id`** (placeholder — `RazorpayFundAccountService` never called); **no creator-facing bank-instrument endpoints** (add/list/set-primary orphaned); escrow-release net vs payout **gross** amount mismatch. Works today only because RazorpayX `isConfigured()` is false in dev. See [../known-limitations.md](../known-limitations.md).
- **Missing**: bank-account HTTP routes, real fund-account provisioning, payout status reconciliation.
- **Last verified**: 2026-07-15
