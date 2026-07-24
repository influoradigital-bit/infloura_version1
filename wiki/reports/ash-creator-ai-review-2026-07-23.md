# Ash — Creator-Side AI Code Review (Influora)

**Reviewer:** Ash (AI/ML expert & AI code reviewer)
**Date:** 2026-07-23
**Method:** Code-level review only. Reference behaviour from the live app (`http://200.141.1.6`, `demo.creator@influora.com`); **zero AI credits spent**. Every answer is backed by an exact file + line/section.
**Scope:** the two creator AI surfaces from today's walkthrough — (1) Creator Co-pilot daily idea, (2) Deal Room bid/accept/sign/escrow flow. Plus the traced finding **CP-1** (Co-pilot hard-gated behind Instagram, no pre-connect preview) and the cross-cutting question of whether the brand-side **M-1 on-behalf scope path** touches the creator side.

---

## Headline answer to the coordinator's cross-cutting question

**The M-1 fix does not affect the creator side, and creator flows do not share the on-behalf scope path at all.** Three independent reasons, each cited below:
1. The Creator Co-pilot authenticates with a **creator service token** (`verify_creator_token`, keyed on `creator_profile_id`), a sibling of the trendspark/brand-safety service token — **not** an `OnBehalfTokenService` JWT and **not** a Meera tool call (`influora-ai/app/routes/creator_suggestion.py:80,212-217`; docstring `:6-11`).
2. There is **no creator-facing Meera tool loop**. `MeeraController` is brand-only — every route calls `requireBrandWorkspace(principal)` (`MeeraController.java:98,123,171,183,201,240,283`), and the prompt assembler is hard-scoped `audience="BRAND"` for Phase 1 (`influora-ai/app/prompt/assembler.py:421-425`). A creator never reaches the on-behalf mint/scope machinery.
3. The Deal Room accept/contract/sign/escrow path is **plain authenticated REST with zero AI** (no controller in that flow references any AI client — see Q10-Q13). Nothing there consumes an on-behalf scope.

So widening `SCOPE_DEFAULT` (create_campaign + get_campaign_performance) is correctly a **brand-only** change; it neither helps nor harms the creator surface.

---

## Part A — 15 questions, answered with citations

### Surface 1 — Creator Co-pilot (daily idea)

**Q1. What model/provider generates the "daily idea"?**
Anthropic Claude **Haiku** — `CREATOR_COPILOT_MODEL`, which defaults to the exact `TRENDSPARK_MODEL` string `claude-haiku-4-5-20251001` (`influora-ai/app/config.py:96-103`, `:86`). Invoked as a single non-streaming `complete_text` call (`creator_suggestion.py:279-284`, provider `influora-ai/app/providers/claude.py:205-262`). Deliberately cheap: this is one bounded phrasing call, never Opus/Sonnet (`creator_suggestion.py:30-31`).

**Q2. How is the prompt constructed and what does the model actually see?**
System prompt is a fixed peer-to-peer creator voice, no brand/marketplace concept (`influora-ai/app/prompt/creator_suggestion.py:69-102`). User message carries exactly two fields: `theme` (closed-vocab, rendered plain) and `trend_text` (wrapped as `<untrusted_trend_text>`) — `creator_suggestion.py:105-119`. **No creator caption or personal text ever reaches the model** (route docstring `creator_suggestion.py:23-28`; prompt docstring `:17-31`).

**Q3. What creator data feeds the suggestion?**
Server-derived only: `theme_matched` comes from deterministic Java theme-tagging (`ThemeMatchService`), and `trend_text` from the best-scoring active `Trend` (`CreatorNudgeService.java:112-132`). The creator's own theme tags (`profile.getThemeTagsJson()`, `CreatorNudgeService.java:105`) are produced by the nightly `CreatorThemeTaggingJob` off IG captions synced by `CreatorCaptionSyncJob`. The AI call receives none of this raw data — only the matched closed-vocab theme string.

**Q4. How does the Instagram connection gate the Co-pilot (frontend)?**
Hard gate in `src/hooks/useDailySuggestion.ts`: the React-Query fetch is `enabled: isConnected` (`useDailySuggestion.ts:126`) so **nothing is requested until IG is linked**, and `status` resolves to `'idle'` whenever `!isConnected` (`:148-156`). The `idle` state renders `IGConnectPrompt` (hook docstring `:18-24`). `isConnected` is read fresh each render from `api.metaOAuth.getLocalConnectionState()` (`:115-119`).

**Q5. How does the connection gate work on the backend?**
Even past the frontend gate, the suggestion depends on `profile.getThemeTagsJson()`; if it's blank (no IG captions tagged yet) the service returns `pending_tagging` and makes no AI call (`CreatorNudgeService.java:105-110`). IG connection is a Meta OAuth flow (`CreatorMetaOAuthService.java`), and a **personal** (non-business) IG account resolves to `NO_BUSINESS_ACCOUNT` — a 200 with `accountType:"personal"`, which the UI turns into the `BusinessAccountRequired` sub-state (`CreatorMetaOAuthService.java:80-84`, `useDailySuggestion.ts:118-119`). So the gate is really two coupled dependencies: an OAuth **business** token, then a nightly theme-tag rollup.

