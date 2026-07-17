# Feature: Escrow

**Business Purpose** — Escrow is the platform's trust mechanism: a brand's funds are held (in the platform's clearing wallet) against a campaign/milestone while the creator works, then released (net of the platform commission) to the creator, refunded to the brand, or frozen/split under dispute. No creator gets paid until work is accepted.

**Who uses it** — Brands (fund/release/refund), creators (receive), admins (dispute settlement), Meera (`confirm_launch` requires funded escrow).

## User Roles
Brand (fund/release/refund, OWNER/ADMIN for fund), Creator (payee), Admin (dispute settlement).

## Permissions
Fund → OWNER/ADMIN. Release/refund → brand member. Dispute settlement → admin (MFA).

## Business Flow
```
Brand fund (campaign/milestone) → Razorpay order → webhook → brand → clearing (ESCROW_HOLD, FUNDED)
Release (milestone) → dispute gate + FUNDED → platform fee (clearing → revenue) → net → creator (ESCROW_RELEASE, RELEASED)
Refund → clearing → brand (full gross, ESCROW_REFUND)
Dispute → freeze (FUNDED→FROZEN) → admin release/refund/split
```
`EscrowStatus`: PENDING → FUNDED → RELEASED / REFUNDED / FROZEN.

## Frontend
- **Components**: `feature/meera/FundEscrowButton`, `ui/escrow-pill`, `shared/escrow-status-bar`; `hooks/useEscrowFund` (idle→initiating→awaiting_payment→verifying→funded).
- **API**: `api.payments.fundEscrow`, `api.payments.releasePayout`.

## Backend
- **Controller**: `EscrowController` (`/wallet/escrow`).
- **Service**: `EscrowService` (deriveFundAmount, initiateFund, confirmFunded, release, refund, admin dispute settlement).

## Database
`escrow_holds` (V9; `amount` gross, `status`, `hold_txn_id`, `release_txn_id`, `idempotency_key`), `payment_milestones` (V10). See [../database.md](../database.md).

## APIs
`POST /wallet/escrow/fund` (Idempotency-Key, no amount — derived), `GET /wallet/escrow/{id}`, `POST /wallet/escrow/release`, `POST /wallet/escrow/refund`, `POST /wallet/escrow/payout`.

## AI
`confirm_launch` reads FUNDED holds **fresh from DB** (never an AI-asserted flag) before activating a campaign.

## Notifications
`EscrowFundedEvent` → `creator.campaign_live`.

## Dependencies
- **Depends on**: wallet ledger, Razorpay (fund order/webhook), platform fees, invoicing, contracts/milestones.
- **Depended on by**: campaigns (publish/launch), payouts, disputes.

## Connected Files
`EscrowController`, `EscrowService`, `PlatformFeeService`, `WalletLedgerService`, `domain/entity/{EscrowHold,PaymentMilestone}`, `RazorpayWebhookController`.

## Execution Flow
```
Fund: POST /wallet/escrow/fund → deriveFundAmount (milestone.amount or campaign.budgetMax)
  → balance pre-check (INSUFFICIENT_FUNDS 402) → Razorpay order (PENDING) → webhook → confirmFunded
  → brand DEBIT → clearing CREDIT (ESCROW_HOLD, key "escrow-fund:"+holdId) → FUNDED
Release: POST /wallet/escrow/release → dispute gate + FUNDED → fee = gross*feeBps/10000 (clearing→revenue)
  + creator-leg commission invoice → net = gross−fee → clearing → payee (ESCROW_RELEASE) → RELEASED + service invoice
```

## Error Handling
`INSUFFICIENT_FUNDS` (402), `ESCROW_BLOCKED_BY_DISPUTE`, `CAMPAIGN_BUDGET_MISSING`, `ESCROW_NOT_FUNDED` (409, launch). Terminal states are idempotent no-ops.

## Security
Amount derived server-side (never request body); webhook amount/currency cross-checked; dispute gate prevents release of contested funds; payee resolved from `Collaboration.creatorId`.

## Performance
Pessimistic hold lock; net-zero effect on clearing wallet per hold; idempotent postings.

## Testing
Escrow release/refund/split tests. Regression risks: fee split, dispute gate, idempotency.

## Production Readiness
- **Health**: 7/10 · **Completion**: ~82%
- **Known issues**: `release_condition` (V52) unmapped — release gates only on dispute + FUNDED; escrow-release net vs RazorpayX payout gross mismatch (see [payouts.md](payouts.md)); `escrow_balance` field never written. See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
