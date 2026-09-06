# T-MEERA-CREATOR-PHASE-B — Build Spec: Deal PR and Paste-and-Secure

**Status:** ready to build **Phase B0 (Paste and Read) only** — see §14.5; Phase B1 (Secure and Send) is gated on B0's live numbers. **Baseline commit:** branch from HEAD `143ca1e` on a clean tree (was `8c7b18b`; see `PRIYA-COMPAT-0904.md` §6). **Date:** 2026-09-04. **Amendments:** §14 (product-risk pass, 2026-09-04) supersedes the sections it names; pointers marked `<!-- AMEND-0904 -->`.
**Plan reference:** Meera for Creators plan rev 2, Part 7 Phase B (weeks 2 to 4).
**Fact sheets (read before touching a file):** `facts/backend.md`, `facts/ai-service.md`, `facts/frontend.md` in this folder. Every signature quoted below was verified against HEAD; if a fact sheet and this spec disagree, the fact sheet wins and this spec has a bug to report.

This document is the contract for three engineers working in parallel (backend `influora-api`, AI service `influora-ai`, frontend `src/`). It names every file to add or change, every step, and every test. Nothing in it requires a Swapnil decision except the four flagged in section 12 (each ships with a stated default if no ruling arrives by day 1).

---

## 0. Rules that apply to every step

1. **Compile against HEAD.** Do not rename existing symbols. Extend records by appending components at the end and updating every constructor call site the fact sheet lists.
2. **Vocabulary.** The word "escrow" is banned in any user-facing string, API message, prompt text, or notification. Use "Secure Payments" and "secured funds". Existing identifiers (`EscrowHold`, `escrowFunded`) stay.
3. **Info barrier.** `CreatorAgentPreferencesRepository` may be imported only by `MeeraContextService`, `CreatorAgentPreferencesService`, and itself (`InfoBarrierTest` allow-list). Every new class reads floors and preferences through `CreatorAgentPreferencesService`. Never put a creator's floor, private preference, or agency name on any payload a brand can read.
4. **Numbers to the model.** Every number that reaches a prompt is rendered by Java into a formatted string (`NumberFormat`, locale from `creator_language`). Python never formats, sums, or converts. Numeric values for the frontend travel as numbers in separate fields.
5. **Migrations.** Timestamp-versioned `V2026MMDDHHMMSS__name.sql` with version greater than `20260903170000` (verified: `V20260903170000__creator_agent_preferences_timezone_currency.sql` is the highest on HEAD). Every `CREATE TABLE` ends with `) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;`. Every entity column has an explicit `@Column(name = "...")`. `ddl-auto=validate` is on.
   <!-- PRIYA: added — CHAR(n) is banned. V20260718150000__char_to_varchar_remaining.sql documents a live boot failure ("wrong column type ... found [char]"): Hibernate maps every @Column(length=N) to VARCHAR(N) and no entity here declares a char columnDefinition. Use VARCHAR(n) for every fixed-width column, including token hashes (precedent: workspace_member_invites.invite_token_hash VARCHAR(64)). -->
   **No `CHAR(n)` columns.** `ddl-auto=validate` rejects them against a `@Column(length=N)` field. Use `VARCHAR(n)`.
6. **Feature flag.** Every new creator route calls `requireFeatureEnabled()` (404 `FEATURE_DISABLED`) exactly as `CreatorMeeraController` does at L104.
7. **Consent.** Every new creator route that touches data calls `requireConsent(creatorUserId)` after `requireCreatorProfile`.
8. **Id spaces.** `Collaboration.creatorId` is a `users.id`. `CreatorAgentPreferences.creatorId`, `CreatorMetric.creatorProfileId`, and every new table's `creator_profile_id` are `creator_profiles.id`. On the CREATOR audience, `workspace_id` in every Meera payload carries the creator's `users.id`. Resolve with `creatorProfileRepository.findByUserId(userId)`.
9. **Tool schemas.** No `anyOf`, `oneOf`, `allOf`, `not`, `$ref`, `if`/`then`/`else`. Every node has a single concrete `type`. Arrays declare `items`. `tests/tools/test_tool_schema_anthropic_valid.py` enforces this on every schema returned by `get_tool_schemas()` and must be extended to cover `get_creator_tool_schemas()` (step B.AI.1).
10. **Prompt version.** Any change to Block A text, the persona, or a tool schema requires bumping `PROMPT_VERSION` in `influora-ai/app/config.py` L69. Phase B bumps it once, to `meera-2026.09.10.1`.
11. **Tests are part of the deliverable.** Each step lists its tests. A step without green tests is not done.
12. **Git.** Branch from `8c7b18b`. `git add` every new file. Never commit unrelated dirty files from other tasks.

---

## 1. Scope in one table

| Id | Job | Backend | AI service | Frontend |
|----|-----|---------|------------|----------|
| B1 | Paste-and-secure: paste any brief, get summary, rate, flags, draft, then a link that creates the deal on Influora | briefs entity, extraction call, secure links, brand redemption | brief extraction route | Paste card, analysis card, secure-link landing |
| B2 | Creator tools in Meera chat: my deals, brief, rate, metrics, risks, draft | 6 executors, creator tool controller, per-level on-behalf scope | creator tool schemas, loop dispatch, persona, Block A | tool result cards, approve/edit/send |
| B3 | Rate quote: unit table, add-ons, package quote, scope-down default, anchor | `RateQuoteService`, constants, own-history override | renders quote | quote card, prefill counter with deal terms |
| B4 | Deal risk rules: ten deterministic rules with reason codes, warning card | `DealRiskService`, `/deals/{id}/risks` | phrases codes | `DealRiskCard` on deal page and thread |
| B5 | Level 1 routine replies with intent allow-list, send log, 60-second cancel, working hours; level-0 exit after 10 approved drafts | drafts, send log, delayed sender job, per-level scope | `send_routine_reply` tool, fixed refusal sentence | send log section, cancel, level-up prompt |
| B6 | Media kit and opt-in rate card; offer history; 20 percent holdout | media kit endpoints, prefs fields, offer history, holdout | reads `negotiation_holdout` | `/c/:username/kit`, settings fields |
| B7 | On-platform pitching: rank open campaigns, draft the application | `CampaignFitService`, 2 executors | 2 tool schemas | fit cards, apply with draft |

Out of scope for Phase B: Gmail or DM reading, brand outreach off-platform, auto-accept at any level, any money movement beyond the existing contract and funding flow, TDS or GST figures, WhatsApp (Phase C), YouTube.

---

## 2. Data model

### 2.1 Migration `V20260910100000__meera_phase_b_prefs.sql` (alter)

```sql
ALTER TABLE creator_agent_preferences
    ADD COLUMN rate_card_shareable TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN rate_card_json TEXT NULL,
    ADD COLUMN negotiation_holdout TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN holdout_until DATE NULL,
    ADD COLUMN approved_draft_count INT NOT NULL DEFAULT 0,
    ADD COLUMN level_up_prompted_at TIMESTAMP NULL;
```

Entity `CreatorAgentPreferences` (`influora-api/src/main/java/com/influora/domain/entity/CreatorAgentPreferences.java`): append six fields after `aiMonthlyCapUsd` (L138-139), each with explicit `@Column(name = ...)`:

```java
@Column(name = "rate_card_shareable", nullable = false)
private boolean rateCardShareable;
@Column(name = "rate_card_json", columnDefinition = "TEXT")
private String rateCardJson;                 // JSON object {"reel":"5000","story_set":"2500","post":"3000"} as creator-typed strings
@Column(name = "negotiation_holdout", nullable = false)
private boolean negotiationHoldout;
@Column(name = "holdout_until")
private LocalDate holdoutUntil;
@Column(name = "approved_draft_count", nullable = false)
private int approvedDraftCount;
@Column(name = "level_up_prompted_at")
private Instant levelUpPromptedAt;
```

Add getters for all six and these mutators:

```java
public void applyRateCard(boolean shareable, String rateCardJson)   // nulls json when !shareable
public void assignHoldout(boolean holdout, LocalDate until)          // called once at creation, see 2.9
public int recordApprovedDraft()                                     // approvedDraftCount += 1; returns new value; touches updatedAt
public void markLevelUpPrompted(Instant when)
```

`newWithDefaults(...)` (L155) keeps its signature. The holdout is assigned in `createWithComputedDefaults`, not in `getOrCreatePreferences` — see 2.9.

`applyPreferences(...)` (L299) keeps its 16 parameters. Rate card is applied through the new `applyRateCard`, called from `updatePreferences` when the request carries the two new fields.

<!-- PRIYA: added — `holdoutUntil` is the first LocalDate on this entity; add `import java.time.LocalDate;` (Instant and BigDecimal are already imported). -->
Add `import java.time.LocalDate;` — `holdoutUntil` is the first `LocalDate` field on this entity.

### 2.2 Migration `V20260910100100__creator_briefs.sql` (create)

