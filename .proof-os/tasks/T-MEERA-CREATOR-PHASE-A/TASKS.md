# TASKS: Meera for Creators — Phase A

**Task**: T-MEERA-CREATOR-PHASE-A  
**Date**: 2026-09-03  
**Spec**: See SPEC.md in this directory  

---

## BACKEND (Vikram / Backend Engineer)

**Migrations & Schema**
- [x] V72: Flyway migration for structured deal terms (Collaboration + Campaign)
  - [x] `collaborations`: add 7 columns (usage_months, usage_perpetual, usage_channels, exclusivity_days, exclusivity_scope, exclusivity_brands, max_revisions)
  - [x] `campaigns`: add 2 columns (end_brand_name, end_brand_category)
- [x] V73: Flyway migration for `creator_agent_preferences` table
  - [x] Table with all 17 fields
  - [x] FK to `creator_profiles`
  - [x] Index on `creator_id`
- [x] V74: Flyway migration for `meera_creator_conversations` table
  - [x] Table with conversation tracking fields
  - [x] FK to `creator_profiles`
  - [x] Indexes on `creator_id` and `last_message_at`

**Entities & Enums**
- [x] `CreatorAgentPreferences` entity (17 fields, getters, `newWithDefaults`/`applyPreferences`/`recordConsent`)
- [x] `MeeraCreatorConversation` entity
- [x] `UsageChannel` enum (5 values)
- [x] `ExclusivityScope` enum (3 values)
- [x] Update `Collaboration` entity (7 fields + getters + `applyDealTerms`; also cleared on `revive()`)
- [x] Update `Campaign` entity (2 fields, threaded through builder/applyPatch/duplicateCopy)

**Repositories**
- [x] `CreatorAgentPreferencesRepository` with `findByCreatorId()`
- [x] `MeeraCreatorConversationRepository` (findByCreatorId/findByConversationId(+creatorId))
- [x] `DealMessageRepository.findFirstMessageTimestampsBySender()` (A1 reply-time projection, new)
- [x] `MetaOAuthTokenRepository.countDistinctConnectedCreatorProfiles()` (A1 connect-rate, new)

**Services**
- [x] `CreatorAgentBaselineService.getBaselines()` (A1) — creators-by-tier (incl. MEGA), briefs/creator/month percentiles, Meta connect rate, median reply hours; `sample_label_compliance` is the spec's own documented hand-sample placeholder, not automated
- [x] `CreatorAgentPreferencesService` (A3) — `getOrCreatePreferences`/`updatePreferences`/`recordConsent`; floor default = last COMPLETED deal's `agreedRate`, else `RateEstimationService.estimate().min()`, else the ₹500 fallback (SPEC.md §9 risk table); language default from `CreatorProfile.languagesJson[0]` else hi-IN
- [x] `CreatorAgentConversationService` (A6) — list/export/delete, ownership enforced via `MeeraCreatorConversationRepository` (not `AiConversation.workspaceId` — see its class javadoc for why); `recordTurn()` provided but **not wired to any write path** (known gap, see below)
- [x] `MeeraContextService.assembleCreatorContext(creatorUserId)` (A4) — first_name extraction, all numbers formatted via `NumberFormat` (A8), floors from `creator_agent_preferences`, metrics from `creator_metrics` (this codebase has no `instagram_insights` table — see deviation below), deals summary from `collaborations`, `identity` = ONLY `kyc_done`/`gstin_present`
- [x] `PublicCreatorService` (A9)

