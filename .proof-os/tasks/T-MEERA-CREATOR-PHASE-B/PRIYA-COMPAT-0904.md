# PRIYA — Phase B spec ↔ current-tree compatibility report

**Reviewer:** Priya (CTO). **Date:** 2026-09-04.
**Spec under review:** `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/SPEC.md` (1366 lines, §0-13; §13 is my earlier review against `8c7b18b`).
**Tree state at review time:** branch `fix/f0390-money-flags-build-pipeline`, HEAD `143ca1e`, working tree **dirty** with an unrelated money-flags/admin-provisioning task (33 tracked files modified or added vs HEAD).
**Method:** every symbol, signature, record arity, construction site, test assertion, property name and enum the spec depends on was read from the actual working-tree file. Where a line number moved I separated three causes: (a) the spec was already wrong at its own baseline `8c7b18b`, (b) HEAD moved it, (c) the uncommitted money-flags edits moved it. Only (c) disappears if you branch clean.

Nothing in the spec has been built. No migration newer than `V20260903170000` exists (confirmed: 119 files in `db/migration/`, none uncommitted). None of the nine key Phase-B artifacts exist.

---

## 1. Summary

| Service | HOLDS | DRIFTED | BROKEN |
|---|---:|---:|---:|
| Backend (`influora-api`) | 62 | 14 | 5 |
| AI service (`influora-ai` + `ci/` + `.github/`) | 34 | 6 | 1 |
| Frontend (`src/`) | 38 | 7 | 1 |
| **Total** | **134** | **27** | **7** |

Plus **8 spec-internal inconsistencies** (§4), one of which (the `deal_offer_history` unique key) I had already flagged as a risk but never wrote into the DDL.

Of the 27 DRIFTED items, **19 are caused solely by the uncommitted money-flags working tree** and vanish if Phase B branches from a clean `143ca1e`. The remaining 8 are genuine — 5 are spec errors that were already wrong at `8c7b18b`, 3 are HEAD-vs-baseline moves.

**Headline:** the single most dangerous finding is not in the spec's Java at all. It is `.github/workflows/schema-check.yml` — §2.10 asserts that adding creator context fields "does not move that gate". That assertion is false, the gate is a hard `exit 1`, and Phase B's **day-1 commit is exactly the commit that triggers it**. See BROKEN-A1.

---

## 2. BROKEN findings (spec instruction cannot be executed as written)

### BROKEN-B1 — §2.8: `toMessageResponse` has **nine** call sites, not two

`influora-api/src/main/java/com/influora/service/DealService.java`

§2.8 says: *"Change its signature to `toMessageResponse(DealMessage message, boolean isBrandViewer)` and update both call sites (`listMessages`, `sendMessage`) plus `seedNotesMessage` … Also update `publishToStream`."*

There are **nine** call sites. Worktree line numbers (baseline `8c7b18b` in brackets):

| # | Line (worktree) | Line (`8c7b18b`) | Context |
|---|---|---|---|
| 1 | 470 | 471 | `doReject` — superseded-card fan-out |
| 2 | 471 | 472 | `doReject` — system message |
| 3 | **612** | **613** | `listMessages` loop ✅ (spec named this one, but said L611) |
| 4 | **658** | **659** | `sendMessage` ✅ (spec named this one, but said L664) |
| 5 | 1032 | 1033 | `appendSystemMessage` publish helper |
| 6 | 1160 | 1161 | `doAccept` — superseded-card fan-out |
| 7 | 1161 | 1162 | `doAccept` — system message |
| 8 | 1364 | 1365 | `doCounter` — superseded-card fan-out |
| 9 | 1365 | 1366 | `doCounter` — new proposal |

Method declaration: worktree **L2214**, baseline L2193.

**Consequence:** an engineer following §2.8 changes the signature, fixes 2 sites, and hits **7 compile errors** in `doReject`, `doAccept`, `doCounter` and `appendSystemMessage`.

**Fix:** either (a) enumerate all nine in §2.8 and pass `false` (creator-visible, unstripped) at the seven fan-out sites — note these all feed `publishToStream`, so the §2.8 "strip on the stream too" decision means they should pass `true`; or, better, (b) **do not change the mapper's signature at all**. Keep `toMessageResponse(DealMessage)` producing the full shape and add a separate `private static DealMessageResponse stripAgentMetadata(DealMessageResponse)` applied at exactly the three viewer-facing points (`listMessages` return, `sendMessage` return, `publishToStream` entry). That is 3 edits instead of 9 and keeps the strip decision in one place. **(b) is my recommendation** — it also removes the §13.2 risk-2 wording problem, because the stream strip becomes a single call.

### BROKEN-B2 — §3.7: `sent_message_id` is unobtainable on three of the four send paths

`ApproveDraftResponse{draft_id, sent_message_id, deal_id, approved_draft_count, level_up_eligible}` and *"sets SENT with `sentMessageId`"*. Verified return types:

- **REPLY** → `DealService.sendMessage(...)` returns `DealMessageResponse` — has `.id()` ✅
- **COUNTER** → `DealService.counter(...)` returns `DealResponse` (`DealDtos.java` L27-65) — **no message id field**. `doCounter` creates `newProposal` internally (worktree L1365) and discards its id.
- **DECLINE** → `DealService.reject(...)` returns **`OkResponse(boolean ok)`** (`DealDtos.java` L154) — no id at all.
- **APPLICATION** → `CreatorCampaignService.apply(...)` returns `ApplyResponse(collaborationId, status, appliedAt)` (`web/dto/creatorcampaign/CreatorCampaignDtos.java` L72) — no message id.

**Consequence:** `meera_drafts.sent_message_id` and `ApproveDraftResponse.sent_message_id` are always null on 3 of 4 kinds, and the §3.7 idempotency design ("return the stored `sentMessageId` on replay") has nothing to return, so the PENDING-guard replay path silently degrades.

**Fix:** state explicitly that `sent_message_id` is populated on REPLY only and nullable elsewhere, and change the replay guard to key on `draft.status != PENDING` returning the stored `deal_id` + status. If a real id is wanted for COUNTER, add a `String proposalMessageId` component to `DealResponse` — but that is a brand-facing DTO change with its own call-site sweep, so I would not do it in Phase B.

