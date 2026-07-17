# Red-Team Review: Wave D3 Follow-up — Frontend Campaign Gate Activation

**Date:** 2026-07-07
**Reviewer:** Kabir (Red-Team / Offensive Security)
**Status:** ✅ CLEARED — no blocking findings
**Task:** Narrow client-trust review per Kavya's QA APPROVED report (`wiki/errors/wave-d3-followup-frontend-gate-activation-QA.md`, 10/10). Backend gate itself already fully red-teamed and cleared (`wiki/errors/wave-d3-followup-human-path-gate-kabir-redteam.md`). This diff only makes the frontend finally send `campaignType` — confirming the client cannot bypass/spoof the gate in a way that matters.

---

## 1. Can a modified client spoof `campaignType` to bypass the store-integration requirement?

**Verdict: yes, trivially — and it is correctly a UX nicety, not a security boundary. No integrity concern.**

Traced the field end-to-end:

- Client-side, `campaignIntentType` is derived purely from the brand's own objective checkboxes (`campaign-form.tsx:160-162`, `resolveCampaignIntentType`) — it is UI state with no signature, no server-issued token, nothing binding it to anything.
- `campaignToPayload` (`src/lib/api.ts:454-468`) puts it on the wire as a plain JSON field `campaignType`. Any client — browser devtools, a hand-crafted `curl`, a modified build — can set this to `STANDARD` (or omit it) for what is actually a sale-shaped campaign.
- This is a self-service form for the brand's own workspace (`brandContext.requireBrandWorkspace(principal)` scoping, confirmed in the prior backend review). There is no cross-tenant actor here: the only party who could "trick" the gate is the brand tricking its own campaign-creation flow.
- Consequence of a successful bypass, re-confirmed against the current codebase: `CampaignService.create` writes exactly one `Campaign` row with `status=DRAFT`. Per the already-cleared backend review, nothing downstream — coupon/redemption (`RedemptionService`), affiliate earnings (`AffiliateEarningsService`), escrow unlock (`ConfirmLaunchExecutor`, re-verifies `EscrowStatus.FUNDED` fresh from DB) — reads `campaignType`/`campaignIntentType` at all. I independently re-grepped `campaignType`/`getCampaignType()` across `influora-api/src/main/java` for this review and confirm the reference set is unchanged from the prior review: gate itself, its two call sites, entity accessors, tests. Zero hits in money-bearing or audit/compliance code paths.
- So a spoofed `STANDARD` on an actually-sale-shaped campaign produces: a campaign that can never get real order-attribution (no store connected) and just won't show meaningful conversion data. That's the brand shooting itself in the foot, not a platform-integrity or cross-tenant issue. Nothing treats `campaignType` as an audit/compliance signal — it's a feature gate on the brand's own drafting UX, full stop.

**Conclusion: (a) applies.** This is a UX/business-logic gate that happens to live on a field the client supplies. The backend enforces its actual invariants (workspace scoping, escrow state, redemption correctness) independently of what `campaignType` claims. Recommend no change — building server-side inference of "sale-shaped-ness" from `product_url`/`product_price` presence to make the gate tamper-proof would be over-engineering for a self-inflicted-only failure mode. Consistent with the backend review's judgment call 1 (draft-creation-only is genuinely lower risk).

---

## 2. Error banner leak check (spot-confirming Kavya's QA)

Read the banner code directly (`campaign-form.tsx:1071-1099`):

- The `NO_STORE_INTEGRATION` branch (lines 1074-1091) is fully static, hand-written copy — "Connect Shopify or WooCommerce, then try again" + a link to `/brand/settings`. No server-supplied string is interpolated into this branch at all. No error code, service name, or internal identifier is echoed.
- The fallback branch (lines 1092-1096) renders `errors.submit` directly, which for any other `ApiError` is `err.message` (`campaign-form.tsx:347-349`) — a **server-supplied message string, verbatim, in the UI**. Checked where these messages originate: `ApiError` is thrown by the shared HTTP client (`src/lib/api.ts:95-104`, plus throw sites at lines 169-195, 1134, 1344) using `envelope.error?.message` from the backend's error envelope. This is a **pre-existing, app-wide pattern** — every `ApiError` consumer that renders `err.message` has the same shape, not something introduced by this diff. Backend `ApiException` messages (`influora-api/.../common/ApiException.java`) are deliberately human-authored client-facing strings (e.g., the `NO_STORE_INTEGRATION` message itself), not stack traces or raw exception text — consistent with the rest of the app's error-handling convention. Out of scope to re-litigate the whole app's generic-error-message convention here; this diff doesn't change or worsen it.
- Confirmed: no `campaignType`/`CampaignIntentType`/`IntegrationHealthService` internal name leaks anywhere in the banner. Matches Kavya's finding.

