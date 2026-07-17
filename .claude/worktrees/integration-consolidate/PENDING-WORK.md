# Influora — Pending Work

**Owner:** Priya (CTO) · **Date:** 13 Jul 2026 · **Overall build progress: ~85%**
Source: code-verified audit of the working tree (not planning docs).

> **Summary:** Foundation is done. The remaining ~15% is *verification + deployment*, not new features. Only small amounts of new code required (KYC server-side, TODO cleanup, and whatever the build surfaces).

---

## P0 — Blockers (must close first)

- [ ] **Backend build not proven.** No packaged jar in `influora-api/target/`. Run full `mvn verify` — confirm all 953 tests pass green and produce the deployable artifact. *(biggest unknown)*
- [ ] **Frontend still mock by default.** Live `fetch()` is coded but `VITE_API_MODE=mock`. Flip to `live`, set `VITE_API_BASE_URL` to the deployed API, and smoke-test all 31 client resource groups against real endpoints.

## P1 — Finish server-side gaps

- [ ] **KYC (B-5) backend.** Frontend prompt is done; backend path flagged "Maven-gated" in git. Finish + verify server-side.
- [ ] **AI service live integration.** Verify Claude / Gemini / Sarvam calls end-to-end with real provider keys; tune the per-workspace cost gate / budget.

## P2 — Deploy

- [ ] Produce build artifacts for all 3 services (SPA `dist`, Spring jar, AI image).
- [ ] Wire production env + secrets (JWT/JWKS, Razorpay, Meta, Shopify/WooCommerce, MSG91, S3/R2, AI keys).
- [ ] Deploy Web + API + AI service; run post-deploy smoke test.

## P3 — Cleanup & hardening

- [ ] Reconcile recent "restore from stash / restore stub'd core files" commits — diff-review to confirm nothing was lost.
- [ ] Clear open TODO/FIXME markers (~25 frontend, 7 backend, ~1 AI).
- [ ] Confirm placeholder routes are intentional (deals→chat, pipeline→chat, inbox→deals) — not unfinished stubs.
- [ ] Retire / update stale `TECH-STACK.md` (still describes a Next.js monolith; real stack is React SPA + Spring + FastAPI).
- [ ] Security: pen-test pass + secret rotation drill before launch.

---

## Progress snapshot (code-verified)

| Layer | Weight | Done | Remaining |
|---|---|---|---|
| Backend (Spring) | 40% | ~90% | Green `mvn verify` + jar, KYC server-side |
| Frontend (React) | 30% | ~85% | Flip to live, smoke-test resources |
| AI service (FastAPI) | 10% | ~80% | Live provider verification + budget tuning |
| Security | 10% | ~90% | Pen-test + secret rotation drill |
| DevOps / deploy | 10% | ~60% | Artifacts, prod env, deploy |
| **Total** | **100%** | **~85%** | Verification + deployment |

**Bottom line:** path from 85% → 100% is *finishing and shipping*. No architectural rework required.