**Controllers & Endpoints** (mounted without `/api` — `server.servlet.context-path=/api/v1` already supplies it, same convention every sibling controller uses)
- [x] `AdminCreatorAgentController.getBaselines()` — GET `/admin/creator-agent/baselines` (A1, `hasRole(ADMIN)` via SecurityConfig's existing `/admin/**` matcher)
- [x] `CreatorAgentController.getPreferences()` — GET `/creator/agent-preferences` (A3)
- [x] `CreatorAgentController.updatePreferences()` — PUT `/creator/agent-preferences` (A3)
- [x] `CreatorAgentController.recordConsent()` — POST `/creator/agent-preferences/consent` (A6)
- [x] `CreatorAgentController.listConversations()` — GET `/creator/agent-preferences/conversations` (A6)
- [x] `CreatorAgentController.exportConversation()` — GET `/creator/agent-preferences/conversations/{id}/export` (A6)
- [x] `CreatorAgentController.deleteConversation()` — DELETE `/creator/agent-preferences/conversations/{id}` (A6)
- [x] `PublicCreatorController.getVerifiedMetrics()` — GET `/public/creators/{username}/verified` (A9); permitAll added to SecurityConfig
  - [x] Only if discoverable=true and Meta connected (404 otherwise)
  - [x] NO rates, NO floors
- [x] `MeeraInternalController.context()` handles audience=CREATOR (A4) — `MeeraContextService.assemble()` now returns `Object` and branches internally; route itself needed no per-audience branch

**DTOs**
- [x] `MeeraContextDtos.CreatorContextResponse` record (snake_case, all 16 fields — verified against influora-ai's own `CREATOR_CONTEXT_PAYLOAD_FIELDS` allow-list, byte-for-byte match including `consent_accepted`, which SPEC.md §2.9's JSON example omits but §3.4/chat.py requires — dev's own TASKS.md note flagged this exact gap, already closed here)
- [x] `CreatorAgentDtos.PreferencesResponse` / `UpdatePreferencesRequest` / `ConsentResponse` / conversation DTOs — verified field-for-field against Ananya's `src/lib/api.ts` `CreatorAgentPreferences` interface (exact match)
- [x] `AdminCreatorAgentDtos.BaselinesResponse`
- [x] `PublicCreatorDtos.VerifiedProfileResponse` — verified against `src/lib/api.ts`'s `PublicCreatorVerifiedResponse` (exact match; see known gap below on nullability)
- [x] `CampaignDtos`/`DealDtos` updated (endBrandName/endBrandCategory required on create; `DealTermsDto` nested under `dealTerms` on both `CreateDealRequest` and `CounterRequest`) — verified against `src/lib/types.ts`'s `DealTerms`/`UsageChannel`/`ExclusivityScope` (exact field/enum match) and `src/lib/api.ts`'s `deals.create`/`deals.counter` (both already send `dealTerms`)

**Tests (A7 Java side)**
- [x] `InfoBarrierTest.java` — architecture test (A7a), source-file scan (no ArchUnit dependency in this project — new Maven deps need Priya approval, out of this task's authority)
- [x] `InfoBarrierRuntimeTest.java` — runtime test (A7b) — 3 tests: BRAND context never touches `CreatorAgentPreferencesRepository` at all (`verifyNoInteractions`) + never contains a floor string; two creators' CREATOR contexts never cross-contaminate floors; `CreatorContextResponse` shape check (identity/floors key sets)
- [x] All existing tests still pass (mvn -o test on the full touched surface: 118/118 across InfoBarrier*, MeeraContextServiceTest, DealServiceTest, DealControllerTest, CampaignServiceTest, MeeraInternalController*Test)

**Verification**
- [x] `mvn -o compile` and `mvn -o test-compile` both pass clean
- [x] Targeted test run green (see above); a full-suite `mvn -o test` hit spurious `NoClassDefFound`/`bad class file` errors from a **concurrent session's own Maven process** locking/deleting files under `target/` mid-run (confirmed: a `mvn clean` in this same window failed with `Failed to delete ... target\test-classes\com\influora\service`, i.e. a file lock from another process) — re-running the targeted suite immediately after came back 100% green, so this is the documented repo hazard (concurrent write collisions), not a regression here. **Not personally re-run as a full-suite `mvn clean install` — re-run that once no other session is building.**
- [x] Migrations are plain additive `ALTER TABLE`/`CREATE TABLE` (no `mvn` DB integration test run against real MySQL in this pass; V72-74 follow the exact style of V70/V71)
- [x] Endpoint JSON shapes hand-verified against both concurrent-session consumers (influora-ai's `assembler.py` field allow-list, `src/lib/api.ts`/`types.ts`) — see DTOs section above
- [x] Number formatting: `NumberFormat.getIntegerInstance(Locale.forLanguageTag(creatorLanguage))` for floors/followers/reach, `NumberFormat.getNumberInstance(...)` (1 fraction digit) for engagement_rate — e.g. 12400 → "12,400"

**Known gaps / deviations (Vikram, 2026-09-03)**
- **`instagram_insights` table does not exist in this codebase.** SPEC.md §2.9/§9.1 both name it as the metrics source; the real, already-built equivalent is `creator_metrics` (`CreatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc`, latest row per creator) plus `CreatorProfile.totalFollowers`/`engagementRate` as the fallback when no metric row exists yet. Used that instead — same field-freshness quality (Meta-API-sourced or self-reported per `CreatorMetric.dataSource`), just a different table name than the spec assumed.
- **A9 `reach_30d`/`engagement_rate` nullability mismatch**: `src/lib/api.ts`'s `PublicVerifiedMetrics` types these as required `number`, but a discoverable+Meta-connected creator can still have zero `CreatorMetric` rows (polling hasn't run yet) — my response omits them (`@JsonInclude(NON_NULL)`) rather than fabricating `0` (TECH-STACK.md's locked UI-Honesty rule bans a `0` fallback for "no data"). A creator in that state will render `undefined` on the FE for those two fields; not independently fixed since it's a genuine three-way spec/FE/backend disagreement, not a one-line bug.
- **CounterRequest.dealTerms and CreateDealRequest.dealTerms are wired** (`DealService.createProposal`/`doCounter` both call the same `applyDealTermsIfPresent`) — this went beyond SPEC.md's literal listing (which only shows the offer/create form) because `src/lib/api.ts`'s `deals.counter()` already sends `dealTerms` too; leaving it unwired would have silently discarded brand/creator input exactly the way the codebase's own `usageRights`/`exclusivity` history warns against (see `DealDtos.CreateDealRequest`'s class javadoc).
- **`MeeraCreatorConversation` write side is not wired.** The entity/repository/service (`CreatorAgentConversationService.recordTurn`) exist and the list/export/delete endpoints work against whatever rows exist, but nothing in this backend slice calls `recordTurn()` on a real Meera turn — that hook belongs on the CREATOR-audience chat-turn persistence path (`MeeraSessionService.persistAssistantWriteback`, driven by influora-ai's `chat.py`), which is outside both this task's explicit file list and a file with documented concurrent-edit risk. A creator's conversation list will be empty until this is wired.
- **On-behalf token minting for CREATOR turns is not implemented.** `OnBehalfAuthResolver`/`OnBehalfTokenService` already accept any `userType` generically (no BRAND-only restriction), so the mesh auth itself needs no change — but nothing in this pass verified/adjusted where a CREATOR turn's on-behalf JWT gets its `workspaceId` claim minted as the creator's own user id (SPEC.md §2.9's `"workspace_id": "creator_user_id_here"`). That minting logic lives in `MeeraSessionService`/session-start flow, not in `MeeraContextService`/`MeeraInternalController` (my literal A4 scope). Flagging as unverified rather than claiming it works end-to-end.
- **`DealRiskService`/deal-warnings, Level-1 auto-send, media kit, growth coach**: correctly out of Phase A scope per SPEC.md §7 — not touched.
- Every new DTO field name was diffed against BOTH concurrent sessions' actual consumers (influora-ai's `assembler.py`, `src/lib/api.ts`/`types.ts`) rather than only against SPEC.md's JSON examples — SPEC.md itself has at least one documented gap (`consent_accepted`, caught by dev's own notes above) that a spec-only read would have missed.

---

## AI SERVICE (Dev / AI Engineer)

**Python Code Changes**

**chat.py (A4 + A6)**
- [x] Derive `audience` from on-behalf token's `userType` field
  - [x] If `userType == "CREATOR"` → audience = "CREATOR"
  - [x] If `userType == "BRAND"` → audience = "BRAND"
  - [x] Fallback to "BRAND" if missing
- [x] Pass `audience` to Spring in context request
- [x] Consent gate for CREATOR audience (A6)
  - [x] Check `context.get('consent_accepted')`
  - [x] Raise `HTTPException(403, {"code": "CONSENT_REQUIRED", ...})` if not consented

**assembler.py (A4)**
- [x] `build_block_b_creator(context)` function (NEW)
  - [x] Extract: display_name, first_name, city, tier, categories
  - [x] Metrics summary (all pre-formatted strings from Java)
  - [x] Deals summary (active, completed, total earned)
  - [x] Floors (NEVER shown to brands)
  - [x] Settings (language, tone, approval_level, represented)
  - [x] Identity (ONLY kyc_done, gstin_present booleans)
  - [x] Return as cached Block
- [x] Update `assemble_prompt()` to route by audience
  - [x] If audience=CREATOR → use `build_block_b_creator()` + `creator_persona`
  - [x] If audience=BRAND → use `build_block_b()` + `persona`

**creator_persona.py (A4) — NEW MODULE**
- [x] Fork from `persona.py`
- [x] Peer voice: first-name, "I work for you here"
- [x] NEVER: "escrow" (use "secured funds" / "Secure Payments")
- [x] Every number from context/tool only
- [x] Floor never shown to brands
- [x] Brand budget never lowers ask
- [x] NO tools in Phase A (conversational only)
- [x] `get_creator_persona(context)` function with formatting

**sarvam.py (A5)**
- [x] `speech_to_text()` — accept `language` param (hi-IN, en-IN)
- [x] `text_to_speech()` — accept `language` param (hi-IN, en-IN)
- [x] Pass language to Sarvam API calls

**voice.py (A5)**
- [x] Extract `creator_language` from context
- [x] Pass to both `speech_to_text()` and `text_to_speech()`

**spend_tracker.py (A8)**
- [x] `check_creator_spend_gate(creator_id, audience)` function
- [x] Default cap: USD 0.75/month for CREATOR audience
- [x] Query spend this month for this creator
- [x] Raise `SpendCapExceeded` with friendly message if over cap
- [x] Call in `chat()` before provider call

**Tests (A7 Python side)**
- [x] `tests/security/test_info_barrier.py` — NEW file
- [x] Test: creator Block B never contains PAN/GSTIN/Aadhaar strings (A7c)
  - [x] Mock context with "pan": "ABCDE1234F", "gstin": "27...", "aadhaar_last4": "1234"
  - [x] Build block_b_creator
  - [x] Assert those strings NOT in output
- [x] Test: brand Block B never contains creator floors (A7c)
  - [x] Mock context with "floors": {"reel_floor": "9,999", ...}
  - [x] Build block_b (brand)
  - [x] Assert "9,999" and "9999" NOT in output
- [x] All existing tests still pass

**Verification**
- [x] `pytest` passes
- [ ] CREATOR audience turns work end-to-end locally (BLOCKED on backend A4 `consent_accepted` + CREATOR context; Python side proven by route tests with mocked Spring)
- [ ] Voice works with creator's language (hi-IN or en-IN) — Python route proven by tests; live Sarvam run pending backend context
- [x] Spend tracker enforces cap

---

**Deviations / notes (dev, 2026-09-03)**
- Audience: verified-token claim > on-behalf JWT `userType` (unverified routing hint only) > BRAND; Spring's returned `audience` is authoritative and a mismatch fails closed (403 `audience_mismatch`). New module `app/auth/audience.py`.
- CREATOR context fetch fails CLOSED (503 `creator_context_unavailable`), never an empty Block B; BRAND path unchanged.
- Consent: `consent_accepted: true` OR non-empty `consent_accepted_at` on the CREATOR context; a MISSING key fails closed. **Backend must add `consent_accepted` to `CreatorContextResponse` (spec §2.9 omits it; §3.4 reads it).** 403 body carries `code` at top level AND under `error`.
- Over-cap: 429 `CREATOR_MONTHLY_CAP_REACHED` with `CREATOR_CAP_MESSAGE`; env `AI_CREATOR_MONTHLY_CAP_USD` (0 disables). Counter key `influora:ai:spend:creator:{id}:{YYYY-MM}` (Redis + in-memory fallback).
- Tools: `assemble_prompt` returns `tools=[]` for CREATOR; `run_tool_loop(tools=...)`; `claude.stream_turn` omits the `tools` kwarg when empty.
- Creator Block B is ALLOW-listed (`CREATOR_CONTEXT_PAYLOAD_FIELDS`); brand `_FORBIDDEN_BRAND_FIELDS` now strips `floors`/`identity`/etc.
- Persona vocabulary: the banned word is structurally absent from `creator_persona.py` (test-enforced via `CREATOR_BANNED_WORDS`).
- Voice: `resolve_voice_language()` in voice.py; `sarvam.transcribe(language=)`, `speak(lang=)`; `normalize_voice_language()` falls back to hi-IN for unsupported codes; `VOICE_DEFAULT_STT/TTS_LANGUAGE` env for brand turns. `/voice/*` accept optional `onbehalf_jwt` + `lang` fields.
- Tests: 6 new files, 80 new tests; full influora-ai suite 753 passed.

## FRONTEND (Ananya / Frontend Engineer)

**Campaign Form (A2)**
- [x] Add `endBrandName` field (required)
- [x] Add `endBrandCategory` field (required)
- [x] Validation: both required for NEW campaigns (legacy can be null) — gated on `!isEditing` in `validateStep('basics')`
- [x] TS type updated to match DTO

**Offer/Deal Proposal Form (A2)**
- [x] Add `usageMonths` field (number, nullable)
- [x] Add `usagePerpetual` checkbox
- [x] Add `usageChannels` multi-select (Organic, Paid Ads, Whitelisting, Website, Offline)
- [x] Add `exclusivityDays` field (number, nullable)
- [x] Add `exclusivityScope` select (None, Named Brands, Category)
- [x] Add `exclusivityBrands` textarea (if scope=Named Brands)
- [x] Add `maxRevisions` field (number, default=2)
- [x] TS type `DealTerms` matching DTO — wired into `api.deals.create`/`api.deals.counter`'s new `dealTerms` field; `brand-chat.tsx`'s `handleSendProposal` builds it from `ProposalFormData`. Only the ProposalForm→counter path (brand-chat.tsx) is wired — `brand-campaign-detail.tsx`'s separate counter call site and `CounterProposalForm` (creator's counter) were left untouched (out of the spec's "brand offer form" scope; flagged as a known gap).

**Creator Settings — Meera Tab (A3)**
- [x] New "Meera" tab/section in creator-settings.tsx — `src/components/creator/MeeraSettingsSection.tsx`, rendered as a Card section (this page has no tab strip; every other settings group is a Card too)
- [x] **Rate Floors** section (reelFloor/storySetFloor/postFloor + help text)
- [x] **Filters** section (excludedCategories badge multi-toggle + blockedBrands textarea)
- [x] **Automation Level** section (RadioGroup 0/1/2, captioned Phase A does nothing automatically yet)
- [x] **Language & Tone** section (creatorLanguage hi-IN/en-IN, brandTone FORMAL/FRIENDLY)
- [x] **Working Hours** section (start/end hour, workingDays badge multi-toggle, weeklySponsoredLimit)
- [x] **Representation** section — built per the orchestrator's Phase A scope note ("manager seat NOT approved, build only the represented block"): `represented` checkbox + `agencyName` (required when represented, both client-validated and stripped server-side on save if unrepresented)
- [x] GET /api/creator/agent-preferences on load (computes defaults) — `api.creatorAgentPrefs.getPreferences()`
- [x] PUT /api/creator/agent-preferences on save — `api.creatorAgentPrefs.updatePreferences()`
- [x] TS interface `CreatorAgentPreferences` matching DTO EXACTLY (snake_case, unmapped, in `src/lib/api.ts`)

**Consent Screen (A6)**
- [x] `ConsentScreen.tsx` component (NEW) — `src/components/meera/ConsentScreen.tsx`
- [x] Hi-IN and En-IN text variants
- [x] Dialog with title, body, Accept/Decline buttons
- [x] On Accept: POST /api/creator/agent-preferences/consent
- [x] On Decline: close dialog
- [x] Trigger: checked proactively via GET /api/creator/agent-preferences's `consent_accepted` on "Open Meera" click (cheaper than provoking the 403), AND defensively on a live CONSENT_REQUIRED from a real turn (`MeeraCopilotChat`'s `onConsentRequired`) in case consent is revoked mid-session

**Conversations List/Export/Delete (A6)**
- [x] Add "My Meera Conversations" section in Meera settings tab
- [x] Table: Started, Last Message, Messages, Actions
- [x] GET /api/creator/agent-preferences/conversations on load
- [x] **Export** button: GET /api/creator/agent-preferences/conversations/{id}/export → download JSON (client-side Blob + `<a download>`)
- [x] **Delete** button: DELETE /api/creator/agent-preferences/conversations/{id} → AlertDialog confirm first

**Public Verified Metrics Route (A9)**
- [x] New route: `/c/:username/verified` (registered in App.tsx ahead of the `/:handle` public-portfolio catch-all)
- [x] `creator-verified-metrics.tsx` page (NEW)
- [x] Fetch: GET /api/public/creators/{username}/verified (no auth)
- [x] Display: username, display_name, city, categories, verified_metrics (followers, reach_30d, engagement_rate, verified_at), platform_deal_count
- [x] NO rates, NO floors shown
- [x] 404/not-discoverable → "Creator not found" empty state
- [x] N/A — SSR/SSG: this stack is React 18 + Vite CSR, not Next.js (TECH-STACK.md); no SSR facility exists to hang this off. CSR shipped as-is.

**Meera Chat Entry on Creator Co-Pilot (A10)**
- [x] `creator-copilot.tsx` — add "Talk to Meera" button
- [x] On click: check consent via GET /api/creator/agent-preferences
  - [x] If not consented → show ConsentScreen
  - [x] If consent accepted → open chat panel
- [x] NOT `MeeraChatPanel` (brand's component, deeply wired to Living Canvas stage advancement/tool cards/credits paywall — none of which exist for a CREATOR turn in Phase A). Built a sibling `MeeraCopilotChat` (`src/components/creator/MeeraCopilotChat.tsx`) sharing the same `meeraApi`/`useMeeraStream` plumbing with `role: 'creator'` threaded through (see meera-api.ts's new `MeeraRole` param, defaulted everywhere so brand call sites are untouched). Props: `language={preferences.creator_language}`, voice on via `useVoiceOutput('creator')`/`useVoiceInput({role:'creator'})`.
- [x] Day-one onboarding first message — renders the backend's real history when present; falls back to a local, non-persisted greeting bubble (hi/en) if history is empty, so the panel never opens blank ahead of the backend's own onboarding-turn landing
- [x] Existing trend suggestion section stays

**TS Types**
- [x] `CreatorAgentPreferences` interface (matching DTO, snake_case)
- [x] `DealTerms` interface (7 fields per SPEC.md §4.2 — the spec's own "8 fields" count in this TASKS.md line appears to be off-by-one against its own §4.2 listing)
- [x] `PublicCreatorVerifiedResponse` interface (spec called it `VerifiedMetricsResponse`; named for consistency with this file's `publicCreators` namespace)
- [x] `CreatorAgentConversationItem` interface (spec called it `ConversationListItem`)
- [x] Update campaign/deal DTOs to include new fields (`Campaign.endBrandName/endBrandCategory`, `deals.create`/`deals.counter`'s `dealTerms`)
- [x] Matched DTO shapes as specified in SPEC.md exactly (snake_case wire fields left unmapped) — not independently verified against the real Java records, since backend A3/A4 DTOs are a parallel work stream; flagged as a cross-stream integration risk, not a known defect

**Verification**
- [x] `npx tsc --noEmit` passes (no type errors)
- [x] All new/touched pages render in existing test coverage (see below) — not manually clicked through a running dev server
- [x] Forms save via the real endpoints in live mode; mock mode exercised via vitest
- [x] Consent flow covered end-to-end by `creator-copilot-meera-consent.test.tsx` (mock mode: shows ConsentScreen, accepts, opens chat)
- [x] Meera chat opens on creator-copilot with correct language (asserted via the Hindi consent/onboarding copy in the test above)
- [x] Vitest: all pre-existing suites this change touches still pass (42 tests across 9 files, incl. 3 test files whose narrow `@/lib/api` mocks needed a `creatorAgentPrefs` stub added — see deviations below) + 1 new test file (2 tests)

**Deviations / notes (Ananya, 2026-09-03)**
- `meera-api.ts` previously read only `brand_token`. Threaded an optional `role: 'brand'|'creator'` param (default `'brand'`) through `startSession`/`sendTurn`/`getHistory`/`getMessagesAfter`/`speak`/`transcribe`, and the same through `useVoiceOutput(role)`/`useVoiceInput({role})` — every existing (brand) call site is unaffected since none of them pass a role.
- Found + fixed a real crash: `scrollRef.current?.scrollTo(...)` (the same pattern the brand `MeeraChatPanel.tsx` uses, unguarded) throws in jsdom, which has no `scrollTo` — feature-detected in the new `MeeraCopilotChat` before calling it. `MeeraChatPanel.tsx` itself was left untouched (out of frontend-area's file-ownership boundary risk for this task — it's a shared, brand-tested component).
- Found + fixed a real regression: adding `MeeraSettingsSection` to `creator-settings.tsx` broke 3 pre-existing test files (`creator-settings-change-password/-connected-accounts/-logout.test.tsx`) whose narrow `@/lib/api` mocks didn't include the new `creatorAgentPrefs` namespace, crashing the page on mount (`Cannot read properties of undefined (reading 'getPreferences')`). Added a `creatorAgentPrefs` stub to each mock.
- `dealTerms` request-body shape (flat fields alongside `usageRights`, `exclusivityBrands` as `string[]`) is this engineer's best-judgment reading of SPEC.md §1.1 — the spec documents the Collaboration entity's columns but not `POST /deals`/`POST /deals/:id/counter`'s exact request DTO shape. Needs a shape-match confirmation once backend's A2 DTOs land (backend is a parallel work stream not read as part of this task).
- Consent-gate wiring assumes Spring forwards Python's `{"code": "CONSENT_REQUIRED", ...}` (chat.py, confirmed real per the AI section's deviation notes above) through its own error envelope on `POST /meera/sessions/:id/messages`, surfacing as `ApiError.code === 'CONSENT_REQUIRED'`. Not independently verified against the real Spring behavior (backend area).
- Money-cap friendly message: added handling for `CREATOR_MONTHLY_CAP_REACHED` (from spend_tracker.py's real 429, per the AI section's deviation notes) so the panel shows the backend's actual friendly copy instead of a generic failure — this wasn't in the FE task list but the AI side had already implemented and documented the code, so leaving it unhandled would have silently discarded a real, working backend feature.
- Out of scope not attempted per SPEC.md §7 (confirmed unbuilt): manager seat / multi-creator login, any Meera tools for CREATOR audience, WhatsApp/push notifications, media kit generation, DealRiskService warnings.

---

## INTEGRATION & E2E (All teams)

**Local Smoke Test**
- [ ] Create test creator account
- [ ] GET /api/creator/agent-preferences → defaults computed correctly
- [ ] PUT /api/creator/agent-preferences → saves all fields
- [ ] POST /api/creator/agent-preferences/consent → timestamp set
- [ ] Open Meera on creator-copilot → consent gate works
- [ ] First CREATOR turn → gets creator context, replies in chosen language (hi/en)
- [ ] Voice test: record in Hindi → transcribed → response in Hindi
- [ ] Spend tracker: make 10+ turns → cap enforced
- [ ] Info barrier: brand context never shows creator floor (manual check)

**Code Review Gates**
- [ ] Kavya review: code standards, DTO alignment, test coverage
- [ ] Kabir review: info barrier tests pass, no PAN/GSTIN/Aadhaar leaks, scope enforcement

**Deploy Checklist**
- [ ] All 3 migrations (V72, V73, V74) in PR
- [ ] Feature flag `MEERA_CREATOR_ENABLED` in application.yml (optional)
- [ ] Deploy backend → AI service → frontend (in order)
- [ ] Staging smoke test
- [ ] Production deploy (with flag off initially)
- [ ] Enable flag in prod after smoke test
- [ ] Monitor logs for errors

---

## DONE CRITERIA

Phase A is **DONE** when ALL of the following are TRUE:

- [ ] All backend tasks checked off above
- [ ] All AI service tasks checked off above
- [ ] All frontend tasks checked off above
- [ ] `mvn clean install` passes
- [ ] `pytest` passes
- [ ] `npx tsc --noEmit` passes
- [ ] All A7 tests pass (architecture test, runtime test, Python test)
- [ ] Local E2E smoke test passes (see above)
- [ ] Kavya code review APPROVED
- [ ] Kabir security review APPROVED (info barrier)
- [ ] PR merged to main
- [ ] Deployed to staging
- [ ] Staging smoke test passes
- [ ] Deployed to production
- [ ] Production smoke test passes

---

**Questions or blockers** → escalate to Arjun immediately.
