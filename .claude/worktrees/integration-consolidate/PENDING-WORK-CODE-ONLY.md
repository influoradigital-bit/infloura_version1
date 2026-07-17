# Influora — Pending Work That Needs CODE

**Owner:** Priya (CTO) · **Date:** 13 Jul 2026
Scope: only tasks that require **writing/editing code**. Verification, env flips, and deployment are excluded (see `PENDING-WORK.md` for those).

> **Reality check:** very little new code left. Most of the remaining ~15% is verification + deploy. The list below is the actual coding work.

---

## Definite code work

- [ ] **KYC (B-5) backend.** Frontend prompt done; server-side path flagged "Maven-gated" in git. Implement/finish the KYC endpoint + entity/service wiring in `influora-api`. *(Vikram)*
- [ ] **Clear TODO/FIXME markers** — ~25 frontend, 7 backend, ~1 AI. Resolve each in place. *(Ananya / Vikram)*
- [ ] **Fixes from `mvn verify`.** Once the full backend build runs, patch whatever tests/compile issues it surfaces. *(Vikram / Meera)* — conditional on build output.

## Likely small code work (confirm first)

- [ ] **Reconcile "restore from stash / restore stub'd core files" commits.** Diff-review the recovery commits; re-implement anything that was lost or left as a stub. *(Vikram)*
- [ ] **Placeholder routes.** Verify `deals→chat`, `pipeline→chat`, `inbox→deals` are intentional redirects. If any is an unfinished feature, build it out. *(Ananya)*
- [ ] **AI service live integration.** Mostly keys/config, but expect minor code fixes when Claude/Gemini/Sarvam calls run end-to-end (error handling, response mapping, cost-gate thresholds). *(Ash / AI)*

## NOT code (excluded — for reference)

- Run `mvn verify` · flip `VITE_API_MODE=live` · wire prod secrets · deploy 3 services · pen-test + rotation drill.

---

**Bottom line:** the only *guaranteed* new code is KYC server-side + TODO cleanup + any build fixes. Everything else is confirm-then-maybe-patch. No new features or architecture.
