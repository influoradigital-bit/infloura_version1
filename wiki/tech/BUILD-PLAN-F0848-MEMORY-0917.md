# Build Plan — F-0848 then T-MEERA-MEMORY-0917

**Owner:** Priya (CTO) | **Date:** 2026-09-17 | **Branch:** `feat/meera-creator-phase-e` | **Status:** PLAN ONLY, nothing edited
**Approved by:** Swapnil. **Order:** F-0848 first; T-MEERA-MEMORY-0917 starts only after F-0848 is tester-passed.
**done_when (both, Swapnil):** "check my testing team code". A build is done only when the testing team below has run it and the named falsifications went red. A review nobody could falsify does not count.

Every `file:line` here was opened for this plan. Anything not opened is marked **[unverified]**.

---

## PART 1 — F-0848: a campaign can go ACTIVE without paying the fee or securing funds

### 1.1 Verified state

| # | Fact | Evidence |
|---|---|---|
| 1 | `create()` takes `req.status()` directly and defaults to DRAFT. Its only status check is workspace verification. There is no funds check and no fee. | `CampaignService.java:189-190`, validator `CampaignValidator.java:62-70`, save `:224` |
| 2 | `update()` detects a real move to ACTIVE, then checks funds and charges the fee inside one transaction on a locked row | `CampaignService.java:254` (lock), `:273-274`, `:368-370`, `:375-377` |
| 3 | The funds check is "at least one hold for this campaign id is FUNDED" | `CampaignService.java:704-714` |
| 4 | `ConfirmLaunchExecutor` DOES charge the fee, once per real transition. It checks FUNDED holds (`:261-277`), refuses a campaign that went ACTIVE by another path (`:285-307`), treats a real replay as a no-op without charging (`:309-329`), then flips status and charges (`:338-339`). Its own check is a duplicate of `requireFundedEscrow`, not a shared call. | `ConfirmLaunchExecutor.java:256-339` |
| 5 | Double-charge protection is the ledger key `brand-fee-publish:<campaignId>`, not the callers. So PAUSED→ACTIVE resume calls `chargeOnPublish` again and relies on that key to dedupe. | `BrandCampaignFeeService.java:40-50`, `:201`; resume path `CampaignService.java:264-265` + `:273-274` |
| 6 | `chargeOnPublish` has exactly two production callers | `CampaignService.java:376`, `ConfirmLaunchExecutor.java:339` |
| 7 | Meera's `create_campaign` only ever creates DRAFT | `CreateCampaignExecutor.java:309` |

### 1.2 Frontend callers that create as ACTIVE (exact)

Arjun's grep missed these because status is passed in as a function argument.

| Flow | Trigger | Payload status | Create call |
|---|---|---|---|
| **Standard form "Publish Campaign"** | `campaign-form.tsx:1695` `onClick={() => handleSubmit('ACTIVE')}` | `handleSubmit(status)` `:622`, payload `status` `:636` | `campaign-form.tsx:663` `api.campaigns.create(payload)` when not editing |
| **Hype form submit (Launch)** | `brand-new-hype-campaign.tsx:221-223` `handleFormSubmit` → `submit('ACTIVE')` (form `onSubmit` `:391`) | `submit(status)` `:226`, payload `status` `:241` | `brand-new-hype-campaign.tsx:273` `api.campaigns.create(payload)` when not editing |

"Save Draft" (`campaign-form.tsx:1687`, `brand-new-hype-campaign.tsx:635`) sends DRAFT and is not affected.

### 1.3 The structural catch is worse than a missing line: human publish is a deadlock

- `update()` refuses ACTIVE without a FUNDED hold (fact 2).
- The only campaign-level "secure the funds" control a brand can reach lists **ACTIVE campaigns only** (`brand-wallet.tsx:483-488`, `api.campaigns.list({ status: 'ACTIVE' })`, rendered at `:1511`). The other `FundEscrowButton` mount is milestone-scoped in the deal room (`deal-payments-tab.tsx:205`).
- So today a brand **cannot** fund a DRAFT, and so cannot publish a DRAFT through `update()`. **Creating as ACTIVE is the only human publish route that works, and it is the free one.** If create simply rejects ACTIVE, human publishing stops working entirely. The fix has to add a way to secure funds for a draft.
- The server already supports that. `EscrowService.initiateFund` accepts campaign-level funding with no milestone and no status check (`EscrowService.java:213-262`), and derives the amount from `budgetMax` (`:344-373`). `FundEscrowButton` takes a `campaignId` (`FundEscrowButton.tsx:35-43`). **No new endpoint is needed.**