### BROKEN-B3 — §3.6 / §13.3 condition 2: widening `InfoBarrierTest` is not "one line"

`influora-api/src/test/java/com/influora/architecture/InfoBarrierTest.java` (note: **`architecture/`**, not `service/meera/` — the spec never states the path).

```java
L68:  List<Path> candidateDirs =
L69:      List.of(mainRoot.resolve("com/influora/service/meera"), mainRoot.resolve("com/influora/web"));
L71:  List<Path> offendingFiles;
L72:  try (Stream<Path> serviceMeera = walkIfExists(candidateDirs.get(0));
L73:          Stream<Path> web = walkIfExists(candidateDirs.get(1))) {
L74:      offendingFiles =
L75:              Stream.concat(serviceMeera, web)
```

`candidateDirs` is consumed **by index** in a two-resource try-with-resources and a two-way `Stream.concat`. Adding entries to the `List.of(...)` changes nothing. §3.6's *"one line in `InfoBarrierTest.candidateDirs`"* and §13.3's condition 2 both understate this: it is a ~8-line restructure (walk each dir, flat-map, close deterministically).

**Good news I verified while here:** widening to `service/**` and `job/**` will **not** turn the test red today. `FORBIDDEN_IMPORT_STATEMENT` (L48-49) is anchored to real `import` statements, and there are exactly two in `main`: `service/CreatorAgentPreferencesService.java:12` and `service/meera/MeeraContextService.java:26` — both already in `ALLOWED_IMPORTERS` (L59-60). `service/PublicCreatorService.java:24` and `domain/entity/CreatorAgentPreferences.java` only mention the name in javadoc and will not match. So the widening is safe, just not one line.

### BROKEN-B4 — §8.1: `applyDealTermsIfPresent` is `private static`

`DealService.java` worktree **L2102** (`private static void applyDealTermsIfPresent(Collaboration, DealTermsDto)`), called only from within `DealService` (L283, L298, L1306).

§8.1's secure-link redeem instruction — *"applies deal terms via `applyDealTermsIfPresent`"* — cannot be executed from a new `SecureLinkRedeemService` / controller in another package.

**Fix:** route the whole redeem through a new `public @Transactional DealService.proposeFromSecureLink(...)` (which can then also do the `Collaboration.propose`, the proposal `DealMessage`, and the `OFFER` offer-history row in one transaction), or promote the helper to `public static`. The former is cleaner and folds §13.2 risk 8 into a single method. Either way the spec must say which.

### BROKEN-B5 — `Rendered` does not exist and is never scheduled

§3.6 introduces `Rendered.money(BigDecimal, Locale)` / `Rendered.date(LocalDate|Instant, Locale)` in one sentence, as if it were existing infrastructure. Verified: **no `Rendered*.java` anywhere in `influora-api`**. Neither does `CreatorTiers` (§3.6) or `CreatorDealStatuses` (§3.6) — those two the spec at least explicitly says to create.

The blocker is sequencing: §2.10 puts `CreatorContextResponse` component 29 (`holdout_until` = `Rendered.date(prefs.getHoldoutUntil(), locale)`) on **day 1**, but `Rendered` first appears in §3.6, which §10 schedules for **day 2**, and it appears in no row of the §10 build-order table at all.

