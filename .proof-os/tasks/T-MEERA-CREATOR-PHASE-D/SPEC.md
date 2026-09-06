# T-MEERA-CREATOR-PHASE-D — Build Spec: Deal-Linked Growth Rules and the Weekly Note

**Status:** ready for Priya review, then build. **Baseline commit:** `143ca1e` plus the uncommitted working tree. Steps marked **[needs Phase B]** use the creator tool controller and `CreatorToolName`; **[needs Phase C]** use `CreatorMoneyService`, `deliverables.removed_detected_at`, snapshot `platform_media_id`, `ChannelRouter`, and the WhatsApp templates. Everything else builds on HEAD. **Date:** 2026-09-05.
**Fact sheet:** `facts/health-digest.md` (this phase), plus Phase B, C, E fact sheets. If a fact sheet and this spec disagree, the fact sheet wins and this spec has a bug to report.

Plan reference: Meera for Creators plan rev 2, Part 7 Phase D (week 6, one week), as cut by the panel: deal-linked rules only, a weekly note whose first line is money, measured by rate changes and brand shortlist rate, not report opens. Niche drift and gone-quiet are deferred and stay deferred.

---

## 0. Rules that apply to every step

Rules 1 to 26 from Phases B, C and E apply. Two rules Priya added after Phase C are restated because this spec was written under them:

27. **Cite only symbols you opened.** Every class, method, column, and line number in this document was read in the source on 2026-09-04. Anything the author could not open is marked `[VERIFY]` with the file to open, never asserted.
28. **Never instruct a change to code you did not read.** Where a step edits an existing method, the current body is described first.

Phase D adds:

29. **No creator caption text reaches any model on the creator scope.** This is Priya's R1 ruling, binding, quoted in three places (`routes/creator_suggestion.py` L23-28, `prompt/creator_suggestion.py` L17-22, `CreatorSuggestionAiDtos.java` L10-13). Every rule in this phase that reads a caption is deterministic Java, the same way `ThemeMatchService.themesForText` is. The GARM classifier on the creator's own captions stays out of Phase D pending the Tier-2 ruling (plan Part 8, decision 11).
30. **A rule that lacks its data says `DATA_GAP`, never guesses.** Preconditions are evaluated first and recorded with the reason.
31. **Every flag names the data it fired on, says what it costs the creator, and gives one action.** Legal ones are not dismissible; every other one is.
32. **Numbers to the creator are rendered by Java** into formatted strings, as in every previous phase.

---

## 1. Scope in one table

| Id | Job | Depends on | Backend | AI | Frontend |
|----|-----|-----------|---------|----|----------|
| D0 | Foundation: caption finder and label verdict columns, series helpers, shortlist counter, FY payout sum, reply-speed query, revive the follower-spike signal | HEAD | migrations, repository finders, `ScoreCalculationJob` fix | | |
| D1 | Deterministic label check: multilingual vocabulary, first-125-chars rule, per-post verdicts, Influora-linked deliverable check | HEAD; linked check **[needs Phase C]** | `DisclosureLabelService`, vocabulary resource, `CaptionLabelJob` | | |
| D2 | Account health rules with DATA_GAP: labelled-sponsored ratio with season mode, unlabelled linked deliverable, follower spike with flat reach, removed during keep-up, GST threshold, reply speed | HEAD; two rules **and the job's audit line [needs Phase C]** <!-- PRIYA: was "HEAD; two rules". 5.3 calls `AuditLogService.recordSystemEvent`, which does not exist at HEAD — Phase C §3.11 adds it. On HEAD alone the job logs counts and writes no audit row. --> | `AccountHealthService`, rules, flags table, `AccountHealthJob`, routes | `get_account_health` tool **[needs Phase B]** | health section, flag cards |
| D3 | Weekly note: money first line, one post to make, one thing to fix, why your rate should move; in-app, email, WhatsApp, push; read aloud | HEAD; channels **[needs Phase C]** | digest table, builder, `WeeklyDigestJob`, event, template, audio | none (deterministic text) | week card, settings |
| D4 | Measurement: rate movement, shortlist rate, flags raised and cleared, digest opened and tapped; replace the hardcoded label-compliance sample | HEAD | admin growth metrics route, baseline fix | | admin panel |

Out of scope: niche drift, gone-quiet, the GARM classifier on own captions, brand weekly digest (its switch stays disabled and its test stays), YouTube, any model call that receives a caption.

---

## 2. Data model

Migrations timestamp-versioned above Phase C (`V20260930101000`): use `V20261005...`. Every `CREATE TABLE` ends with `) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;`. Every `VARCHAR(n)` field carries `@Column(length = n)`. `MEDIUMTEXT` for JSON payloads a job writes.

### 2.1 `V20261005100000__creator_captions_label.sql` (alter)

```sql
ALTER TABLE creator_captions
    ADD COLUMN label_status VARCHAR(24) NULL,            -- LABELLED_UPFRONT | LABELLED_LATE | UNLABELLED | NO_CAPTION
    -- PRIYA: was VARCHAR(16). LABELLED_UPFRONT is exactly 16 characters — zero margin, and 5.4's
    -- confirm-label flow already contemplates further statuses. 24 with @Column(length = 24).
    ADD COLUMN label_matched VARCHAR(64) NULL,           -- the vocabulary entry that matched, e.g. "#ad" or "प्रायोजित"
    ADD COLUMN label_vocab_version VARCHAR(16) NULL,
    ADD COLUMN label_checked_at TIMESTAMP NULL,
    ADD COLUMN media_type VARCHAR(20) NULL;              -- from Graph media_type, needed to exclude Stories
CREATE INDEX idx_creator_captions_creator_posted ON creator_captions (creator_profile_id, posted_at);
CREATE INDEX idx_creator_captions_label_pending ON creator_captions (label_checked_at, created_at);
```

Entity `CreatorCaptionCache` (`domain/entity/CreatorCaptionCache.java`): five fields with explicit `@Column(name = ..., length = ...)`; enum `CaptionLabelStatus {LABELLED_UPFRONT, LABELLED_LATE, UNLABELLED, NO_CAPTION}` stored as string; mutator `applyLabelVerdict(CaptionLabelStatus status, String matched, String vocabVersion, Instant checkedAt)`; builder gains `.mediaType(String)`. `CreatorCaptionSyncJob.persistIfNew` (L186-205) passes **`item.mediaType()`** into the builder (`InstagramMediaResponse.MediaItem` L12-20 declares `@JsonProperty("media_type") String mediaType` — `media_type` is the wire key, `mediaType()` is the accessor). Existing rows have `media_type NULL` and are treated as feed posts.

<!-- PRIYA: was `item.media_type()`, which does not compile. Record components take their Java name;
     the @JsonProperty only renames the wire key. Verified at InstagramMediaResponse.java L12-20.
     Line range for MediaItem corrected from L11-20 to L12-20 (L11 is the annotation). -->
<!-- PRIYA: the builder's build() stamps tagStatus = PENDING and createdAt = now (L150-154). The new
     .mediaType(String) setter goes on Builder alongside .postedAt; do not touch build(). -->
<!-- PRIYA: the six-field claim is right if you count media_type — five label columns is wrong; it is
     four label columns plus media_type. Entity gains five fields total. -->
<!-- PRIYA: `label_matched` is populated from the VOCABULARY entry, never sliced out of the caption.
     That is what keeps rule 29 true for this column; 12's security answer already says so. -->
<!-- PRIYA: the caption entity carries its own Kabir-signed risk acceptance for plaintext caption_text
     (CreatorCaptionCache L22-28). The V26 "BrandSafety pipeline ONLY" note is on MediaMetric.caption
     (L42-48) and does NOT reach this table. Reading caption_text here is in bounds. -->
<!-- PRIYA: rule 29 holds end to end — I traced every step in this phase that touches a caption and
     none of them reaches a model. The only creator-scope AI call is the pre-existing daily
     suggestion, whose wire body is three fields with no caption (CreatorSuggestionAiDtos L10-13). -->

Repository `CreatorCaptionCacheRepository` gains:

```java
List<CreatorCaptionCache> findByCreatorProfileIdAndPostedAtAfterOrderByPostedAtDesc(String creatorProfileId, Instant after, Pageable pageable);
List<CreatorCaptionCache> findByLabelCheckedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
Optional<CreatorCaptionCache> findFirstByCreatorProfileIdAndIgMediaId(String creatorProfileId, String igMediaId);   // same as the existing two-arg finder; keep the existing one, this line is a reminder not to add a duplicate
```

(Delete the third line before building; the existing `findByCreatorProfileIdAndIgMediaId` is used as is.)

### 2.2 `V20261005100100__creator_health_flags.sql` (create)

