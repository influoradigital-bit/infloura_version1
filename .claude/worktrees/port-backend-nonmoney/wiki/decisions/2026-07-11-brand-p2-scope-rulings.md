# BRAND P2 Scope Rulings — 2026-07-11

> Compiled by the orchestrator from code evidence + CTO direction.
> (The `priya` arbiter agent misfired — 0 tool uses, no output — so these rulings are
> code-anchored directly. CTO steer: "finish BRAND P2 first; KYC is optional.")

## B-2 Timeline — RULING: ship PARTIAL, backlog a unified activity-log
`collaboration-timeline.tsx` is genuinely live for messages+proposals (Kavya QA PASS).
Backend persists only 3 of 7 event kinds (`text`/`proposal`/`system`); contract/deliverable/
payment/shipment have no activity feed. **Ship as PARTIAL** (repo's established "SHIPPED/
CONDITIONAL + deferred wave" convention). **New backlog (P2 backend):** unified deal
**activity-log** — cheapest path is likely extending `DealMessageKind` + appending a
`DealMessage` (kind=contract/deliverable/payment/shipment) from the respective services on
each state change, rather than a separate `activity_log` table. Owner: Vikram. Not now.

## B-3 Settings / Store Integration — RULING: BUILD (do not remove)
`StoreIntegrationSetup.tsx` + `useStoreIntegration` are real; `api.storeIntegrations.*`
(`authorizeShopify`/`connectWooCommerce`/`disconnect`/`status`) reject `NOT_IMPLEMENTED`
in live. Evidence: `integration/shopify` + `integration/woocommerce` backend packages exist
(clients/webhooks for conversion tracking) but expose **no settings-facing connect/status
controllers**. So this is **real net-new backend** (store-connect OAuth authorize + callback,
connection status, disconnect, a `StoreIntegration` entity), reusing the existing Shopify/Woo
clients. **Largest P2 item; Kabir-relevant (OAuth/secrets).** UI already degrades gracefully,
so it is not urgent — schedule after B-4/B-5. Owner: Vikram (backend) + Ananya (wire), Kabir.

## B-5 KYC at first campaign creation — RULING: build, KYC **OPTIONAL** (CTO)
CTO direction 2026-07-11: **KYC is optional** — collect GSTIN/PAN at first campaign creation
via a **soft, non-blocking** flow (prompt + skip; NOT a hard gate on campaign publish). Wire
the already-existing `api.onboarding.submitBrandKyc` (currently zero call sites). Owner:
Ananya + Vikram; Kabir (compliance-adjacent). No hard publish block.

## B-6 Deduplicate source trees — RULING: remove root `components/` (dead), do it last
`@/` alias → `./src` only (vite + tsconfig); tsconfig `include` = `src/**` only; **zero** `src/`
imports of the root `components/` tree; no CSS/asset imports into it; 67 files, outside the
Vite build entirely. **Confirmed dead → safe to delete** as the final cleanup step (git-
recoverable; commit separately). `.claude/worktrees/**` copies are git worktrees — leave them.
Do a final `npm run build` after removal to prove nothing broke. Owner: orchestrator/Vikram.

## Execution order (CTO: finish BRAND P2 first)
1. **B-4** brand accept/reject (Vikram backend → Ananya wire → Kavya → Kabir) — in flight.
2. **B-5** KYC optional (Ananya + Vikram → Kavya → Kabir).
3. **B-3** store-connect backend (largest; Vikram + Ananya → Kabir → Kavya).
4. **B-6** delete dead root `components/` + build-verify (cleanup, last).
5. Then reassess before ADMIN Phase 2.