**Verdict: clean, no new leak surface.** Spot-check confirms Kavya's report.

---

## 3. Alert contrast fix — no security-adjacent regression

Read `src/components/ui/alert.tsx` directly (current state, not diff):

- `destructive` variant now: `text-destructive-foreground bg-destructive border-destructive/30` — matches `toast.tsx`'s existing correct pairing. This raises contrast (pale-pink-on-white → strong-red-on-pink-tint), it does not soften or mute a previously-alarming look. There is no direction-reversal risk (i.e., no risk that a genuinely dangerous action now reads as calm/safe) — the change strictly *increases* visual alarm/legibility.
- Consumer audit, done independently rather than trusting Kavya's "exactly 1 consumer" count: grepped all `variant="destructive"` usage codebase-wide. **Kavya's QA report undercounted** — there are 3 `<Alert variant="destructive">` consumers, not 1: `campaign-form.tsx:1072` (this diff's subject), plus two pre-existing ones Kavya's report didn't list: `src/components/analytics/ContentPerformancePanel.tsx:82` ("Couldn't load content performance") and `src/pages/creator-coupons.tsx:50` ("Couldn't load your coupons"). Read both directly — both are plain fetch-error banners with a message + retry button, no manual color overrides, no `className` fighting the variant's colors. The fix applies uniformly and safely to both; they benefit from the same contrast improvement. This is a factual correction to Kavya's count, not a safety concern — the conclusion ("fix is safe, no regressions") still holds, just on the correct consumer count.
- Note: `variant="destructive"` `Badge`/`Button` usages (proposal-card.tsx, brand-pipeline.tsx, brand-settings.tsx, brand-campaign-detail.tsx, creator-inbox.tsx) are a separate component (`Badge`/`Button`, not `Alert`) with their own `cva` definition — untouched by this diff, not affected, not in scope.
- No destructive **confirmation dialog** (e.g., "Delete campaign?", "Decline Bid") uses the `Alert` component at all in this codebase — those are `Button variant="destructive"` inside dialogs/pages, a different component untouched by this change. So there is no risk of a delete/decline confirmation now reading as "OK" when it should read as alarming, or vice versa.

**Verdict: clean.** No security-adjacent implication; strictly improves legibility of already-alarming UI, and the (minor, non-blocking) consumer-count discrepancy in Kavya's report doesn't change the safety conclusion.

---

## Summary

1. **Client-spoofable `campaignType` — not a security boundary, confirmed by design.** No cross-tenant impact, no money movement, no audit/compliance signal corruption. A brand can only mislead its own draft-creation UX. Consistent with and reconfirms the backend review's existing risk classification.
2. **Error banner — clean**, spot-check confirms Kavya. Static copy on the gate path; generic `err.message` fallback is a pre-existing app-wide pattern, not new leak surface from this diff.
3. **Alert contrast fix — safe**, strictly increases alarm/legibility, no confirmation-dialog impact (dialogs use `Button`, not `Alert`). Minor correction: 3 consumers exist, not 1 as QA reported — verified both additional consumers are unaffected/benefit equally.

**Zero blocking findings.**

**Files reviewed (direct read of current state):**
- `src/lib/types.ts`
- `src/lib/api.ts` (campaignToPayload/mapCampaignFromApi, ApiError class, throw sites)
- `src/components/brand/campaigns/campaign-form.tsx` (resolveCampaignIntentType, submit handler, error banner JSX)
- `src/components/ui/alert.tsx`
- `src/components/analytics/ContentPerformancePanel.tsx` (consumer-count spot-check)
- `src/pages/creator-coupons.tsx` (consumer-count spot-check)
- `wiki/errors/wave-d3-followup-frontend-gate-activation-QA.md` (Kavya's QA, full read)
- `wiki/errors/wave-d3-followup-human-path-gate-kabir-redteam.md` (own prior backend verdict, full read)

---

## Verdict

✅ **CLEARED.** Frontend gate activation is signed off from the security side. Routes to Meera for local build/dev verification (`npm run build`, `npm run dev`, confirm gate fires correctly against a real/mocked 409 in the running dev app).

**Next:** Meera — local verification. Arjun — mark Wave D3 (backend + frontend) fully cleared through the security gate once Meera passes.