### 1.4 Rulings

**(a) Approach: REJECT `status=ACTIVE` on create.** Create accepts `DRAFT` only (or null, which becomes DRAFT). Anything else returns `400 CAMPAIGN_CREATE_STATUS_NOT_ALLOWED`. Why reject rather than silently downgrading: funds are bound to a campaign id, so ACTIVE can never legitimately pass at create time. A silent downgrade would also send an old client to "published" navigation for a campaign that is really an unfunded draft.
- PAUSED, COMPLETED and CANCELLED are refused on create for the same reason. `PENDING_APPROVAL` is refused too: no create caller sends it (`campaign-form.tsx:1687/1695`, `brand-new-hype-campaign.tsx:223/635`).

**(b) New brand flow: Save as draft, then secure the funds, then publish. It is one screen with one extra visible step, not a new page.**
- "Publish Campaign" / "Launch" now: `create` with DRAFT → an inline **"Secure the funds"** step on the same review screen, which mounts the existing `FundEscrowButton campaignId={saved.id}` (it already shows the server-derived amount and handles top-up) → when `onFunded` fires, `update(saved.id, { status: 'ACTIVE' })`, which checks funds and charges the fee.
- Copy on that step, fixed wording: title **"Secure the funds to publish"**. Body: **"We hold ₹{budget} securely until creators deliver. Your platform fee is charged from your wallet when the campaign goes live."** If the fee step then fails for low balance, the existing `BrandCampaignFeeService` message is shown. The campaign stays a draft with its funds secured, and the button reads **"Publish campaign"** (retry = `update` only, never a second fund).
- **Deploy order:** the frontend ships in the same deploy as the backend, or before it. The new flow (create DRAFT → fund → update) also works on the current backend. The backend rejection must never be live while the old frontend is.
- If the brand leaves mid-flow, the campaign stays a DRAFT in the list. Reopening it and publishing lands on the same step, because the edit path runs create→update the same way.
- Add DRAFT campaigns to the wallet page's fundable list? **No, not in this build.** `brand-wallet.tsx` sits in the billing area the other session is editing (see 1.6). The inline step makes it unnecessary.
- No brand-facing string in this build uses "escrow". Existing server messages already say "secured payment" (`CampaignService.java:711`, `ConfirmLaunchExecutor.java:275`).

**(c) One guard: new class `influora-api/src/main/java/com/influora/service/CampaignActivationGuard.java`.** It has a single method, `activate(Campaign campaign, String workspaceId)`, that (1) refuses unless the campaign's current status is not ACTIVE, (2) runs the FUNDED-hold check, currently `requireFundedEscrow` (moved here and deleted from `CampaignService`), (3) calls `chargeOnPublish`, and (4) sets status ACTIVE. It runs with `@Transactional(propagation = MANDATORY)`, so it throws if a caller has no transaction and can never commit a half-activation.
- Callers: `CampaignService.update` at the `transitioningToActive` edge, and `ConfirmLaunchExecutor.doExecute` in place of `:338-339`. That executor keeps its audit-logged FUNDED pre-check at `:261-277`, because its audit contract depends on it. The guard re-checks, which costs nothing.
- `create()` never calls it, because create cannot produce ACTIVE (ruling a).
- **Stopping a fourth path:** an ArchUnit rule in `influora-api/src/test/java/com/influora/architecture/` says only `CampaignActivationGuard` may call `BrandCampaignFeeService.chargeOnPublish`. A second test reads the source of `src/main` and fails on any `setStatus(CampaignStatus.ACTIVE)` or `.status(CampaignStatus.ACTIVE)` outside the guard. `Campaign.applyPatch` with an ACTIVE status stays in `update`, which calls the guard first. The source test allow-lists that one call site by class+method, never by comment text (see memory: a grep gate matched its own comment).