```sql
CREATE TABLE creator_briefs (
    id                      VARCHAR(26)  NOT NULL,
    creator_profile_id      VARCHAR(26)  NOT NULL,
    source                  VARCHAR(16)  NOT NULL,            -- PASTED | PLATFORM
    collaboration_id        VARCHAR(26)  NULL,                -- set when source = PLATFORM or after secure-link redemption
    raw_text                TEXT         NOT NULL,
    brand_name_guess        VARCHAR(200) NULL,
    extracted_json          TEXT         NULL,                -- BriefExtraction JSON (2.11)
    risk_flags_json         TEXT         NULL,                -- List<RiskFlag> JSON (5.2)
    quote_json              TEXT         NULL,                -- PackageQuote JSON (4.3)
    status                  VARCHAR(16)  NOT NULL,            -- NEW | ANALYZED | DRAFTED | SECURED | DISMISSED
    extraction_source       VARCHAR(16)  NULL,                -- AI | FALLBACK
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_creator_briefs_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_creator_briefs_creator_created (creator_profile_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `com.influora.domain.entity.CreatorBrief` with enums `com.influora.domain.enums.BriefSource {PASTED, PLATFORM}` and `BriefStatus {NEW, ANALYZED, DRAFTED, SECURED, DISMISSED}`. Factory `static CreatorBrief paste(String id, String creatorProfileId, String rawText)` (sanitize with `TextSanitizer.sanitizePlainText`, cap 8000 chars, status NEW). Mutators `applyAnalysis(String brandNameGuess, String extractedJson, String riskFlagsJson, String quoteJson, String extractionSource)` sets ANALYZED; `markDrafted()`; `markSecured(String collaborationId)`; `dismiss()`.

Repository `CreatorBriefRepository extends JpaRepository<CreatorBrief, String>`: `List<CreatorBrief> findByCreatorProfileIdOrderByCreatedAtDesc(String creatorProfileId, Pageable pageable)`, `Optional<CreatorBrief> findByIdAndCreatorProfileId(String id, String creatorProfileId)`.

### 2.3 Migration `V20260910100200__creator_secure_links.sql` (create)

```sql
CREATE TABLE creator_secure_links (
    id                   VARCHAR(26)  NOT NULL,
    token_hash           VARCHAR(64)  NOT NULL,               -- SHA-256 of the URL token, never the token; VARCHAR not CHAR (see 2.3 note)
    creator_profile_id   VARCHAR(26)  NOT NULL,
    brief_id             VARCHAR(26)  NOT NULL,
    package_json         TEXT         NOT NULL,               -- frozen PackageQuote shown to the brand (no floors)
    deal_terms_json      TEXT         NOT NULL,               -- DealTermsDto JSON
    end_brand_name       VARCHAR(200) NOT NULL,
    status               VARCHAR(16)  NOT NULL,               -- ACTIVE | REDEEMED | EXPIRED | REVOKED
    redeemed_workspace_id VARCHAR(26) NULL,
    redeemed_collaboration_id VARCHAR(26) NULL,
    expires_at           TIMESTAMP    NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    redeemed_at          TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_creator_secure_links_token (token_hash),
    CONSTRAINT fk_csl_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    CONSTRAINT fk_csl_brief FOREIGN KEY (brief_id) REFERENCES creator_briefs(id) ON DELETE CASCADE,
    INDEX idx_csl_creator_status (creator_profile_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `CreatorSecureLink`, enum `SecureLinkStatus {ACTIVE, REDEEMED, EXPIRED, REVOKED}`. Token: 32 random bytes, base64url, hashed with SHA-256 (reuse `JwtService.hashToken(String raw)` — verified `public static`, `security/JwtService.java` L61). TTL 30 days. Repository: `Optional<CreatorSecureLink> findByTokenHash(String tokenHash)`, `List<CreatorSecureLink> findByCreatorProfileIdOrderByCreatedAtDesc(String)`.

<!-- PRIYA: `token_hash` was CHAR(64) — changed to VARCHAR(64). ddl-auto=validate boots against `@Column(name="token_hash", nullable=false, length=64) private String tokenHash;` and rejects a char column outright. This is the exact class of boot break V20260718150000 was written to repair. -->
**`token_hash` is `VARCHAR(64)`, never `CHAR(64)`** — Hibernate maps `@Column(length = 64)` to `VARCHAR(64)` and `ddl-auto=validate` fails with "wrong column type ... found [char]" on a `CHAR` column. Precedent: `workspace_member_invites.invite_token_hash VARCHAR(64)`.

### 2.4 Migration `V20260910100300__meera_drafts.sql` (create)

```sql
CREATE TABLE meera_drafts (
    id                   VARCHAR(26)  NOT NULL,
    creator_profile_id   VARCHAR(26)  NOT NULL,
    conversation_id      VARCHAR(26)  NULL,                   -- ai_conversations.id that produced it
    collaboration_id     VARCHAR(26)  NULL,                   -- deal thread target, if any
    brief_id             VARCHAR(26)  NULL,
    campaign_id          VARCHAR(26)  NULL,                   -- for application drafts (B7)
    kind                 VARCHAR(24)  NOT NULL,               -- REPLY | COUNTER | DECLINE | APPLICATION | ROUTINE
    intent               VARCHAR(32)  NULL,                   -- RoutineIntent name when kind = ROUTINE
    text                 TEXT         NOT NULL,
    proposed_amount      DECIMAL(12,2) NULL,
    deal_terms_json      TEXT         NULL,
    status               VARCHAR(16)  NOT NULL,               -- PENDING | APPROVED | EDITED | DISCARDED | SENT
    approved_at          TIMESTAMP    NULL,
    sent_message_id      VARCHAR(26)  NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_meera_drafts_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_meera_drafts_creator_created (creator_profile_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `MeeraDraft`, enums `DraftKind {REPLY, COUNTER, DECLINE, APPLICATION, ROUTINE}`, `DraftStatus {PENDING, APPROVED, EDITED, DISCARDED, SENT}`. Repository: `findByIdAndCreatorProfileId`, `findByCreatorProfileIdAndStatusOrderByCreatedAtDesc(String, DraftStatus)`.

### 2.5 Migration `V20260910100400__meera_send_log.sql` (create)

```sql
CREATE TABLE meera_send_log (
    id                   VARCHAR(26)  NOT NULL,
    creator_profile_id   VARCHAR(26)  NOT NULL,
    collaboration_id     VARCHAR(26)  NOT NULL,
    draft_id             VARCHAR(26)  NULL,
    intent               VARCHAR(32)  NOT NULL,               -- RoutineIntent
    text                 TEXT         NOT NULL,
    approval_level       INT          NOT NULL,               -- level in force when queued
    status               VARCHAR(16)  NOT NULL,               -- QUEUED | CANCELLED | SENT | FAILED
    send_at              TIMESTAMP    NOT NULL,               -- queued_at + 60s
    sent_message_id      VARCHAR(26)  NULL,
    failure_code         VARCHAR(64)  NULL,
    queued_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_meera_send_log_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_meera_send_log_due (status, send_at),
    INDEX idx_meera_send_log_creator (creator_profile_id, queued_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `MeeraSendLog`, enum `SendLogStatus {QUEUED, CANCELLED, SENT, FAILED}`. Repository: `List<MeeraSendLog> findByStatusAndSendAtBefore(SendLogStatus status, Instant before)`, `findByIdAndCreatorProfileId`, `findByCreatorProfileIdOrderByQueuedAtDesc(String, Pageable)`, `long countByCollaborationIdAndStatusIn(String, Collection<SendLogStatus>)`.

### 2.6 Migration `V20260910100500__deal_offer_history.sql` (create)

```sql
CREATE TABLE deal_offer_history (
    id                   VARCHAR(26)  NOT NULL,
    collaboration_id     VARCHAR(26)  NOT NULL,
    sequence_no          INT          NOT NULL,
    actor                VARCHAR(16)  NOT NULL,               -- BRAND | CREATOR | SYSTEM
    event                VARCHAR(24)  NOT NULL,               -- OFFER | COUNTER | MEERA_COUNTER | ACCEPT | REJECT
    amount               DECIMAL(12,2) NULL,
    currency             VARCHAR(3)   NOT NULL DEFAULT 'INR',
    meera_drafted        TINYINT(1)   NOT NULL DEFAULT 0,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_doh_collab FOREIGN KEY (collaboration_id) REFERENCES collaborations(id) ON DELETE CASCADE,
    INDEX idx_doh_collab_seq (collaboration_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `DealOfferHistory`, enums `OfferActor {BRAND, CREATOR, SYSTEM}`, `OfferEvent {OFFER, COUNTER, MEERA_COUNTER, ACCEPT, REJECT}`. Repository: `List<DealOfferHistory> findByCollaborationIdOrderBySequenceNoAsc(String)`, `int countByCollaborationId(String)`.

`DealService` writes rows in four places (all inside the existing transactions):
<!-- PRIYA: line numbers re-verified against HEAD. createProposal is L243 (spec said 242); doCounter L1268 and doAccept L1103 and doReject L436 are correct. `updateAgreedRate` inside doCounter is at L1299. -->
- `createProposal` (**L243**) after the collaboration is saved: `OFFER`, actor BRAND, `body.amount()`.
- `doCounter` (L1268) after `updateAgreedRate` (L1299): `COUNTER` (or `MEERA_COUNTER` when `body.meeraDraftId()` is non-null, see 3.4), actor from `senderType`.
- `doAccept` (L1103): `ACCEPT`, actor from role, amount = `collaboration.getAgreedRate()`.
- `doReject` (L436): `REJECT`.
Add a private helper `recordOffer(Collaboration c, OfferActor actor, OfferEvent event, BigDecimal amount, boolean meeraDrafted)` and inject `DealOfferHistoryRepository`.

### 2.7 Migration `V20260910100600__collaborations_calendar_hint.sql` (alter)

No new column. Delivered as a comment-only migration is not allowed by Flyway; skip this file. Calendar overload uses `Collaboration.appliedAt`, `Campaign.startDate` and `endDate` already present.

### 2.8 `DealMessage` metadata convention (no schema change)

<!-- AMEND-0904: §14.3 changes WHAT is stripped for brands. `agent` and a new `auto_sent` key are now KEPT for brand viewers; only `send_log_id`, `draft_id`, `intent`, `edited` are stripped. The "brand-stripped shape" below means that narrower strip. Also PRIYA-COMPAT-0904 BROKEN-B1: there are nine toMessageResponse call sites, not two — implement the strip as a separate helper applied at listMessages / sendMessage / publishToStream, not as a signature change. -->

Meera-sent or Meera-drafted messages carry `metadataJson` keys:

```json
{"agent": "meera", "intent": "ACK_BRIEF", "send_log_id": "01J...", "draft_id": "01J...", "edited": false}
```

<!-- PRIYA: `toDealMessageResponse` does not exist. The real private mapper is `toMessageResponse`, and it takes only the DealMessage — it has no principal in scope, so the strip cannot be written as described. Line numbers were also off by one. -->
The private mapper is **`DealService.toMessageResponse(DealMessage)`**, called from `listMessages` (**L611**, inside the loop; method at L602) and `sendMessage` (**L664**; method at L619). There is no `toDealMessageResponse`.

`toMessageResponse` **has no `principal` parameter**, so the strip cannot be done inside it as written. Change its signature to `toMessageResponse(DealMessage message, boolean isBrandViewer)` and update both call sites (`principal.getUserType() == UserType.BRAND`) plus **`seedNotesMessage`** (the second `new DealMessageResponse(...)` site, L2181/L2194 — one of the two is the mapper, the other the seed row; seed rows carry no metadata, so pass `false`). Also update `publishToStream` (L~666): the SSE fan-out reuses the same DTO and would otherwise push unstripped metadata to a brand's open stream. Publish the **brand-stripped** shape to the stream and let the creator's client refetch, or publish per-subscriber; the simplest correct choice for Phase B is to strip on the stream too and have the creator's `DraftCard` carry the ids it already holds locally.

<!-- PRIYA: SUPERSEDED by §14.3.a and contradicts the AMEND pointer at the top of this section, which the product pass left standing. Corrected: strip `intent`, `send_log_id`, `draft_id`, `edited` for a brand viewer; KEEP `agent` and the new `auto_sent`. Recommendation 4 (no brand-facing stamp) is NO LONGER the default (§12 item 1) — the flip is the property `influora.meera.brand-facing-stamp=false`, not the removal of the strip. Add `auto_sent` to the metadata JSON example above; it is written on the draft-approve path in B0 as `false`. -->
Strip `agent`, `intent`, `send_log_id`, `draft_id`, `edited` from `metadata` when the viewer is a brand. Creators see them. Recommendation 4 in the plan (no brand-facing stamp) is implemented this way and can be flipped by removing the strip.

### 2.9 Holdout assignment (B6)

<!-- PRIYA: WRONG SITE. `getOrCreatePreferences` (L77) does not call `newWithDefaults` — it calls the private `createWithComputedDefaults(profile)` (L103-110), which is ALSO called from `updatePreferences` (L154), `recordConsent` (L227) and `adminSetMonthlyCapOverride` (L279). In the real flow the row is usually created by `recordConsent` (consent precedes the first prefs read), so putting the assignment in `getOrCreatePreferences` would leave most Phase-B creators with holdout=false. Put it in `createWithComputedDefaults`, before the save. -->
In `CreatorAgentPreferencesService.createWithComputedDefaults` (**L103-110** — the single private factory; `getOrCreatePreferences` at L77 does *not* call `newWithDefaults` directly, and three other methods — `updatePreferences` L154, `recordConsent` L227, `adminSetMonthlyCapOverride` L279 — also create the row through it), between `newWithDefaults(...)` and `preferencesRepository.save(prefs)`:

```java
CreatorAgentPreferences prefs =
        CreatorAgentPreferences.newWithDefaults(
                Ulids.newUlid(), profile.getId(), floor, floor, floor, language);
boolean holdout = Math.floorMod(profile.getId().hashCode(), 5) == 0;      // deterministic 20 percent
prefs.assignHoldout(holdout, holdout ? LocalDate.now(ZoneOffset.UTC).plusDays(90) : null);
return preferencesRepository.save(prefs);
```

`String.hashCode()` is contractually specified by the JLS, so this is stable across JVMs and restarts.

Holdout means: `estimate_my_rate` and `check_deal_risks` still work, but `draft_reply` of kind COUNTER and the `PackageQuote.anchor` are withheld (the tool returns `{"withheld": true, "reason": "holdout"}`) and Meera says she cannot coach on price for this deal yet. Existing rows (created in Phase A) get `negotiation_holdout = 0` from the migration default, which is correct: the holdout applies only to creators onboarded from Phase B on. Expose `negotiation_holdout` on the creator context (3.6) so Python can render the rule.

### 2.10 `CreatorContextResponse` additions (3.6) and drift test

<!-- PRIYA: this section said FOUR new components (27 -> 31), but 7.2 separately adds `holdout_until` as "component 32" and the build-order table on day 1 says "+5". Reconciled to FIVE here so there is one number. Record is at L166-215 and has exactly 27 components today; the only construction site in main is MeeraContextService L274-301. -->
Append **five** components to `MeeraContextDtos.CreatorContextResponse` (**L166-215**, 27 components today) after `ai_monthly_cap_usd`:

| # | `@JsonProperty` | Java type | source |
|---|---|---|---|
| 28 | `negotiation_holdout` | `boolean` | `prefs.isNegotiationHoldout()` |
| 29 | `holdout_until` | `String` | `Rendered.date(prefs.getHoldoutUntil(), locale)`, null when not held out (record is `@JsonInclude(NON_NULL)`) |
| 30 | `rate_card_shareable` | `boolean` | `prefs.isRateCardShareable()` |
| 31 | `approved_draft_count` | `int` | `prefs.getApprovedDraftCount()` |
| 32 | `tools_enabled` | `List<String>` | `CreatorToolScopes.toolNamesForLevel(level, represented, holdout)` (3.3) |

Update the positional constructor call in `MeeraContextService.assembleCreatorContext` **L274-301** (27 args become 32). That is the **only** construction site in `main`; no test constructs the record (`MeeraContextServiceTest` and `InfoBarrierRuntimeTest` only cast and read it), so no test needs a positional update.

Python side: add the five snake_case names to `CREATOR_CONTEXT_PAYLOAD_FIELDS` (`assembler.py` L122-165, a 27-entry alphabetically sorted `tuple[str, ...]`) **in sorted position**, render `negotiation_holdout`, `holdout_until` and `tools_enabled` in `build_block_b_creator`, and add `rate_card_shareable` + `approved_draft_count` to `CREATOR_CONTEXT_FIELDS_NOT_RENDERED` (`assembler.py` L169-171).

<!-- PRIYA: resolved the drift-test coupling by reading tests/prompt/test_creator_context_drift.py. It is stricter than the spec implied — four separate assertions break, not one, and it parses the real Java file. -->
**`tests/prompt/test_creator_context_drift.py` breaks in four places, not one.** It parses the real `MeeraContextDtos.java` and `pytest.fail`s (never skips) if the file is missing, so the Java record and the Python tuple **must land in the same commit**:
1. **L67** `test_creator_context_payload_fields_match_the_java_record_exactly` — exact set equality against the Java `@JsonProperty` names, failing in both directions.
2. **L82** — asserts `list(CREATOR_CONTEXT_PAYLOAD_FIELDS) == sorted(set(...))`; insert the five names alphabetically.
3. **L87** `test_every_allow_listed_field_is_actually_read_by_the_creator_block_builder` — for every allow-listed name not in `CREATOR_CONTEXT_FIELDS_NOT_RENDERED`, the regex `ctx(?:\.get\(|, )['"]<name>['"]` must match somewhere in `assembler.py`. **The local variable must literally be named `ctx`** (`build_block_b_creator`'s parameter is `context`; it is narrowed to `ctx` at L557). Write the new renders as `ctx.get("negotiation_holdout")` / `_creator_str(ctx, "holdout_until")`, not `context.get(...)`.
4. **L103** `test_every_java_field_changes_the_rendered_creator_block` — each new Java field needs a `distinctive` value (local dict, L119-143) **and** a render line that measurably changes Block B output, plus a `_PREREQUISITES` entry (L157-159) if its render is conditional. `holdout_until` renders only when `negotiation_holdout` is true → it needs `_PREREQUISITES["holdout_until"] = {"negotiation_holdout": True}`.

Note `_NOT_RENDERED_BY_DESIGN` in that test is `assembler.CREATOR_CONTEXT_FIELDS_NOT_RENDERED` itself (L44), so adding to the assembler constant feeds the test automatically.

**CI:** `.github/workflows/schema-check.yml` triggers on both `MeeraContextDtos.java` and `assembler.py`, but its blocking context-field diff extracts only the **brand** record (`awk '/public record ContextResponse/,/\{\}\)/'` — that pattern does not match `public record CreatorContextResponse`) and compares it against `CONTEXT_PAYLOAD_FIELDS`, not the creator tuple. Adding creator fields does not move that gate. Its other blocking invariant compares `[t['name'] for t in TOOL_SCHEMAS]` to the `MeeraToolName` enum — see 3.2 and 7.1, both of which leave those two sets untouched.

### 2.11 `BriefExtraction` JSON (shared contract, both services)

```json
{
  "brand_name": "Glow Cosmetics",
  "product": "Vitamin C serum",
  "category": "BEAUTY",
  "deliverables": [{"type": "REEL", "qty": 1}, {"type": "STORY_SET", "qty": 1}],
  "budget_inr": 8000,
  "budget_stated": true,
  "barter_only": false,
  "barter_mrp_inr": null,
  "deadline": "2026-10-05",
  "usage_months": null,
  "usage_perpetual": false,
  "usage_channels": ["ORGANIC"],
  "exclusivity_days": 60,
  "exclusivity_scope": "CATEGORY",
  "exclusivity_brands": [],
  "max_revisions": null,
  "payment_terms": "50% advance",
  "off_platform_payment_hint": false,
  "disclosure_hidden_hint": false,
  "claims": ["clinically proven"],
  "regulated_category": null,
  "vague_deliverables": false,
  "summary_lines": ["Glow Cosmetics wants 1 reel + 1 story set for a Vitamin C serum", "Budget stated: 8,000", "Deadline 5 Oct", "60 days category exclusivity", "Organic usage only"]
}
```

`deliverables[].type` is one of `REEL, STATIC_POST, STORY_SET, SHORT, YT_INTEGRATION, YT_DEDICATED, UGC_ONLY, OTHER` (the deliverable taxonomy, 4.1). `usage_channels`, `exclusivity_scope` use the existing `UsageChannel` and `ExclusivityScope` enum names. `category` uses the `RateEstimationService.CATEGORY_MULTIPLIERS` keys. `regulated_category` is one of `FINANCE, HEALTH, RMG, CRYPTO, ALCOHOL, TOBACCO` or null. The Java record `BriefExtraction` lives in `com.influora.web.dto.brief.BriefDtos` with snake_case `@JsonProperty` on every component and `@JsonInclude(NON_NULL)`.

---

## 3. Backend: Meera creator tools (B2, B5, B7)

### 3.1 Tool catalogue

| Tool name | Tier | Spring route | Executor | Purpose |
|---|---|---|---|---|
| `get_my_deals` | R | `POST /internal/meera/creator/get_my_deals` | `GetMyDealsExecutor` | active and recent deals with status, amounts, next action |
| `get_brief` | R | `POST /internal/meera/creator/get_brief` | `GetBriefExecutor` | one brief (pasted or platform deal) with extraction, risks, quote |
| `estimate_my_rate` | R | `POST /internal/meera/creator/estimate_my_rate` | `EstimateMyRateExecutor` | package quote for a deliverable list, with provenance |
| `get_my_metrics` | R | `POST /internal/meera/creator/get_my_metrics` | `GetMyMetricsExecutor` | latest verified metrics, rendered strings |
| `check_deal_risks` | R | `POST /internal/meera/creator/check_deal_risks` | `CheckDealRisksExecutor` | reason codes for a deal or brief |
| `draft_reply` | D | `POST /internal/meera/creator/draft_reply` | `DraftReplyExecutor` | persists a `MeeraDraft`, returns `draft_id` and text back for the creator's tap |
| `send_routine_reply` | C | `POST /internal/meera/creator/send_routine_reply` | `SendRoutineReplyExecutor` | level 1 only: queues a `MeeraSendLog` row with a 60-second cancel window |
| `rank_open_campaigns` | R | `POST /internal/meera/creator/rank_open_campaigns` | `RankOpenCampaignsExecutor` | top 5 open campaigns by fit |
| `draft_application` | D | `POST /internal/meera/creator/draft_application` | `DraftApplicationExecutor` | persists an APPLICATION draft for one campaign |

"C" here means commit-like (sends to a brand). It is not a money tool. Money tools stay excluded from every creator scope.

### 3.2 New enum and validator (do not touch `MeeraToolName`)

`com.influora.domain.enums.CreatorToolName`:

```java
public enum CreatorToolName {
    get_my_deals, get_brief, estimate_my_rate, get_my_metrics, check_deal_risks,
    draft_reply, send_routine_reply, rank_open_campaigns, draft_application;

    public static Optional<CreatorToolName> parse(String raw) { ... }   // exact match on name()
}
```

`com.influora.service.meera.tool.creator.CreatorToolCallValidator` (`@Service`), mirroring `ToolCallValidator` (facts/backend.md 2.10):

```java
public CreatorToolName validateAndResolve(String rawToolName, String creatorUserId)  // UNKNOWN_TOOL_NAME
public MeeraToolTier tierOf(CreatorToolName tool)                                     // R for the five reads and rank; D for the two drafts; C for send_routine_reply
```

<!-- PRIYA: recordToolCall's 3rd parameter is a String, not MeeraToolTier — passing the enum will not compile. Signature verified at AuditLogService.java L44-52: (String workspaceId, String toolName, String toolTier, String outcome, String reasonCode, String idempotencyKey, BigDecimal serverAmount, Map<String,Object> detail). -->
Rejections write `auditLogService.recordToolCall(creatorUserId, tool.name(), tier.name(), AuditLogService.OUTCOME_REJECTED, reasonCode, null, null, Map.of())` and throw `ToolCallValidator.ToolCallRejectedException` (reuse the existing exception class, `ToolCallValidator.java` L53).

**`recordToolCall` takes Strings, not enums** — `AuditLogService.recordToolCall(String workspaceId, String toolName, String toolTier, String outcome, String reasonCode, String idempotencyKey, BigDecimal serverAmount, Map<String,Object> detail)` (L44-52). Call `.name()` on both the tool and the tier.

`MeeraToolName` and its 6 values are **not touched**: `ToolCallValidatorTest` L149 asserts `assertEquals(6, MeeraToolName.values().length, ...)`, and `.github/workflows/schema-check.yml` blocks on `MeeraToolName` ↔ `TOOL_SCHEMAS` name-set equality. `CreatorToolName` is a separate enum in the same package; neither gate sees it.

### 3.3 Per-level on-behalf scope

New class `com.influora.service.meera.CreatorToolScopes` (pure static):

```java
public static final String SCOPE_LEVEL_0 = "get_my_deals get_brief estimate_my_rate get_my_metrics check_deal_risks draft_reply rank_open_campaigns draft_application";
public static final String SCOPE_LEVEL_1 = SCOPE_LEVEL_0 + " send_routine_reply";
public static final String SCOPE_LEVEL_2 = SCOPE_LEVEL_1;            // level 2 adds no tool; auto-decline is a server job in Phase E
public static final String SCOPE_REPRESENTED = "get_my_deals get_brief estimate_my_rate get_my_metrics check_deal_risks rank_open_campaigns";  // warn-only: no drafts, no sends

public static String scopeFor(int approvalLevel, boolean represented)
public static List<String> toolNamesForLevel(int approvalLevel, boolean represented, boolean holdout)  // holdout removes nothing from scope; the executor withholds
```

`OnBehalfTokenService` (`influora-api/src/main/java/com/influora/service/meera/OnBehalfTokenService.java`): add an overload that keeps the existing five-arg `mint` intact:

```java
public String mint(String workspaceId, String conversationId, String turnId, String userId, UserType userType, String scope)
```

The existing five-arg `mint` (L82) delegates with `SCOPE_DEFAULT` (L68-69). Verified: today's five-arg body hardcodes `.claim("scope", SCOPE_DEFAULT)` at L105, so the overload is a clean extraction.

<!-- PRIYA: doSendTurn is L309 (not 308) and the mint call is L397 (not 396); isCreatorTurn is L332. -->
`MeeraSessionService.doSendTurn` (**L309**; `isCreatorTurn` at L332): for `isCreatorTurn`, resolve the scope before the mint at **L397**:

```java
PreferencesResponse prefs = creatorAgentPreferencesService.getOrCreatePreferences(userId);
String scope = CreatorToolScopes.scopeFor(prefs.approvalLevel(), prefs.represented());
String onBehalfToken = onBehalfTokenService.mint(workspaceId, conversationId, messageId, userId, userType, scope);
```

<!-- PRIYA: resolved. MeeraSessionService's ctor has 10 params (L102-112) and there is exactly ONE construction site in tests: MeeraSessionServiceTest:81. CreatorMeeraControllerTest constructs CreatorMeeraController (L70) and mocks MeeraSessionService — it needs no change unless the CONTROLLER ctor changes, which it does not. -->
Inject `CreatorAgentPreferencesService` into `MeeraSessionService` (ctor at **L102-112** grows from 10 to 11 params). **The only construction site is `MeeraSessionServiceTest:81`** — add one mock there. `CreatorMeeraControllerTest` constructs `CreatorMeeraController`, not `MeeraSessionService`, and needs no change. `MeeraSessionService` is under `service/meera` and imports the **service**, not the repository, so `InfoBarrierTest` stays green.

`OnBehalfAuthResolver.requireScope` (L178) already checks the scope list; no change. The class lives at **`com.influora.security.OnBehalfAuthResolver`**, not under `service/meera`. `resolveForWorkspaceRequiringScope(onBehalfJwt, bodyWorkspaceId, requiredTool)` is L150 and `OnBehalfContext(userId, workspaceId, userType, conversationId)` is L78; `resolveForWorkspace` (L86) is audience-agnostic — it only equality-checks the token's `workspaceId` claim against the body, so a CREATOR turn carrying the creator's `users.id` as `workspace_id` resolves correctly with no change.

### 3.4 Controller: `CreatorMeeraToolController`

New file `influora-api/src/main/java/com/influora/web/CreatorMeeraToolController.java`, `@RestController @RequestMapping("/internal/meera/creator")`. It sits under `/internal/**`, so `InternalServiceTokenFilter` authenticates the Python service and the on-behalf JWT authenticates the creator. Structure copies `MeeraInternalController` (facts/backend.md 3.3):

```java
private static final String ON_BEHALF_HEADER = "X-Onbehalf-Authorization";
private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

@PostMapping("/get_my_deals")          public ResponseEntity<ApiResponse<GetMyDealsResult>> getMyDeals(@RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body)
@PostMapping("/get_brief")             ... GetBriefResult
@PostMapping("/estimate_my_rate")      ... EstimateMyRateResult
@PostMapping("/get_my_metrics")        ... GetMyMetricsResult
@PostMapping("/check_deal_risks")      ... CheckDealRisksResult
@PostMapping("/draft_reply")           ... DraftReplyResult        (201)
@PostMapping("/send_routine_reply")    ... SendRoutineReplyResult  (requires IDEMPOTENCY_HEADER)
@PostMapping("/rank_open_campaigns")   ... RankOpenCampaignsResult
@PostMapping("/draft_application")     ... DraftApplicationResult  (201)
```

Each handler:
1. `String workspaceId = requireWorkspaceId(body);` (snake_case `workspace_id`, 400 `WORKSPACE_ID_REQUIRED`).
2. `OnBehalfContext ctx = onBehalfAuthResolver.resolveForWorkspaceRequiringScope(onBehalfJwt, workspaceId, "<tool>");`
3. `requireCreatorPrincipal(ctx)`: `ctx.userType() != UserType.CREATOR` → 403 `AUDIENCE_PRINCIPAL_MISMATCH`.
4. `requireConsentByUserId(ctx.userId())`: `creatorAgentPreferencesService.isConsentAccepted(userId)` false → 403 `CONSENT_REQUIRED`.
5. `creatorToolCallValidator.validateAndResolve("<tool>", ctx.userId())`.
6. Delegate to the executor with `(ctx.userId(), ctx.conversationId(), body)` (plus `idempotencyKey` for `send_routine_reply`).
7. `auditLogService.recordToolCall(ctx.userId(), tool.name(), tier.name(), AuditLogService.OUTCOME_ALLOWED, null, null, null, Map.of())` on success.
   <!-- PRIYA: `OUTCOME_OK` does not exist. AuditLogService declares OUTCOME_ALLOWED (L32), OUTCOME_REJECTED (L33), OUTCOME_FAILED (L34) only. Also tool/tier must be .name() Strings — see 3.2. -->
   **There is no `AuditLogService.OUTCOME_OK`** — the constants are `OUTCOME_ALLOWED` (L32), `OUTCOME_REJECTED` (L33), `OUTCOME_FAILED` (L34).

<!-- PRIYA: requireFeatureEnabled is a PRIVATE method on CreatorMeeraController (L104-109); it is not inheritable or callable from a new controller. Copy the 5-line helper and inject MeeraCreatorFeatureProperties. -->
Feature flag: `CreatorMeeraController.requireFeatureEnabled()` is **private** (L104-109) and cannot be reused. Inject `MeeraCreatorFeatureProperties` (a `@Component` with `isCreatorEnabled()`) and copy the five-line helper verbatim into the new controller; call it first in every handler, before the principal/consent checks, so a disabled feature 404s uniformly.

<!-- PRIYA: the IP key is a real defect, not a nit. Python calls these routes server-to-server from ONE process, so an IP-keyed 60/window bucket is a platform-wide cap shared by every creator — the first busy creator starves the rest. -->
Rate limiting (`AuthRateLimitFilter`): add pattern `^/internal/meera/creator/[^/]+$`, POST only, property `influora.meera.creator-tool-rate-limit-per-window` (matches the existing `influora.meera.turn-rate-limit-per-window:20` naming). Five edits per facts/backend.md 5.3.

**Do not key this bucket on IP.** Every call arrives server-to-server from the single influora-ai process, so one IP-keyed bucket is a *platform-wide* cap that the busiest creator starves for everyone. Either (a) key on the on-behalf JWT's `sub` — the filter can read the `X-Onbehalf-Authorization` header and take the JWT's subject without verifying it, since verification happens downstream and the key only needs to be *stable*, not *trusted* — or (b) drop the filter-level bucket entirely and cap per-creator in `CreatorToolCallValidator` (which already has `creatorUserId`) alongside the audit write. **(a) is the default.** The real per-turn ceiling is already enforced upstream by the `meera-turn` bucket on `^(/creator)?/meera/sessions/[^/]+/messages$` (L106) plus the AI spend gate; this bucket is defence-in-depth, so 60/window per creator is right and 60/window per platform is not.

### 3.5 Result DTOs: `com.influora.web.dto.meera.CreatorToolDtos`

`public final class CreatorToolDtos`, snake_case `@JsonProperty` on every component (creator payloads follow `MeeraContextDtos`, not `MeeraToolDtos`), `@JsonInclude(NON_NULL)` on every record. Numbers that the model will read are formatted strings; numbers the frontend needs are numeric siblings suffixed `_value`.

```java
public record DealSummary(
    @JsonProperty("deal_id") String dealId,
    @JsonProperty("brand_name") String brandName,
    @JsonProperty("campaign_title") String campaignTitle,
    @JsonProperty("status") String status,                       // CollaborationStatus name
    @JsonProperty("status_label") String statusLabel,            // human label
    @JsonProperty("amount") String amount,                       // "8,000" or null
    @JsonProperty("amount_value") BigDecimal amountValue,
    @JsonProperty("currency") String currency,
    @JsonProperty("next_action") String nextAction,              // "reply to brand" | "accept or counter" | "deliver by 5 Oct" | "waiting for brand" | "funds secured, start work" | null
    @JsonProperty("next_deadline") String nextDeadline,          // "5 Oct 2026" or null
    @JsonProperty("secured") boolean secured,                    // escrowFunded
    @JsonProperty("unread_count") int unreadCount,
    @JsonProperty("has_pending_offer") boolean hasPendingOffer,
    @JsonProperty("brief_id") String briefId) {}

public record GetMyDealsResult(@JsonProperty("deals") List<DealSummary> deals, @JsonProperty("active_count") int activeCount, @JsonProperty("completed_count") int completedCount) {}

public record QuoteLine(
    @JsonProperty("type") String type, @JsonProperty("qty") int qty,
    @JsonProperty("unit_price") String unitPrice, @JsonProperty("unit_price_value") BigDecimal unitPriceValue,
    @JsonProperty("line_total") String lineTotal, @JsonProperty("line_total_value") BigDecimal lineTotalValue,
    @JsonProperty("below_floor") boolean belowFloor) {}

public record AddOnLine(@JsonProperty("code") String code, @JsonProperty("label") String label, @JsonProperty("amount") String amount, @JsonProperty("amount_value") BigDecimal amountValue, @JsonProperty("basis") String basis) {}

public record PackageQuote(
    @JsonProperty("lines") List<QuoteLine> lines,
    @JsonProperty("add_ons") List<AddOnLine> addOns,
    @JsonProperty("bundle_discount") String bundleDiscount, @JsonProperty("bundle_discount_value") BigDecimal bundleDiscountValue,
    @JsonProperty("total") String total, @JsonProperty("total_value") BigDecimal totalValue,
    @JsonProperty("anchor") String anchor, @JsonProperty("anchor_value") BigDecimal anchorValue,          // null when holdout
    @JsonProperty("floor_total") String floorTotal, @JsonProperty("floor_total_value") BigDecimal floorTotalValue,   // creator-only, never on the secure link
    @JsonProperty("range_min") String rangeMin, @JsonProperty("range_max") String rangeMax,
    @JsonProperty("currency") String currency,
    @JsonProperty("payment_schedule") String paymentSchedule,       // "50% on securing funds, 50% on delivery"
    @JsonProperty("revision_rounds") int revisionRounds,
    @JsonProperty("provenance") String provenance,                  // "benchmark, not market data" | "your last N priced deals" | "N closed deals in your tier in 90 days"
    @JsonProperty("provenance_sample_size") int provenanceSampleSize,
    @JsonProperty("recommended_move") String recommendedMove,       // "SCOPE_DOWN" | "COUNTER_AT_FLOOR" | "ACCEPT" | "DECLINE" | null
    @JsonProperty("scope_down_offer") String scopeDownOffer,        // "1 reel + 1 story on product at 8,000; 3 reels is 18,000" or null
    @JsonProperty("withheld") boolean withheld, @JsonProperty("withheld_reason") String withheldReason) {}

public record EstimateMyRateResult(@JsonProperty("quote") PackageQuote quote) {}

public record MetricsResult(
    @JsonProperty("connected") boolean connected,
    @JsonProperty("followers") String followers, @JsonProperty("reach_30d") String reach30d,
    @JsonProperty("engagement_rate") String engagementRate, @JsonProperty("avg_reach_per_post") String avgReachPerPost,
    @JsonProperty("verified_at") String verifiedAt, @JsonProperty("data_source") String dataSource,
    @JsonProperty("tier") String tier, @JsonProperty("quality_score") String qualityScore) {}
public record GetMyMetricsResult(@JsonProperty("metrics") MetricsResult metrics) {}

public record RiskFlag(
    @JsonProperty("code") String code,                 // 5.2 codes
    @JsonProperty("severity") String severity,         // INFO | WARN | CRITICAL
    @JsonProperty("title") String title,
    @JsonProperty("detail") String detail,             // one sentence, numbers pre-rendered
    @JsonProperty("cost") String cost,                 // "≈ 6,000 of lost income" or null
    @JsonProperty("action") String action,             // what Meera drafts
    @JsonProperty("data") Map<String, String> data,    // rendered strings only
    @JsonProperty("dismissible") boolean dismissible) {}
public record CheckDealRisksResult(@JsonProperty("flags") List<RiskFlag> flags, @JsonProperty("highest_severity") String highestSeverity, @JsonProperty("target") String target, @JsonProperty("target_id") String targetId) {}

public record GetBriefResult(
    @JsonProperty("brief_id") String briefId, @JsonProperty("source") String source, @JsonProperty("status") String status,
    @JsonProperty("deal_id") String dealId,
    @JsonProperty("extraction") BriefDtos.BriefExtraction extraction,
    @JsonProperty("flags") List<RiskFlag> flags, @JsonProperty("quote") PackageQuote quote,
    @JsonProperty("extraction_source") String extractionSource) {}

public record DraftReplyResult(@JsonProperty("draft_id") String draftId, @JsonProperty("kind") String kind, @JsonProperty("text") String text, @JsonProperty("proposed_amount") String proposedAmount, @JsonProperty("proposed_amount_value") BigDecimal proposedAmountValue, @JsonProperty("deal_terms") DealDtos.DealTermsDto dealTerms, @JsonProperty("target_deal_id") String targetDealId, @JsonProperty("target_brief_id") String targetBriefId, @JsonProperty("withheld") boolean withheld, @JsonProperty("withheld_reason") String withheldReason) {}

public record SendRoutineReplyResult(@JsonProperty("send_log_id") String sendLogId, @JsonProperty("status") String status, @JsonProperty("send_at") String sendAt, @JsonProperty("cancel_window_seconds") int cancelWindowSeconds, @JsonProperty("refused_code") String refusedCode, @JsonProperty("refused_reason") String refusedReason) {}

public record CampaignFit(@JsonProperty("campaign_id") String campaignId, @JsonProperty("title") String title, @JsonProperty("brand_name") String brandName, @JsonProperty("budget_band") String budgetBand, @JsonProperty("fit_score") int fitScore, @JsonProperty("fit_reasons") List<String> fitReasons, @JsonProperty("application_deadline") String applicationDeadline, @JsonProperty("already_applied") boolean alreadyApplied, @JsonProperty("below_floor") boolean belowFloor) {}
public record RankOpenCampaignsResult(@JsonProperty("campaigns") List<CampaignFit> campaigns) {}
public record DraftApplicationResult(@JsonProperty("draft_id") String draftId, @JsonProperty("campaign_id") String campaignId, @JsonProperty("text") String text) {}
```

Note `RiskFlag.cost` uses the "≈" character, which is fine for UTF-8 JSON. Do not use "→" or emoji.

### 3.6 Executors (`com.influora.service.meera.tool.creator`)

All `@Service`, all `@Transactional(readOnly = true)` unless stated. Constructor injection only. Inputs come as `Map<String, Object>` with snake_case keys, read through a small `ToolInput` helper (`string(key)`, `optionalString`, `intOr(key, default)`, `list(key)`). Every executor resolves the profile once — **reuse the existing public method `creatorAgentPreferencesService.requireCreatorProfile(creatorUserId)` (L67-74)**, which already throws the exact `ApiException("CREATOR_PROFILE_NOT_FOUND", ..., NOT_FOUND)`; do not re-implement it and do not inject `CreatorProfileRepository` into every executor.

Locale: `Locale.forLanguageTag(prefs.creatorLanguage())` with a fallback to `Locale.forLanguageTag("en-IN")`. Formatting helper `Rendered.money(BigDecimal, Locale)` → grouping, no fraction, and `Rendered.date(LocalDate|Instant, Locale)` → `"5 Oct 2026"`.

<!-- PRIYA: unresolved id-space break in the original. RateQuoteService.floorTotal, DealRiskService.evaluateDeal/evaluateBrief all take a creatorProfileId, but the ONLY way to read prefs through the service is getOrCreatePreferences(userId) — there is no profileId-keyed accessor (adminSetMonthlyCapOverride is admin-only, takes profileId, and returns a BigDecimal cap, not prefs). Without this the new services either cannot compile or reach for the repository and break the info barrier. -->
**Id-space fix (blocking, do this on day 1).** `CreatorAgentPreferencesService` exposes prefs **only** by `users.id` (`getOrCreatePreferences(String userId)` L77, `isConsentAccepted(String userId)` L95). Several Phase-B signatures below take a `creator_profiles.id` (`RateQuoteService.floorTotal`, `DealRiskService.evaluateDeal` / `evaluateBrief`, `CreatorBriefService.ensurePlatformBrief`). Add one method to `CreatorAgentPreferencesService`:

```java
@Transactional(readOnly = true)
public PreferencesResponse getByProfileId(String creatorProfileId)   // 404 CREATOR_PROFILE_NOT_FOUND; creates the row with computed defaults if absent, same as getOrCreatePreferences
```

Every new class outside `CreatorAgentPreferencesService`/`MeeraContextService` reads floors and preferences through this or `getOrCreatePreferences` and **never** injects `CreatorAgentPreferencesRepository`. Note `InfoBarrierTest` only scans `service/meera/**` and `web/**` — `RateQuoteService` (`service/rates`), `DealRiskService` (`service/risk`), `CreatorBriefService`, `MediaKitService`, `CampaignFitService`, `RoutineReplyService` (`service/`) and `MeeraDelayedSendJob` (`job/`) are **outside the scanned tree**, so the barrier test will not catch a violation there. Either widen `ALLOWED_IMPORTERS`' scan roots to include `service/**` and `job/**` in this phase, or accept that rule 0.3 is enforced by review alone for those seven classes. **Widening the scan is the default** — one line in `InfoBarrierTest.candidateDirs`.

**Accessor names are Java camelCase.** `PreferencesResponse` is a record with accessors `reelFloor()`, `storySetFloor()`, `postFloor()`, `approvalLevel()`, `represented()`, `creatorLanguage()`, `excludedCategories()`, `blockedBrands()`, `weeklySponsoredLimit()` — the snake_case forms used in 4.3/5.2 (`prefs.reel_floor`, `prefs.negotiation_holdout()`) are the **wire** names, not callable Java.

**`GetMyDealsExecutor.execute(String creatorUserId, Map<String,Object> input)`**
- Input: `status` optional (`active|completed|all`, default `active`), `limit` default 10, max 25.
<!-- PRIYA: MeeraContextService.ACTIVE_DEAL_STATUSES is `private static final` (L93) and the executors sit in a DIFFERENT package (service.meera.tool.creator vs service.meera), so even package-private would not reach it. Promote it to a shared public constant. -->
- Query `collaborationRepository.findByCreatorId(creatorUserId)` (L101, keyed on `users.id` ✓) and filter by the active-status set for active, `COMPLETED` for completed. **`MeeraContextService.ACTIVE_DEAL_STATUSES` is `private static final` (L93) and the executors are in a different package** — move it to a new `public final class CreatorDealStatuses { public static final Set<CollaborationStatus> ACTIVE = ...; }` in `com.influora.domain.enums` (or make the existing field `public static final`) and have `MeeraContextService` L384 read the shared constant. Do not duplicate the set.
- For each: campaign via `campaignRepository.findById`, brand name via `workspaceRepository` and `brandProfileRepository` the way `DealService.resolveCounterparty` does (copy the lookup, do not call the private method). `nextAction` from status: INVITED or APPLIED → "accept or counter"; IN_NEGOTIATION with last message from brand → "reply to brand"; IN_NEGOTIATION with last message from creator → "waiting for brand"; CONTRACT_PENDING → "sign the contract"; CONTRACTED with `escrowFunded` → "funds secured, start work"; CONTRACTED without → "waiting for brand to secure funds"; IN_PROGRESS → "deliver by {date}"; REVIEW_PENDING → "waiting for brand review"; REVISION_REQUESTED → "revise and resubmit"; DISPUTED → "in dispute, Meera is in draft-only mode". <!-- PRIYA: WRONG FINDER — this would reintroduce CR-49. findByCollaborationIdAndStatus DOES exist (EscrowHoldRepository L157) but reads the DIRECT collaboration_id column, which is NULL on every ordinary brand-funded hold; DealService L2018-2026 documents this and deliberately uses the milestone-aware query instead. Using the direct finder makes `secured` silently false on genuinely funded deals. -->
`secured`: use **`escrowHoldRepository.hasEscrowForCollaboration(collaborationId, Set.of(EscrowStatus.FUNDED))`** (L153), the same milestone-aware query `DealService` uses to compute `escrowFunded` at **L2024-2026**. Do **not** use `findByCollaborationIdAndStatus` (L157) — it exists, but it reads the direct `collaboration_id` column, which is `NULL` on every ordinary brand-funded hold; CR-49/CR-50 fixed exactly this and the comment at L2019-2023 says so. Note `escrowFunded` is **not** a `Collaboration` column (L1478).

`briefId` = the PLATFORM `CreatorBrief` for this collaboration if one exists.
- Unread count: `DealService`'s computation is inline at **L2002-2007** (`dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(...)` filtered by `!parseReadBy(m.getReadByJson()).contains(userId)`). Extract it to a `public static int unreadCountFor(List<DealMessage>, String userId)` in `DealService` (`parseReadBy` must become static too, or move both to a small helper) and call it from L2002. **Package-visible is not enough** — the executor is in `com.influora.service.meera.tool.creator`, `DealService` is in `com.influora.service`.

**`GetBriefExecutor.execute(String creatorUserId, Map<String,Object> input)`**
- Input: exactly one of `brief_id` or `deal_id`. With `deal_id`: find or create the PLATFORM brief for that collaboration (`CreatorBriefService.ensurePlatformBrief(profileId, collaborationId)`, 3.8), which analyses it if not yet ANALYZED.
- Returns `GetBriefResult` with the stored extraction, flags, quote. Ownership: `findByIdAndCreatorProfileId`. A brief for a collaboration whose `creatorId != creatorUserId` → 404.

**`EstimateMyRateExecutor.execute(String creatorUserId, Map<String,Object> input)`**
- Input: `deliverables` list of `{type, qty}`, optional `add_ons` list of codes (4.2), optional `brand_budget_inr` number, optional `deal_id` or `brief_id` for context.
- Delegates to `RateQuoteService.quote(...)` (4.3). Returns `EstimateMyRateResult`.

**`GetMyMetricsExecutor.execute(String creatorUserId, Map<String,Object> input)`**
<!-- PRIYA: resolved both hand-waves by reading the code. The single caller of QualityScoreService.calculate is ScoreCalculationJob L291; recentMedia comes from MediaMetricsRepository L280-281. deriveTier is private static at MeeraContextService L415 — and note it returns "MEGA" for >=1M, a value CreatorTier does NOT have. -->
- `creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(profileId, PageRequest.of(0,1))` (repo L40).
- `qualityScoreService.calculate(latestMetric, recentMedia)` — signature verified: **`calculate(Optional<CreatorMetric> latestMetric, List<MediaMetric> recentMedia)`**, two args (`QualityScoreService` L58). `recentMedia` comes from **`mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(creatorProfileId, PageRequest.of(0, RECENT_MEDIA_LIMIT))`** — copied from the only caller, `ScoreCalculationJob` L280-281 / L291. Returns `QualityScoreResult.absent()` when either input is empty.
- Tier: `MeeraContextService.deriveTier` is **`private static` at L415** and its javadoc already flags it as a duplicate of `CreatorAgentBaselineService.deriveTier` — i.e. two copies exist today. Extract to `public static String CreatorTiers.derive(long followers)` in `com.influora.service.scoring` and make **all three** call sites use it. **`deriveTier` returns `"MEGA"` for followers ≥ 1,000,000, which is not a value of the `CreatorTier` enum (`NANO, MICRO, MID, MACRO`)** — never `CreatorTier.valueOf(tier)`, and any "MID or above" branch (4.3 step 7) must treat `MEGA` as above MID.
- `connected=false` when no metric row: every string field null except `tier` and `data_source = "SELF_REPORTED"` when `profile.getTotalFollowers() > 0`.

**`CheckDealRisksExecutor.execute(String creatorUserId, Map<String,Object> input)`**
- Input: one of `deal_id`, `brief_id`. Delegates to `DealRiskService.evaluateDeal(profileId, collaborationId)` or `evaluateBrief(profileId, briefId)` (5). Returns flags sorted CRITICAL, WARN, INFO.

**`DraftReplyExecutor.execute(String creatorUserId, String conversationId, Map<String,Object> input)`** — `@Transactional`
- Input: `kind` (`REPLY|COUNTER|DECLINE`), `text` (the model's draft, max 2000 chars), one of `deal_id`/`brief_id`, optional `proposed_amount` (number), optional `deal_terms` (DealTermsDto shape, camelCase keys as in `DealDtos.DealTermsDto`).
- Guards: represented → 403 `REPRESENTED_WARN_ONLY`; `kind == COUNTER && prefs.negotiationHoldout()` → return `withheld=true, withheldReason="holdout"` without persisting; `kind == COUNTER && proposed_amount < floorTotal` (from `RateQuoteService.floorTotal(profileId, deliverables)`) → 422 `BELOW_FLOOR_DRAFT` with message "below your floor; ask the creator to mark this deal strategic" unless `input.strategic_override == true` and `input.strategic_reason` is non-blank, in which case persist with `metadata.strategic_reason` and write an audit row `CREATOR_STRATEGIC_OVERRIDE`.
- Text hygiene: `TextSanitizer.sanitizePlainText`; reject if it contains the creator's floor values (compare against the three floors rendered with and without grouping) → 422 `FLOOR_LEAK_IN_DRAFT`. Reject if it contains the banned word.
- Persist `MeeraDraft` PENDING with `conversationId`, and mark the brief DRAFTED if any. Return `DraftReplyResult`.

**`SendRoutineReplyExecutor.execute(String creatorUserId, String conversationId, String idempotencyKey, Map<String,Object> input)`** — `@Transactional`
- Input: `deal_id`, `intent` (`RoutineIntent` name), `text`.
- `RoutineReplyService.queue(profile, collaborationId, intent, text, idempotencyKey)` (3.9). Returns `SendRoutineReplyResult`; refusals come back as `status="REFUSED"` with `refused_code` rather than an exception, so the model can say the fixed sentence.

**`RankOpenCampaignsExecutor.execute(String creatorUserId, Map<String,Object> input)`**
- Input: optional `limit` (default 5, max 10), optional `niche`.
- `CampaignFitService.rank(profile, prefs, limit)` (7.1).

**`DraftApplicationExecutor.execute(String creatorUserId, String conversationId, Map<String,Object> input)`** — `@Transactional`
- Input: `campaign_id`, `text` (max 2000). Verifies the campaign is ACTIVE, not private, deadline not passed, creator has not applied (`collaborationRepository.existsByCampaignIdAndCreatorId`). Persists `MeeraDraft` kind APPLICATION. Represented → 403.

### 3.7 Drafts and approvals: `CreatorMeeraDraftController`

`@RestController @RequestMapping("/creator/meera/drafts")`, creator JWT (under `/creator/**`, `hasRole("CREATOR")`), feature flag, consent.

| Route | Signature | Behaviour |
|---|---|---|
| `GET /creator/meera/drafts?status=PENDING` | `list(@AuthenticationPrincipal AuthPrincipal p, @RequestParam(defaultValue="PENDING") String status)` | `List<DraftItem>` |
| `POST /creator/meera/drafts/{id}/approve` | `approve(p, @PathVariable String id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ApproveDraftRequest body)` | body `{text, proposed_amount?, deal_terms?}`; `edited = !text.equals(draft.text)`; sends through the right path (below); sets SENT with `sentMessageId`; `prefs.recordApprovedDraft()`; returns `ApproveDraftResponse{draft_id, sent_message_id, deal_id, approved_draft_count, level_up_eligible}` |
| `POST /creator/meera/drafts/{id}/discard` | `discard(p, id)` | DISCARDED |

Send paths inside `approve`, all via existing public service methods so business rules stay in one place. **Every signature below was re-verified against HEAD:**

<!-- PRIYA: sendMessage takes THREE params, not four — there is no idempotencyKey. DealService.java L619-620: sendMessage(AuthPrincipal principal, String dealId, SendMessageRequest body). Passing `key` will not compile. -->
- **REPLY** → `dealService.sendMessage(principal, dealId, new SendMessageRequest(text, DealMessageKind.text))` — **three arguments, no idempotency key** (`DealService` L619-620). The method ignores `body.kind()` and hardcodes `DealMessageKind.text` internally (Kabir M-1), so the second constructor argument is inert but still required. Because there is no idempotency key on this path, `approve` must be idempotent itself: guard on `draft.status == PENDING` inside the transaction and return the stored `sentMessageId` on replay. Then patch metadata via a new `public void DealService.tagMessageMetadata(String messageId, Map<String,Object>)` (public, not package-visible — the caller is in `com.influora.web`).

<!-- PRIYA: "three existing construction sites ... so only tests" is wrong by an order of magnitude. There are FOURTEEN `new CounterRequest(...)` sites, all in two test files. Enumerated below so nobody discovers them at compile time. -->
- **COUNTER** → `dealService.counter(principal, dealId, new CounterRequest(amount, text, null, null, null, dealTerms, draftId), key)` — `counter(AuthPrincipal, String, CounterRequest, String idempotencyKey)` at L558-559, four args ✓. Adding the trailing `String meeraDraftId` component takes `CounterRequest` from 6 to 7 components and breaks **14 construction sites**, not three:
  - `DealServiceTest` — L605, L645, L708, L749, L768, L982, L1654, L2203, L2316 (**9**)
  - `DealControllerTest` — L142, L143, L153, L165, L284 (**5**)
  - `DealService` and `DealController` only *reference* the type (`DealController` L109 binds it from JSON); neither constructs one, so no main-source construction site exists.
  Frontend sends `meeraDraftId` optionally. `doCounter` records `MEERA_COUNTER` in offer history when non-null.

<!-- PRIYA: RejectRequest.reason is @Size(max = 500) but a DECLINE draft is allowed up to 2000 chars. Calling the service directly bypasses bean validation, so this ships a 2000-char reason into a column sized for 500 and only fails at the DB. -->
- **DECLINE** → `dealService.reject(principal, dealId, new RejectRequest(text), key)` — `reject(AuthPrincipal, String, RejectRequest, String idempotencyKey)` at L399-400, four args ✓. **But `RejectRequest` is `record RejectRequest(@Size(max = 500) String reason)`** while `ApproveDraftRequest.text` allows 2000. Calling the service directly bypasses `@Valid`, so a long decline reaches the column unchecked. Cap DECLINE draft text at 500 chars in `DraftReplyExecutor` (reject with 422 `DECLINE_TEXT_TOO_LONG`) and in the `draft_reply` tool description, rather than truncating silently at send.

- **APPLICATION** → `creatorCampaignService.apply(principal, campaignId, new ApplyRequest(text))` — **verified**: `CreatorCampaignService.apply(AuthPrincipal principal, String campaignId, ApplyRequest req)` returns `ApplyResponse` (L208), and `ApplyRequest` is `record ApplyRequest(@Size(max = 2000) String message)` (`CreatorCampaignDtos` L70). Same method `CreatorCampaignController.apply` calls (L61-64). No fact-sheet lookup needed.

`level_up_eligible` = `approvedDraftCount >= 10 && consentAcceptedAt <= now - 7 days && approvalLevel == 0 && levelUpPromptedAt == null`. The frontend shows the prompt once; `PUT /creator/agent-preferences` with `approval_level: 1` is the existing path; `POST /creator/meera/drafts/level-up-seen` sets `levelUpPromptedAt`.

DTOs in `CreatorToolDtos`: `DraftItem(draft_id, kind, intent, text, proposed_amount, deal_id, brief_id, campaign_id, status, created_at)`, `ApproveDraftRequest(@NotBlank @Size(max=2000) text, proposed_amount, deal_terms)`, `ApproveDraftResponse(...)`.

### 3.8 Briefs: `CreatorBriefService` and `CreatorBriefController` (B1)

`CreatorBriefService` (`com.influora.service`):

```java
@Transactional public BriefAnalysisResponse paste(String creatorUserId, String rawText)
@Transactional public CreatorBrief ensurePlatformBrief(String creatorProfileId, String collaborationId)
@Transactional(readOnly = true) public BriefAnalysisResponse get(String creatorUserId, String briefId)
@Transactional(readOnly = true) public List<BriefListItem> list(String creatorUserId, int limit)
@Transactional public void dismiss(String creatorUserId, String briefId)
@Transactional public SecureLinkResponse createSecureLink(String creatorUserId, String briefId, CreateSecureLinkRequest req)
```

`paste` flow:
1. Profile, prefs, consent.
2. `CreatorBrief.paste(...)`, save (status NEW) so the raw text survives an AI outage.
<!-- PRIYA: token-type contradiction. 3.8 says the client mirrors MeeraVoiceAiClient (a SERVICE-scoped token), but 7.5 says the route authenticates with verify_creator_token, which requires SCOPE_CREATOR and a matching body_creator_profile_id. A service token can never satisfy the creator route's scope — service_token.py's ENDPOINT_SCOPES comment says the segregation is deliberate (Kabir). Also the arg is a userId here and a creator_profile_id in 7.5. Resolved: mirror CreatorSuggestionAiClient, pass the PROFILE id. -->
3. Call the AI service: **`MeeraBriefAiClient.extract(String creatorProfileId, String rawText, String creatorLanguage)`** → `Optional<BriefExtraction>`. The client mirrors **`CreatorSuggestionAiClient`, not `MeeraVoiceAiClient`** — the Python route authenticates with `verify_creator_token`, which requires `SCOPE_CREATOR` and a `creator_profile_id` that matches the body (7.5). A `MeeraVoiceAiClient`-style **service** token cannot satisfy that scope; `app/auth/service_token.py`'s `ENDPOINT_SCOPES` comment says the segregation between `SCOPE_SERVICE` and `SCOPE_CREATOR` is deliberate and bidirectional. Pass `profile.getId()` (a `creator_profiles.id`), **not** the user id. `POST {ai.baseUrl}/internal/brief-extract`. On failure returns empty and the service falls back to `BriefFallbackExtractor.extract(rawText)` (deterministic: regex for INR amounts, dates, "reel/story/post" counts, "perpetual", "exclusiv", "UPI", "#ad") with `extraction_source = FALLBACK`.
4. `DealRiskService.evaluateExtraction(profile, prefs, extraction, null)` → flags.
5. `RateQuoteService.quoteForExtraction(profile, prefs, extraction)` → quote.
6. `brief.applyAnalysis(...)`, save, return `BriefAnalysisResponse{brief_id, status, extraction, flags, quote, extraction_source, summary_lines}`.

`ensurePlatformBrief`: builds `rawText` from the collaboration's proposal message plus campaign description, requirements, brand guidelines, deal terms (`Collaboration` V72 fields), and runs steps 3 to 6. Idempotent per collaboration (find existing PLATFORM brief first).

<!-- AMEND-0904: §14.2 — the secure link is a nudge, never a gate on any other output; SecureLinkResponse gains `share_text`; redeem writes campaigns.introduced_by_creator_profile_id (new column, §14.2.d); the whole of §3.8's secure-link half plus 8.1's redeem route is Phase B1, not B0 (§14.5). -->
`createSecureLink`:
- Brief must be ANALYZED or DRAFTED and PASTED. Request `{end_brand_name, package (PackageQuote subset: lines, add_ons, total_value, currency, payment_schedule, revision_rounds), deal_terms (DealTermsDto)}`; server recomputes `total_value` from lines and add-ons and rejects a mismatch (`SECURE_LINK_TOTAL_MISMATCH`). Server strips floors: the stored `package_json` never contains `floor_total`, `anchor`, `range_*`, `provenance`.
- Refuses when total below floor total unless the brief's draft carried a strategic override.
<!-- PRIYA: `influora.public-base-url` does NOT exist. Grepped application.yml and every @Value in main: the two real properties are `influora.web-base-url` (L133, ${INFLUORA_WEB_BASE_URL:http://localhost:5173} — the SPA origin, used by AuthService, CreatorConnectNudgeJob, AdminCreatorConnectionService, MetaPlatformCallbackController) and `influora.api.public-url` (the backend origin). /secure/{token} is a frontend SPA route, so it is web-base-url. -->
- Returns `SecureLinkResponse{link_id, url, expires_at}` where `url = {influora.web-base-url}/secure/{token}`. **There is no `influora.public-base-url` property.** The SPA origin is **`influora.web-base-url`** (`application.yml` L133, `${INFLUORA_WEB_BASE_URL:http://localhost:5173}`); inject it exactly as `AuthService` L72 does. Do not use `influora.api.public-url` — that is the backend origin, for `/track/click/...` style links.

`CreatorBriefController` `@RequestMapping("/creator/briefs")`, feature flag and consent on every route:

| Route | Body / params | Response |
|---|---|---|
| `POST /creator/briefs` | `PasteBriefRequest{@NotBlank @Size(max=8000) text}` | 201 `BriefAnalysisResponse` |
| `GET /creator/briefs?limit=20` | | `List<BriefListItem>` |
| `GET /creator/briefs/{id}` | | `BriefAnalysisResponse` |
| `POST /creator/briefs/{id}/dismiss` | | 204 |
| `POST /creator/briefs/{id}/secure-link` | `CreateSecureLinkRequest` | 201 `SecureLinkResponse` |
| `GET /creator/briefs/{id}/secure-links` | | `List<SecureLinkItem>` |
| `POST /creator/briefs/secure-links/{linkId}/revoke` | | 204 |

Rate limit bucket `creator-brief-paste`: `^/creator/briefs$` POST, 10 per window, user-keyed (AI cost).

### 3.9 Level 1: `RoutineReplyService`, `MeeraSendLog`, delayed sender (B5)

`com.influora.domain.enums.RoutineIntent`:

```java
SEND_MEDIA_KIT, SEND_RATE_CARD, ACK_BRIEF, ASK_DELIVERABLE_COUNT, ASK_TIMELINE, TENTATIVE_AVAILABILITY
```

`RoutineReplyService.queue(CreatorProfile profile, String collaborationId, RoutineIntent intent, String text, String idempotencyKey)` returns `SendRoutineReplyResult`. Server-side allow-list, in this order, each producing a `refused_code`:

| Check | Code |
|---|---|
| `prefs.approvalLevel() < 1` | `LEVEL_TOO_LOW` |
| `prefs.represented()` | `REPRESENTED_WARN_ONLY` |
| collaboration not owned by creator or status not in INVITED, APPLIED, IN_NEGOTIATION | `DEAL_NOT_OPEN` |
| no brand message exists in the thread — use the existing `dealMessageRepository.existsByCollaborationIdAndSenderType(collaborationId, DealSenderType.brand)` (repo L18) | `NO_BRAND_CONTACT_YET` |
| intent SEND_RATE_CARD and `!prefs.rateCardShareable()` | `RATE_CARD_NOT_SHAREABLE` |
| text contains a digit sequence that matches any floor value, or the rupee sign followed by digits, or a date-like commitment (`\b\d{1,2}\s?(Jan|Feb|...|Dec)\b`, `\d{4}-\d{2}-\d{2}`), or the words "accept", "confirm", "deal done", "yes to", "decline", "not interested" | `INTENT_TEXT_NOT_ROUTINE` (the classifier: text must not contain a price, a date commitment, or an accept/decline) |
| outside working hours in `working_hours_timezone` on a working day | queue anyway with `send_at` = next working slot; `status="QUEUED"` and `send_at` reflects it |
| duplicate `idempotencyKey` | return the existing row |

Otherwise create `MeeraSendLog` QUEUED with `sendAt = now + 60s` (or next working slot) and return `status="QUEUED"`, `cancel_window_seconds=60`.

`MeeraDelayedSendJob` (`com.influora.job`, `@Scheduled(fixedDelay = 15000)`, guarded by `influora.meera.delayed-send.enabled:true` and by the feature flag): loads `findByStatusAndSendAtBefore(QUEUED, now)` in batches of 50; for each, sends via `dealService.sendMessageAsCreatorAgent(creatorUserId, collaborationId, text, metadata)` — a new **`public @Transactional`** method that builds an `AuthPrincipal(userId, email, UserType.CREATOR, null)` (ctor verified, `security/AuthPrincipal.java` L17) and calls the existing `sendMessage`, then tags metadata per 2.8; marks SENT with `sentMessageId`; on `ApiException` marks FAILED with `failure_code`.

<!-- PRIYA: two resolutions. (1) ShedLock EXISTS — V68__shedlock.sql plus @SchedulerLock on AffiliateEarningReconciliationJob L83, AffiliateSettlementJob L132, AICreditResetJob L54, AudienceDemographicsJob L97, CreatorCaptionSyncJob L86. The "if none, single replica is fine" escape hatch does not apply. (2) sendMessageAsCreatorAgent must be public AND @Transactional in its own right; if it were package-visible its self-invocation of sendMessage would run outside a proxy and the "atomic with the send" guarantee would be silently lost. -->
**ShedLock exists — use it.** `V68__shedlock.sql` is applied and five jobs already carry `@SchedulerLock` (e.g. `AICreditResetJob` L54, `CreatorCaptionSyncJob` L86). Annotate: `@SchedulerLock(name = "MeeraDelayedSendJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")`. The "if none, the job is safe under a single API replica" fallback in the original draft does not apply and must not be relied on.

`sendMessageAsCreatorAgent` is **public and `@Transactional`**. It calls `sendMessage` on `this` (self-invocation, so the proxy does not re-enter) — the outer annotation is what actually opens the transaction, so it cannot be package-visible or un-annotated.

Creator-facing routes on `CreatorMeeraController` (extend the existing controller, under `/creator/meera`):

| Route | Behaviour |
|---|---|
| `GET /creator/meera/sends?limit=50` | `List<SendLogItem{send_log_id, deal_id, brand_name, intent, text, status, send_at, sent_at, failure_code}>` |
| `POST /creator/meera/sends/{id}/cancel` | QUEUED → CANCELLED if `now < send_at`; else 409 `ALREADY_SENT` |

<!-- PRIYA: there is no method named `export`. CreatorAgentConversationService has exportConversation(String userId, String conversationId) at L84 and deleteConversation(String userId, String conversationId) at L107. The response record also has to change, which the spec did not mention. -->
Add both to the DPDP export: **`CreatorAgentConversationService.exportConversation(String userId, String conversationId)` (L84)** — there is no method called `export` — gains `sends` and `drafts` arrays. That means extending `CreatorAgentDtos.ConversationExportResponse` (`conversationId, startedAt, messages` — 3 components) to 5, and updating its one construction site in `exportConversation` plus any assertion in `CreatorAgentConversationServiceTest`. **`deleteConversation` (L107)** also discards PENDING drafts created by that conversation.

### 3.10 Media kit (B6)

`MediaKitService` (`com.influora.service`):

```java
@Transactional(readOnly = true) public MediaKitResponse selfKit(String creatorUserId)           // includes rate card if set, plus a "share_url"
@Transactional(readOnly = true) public PublicMediaKitResponse publicKit(String username)        // 404 unless discoverable, feature on, not suspended
```

`PublicMediaKitResponse` (snake_case, NON_NULL): `username, display_name, city, categories, languages, bio, avatar_url, verified_metrics (reuse PublicCreatorDtos.VerifiedMetrics), platform_deal_count, top_platforms [{platform, handle, followers}], samples [{url, thumbnail_url, caption}] (from portfolio, max 6), rate_card {reel, story_set, post, currency} (only when rate_card_shareable, values are the creator-typed strings, never floors), generated_at`. `MediaKitResponse` extends it with `share_url` and `rate_card_shareable`.

Routes: `GET /creator/media-kit` on a new `CreatorMediaKitController` (`/creator/media-kit`, feature flag, no consent needed since it reads the creator's own public data); `GET /public/creators/{username}/media-kit` on `PublicCreatorController` (feature flag, `permitAll` GET entry in `SecurityConfig` right after the verified entry at L177, rate bucket `public-creator-verified` pattern widened to `^/public/creators/[^/]+/(verified|media-kit)$`, `Cache-Control: no-store, private`).

Prefs API: `UpdatePreferencesRequest` gains `rate_card_shareable` (Boolean) and `rate_card` (`RateCardDto{reel, story_set, post}` as strings, each `@Pattern("^[0-9,]{0,12}$")`); `PreferencesResponse` gains `rate_card_shareable`, `rate_card`, `negotiation_holdout`, `approved_draft_count`, `level_up_eligible`. `CreatorAgentPreferencesService.updatePreferences` (L150) calls `applyRateCard`.

<!-- PRIYA: the spec never listed the construction sites for these two records. Both are positional Java records; every `new X(...)` breaks. Enumerated from HEAD. -->
**Both are positional records; every construction site must be updated in the same commit.**

`PreferencesResponse` (`CreatorAgentDtos` L25-46, **18 components** → 23). Construction sites:
- `CreatorAgentPreferencesService.toResponse` L288-306 (the only `main` site — add the five values; `level_up_eligible` is computed here from `approvedDraftCount >= 10 && consentAcceptedAt <= now-7d && approvalLevel == 0 && levelUpPromptedAt == null`)
- `CreatorAgentControllerTest` L65, L86 (**2**)
- `CreatorMeeraControllerTest` L186 (the `preferencesResponseWithLanguage` stub) (**1**)

`UpdatePreferencesRequest` (`CreatorAgentDtos` L52-70, **16 components** → 18). Construction sites, all in tests:
- `CreatorAgentPreferencesServiceTest` L156, L198, L210, L221, L239, L252, L263 (**7**)
- `CreatorAgentControllerTest` L82, L131 (**2**)

`CreatorAgentPreferences.applyPreferences` (entity L299-333) keeps its 16 parameters — the rate card goes through the separate `applyRateCard` mutator, so `updatePreferences`' 16-arg call at L156-174 is unchanged.

---

## 4. Backend: rate quote (B3)

### 4.1 Deliverable taxonomy: `com.influora.service.rates.QuoteDeliverableType`

<!-- PRIYA: NAME COLLISION — com.influora.domain.enums.DeliverableType ALREADY EXISTS with a completely different, platform-shaped taxonomy (INSTAGRAM_POST, INSTAGRAM_REEL, INSTAGRAM_STORY, INSTAGRAM_CAROUSEL, YOUTUBE_VIDEO, YOUTUBE_SHORT, FACEBOOK_POST, FACEBOOK_REEL, TIKTOK_VIDEO). It is persisted on the Deliverable entity, parsed by ContractService.valueOf at L512, referenced by CreatorDeliverableDtos and 12 test classes. Adding REEL/STATIC_POST/... to it pollutes the deliverables spec, changes ContractService's valueOf behaviour, and (if @Enumerated(STRING)) touches a persisted column. Use a different name in a different package. -->
**Resolved: `com.influora.domain.enums.DeliverableType` already exists and must not be touched.** Its values are `INSTAGRAM_POST, INSTAGRAM_REEL, INSTAGRAM_STORY, INSTAGRAM_CAROUSEL, YOUTUBE_VIDEO, YOUTUBE_SHORT, FACEBOOK_POST, FACEBOOK_REEL, TIKTOK_VIDEO` — a platform taxonomy, persisted on the `Deliverable` entity, parsed by `ContractService` L509-514 via `valueOf`, and asserted in 12 test classes. Adding the pricing values to it would change `ContractService`'s parse behaviour and pollute a persisted column.

Create a **new, separately named** enum instead — `com.influora.service.rates.QuoteDeliverableType`:

```java
REEL(1.00), STATIC_POST(0.50), STORY_SET(0.50), SHORT(0.70), YT_INTEGRATION(1.50), YT_DEDICATED(3.00), UGC_ONLY(0.60), OTHER(1.00)
public final double unitWeight;
```

It is a **pricing** vocabulary, never persisted, never on a `Deliverable` row. Every occurrence of "DeliverableType" elsewhere in this spec (2.11's `deliverables[].type`, 3.5's `QuoteLine.type`, 7.1's `estimate_my_rate` enum, 8.x TS types) means `QuoteDeliverableType`.

`DealDtos.DeliverableSlot.type` stays a free string on the wire (`record DeliverableSlot(@NotBlank String type, @NotNull @Positive Integer qty)`); `RateQuoteService` parses with `QuoteDeliverableType.parse(String)` (case-insensitive, accepts legacy `"reel"`, `"story"`, `"post"`, mapping `story` → `STORY_SET` and `post` → `STATIC_POST`, and the existing platform names `INSTAGRAM_REEL` → `REEL`, `INSTAGRAM_STORY` → `STORY_SET`, `INSTAGRAM_POST`/`INSTAGRAM_CAROUSEL` → `STATIC_POST`, `YOUTUBE_SHORT` → `SHORT`, `YOUTUBE_VIDEO` → `YT_INTEGRATION`, unknown → `OTHER`). Real proposal metadata carries the platform names, so the platform mapping is not optional.

### 4.2 Add-on constants: `com.influora.service.rates.RateAddOns`

| Code | Label | Basis |
|---|---|---|
| `REPOST_30D` | Brand repost rights, 30 days | +25% of package |
| `PAID_ADS_QUARTER` | Paid ads usage, per quarter | +40% of package per quarter |
| `WHITELISTING` | Whitelisting or partnership ads | +75% of package |
| `PERPETUITY` | Perpetual usage | 2.5× package |
| `EXCLUSIVITY_30D` | Category exclusivity, 30 days | lost income = median monthly category earnings, floor 15% of package |

Percentages are the midpoints the plan gave (20 to 30, 25 to 50, 50 to 100, 2 to 3×). They are admin-overridable through `application.yml` keys `influora.rates.addons.repost-30d-pct:25` etc., read by `RateAddOns` via `@Value` (plain `@Component`, not `@ConfigurationProperties`, same reason as the feature flag). The provenance string for constants is exactly `"benchmark, not market data"`.

### 4.3 `RateQuoteService` (`com.influora.service.rates`)

<!-- AMEND-0904: §14.1 changes step 1 (shrinkage blend for 1-2 own deals; benchmark percentile 0.50 not 0.65; distinct-workspace floor and Meera-anchored share on the tier band), step 6 (anchor ×1.10 only in benchmark mode), adds a RATE_QUOTE_ISSUED audit row per quote, a calibration report on the existing admin baselines route, and admin-overridable tier constants. Read §14.1 before implementing this section. -->

```java
public PackageQuote quote(CreatorProfile profile, PreferencesResponse prefs, List<DeliverableSlot> deliverables, List<String> addOnCodes, BigDecimal brandBudgetInr, Locale locale)
public PackageQuote quoteForExtraction(CreatorProfile profile, PreferencesResponse prefs, BriefExtraction extraction)
public BigDecimal floorTotal(String creatorProfileId, List<DeliverableSlot> deliverables)
```

Algorithm:
1. **Base unit price for a reel** in this order of provenance:
   a. Own history: accepted or completed collaborations for this creator with `agreedRate > 0` in the last 180 days, normalised to reel-equivalents by dividing `agreedRate` by the summed unit weights of the collaboration's deliverables (deliverables live in the proposal message metadata; use `dealMessageRepository` proposal `kind == proposal` metadata `deliverables`, via `deliverable-slots` parsing; if absent, treat as one reel). If at least 3 such deals: `unit = median`, provenance `"your last N priced deals"`, sample N.
   b. Else platform band: `collaborationRepository.findRateBandCandidates(niche)` (L75).
      <!-- PRIYA: this query takes ONLY `niche` — there is no date or tier parameter. Its WHERE is status='COMPLETED' AND agreed_rate IS NOT NULL AND JSON_CONTAINS(cp.categories, :niche), with no time bound. It returns a projection interface, not Collaborations, and its javadoc carries a MANDATORY Kabir gate: no row may be serialized directly; callers must aggregate behind the k-anonymity floor. -->
      **The query takes only `niche`** — `List<RateBandCandidateRow> findRateBandCandidates(@Param("niche") String niche)`, a native query whose `WHERE` is `status = 'COMPLETED' AND agreed_rate IS NOT NULL AND JSON_CONTAINS(cp.categories, JSON_QUOTE(:niche))`. There is **no date bound and no tier filter in SQL**; do the "last 90 days" and "same tier" filtering in Java. The row is a projection interface `RateBandCandidateRow { getAgreedRate(), getCurrency(), getWorkspaceId(), getCreatorId(), getTotalFollowers() }` (repo L40-49) — note it exposes **no timestamp**, so a 90-day filter is not possible from this projection alone: either widen the projection with `co.updated_at AS updatedAt` (a query edit, safe) or drop the 90-day claim from the provenance string. **Widen the projection.** Tier comes from `getTotalFollowers()` through `CreatorTiers.derive`.
      Its javadoc carries a **mandatory Kabir Phase-2 gate: "no row from this query may be serialized directly"** — aggregate to a single median behind the k-anonymity floor before anything reaches a response. The `≥ 5` threshold below is that floor; keep it, and never put `workspaceId`/`creatorId` on any payload.
      If at least 5: `unit = median`, provenance `"N closed deals in your tier in 90 days"`.
   c. Else `rateEstimationService.estimate(latestMetric, qualityScore, categories)` — verified signature `estimate(Optional<CreatorMetric>, QualityScoreService.QualityScoreResult, List<String>)` returning `RateEstimation` with `.min()`/`.max()` (`RateEstimationService` L76; same call shape as `CreatorAgentPreferencesService` L137). If `min > 0`, `unit = min + 0.65 × (max − min)` for the anchor and `min` for the floor-side figure, provenance `"benchmark, not market data"`, sample 0. If the estimate is zero (no metric), use **`prefs.reelFloor()`** and provenance `"your floor"`. `RateEstimationService.CATEGORY_MULTIPLIERS` is `private static final` (L45) — its keys are the reference vocabulary for 2.11's `category`, but the map itself is not readable from outside; do not try to import it.
2. **Lines**: `unit_price = unit × weight(type)`; `line_total = unit_price × qty`; `below_floor` compares `unit_price` against the matching floor: REEL → `reel_floor`, STORY_SET → `story_set_floor`, everything else → `post_floor` (the plan's "everything else same as post" rule).
3. **Bundle discount**: when total qty ≥ 3, 10% off the lines subtotal, shown as the creator's concession.
4. **Add-ons** per 4.2, computed on the discounted subtotal.
5. **Total** = subtotal − discount + add-ons. **Floor total** = Σ floor(type) × qty.
6. **Anchor** = the 60th to 75th percentile figure: `min(total × 1.15, rangeMax)` when a range exists, else `total × 1.10`. Null when **`prefs.negotiationHoldout()`** (Java accessor; `negotiation_holdout` is the wire name).
7. **Recommended move** when `brandBudgetInr` is given: budget ≥ total → `ACCEPT`; budget ≥ floorTotal and tier is NANO or MICRO → `SCOPE_DOWN` with `scope_down_offer` built by removing the lowest-weight lines until the package fits the budget; budget ≥ floorTotal and tier MID **, MACRO or MEGA** → `COUNTER_AT_FLOOR`; budget < floorTotal → `DECLINE` unless a barter or strategic context applies (the model decides wording, the code decides the move). <!-- PRIYA: "MID or above" was ambiguous — deriveTier returns the string "MEGA" for >=1M followers, which is not a CreatorTier enum value. Enumerate the tiers explicitly and branch on the String, never CreatorTier.valueOf. --> Branch on the tier **String**, never `CreatorTier.valueOf(tier)` — `"MEGA"` is not a `CreatorTier` constant and would throw.
8. `payment_schedule` fixed string `"50% on securing funds, 50% on delivery"`, `revision_rounds` = `prefs` has no field, use 2.
9. All money strings via `Rendered.money(value, locale)`.

Tests: `RateQuoteServiceTest` with the three provenance branches, the discount threshold, each add-on, the four recommended moves, holdout nulling the anchor, floor comparison per type, and legacy type parsing.

---

## 5. Backend: deal risk rules (B4)

### 5.1 `DealRiskService` (`com.influora.service.risk`)

```java
public List<RiskFlag> evaluateDeal(String creatorProfileId, String collaborationId)
public List<RiskFlag> evaluateBrief(String creatorProfileId, String briefId)
public List<RiskFlag> evaluateExtraction(CreatorProfile profile, PreferencesResponse prefs, BriefExtraction extraction, Collaboration collaborationOrNull)
```

`evaluateDeal` builds an extraction-like view from the collaboration and calls `evaluateExtraction`. Fields verified on HEAD: `Collaboration.getUsageMonths()` (L210), `isUsagePerpetual()` (L214), **`getUsageChannels()` — returns a JSON `String`, not a `List`; parse with `JsonLists.stringListFromJson`** (L218), `getExclusivityDays()` (L222), `getExclusivityScope()` → `ExclusivityScope {NONE, NAMED_BRANDS, CATEGORY}` (L226), **`getExclusivityBrands()` — also a JSON `String`** (L230), `getMaxRevisions()` (L234), `getAppliedAt()` (L180), `getAgreedRate()` (L188). Campaign: `getEndBrandName()` (L238), `getEndBrandCategory()` (L242), `startDate`/`endDate` are `LocalDate` (L48/L51), budget is **`budgetMin`/`budgetMax`** — there is no single `budget` column. `UsageChannel` has exactly five values (`ORGANIC, PAID_ADS, WHITELISTING, WEBSITE, OFFLINE`), which is what 5.2's `USAGE_PERPETUAL` "contains all five" rule counts against. Take `prefs` via `CreatorAgentPreferencesService.getByProfileId(creatorProfileId)` (see 3.6). Rules are pure functions in `com.influora.service.risk.rules`, one class per rule implementing:

```java
interface RiskRule { Optional<RiskFlag> apply(RiskContext ctx); }
```

`RiskContext` carries: profile, prefs, extraction, collaboration (nullable), the creator's active collaborations with campaign end-brand and category and exclusivity dates, the package quote (for floor and value scaling), locale, `now`.

### 5.2 The rules and their codes

Severity scales with deal value: `value = extraction.budget_inr` or `collaboration.agreedRate` or `quote.total_value`. Thresholds: small < 10,000; mid 10,000 to 25,000; large > 25,000.

| Code | Severity | Fires when | `detail` and `action` |
|---|---|---|---|
| `BELOW_FLOOR` | CRITICAL | value < floor total of the deliverables | "Offer is {value} against your floor of {floor}." Action: scope-down draft for NANO/MICRO, counter at floor otherwise; note the strategic override |
| `USAGE_PERPETUAL` | CRITICAL | `usage_perpetual` or `usageChannels` contains all five, or brief text matches `perpetu|in perpetuity|all media` | "Brand can use this content forever." Action: price PERPETUITY add-on |
| `USAGE_LONG` | INFO ≤ 6 months, WARN > 12 months | `usage_months` set | "Usage window {n} months." Action: price REPOST or PAID_ADS |
| `EXCLUDED_CATEGORY` | CRITICAL | `extraction.category` or `endBrandCategory` in `prefs.excluded_categories` | Action: polite decline draft |
| `BLOCKED_BRAND` | CRITICAL | brand name (case-insensitive, trimmed) in `prefs.blocked_brands` | Action: decline |
| `OFF_PLATFORM_PAYMENT` | WARN (shadow mode: logged, never blocks) | `off_platform_payment_hint` or text matches `\b(upi|gpay|phonepe|paytm|bank transfer|neft|imps|pay(ment)? after)\b` | "Paying outside Secure Payments loses dispute cover." Action: steer-back draft; the creator has the report button; write an `OFF_PLATFORM_HINT` audit row with brand id only, never the creator's name or the message text |
| `COMPETITOR_CONFLICT` | WARN | an active collaboration (CONTRACTED, IN_PROGRESS, REVIEW_PENDING, REVISION_REQUESTED, COMPLETED within its exclusivity window) has `exclusivityScope == NAMED_BRANDS` containing this brand, or `CATEGORY` matching this category, and `appliedAt + exclusivityDays > now` | "Conflicts with {brand} until {date}." Action: propose a start date after the window |
| `EXCLUSIVITY_LONG` | INFO at 30 days; WARN at ≥ 60 days or CATEGORY scope with ≥ 1 deal in that category in 90 days | `exclusivity_days` set | "{n} days exclusivity ≈ {lost income} at your usual rate." Action: price EXCLUSIVITY add-on |
| `VAGUE_DELIVERABLES` | WARN | `vague_deliverables`, or no deliverables with qty, or text matches `a few|some posts|until (we're|we are) happy|unlimited revisions` | Action: ask the brand for the count; after 48 hours of no answer the assumption quote (Phase C job) |
| `HIDE_DISCLOSURE` | WARN, not dismissible | `disclosure_hidden_hint` or text matches `(no|don'?t|without)\s+(#ad|#collab|#sponsored|disclos|paid partnership)` | "Breaks ASCI guidelines." Action: refuse to draft that; explain in one line |
| `BARTER` | WARN | `barter_only` or (budget absent and `barter_mrp_inr` present) | "Product worth {mrp}, about {40% mrp} real value; cash gap {gap}." Keyed on followers and engagement, so it fires for unconnected creators. Action: option draft "1 reel plus 1 story on product; 3 reels is {quote}" |
| `REGULATED_CATEGORY` | WARN, not dismissible | `regulated_category` in FINANCE, HEALTH, RMG, CRYPTO, ALCOHOL, TOBACCO, or `claims` non-empty | Codes in `data.sub_code`: `SEBI_DISCLOSURE`, `ASCI_HEALTH`, `RMG_DISCLAIMER`, `CRYPTO_DISCLAIMER`, `CLAIMS_SUBSTANTIATION`. Action: ask the brand for evidence in-thread |
| `CALENDAR_OVERLOAD` | INFO | the deadline week already holds ≥ `prefs.weekly_sponsored_limit` deliverables (count IN_PROGRESS and CONTRACTED collaborations with `endDate` in that ISO week) | Action: propose a later date |
| `PARTNERSHIP_ADS_REQUEST` | WARN | only in `evaluateDeal`: status ≥ CONTRACTED and the last brand message matches `partnership ad|boost|promote (this|the) (post|reel)|whitelist` and `usageChannels` lacks PAID_ADS and WHITELISTING | "Do not approve the partnership-ads request until the paid-ads add-on is paid." |

Every flag: `title` ≤ 60 chars, `detail` one sentence with rendered numbers, `action` one sentence, `data` rendered strings only. `dismissible = false` for `HIDE_DISCLOSURE`, `OFF_PLATFORM_PAYMENT`, `REGULATED_CATEGORY`.

### 5.3 Deal risk endpoint

`DealController` gains `GET /deals/{id}/risks` → `CheckDealRisksResult`. Creator principal only (403 `CREATOR_ONLY` for brands, since the flags reference floors). Implemented in `DealService.risksForCreator(AuthPrincipal, String dealId)` which resolves the profile and calls `DealRiskService.evaluateDeal`. Also `GET /creator/briefs/{id}` already returns the brief's flags.

Tests: `DealRiskServiceTest` with one test per rule firing and one per rule not firing, plus severity scaling and the shadow-mode audit row for `OFF_PLATFORM_PAYMENT`.

---

## 6. Backend: campaign fit (B7)

### 6.1 `CampaignFitService` (`com.influora.service`)

```java
@Transactional(readOnly = true) public List<CampaignFit> rank(CreatorProfile profile, PreferencesResponse prefs, int limit)
```

Candidates: `CreatorCampaignService.browse(...)` for the creator (reuse the same visibility rules: ACTIVE, not private, deadline in the future, not already applied). Score 0 to 100:

| Signal | Points |
|---|---|
| category overlap between `campaign.endBrandCategory` (or objectives text) and profile categories | 35 |
| platform overlap with profile platforms | 20 |
| budget: `budget_max ≥ floor total for one reel` | 25 (0 if below floor; sets `below_floor=true`) |
| deadline ≥ 3 days away | 10 |
| brand verified | 10 |

Excluded categories and blocked brands are filtered out entirely. `fit_reasons` are short strings ("Beauty matches your niche", "Budget above your floor", "Verified brand"). Sort by score, take `limit`.

---

## 7. AI service (influora-ai)

### 7.1 Creator tool schemas: `app/tools/creator_schemas.py` (new)

Do not add to `TOOL_SCHEMAS`, `TOOL_NAMES`, or `TOOL_TO_SPRING_PATH` (the brand set, diffed by CI). New module:

```python
GET_MY_DEALS = "get_my_deals"; GET_BRIEF = "get_brief"; ESTIMATE_MY_RATE = "estimate_my_rate"
GET_MY_METRICS = "get_my_metrics"; CHECK_DEAL_RISKS = "check_deal_risks"; DRAFT_REPLY = "draft_reply"
SEND_ROUTINE_REPLY = "send_routine_reply"; RANK_OPEN_CAMPAIGNS = "rank_open_campaigns"; DRAFT_APPLICATION = "draft_application"

CREATOR_TOOL_NAMES: tuple[str, ...] = (...)                       # all nine, in that order
CREATOR_TOOL_TO_SPRING_PATH: dict[str, str] = {name: f"/internal/meera/creator/{name}" for name in CREATOR_TOOL_NAMES}
CREATOR_IDEMPOTENT_REQUIRED_TOOLS = (SEND_ROUTINE_REPLY,)
CREATOR_TOOL_SCHEMAS: list[dict[str, Any]] = [...]                # one flat object schema each, see below
def get_creator_tool_schemas(tools_enabled: list[str] | None) -> list[dict[str, Any]]
    # returns CREATOR_TOOL_SCHEMAS filtered to tools_enabled; None means all nine
def is_creator_tool(name: str) -> bool
```

Schema shapes (all flat `type: object`, single-type properties, `required` listed):

- `get_my_deals`: `status` (string, enum active|completed|all), `limit` (integer, minimum 1, maximum 25). required: none.
- `get_brief`: `brief_id` (string), `deal_id` (string). required: none (server rejects both-missing).
- `estimate_my_rate`: `deliverables` (array of object {`type` string enum of the eight types, `qty` integer minimum 1}), `add_ons` (array of string enum of the five codes), `brand_budget_inr` (number), `deal_id` (string), `brief_id` (string). required: `deliverables`.
- `get_my_metrics`: no properties.
- `check_deal_risks`: `deal_id` (string), `brief_id` (string).
- `draft_reply`: `kind` (string enum REPLY|COUNTER|DECLINE), `text` (string), `deal_id`, `brief_id`, `proposed_amount` (number), `deal_terms` (object with the seven DealTermsDto keys, camelCase, each single-typed; `usageChannels` array of string enum; `exclusivityBrands` array of string), `strategic_override` (boolean), `strategic_reason` (string). required: `kind`, `text`.
- `send_routine_reply`: `deal_id` (string), `intent` (string enum of the six RoutineIntent names), `text` (string). required: all three.
- `rank_open_campaigns`: `limit` (integer 1..10), `niche` (string).
- `draft_application`: `campaign_id` (string), `text` (string). required: both.

Descriptions must tell the model when to call and what it must never do (never put a floor in `text`; `proposed_amount` must be at or above the floor unless the creator said "strategic"; `send_routine_reply` only for routine intents and only when the creator's context says level 1).

<!-- PRIYA: verified the extension point. All three tests share one decorator `@pytest.mark.parametrize("name,tool", _all_tool_schemas())` (L64/L77/L90) fed by the module-level helper _all_tool_schemas() at L59-61, so ONE edit covers all three. Also: get_tool_schemas() takes NO arguments — do not add one. -->
Extend `tests/tools/test_tool_schema_anthropic_valid.py` by editing the single module-level helper `_all_tool_schemas()` (**L59-61**) to append `[(t["name"], t) for t in get_creator_tool_schemas(None)]`. All three parametrised tests (L64, L77, L90) share that helper via `@pytest.mark.parametrize("name,tool", _all_tool_schemas())`, so one edit covers all three. Parametrize args are evaluated at **collection time**, so pass `None` (all nine), never a flag-dependent list.

`get_tool_schemas()` takes **no arguments** (`schemas.py` L425) and returns the four non-money Spring tools plus `analyze_site` and `present_options`. Do not change it. `test_get_tool_schemas_returns_expected_count` (L114) is named "count" but only asserts membership, so it is unaffected either way. The one hard count assertion in the tree is **`tests/eval/test_prompt_injection.py:576` `test_exactly_six_tools_exist_and_tiers_match_spec`**, which asserts `set(TOOL_NAMES) == {...six...}` — this is why nothing may be added to `TOOL_NAMES`.

### 7.2 Tool selection and Block A: `app/prompt/assembler.py`

- `assemble_prompt` (L809): in the CREATOR branch replace `tools = []` (**L832**; the branch is L827-836) with `tools = get_creator_tool_schemas(creator.get("tools_enabled"))`. When `tools_enabled` is absent or empty (older Spring), pass `[]` so the info-barrier behaviour degrades to Phase A. This degrade rule is what keeps most existing tests green — see 7.4.
- `build_block_a_creator()` (L435): take the tool names and render `"Available tools: " + ", ".join(names)` or `"Available tools: none (warn-only mode)"` when the list is empty. Signature becomes `build_block_a_creator(tool_names: list[str])`. Keep `cache_control`.
  <!-- PRIYA: this function currently takes NO arguments and has THREE call sites, two of them tests. The spec listed none of them. -->
  **Three call sites must change:** `assembler.py:830` (production), `tests/prompt/test_creator_prompt.py:90`, `tests/security/test_info_barrier.py:222`. Give the parameter a default of `[]` if you want the two test call sites to keep compiling unchanged; otherwise update all three.
- `build_block_b_creator` (L541): render `- Negotiation coaching: withheld for this deal until {holdout_until}` when `negotiation_holdout` is true, and `- Tools you may call now: a, b, c`.
  **Write the reads as `ctx.get("negotiation_holdout")` / `_creator_str(ctx, "holdout_until")`.** The function's parameter is named `context` and is narrowed to `ctx` at L557; `test_creator_context_drift.py:98` greps `assembler.py` for the literal regex `ctx(?:\.get\(|, )['"]<name>['"]`, so a read written against `context` fails that test. `holdout_until` is context component **29** (see 2.10 — it is a full component of the Java record, not an afterthought).
- `CREATOR_CONTEXT_PAYLOAD_FIELDS` (L122-165, a 27-entry sorted `tuple[str, ...]`): add `approved_draft_count`, `holdout_until`, `negotiation_holdout`, `rate_card_shareable`, `tools_enabled` in sorted position. `CREATOR_CONTEXT_FIELDS_NOT_RENDERED` (L169-171, a `frozenset`): add `approved_draft_count`, `rate_card_shareable`.
- `_FORBIDDEN_BRAND_FIELDS` (L70-103, a plain `set` used by `_strip_forbidden_fields` L204-205, called only from `build_block_b` L394): add `tools_enabled`, `negotiation_holdout`, `holdout_until`, `rate_card_shareable`, `approved_draft_count`, `rate_card`. None of the six collide with the 25 entries already there. `tests/prompt/test_creator_context_drift.py:170` asserts against this set.
- Bump `PROMPT_VERSION` in `app/config.py` **L69** from its current value `"meera-2026.08.10.1"` to `"meera-2026.09.10.1"`. Verified: **no test, Java file, YAML or env file pins the literal**, so the bump breaks nothing. `ci/stale-comment-check.py:270` *requires* a `^\+\s*PROMPT_VERSION\s*=` line in the diff whenever prompt content changes (F-0150), so the bump is mandatory, not optional. (`CREATOR_COPILOT_PROMPT_VERSION` in `application.yml` L479 is an unrelated constant.)

### 7.3 Loop dispatch: `app/tools/loop.py`

- `is_known_tool` (schemas.py **L455-456**, currently `return name in TOOL_NAMES or name in LOCAL_TOOL_NAMES`): also return true for `is_creator_tool(name)`. It is called from `loop.py:327`; on false the loop yields an `unknown_tool` error result and `continue`s.
  <!-- PRIYA: this is the single highest-risk line in the AI-service change. loop.py L452 is `TOOL_TO_SPRING_PATH[tool_name]` — a bracket subscript that sits OUTSIDE the try block (which starts at L465). Widening is_known_tool without widening the path lookup in the SAME edit produces an unhandled KeyError mid-stream, and no existing test covers it. -->
  **These two edits must land together.** `loop.py` L452 is a bracket subscript `path = TOOL_TO_SPRING_PATH[tool_name]` that sits **outside** the `try` (which opens at L465), so widening `is_known_tool` alone raises an unhandled `KeyError` in the middle of a stream. Add a `test_loop_creator_dispatch.py` case that asserts a creator tool name resolves to its Spring path, and one that asserts a name known-but-unmapped degrades to an error tool_result rather than raising.
- Path lookup at **L452**: `path = TOOL_TO_SPRING_PATH.get(tool_name) or CREATOR_TOOL_TO_SPRING_PATH[tool_name]`.
- Idempotency: `loop.py` L453-455 (`if tool_name in IDEMPOTENT_REQUIRED_TOOLS:`) **and** L472 (`allow_retry=tool_name not in IDEMPOTENT_REQUIRED_TOOLS`) — **both** become `... in IDEMPOTENT_REQUIRED_TOOLS or ... in CREATOR_IDEMPOTENT_REQUIRED_TOOLS`. Missing L472 makes `send_routine_reply` silently retryable.
- Scope refusal for `send_routine_reply`: add beside the money decline, which is at **L494-509** (inside `except SpringCallError as exc:`, which opens at L474 — the original spec cited the `except` line, not the decline):

```python
ROUTINE_REPLY_SCOPE_DECLINE = (
    "I can draft that for you, but sending on my own needs routine replies turned on in your Meera settings."
)
if tool_name == SEND_ROUTINE_REPLY and exc.code in ("ON_BEHALF_SCOPE_INSUFFICIENT",):
    yield tool_result error; yield token ROUTINE_REPLY_SCOPE_DECLINE; yield done finish_reason="routine_reply_scope_declined"; return
```

- A `send_routine_reply` result with `status == "REFUSED"` is fed back as a normal (non-error) tool result; the model explains using `refused_reason`.
- `tool_result` events for creator tools pass `data` through unchanged so the frontend renders cards.

### 7.4 Persona: `app/prompt/creator_persona.py`

<!-- AMEND-0904: §14.1.b adds a provenance rail ("say the provenance sentence before any number when the quote is a benchmark") and §14.3 adds an authorship rail ("never write as if the creator typed it personally") to the persona text below. Both are pinned by new substring tests in tests/prompt/test_creator_prompt.py. In B0 the "What you can do now" list carries six tools, not nine — send_routine_reply, rank_open_campaigns and draft_application are B1 (§14.5). -->

Replace the two Phase A sections ("What you do right now (Phase A is conversational only)" and "What you cannot do yet") with:

```
What you can do now:
- get_my_deals, get_brief, get_my_metrics: read the creator's own data. Call them
  before answering any question about a deal, a brief, or a number.
- estimate_my_rate: price a package. Quote the returned lines and total as given.
  The "anchor" is the opening ask; the floor is never spoken to a brand.
- check_deal_risks: run this on any brief or offer before drafting a reply.
  Explain each flag in one plain sentence, then the action.
- draft_reply: write the reply, counter, or decline. The tool saves it as a draft;
  the creator taps to send. You never send a reply, counter, decline, or
  application yourself. Say "I've drafted it, tap to send" and stop.
- send_routine_reply: only when your context lists it, and only for the six routine
  intents (media kit, rate card, acknowledge, ask deliverable count, ask timeline,
  tentative availability). Never a price, a date commitment, an accept, or a decline.
  Tell the creator it goes out in 60 seconds and they can cancel from the send log.
- rank_open_campaigns and draft_application: find open campaigns that fit and draft
  the application for a tap.

Negotiation rules:
- For NANO and MICRO creators, the default counter is a scope-down at the brand's
  number, not a price counter. Use the quote's recommended_move.
- Quote packages, never per-unit prices, in anything addressed to a brand.
- If your context says negotiation coaching is withheld, say so plainly and do not
  suggest a price for that deal.
- A flag marked not dismissible must be mentioned before anything else.

What you still cannot do:
- Accept, sign, or commit the creator to anything. Move money. Post to social accounts.
  Contact a brand outside Influora. Give legal or tax conclusions as fact.
```

Keep every other rail verbatim (tests pin substrings).

<!-- PRIYA: the two Phase-A strings live in DIFFERENT files. "NO tools in this phase" is creator_persona.py:86 (inside MEERA_CREATOR_PERSONA). "none in this phase" is assembler.py:444, inside build_block_a_creator — NOT in the persona. A persona-only rewrite leaves assembler.py:444 saying "Available tools: none in this phase" while the tool list is non-empty. Also enumerated the full breakage set, which the original listed only partly. -->
**The two Phase-A strings are in different files.** `"NO tools in this phase"` is **`creator_persona.py:86`**, inside the `MEERA_CREATOR_PERSONA` literal (the two blocks to replace are L78-83 and L85-94). `"none in this phase"` is **`assembler.py:444`**, inside `build_block_a_creator` — the 7.2 rewrite of that function is what removes it.

Test impact, enumerated from HEAD:

| Test | Effect | Action |
|---|---|---|
| `tests/prompt/test_creator_prompt.py:60` | breaks — `assert "NO tools in this phase" in text` | change to `"You never send a reply, counter, decline, or"` |
| `tests/prompt/test_creator_prompt.py` L50, 53, 54, 56, 57, 58, 62 | **stay green** — all pin persona text outside L78-94 | no change |
| `tests/prompt/test_creator_prompt.py:90` | `build_block_a_creator()` call site | pass the tool-name list (or rely on a `[]` default) |
| `tests/prompt/test_creator_prompt.py:192` `assert prompt.tools == []` | **stays green** — its fixture carries no `tools_enabled`, so the 7.2 degrade rule returns `[]` | no change needed |
| `tests/prompt/test_creator_settings_in_prompt.py:86` `assert assembled.tools == []` | **stays green**, same reason | update the comment only |
| `tests/security/test_info_barrier.py:222` | `build_block_a_creator()` call site | as above |
| `tests/security/test_info_barrier.py:219-225` `test_creator_block_a_lists_no_brand_tool` | breaks on `assert "none in this phase" in block_a_text` | keep the brand-tool-absence loop (safe: no brand tool name is a substring of any of the nine creator names), replace the string assertion with "every name in `get_creator_tool_schemas(None)` appears when `tools_enabled` lists all nine" |
| `tests/security/test_info_barrier.py:228-239` `test_creator_turn_is_assembled_with_an_empty_tool_set` | **stays green** as written | split into two: empty when `tools_enabled` absent, the nine when present |
| `tests/security/test_info_barrier.py:242-248` `test_brand_turn_still_carries_the_full_tool_set` | **stays green** — it never calls `is_known_tool` and both sides of its assertion derive from `get_tool_schemas()` | no change |
| `tests/routes/test_chat_creator_audience.py:491`, `:579` | **stay green** — neither fixture sets `tools_enabled` | optional: add `tools_enabled` to one fixture and assert `[t["name"] for t in recorded["tools"]] == list(CREATOR_TOOL_NAMES)`; keep the other as the degrade case |
| `tests/routes/test_chat_creator_audience.py:615` | brand counterpart, unaffected | no change |
| `tests/eval/test_prompt_injection.py:568-573` `test_attacker_invented_tool_names_are_unknown` | **stays green** — none of its six probe names collide with the nine | no change |
| `tests/eval/test_prompt_injection.py:576` `test_exactly_six_tools_exist_and_tiers_match_spec` | **stays green only because `TOOL_NAMES` is untouched** | do not add creator names to `TOOL_NAMES` |

Never add a creator tool to `get_tool_schemas()`: `test_info_barrier.py:223-224` loops it asserting each name is *absent* from creator Block A, which would become self-contradictory.

### 7.5 Brief extraction route: `app/routes/brief_extract.py` (new)

`POST /internal/brief-extract`, authenticated like `creator_suggestion`. Body `{creator_profile_id, raw_text, creator_language}` — **drop `workspace_id`**; the spend gate is keyed on `creator_profile_id` passed into the `workspace_id` slot, exactly as `creator_suggestion.py` L264-270 does (the keys are opaque strings).

<!-- PRIYA: three concrete errors here, all resolved against source. (1) ENDPOINT_SCOPES has no "brief_extract" entry — verify_creator_token would reject every call. (2) verify_creator_token also REQUIRES body_creator_profile_id and is SYNCHRONOUS; creator_suggestion offloads it via anyio.to_thread.run_sync (F-09) because a JWKS kid-miss blocks the event loop. (3) complete_with_forced_tool is async and keyword-ONLY, and the parsed result is on .tool_input, not .text. -->
**Three fixes to the auth line:**

1. **Register the endpoint scope.** `app/auth/service_token.py` `ENDPOINT_SCOPES` (L53-69) has no `brief_extract` key, so `verify_creator_token(endpoint="brief_extract")` rejects every call. Add `"brief_extract": (SCOPE_CREATOR,)` beside `"creator_suggestion": (SCOPE_CREATOR,)`. This is why 3.8's Java client must mint a **creator**-scoped token, not a service token.
2. **The exact signature is `verify_creator_token(token, *, endpoint: str, body_creator_profile_id: str) -> VerifiedCreatorToken`** (`service_token.py` L420-425) — `body_creator_profile_id` is keyword-only and **required**; the spec's call omitted it.
3. **It is synchronous.** Copy `creator_suggestion.py` L216-226 verbatim, including the `await anyio.to_thread.run_sync(lambda: verify_creator_token(...))` wrapper — F-09: an unknown-`kid` JWKS miss blocks the event loop otherwise. Wrap in `except AuthError as exc: raise auth_error_to_http(exc) from exc`.

Then:

```python
result = await claude.complete_with_forced_tool(
    system_blocks=[...],
    messages=[{"role": "user", "content": wrap_untrusted("pasted_brief", raw_text)}],
    tool_schema=get_brief_extraction_schema(),
    max_tokens=1024,
    model=BRIEF_EXTRACT_MODEL,
)
```

**`complete_with_forced_tool` is `async` and every parameter is keyword-only** (`app/providers/claude.py` L362-370, bare `*` at L364) — it must be `await`ed and can never be called positionally. It returns `ClaudeToolResult(ok, tool_input, error, usage)` (L83-93): **the parsed payload is `result.tool_input`, not `result.text`** — that field does not exist on this type. It never raises; on `no_tool_use_in_response` it returns `ok=False` **with `usage` populated** (L428-430, the F-06 fix), which is why billing on `result.usage` regardless of `ok` is correct.

`BRIEF_EXTRACT_MODEL = os.getenv("BRIEF_EXTRACT_MODEL", TRENDSPARK_MODEL)` — define it in **`app/config.py`** beside `CREATOR_COPILOT_MODEL` (L149), which follows exactly this pattern, not in the route file. `TRENDSPARK_MODEL` (config L132, `claude-haiku-4-5-20251001`) is one of the three models `app/costs/pricing.py:120` pins into `PRICING_TABLE`, so defaulting to it inherits a priced row; a **new model literal would fail the pricing tests**.

`wrap_untrusted(label, content)` is positional (`app/prompt/untrusted.py` L47) ✓.

Validate the tool input with `parse_and_validate_extraction` (never raises): enum membership, integer bounds, `summary_lines` 3 to 5 items each ≤ 120 chars, no petnames, no banned word, no floor-like numbers other than the brief's own. On failure return `{"success": false, "error": {"code": "extraction_failed"}}` so Spring uses its fallback. Follow `creator_suggestion.py`'s invariant: **every failure path returns HTTP 200 with a deterministic body**; only a missing `creator_profile_id` (400) and auth (401/403) are non-200.

`get_brief_extraction_schema()` in `app/tools/schemas.py` follows the `get_analyze_creator_content_schema` pattern (**L596-602**: a zero-arg function returning the module-level `ANALYZE_CREATOR_CONTENT_SCHEMA` dict at L508, deliberately outside `TOOL_SCHEMAS` — see the rationale comment at L469-485). Note it returns a **single dict**, not a list. One flat object mirroring 2.11 with single types (`budget_inr` number, `barter_mrp_inr` number, `deadline` string, `usage_months` integer, `max_revisions` integer; absent means null).

Register the router in `app/main.py` inside the defensive try/except block like the other optional routers (**L74-107**, four identical blocks): import **inside** the `try`, bare `except Exception`, `logger.exception` naming the exact dead path.

Tests: `tests/routes/test_brief_extract.py` (auth failure, gate blocked → error envelope, happy path with a mocked provider returning a valid tool input, malformed output → `extraction_failed`, banned word stripped), `tests/tools/test_brief_extraction_schema_valid.py` (combinator-free).

### 7.6 Spend and holds

<!-- AMEND-0904: §14.4 supersedes the "no change" below. Brief extraction gets its OWN cap key and cap value, separate from the chat cap; cap exhaustion degrades the chat to a message that names the three surfaces that still work; the creator cap default is raised to $2.00 (ruling 3, §12). -->
~~No change to the cap logic.~~ `send_routine_reply` and `draft_reply` cost nothing extra. The brief extraction route is billed through the existing gate but under its **own** key and cap — see §14.4.b — so a chatty creator never loses paste-and-read.

---

## 8. Frontend (src/)

### 8.1 `src/lib/api.ts`

<!-- AMEND-0904: §14.2.b adds `share_text` (string) to `SecureLinkResponse` and §14.4.a adds `degraded_reason` (`"cap" | "ai_unavailable" | null`) to `BriefAnalysisResponse`. Both TS types below must carry them or this section's own "every field the Java record sends" rule is violated. `secureLinks.redeem` is Phase B1 (§14.5). -->
<!-- PRIYA: this pointer was MISSING from §14's pointer set. §8.1 is a day-1-adjacent file and the two new fields are invisible from here without it. -->
Add types (snake_case, mirroring the Java records exactly; every field the Java record sends and nothing it does not):

`BriefExtraction`, `RiskFlag`, `QuoteLine`, `AddOnLine`, `PackageQuote`, `BriefAnalysisResponse`, `BriefListItem`, `SecureLinkResponse`, `SecureLinkItem`, `CreateSecureLinkRequest`, `DealRisksResponse` (= `CheckDealRisksResult`), `DraftItem`, `ApproveDraftRequest`, `ApproveDraftResponse`, `SendLogItem`, `MediaKitResponse`, `PublicMediaKitResponse`, `CampaignFit`, `SecureLinkPreview`. **Verified: all 19 names, and all 6 namespace names below, are unused anywhere in `src/` — no collisions.**

Extend `CreatorAgentPreferences` (`api.ts` **L6019-6052**, snake_case throughout) with `rate_card_shareable: boolean`, `rate_card: { reel: string | null; story_set: string | null; post: string | null } | null`, `negotiation_holdout: boolean`, `approved_draft_count: number`, `level_up_eligible: boolean`.

<!-- PRIYA: CreatorAgentPreferencesUpdate is ALREADY an Omit (api.ts L6059-6062, omitting consent_accepted | consent_version) — this is an edit, not a new declaration. And MeeraSettingsSection.tsx:125 aliases it as `Draft`, so every field added to CreatorAgentPreferences that is NOT omitted lands in the settings form's draft type and must be produced by toDraft() (L127) or the file stops compiling. -->
`CreatorAgentPreferencesUpdate` **already exists** at `api.ts` L6059-6062 as `Omit<CreatorAgentPreferences, 'consent_accepted' | 'consent_version'>` — widen that union to also omit `'negotiation_holdout' | 'approved_draft_count' | 'level_up_eligible'`.

**Knock-on:** `MeeraSettingsSection.tsx:125` does `type Draft = CreatorAgentPreferencesUpdate`, so the two *non-omitted* new fields (`rate_card_shareable`, `rate_card`) land in the settings draft type automatically and **must be produced by `toDraft()` (L127) or the file fails `tsc`**. The three omitted fields must not appear there — `MeeraSettingsSection.ratecard.test.tsx` asserts the PUT never carries `negotiation_holdout`.

Add namespaces (each method `isLive() ? http.request(...) : mockOr(MOCK)`), and register them in the `api` object literal at **L6313** (verified: `export const api = {` is exactly L6313, keys run L6314-6359).

`http.request` (L536-545) defaults `role` to `'brand'` (L546), so **every creator method must pass `{ role: 'creator' }` explicitly** — copy the `creatorAgentPrefs` idiom at L6108-6122. `publicMediaKit.get` and `secureLinks.preview` are unauthenticated and pass **no `opts` at all** — copy `publicCreators.getVerifiedMetrics` at L6196-6202. `isLive` (L780) and `mockOr` (L775-778) are module-private; both are in scope inside `api.ts`.

```ts
export const creatorBriefs = {
  paste: (text: string) => Promise<BriefAnalysisResponse>,                  // POST /creator/briefs
  list: (limit = 20) => Promise<BriefListItem[]>,                            // GET  /creator/briefs
  get: (id: string) => Promise<BriefAnalysisResponse>,                       // GET  /creator/briefs/:id
  dismiss: (id: string) => Promise<void>,                                    // POST /creator/briefs/:id/dismiss
  createSecureLink: (id: string, body: CreateSecureLinkRequest) => Promise<SecureLinkResponse>,
  listSecureLinks: (id: string) => Promise<SecureLinkItem[]>,
  revokeSecureLink: (linkId: string) => Promise<void>,
};
export const creatorMeeraDrafts = {
  list: (status = 'PENDING') => Promise<DraftItem[]>,
  approve: (id: string, body: ApproveDraftRequest, idempotencyKey: string) => Promise<ApproveDraftResponse>,
  discard: (id: string) => Promise<void>,
  levelUpSeen: () => Promise<void>,
};
export const creatorMeeraSends = {
  list: (limit = 50) => Promise<SendLogItem[]>,
  cancel: (id: string) => Promise<void>,
};
export const creatorMediaKit = { getSelf: () => Promise<MediaKitResponse> };
export const publicMediaKit = { get: (username: string) => Promise<PublicMediaKitResponse> };   // no auth
export const secureLinks = {
  preview: (token: string) => Promise<SecureLinkPreview>,                    // GET  /public/secure-links/:token (no auth; package, creator public card, no floors)
  redeem: (token: string) => Promise<{ deal_id: string; campaign_id: string }>,  // POST /secure-links/:token/redeem, role 'brand'
};
```

`api.deals` (L2106-2216) gains `risks: (id: string) => Promise<DealRisksResponse>` (`GET /deals/:id/risks`, `{ role: 'creator' }`) — verified absent today. `counter` (L2158-2180) gains **`meeraDraftId?: string`** only.

<!-- PRIYA: the spec elsewhere implies dealTerms must be added to counter's payload. It is ALREADY there — api.ts:2173 `dealTerms?: DealTerms` (added in Phase A, A2), and deals.create has it at :2211. Only meeraDraftId is new. -->
**`dealTerms?: DealTerms` is already on `counter`'s payload (`api.ts:2173`, added in Phase A A2)** and on `deals.create` (L2211). Do not re-add it. What is missing is not the type but the *wiring* — `handleSubmitCounterForm` (8.6) never passes it.

`DealTerms` is defined in `src/lib/types.ts:31-39` and is **not** re-exported from `@/lib/api` (L47 re-exports only `DeliverableStatus, ContractStatus`), so import it from `@/lib/types`.

Backend routes for secure links (add to 3.8): `GET /public/secure-links/{token}` (permitAll GET, feature flag, rate bucket `public-secure-link`, IP-keyed 30) and `POST /secure-links/{token}/redeem` (brand principal; requires an Owner or Admin workspace; creates a DIRECT `Campaign` via `CampaignService.create` with `endBrandName` from the link, `budget = total`, `title = "{creator display name} × {end brand}"`, then `Collaboration.propose(...)` with `agreedRate = total`, applies deal terms via `applyDealTermsIfPresent`, persists a `proposal` `DealMessage` with the package as metadata the same way `createProposal` does, marks the link REDEEMED and the brief SECURED, records `OFFER` in offer history with actor BRAND). Funding then follows the existing contract and milestone flow; nothing new touches money.

### 8.2 `src/lib/meera-api.ts`

- `export type MeeraRole = 'brand' | 'creator';` — verified: the alias is at **L399** and is declared `type`, **not `export type`**, and is not exported anywhere in `src/`. Add the `export` keyword; nine internal uses (L401, 413, 424, 500, 520, 649, 666, 707, 755) are unaffected.
- Creator tool payload types and guards, mirroring the Java records: `GetMyDealsPayload`, `GetBriefPayload`, `EstimateMyRatePayload`, `GetMyMetricsPayload`, `CheckDealRisksPayload`, `DraftReplyPayload`, `SendRoutineReplyPayload`, `RankOpenCampaignsPayload`, `DraftApplicationPayload`, with `isXPayload(data: unknown)` guards that check the discriminating keys (`deals`, `brief_id` + `extraction`, `quote`, `metrics`, `flags`, `draft_id` + `kind`, `send_log_id`, `campaigns`, `draft_id` + `campaign_id`).
- `CREATOR_TOOL_NAMES` constant (the nine) and `isCreatorToolName(name: string)`.

### 8.3 `src/components/creator/MeeraCopilotChat.tsx`

- Widen `ChatMessage` (a local, non-exported interface at **L28-32**: `{ id, role: 'meera' | 'creator', text }`) with `toolResults?: CreatorToolResult[]` where `CreatorToolResult = { id: string; name: string; status: 'ok' | 'error'; data?: unknown; errorMessage?: string }`.
- In the `stream.open(...)` handlers (**L231-288** ✓ — today only `onToken` L232, `onDone` L243, `onError` L255, `onHeartbeatTimeout` L275) add `onToolStart` (append a loading card) and `onToolResult` (replace the loading card; only names in `CREATOR_TOOL_NAMES` are rendered; unknown names are ignored with a dev-only warn).
  <!-- PRIYA: two path corrections. The stream client is NOT in meera-api.ts — `open()` and the `MeeraStreamHandlers` interface live in src/hooks/useMeeraStream.ts (L47-57, L117-122), and it ALREADY declares onToolStart/onToolResult and already dispatches tool_start/tool_result at L184-193. No hook change is needed. And MeeraChatPanel.tsx is at src/components/feature/meera/, not src/components/creator/. -->
  **No hook change is needed.** `MeeraStreamHandlers` (`src/hooks/useMeeraStream.ts` **L47-57**) already declares `onToolStart`/`onToolResult`, and the SSE dispatcher already routes `tool_start`/`tool_result` to them at **L184-193**. `MeeraToolStartEvent` / `MeeraToolResultEvent` are exported from `meera-api.ts` (L165, L170). `MeeraCopilotChat` simply stops ignoring them. (`open()` is on the hook, **not** on `meera-api.ts`.)
  If a result arrives before the first token, create the assistant bubble lazily the way **`src/components/feature/meera/MeeraChatPanel.tsx:538-570`** does — that file is under `components/feature/meera/`, **not** `components/creator/`. Note it is written without semicolons; `MeeraCopilotChat.tsx` uses them and its L18-25 comment documents the fork as deliberate. Copy the *pattern*, not the formatting.
- Render after the bubble inside the `messages.map` (L357-368): `<CreatorToolResultRenderer toolName status data onApproveDraft onDiscardDraft onCancelSend onPrefillCounter />`.
- New prop `onDealMutated?: (dealId: string) => void` so the deal page can refresh after an approve.
- Consent and cap handling unchanged.

### 8.4 New `src/components/creator/meera/CreatorToolResultRenderer.tsx`

Card library for the nine tools, styled with the creator tokens (`border-border`, `bg-card`, `bg-muted`), not the brand `meera-*` family:

- `MyDealsCard`: rows with brand, status pill, amount, next action, secured badge.
- `BriefCard`: summary lines, extraction chips (deliverables, budget, deadline, usage, exclusivity), then `<DealRiskCard flags>` and `<PackageQuoteCard quote>`.
<!-- AMEND-0904: §14.1.b — in benchmark mode `PackageQuoteCard` renders the provenance line as the card's SUBTITLE (not a footnote) and labels the anchor "opening ask (benchmark)". -->
<!-- PRIYA: this pointer was MISSING from §14's pointer set. -->
- `PackageQuoteCard`: lines, discount, add-ons, total, anchor (hidden when withheld), provenance line; "Use in counter" button calls `onPrefillCounter({ amount: quote.anchor_value ?? quote.total_value, dealTerms })`.
- `MetricsCard`: the six strings with "Not available yet" fallbacks.
- `DealRiskCard` (shared, also used by the deal pages): one row per flag with a severity stripe (`bg-destructive` surface for CRITICAL with `text-destructive-foreground`, `bg-stage-negotiating` for WARN, `bg-muted` for INFO), title, detail, cost, action; non-dismissible flags have no dismiss control. Put it in `src/components/shared/deal-risk-card.tsx` (the directory exists; `deal-terms-summary.tsx` + `deal-terms-summary.test.tsx` are the naming and co-located-test precedent).
  <!-- PRIYA: token check — all six classes are real, but the spec pointed at src/index.css. That file does not exist; this is Tailwind v4 CSS-first and the tokens live in src/app/globals.css @theme inline. There is no tailwind.config. -->
  **All six tokens verified real**, defined in **`src/app/globals.css`** (there is no `src/index.css` and no `tailwind.config` — Tailwind v4 CSS-first, `@theme inline` at L191): `--destructive` L28/L148, `--destructive-foreground` L29/L149, `--stage-negotiating` L69, `--border` L36, `--card` L16, `--muted` L24, each with a dark-mode value. Also available: `text-stage-negotiating-fg` and `border-stage-negotiating-border` (L243-244), already used in `creator-chat.tsx`. `bg-destructive` is a **pale** surface — error text on it must be `text-destructive-foreground`, never `text-destructive`.
- `DraftCard`: the draft text in an editable `Textarea`, "Send" (calls `onApproveDraft(draft_id, text, amount, dealTerms)`), "Discard"; when `withheld`, shows the holdout note instead.
- `SendQueuedCard`: intent label, text, a 60-second countdown driven by `send_at`, "Cancel" until it passes; refused state shows `refused_reason`.
- `CampaignFitCard`: up to five rows with fit score, reasons, "Draft application" (sends the chat message "draft an application for {title}").
- `ApplicationDraftCard`: editable text and "Apply" which calls `creatorMeeraDrafts.approve`.

### 8.5 Paste-and-secure surfaces

- `src/components/creator/copilot/PasteBriefCard.tsx`: a `Textarea` (max 8000, counter), "Analyse with Meera" → `api.creatorBriefs.paste(text)`; renders `BriefCard` from 8.4 with the result, plus "Open in Meera" (opens the chat with the first message "Look at brief {brief_id}") and "Create secure link" which opens `SecureLinkDialog`.
- `src/components/creator/briefs/SecureLinkDialog.tsx`: shows the package the brand will see (no floors), end-brand name input (prefilled from `extraction.brand_name`), confirm → `createSecureLink`; result shows the URL with a copy button and the expiry.
- `src/pages/creator-copilot.tsx`: mount `<PasteBriefCard />` between the Meera card and `<ConsentScreen>` (imported L10 from `@/components/meera/ConsentScreen`, rendered L184), gated on `featureDisabled !== true` and consent (reuse `openMeera`'s probe at **L87**, which calls `api.creatorAgentPrefs.getPreferences()`, branches on `consent_accepted`, and catches `ApiError` code `FEATURE_DISABLED` → `setFeatureDisabled(true)` at L99-102; the static fallback card is L129-142). Remove the Phase A wording at **L125-128** (a JSX comment, four lines — not user-visible) and replace the CardDescription at **L150** (currently "Your AI manager — ask about deals, earnings, and metrics."; the card title at L148 is "Talk to Meera") with: "Your manager for briefs, prices and replies."
- `src/pages/secure-link.tsx` (new public route `/secure/:token`, above `/:handle` in `App.tsx`): fetches `secureLinks.preview(token)`; shows the creator's public card and the package; if a brand token exists, "Accept and open the deal" → `secureLinks.redeem(token)` → navigate to `/brand/chat?deal={deal_id}`; else "Sign in as a brand to continue" linking to `/brand/login?next=/secure/{token}`. Expired or redeemed links show a calm state.
  <!-- AMEND-0904: §14.2.c replaces the "Sign in as a brand" dead-end with an on-page "Continue with work email" flow (existing OTP + register routes, company name and industry prefilled from the link) and adds the brand-benefit copy block. Phase B1. -->

### 8.6 Deal pages

- `src/pages/creator-chat.tsx`: after `DealTermsSummary` in the Brand Proposal card (**L2434-2439**, element at L2437 ✓) and the Counter Proposal card (**L2639-2644**, element at L2642 ✓), render `<DealRiskCard flags={risks.flags} />` where `risks` comes from `api.deals.risks(selectedDeal.id)` loaded in `refreshDeal` (**L728-770** ✓ — note its supersede-token guard at L731-738; follow it for the risks fetch too, and note `afterDealMutation` at L885 already fans out to `refreshDeal` + `loadMessages`). Only when `liveApi`; ignore 403. Add a "Ask Meera" button on the proposal card that opens `MeeraCopilotChat` in a `Sheet` with the first message "Look at deal {id}" (import the chat; pass `onDealMutated={afterDealMutation}`).
- `handleSubmitCounterForm` (**L1466** ✓): send `dealTerms` and `meeraDraftId` when present. Today it passes only `{amount, message, deadline}` at L1480-1490 even though `api.deals.counter` has accepted `dealTerms` since Phase A — that gap is the actual work.
  <!-- PRIYA: CounterProposalFormData is NOT in creator-chat.tsx. It is exported from src/components/creator/deal-room/counter-proposal-form.tsx:14-19. And there is NO zod / react-hook-form / resolver in that file (grep returns zero hits) — it is plain React.useState at L38-44, so extending the interface needs no schema change, only an initializer update. -->
  **`CounterProposalFormData` lives in `src/components/creator/deal-room/counter-proposal-form.tsx:14-19`**, not in `creator-chat.tsx`. It is `{ proposedAmount: number; deadline: string; terms: string; message: string }`. Add `dealTerms?: DealTerms` and `meeraDraftId?: string`.
  **There is no zod schema and no react-hook-form resolver** in that file — state is plain `React.useState<CounterProposalFormData>` (L38-44) with hand-rolled validation. Extending the interface therefore requires only the interface edit plus the `useState` initializer; no schema change exists to make.
  `CounterProposalForm` gains an optional `initial?: Partial<CounterProposalFormData>` prop (props interface L21-28) used by "Use in counter"; it is wired at `creator-chat.tsx:3144`.
- `src/pages/creator-deals.tsx`: in `DealRow` after `DealTermsSummary` (L652) render the highest-severity flag as a single line with a link to the deal room; fetch risks lazily on row expand.

### 8.7 Settings and level-up

- `src/components/creator/MeeraSettingsSection.tsx`: delete the Phase A disclaimer at **L454-456** (`<p>` opens L454, text on L455: "Phase A is conversational only — Meera does not send or decline anything on your behalf yet, regardless of this setting.", sitting under the approval-level RadioGroup that closes at L453) and rewrite the represented note at **L603-605** (text on L604: "Represented mode: Meera warns you only — she never drafts or sends anything to brands.") to "Meera warns you only and never drafts anything addressed to a brand." The save error at L608 already uses `text-destructive-foreground` — keep it. Add a "Rate card" block: `rate_card_shareable` switch and three text inputs (reel, story set of 3, post) with the hint "Shown on your media kit and sent by Meera as a routine reply when on. Your floors stay private." Add a "Sent by Meera" section listing `creatorMeeraSends.list()` with status, countdown, cancel.
- Level-up prompt: `src/components/creator/meera/LevelUpPrompt.tsx`, shown on the co-pilot page when `prefs.level_up_eligible`; "Turn on routine replies" → `updatePreferences({...draft, approval_level: 1})`; "Not now" → `levelUpSeen()`.

### 8.8 Media kit page

- `src/pages/creator-media-kit.tsx` at public route `/c/:username/kit` (above `/:handle`), modelled on `creator-verified-metrics.tsx`; print stylesheet (`@media print`) so "Download PDF" is the browser's print. No `CreatorLayout`.
- Creator profile page gets a "Media kit" link to the public URL and a share button.

### 8.9 Routes (`src/App.tsx`)

Add imports and routes: `/secure/:token` (public), `/c/:username/kit` (public), both above `/:handle`.

<!-- PRIYA: verified against App.tsx. `/:handle` is L825 (the deliberate last-wins catch-all, comment L821-824); `/c/:username/verified` is L818 and is the only /c/ route. No existing /secure* route (only /features/secure-payments L652). Both insertions are conflict-free. -->
**Verified conflict-free.** `/:handle` is at **L825** and is deliberately last (comment L821-824: React Router 7 cannot match `/@:username`, so the whole first segment is captured). `/c/:username/verified` is **L818** and is the only `/c/` route — `/c/:username/kit` is a 3-segment sibling, no ambiguity. There is **no existing `/secure*` route** (`/features/secure-payments` L652 and the `/features/escrow` redirect L662 are unrelated), and `/secure/:token` is 2 segments so it outranks the 1-segment catch-all. Insert both between L818 and L825. `src/App.tsx` and `src/pages/admin-console.tsx` (nested under `/admin/*`) are the only `<Routes>` declarations in non-test code.

No new guarded creator pages are needed; everything creator-side lives on existing pages.

### 8.10 Frontend tests (Vitest 3.2.7, `vi.hoisted` + `vi.mock('@/lib/api')`)

Pattern confirmed. Copy `src/pages/creator-copilot-feature-disabled.test.tsx:33-53` — `vi.hoisted()` for the mock fns, then `vi.mock('@/lib/api', async () => { const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api'); return { ...actual, api: { ...actual.api, <ns>: { ...actual.api.<ns>, method: (...a) => mockFn(...a) } } }; })`. Prefer the `importActual` spread form over the full-replacement form used in `MeeraCopilotChat.test.tsx:27-36`.

- `PasteBriefCard.test.tsx`: paste → renders summary, flags, quote; error → inline message, no toast.
- `CreatorToolResultRenderer.test.tsx`: one render per tool payload; unknown tool renders nothing; `DraftCard` send calls approve with edited text.
- `deal-risk-card.test.tsx`: severity ordering, non-dismissible has no dismiss.
- `MeeraCopilotChat.tools.test.tsx`: `tool_start` then `tool_result` produce a card attached to the right bubble.
- `creator-chat.risks.test.tsx`: risks rendered on a pending proposal; 403 ignored.
- `secure-link.test.tsx`: preview, redeem when brand token present, expired state.
- `MeeraSettingsSection.ratecard.test.tsx`: PUT carries `rate_card_shareable` and `rate_card`; never carries `negotiation_holdout`.

---

## 9. Backend tests (JUnit 5 + Mockito, controllers by direct instantiation)

| Test class | Covers |
|---|---|
| `CreatorToolScopesTest` | scope strings per level and represented |
| `OnBehalfTokenServiceScopeTest` | six-arg mint carries the scope claim; five-arg still `SCOPE_DEFAULT` |
| `MeeraSessionServiceCreatorScopeTest` | creator turn mints level scope; brand turn unchanged |
| `CreatorMeeraToolControllerTest` | each route: scope refusal 403, brand principal 403, no consent 403, happy path, audit row |
| `CreatorToolCallValidatorTest` | unknown tool, tiers |
| `GetMyDealsExecutorTest`, `GetBriefExecutorTest`, `EstimateMyRateExecutorTest`, `GetMyMetricsExecutorTest`, `CheckDealRisksExecutorTest`, `DraftReplyExecutorTest` (floor leak 422, below floor 422, strategic override path, holdout withheld, represented 403), `SendRoutineReplyExecutorTest`, `RankOpenCampaignsExecutorTest`, `DraftApplicationExecutorTest` | |
| `RateQuoteServiceTest` | section 4.3 list |
| `DealRiskServiceTest` | section 5.2 list |
| `RoutineReplyServiceTest` | every refusal code, working-hours scheduling, idempotent replay |
| `MeeraDelayedSendJobTest` | sends due rows, marks SENT/FAILED, skips CANCELLED |
| `CreatorBriefServiceTest` | paste with AI, paste with fallback, ensurePlatformBrief idempotent, secure link total mismatch, floors stripped from package_json |
| `SecureLinkRedeemTest` | creates DIRECT campaign, collaboration IN_NEGOTIATION with terms, proposal message, link REDEEMED, second redeem 409 |
| `CreatorMeeraDraftControllerTest` | approve REPLY/COUNTER/DECLINE/APPLICATION, edited flag, approved count increments, level-up eligibility |
| `MediaKitServiceTest` | public kit 404 rules, rate card only when shareable, never floors |
| `CampaignFitServiceTest` | scoring, exclusions, below-floor flag |
| `DealServiceOfferHistoryTest` | four write points |
<!-- AMEND-0904: §14.3.a INVERTS the row below. A brand viewer now KEEPS `agent` and the new `auto_sent`; only `send_log_id`, `draft_id`, `intent`, `edited` are stripped. -->
<!-- PRIYA: this pointer was MISSING from §14's pointer set, and this is the sharpest case of the three — an engineer writing this test from §9 writes the exact assertion §14.3 forbids. Corrected cell: "brand keeps `agent` + `auto_sent`, never sees `send_log_id`/`draft_id`/`intent`/`edited`; creator sees all". -->
| `DealMessageMetadataStripTest` | brand never sees `agent` keys |
<!-- PRIYA: "unchanged" is stale. §13.3 condition 2 and PRIYA-COMPAT-0904 §7 condition 2 both REQUIRE widening this test's scan roots to `service/**` and `job/**` (an ~8-line restructure of L68-76, not a one-line edit). §14.5.a repeats the requirement. Read this cell as "widened allow-list still passes". -->
| `InfoBarrierTest` | unchanged allow-list must still pass: no new class imports the repository |
| `InfoBarrierRuntimeTest` | extend: `CreatorToolDtos.PackageQuote` on a secure link never contains floor values; brand context never contains `tools_enabled` |
| `MeeraPhaseBBootValidationTest` | Testcontainers, all six migrations apply on stock MySQL 8, `ddl-auto=validate` boots; skipped without Docker |
| `AuthRateLimitFilterPhaseBBucketsTest` | the three new buckets |

Python: `tests/tools/test_creator_schemas.py`, `tests/tools/test_loop_creator_dispatch.py` (path lookup, idempotency, routine-reply scope decline), `tests/prompt/test_creator_prompt.py` updates, `tests/prompt/test_creator_context_drift.py` updates, `tests/security/test_info_barrier.py` updates, `tests/routes/test_brief_extract.py`, `tests/routes/test_chat_creator_audience.py` updates.

---

## 10. Build order and dependencies

<!-- AMEND-0904: §14.5 splits this table into Phase B0 (days 1-3 plus the draft_reply slice of day 5) and Phase B1 (the rest), with a live-metrics gate between them. The day numbers below still hold inside each phase; the B0/B1 column assignment is in §14.5's table. Phase A must be DEPLOYED before B0 day 8, or B0 cannot be measured. -->

Work in this order so each engineer always compiles against something that exists:

| Day | Backend | AI service | Frontend |
|---|---|---|---|
| 1 | Migrations 2.1 to 2.6, entities, repositories, `BriefDtos`, `CreatorToolDtos`, `CreatorContextResponse` +5 (27→32) and its **one** call site, `CreatorAgentPreferencesService.getByProfileId`, `PreferencesResponse` +5 and its **4** sites, `UpdatePreferencesRequest` +2 and its **9** sites, `InfoBarrierTest` scan-root widening, boot test | `creator_schemas.py`, schema tests, `PROMPT_VERSION` bump, `ENDPOINT_SCOPES["brief_extract"]`, drift test update (4 assertions) | TS types in `api.ts`, namespaces with mocks, `meera-api.ts` payload types and `MeeraRole` **export keyword** |
| 2 | `CreatorToolScopes`, `OnBehalfTokenService` overload, `MeeraSessionService` scope, `CreatorToolCallValidator`, `CreatorMeeraToolController` skeleton with the five read executors, rate bucket | `assembler.py` tool selection and Block A/B, persona, `loop.py` dispatch, test updates | `CreatorToolResultRenderer` with the read cards, `MeeraCopilotChat` tool events |
| 3 | `RateQuoteService`, `RateAddOns`, `DeliverableType`, `DealRiskService` and rules, `/deals/{id}/risks` | brief extraction route and schema | `DealRiskCard`, `PackageQuoteCard`, deal page integration, counter prefill |
| 4 | `CreatorBriefService`, `MeeraBriefAiClient`, fallback extractor, `CreatorBriefController`, secure links and redemption, offer history | | `PasteBriefCard`, `SecureLinkDialog`, `/secure/:token` page |
| 5 | `MeeraDraft`, `DraftReplyExecutor`, `CreatorMeeraDraftController`, approvals, level-up eligibility | | `DraftCard`, approve flow, `LevelUpPrompt` |
| 6 | `RoutineReplyService`, `MeeraSendLog`, `MeeraDelayedSendJob`, sends routes, metadata strip, DPDP export extension | routine-reply scope decline | Sends section, `SendQueuedCard`, settings copy changes |
| 7 | `MediaKitService`, media kit routes, prefs rate card, `CampaignFitService`, rank and application executors | | Media kit page, rate card settings, campaign fit and application cards |
| 8 | Full `mvn -o test`, schema diff, Kavya QA, Kabir barrier review | full pytest | tsc, build, vitest |

Contract points that must match across engineers on day 1: the `BriefExtraction` JSON (2.11), the `CreatorToolDtos` field names (3.5), the tool schema property names (7.1), the `tools_enabled` context field (2.10), the `DealMessage` metadata keys (2.8).

---

## 11. Acceptance: the zero-context tester's ten questions

Phase B is done when Priya answers all ten positively from code, with the same rule as Phase A (live smoke pending where this machine cannot run Docker).

1. Paste a WhatsApp brief: does the creator get a summary, a rate, and flags within one request, and does the raw text survive an AI outage?
2. Does every creator tool refuse a brand principal, a missing consent, and an out-of-scope level, at the server?
3. Can any tool result, draft, secure link, or media kit ever carry a floor value or the agency name to a brand? Show the strip points and the tests.
4. Does `draft_reply` refuse a below-floor counter unless the creator marked the deal strategic, and is the override logged?
5. At level 1, what exactly can Meera send alone, what stops a price or a date from going out, and can the creator cancel inside 60 seconds?
6. Do the ten risk rules fire on the right data and scale severity with deal value? Show one firing and one non-firing test per rule.
7. What does the brand see when it opens a secure link, what is created on redeem, and where does funding happen?
8. Does the counter from "Use in counter" carry structured deal terms, and does offer history record it as Meera-drafted?
9. Does the holdout withhold only the anchor and counter drafts, deterministically, and for existing creators not at all?
10. Do all six migrations apply on a stock MySQL 8 and boot with `ddl-auto=validate`, and do old deals still load with no drafts, no history, and no risks?

---

## 12. Decisions still open (build with the default, flag to Swapnil)

<!-- AMEND-0904: rewritten. Was two items; now four, each with a recommended default that ships if no ruling arrives by B0 day 1. Full reasoning and numbers in DECISIONS-0904.md in this folder. -->

<!-- PRIYA (review of the table below; full working in 14.6):
     Item 1 - the flip property is declared in FOUR files, not the "three places" 14.3.d claims:
       influora-api/src/main/resources/application.yml:199, influora-api/env.example:29,
       deploy/hostinger/docker-compose.hostinger.yml:158, deploy/utho/docker-compose.utho.yml:179.
       (influora-ai/env.example also exists but carries no Java-side flag; there is no root env.example.)
     Item 3 - "88%" is the credit sheet's 88.5% rounded (finance/meera-credit-sheet.md L21). 33% / 95%
       at $2.00 are exact (L71). The current 0.75 default is verified at config.py L455-457. The brief
       cap 14.4.b sets is 0.25 USD/month - state the number here so all three documents carry it.
     Item 4 - the five metrics are right, but metric 3's second threshold (">= 25% approved unedited")
       is UNMEASURABLE as the spec stands: 3.7's approve sets status SENT, never APPROVED or EDITED,
       and 2.4's meera_drafts DDL has no `edited` column. Add `edited TINYINT(1) NOT NULL DEFAULT 0`
       to 2.4 on B0 day 1 - free now, a second migration mid-measurement later.
     Every other number in this table was checked against source and is correct. -->

| # | Decision | Default that ships | Flip cost |
|---|---|---|---|
| 1 | **Brand-facing "Meera" marker on drafted and auto-sent messages.** | **Shown to brands** (§14.3): a muted "Drafted with Meera" / "Sent by Meera for {creator}" label. Was: stripped. Reason: AI-transparency obligations already apply in the EU (AI Act Art. 50, from August 2026) and Indian advisories point the same way; a brand that later learns it negotiated with an undisclosed AI is a trust loss on the platform, not the creator. | One property, `influora.meera.brand-facing-stamp=false`, restores the full strip. |
| 2 | **Referral incentive on creator-brought brands.** | **Record it now, pay it later** (§14.2.d): `campaigns.introduced_by_creator_profile_id` is written at every secure-link redeem from B1 day 1, so whatever the ruling is it can apply retroactively to every creator-introduced deal. Recommended ruling: platform fee 10% instead of 15% on deals with that brand for its first 90 days, creator sees a "you brought this brand" badge. No fee logic ships until ruled. | Column is nullable and read by nothing; a ruling of "no incentive" costs one unused column. |
| 3 | **Creator AI cap.** | **`AI_CREATOR_MONTHLY_CAP_USD=2.00`** on the creator gate (§14.4.d), up from 0.75. The credit sheet (§6, §8 item 3) already recommends this: Typical falls from 88% to 33% of cap, Heavy from 253% to 95%. Brief extraction is capped separately (§14.4.b). | Env var. |
| 4 | **The B0 → B1 gate.** | The five metrics and thresholds in §14.5.c, measured over the first 14 days after B0 is live or the first 100 pasted briefs, whichever comes first. B1 does not start until Swapnil has seen the numbers. | Thresholds are Swapnil's to move; the *measurement* is not optional. |

Everything else in this document is buildable now.

---

## 13. Priya review

**Reviewer:** Priya (CTO). **Baseline:** HEAD `8c7b18b`. **Method:** every symbol, signature, line reference, record arity, test assertion and property name the spec names was checked against the actual source, not against the fact sheets. Corrections are applied inline above, each tagged `<!-- PRIYA: ... -->`.

### 13.1 Corrections applied

**Would not compile (10)**

| # | § | Was | Is |
|---|---|---|---|
| 1 | 3.7 | `dealService.sendMessage(principal, dealId, body, key)` | `sendMessage` takes **three** args — `DealService` L619-620 has no `idempotencyKey` parameter. `approve` must self-guard on `draft.status == PENDING` instead. |
| 2 | 3.4 | `AuditLogService.OUTCOME_OK` | Does not exist. The constants are `OUTCOME_ALLOWED` / `OUTCOME_REJECTED` / `OUTCOME_FAILED` (L32-34). |
| 3 | 3.2, 3.4 | `recordToolCall(..., tier, ...)` passing enums | The tool-name and tier params are `String` (L44-52). Needs `tool.name()`, `tier.name()`. |
| 4 | 2.8 | `DealService.toDealMessageResponse` | No such method. It is `toMessageResponse(DealMessage)` (call sites L611, L664) — **and it has no `principal` in scope**, so the brand strip needs a signature change plus a fix to `publishToStream`, which would otherwise push unstripped metadata over SSE. |
| 5 | 4.1 | "create `com.influora.domain.enums.DeliverableType`" | **It already exists** with a different, platform-shaped taxonomy (`INSTAGRAM_REEL`, …), persisted on `Deliverable`, parsed by `ContractService` L512, asserted in 12 test classes. Renamed the new one to `com.influora.service.rates.QuoteDeliverableType` and specified the platform-to-pricing mapping that real proposal metadata requires. |
| 6 | 3.6 | `MeeraContextService.ACTIVE_DEAL_STATUSES` | `private static final` (L93) and in a **different package** from the executors. Must be promoted to a shared public constant. |
| 7 | 3.6 | `MeeraContextService.deriveTier` "extract to package-visible" | Also cross-package; needs `public`. It also returns `"MEGA"`, which is **not** a `CreatorTier` value — never `CreatorTier.valueOf(tier)`. |
| 8 | 3.9 | `CreatorAgentConversationService.export` | The method is `exportConversation(userId, conversationId)` (L84). Also requires widening the `ConversationExportResponse` record from 3 to 5 components. |
| 9 | 7.5 | `verify_creator_token(token, endpoint=...)` | Signature is `(token, *, endpoint, body_creator_profile_id)` — `body_creator_profile_id` is required and keyword-only; the function is **synchronous** and must be offloaded via `anyio.to_thread.run_sync` (F-09). |
| 10 | 7.5 | `claude.complete_with_forced_tool(...)`, result `.text` | It is `async` and **keyword-only** (bare `*` at claude.py L364); the parsed payload is `result.tool_input`. `.text` does not exist on `ClaudeToolResult`. |

**Would compile but fail at boot, at runtime, or in CI (7)**

| # | § | Defect |
|---|---|---|
| 11 | 2.3 | `token_hash CHAR(64)` — `ddl-auto=validate` rejects `CHAR` against a `@Column(length=64)` String ("wrong column type … found [char]"). This is the exact live boot break `V20260718150000` was written to repair. Changed to `VARCHAR(64)` and promoted the rule into §0.5. |
| 12 | 7.5 | `ENDPOINT_SCOPES` has **no `brief_extract` key** (`service_token.py` L53-69), so `verify_creator_token(endpoint="brief_extract")` rejects every call. Must be registered. |
| 13 | 3.8 / 7.5 | Token-type contradiction: 3.8 said mirror `MeeraVoiceAiClient` (a **service** token); 7.5 requires `SCOPE_CREATOR`. `service_token.py` documents that segregation as deliberate and bidirectional (Kabir), so a service token can never satisfy it. Resolved to mirror `CreatorSuggestionAiClient` and pass `creator_profiles.id` — the two sides also disagreed on which id space. |
| 14 | 2.9 | Holdout assigned in `getOrCreatePreferences`, which **does not call `newWithDefaults`**. The row is created by the private `createWithComputedDefaults` (L103-110), reached from four methods — and in the real flow usually from `recordConsent`, not `getOrCreatePreferences`. As written, most Phase-B creators would have got `holdout = false`. |
| 15 | 3.6 | `secured` via `escrowHoldRepository.findByCollaborationIdAndStatus`. That finder exists, but it reads the direct `collaboration_id` column, which is **NULL on every ordinary brand-funded hold** — CR-49/CR-50 fixed exactly this and `DealService` L2019-2026 says so in a comment. As written, `secured` reports `false` on genuinely funded deals. Corrected to `hasEscrowForCollaboration(id, Set.of(FUNDED))`. |
| 16 | 7.3 | `loop.py` L452 is `TOOL_TO_SPRING_PATH[tool_name]`, a bracket subscript **outside** the `try` (which opens at L465). Widening `is_known_tool` without the path edit in the same commit is an unhandled `KeyError` mid-stream, and no existing test covers that path. Called out as a paired edit with a required regression test. |
| 17 | 7.3 | The idempotency edit named only `loop.py` L453-455 and missed **L472** (`allow_retry=`), which would leave `send_routine_reply` silently retryable. |

**Materially understated work (6)**

| # | § | Was | Is |
|---|---|---|---|
| 18 | 3.7 | `CounterRequest` "three existing construction sites … so only tests" | **14** sites: `DealServiceTest` L605/645/708/749/768/982/1654/2203/2316 (9) and `DealControllerTest` L142/143/153/165/284 (5). All enumerated inline. |
| 19 | 3.10 | Prefs record changes listed no call sites | `PreferencesResponse` 18→23 components, **4** construction sites; `UpdatePreferencesRequest` 16→18, **9** sites. All enumerated inline. |
| 20 | 2.10 / 7.2 | §2.10 said four components (27→31); §7.2 separately added `holdout_until` as "component 32"; the day-1 table said "+5" | Reconciled to **five** (27→32) in one place. |
| 21 | 2.10 | Drift test: "add distinctive values" | `test_creator_context_drift.py` breaks in **four** places, parses the real `MeeraContextDtos.java`, and `pytest.fail`s (never skips) if it is missing — so Java and Python must land in the same commit. Its L98 regex also requires the render variable be literally named `ctx`, not `context`. |
| 22 | 7.2 | `build_block_a_creator` signature change | Has **three** call sites (`assembler.py:830`, `test_creator_prompt.py:90`, `test_info_barrier.py:222`); none were listed. |
| 23 | 7.4 | Test impact partly listed | Replaced with a full 13-row table. Notably `"none in this phase"` is in **`assembler.py:444`**, not the persona, so a persona-only rewrite leaves Block A contradicting the tool list; and five zero-tool assertions **stay green** under the §7.2 degrade rule, which the spec never said. |

**Wrong references, resolved (9)**

| # | § | Correction |
|---|---|---|
| 24 | 3.8 | `influora.public-base-url` **does not exist**. The SPA origin is `influora.web-base-url` (application.yml L133). |
| 25 | 8.1 | `dealTerms?: DealTerms` is **already** on `api.deals.counter` (api.ts:2173, Phase A A2). Only `meeraDraftId` is new; the real gap is `handleSubmitCounterForm` never passing it. |
| 26 | 8.1 | `CreatorAgentPreferencesUpdate` is **already** an `Omit` (api.ts L6059-6062) — widen it, don't declare it. Knock-on: `MeeraSettingsSection.tsx:125` aliases it as `Draft`, so `toDraft()` must produce the two new non-omitted fields or `tsc` fails. |
| 27 | 8.3 | `MeeraChatPanel.tsx` is at **`src/components/feature/meera/`**, not `src/components/creator/`. L538 itself is correct. |
| 28 | 8.2 / 8.3 | The stream client is **`src/hooks/useMeeraStream.ts`**, not `meera-api.ts`, and it **already** declares and dispatches `onToolStart` / `onToolResult` (L47-57, L184-193). No hook change is needed. |
| 29 | 8.2 | `MeeraRole` is `type`, **not `export type`** (meera-api.ts:399) — the `export` keyword must actually be added. |
| 30 | 8.6 | `CounterProposalFormData` is in `src/components/creator/deal-room/counter-proposal-form.tsx:14`, and there is **no zod / react-hook-form resolver** — plain `useState`, so there is no schema change to make. |
| 31 | 8.4 | Tokens live in **`src/app/globals.css`** (`@theme inline`); there is no `src/index.css` and no `tailwind.config`. All six named tokens verified real. |
| 32 | 2.6, 3.3, 7.3, 7.5 | Off-by-one and wrong-line references: `createProposal` L**243**; `listMessages` L**602** / `sendMessage` L**619**; `doSendTurn` L**309**, mint L**397**; the money decline is L**494-509**, not L474 (L474 is the `except` line). |

**Hand-waves resolved by reading the code (8)**

| # | § | Resolution |
|---|---|---|
| 33 | 3.6 | `recentMedia` = `mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(profileId, PageRequest.of(0, RECENT_MEDIA_LIMIT))`, from the single caller `ScoreCalculationJob` L280-291. `calculate` takes **two** args. |
| 34 | 3.7 | `CreatorCampaignService.apply(AuthPrincipal, String, ApplyRequest)` returns `ApplyResponse` (L208); `ApplyRequest(@Size(max=2000) String message)`. Confirmed correct as written. |
| 35 | 3.9 | ShedLock **exists** (`V68__shedlock.sql`, five jobs annotated). The "single replica is fine" escape hatch is void; `@SchedulerLock` is mandatory. |
| 36 | 3.9 | Brand-message check = the existing `dealMessageRepository.existsByCollaborationIdAndSenderType(id, DealSenderType.brand)`. |
| 37 | 3.6 | Unread computation is inline at `DealService` L2002-2007; the extraction target must be **public**, not package-visible (cross-package caller). |
| 38 | 4.3 | `findRateBandCandidates` takes **only** `niche` — no date filter, no tier filter — and its projection carries **no timestamp**, so the "last 90 days" claim is unimplementable without widening the query. Its javadoc also carries a mandatory Kabir k-anonymity gate: no row may be serialized directly. |
| 39 | 5.1 | `Collaboration.getUsageChannels()` and `getExclusivityBrands()` return **JSON Strings**, not Lists; `Campaign` has `budgetMin`/`budgetMax`, not a single `budget`. |
| 40 | 3.4 | `CreatorMeeraController.requireFeatureEnabled()` is **private** — copy the helper, don't call it. |

**Design defects flagged and corrected (2)**

| # | § | Defect |
|---|---|---|
| 41 | 3.6 | **Id-space break.** `RateQuoteService.floorTotal`, `DealRiskService.evaluateDeal` / `evaluateBrief` and `CreatorBriefService.ensurePlatformBrief` all take a `creator_profiles.id`, but `CreatorAgentPreferencesService` exposes prefs **only** by `users.id`. Without a new `getByProfileId`, those services either cannot compile or reach for `CreatorAgentPreferencesRepository` and break the info barrier. Added to day 1. |
| 42 | 3.4 | **IP-keyed rate limit is a platform-wide cap.** Every `/internal/meera/creator/*` call arrives server-to-server from one Python process, so a single IP bucket at 60/window is shared by all creators — the busiest one starves everyone. Changed the default to keying on the on-behalf JWT's `sub`. |

### 13.2 Remaining risks

1. **The info barrier does not cover seven of the new classes.** `InfoBarrierTest` scans only `service/meera/**` and `web/**`. `RateQuoteService`, `DealRiskService`, `CreatorBriefService`, `MediaKitService`, `CampaignFitService`, `RoutineReplyService` and `MeeraDelayedSendJob` all sit outside it — exactly the classes that handle floors. §3.6 now instructs widening the scan roots to `service/**` and `job/**`. **If that widening is skipped, this phase ships a decorative barrier.** Blocking for Kabir's review.
2. **Brand-strip on the SSE path.** §2.8's strip is straightforward on the two REST paths, but `publishToStream` reuses one DTO for both parties on a shared emitter registry. The spec now says to publish the stripped shape, which costs the creator the `draft_id`/`edited` stamp on pushed rows until refetch. Acceptable for Phase B — note it, don't silently regress it later.
3. **`approve` has no idempotency key** on the REPLY path (correction 1). The `status == PENDING` guard is right but is a read-then-write; under the existing row lock it is safe, without one a double-tap double-sends. Use the same `IdempotencyService` wrapper `counter`/`reject` use, or lock the draft row.
4. **`creator_briefs.raw_text` is uncapped in SQL** while `CreatorBrief.paste` caps at 8000. Any future writer bypassing the factory loses the cap. Cosmetic today.
5. **`RateQuoteService` provenance branch (b) is unbuildable as specified** (correction 38). The fix is a one-line query widening, but it touches a query carrying a Kabir gate — get his sign-off with the change, not after it.
6. **`deal_offer_history.sequence_no` has no uniqueness constraint** and four write points across three transactions. Two concurrent counters can produce duplicate sequence numbers. Add `UNIQUE KEY uk_doh_collab_seq (collaboration_id, sequence_no)` and derive the number under the existing row lock, or accept ties and order by `created_at`.
7. **Nine new tool schemas ship in one PR.** The combinator ban (§0.9) is a hard 400 that takes down *every* Meera turn, brand included, and CI surfaces it as `provider_timeout`. The extended `test_tool_schema_anthropic_valid.py` is the only thing between a bad schema and a full outage — make it a required check, not advisory.
8. **Secure-link redemption is the least-specified part of the spec** (§8.1). `CampaignService.create` takes a `CampaignWriteRequest` with `BudgetDto`/`TimelineDto`, not a scalar budget; `Collaboration.propose` is `(id, campaignId, creatorUserId, amount, currency, message)`. Both are reachable, but this path deserves its own design pass before day 4 rather than one paragraph.
9. **Day-1 cross-repo coupling is unavoidable.** The creator-context drift test parses the Java DTO from Python, so the Java record, the Python tuple, the render lines and the drift fixture must be one commit. Three engineers on parallel branches means day 1 merges first or the Python suite is red for everyone.
10. **`ci/stale-comment-check.py` requires the `PROMPT_VERSION` bump line in the diff** whenever prompt content changes. Persona edits landing in a different PR from the config bump fail that gate.

### 13.3 Verdict

**Buildable as written: no.** An engineer following §3.7, §3.4, §4.1, §2.8, §3.9, §7.5 or §2.3 literally would hit ten compile errors, one `ddl-auto=validate` boot failure, an auth path that rejects 100% of calls, and a name collision with a persisted enum — plus two silent correctness regressions (`secured` false on funded deals; the holdout never assigned) that no listed test would catch.

**Buildable as corrected: yes**, on three conditions:

1. The day-1 block in §10 lands as one merged commit across all three services before parallel work starts. The record arities, `getByProfileId`, the `ENDPOINT_SCOPES` entry and the drift-test coupling are day-1 or nothing.
2. `InfoBarrierTest`'s scan roots are widened to `service/**` and `job/**` in this phase (risk 1). Without it the barrier does not cover the seven classes that handle floors.
3. `deal_offer_history` gets its uniqueness constraint (risk 6) before offer history is read by anything user-facing.

Everything else in 13.2 is a risk to carry, not a blocker. The two items in §12 remain genuinely open and correctly flagged; nothing else in this document needs a Swapnil ruling.

---

## 14. Product-risk amendments (2026-09-04)

**Origin:** an owner-requested honest critique after Priya's compatibility pass (`PRIYA-COMPAT-0904.md`). Five product-level worries, each resolved here with a mechanism, a test, and where needed a ruling. Each subsection names the sections it supersedes; the `<!-- AMEND-0904 -->` pointers in those sections lead back here. Every symbol and line cited below was read from the working tree on 2026-09-04.

### 14.1 Cold-start pricing is a formula, not data

**The worry, made concrete.** Until a creator has 3 priced deals (4.3 step 1a) or their tier has 5 closed deals in 90 days (1b), every quote comes from `RateEstimationService` (1c), <!-- PRIYA: three line refs wrong and one factual claim wrong. Verified against RateEstimationService.java at HEAD:
     - TIER_BASE_RATES L35-42 CORRECT, all five value pairs CORRECT including MEGA 500,000-2,500,000, and "per post" is the file's own wording (L34).
     - engagement multiplier: the THRESHOLDS are L96-98 (>5 -> 1.3, >3 -> 1.15, <1 -> 0.7). L100-101 only APPLIES them. Cite L96-98.
     - category multiplier: CATEGORY_MULTIPLIERS is DECLARED at L45-55 (FASHION 1.3, BEAUTY 1.25, LIFESTYLE 1.2, TRAVEL 1.15, FOOD 1.1, TECH 1.05, FITNESS 1.0, GAMING 0.95, EDUCATION 0.9). L104-108 only computes the max over the creator's categories. Cite L45-55.
     - quality multiplier L118-122 (declaration + branch); L124-125 applies it. "L118-123" reaches a blank line.
     - "no source comment" is WRONG. L34 IS a source comment ("Base rates per post by follower tier (INR)") and L37-41 carry per-tier follower bands. Those bands match determineTier (L153-159) except the NANO one, which says "1K-10K" while determineTier returns NANO for every value below 10,000 including 0.
     What section 14 MISSED after L123, and a calibration pass has to know:
     - Math.round on both bounds (L145-146). Every quote is a whole rupee.
     - currency is the hard-coded string "INR" (L147). There is no multi-currency path.
     - a confidence score of 50 / 80 / 100 (L129-135) that nothing in 4.3 reads. If a benchmark quote is going to carry an honesty label, confidence is the field that already exists to carry it. Use it or say why not.
     - metric.isEmpty() returns min=max=0 AND tier="UNKNOWN" (L79-82). "UNKNOWN" is not a TIER_BASE_RATES key and not a CreatorTier value; 4.3 step 7's tier branch must never receive it. 4.3 1c's `min > 0` guard is what keeps it out - keep that guard. -->
whose inputs are hard-coded constants with no source comment and no calibration: `TIER_BASE_RATES` (L35-42, "per post": NANO 1,000-5,000, MICRO 5,000-25,000, MID 25,000-100,000, MACRO 100,000-500,000, MEGA 500,000-2,500,000), an engagement multiplier (L98-101: >5% ×1.3, >3% ×1.15, <1% ×0.7), a category multiplier (L105-110: FASHION 1.3 … EDUCATION 0.9) and a quality multiplier (L118-123). Worked example, §4.3 as written:

<!-- PRIYA: the min/max and unit columns are CORRECT - recomputed from the code, not from the table (NANO base 1,000/5,000 x engagement 1.0 x BEAUTY 1.25 x quality 1.0 = 1,250/6,250; 1,250 + 0.65 x 5,000 = 4,500. At 5.5% engagement the multiplier is 1.3: 1,625/8,125, unit 5,850).
     The ANCHOR column is WRONG for a table labelled "as written". 4.3 step 6 as written is `min(total x 1.15, rangeMax)` when a range exists - and benchmark mode is the ONLY branch that HAS a range. So the anchors today are 4,950 -> **5,175** (min(4,500 x 1.15, 6,250)) and 6,435 -> **6,727.50** (min(5,850 x 1.15, 8,125)). The x1.10 in this header is what 14.1.e INTRODUCES; applying it to the baseline makes the amendment look like it changes nothing about the anchor when in fact it cuts it by ~4.3%.
     Corrected header: "Anchor for 1 reel (`min(total x 1.15, rangeMax)`, 4.3 step 6 as written)" with 5,175 and 6,727.50 in the cells. -->
| Creator | min / max | 4.3 step 1c unit (`min + 0.65 × (max − min)`) | Anchor for 1 reel (`× 1.10`) |
|---|---|---|---|
| NANO, 3,000 followers, 2.4% engagement, BEAUTY, no quality score | 1,250 / 6,250 | **4,500** | **4,950** |
| Same creator at 5.5% engagement | 1,625 / 8,125 | **5,850** | **6,435** |

Nobody on this team has evidence that a 3,000-follower creator closes a reel at 4,950. There are zero completed deals with an `agreed_rate` in any seed migration, and the constants were written for the brand-side estimate card, never checked against a close. If they are high, Meera tells every nano creator to open high, brands walk, and the creator blames Meera. If they are low, Meera anchors the whole cohort under market and the platform-band branch (1b) later "confirms" it from Meera's own deals. Either way the number is confident and the provenance line is small.

**Changes (all in `RateQuoteService`, §4.3, unless noted):**

<!-- PRIYA: recomputed and CORRECT. unit = 1,250 + 0.50 x 5,000 = 3,750; anchor 3,750 x 1.10 = 4,125 under 14.1.e's new rule. Flag for the reader: the 4,125 quoted here is post-14.1.e, not current behaviour - see the table correction above. -->
a. **Benchmark mode uses the midpoint, not the 65th percentile.** Step 1c becomes `unit = min + 0.50 × (max − min)`. The opening-ask lift is the anchor's job (step 6), not the unit's. NANO example: unit 3,750, anchor 4,125. Provenance string unchanged: `"benchmark, not market data"`, sample 0.

b. **In benchmark mode the persona must say so before any number.** Add to the "Negotiation rules" block of `MEERA_CREATOR_PERSONA` (§7.4):
   ```
   - When a quote's provenance is "benchmark, not market data", say in your first
     sentence that this is a benchmark estimate, not what creators like them have
     actually closed at, before you say any figure.
   ```
   Test: `tests/prompt/test_creator_prompt.py` pins the substring `"benchmark estimate, not what creators like them"`. `PackageQuoteCard` (§8.4) renders the provenance line as the card's **subtitle** in benchmark mode, not a footnote, and labels the anchor "opening ask (benchmark)".

<!-- PRIYA: recomputed and CORRECT. n=1, ownMedian 2,000, benchmarkUnit 3,750 -> (1 x 2,000 + 3,750) / 2 = 2,875. Add the rounding rule: RateEstimationService rounds both bounds to whole rupees (L145-146) and this blend does not, so `unit` must be rounded the same way or a quote and its own recomputation differ by paise. -->
c. **Shrinkage for 1-2 own deals instead of a hard 3-deal threshold.** Step 1a: with `n` own priced deals in 180 days, `n ≥ 3` → own median (unchanged). `n ∈ {1, 2}` → `unit = (n × ownMedian + benchmarkUnit) / (n + 1)`, provenance `"your last N priced deals, blended with benchmark"`, sample `n`. One real deal at 2,000 moves the NANO example from 3,750 to 2,875; the creator's own data matters from deal one. `n = 0` → step 1b/1c.

d. **Tier band (step 1b) gets two honesty guards.** Besides the `≥ 5` k-anonymity floor: (i) the 5 deals must come from **≥ 3 distinct `workspaceId`s**, else fall through to 1c; (ii) compute `meeraAnchoredShare` = fraction of those deals whose `deal_offer_history` holds a `MEERA_COUNTER` row. When `> 0.5`, provenance becomes `"N closed deals in your tier in 90 days, mostly Meera-quoted"`. This is the feedback-loop label: it does not stop the loop, it stops the loop from being invisible. Needs the projection widening Priya already required (§4.3 1b: add `co.updated_at AS updatedAt`) plus `co.id AS collaborationId` so the share can be counted with `DealOfferHistoryRepository.countByCollaborationIdInAndEvent(Collection<String>, OfferEvent)` (new derived query)
   <!-- PRIYA: the derived-query NAME is valid Spring Data for 2.6's entity shape (`collaborationId` String, `event` OfferEvent) - but the query is WRONG for what 14.1.d wants. It counts ROWS, not distinct collaborations, and 2.6 permits several MEERA_COUNTER rows on one collaboration at different `sequence_no` values (the uniqueness constraint 13.2 risk 6 adds is on (collaboration_id, sequence_no), NOT on (collaboration_id, event)). A two-counter negotiation pushes `meeraAnchoredShare` above 1.0 and fires the > 0.5 label off a single deal. Use instead:
       @Query("select distinct h.collaborationId from DealOfferHistory h where h.collaborationId in :ids and h.event = :event")
       List<String> findDistinctCollaborationIdsByEvent(@Param("ids") Collection<String> ids, @Param("event") OfferEvent event);
     and take .size(). -->
   <!-- PRIYA: the projection widening is CONFIRMED buildable. CollaborationRepository's native query (@Query L63-74, method L75) aliases collaborations as `co`, so both `co.id AS collaborationId` and `co.updated_at AS updatedAt` are valid: `collaborations.id VARCHAR(26) PRIMARY KEY` and `collaborations.updated_at TIMESTAMP` both exist (V6__creators_collaborations.sql L27, L36). Add the matching getters to the `RateBandCandidateRow` interface (repo L40-50).
     CAVEAT section 14 did not state: `updated_at` is declared ON UPDATE CURRENT_TIMESTAMP, so it is last-modified, not completed-at. Any later write to a COMPLETED collaboration - dispute close, payout state change, metadata patch - refreshes it and readmits an old deal into the "90 days" window. Either accept that and soften the provenance string to "closed deals in your tier, recently", or add a real `completed_at`. Do not ship a precise-sounding "in 90 days" on a mutable timestamp. -->. Never serialise a row (Kabir gate, unchanged).

<!-- PRIYA: WRONG as written, and it inverts the existing structure. `min(total x 1.15, rangeMax)` is unimplementable for own-history and tier-band mode: both of those branches produce a single MEDIAN (4.3 steps 1a, 1b), so there is no rangeMax to clamp against. The only branch that has a range is benchmark mode - the branch this rule assigns the un-clamped 1.10 to. 4.3 step 6 as written already says "when a range exists, else total x 1.10", so 14.1.e swaps which branch gets which formula and leaves the clamp pointing at nothing.
     Corrected step 6:
       own history (1a) or tier band (1b): anchor = total x 1.15, no clamp (there is no range).
       benchmark (1c):                     anchor = min(total x 1.10, rangeMax).
     That keeps the clamp where a range actually exists and still delivers the smaller benchmark lift 14.1.e is asking for. Null under holdout, unchanged. -->
e. **Anchor lift is smaller in benchmark mode.** Step 6: `min(total × 1.15, rangeMax)` when provenance is own-history or tier band; `total × 1.10` in benchmark mode. Null under holdout, unchanged.

<!-- PRIYA: WRONG MODEL. `recordAuthRejection` (AuditLogService L70-82) takes NO detail map and hard-codes actorType=ACTOR_SERVICE and outcome=OUTCOME_REJECTED. Copying it gives you a method that cannot carry the detail map this item is entirely about. The right model is `recordAdminAction(String actorId, String eventType, String outcome, Map<String,Object> detail)` at L94-106 - it already does exactly this shape, leaves workspaceId null, and calls JsonLists.toJsonObject(detail).
     So `recordCreatorEvent` is a ~12-line copy of L94-106 with actorType ACTOR_HUMAN (or a new ACTOR_CREATOR constant) and outcome hard-coded to OUTCOME_ALLOWED - `outcome` is NOT NULL on the table and the proposed 3-arg signature supplies none.
     SCHEMA: confirmed NOT a schema change. audit_log.detail_json is `JSON NULL` (V15__audit_log.sql), mapped as @Column(name="detail_json", columnDefinition="json") on AuditLogEntry L69-70 - not VARCHAR, no practical size ceiling for this payload. event_type is VARCHAR(64) NOT NULL and accepts any string: RATE_QUOTE_ISSUED (17), SECURE_LINK_CREATED (19), SECURE_LINK_OPENED (18), SECURE_LINK_REDEEMED (20) all fit. actor_id is VARCHAR(64), a ULID is 26. workspace_id is VARCHAR(26) NULL with the migration's own comment "NULL for pre-auth rejections", so a creator-scoped or link-scoped row with no workspace is already precedented. -->
f. **Every quote leaves an audit trail.** `AuditLogService` gains `recordCreatorEvent(String creatorUserId, String eventType, Map<String, Object> detail)` modelled on `recordAuthRejection` (L71). `RateQuoteService.quote(...)` writes `RATE_QUOTE_ISSUED` with `{tier, provenance, sample, total, anchor, currency, deliverable_count, context: "chat"|"brief"|"deal"}`. **No floors, no floor_total, no range_min in the detail map** — `InfoBarrierRuntimeTest` asserts the detail keys against an allow-list. This is what makes quoted-vs-realised measurable after launch (§14.5.c metric 4).

<!-- PRIYA: the backend half is CORRECT and verified - AdminCreatorAgentController @RequestMapping("/admin/creator-agent") L27, @GetMapping("/baselines") L39, CreatorAgentBaselineService.getBaselines() L62 returning BaselinesResponse (a bare DTO, and the controller's own javadoc L23-24 states the deliberate no-ApiResponse deviation, so the "per the Phase E finding" attribution is unnecessary - it is documented on this file). PUT /creators/{creatorId}/monthly-cap L52 also correct.
     The FRONTEND half has two errors:
     1. `TIER_ORDER` at CreatorAgentBaselinesPage.tsx L33 is `['BRONZE','SILVER','GOLD','PLATINUM']` - loyalty tiers, a completely different vocabulary from NANO/MICRO/MID/MACRO/MEGA. Reusing it sorts every rate tier into the -1 bucket and falls through to localeCompare (L40), i.e. MACRO, MEGA, MICRO, MID, NANO. The calibration table needs its OWN `const RATE_TIER_ORDER = ['NANO','MICRO','MID','MACRO','MEGA']`.
        (Separately, and out of scope for Phase B: this is a live defect on the EXISTING page. CreatorAgentBaselineService.creatorsByTier L75-83 already emits NANO/MICRO/... keys into this same sort, so the shipped baselines table is already ordering creators-by-tier alphabetically. Worth its own ticket.)
     2. The page does not fetch through any api.ts namespace - it uses the hook `useCreatorAgentBaselines()` (page L48, hook at src/admin/hooks/useCreatorAgentBaselines.ts L25). The calibration table needs a sibling hook (src/admin/hooks/useCreatorAgentRateCalibration.ts), not an api.ts entry. Say so, or the frontend engineer looks for a namespace that is not there. -->
g. **Calibration report on the existing admin surface.** `AdminCreatorAgentController` (`/admin/creator-agent`, L27) already serves `GET /baselines` (L39) from `CreatorAgentBaselineService.getBaselines()` (L62). Add `GET /admin/creator-agent/rate-calibration` → `RateCalibrationResponse { tiers: [{ tier, benchmark_min, benchmark_max, benchmark_unit, realised_median, realised_n, distinct_workspaces, meera_anchored_share, quoted_median_90d, quoted_n_90d }] }`, admin JWT, bare DTO (admin controllers return bare DTOs, per the Phase E §14 finding). `realised_*` come from the same `findRateBandCandidates` data aggregated per tier behind `n ≥ 5`; `quoted_*` come from the `RATE_QUOTE_ISSUED` rows. Tiers with `realised_n < 5` render `realised_median = null`. Frontend: one table appended to `src/admin/pages/CreatorAgentBaselinesPage.tsx` (which already knows `TIER_ORDER`, L33).

<!-- PRIYA: the `RateAddOns` precedent claim is CONFIRMED. MeeraCreatorFeatureProperties (influora-api/src/main/java/com/influora/config/MeeraCreatorFeatureProperties.java L25-33) is a plain @Component with constructor @Value injection, and its javadoc L16-23 records the exact reason: a @ConfigurationProperties class that compiled but was never added to @EnableConfigurationProperties crashed boot. Same pattern is right for RateTierProperties. Also CONFIRMED: `REPORT.md` is an established convention in this tree (.proof-os/tasks/T-MEERA-CREATOR-PHASE-A/REPORT.md, .proof-os/tasks/api-conn-audit/REPORT.md). -->
h. **Tier constants become admin-overridable.** `RateEstimationService.TIER_BASE_RATES` stays the compiled default; `RateQuoteService` reads `influora.rates.tier.<nano|micro|mid|macro|mega>.min` / `.max` from `application.yml` through a plain `@Component RateTierProperties` (same pattern as §4.2's `RateAddOns`), falling back to the constants when unset. Day-3 task (backend): run the calibration query once against the production replica; for any tier with `realised_n ≥ 20`, set that tier's yml override to `realised_median × 0.6` / `× 1.4` before B0 goes live. For tiers below 20, launch in benchmark mode with the honest label. Record what was set and why in `REPORT.md`.

<!-- PRIYA: `InfoBarrierRuntimeTest` EXISTS today at influora-api/src/test/java/com/influora/service/meera/InfoBarrierRuntimeTest.java, so these are extensions to a real test, not a new file. Correct. Add one case the list misses: the audit detail allow-list must also reject `range_max` and `unit`, not just floor keys - `unit` in benchmark mode IS derivable back to the floor-side figure that 4.3 1c takes from `min`. -->
**Tests added:** `RateQuoteServiceTest` — midpoint unit, shrinkage at n=1 and n=2 with the exact blended figures above, distinct-workspace fall-through, Meera-anchored-share label, benchmark-mode anchor ×1.10, audit row shape. `InfoBarrierRuntimeTest` — `RATE_QUOTE_ISSUED` detail never carries a floor key. `AdminCreatorAgentControllerTest` — calibration route 403 for non-admin, null median under the floor.

### 14.2 Adoption friction sits on the brand

**The worry.** A secure link only pays off if the brand signs up and funds through Secure Payments. A brand doing a WhatsApp deal with a nano creator has no account, no habit, and the creator is the weaker party asking. §8.5 as written sends the brand to "Sign in as a brand to continue" — a dead end for a brand that has never heard of Influora.

**Changes:**

a. **The link is a nudge, never a gate.** State it in §3.8 and §8.5 and in the persona: every paste-and-secure output (summary, flags, quote, draft) works with no link created. Meera never makes the link a precondition for help, and never repeats the suggestion more than once per brief. Persona line, "What you can do now" block: `- Offer the secure link once per brief, after the flags and the quote, never before.`

<!-- PRIYA: CONFIRMED safe. `SecureLinkResponse` does not exist anywhere in influora-api/src/main/java or src/ at HEAD - it is a record this spec introduces, so appending `share_text` has ZERO existing construction sites. Same for `BriefAnalysisResponse` (14.4.a) and `RateCalibrationResponse` (14.1.g). Rule 0.1's arity discipline is satisfied trivially. The share_text template also passes vocabulary rule 0.2. -->
b. **Meera drafts the ask; the creator does not compose it.** `SecureLinkResponse` (§3.8) gains `share_text` (String, Java-rendered, no model call): the package in one line, the link, and three brand benefits in plain words. Template, rendered with `Rendered.money`:
   ```
   Here's the package we discussed: {lines, e.g. "1 reel + 1 story set"} for {total}.
   {url}
   One link does it: you secure the amount now, it's released to me only when you
   approve the delivery, and you get a GST invoice automatically. No account needed
   to view it.
   ```
   Vocabulary rule 0.2 applies (`InfoBarrierRuntimeTest` also checks `share_text` for the banned word and for any floor value). `SecureLinkDialog` (§8.5) shows `share_text` with a copy button; on mobile, a native share sheet (`navigator.share` when present, copy otherwise).

<!-- PRIYA: the three route line refs are CORRECT (AuthController @RequestMapping("/auth") L35; @PostMapping("/brand/send-email-otp") L51, "/brand/verify-email" L57, "/brand/register" L64). The rest of this item is materially wrong about how big the form is - which matters, because reducing form size is the entire point of the item.
     1. WRONG ARITY. `BrandRegisterRequest` has NINE components, not six:
          (@NotBlank @Size(max=50) firstName, @NotBlank @Size(max=50) lastName, @NotBlank @Email email,
           @NotBlank @Size(min=8,max=128) password, @NotBlank @Size(max=200) companyName,
           @Size(max=100) industry, @Size(max=50) companySize,
           @AssertTrue Boolean acceptedTerms, @Size(max=20) phone)
        `industry` IS optional (no @NotBlank), so prefilling it from extraction.category is fine.
     2. WRONG: "the brand types a name, an email, an OTP and a password; nothing else." There are TWO hidden mandatory steps:
        (a) PHONE IS REQUIRED. AuthService.brandRegister L134-137 throws 400 PHONE_REQUIRED on null/blank, then L147 runs userPhoneService.normalizeAndValidate - a strict INDIAN-MOBILE rule - and L148-158 throws 409 PHONE_ALREADY_EXISTS on a duplicate. A brand outside India cannot complete this flow at all. This is a Swapnil-level product constraint on a page whose job is brand acquisition, not an engineering detail.
        (b) TERMS ACCEPTANCE. `@AssertTrue Boolean acceptedTerms` needs a checkbox. Note the latent trap: Jakarta @AssertTrue treats `null` as VALID, so omitting the field passes validation - do NOT use that to skip the checkbox. Shipping brand registration that silently records no terms consent is a legal problem, not a shortcut.
        Honest minimum form: first name, last name, work email, OTP, password (>=8), company name, Indian mobile, terms checkbox. Eight fields. Say so.
     3. The OTP step is currently DECORATIVE for register. `influora.auth.require-email-otp-before-register` defaults to FALSE (AuthService L69-70); only when true does L179 call brandEmailOtpService.requireVerifiedEmail. If this page is going to claim a verified email, turn the property on for the environment - otherwise the OTP adds a step and proves nothing.
     4. Verification state is DB-backed, keyed on the email itself: BrandEmailOtpService uses EmailOtpChallengeRepository (not Redis), and `VerifyEmailOtpResponse` is `(boolean emailVerified, String message)` - it returns NO token for register to echo. So the SPA can genuinely drive send -> verify -> register with only these three routes. Good.
     5. Rate limit worth designing around: `influora.auth.otp-send-per-email-per-hour` defaults to 3 (BrandEmailOtpService L39). A brand who mistypes their address twice is locked out of a one-shot conversion page for an hour. Show the remaining attempts.
     6. CONFIRMED and load-bearing for the "redeem in the same page" claim: register returns 201 with ApiResponse<TokenPair> plus a refresh cookie (AuthController L64-71), i.e. it LOGS THE BRAND IN, and AuthService L183-200 creates the Workspace and a WorkspaceMember.owner row in the same transaction. So the 8.1 redeem route's Owner/Admin requirement is satisfied immediately after register with no extra call. -->
c. **Lower the brand's first step to one screen.** `/secure/:token` (§8.5) replaces "Sign in as a brand" with an on-page **"Continue with work email"** flow that uses only routes that exist today: `POST /auth/brand/send-email-otp` (`AuthController` L51) → `POST /auth/brand/verify-email` (L57) → `POST /auth/brand/register` (L64) with `BrandRegisterRequest` (`firstName, lastName, email, password, companyName, industry`) where `companyName` is prefilled from the link's `end_brand_name` and `industry` from `extraction.category`, then `secureLinks.redeem(token)` in the same page. The brand types a name, an email, an OTP and a password; nothing else. Existing brands see "Sign in" as the secondary action. The page's top block is written for the brand, not the creator: the package, the total, "secured now, released on approved delivery", "GST invoice included", "dispute cover included". No "escrow".

<!-- PRIYA: CONFIRMED. `Campaign` has no introduced_by field of any kind, and the entity's convention is an explicit `@Column(name = "snake_case")` on every column (Campaign.java L25-131), so the proposed annotation matches. Version placement is correct: V20260910100700 is later than every existing migration and later than every B0 migration in section 2 (100000-100600). The most recent migration touching the `campaigns` TABLE today is V20260718190000__campaign_hype_config.sql - the later-dated V20260903*__admin_email_campaigns*.sql files target a different table. -->
d. **Record the referral now, rule on the reward later.** Reverses §12's original item 2. Migration `V20260910100700__campaigns_introduced_by.sql`: `ALTER TABLE campaigns ADD COLUMN introduced_by_creator_profile_id VARCHAR(26) NULL, ADD INDEX idx_campaigns_introduced_by (introduced_by_creator_profile_id);` plus the matching `@Column(name = "introduced_by_creator_profile_id")` on `Campaign` with a single mutator `markIntroducedBy(String creatorProfileId)` called only from the secure-link redeem path. Read by nothing until Swapnil rules (§12 item 2). Without the column, no early creator can ever be credited; with it, any ruling applies retroactively.

<!-- PRIYA: two problems.
     1. WRONG HELPER. "recordAuthRejection-style rows" for SECURE_LINK_OPENED / SECURE_LINK_REDEEMED would stamp every funnel row actorType=ACTOR_SERVICE and outcome=OUTCOME_REJECTED (AuditLogService L76, L79 - both hard-coded, no parameter). That is false data and it poisons any query or alert built on auth rejections. Write these through the same `recordCreatorEvent` 14.1.f adds (with an explicit outcome), not through recordAuthRejection.
        On the public-route question 14 raises implicitly: passing a null workspaceId is LEGAL and precedented - audit_log.workspace_id is `VARCHAR(26) NULL` with the migration comment "NULL for pre-auth rejections", and recordAdminAction (L94-106) already writes rows with no workspace at all. Pass null on SECURE_LINK_OPENED and backfill nothing; set it on SECURE_LINK_REDEEMED once the workspace exists.
     2. N+1 CONFIRMED. `hasEscrowForCollaboration` (EscrowHoldRepository L145-153) is a per-collaboration boolean @Query with a correlated subquery over PaymentMilestone - there is NO bulk form anywhere in that repository. Calling it once per redeemed link makes the funnel endpoint O(redeemed links) queries, each with a subquery. Add one bulk query beside it and use that:
          @Query("select distinct e.collaborationId from EscrowHold e where e.status in :statuses and e.collaborationId in :ids")  -- plus the milestone leg, same UNION shape as L145-152
        Or, if the funnel is accepted as a small admin report, bound it explicitly to the last 90 days and say so. Do not leave it unstated. -->
e. **The link funnel is instrumented from day one.** `recordCreatorEvent` rows `SECURE_LINK_CREATED` (creator id, brief id), and brand-side `recordAuthRejection`-style rows `SECURE_LINK_OPENED` and `SECURE_LINK_REDEEMED` keyed on link id and, after redeem, brand workspace id — never the creator's name or floor. `GET /admin/creator-agent/rate-calibration` (14.1.g) gains a sibling `GET /admin/creator-agent/secure-link-funnel` → `{created, opened, redeemed, funded, by_week: [...]}` where `funded` counts redeemed links whose collaboration reached `escrowFunded` via `hasEscrowForCollaboration`.

**Phase:** all of 14.2 is **B1** (§14.5) except 14.2.a's persona line, which ships in B0 because the persona is written once.

### 14.3 Hidden AI authorship by default

**The worry.** §2.8 strips every `agent` key for brand viewers, so a brand never learns it negotiated with software. Transparency obligations for AI that interacts with people are already law in the EU (AI Act Article 50, applicable from August 2026) and Indian advisories from MeitY point the same way. More practically: the first brand that discovers it was auto-replied to by "Meera" without a label will say so publicly, and the trust cost lands on the platform.

**Changes (supersede §2.8's strip list and §12 item 1's default):**

a. **What brands see.** For a brand viewer, `metadata` keeps `agent` (`"meera"`) and a new `auto_sent` (boolean: `true` for `MeeraSendLog` sends, `false` for creator-approved drafts). Strip `send_log_id`, `draft_id`, `intent`, `edited` — internal ids, and whether the creator edited the draft is the creator's business. Creators see everything, unchanged. `DealMessageMetadataStripTest` (§9) asserts exactly this split.

<!-- PRIYA: CONFIRMED on all three counts, with the exact insertion point.
     - src/pages/brand-chat.tsx L2323 is EXACTLY `const isOwn = m.senderType === 'brand';` inside `liveMessages.map` (L2322). The proposal branch returns early at L2327-2340, so the plain-bubble branch is L2341-2360+.
     - `DealMessage` (src/lib/api.ts L2368-2378) carries `metadata?: Record<string, unknown>`, so `m.metadata?.agent === 'meera'` type-checks. Follow the file's own convention for metadata reads and go through String() as the proposal branch does at L2333/L2336.
     - The creator's display name IS in scope: `selectedDeal.creatorName`, already used at L994.
     Insertion point: the bubble div is L2345-2354 and the timestamp row is L2355-2359. Put the label as a sibling between them, or as a second child of the timestamp row - `text-muted-foreground` is already that row's class (L2356), so "no icon, no badge colour" comes for free. -->
b. **Where it renders.** `src/pages/brand-chat.tsx` plain-bubble branch (L2323, `const isOwn = m.senderType === 'brand'`): when `!isOwn && m.metadata?.agent === 'meera'`, render one muted line under the bubble — `"Drafted with Meera, approved by {creator display name}"` or, when `auto_sent`, `"Sent by Meera for {creator display name}"`. `text-muted-foreground`, no icon, no badge colour. Test: `brand-chat.meera-stamp.test.tsx`.

c. **A persona rail, so the model never undercuts the label.** Add to "What you still cannot do": `- Write as if the creator typed the message personally, or deny being Meera if a brand asks.` Pinned by substring test.

<!-- PRIYA: FOUR files, not three. MEERA_CREATOR_ENABLED is declared at influora-api/src/main/resources/application.yml:199 (`creator-enabled: ${MEERA_CREATOR_ENABLED:true}`), influora-api/env.example:29, deploy/hostinger/docker-compose.hostinger.yml:158, and deploy/utho/docker-compose.utho.yml:179. Mirror all four. On the env.example question: there are TWO env.example files (influora-api/env.example and influora-ai/env.example) and NO root one - only the influora-api file carries Java-side flags, so only it gets the new key. Ignore influora-api/target/classes/application.yml, that is a build artifact. -->
d. **The flip stays cheap.** Property `influora.meera.brand-facing-stamp` (default `true`, declared in `application.yml`, `env.example`, both deploy composes — the same three places as `MEERA_CREATOR_ENABLED`). `false` restores the full strip and hides the label; the persona rail stays regardless.

<!-- PRIYA: CONSISTENT with PRIYA-COMPAT-0904 section 7 condition 4 - the helper is the single place the split lives, and that is the right call. One naming point: `stripAgentMetadata` is now a misnomer, because under 14.3.a the helper KEEPS `agent`. Name it `brandVisibleMetadata(Map)` so the method name does not contradict its behaviour on day 1. Also CONFIRMED: `DealMessageMetadataStripTest` does not exist anywhere in the tree today - it is a new file, and section 9's row for it has been corrected. -->
e. **What this does not change.** `publishToStream` still publishes the brand-stripped shape (Priya §13.2 risk 2); the strip helper is the single place the split lives (`PRIYA-COMPAT-0904` BROKEN-B1 fix (b)).

**Phase:** B0 for drafts (`auto_sent=false` path, since `draft_reply` ships in B0); the `auto_sent=true` path lands with `RoutineReplyService` in B1.

### 14.4 The heavy users get throttled

<!-- PRIYA: refs verified. The Settings field is config.py L455-457 (the field() spans three lines) with default 0.75 - correct. `creator_monthly_cap_usd(override=...)` is spend_tracker.py L514-532 - correct. chat.py's CREATOR_CAP_CODE is L253 - exact. MeeraCopilotChat.tsx L38 is exact. The credit-sheet numbers are exact too (88.5% / 253% at L21-22; 33% / 95% at a $2 cap at L71). One correction to the framing: the wall is an HTTP 429, not a 200 - see 14.4.a below. -->
**The worry.** The creator cap is `AI_CREATOR_MONTHLY_CAP_USD` = 0.75 (`config.py` L455-456; `spend_tracker.creator_monthly_cap_usd` L514). The credit sheet puts a Typical creator at 88.5% of it and a Heavy creator at 253%. Cap exhaustion today is a wall: `chat.py` returns `CREATOR_MONTHLY_CAP_REACHED` (L253), `MeeraCopilotChat.tsx` renders the sentence (L38, L266, L309) and the conversation ends. The people who use Meera most hit it first.

**Changes:**

<!-- PRIYA: the last sentence is WRONG on both halves of the invariant it cites.
     1. creator_suggestion.py's actual invariant is HTTP 200 with `success: TRUE` plus a source marker, NOT success:false. On a gate block it calls `_fallback_response()` (L278), which returns
          {"success": True, "data": {..., "message_source": "FALLBACK"}}   (L254-257)
        and the comment at L262-263 says it in the file's own words: "skip the AI call and return the deterministic fallback (still 200)".
     2. chat.py's cap response is NOT 200 and carries no `success` key at all. `_creator_cap_response` (L263-274) returns HTTP 429 with
          {"code": ..., "message": ..., "error": {"code": ..., "message": ...}}
        and its docstring (L265-266) says 429 is deliberate, to distinguish the per-creator allowance from the 503 platform-wide ceiling. Do not change it - MeeraCopilotChat.tsx L309 matches on ApiError.code and would break.
     3. CONSEQUENCE the item misses: under the REAL invariant, a cap block and an AI outage return the SAME 200 fallback body, so Spring cannot tell them apart - which is exactly what `degraded_reason` is for. Resolve it explicitly: the brief-extract route returns 200 / success:true / deterministic payload PLUS a `degraded_reason` field of "cap" | "ai_unavailable" | null, and MeeraBriefAiClient maps that straight onto BriefAnalysisResponse.degraded_reason. Say that, rather than describing an envelope that does not exist. -->
a. **Cap exhaustion degrades to deterministic mode, not a wall.** The deterministic surfaces cost nothing: paste (`POST /creator/briefs`) already survives an AI failure by design (§3.8 step 3 falls back to `BriefFallbackExtractor`), flags and quote are Java, drafts and sends are approved without a model call, `GET /deals/{id}/risks` is Java. Make that explicit and visible: `BriefAnalysisResponse` (§3.8) gains `degraded_reason` (`"cap"` | `"ai_unavailable"` | null); `PasteBriefCard` shows "Meera's monthly limit is reached, so this summary is rule-based" when set, and the fallback extractor's output is labelled, not disguised. The Python route returns `{"success": false, "error": {"code": "CREATOR_MONTHLY_CAP_REACHED"}}` with HTTP 200 (creator_suggestion's deterministic-body invariant) so Spring can distinguish `cap` from `ai_unavailable`.

<!-- PRIYA: the CONCLUSION is right - a separate cap IS a route-level change with no spend_tracker edit - but the mechanism named here is the wrong gate, and following it literally enforces nothing.
     There are TWO gates and 14.4.b mixes them:
       - `check_spend_gate(workspace_id=..., reserve_usd=...)` (app.costs.gate) - the DAILY workspace/global ceiling. This is what creator_suggestion.py L264-271 calls, and the "L265 precedent" quoted here is that call. It has NO cap override parameter, so BRIEF_EXTRACT_MONTHLY_CAP_USD would bind to nothing.
       - `check_creator_spend_gate(creator_id, audience, *, reserve_usd, reserve_ttl_seconds, cap_usd)` (spend_tracker.py L535-542) - the per-creator MONTHLY cap. This is the one that matters here.
     So: the parameter is `creator_id`, NOT `workspace_id`. Corrected instruction:
       await check_creator_spend_gate(
           f"{creator_profile_id}:brief", "CREATOR",
           reserve_usd=..., cap_usd=get_settings().brief_extract_monthly_cap_usd)
       inside try/except SpendCapExceeded.
     The opaque-key claim itself is CONFIRMED: the key is built by `_creator_month_key` (L151-152) as an f-string, so a ":brief" suffix is a distinct bucket with no plumbing change.
     Two traps to state in the spec or the route silently no-ops:
       - the gate returns None immediately unless `audience.upper() == "CREATOR"` (L570). Pass audience explicitly.
       - `cap <= 0` DISABLES the cap (L573-574). 0.25 is fine; a mis-set empty env var is not.
     The cost basis is correct: the credit sheet has extraction at INR 0.294/call on Haiku (L70). -->
b. **Brief extraction has its own small cap.** It costs ≈ ₹0.29 per call on Haiku and is already rate-limited to 10 per window by the `creator-brief-paste` bucket. Give it a separate spend key so chat never starves it: the route passes `workspace_id = f"{creator_profile_id}:brief"` to the gate (the keys are opaque strings, `creator_suggestion.py` L265 precedent) and reads its cap from a new `BRIEF_EXTRACT_MONTHLY_CAP_USD` (`config.py`, beside `ai_creator_monthly_cap_usd`, default **0.25** ≈ 70 extractions). Test in `tests/routes/test_brief_extract.py`: a creator at the chat cap still extracts; a creator at the brief cap gets the `cap` body.

<!-- PRIYA: the line refs are right and the instruction is incomplete in two ways that matter.
     1. RESET DATE - resolve the "if it does not" hedge: it does not. The only reset information today is prose inside CREATOR_CAP_MESSAGE (spend_tracker.py L85-88, "It resets on the 1st of next month"); there is no machine-readable field anywhere in the 429 body (chat.py L269-273). So `resets_on` MUST be added - an ISO date for the 1st of the next UTC month, derived from `_current_month_utc()` (spend_tracker.py L142-144), which is where the month boundary is computed. Add it to the payload dict in `_creator_cap_response`.
     2. TWO SITES, not one. The fallback sentence is at L266-267 as stated, but that is only the STREAM path (useMeeraStream onError, L265-273). The non-stream `.catch` at L307-311 renders cap text too, from `err.message`. Change both or the block appears on one path and the bare sentence on the other.
     3. UNDERSTATED WORK. Both sites push a plain STRING into `messages` ({id, role:'meera', text}). "Three links" cannot be a string. This needs either a message variant (e.g. kind:'cap' rendered by a dedicated component) or link support in the message renderer. Budget it as a component change, not a copy change - MeeraCopilotChat.test.tsx asserting three rendered links will not pass against a text node. -->
c. **The cap message names what still works.** Replace the fallback sentence in `MeeraCopilotChat.tsx` (L266-267) with a short block: the limit line, the reset date (the Python error already carries the month boundary; if it does not, add `resets_on` to the error body), and three links: "Paste a brief", "Check a deal's risks", "Your drafts and sends". Test in `MeeraCopilotChat.test.tsx` asserts the three links render on `CREATOR_MONTHLY_CAP_REACHED`.

d. **Raise the cap.** Ruling 3 in §12; default `AI_CREATOR_MONTHLY_CAP_USD=2.00` on the creator gate, per the credit sheet's own recommendation (§6: Typical 33%, Heavy 95% of a $2 cap). This is configuration, not code, and the sheet's caveat stands: Haiku for extraction and a ≥70% cache-hit rate are load-bearing, not tunable.

<!-- PRIYA: CONFIRMED - `TASK_INBOX.md` exists at the repo root, so 14.4.e's note has somewhere to go. -->
e. **Not in Phase B.** A per-creator usage meter (`GET /creator/meera/usage`) needs Spring to read Python's spend tracker; deferred to Phase C with a note in `TASK_INBOX.md`.

### 14.5 Nothing is validated: split into B0 and B1 with a gate between

**The worry.** Phase A is not deployed. Zero creators have used it. Phase B as specified is nine tools, fourteen rules, six migrations, secure links, media kit, campaign fit and level-1 sending — roughly 24 engineer-days — before one creator has pasted one brief. If the flags fire wrong or the quotes are off, that is discovered after the sending automation is built on top of them.

<!-- PRIYA: the split itself is sound and I endorse it. Four corrections to the table below.
     1. FALSE PREMISE on the renumbering rule. `spring.flyway.out-of-order` is set to **true** in this repo - influora-api/src/main/resources/application.yml:61, with a comment at L55-60 explaining exactly why (numeric V41-V64 sort below the timestamped ones). So the parenthetical "Flyway outOfOrder=false rejects a lower version applied after a higher one" is wrong here, and the renumbering it mandates is UNNECESSARY. B1 can ship 2.3 (V20260910100200) and 2.5 (V20260910100400) at their original versions after B0's 2.6 (100500) has been applied. The real constraint is FK order, and it is already satisfied: 2.3's fk_csl_brief references creator_briefs, which B0's 2.2 creates.
        Keep the renumbering as optional hygiene if you like, but do not present it as a Flyway requirement - a B1 engineer who renumbers under time pressure risks a hash mismatch on an environment that already has the file.
     2. FK CHECK CONFIRMED, as asked: 2.4 meera_drafts has exactly ONE foreign key, fk_meera_drafts_creator -> creator_profiles(id). No FK to send_log or secure_links. B0's migration set (2.1, 2.2, 2.4, 2.6) is self-contained.
     3. MIGRATION OMITTED. Section 2.7 (V20260910100600__collaborations_calendar_hint.sql) appears in NEITHER column. Assign it. It is calendar/scheduling support, so B1 unless something in the B0 read half needs it.
     4. SCOPE_LEVEL_* NEEDS NO B1 EDIT. Section 3.3's SCOPE_LEVEL_0 string (L383) already lists all EIGHT level-0 names; SCOPE_LEVEL_1 appends send_routine_reply. `OnBehalfAuthResolver.requireScope` (L178-182) only asserts that the REQUIRED tool is present in the space-delimited claim - it never validates scope entries against a tool registry, so extra names with no backing executor are inert (a call would fail at dispatch, and scope is a ceiling, not the gate). So B0 ships the 8-name string verbatim and B1 appends nothing to SCOPE_LEVEL_*. Correct the B1 cell to "append to `CreatorToolName`, `CREATOR_TOOL_NAMES`, `tools_enabled`" only. -->
**a. The split.**

| | **Phase B0 — Paste and Read** | **Phase B1 — Secure and Send** |
|---|---|---|
| Jobs (§1) | B1 read half (paste → summary, flags, quote, draft), B2 reads + `draft_reply`, B3, B4 | B1 secure-link half, B5, B6, B7 |
| Migrations | 2.1 prefs, 2.2 briefs, 2.4 drafts, 2.6 offer history (+ its unique key) | 2.3 secure links, 2.5 send log, 14.2.d `introduced_by` — **renumbered to timestamps later than every B0 migration** (Flyway `outOfOrder=false` rejects a lower version applied after a higher one) |
| Creator tools | 6: `get_my_deals`, `get_brief`, `estimate_my_rate`, `get_my_metrics`, `check_deal_risks`, `draft_reply` | +3: `send_routine_reply`, `rank_open_campaigns`, `draft_application` (append to `CreatorToolName`, `CREATOR_TOOL_NAMES`, `SCOPE_LEVEL_*`, `tools_enabled`) |
| Risk rules | all 14 | — |
| Backend | §10 days 1-3, plus day 5's `MeeraDraft`, `DraftReplyExecutor`, `CreatorMeeraDraftController` (approve REPLY / COUNTER / DECLINE via the existing `sendMessage` / `counter` / `reject` paths), §14.1, §14.3.a-d (drafts), §14.4 | §10 days 4, 6, 7; secure links + redeem; `RoutineReplyService`, send log, `MeeraDelayedSendJob`; media kit; `CampaignFitService`; level-up; §14.2; §14.3 `auto_sent` path |
| AI service | `creator_schemas.py` with six schemas; assembler, persona (six-tool list + 14.1.b + 14.3.c rails), loop dispatch; brief extraction route + own cap | three more schemas; routine-reply scope decline |
| Frontend | `PasteBriefCard`, `BriefCard`, `DealRiskCard`, `PackageQuoteCard`, `MetricsCard`, `MyDealsCard`, `DraftCard`, tool events in `MeeraCopilotChat`, deal-page risks, counter prefill, brand-chat stamp, cap-message block | `SecureLinkDialog`, `/secure/:token` with the email-claim flow, `SendQueuedCard`, sends section, `LevelUpPrompt`, media kit page, campaign fit cards, rate-card settings |
| Size | ≈ 10-12 engineer-days | ≈ 12-14 engineer-days |

Everything Priya's §13.3 and `PRIYA-COMPAT-0904` §7 require still applies to B0 (day-1 block as one commit; `InfoBarrierTest` widening; offer-history unique key; `stripAgentMetadata` helper; `sent_message_id` REPLY-only; `schema-check.yml` awk repair first). `CreatorContextResponse.tools_enabled` lists six names in B0; the drift test does not care how many.
<!-- PRIYA: CONFIRMED, and worth stating precisely because it is the one place a careless edit re-arms a red gate. tests/prompt/test_creator_context_drift.py contains NO assertion on len(tools_enabled) and pins none of the nine names - so a six-name list in B0 is safe. What the test DOES do is parse every @JsonProperty("...") off the CreatorContextResponse record (L61-63) and assert Java == Python CREATOR_CONTEXT_PAYLOAD_FIELDS in BOTH directions (L67-77), plus sorted/no-dupes (L82-84), plus every allow-listed field is read by build_block_b_creator (L87-100), plus every field changes the rendered block (L103-153). So any NEW @JsonProperty on that record is a four-assertion cascade. Section 14 adds none - verified - and `tools_enabled` already exists per 2.10. The other 14.6 arity check: SecureLinkResponse (+share_text), BriefAnalysisResponse (+degraded_reason) and RateCalibrationResponse are all records this spec introduces, zero existing construction sites; and section 14 adds no value to any existing enum. -->
<!-- PRIYA: the six carried conditions are all correctly named here. Two qualifiers were dropped in the shorthand and both are load-bearing - PRIYA-COMPAT-0904 section 7 condition 2 says the InfoBarrierTest widening is "an ~8-line restructure of L68-76, not a one-line list edit", and condition 3 requires that 2.6 ALSO state that `sequence_no` is derived under the existing row lock. Carry both wordings, not just the headings. -->

<!-- PRIYA: agreed, and this is the single most important line in section 14. It converts the Docker/VPS smoke test from a deploy chore into a gate dependency. Note it also inherits the Phase A memo's constraint: the smoke needs a machine with Docker or the VPS, and commit 1792c37 must never deploy without 8c7b18b. -->
**b. Precondition.** Phase A must be **deployed and smoke-tested** before B0's day 8 (§10). B0 cannot be measured on an undeployed Phase A. The smoke needs a machine with Docker or the VPS; that is the oldest open item on this feature and it now blocks the gate, not just the deploy.

<!-- PRIYA: the five metrics are the right five, and I endorse metric 4 having no threshold. Three corrections:
     1. METRIC 3 IS PARTLY UNMEASURABLE. `DraftStatus` does declare {PENDING, APPROVED, EDITED, DISCARDED, SENT} (2.4), but 3.7's approve path sets **SENT** and discard sets DISCARDED - nothing ever writes APPROVED or EDITED. As specified, metric 3 reads 0% forever. And the second threshold (">= 25% approved unedited") has no backing column at all: 3.7 computes `edited` as a LOCAL boolean and 2.4's DDL has no `edited` column.
        Fix on B0 day 1, while 2.4 is still an unapplied migration: add `edited TINYINT(1) NOT NULL DEFAULT 0` to meera_drafts, set it in approve, and restate metric 3 as "status = SENT >= 50% of non-PENDING drafts; status = SENT AND edited = 0 >= 25%". Doing this after B0 ships costs a second migration in the middle of the measurement window.
     2. Metric 4's input CONFIRMED: `budget_stated` is a real field on 2.11's BriefExtraction JSON contract.
     3. Metric 5's harness CONFIRMED: InfoBarrierRuntimeTest exists today (influora-api/src/test/java/com/influora/service/meera/InfoBarrierRuntimeTest.java), so metric 5 extends a real gate.
     On 14.5.c's "written by Tara": there is a direct precedent for the author and the format - .proof-os/tasks/T-MEERA-CREATOR-PHASE-A/REPORT.md is headed "From: Tara (reporting)". There is NO *METRICS*.md precedent anywhere under .proof-os/tasks. Have B0-METRICS.md copy the Phase A REPORT.md header block (For / From / Date / Branch / Sources) so it reads as the same artefact family. -->
**c. The gate to start B1.** Measured over the first 14 days after B0 is live, or the first 100 pasted briefs, whichever comes first. Numbers come from the audit rows in 14.1.f and the `creator_briefs` / `meera_drafts` tables; a one-page `B0-METRICS.md` in this folder, written by Tara, goes to Swapnil before any B1 work starts.

| # | Metric | Threshold | If it fails, the problem is |
|---|---|---|---|
| 1 | Distinct creators who pasted ≥ 1 brief | ≥ 40 | distribution and onboarding, not features — do not build B1, fix the entry point |
| 2 | Flag precision on a hand-reviewed sample of the first 50 briefs with ≥ 1 flag | ≥ 80% of CRITICAL flags judged correct; `HIDE_DISCLOSURE` and `OFF_PLATFORM_PAYMENT` false-positive rate < 10% | the rules — fix them before automating anything that acts on them |
| 3 | Draft acceptance | ≥ 50% of drafts APPROVED or EDITED (not DISCARDED); ≥ 25% approved unedited | the persona and draft quality — prompt work before send automation |
| 4 | Quote sanity | for briefs with `budget_stated`, report per tier the median of `|quote.total − budget| / budget` and the benchmark-mode share. **No threshold**; must be shown, with the calibration report from 14.1.g | pricing constants — recalibrate (14.1.h) before B1's secure links freeze a package total |
| 5 | Floor leaks | 0 in `InfoBarrierRuntimeTest` and 0 in a manual sample of 30 brand-visible payloads | the barrier — stop everything |

Thresholds are Swapnil's to move (§12 item 4). The measurement is not.

**d. What B0 deliberately leaves out, and why that is fine.** No message leaves the platform without the creator's tap, so B0 carries none of B1's send-side risk. The creator still gets the thing the plan promised first: paste a brief, know what it is worth, know what is wrong with it, and have the reply written.

---

**Amendment summary:** 14.1 eight changes to pricing (midpoint, provenance rail, shrinkage, band guards, anchor lift, audit row, calibration report, overridable constants); 14.2 five changes to the link (nudge rule, `share_text`, email-claim flow, `introduced_by` now, funnel instrumentation); 14.3 flipped default with a keep-list, a render point, a persona rail and a property; 14.4 degraded mode, separate extraction cap, cap-message block, $2.00 default; 14.5 B0/B1 split, Phase A deploy precondition, five gate metrics. Four rulings in §12, each with a shipping default.

### 14.6 Priya review of §14

**Reviewer:** Priya (CTO). **Baseline:** working tree at HEAD `143ca1e`, 2026-09-05. **Method:** §14 was written by a product pass, so every line reference, signature, route, field, constant and arithmetic result in it was treated as a claim and checked against the actual file, not against §1-13 and not against the fact sheets. `PRIYA-COMPAT-0904.md` settled the Phase A→B compatibility surface; nothing it closed was re-verified. Corrections are applied inline above as `<!-- PRIYA: ... -->` immediately above the claim they correct; the original claim is left standing in every case.

#### Counts

| Subsection | Claims checked | HOLDS | DRIFTED | WRONG |
|---|---|---|---|---|
| 14.1 pricing | 25 | 14 | 4 | **7** |
| 14.2 brand friction | 15 | 9 | 2 | **4** |
| 14.3 AI authorship | 10 | 6 | 2 | **2** |
| 14.4 cap | 17 | 10 | 3 | **4** |
| 14.5 B0/B1 split | 14 | 7 | 2 | **5** |
| §12 table, pointer set, cross-document | 9 | 6 | 2 | **1** |
| **Total** | **90** | **52** | **15** | **23** |

*HOLDS* = verified true against source. *DRIFTED* = substantively right, reference or detail off. *WRONG* = an engineer following it literally builds the wrong thing, or it does not compile.

Zero of the 23 WRONG items invalidate a §14 decision. Every one of them is a mechanism error underneath a decision I agree with. That is the pattern to expect from a product pass and it is why this review exists — but it means the four rulings in §12 can go to Swapnil today, unchanged.

#### WRONG (23)

**14.1 — pricing**

| # | Claim | File:line | Fix |
|---|---|---|---|
| W1 | `CATEGORY_MULTIPLIERS` "L105-110" | `RateEstimationService.java` L45-55 | The map is **declared** at L45-55. L104-108 only computes the max over the creator's categories. Values quoted (FASHION 1.3 … EDUCATION 0.9) are correct. |
| W2 | "hard-coded constants with **no source comment**" | same, L34, L37-41 | There is a source comment (L34, "Base rates per post by follower tier (INR)") and per-tier follower bands at L37-41. They match `determineTier` except NANO's, whose comment says "1K-10K" while the method returns NANO for everything below 10,000 including 0. |
| W3 | Worked-example anchor column, labelled "§4.3 as written", ×1.10 | `SPEC.md` §4.3 step 6 | §4.3 step 6 as written is `min(total × 1.15, rangeMax)` when a range exists, and benchmark mode is the only branch that has one. Today's anchors are **5,175** and **6,727.50**, not 4,950 and 6,435. Using 14.1.e's new lift in the baseline row makes the amendment look like it changes nothing. |
| W4 | `countByCollaborationIdInAndEvent` gives `meeraAnchoredShare` | `SPEC.md` §2.6 | Valid Spring Data naming, wrong semantics: it counts **rows**, and §2.6 permits several `MEERA_COUNTER` rows per collaboration (the uniqueness key §13.2 risk 6 adds is on `(collaboration_id, sequence_no)`, not `(collaboration_id, event)`). A two-counter negotiation drives the share above 1.0. Use `select distinct h.collaborationId … ` and `.size()`. |
| W5 | 14.1.e: `min(total × 1.15, rangeMax)` for own-history / tier band | `SPEC.md` §4.3 steps 1a, 1b | Unimplementable — both of those branches produce a single median, so there is no `rangeMax`. 14.1.e assigns the clamped formula to the two branches with no range and the unclamped one to the branch that has one. Corrected: own/tier `total × 1.15` uncapped; benchmark `min(total × 1.10, rangeMax)`. |
| W6 | 14.1.f: `recordCreatorEvent` "modelled on `recordAuthRejection` (L71)" | `AuditLogService.java` L70-82 vs L94-106 | `recordAuthRejection` takes **no detail map** and hard-codes `ACTOR_SERVICE` / `OUTCOME_REJECTED`. The model is `recordAdminAction(actorId, eventType, outcome, detail)` at L94-106. The 3-arg signature §14 proposes also supplies no `outcome`, which is `NOT NULL`. |
| W7 | 14.1.g: reuse `TIER_ORDER` (L33) for the calibration table | `CreatorAgentBaselinesPage.tsx` L33 | `TIER_ORDER` is `['BRONZE','SILVER','GOLD','PLATINUM']` — loyalty tiers, a different vocabulary. Reusing it sorts every rate tier to `-1` and falls through to `localeCompare` (L40). Needs its own `RATE_TIER_ORDER = ['NANO','MICRO','MID','MACRO','MEGA']`. |

**14.2 — brand friction**

| # | Claim | File:line | Fix |
|---|---|---|---|
| W8 | `BrandRegisterRequest (firstName, lastName, email, password, companyName, industry)` | `BrandRegisterRequest.java` L28-37 | **Nine** components, not six. Also `companySize`, `acceptedTerms`, `phone`. |
| W9 | "The brand types a name, an email, an OTP and a password; nothing else." | `AuthService.java` L134-158 | **Phone is required.** L134-137 throws 400 `PHONE_REQUIRED`; L147 enforces a strict **Indian-mobile** rule; L148-158 throws 409 on a duplicate. A non-Indian brand cannot complete this flow at all. That is a Swapnil-level constraint on a brand-acquisition page, not a detail. |
| W10 | Same sentence — hidden step #2 | `BrandRegisterRequest.java` L36 | `@AssertTrue Boolean acceptedTerms` needs a checkbox. Trap: Jakarta `@AssertTrue` treats `null` as valid, so omitting the field passes validation — do not use that to skip consent. Honest minimum: **eight** fields. |
| W11 | 14.2.e: "`recordAuthRejection`-style rows" for `SECURE_LINK_OPENED` / `_REDEEMED` | `AuditLogService.java` L76, L79 | That helper hard-codes `actorType=ACTOR_SERVICE` and `outcome=OUTCOME_REJECTED` with no parameter for either. Every funnel row would be recorded as a rejected service-auth event, poisoning any auth-rejection query or alert. Write them through 14.1.f's `recordCreatorEvent` with an explicit outcome. |

**14.3 — authorship**

| # | Claim | File:line | Fix |
|---|---|---|---|
| W12 | "the same **three** places as `MEERA_CREATOR_ENABLED`" | four files | `application.yml:199`, `influora-api/env.example:29`, `deploy/hostinger/docker-compose.hostinger.yml:158`, `deploy/utho/docker-compose.utho.yml:179`. The sentence names four locations and calls them three. |
| W13 | §9's test row survives §14.3 | `SPEC.md` §9 | §9 says `DealMessageMetadataStripTest` covers "brand never sees `agent` keys" — the exact assertion §14.3.a forbids — and §9 carried **no** `AMEND-0904` pointer. An engineer writing that test from §9 writes a test that fails against §14.3's implementation. Pointer and corrected cell added. |

**14.4 — cap**

| # | Claim | File:line | Fix |
|---|---|---|---|
| W14 | "the route passes `workspace_id = f\"{creator_profile_id}:brief\"` to the gate" | `spend_tracker.py` L535-542 | The monthly gate is `check_creator_spend_gate(creator_id, audience, *, reserve_usd, reserve_ttl_seconds, cap_usd)`. There is **no `workspace_id` parameter**. |
| W15 | "`creator_suggestion.py` L265 precedent" | `creator_suggestion.py` L264-271 | That call is `check_spend_gate(workspace_id=…)` from `app.costs.gate` — the **daily** workspace/global ceiling, a different gate with **no cap override**. Following the cited precedent literally makes `BRIEF_EXTRACT_MONTHLY_CAP_USD` bind to nothing and enforces no monthly cap at all. |
| W16 | "HTTP 200 with `success:false` (creator_suggestion's deterministic-body invariant)" | `creator_suggestion.py` L254-257, L262-263, L278; `chat.py` L263-274 | The real invariant is 200 with `success: **true**` plus `message_source: "FALLBACK"`. And chat's cap response is **429** with no `success` key. Consequence §14 missed: under the real invariant a cap block and an AI outage return the same body, so Spring cannot distinguish them — which is precisely what `degraded_reason` must carry as an explicit field. |
| W17 | 14.4.c: "replace the fallback sentence" with three links | `MeeraCopilotChat.tsx` L265-273, L307-312 | Two problems. Both cap sites push a plain **string** into `messages` (`{id, role, text}`) — three links need a message variant or link support in the renderer, i.e. a component change, not a copy change; a test asserting three rendered links will not pass against a text node. And 14.4.c instructs only L266-267 (the stream path); the `.catch` path at L307-311 renders cap text too. |

**14.5 — split**

| # | Claim | File:line | Fix |
|---|---|---|---|
| W18 | "Flyway `outOfOrder=false` rejects a lower version applied after a higher one" | `application.yml:61` | **`spring.flyway.out-of-order: true` is set in this repo**, with a comment at L55-60 explaining why. The renumbering mandate rests on a false premise and is unnecessary: B1 can ship 2.3 (`…100200`) and 2.5 (`…100400`) at their original versions after B0's 2.6 (`…100500`). Keep it as optional hygiene if you like, but a B1 engineer renumbering under time pressure risks a checksum mismatch on an environment that already holds the file. |
| W19 | The B0/B1 migration row is complete | `SPEC.md` §2.7 | §2.7 (`V20260910100600__collaborations_calendar_hint.sql`) appears in **neither** column. Assign it — B1 unless the B0 read half needs it. |
| W20 | B1 appends to `SCOPE_LEVEL_*` | `SPEC.md` §3.3 L383; `OnBehalfAuthResolver.java` L178-182 | `SCOPE_LEVEL_0` already lists all **eight** level-0 names. `requireScope` only asserts the *required* tool is present in the space-delimited claim; it never validates entries against a tool registry, so names with no backing executor are inert (scope is a ceiling; dispatch is the gate). B0 ships the 8-name string verbatim; B1 appends nothing to `SCOPE_LEVEL_*`. |
| W21 | Gate metric 3: "≥ 50% of drafts APPROVED or EDITED" | `SPEC.md` §3.7 | The enum has those values (§2.4) but **nothing ever writes them** — approve sets `SENT`, discard sets `DISCARDED`. As specified, metric 3 reads 0% forever. |
| W22 | Gate metric 3: "≥ 25% approved unedited" | `SPEC.md` §2.4 DDL | **Unmeasurable.** §3.7 computes `edited` as a local boolean; `meera_drafts` has no `edited` column. Fix on B0 day 1 while 2.4 is still unapplied: add `edited TINYINT(1) NOT NULL DEFAULT 0`, set it in approve, restate metric 3 as `SENT ≥ 50%` of non-PENDING and `SENT AND edited = 0 ≥ 25%`. After B0 ships this costs a second migration mid-measurement. |

**Pointer set**

| # | Claim | Fix |
|---|---|---|
| W23 | "ten `<!-- AMEND-0904 -->` pointer comments" | **Eight** exist (§2.8, §3.8, §4.3, §7.4, §7.6, §8.5, §10, §12). Three sections §14 changes carried none and have been given one: **§8.1** (`SecureLinkResponse.share_text`, `BriefAnalysisResponse.degraded_reason` — and §8.1's own rule is "every field the Java record sends and nothing it does not", so silence there is a spec violation), **§8.4** (`PackageQuoteCard` provenance-as-subtitle), **§9** (see W13). |

#### DRIFTED (15)

14.1: engagement thresholds are L96-98 not L98-101 (L100-101 applies them) · quality multiplier is L118-122 + L124-125 not L118-123 · `co.updated_at` is `ON UPDATE CURRENT_TIMESTAMP`, i.e. last-modified not completed-at, so a precise "in 90 days" provenance on it silently readmits old deals · 14.1.g's calibration table needs a hook (`src/admin/hooks/useCreatorAgentRateCalibration.ts`), not an `api.ts` namespace — the page fetches via `useCreatorAgentBaselines()` (page L48, hook L25).
14.2: `influora.auth.require-email-otp-before-register` defaults to **false** (`AuthService.java` L69-70), so the OTP step is currently decorative for register — turn it on for the environment or stop claiming a verified email · `hasEscrowForCollaboration` (`EscrowHoldRepository.java` L145-153) has **no bulk form**, so the funnel N+1s once per redeemed link, each with a correlated subquery.
14.3: `stripAgentMetadata` is now a misnomer — under 14.3.a it *keeps* `agent`; rename to `brandVisibleMetadata` · §2.8's trailing sentence ("Recommendation 4 … implemented this way and can be flipped by removing the strip") still states the pre-amendment default; the pointer at the head of §2.8 did not reach it.
14.4: `ai_creator_monthly_cap_usd` is L455-**457** · the reset date is prose inside `CREATOR_CAP_MESSAGE` (`spend_tracker.py` L85-88), not a field — `resets_on` must be added, derived from `_current_month_utc()` (L142-144); resolve 14.4.c's "if it does not" hedge to "it does not" · 14.4.c names one cap site where the §14.4 preamble correctly names two.
14.5: no `*METRICS*.md` precedent exists under `.proof-os/tasks`, though the Tara authorship and header format do (`T-MEERA-CREATOR-PHASE-A/REPORT.md`, "From: Tara (reporting)") — have `B0-METRICS.md` copy that header block · 14.5.a names all six carried conditions but drops two load-bearing qualifiers from `PRIYA-COMPAT-0904` §7: the `InfoBarrierTest` widening is an ~8-line restructure of L68-76, and §2.6 must *also* state that `sequence_no` is derived under the existing row lock.
§12: "88%" is the credit sheet's 88.5% rounded; §12 also omits the brief cap's value (0.25).

#### Recomputed worked-example table

Recomputed from `RateEstimationService.estimate()`, not from §14. NANO, 3,000 followers, BEAUTY, no quality score, platform-unverified. Base `[1000, 5000]`; BEAUTY ×1.25; quality ×1.0 (absent is neutral, L119); both bounds `Math.round`ed (L145-146); currency hard-coded `"INR"` (L147); confidence 50 (L129-135, read by nothing in §4.3).

| Row | eng. | eng. × | min | max | unit, §4.3 as written (`min + 0.65Δ`) | unit, **14.1.a** (`min + 0.50Δ`) | anchor, §4.3 as written (`min(total × 1.15, rangeMax)`) | anchor, **14.1.a + corrected 14.1.e** (`min(total × 1.10, rangeMax)`) |
|---|---|---|---|---|---|---|---|---|
| 1 | 2.4% | 1.00 | 1,250 | 6,250 | 4,500 ✓ | **3,750** ✓ | **5,175** (§14 said 4,950 ✗) | **4,125** ✓ |
| 2 | 5.5% | 1.30 | 1,625 | 8,125 | 5,850 ✓ | 4,875 | **6,727.50** (§14 said 6,435 ✗) | 5,362.50 |

14.1.c shrinkage, row 1, one own priced deal at 2,000: `(1 × 2,000 + 3,750) / 2` = **2,875** ✓. Add the rounding rule — `RateEstimationService` rounds to whole rupees and the blend does not, so `unit` must be rounded the same way or a quote and its own recomputation differ by paise.

Every min, max and unit figure in §14 is correct. Only the two anchor cells labelled "as written" are wrong, and they are wrong because they were computed with the amendment's own new rule.

#### `determineTier` vs `deriveTier` — the §3.6 unification is safe

I checked all three because §3.6 orders them merged into `CreatorTiers.derive` and a silent threshold change there would move `RateEstimationService.estimate()`'s output and `RateEstimationServiceTest` with it.

| Implementation | Thresholds |
|---|---|
| `RateEstimationService.determineTier` L153-159 | ≥1,000,000 MEGA · ≥500,000 MACRO · ≥50,000 MID · ≥10,000 MICRO · else NANO |
| `MeeraContextService.deriveTier` L415-421 | identical |
| `CreatorAgentBaselineService.deriveTier` L84- | identical |

**All three are byte-identical, MEGA included.** `MeeraContextService`'s own javadoc (L414) already flags itself as a duplicate. So the §3.6 extraction to `CreatorTiers.derive` is a pure move: **nothing in `estimate()` changes, `RateEstimationServiceTest` is unaffected, and no threshold "wins" over another.** The premise behind the concern — that `determineTier` never returns MEGA — is false; it returns MEGA at ≥1M exactly as the other two do.

Two things the merge must carry across, both already in §3.6/§4.3 as Priya notes and neither weakened by §14:
1. `"MEGA"` is **not** a `CreatorTier` enum constant (`NANO, MICRO, MID, MACRO`). Never `CreatorTier.valueOf(tier)`. §4.3 step 7 branches on the String.
2. `estimate()` returns tier `"UNKNOWN"` when the metric is absent (L79-82) — not a `TIER_BASE_RATES` key and not a `CreatorTier` value. §4.3 1c's `min > 0` guard is what keeps it out of step 7. Keep that guard.

#### Three-document consistency: §12 ↔ §14 ↔ DECISIONS-0904.md

| Item | §12 | §14 | DECISIONS | Verdict |
|---|---|---|---|---|
| Brand-facing stamp default | shown; flip = `brand-facing-stamp=false` | same | same | consistent |
| Referral: 10% instead of 15%, first 90 days | ✓ | 14.2.d ✓ | ✓ (adds the ₹250-on-₹5,000 illustration; 15% platform fee confirmed at `application.yml` `platform-fee-percent:15.00`) | consistent |
| Creator cap $0.75 → $2.00 | ✓ | 14.4.d ✓ | ✓ | consistent |
| Typical / Heavy percentages | 88% / 253% → 33% / 95% | **88.5%** / 253% → 33% / 95% | 88% / 253% → 33% / 95% | §14 is exact (credit sheet L21-22, L71); the other two round. Harmless. |
| Brief-extract cap 0.25 | value omitted | 14.4.b: **0.25** | value omitted | §14 is source. **DECISIONS corrected** to carry the number. |
| Gate metric 1 (≥ 40 creators) | ✓ | ✓ | ✓ | consistent |
| Gate metric 2 (≥ 80% CRITICAL precision **and < 10% FP rate**) | ✓ | ✓ | FP rate **missing** | **DECISIONS corrected.** |
| Gate metric 3 (≥ 50% sent **and ≥ 25% unedited**) | ✓ | ✓ | 25% **missing** | **DECISIONS corrected** — a threshold Swapnil is being asked to own was invisible in the memo he reads. |
| Gate metrics 4, 5 | ✓ | ✓ | ✓ | consistent |
| B0 ≈10-12 / B1 ≈12-14 engineer-days | — | ✓ | ✓ | consistent |

**Changes made to `DECISIONS-0904.md`** (it is derived; §14 is source): 88% → 88.5%; the brief-extract cap's value (0.25 USD/month) added to ruling 3; the `< 10%` false-positive threshold added to gate item 2; the `≥ 25% unedited` threshold added to gate item 3. No number in DECISIONS contradicted §14 — it was under-specified, not wrong.

#### Consistency with §13.3 and PRIYA-COMPAT-0904 §7

No contradiction. §14.5.a names all six carried conditions and adds none of its own that conflict. Specifically:
- **COMPAT §7 condition 4** (§2.8 rewritten to the 3-edit strip-helper form): 14.3.e references it correctly and keeps the helper as the single place the split lives. Only the *name* needs to change — see DRIFTED.
- **COMPAT §7 condition 6** (`schema-check.yml` awk repair before day 1): 14.5.a carries it, and it remains the first thing to do.
- **§13.3 conditions 2 and 3** (InfoBarrier widening, offer-history unique key): both carried; §14.1.d in fact *increases* the load on condition 3, because `meeraAnchoredShare` is the first consumer of offer history that reaches a user-facing string.
- Vocabulary rule 0.2: clean. §14's two "escrow" hits are `escrowFunded` / `hasEscrowForCollaboration` (identifiers, explicitly permitted by rule 0.2) and the instruction `No "escrow".` in 14.2.c, which is the rule being enforced, not broken. DECISIONS-0904.md has zero hits. `share_text` and the `/secure/:token` copy block are both clean.
- Arity and schema discipline: §14 adds **no** new `@JsonProperty` to `CreatorContextResponse` (so the drift test is not re-armed — and it asserts Java ≡ Python in *both* directions plus sorted/no-dupes plus read-by-the-builder plus changes-the-rendered-block, a four-assertion cascade for any future field); **no** new value on any existing enum; and its three new fields land on `SecureLinkResponse`, `BriefAnalysisResponse` and `RateCalibrationResponse`, all of which are records this spec introduces with **zero** existing construction sites.

#### Verdict

**Is §14 buildable as written? No — but it is buildable as corrected, and none of the corrections touch a decision.**

All five product judgements survive contact with the code and I endorse them: the pricing constants really are uncalibrated and undisclosed; the friction really does sit on the brand (and is **worse** than §14 measured — see W9); hidden AI authorship really is a liability we would have to unwind; the cap really does throttle the heaviest users first; and shipping 24 engineer-days of automation on top of unvalidated flags and unvalidated prices is the wrong order. The B0/B1 split with a measured gate is the right call and 14.5.b — Phase A deployed before B0 can be measured — is the most important line in the section, because it turns the Docker/VPS smoke test from a deploy chore into a critical-path dependency.

**Must change before B0 day 1** (in priority order):

1. **Add `edited TINYINT(1) NOT NULL DEFAULT 0` to §2.4's `meera_drafts` DDL** and set it in §3.7's approve; restate gate metric 3 against `SENT` / `edited`. *(W21, W22.)* This is first because §2.4 is a B0 day-1 migration: free now, a second migration mid-measurement later, and without it a threshold Swapnil is being asked to sign off cannot be computed at all.
2. **Fix 14.4.b's gate.** `check_creator_spend_gate(f"{pid}:brief", "CREATOR", cap_usd=…)`, not `check_spend_gate(workspace_id=…)`. *(W14, W15.)* As written the separate brief cap enforces nothing, and it fails silently — the worst possible failure mode for a cost control.
3. **Fix 14.1.e's anchor formula** to `total × 1.15` uncapped for own/tier and `min(total × 1.10, rangeMax)` for benchmark. *(W5.)* As written it does not compile against a branch that has no range.
4. **Fix 14.1.d's offer-history query** to distinct collaboration ids. *(W4.)* Otherwise the feedback-loop label — the whole point of the honesty guard — fires off a single deal.
5. **Fix 14.1.f / 14.2.e's audit helper** to `recordAdminAction`'s shape with an explicit outcome. *(W6, W11.)* Writing funnel rows through `recordAuthRejection` puts false REJECTED rows in an append-only money-audit table.
6. **Rewrite 14.2.c's form description honestly**: eight fields including a required Indian mobile and a terms checkbox. *(W8, W9, W10.)* Then take the India-only registration constraint to Swapnil as its own question — it is a bigger brand-acquisition fact than anything else in §14.2, and it was invisible.
7. **Drop the Flyway renumbering mandate** or restate it as hygiene. *(W18.)* `out-of-order: true` is already set.
8. **Correct the §9 test row and the two anchor cells**, and keep the three pointers added above. *(W3, W13, W23.)*

Items 9-15 (W1, W2, W7, W12, W16, W17, W19, W20 and the fifteen DRIFTED entries) are annotated inline and can be absorbed by whoever touches the section; none of them blocks day 1.

**One thing §14 got right that is worth naming:** the claim it was most likely to have invented — "there are zero completed deals with an `agreed_rate` in any seed migration" — is true. No migration inserts into `collaborations` at all, and no migration insert anywhere carries `agreed_rate`. The cold-start problem is real and the honest label is the right response to it.

---

## 15. Credits sub-track (2026-09-05)

The credit model for creator Meera (turn 1 / voice 2 / brief 3, 30 signup + 40 monthly, packs ₹149/₹249/₹649) is specified separately in **`CREDITS-SPEC.md`** in this folder, grounded in `facts/credits-billing.md`. It touches `MeeraSessionService.doSendTurn` (creator branch, L332–344), `doPersistAssistantWriteback` (L575), `releaseTurnCredit` (L635), `MeeraInternalController.releaseTurnCredit` (L354–360), `CreatorMeeraController` (session/turn responses, transcribe), `RazorpayWebhookController.dispatchFundingEvent`, and `AdminCreatorAgentController`, and reserves migrations `V20260912100000`–`V20260912100300` (outside this spec's `V20260910*` block). The only hook inside this spec is §3.8 `paste` step 2→3: the brief charge and its fallback refund (`CREDITS-SPEC.md` §4.3). §14.4's USD cap stays as the cost fuse under the credit gate (`CREDITS-SPEC.md` R4). Ships dark behind `CREATOR_CREDITS_ENABLED=false`.