**Q6. [CP-1] Can a demo/preview idea render before IG connect — and why doesn't it today?**
**Today: no.** The frontend never fetches pre-connect (`useDailySuggestion.ts:126`), and the backend has no unauthenticated/pre-connect suggestion path — `CreatorCopilotController.getToday` requires a resolved creator profile (`CreatorCopilotController.java:42-49`). So a first-time creator sees only the locked "Link Instagram to unlock Co-pilot" prompt with **zero sample of the value**.
**But a preview is trivially feasible in code.** The deterministic fallback (`fallback_message`, `creator_suggestion.py:122-138`; Java mirror `CreatorNudgeService.templatedFallback:220-229`) needs only a `theme` + a `trend_text` — **neither requires IG**. And the AI phrasing call itself already takes only those two fields (Q2), so a preview generated from a creator-**selected** niche + a current active trend would be near-identical in quality to the post-connect idea. The IG gate exists to source the *theme automatically*, not because the AI needs private data. Fix spec in Part B.

**Q7. What does the Co-pilot cost, and how is spend controlled?**
Haiku rates $1/$5 per MTok (`influora-ai/app/costs/pricing.py:52-53`), one ~300-output-token call (`config.py:284-286`). Three independent cost guards: (a) per-creator/day cap via idempotent-read-first (`CreatorNudgeService.java:95-103`) so a same-day repeat spends nothing; (b) the shared spend gate keyed on `creator_profile_id` (`creator_suggestion.py:257-264`); (c) DB unique key `uq_creator_nudge_day` as the race backstop (`CreatorNudgeService.java:160-170`).

**Q8. What are the Co-pilot's failure modes?**
Every failure degrades to a deterministic templated suggestion, **always HTTP 200** — provider error, malformed JSON, failed validation, or spend gate all route to `_fallback_response` (`creator_suggestion.py:231-250,307-321`). Output is defensively parsed and validated: JSON-only, length caps, ≤2 sentences, no pet-names, no echoed price, and no marketplace brand name (`parse_and_validate`, `creator_suggestion.py:151-197`). Off-vocab `theme_matched` fails closed to empty (`_normalize_theme:138-148`). Java wraps the AI client in a belt-and-braces try/catch too (`CreatorNudgeService.java:201-213`).

**Q9. Does the same on-behalf scope bug affect the Co-pilot?**
**No.** The Co-pilot has no `OnBehalfTokenService` token, no `scope` claim, and no Meera tool routing — it is a single internal phrasing call authenticated by `verify_creator_token` (`creator_suggestion.py:212-217`). The M-1 scope gate lives entirely in `MeeraInternalController` + `OnBehalfAuthResolver`, which the Co-pilot never touches. The Co-pilot's *only* gate is IG-connect + theme-tags (Q4/Q5) — a product gate, not a permission/scope bug.

### Surface 2 — Deal Room (bid / accept / sign / escrow)

**Q10. Does any AI assist proposal negotiation?**
**No.** The proposal/deal flow is `DealController` — plain authenticated REST; it does not appear in any AI-client reference grep across `service/**`. No model proposes, counters, or prices a bid.

**Q11. Is contract generation AI-driven?**
**No.** `ContractController.sign` is a human self-attestation — the FE sends `{name, agreedAt}` and the signer role is **server-derived from the authenticated principal**, not AI-produced (`ContractController.java:78-99`). The contract PDF is generated/served via a presigned link once both parties sign (`ContractController.java:110-111`) — deterministic document generation, no LLM.