```sql
CREATE TABLE creator_health_flags (
    id                  VARCHAR(26)  NOT NULL,
    creator_profile_id  VARCHAR(26)  NOT NULL,
    code                VARCHAR(40)  NOT NULL,            -- section 5 codes
    severity            VARCHAR(16)  NOT NULL,            -- INFO | WARN | CRITICAL | POSITIVE
    dismissible         TINYINT(1)   NOT NULL,
    title               VARCHAR(120) NOT NULL,
    detail              VARCHAR(500) NOT NULL,            -- one sentence, numbers rendered
    cost_line           VARCHAR(200) NULL,
    action_line         VARCHAR(300) NOT NULL,
    data_json           TEXT         NOT NULL,            -- rendered strings only
    entity_type         VARCHAR(24)  NULL,                -- DELIVERABLE | CAPTION | PROFILE
    entity_id           VARCHAR(64)  NOT NULL DEFAULT '', -- PRIYA: was NULL. See the note below the DDL.
    first_seen_at       TIMESTAMP    NOT NULL,
    last_seen_at        TIMESTAMP    NOT NULL,
    cleared_at          TIMESTAMP    NULL,
    dismissed_at        TIMESTAMP    NULL,
    open_marker         VARCHAR(1)   NULL,                -- 'O' while open (not cleared, not dismissed), NULL otherwise
    rule_version        VARCHAR(16)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chf_open (creator_profile_id, code, entity_id, open_marker),
    CONSTRAINT fk_chf_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_chf_creator_open (creator_profile_id, open_marker, severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

`entity_id` is `''` (empty string, never NULL) for profile-level flags so the unique key holds one open row per code. Entity `CreatorHealthFlag`; enums `HealthFlagCode` (section 5), `HealthSeverity {INFO, WARN, CRITICAL, POSITIVE}` **with an explicit `int rank()`**; mutators `touch(Instant seen, String detail, String costLine, String dataJson)`, `clear(Instant)`, `dismiss(Instant)`; `open_marker` maintained by the entity on every state change. Repository: `findByCreatorProfileIdAndOpenMarker(String, String)`, `findByCreatorProfileIdAndCodeAndEntityIdAndOpenMarker(String, HealthFlagCode, String, String)`, `countByCodeAndFirstSeenAtBetween(HealthFlagCode, Instant, Instant)`, `countByCodeAndClearedAtBetween(...)`, `countByCodeAndDismissedAtBetween(...)`.

<!-- PRIYA: two corrections in that paragraph, both load-bearing.

     1. `entity_id` is now NOT NULL DEFAULT '' in the DDL, with @Column(nullable = false, length = 64)
        and a null -> '' coercion in the entity constructor and in touch(). The open_marker trick
        itself is sound: MySQL exempts a row from a UNIQUE KEY when any keyed column is NULL, so
        cleared and dismissed rows (open_marker = NULL) never collide, which is exactly rule 23.
        But it is load-bearing that entity_id is '' and NEVER NULL for profile-level flags — with
        entity_id NULL the unique key does not fire AT ALL and AccountHealthJob inserts a fresh open
        row every night: an unbounded table and a duplicated card on the creator's screen. An empty
        string does survive as '' in a MySQL VARCHAR (no Oracle-style ''->NULL coercion), so the
        value was fine; what was missing was anything FORCING it. Now the schema owns the invariant.

     2. `findByCreatorProfileIdAndOpenMarkerOrderBySeverityDescLastSeenAtDesc` was WRONG and would
        have shipped. severity is @Enumerated(STRING) in a VARCHAR, so a derived OrderBySeverityDesc
        sorts the stored STRINGS alphabetically: WARN > POSITIVE > INFO > CRITICAL — the exact
        reverse of what 6.1 item 4 and 8.2 require. It compiles, it passes a one-flag test, and it
        puts a non-dismissible legal flag LAST on the creator's screen. Drop the ordering from the
        finder; sort in Java by HealthSeverity.rank() (CRITICAL 3, WARN 2, INFO 1, POSITIVE 0) then
        lastSeenAt desc, inside AccountHealthService.openFlags. Same fix applies to 6.1 item 4.

     Key size checks (both fine): uk_chf_open is 26+40+64+1 = 131 chars x 4 bytes utf8mb4 = 524 bytes,
     well inside InnoDB's 3072-byte limit; idx_chf_creator_open is 172 bytes. FK target
     creator_profiles(id) is VARCHAR(26) since V20260718130000, so the types match.

     Race not closed by the key: evaluate() is reachable from a route as well as the locked job, so
     two runs can collide on uk_chf_open. Catch DataIntegrityViolationException at the upsert and
     re-read — and use saveAndFlush, because a catch around a plain save() on a managed entity is
     dead code (the violation fires at commit, outside the catch). -->

<!-- PRIYA: index names checked against every migration in resources/db/migration. No collisions, and
     MySQL scopes index names per table anyway. idx_creator_scores_creator_time (3.1) does exist, in
     V22__creator_scores.sql — that claim is correct. `INDEX idx_chr_creator_time (..., evaluated_at
     DESC)` is valid: MySQL 8 supports descending indexes. -->

### 2.3 `V20261005100200__creator_health_runs.sql` (create)

```sql
CREATE TABLE creator_health_runs (
    id                  VARCHAR(26) NOT NULL,
    creator_profile_id  VARCHAR(26) NOT NULL,
    evaluated_at        TIMESTAMP   NOT NULL,
    rule_version        VARCHAR(16) NOT NULL,
    outcomes_json       TEXT        NOT NULL,             -- {"LABELLED_SPONSORED_RATIO":"OK","FOLLOWER_SPIKE":"DATA_GAP:NO_TOKEN",...}
    data_gaps_json      TEXT        NULL,
    duration_ms         INT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chr_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_chr_creator_time (creator_profile_id, evaluated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Retention 90 days, purged by `AccountHealthJob` at the end of each run.

### 2.4 `V20261005100300__creator_weekly_digests.sql` (create)

```sql
CREATE TABLE creator_weekly_digests (
    id                  VARCHAR(26)  NOT NULL,
    creator_profile_id  VARCHAR(26)  NOT NULL,
    week_start          DATE         NOT NULL,            -- Monday, IST
    payload_json        MEDIUMTEXT   NOT NULL,            -- DigestPayload, rendered strings
    first_line          VARCHAR(200) NOT NULL,
    channels_sent       VARCHAR(64)  NULL,                -- "in_app,email,whatsapp,push"
    audio_r2_key        VARCHAR(500) NULL,
    generated_at        TIMESTAMP    NOT NULL,
    opened_at           TIMESTAMP    NULL,
    tapped_at           TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cwd_creator_week (creator_profile_id, week_start),
    CONSTRAINT fk_cwd_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_cwd_week (week_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `CreatorWeeklyDigest`; mutators `markOpened(Instant)`, `markTapped(Instant)`, `applyAudio(String r2Key)`, `applyChannels(String)`. Repository: `findByCreatorProfileIdAndWeekStart(String, LocalDate)`, `findFirstByCreatorProfileIdOrderByWeekStartDesc(String)`, `countByWeekStart(LocalDate)`, `countByWeekStartAndOpenedAtIsNotNull(LocalDate)`, `countByWeekStartAndTappedAtIsNotNull(LocalDate)`.

### 2.5 `V20261005100400__creator_agent_preferences_phase_d.sql` (alter)

```sql
ALTER TABLE creator_agent_preferences
    ADD COLUMN weekly_note_enabled TINYINT(1) NOT NULL DEFAULT 1,
    ADD COLUMN season_mode_until DATE NULL;
```

Entity fields `weeklyNoteEnabled`, `seasonModeUntil` with explicit `@Column`; mutators `applyWeeklyNote(boolean)`, `applySeasonMode(LocalDate until)`. `PreferencesResponse` and `UpdatePreferencesRequest` in `CreatorAgentDtos` gain `weekly_note_enabled` (boolean) and `season_mode_until` (String date or null). Both records are positional. **Counted on the working tree at `143ca1e` (Phase B and Phase C are unbuilt, so these are the counts a builder will actually meet):**

| Record | Sites | Where |
|---|---|---|
| `CreatorAgentDtos.PreferencesResponse` | **4** | `CreatorAgentPreferencesService.toResponse` L285-288; `CreatorAgentControllerTest` L65 and L86; `CreatorMeeraControllerTest` L186 |
| `CreatorAgentDtos.UpdatePreferencesRequest` | **9** | all in tests: `CreatorAgentPreferencesServiceTest`, `CreatorAgentControllerTest` |

Update every one in the same commit. `CreatorContextResponse` is not touched.

<!-- PRIYA: counts written in as instructed, and one trap with them.

     A bare `grep "new PreferencesResponse("` returns FIVE, not four. The fifth is
     NotificationController L262 — `new PreferencesResponse(preferences)`, a ONE-ARG constructor for
     an unrelated notification-preferences record that merely shares the simple name. Grep the
     qualified name or the import, not the simple name, or you will spend an afternoon adding two
     arguments to a record that does not have them.

     The nine UpdatePreferencesRequest sites are ALL in tests — no main-source construction site —
     so nothing in main breaks, but the round-trip test does.

     Frontend consequence, verified: CreatorAgentPreferencesUpdate really is
     Omit<CreatorAgentPreferences, 'consent_accepted' | 'consent_version'> (src/lib/api.ts
     L6295-6298). So adding the two fields to CreatorAgentPreferences carries them into the PUT
     automatically via MeeraSettingsSection's `...draft` spread. That is exactly why
     UpdatePreferencesRequest must gain them in the SAME commit — otherwise the browser sends two
     fields the Java record does not declare, Jackson drops them silently (FAIL_ON_UNKNOWN is off by
     default), and MeeraSettingsSection.roundtrip.test.tsx's re-seed-from-response assertion fails
     with no clue why. -->

<!-- PRIYA: `creator_agent_preferences.creator_id` holds a creator_profiles.id, NOT a users.id —
     CreatorAgentPreferencesService L79-84 does findByCreatorId(profile.getId()). 5.3's candidate
     query ("creator profiles with a preferences row") is therefore correct as written. -->

### 2.6 Season calendar resource

`influora-api/src/main/resources/creator-agent/season-calendar.json`:

```json
{"version": "2026.1", "seasons": [
  {"name": "Diwali", "from": "2026-10-10", "to": "2026-11-21"},
  {"name": "Wedding season", "from": "2026-11-15", "to": "2026-12-27"},
  {"name": "Holi", "from": "2027-02-22", "to": "2027-03-14"}
]}
```

Loaded by `SeasonCalendar` (`@Component`, `@PostConstruct`, fail-closed to empty like `ThemeMatchService` L39-54), `boolean inSeason(LocalDate)`. A creator's `season_mode_until` extends season mode manually for six weeks.

### 2.7 Disclosure vocabulary resource

`influora-api/src/main/resources/creator-agent/disclosure-labels.json`:

```json
{"version": "2026.1",
 "labels": [
   {"text": "#ad", "kind": "HASHTAG"}, {"text": "#advertisement", "kind": "HASHTAG"}, {"text": "#sponsored", "kind": "HASHTAG"},
   {"text": "#paidpartnership", "kind": "HASHTAG"}, {"text": "#paidpromotion", "kind": "HASHTAG"}, {"text": "#collab", "kind": "HASHTAG"},
   {"text": "#collaboration", "kind": "HASHTAG"}, {"text": "#partnership", "kind": "HASHTAG"}, {"text": "#gifted", "kind": "HASHTAG"},
   {"text": "#freebie", "kind": "HASHTAG"}, {"text": "#employee", "kind": "HASHTAG"}, {"text": "#brandpartner", "kind": "HASHTAG"},
   {"text": "paid partnership with", "kind": "PHRASE"}, {"text": "in partnership with", "kind": "PHRASE"}, {"text": "sponsored by", "kind": "PHRASE"},
   {"text": "in collaboration with", "kind": "PHRASE"}, {"text": "advertisement", "kind": "WORD"}, {"text": "sponsored", "kind": "WORD"},
   {"text": "#विज्ञापन", "kind": "HASHTAG"}, {"text": "विज्ञापन", "kind": "WORD"}, {"text": "प्रायोजित", "kind": "WORD"},
   {"text": "#प्रायोजित", "kind": "HASHTAG"}, {"text": "सहयोग", "kind": "WORD"}, {"text": "#sahyog", "kind": "HASHTAG"},
   {"text": "#vigyapan", "kind": "HASHTAG"}, {"text": "#prayojit", "kind": "HASHTAG"},
   {"text": "#जाहिरात", "kind": "HASHTAG"}, {"text": "जाहिरात", "kind": "WORD"},
   {"text": "#விளம்பரம்", "kind": "HASHTAG"}, {"text": "#ప్రకటన", "kind": "HASHTAG"}, {"text": "#ജാഹിരാത", "kind": "HASHTAG"},
   {"text": "#ಜಾಹೀರಾತು", "kind": "HASHTAG"}, {"text": "#বিজ্ঞাপন", "kind": "HASHTAG"}, {"text": "#જાહેરાત", "kind": "HASHTAG"}
 ],
 "upfront_chars": 125}
```

The ASCI 2021 guidelines accept "Advertisement", "Ad", "Sponsored", "Collaboration", "Partnership", "Employee", "Free gift" in English and Indian languages; the file carries transliterations and the six most common scripts. Content is ops-editable; the version is stamped on every verdict. `WORD` entries match on Unicode word boundaries (`\b` is ASCII-only in Java; use **`(?<![\p{L}\p{M}])` and `(?![\p{L}\p{M}])`** lookarounds with `Pattern.UNICODE_CHARACTER_CLASS`), `HASHTAG` entries match case-insensitively at a `#` boundary, `PHRASE` entries match as a case-insensitive substring.

<!-- PRIYA: the lookarounds were `(?<!\p{L})` / `(?!\p{L})`; corrected to include \p{M}.

     Devanagari matras and the virama are \p{Mn} (nonspacing mark), not \p{L}. With \p{L} alone, an
     inflected form such as प्रायोजितों reads as a boundary right after the stem, so the entry matches
     inside a word it should not. \p{M} closes it. This is the whole reason the vocabulary is
     multilingual — getting the boundary class wrong makes the Indic half decorative.

     Otherwise the regex design is implementable in Java exactly as described, and I checked each
     piece: Pattern.UNICODE_CHARACTER_CLASS plus lookarounds is the correct construction (Java's \b
     really is ASCII-only); java.text.Normalizer.normalize(s, Form.NFC) covers the normalisation;
     and the negative cases fall out correctly from the hashtag rule — #ads does not match #ad
     (followed by 's', not whitespace/punctuation/end), #adventure does not match #ad (followed by
     'v'). Lowercasing with Locale.ROOT for the ASCII entries only is right; do NOT lowercase the
     Indic entries (Locale.ROOT lowercase is a no-op on them, but the intent should be explicit). -->

<!-- PRIYA: rule 27 does not reach a .json resource, and this file is the one part of the spec nobody
     on this team can check by reading. A wrong glyph produces a silent false UNLABELLED on a legal
     flag that is NOT dismissible. The day-1 native-speaker check in section 10 is a BLOCKING gate,
     not a checklist line. Until it passes, UNLABELLED_LINKED_DELIVERABLE ships as WARN and
     dismissible. --> Marathi `जाहिरात`, Tamil, Telugu, Malayalam, Kannada, Bengali, Gujarati entries are the ASCI-recognised words for advertisement; a native speaker on the team confirms each on day 1 (checklist item).

Four migrations plus two resources. `MeeraPhaseDBootValidationTest` (Testcontainers, `mysql:8.0.40`, `ddl-auto=validate`, `DockerAvailableCondition`) covers the four.

---

## 3. D0: foundation

### 3.1 Series and count helpers (new repository methods, all with explicit signatures)

- `SavedCreatorRepository`: `long countByCreatorProfileIdAndSavedTrue(String creatorProfileId);` and `long countByCreatorProfileIdAndSavedTrueAndUpdatedAtAfter(String creatorProfileId, Instant after);` (the entity has `updated_at`, bumped by `setSaved`).
- `PayoutRepository`: `@Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payout p WHERE p.creatorUserId = :creatorUserId AND p.confirmedAt BETWEEN :from AND :to AND p.status NOT IN :failedStatuses") BigDecimal sumSettledAmountByCreatorUserIdBetween(@Param("creatorUserId") String creatorUserId, @Param("from") Instant from, @Param("to") Instant to, @Param("failedStatuses") Collection<String> failedStatuses);` — callers pass `PayoutReconciliationService.FAILURE_STATUSES`. Style matches the existing `sumAmountByCreatorUserIdAndConfirmedAtIsNull` (L114-118).

<!-- PRIYA: the original query was WRONG and the wrongness is on the money path.

     The spec's version was `... AND p.confirmedAt BETWEEN :from AND :to`, described as "confirmed
     payouts only". That is not what confirmedAt means here, and PayoutRepository's own javadoc
     (L100-113, directly above the sibling query the spec cites as its model) says so in full:
     confirmStatus stamps confirmedAt on EVERY terminal webhook — processed, but also reversed,
     rejected and cancelled (PayoutReconciliationService.FAILURE_STATUSES, L63-64). Those are
     payouts where the money never reached the creator and was already re-credited to the wallet.

     So the original sum counts failed payouts as FY income. The consumer is
     GST_THRESHOLD_APPROACHING (5.2), which tells a creator they are near a statutory registration
     threshold. Over-counting there is not a rounding error — it sends a creator to their CA on
     numbers we invented. Filter the failure statuses.

     Second, smaller: STATUS_MANUAL_PAID ("MANUAL_PAID", Payout L110, the V71 manual rail) is real
     money that did reach the creator. Confirm on the working tree whether the manual rail stamps
     confirmedAt; if it does not, this sum silently misses every manually-paid rupee and the rule
     under-fires. Either way, state the answer in 5.2's detail line rather than leaving it implicit.

     Line reference corrected: the sibling query is L114-118, not L115-119. -->
<!-- PRIYA: `p.creatorUserId` is a users.id (Payout L34-35) and the FY window comes from
     InvoiceNumberService.currentFiscalYear() (Apr-1 IST cutoff). Both correct as the spec has them. -->
- `CreatorScoreRepository`: `List<CreatorScore> findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(String creatorProfileId, Instant from, Instant to);` (rows are daily from `ScoreCalculationJob`; index `idx_creator_scores_creator_time` exists).
- `MediaMetricsRepository`: `@Query("SELECT m FROM MediaMetric m WHERE m.creatorProfileId = :creatorProfileId AND m.time = (SELECT MAX(m2.time) FROM MediaMetric m2 WHERE m2.mediaId = m.mediaId) ORDER BY m.postedAt DESC") List<MediaMetric> findLatestPerMediaByCreatorProfileId(@Param("creatorProfileId") String creatorProfileId, Pageable pageable);` — the greatest-n-per-group the fact sheet says is missing; mirrors `CreatorMetricsRepository.findLatestPerCreatorAndPlatform` L55-60.

<!-- PRIYA: checked, and this one is CORRECT as written — both halves of it.
     - JPQL DOES allow that correlated subquery on this Hibernate version. The proof is in-repo:
       CreatorMetricsRepository.findLatestPerCreatorAndPlatform (L55-60) is the identical shape,
       already running in production, and its own javadoc calls it a "plain JPQL correlated
       subquery".
     - `m.postedAt` DOES exist on MediaMetric — L80-81, @Column(name = "posted_at",
       columnDefinition = "DATETIME(6)"), nullable. Order NULLS LAST or accept that an unbackfilled
       row sorts unpredictably.
     One caveat the spec does not state: media_metrics is indexed on (media_id) ALONE
     (V21__*.sql L62), not (media_id, time), so this is a scan per media group. The 60-row Pageable
     in 5.1 is the control. NEVER call this unpaged.
     A @Query returning List with a Pageable gets LIMIT appended and needs no count query — correct.
     Pass PageRequest.of(0, 60) with NO Sort, or Spring appends a second ORDER BY. -->
- `DealMessageRepository` — **[VERIFY resolved by Priya 2026-09-05]**: `List<DealMessage> findByCollaborationIdInAndCreatedAtAfterOrderByCreatedAtAsc(Collection<String> collaborationIds, Instant after);` — one query per creator for the reply-speed rule; the pairing is computed in Java.

<!-- PRIYA: [VERIFY] resolved — I opened the file. It has exactly SIX finders, as guessed:
       findByCollaborationIdOrderByCreatedAtAsc                      L15
       existsByCollaborationIdAndSenderType                          L18
       findFirstByCollaborationIdAndKindOrderByCreatedAtDesc         L24-25
       findPageBefore (@Query with Pageable)                         L27-33
       findFirstByCollaborationIdOrderByCreatedAtDesc                L35
       findFirstMessageTimestampsBySender (@Query, projection)       L52-57
     The proposed derived finder is valid — DealMessage declares collaborationId (L56-57) and
     createdAt (L81-82) — and collides with none of them. Marking this [VERIFY] rather than
     asserting it was the right call; the guess behind it happened to be right.

     Two notes for the builder. (a) Guard an empty collaborationIds list at the call site anyway;
     Hibernate 6 handles an empty IN, but an empty list here means "this creator has no deals",
     which is a DATA_GAP, not an empty result. (b) DealSenderType.brand / .creator are LOWERCASE
     enum constants — confirmed by the JPQL at L54-55 — and 3.3 already has them right. -->
<!-- PRIYA: CreatorAgentBaselineService already pairs first-brand/first-creator timestamps globally
     via findFirstMessageTimestampsBySender (unwindowed, platform-wide). That is a different
     measurement from 3.3's per-creator windowed median. Do not conflate them in D4. -->
- `CollaborationRepository`: nothing new; `findByCreatorId(String creatorUserId)` exists (Phase B facts) and takes a `users.id`.
- `MetaOAuthTokenRepository`: `findByCreatorProfileIdAndRevokedFalseAndExpiresAtAfter(String, Instant)` exists (Phase B/C facts) and gives the token precondition; connection age comes from `MetaOAuthToken.createdAt`.

### 3.2 Revive the follower-spike signal

`ScoreCalculationJob.scoreOne` (**L267-325**) calls `fakeFollowerDetectionService.analyze(latestMetric, recentMedia, List.of())` at **L288-289** with the comment that the history is not readily available. Change the third argument to `creatorMetricsRepository.findByCreatorProfileIdAndPlatformAndTimeBetweenOrderByTimeAsc(creatorProfileId, PLATFORM_INSTAGRAM, now.minus(30, DAYS), now)` (finder exists, L43-44).

<!-- PRIYA: line numbers corrected (scoreOne L266-324 -> L267-325; the analyze call L286-289 ->
     L288-289). The substance is all correct and I verified each piece:
       - PLATFORM_INSTAGRAM is a private constant on the job, L97. Reuse it, do not inline "INSTAGRAM".
       - the 30-day finder exists verbatim at CreatorMetricsRepository L43-44.
       - FakeFollowerDetectionService signal 2 is exactly as described: historicalMetrics.size() >= 7,
         maxDailyGrowth > 20 -> +30, > 10 -> +15. Confirmed in the source.
       - CreatorMetricsRepository's javadoc (L22-31) requires caller-supplied ids to go through
         MetricsAuthorizationService. A scheduled job holds a server-derived id, so it is exempt —
         but AccountHealthService (5.1) reads the same finder from a REQUEST path via the health
         routes, and there the id must come from the authenticated principal's own profile, never
         from a path variable. Say it in the controller.
     One thing the step does not mention: scoreOne has no `now` in scope — it calls Instant.now()
     inline twice. Hoist a single `Instant now = Instant.now();` at the top so the window and the
     row's time/computedAt agree, rather than adding a third clock read. -->
<!-- PRIYA: this is a scoring change with a brand-visible blast radius — brands read the authenticity
     score, and a creator's rate estimate falls out of the quality score. 3.2 is right to say so.
     Add: run one night in shadow and diff the fake_follower_score distribution before the flag
     flips. A score that moves for the right reason still moves someone's rate. --> The service's signal 2 (`historicalMetrics.size() >= 7`, +30 at >20 percent daily growth, +15 at >10) starts firing. This changes `creator_scores.fake_follower_score` for creators with a real spike, which is the intended behaviour and the documented purpose of the signal. Note it in `wiki/tech/` as a scoring change with the date, since brands see the authenticity score. Phase D's own spike rule (5.3) is separate and gentler; the two are consistent because the rule reads the same series.

### 3.3 Reply-speed pairing helper

`ReplySpeed.medianHours(List<DealMessage> messages, String creatorUserId)` (pure static, `com.influora.service.health`): sort by `createdAt`; for each message with `senderType == DealSenderType.brand` find the next message from `senderType == DealSenderType.creator` in the same collaboration; collect the hour deltas; return the median as `Optional<Double>`; fewer than 3 pairs → empty. Unit-tested with interleaved threads.

---

## 4. D1: the deterministic label check

### 4.1 `DisclosureLabelService` (`com.influora.service.health`)

```java
public record LabelVerdict(CaptionLabelStatus status, String matched, String vocabVersion) {}
public LabelVerdict check(String captionText)
```

Algorithm: null or blank → `NO_CAPTION`. Normalise: NFC, collapse whitespace, lowercase with `Locale.ROOT` for ASCII entries only (Indic scripts have no case). Scan `upfront = first 125 code points` for any vocabulary entry → `LABELLED_UPFRONT` with the matched text; else scan the whole caption → `LABELLED_LATE`; else `UNLABELLED`. Hashtag match: the entry preceded by start-of-text or whitespace and followed by end-of-text, whitespace, or punctuation. `WORD` match with the lookarounds from 2.7. `PHRASE` substring. Vocabulary loaded from the resource with the `ThemeMatchService` loading pattern (L39-54), fail-closed to an empty list, in which case every verdict is `DATA_GAP` at the rule level (the service returns `UNLABELLED` only when the vocabulary loaded; expose `boolean isReady()`).

Tests: one per entry kind and script, first-125 boundary at 124 and 126, `#ads` (does not match `#ad`), `#adventure` (does not match), emoji-only caption, Hinglish "sponsored hai" (matches `sponsored` as a word), Marathi hashtag.

### 4.2 `CaptionLabelJob`

`@Component`, `@Scheduled(cron = "${influora.creator-agent.caption-label-cron:0 20 3 * * *}", zone = "UTC")` (after `CreatorThemeTaggingJob` at 03:00), `@SchedulerLock(name = "CaptionLabelJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")`, gated by `influora.creator-agent.health.enabled:false`. Reads `findByLabelCheckedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 500))` in a loop until empty or 5,000 rows; for each: `applyLabelVerdict(...)`, save. Stories (`media_type = "STORY"` if the sync ever returns them; the media endpoint returns feed media only, so today none) are marked `NO_CAPTION` and excluded from ratios. Runs only when `DisclosureLabelService.isReady()`.

Existing rows: the first run labels every caption already cached (the sync keeps 25 per creator per night, so the backlog is small).

### 4.3 The Influora-linked deliverable check **[needs Phase C]**

Phase C persists `platform_media_id` on `deliverable_metric_snapshots`. For a deliverable with status in `POSTED, METRICS_REPORTED, VERIFIED` and a known media id, look up `creatorCaptionCacheRepository.findByCreatorProfileIdAndIgMediaId(profileId, mediaId)`: absent → `DATA_GAP:CAPTION_NOT_SYNCED`

<!-- PRIYA: correct finder, verified — CreatorCaptionCacheRepository L12-13, returns
     Optional<CreatorCaptionCache>, and it is one of only two finders on that interface today. Use
     it as-is; 2.1's third bullet was right to say so and right to say delete that reminder line
     before building (`findFirstBy...` would have been a THIRD, duplicate finder).
     Phase C dependency confirmed on the other side too: Phase C section 2.1 really does add
     platform_media_id VARCHAR(100) to deliverables with a write-once applyPlatformMediaId(String),
     and its correction 10 explains why the id is persisted on first match rather than derived from
     the shortcode. The [needs Phase C] marking and the NO_MEDIA_ID fallback are both right. --> (the sync only caches the last 25 posts; a deliverable older than that is a gap, not a failure); present → the verdict. Without Phase C, the rule reports `DATA_GAP:NO_MEDIA_ID` for every deliverable.

---

## 5. D2: account health rules

### 5.1 Engine

`AccountHealthService` (`com.influora.service.health`, public `@Transactional` methods):

```java
public HealthRunResult evaluate(String creatorProfileId)                       // runs every rule, upserts flags, clears flags whose rule no longer fires, writes creator_health_runs
public List<HealthFlagView> openFlags(String creatorUserId)                    // creator-facing, rendered
public void dismiss(String creatorUserId, String flagId)                       // 409 FLAG_NOT_DISMISSIBLE for legal ones
```

`RuleContext` built once per creator: profile, prefs (through `CreatorAgentPreferencesService`, never the repository), token (`findByCreatorProfileIdAndRevokedFalseAndExpiresAtAfter`), 30-day `CreatorMetric` series (`findByCreatorProfileIdAndPlatformAndTimeBetweenOrderByTimeAsc`), latest-per-media `MediaMetric` list (3.1, limit 60), captions from the last 30 days (2.1 finder, limit 60), collaborations (`findByCreatorId(profile.getUserId())`), deliverables of those collaborations, deal messages of the last 30 days (3.1), confirmed payouts this FY (3.1), season flag, `now`, locale.

Preconditions, evaluated first and recorded as `DATA_GAP:<reason>`:

| Reason | Condition |
|---|---|
| `NO_TOKEN` | no valid token |
| `TOO_FEW_POLLS` | fewer than 20 `CreatorMetric` rows in the last 7 days (`MetricsPollingJob` runs every 6 hours, so 28 expected) |
| `RATE_LIMITED` | more than 25 percent of expected rows missing in the last 24 hours (a proxy for the tracker's in-memory state, which is not persisted) |
| `TOO_YOUNG` | token `createdAt` within 60 days |
| `NO_CAPTIONS` | fewer than 5 labelled-checked captions in 30 days |
| `VOCAB_NOT_LOADED` | `DisclosureLabelService.isReady()` false |
| `CAPTION_NOT_SYNCED`, `NO_MEDIA_ID` | 4.3 |
| `TOO_FEW_REPLIES` | fewer than 3 brand→creator message pairs in 30 days (the `REPLY_SPEED` precondition, 3.3) |

<!-- PRIYA: TOO_FEW_REPLIES was named in 5.2's precondition column but missing from this table, so
     rule 30 ("a rule that lacks its data says DATA_GAP, never guesses") had no reason code to emit.
     Added.

     TOO_FEW_POLLS arithmetic verified: MetricsPollingJob really does run `0 0 */6 * * *` UTC, so
     4 polls/day x 7 = 28 expected rows. The "fewer than 20 in 7 days" bar is right.

     RuleContext note: 5.1 says prefs come "through CreatorAgentPreferencesService, never the
     repository". At HEAD that service is keyed by USER id — getOrCreatePreferences(String userId),
     isConsentAccepted(String userId) — while evaluate() takes a creatorProfileId. Get the user id
     from profile.getUserId() (CreatorProfile L232). Do not reach for the repository; InfoBarrierTest
     with Phase B's widened scan roots (service/** and job/**) will catch it, and section 9 is right
     that the widening is a Phase B condition — Phase B section 3.6 requires it explicitly.
     Beware: getOrCreatePreferences CREATES a row. Harmless here, because 5.3's candidates already
     have one, but do not call it from a path where absence is meaningful. -->

`RuleOutcome {OK, FIRED(HealthFlagDraft), DATA_GAP(reason)}`. Rules are classes implementing `interface HealthRule { HealthFlagCode code(); RuleOutcome apply(RuleContext ctx); }` in `com.influora.service.health.rules`, unit-tested one firing and one non-firing case each.

### 5.2 Rules

| Code | Severity | Dismissible | Preconditions | Fires when | Title, detail, cost, action |
|---|---|---|---|---|---|
| `UNLABELLED_LINKED_DELIVERABLE` | CRITICAL | no | 4.3 | an Influora-linked deliverable's caption verdict is `UNLABELLED` or `LABELLED_LATE` | "No caption label found on your {brand} reel"; "We could not find #ad or an equivalent label in the first 125 characters. Was it in a comment or a sticker? Tap to confirm."; cost: "The brand can dispute delivery."; action: "Add the label to the caption or confirm where it is." `LABELLED_LATE` is INFO with "Move the label into the first line." |
| `LABELLED_SPONSORED_RATIO` | WARN | yes | NO_CAPTIONS, VOCAB | in the last 30 days, `labelled_posts / total_posts > threshold` where threshold = `prefs.weeklySponsoredLimit × 4 / total_posts` when the limit is set, else 0.30; in season mode the threshold is 0.45 | "Sponsored posts are {ratio} of your last {n}"; detail cites the reach trend: median reach of labelled posts versus unlabelled from the latest-per-media list; cost: "Engagement usually drops when the feed reads as ads."; action: "Space the next brand post after two organic ones." No cost line when reach is not down. |
| `FOLLOWER_SPIKE_FLAT_REACH` | WARN | yes | NO_TOKEN, TOO_FEW_POLLS, RATE_LIMITED, TOO_YOUNG | followers rose 20 percent or more over any 7-day window in the last 30 days, and median `avg_reach_per_post` (from `CreatorMetric`) in the 7 days after the rise is within ±10 percent of the 7 days before, and no single post in the latest-per-media list has reach above 3× the median | "Followers up {pct}, reach flat"; detail: "Brands' quality score reads this as low-quality growth."; cost: "Your rate estimate falls with the quality score."; action: "If this was a paid campaign or a viral post, ignore this; otherwise avoid growth services." Never says "bought." |
| `REMOVED_DURING_KEEP_UP` | CRITICAL | no | **[needs Phase C]** `deliverables.removed_detected_at` | set, and `keep_up_until >= today` | "Your {brand} post looks removed or archived"; cost: "The contract asks for it to stay up until {date}."; action: "Restore it or tell the brand." Without Phase C: DATA_GAP. |
| `GST_THRESHOLD_APPROACHING` | INFO | yes | none | confirmed payouts this FY ≥ 80 percent of `influora.tax.gst-registration-threshold-inr` (default `2000000`), `profile.gstin` blank, `taxRegistrationStatus != GST_REGISTERED` | "You are near the GST registration threshold"; detail with the FY total rendered; action: "Talk to your CA. This is not tax advice." `EXEMPT` status suppresses it. |
| `REPLY_SPEED` | POSITIVE when median < 24h, INFO when > 48h, else OK | yes | ≥ 3 brand→creator pairs in 30 days, else `DATA_GAP:TOO_FEW_REPLIES` | **`ReplySpeed.medianHours` (3.3) returns a median below 24h (POSITIVE) or above 48h (INFO); between the two the rule returns OK and clears any open flag** | POSITIVE: "You reply to brands in {hours} on average. Creators under 24 hours close more deals."; INFO: "Brands wait {hours} for your replies"; action: "Turn on routine replies so Meera answers the simple ones." **[needs Phase B]** for the action link; without it the action is "Check your deals inbox daily." |

<!-- PRIYA: three gaps in 5.2 resolved rather than left to the builder.

     1. REPLY_SPEED's "Fires when" cell was EMPTY. Filled in the table above, and TOO_FEW_REPLIES
        added to 5.1's precondition table where it was missing.

     2. SEASON MODE was ambiguous when a creator has BOTH weeklySponsoredLimit and season mode: the
        row gives a computed threshold AND a flat 0.45 with no precedence. Ruling: the threshold is
        max(computed, 0.45). Season mode only ever RELAXES the rule; it must never tighten it for a
        creator who set a generous limit.
        Worked example so nobody re-derives it: weeklySponsoredLimit = 2, 12 posts in 30 days ->
        threshold = 2 x 4 / 12 = 0.67, and the rule fires above 8 labelled posts. That is looser than
        the 0.30 default and it is deliberate — the rule is "you exceeded what you told us you
        wanted". But it means a creator who sets a high limit silences the rule. Say that in the copy
        or the flag reads as broken. Division by zero is covered by the NO_CAPTIONS precondition.

     3. GST_THRESHOLD_APPROACHING must NULL-GUARD taxRegistrationStatus. The column is nullable
        (CreatorProfile L161-163) and is genuinely NULL for every creator predating the tax-identity
        feature — which is most of them. `status != GST_REGISTERED` is the right predicate and NULL
        correctly satisfies it, but write it null-safe rather than relying on that. EXEMPT suppresses,
        as the row says. The threshold config key influora.tax.gst-registration-threshold-inr does
        not exist anywhere today; a bare @Value with the :2000000 default is fine and needs no yaml
        entry (ConfigurationPropertiesRegistrationTest only guards @ConfigurationProperties beans,
        not @Value keys). Also confirmed: CreatorTaxRegistrationStatus really is
        {UNREGISTERED, GST_REGISTERED, EXEMPT}, gstin is length 15, pan is length 10.

     One more, on FOLLOWER_SPIKE_FLAT_REACH: avg_reach_per_post DOES exist on CreatorMetric
     (L94-95, a nullable Long). The rule as written is fine. The 60-day TOO_YOUNG guard is right for
     the reason 12 gives. -->

Flag lifecycle: a rule that fires upserts the open flag (`uk_chf_open`), updating `last_seen_at`, detail, data; a rule that returns OK clears an open flag (`cleared_at`, `open_marker = NULL`); a dismissed flag stays dismissed until the rule stops firing and fires again later (a new row). Legal flags (`UNLABELLED_LINKED_DELIVERABLE`, `REMOVED_DURING_KEEP_UP`) are `dismissible = false`.

### 5.3 `AccountHealthJob`

`@Scheduled(cron = "${influora.creator-agent.health-cron:0 0 5 * * *}", zone = "UTC")` (after `ScoreCalculationJob` at 04:00), `@SchedulerLock(name = "AccountHealthJob", lockAtMostFor = "PT45M", lockAtLeastFor = "PT1M")`, gated by `influora.creator-agent.health.enabled:false`, batch `influora.creator-agent.health.batch-limit:2000`. Candidates: creator profiles with a `CreatorAgentPreferences` row whose `consentAcceptedAt` is not null (consent covers the rules; find them through `CreatorAgentPreferencesService.findConsentedProfileIds(Pageable)`, a new service method so the job never imports the repository, per `InfoBarrierTest`'s scan roots). Per creator try/catch; counts to `AuditLogService.recordSystemEvent` **[needs Phase C]**. Purges `creator_health_runs` older than 90 days.

<!-- PRIYA: the attribution is CORRECT — Phase C section 3.11 defines
     recordSystemEvent(String workspaceId, String eventType, String outcome, Map<String,Object>
     detail), and Phase C's own correction 23 explains why Phase C rather than Phase E owns it. What
     was wrong is section 1's dependency column, which listed D2 as HEAD-only. The method does NOT
     exist at HEAD — AuditLogService has recordToolCall (L44), recordAuthRejection (L71),
     recordAdminAction (L95) and recordMoneyEvent (L110), and nothing else. Marked here and in the
     section 1 table. Building D2 on HEAD alone means dropping this line, not inventing the method.
     Its outcome constants are OUTCOME_ALLOWED / OUTCOME_REJECTED / OUTCOME_FAILED — there is no
     OUTCOME_OK. -->
<!-- PRIYA: the InfoBarrier reasoning here is right, and section 9's parenthetical is right too —
     Phase B section 3.6 explicitly requires widening InfoBarrierTest's scan roots from
     service/meera/** + web/** to service/** + job/**, and calls it an ~8-line restructure of
     L68-76, not a one-line edit. Until that widening lands, this job's compliance is enforced by
     review alone: at HEAD, job/** is outside the scanned tree entirely. -->

### 5.4 Routes

`CreatorHealthController` `@RequestMapping("/creator/health")`, creator principal, feature flag (copy the private `requireFeatureEnabled()` from `CreatorMeeraController` L104; do not call it), consent:

| Route | Response |
|---|---|
| `GET /creator/health/flags` | `List<HealthFlagView{flag_id, code, severity, dismissible, title, detail, cost_line, action_line, action_url, entity_type, entity_id, first_seen_at, last_seen_at, data}>` |
| `POST /creator/health/flags/{id}/dismiss` | 204, or 409 `FLAG_NOT_DISMISSIBLE` |
| `POST /creator/health/flags/{id}/confirm-label` | for `UNLABELLED_LINKED_DELIVERABLE`: body `{where: COMMENT | STICKER | CAPTION_EDITED}`; records the confirmation in `data_json`, clears the flag, writes an audit row; the next run re-evaluates the caption (an edited caption is re-synced by the nightly sync only if it is still in the last 25; otherwise the confirmation stands) |
| `GET /creator/health/last-run` | `{evaluated_at, outcomes, data_gaps}` for the creator's most recent run, so the UI can show "checked 3 hours ago" and which rules had gaps |

Meera tool `get_account_health` **[needs Phase B]**: tier R, no input, returns the open flags and the last run; add to `CreatorToolName`, both scopes, the creator schemas, and the frontend `CREATOR_TOOL_NAMES`; card `HealthFlagsCard`.

Persona addition (bump `PROMPT_VERSION` to `meera-2026.10.05.1`): "Account health: call get_account_health before commenting on labels, growth, or tax. Read the flag as written; the numbers come from the flag, never from memory. A flag marked not dismissible is mentioned first. Never say a creator bought followers; say what the quality score reads."

---

## 6. D3: the weekly note

### 6.1 Payload (deterministic, no model)

`WeeklyNoteBuilder.build(String creatorProfileId, LocalDate weekStart)` → `DigestPayload` (snake_case JSON stored in `payload_json`):

```json
{"week_start": "2026-10-05", "first_line": "4,500 releasing Thursday from Glow Cosmetics",
 "money": {"kind": "RELEASING|PAID|AWAITING_FUNDING|NOTHING_DUE", "amount": "4,500", "brand": "Glow Cosmetics", "when": "Thursday", "deal_id": "01J..."},
 "attention": {"saved_by_brands_7d": 2, "new_invites_7d": 1, "line": "2 brands saved your profile this week"},
 "one_post": {"headline": "...", "content_idea": "...", "suggestion_id": "01J...", "source": "AI|FALLBACK|NONE"},
 "one_fix": {"flag_id": "...", "title": "...", "action_line": "..."},
 "rate": {"line": "Your estimated rate moved from 1,800 to 2,100 in 30 days", "from": "1,800", "to": "2,100", "direction": "UP|DOWN|FLAT|UNKNOWN", "reason": "quality score up, 3 deals closed"},
 "more_url": "https://influora.in/creator/copilot#week"}
```

Sources, in order, each optional with an honest fallback:

1. **Money first line** **[needs Phase C]**: `CreatorMoneyService.listDeals(creatorUserId, 20)`; pick, in priority, a milestone `RELEASED` with payout in flight (kind RELEASING, "when" = "in 1 to 3 working days"), else `PAID` in the last 7 days, else `AWAITING_FUNDING` ("waiting on {brand} to secure {amount}"), else `NOTHING_DUE`. Without Phase C: `WalletService.getSummaryForUser(userId)` (L209) → `escrowLocked > 0` → "{amount} secured across your deals"; else "No money moving this week".
2. **Attention**: `countByCreatorProfileIdAndSavedTrueAndUpdatedAtAfter(profileId, weekStart - 7d)` and invites from `collaborationRepository.findByCreatorId(userId)` with status `INVITED` and `createdAt` in the window.
3. **One post to make**: `CreatorNudgeService.getSuggestion(profileId)` (L81-82). It is idempotent per UTC day and writes the daily row, so calling it from the Monday job consumes that day's suggestion; that is intended (the note and the co-pilot page show the same idea). `pending_tagging` or `no_suggestion_today` → `source = "NONE"` and the line "Nothing trending in your niche this week; post what you would anyway." **This call spends — see the ruling below — and is bounded by `influora.creator-agent.weekly-note.suggestion-limit:500` per run; creators past the ceiling get `source = "NONE"`.**

<!-- PRIYA: this is the biggest thing the spec did not state, and it contradicts section 12.

     getSuggestion is NOT a read. I opened it (CreatorNudgeService L82-172). For a creator with no
     row for the current UTC day AND a matching trend at or above scoreThreshold, it calls
     CreatorSuggestionAiClient.requestSuggestion — a real Haiku turn, billed — and writes the
     nudge-log row. The client never throws and falls back to a template on failure, but a SUCCESS
     costs money. Section 12's "It costs nothing" is false as written and is corrected there.

     Arithmetic, so Rohan is not surprised: a suggestion is roughly 1.5k in / 300 out on Haiku 4.5,
     ~$0.003 a call. At the batch-limit of 5,000 that is ~$15 a week, ~Rs 1,250 — not catastrophic,
     but it is a spend spike at 04:00 UTC every Monday that nothing in the original spec named, on
     creators who never opened the co-pilot page. The per-creator spend gate keyed on
     creator_profile_id degrades to a template rather than erroring, so the failure mode is quiet.
     Hence the 500-per-run ceiling for the first weeks; raise it once a real Monday is observed.

     What the spec got RIGHT here, and I checked all of it:
       - idempotent per UTC day: yes, the same-day short-circuit at L102 returns the identical row.
       - the job at 09:30 IST is 04:00 UTC, the SAME UTC day, so a creator opening the co-pilot page
         later that Monday sees exactly the row the note showed. The claim holds.
       - requires themeTagsJson: yes, blank -> pending_tagging (L109). Handled.
     What it missed besides the spend: getSuggestion THROWS
     ApiException(CREATOR_PROFILE_NOT_FOUND) when the profile is gone (L86-99). The per-creator
     try/catch in 6.2 is therefore mandatory, not defensive. And it is @Transactional, so call it
     outside the digest's own write transaction. -->
<!-- PRIYA: this is the ONLY model call in Phase D. Everything else — the label check, every rule,
     every line of the note — is deterministic Java, which is what keeps rule 29 true. Do not let a
     later "just summarise the week with Meera" change that without reopening the R1 ruling. -->
4. **One fix**: the highest-severity open flag from 5.1, CRITICAL first, then WARN, then INFO; POSITIVE flags go into `rate.reason` instead. **Rank in Java via `HealthSeverity.rank()` — see the correction in 2.2; a derived `OrderBySeverityDesc` sorts this list backwards.**
5. **Rate**: `estimated_rate_min` from the latest `CreatorScore` versus the one closest to 30 days ago (3.1 finder); rendered with `NumberFormat` in the creator locale; `UNKNOWN` when either is null; `reason` composed from: quality score delta sign, completed deals in 30 days (`countByCreatorIdAndStatus(userId, COMPLETED)` delta requires history; use Phase B's `deal_offer_history` when present, else omit), and the POSITIVE reply-speed flag. This is the panel's "why your rate should go up" line.

All strings rendered in Java in the creator's language: English and Hindi templates in a `WeeklyNoteCopy` class keyed by `creator_language.startsWith("hi")`, the same split `MeeraCopilotChat.tsx` uses. No model call anywhere in the note; that keeps it inside the caption ruling, costs nothing, and makes every line testable.

### 6.2 `WeeklyDigestJob`

`@Scheduled(cron = "${influora.creator-agent.weekly-note-cron:0 30 9 * * MON}", zone = "Asia/Kolkata")` (the `CreatorConnectNudgeJob` precedent: a human reads it, so send when it lands), `@SchedulerLock(name = "WeeklyDigestJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")`, gated by `influora.creator-agent.weekly-note.enabled:false`, batch `influora.creator-agent.weekly-note.batch-limit:5000`, AI ceiling `influora.creator-agent.weekly-note.suggestion-limit:500`.

<!-- PRIYA: the CreatorConnectNudgeJob precedent is CORRECT — that job really does run
     `${influora.creator-connect-nudge.cron:0 30 9 * * *}` in zone Asia/Kolkata (L86), with
     @SchedulerLock PT20M/PT1M, and the "a human reads it, send when it lands" reasoning is its
     actual rationale. Monday 09:30 IST is the right call and the lock names (CaptionLabelJob,
     AccountHealthJob, WeeklyDigestJob) are all unique against the existing set. Config flags all
     default false. Good.

     Two things this step must add:
     (a) The suggestion ceiling above — see the ruling in 6.1 item 3. Without it this job makes up
         to 5,000 billed AI calls in one burst every Monday.
     (b) SCHEDULER CAPACITY. TaskSchedulerConfig.POOL_SIZE is 10 (L36) against 22 @Scheduled methods
         today — I counted them, the fact sheet's number is right. Phase D adds 3; Phases B, C and E
         add more. This job declares lockAtMostFor = PT2H and, as originally written, would occupy
         one of those ten threads for the length of thousands of outbound HTTP calls while the other
         nine carry every other job on the platform. Raise POOL_SIZE to 16 in this phase. That is a
         condition on the build, not a suggestion — the ceiling in (a) is otherwise carrying this
         risk alone. --> Candidates: consented creators with `weekly_note_enabled` (through the service). Per creator: skip if a row exists for `weekStart` (`uk_cwd_creator_week`); build; save; **publish** `CreatorWeeklyNoteEvent(String userId, String workspaceId /* null */, String entityId /* digest id */, String firstLine, String noteUrl)` with `eventType() → "creator.weekly_note"` through `ApplicationEventPublisher` (published, not handed to `NotificationService`, because `NotificationEventContractTest` requires a real publisher and a listener). Record `channels_sent` from what the router queued **[needs Phase C]**; without Phase C, "in_app,email".

Event wiring: record in `com.influora.service.notification.event`, `permits` entry (the last entry `CreatorNotConnectedEvent` at L49 has no trailing comma; add one), listener handler — **annotated plain `@EventListener`, NOT `@Async @TransactionalEventListener`, see the ruling below** — calling `notificationService.notify(event, /* title */ event.firstLine(), /* body */ "Your week on Influora", /* link */ event.noteUrl(), emailOf(event.userId()), "creator.weekly_note", Map.of("first_line", ..., "note_url", ...))`, template `creator.weekly_note` added to `EmailTemplateRegistry.SPECS`

<!-- PRIYA: THIS IS THE ONE THAT WOULD HAVE SHIPPED BROKEN, and no gate would have caught it.

     The listener MUST be a plain @EventListener. Almost every handler in NotificationListener is
     `@Async @TransactionalEventListener(phase = AFTER_COMMIT)` — see the MonthlyStatementEvent
     handler at L605-619, which is the nearest structural neighbour and the one a builder will copy.
     WeeklyDigestJob is NOT @Transactional. An AFTER_COMMIT listener with no transaction bound to
     the thread is SILENTLY SKIPPED by Spring: no error, no log, no email, no in-app row. The digest
     table fills up every Monday and not one creator is notified.

     NotificationEventContractTest does not catch this. It asserts that a handler method annotated
     @EventListener OR @TransactionalEventListener exists for the type, and that something in
     src/main/java contains the literal `new CreatorWeeklyNoteEvent(`. Both hold. Green test, dead
     feature — exactly the class of bug that test was written to catch, arriving through the one
     door it does not cover.

     The precedent is already in this codebase and already documents the resolution:
     CreatorConnectNudgeJob L118-121 says "The listener runs synchronously (plain @EventListener, no
     @Async), so a failure still lands in the catch below and the count stays honest", and its
     handler sits at NotificationListener L841 as a bare @EventListener. Copy THAT, not the
     MonthlyStatement one.

     This is also what makes "record channels_sent from what the router queued" possible at all. An
     @Async listener has not run by the time the job reads the row back, so channels_sent would be
     empty or racy. Synchronous is required by the design, not just safer.

     Argument order fixed against the real signature (NotificationService L69-76):
     notify(event, title, body, link, toEmail, templateKey, templateData). The spec's positional
     call was right but unlabelled, and title=first_line / body=heading reads backwards at a glance;
     the inline comments now pin it. Note the in-app card will show the money line as its TITLE and
     "Your week on Influora" as its body — which is what we want, and is worth stating so nobody
     "fixes" it later.

     Routing verified: "creator.weekly_note" is in NEITHER EMAIL_ONLY_EVENTS nor IN_APP_ONLY_EVENTS
     (NotificationService L41-46), so notify() takes the both-channels branch — in-app row plus
     queued email. That is what 6.2 wants. Do not add it to either set.

     emailOf(String) is a PRIVATE helper on NotificationListener (L145-149, via UserRepository), so
     calling it from a handler in that same class is legal. Correct as written. -->
<!-- PRIYA: NotificationEvent is a SEALED interface and the permits list ends `CreatorNotConnectedEvent {`
     on L49 — the trailing-comma warning is correct and worth having kept. The interface requires
     four methods: eventType(), userId(), workspaceId(), entityId(). The record's declared component
     order in 6.2 supplies all four plus firstLine/noteUrl. Good. --> inside that package-private file with the 5-arg `Spec(subject, heading, bodyTemplate, ctaLabel, ctaUrlVar)`: subject "{first_line}", heading "Your week on Influora", CTA "See the rest", var `note_url`. Preference: honour `email_preferences` for `creator.weekly_note` (opt-out through the existing `POST /notifications/preferences`), and `weekly_note_enabled` for all channels.

<!-- PRIYA: the opt-out claim is CORRECT and now cited, because it is the kind of claim that is
     usually wrong. queueEmailIfNotUnsubscribed resolves an EmailPreference row per event type and
     returns .orElse(false) on isUnsubscribed (NotificationService L142-178) — i.e. NO ROW MEANS
     SUBSCRIBED. So a brand-new eventType works through the existing endpoint with no migration and
     no seed row. Nothing to build here beyond passing the right templateKey.

     EmailTemplateRegistry.Spec's 5-arg form is also real: `Spec(String subject, String heading,
     String bodyTemplate, String ctaLabel, String ctaUrlVar)` at L50, alongside a 3-arg form at L46.
     Both Spec and SPECS are private to that file, so the new entry goes in the file's static
     initialiser next to its siblings — you cannot register it from outside. Correct as written. --> WhatsApp **[needs Phase C]**: template `influora_weekly_note` ("Hi {{1}}, {{2}}. One thing to post this week is ready. Open: {{3}}") added to the Phase C template table and to `ChannelRouter`'s event map under category MEERA.

### 6.3 Routes

`CreatorWeeklyNoteController` `@RequestMapping("/creator/weekly-note")`, creator principal, feature flag, consent:

| Route | Behaviour |
|---|---|
| `GET /creator/weekly-note/latest` | the latest digest payload. **Read-only, `@Transactional(readOnly = true)`, no side effect.** |
| `POST /creator/weekly-note/{id}/opened` | sets `opened_at` if null; idempotent, 204 |
| `POST /creator/weekly-note/{id}/tapped` | sets `tapped_at` |

<!-- PRIYA RULING on decision 2 — the GET-with-read-receipt exception is REJECTED. Replaced with a
     POST above; section 13 decision 2 is closed.

     Rule 24 is "GET never writes. Allocation is POST." That rule is mine and I am not carving an
     exception into it for a timestamp — a rule with one documented exception is a rule with two
     next quarter, and the javadoc that was going to hold the line would have been read by nobody.

     But the practical argument is stronger than the principle, and it is the one that decides it.
     GET /latest will be re-fetched by react-query on window focus, by route prefetch, by any mobile
     background refresh, and by the react-query dedupe patterns this frontend already uses
     everywhere (useDailySuggestion runs two instances deduped by the cache). opened_at would then
     record MACHINERY, not a human. And opened_at is an input to section 7 — the section whose
     entire argument is that we measure outcomes honestly rather than counting opens. Instrumenting
     it with a number we know is inflated would poison the one honest thing about D4.

     It also forces a write transaction on every read of a page we expect to be polled, and makes
     the endpoint non-cacheable for no gain.

     Cost of the fix: one line. The frontend already has a POST call site for `tapped` (8.2 calls it
     on expand), so WeekCard fires `opened` on mount the same way. Nothing else changes. -->
<!-- PRIYA: 6.4 says opened_at and tapped_at feed D4 — still true, just written by a POST now.
     Update 8.1's `creatorWeeklyNote` namespace to (`latest`, `opened`, `tapped`, `audio`) and 8.4's
     WeekCard test to assert `opened` fires once on mount, not on every re-render. -->
| `POST /creator/weekly-note/{id}/audio` | builds the spoken text: `first_line + ". " + one_post.headline + ". " + one_fix.title` truncated to 480 characters at a sentence boundary; `voiceAiClient.speak(profile.getUserId(), text, prefs.creatorLanguage())` (L192; creator user id in the workspaceId slot); on `ok`, `r2StorageService.putBytes("weekly-notes/" + digestId + ".audio", bytes, contentType)` and `applyAudio`; returns `{url: r2StorageService.presignGet(key).uploadUrl(), content_type}`; on fallback returns `{fallback: true}`.

<!-- PRIYA: `presignGet(key)` returned straight into a String field does not compile. It returns
     PresignResult(String uploadUrl, String key, String bucket, Instant expiresAt, long maxSize)
     (R2StorageService L88-89, L143-163) — and note the URL component is named `uploadUrl` even on
     the GET path, which is exactly the kind of misnaming that makes this worth spelling out. Call
     .uploadUrl(). Expiry comes from props.getPresignExpirySeconds(), the same one proof uploads
     use, so 12's security answer is accurate.

     The rest of this route checks out: putBytes(objectKey, bytes, contentType) is void (L96) and is
     used correctly; speak(String workspaceId, String text, String lang) is at MeeraVoiceAiClient
     L192 with exactly that signature and never throws (falls back); the 480-character cap sits
     safely under the Python TTS_MAX_CHARS of 500. Passing the creator user id in the workspaceId
     slot matches what CreatorMeeraController.speak already does — ugly, pre-existing, in bounds. -->
<!-- PRIYA: "One audio per digest; a second call returns the stored one" needs the guard written, not
     implied: check audio_r2_key BEFORE calling speak, or a double-tap bills Sarvam twice. --> One audio per digest; a second call returns the stored one. Sarvam TTS is billed per character inside the AI service; the cost is about ₹1.50 per note. |

### 6.4 Read receipt for measurement

`opened_at` and `tapped_at` feed D4. The brand-side "Weekly Digest" switch in `brand-settings.tsx` L900-907 stays disabled; its test stays. Creator opt-out is the `weekly_note_enabled` switch in Meera settings.

---

## 7. D4: measurement and the baseline fix

### 7.1 `GET /admin/creator-agent/growth-metrics`

On `AdminCreatorAgentController` (package `com.influora.web`), bare DTO like its siblings (Priya's Phase E note: admin controllers return bare DTOs):

<!-- PRIYA: correct, and now cited — that controller's own javadoc at L23-24 says it "Returns a raw
     DTO (no ApiResponse envelope), matching AdminCreatorController's deliberate deviation for the
     admin console's client contract". Admin auth is structural via SecurityConfig's /admin/**
     hasRole("ADMIN") matcher, so no per-method check. Take @AuthenticationPrincipal AuthPrincipal
     like both existing handlers do even though it is unused.
     One thing the spec's frontend half misses: the CLIENT wraps it anyway. apiRequest in
     src/admin/services/api-contracts.ts (L85-88) returns ApiResponse<T> regardless of what the
     server sends. So the TS return type is Promise<ApiResponse<GrowthMetrics>>, NOT
     Promise<GrowthMetrics>. See the correction in 8.3. -->

```java
record GrowthMetricsResponse(
  @JsonProperty("window_days") int windowDays,
  @JsonProperty("notes_sent") long notesSent, @JsonProperty("notes_opened") long notesOpened, @JsonProperty("notes_tapped") long notesTapped,
  @JsonProperty("flags_raised_by_code") Map<String, Long> flagsRaisedByCode, @JsonProperty("flags_cleared_by_code") Map<String, Long> flagsClearedByCode, @JsonProperty("flags_dismissed_by_code") Map<String, Long> flagsDismissedByCode,
  @JsonProperty("data_gap_share_by_reason") Map<String, Double> dataGapShareByReason,
  @JsonProperty("median_rate_min_delta_pct_recipients") Double medianRateMinDeltaPctRecipients,
  @JsonProperty("median_rate_min_delta_pct_non_recipients") Double medianRateMinDeltaPctNonRecipients,
  @JsonProperty("saved_by_brands_delta_recipients") Double savedByBrandsDeltaRecipients,
  @JsonProperty("saved_by_brands_delta_non_recipients") Double savedByBrandsDeltaNonRecipients,
  @JsonProperty("label_compliance") SampleLabelCompliance labelCompliance,
  @JsonProperty("computed_at") Instant computedAt)
```

Recipients are creators with a digest row in the window; non-recipients are consented creators without one (opted out or job disabled); the rate delta uses `CreatorScore.estimatedRateMin` at window start and end. This is the plan's measurement: rate movement and shortlist rate, not opens.

### 7.2 Replace the hardcoded sample

`CreatorAgentBaselineService` (**`com.influora.service.admin`**) holds `HAND_SAMPLE_LABEL_COMPLIANCE = new SampleLabelCompliance(100, 73, 0.73)` at **L43** (javadoc L37-42), returned from `getBaselines()` at L70.

<!-- PRIYA: value and record shape both verified exact — AdminCreatorAgentDtos L23-26 declares
     SampleLabelCompliance(@JsonProperty("sample_size") int sampleSize,
     @JsonProperty("labelled_count") int labelledCount,
     @JsonProperty("compliance_rate") double complianceRate). "Keep the record shape" is achievable
     as written; nothing downstream needs to change. Note complianceRate is a primitive double, so
     an empty window must yield 0.0, never a null.
     Corrections: the constant is at L43 (L42 is the last javadoc line), it is named
     HAND_SAMPLE_LABEL_COMPLIANCE rather than being inline, and the class lives in service/admin/,
     not service/. Its own javadoc at L38-41 names Phase D as the replacement owner — so this step
     is closing a loop the code already asked for. -->
<!-- PRIYA: the two new count finders are derivable as named, but check the property spelling:
     the entity field is `labelStatus`, so it is countByLabelCheckedAtAfterAndLabelStatusIn(...),
     which is what 7.2 has. Correct. --> Replace with real counts from `creator_captions` where `label_checked_at` is not null: `labelled = LABELLED_UPFRONT + LABELLED_LATE`, `sample_size = all checked`, over the last 30 days, through a new repository count pair `countByLabelCheckedAtAfter(Instant)` and `countByLabelCheckedAtAfterAndLabelStatusIn(Instant, Collection<CaptionLabelStatus>)`. Keep the record shape (`SampleLabelCompliance(sample_size, labelled_count, compliance_rate)`). The admin type comment in `src/admin/types/admin.types.ts` L1100-1104 that names the hand sample is updated.

---

## 8. Frontend

### 8.1 `src/lib/api.ts`

Types: `HealthFlagView`, `HealthLastRun`, `WeeklyNotePayload` (mirroring 6.1), `WeeklyNoteAudio`, `GrowthMetrics` (admin). Namespaces `creatorHealth` (`listFlags`, `dismiss`, `confirmLabel`, `lastRun`), `creatorWeeklyNote` (`latest`, `opened`, `tapped`, `audio`), all `{ role: 'creator' }`, mocks for each, registered in the `api` object.

<!-- PRIYA: `opened` added — the GET read receipt is now a POST (see the ruling in 6.3).
     Conventions verified so the builder copies the right one: each namespace is a module-level
     `export const foo = { ... }` whose every method is a ternary
     `isLive() ? http.request<T>(method, path, { role: 'creator', ... }) : mockOr<T>(MOCK_FOO)`
     (isLive at L909, mockOr at L904-907), returning BARE DTOs with no envelope — the opposite of
     the admin client. `{ role: 'creator' }` is the third argument to http.request alongside
     body/query. Registration is one shorthand line in the `export const api = { ... }` literal at
     L6549-6595. creatorCopilot (L6219-6241) is the closest model to copy.
     CreatorAgentPreferencesUpdate keeping its Omit is CORRECT (L6295-6298) — verified. --> `CreatorAgentPreferences` gains `weekly_note_enabled: boolean` and `season_mode_until: string | null`; `CreatorAgentPreferencesUpdate` keeps its `Omit`.

### 8.2 Creator surfaces

- `src/components/creator/copilot/WeekCard.tsx`: rendered between the "Talk to Meera" card and `<DailySuggestionSection />` in `creator-copilot.tsx` (the slot the fact sheet identified). Shows `first_line` large with `tabular-nums`, then `attention.line`, then a collapsed "See the rest" (progressive disclosure: one post, one fix, rate line) which calls `tapped` on expand. "Listen" button calls `audio` and plays the returned URL; hidden when `fallback`. Empty state when there is no note yet: "Your first weekly note arrives Monday."
- `src/components/creator/health/HealthFlagsSection.tsx`: below `WeekCard`; one `HealthFlagCard` per open flag with the severity stripe (reuse the Phase B `DealRiskCard` stripe styles; CRITICAL uses the destructive surface with `text-destructive-foreground`), title, detail, cost, action button linking to `action_url`; "Not now" for dismissible flags; `UNLABELLED_LINKED_DELIVERABLE` shows the three "where is the label" choices. A footer line "Checked {relative time}; {n} checks skipped for missing data" from `lastRun`, with the reasons behind a tap.
- `src/pages/creator-dashboard.tsx`: a full-width card **between L557 (the `</FadeUp>` closing the three-tile grid) and L559** showing `first_line` and a link to the co-pilot page.
- `src/components/creator/MeeraSettingsSection.tsx`: a "Weekly note" **`Checkbox`** and a "Season mode until" date input (explained as "relaxes the sponsored-ratio check for festival weeks").

<!-- PRIYA: two wrong claims in these two lines.

     1. L553 is NOT the end of the grid. The three-tile `FadeUp` grid OPENS at L476 and CLOSES at
        L557; L553 is the `)}` terminating the third tile's `loading ?` ternary, INSIDE
        <CardContent>. Inserting the week card at L553 nests it inside the third tile. The real
        full-width slot is between L557 and L559 (the contracts card's comment), or equivalently
        before the `<FadeUp y={0} delay={0.02}>` at L562. The claim that the contracts card is at
        L563 is correct.

     2. MeeraSettingsSection has NO SWITCHES. The file (717 lines) does not import Switch at all.
        Its only boolean control is a shadcn Checkbox for `represented` at L583-592; every other
        field is an Input, Select, RadioGroup, Textarea or Badge list. Use a Checkbox and match the
        file, or you are introducing a control vocabulary of one.
        Also: the component takes NO PROPS (zero-arg, L143), the path is
        src/components/creator/MeeraSettingsSection.tsx, and the save path is
        api.creatorAgentPrefs.updatePreferences(payload) at L252 building from a `draft` typed as
        CreatorAgentPreferencesUpdate (L125) via toDraft() at L127-133. Both new fields ride the
        `...draft` spread automatically once they are on the TS interface — which is why the Java
        UpdatePreferencesRequest must gain them in the same commit (see 2.5).
        An existing test already covers this file: MeeraSettingsSection.roundtrip.test.tsx. The new
        MeeraSettingsSection.weeklynote.test.tsx is a second file, not a replacement; keep both. -->
<!-- PRIYA: creator-copilot.tsx render order verified exactly as 8.2 claims, and the slot is real.
     One clarification: the "Talk to Meera" card is one branch of a TERNARY (L129-182, the other
     branch being the featureDisabled card), so <WeekCard /> goes AFTER L182, not inside either
     branch. ConsentScreen (L184-189) is a Radix Dialog — it renders no inline layout and does not
     early-return, so it is not an obstacle between the two. -->
- Meera card **[needs Phase B]**: `HealthFlagsCard` in `CreatorToolResultRenderer`.
- `meera_nudge` type: leave as is; not part of this phase.

### 8.3 Admin

`src/admin/pages/CreatorAgentBaselinesPage.tsx` gains a "Growth" panel reading a new `useCreatorAgentGrowthMetrics()` hook (sibling to `src/admin/hooks/useCreatorAgentBaselines.ts`), which wraps a new `creatorAgentApi.getGrowthMetrics()` added inside the `creatorAgentApi` object in `api-contracts.ts` (**L1039-1042**):

<!-- PRIYA: location correct — creatorAgentApi really is at api-contracts.ts L1039-1042 with a
     single getBaselines method, and admin.types.ts L1099-1104 really does carry the comment naming
     CreatorAgentBaselineService's hand sample as the thing to replace. Both claims verified.
     Two adjustments:
     (a) The page does NOT call creatorAgentApi directly. It goes through useCreatorAgentBaselines()
         (src/admin/hooks/useCreatorAgentBaselines.ts), which does the ApiResponse unwrap and
         supplies { data, isLoading, error, refresh }. Reading the api object straight from the page
         would make this the only panel on it that does. Add a sibling hook.
     (b) getGrowthMetrics returns Promise<ApiResponse<GrowthMetrics>>, because apiRequest wraps
         everything (L85-88) even though the server sends a bare DTO. The hook unwraps.
     Also update the admin.types.ts comment at L1099-1103, which currently tells the reader not to
     imply the number is live-measured — after 7.2 it IS live-measured, and the page's own
     disclaimer at CreatorAgentBaselinesPage L150-152 ("Fixed reference constant, not yet a
     live-measured rate") must go with it. 7.2 mentions the type comment but not the visible
     disclaimer; both. --> notes sent, opened, tapped; flags raised, cleared, dismissed by code; recipients versus non-recipients rate delta and saved-by-brands delta; label compliance from real data with the sample size.

### 8.4 Tests

`WeekCard.test.tsx` (first line, expand calls tapped, listen hidden on fallback, empty state), `HealthFlagsSection.test.tsx` (legal flag has no dismiss, confirm-label choices, data-gap footer), `MeeraSettingsSection.weeklynote.test.tsx` (PUT carries the two new fields), `growth-panel.test.tsx`. `brand-settings.disclaimer.test.tsx` unchanged and still green.

---

## 9. Backend tests

| Test | Covers |
|---|---|
| `DisclosureLabelServiceTest` | every vocabulary kind and script, 125-char boundary, false positives, blank, vocabulary not loaded |
| `CaptionLabelJobTest` | labels pending rows, marks Stories NO_CAPTION, stops at 5,000, skips when not ready |
| `ReplySpeedTest` | pairing, median, fewer than 3 pairs |
| `AccountHealthServiceTest` | each precondition produces the right DATA_GAP; each rule one firing and one non-firing case; flag upsert, clear, dismiss, legal not dismissible; confirm-label clears; season mode threshold |
| `AccountHealthJobTest` | consented-only candidates, batch limit, per-creator isolation, run purge |
| `ScoreCalculationJobHistoryTest` | the 30-day series reaches `analyze`; signal 2 fires on a synthetic spike |
| `WeeklyNoteBuilderTest` | money priority order with and without Phase C, attention counts, suggestion NONE fallback, one-fix selection, rate line UP/DOWN/FLAT/UNKNOWN, Hindi and English templates, no unrendered number |
| `WeeklyDigestJobTest` | Monday IST, one row per creator per week, publishes the event, opted-out skipped |
| `CreatorWeeklyNoteEventTest` | in `NotificationEventContractTest`'s style: publisher and listener exist, template registered — **plus one case that test cannot express: the handler is a plain `@EventListener`, so publishing from a non-transactional job actually invokes it. Assert on the annotation, or write a slice test that publishes outside a transaction and asserts a `Notification` row exists.** |
| `CreatorWeeklyNoteControllerTest` | **latest is read-only and writes nothing**, `opened` is idempotent, tapped, audio stores to R2 once and returns a presigned URL, fallback shape |
| `CreatorHealthControllerTest` | flags list, dismiss 409 on legal, confirm-label |
| `GrowthMetricsTest` | recipient versus non-recipient split, delta arithmetic, empty windows |
| `CreatorAgentBaselineServiceLabelTest` | real counts replace the sample |
| `MeeraPhaseDBootValidationTest` | four migrations, `ddl-auto=validate`; skipped without Docker |
| `InfoBarrierTest` | no Phase D class imports `CreatorAgentPreferencesRepository` (scan roots per Phase B condition 2 include `service/**` and `job/**`) |

Python: tool schema for `get_account_health` through Phase B's parametrisation; a persona test pinning "Never say a creator bought followers".

---

## 10. Build order (one week)

| Day | Backend | AI | Frontend | Ops |
|---|---|---|---|---|
| 1 | four migrations, entities, finders (3.1), boot test, `ScoreCalculationJob` history fix, resources 2.6 and 2.7 | | api types and mocks | native-speaker check of the vocabulary |
| 2 | `DisclosureLabelService`, `CaptionLabelJob`, `ReplySpeed` | | | |
| 3 | `AccountHealthService`, rules, `AccountHealthJob`, `CreatorHealthController` | `get_account_health` schema, persona bump **[Phase B]** | `HealthFlagsSection`, settings fields | |
| 4 | `WeeklyNoteBuilder`, `WeeklyNoteCopy`, `WeeklyDigestJob`, event, listener, template, controller, audio | | `WeekCard`, dashboard card | WhatsApp template submitted **[Phase C]** |
| 5 | growth metrics route, baseline fix, full `mvn -o test`, schema diff, Kavya, Kabir (label vocabulary injection, audio storage, admin route) | full pytest | admin growth panel, tsc, build, vitest | staging boot with `ddl-auto=validate`; enable `health` on staging; first Monday note observed |

---

## 11. Acceptance: the zero-context tester's ten questions

1. Post a reel with "#ad" at character 130 and one with "प्रायोजित" in the first line: what verdict does each get, where is the vocabulary, and which code decides it? Prove no model ever sees the caption.
2. A creator with no connected Instagram, one connected yesterday, and one rate-limited last night: which rules run, which say `DATA_GAP`, with which reason, and what does the creator see?
3. Followers up 25 percent in a week with flat reach: what fires, what wording, and what does the authenticity score do now that the history reaches the detector?
4. Six sponsored posts out of twelve during Diwali: does the ratio rule fire, and what changes when season mode is on?
5. A creator at ₹17 lakh of confirmed payouts this FY with no GSTIN: what fires, what does it say, and what does it refuse to say?
6. Monday 09:30 IST: trace one creator's weekly note from job to in-app row, email, WhatsApp, and push. What is the first line when money is releasing, when nothing is due, and when Phase C is not deployed?
7. Tap "Listen": where does the audio come from, how long is the text, where is it stored, and what does a second tap do?
8. Dismiss a legal flag, dismiss a warning, confirm a label was in a comment: what happens to each row, and what does the next run do?
9. Show the growth metrics for a window with 40 recipients and 60 non-recipients: how are the rate deltas computed, and why is "opens" not the headline number?
10. Four migrations on stock MySQL 8 with `ddl-auto=validate`; a creator with no captions, no scores, no deals, no consent: does every new page, job, and route handle them with honest empty states?

---

## 12. Expert questions and answers

**Captions and the ruling**
- *Why not use the classifier that already reads captions?* It runs on the brand side with a workspace-bound service token, and the ruling forbids creator captions on the creator scope. A deterministic vocabulary is also faster, free, and explainable to a creator who asks "why did you flag this."
- *Which caption copy?* `creator_captions.caption_text`, which the sync job writes for this purpose. `media_metrics.caption` is restricted to the brand-safety pipeline by its migration note.

<!-- PRIYA: this answer is CORRECT and I checked it rather than taking it. The V26 restriction
     ("internal BrandSafety pipeline ONLY, never surface via any brand-facing DTO, keep out of
     logs") is declared on MediaMetric.caption at L42-48 and constrains THAT column. creator_captions
     is the second, independent copy, written by CreatorCaptionSyncJob for exactly this kind of
     consumer, and CreatorCaptionCache's own javadoc at L22-28 records a deliberate Kabir-signed
     risk acceptance for it. The two are never joined. Reading caption_text in Java for a
     deterministic label check violates neither note. The one live constraint on this table — its
     migration's "any consumer MUST wrap_untrusted before any model call" — is satisfied vacuously,
     because Phase D makes no model call with it. -->
- *What about captions older than the last 25 posts?* The sync keeps 25 per night. A deliverable older than that is a `DATA_GAP`, and the creator is asked to confirm, never accused.
- *Hinglish?* Word entries match transliterations (`#sahyog`, `#vigyapan`) and the English words inside Hinglish sentences. The vocabulary is a resource file ops can extend without a deploy of code, only of the resource.

**Rules**
- *Why never say "bought followers"?* One wrong accusation travels to a 40-creator WhatsApp group. The flag states what the quality score reads and offers the innocent explanations first.
- *Why 60 days before the spike rule?* New connections backfill history unevenly; the rule needs a stable baseline.
- *Why is the GST rule INFO?* It is not tax advice, the threshold differs by state and turnover type, and the creator's CA decides. The rule points; it never concludes.
- *Season mode?* A calendar resource plus a per-creator override, because every creator's feed is 45 percent sponsored in the Diwali fortnight and the rule would otherwise fire for everyone.

**The weekly note**
- *No AI at all?* **Almost, and the difference costs money.** Every line the note itself composes is a Java template with numbers rendered by Java — it cannot hallucinate, it stays inside the caption ruling, and it is testable line by line. But the "one post to make" line calls `CreatorNudgeService.getSuggestion`, which is not a read: on a creator with no row for the current UTC day and a matching trend, it makes a real billed Haiku call. At the 5,000 batch limit that is roughly $15 (~₹1,250) every Monday morning, spent on creators who may never open the note. That is why 6.1 item 3 now carries a `suggestion-limit:500` ceiling and why this answer no longer says "it costs nothing". The AI-produced sentence is the daily suggestion, which already exists and already passes its validators — but it is produced on demand, and the Monday job is a new demand.

<!-- PRIYA: this answer said "It costs nothing", which was false and would have gone into a cost
     review unchallenged. Corrected against CreatorNudgeService L82-172. Everything else in this
     answer is right and worth keeping. -->
- *Why Monday 09:30 IST?* The nudge job precedent: a person reads it, so it lands when they start the week.
- *Why is "money first"?* The panel: creators open a message that says "4,500 releasing Thursday" and ignore one that says "your weekly report is ready."
- *Read aloud?* Sarvam TTS through the existing voice client, capped at 480 characters, stored once in R2. Hindi voice for Hindi-language creators.

**Measurement**
- *Why not opens?* Opens measure the notification, not the outcome. The plan asks for rate movement and brand shortlist rate. Both are computed from rows that exist (`creator_scores.estimated_rate_min`, `saved_creators`), split by whether the creator received notes.

**Security and privacy**
- *Caption vocabulary injection?* The vocabulary is a checked-in resource, not user input; captions are matched, never executed or sent anywhere.
- *Flag text?* Rendered by Java from rule constants and numbers; no caption text is copied into a flag (the matched label is at most 64 characters from the vocabulary itself, never from the caption).
- *Audio?* Stored under a key derived from the digest id, served by presigned URL with the same expiry as proof uploads, owned by the creator.
- *Admin route?* Aggregates only, no creator ids, consistent with the baselines route.

**Deliverables checklist**
- Four migrations boot-validated on staging.
- Vocabulary confirmed by a native speaker for each script.
- One real creator's flags observed on staging with at least one `DATA_GAP` and one fired rule.
- One Monday note observed end to end, including audio.
- Growth metrics rendered with real rows.
- Kabir on the vocabulary, the audio path, and the admin route. Kavya QA. Meera verification. Priya's ten questions positive.

---

## 13. Decisions still open (build with the default, flag to Swapnil)

1. **GST threshold value and state variation.** Default ₹20 lakh; some states ₹10 lakh. Config value; the CA decides the wording.
2. ~~**The GET-with-read-receipt exception** (6.3).~~ **CLOSED 2026-09-05 — Priya rejected it.** `GET /creator/weekly-note/latest` is read-only; `POST /creator/weekly-note/{id}/opened` carries the receipt. Full reasoning in the ruling at 6.3 and in 14.4. Not a decision for Swapnil.
3. **Season calendar ownership.** Ops edits the resource; whether creators can add their own season is deferred.
4. **Tier-2 caption ruling** (plan decision 11) stays open; nothing in Phase D depends on it.

---

## 14. Priya review

Reviewed 2026-09-05 against HEAD `143ca1e` plus the uncommitted working tree, and against the Phase B/C/E specs as corrected by my §13/§15 notes in those files. Every symbol below was opened in the source; line numbers are from the working tree.

### 14.1 Verdict

- **Buildable as written: no.** Six symbol claims are wrong. One (`item.media_type()`) does not compile. One (`presignGet(...)` used as a `String`) does not compile. One (the `OrderBySeverityDesc` finder) compiles and silently returns flags in the reverse of the required order. Two more (the payout FY sum, the listener annotation) build green and are wrong at runtime.
- **Buildable as corrected: yes**, subject to the six conditions in 14.6.

### 14.2 Rule 27/28 scorecard

| | Count |
|---|---|
| Symbol claims that are **wrong** | **6** (14.3, items 1-6) |
| Items **correctly marked `[VERIFY]`** and left unasserted | **1** (`DealMessageRepository`, §3.1) — now resolved, and the guess behind it was right |
| Line references off by 1-3 lines | 5 (14.5) — imprecise, not wrong |

The rules mostly held. Six wrong claims across roughly ninety symbol assertions is a good ratio, and the one `[VERIFY]` was placed on exactly the right file. But three of the six sit in the two places that carry money and legal weight — the FY payout sum, and the ordering that decides which legal flag a creator sees first — which is where rule 27 has to hold hardest. The dominant failure mode is not fabrication. It is **reading a method's name and not its javadoc** (items 2 and 6), and **reading a record's wire name instead of its accessor** (item 1).

`[VERIFY]` resolved: `repository/DealMessageRepository.java` has exactly **six** finders — `findByCollaborationIdOrderByCreatedAtAsc` (L15), `existsByCollaborationIdAndSenderType` (L18), `findFirstByCollaborationIdAndKindOrderByCreatedAtDesc` (L24-25), `findPageBefore` (L27-33), `findFirstByCollaborationIdOrderByCreatedAtDesc` (L35), `findFirstMessageTimestampsBySender` (L52-57). The proposed `findByCollaborationIdInAndCreatedAtAfterOrderByCreatedAtAsc` is derivable (`collaborationId` and `createdAt` both exist on the entity, L56-57 and L81-82) and collides with nothing. `DealSenderType.brand` / `.creator` are lowercase constants, as §3.3 already has them — confirmed by the JPQL at L54-55.

### 14.3 The six wrong symbol claims

1. **`item.media_type()` does not exist** (§2.1). `InstagramMediaResponse.MediaItem` (L12-20) declares `@JsonProperty("media_type") String mediaType` — the wire name is `media_type`, the accessor is **`item.mediaType()`**. The spec read the JSON key and wrote it as a method.
2. **The payout FY sum does not mean "confirmed payouts"** (§3.1). `PayoutRepository` L100-118 documents in its own javadoc that `confirmedAt` is stamped on **every terminal webhook**, including `reversed` / `rejected` / `cancelled` (`PayoutReconciliationService.FAILURE_STATUSES`, L63-64) — money that never reached the creator and was already re-credited to the wallet. `SUM(amount) WHERE confirmedAt BETWEEN ...` therefore over-counts FY income with failed payouts. On a rule that tells a creator they are near a statutory GST threshold, that is not a rounding error.
3. **`creator-dashboard.tsx` L553 is not the end of the grid** (§8.2). The three-tile `FadeUp` grid opens at L476 and closes at **L557**; L553 is the `)}` closing the third tile's `loading ?` ternary, *inside* `<CardContent>`. Inserting there nests the week card inside a tile. (The contracts card at L563 is correct.)
4. **`MeeraSettingsSection` has no switches** (§8.2). `src/components/creator/MeeraSettingsSection.tsx` (717 lines) does not import `Switch` at all; its only boolean control is a shadcn `Checkbox` for `represented` (L583-592), and the component takes **no props**. "Weekly note switch" describes a control pattern the file does not use.
5. **`presignGet` does not return a URL** (§6.3). `R2StorageService.presignGet(String)` (L143) returns `PresignResult(String uploadUrl, String key, String bucket, Instant expiresAt, long maxSize)` (L88-89) — the URL field is named `uploadUrl` even on the GET path. `{url: presignGet(key)}` does not compile.
6. **`OrderBySeverityDesc` sorts backwards** (§2.2). `severity` is `@Enumerated(EnumType.STRING)` in a `VARCHAR(16)`, so a derived `OrderBySeverityDesc` orders the *stored strings* alphabetically: `WARN` > `POSITIVE` > `INFO` > `CRITICAL`. That is the exact reverse of what §6.1 item 4 and §8.2 require ("CRITICAL first"). It compiles, it passes a naive one-flag test, and it puts a non-dismissible legal flag last on a creator's screen.

### 14.4 Corrections applied (each marked `<!-- PRIYA: ... -->` in place)

**Data model**

- §2.1 `item.mediaType()`, not `item.media_type()`. Also `label_status VARCHAR(16)` fits `LABELLED_UPFRONT` at exactly 16 characters with zero margin — widened to 24, since §5.4's confirm-label flow already contemplates further statuses.
- §2.2 `entity_id` changed from `VARCHAR(64) NULL` to `NOT NULL DEFAULT ''`, with `@Column(nullable = false, length = 64)` and a null→`''` coercion in the entity. **This is the answer to the question the spec left implicit.** The `open_marker` partial-uniqueness trick is sound on MySQL — a `NULL` in any unique-key column exempts the row, so cleared and dismissed rows never collide, which is exactly rule 23. But it is load-bearing that `entity_id` is `''` and never `NULL` for profile-level flags: with `entity_id NULL` the unique key does not fire **at all**, and the engine would insert a fresh open row on every nightly run — an unbounded flag table and a duplicated card on the creator's screen. An empty string does survive as `''` in a MySQL `VARCHAR` (no Oracle-style `''`→`NULL` coercion), so the value itself is fine; what was missing was anything *forcing* it. `NOT NULL DEFAULT ''` makes the invariant the schema's job rather than a convention in prose.
- §2.2 severity ordering: the finder becomes `findByCreatorProfileIdAndOpenMarker(String, String)` and `AccountHealthService.openFlags` sorts by an explicit `HealthSeverity.rank()` then `lastSeenAt` desc. Same fix in §6.1 item 4.
- §2.5 construction-site counts written in, as instructed. **`PreferencesResponse` has 4 sites**: `CreatorAgentPreferencesService.toResponse` (L285-288), `CreatorAgentControllerTest` L65 and L86, `CreatorMeeraControllerTest` L186. **`UpdatePreferencesRequest` has 9**, all in tests (`CreatorAgentPreferencesServiceTest`, `CreatorAgentControllerTest`). Phase B and Phase C are unbuilt, so these are the counts a builder will actually meet. **Trap written into the spec:** a bare `grep "new PreferencesResponse("` returns **5**, because `NotificationController` L262 constructs an unrelated one-arg `PreferencesResponse` for notification preferences. Grep the qualified name or the import.
- §2.7 the Indic word-boundary lookarounds corrected to `(?<![\p{L}\p{M}])` / `(?![\p{L}\p{M}])`. Devanagari matras and the virama are `\p{Mn}`, not `\p{L}`, so `(?!\p{L})` alone treats a mid-word mark as a boundary and matches inside inflected forms.

**D0 / D1**

- §3.1 payout sum given an explicit terminal-status filter (14.3 item 2).
- §3.1 the `[VERIFY]` on `DealMessageRepository` resolved in place.
- §3.2 line numbers corrected: `scoreOne` is **L267-325**, the `analyze(...)` call is **L288-289**, `PLATFORM_INSTAGRAM` is L97. The substance is right — `findByCreatorProfileIdAndPlatformAndTimeBetweenOrderByTimeAsc` exists at L43-44, and signal 2 (`historicalMetrics.size() >= 7`, `>20` → +30, `>10` → +15) is exactly as described. Note added that `scoreOne` has no `now` in scope.
- §3.1 the `MediaMetricsRepository` JPQL **is valid**: JPQL permits that correlated subquery, `findLatestPerCreatorAndPlatform` (L55-60) is the working precedent in the same codebase on this Hibernate version, and `m.postedAt` **does** exist (`MediaMetric` L80-81). Confirmed in place, with one caveat: `media_metrics` is indexed on `(media_id)` alone (V21 L62), not `(media_id, time)`, so this is a scan per media group — fine at a 60-row page, never unpaged.

**D2**

- §5.2 `REPLY_SPEED`'s "Fires when" cell was empty. Filled: POSITIVE below a 24h median, INFO above 48h, OK between; fewer than 3 brand→creator pairs is `DATA_GAP:TOO_FEW_REPLIES`, and that reason is added to the §5.1 precondition table, where it was missing.
- §5.2 season mode was ambiguous when a creator has *both* `weeklySponsoredLimit` and season mode. Resolved: the threshold is `max(computed, 0.45)`.
- §5.2 `GST_THRESHOLD_APPROACHING` must null-guard `taxRegistrationStatus`. The column is nullable (`CreatorProfile` L161-163) and is genuinely `NULL` for every creator predating the tax-identity feature. `status != GST_REGISTERED` is the right predicate; it must be written null-safe.
- §5.3 **`AuditLogService.recordSystemEvent` does not exist at HEAD.** The spec is right that Phase C adds it — Phase C §3.11 defines it and Phase C §15 correction 23 explains why Phase C rather than Phase E owns it — but §1 marks D2 as depending on HEAD only. The dependency table is corrected: D2 is `HEAD; job audit + two rules **[needs Phase C]**`. At HEAD the job logs counts and nothing else.

**D3 — the one that would have shipped broken**

- §6.2 **the listener must be a plain `@EventListener`, not `@Async @TransactionalEventListener(AFTER_COMMIT)`.** Almost every handler in `NotificationListener` uses the latter, so an engineer copying the nearest neighbour produces a job that publishes into the void: `WeeklyDigestJob` is not `@Transactional`, and an `AFTER_COMMIT` listener with no transaction bound is **silently skipped**. `NotificationEventContractTest` still passes — it checks that an annotated handler exists, not that it ever runs. `CreatorConnectNudgeJob` L118-121 already hit this and documents the resolution ("The listener runs synchronously — plain `@EventListener`, no `@Async`"), with its handler at `NotificationListener` L841. Written into §6.2 with the citation. This is also what makes §6.2's "record `channels_sent` from what the router queued" possible at all: an async listener would not have run by the time the job reads it back.
- §6.2 the `notify(...)` argument order corrected against the real signature (`event, title, body, link, toEmail, templateKey, templateData` — `NotificationService` L69-76). `creator.weekly_note` is in neither `EMAIL_ONLY_EVENTS` nor `IN_APP_ONLY_EVENTS` (L41-46), so it correctly takes both channels.
- §6.2 the **email opt-out claim is correct and now cited**: `queueEmailIfNotUnsubscribed` resolves an `EmailPreference` per event type and defaults to *subscribed* when no row exists (`.orElse(false)` on `isUnsubscribed`, L178), so a brand-new `eventType` works through the existing `POST /notifications/preferences` with no migration and no seed. `emailOf(String)` is a private helper on `NotificationListener` (L145-149), so calling it from a handler in that class is legal.
- §6.1 item 3 — **the biggest thing the spec did not state.** `CreatorNudgeService.getSuggestion` (L82) is not a read. For a creator with no row for the current UTC day and a matching trend above `scoreThreshold`, it **calls the AI service and spends** (`CreatorSuggestionAiClient.requestSuggestion`, Haiku; it falls back to a template on failure but still bills a turn when it succeeds), and it writes the nudge-log row. §12's "It costs nothing" is false as written. Everything else the spec says about it is correct: it is idempotent per UTC day; the Monday 09:30 IST run is 04:00 UTC, so the co-pilot page later that day returns the identical row; `pending_tagging` / `no_suggestion_today` are handled. Corrected: the cost is stated with its arithmetic, a `weekly-note.suggestion-limit:500` per-run ceiling is added for the first weeks, and §12's answer is rewritten. It also throws `ApiException(CREATOR_PROFILE_NOT_FOUND)`, so the per-creator try/catch is mandatory, not defensive.
- §6.3 **ruling on the GET-with-read-receipt: rejected, replaced with a POST.** Rule 24 ("GET never writes") is mine and I am not carving an exception into it for a timestamp. The practical argument is stronger than the principle: `GET /latest` will be re-fetched by react-query on window focus, by route prefetch, and by any mobile background refresh, so `opened_at` would record machinery rather than a human — and `opened_at` is an input to D4, the section whose entire point is that we measure outcomes honestly. It also forces a write transaction on every read of a page we expect to be polled. `POST /creator/weekly-note/{id}/opened` costs one line on a frontend that already has a `tapped` POST call site. §13 decision 2 is closed.
- §6.3 audio: `voiceAiClient.speak(...)` confirmed at `MeeraVoiceAiClient` L192 with exactly the claimed signature, and the 480-character cap sits safely under the Python `TTS_MAX_CHARS = 500`. `putBytes(objectKey, bytes, contentType)` (L96) returns `void` — correct as used. `presignGet` corrected per 14.3 item 5.

**D4 / frontend**

- §7.2 the baseline constant is `HAND_SAMPLE_LABEL_COMPLIANCE` at **L43** (javadoc L37-42) in `service/`**`admin`**`/CreatorAgentBaselineService.java`. The value `new SampleLabelCompliance(100, 73, 0.73)` and the record shape `(sample_size, labelled_count, compliance_rate)` are both exactly as the spec says (`AdminCreatorAgentDtos` L23-26). Path and line corrected.
- §7.1 the bare-DTO convention is confirmed: `AdminCreatorAgentController` (package `com.influora.web`, not `web/admin`) documents at L23-24 that it returns a raw DTO with no `ApiResponse` envelope. Note added that the **client** wraps it anyway — `apiRequest` in `src/admin/services/api-contracts.ts` L85-88 returns `ApiResponse<T>` — so the new method's TS return type is `Promise<ApiResponse<GrowthMetrics>>`, not `Promise<GrowthMetrics>`.
- §8.3 `creatorAgentApi` is at `src/admin/services/api-contracts.ts` **L1039-1042** exactly as claimed, and `admin.types.ts` L1099-1104 does name the hand sample. But `CreatorAgentBaselinesPage.tsx` never calls `creatorAgentApi` directly — it goes through `useCreatorAgentBaselines()` (`src/admin/hooks/useCreatorAgentBaselines.ts`), which does the unwrap. Corrected to add a sibling hook rather than break the page's convention.
- §8.2 dashboard slot and settings control corrected per 14.3 items 3 and 4.
- §8.1 confirmed: `CreatorAgentPreferencesUpdate` really is `Omit<CreatorAgentPreferences, 'consent_accepted' | 'consent_version'>` (`src/lib/api.ts` L6295-6298), so adding the two fields to `CreatorAgentPreferences` carries them into the PUT automatically through `MeeraSettingsSection`'s `...draft` spread — which is exactly why `UpdatePreferencesRequest` on the Java side must gain them in the same commit, or the round-trip test drops them.
- §8.2 `creator-copilot.tsx`: the render order is exactly as claimed and a slot exists. One clarification written in — the "Talk to Meera" card is one branch of a ternary (L129-182), so the insertion point is **after L182**; `ConsentScreen` (L184-189) is a Radix dialog that renders no inline layout and does not early-return, so it is not an obstacle.
- §8.4 `brand-settings.disclaimer.test.tsx` stays green untouched. Verified: the switch block is L898-909 with `disabled` unconditional and `title="Weekly digest isn't available yet"`, and the test asserts that exact title string at L199-201. Nothing in Phase D goes near `brand-settings.tsx`.

**Caption ruling (§12) — confirmed, no correction needed**

- No step in this phase sends caption text to any model. `DisclosureLabelService` is deterministic Java; `CaptionLabelJob` calls only it; flag text is composed from vocabulary constants and rendered numbers, never from the caption. The one AI call in the phase is the pre-existing daily suggestion, whose wire body is three fields and no caption (`CreatorSuggestionAiDtos` L10-13).
- The regex approach is implementable in Java as described, with the `\p{M}` correction above. `Pattern.UNICODE_CHARACTER_CLASS` plus lookarounds is the right construction — Java's `\b` is ASCII-only, exactly as the spec says. `java.text.Normalizer.normalize(s, Form.NFC)` covers normalisation. The `#ads` / `#adventure` negative cases fall out correctly from the "followed by end-of-text, whitespace, or punctuation" rule.
- **Reading `creator_captions.caption_text` does not violate the V26 note.** That note lives on `MediaMetric.caption` (L42-48, "internal BrandSafety pipeline ONLY") and constrains that column alone. `creator_captions` is the second, independent copy, and its own entity javadoc (`CreatorCaptionCache` L22-28) records a deliberate, Kabir-signed risk acceptance for precisely this class of consumer. The two are never joined. §12's answer stands as written.

### 14.5 Line references off by 1-3 (imprecise, not wrong)

`scoreOne` L266-324 → **L267-325**; `analyze` L286-289 → **L288-289**; `PayoutRepository` L115-119 → **L114-118**; `MediaItem` L11-20 → **L12-20**; `CreatorAgentBaselineService` L42-43 → **L43**. All corrected in place. Everything else I spot-checked was exact, including several that are easy to get wrong: `ThemeMatchService.loadTaxonomy` L39-54, `CreatorNudgeService.getSuggestion` L81-82, `CreatorMeeraController.requireFeatureEnabled` L104, `MeeraVoiceAiClient.speak` L192, `WalletService.getSummaryForUser` L209, `creatorAgentApi` L1039, `brand-settings.tsx` L900-907, `idx_creator_scores_creator_time` in V22, and the `MetricsPollingJob` cron (`0 0 */6 * * *` UTC → 28 rows per week, so §5.1's `TOO_FEW_POLLS` arithmetic is right).

### 14.6 Conditions on "buildable as corrected"

1. **Migrations.** Four files, `V20261005100000` through `V20261005100400`, all above Phase C's true highest (`V20260930101000`, verified against Phase C's own §2), above Phase B (`V20260910100700`) and above Phase E (`V20260920101200`). Every `CREATE TABLE` carries the collation clause; every VARCHAR carries `@Column(length = n)`; `MEDIUMTEXT` on `payload_json`; no `CHAR`. Index names collide with nothing, and MySQL scopes index names per table anyway. FK targets `creator_profiles(id)`, which is `VARCHAR(26)` since `V20260718130000`. `uk_chf_open` is 524 bytes, well inside the 3072-byte InnoDB limit. **This part is sound** — with the `entity_id NOT NULL DEFAULT ''` change.
2. **Job concurrency is not free.** `TaskSchedulerConfig.POOL_SIZE = 10` (L36) against **22** `@Scheduled` methods today (the fact sheet's count is right). Phase D adds 3, and Phases B/C/E add more. `WeeklyDigestJob` declares `lockAtMostFor = PT2H` and, as originally written, would hold one of those ten threads for the length of up to 5,000 outbound AI calls. Raise `POOL_SIZE` to 16 in this phase, or the suggestion ceiling in 14.4 is carrying that load alone. Not optional.
3. **The `[needs Phase C]` set is larger than §1 says.** Beyond the two rules and the money first line, D2's audit logging needs `recordSystemEvent`. Build D2 on HEAD only if you drop that line.
4. **The vocabulary is unverified content, not unverified code.** Rule 27 does not reach a `.json` resource. The Marathi, Tamil, Telugu, Malayalam, Kannada, Bengali and Gujarati entries in §2.7 are the one part of this spec nobody on this team can check by reading, and a wrong glyph produces a silent false `UNLABELLED` on a legal flag that is **not dismissible**. The day-1 native-speaker check in §10 is a blocking gate, not a checklist item. Until it passes, `UNLABELLED_LINKED_DELIVERABLE` ships as `WARN` and dismissible.
5. **`ScoreCalculationJob` is a scoring change with a brand-visible blast radius.** Reviving signal 2 moves `creator_scores.fake_follower_score` for real creators, and brands read the authenticity score. §3.2 is right to say so; add that it runs in shadow for one night and the score distribution is diffed before the flag flips. A creator's rate estimate falls out of the quality score.
6. **`MediaMetricsRepository.findLatestPerMediaByCreatorProfileId` stays capped.** It is a correlated subquery over a poll table with no `(media_id, time)` index. The 60-row `Pageable` in §5.1 is the control. Never call it unpaged.

### 14.7 Remaining risks I am not fixing here

- **The unique key does not stop two runs racing.** `AccountHealthJob` is `@SchedulerLock`ed, but `AccountHealthService.evaluate` is also reachable from a route. Two concurrent evaluations of the same creator race on the flag upsert and one takes a `DataIntegrityViolationException` on `uk_chf_open`. Catch it at the upsert and re-read — and note that a `catch` around a plain `save()` on a managed entity is dead code, because the violation fires at commit, outside the catch. Use `saveAndFlush`.
- **`saved_creators` has no per-creator index.** `countByCreatorProfileIdAndSavedTrue` scans on a column the table is not keyed on (it is workspace-first). Small today; add the index the first week the shortlist counter reaches a dashboard.
- **`REPLY_SPEED` measures reply speed only to the brands who wrote first.** `ReplySpeed.medianHours` pairs brand→creator inside a collaboration. A creator who is never messaged has no pairs and no flag, which is correct; a creator who replies fast to one chatty brand and ignores four others still scores POSITIVE. Acceptable for a POSITIVE flag. It must never become an input to a score.
- **§5.2's `LABELLED_SPONSORED_RATIO` uses the creator's own limit as the threshold.** With `weeklySponsoredLimit = 2` and 12 posts in 30 days the threshold is 0.67, looser than the 0.30 default. That is deliberate — the rule is "you exceeded what you told us you wanted" — but a creator who sets a high limit silences the rule. Say so in the copy, or the flag reads as broken.
- **Nothing in this phase writes `CollaborationStatus.SHORTLISTED`**, which is still never set anywhere in main source. §7.1's "brand shortlist rate" is therefore measured entirely from `saved_creators`. That is the honest available signal and the spec uses it correctly, but the admin panel must not label it "shortlisted".