**(d) Campaigns already ACTIVE that never paid: DETECT now, do NOT backfill, and FLAG for Swapnil.**
- Detection (read-only, in scope): a campaign whose status is or was ACTIVE and that has no PLATFORM_FEE ledger row with key `brand-fee-publish:<id>` and/or no FUNDED/RELEASED hold. Meera runs it as a read-only SELECT and hands Swapnil the list with count, total budget and missed fee. Running it against the **production** DB needs Swapnil's go-ahead.
- Recommendation to Swapnil: **no automatic charge.** Debiting a brand's wallet after the fact for a fee they were never shown is a trust and consent problem. Suggested handling: leave live campaigns alone, show nothing in the product, and decide case by case (waive, or ask the brand). Any backfill is a separate money ticket with its own approval. **Out of scope for F-0848.**

### 1.5 Lanes (file-disjoint)

| Lane | Owner | Files (exclusive) | Tests that must go RED on current code |
|---|---|---|---|
| **F1 — backend guard + create rejection** | **Vikram** | `service/CampaignService.java`; NEW `service/CampaignActivationGuard.java`; `service/meera/tool/ConfirmLaunchExecutor.java`; NEW `src/test/.../service/CampaignCreateStatusGateTest.java`; NEW `src/test/.../service/CampaignActivationGuardTest.java`; NEW `src/test/.../architecture/CampaignActivationPathTest.java`; edits to existing `CampaignActivationGatesTest.java`, `CampaignServiceTest.java`, `meera/tool/ConfirmLaunchExecutorTest.java` (constructor/mocks only) | **T1** create with `status=ACTIVE` → 400 `CAMPAIGN_CREATE_STATUS_NOT_ALLOWED`, `campaignRepository.save` never called, `chargeOnPublish` never called (RED today: create saves ACTIVE). **T2** create with no status → saved DRAFT, fee never charged. **T3 no double charge:** (i) `update` DRAFT→ACTIVE then a no-op PATCH ACTIVE→ACTIVE → guard called once; (ii) `update`-activated campaign then `confirm_launch` → `CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH`, no second charge; (iii) `confirm_launch` replay → no charge; (iv) PAUSED→ACTIVE resume → exactly one PLATFORM_FEE posting in total for the campaign (proven against the ledger key; see T3-DB). **T4** ArchUnit: only `CampaignActivationGuard` calls `chargeOnPublish` (RED today: two callers). **T5** source scan: no `setStatus/.status(CampaignStatus.ACTIVE)` outside the guard (RED today: `ConfirmLaunchExecutor.java:338`). |
| **F2 — frontend secure-then-publish** | **Ananya** | `src/components/brand/campaigns/campaign-form.tsx`; `src/pages/brand-new-hype-campaign.tsx`; NEW `src/components/brand/campaigns/secure-and-publish-step.tsx` (wraps `FundEscrowButton`, no edits to it); NEW `src/components/brand/campaigns/__tests__/campaign-form-f0848-publish-route.test.tsx`; NEW `src/pages/brand-new-hype-campaign.f0848-publish-route.test.tsx`; update of existing `campaign-form-f0290-draft-invite-guard.test.tsx` (creator invite must now fire after the ACTIVE `update`, not after create) | **T6** clicking "Publish Campaign" on a NEW campaign → `api.campaigns.create` called with `status: 'DRAFT'` and **never** `'ACTIVE'` (RED today: `:1695`→`:663` sends ACTIVE). **T7** same for Hype submit (RED today: `:223`→`:273`). **T8 route still reaches published:** create resolves → secure step renders with `campaignId=saved.id` → firing `onFunded` → `api.campaigns.update(id, {status:'ACTIVE'})` → navigates to `/brand/campaigns`. **T9** fee failure after funding leaves the brand on the step with "Publish campaign" retry, and `fundEscrow` is not called a second time. **T10** no rendered text on the step matches `/escrow/i`. |