**Q12. Does escrow funding / payout involve AI?**
**No.** `EscrowController` / `EscrowService` and the deliverable controllers carry no AI client. Amounts and state transitions are deterministic money-core logic. (This mirrors the brand side's hard rule: money is never AI-authoritative.)

**Q13. Does Meera participate anywhere in the Deal Room?**
**No.** Meera is brand-only (`MeeraController` → `requireBrandWorkspace`, Q-headline reason 2) and its tools are campaign-planning, not deal execution. No creator-side surface invokes Meera or an on-behalf tool call.

### Cross-cutting

**Q14. Is there any creator-content AI besides the Co-pilot?**
Yes, one — `DeliverableSafetyReviewService` (`GET /deliverables/{id}/safety-review`). It reuses the hardened GARM classifier (`BrandSafetyAiClient#classify` → influora-ai `POST /internal/brand-safety`, forced-tool Claude **Sonnet** `BRAND_SAFETY_MODEL`) to score a submitted deliverable's caption (`DeliverableSafetyReviewService.java:31-60`). It is **brand-facing, advisory-only, and never blocks submit/approve** (`:47-51`); the verdict is server-computed from enum-validated categories, so a hostile caption can't flip it (`:52-60`). Not negotiation, not creator-facing — but it *is* a creator-content AI touchpoint worth noting for cost (Sonnet; see brand review P1 recommendation to move GARM to Haiku after the golden-set A/B).

**Q15. Net: does the creator side share the on-behalf scope path, and does the M-1 fix change anything for creators?**
**No and no.** Creator auth is a distinct service-token family (`verify_creator_token`), Meera/on-behalf is structurally brand-only, and the Deal Room is AI-free. The `SCOPE_DEFAULT` widening (`OnBehalfTokenService.java`) is inert on the creator surface. If a **CREATOR-audience Meera** ships later (assembler notes it as Phase 3, `assembler.py:421-425`), *that* is when the mint's hardcoded scope and the `audience` cache-key partition (`assembler.py:400-412`) will need a creator-scoped review — but it does not exist today.

---

## Part B — Fix specs

### CP-1 — add a pre-connect Co-pilot preview (no IG required)

**Problem:** A first-time creator sees only "Link Instagram to unlock Co-pilot" with no sample of the value. The gate is a data-sourcing convenience, not an AI requirement — the phrasing call needs only a theme + a trend (Q2/Q6).

**Fix (frontend-light, backend-additive):**
1. **Backend — add a preview endpoint** `GET /creator/copilot/suggestion/preview?niche={theme}` on `CreatorCopilotController.java` (alongside `getToday`, `:42-49`). It:
   - accepts a creator-**selected** niche from the closed vocab (`app.prompt.trend_tag.THEME_SET` / `ThemeMatchService` vocabulary) — reject off-vocab, same discipline as `CreatorNudgeService.bestMatchedTheme`;
   - picks the best active `Trend` for that theme (reuse `trendRepository.findActive` + `themeMatchService.score`, `CreatorNudgeService.java:112-121`);
   - returns a suggestion **without writing `creator_nudge_log`** and **without the per-day cap** (it's a sample, not the daily entitlement), clearly flagged `preview: true` and `message_source`.
   - To keep it truly free/abuse-proof, default it to the **deterministic `templatedFallback`** (`CreatorNudgeService.java:220-229`) — zero AI spend — and only optionally call the AI path behind the existing spend gate if you want a live sample.
2. **Frontend — render the preview in the `idle` state.** In `useDailySuggestion.ts`, when `!isConnected`, fetch the preview instead of collapsing straight to `IGConnectPrompt` (`:126,148-156`), and have the card show the sample idea with the connect CTA layered on ("This is a sample — link Instagram to get one tailored to your real content every day").

**Why safe:** no private data, no per-day entitlement consumed, no `creator_nudge_log` mutation, and (if templated) no AI cost. It converts a dead lock screen into a value demonstration — the single highest-leverage creator-activation change in this surface.

### Secondary — surface *why* IG is required, and support personal→business upgrade

`BusinessAccountRequired` is reachable (`useDailySuggestion.ts:118-119`, `CreatorMetaOAuthService.java:80-84`) but a creator who connected a personal account hits a wall. Pair the CP-1 preview with explicit copy on the `requiresBusinessAccount` branch explaining the business-account requirement and linking Meta's switch flow — otherwise the preview raises intent that the account-type gate then frustrates.

### Note — no creator fix needed for the on-behalf scope path

Confirmed inert (Q9/Q15). Do **not** add creator tool names to `OnBehalfTokenService` scope — there is no creator on-behalf consumer to authorize, and doing so would mint capability with no gate behind it. Revisit only if/when a CREATOR-audience Meera ships.

---

## Top creator AI findings (summary)

1. **CP-1 confirmed — the Co-pilot is a lock screen with no preview.** Hard-gated at `useDailySuggestion.ts:126` (`enabled: isConnected`) and by the theme-tags dependency (`CreatorNudgeService.java:105-110`). Yet the AI needs no private data — a templated or live preview from a self-selected niche is trivially buildable (Part B).
2. **The Co-pilot AI barely uses IG.** The model sees only a closed-vocab theme + a trend string (Q2); IG's role is to auto-derive the theme, not to enrich the prompt — which is exactly why a pre-connect preview would be near-identical in quality.
3. **Creator flows do NOT share the on-behalf scope path.** Separate `verify_creator_token` auth, no Meera tool loop, brand-only `MeeraController`. The M-1 `SCOPE_DEFAULT` widening is inert for creators (Q9/Q15).
4. **The Deal Room is entirely AI-free.** Proposal accept (`DealController`), contract sign (`ContractController.java:78-99`, human self-attestation), and escrow (`EscrowService`) involve no model — no AI negotiation or contract generation exists to review.
5. **One creator-content AI touchpoint on Sonnet:** `DeliverableSafetyReviewService` (GARM safety review) reuses the Sonnet brand-safety classifier — same Haiku-migration cost opportunity flagged in the brand review, and the only place a creator's authored caption reaches an LLM.