**Fix:** add `com.influora.common.Rendered` (with an explicit spec of both methods' output format — §3.6 says `"5 Oct 2026"` for dates and "grouping, no fraction" for money) to the **day-1 backend** cell of §10, alongside `CreatorTiers` and `CreatorDealStatuses`, since §2.10's day-1 record depends on it.

### BROKEN-A1 — §2.10: the schema-check CI gate **is** moved by the creator fields, and it blocks

`.github/workflows/schema-check.yml`

§2.10 states: *"its blocking context-field diff extracts only the **brand** record (`awk '/public record ContextResponse/,/\{\}\)/'` — that pattern does not match `public record CreatorContextResponse`). Adding creator fields does not move that gate."*

The **start** half of that claim is true. The **conclusion** is false, because the **end** pattern never matches:

- `grep -c '{})' MeeraContextDtos.java` → **0**. The range end `/\{\}\)/` matches nothing in the file.
- The awk range therefore runs from Java **L122** (`public record ContextResponse(`) to **EOF (L216)** — 95 lines — swallowing `CreatorContextResponse` (L166) entirely.
- Simulated on the real file: the extraction yields **42 `@JsonProperty` names (40 unique)**, i.e. `ContextResponse`'s own 15 **plus all 27 creator ones**.
- The comparison step (workflow L242-250) is a plain string compare against Python's `CONTEXT_PAYLOAD_FIELDS`, which has **15** entries, and ends `exit 1`.
- The workflow's `paths:` trigger (L5-17) includes **both** `influora-api/.../MeeraContextDtos.java` **and** `influora-ai/app/prompt/assembler.py` — the two files §10 requires to land in the **same day-1 commit**.

**Consequence:** Phase B's day-1 PR is guaranteed to run this job, and the job fails. (The gate appears to be already failing or already not enforced on `main` — 42 ≠ 15 today — which is the "CI gates passing vacuously" pattern this repo has hit before. Either way Phase B cannot rely on it staying quiet, and day 1 makes the delta worse, 42 → 47.)

**Fix (do this before day 1):** repair the awk range so it actually bounds `ContextResponse`. The record's terminator in this file is a line matching `^\s*@JsonProperty\(.*\) \w+\) \{\}$`; the robust form is to bound on the next record instead:

```yaml
JAVA_CONTEXT_FIELDS=$(awk '/public record ContextResponse\(/{f=1} f&&/public record CreatorContextResponse\(/{exit} f' \
  src/main/java/com/influora/web/dto/meera/MeeraContextDtos.java \
  | grep -oP '@JsonProperty\("\K[a-z_]+(?="\))' \
  | jq -R -s -c 'split("\n")[:-1] | sort')
```

and verify it emits exactly the 15 names in `CONTEXT_PAYLOAD_FIELDS` **before** Phase B touches either file. Then add §2.10's claim back as a *verified* statement rather than an assumption.

### BROKEN-F1 — §8.9: there are **three** `<Routes>` declarations, not two

§8.9 asserts: *"`src/App.tsx` and `src/pages/admin-console.tsx` … are the only `<Routes>` declarations in non-test code."*

Third one: **`src/admin/pages/UsersPage.tsx:744`** — a nested `<Routes>` mounted by `AdminConsolePage` at `"users/*"` (documented in its own comment at L18).

Low blast radius (Phase B adds only public top-level routes, which cannot reach a nested admin router), but the statement as written is false and any reasoning premised on "only two" needs re-checking.

---

## 3. DRIFTED findings

### 3a. Caused by the uncommitted money-flags tree (19 items) — all vanish on a clean branch

The dirty tree contains an unrelated task (F-0399/F-0476 budget gate, F-0653 contract resolution, F-0489/F-0652 escrow release, admin provisioning). Files it touches that Phase B also references:

| File | Δ vs HEAD | Effect on Phase B's line refs |
|---|---|---|
| `service/DealService.java` | +48 / −27 | **−1** for everything above L1726 (one removed import); **+21** for everything below (a rewritten javadoc + new `committedValue` helper in `requireWithinRemainingBudget`) |
| `service/ContractService.java` | +390 / −0 | `DeliverableType.valueOf` L512 → **L821** |
| `test/.../DealServiceTest.java` | +127 / −0 | 2 imports at top + 125 lines appended at EOF → all 9 `new CounterRequest(...)` sites **+2** |
| `src/lib/api.ts` | +46 / −5 | uniform **+41** for everything below ~L3665 |
| `src/App.tsx` | +7 / −2 | uniform **+5** for everything below L88 |

Concrete drifted anchors:

| # | Spec ref | Spec says | Worktree now | Baseline `8c7b18b` |
|---|---|---|---|---|
| D1 | §2.6 | `createProposal` L243 | **242** | 243 ✅ |
| D2 | §2.6 | `doReject` L436 | **435** | 436 ✅ |
| D3 | §2.6 | `doAccept` L1103 | **1102** | 1103 ✅ |
| D4 | §2.6 | `doCounter` L1268 | **1267** | 1268 ✅ |
| D5 | §2.6 | `updateAgreedRate` in `doCounter` L1299 | **1298** | 1299 ✅ |
| D6 | §2.8 | `listMessages` method L602 | **601** | 602 ✅ |
| D7 | §2.8 | `sendMessage` L619-620 | **618-619** | 619-620 ✅ |
| D8 | §2.8 | `seedNotesMessage` / mapper `new DealMessageResponse` L2181 / L2194 | **2202 / 2215** | 2181 / 2194 ✅ |
| D9 | §3.6 | unread computation L2002-2007 | **2018-2024** | 2002-2008 (spec off by 1 on the end) |
| D10 | §3.6 | `hasEscrowForCollaboration` L2024-2026 | **2045-2047** | 2024-2026 ✅ |
| D11 | §3.6 | `escrowFunded` not-a-column comment L1478 | **1477** | 1478 ✅ |
| D12 | §3.7 | `DealServiceTest` CounterRequest L605/645/708/749/768/982/1654/2203/2316 | **607/647/710/751/770/984/1656/2205/2318** | as spec ✅ |
| D13 | §4.1 | `ContractService` L509-514 (`DeliverableType.valueOf` L512) | **L818-823 (valueOf L821)** | L512 ✅ |
| D14 | §8.1 | `CreatorAgentPreferences` api.ts L6019-6052 | **L6060-6095** (doc comment L6058) | — |
| D15 | §8.1 | `CreatorAgentPreferencesUpdate` L6059-6062 | **L6100-6103** | — |
| D16 | §8.1 | `export const api = {` L6313, keys L6314-6359 | **L6354, keys L6355-6400** | L6354 at HEAD too (see 3b) |
| D17 | §8.1 | `creatorAgentPrefs` L6108-6122 | **L6149-6200** (block is 52 lines, not 15) | — |
| D18 | §8.1 | `publicCreators.getVerifiedMetrics` L6196-6202 | **L6237-6256** | — |
| D19 | §8.9 | `/c/:username/verified` L818, `/:handle` L825, `/features/secure-payments` L652 | **823 / 830 / 657** | 818 / 825 / 652 ✅ |

`api.deals` (§8.1 L2106-2216, `counter` L2158, `dealTerms` L2173, `deals.create` dealTerms L2211) sits **above** the api.ts change region and **HOLDS unchanged**. Confirmed there is still **no `risks:` method** on `api.deals`.

### 3b. Caused by HEAD moving past the spec's baseline (1 item)

| # | Spec ref | At `8c7b18b` | At `143ca1e` |
|---|---|---|---|
| D20 | §8.1 | `export const api = {` L6313 | **L6354** (`143ca1e` added +88 lines to `api.ts`) |

That is the **only** spec-referenced anchor that `143ca1e` moved. Every other Java, Python, CSS and TSX anchor is byte-identical between `8c7b18b` and `143ca1e`.

### 3c. Spec was already wrong at its own baseline (7 items)

| # | Spec ref | Spec says | Truth at `8c7b18b` (and at HEAD) |
|---|---|---|---|
| D21 | §2.8 | `toMessageResponse` called from `listMessages` at **L611** | **L613** |
| D22 | §2.8 | `toMessageResponse` called from `sendMessage` at **L664** | **L659** |
| D23 | §5.1 | `Collaboration` getters L180/188/210/214/218/222/226/230/234 | **L179/187/209/213/217/221/225/229/233** — all **+1** in the spec (the getters exist with exactly the stated types; `getMaxRevisions()` returns `int`, not `Integer`) |
| D24 | §5.1 | `Campaign.getEndBrandName()` L238, `getEndBrandCategory()` L242 | **L237 / L241** |
| D25 | §7.2 | `assembler.py` L832 is `tools = []` | **L832 is `        tools: list[dict[str, Any]] = []`** — an *annotated* assignment. A literal-string anchor on `tools = []` will not match. |
| D26 | §7.5 | `creator_suggestion.py` L216-226 (the `to_thread` wrapper) | **L213-226** (`try:` at L213, `await anyio.to_thread.run_sync(` at L218, `except AuthError` at L225) |
| D27 | §2.10 / §7.2 | drift-test assertions at **L67** and **L82** | Those are the `def` lines. The set-equality is **two directional asserts at L72 and L78**; the sortedness assert is at **L84**. L87/L98/L103/L119-143/L157-159/L170 all ✅ correct. |

### 3d. Minor AI-service / frontend shape drift (cosmetic, listed for completeness)

- §7.2 `_FORBIDDEN_BRAND_FIELDS` (L70-103 ✅) has **26** entries, not 25. None of the six names Phase B adds collide ✅.
- §7.3 the idempotency `if` is at **L454** (spec said the L453-455 range — correct as a range).
- §7.5 `app/main.py` L74-107 holds four optional-router blocks ✅ but they are **not byte-identical**: the first three end with a one-line `logger.exception(...)`, the fourth spans L105-107.
- §7.5 pricing: `PRICING_TABLE` starts at **L103** (5 rows); the three-model pin the spec means is the import-time loop at **L120** (`CLAUDE_MODEL`, `GEMINI_MODEL`, `TRENDSPARK_MODEL`). `claude-haiku-4-5-20251001` is priced at L106 ✅ — so defaulting `BRIEF_EXTRACT_MODEL` to `TRENDSPARK_MODEL` is safe as claimed.
- §8.6 `afterDealMutation` — the **declaration** is L883; **L885 is the `Promise.all([refreshDeal, loadMessages])` line** the spec means ✅.
- §8.6 `counter-proposal-form.tsx` `useState<CounterProposalFormData>` is **L39-44** (L38 is the `step` state). Zero `zod` / `useForm` / `resolver` hits ✅ as claimed.

---

## 4. Spec-internal inconsistencies

### I1 — §2.6 DDL lacks the unique key its own verdict requires (CONFIRMED)

§2.6's `CREATE TABLE deal_offer_history` ends:

```sql
    INDEX idx_doh_collab_seq (collaboration_id, sequence_no)
```

§13.2 risk 6 and §13.3 **condition 3** require `UNIQUE KEY uk_doh_collab_seq (collaboration_id, sequence_no)`. Confirmed: the DDL has only a non-unique index, and `uk_doh_collab_seq` appears nowhere in §2.6.

**Corrected §2.6 DDL** (replace the `INDEX` line; do **not** keep both — a unique key already provides the index, and two near-identical names on the same columns is a maintenance trap):

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
    UNIQUE KEY uk_doh_collab_seq (collaboration_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

And §2.6's prose must say how `sequence_no` is derived: `countByCollaborationId(id) + 1` **read inside the same transaction that already holds the collaboration row lock** (`createProposal`, `doCounter`, `doAccept`, `doReject` all run under `requireOwnedCollaboration` + `IdempotencyService.executeOnce`). Without stating that, the unique key converts a silent duplicate into a 500 on a concurrent counter.

### I2 — "ten risk rules" vs the fourteen actually listed (NEW)

§1 (row B4): *"Deal risk rules: **ten** deterministic rules with reason codes."*
§11 Q6: *"Do the **ten** risk rules fire on the right data … Show one firing and one non-firing test per rule."*
§5.2 lists **fourteen**: `BELOW_FLOOR`, `USAGE_PERPETUAL`, `USAGE_LONG`, `EXCLUDED_CATEGORY`, `BLOCKED_BRAND`, `OFF_PLATFORM_PAYMENT`, `COMPETITOR_CONFLICT`, `EXCLUSIVITY_LONG`, `VAGUE_DELIVERABLES`, `HIDE_DISCLOSURE`, `BARTER`, `REGULATED_CATEGORY`, `CALENDAR_OVERLOAD`, `PARTNERSHIP_ADS_REQUEST`.

This matters because §9 sizes `DealRiskServiceTest` as "section 5.2 list" (28 tests) while §11 sizes acceptance at 20. Fix the two "ten"s to "fourteen", or cut four rules and say which.

### I3 — `Rendered` is a day-1 dependency scheduled nowhere

See BROKEN-B5. §10's day-1 backend cell lists `CreatorContextResponse +5 and its one call site` but not the class that renders component 29.

### I4 — §3.9 misses a `ConversationExportResponse` construction site (NEW)

§3.9: *"extending `CreatorAgentDtos.ConversationExportResponse` … to 5, and updating its **one** construction site in `exportConversation` plus any assertion in `CreatorAgentConversationServiceTest`."*

There are **two** `new ConversationExportResponse(...)` sites:
- `service/CreatorAgentConversationService.java:103` ✅ (named)
- **`test/java/com/influora/web/CreatorAgentControllerTest.java:185`** ❌ (not named)

The record is confirmed 3 components at `CreatorAgentDtos.java` L90-93 ✅; `exportConversation` L84 ✅ and `deleteConversation` L107 ✅ are both correct.

### I5 — §2.7 declares a migration filename and then forbids it

The heading is `### 2.7 Migration V20260910100600__collaborations_calendar_hint.sql (alter)`; the body says *"skip this file."* §10 day 1 correctly says "Migrations 2.1 to 2.6" and §9/§11 correctly say "six migrations" — so the counts are consistent, but a reader who works from headings will create a seventh migration. Retitle to `### 2.7 Calendar overload (no migration)`.

### I6 — §3.7 lists three draft routes; §8.1 needs four

The §3.7 route table has `GET /creator/meera/drafts`, `POST .../{id}/approve`, `POST .../{id}/discard`. `POST /creator/meera/drafts/level-up-seen` appears only in the prose sentence after the table, but §8.1's `creatorMeeraDrafts.levelUpSeen()` and §8.7's `LevelUpPrompt` both depend on it. Add it to the table.

### I7 — `RateCardDto` has three fields in §3.10 prefs and four in §3.10 media kit

Prefs: `rate_card (RateCardDto{reel, story_set, post})`. Media kit: `rate_card {reel, story_set, post, currency}`. Probably two different DTOs, but the spec uses the same name for both and §8.1's TS type declares three. Name them apart (`RateCardDto` / `PublicRateCardDto`) or add `currency` to both.

### I8 — §3.4's default rate-limit key needs a new key-derivation branch, not a bucket entry

§3.4 default (a): *"key on the on-behalf JWT's `sub` — the filter can read the `X-Onbehalf-Authorization` header."* Verified in `security/AuthRateLimitFilter.java`: keying today runs through `isUserKeyedBucket(bucket)` (L449) → the ordinary `Authorization` JWT. On `/internal/**` that header carries the **service** token, not the creator. So the "five edits per facts/backend.md 5.3" undercounts: it is five edits **plus** a new key-derivation branch that parses the on-behalf header. Say so, or take option (b) (cap in `CreatorToolCallValidator`), which is genuinely cheaper and already has `creatorUserId` in hand.

---

## 5. Everything that HOLDS (grouped)

**Backend — all verified unchanged at HEAD and in the worktree unless noted:**

- `CounterRequest` **6 components** (`DealDtos.java` L125-135) and exactly **14** construction sites — 9 in `DealServiceTest`, 5 in `DealControllerTest` (L142, L143, L153, L165, L284, **unchanged**). `DealService`/`DealController` only reference the type (`DealController` L109 binds it) ✅.
- `RejectRequest(@Size(max = 500) String reason)` L137 ✅; `SendMessageRequest(@NotBlank @Size(max=5000) content, kind)` L150-152 ✅; `DealTermsDto` **7 components** L75-82 ✅; `DeliverableSlot(@NotBlank type, @NotNull @Positive qty)` L110 ✅; `DealMessageKind` = 7 values incl. `text` ✅.
- `parseReadBy` is **already `private static`** (worktree L2355) — §3.6's "must become static too" is already satisfied; only visibility needs raising.
- `public DealResponse counter(AuthPrincipal, String, CounterRequest, String)` and `public OkResponse reject(AuthPrincipal, String, RejectRequest, String)` — **four args each** ✅; `public DealMessageResponse sendMessage(AuthPrincipal, String, SendMessageRequest)` — **three args** ✅.
- `PreferencesResponse` **18 components** (`CreatorAgentDtos` L25-46) with exactly **4** construction sites: `CreatorAgentPreferencesService:288`, `CreatorAgentControllerTest:65`, `:86`, `CreatorMeeraControllerTest:186` ✅. (`NotificationController:262` constructs a *different* `PreferencesResponse` from `NotificationDtos` L61 — not a fifth site.)
- `UpdatePreferencesRequest` **16 components** (L52-70) with exactly **9** sites at the spec's line numbers: `CreatorAgentPreferencesServiceTest` L156/198/210/221/239/252/263, `CreatorAgentControllerTest` L82/131 ✅.
- `CreatorAgentPreferences` entity: `aiMonthlyCapUsd` L139, `newWithDefaults` L155 (6-arg), `applyPreferences` L299 with **16** params, `import java.time.Instant` L8 and **no `LocalDate` import** ✅.
- `CreatorAgentPreferencesService`: `requireCreatorProfile` L67, `getOrCreatePreferences` L77, `isConsentAccepted` L95, **`createWithComputedDefaults` L103** (private, reached from L82, L154, L227, L279 — all four ✅), `newWithDefaults` call L107, `updatePreferences` L150 with the 16-arg `applyPreferences` at L156, `toResponse` L285 / ctor L288 ✅. No `getByProfileId` exists ✅ (day-1 addition confirmed necessary).
- `CreatorContextResponse` L166-215 with exactly **27** `@JsonProperty` components ✅; **one** construction site, `MeeraContextService:274` ✅; no test constructs it ✅.
- `MeeraContextService`: `ACTIVE_DEAL_STATUSES` `private static final` L93 ✅, read at L384 ✅; `deriveTier` `private static` L415 returning **`"MEGA"`** for ≥1M ✅; `CreatorTier` has only `NANO, MICRO, MID, MACRO` ✅ (so `valueOf("MEGA")` would indeed throw).
- `AuditLogService` (in `com.influora.service`, **not** `service/meera`): `OUTCOME_ALLOWED` L32 / `OUTCOME_REJECTED` L33 / `OUTCOME_FAILED` L34 ✅, no `OUTCOME_OK` ✅; `recordToolCall(String, String, String, String, String, String, BigDecimal, Map)` L44-52 ✅.
- `ToolCallValidator.ToolCallRejectedException` L53 (public static final class) ✅; `MeeraToolTier` = `R, D, C, FORBIDDEN` ✅; `MeeraToolName` = exactly **6** values ✅ and `ToolCallValidatorTest:149` asserts it ✅.
- `OnBehalfAuthResolver` (`com.influora.security`): `OnBehalfContext(userId, workspaceId, userType, conversationId)` L78 ✅, `resolveForWorkspace` L86 ✅, `resolveForWorkspaceRequiringScope` L150 ✅, `requireScope` L178 ✅.
- `MeeraSessionService`: ctor L102-112 with **10** params ✅; **`MeeraSessionServiceTest:81` is the only construction site** ✅; `doSendTurn` L309 ✅; `isCreatorTurn` L332 ✅; `onBehalfTokenService.mint(...)` L397 ✅. `CreatorMeeraControllerTest:70` constructs the *controller* ✅.
- `OnBehalfTokenService`: `SCOPE_DEFAULT` L68 ✅, 5-arg `mint` L82 ✅, `.claim("scope", SCOPE_DEFAULT)` L105 ✅.
- `CreatorMeeraController.requireFeatureEnabled()` **private** L104-109 ✅; `MeeraCreatorFeatureProperties` is a `@Component` with `isCreatorEnabled()` L35 ✅.
- `JwtService.hashToken` **public static** L61 ✅; `AuthPrincipal(userId, email, userType, workspaceId)` L17 ✅; `Ulids.newUlid()` L9 ✅; `TextSanitizer.sanitizePlainText` L23 ✅; `JsonLists.stringListFromJson` L37 ✅.
- Repositories: `CollaborationRepository.findByCreatorId` L101 ✅, `findRateBandCandidates(@Param("niche"))` **single-arg** L75 ✅ with `RateBandCandidateRow` projection L40 ✅, `existsByCampaignIdAndCreatorId` L77 ✅; `EscrowHoldRepository.hasEscrowForCollaboration` L153 ✅ and `findByCollaborationIdAndStatus` L157 ✅ (the trap the spec correctly warns against); `DealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc` L15 / `existsByCollaborationIdAndSenderType` L18 ✅; `CreatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc` L40 ✅; `MediaMetricsRepository` same L31 ✅.
- `QualityScoreService.calculate(Optional<CreatorMetric>, List<MediaMetric>)` L58, **two args** ✅, `QualityScoreResult.absent()` L48 ✅; `ScoreCalculationJob` L280-281 / L291, `RECENT_MEDIA_LIMIT = 30` L101 ✅. `RateEstimationService.estimate` L76 ✅, `CATEGORY_MULTIPLIERS` `private static final` L45 ✅.
- `Collaboration` V72 fields all present with the stated types (`getUsageChannels()` and `getExclusivityBrands()` return **`String`** ✅); `Campaign` has `budgetMin`/`budgetMax` L39/L42 and `startDate`/`endDate` as **`LocalDate`** L48/L51 ✅; `UsageChannel` **5** values ✅; `ExclusivityScope` **3** values ✅; `DeliverableType` **9** platform values ✅ (the name collision is real).
- `CreatorCampaignService.apply(AuthPrincipal, String, ApplyRequest)` L208 → `ApplyResponse` ✅; `ApplyRequest(@Size(max=2000) String message)` L70 and `ApplyResponse` L72 — both in `web/dto/**creatorcampaign**/CreatorCampaignDtos.java` ✅.
- ShedLock: `V68__shedlock.sql` present ✅; **ten** jobs already carry `@SchedulerLock` (spec said five — the claim holds a fortiori), incl. `AICreditResetJob:54` and `CreatorCaptionSyncJob:86` ✅.
- `AuthRateLimitFilter`: `MEERA_TURN = ^(/creator)?/meera/sessions/[^/]+/messages$` L105-106 ✅, `influora.meera.turn-rate-limit-per-window:20` L204 ✅, `PUBLIC_CREATOR_VERIFIED` L112-113 ✅, bucket→limit switch L420-423, `isUserKeyedBucket` L449 ✅. No `creator-tool-rate-limit-per-window` property exists yet ✅.
- `SecurityConfig` `permitAll` on `GET /public/creators/*/verified` L177-178 ✅; `PublicCreatorDtos.VerifiedMetrics` L18 ✅.
- Flyway: **`V20260903170000__creator_agent_preferences_timezone_currency.sql` is still the highest**, no uncommitted migrations ✅. CHAR precedent `V20260718150000__char_to_varchar_remaining.sql` present ✅, `workspace_member_invites.invite_token_hash VARCHAR(64)` ✅.
- `influora.web-base-url` = `application.yml` **L133** `${INFLUORA_WEB_BASE_URL:http://localhost:5173}` ✅; `influora.api.public-url` L145 ✅; **no `influora.public-base-url`** ✅; `AuthService` L72 `@Value("${influora.web-base-url}") private String webBaseUrl;` ✅; `CREATOR_COPILOT_PROMPT_VERSION` L479 ✅ (unrelated constant, as stated).
- `CreatorSuggestionAiClient.requestSuggestion(String creatorProfileId, String theme, String trendText)` → `SuggestionCopy` (L102) — a profile-id-keyed, creator-scoped client ✅, exactly the shape §3.8 says `MeeraBriefAiClient` should mirror.
- `Collaboration.propose(id, campaignId, creatorUserId, amount, …)` L326 ✅ and `CampaignService.create(AuthPrincipal, CampaignWriteRequest)` L145 with a structured `CampaignWriteRequest` L60 ✅ — §13.2 risk 8's characterisation is accurate.
- `DealController` has **no** `/risks` route today ✅.

**AI service — all verified on a clean tree (`git status --porcelain influora-ai ci .github` is empty):**

- `schemas.py`: `get_tool_schemas()` **zero-arg** L425 ✅; `is_known_tool` L455-456 body exact ✅; `TOOL_NAMES` L38 (6 names) / `LOCAL_TOOL_NAMES` L66 / `TOOL_TO_SPRING_PATH` L78 / `IDEMPOTENT_REQUIRED_TOOLS` L89 (`create_campaign, request_payment, confirm_launch`) / `TOOL_SCHEMAS` L91 ✅; `get_analyze_creator_content_schema` L596-602 zero-arg returning `ANALYZE_CREATOR_CONTENT_SCHEMA` L508, rationale comment L469-485 ✅.
- `loop.py`: **L452 `path = TOOL_TO_SPRING_PATH[tool_name]`** — bracket subscript, outside the `try` ✅ (the highest-risk line, confirmed); `try:` L465, `except SpringCallError as exc:` L474 ✅; money decline **L494-509** ✅; `allow_retry=` **L472** ✅; `is_known_tool` called at L327, yields `unknown_tool` + `continue` ✅.
- `assembler.py`: `_FORBIDDEN_BRAND_FIELDS` L70-103 plain set, **none of Phase B's six names collide** ✅; `_strip_forbidden_fields` L204-205 with its **only** call site `build_block_b` L394 ✅; `CREATOR_CONTEXT_PAYLOAD_FIELDS` L122-165, **27 entries, alphabetically sorted** ✅; `CREATOR_CONTEXT_FIELDS_NOT_RENDERED` L169-171 frozenset (5 entries) ✅; `build_block_a_creator()` **zero-arg** L435 with `"none in this phase"` at **L444** ✅ and exactly **three** call sites — `assembler.py:830`, `test_creator_prompt.py:90`, `test_info_barrier.py:222` ✅; `build_block_b_creator(context)` L541 narrowed to **`ctx`** at **L557** ✅; `assemble_prompt` L809, CREATOR branch L827-836 ✅.
- `config.py`: `PROMPT_VERSION = "meera-2026.08.10.1"` **L69**, and repo-wide grep confirms **no test, Java, YAML or env file pins the literal** ✅ — the bump is safe; `TRENDSPARK_MODEL` L132 = `claude-haiku-4-5-20251001` ✅, `CREATOR_COPILOT_MODEL` L149 ✅.
- `service_token.py`: `ENDPOINT_SCOPES` L53-69 with **7 keys**, `"creator_suggestion": (SCOPE_CREATOR,)` at L68 and **no `brief_extract` key** ✅; `verify_creator_token(token, *, endpoint, body_creator_profile_id)` L420-425, **synchronous `def`** ✅.
- `creator_suggestion.py` L265 `workspace_id=creator_profile_id` with the explicit comment ✅.
- `claude.py`: `complete_with_forced_tool` `async def` L362 with bare `*` at **L364** ✅ (params `system_blocks, messages, tool_schema, max_tokens=1024, model=CLAUDE_MODEL`); `ClaudeToolResult` L83-93 with `ok, tool_input, error, usage` and **no `.text`** ✅; `no_tool_use_in_response` returns `ok=False` **with usage** L428-430 ✅.
- `creator_persona.py:86` `"NO tools in this phase"` inside `MEERA_CREATOR_PERSONA`, blocks L78-83 and L85-94 ✅.
- `untrusted.py:47` `wrap_untrusted(label, content)` positional ✅.
- `test_creator_context_drift.py`: `_NOT_RENDERED_BY_DESIGN` L44 ✅; regex `ctx(?:\.get\(|, )['"]…['"]` **L98** ✅; `distinctive` dict L119-143 ✅; `_PREREQUISITES` L157-159 ✅; `_FORBIDDEN_BRAND_FIELDS` assert L170 ✅; **`pytest.fail()` at L54, never skips** ✅; parses `influora-api/src/main/java/com/influora/web/dto/meera/MeeraContextDtos.java` via `parents[3]` ✅ — the Java↔Python same-commit coupling is real.
- `test_tool_schema_anthropic_valid.py`: `_all_tool_schemas()` **L59-61**, three shared `@pytest.mark.parametrize` at **L64/L77/L90** ✅ — one edit does cover all three.
- `test_prompt_injection.py`: L568-573 six probes (none collide with the nine creator names) ✅; `test_exactly_six_tools_exist_and_tiers_match_spec` **L576** ✅.
- `test_info_barrier.py`: L219-225, L222 call site, L228-239, L242-248 ✅ exactly as the §7.4 table says.
- `test_creator_prompt.py` L50/53/54/56/57/58/**60**/62, L90, L192 ✅; `test_creator_settings_in_prompt.py:86` ✅; `test_chat_creator_audience.py` L491/L579/L615 ✅ and **`tools_enabled` appears nowhere in that file** ✅ — so the §7.2 degrade rule does keep them green.
- `ci/stale-comment-check.py:270` `re.search(r"^\+\s*PROMPT_VERSION\s*=", diff, re.M)` ✅ — the bump is mandatory.
- `schema-check.yml` MeeraToolName ↔ TOOL_SCHEMAS blocking diff present (L43-72, L100-105, compare L206-207) ✅ and untouched by Phase B ✅.

**Frontend:**

- `http.request` L536-545 with `role = 'brand'` default at **L546** ✅; `mockOr` L775-778 and `isLive` L780, both module-private ✅.
- `api.deals` L2106-2216 ✅, `counter` L2158 ✅, **`dealTerms?: DealTerms` already present at L2173** and on `deals.create` at L2211 ✅ (only `meeraDraftId` is new); **no `risks:` method** ✅.
- api.ts L47 re-exports only `DeliverableStatus, ContractStatus` ✅; `DealTerms` is *imported* at L32, not re-exported — import from `@/lib/types` as the spec says ✅. `types.ts` `DealTerms` L31-39 with the 7 fields ✅.
- **All 19 new type names and all 6 namespace names are unused anywhere in `src/`** ✅ — zero collisions, re-confirmed on the current tree.
- `meera-api.ts:399` `type MeeraRole = …` — **not** `export type`, not exported anywhere ✅; `MeeraToolStartEvent` L165 / `MeeraToolResultEvent` L170 both exported ✅.
- `useMeeraStream.ts`: `MeeraStreamHandlers` L47-59 **already declares** `onToolStart` L50 / `onToolResult` L51 ✅; dispatcher already routes `tool_start` L184-188 / `tool_result` L189-193 ✅ — **no hook change needed**, as §8.3 says.
- `MeeraCopilotChat.tsx`: `ChatMessage` L28-32 ✅; `stream.open` handlers L231-288 with `onToken` L232 / `onDone` L243 / `onError` L255 / `onHeartbeatTimeout` L275 and **no tool handlers** ✅; `messages.map` L357-368 ✅; fork comment L18-25 ✅.
- `MeeraChatPanel.tsx` is at **`src/components/feature/meera/`** ✅ with the lazy-bubble pattern at L538-570 ✅.
- `creator-chat.tsx`: `DealTermsSummary` L2434-2439 (element L2437) ✅ and L2639-2644 (element L2642) ✅; `refreshDeal` L728-770 with the supersede-token guard L731-738 ✅; `handleSubmitCounterForm` **L1466** ✅ passing only `{amount, message, deadline}` at L1482-1486 ✅; `CounterProposalForm` wired at **L3144** ✅.
- `counter-proposal-form.tsx`: `CounterProposalFormData` L14-19 with exactly the four stated fields ✅; props interface L21-28 ✅; **no zod / react-hook-form** ✅.
- `creator-deals.tsx` `DealTermsSummary` at **L652** ✅.
- `MeeraSettingsSection.tsx`: `type Draft = CreatorAgentPreferencesUpdate` **L125** ✅, `toDraft()` **L127** ✅, Phase A disclaimer L454-456 (text L455 verbatim) with the RadioGroup closing L453 ✅, represented note L603-605 (text L604 verbatim) ✅, `text-destructive-foreground` save error **L608** ✅.
- `creator-copilot.tsx`: ConsentScreen import L10 ✅, `openMeera` L87 (the `getPreferences()` call is inside it at L91) ✅, FEATURE_DISABLED catch L99-102 ✅, static fallback L129-142 ✅, Phase A JSX comment L125-128 ✅, title L148 / description L150 verbatim ✅, ConsentScreen rendered L184 ✅.
- `globals.css`: `--card` L16, `--muted` L24, `--destructive` L28/L148, `--destructive-foreground` L29/L149, `--border` L36, `--stage-negotiating` L69, `@theme inline` L191, `--color-stage-negotiating-fg` L243 / `-border` L244 — **all six tokens real** ✅; **`src/index.css` does not exist and there is no `tailwind.config.*`** ✅.
- `src/components/shared/deal-terms-summary.tsx` + its co-located test exist ✅; `deal-risk-card.tsx` does not ✅.
- Test patterns: `creator-copilot-feature-disabled.test.tsx` L33-53 `vi.hoisted` + `importActual`-spread ✅; `MeeraCopilotChat.test.tsx` L27-36 full-replacement form ✅.

---

## 6. The uncommitted money-flags tree — impact assessment

**Does it touch a symbol Phase B extends?**

| Symbol Phase B extends | Money-flags touches it? | Verdict |
|---|---|---|
| `DealService.createProposal` / `doCounter` / `doAccept` / `doReject` | **No** — hunks are in `requireWithinRemainingBudget` (worktree L1726-1800, rewritten javadoc + new `private static committedValue`) and `toDealResponse`'s contract resolution (L2031, F-0653) | Adjacent, not overlapping |
| `DealService.toMessageResponse` / `publishToStream` / `seedNotesMessage` / `listMessages` / `sendMessage` | **No** | Clean |
| `DealService` unread computation (§3.6 extraction target) | **Almost** — the unread block is worktree **L2018-2024**, the F-0653 hunk starts at **L2031**. Seven lines apart. | Merge-clean but re-read on merge |
| `CampaignService.create` (§8.1 redeem) | Modified (+44/−0) but Phase B only **calls** it, never edits it | Clean |
| `ContractController`, `EscrowService`, `EscrowController`, `MoneyDtos` | Modified, **Phase B touches none of them** | Irrelevant |
| `DealServiceTest` (9 `CounterRequest` sites) | Modified (+127: 2 imports at top, 125 lines appended at EOF). Still exactly 14 `CounterRequest` sites tree-wide. | No textual overlap; sites shifted +2 |
| `DealControllerTest` (5 `CounterRequest` sites) | **Not modified** | Clean |
| `src/lib/api.ts` | Modified, change confined to **L3590-3663** (`safeRandomUUID` + `payments.releasePayout` XOR rewrite). Every Phase B anchor is above L2216 or below L6060. | Clean |
| `src/App.tsx` | Modified at **L88-149** (`readAuthToken` / `CreatorProtectedRoute` exported for F-0459). Phase B inserts routes at L823-830. | Clean |
| `ContractService` | Modified (+390). Phase B only reads `DeliverableType.valueOf`'s existence as a "do not touch" argument. | Irrelevant |

**Would a Phase B day-1 branch from `8c7b18b` conflict on merge?** Not textually. Day 1 is migrations, entities, repositories, `BriefDtos`, `CreatorToolDtos`, `CreatorContextResponse`, `CreatorAgentPreferencesService.getByProfileId`, the two prefs records, `InfoBarrierTest` — **none of which the money-flags tree touches at all**. Days 4-6 (offer history in `DealService`, the metadata strip) are where the two meet, and even there git will auto-merge; the risk is semantic re-anchoring, not conflict markers.

### Recommendation: **branch from HEAD `143ca1e`.**

1. `8c7b18b` → `143ca1e` moved **exactly one** spec-referenced anchor: `api.ts`'s `export const api = {` from L6313 to L6354. Every other Java, Python, CSS and TSX anchor is byte-identical. Branching from `8c7b18b` therefore buys nothing and guarantees a later rebase over `143ca1e`.
2. Branching from `143ca1e` gives a **clean tree**, so Phase B never inherits the money-flags edits and §0.12's "never commit unrelated dirty files" becomes automatic rather than a discipline problem. This matters more than usual here: a second session is actively editing this same working tree right now (33 files, changing during this very review), and this repo has a documented history of cross-session write collisions on `DealService.java` specifically.
3. **Do not wait for the money-flags branch to land.** Phase B day 1 has zero intersection with it. Instead sequence it: start Phase B day 1 immediately, and let money-flags merge before Phase B reaches **day 4-6** (the `DealService` offer-history + metadata-strip days). If money-flags lands first, re-derive the `DealService` anchors once (+21 below L1726) rather than resolving them nine times.
4. Update §0.12 from *"Branch from `8c7b18b`"* to *"Branch from `143ca1e` (clean tree — stash or discard the money-flags work first)"*, and add a day-4 checkpoint: "re-verify the `DealService` line anchors against whatever has landed on `main`."

---

## 7. Verdict

**Buildable against the current tree, as written: no.** Seven BROKEN items stand: five in the backend (a 9-site compile break in §2.8, an unobtainable `sent_message_id` on three of four send paths, an `InfoBarrierTest` widening that is eight lines not one, a `private static` helper the redeem path cannot call, and a formatting class that is a day-1 dependency scheduled nowhere), one in CI (a "blocking" schema gate whose awk range has run to EOF for months and which Phase B's day-1 commit is the exact trigger for), and one false uniqueness claim about `<Routes>`.

**Buildable as corrected: yes**, on six conditions — the three from §13.3, plus three new ones:

1. *(carried)* The §10 day-1 block lands as one merged commit across all three services before parallel work starts. The record arities, `getByProfileId`, the `ENDPOINT_SCOPES` entry and the drift-test coupling are day-1 or nothing.
2. *(carried, restated)* `InfoBarrierTest`'s scan roots are widened to `service/**` and `job/**` — **as an ~8-line restructure of L68-76, not a one-line list edit**. Verified safe: no existing class outside the allow-list imports the repository.
3. *(carried)* `deal_offer_history` gets `UNIQUE KEY uk_doh_collab_seq (collaboration_id, sequence_no)` — corrected DDL in §I1 above — **and** §2.6 states that `sequence_no` is derived under the existing row lock.
4. **(new)** §2.8 is rewritten to the 3-edit `stripAgentMetadata` form, or all nine `toMessageResponse` call sites are enumerated.
5. **(new)** §3.7 states that `sent_message_id` is REPLY-only and nullable, and the approve-replay guard is re-specified accordingly.
6. **(new)** `.github/workflows/schema-check.yml`'s context-field awk range is repaired and proven green **before** day 1 lands `MeeraContextDtos.java` and `assembler.py`.

Beyond those, add `com.influora.common.Rendered` to §10's day-1 cell, fix "ten"→"fourteen" risk rules in §1 and §11, add `CreatorAgentControllerTest:185` to §3.9's export-record call sites, add `level-up-seen` to §3.7's route table, retitle §2.7, disambiguate the two `RateCardDto`s, and correct §3.4's rate-limit edit count. All 27 DRIFTED line references need a refresh, but 19 of them fix themselves the moment you branch clean.

**What to do first:** repair the `schema-check.yml` awk range and confirm it emits exactly the 15 brand field names — that gate is the only thing in this list that turns red on day 1's own commit rather than on the day the code is written.