- **Not touched by any lane:** `src/lib/api.ts` (being modified in the working tree by another session), `brand-wallet.tsx`, `BrandCampaignFeeService.java`, `EscrowService.java`, `FundEscrowButton.tsx`, and everything under `service/billing/`.
- **T3-DB (real ledger):** the "exactly one PLATFORM_FEE posting" claim ultimately rests on the ledger's unique idempotency key (`BrandCampaignFeeService.java:44-50`). Mock tests prove call counts, not the key. Vikram adds a Testcontainers case to F1's `CampaignActivationGuardTest` (or a sibling `*IT`). Docker is absent locally, where these tests show as "Skipped". Unless it runs on CI or on a machine with Docker, T3-DB is reported **NOT PROVEN**, not green.
- **Order:** F1 and F2 run in parallel (disjoint files, and the frontend flow works on the old backend too). Then the testing team (1.7).

### 1.6 Conflicts with the other session

- `src/lib/api.ts` is modified in the working tree (git status). Neither lane edits it. F2 uses existing `api.campaigns.create/update` and `FundEscrowButton` as they are.
- Billing / analytics / metrics: F1 only *calls* `BrandCampaignFeeService` and does not edit it. `brand-wallet.tsx` is deliberately left out of scope (1.4b).
- Commit discipline (memory: shared-tree lane attribution): stage by explicit path only, and compile a `git archive` of the index before committing.

### 1.7 Testing team — F-0848

