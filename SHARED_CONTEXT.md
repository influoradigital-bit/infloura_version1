# SHARED CONTEXT — Active Pipeline

**Last update:** 2026-08-29 by Priya (CTO)
**Current task:** FIX-WAVE-0828 — launch-blocker remediation
**Status:** ✅ DONE — all 5 tracks + 4 follow-ups (C5/D4/E3/B4) complete. Consolidation gate:
**270 tests / 0 failures** across all touched backend classes on the composed tree; frontend
build + tsc clean (5,173 modules, 20/20 prerender). Audit score 54.5% → **86.4%**
(26 aligned / 5 partial / 2 missing-by-ruling). All changes UNCOMMITTED on
`fix/f0390-money-flags-build-pipeline` — commit/push is Swapnil's call.
Dashboard updated: see the Influora Launch Audit artifact. Ops checklist + follow-up tickets in
`TASK_INBOX.md`. Ready for Arjun to archive this thread once Swapnil reviews.
**Spec:** `wiki/tech/TASK-FIX-WAVE-0828-PRIYA.md` (assignments, severities, CTO rulings)
**Prior bus contents archived to:** `wiki/reports/archive-shared-context-2026-07-30-brand-audit.md`

---

## ORIGIN

Deep production-readiness audit, 2026-08-28. 33 features traced declaration → wiring → invocation
→ surfaced result, against the working tree of `fix/f0390-money-flags-build-pipeline`.
Baseline **54.5%** — 12 aligned, 12 partial, 6 broken, 3 missing.

Question asked: *"is this production ready if we added the keys?"* Answer: **no.** Five gates stand
between the keys and a working launch, and none of them is fixed by a key. The architecture is
sound; the last mile is unwired.

---

## ACTIVE ASSIGNMENTS

| Owner | Track | Domain | Headline item |
|---|---|---|---|
| Meera | A | `deploy/**`, `generate-env.sh` | A1 `VOICE_AI_BASE_URL` unset → **API does not boot** |
| Ananya | B | `docker/nginx.conf.template`, `public/_headers`, `publish-images.yml`, `creator-profile.tsx` | B1 CSP blocks Razorpay Checkout → **money-in dead** |
| Vikram | C | `SecretsStartupValidator`, `Meta*` (refresh/storage/controller), `MeeraController` | C1 refresh drops `authPath` → IG dies ~55d after connect |
| Vikram | D | `EscrowService`, `CampaignServiceInvoiceService`, `WalletService`, `UploadService` | D1 GST Doc#2 loss is `log.error`-only |
| Kabir | E | new Meta platform-callback controller + SecurityConfig | E1/E2 no deauthorize or data-deletion callback |

**File domains are strictly non-overlapping.** This repo has a documented history of concurrent
sessions clobbering in-progress edits (see memory: concurrent write collisions on
DealService/PortfolioService). Every owner re-reads their region after editing to confirm the
change survived. No owner commits or pushes.

---

## CTO RULINGS IN FORCE

1. **TDS (§194-O) is NOT in this wave.** It is an unbuilt statutory subsystem, not a bug —
   rate selection, PAN-linked rates, FY thresholds, Form 16A, quarterly returns. Autonomous
   implementation would produce plausible tax code that mis-withholds real money against real PANs.
   Spec'd at `wiki/tech/TASK-TDS-194O-SPEC.md`; needs Swapnil sign-off + CA review before an
   engineer starts. **Do not flip `VITE_PAYOUTS_ENABLED=true` until it is DONE.**
2. **Meta media insights** excluded — product call (wire it, or drop `instagram_manage_insights`
   from the review request), not a defect.
3. **`R2StorageService.presignPut`** dead code — leave it. Removing is churn; wiring is a separate
   upload-architecture change.
4. **Interim launch posture:** money-in live, payouts on the manual admin rail only. That is
   already the shipped default, so adopting it requires no change.

---

## STANDING GATE — independent of this wave

🔴 **Rotate the Anthropic, Gemini and Sarvam keys before launch.** `influora-ai/env.example` is
git-tracked and holds real-shaped credentials (`:12`, `:19`); `.gitignore:87` shows
`.env.production` was previously committed. `git rm --cached` any tracked secret file. This is
required regardless of every other fix in this wave.

---

## VERIFICATION STANDARD

A fix is DONE when it builds **and** the specific failure it targets is demonstrably gone. "Build
exits 0" is not evidence — this repo has a documented history of gates passing vacuously and of
producer-written checks greening their own wrong fix. Every owner reports file:line for each edit
so a second party can re-check it.

---

## NEXT

Owners report to Arjun → Priya consolidates → re-audit the changed surface → update the audit
dashboard percentage. Wave closes at DONE_WHEN in the spec (TDS/media-insights/presignPut
explicitly excluded, each carrying its own ticket).

- Kabir / Track E (F-0392): Meta Deauthorize + Data Deletion callbacks DONE — new
  `influora-api/src/main/java/com/influora/integration/meta/webhook/MetaPlatformCallbackController.java`
  (+ test, 18/18 green) and 2 permitAll entries in SecurityConfig. BLOCKER for the Facebook app:
  the FB app-scoped user id is never persisted, so `signed_request.user_id` resolves only for
  INSTAGRAM_LOGIN tokens — needs a `meta_oauth_tokens.meta_user_id` column + a
  `findByIgBusinessAccountIdAndAuthPathAndRevokedFalse` repo method (storage layer, not mine to edit).
- Kabir / Track E follow-up (E3): BLOCKER above RESOLVED by Vikram's C5 — controller migrated onto
  MetaTokenStorage.revokeByMetaUserId (EntityManager seam + raw JPQL deleted). Tests 18/18 green
  (mvn -Dtest=MetaPlatformCallbackControllerTest, BUILD SUCCESS), pre-migration NULL meta_user_id
  no-op path pinned by test.
