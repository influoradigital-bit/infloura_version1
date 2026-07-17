# B1 · Referral / Invite Program — Workflow & Build Spec

> **Owners:** Priya (CTO) · Arjun (routing) · Tejas (growth) · Rohan (reward economics) · **Status:** 🔴 0% — not started
> **Date:** 2026-07-14 · Closes gap **B1** · Grounded in real code.
> **BLOCKED ON A DECISION** — see §6. Build starts once reward economics are locked.

---

## 1. What it is
Both-sides referral: creator invites creator, brand refers brand → both get a reward when the invitee reaches a qualifying event. Cheapest acquisition channel in the creator economy; currently zero.

## 2. Build with the current system (reuse, don't reinvent)
| Need | Already exists | How to use it |
|---|---|---|
| Reward ledger | `CouponCode` / `CouponRedemption` / `AffiliateEarning` + `WalletLedgerService.post()` | Copy the **exact idempotency-key + UNIQUE-constraint discipline** — never build a second wallet-adjacent system from scratch |
| Paying a reward | `WalletLedgerService.post()` (money movement into a wallet) | A "referral credit" is a ledger posting into the referrer's wallet, same as any other credit |
| Admin surface | Frontend **already has orphan stubs** — `src/admin/services/api-contracts.ts:727` calls `GET /marketing/referrals` (also `/marketing/acquisition`, `/growth`, `/reputation`) — **all 404 today** | Build the backend these already expect; reconcile the stub, don't add a parallel one |
| Fraud guard | `IdempotencyKeyRecord`, existing rate-limit filter | Self-referral + throwaway-account checks reuse the same primitives |

## 3. Architecture
- **New entities:** `ReferralCode` (owner userId, code, side BRAND|CREATOR, active), `ReferralReward` (referrerId, inviteeId, qualifyingEvent, rewardType, amount, status, ledgerRef).
- **Qualifying event (decision, §6):** e.g. invitee's **first funded campaign** (brand) / **first payout** (creator) — reuse existing events (`ConfirmLaunchExecutor` funded-launch, escrow release/payout) as the trigger, so no new event plumbing.
- **Endpoints:** `GET /me/referral-code` (get/generate), `POST /referrals/redeem` (invitee applies a code at signup), `GET /admin/marketing/referrals` (wire the existing stub).
- **Reward posting:** on the qualifying event, post the reward via `WalletLedgerService` with a deterministic idempotency key (`referral-reward:{inviteeId}`) so it can never double-pay.
- **Migration:** timestamp-named (`V<timestamp>__referral_program.sql`) — **note: the repo switched to timestamp migrations** (`V20260713120000__…`), not `V55`.
- **Fraud:** block self-referral (referrer≠invitee), one reward per invitee, optional KYC-verified gate before payout.

## 4. Task loop (Arjun routing)
| # | Task | Owner | Blocked by |
|---|---|---|---|
| R0 | **Reward economics** (who/what/when/amount/fraud thresholds) | Rohan + Swapnil | — (decision) |
| R1 | Spec: entities, qualifying event, API contract (GATE) | Priya + Arjun | R0 |
| R2 | `ReferralCode`/`ReferralReward` entities + migration | Vikram | R1 |
| R3 | Redeem-at-signup + code generation endpoints | Vikram | R2 |
| R4 | Reward trigger on qualifying event via `WalletLedgerService` + fraud guard | Vikram + Kabir | R2 |
| R5 | Wire `GET /admin/marketing/referrals` (existing FE stub) | Vikram | R2 |
| R6 | FE: invite screen + share link + reward status | Ananya | R1 |
| R7 | VERIFY: QA → mvn verify → **Kabir fraud red-team** → Priya sign-off | Kavya/Meera/Kabir/Priya | R2–R6 |

Kabir gate is **required here** — it moves money on a user-triggered event.

## 5. Acceptance criteria
- [ ] A user gets a referral code; an invitee redeeming it links referrer↔invitee.
- [ ] Reward posts **once** on the qualifying event (idempotent — verified against double-fire/replay).
- [ ] Self-referral and one-reward-per-invitee enforced; Kabir red-team can't script past it.
- [ ] Admin `/marketing/referrals` returns real data (stub no longer 404s).
- [ ] `mvn verify` green + reward-idempotency integration test green.

## 6. Decision needed from Swapnil / Rohan (the blocker)
1. **Reward type + amount** (wallet credit? fee discount? cash?) — Rohan models cost, Swapnil approves spend.
2. **Qualifying event** — first funded campaign / first payout / first login?
3. **Fraud thresholds** — KYC-gate before reward? cap per referrer?

Optional **pre-decision groundwork** (schema + entities only, no reward logic) is safe to start if the team wants a head start — but don't build the payout path before §6 is locked (risks throwaway work).