| Checker | Ceiling | What they prove | Counts toward done_when? |
|---|---|---|---|
| **Meera** (build + falsify) | proved | (1) `mvn clean test` from `influora-api/` with a visible "Compiling N source files" line (never trust "Nothing to compile"), not piped through grep (memory: piped mvn hides failure). Report run/fail/**skipped** counts and name which Docker tests were skipped. (2) `npx tsc --noEmit`, `npx vitest run` for F2 files, `npm run build`. (3) **Falsify:** reverse each fix separately and show the named test goes red: restore `create()` accepting ACTIVE → T1 red; re-add a direct `chargeOnPublish` in `ConfirmLaunchExecutor` → T4 red; restore `setStatus(ACTIVE)` at `:338` → T5 red; delete the `transitioningToActive` guard call in `update` → a T3/`CampaignActivationGatesTest` case red; revert F2 to `handleSubmit('ACTIVE')`→create → T6 red; revert Hype → T7 red. Each reversion is restored afterwards and followed by a fresh `clean` run. | **YES, primary** |
| **Neha** (live E2E) | proved | On a staging/live deploy with both lanes: a verified brand creates a new Standard campaign and a Hype campaign through the UI → secures the funds → the campaign shows ACTIVE → wallet shows exactly one platform fee line. Also: a direct `POST /campaigns` with `status:"ACTIVE"` returns 400. Needs a real small-amount funding. Swapnil approves the spend first, per the money rules. | **YES**, for the live claim |
| **Kavya** (QA) | echo | Reads the diff for standards and copy ("escrow"), and tries her own bypasses (e.g. `status:"active"` lowercase, an unknown enum value, a PATCH to PAUSED then ACTIVE). Every bypass she finds becomes a test that Meera then runs. | No on its own; only the tests Meera runs from her findings count |
| **Kabir** (red-team) | echo | Money-path attack list: concurrent `update`+`confirm_launch` on the same draft, replaying an idempotency key across workspaces, funding workspace A's draft from workspace B. Same rule: findings count only once Meera has run them as tests. | No on its own |

**F-0848 is DONE when:** Meera's clean run is green with all six falsifications shown red, AND Neha's live pass is recorded (or explicitly deferred by Swapnil), AND (d)'s detection list is in Swapnil's hands.

---

## PART 2 — T-MEERA-MEMORY-0917 (starts after F-0848 is DONE)

Design: `wiki/tech/MEERA-ASK-AND-REMEMBER-DESIGN-0917.md`. Test numbers below are that document's §10 tests 1-16.

### 2.0 Contract freeze (Priya, before any lane starts, ~0.25 day)

Appended to the design doc as §11: the exact JSON for `POST/GET/PATCH/DELETE /api/brand/meera-facts`, the `brand_stated_facts[]` item in the Meera context, the `calculate_budget` fields `rateBasis="brand_stated"` + `brandStatedBudgetMin/Max`, and the `propose_brand_fact` echo payload. With these frozen, M2 and M3 can build against them in parallel with M1. No shape changes after the freeze without Priya.

### 2.1 Lanes (file-disjoint)

| Lane | Owner | Files (exclusive) | §10 tests owned | Blocks / blocked by |
|---|---|---|---|---|
| **M0 — canonical niche list** | **Vikram** | NEW `influora-api/src/main/java/com/influora/domain/enums/CreatorNiche.java` (values plus a `fromWire` normaliser that is case-exact on output). Before writing, Vikram reads the values actually stored in creator `categories` (read-only DISTINCT via Meera) and lists every casing variant. A normalising data migration, if one is needed, is a separate file in this lane: NEW `db/migration/V20260917..__normalise_creator_categories.sql`. Exposed read-only by M1's controller as `GET /api/brand/meera-facts/niches`, so the frontend and AI never keep a copy. | 16 (off-list / wrong-case rejected) | **Blocks M1, M2, M3.** No niche fact can be saved until this lands. Mock disagreement on casing already seen: `src/lib/api.ts:6739` vs `src/lib/meera-api.ts:648` (from the design, §2). If existing creator rows need a data migration, **that migration needs Swapnil/Priya sign-off before it runs on prod.** |
| **M1a — storage + write API** | **Vikram** | NEW migration `V20260917..__brand_facts.sql`; NEW `domain/entity/BrandFact.java`; NEW `repository/BrandFactRepository.java`; NEW `service/meera/BrandFactService.java` (validation, supersede, forget, audit); NEW `service/meera/BrandFactResolver.java`; NEW `web/controller/BrandFactController.java` + NEW DTO file [package path of existing DTOs unverified; Vikram follows the neighbouring controller] | 4, 11, 15 (resolver/forget half) | Blocked by M0. Blocks M1b. |
| **M1b — read path + budget branch** | **Vikram** | `service/meera/MeeraContextService.java`; `service/meera/BrandContextAssembler.java` (only if the context DTO is built there) plus the context response DTO [file unverified]; `service/meera/tool/CalculateBudgetExecutor.java`; NEW ArchUnit `src/test/.../architecture/BrandFactBarrierTest.java`; NEW Testcontainers `src/test/.../meera/BrandFactTenantIsolationIT.java` | 3, 5, 8, 9, 10, 12 (backend half), 16 (repository-argument spy) | Blocked by M1a. **Conflict watch:** `MeeraContextService` / `BrandContextAssembler` compute the rate band and outcome figures, which touch metrics. Before starting, Vikram checks `git status` and `git log -3 --` on these two files, and stops if the other session has them open. |
| **M2 — AI tool, prompt, F-0852** | **Ash** (Priya reviews, does not write) | `influora-ai/app/tools/schemas.py` (`propose_brand_fact` in `LOCAL_TOOL_NAMES` only); `influora-ai/app/tools/loop.py` (only if the local-tool branch needs a case); `influora-ai/app/prompt/assembler.py` (`_render_brand_stated_facts`, `CONTEXT_PAYLOAD_FIELDS`, **F-0852**: the `price_source` label on every catalog line at `:408-415`); `influora-ai/app/prompt/persona.py` (propose-once, never say "saved", attribute every stated number, re-ask when stale; replace the "not persisting" rule at `:225-227`); NEW tests under `influora-ai/tests/prompt/` and `influora-ai/tests/tools/` | 1, 2, 6, 14, plus **F-0852 test**: a catalog with one `scraped` and one `inferred` price → each Block B line carries its own label, and no unlabelled `name (INR price)` line remains | Blocked by 2.0 freeze only. Runs in parallel with M1 against fixture contexts. |
| **M3 — frontend card + memory panel** | **Ananya** | NEW `src/components/feature/meera/BrandFactCard.tsx`; `src/components/feature/meera/ToolResultRenderer.tsx` (the card branch and the `brand_stated` calculate_budget branch); `src/lib/meera-api.ts` (facts client; **not** `api.ts`); NEW `src/components/brand/settings/meera-memory-panel.tsx`; `src/pages/brand-settings.tsx` (one mount line only); NEW tests alongside | 7, 13, plus card tests: Save sends no `source` field; "Don't save" sends nothing; Forget calls DELETE | Blocked by 2.0 freeze (builds against mocks). Test 13 reads `campaign-form.tsx` / `brand-new-hype-campaign.tsx` **in tests only**. Those files are F2's and are closed by then, so M3 must not edit them. |
| **M4 — cross-tenant gate** | **Kabir** (hard gate) | NEW `.proof-os/tasks/T-MEERA-MEMORY-0917/kabir-attacks.md` only; no product files | Attack cases turned into tests that Meera runs: B reads A's fact by id, B PATCH/DELETE on A's id (expect 404, not 403), `conversation_id` from A in B's save (400), niche from A leaking into B's band, a prompt-injected `brand_quote` reaching Block B unneutralised, and 50 extreme stated budgets not moving the band | After M1b+M2+M3 are integrated, before merge. **Merge is blocked until every Kabir case exists as a test and Meera has run it green, with falsification.** |

**Dependency order:** `2.0 freeze` → `M0` → `M1a` → `M1b` → integration → Kavya → **M4 Kabir gate** → Meera → Neha. **M2 and M3 run in parallel from the freeze** and join at integration.
Estimate is unchanged at ~8.5 dev-days + M0 (0.5d, more if the category data needs a migration) + the freeze (0.25d).

### 2.2 Testing team — T-MEERA-MEMORY-0917

| Checker | Ceiling | What they prove | Counts? |
|---|---|---|---|
| **Meera** | proved | `mvn clean test` (with "Compiling N source files" visible), `pytest` (with `requirements-dev.txt` installed), `tsc --noEmit`, `vitest`, `npm run build`. **Tests 8 and 9 are Testcontainers.** A local run that reports them Skipped is **not** a pass. They must run on CI or on a machine with Docker, with the run log showing them executed. **Falsify** at least: render stated facts inside `_render_outcome_digest` → 1/2 red; drop the `workspaceId` filter in `findLiveByWorkspaceId` → 8/11 red (after the non-vacuous A-sees-own check passes); let the save endpoint honour a body `source` → 4 red; import `BrandFactResolver` from a campaign/wallet service → 10 red; remove the F-0852 label → F-0852 test red; lowercase niche accepted → 16 red; add `anyOf` to `propose_brand_fact` → 6 red. | **YES, primary** |
| **Neha** | proved | Live: a brand with no band asks for a budget → answers → card appears → Save → new conversation → Meera cites "you mentioned…" → Settings panel shows the fact → Forget → the next conversation no longer cites it. Also with a second brand account: the fact never appears. | **YES**, for the live claim |
| **Kabir** | echo | M4 attack list (above). Hard merge gate, but proof comes only from Meera running his cases. | Via Meera only |
| **Kavya** | echo | Functional QA of card/panel, copy ("escrow", "market rate", "saved" before Save), accessibility of the card. Findings become tests. | Via Meera only |
| **Ash** | — (producer on M2) | Does not review his own lane. Priya reviews M2 as believed; that does not count toward done_when either. | No |

**T-MEERA-MEMORY-0917 is DONE when:** Meera's clean run is green including tests 8 and 9 actually executed (not skipped), every falsification above is shown red, every Kabir case exists as a test and passed through Meera, and Neha's two-account live pass is recorded.

---

## Items for Swapnil

1. **(d)** Approve the read-only detection query on prod. Decide: no charge (recommended), waive, or case by case.
2. **F-0848 live test:** approve a small real funding + fee on staging/live for Neha.
3. **M0:** if creator `categories` casing needs a data migration, approve it before it runs on prod.
4. **Docker/CI:** without it, tests 8, 9 and T3-DB cannot reach `proved`, and done_when for the memory build cannot be met honestly.
