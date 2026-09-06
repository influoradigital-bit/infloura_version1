# T-MEERA-CREATOR-PHASE-C — Build Spec: Money, Delivery, Channels

<!-- PRIYA: baseline corrected. Every file:line citation in this document matches the WORKING TREE, not `143ca1e` — verified drift: ContractService.generate 143→146, materializeDeliverables 425→734, EscrowService.tryReleaseOnApproval 702→729, assertReleaseConditionSatisfied 1328→1545, DealService.sendMessage 619→618. One citation has no target at HEAD at all: ContractService.amend (F-0414) exists ONLY in the uncommitted wave. See §15 condition 1. -->
**Status:** Priya-reviewed 2026-09-04 (§15); build only after §15.4's four conditions. **Baseline commit:** `143ca1e` (HEAD; contains Phase A) **plus the uncommitted working tree** — the +864-line money-path wave on this branch. All line citations below are working-tree lines. Steps marked **[needs Phase B]** consume symbols from `../T-MEERA-CREATOR-PHASE-B/SPEC.md` (creator tool controller, `CreatorToolName`, `CreatorToolScopes`, `MeeraDraft`, `RoutineReplyService`, `DealRiskService`); every other step builds on HEAD alone. **Date:** 2026-09-04.
**Fact sheet:** `facts/money-delivery-channels.md` (this phase), plus Phase B and Phase E fact sheets. If a fact sheet and this spec disagree, the fact sheet wins and this spec has a bug to report.

Plan reference: Meera for Creators plan rev 2, Part 7 Phase C (week 5). The fact sheet showed that three things the plan assumed do not exist: deliverables are never linked to milestones (so release-on-approval and verification are dead paths), there is no SMS, WhatsApp, or push code at all, and per-post metrics are not tied to deals. Section 3 fixes the first before anything else; sections 6 and 7 build the second and third.

---

## 0. Rules that apply to every step

Rules 1 to 12 from the Phase B spec and 13 to 17 from Phase E apply. Phase C adds, and restates the ones Priya has had to correct twice:

18. **Never compute tax.** TDS and GST are unimplemented platform-wide by decision. Phase C shows a TDS figure only when a human recorded one (`Payout.tdsAmount` non-null) and shows it as three states: not recorded, recorded as none due (`0.00`), recorded amount. No arithmetic, no rate, no estimate. Any wire field that carries it is new, named `tds_recorded`, and documented as reversing `MoneyDtos` L148-157 deliberately.
19. **Do not extend positional records that have many construction sites.** `CreatorPayoutRowResponse`, `DeliverableStatusResponse`, `MilestoneDto`, `CreatorContextResponse` stay as they are. New data goes in new records or, for the Meera context, into the existing `Map<String, Object> dealsSummary` (no record change).
20. **Every service method a job or webhook calls to perform a write is `public` and `@Transactional`**, invoked through the Spring proxy from another bean. No package-visible siblings, no self-invocation.
21. **Every scheduled job** is `@Component`, `@Scheduled` with a config-driven cron, `@SchedulerLock(name = "<ClassName>", lockAtMostFor, lockAtLeastFor)`, gated by an `influora.*.enabled` flag defaulting to `false`, batched, and per-item try/catch.
22. **Every notification event** is a record in package `com.influora.service.notification.event`, added to the `permits` list of `NotificationEvent` (the last entry has no trailing comma), handled in `NotificationListener` with `@Async @TransactionalEventListener(phase = AFTER_COMMIT)`, and given a template in `EmailTemplateRegistry` by editing that package-private file with the 5-arg `Spec(subject, heading, bodyTemplate, ctaLabel, ctaUrlVar)`.
23. **MySQL has no partial unique index.** One-open-row-per-parent uses a nullable marker column in the unique key. `VARCHAR` never `CHAR`. `MEDIUMTEXT` for anything a user can paste. Every `CREATE TABLE` ends with `) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;`. Migrations timestamp-versioned above Phase B (`V20260910100500`) and Phase E (`V20260920...`): use `V20260930...`. <!-- PRIYA: added the missing half of Phase B correction 11. -->
    **Every `VARCHAR(n)` column gets an explicit `@Column(name = "...", length = n)` on its entity field.** Hibernate maps a bare `String` to `VARCHAR(255)`; `ddl-auto=validate` then reports "wrong column type ... found [varchar]" against a narrower DDL column and the app will not boot. This fires on `source VARCHAR(20)`, every `channel`/`status VARCHAR(16)`, `active_marker VARCHAR(1)`, `graph_api_version VARCHAR(16)`, both `VARCHAR(2048)`s, and `offset_hours`/`failure_count` (`@Column(name=...)` only, `int` needs no length). Same family as the `CHAR` ban, same live boot break (`V20260718150000`).
24. **GET never writes.** Allocation is POST.
25. <!-- PRIYA: reworded — the original is physically impossible. An HMAC over the body requires reading the body; nothing can authenticate "before" it. The real control is a size cap ahead of the read. --> **Third-party webhooks cap the body before reading it, verify the signature over the raw bytes before parsing**, are replay-protected, and answered fast; the work happens in a job. Concretely (see 6.4): reject on `Content-Length` first, then read through `LimitedInputStream`, then HMAC, then parse. The proxy limit is the primary control because a handler bug cannot bypass it.
26. **Phone numbers are PII.** Stored E.164, encrypted at rest with the existing `EmailPhonePiiCipher` (`com.influora.service.security`) where the column is new, never logged, never in audit `detail`. <!-- PRIYA: --> **Lookup hashes use `EmailPhonePiiCipher.blindIndex` (keyed SHA-256, 64 hex), never a bare `MessageDigest` SHA-256** — an unkeyed digest of a 10-digit Indian mobile is a 10^10 space and this hash is the join key on an unauthenticated webhook.
27. <!-- PRIYA: new rule. This is the class that caused most of Phase B's ten compile errors and it recurs 14 times here. --> **No claim about existing code without having opened the file in this session.** Every sentence of the form "X exists", "X takes N args", "add if missing", "read L*a*-*b* and change Y", "the same client Z uses" must be resolved to a verified `path:line` + exact signature **before** the spec ships — not left for the engineer. "Add if missing" is not an instruction; it is an unfinished sentence. Two failure modes are equally bad: naming a symbol that does not exist, and telling someone to change code that is **already correct**.
28. <!-- PRIYA: new rule. --> **A private helper cannot be called from another class.** Before reusing an existing method, check its modifier and its package. Recurring offenders in this codebase: `CreatorMeeraController.requireFeatureEnabled()` (private), `DisputeService.requireOwnedCollaboration` (private), `DealService.publishToStream`/`toMessageResponse`/`appendSystemMessage` (private), `MeeraContextService.assembleCreatorContext`/`buildDealsSummary` (private, and `buildDealsSummary` is also `static` with no repository access). Either add the new method **inside** that class or promote the helper deliberately, and say which.

---

## 1. Scope in one table

| Id | Job | Depends on | Backend | AI | Frontend | Ops |
|----|-----|-----------|---------|----|----------|-----|
| C0 | Foundation fixes: milestone-deliverable link, revision-cap truth, client type drift | HEAD | migration, materialization, backfill, DTO fix | | type fixes | |
| C1 | Money watch: per-deal money timeline, payout status classes, TDS as recorded, invoice presence, TDS ledger CSV | HEAD; tool needs Phase B | `CreatorMoneyService`, routes | `get_payment_status` tool, context keys | money card, wallet timeline | |
| C2 | Delivery proof: 24h/72h/7d snapshots, Story poll, post probe, keep-up-until, proof card to brand | HEAD; tool needs Phase B | snapshot table, Meta client methods, two jobs | `get_delivery_proof` tool | proof card, send proof | |
| C3 | Channels: WhatsApp (opt-in, verification, five templates, STOP, approve-by-reply), web push, channel preferences | HEAD; approve-by-reply needs Phase B | consents, OTP, sender, webhook, router | | settings, service worker | WABA, templates, VAPID keys |
| C4 | Brand nudges: review overdue, milestone unfunded, deadline tomorrow; escalation; de-dup ledger; metrics | HEAD | ledger, job, three events, system messages | | brand thread rendering | |
| C5 | Revisions and disputes: cap surfaced, dispute evidence and responses, dispute mode for Meera, evidence pack | HEAD; Meera mode needs Phase B | evidence table, routes, pack builder, cleanup guard | dispute rails | evidence UI, download | |

Out of scope: any TDS computation, auto-release without a Swapnil ruling (built behind a flag defaulting off), YouTube verification, the weekly digest (Phase D), the manager seat.

---

## 2. Data model

### 2.1 `V20260930100000__deliverables_phase_c.sql` (alter)

```sql
ALTER TABLE deliverables
    ADD COLUMN keep_up_until DATE NULL,
    ADD COLUMN removed_detected_at TIMESTAMP NULL,
    ADD COLUMN last_probe_at TIMESTAMP NULL,
    ADD COLUMN last_probe_outcome VARCHAR(24) NULL;      -- LIVE | NOT_FOUND | NO_TOKEN | RATE_LIMITED | ERROR
ALTER TABLE deliverables
    ADD COLUMN platform_media_id VARCHAR(100) NULL;         -- PRIYA: the numeric Graph media id, once matched. Without it the keep-up probe has nothing to GET (see 5.3).
CREATE INDEX idx_deliverables_keep_up ON deliverables (keep_up_until, status);
```

<!-- PRIYA: dropped `CREATE INDEX idx_deliverables_milestone`. V37__deliverables.sql:39 already declares `CONSTRAINT fk_deliverable_milestone FOREIGN KEY (milestone_id) REFERENCES payment_milestones(id)`, and InnoDB backs every FK with an index — this would be a duplicate. Added `platform_media_id`; see correction 10 in §15. -->

Entity `Deliverable`: five fields with explicit `@Column(name = ..., length = n)` (rule 23 — `last_probe_outcome` is `length = 24`, `platform_media_id` is `length = 100`); mutators `applyKeepUpUntil(LocalDate)`, `applyProbe(Instant at, String outcome, boolean removed)` (sets `removedDetectedAt` the first time `removed` is true, never clears it), `applyPlatformMediaId(String)` (write-once), and **`assignMilestone(String milestoneId)`** (the column exists at L34-35 and has no setter; add one, used only by section 3).

### 2.2 `V20260930100100__deliverable_metric_snapshots.sql` (create)

