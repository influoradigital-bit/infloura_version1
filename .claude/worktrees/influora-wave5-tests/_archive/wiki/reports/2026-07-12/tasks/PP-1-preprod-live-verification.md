# PP-1 — Pre-prod LIVE verification gate (real host required)

**Owner:** Meera (run on a networked, non-sandbox host) → Vikram/Kabir on any failure · **Reviewers:** Priya (final ship gate) · **Priority:** 🔴 PRE-PROD BLOCKER · **Depends on:** a non-sandbox host
**Status:** ⬜ TODO (cannot run in this sandbox)

## Why this exists
Three rows are signed 🟢 for **code** but carry a MANDATORY live-run gate — the sandbox physically cannot boot Spring's `HttpClient` (Windows NIO loopback-socket restriction, independently reproduced). These MUST be run for real before production. **Do NOT ship these paths on static verification alone.**

## Gates to run (on a real host with network)
1. **P1-5 — Meera 3-tier E2E:** boot full stack; send a chat message in the browser; confirm a **real Claude reply** streams into the UI end-to-end (browser → Python SSE → Spring writeback). Ref: `tasks/P1-5-meera-e2e.md`.
2. **P2-14 — endpoint curl smoke test:** live `curl` all four endpoints (content-performance, brand review inbox, brand disputes, creator disputes) → expect 2xx + correct shapes. Ref: `tasks/P2-14-content-review-disputes.md`.
3. **P2-12 — Razorpay sandbox smoke test (real money path):** run `createContact` → `createFundAccount` → `initiatePayout` against Razorpay **sandbox** credentials on a networked host. Confirm no double-pay, correct fund-account resolution, FAILED-key retry (`reclaimFailedForRetry`) works. Ref: `tasks/P2-12-payout-kyc.md`.

## Acceptance criteria
- [ ] P1-5 live E2E: real AI reply rendered end-to-end (screenshot/log)
- [ ] P2-14: all 4 endpoints return 2xx + correct shape via live curl
- [ ] P2-12: Razorpay sandbox 3-call flow passes; retry path verified
- [ ] Priya final ship sign-off recorded

## Completion log
- _(run on real host — attach logs/screenshots as proof; do NOT mark done from the sandbox)_
