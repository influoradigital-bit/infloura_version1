# 🏗️ Docs vs. Code — Exact-Match Comparison

> **Date:** 2026-07-13 | **Method:** Two independent agents run in parallel — **Tara** (docs-only, zero source code read) and **Priya-agent** (code-only, zero `.md` content read) — then arbitrated against a third, deeper code pass (this session's earlier 6-way audit) where the two disagreed.
> **Verdict up front: the .md trackers do NOT reliably match the code.** They disagree with *each other* almost as often as they disagree with the code, and even a shallow code grep can itself overclaim if it doesn't check for inline mock data.

---

## 1. Side-by-side table

| # | Dimension | 📄 Docs claim | 💻 Code (verified) | Match? |
|---|---|---|---|---|
| 1 | Admin | 40–55% (`ADMIN_PENDING_WORK_LOOP.md`, `MASTER-BUSINESS-PLAN.md`) **vs.** "19/20 signed off" (`wiki/INDEX.md`, same-week doc) | **65%** — hooks all live, but 5/6 screens unrouted | ❌ Docs contradict each other; neither matches code exactly. Code is between the two doc claims. |
| 2 | Brand | "Brand Side — COMPLETE" (`docs/PROJECT-STATUS.md`, undated/stale) **vs.** 58% built / 47% live (`MASTER-BUSINESS-PLAN.md`) | **60%** — happy path live, wallet/contracts/campaigns-list confirmed inline-mock | ⚠️ Partial — the 47–58% doc figure is close; the "COMPLETE" doc is flatly wrong |
| 3 | Creator | ~84% blended, most areas "100% SHIPPED/CONDITIONAL" (`MASTER-BUSINESS-PLAN.md`) | **55%** — wallet/profile mock, Meta OAuth callback confirmed **unrouted** (0 references in `App.tsx`) | ❌ Docs overclaim by ~30pts. The doc's own "90% SHIPPED" Meta OAuth claim is contradicted by the route file having zero mention of the callback page |
| 4 | AI | Self-contradictory: "80% built, 0% live" **vs.** "wired live" in a same-week doc | **95%** — Claude/Gemini/Sarvam make real calls, 228/228 tests pass | ❌ Docs disagree with each other by the full range; code is unambiguous and high |
| 5 | API-connected | "~42% end-to-end live" (`MASTER-BUSINESS-PLAN.md:27`) | **95%** — 91–93 real HTTP calls, 99 `isLive()` branches, `VITE_API_MODE=live` | ❌ Large mismatch — doc figure looks stale/pre-wiring |
| 6 | FE↔BE connected | 2 concrete contract-drift bugs named + "fixed 2026-07-13" | **90%** — verified controller-for-endpoint on every major group; those 2 named bugs no longer reproduce | ✅ Close — the doc's bug claims check out and appear resolved in code |
| 7 | Backend error handling | Qualitative only ("leaks no stack traces"); 3 real defects named + "fixed" | **85–88%** — `GlobalExceptionHandler` present, `@Valid` on 27 controllers | ✅ Consistent |
| 8 | Frontend error handling | No dedicated scorecard row in any doc; one masked-fallback bug noted (struck through = resolved) | **55%** — confirmed **zero** React error boundaries anywhere in `src/`, `react-error-boundary` not even a dependency | ❌ Docs are silent on the single biggest frontend gap — not overclaiming, just never audited |
| 9 | Security | 86% (`MASTER-BUSINESS-PLAN.md`, predates 3 hardening items signed off after that doc's date) | **88%** — no criticals; one pre-prod gap (`META_TOKEN_ENCRYPTION_KEY` not in boot validator) not mentioned in any doc | ✅ Close — doc is stale-but-conservative, actual is slightly better |

---

## 2. How often do docs and code *exactly* match?

**1 of 9 dimensions (Security) is within ~2 points. 2 of 9 (FE↔BE, Backend errors) are directionally correct.** The other **6 of 9 are meaningfully wrong** — either because:

- **Multiple docs contradict each other** on the same dimension (Admin, AI) — so there is no single "the docs say X" to even compare against.
- **A doc is stale and never retracted** (`docs/PROJECT-STATUS.md` claiming Brand "COMPLETE" while every current tracker says 47–58%).
- **A doc's figure predates a wave of fixes** and was never re-issued (API-connected 42% vs. current 95%; Security 86% vs. current 88%).
- **A doc makes a specific claim that a direct file check disproves** — Creator docs say Meta OAuth is "90% SHIPPED"; `src/App.tsx` has zero references to `creator-meta-callback.tsx`, meaning the OAuth return flow cannot execute in the deployed router.
- **No doc covers the dimension at all** (Frontend error handling has no scorecard anywhere, despite being the single largest code-verified gap in the whole platform).

## 3. A second, quieter finding: even "code-based" checks disagree with each other

Running two code-only passes back to back (a fast grep-based one vs. this session's earlier deep per-page audit) produced **Brand 88% vs. 60%** and **Creator 80% vs. 55%** — a ~28-point spread from methodology alone. Root cause, arbitrated directly: the fast pass checked only `grep "@/data/mock"` imports and found none, but `brand-wallet.tsx:108` defines `mockWalletData` **inline** — no import statement to catch. The deep pass, which reads each page's actual data source rather than its import list, was correct. **Lesson: "0 mock imports" is not the same as "no mock data," and a code audit is only as trustworthy as how it defines "live."**

---

## 4. Bottom line

- **Docs are not a reliable source of truth here** — not because they're uniformly optimistic (some are conservative/stale-low, like AI's "0% live"), but because the *tracker corpus is internally inconsistent*, with newer sign-off docs never reconciled back into the master planning docs.
- **Code is ground truth, but only when checked at the right granularity** — page-level "does this render mock data" beats file-level "does this import from a mock module."
- **The single fact both sources under-report:** frontend has no error boundary anywhere. No doc flags it; a shallow code grep for "error handling" (which finds `ApiError`/toasts) would also miss it. Only an explicit `ErrorBoundary`/`componentDidCatch` grep surfaces it.

**Recommendation:** treat `PRIYA-CTO-CODEBASE-AUDIT-2026-07-13.md` (this session's deep per-page audit) as the authoritative completion baseline going forward, not any `.md` tracker, and not a quick code grep. Re-issue `MASTER-BUSINESS-PLAN.md`'s completion table against it so the corpus stops contradicting itself.

---

*Produced by Tara (docs-only agent) + Priya-agent (code-only agent) run in parallel, arbitrated by direct file checks where they disagreed. No application code was modified.*