```sql
CREATE TABLE deliverable_metric_snapshots (
    id                  VARCHAR(26)  NOT NULL,
    deliverable_id      VARCHAR(26)  NOT NULL,
    collaboration_id    VARCHAR(26)  NOT NULL,
    creator_profile_id  VARCHAR(26)  NOT NULL,
    platform_media_id   VARCHAR(100) NULL,
    offset_hours        INT          NOT NULL,           -- 24 | 72 | 168 | 0 for a manual "now" snapshot
    captured_at         TIMESTAMP    NOT NULL,
    source              VARCHAR(20)  NOT NULL,           -- META_API | CREATOR_REPORTED
    reach               BIGINT       NULL,
    views               BIGINT       NULL,
    likes               BIGINT       NULL,
    comments            BIGINT       NULL,
    saves               BIGINT       NULL,
    shares              BIGINT       NULL,
    total_interactions  BIGINT       NULL,
    story_replies       BIGINT       NULL,
    story_navigation_json TEXT       NULL,               -- Story only, raw navigation breakdown
    graph_api_version   VARCHAR(16)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dms_deliverable_offset (deliverable_id, offset_hours),
    CONSTRAINT fk_dms_deliverable FOREIGN KEY (deliverable_id) REFERENCES deliverables(id) ON DELETE CASCADE,
    INDEX idx_dms_collab (collaboration_id, captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `DeliverableMetricSnapshot` (builder only, immutable like `CreatorMetric`), enum `SnapshotSource {META_API, CREATOR_REPORTED}`. Repository: `List<DeliverableMetricSnapshot> findByDeliverableIdOrderByOffsetHoursAsc(String)`, `boolean existsByDeliverableIdAndOffsetHours(String, int)`, `List<DeliverableMetricSnapshot> findByCollaborationIdOrderByCapturedAtAsc(String)`.

The unique key on `(deliverable_id, offset_hours)` makes the snapshot job idempotent; `offset_hours = 0` rows are the creator-triggered "snapshot now" and there is at most one, replaced by delete-then-insert inside one transaction.

### 2.3 `V20260930100200__channel_consents.sql` (create)

```sql
CREATE TABLE channel_consents (
    id                  VARCHAR(26)  NOT NULL,
    user_id             VARCHAR(26)  NOT NULL,
    channel             VARCHAR(16)  NOT NULL,           -- WHATSAPP | PUSH
    phone_e164_enc      VARCHAR(512) NULL,               -- EmailPhonePiiCipher.encrypt of the E.164 form; WHATSAPP only
    phone_hash          VARCHAR(64)  NULL,               -- PRIYA: EmailPhonePiiCipher.blindIndex (keyed SHA-256, 64 hex) of the NORMALIZED 10-DIGIT form, not a bare SHA-256 of E.164 — see rule 26
    status              VARCHAR(16)  NOT NULL,           -- PENDING | ACTIVE | STOPPED | REVOKED
    consent_version     VARCHAR(16)  NOT NULL,
    consented_at        TIMESTAMP    NULL,
    verified_at         TIMESTAMP    NULL,
    stopped_at          TIMESTAMP    NULL,
    active_marker       VARCHAR(1)   NULL,               -- 'A' while ACTIVE or PENDING, NULL otherwise: partial-uniqueness substitute
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cc_user_channel_active (user_id, channel, active_marker),
    UNIQUE KEY uk_cc_phone_hash_active (phone_hash, active_marker),
    CONSTRAINT fk_cc_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_cc_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `ChannelConsent`, enums `NotificationChannel {IN_APP, EMAIL, WHATSAPP, PUSH}` (IN_APP and EMAIL exist implicitly today; the enum is new), `ChannelConsentStatus {PENDING, ACTIVE, STOPPED, REVOKED}`. `active_marker` is `'A'` for PENDING and ACTIVE, `NULL` for STOPPED and REVOKED, maintained by the entity's status mutators. Constant `CURRENT_CHANNEL_CONSENT_VERSION = "wa-v1"`.

### 2.4 `V20260930100300__phone_otp_challenges.sql` (create)

<!-- PRIYA: hand-wave resolved. V5__email_otp.sql is `id, email VARCHAR(255), otp_hash VARCHAR(64), expires_at, verified BOOLEAN, attempts INT, created_at, INDEX idx_email_otp_email`. The DDL below deliberately differs in two ways and both are improvements: `consumed_at TIMESTAMP NULL` instead of `verified BOOLEAN` (records WHEN, and is null-safe for "one active challenge" queries), and a `user_id` V5 has no need for. Nothing to copy; the shape below is final. -->
Shape below is final (V5's differences are deliberate and noted above):

```sql
CREATE TABLE phone_otp_challenges (
    id              VARCHAR(26)  NOT NULL,
    user_id         VARCHAR(26)  NOT NULL,
    phone_hash      VARCHAR(64)  NOT NULL,               -- blindIndex, same form as channel_consents.phone_hash
    code_hash       VARCHAR(64)  NOT NULL,               -- SHA-256(code + id); the challenge id is the per-row salt
    attempts        INT          NOT NULL DEFAULT 0,
    expires_at      TIMESTAMP    NOT NULL,
    consumed_at     TIMESTAMP    NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_poc_user_created (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.5 `V20260930100400__push_subscriptions.sql` (create)

```sql
CREATE TABLE push_subscriptions (
    id              VARCHAR(26)  NOT NULL,
    user_id         VARCHAR(26)  NOT NULL,
    endpoint_hash   VARCHAR(64)  NOT NULL,
    endpoint        VARCHAR(2048) NOT NULL,
    p256dh          VARCHAR(255) NOT NULL,
    auth_secret     VARCHAR(255) NOT NULL,
    user_agent      VARCHAR(255) NULL,
    status          VARCHAR(16)  NOT NULL,               -- ACTIVE | EXPIRED | REVOKED
    last_success_at TIMESTAMP    NULL,
    failure_count   INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ps_endpoint (endpoint_hash),
    CONSTRAINT fk_ps_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_ps_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.6 `V20260930100500__notification_channel_prefs.sql` (create)

```sql
CREATE TABLE notification_channel_prefs (
    id          VARCHAR(26) NOT NULL,
    user_id     VARCHAR(26) NOT NULL,
    category    VARCHAR(32) NOT NULL,                    -- DEALS | MONEY | DEADLINES | MEERA
    channel     VARCHAR(16) NOT NULL,                    -- WHATSAPP | PUSH  (email stays in email_preferences)
    enabled     TINYINT(1)  NOT NULL DEFAULT 1,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ncp_user_cat_chan (user_id, category, channel),
    CONSTRAINT fk_ncp_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

`NotificationCategory {DEALS, MONEY, DEADLINES, MEERA}` with a static map from `eventType` string to category (`escrow.*`, `payout.*` → MONEY; `proposal.*`, `deal.*`, `deliverable.*` → DEALS; `deadline.*` → DEADLINES; `meera.*` → MEERA; anything else → no WhatsApp/push). Absent row = enabled.

### 2.7 `V20260930100600__deal_nudges.sql` (create)

```sql
CREATE TABLE deal_nudges (
    id               VARCHAR(26) NOT NULL,
    entity_type      VARCHAR(24) NOT NULL,               -- DELIVERABLE | MILESTONE | DEAL
    entity_id        VARCHAR(26) NOT NULL,
    stage            VARCHAR(32) NOT NULL,               -- REVIEW_OVERDUE_D3 | REVIEW_OVERDUE_D7 | UNFUNDED_D7 | UNFUNDED_D14 | DEADLINE_T1
    recipient_user_id VARCHAR(26) NOT NULL,
    channel_summary  VARCHAR(64) NULL,                   -- e.g. "in_app,email,whatsapp"
    sent_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dn_entity_stage_recipient (entity_type, entity_id, stage, recipient_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.8 `V20260930100700__dispute_evidence.sql` (create)

```sql
CREATE TABLE dispute_evidence (
    id            VARCHAR(26)  NOT NULL,
    dispute_id    VARCHAR(26)  NOT NULL,
    party         VARCHAR(16)  NOT NULL,                 -- CREATOR | BRAND | ADMIN
    author_user_id VARCHAR(26) NOT NULL,
    kind          VARCHAR(16)  NOT NULL,                 -- TEXT | LINK | FILE
    text          MEDIUMTEXT   NULL,
    url           VARCHAR(2048) NULL,
    r2_key        VARCHAR(500) NULL,
    file_name     VARCHAR(255) NULL,
    content_type  VARCHAR(100) NULL,
    file_size     BIGINT       NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_de_dispute FOREIGN KEY (dispute_id) REFERENCES disputes(id) ON DELETE CASCADE,
    INDEX idx_de_dispute_created (dispute_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.9 `V20260930100800__collaborations_evidence_hold.sql` (alter)

```sql
ALTER TABLE collaborations ADD COLUMN evidence_hold_until DATE NULL;
```

`Collaboration.evidenceHoldUntil` with `applyEvidenceHold(LocalDate)`. Set to `max(existing, today + 90 days)` when an evidence pack is requested or a dispute is opened; read by the cleanup job (section 8.5).

### 2.10 `V20260930100900__outbound_channel_messages.sql` (create)

```sql
CREATE TABLE outbound_channel_messages (
    id                VARCHAR(26)  NOT NULL,
    user_id           VARCHAR(26)  NOT NULL,
    channel           VARCHAR(16)  NOT NULL,             -- WHATSAPP | PUSH
    event_type        VARCHAR(64)  NOT NULL,
    template_name     VARCHAR(64)  NULL,
    params_json       TEXT         NOT NULL,             -- rendered template parameters, no PII beyond first name
    idempotency_key   VARCHAR(128) NOT NULL,
    status            VARCHAR(16)  NOT NULL,             -- QUEUED | SENT | DELIVERED | READ | FAILED | SKIPPED
    provider_message_id VARCHAR(128) NULL,
    failure_code      VARCHAR(64)  NULL,
    pending_action_json TEXT       NULL,                 -- {"kind":"APPROVE_DRAFT","draft_id":"..."} for approve-by-reply
    session_window_until TIMESTAMP NULL,                -- set when an inbound message opens the 24h window
    queued_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at           TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ocm_idempotency (idempotency_key),
    UNIQUE KEY uk_ocm_provider (provider_message_id),
    INDEX idx_ocm_status_queued (status, queued_at),
    INDEX idx_ocm_user_queued (user_id, queued_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.11 `V20260930101000__inbound_whatsapp_messages.sql` (create)

<!-- PRIYA: the eleventh migration. §6.4 introduced it as a parenthetical and never wrote its SQL; §2 then said "ten". Written out here, and every "ten" in this document is now "eleven" (§2 count, §10 boot test, §11 day 1, §12 Q10). -->

```sql
CREATE TABLE inbound_whatsapp_messages (
    id              VARCHAR(26)  NOT NULL,
    wa_message_id   VARCHAR(128) NOT NULL,               -- Meta's messages[].id; the durable replay arbiter
    from_hash       VARCHAR(64)  NOT NULL,               -- blindIndex of the normalized 10-digit number
    from_e164_enc   VARCHAR(512) NOT NULL,               -- EmailPhonePiiCipher ciphertext, never logged
    body_text       MEDIUMTEXT   NULL,                   -- user-pasted; MEDIUMTEXT per rule 23
    received_at     TIMESTAMP    NOT NULL,               -- Meta's own timestamp, not ours
    ingested_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP    NULL,
    outcome         VARCHAR(32)  NULL,                   -- STOPPED | APPROVED | DEEP_LINK | UNKNOWN_TEXT | NO_CONSENT | ERROR
    PRIMARY KEY (id),
    UNIQUE KEY uk_iwm_wa_message (wa_message_id),
    INDEX idx_iwm_unprocessed (processed_at, ingested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Column named `body_text`, not `text` — `TEXT` is a MySQL type name and an unquoted `text` column reads badly in every later migration. `uk_iwm_wa_message` is the replay defence and must be inserted with `saveAndFlush` inside the `try` so a `DataIntegrityViolationException` is actually catchable (recorded trap: a plain `save()` on a managed entity defers the INSERT to commit, outside the catch). No FK: a message can arrive from a number with no consent row, and we still want the row for the audit count.

Eleven migrations. `MeeraPhaseCBootValidationTest` (Testcontainers, `mysql:8.0.40`, `ddl-auto=validate`, `DockerAvailableCondition`) covers all eleven. It is the only thing standing between rule 23's `@Column(length)` requirement and eleven boot failures — it must run with Docker present at least once before day 7, not be skipped every time.

---

## 3. C0: foundation fixes

### 3.1 Milestone-deliverable link

Today `Deliverable.milestoneId` is never set, so `BrandDeliverableService.approve` always gets `held("NO_MILESTONE")` from `tryReleaseOnApproval`, `DeliverableVerificationService.verify` always returns `FALLBACK_NO_MILESTONE`, and `DeliverableMetric` can never be written. Phase C assigns the link at materialization and backfills.

**Assignment rule** (`MilestoneAssignment.assign(List<Deliverable> bySlot, List<PaymentMilestone> bySequence)` in `com.influora.service.deals`): one milestone → all deliverables to it. N milestones and M deliverables → deliverable at slot `i` goes to milestone `floor(i × N / M)` (contiguous blocks in order, so the first milestone covers the first deliverables). Zero milestones → leave null. Pure static, unit-tested with the (1,3), (2,3), (3,2), (3,7), (0,2) cases.

<!-- PRIYA: this step reverses a recorded CTO ruling and must say so. DeliverableRepository.findByMilestoneId carries an @deprecated note reading "CR-51 / Priya's Option B ruling: ... milestones and deliverables come from independent sources with no principled N:M mapping ... this lookup always returns empty and has no remaining production caller." The rule above IS that mapping. I supersede CR-51 Option B here — the heuristic beats three permanently dead code paths — but the javadoc must be rewritten in the SAME commit and the supersession recorded in wiki/tech/architecture.md. See §15.3 risk 1 and §15.4 condition 2. -->
**Supersedes CR-51 Option B.** Rewrite `DeliverableRepository.findByMilestoneId`'s `@deprecated` javadoc in the same commit (it currently asserts the lookup "always returns empty") and record the supersession in `wiki/tech/architecture.md`. It has no production callers today, so nothing breaks — but leaving a javadoc that says "always empty" over a finder that now returns rows is how the next audit goes wrong.

**Materialization:** the order in `generate` is **already correct** — `contractRepository.save` L308, milestones built L310-323, `milestoneRepository.saveAll(milestones)` **L324**, `materializeDeliverables(collaboration)` **L327**. <!-- PRIYA: the spec said "reorder if needed"; verified, no reorder is needed. --> **Insert the assignment immediately after L327**, before `collaborationLifecycleService.onContractGenerated` at L329. `materializeDeliverables` returns `void` **and early-returns at L735-739 when the collaboration already has deliverables** (a regenerated contract), so do not try to use its return value or assume it just wrote the rows — re-read:

```java
// after L327
List<Deliverable> slots =
        deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(collaboration.getId());
MilestoneAssignment.assign(slots, milestones);   // milestones is the local list saved at L324
deliverableRepository.saveAll(slots);
```

`MilestoneAssignment.assign(List<Deliverable>, List<PaymentMilestone>)` mutates in place via `assignMilestone`; keep it `void` and pure-by-position so the unit test can call it with detached objects.

**Amend.** <!-- PRIYA: `ContractService.amend` DOES NOT EXIST at HEAD 143ca1e. It is F-0414, part of the uncommitted +423-line ContractService diff. This step is conditional on that wave landing — §15.4 condition 1. --> In `ContractService.amend` (working tree L441; **absent at HEAD `143ca1e`**), insert the same three lines after `milestoneRepository.saveAll(milestones)` at **tree L538**, before the `promptCreatorToSignIfPossible` try-block. Two things `amend` makes true that `generate` does not:

- `amend` mints a **new** contract version with **new** milestone ids and **never deletes the superseded milestones** (verified: no delete call anywhere in the method). So `payment_milestones` accumulates across versions and any by-collaboration milestone query is wrong (see 4.1).
- **Never move a deliverable off a milestone whose `EscrowHold` is `FUNDED`.** The old milestone still carries `escrow_hold_id` and real secured money; re-pointing the deliverable at an unfunded new milestone means the next approval releases nothing while funds sit against a row nothing points to. Skip those deliverables, count the skips, and log one line per amend with a count (never ids in `detail`). This is a hard guard, not a nicety — see §15.3 risk 3.

**Backfill:** `MilestoneLinkBackfillJob` (`influora.deals.milestone-link-backfill.enabled:false`, daily 03:15 IST, `PT30M/PT1M`): for collaborations with at least one contract and at least one deliverable where `milestone_id IS NULL`, apply the same rule using the latest contract's milestones. Idempotent, and it carries the same FUNDED-hold guard `amend` does. Batched: `Pageable` of 200 collaborations per run, never an unbounded scan. Logs counts through `AuditLogService.recordSystemEvent`. <!-- PRIYA: that method does not exist and Phase E cannot supply it — Phase C lands first (this spec's own §10 says so). Phase C adds it. -->
**`AuditLogService.recordSystemEvent(String workspaceId, String eventType, String outcome, Map<String,Object> detail)` is added in THIS phase**, modelled on `recordMoneyEvent` (`AuditLogService` L110-131): `ACTOR_SYSTEM`, `@Transactional(propagation = REQUIRES_NEW)`, `detailJson` via `JsonLists.toJsonObject`. Outcome constants are `OUTCOME_ALLOWED` / `OUTCOME_REJECTED` / `OUTCOME_FAILED` (L32-34) — there is no `OUTCOME_OK`. Phase E 4.1 then consumes it rather than defining it.

**Consequences to verify by test:** `BrandDeliverableService.approve` (L109) on a linked deliverable now reaches `releaseInternal`; with the release gate disabled (blank cutover, the default) release proceeds when the hold is FUNDED. Note what that means operationally: **money starts moving on approval for every funded milestone, on a path that has moved none since it shipped.** That is the point of C0, but it is a behaviour change to a live money path and belongs in the release note, not only in a test.

<!-- PRIYA: the cutover sentence badly understates the gate. assertReleaseConditionSatisfied (L1545-1583) reads findByCollaborationIdOrderBySlotIndexAsc — ALL deliverables of the collaboration — and requires allMatch. Linking to milestones does not re-key it. -->
**With the cutover set, staged per-milestone release remains impossible.** `assertReleaseConditionSatisfied` reads **every** deliverable of the collaboration (L1554) and requires `allMatch(satisfying)` (L1578). On a two-milestone deal, approving deliverable 1 cannot release milestone 1 until deliverable 2 also satisfies the condition. It also **throws** `RELEASE_CONDITION_NOT_MET` outright for a post-cutover milestone whose collaboration has zero deliverables (L1555-1571, Swapnil's CR-51 step-3 "forbid" ruling). Section 14 decision 2 must be re-framed on this before ops sets the cutover: it enables an all-deliverables-or-nothing gate, not staged release. Re-keying the gate to the milestone is a CR-51 reopen, not a Phase C edit.

<!-- PRIYA: DELETED — `DeliverableVerificationService.persistVerified` (L268-297) is ALREADY the upsert this asked for: findByMilestoneId → present ? reuse : build, then applyVerifiedReport + save. Nothing to change. L124-200 is verify() plus the head of verifyInstagram, not the persist path. This is the worst kind of spec bug: it sends an engineer to rewrite correct code. -->
`DeliverableVerificationService.verify` now passes the milestone check (L130-137) and can finally write `DeliverableMetric`. **No change is needed there** — `persistVerified` (L268-297) is already a `findByMilestoneId` upsert, so two deliverables sharing a milestone correctly refresh one row. `DeliverableVerificationServiceUpsertTest` is a characterization test of existing behaviour that C0 makes reachable, not a test of new code.

### 3.2 Revision-cap truth

`CreatorDeliverableService` L1134-1148 returns `maxRevisions = null` and asserts there is no cap, while `BrandDeliverableService.requestRevision` L340-352 enforces `Collaboration.maxRevisions`. <!-- PRIYA: `getStatus` returns DeliverableStatusResponse, which has no maxRevisions and (rule 19) is not being extended — there is nothing to change there. The cap lives only in the private static `toListItem` (L1121), and its two call sites need different treatment. -->
Fix the creator side in `CreatorDeliverableService` only. `toListItem` is `static DeliverableListItem toListItem(Deliverable)` at **L1121** with exactly two call sites; widen it to `toListItem(Deliverable, Integer maxRevisions)` and fix both:

- **L737**, `listForCollaboration` — a method reference `.map(CreatorDeliverableService::toListItem)` that will not compile after the widening. Change `requireOwnedCollaboration(AuthPrincipal, String)` (L790) from `void` to **returning the `Collaboration`** (its `findByIdAndCreatorId` already loads one; do not add a second query), and pass `collaboration.getMaxRevisions()` through a lambda.
- **L785**, `listForCollaborations` — the batch path. It already has the `Collaboration` objects from `findByCreatorIdAndIdIn` (L774); build a `Map<String,Integer>` of collaboration id to cap and index into it. No extra query.

`getStatus` (L717) and `DeliverableStatusResponse` are untouched (rule 19). Delete the F-0360 comment block at L1133-1140 — it asserts "this platform has NO revision cap", which `BrandDeliverableService.requestRevision` L340-352 has contradicted since F-0417.

Frontend `contracts-and-deliverables.tsx`: the six literals are at L203, 216, 226, 273, 283, 330 — <!-- PRIYA: incomplete. --> but the field is also **declared required at L121 (`maxRevisions: number`)** and **rendered at L1510** (`{deliverable.revisionCount}/{deliverable.maxRevisions} used`). Type, literals and render move together, or `tsc` fails on the first of the six. Read `deal.dealTerms?.maxRevisions ?? 2`.

### 3.3 Client type drift

`src/lib/api.ts`: `EscrowHoldRow.status` becomes `'PENDING' | 'FUNDED' | 'RELEASED' | 'REFUNDED' | 'FROZEN'` and gains `workspaceId: string` and `releasedAt: string | null` (the server sends both); `WalletTransactionRow.type` gains `'AFFILIATE_COMMISSION'`.

<!-- PRIYA: the deliverable-card union is not a drifted copy of DeliverableStatus — it is a different vocabulary. Verified at deliverable-card.tsx L24: 'submitted' | 'approved' | 'revision_requested' | 'payment_released', lowercase, and `payment_released` is a PAYMENT state with no DeliverableStatus counterpart. Swapping in DeliverableStatus silently deletes it. -->
`deliverable-card.tsx` (L24): replace the lowercase four-value union with `status: DeliverableStatus` **plus a separate `paymentReleased?: boolean` prop**, and a `cardVariantFor(status, paymentReleased)` mapper for the visual states. Do not fold `payment_released` into the status enum; it is not one.

**Enum-drift vitest** (`src/lib/__tests__/enum-drift.test.ts`). Verified feasible: vitest runs on Node with `environment: 'jsdom'`, so `fs` works, and the repo root is the vitest root. Read, with `path.resolve(process.cwd(), ...)`:
- `influora-api/src/main/java/com/influora/domain/enums/DeliverableStatus.java`
- `influora-api/src/main/java/com/influora/domain/enums/EscrowStatus.java`
- `influora-api/src/main/java/com/influora/domain/enums/WalletTransactionType.java`

against the TS unions in `src/lib/types.ts` (which is where `DeliverableStatus` actually lives; `api.ts:47` re-exports it) and `src/lib/api.ts`. Fail loudly — `expect.fail`, never `it.skip` — if a `.java` file is missing, exactly as `test_creator_context_drift.py` does on the Python side; a drift test that skips when it cannot find its source is worse than none. `vitest.config.ts` excludes `**/.claude/**` and `**/.proof-os/**`, so sibling worktrees will not be swept in.

---

## 4. C1: money watch

### 4.1 Read model: `CreatorMoneyService` (`com.influora.service.money`)

```java
@Transactional(readOnly = true) public List<DealMoneyItem> listDeals(String creatorUserId, int limit)
@Transactional(readOnly = true) public DealMoneyDetail getDeal(String creatorUserId, String collaborationId)
@Transactional(readOnly = true) public byte[] tdsLedgerCsv(String creatorUserId, int financialYearStart)   // April to March
```

<!-- PRIYA: five of the eight sources here were wrong. Every one below is now a verified path:line signature (rule 27). -->
Sources, each verified:

| Need | Use | Note |
|---|---|---|
| creator's deals | `collaborationRepository.findByCreatorId(String)` — `CollaborationRepository` L101 | exists as written |
| contracts for a deal | `contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(String)` — `ContractRepository` L32, take element 0 | **not** `ContractService.listForCreator` (L1365), which is `(AuthPrincipal, String dealId)` — a route helper, not a service-internal read |
| milestones for a deal | `milestoneRepository.findByContractIdOrderBySequenceNoAsc(latestContract.getId())` — `PaymentMilestoneRepository` L14 | **exists.** Do **not** add a by-collaboration variant: `amend` never deletes superseded milestones, so a by-collaboration query returns every version's rows and the money view would show phantom milestones |
| hold for a milestone | `escrowHoldRepository.findByMilestoneId(String)` — `EscrowHoldRepository` L110 | **exists**, returns `List<EscrowHold>` (a refunded-then-refunded milestone has several). Pick the one whose status is not `REFUNDED`, newest `created_at` first. Never `findByCollaborationIdAndStatus` — `EscrowHold.collaborationId` is NULL on ordinary brand-funded holds (CR-49/CR-50, Phase B correction 15) |
| payout for a milestone | `payoutRepository.findByMilestoneId(String)` — `PayoutRepository` L45, `Optional<Payout>` | exists as written |
| payouts for a creator | `payoutRepository.findByCreatorUserIdOrderByCreatedAtDesc(String, Pageable)` — L39 | returns **`Page<Payout>`** and **requires a `Pageable`**; used only by the CSV, which pages |
| invoice for a hold | `invoiceRepository.findByEscrowHoldId(String)` — `CampaignServiceInvoiceRepository` L21, `Optional` | exists as written |
| invoice failure marker | **new** `CampaignServiceInvoiceService.hasPendingInvoiceFailure(String escrowHoldId)` | <!-- PRIYA: --> there is **no entity and no repository** for `escrow_invoice_failures`; it is `EntityManager` native SQL inside `CampaignServiceInvoiceService` (L305/349/413/498) and its javadoc at L288 says that is deliberate. Add a public read there modelled on `fetchPendingFailures` (L411-423): `SELECT 1 FROM escrow_invoice_failures WHERE escrow_hold_id = :id AND status = 'PENDING' LIMIT 1`. Do **not** introduce an `@Entity` — it would add a `ddl-auto=validate` surface for a table nobody has validated |
| deliverables for a deal | `deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(String)` | exists as written |

**Payout status classes** (`PayoutStatusClass.classify(Payout p)`): `IN_FLIGHT` when `confirmedAt == null` and status in `PENDING, queued, pending, processing`; `PAID` when status in `processed, MANUAL_PAID`; `FAILED` when status in `reversed, cancelled, rejected`; `UNKNOWN` otherwise (logged). Keep the raw status string beside the class.

**Milestone money state** (`MilestoneMoneyState`): `AWAITING_CONTRACT` (no contract), `AWAITING_SIGNATURES` (contract not fully signed), `AWAITING_FUNDING` (signed, milestone PENDING, no hold), `SECURED` (hold FUNDED), `FROZEN` (hold FROZEN, dispute), `RELEASED` (milestone RELEASED, no payout row yet), `PAYOUT_IN_FLIGHT`, `PAID`, `REFUNDED`. Derived purely from the rows; never from `escrowFunded` on the deal response.

**Release condition** shown as text: `milestone.getReleaseCondition().name()` plus the gate state: `"gate disabled"` when `influora.escrow.release-gate.cutover-instant` is blank, else `"on approval" | "when posted" | "when metrics verified"`.

**TDS** on a payout: `tds_recorded` object `{state: NOT_RECORDED | NONE_DUE | RECORDED, amount, amount_value}` from `payout.getTdsAmount()`: null → NOT_RECORDED; `0.00` → NONE_DUE; else RECORDED.

**Invoice** on a released milestone: `{state: ISSUED | PENDING_RETRY | NOT_APPLICABLE, invoice_number, issued_at}`; PENDING_RETRY when a row exists in `escrow_invoice_failures` for the hold and no invoice.

DTOs in new `com.influora.web.dto.money.CreatorMoneyDtos` (snake_case `@JsonProperty`, `@JsonInclude(NON_NULL)`, formatted strings plus `_value` numerics as in Phase B):

```java
record MilestoneMoney(milestone_id, sequence_no, description, amount, amount_value, currency, state, release_condition, gate_state, escrow_hold_id, funded_at, released_at, payout (PayoutMoney or null), invoice (InvoiceState), deliverable_ids List<String>, next_action String)
record PayoutMoney(payout_id, status_class, raw_status, amount, amount_value, requested_at, settled_at, reference, tds_recorded (TdsRecorded))
record TdsRecorded(state, amount, amount_value)
record InvoiceState(state, invoice_number, issued_at)
record DealMoneyItem(deal_id, brand_name, campaign_title, deal_value, deal_value_value, currency, contract_state, milestones_total, secured_count, awaiting_funding_count, paid_count, next_action, next_action_at)
record DealMoneyDetail(DealMoneyItem summary, List<MilestoneMoney> milestones, List<String> notes)   // notes: plain sentences such as "TDS is shown only when recorded on a payout; Influora does not compute tax."
record TdsLedgerRow(financial_year, payout_id, settled_at, gross_amount, tds_state, tds_amount, reference, deal_id)
```

`next_action` values: `"sign the contract"`, `"waiting for brand to secure funds"`, `"funds secured, start work"`, `"submit deliverable"`, `"waiting for brand review"`, `"mark as posted to release"`, `"released, payout in flight"`, `"paid"`, `"in dispute, funds frozen"`.

### 4.2 Routes: `CreatorMoneyController` `@RequestMapping("/creator/money")`

Creator principal, feature flag (`MeeraCreatorFeatureProperties`, `config/MeeraCreatorFeatureProperties.java:26`; money watch is part of the creator agent surface), no consent needed (it is the creator's own money data, not an AI feature). <!-- PRIYA: Phase B correction 40, verbatim recurrence. --> **`CreatorMeeraController.requireFeatureEnabled()` is `private` (L104) — copy the four-line helper into this controller, do not call it.**

| Route | Response |
|---|---|
| `GET /creator/money/deals?limit=20` | `List<DealMoneyItem>` |
| `GET /creator/money/deals/{dealId}` | `DealMoneyDetail` (404 unless `collaboration.creatorId == principal.userId`) |
| `GET /creator/money/tds-ledger.csv?fy=2026` | `text/csv`, `Content-Disposition: attachment`, columns from `TdsLedgerRow`, plus a first comment line `# TDS shown only where recorded on a payout by Influora admin; this is not a tax certificate.` |

Rate bucket: none needed (GETs, authenticated, cheap). <!-- PRIYA: correct as written — but note for §8.2/§8.4 that a bucket is FIVE edits, not one: a `Pattern` constant (~L87-113), an `if` in `bucketFor`, a `case` in `limitFor` (L405-427), a `case` in `isUserKeyedBucket` (L449-466) and a `@Value` limit field. And `bucketFor` splits on method at L298: the `isGet` block (L300-316) `return null`s at L315, so a GET bucket placed in the POST chain below can never match. -->

### 4.3 Meera tool `get_payment_status` **[needs Phase B]**

Add `get_payment_status` to `CreatorToolName`, tier R, `CreatorToolScopes.SCOPE_LEVEL_0` and `SCOPE_REPRESENTED`, `POST /internal/meera/creator/get_payment_status` on `CreatorMeeraToolController`, executor `GetPaymentStatusExecutor.execute(String creatorUserId, Map<String,Object> input)` with input `deal_id` optional (absent → the list). Returns `DealMoneyDetail` or the list, all numbers pre-formatted. Python: schema in `creator_schemas.py` (`deal_id` string), add to `CREATOR_TOOL_NAMES`, `CREATOR_TOOL_SCHEMAS`, and the frontend `CREATOR_TOOL_NAMES`; card `MoneyStatusCard` in `CreatorToolResultRenderer`.

### 4.4 Meera context keys (no record change)

<!-- PRIYA: buildDealsSummary is `private static Map<String,Object> buildDealsSummary(List<Collaboration>, Locale)` at L382 — it has NO repository access. Every new key here needs milestone/hold/payout data. This is a signature change, not an addition. -->
`MeeraContextService.buildDealsSummary` (**`private static (List<Collaboration>, Locale)`, L382**) must become a **non-static instance method** with `PaymentMilestoneRepository`, `EscrowHoldRepository` and `PayoutRepository` injected (its only caller is `assembleCreatorContext` L252, in the same class, so nothing outside changes). Counts go milestone → `findByMilestoneId`, **never** by collaboration (Phase B correction 15: `EscrowHold.collaborationId` is NULL on ordinary brand-funded holds, so a by-collaboration count reports zero on genuinely funded deals). It then adds to the existing `Map<String,Object>`: `secured_count` (int), `awaiting_funding_count` (int), `payout_in_flight_inr` (formatted string — Java formats, rule 4), `frozen_count` (int), `next_release_hint` (string such as `"Glow Cosmetics milestone 1, 8,000, released when you mark the reel as posted"`). Rule 19 holds: `CreatorContextResponse` stays at 27 components and the Python drift test is untouched, because `deals_summary` is an allow-listed **top-level** key (`assembler.py` L147) and its sub-keys are invisible to that test. `assembler.py` `build_block_b_creator` (L541) renders two more lines after the deals line (L589-599): `- Secure Payments: {secured} secured, {awaiting} awaiting brand funding, {frozen} frozen` and `- Next release: {hint}` when present. The drift test is unaffected (map values, not record components); add a render test in `tests/prompt/test_creator_settings_in_prompt.py`.

<!-- PRIYA: "whichever lands first" is not an instruction anyone can follow. config.py:69 is currently meera-2026.08.10.1; Phase B takes meera-2026.09.10.1. -->
Persona addition. **Phase C sets `PROMPT_VERSION = "meera-2026.09.30.1"` in `influora-ai/app/config.py` L69, in the same diff as the persona edit** — `ci/stale-comment-check.py` requires the bump line to be in the diff whenever prompt content changes, so a persona edit in a separate PR from the bump fails that gate. Text: "Money questions: call get_payment_status first. Say secured, awaiting funding, released, in flight, or paid, in those words. Tax: say only what is recorded on a payout; Influora does not compute TDS or GST, point them to their CA for the rest."

---

## 5. C2: delivery proof

### 5.1 Meta client additions (`InstagramInsightsClient`)

```java
public InstagramMediaResponse getMediaById(String mediaId, String accessToken, String businessAccountId, MetaAuthPath authPath)   // GET /{mediaId}?fields=MEDIA_FIELDS ; 404 or error code 100 with subcode 33 -> MetaNotFoundException (new, extends MetaApiException)
public InstagramStoriesResponse getStories(String igUserId, String accessToken, String businessAccountId, MetaAuthPath authPath)   // GET /{igUserId}/stories?fields=id,media_type,timestamp,permalink  (live stories only, 24h)
public InstagramInsightsResponse getStoryInsights(String storyId, String accessToken, String businessAccountId, MetaAuthPath authPath)   // GET /{storyId}/insights?metric=reach,replies,shares,total_interactions,navigation&breakdown=story_navigation_action_type
```

Reuse `MetaGraphApiClient.get(String path, String accessToken, Class<T> responseType, String businessAccountId, MetaAuthPath authPath)` — **L104**, the five-arg overload (the four-arg one at L92 defaults `FACEBOOK_LOGIN` and must not be used for creator tokens). Story metric names must be checked against `MetaApiProperties.graphApiVersion` (v25.0) on day 1; `navigation` with the breakdown is the v22+ name, `exits`/`taps_forward` are deprecated. If the version rejects a metric, drop it and log, do not fail the poll — and say so on the proof card rather than rendering a blank.

<!-- PRIYA: two blockers here. (a) getMediaById needs a NUMERIC media id; PostUrlIdentifier.extract returns a SHORTCODE and the Graph API has no shortcode lookup, and Deliverable.postId is declared but never written by anything. (b) A new exception subclass changes nothing on its own — translate() has no 404 branch. -->
**`MetaNotFoundException` needs three edits, not one.** (1) The class itself: `MetaApiException` has a `protected MetaApiException(String code, String message, HttpStatus status)` ctor, so `MetaNotFoundException extends MetaApiException` with `("META_OBJECT_NOT_FOUND", msg, HttpStatus.NOT_FOUND)` compiles. (2) **`MetaGraphApiClient.translate` (L143-161) must gain the branch** — today 404 falls straight through to the base `MetaApiException` at L160 and the new subclass would never be thrown. (3) Meta returns **HTTP 400 with `error.code = 100`, `error.error_subcode = 33`** for a deleted or unreachable object at least as often as it returns 404, and `translate` only has `e.getResponseBodyAsString()` to see it — parse that body defensively (try/catch, never let a parse failure change the mapped exception) and map `400 + 100/33` to `MetaNotFoundException` as well.

**`getMediaById` takes the numeric Graph media id, and we only have one after a successful match.** `PostUrlIdentifier.extract` returns an Instagram **shortcode** (`INSTAGRAM_URL` regex, `/p/{code}` and `/reel/{code}` only), `Deliverable.postId` is never written (`applyMarkPosted` sets `postUrl` alone), and the Graph API has no shortcode lookup. So: whenever the snapshot job or `DeliverableVerificationService` matches a media item from the recent-media list, **persist its id via the new `deliverable.applyPlatformMediaId(...)` (2.1)**. `getMediaById` is then usable only on deliverables that carry one; everything else falls back to the `getMedia` list and reports `NOT_FOUND` if the post has aged out of the last 50. The keep-up probe (5.3) skips deliverables with no `platform_media_id` and counts the skips — it does not guess.

### 5.2 Snapshot job

`DeliverableSnapshotJob` (`influora.delivery.snapshots.enabled:false`, cron `0 15 * * * *` hourly, `PT50M/PT1M`): candidates are deliverables with `postUrl` not null, status in `POSTED, METRICS_REPORTED, VERIFIED`, `postedAt` not null, and a missing snapshot for any offset whose window has arrived (`postedAt + 24h <= now` and no 24 row; same for 72 and 168; a window is skipped, never retried, after `postedAt + offset + 12h`). <!-- PRIYA: the fallback as written is impossible (shortcode ≠ media id). Reversed: getMediaById is the FAST path when we already stored an id, the list is the fallback. --> Resolve the media id in this order: (1) `deliverable.getPlatformMediaId()` if set → `getMediaById`; (2) otherwise `PostUrlIdentifier.extract` → `getMedia(igBusinessAccountId, token, RECENT_MEDIA_LIMIT, authPath)` → match by shortcode → **store the id via `applyPlatformMediaId`**; (3) no match → record `NOT_FOUND` for that window and move on. Token, auth path and rate-limit guard exactly as `DeliverableVerificationService.verifyInstagram` does them (L158-215): `metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc`, `metaTokenStorage.getValidCreatorToken(creatorProfileId)`, `metaTokenStorage.getCreatorAuthPath(...).orElse(FACEBOOK_LOGIN)`, `tokenRow.getIgBusinessAccountId()` (never the ULID), `rateLimitTracker.getCurrentUsage(igBusinessAccountId) >= 90`. Then `getMediaInsights`; insert the row with `source = META_API`, `graphApiVersion`. Rate-limit guard identical to `MetricsPollingJob` (skip the creator when `rateLimitTracker.getCurrentUsage(igBusinessAccountId) >= throttle`). For `INSTAGRAM_STORY` deliverables: within 24 hours of `postedAt`, `getStories` and match by timestamp proximity (Stories have no shortcode URL), `getStoryInsights`, one row at `offset_hours = 24` captured as late as possible inside the window (run when `now >= postedAt + 20h`). Per-item try/catch; outcomes counted into `recordSystemEvent`.

Creator-reported fallback: when there is no token, the existing `reportMetrics` (L494) path writes a `CREATOR_REPORTED` snapshot at `offset_hours = 0` in addition to what it does today.

### 5.3 Keep-up-until and the probe

`keep_up_until` is set at contract generation from the deal terms: `postedAt` is unknown then, so store the rule and compute at posting: <!-- PRIYA: the original formula was self-contradictory (null meant both 90 and 365). Collaboration carries TWO fields — usagePerpetual (boolean NOT NULL) and usageMonths (Integer) — set together by applyDealTerms; the entity javadoc at L62-66 says usageMonths NULL means perpetual ONLY when usagePerpetual is true. -->
in `CreatorDeliverableService.markPosted` (L591, after `applyMarkPosted` at L611) set `keepUpUntil` by three branches, in this order: `collaboration.isUsagePerpetual()` → `postedAt.date + 365`; else `collaboration.getUsageMonths() != null` → `postedAt.date + usageMonths × 30`; else → `postedAt.date + 90`. **Never set `keepUpUntil` on a `DeliverableType.INSTAGRAM_STORY`** — a Story is gone from the platform after 24 hours by design, so a keep-up window on one is a scheduled false alarm (§15.3 risk 6).

`DeliverableKeepUpProbeJob` (`influora.delivery.keep-up-probe.enabled:false`, daily 04:00 IST via `@Scheduled(cron = "${...}", zone = "Asia/Kolkata")` — the `CreatorConnectNudgeJob:86` pattern, `@SchedulerLock(name = "DeliverableKeepUpProbeJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")`, batch of 200 per run): candidates are deliverables with `keepUpUntil >= today`, `postUrl` not null, `platformMediaId` not null, `type != INSTAGRAM_STORY`, `removedDetectedAt` null, and `lastProbeAt` older than 24h. <!-- PRIYA: the new finder does not exist — DeliverableRepository has nothing keyed on keepUpUntil, submittedAt or deadline. All new finders take a Pageable (rule 21); none of the existing ones do. --> The finder is new and takes a `Pageable`. Call `getMediaById`. On `MetaNotFoundException`: `applyProbe(now, "NOT_FOUND", false)` on the **first** occurrence and `applyProbe(now, "NOT_FOUND", true)` only on a **second consecutive `NOT_FOUND` at least 24 h after the first**, and only when the token resolved and the rate-limit guard did not trip. <!-- PRIYA: removedDetectedAt is never cleared, so a single false positive brands a live post "removed" forever, and Phase D reads that flag. The same 404/code-100/subcode-33 fires for a scope loss or a temporarily restricted account. Two strikes. --> Then publish `DeliverableRemovedEvent(creatorUserId, null, deliverableId, brandName, keepUpUntil)` (`eventType "deliverable.removed_during_keep_up"`, in-app plus email template `creator.deliverable_removed`, wording "removed or archived", never "deleted"); other outcomes recorded without a flag. Phase D's warning rule reads `removedDetectedAt`.

### 5.4 Proof card

`GET /creator/deliverables/{id}/proof` on `CreatorDeliverableController` → `DeliverableProofResponse{deliverable_id, title, post_url, posted_at, snapshots: [{offset_hours, captured_at, reach, views, likes, comments, saves, shares, total_interactions, source, graph_api_version}], keep_up_until, removed_detected_at, source_line: "Source: Instagram Graph API v25.0 at 2026-10-06 09:15 IST"}` (rendered strings; `source_line` built by Java). <!-- PRIYA: `DealService.sendMessage` CANNOT do this. It is three-arg as the fact sheet says, but L625-628 hard-code `DealMessageKind kind = DealMessageKind.text` behind an explicit Kabir M-1 comment ("only 'text' is client-selectable here; all server-authoritative kinds are set exclusively on internal paths"), sanitize the content, and pass `metadataJson = null` (L636). It can carry neither a `deliverable` kind nor a proof blob. -->
`POST /creator/deliverables/{id}/proof/send` posts a `DealMessage` of kind `deliverable` from the creator with `metadataJson = {"proof": <the response>}` through a **new** public `@Transactional DealService.postDeliverableProofMessage(String collaborationId, String creatorUserId, String deliverableId, Map<String,Object> proof)`, and returns the message id. Not `sendMessage` — that method is deliberately locked to `kind = text` with null metadata (Kabir M-1, anti-spoof), and widening it would hand every client the ability to forge `payment`/`contract`/`proposal` cards. The new method lives **inside `DealService`** because the two helpers it needs are private there (rule 28), and mirrors `appendLifecycleRow` (L1029-1033):

```java
DealMessage m = DealMessage.create(
        Ulids.newUlid(), collaborationId, DealMessageKind.deliverable,
        creatorUserId, DealSenderType.creator,
        TextSanitizer.sanitizePlainText(summaryLine), objectMapper.writeValueAsString(Map.of("proof", proof)));
dealMessageRepository.save(m);
publishToStream(collaborationId, toMessageResponse(m));   // both private, same class; publishToStream is afterCommit-registered
```

`DealMessage.create(id, collaborationId, kind, senderId, senderType, content, metadataJson)` — 7 args, verified at `DealMessage.java:86-105`. `DealMessageKind.deliverable` and `DealSenderType.creator` both exist. Idempotent per `(deliverable, latest snapshot captured_at)` by checking the last proof message's metadata. Brand thread renders it as a card (`ProofCard` in `brand-chat.tsx` where `deliverable` kind messages render).

Meera tool `get_delivery_proof` **[needs Phase B]**: tier R, input `deliverable_id` or `deal_id`, returns the proof response(s); the chat card offers "Send to brand" which calls the POST above.

---

## 6. C3: channels

### 6.1 Provider choice

WhatsApp goes through the **Meta WhatsApp Cloud API** (`POST https://graph.facebook.com/{version}/{phone_number_id}/messages`), not a BSP. Reasons: Influora already has a verified Meta app and business, the Cloud API has no BSP margin, template and webhook handling reuse the Graph client patterns in `integration/meta`, and MSG91's non-SMTP APIs returned 401 in this codebase's history. Keep the Java side provider-neutral behind `WhatsAppSender` so a BSP can be swapped in.

### 6.2 Config

```yaml
influora:
  whatsapp:
    enabled: ${WHATSAPP_ENABLED:false}
    phone-number-id: ${WHATSAPP_PHONE_NUMBER_ID:}
    waba-id: ${WHATSAPP_WABA_ID:}
    access-token: ${WHATSAPP_ACCESS_TOKEN:}               # system-user token, never a user token
    app-secret: ${WHATSAPP_APP_SECRET:}                   # for X-Hub-Signature-256
    webhook-verify-token: ${WHATSAPP_WEBHOOK_VERIFY_TOKEN:}
    graph-api-version: ${WHATSAPP_GRAPH_API_VERSION:v25.0}
    template-namespace: ${WHATSAPP_TEMPLATE_NAMESPACE:}
    daily-send-ceiling: ${WHATSAPP_DAILY_SEND_CEILING:1000}
  push:
    enabled: ${PUSH_ENABLED:false}
    vapid-public-key: ${PUSH_VAPID_PUBLIC_KEY:}
    vapid-private-key: ${PUSH_VAPID_PRIVATE_KEY:}
    vapid-subject: ${PUSH_VAPID_SUBJECT:mailto:support@influora.in}
```

`WhatsAppProperties` and `PushProperties` as `@ConfigurationProperties` classes registered in `InfluoraApiApplication` (append after `ConversionWebhookProperties.class`, add the comma), env.example, both composes. Frontend: `VITE_PUSH_VAPID_PUBLIC_KEY`.

### 6.3 Templates (utility category, pre-approved in the WABA before day 4)

| Name | Category | Body (params in braces) | Used by |
|---|---|---|---|
| `influora_new_brief` | UTILITY | "Hi {{1}}, a new brief from {{2}} is waiting on Influora. Budget: {{3}}. Reply 1 to have Meera draft a reply, or open: {{4}}" | `proposal.*` to creator |
| `influora_funds_secured` | UTILITY | "Hi {{1}}, {{2}} has secured {{3}} for your deal. You can start work. Details: {{4}}" | `EscrowFundedEvent` |
| `influora_payout_sent` | UTILITY | "Hi {{1}}, {{2}} has been released for {{3}}. It reaches your bank in 1 to 3 working days. Details: {{4}}" | `PayoutReleasedEvent` |
| `influora_approval_late` | UTILITY | "Hi {{1}}, {{2}} has not reviewed your deliverable for {{3}} days. Meera has nudged them. Track: {{4}}" | C4 (creator copy) and brand copy `influora_review_overdue_brand` |
| `influora_deadline_tomorrow` | UTILITY | "Hi {{1}}, your {{2}} for {{3}} is due tomorrow. Open: {{4}}" | C4 |
| `influora_verify_code` | AUTHENTICATION | Meta's fixed authentication template with the OTP button | phone verification |
| `influora_draft_ready` | UTILITY | "Hi {{1}}, Meera drafted a reply to {{2}}. Reply 1 to send it as is, 2 to edit on Influora, or ignore. Preview: {{3}}" | Phase B drafts, approve-by-reply |

`WhatsAppTemplates` enum with `name`, `paramCount`, `category`; params are rendered by Java, first name only, amounts formatted, links are `influora.web-base-url` deep links.

### 6.4 Sender and webhook

`WhatsAppSender` interface: `SendResult sendTemplate(String toE164, WhatsAppTemplates template, List<String> params)`, `SendResult sendText(String toE164, String text)` (only inside a session window), `boolean isConfigured()`. `MetaWhatsAppCloudClient` implements it with `RestClient` built lazily with explicit connect 5 s and read 15 s timeouts (per Phase E correction 42), `Authorization: Bearer {access-token}`, body `{"messaging_product":"whatsapp","to":..,"type":"template","template":{"name":..,"language":{"code":"en"},"components":[{"type":"body","parameters":[{"type":"text","text":..}]}]}}`. Never throws; dev mock like `Msg91EmailClient`.

`WhatsAppWebhookController` `@RequestMapping("/webhooks/whatsapp")`. In `SecurityConfig` add two **method-scoped, exact-path** permits — `.requestMatchers(HttpMethod.GET, "/webhooks/whatsapp").permitAll()` and the `POST` equivalent — next to the Meta ones at L134-137 and above `/admin/**` (L207) and `/creator/**` (L227). <!-- PRIYA: verified the house rule at SecurityConfig L132-133: "named explicitly rather than /webhooks/meta/** so a future Meta endpoint cannot inherit permitAll by accident". Never a /** matcher here. --> Never a `/**` matcher:
- `GET` handles Meta's verification handshake (`hub.mode=subscribe`, `hub.verify_token` equals config, echo `hub.challenge`).
- `POST`: <!-- PRIYA: `ContentCachingRequestWrapper` does NOT work here — it caches bytes only as downstream code reads them and needs a filter registration, so it gives you nothing before Jackson. And the house convention (`@RequestBody String rawPayload` then verify — RazorpayWebhookController L88-93, ShopifyWebhookController L106-118) is right on ordering but buffers an unbounded body first. Exact mechanism below. --> the handler takes **`HttpServletRequest`, not `@RequestBody`**, and does, in this order:
  1. `request.getContentLengthLong() > 262_144` → 413, no body read. (Belt: the real control is `client_max_body_size 256k` on the nginx `/webhooks/whatsapp` location, because a handler bug cannot bypass it.)
  2. `byte[] raw = new LimitedInputStream(request.getInputStream(), 262_144).readAllBytes()` — `com.influora.common.LimitedInputStream` throws `IOException` past the cap (`LimitedInputStream.java:52`); catch it and return 413.
  3. Verify `X-Hub-Signature-256` = `sha256=` + HMAC-SHA256(app-secret, `raw`) with `MessageDigest.isEqual`. Failure → **401** plus `auditLogService.recordAuthRejection(null, "whatsapp.webhook", reasonCode, null)` — signature verified at `AuditLogService.java:71`, four args, exists.
  4. Only now `objectMapper.readTree(raw)`.

  Then parse `entry[].changes[].value`: `statuses[]` (sent, delivered, read, failed → update `outbound_channel_messages` by `provider_message_id`), `messages[]` (inbound text). Return 200 within 200 ms. <!-- PRIYA: the spec offered a choice and then chose; recording the choice as final. --> **Persist to `inbound_whatsapp_messages` (2.11) and process in `ChannelSendJob`'s sibling pass — not `@Async` after the response.** `@Async` is per-replica and loses the message on a restart mid-flight; the table's `uk_iwm_wa_message` is the durable replay arbiter and the only thing that stops two replicas behind the LB both acting on the same Meta retry. Inserting it: `saveAndFlush` **inside** the try, so a `DataIntegrityViolationException` on the unique key is actually catchable (a plain `save()` on a managed entity defers the INSERT to commit, outside the catch — the recorded trap).

Inbound processing (`InboundWhatsAppService`, public `@Transactional` methods):
1. Resolve the consent by `phone_hash`; none → ignore. <!-- PRIYA: Meta delivers the sender as `wa_id` = "91XXXXXXXXXX" (no '+'). `IndianPhoneUtils.normalize` (common/IndianPhoneUtils.java:32) strips a leading "91" from a 12-digit string, so run the inbound wa_id through `UserPhoneService.normalizeAndValidate` and blind-index the 10-digit result — the same form written at opt-in. Hashing the raw wa_id would never match. -->
2. Text matches `^\s*(stop|unsubscribe|band karo|बंद)\s*$` (case-insensitive) → consent STOPPED, `stopped_at`, `active_marker = NULL`, reply with one confirmation via `sendText` if within a window else nothing; audit count.
3. Text `1` or `approve` → find the latest `outbound_channel_messages` row for this user with `pending_action_json` and `sent_at >= now - 24h` and not yet acted; run the pending action: `APPROVE_DRAFT` → Phase B's approve path (`CreatorMeeraDraftService.approve(userId, draftId, originalText, idempotencyKey = "wa:" + waMessageId)`), which sends the reply and records the approval; mark acted; reply "Sent." with `sendText`. Text `2` → reply with the deep link. Anything else inside a window → reply once "Reply 1 to send Meera's draft, 2 to edit, or STOP to stop these messages." and open `session_window_until = now + 24h` on the row. **[needs Phase B]** for the approve action; without it, `1` replies with the deep link.

### 6.5 Phone verification and consent

`PhoneVerificationService` (public `@Transactional`): `startVerification(String userId, String rawPhone)` → normalise via `UserPhoneService.normalizeAndValidate`, E.164 `+91` prefix, refuse if `isTaken` by another user, create `phone_otp_challenges` (6-digit code, SHA-256 with the challenge id as salt, 10-minute TTL, 5 attempts, one active challenge per user), send `influora_verify_code` via `WhatsAppSender`, rate bucket `phone-otp` (`^/me/phone/otp$` POST, user-keyed, 5 per window); `confirmVerification(String userId, String code)` → on success set the verified flag, set `user.phoneNumber` via `UserPhoneService.applyPhone(user, raw)` (L103; it re-normalizes, re-checks `isTaken` against **other** users only, and uses `saveAndFlush` so a raced `UNIQUE(phone_number)` violation surfaces as `PHONE_ALREADY_EXISTS`/409 rather than a 500), and create or reactivate the `ChannelConsent` WHATSAPP row as ACTIVE with `consent_version`, `consented_at`, `verified_at`, `phone_e164_enc`, `phone_hash`.

<!-- PRIYA: `User.phoneVerified` has NO setter and no mutator — a getter at L194 and two `= false` initialisers at L108/L134. `user.phoneVerified = true` does not compile from another package. -->
**`User` needs a new mutator.** Add `public void markPhoneVerified()` next to `setPhoneNumber` (L167), following that method's PHONE-0829 javadoc convention, and a `clearPhoneVerified()` invoked from `setPhoneNumber(null)` so clearing a number cannot leave `phone_verified = true` pointing at nothing. This is the first writer of that flag in the codebase — `UserDtos.java:32` already exposes it on the wire, reading `false` for everyone today. The consent text is captured at the same time by a required `accepted_consent_version` field in the confirm request.

Routes on a new `PhoneChannelController` `@RequestMapping("/me/phone")` (any authenticated user; brands may opt in for nudges too): `POST /me/phone/otp {phone}` → `{challenge_id, expires_at}`; `POST /me/phone/otp/confirm {challenge_id, code, accepted_consent_version}` → `{phone_verified, whatsapp_consent_status}`; `DELETE /me/phone/whatsapp-consent` → REVOKED; `GET /me/channels` → `{whatsapp: {status, phone_masked, consent_version}, push: {subscriptions: n}, prefs: [...]}`; `PUT /me/channels/prefs {category, channel, enabled}`.

### 6.6 Push

<!-- PRIYA: hand-wave resolved. The pom has NO BouncyCastle at all (Spring Boot 3.3.5, Java 21 — verified). There is nothing to "align" with; adding jdk18on alongside the jdk15on web-push drags in would put two artifacts' classes in the same packages. -->
Dependency `nl.martijndwars:web-push:5.1.1`, **version-pinned, with its transitive BouncyCastle excluded and exactly one BC artifact (`org.bouncycastle:bcprov-jdk18on` + `bcpkix-jdk18on`) declared explicitly at a single pinned version.** There is no BouncyCastle in `pom.xml` today. Two day-0 checks, both blocking for day 4: (a) `web-push` also pulls an async HTTP client — confirm nothing in it collides with the `httpclient5` 5.3.1 / Netty already on the classpath; (b) **this repo builds offline (`mvn -o`)** — the artifact and its whole transitive tree must be in the local repository before day 4 or the build fails outright with no code written. If either check fails, ship C3 with WhatsApp only and defer push; the two are independent and nothing else in this phase depends on push (§15.3 risk 9). `WebPushSender.send(PushSubscription sub, String title, String body, String url)` → on 404 or 410 mark the subscription EXPIRED. Routes on `PushSubscriptionController` `@RequestMapping("/me/push")`: `POST /me/push/subscriptions {endpoint, keys: {p256dh, auth}, user_agent}` (upsert by endpoint hash), `DELETE /me/push/subscriptions {endpoint}`. Frontend: `public/sw.js` (push event shows the notification, click opens `url`), `public/manifest.webmanifest`, `<link rel="manifest">` in `index.html`, `src/lib/push.ts` (`subscribeToPush(vapidPublicKey)`, `unsubscribe()`), a switch in creator settings and brand settings. iOS requires the site to be installed to the home screen; the switch explains that.

### 6.7 Channel router

`ChannelRouter.fanOut(NotificationEvent event, String title, String body, String link, Map<String,Object> templateParams)` is called from `NotificationService.notify` and `notifyInApp` **after** the in-app row is created and email is queued, inside the same transaction, wrapped in try/catch so a channel failure never rolls back the notification. It:
1. Maps `event.eventType()` to a `NotificationCategory`; no category → return.
2. Looks up `ChannelConsent` WHATSAPP ACTIVE and `notification_channel_prefs` for the user and category; if enabled and a `WhatsAppTemplates` entry maps to this event type, insert an `outbound_channel_messages` QUEUED row with idempotency `event.eventType() + ":" + event.entityId() + ":" + userId + ":whatsapp"`.
3. Same for PUSH with ACTIVE subscriptions.
`ChannelSendJob` (`influora.channels.send.enabled:false`, `fixedDelay = 10000`, `PT2M/PT5S`) sends QUEUED rows under the daily ceiling, marks SENT with `provider_message_id`, FAILED with `failure_code`. Only the events in the template table go to WhatsApp; everything else stays in-app plus email as today.

---

## 7. C4: brand nudges

### 7.1 Events (package `com.influora.service.notification.event`, permits, listener, templates)

- `DeliverableReviewOverdueEvent(String userId /* brand campaign creator */, String workspaceId, String entityId /* deliverable id */, String creatorName, String campaignTitle, int daysWaiting, String dealUrl)` → `"deal.review_overdue"`, template `brand.review_overdue` (subject "{creator} is waiting on your review", CTA "Review now", var `deal_url`).
- `MilestoneUnfundedEvent(String userId, String workspaceId, String entityId /* milestone id */, String creatorName, String amount, int daysSinceSigning, String dealUrl)` → `"deal.milestone_unfunded"`, template `brand.milestone_unfunded` (subject "Secure the funds to start work with {creator}").
- `DeliverableDeadlineTomorrowEvent(String userId /* creator */, String workspaceId null, String entityId /* deliverable id */, String brandName, String deliverableTitle, String dealUrl)` → `"deadline.deliverable_tomorrow"`, template `creator.deadline_tomorrow`.
- `CreatorReviewOverdueInfoEvent(String userId /* creator */, ...)` → `"deal.review_overdue_creator"`, in-app plus WhatsApp `influora_approval_late`, no email. <!-- PRIYA: the rationale here is inverted. `IN_APP_ONLY_EVENTS` cannot block WhatsApp: `ChannelRouter.fanOut` runs at the END of `notify`, after all three branches (NotificationService L78-93), so an IN_APP_ONLY member still reaches the router. A second set would be dead weight with two places to forget. --> **Add it to the existing `IN_APP_ONLY_EVENTS` (`NotificationService` L44-45). Do not add a `NO_EMAIL_EVENTS` set.**

<!-- PRIYA: verified against NotificationEvent.java. The sealed interface's permits list ends with `CreatorNotConnectedEvent {` — no trailing comma, rule 22 is correct. Its four required accessors are eventType(), userId(), workspaceId(), entityId(); workspaceId may be null (notifications.workspace_id is nullable), which the creator-side events rely on. -->
Every event above must also be registered in `NotificationEvent`'s `permits` list (34 entries today, last is `CreatorNotConnectedEvent`), handled in `NotificationListener`, and given an `EmailTemplateRegistry` `Spec` — verified `private record Spec(subject, heading, bodyTemplate, ctaLabel, ctaUrlVar, htmlBodyTemplate)` with a **5-arg convenience ctor**, in a **package-private final** class, so rule 22 is exactly right. Note the default in `notify`: an `eventType` in **neither** `EMAIL_ONLY_EVENTS` nor `IN_APP_ONLY_EVENTS` gets **both** channels, and `isUnsubscribed` returns false when no `email_preferences` row exists — so every new event emails on first fire unless you decide otherwise explicitly. `NotificationEventPermitsTest` must assert channel-set membership per event, not only that a handler exists.

### 7.2 `DealNudgeJob` (`influora.deals.nudges.enabled:false`, cron `0 5 * * * *` hourly, `PT50M/PT1M`)

Stages, each idempotent through `deal_nudges` (`uk_dn_entity_stage_recipient`):

| Stage | Condition | Recipients | Also |
|---|---|---|---|
| `REVIEW_OVERDUE_D3` | deliverable status `SUBMITTED` or `RESUBMITTED` with `submittedAt <= now - 72h` and no dispute | brand `campaign.createdBy` (in-app, email, WhatsApp if opted in) | system `DealMessage` in the thread: "Meera: this deliverable has been waiting for review for 3 days." |
| `REVIEW_OVERDUE_D7` | same, 7 days | brand creator plus every workspace OWNER — <!-- PRIYA: there is no `findByWorkspaceIdAndRoleAndActiveTrue`. --> `workspaceMemberRepository.findByWorkspaceIdInAndRoleAndActiveTrue(List.of(workspaceId), MemberRole.OWNER)` (L57) | creator gets `CreatorReviewOverdueInfoEvent` |
| `UNFUNDED_D7` | contract status `ACTIVE` (fully signed) and a milestone `PENDING` with no hold, and <!-- PRIYA: `Contract.effectiveDate` is NEVER WRITTEN — the only reference in the codebase is the builder method at Contract.java:309, zero call sites. It is NULL on every row, so an `effectiveDate ?: signature` fallback silently degrades to signature-only anyway. --> `max(brandSignedAt, creatorSignedAt) <= now - 7d` (**not** `effectiveDate`, which is null on every row) | brand creator | system message |
| `UNFUNDED_D14` | 14 days | brand creator plus OWNERs | |
| `DEADLINE_T1` | deliverable `deadline == tomorrow` (IST), status in `PENDING, DRAFT, REVISION_REQUESTED` | creator | |

System messages use a new public `@Transactional DealService.postSystemMessage(String collaborationId, String text, Map<String,Object> metadata)` that creates `DealMessage.create(id, collaborationId, DealMessageKind.system, "system", DealSenderType.system, TextSanitizer.sanitizePlainText(text), metadataJson)` — 7 args, verified at `DealMessage.java:86-105` — and publishes it. <!-- PRIYA: the idiom to copy is NOT `sendMessage` (L618-700, which is user-facing and locked to kind=text). It is `appendLifecycleRow` at L1029-1033. Also: `appendSystemMessage` (L1971) takes no metadata param, so this method builds its own row rather than calling it. --> The publish idiom is `appendLifecycleRow`'s, at **L1029-1033**: `publishToStream(collaborationId, toMessageResponse(message))`. Both helpers and `publishToStream`'s afterCommit registration are `private` in `DealService`, so this method must live **in that class** (rule 28); `appendSystemMessage` (L1971) is close but has no metadata parameter, so do not try to reuse it. Metadata `{"agent":"meera","nudge_stage":"REVIEW_OVERDUE_D3"}`.

The job itself: `@SchedulerLock(name = "DealNudgeJob", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")` — no collision with the 20 existing lock names (all checked). Deadline arithmetic is **IST**: `@Scheduled(cron = ..., zone = "Asia/Kolkata")` and `LocalDate.now(ZoneId.of("Asia/Kolkata"))` for "tomorrow", never the JVM default. Candidate finders keyed on `submittedAt` and `deadline` do not exist on `DeliverableRepository` and are new; all take a `Pageable` (rule 21).

Metrics: `GET /admin/creator-agent/nudge-metrics` (new route on `AdminCreatorAgentController`, bare DTO like its siblings) returning counts per stage for 7 and 30 days, median hours from submit to review, milestones unfunded more than 7 days (count and rupees).

### 7.3 Auto-release after brand silence (flag off, Swapnil decision 5)

`influora.deals.auto-approve-after-days:0` (0 = disabled). When > 0, `DealNudgeJob` adds stage `AUTO_APPROVE`: a deliverable `SUBMITTED` or `RESUBMITTED` for that many days, after `REVIEW_OVERDUE_D7` was sent, with no dispute, is approved through a new public `@Transactional BrandDeliverableService.autoApproveAfterSilence(String deliverableId)`. <!-- PRIYA: it cannot "reuse the approve body". `approve` opens with `brandContext.requireBrandWorkspace(principal)` (L110) and re-resolves the row TWICE off that workspace under PESSIMISTIC_WRITE (the F-0580 double-approve fix). A job has no principal. --> It is a **sibling**, not a reuse: resolve the workspace from the deliverable's collaboration → campaign, then take the same `findByIdAndWorkspaceId` `PESSIMISTIC_WRITE` lock, re-read the status inside the lock, `canReview` check, `requireNotCancelled`, `applyApprove`, and `escrowService.tryReleaseOnApproval` in the same order `approve` uses (L109-190) — the ordering there is load-bearing (the escrow attempt runs *ahead* of the `REQUIRES_NEW` history writes precisely so a failed release cannot leave permanent history asserting an approval that rolled back). Then records `deal_nudges` stage `AUTO_APPROVED`, posts a system message, and notifies both parties. Release then follows the existing `tryReleaseOnApproval` path and the release gate. Until Swapnil rules, the flag stays 0 and Meera says "escalates to support on day 7" (persona line).

---

## 8. C5: revisions and disputes

### 8.1 Revisions default

Covered by 3.2. Additionally `DealService.createProposal` and `doCounter` already default `maxRevisions` to 2 through `applyDealTermsIfPresent`; the brand proposal form shows the value; nothing else to build.

### 8.2 Dispute evidence and responses

`DisputeEvidenceService` (public `@Transactional`): `addText(AuthPrincipal, String disputeId, String text)`, `addLink(AuthPrincipal, String disputeId, String url)`, `addFile(AuthPrincipal, String disputeId, MultipartFile file)`, `list(AuthPrincipal, String disputeId)`. The upload path mirrors `CreatorDeliverableService.uploadProof` (L289-322) step for step: `r2StorageService.isAvailable()` guard, size cap (25 MB), MIME whitelist (image/jpeg, image/png, image/webp, video/mp4, application/pdf), then the scan, then a `LimitedInputStream`-wrapped `r2StorageService.putStream(key, in, len, contentType)` under key `disputes/{disputeId}/{ulid}.{ext}`, then `presignGet` for the read URL.

<!-- PRIYA: two wrong references. The injectable scanner is not in integration/clamav (that holds only the low-level ClamAvClient), and the bound implementation is a stub in most environments. -->
**Scanner:** inject `com.influora.service.security.MalwareScanService` and call `requireClean(file, "dispute-evidence")`. `integration/clamav` holds only `ClamAvClient`. **The bound bean is `NoOpMalwareScanService` unless ClamAV is configured** — its own javadoc calls it "the accepted-risk stub", and MIME sniff plus size cap are the active controls. Do not describe these files as scanned in user copy, and get Kabir's sign-off on that binding for a path where a brand downloads a counterparty's file out of a ZIP (§15.3 risk 7).

<!-- PRIYA: `DisputeService.requireOwnedCollaboration` is PRIVATE, three-arg `(AuthPrincipal, UserType role, String dealId)`, at L561. L109-147 is `openDispute`, a different method. -->
**Party derivation:** `DisputeService.requireOwnedCollaboration` is `private`, **three-arg** `(AuthPrincipal, UserType role, String dealId)`, at **L561** — not L109-147, which is `openDispute`. It cannot be called from a new class as written (rule 28). Either promote it to `public` in the same commit, or put the two evidence write methods on `DisputeService` itself. Promoting is preferable: one ownership check, one place to audit. Adding evidence moves an `OPEN` dispute to `UNDER_REVIEW` (first time either party responds; `UNDER_REVIEW` is currently unreachable), sets `collaboration.evidenceHoldUntil`, and notifies the other party (`DisputeEvidenceAddedEvent` → `"dispute.evidence_added"`, in-app plus email).

Routes: `POST /disputes/{id}/evidence/text`, `POST /disputes/{id}/evidence/link`, `POST /disputes/{id}/evidence/file` (multipart), `GET /disputes/{id}/evidence` on a new `DisputeEvidenceController` `@RequestMapping("/disputes")` (brand or creator principal; the service checks ownership). Admin: `GET /admin/disputes/{id}/evidence` on `AdminDisputeController`. Rate bucket `dispute-evidence` (`^/disputes/[^/]+/evidence/(text|link|file)$`, user-keyed, 30).

Opening a dispute stays gated on funded escrow (`NO_FUNDED_ESCROW`); that is a policy, not a Phase C change.

### 8.3 Dispute mode for Meera **[needs Phase B]**

When `collaboration.getStatus() == DISPUTED`: `RoutineReplyService.queue` refuses with `DEAL_IN_DISPUTE`; `DraftReplyExecutor` allows only kind `REPLY` and injects a system note into the draft context: "This deal is in dispute. Funds are frozen, not lost. Do not admit fault, apologise, or offer a free reshoot; state facts and dates." `DealRiskService.evaluateDeal` adds an INFO flag `IN_DISPUTE` with the evidence-pack action. Persona: "In a dispute you go silent in the brand thread: no drafts to the brand except factual replies the creator asked for. Explain that secured funds are frozen, not lost. Never admit fault, apologise, or offer a free reshoot."

### 8.4 Evidence pack

`EvidencePackService.build(AuthPrincipal, String collaborationId) -> byte[]` (ZIP) for the creator or the brand of that collaboration, and admins. Contents:

- `manifest.json`: generated_at, generated_by, collaboration id, campaign, both parties (names and ids), contract id and status, signature timestamps, `Contract.termsText` (the real terms; `termsJson` is a hash, do not include it as terms), milestones with money state (4.1), payouts with `tds_recorded`, invoices or their failure marker, deliverables with all timestamps, post URLs, snapshots, probe outcomes, disputes and evidence entries, offer history (Phase B table, if present), audit rows for this collaboration filtered to shapes only (`AuditLogService` detail is already PII-free by rule).
- `messages.json`: every `DealMessage` on the thread, both parties, with metadata stripped of `agent` keys for the brand copy (2.8 of Phase B).
- `summary.html`: a printable one-page summary (the browser prints to PDF; no server PDF library is added).
- `files/`: deliverable files still present in R2 (`filesJson` keys) and dispute evidence files, streamed from R2; missing files listed in `manifest.json.missing_files` with the reason (`cleaned_up_after_retention`).

<!-- PRIYA: "streamed from R2" has no implementation. R2StorageService's ENTIRE public surface is isAvailable / publicUrl / presignPut / PresignResult / putBytes / putStream / presignGet / deleteObject. There is no read. -->
**`R2StorageService` needs a new read method: `public InputStream getObjectStream(String objectKey)`** (S3 `GetObject`, same client and bucket the other methods use, returning the SDK's `ResponseInputStream` so the ZIP can stream rather than buffer; `NoSuchKey` → `Optional.empty()`/null so a cleaned-up file lands in `missing_files` rather than failing the pack). Do **not** implement this by having the server fetch its own `presignGet` URL over HTTP — that introduces an outbound-fetch path this codebase deliberately does not have, and the SSRF review that comes with it. Stream each entry into the `ZipOutputStream` with a bounded buffer; never `readAllBytes` a 500 MB video into heap.

Route `GET /deals/{id}/evidence-pack` (`DealController`, both roles, `application/zip`, streamed, rate bucket `evidence-pack` user-keyed 5 per window — <!-- PRIYA: this is a GET, so its matcher goes INSIDE AuthRateLimitFilter.bucketFor's `isGet` block (L300-316, which `return null`s at L315); in the POST chain below it can never match. Five edit points per bucket: Pattern constant, the `if`, `limitFor` L405-427, `isUserKeyedBucket` L449-466, and the `@Value` field. --> see the note in 4.2 for the five edit points, and place this one in the `isGet` branch). Requesting a pack sets `collaboration.evidenceHoldUntil = max(existing, today + 90)`.

### 8.5 Cleanup job guard

`DeliverableCleanupJob` (dry-run by default) gains two skips: `collaboration.evidenceHoldUntil >= today` and any deliverable of the collaboration with `keepUpUntil >= today`. Keep the existing active-dispute and unreleased-escrow guards.

---

## 9. Frontend

### 9.1 `src/lib/api.ts`

Types mirroring the Java records: `MilestoneMoney`, `PayoutMoney`, `TdsRecorded`, `InvoiceState`, `DealMoneyItem`, `DealMoneyDetail`, `DeliverableProofResponse`, `ChannelsResponse`, `PhoneOtpStart`, `PhoneOtpConfirm`, `DisputeEvidenceItem`, `NudgeMetrics` (admin). Namespaces `creatorMoney` (`listDeals`, `getDeal`, `tdsLedgerUrl(fy)` returning the URL for a download link), `deliveryProof` (`get(id)`, `send(id)`), `channels` (`get`, `startPhoneOtp`, `confirmPhoneOtp`, `revokeWhatsApp`, `setPref`, `subscribePush`, `unsubscribePush`), `disputeEvidence` (`list`, `addText`, `addLink`, `addFile`), `evidencePack.url(dealId)`. Register in the `api` object. Type fixes from 3.3.

### 9.2 Creator surfaces

- `src/pages/creator-wallet.tsx`: a new "Deals" tab before History: `DealMoneyList` with per-deal rows and an expandable milestone timeline (`MilestoneTimeline` component: state chips SECURED, AWAITING FUNDING, FROZEN, RELEASED, PAID with the semantic stripe colours; TDS shown as "not recorded", "none due", or the amount; invoice as "issued", "pending", or nothing). "Download TDS ledger" link with the disclaimer sentence.
- Deal room (`creator-chat.tsx`): a money strip under the deal header from `getDeal`, with `next_action`.
- Deliverable lifecycle panel: "Proof" section showing snapshots and "Send proof to brand"; "Keep up until {date}" line; a calm "removed or archived" state when `removed_detected_at` is set.
- `creator-settings.tsx`: replace the local-only notification switches (L62-83) with the real `channels` section: WhatsApp block (phone entry, OTP dialog, consent text with version, status, STOP note), push switch, per-category matrix for WhatsApp and push, email opt-out unchanged.
- `creator-disputes.tsx`: evidence list and add text, link, file; "Download evidence pack".
- Meera cards **[needs Phase B]**: `MoneyStatusCard`, `ProofCard` in `CreatorToolResultRenderer`.

### 9.3 Brand surfaces

- Deal room thread: render system nudge messages with a distinct "Meera" system style; `ProofCard` for proof messages.
- `brand-settings.tsx`: WhatsApp and push opt-in blocks (same components as creator).
- `brand-disputes.tsx`: evidence list and add.
- Admin: `NudgeMetricsPanel` on `CreatorAgentBaselinesPage` (reads the new bare-DTO route).

### 9.4 Service worker and manifest

`public/sw.js`, `public/manifest.webmanifest`, `index.html` manifest link, `src/lib/push.ts`, registration on app load guarded by `'serviceWorker' in navigator`. Vitest cannot run the service worker; test `push.ts` with a mocked `navigator.serviceWorker`.

<!-- PRIYA: four facts the spec assumed. -->
Four verified constraints:
- **The icons do not exist.** `public/` holds `icon.svg`, `icon-light-32x32.png`, `icon-dark-32x32.png`, `apple-icon.png` — no 192px or 512px PNG. Chrome requires both for installability, and iOS requires the home-screen install that gates iOS push. New assets are part of this step, not a reference to existing ones.
- **`scripts/prerender.mjs` overwrites `dist/index.html`** with the rendered React output at build time (`index.html`'s own comment says so). Verify the `<link rel="manifest">` survives prerender, or the manifest is present in dev and absent in production.
- **`.proof-os/gates/F-SEO-marketing-surface.sh` gates `index.html`.** Re-run it after the edit.
- **CSP is already compatible** — `public/_headers` sets `worker-src 'self' blob:` (service worker allowed) and has no `manifest-src`, so the manifest inherits `default-src 'self'`. No CSP change needed. Do add `Cache-Control: no-cache` for `/sw.js` in `_headers` so a stale worker cannot pin itself.

### 9.5 Tests

`DealMoneyList.test.tsx` (three TDS states render as words, FROZEN chip), `MilestoneTimeline.test.tsx`, `deliverable-proof.test.tsx` (send button disabled without snapshots; removed state), `channels-settings.test.tsx` (OTP flow, consent version sent, STOP note), `dispute-evidence.test.tsx`, `enum-drift.test.ts` (3.3), `push.test.ts`.

---

## 10. Backend tests

| Test | Covers |
|---|---|
| `MilestoneAssignmentTest` | the five cases in 3.1 |
| `ContractServiceMilestoneLinkTest` | generate sets `milestone_id` (insertion after L327, re-read path, and the already-has-deliverables early return); amend reassigns **and skips a deliverable whose current milestone's hold is FUNDED**; approve now reaches `releaseInternal` (mock `EscrowService`) |
| `MilestoneLinkBackfillJobTest` | idempotent, skips linked, skips FUNDED-hold milestones, counts, `Pageable` bound honoured |
| `DeliverableVerificationServiceUpsertTest` | <!-- PRIYA: characterization, not new behaviour — persistVerified L268-297 is already an upsert. --> two deliverables on one milestone refresh **one** metric row. This asserts existing behaviour that C0 makes reachable; no production code changes for it. |
| `CreatorDeliverableServiceMaxRevisionsTest` | list item carries the collaboration cap on **both** paths — `listForCollaboration` (single) and `listForCollaborations` (batch map), the second being the one a signature change silently breaks |
| `EscrowReleaseGateScopeTest` | <!-- PRIYA: new — pins §15.3 risk 2 so the next reader cannot mistake C0 for staged release. --> with the cutover **set**, approving deliverable 1 of a two-deliverable collaboration does **not** release milestone 1; and a post-cutover milestone whose collaboration has zero deliverables throws `RELEASE_CONDITION_NOT_MET` |
| `CreatorMoneyServiceTest` | every `MilestoneMoneyState`, payout classes incl. UNKNOWN, three TDS states, invoice PENDING_RETRY, ownership 404, CSV header line |
| `InstagramInsightsClientPhaseCTest` | three new methods build the right paths; not-found mapping |
| `DeliverableSnapshotJobTest` | windows, skip after grace, unique idempotency, story branch, rate-limit skip |
| `DeliverableKeepUpProbeJobTest` | <!-- PRIYA: two-strike rule, and the Story exclusion. --> a **single** `NOT_FOUND` does **not** set `removedDetectedAt`; two consecutive ≥24 h apart do; no re-flag after; `INSTAGRAM_STORY` never enters the candidate set; no `platformMediaId` → counted skip, never a guess; a rate-limited or token-expired probe never counts as a strike |
| `ChannelConsentTest` | active marker transitions, phone hash uniqueness |
| `PhoneVerificationServiceTest` | OTP hash, TTL, attempts, taken phone, consent row created, `phoneVerified` true |
| `MetaWhatsAppCloudClientTest` | request body, timeouts, mock mode |
| `WhatsAppWebhookControllerTest` | handshake, signature valid and invalid (401), body cap, statuses update rows, STOP, approve-by-reply inside and outside the window |
| `ChannelRouterTest` | category mapping, consent and prefs gating, idempotency key, failure never rolls back |
| `ChannelSendJobTest`, `WebPushSenderTest` (410 expires) |
| `DealNudgeJobTest` | each stage fires once, D7 adds owners, unfunded needs full signatures, deadline T-1 in IST, auto-approve only when flag > 0 |
| `DealServicePostSystemMessageTest` | system message shape and stream publish |
| `DisputeEvidenceServiceTest` | party derivation, OPEN → UNDER_REVIEW, file type and size, evidence hold set |
| `EvidencePackServiceTest` | manifest fields, terms from `termsText` not `termsJson`, missing files listed, brand copy has no agent metadata |
| `DeliverableCleanupJobGuardTest` | evidence hold and keep-up skips |
| `NotificationEventPermitsTest` | every new event has a listener handler and a template (Phase E risk 7; write it here since Phase C lands first) — <!-- PRIYA: --> **and assert channel-set membership per event**, because an `eventType` in neither `EMAIL_ONLY_EVENTS` nor `IN_APP_ONLY_EVENTS` silently gets both channels and `isUnsubscribed` is false with no `email_preferences` row |
| `WebhookBodyCapTest` | <!-- PRIYA: new — this is acceptance question 7 and nothing else covers it. --> a 5 MB POST to `/webhooks/whatsapp` is rejected on `Content-Length` with **no** call to the signature verifier; a chunked 5 MB body trips `LimitedInputStream` and 413s; a valid-length forged body 401s with `recordAuthRejection`; a replayed `wa_message_id` is a no-op via `uk_iwm_wa_message` |
| `SecurityConfigMatcherTest` | `/webhooks/whatsapp` GET and POST before role gates |
| `MeeraPhaseCBootValidationTest` | eleven migrations, `ddl-auto=validate`; skipped without Docker |

Python: `tests/prompt/test_creator_money_lines.py` (the two new rendered lines and their absence), tool schema tests via the Phase B parametrisation for `get_payment_status` and `get_delivery_proof`.

---

## 11. Build order

| Day | Backend | AI | Frontend | Ops |
|---|---|---|---|---|
| 0 | | | | <!-- PRIYA: three blocking preconditions added — see §15.4. --> **(a) The uncommitted money-path wave lands or is stashed** — C0 edits five of the seven files it touches, and `ContractService.amend` exists only in it (§15.4 condition 1). **(b) `nl.martijndwars:web-push` + one pinned BouncyCastle pre-fetched into the local Maven repo** and a trial `mvn -o` compile, or push is deferred (correction 35). **(c) 192px and 512px PNG icons produced** (correction 33). Then: WABA created, phone number verified, display name approved, seven templates submitted; VAPID key pair generated; `WHATSAPP_*` and `PUSH_*` secrets in the vault; nginx `client_max_body_size 256k` on the `/webhooks/whatsapp` location |
| 1 | eleven migrations, entities, repositories, boot test; `MilestoneAssignment`, contract link, backfill job, revision-cap fix | context keys render | type drift fixes, enum-drift test, api types and mocks | |
| 2 | `CreatorMoneyService`, money routes, CSV; `get_payment_status` executor **[Phase B]** | tool schema, persona | wallet Deals tab, `MilestoneTimeline`, money strip | |
| 3 | Meta client methods, snapshot job, keep-up probe, proof routes, verification upsert | `get_delivery_proof` schema | proof section, `ProofCard` | |
| 4 | consents, OTP, `MetaWhatsAppCloudClient`, webhook, inbound processing, router, send job, push sender and routes | | channels settings, OTP dialog, sw.js, manifest, push.ts | templates approved, webhook subscribed in the Meta app |
| 5 | three events, `DealNudgeJob`, `postSystemMessage`, nudge metrics, auto-approve behind flag | dispute rails | system message style, nudge metrics panel | |
| 6 | dispute evidence service and routes, evidence pack, cleanup guards | | evidence UI, download | |
| 7 | full `mvn -o test`, schema diff, Kavya QA, Kabir on the webhook, OTP, PII cipher, evidence pack authorisation | full pytest | tsc, build, vitest | staging boot with `ddl-auto=validate`, one real WhatsApp round trip |

---

## 12. Acceptance: the zero-context tester's ten questions

1. Approve a deliverable on a funded milestone: does the money actually release now, and what did it do before this phase? Show the link, the backfill, and the test.
2. Open the money view for a deal with two milestones, one funded and one not: what does each say, where does every state come from, and where does the word "escrow" not appear?
3. A payout with `tds_amount` null, one with `0.00`, one with `450.00`: what does the creator see for each, and where is the sentence that says Influora does not compute tax?
4. A reel posted 8 days ago: which snapshots exist, at what times, from which API version, and what does the brand receive when the creator taps "send proof"?
5. The brand archives the post 20 days into a 90-day keep-up: what detects it, how soon, what is the creator told, and what wording is used?
6. A creator enters a phone number: trace verification, consent text and version, the first template sent, and what "STOP" does. Where is the number stored and how is it encrypted?
7. A forged WhatsApp webhook call, a replayed one, and a 5 MB one: what happens to each before any body is parsed?
8. A brand ignores a submitted deliverable for 9 days: list every message sent, to whom, on which channel, and prove none is sent twice.
9. A dispute is opened, the creator adds a screenshot, the brand adds a link: statuses, notifications, and what the evidence pack contains, including a deliverable file the cleanup job already removed.
10. All eleven migrations on stock MySQL 8 with `ddl-auto=validate`; old deals with no milestone link, no snapshots, no consents: does every new page and job handle them with honest empty states?

---

## 13. Expert questions and answers

**Money**
- *Why not compute TDS now?* Because the rate and the withholding point (release versus payout) are unanswered CA questions and the policy page says so. Showing a computed number would be a tax certificate we cannot stand behind. Recording what admin actually deducted is true today.
- *Will the release gate suddenly block releases?* No. The cutover instant is blank by default, so the gate fails open. The milestone link makes the gate meaningful when ops sets the cutover; that is a separate ops decision, listed in section 14.
- *Existing deals with several milestones and deliverables: how are they linked?* Contiguous blocks by order (3.1). It is a heuristic and the backfill is behind a flag; ops runs it once after reading the counts from a dry-run log.
- *What if the invoice for a release is missing?* The money view says "pending" and the retry job keeps trying; nothing is hidden.

**Delivery**
- *Why 24, 72, 168 hours?* They match how brands read performance: first day, first weekend, first week. Missed windows are skipped after 12 hours so a snapshot is never mislabelled.
- *Stories?* Live only 24 hours and reachable only through the stories list, so one snapshot near hour 20. If the Graph version rejects a metric the poll keeps the rest.
- *What if the creator's token expired?* The snapshot is skipped with `NO_TOKEN`, the proof card says so, and the creator-reported path still works.
- *YouTube?* Out of scope; no client exists.

**WhatsApp**
- *Cloud API versus a BSP?* Cloud API: no margin, we already have the Meta business and app, webhook and token patterns match the Instagram client. A BSP is a config swap behind `WhatsAppSender`.
- *WABA setup?* A WhatsApp Business Account under the existing Business Manager, a phone number that is not on consumer WhatsApp, display name review, business verification (already done for the app), a system user with `whatsapp_business_messaging` and `whatsapp_business_management`, and a permanent token stored as `WHATSAPP_ACCESS_TOKEN`.
- *Template approval?* Utility templates approve in hours to a day; the authentication template uses Meta's fixed format. Submit on day 0; nothing sends before approval, the send job records `FAILED` with the provider code.
- *The 24-hour window?* Business-initiated messages must be templates. After the user replies, free text is allowed for 24 hours; that is what approve-by-reply uses. The window is tracked per outbound row.
- *Cost?* Utility messages in India are about ₹0.12 each; authentication about ₹0.13. A creator on the five templates costs under ₹5 a month. Push is free.
- *Opt-in under DPDP?* Explicit checkbox with the consent text and version stored, phone verified by OTP on the same channel, STOP honoured instantly and permanently until re-opt-in, revoke in settings, number encrypted at rest, hash for lookup, never logged.
- *What can STOP not stop?* Nothing. Transactional OTP is only sent when the user asks for it.
- *Number display?* The sender is "Influora" on the verified business number; templates say "Meera" only inside the body.

**Push**
- *Why web push and not FCM?* No native apps. VAPID web push works on Android Chrome and desktop; iOS Safari only after the site is added to the home screen, which the settings switch explains.
- *Key rotation?* New VAPID pair invalidates every subscription; the sender marks 410s EXPIRED and the UI re-subscribes on next visit.

**Security**
- *Webhook?* Meta handshake with the verify token, `X-Hub-Signature-256` HMAC over the raw body with a constant-time compare, 256 KB body cap before parsing, `wa_message_id` uniqueness for replay, 200 within 200 ms and processing off the request thread.
- *OTP?* Six digits, hashed with the challenge id, ten minutes, five attempts, one active challenge, user-keyed rate limit, never logged.
- *Evidence files?* Same ClamAV scan and R2 path as deliverable uploads, types and size capped, served through signed URLs with the same expiry as proof uploads.
- *Evidence pack authorisation?* Only the two parties and admins; the brand copy has agent metadata stripped; audit row per download.
- *PII in audit and logs?* Phone numbers appear nowhere but the encrypted column; `detail` maps carry counts and ids only.

**Deliverables checklist**
- Eleven migrations boot-validated on staging.
- One real reel snapshotted at 24 hours from a staging creator; one proof sent to a staging brand.
- One real WhatsApp round trip: OTP, consent, a `funds_secured` template, a STOP.
- One push received on Android Chrome.
- One nudge cycle observed on staging with the clock advanced.
- One evidence pack downloaded and opened.
- Kabir sign-off on the webhook, OTP, cipher, and pack authorisation. Kavya QA. Meera verification. Priya's ten questions positive.

---

## 14. Decisions still open (build with the default, flag to Swapnil)

1. **Auto-approve after brand silence** (plan decision 5): built behind `auto-approve-after-days`, default 0. Recommend 7 days after the D7 nudge.
2. **Release gate cutover** — <!-- PRIYA: re-framed. Setting the cutover does NOT enable staged per-milestone release; assertReleaseConditionSatisfied reads ALL the collaboration's deliverables and requires allMatch, and it hard-throws for a post-cutover milestone with zero deliverables. §15.4 condition 3. --> **this decision is not the one it appears to be, and stays closed until re-ruled.** Setting `INFLUORA_ESCROW_RELEASE_GATE_CUTOVER_INSTANT` enables an **all-deliverables-or-nothing** gate on the whole collaboration, not staged per-milestone release: milestone 1 cannot release until every deliverable of the deal satisfies the condition, and a post-cutover milestone whose collaboration has zero deliverables is refused outright (Swapnil's CR-51 step-3 "forbid"). Re-keying the gate to the milestone is a CR-51 reopen, not a Phase C edit. Until then, releases follow today's behaviour (gate fails open on a blank cutover), which after C0 means **release on approval for every funded milestone**.
3. **TDS display wording**: "not recorded" versus "not applicable". Default "not recorded".
4. **WhatsApp for brands**: nudges to brands over WhatsApp need brand opt-in too; built, default off per brand.
5. **Story metric set**: confirm against v25.0 on day 1; drop what the version rejects.

---

## 15. Priya review

**Reviewer:** Priya (CTO). **Date:** 2026-09-04. **Baseline:** HEAD `143ca1e` on `fix/f0390-money-flags-build-pipeline` **plus the uncommitted working tree** (7 of the files this phase edits carry +864 uncommitted lines). **Method:** every symbol, signature, line reference, record arity, column type, repository finder, enum value and config key this document names was checked against the actual source, not against the fact sheet. Corrections are applied inline above, each tagged `<!-- PRIYA: ... -->`.

**The header was wrong in the same way Phase E's was.** These citations are not against `143ca1e`; they are against the **working tree**. Verified drift: `ContractService.generate` 143 → **146**, `materializeDeliverables` 425 → **734**, `EscrowService.tryReleaseOnApproval` 702 → **729**, `assertReleaseConditionSatisfied` 1328 → **1545**, `DealService.sendMessage` 619 → **618**. And one citation resolves to nothing at HEAD at all: **`ContractService.amend` does not exist at `143ca1e`.** It is F-0414, part of the uncommitted wave. Section 3.1's amend step has no target on a clean checkout.

### 15.1 Did section 0 rules 18-26 pre-empt the Phase B/E mistake classes?

**Partly — about half.** Rules 18-26 are aimed at the *design* mistakes Phase B and E made (tax arithmetic, record widening, self-invocation, missing `@SchedulerLock`, `CHAR`, partial indexes, writing GETs, webhook auth, PII). Those all held: I found **no** record-arity violation, **no** `CHAR(n)`, **no** missing collation clause, **no** unlocked job, **no** writing GET, and no place where TDS is computed. Rule 19 in particular is the single most effective rule in this document — every one of the five named records is genuinely untouched, and the `dealsSummary` map route genuinely dodges the Python drift test (`deals_summary` is an allow-listed **top-level** key in `assembler.py` L147; sub-keys are invisible to it).

What the rules did **not** pre-empt is the mistake class that actually caused most of Phase B's ten compile errors: **asserting that a symbol exists, or has a given shape, without opening the file.** Rules 18-26 govern what to build; nothing in them governs how a claim about existing code gets made. This spec repeats that class fourteen times, and twice in the most damaging form — telling the engineer to change code that is **already correct** (3.1's verification upsert) and telling them to call a method whose contract forbids what they need (5.4's `sendMessage`). Two further rules are needed and are added to section 0 as **27** and **28**.

Three specific carry-forwards were also missed:

- Phase B correction 11 (`CHAR` to `VARCHAR`) was internalised as rule 23, but its sibling — **Hibernate defaults a `String` column to `VARCHAR(255)`, so every `VARCHAR(n)` needs an explicit `@Column(length = n)` or `ddl-auto=validate` rejects it** — was not. Every one of the eleven migrations here would trip it.
- Phase B correction 40 (`requireFeatureEnabled()` is private) recurs verbatim in 4.2.
- Phase E 14.2's process warning ("do not start on top of an uncommitted 5,600-line diff") applies **harder** here than it did to Phase E: Phase C's C0 edits `ContractService`, `EscrowService`, `BrandDeliverableService`, `DeliverableMetricService` and `MoneyDtos` — five of the seven files the concurrent session is actively rewriting, and a recorded property of this repo is that that session has silently clobbered in-progress work three times.

### 15.2 Corrections applied

**Would not compile, or would compile and do nothing (14)**

| # | Sec | Was | Is |
|---|---|---|---|
| 1 | 5.4 | Proof card posted "through `DealService.sendMessage` (three args)" | `sendMessage` is three-arg, but it **hard-codes `kind = DealMessageKind.text` and `metadataJson = null`** and sanitizes the content (`DealService` L625-628, Kabir M-1: only `text` is client-selectable, every server-authoritative kind is set on internal paths only). It cannot carry a `deliverable` kind or a `proof` metadata blob. Needs a new public `@Transactional postDeliverableProofMessage(...)`. |
| 2 | 3.1 | "read L124-200 and change the insert to `findByMilestoneId().map(update).orElse(insert)`" | **`DeliverableVerificationService.persistVerified` is already exactly that upsert** (L268-297: `findByMilestoneId` then present ? reuse : build). There is nothing to change. L124-200 is `verify()` plus the head of `verifyInstagram`, not the persist path at all. Step deleted; the test becomes a characterization test. |
| 3 | 4.1 | `escrow_invoice_failures` "repository exists for the retry job" | **No entity, no repository.** The table is reached only through `EntityManager` native SQL inside `CampaignServiceInvoiceService` (L305, L349, L413, L498), and its own javadoc at L288 says that is deliberate ("rather than a dedicated `@Entity`/`@Repository` pair"). Adding an entity would also add a `ddl-auto=validate` surface for a table nobody validated. Add a public `hasPendingInvoiceFailure(String escrowHoldId)` on that service instead. |
| 4 | 4.1 | `milestoneRepository.findByCollaborationIdOrderBySequenceNoAsc` "add if missing" | Missing **and wrong**. `amend` creates a new contract version with brand-new milestone rows and **never deletes the superseded ones**, so a by-collaboration query returns milestones from every version of the contract. Use the existing `findByContractIdOrderBySequenceNoAsc(latestContract.getId())`. |
| 5 | 4.1 | `escrowHoldRepository.findByMilestoneId` "add if missing" | **Exists** (`EscrowHoldRepository` L110) and returns `List<EscrowHold>`, not `Optional` — a refunded-then-refunded milestone has more than one. Pick the live hold. |
| 6 | 4.1 | `payoutRepository.findByCreatorUserIdOrderByCreatedAtDesc` used as a list finder | Returns `Page<Payout>` and takes a `Pageable` (`PayoutRepository` L39). |
| 7 | 4.1 | contracts via `ContractService.listForCreator` L1365 | That method is `(AuthPrincipal, String dealId)` — a creator-principal route helper, not a service-internal read. Use `contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(collaborationId)` and take element 0. |
| 8 | 3.1 | "in `ContractService.generate` (L146), after milestones are saved (L323) ... If `materializeDeliverables` runs before milestones are saved, reorder" | **The order is already correct** — `milestoneRepository.saveAll` L324, `materializeDeliverables(collaboration)` L327. No reorder. But `materializeDeliverables` returns `void` **and early-returns when deliverables already exist** (L735-739), so the assignment must live outside it and re-read `findByCollaborationIdOrderBySlotIndexAsc`. Exact insertion line given inline. |
| 9 | 3.1 | "Also in `ContractService.amend` (L441)" | **`amend` does not exist at HEAD `143ca1e`.** It is uncommitted (F-0414, +423 lines in `ContractService`). Insertion point is after `milestoneRepository.saveAll(milestones)` at tree L538 — conditional on that wave landing. |
| 10 | 5.1-5.3 | `getMediaById(mediaId, ...)`, "falling back to `getMediaById` when the shortcode is older than the last 50 posts" | **Not implementable.** `PostUrlIdentifier.extract` returns an Instagram **shortcode** (`/p/{code}`, `/reel/{code}`), and the Graph API has no shortcode lookup. `Deliverable.postId` is declared but **never written** (`applyMarkPosted` sets only `postUrl`). The only numeric media id we ever hold is the one matched from the recent-media list. Rewritten: persist `platform_media_id` on first match, probe only deliverables that have one, fall back to the list otherwise. |
| 11 | 5.1 | "404 ... to `MetaNotFoundException` (new, extends `MetaApiException`)" | The subclass is addable (`MetaApiException` has a `protected (code, message, status)` ctor), but **`MetaGraphApiClient.translate` L143-161 has no 404 branch** — 404 falls through to the base `MetaApiException`. Declaring the subclass changes nothing until `translate` is edited. Meta also returns **400** with `code 100 / subcode 33` for a deleted object, and `translate` only has `e.getResponseBodyAsString()` to see it. Both edits specified inline. |
| 12 | 5.3 | `usageMonths != null ? usageMonths x 30 : 90`; "perpetual usage gives `postedAt + 365`" | Self-contradictory (null is both 90 and 365). The real model is two fields: `Collaboration.usagePerpetual` (boolean, NOT NULL) **and** `usageMonths` (Integer), set together by `applyDealTerms`; `usageMonths == null` means perpetual **only when `usagePerpetual` is true** (entity L62-66). Rewritten as a three-branch rule. |
| 13 | 6.5 | "set `user.phoneVerified = true` (the first writer of that flag)" | **`User` has no setter or mutator for it** — a getter at L194 and two `= false` initialisers (L108, L134). A mutator must be added. |
| 14 | 7.2 | `postSystemMessage` "publishes to the SSE stream the way `sendMessage` does (read L618-700 ...)" | The idiom to copy is `appendLifecycleRow` at **L1029-1033**, not `sendMessage`: `publishToStream(collaborationId, toMessageResponse(appendSystemMessage(collaborationId, content)))`. `appendSystemMessage` (L1971) takes no metadata, so `postSystemMessage` builds its own `DealMessage.create(...)` (7 args — verified correct as written) and calls the same two private helpers. |

**Would compile but be wrong at runtime, at boot, or in the schema (9)**

| # | Sec | Defect |
|---|---|---|
| 15 | 2.1-2.10 | **Every `VARCHAR(n)` column needs an explicit `@Column(length = n)` on its field.** Hibernate maps a bare `String` to `VARCHAR(255)` and `ddl-auto=validate` rejects the mismatch — same family as Phase B correction 11, and it would fire on `source VARCHAR(20)`, `channel VARCHAR(16)`, every `status VARCHAR(16)`, `active_marker VARCHAR(1)`, and the two `VARCHAR(2048)`s. Promoted into rule 23. |
| 16 | 7.1 | The `NO_EMAIL_EVENTS` rationale is **inverted**. `IN_APP_ONLY_EVENTS` cannot "also block WhatsApp": `ChannelRouter.fanOut` runs at the **end** of `notify`, after all three branches (`NotificationService` L78-93), so an `IN_APP_ONLY_EVENTS` member still reaches the router. Use the existing set; a second set is dead weight with two places to forget. |
| 17 | 7.2 | `UNFUNDED_D7` keys on "`contract.effectiveDate` or last signature". **`Contract.effectiveDate` is never written by anything** — the only reference in the codebase is the builder method at `Contract.java:309`; zero call sites. It is NULL on every row. Use `max(brandSignedAt, creatorSignedAt)`. |
| 18 | 7.2 | "every workspace OWNER (`workspaceMemberRepository` role OWNER active)". There is no `findByWorkspaceIdAndRoleAndActiveTrue`. The list finder is `findByWorkspaceIdInAndRoleAndActiveTrue(List<String>, MemberRole)`; the single-row one is `findFirstByWorkspaceIdAndRoleAndActiveTrue`. |
| 19 | 7.3 | `autoApproveAfterSilence` "reuses the approve body with a system actor". `approve` **opens with `brandContext.requireBrandWorkspace(principal)`** (L110) and re-resolves the row twice under `PESSIMISTIC_WRITE` off that workspace (F-0580). A job has no principal. It must resolve the workspace from the deliverable's collaboration then campaign, and keep the same lock discipline — a sibling method, not a reuse. |
| 20 | 8.2 | `DisputeService.requireOwnedCollaboration` cited as "L109-147". That range is `openDispute`. The method is **private**, three-arg `(AuthPrincipal, UserType role, String dealId)`, at **L561**. It must be promoted or replicated; it cannot be called from a new service as written. |
| 21 | 8.4 | "streamed from R2". **`R2StorageService` has no read method** — its entire public surface is `isAvailable`, `publicUrl`, `presignPut`, `putBytes`, `putStream`, `presignGet`, `deleteObject`. The evidence pack needs a new `getObjectStream(String key)`; the alternative (server fetching its own `presignGet` URL over HTTP) is an outbound-fetch path this codebase deliberately does not have. |
| 22 | 2.1 | `CREATE INDEX idx_deliverables_milestone ON deliverables (milestone_id)` is **redundant**. `V37__deliverables.sql:39` already declares `CONSTRAINT fk_deliverable_milestone FOREIGN KEY (milestone_id) REFERENCES payment_milestones(id)`, and InnoDB backs every FK with an index. Removed. (All other new index names checked against all 119 migrations: **no collisions**.) |
| 23 | 3.1 | `AuditLogService.recordSystemEvent` "add this method per Phase E 4.1". Phase C lands **first** (its own section 10 says so), so Phase E cannot supply it. Phase C adds it; the shape to copy is `recordMoneyEvent` (L110-131, `ACTOR_SYSTEM` plus `OUTCOME_ALLOWED` plus `REQUIRES_NEW`). `recordAuthRejection(workspaceId, eventType, reasonCode, actorId)` used by 6.4 **does** exist (L71). |

**Security and operational corrections (4)**

| # | Sec | Correction |
|---|---|---|
| 24 | 0.25 / 6.4 | **"Authenticated before the body is read into memory" is not achievable** — an HMAC over the body requires reading the body. And of the two mechanisms offered, one does not work: `ContentCachingRequestWrapper` caches bytes only *as downstream code reads them* and needs a filter registration, so it gives you nothing before Jackson. The house convention (`RazorpayWebhookController` L88-93, `ShopifyWebhookController` L106-118) is `@RequestBody String rawPayload` then verify — correct on ordering, but it buffers an unbounded body first. Rewritten to: reject on `Content-Length` first, then read `new LimitedInputStream(request.getInputStream(), 262_144)` (throws past the cap — `common/LimitedInputStream.java:52`), HMAC the bytes, then parse; with `client_max_body_size 256k` on the nginx location as the control that cannot be bypassed by a handler bug. Rule 25 reworded. |
| 25 | 2.3 / 6.5 | `phone_hash` as "SHA-256 of E.164". An unkeyed SHA-256 over a 10-digit Indian mobile is a 10^10 space — a full rainbow table is minutes of GPU time, and this column is the join key on an unauthenticated webhook. **`EmailPhonePiiCipher.blindIndex` already exists** (L45-57): keyed SHA-256, 64 hex chars, exactly the column width. Use it. Also: `UserPhoneService.normalizeAndValidate` returns a **10-digit** number, not E.164 (`IndianPhoneUtils.normalize` strips `+91`), and Meta delivers `wa_id` as `91XXXXXXXXXX`, which that same normalizer handles. Store E.164 in `phone_e164_enc`, blind-index the 10-digit form, on both sides. |
| 26 | 8.2 | "the existing ClamAV integration ... look for the scanner service in `integration/clamav`". The injectable is `com.influora.service.security.MalwareScanService.requireClean(MultipartFile, String context)`; `integration/clamav` holds only the low-level `ClamAvClient`. **The bound implementation is `NoOpMalwareScanService` unless ClamAV is configured** — its own javadoc calls it "the accepted-risk stub". Say so rather than implying files are scanned. |
| 27 | 4.2, 8.2, 8.4 | Rate buckets are not one edit. Each needs **five**: a `Pattern` constant, an `if` in `bucketFor`, a `case` in `limitFor` (L405-427), a `case` in `isUserKeyedBucket` (L449-466), and a `@Value` limit field. And `evidence-pack` is a **GET**, so it belongs inside the `isGet` block (L300-316) which `return null`s at L315 — put in the POST chain it would never match. |

**Understated work and wrong references (6)**

| # | Sec | Correction |
|---|---|---|
| 28 | 4.4 | `MeeraContextService.buildDealsSummary` is `private static (List<Collaboration>, Locale)` at L382 — **no repository access at all**. Adding `secured_count` / `frozen_count` / `payout_in_flight_inr` makes it an instance method with three injected repositories. Also note Phase B correction 15: `EscrowHold.collaborationId` is NULL on ordinary brand-funded holds, so counts must go milestone then `findByMilestoneId`, never by collaboration. |
| 29 | 3.2 | `getStatus` returns `DeliverableStatusResponse`, which **carries no `maxRevisions`** — rule 19 keeps it that way, so there is nothing to change there. The cap lives only in `toListItem`, which is `static` at L1121 with two call sites: a method reference at L737 (breaks on any signature change) and L785 inside `listForCollaborations`, which needs a per-collaboration cap **map** (available free from `findByCreatorIdAndIdIn`). `requireOwnedCollaboration` returns `void` and must return the `Collaboration`. |
| 30 | 3.3 | `deliverable-card.tsx`'s union is `'submitted' \| 'approved' \| 'revision_requested' \| 'payment_released'` — **lowercase**, and `payment_released` is a *payment* state with no `DeliverableStatus` counterpart. Swapping in `DeliverableStatus` silently deletes it. Keep a separate `paymentReleased` boolean. |
| 31 | 3.2 | The six `maxRevisions: 2` literals are correct (L203, 216, 226, 273, 283, 330) but incomplete: the interface declares `maxRevisions: number` (required) at **L121** and renders it at **L1510**. Type, literals and render move together. |
| 32 | 5.2, 7.2 | Every candidate query the two jobs need is new: `DeliverableRepository` has `findByStatusInAndPostUrlIsNotNull`, `findByStatusInAndApprovedAtBefore`, `findByStatusInAndUpdatedAtBefore` and nothing keyed on `submittedAt`, `deadline` or `keepUpUntil`. All new finders take a `Pageable` — rule 21 requires a batch limit and none of the existing ones have one. |
| 33 | 9.4 | `public/` has `icon.svg`, `icon-light-32x32.png`, `icon-dark-32x32.png`, `apple-icon.png` — **no 192px or 512px PNG**, which Chrome requires for installability and iOS requires for the home-screen install that gates iOS push. New assets, not a manifest reference. Two further facts: `scripts/prerender.mjs` **overwrites `dist/index.html`** at build time, so the manifest link must survive prerender; and `.proof-os/gates/F-SEO-marketing-surface.sh` gates that file. The CSP in `public/_headers` is fine as-is (`worker-src 'self' blob:`, no `manifest-src` so it inherits `default-src 'self'`). |

**Hand-waves resolved by reading the code (5)**

| # | Sec | Resolution |
|---|---|---|
| 34 | 2.4 | "Mirror `V5__email_otp.sql`'s shape (read it first)". It is `id, email VARCHAR(255), otp_hash VARCHAR(64), expires_at, verified BOOLEAN, attempts INT, created_at, INDEX idx_email_otp_email`. Phase C's DDL deliberately differs (`consumed_at TIMESTAMP NULL` instead of `verified BOOLEAN`, plus `user_id`) and is complete as written — the instruction to go read it is now a note recording the one deliberate divergence. |
| 35 | 6.6 | "check the pom for an existing BouncyCastle version and align". **There is none.** Spring Boot **3.3.5**, Java 21. `RestClient` is available (Boot 3.2+, already used by `MetaGraphApiClient`). `web-push` brings its own BouncyCastle and an async HTTP client; pin it, exclude the transitive BC rather than adding a second BC artifact in the same packages, and pre-fetch it — this repo builds offline (`mvn -o`) and an unfetched artifact fails day 4 outright. |
| 36 | 2.10 / 6.4 | Section 2 said "Ten migrations" while 6.4 introduced an eleventh as a parenthetical and never wrote its DDL — and sections 10, 11 and 12 already said "eleven", so the document disagreed with itself in one place only. Section 2's count fixed, and **the eleventh migration's full SQL written into a new 2.11**, with the `body_text` naming, the `uk_iwm_wa_message` replay constraint and the `saveAndFlush`-inside-the-catch trap spelled out. |
| 37 | 4.2 | Feature gating: `CreatorMeeraController.requireFeatureEnabled()` is **private** (L104) — copy the helper, do not call it (Phase B correction 40, repeated verbatim). `MeeraCreatorFeatureProperties` exists at `config/MeeraCreatorFeatureProperties.java:26`. |
| 38 | 4.3 / persona | "bump `PROMPT_VERSION` together with Phase B's or C's own bump, whichever lands first" is not an instruction anyone can follow. `config.py:69` is `meera-2026.08.10.1`; Phase B takes `meera-2026.09.10.1`; **Phase C takes `meera-2026.09.30.1`**, and the bump must be in the same diff as the persona edit or `ci/stale-comment-check.py` fails. |

### 15.3 Remaining risks

1. **C0 reverses a recorded CTO ruling, and the spec never says so.** `DeliverableRepository.findByMilestoneId` carries an explicit `@deprecated` note: *"CR-51 / Priya's Option B ruling ... milestones and deliverables come from independent sources with no principled N:M mapping ... this lookup always returns empty and has no remaining production caller."* Section 3.1's contiguous-block rule **is** that mapping. I am content to supersede my own ruling — the mapping is a heuristic and the alternative is three permanently dead code paths — but it must be written down as a supersession, the `@deprecated` javadoc must be rewritten in the same commit, and the heuristic must be described to ops as a heuristic. Silently making an "always returns empty" finder return rows is how the next audit gets confused.
2. **The release gate is collaboration-scoped, not milestone-scoped, so staged release stays impossible while the cutover is on.** `assertReleaseConditionSatisfied` reads `findByCollaborationIdOrderBySlotIndexAsc` and requires **every** deliverable of the collaboration to satisfy the condition (L1554-1583). Linking deliverables to milestones does not change that. On a two-milestone deal with the cutover set, approving deliverable 1 still cannot release milestone 1 until deliverable 2 is also posted. Section 3.1 says only "ON_POSTED blocks release at approval", which badly understates it. Decision 2 in section 14 should not be taken until this is either accepted or the gate is re-keyed — and re-keying it is a CR-51 reopen, not a Phase C edit.
3. **Reassigning on `amend` can orphan a funded hold.** `amend` mints new milestone rows and leaves the old ones (with their `escrow_hold_id`) in place. Moving a deliverable's `milestone_id` to the new set means an approval now targets an unfunded new milestone while real money sits secured against the superseded one. Guard: never move a deliverable off a milestone whose hold is `FUNDED`, and log a counted skip when it happens.
4. **Working-tree collision, and this is the blocker.** Phase C's C0 edits `ContractService`, `EscrowService`, `BrandDeliverableService`, `DeliverableMetricService` and `MoneyDtos` — five of the seven files carrying the concurrent session's +864 uncommitted lines, on a branch where that session has already silently clobbered in-progress work three times (`DealService.java`, `PortfolioService.java`). One of those files contains a method this spec depends on that does not exist at HEAD. **Land or stash that wave before day 1.** Not a recommendation.
5. **The keep-up probe can permanently mislabel live content.** `removedDetectedAt` is set once and never cleared (2.1, by design), and the "removed" signal is a Meta 404/400 that also fires for a scope loss, a token the creator revoked and re-granted with fewer permissions, or a temporarily restricted account. One bad probe brands a live post "removed or archived" forever and Phase D's warning rule reads that flag. Require **two consecutive `NOT_FOUND` probes at least 24 h apart**, and only when the token resolved and the rate-limit guard did not trip.
6. **Stories will be flagged removed by design.** An Instagram Story is gone after 24 h. With `keep_up_until` set from `usageMonths` and the probe running daily, every Story deliverable trips `NOT_FOUND` on day 2. `INSTAGRAM_STORY` is excluded from both `keepUpUntil` and the probe inline; if the exclusion is dropped in review, this ships a false-alarm generator.
7. **Evidence files are not actually scanned in most environments** (correction 26). Kabir should sign off on `NoOpMalwareScanService` being the live binding for a path where a brand downloads a creator-supplied file out of a ZIP, or ClamAV gets provisioned as part of day 0 ops.
8. **The story metric set is unverified against v25.0.** Section 14 decision 5 correctly flags it, but the snapshot job's Story branch is the only consumer and it has no fallback beyond "drop the metric and log". If `navigation` with `story_navigation_action_type` is rejected on v25.0, the Story rows carry reach and replies only — acceptable, but say it in the proof card rather than rendering blanks.
9. **`web-push` is a heavy dependency for one feature behind a flag defaulting to false.** It pulls BouncyCastle and an async HTTP client into a service that currently has neither. If the offline build or the BC duplication bites on day 4, the honest fallback is to ship C3 with WhatsApp only and defer push — the two are independent and the spec is already structured that way.
10. **`ChannelRouter` runs inside the notification transaction.** Section 6.7 is right that a channel failure must not roll back the notification, and the try/catch handles that — but a slow provider call inside a `@Transactional` boundary holds a DB connection. The design already avoids it (the router only inserts a QUEUED row; `ChannelSendJob` does the network call). Keep it that way; the first person who "optimises" by sending inline reintroduces it.

### 15.4 Verdict

**Buildable as written: no.** An engineer following 5.4, 4.1, 3.1, 5.1-5.3, 6.5, 7.2 or 8.4 literally hits: a `sendMessage` contract that forbids the message the spec asks for; a repository that does not exist for a table that deliberately has none; an `amend` method that does not exist on the baseline commit; a `getMediaById` call with no media id to pass it; a `phoneVerified` field with no setter; an `effectiveDate` that is NULL on every row; and an R2 client with no read method. Plus eleven `ddl-auto=validate` boot failures from missing `@Column(length)`, and one instruction (3.1's verification upsert) to rewrite code that is already correct — the same "assert the shape without opening the file" class that produced most of Phase B's ten compile errors.

**Buildable as corrected: yes**, on four conditions:

1. **The uncommitted money-path wave lands or is stashed before day 1** (risk 4). Phase C's foundation step edits five of its seven files and depends on a method (`ContractService.amend`) that exists only in it. This is the one condition with no workaround.
2. **The CR-51 Option-B supersession is written down** (risk 1) — in `wiki/tech/architecture.md`, in the `@deprecated` javadoc, and in the backfill's ops note — before the backfill flag is turned on in production. The backfill has no inverse; a wrong contiguous-block assignment on a live deal moves real money on the next approval.
3. **Decision 2 (release-gate cutover) stays closed until risk 2 is ruled on.** Setting `INFLUORA_ESCROW_RELEASE_GATE_CUTOVER_INSTANT` after this phase does not enable staged per-milestone release; it enables an all-deliverables-or-nothing gate on multi-milestone deals. That is a product decision, and it is not the one section 14 currently describes.
4. **The `@Column(length = n)` rule (correction 15) is applied to all eleven migrations' entities on day 1** and `MeeraPhaseCBootValidationTest` runs with Docker present at least once before day 7. Without a real `validate` run this phase ships eleven chances to repeat the exact break `V20260718150000` was written to repair.

Everything else in 15.3 is a risk to carry, not a blocker. The five items in section 14 remain genuinely open; decision 2 needs re-framing per condition 3, and nothing else in this document needs a Swapnil ruling.
