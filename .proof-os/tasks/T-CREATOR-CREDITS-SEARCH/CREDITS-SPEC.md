> **SUPERSEDES `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/CREDITS-SPEC.md`** (the Phase B original, which stays frozen and untouched): that file predates Swapnil's 2026-09-19 decisions on decimal credits and paid web search, and its §4.3 is written against `CreatorBriefService.paste` as unbuilt code. Build from THIS file.

# Meera for Creators — Credits build spec (CREDITS-SPEC)

**Task id:** T-CREATOR-CREDITS-SEARCH (amended from T-MEERA-CREATOR-CREDITS, a sub-track of Phase B)
**Written:** 2026-09-05 against HEAD `eac5e58`, branch `feat/meera-creator-phase-e`
**Amended:** 2026-09-20 by Priya (CTO) against HEAD `00fe23a`, branch `feat/creator-credits-search`, worktree `influora-credits` (cut from `release/0919` @ `9bfe7dc`)
**Amendment source:** `.proof-os/tasks/T-CREATOR-CREDITS-SEARCH/PLAN.md` — Swapnil's decisions (§1), the open questions (§2), the build order (§3) and the costs (§5). Where this spec says "PLAN.md §x", read it there; it is not restated here.
**Facts:** `facts/credits-billing.md` (every symbol in the un-amended text was opened there; line numbers are from that read)
**Product inputs:** `finance/credits-gtm-plan.md` (Tejas), `finance/meera-credit-sheet.md` (Rohan)
**Audience:** the engineer who builds it. Every file and every step. Must compile against the current tree.

---

## What changed on 2026-09-20 and why

Five amendments, each edited into the section it belongs to and marked inline with `<!-- PRIYA 2026-09-20 -->`. Nothing is appended as an addendum; if a section is unmarked, it is unchanged from the Phase B original.

| # | Amendment | Sections touched | Why |
|---|---|---|---|
| A1 | **Credits are decimal to one place, stored as INT TENTHS.** 40 credits = `400`. Costs become chat 10, voice 20, brief 30, search 25. | §0 rule 11 (the invariant, stated once), §1 (K9), §2.1, §2.2, §2.3, §2.4, §2.5, §3.1, §3.3, §4.1, §4.2, §6, §7.1, §7.3, §8, §9, §10.1, §10.4, §10.6, §11, §13 Q4, §15 O1 | Swapnil, 2026-09-19: a 2.5-credit search cannot be expressed in whole credits, and rounding a search up to 3 would make the pack maths lie. |
| A2 | **Brief charge re-anchored to shipped B0 code.** §4.3 was written against "Phase B1 `CreatorBriefService.paste` (not yet built)". Paste shipped in B0; the charge now names real lines, and the charge site moves from `analyse` to `paste`. | §4.3, §12 step 10 | The spec's own rule 1 (never instruct a change to code you did not read), and a real double-charge defect: `analyse` has three callers. |
| A3 | **New K12, web search.** 25 tenths per search, 2 free per week, new route `POST /creator/meera/search`, three new ledger reasons, its own rate-limit bucket. | §1 (new K12 row), new §4A | Swapnil, 2026-09-19. |
| A4 | **Weekly free-search counter.** Two columns on `creator_ai_credits`, a lazy Monday-IST roll, and an atomic claim. | §2.1, §2.6, §3.1, §4A.3 | PLAN.md D1–D3. Defaults are written and marked as awaiting Swapnil's word. |
| A5 | **R4 now includes search fees in the USD fuse.** `influora-ai/app/costs/pricing.py` must change; it has no search rows and no per-request fee concept at all. | §1 (K11), R4, §4A.5 | A search fee is per-request, not per-token, so the existing cost formula cannot see it. |

**What I found wrong in the old spec about today's code** (each corrected in place with a dated note, per the spec's own rule 1):

1. **§4.3's premise.** `CreatorBriefService.paste` is built and shipped (`influora-api/src/main/java/com/influora/service/CreatorBriefService.java` L199–209). The old §4.3 is unbuildable as written and its charge site is wrong — see §4.3.
2. **§4.1 step 3's line is stale and its unit is now unsafe.** `creditsCharged = 0` for a creator is at `MeeraSessionService.java` **L646** (inside `if (userType == UserType.CREATOR)` at L645), not L576. And `ai_messages.credits_charged` is a SHARED `INT` column whose own comment reads "1 per exchange, 10 for analysis" (`V12__ai_conversations_messages.sql` L21) — writing 10 tenths there collides with brand's 10-means-analysis. See §4.1.
3. **§6's shared-DTO assumption.** `CreditsSummary` is `(int remaining, boolean unlimited)` (`MeeraDtos.java` L23) and `SendTurnResponse.creditsRemaining` is `int` (L56); both are shared with the brand path, and `CreditsSummary` has exactly one construction site, `MeeraController.java` L113. Neither can become decimal. See §6.
4. **§3.3's cited precedent line.** `AICreditService.tryConsume` is at **L152**, and its daily-counter gate is L167–185 (`bumpDailyActions` at L185); the old spec cites "L122–142". The gate is also a read-then-decide, which is fine for a paid cap and NOT fine for a free allowance — see §4A.3.
5. **The ledger's credit-amount column is `delta`, not `amount`** (§2.2 L92), with `monthly_after` L96 / `purchased_after` L97. All three are tenths.
6. **Frontend line numbers have drifted badly** and are corrected where A1 touches them: `CREATOR_INCLUDED` is `src/pages/pricing.tsx` **L181–187** (not L73–79); `MeeraCreditStatus` is `src/lib/meera-api.ts` **L108** and `getCredits` **L851** (not L99–106 / L572); `MeeraTurnResponse.creditsRemaining` is **L76** (not L67). Sections §10.2, §10.3 and §10.5 are NOT re-verified by this amendment — treat every line number in them as unchecked since 2026-09-05.
7. **PLAN.md step 0.2 is already done on this branch.** `influora-ai/requirements.txt` L7 is `anthropic==0.125.0` as of commit `a4f233e` on `feat/creator-credits-search`; PLAN.md §6 still records `0.42.0` (true at `9bfe7dc`). The clean-venv pytest proof that step 0.2 also asks for is still outstanding.

**What still blocks the build** (all named again in §16.4): PLAN.md D2 and D3 (Swapnil), the Gemini paid-tier check (0.1), the consent ruling (0.4), the $483/month budget (D4), and O2, which A2 does not settle.

---


---

## 0. Rules that apply to every step

1. Cite only symbols you opened; never instruct a change to code you did not read. If a line number below has drifted, open the file and fix the reference before building.
2. Positional records: when a record gains a field, grep every construction site (`new SessionStartResponse(`, `new SendTurnResponse(`, `new MeeraSessionService(`) and update all of them in the same commit. `MeeraSessionServiceTest` L81 constructs `MeeraSessionService` by hand.
3. A `@Transactional` method called from inside the same class runs without a proxy. Public + call through the injected bean, or accept that it joins the caller's transaction.
4. MySQL 8, `utf8mb4_unicode_ci`, `ddl-auto=validate`: every `VARCHAR(n)` needs `@Column(length = n)`; no `CHAR`; no partial unique index, so uniqueness companions are `NOT NULL DEFAULT ''`; `TEXT` is 64 KB.
<!-- PRIYA: true for the SPRING vars, and §3.1's yml block satisfies it. NOT true for the one VITE var
     (§10.6). A `VITE_*` name reaches the published image ONLY through `Dockerfile` L54-63's
     ARG/ENV chain — `src/lib/api.ts` L102-107 (F-0390) records that Vite's loadEnv merges
     process.env OVER the .env files, so a var present in `.env.production` but absent from the
     Dockerfile ARG list binds to nothing in the built bundle. Extend this rule: every VITE var
     named here needs a `Dockerfile` ARG **and** ENV line, not just a .env entry. -->
5. Every env var named here has a `${VAR:default}` placeholder in `application.yml`. A name without a placeholder binds to nothing.
6. GET never writes. The lazy signup grant therefore happens on `POST /creator/meera/sessions` (a write route), never on `GET /creator/meera/credits`.
7. "escrow" never appears in user-facing copy. No creator brief or caption text reaches any model on this scope (unchanged; credits do not touch prompts).
8. Info barrier: nothing in this spec imports `CreatorAgentPreferencesRepository`. `InfoBarrierTest` stays green without edits.
9. Money never rounds in Java `double`. Pack prices are `int` paise in the DB and `BigDecimal` rupees only at the Razorpay boundary, exactly as `WalletTopUp.amount` and `RazorpayClient.createOrder(BigDecimal, …)` already do.
10. Nothing here changes brand behaviour. `AICreditService`, `BrandAiCredit`, `AICreditResetJob`, `MeeraController` are not edited.
11. <!-- PRIYA 2026-09-20 --> **Credits are stored in TENTHS, and this is the only place that says so.** Every credit column named in §2 is `INT` and holds tenths of a credit: 40 credits is `400`, 2.5 credits is `25`. Swapnil's 2026-09-19 ruling is one decimal place, so tenths is the exact integer unit — no `DECIMAL`, no `double`, no rounding anywhere in Java or SQL (rule 9's "money never rounds in a Java double" applies to credits for the same reason). Consequences, which no other section restates:
    - **Every cost, allotment and threshold in `CreatorCreditProperties` (§3.1) is a tenths value.** chat `10`, voice `20`, brief `30`, search `25`, monthly allotment `400`, signup grant `300`, low-balance threshold `100`.
    - **Two settings are NOT tenths, because they count events, not credits:** `daily-action-cap` (500 actions, R7) and `free-weekly-searches` (2 searches, §4A.3). A tenths conversion applied to either is a bug; both carry the comment saying so in §3.1.
    - **Nothing divides by ten except one DTO helper.** `CreatorCreditDtos.toDisplay(int tenths)` (§6) is the single conversion point from tenths to the decimal the creator sees. Grep for `/ 10` and `* 10` on any credit value outside that helper before calling the step done; a second conversion site is how a balance ends up ten times wrong on one screen and right on another.
    - **The two shared brand DTOs stay integral.** `MeeraDtos.CreditsSummary.remaining` and `SendTurnResponse.creditsRemaining` are brand-owned `int`s (§6); a creator never gets a credit number through either.


---

## 1. Scope in one table

| # | Piece | Where | Ships |
|---|---|---|---|
| K1 | Creator balance + ledger + pack catalogue + orders (4 migrations) | influora-api | v1 |
| K2 | `CreatorCreditService`: lazy init + signup grant, weighted debit with bucket split, guarded release, monthly reset, pack credit, admin grant | influora-api | v1 |
| K3 | Charge at send for chat (1), at transcribe for voice (2), at paste for brief (3, Phase B1 hook) | influora-api | v1 (brief hook lands with B1) |
| K4 | User-type-aware release from `/internal/meera/turns/release` | influora-api | v1 |
| K5 | `GET /creator/meera/credits`, `GET /creator/meera/credits/ledger`, real values in session/turn responses | influora-api | v1 |
| K6 | Packs: `GET /creator/credits/packs`, `POST /creator/credits/orders`, webhook branch, `GET /creator/credits/orders/{id}` | influora-api | v1 |
| K7 | `CreatorCreditResetJob` (1st of month) | influora-api | v1 |
| K8 | Admin grant route on `AdminCreatorAgentController` | influora-api | v1 |
| K9 | Kill switch `CREATOR_CREDITS_ENABLED` (default false) + all costs as config, **every cost in tenths (§0 rule 11)** <!-- PRIYA 2026-09-20 --> | influora-api | v1 |
| K10 | FE: credits badge, exhausted state with pack CTA, low-balance nudge, `/creator/credits` page, pricing copy | src | v1 |
| K11 | Python: the USD cap gate stays the cost fuse under the credit gate — **but `app/costs/pricing.py` must now price web search so the fuse counts search fees** (R4, §4A.5) <!-- PRIYA 2026-09-20 --> | influora-ai | **v1** (was: none) |
| K12 | <!-- PRIYA 2026-09-20 --> **Web search:** `POST /creator/meera/search`, 25 tenths per search, 2 free per week, `SEARCH_DEBIT` / `SEARCH_REFUND` / `FREE_SEARCH` ledger reasons, weekly counter columns, new rate-limit bucket, `MEERA_CREATOR_SEARCH_ENABLED` flag (default false) | influora-api + influora-ai + src | v1 (§4A) |
| — | Creator subscriptions, manager seat, referral / streak / first-deal earning, credit expiry | — | **not v1** (Tejas D1, D2, §4; Phase E) |

**Design rulings this spec makes (defaults; flag to Swapnil, build regardless):**

- **R1 Owner key = `users.id`.** The whole creator Meera path keys on the creator's user id (`doSendTurn` comment L326–331; `releaseTurnCredit` receives `conversation.getWorkspaceId()` which *is* the user id; idempotency scopes partition on it). Using `creator_profiles.id` would force a profile lookup on the release route, which has no principal. `CreatorAgentPreferences` keys on profile id; the admin grant route bridges with `CreatorProfileRepository.findById(profileId).getUserId()`.
- **R2 Two buckets.** `monthly_remaining` resets to `monthly_allotment` on the 1st (not cumulative). `purchased_balance` never expires (Tejas D2 default) and holds packs, the signup grant, and admin grants. Debit takes from monthly first, then purchased. Refund returns each part to its own bucket, monthly clamped to the allotment. The split is recorded in the idempotency digest so release refunds exactly what was charged.
- **R3 Signup grant is lazy, not a signup hook.** Granted once, inside `ensureInitialized`, the first time a creator's row is created. That is triggered by `POST /creator/meera/sessions`. This also gives every existing creator the grant on first use, which is Tejas D3's default ("backfill") without a backfill job.
- **R4 Credits gate in Spring, cap stays in Python — and the cap now counts search fees too.** The 402 fires in `doSendTurn` before a stream token exists, so an exhausted creator never reaches influora-ai. The USD cap (`AI_CREATOR_MONTHLY_CAP_USD`, raise to 2.00 per Phase B §14.4.d; the process default is `CREATOR_MONTHLY_CAP_USD = Decimal("0.75")` at `influora-ai/app/costs/spend_tracker.py` L80, enforced by `check_creator_spend_gate` L535 and settled by `record_creator_spend` L476–502) remains as the cost fuse for a creator who bought many packs. Both can be hit; the credits one is the product wall, the cap one is the safety wall.
  <!-- PRIYA 2026-09-20 --> **Amendment A5.** A web search costs a per-REQUEST fee, not tokens, so the fuse is blind to it today and one file has to change: **`influora-ai/app/costs/pricing.py`**. Three separate facts, all verified in this worktree:
  1. It has **no search rows** — `PRICING_TABLE` (L103) is keyed by literal model ids (L105–108 and below) and the string "search" does not appear in the file.
  2. **Adding rows to `PRICING_TABLE` alone would change nothing.** `ModelRate` (L50–53) has exactly four fields and all four are PER-TOKEN — `input_per_token`, `output_per_token`, `cache_read_per_token`, `cache_write_per_token` — and `estimate_cost_usd` (L244) computes the cost as the sum of four `tokens × rate` terms only (L292–297). There is no slot for a per-request fee and no term that would add one. pricing.py needs a per-request fee concept AND a new term in `estimate_cost_usd`, fed by a request-count field the provider puts in its `usage` dict.
  3. `_resolve_rate` raises `ValueError` for an unpriced model id (L241), so any search-capable model id must be in `PRICING_TABLE` or every priced call on that model raises.

  `record_creator_spend` needs **no change**: it takes a finished `Decimal` (L476–480), so once `estimate_cost_usd` includes the fee the monthly cap sees it automatically. Rates to add: Claude web search **$10 per 1,000 requests**, Gemini grounding **$35 per 1,000** beyond the free daily allowance (PLAN.md §6, provider pages read 2026-09-19). **The exact `usage` field name Anthropic returns for the search count is NOT verified here** — PLAN.md step 0.3 must record it from the real call, and until it does, a fee term keyed on a guessed field name would silently add $0. A FREE_SEARCH still calls a model, so its (small) cost must still go through `record_creator_spend`; skipping it because the creator was not charged would under-count the fuse.
- **R5 Voice charges once, at transcribe.** `POST /creator/meera/voice/transcribe` charges `voice-cost` (2). The following chat turn charges 1 as usual, so a voice turn costs 3 in total, matching Tejas ("additive"). `speak` is free. Refund when the transcribe returns `fallback`.
- **R6 Packs are one-time orders through `RazorpayClient.createOrder`, receipt prefix `credits:`.** No subscription, no wallet posting: credits are not money and never enter `wallet_transactions`. GST is computed for the invoice line only; v1 stores the GST-inclusive paise and issues no PDF (open decision O3).
- **R7 Daily hard cap 500 actions** mirrors the brand ledger as an abuse guard. Same error code `DAILY_ACTION_LIMIT_EXCEEDED`.

---

## 2. Data model (K1)

All four files go in `influora-api/src/main/resources/db/migration/`. Highest on disk today is `V20260903170000`. Phase B reserves `V20260910100000`–`V20260910100700`. This track uses `V20260912*`. Re-check on build day.

### 2.1 `V20260912100000__creator_ai_credits.sql`

<!-- PRIYA 2026-09-20 --> **Amendment A1: `monthly_remaining`, `monthly_allotment` and `purchased_balance` stay `INT` and hold TENTHS** (§0 rule 11). A new creator's row is `monthly_remaining = 400`, `monthly_allotment = 400`, `purchased_balance = 300`. `daily_actions_used` is NOT tenths — it counts actions (R7).

<!-- PRIYA 2026-09-20 --> **Amendment A4: two new columns for the weekly free-search counter** (§4A.3), added to this same migration rather than a fifth file — the table does not exist yet, so there is nothing to `ALTER`. They are deliberately the same shape as `daily_actions_used` / `daily_actions_date` two lines above, which is itself the brand shape from `V16__daily_action_cap.sql` L5–6: a used-count plus the period it belongs to, rolled lazily on read, with no reset job. `free_search_week_start` is `NULL` on a fresh row and that NULL is load-bearing — see the claim query in §2.6.

```sql
CREATE TABLE creator_ai_credits (
  creator_user_id     VARCHAR(26) NOT NULL PRIMARY KEY,
  monthly_remaining   INT NOT NULL DEFAULT 0,
  monthly_allotment   INT NOT NULL DEFAULT 0,
  purchased_balance   INT NOT NULL DEFAULT 0,
  cycle_start         DATE NOT NULL,
  last_reset          DATE NOT NULL,
  signup_grant_at     DATETIME(6) NULL,
  daily_actions_used  INT NOT NULL DEFAULT 0,
  daily_actions_date  DATE NULL,
  created_at          DATETIME(6) NOT NULL,
  updated_at          DATETIME(6) NOT NULL,
  free_searches_used  INT NOT NULL DEFAULT 0,
  free_search_week_start DATE NULL,
  CONSTRAINT fk_creator_ai_credits_user FOREIGN KEY (creator_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Verify `users.id` is `VARCHAR(26)` in the base migration before writing the FK (the collation mismatch in `1792c37`'s V73/V74 was exactly this class of error).

### 2.2 `V20260912100100__creator_credit_ledger.sql`

<!-- PRIYA 2026-09-20 --> **Amendment A1: `delta`, `monthly_after` and `purchased_after` are TENTHS** (§0 rule 11). A chat debit row is `delta = -10`, a search debit `-25`, the signup grant `+300`. Note the column is named **`delta`**, not `amount` — PLAN.md §3 step 1 calls it "ledger amounts"; there is no `amount` column here.

<!-- PRIYA 2026-09-20 --> **Amendment A3: `FREE_SEARCH` writes a `delta = 0` row, and every NOT NULL column on it still needs a value.** `bucket` is `NOT NULL` and `CreditBucket` has only `MONTHLY` and `PURCHASED` (§2.5), so a free search records `bucket = MONTHLY` (it spends the free weekly allowance, which is the free tier); `monthly_after` / `purchased_after` are the UNCHANGED balances; `reference_id` is the search's own fresh ULID, never `''`, so the unique key below can never collide between two free searches. Write this down in the migration comment too — a zero-delta audit row whose `bucket` was left null is a runtime `NOT NULL` failure on the first free search, not a test failure.

```sql
CREATE TABLE creator_credit_ledger (
  id                 VARCHAR(26) NOT NULL PRIMARY KEY,
  creator_user_id    VARCHAR(26) NOT NULL,
  delta              INT NOT NULL,                 -- signed; negative = debit
  bucket             VARCHAR(12) NOT NULL,         -- MONTHLY | PURCHASED
  reason             VARCHAR(32) NOT NULL,         -- see CreditLedgerReason
  reference_id       VARCHAR(64) NOT NULL DEFAULT '',   -- turnId / briefId / orderId / cycle date; '' when none
  monthly_after      INT NOT NULL,
  purchased_after    INT NOT NULL,
  actor_id           VARCHAR(26) NOT NULL DEFAULT '',   -- admin user id for ADMIN_GRANT, else ''
  note               VARCHAR(255) NOT NULL DEFAULT '',
  created_at         DATETIME(6) NOT NULL,
  CONSTRAINT fk_creator_credit_ledger_user FOREIGN KEY (creator_user_id) REFERENCES users(id),
  UNIQUE KEY uk_creator_credit_ledger_ref (creator_user_id, reason, bucket, reference_id),
  KEY idx_creator_credit_ledger_user_time (creator_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Why the unique key: `SIGNUP_GRANT` with `reference_id = ''` can exist once per creator; `MONTHLY_RESET` with `reference_id = '2026-10-01'` once per month; `TURN_DEBIT` with the turn id once per bucket. It is the structural double-grant guard, same idea as `affiliate_earnings.UNIQUE(redemption_id)`. `bucket` is in the key because one turn can debit both buckets (two rows).

### 2.3 `V20260912100200__creator_credit_packs.sql` (catalogue + seed)

```sql
CREATE TABLE creator_credit_packs (
  id            VARCHAR(26) NOT NULL PRIMARY KEY,
  code          VARCHAR(20) NOT NULL,
  name          VARCHAR(60) NOT NULL,
  credits       INT NOT NULL,
  price_paise   INT NOT NULL,          -- GST-inclusive, what the creator pays
  sort_order    INT NOT NULL DEFAULT 0,
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    DATETIME(6) NOT NULL,
  updated_at    DATETIME(6) NOT NULL,
  UNIQUE KEY uk_creator_credit_packs_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO creator_credit_packs (id, code, name, credits, price_paise, sort_order, active, created_at, updated_at) VALUES
('01K4CRPACK0000000000STARTR', 'STARTER',  'Starter',   500, 14900, 1, TRUE, NOW(6), NOW(6)),
('01K4CRPACK0000000000STANDR', 'STANDARD', 'Standard', 1000, 24900, 2, TRUE, NOW(6), NOW(6)),
('01K4CRPACK0000000000POWERR', 'POWER',    'Power',    3000, 64900, 3, TRUE, NOW(6), NOW(6));
```

<!-- PRIYA 2026-09-20 --> **Amendment A1: the seeded `credits` values are TENTHS** (§0 rule 11) — 500 / 1000 / 3000, which the creator sees as 50 / 100 / 300. `price_paise` is unchanged: it was already the smallest money unit and has nothing to do with the credit unit. Getting this one row wrong ships a pack that grants a tenth of what it sold, and no compile or unit test would see it — the `ddl-auto=validate` boot does not check seed values, so assert the three amounts in `CreatorCreditOrderServiceTest` (§11).

<!-- PRIYA: WRONG FILENAME. There is no `V55__seed_plans.sql` on disk. The file is
     `influora-api/src/main/resources/db/migration/V55__seed_billing_plans.sql`. The precedent
     itself (DB-seeded catalogue, no yml plan config) is correct. -->
Catalogue lives in the DB by precedent (`plans` is seeded by `V55__seed_billing_plans.sql`; there is no yml plan config). Prices are Tejas §3 (₹149 / ₹249 / ₹649). The ids must be 26 chars of Crockford base32; generate real ULIDs at build time rather than the placeholders above.

### 2.4 `V20260912100300__creator_credit_orders.sql`

<!-- PRIYA 2026-09-20 --> **Amendment A1: the `credits` snapshot is TENTHS** (§0 rule 11), copied verbatim from `creator_credit_packs.credits` at order time — so a Starter order stores `500`. `amount_paise` is unchanged.

```sql
CREATE TABLE creator_credit_orders (
  id                   VARCHAR(26) NOT NULL PRIMARY KEY,
  creator_user_id      VARCHAR(26) NOT NULL,
  pack_id              VARCHAR(26) NOT NULL,
  pack_code            VARCHAR(20) NOT NULL,      -- snapshot
  credits              INT NOT NULL,              -- snapshot
  amount_paise         INT NOT NULL,              -- snapshot
  currency             VARCHAR(3) NOT NULL DEFAULT 'INR',
  status               VARCHAR(12) NOT NULL,      -- PENDING | CREDITED
  razorpay_order_id    VARCHAR(64) NULL,
  razorpay_payment_id  VARCHAR(64) NULL,
  idempotency_key      VARCHAR(64) NOT NULL,
  credited_at          DATETIME(6) NULL,
  created_at           DATETIME(6) NOT NULL,
  updated_at           DATETIME(6) NOT NULL,
  CONSTRAINT fk_creator_credit_orders_user FOREIGN KEY (creator_user_id) REFERENCES users(id),
  CONSTRAINT fk_creator_credit_orders_pack FOREIGN KEY (pack_id) REFERENCES creator_credit_packs(id),
  UNIQUE KEY uk_creator_credit_orders_idem (creator_user_id, idempotency_key),
  UNIQUE KEY uk_creator_credit_orders_rzp (razorpay_order_id),
  KEY idx_creator_credit_orders_user_time (creator_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Mirrors `wallet_topups` (`WalletTopUp` entity) column for column where the concept matches, with snapshots so a later catalogue price change never changes what an old order credits.

### 2.5 Entities (`com.influora.domain.entity`)

<!-- PRIYA: two corrections to this bullet list.
     (a) The `BrandAiCredit` builder defaults are at L173-190, not L177-188 (the `build()` method
         opens at L173 and the class closes at L191). The idiom cited is correct; note that it
         uses `LocalDate.now()` (system zone) while §3.3 specifies a UTC month boundary and
         `release`/`resetForNewCycle` use `LocalDate.now(ZoneOffset.UTC)` — use UTC everywhere in
         the creator entity, do NOT copy the brand builder's system-zone `LocalDate.now()`.
     (b) `ddl-auto=validate` + rule 4: `@Column(length = n)` is MANDATORY on EVERY String field,
         and this list only names some of them. JPA defaults an unannotated String to
         VARCHAR(255), which fails validate against every narrower column here. The complete set
         that needs an explicit length: ledger `bucket`(12) `reason`(32) `referenceId`(64)
         `actorId`(26) `note`(255) `id`(26) `creatorUserId`(26); packs `id`(26) `code`(20)
         `name`(60); orders `id`(26) `creatorUserId`(26) `packId`(26) `packCode`(20)
         `currency`(3) `status`(12) `razorpayOrderId`(64) `razorpayPaymentId`(64)
         `idempotencyKey`(64). Hand-diff every one against the DDL before the Docker validate
         boot — Mockito tests never see the schema.
     (c) `razorpay_order_id` is `NULL` with a UNIQUE key. MySQL permits many NULLs in a UNIQUE
         index, so this works — but the entity must NOT declare `nullable = false` on it, or
         validate fails. -->
- `CreatorAiCredit` — `@Table(name = "creator_ai_credits")`, `@Id @Column(name = "creator_user_id", length = 26) String creatorUserId`, the columns above with `@Column(name = …)` on every one, `Instant` for `DATETIME(6)`, `LocalDate` for `DATE`. Builder with the same defaults idiom as `BrandAiCredit` L173–190: `cycleStart == null → LocalDate.now(ZoneOffset.UTC)`, `lastReset == null → cycleStart`. Method `int total()` = `monthlyRemaining + purchasedBalance`.
- `CreatorCreditLedgerEntry` — `@Table(name = "creator_credit_ledger")`; `@Enumerated(EnumType.STRING) @Column(length = 12) CreditBucket bucket`; `@Enumerated(EnumType.STRING) @Column(length = 32) CreditLedgerReason reason`; `@Column(name = "reference_id", nullable = false, length = 64) String referenceId` defaulting to `""` in the builder (never null, rule 4). Immutable after insert: no setters.
- `CreatorCreditPack` — `@Table(name = "creator_credit_packs")`, `@Column(length = 20) code`, `@Column(length = 60) name`, `int credits`, `@Column(name = "price_paise") int pricePaise`, `sortOrder`, `active`.
- `CreatorCreditOrder` — `@Table(name = "creator_credit_orders")`; `@Enumerated(EnumType.STRING) @Column(length = 12) CreatorCreditOrderStatus status`; `@Column(name = "razorpay_order_id", length = 64)`, `@Column(name = "razorpay_payment_id", length = 64)`, `@Column(name = "idempotency_key", nullable = false, length = 64)`; mutators `setRazorpayOrderId(String)` and `markCredited(String razorpayPaymentId)` exactly as `WalletTopUp.setRazorpayOrderId` L91 / `markCredited` L117.

Enums in `com.influora.domain.enums`:

```java
public enum CreditBucket { MONTHLY, PURCHASED }
public enum CreditLedgerReason {
  SIGNUP_GRANT, MONTHLY_RESET, PACK_PURCHASE, ADMIN_GRANT,
  TURN_DEBIT, TURN_REFUND, VOICE_DEBIT, VOICE_REFUND, BRIEF_DEBIT, BRIEF_REFUND,
  // <!-- PRIYA 2026-09-20 --> Amendment A3 (K12, §4A).
  SEARCH_DEBIT, SEARCH_REFUND, FREE_SEARCH
}
public enum CreatorCreditOrderStatus { PENDING, CREDITED }
```

<!-- PRIYA 2026-09-20 --> The three new names fit the existing width: `reason` is `VARCHAR(32)` (§2.2 L94) with `@Column(length = 32)`, and the longest new constant is `SEARCH_REFUND` at 13 characters. No migration change, and no `ALTER` ever — the column is a `VARCHAR`, not a MySQL `ENUM`, so adding a reason is a Java-only change (this is also why §15 O7's "ledger reasons can be added without schema change" is true).

### 2.6 Repositories (`com.influora.repository`)

`CreatorAiCreditRepository extends JpaRepository<CreatorAiCredit, String>`:

```java
@Query("SELECT c.creatorUserId FROM CreatorAiCredit c")
List<String> findAllCreatorUserIds();

@Modifying @Transactional
@Query("UPDATE CreatorAiCredit c SET c.monthlyRemaining = c.monthlyRemaining - :m, "
     + "c.purchasedBalance = c.purchasedBalance - :p, c.updatedAt = CURRENT_TIMESTAMP "
     + "WHERE c.creatorUserId = :id AND c.monthlyRemaining >= :m AND c.purchasedBalance >= :p")
int tryDebit(@Param("id") String id, @Param("m") int monthlyPart, @Param("p") int purchasedPart);

@Modifying @Transactional
@Query("UPDATE CreatorAiCredit c SET c.monthlyRemaining = "
     + "CASE WHEN c.monthlyRemaining + :m > c.monthlyAllotment THEN c.monthlyAllotment ELSE c.monthlyRemaining + :m END, "
     + "c.purchasedBalance = c.purchasedBalance + :p, c.updatedAt = CURRENT_TIMESTAMP "
     + "WHERE c.creatorUserId = :id")
int refund(@Param("id") String id, @Param("m") int monthlyPart, @Param("p") int purchasedPart);

@Modifying @Transactional
@Query("UPDATE CreatorAiCredit c SET c.purchasedBalance = c.purchasedBalance + :n, c.updatedAt = CURRENT_TIMESTAMP "
     + "WHERE c.creatorUserId = :id")
int addPurchased(@Param("id") String id, @Param("n") int credits);

@Modifying @Transactional
@Query("UPDATE CreatorAiCredit c SET c.dailyActionsUsed = "
     + "CASE WHEN c.dailyActionsUsed > :n THEN c.dailyActionsUsed - :n ELSE 0 END, c.updatedAt = CURRENT_TIMESTAMP "
     + "WHERE c.creatorUserId = :id AND c.dailyActionsDate = :today")
int refundDailyActions(@Param("id") String id, @Param("n") int n, @Param("today") LocalDate today);
```

<!-- PRIYA: BROKEN AS WRITTEN — two defects, both caused by copying `BrandAiCreditRepository`'s
     bare `@Modifying` (verified: that file annotates all three JPQL updates `@Modifying
     @Transactional` with NO `clearAutomatically`/`flushAutomatically`).
     (1) `ensureInitialized` leaves the CreatorAiCredit instance MANAGED in the persistence
         context. A JPQL bulk UPDATE bypasses that context entirely. So the "fresh read inside the
         same transaction" returns the SAME stale instance with pre-debit values — the retry
         recomputes the identical (m, p), issues the identical failing UPDATE, and 402s. The
         retry is dead code, and the 402 is spurious whenever a concurrent debit merely shifted
         the split rather than exhausting the balance.
     (2) Worse: §3.3 writes `monthly_after` / `purchased_after` into the ledger. Read off the
         stale managed instance, those after-balances are WRONG on every row — the ledger, which
         is the money record, silently diverges from the balance table.
     FIX: annotate `tryDebit`, `refund`, `addPurchased` and `refundDailyActions`
     `@Modifying(clearAutomatically = true, flushAutomatically = true)`, and re-read via
     `findById` AFTER the successful UPDATE to source the after-balances. -->
The `tryDebit` guard is the atomic "enough in both buckets" check; the service computes the split from a fresh read inside the same transaction and retries once on `0` rows (a concurrent debit changed the split). Same shape as `BrandAiCreditRepository.tryDecrement` / `refundCredits`, **plus** `clearAutomatically = true, flushAutomatically = true` on every `@Modifying` here.

`CreatorCreditLedgerRepository`: `List<CreatorCreditLedgerEntry> findByCreatorUserIdOrderByCreatedAtDesc(String id, Pageable page)`.
`CreatorCreditPackRepository`: `List<CreatorCreditPack> findByActiveTrueOrderBySortOrderAsc()`, `Optional<CreatorCreditPack> findByCodeAndActiveTrue(String code)`.
`CreatorCreditOrderRepository`: `Optional<CreatorCreditOrder> findByCreatorUserIdAndIdempotencyKey(String, String)`, `Optional<CreatorCreditOrder> findByIdAndCreatorUserId(String, String)`.


<!-- PRIYA 2026-09-20 --> **Amendment A3/A4 — three more repository methods.**

On `CreatorAiCreditRepository`, the atomic free-search claim. It is one statement, not a read-then-decide, and that is the point:

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Transactional
@Query("UPDATE CreatorAiCredit c SET c.freeSearchWeekStart = :weekStart, "
     + "c.freeSearchesUsed = CASE WHEN c.freeSearchWeekStart = :weekStart THEN c.freeSearchesUsed + 1 ELSE 1 END, "
     + "c.updatedAt = CURRENT_TIMESTAMP "
     + "WHERE c.creatorUserId = :id AND ("
     + "  c.freeSearchWeekStart IS NULL OR c.freeSearchWeekStart <> :weekStart "
     + "  OR c.freeSearchesUsed < :cap)")
int tryClaimFreeSearch(@Param("id") String id, @Param("weekStart") LocalDate weekStart, @Param("cap") int cap);
```

Returns `1` when a free search was claimed and `0` when the week's free searches are spent; §4A.2 branches on exactly that. Three things about it are deliberate and each one is a bug if dropped:
- **`c.freeSearchWeekStart IS NULL` is a separate term.** `freeSearchWeekStart` is `NULL` on every freshly-created row, and in SQL `NULL <> :weekStart` evaluates to `NULL`, not `TRUE` — so without the explicit IS NULL term the very first free search of every creator's life matches nothing, updates zero rows, and is silently billed 25 tenths. This is the exact shape of failure that compiles, passes Mockito tests, and only shows up as a support ticket.
- **One statement, not a check followed by a bump.** `AICreditService.tryConsume` (L152) reads the counter at L170–171, decides at L177 and bumps separately at L185. For a paid daily cap a small concurrent overshoot is tolerable; for a FREE allowance it is a free-money leak — two parallel requests both read "1 used" and both go free. `V20260905190000__abuse_throttle_counters.sql`'s header comment is the post-mortem of precisely this TOCTOU ("100 parallel requests each run their COUNT against a snapshot ... and all 100 pass the cap"), and its answer — one row-locked upsert — is the shape copied here.
- **`clearAutomatically`/`flushAutomatically`** for the same reason §2.6 already gives for `tryDebit`: `ensureInitialized` leaves the entity managed, and a JPQL bulk UPDATE bypasses the persistence context, so a later read in the same transaction would return stale counts.

On `CreatorCreditLedgerRepository`, two finders:

```java
// The §3.3 release guard-1 fix: refund only against a real debit row.
List<CreatorCreditLedgerEntry> findByCreatorUserIdAndReasonAndReferenceId(
        String creatorUserId, CreditLedgerReason reason, String referenceId);

// PLAN.md D1 only. Do NOT build this until Swapnil rules D2 = no; see §4A.3.
boolean existsByCreatorUserIdAndReasonAndCreatedAtGreaterThanEqual(
        String creatorUserId, CreditLedgerReason reason, Instant since);
```

---

## 3. `CreatorCreditService` (K2, K9)

Package **`com.influora.service.meera`** — same package as `AICreditService`, so `MeeraSessionService.PERSIST_WRITEBACK_SCOPE` (package-visible, L89) is reachable for the release guard.

### 3.1 Config: `com.influora.config.CreatorCreditProperties`

```java
@ConfigurationProperties(prefix = "influora.meera.creator-credits")
public class CreatorCreditProperties {
  private boolean enabled = false;
  // <!-- PRIYA 2026-09-20 --> Amendment A1: every value below is TENTHS of a credit (§0 rule 11)
  // EXCEPT dailyActionCap and freeWeeklySearches, which count events. 400 == 40.0 credits.
  private int signupGrant = 300;          // 30.0
  private int monthlyAllotment = 400;     // 40.0
  private int turnCost = 10;              // 1.0
  private int voiceCost = 20;             // 2.0  (+ the turn's 10 => 3.0 for a voice turn, R5)
  private int briefCost = 30;             // 3.0
  private int searchCost = 25;            // 2.5  <-- A3/K12, §4A
  private int lowBalanceThreshold = 100;  // 10.0
  private int dailyActionCap = 500;       // ACTIONS, not tenths (R7)
  private int freeWeeklySearches = 2;     // SEARCHES, not tenths (A4, §4A.3)
  // getters/setters
}
```

<!-- PRIYA 2026-09-20 --> **Two of these nine are not tenths and the code must not treat them as such.** `dailyActionCap` counts actions (R7; `AICreditService.DAILY_ACTION_HARD_CAP = 500` at L93 is the brand equivalent and is also a count), and `freeWeeklySearches` counts searches. A blanket "multiply every default by ten" pass over this class would silently give every creator 5,000 actions a day and 20 free searches a week — which is why the invariant lives in §0 rule 11 with these two exceptions named there as well as here.

<!-- PRIYA: too vague for the one step in this spec that can crash boot. Naming it exactly:
     `RazorpayProperties` (config/RazorpayProperties.java L13) is a bare `@ConfigurationProperties`
     with NO stereotype. There is NO `@ConfigurationPropertiesScan` anywhere in this repo. The
     ONLY thing that makes it constructible is its entry in `InfluoraApiApplication`'s
     `@EnableConfigurationProperties({...})` list (InfluoraApiApplication.java L36, RazorpayProperties
     at L38). `CreatorCreditProperties` MUST be added to that list in the same commit. That file's
     own L73-95 comment block is the post-mortem of the incident where eleven such classes were
     unregistered and the app could never boot — and `MeeraCreatorFeatureProperties` L17-24
     chose plain `@Value`/`@Component` specifically to dodge this. Either is acceptable here;
     what is NOT acceptable is `@ConfigurationProperties` with no registration.
     GATE: `influora-api/src/test/java/com/influora/config/ConfigurationPropertiesRegistrationTest.java`
     `everyConfigurationPropertiesClassIsRegistered` classpath-scans for exactly this and needs no
     Docker — it must be green before this step is called done. -->
Register it by adding `CreatorCreditProperties.class` to `InfluoraApiApplication`'s `@EnableConfigurationProperties({…})` list (L36), the same way `RazorpayProperties.class` is registered at L38. `application.yml`, under the existing `influora.meera:` block next to `creator-enabled` (L199):

```yaml
    creator-credits:
      enabled: ${CREATOR_CREDITS_ENABLED:false}
      # <!-- PRIYA 2026-09-20 --> A1: TENTHS (§0 rule 11). 400 == 40.0 credits.
      signup-grant: ${CREATOR_CREDITS_SIGNUP_GRANT:300}
      monthly-allotment: ${CREATOR_CREDITS_MONTHLY_ALLOTMENT:400}
      turn-cost: ${CREATOR_CREDITS_TURN_COST:10}
      voice-cost: ${CREATOR_CREDITS_VOICE_COST:20}
      brief-cost: ${CREATOR_CREDITS_BRIEF_COST:30}
      search-cost: ${CREATOR_CREDITS_SEARCH_COST:25}
      low-balance-threshold: ${CREATOR_CREDITS_LOW_BALANCE_THRESHOLD:100}
      # Counts, NOT tenths:
      daily-action-cap: ${CREATOR_CREDITS_DAILY_ACTION_CAP:500}
      free-weekly-searches: ${CREATOR_CREDITS_FREE_WEEKLY_SEARCHES:2}
```

<!-- PRIYA 2026-09-20 --> **Ten names now, not eight** — `search-cost` and `free-weekly-searches` are new (A1/A3), and every numeric default changed. If this branch is deployed with the OLD env values still set in `deploy/`, `CREATOR_CREDITS_MONTHLY_ALLOTMENT=40` would give every creator 4.0 credits a month, not 40. The env values in `deploy/` must be updated or removed in the SAME change, and rule 5 still applies: a name without a `${VAR:default}` placeholder here binds to nothing.

<!-- PRIYA 2026-09-20 --> **The search feature flag does NOT live on this class.** `CREATOR_SEARCH_ENABLED` is a single boolean and belongs next to the other two Meera-creator flags on `MeeraCreatorFeatureProperties` — see §4A.1 for the exact change and for why `@Value`/`@Component` is the right home for it.

Add the eight names to `deploy/` env examples with the defaults only (never real values). `enabled=false` by default: with it off, every charge method is a no-op that returns "not charged", status reports `state = "DISABLED"`, and the FE hides the badge. This lets the migrations and code deploy ahead of the pricing decision.

### 3.2 Class

```java
@Service
public class CreatorCreditService {
  static final String CHARGE_SCOPE = "meera.creator_charged";      // owner = creator user id, key = turnId | "voice:"+id | "brief:"+briefId
  static final String RELEASE_SCOPE = "meera.creator_released";
  private static final String NO_CHARGE_DIGEST = "0:0";

  public CreatorCreditService(CreatorAiCreditRepository credits, CreatorCreditLedgerRepository ledger,
                              IdempotencyService idempotencyService, CreatorCreditProperties props,
                              ApplicationEventPublisher events) { … }
```

Scopes are distinct from `AICreditService`'s `meera.turn_charged` / `meera.turn_released`, so even a reused ULID cannot collide. Composite is `scope:ownerId:key` ≤ 128 chars: `"meera.creator_charged:" + 26 + ":" + "brief:" + 26` = 76.

### 3.3 Methods

| Method | Transaction | Behaviour |
|---|---|---|
| `public CreatorAiCredit ensureInitialized(String creatorUserId)` | `@Transactional` | `findById` or create: `monthlyAllotment = props.monthlyAllotment`, `monthlyRemaining = monthlyAllotment`, `purchasedBalance = props.signupGrant`, `signupGrantAt = now`, `cycleStart = first day of current UTC month`, `lastReset = today`; write two ledger rows `MONTHLY_RESET` (`reference_id = cycleStart.toString()`) and `SIGNUP_GRANT` (`reference_id = ""`). The unique key makes a racing double-create fail on the ledger insert; catch `DataIntegrityViolationException` **around `saveAndFlush`**, not `save` (memory: a catch around plain `save` on a managed entity is dead code), then re-read and return. |
| `public CreatorAiCredit getStatus(String creatorUserId)` | `readOnly` | `findById` → `Optional`; **does not create** (rule 6). Controller maps empty to "not yet initialised" with allotment and grant values from props so the badge still renders before first session. |
<!-- PRIYA: `charge` must catch `IdempotencyService.AlreadyCompletedException` /
     `AlreadyInProgressException`. `executeOnce` throws both as unchecked exceptions
     (IdempotencyService.java L146-152) and the spec's `release` row correctly copies
     `AICreditService.release`'s catch (AICreditService.java L235-241) — but `charge` has no
     catch. For chat and voice the key is freshly minted so it never fires; for BRIEF the key is
     `"brief:" + brief.getId()` (§4.3), a STABLE key, so a retried paste 500s instead of
     replaying. Wrap the `executeOnce` call the same way `release` does, and on
     AlreadyCompleted return the split parsed out of `findCompletedResultDigest` rather than
     charging again. -->
<!-- PRIYA: the daily-action refund amount is wrong (see the `release` row below). `charge`
     increments `dailyActionsUsed` by ONE per action, mirroring `AICreditService.tryConsume`
     L141 (`credit.getDailyActionsUsed() + 1`) — the cap counts ACTIONS, per R7. `release` must
     therefore refund 1, not `m + p`. The brand code refunds `cost` and gets away with it only
     because brand cost is always 1 (`TURN_CREDIT_COST`, MeeraSessionService L80); a voice charge
     of 2 or a brief charge of 3 would hand the creator 2-3 extra daily actions per refund. -->
| `public ChargeResult charge(String creatorUserId, int cost, String chargeKey, CreditLedgerReason reason)` | `@Transactional` | If `!props.enabled` → return `ChargeResult.NOT_CHARGED`. `ensureInitialized`; daily counter roll + cap (mirror `AICreditService.tryConsume` L122–142; code `DAILY_ACTION_LIMIT_EXCEEDED`, 429); compute split `m = min(monthlyRemaining, cost)`, `p = cost - m`; if `p > purchasedBalance` → 402 `CREDITS_EXHAUSTED` with message "You've used your Meera credits. Top up to keep going." ; `tryDebit(id, m, p)`; if 0 rows re-read once and recompute, then 402; write ledger rows (one per non-zero part) with `reference_id = chargeKey`; `idempotencyService.executeOnce(chargeKey, creatorUserId, CHARGE_SCOPE, () -> new int[]{m, p}, parts -> parts[0] + ":" + parts[1])` — the digest records the split for release. Returns `ChargeResult(cost, m, p, totalAfter)`. |
<!-- PRIYA 2026-09-20 --> **Amendment A1: `cost` is in tenths.** `charge(..., 10, ...)` is one credit. The 402 message stays as written — it names no number, so it needs no unit. The split computation `m = min(monthlyRemaining, cost)`, `p = cost - m` is unit-agnostic and correct as-is; the only thing that changes is that a "short by 5" now means half a credit. **One new behaviour A3 needs:** `charge` must accept `SEARCH_DEBIT` like any other reason, and the §4A.2 caller relies on the `AlreadyCompletedException` catch the note below already requires, because a search's charge key is minted fresh per request and therefore never replays — but a `FREE_SEARCH` row is written by a different path entirely (§4A.2), not by `charge`.
| `public boolean wasCharged(String creatorUserId, String chargeKey)` | `readOnly` | `idempotencyService.isCompleted(chargeKey, creatorUserId, CHARGE_SCOPE)` |
| `public int chargedCost(String creatorUserId, String chargeKey)` | `readOnly` | parse `findCompletedResultDigest(...)`, sum of parts, 0 when absent. Used by the writeback to stamp `ai_messages.credits_charged`. |
<!-- PRIYA: guard 1 is not strong enough here, and the consequence is worse than it is for brands.
     `IdempotencyReservationOps` is REQUIRES_NEW (its class javadoc L54-58 says so explicitly), so
     a COMPLETED `meera.creator_charged` marker COMMITS INDEPENDENTLY of the debit it is supposed
     to attest. If `charge`'s own transaction fails to commit after `executeOnce` has run, the
     marker outlives a debit that never landed — and this `release` will then hand back credits
     that were never taken. `refund`'s monthly leg is clamped to the allotment, but its purchased
     leg (`purchasedBalance + :p`, §2.6) is UNCLAMPED, so the mint lands in the money-backed
     bucket. Brand has the same shape and has survived it only because its refund is clamped and
     its credits are not sold.
     FIX: make guard 1 require a matching DEBIT LEDGER ROW, not just the marker —
     `creatorCreditLedgerRepository.findByCreatorUserIdAndReasonAndReferenceId(id, debitReason,
     chargeKey)` must be non-empty, and refund exactly the (bucket, delta) pairs those rows
     record. The ledger is inside `charge`'s transaction, so it cannot exist without the debit.
     Keep the idempotency marker as the double-release guard; stop using it as proof of payment.
     Add `CreditLedgerReason debitReason` (or derive it from `refundReason`) to do this. -->
| `public void release(String creatorUserId, String chargeKey, CreditLedgerReason refundReason)` | `@Transactional` | Same skeleton as `AICreditService.release` L219–241 + `doRelease` L243–268: blank key → 400 `RELEASE_TURN_ID_REQUIRED`; `executeOnce(chargeKey, creatorUserId, RELEASE_SCOPE, …)` with both `Already*` exceptions swallowed; inside: guard 1 the matching DEBIT ledger row(s) exist (see comment) → else WARN return; guard 2 (chat only, i.e. `refundReason == TURN_REFUND`) `isCompleted(chargeKey, creatorUserId, MeeraSessionService.PERSIST_WRITEBACK_SCOPE)` → WARN return; refund each bucket the amount its debit row records, `refundDailyActions(id, 1, todayUtc)` if same day, ledger rows with positive `delta`. |
| `public void resetForNewCycle(String creatorUserId, LocalDate cycleStart)` | `@Transactional` | `monthlyAllotment = props.monthlyAllotment; monthlyRemaining = monthlyAllotment; cycleStart; lastReset = today`; ledger `MONTHLY_RESET` with `reference_id = cycleStart.toString()` (unique key makes a re-run a no-op: catch the violation around `saveAndFlush`, log, continue). Publish `CreatorCreditsResetEvent` (§3.5). |
| `public void creditPack(String creatorUserId, int credits, String orderId)` | `@Transactional` | `ensureInitialized`; `addPurchased`; ledger `PACK_PURCHASE` `reference_id = orderId`. Idempotent by the ledger unique key; callers already gate on order status. |
| `public int adminGrant(String creatorUserId, int credits, String adminUserId, String note)` | `@Transactional` | `credits` 1..10 000 else 400 `INVALID_GRANT`; `ensureInitialized`; `addPurchased`; ledger `ADMIN_GRANT` with `reference_id = Ulids.newUlid()`, `actor_id`, `note`. Returns new total. |
<!-- PRIYA 2026-09-20 --> **Amendment A1: `adminGrant`'s bounds are tenths, and the admin does not type tenths.** The service guard becomes `credits` 10..100 000 (1.0 to 10 000.0 credits) else 400 `INVALID_GRANT`. The admin route (§9) takes whole or one-decimal CREDITS from the operator and converts once, at the DTO boundary, with the same helper §6 uses in the other direction — never by hand in the controller. A grant of `50` typed by an operator who meant 50 credits and landed as 5.0 is a support incident that looks exactly like a bug in the ledger.
| `public record ChargeResult(int cost, int monthlyPart, int purchasedPart, int totalAfter)` with `static final ChargeResult NOT_CHARGED = new ChargeResult(0,0,0,-1)` | | `totalAfter == -1` means "credits disabled"; controllers map it to `-1` on the wire only where the field is `int` (`SendTurnResponse.creditsRemaining`), and FE treats negative as "hide". |
<!-- PRIYA 2026-09-20 --> **Amendment A1: `cost`, `monthlyPart`, `purchasedPart` and `totalAfter` are all tenths.** `NOT_CHARGED`'s sentinel `totalAfter == -1` is unchanged and still means "credits disabled" — it is a sentinel, not a quantity, so it is not `-10`. Do not widen this record to `BigDecimal`: it is an internal service result, and the single conversion to a decimal happens in the DTO layer (§6).

### 3.4 Errors (add to the same `ApiException` vocabulary; no new exception types)

`CREDITS_EXHAUSTED` 402 (same code as brand so `CreditPaywall`-style handling can be shared), `DAILY_ACTION_LIMIT_EXCEEDED` 429, `RELEASE_TURN_ID_REQUIRED` 400, `INVALID_GRANT` 400, `PACK_NOT_FOUND` 404, `ORDER_NOT_FOUND` 404, `CREDITS_DISABLED` 404 (pack purchase attempted while `enabled=false`).

### 3.5 Events

Two new records in `com.influora.service.notification.event`, added to the `permits` list of `NotificationEvent` **after** `CreatorNotConnectedEvent` (add a comma to that line; the new last entry carries none):

```java
public record CreatorCreditsExhaustedEvent(String userId, String workspaceId, String entityId) implements NotificationEvent {
  @Override public String eventType() { return "ai.creator_credits_exhausted"; }
}
public record CreatorCreditsResetEvent(String userId, String workspaceId, String entityId, int newAllotment) implements NotificationEvent {
  @Override public String eventType() { return "ai.creator_credits_reset"; }
}
```

`workspaceId` is `null` for both (user-level; the interface javadoc allows null). `NotificationListener`: copy the two brand handlers at L647 and L660, deep link `/creator/credits`, in-app only for v1 (no email template; `EmailTemplateRegistry` is package-private with a 5-arg `Spec`, leave it). Exclude both from `NotificationEventContractTest` the same way `CreditsExhaustedEvent` is excluded (L91/L93 of that test; open it). `CreatorCreditsExhaustedEvent` is published from `charge` at the moment the 402 is thrown — publish **before** throwing, and because the throw rolls back, use `TransactionalEventListener` semantics only if the listener is `AFTER_COMMIT`; the brand listeners are `@Async @TransactionalEventListener(phase = AFTER_COMMIT)`, which would never fire on a rolled-back transaction. So: publish it from the **controller** after catching the 402 (`CreatorMeeraController.sendTurn`), not from the service. Exhausted is one event per creator per cycle: the controller checks `ledger` has no `TURN_DEBIT` since `cycleStart`? No — keep it simple: FE shows the state; the event only feeds the in-app notification and may repeat. Note it in O5.

---

## 4. Charging the four actions (K3, K12) <!-- PRIYA 2026-09-20 -->

### 4.1 Chat turn — `MeeraSessionService`

1. Constructor: add `CreatorCreditService creatorCreditService` as the **eleventh** parameter (after `CreatorAgentConversationService`). Update `MeeraSessionServiceTest` L81 (mock it; default `charge` returns `ChargeResult.NOT_CHARGED` so existing creator tests keep passing) and grep for any other `new MeeraSessionService(`.
2. `doSendTurn` L332–344: keep the brand branch untouched. In the creator branch:
   ```java
   if (!isCreatorTurn) {
       creditService.tryConsumeForTurn(workspaceId, TURN_CREDIT_COST, messageId);
   } else {
       creatorCreditService.charge(workspaceId, creatorCreditProperties.getTurnCost(), messageId, CreditLedgerReason.TURN_DEBIT);
   }
   ```
   `workspaceId` here is the creator user id (comment L326–331). The 402 propagates before the USER row, stream token, or on-behalf token exist — same "nothing dangling" property the brand comment at L334–341 describes. Inject `CreatorCreditProperties` too, or expose the cost through `CreatorCreditService.turnCost()`; the latter keeps the constructor at eleven args. Use the latter.
<!-- PRIYA: line drift. `creditsCharged = 0;` is at MeeraSessionService.java **L576**, inside the
     `if (userType == UserType.CREATOR)` opened at L575. Method signature is L561. -->
3. `doPersistAssistantWriteback`: the creator branch sets `creditsCharged = 0` at **L646**, inside the `if (userType == UserType.CREATOR)` opened at L645. The brand branch is L647–663 and stays untouched.
   <!-- PRIYA 2026-09-20 --> **Two corrections here, one of them a unit collision.**
   **(a) Line drift.** The old spec said "L576"; it is L646 on this branch (`int creditsCharged;` is declared at L644). The method's own javadoc region and the brand `creditService.wasCharged(...) ? TURN_CREDIT_COST : 0` line at L655 are the landmarks to grep for if it drifts again.
   **(b) `ai_messages.credits_charged` is a SHARED column and cannot hold tenths.** `V12__ai_conversations_messages.sql` L21 declares `credits_charged INT NOT NULL DEFAULT 0` with the comment "1 per exchange, 10 for analysis (PRD §7)", and `AiMessage.java` L36 maps it. The brand path writes whole brand credits into it (L655, `TURN_CREDIT_COST` = 1). If a creator turn writes `chargedCost(...)` = `10` tenths, the same column now means "1 credit" on a creator row and "an analysis" on a brand row, and every existing report over that column silently mixes two units.
   **Ruling: leave `creditsCharged = 0` on the creator branch.** Do not replace it. The column's stated purpose is display/audit (see the comment at L406–409 on the USER row: "the actual decrement already happened above"), and the creator's authoritative record is `creator_credit_ledger`, which is exact, in tenths, and queryable per turn by `reference_id`. `CreatorCreditService.chargedCost` is still built and still used — by §5's release path — it just does not get stamped here. If a creator per-message charge display is ever wanted, it needs its own tenths column (`credits_charged_tenths`), not this one.
   **This deletes a step from the old build order.** §12 step 3 no longer includes a writeback change.
4. `releaseTurnCredit` L635: add an overload and make the old one delegate:
   ```java
   public void releaseTurnCredit(String workspaceId, String turnId, UserType userType) {
       if (userType == UserType.CREATOR) {
           creatorCreditService.release(workspaceId, turnId, CreditLedgerReason.TURN_REFUND);
       } else {
           creditService.release(workspaceId, TURN_CREDIT_COST, turnId);
       }
   }
   public void releaseTurnCredit(String workspaceId, String turnId) { releaseTurnCredit(workspaceId, turnId, UserType.BRAND); }
   ```
   Confirm `UserType.BRAND` is the brand enum constant by opening `domain/enums/UserType.java`.
<!-- PRIYA: two fixes.
     (a) The single construction site is at **L402**, not L403:
         `return new TurnResult(userMessage.getId(), null, streamToken, onBehalfToken,
         sanitizedContext, null);`. Confirmed by grep over the whole tree: `new TurnResult(`
         appears exactly once, in main, and never in a test. The record at L683 has SIX
         components — userMessageId, assistantMessageId, streamToken, onBehalfToken,
         sanitizedContext, placeholderReply.
     (b) The claim "the brand controller ignores it" is CORRECT and worth keeping for the reason
         it is correct: `MeeraController` L137-149 builds its `SendTurnResponse` from
         `credit.getCreditsRemaining()` (a separate `AICreditService` lookup), not from
         `TurnResult`, so adding a seventh component cannot break the brand path. -->
5. `SendTurnResponse.creditsRemaining` for a creator: `TurnResult` (record at L683) has no credits field. Add `Integer creditsRemaining` as a new last component of `TurnResult` (positional record: update its single construction site at L402) set from `ChargeResult.totalAfter` on the creator branch and `null` on the brand branch. The brand controller ignores it.

### 4.2 Voice — `CreatorMeeraController.transcribe`

<!-- PRIYA 2026-09-20 --> **Amendment A1: `voiceCost` is 20 tenths (2.0 credits).** The charge and refund shape below is unchanged, and all five of the verified defects in the note that follows still stand exactly as written — I re-opened `CreatorMeeraController.java` on this branch (2026-09-20) and confirmed every line it names: three free returns before the provider call at **L287** (null/empty), **L291** (oversize) and **L298** (the `catch (IOException)`), `audioBytes` assigned at **L296**, `voiceAiClient.transcribe(...)` at **L301–302**, and exactly one post-charge fallback return at **L316**. The route is `@PostMapping` L277, method L278, class closes L322. Place the charge after L296 and before L301, as defect (1) says.
Inject `CreatorCreditService`. After `requireConsent` and the size checks, before `voiceAiClient.transcribe(...)`:

```java
String chargeKey = "voice:" + Ulids.newUlid();
creatorCreditService.charge(creatorUserId, creatorCreditService.voiceCost(), chargeKey, CreditLedgerReason.VOICE_DEBIT);
```

Every `return ResponseEntity.ok(Map.of("fallback", true))` **after** the charge line calls `creatorCreditService.release(creatorUserId, chargeKey, CreditLedgerReason.VOICE_REFUND)` first. The three early returns before the charge stay free. `speak` is unchanged.

<!-- PRIYA: THREE defects in §4.2, all verified against CreatorMeeraController.java L277-316.
     (1) "The two early returns" is WRONG — there are THREE returns before
         `voiceAiClient.transcribe(...)`: null/empty audio (L287), oversize (L291), and the
         `catch (IOException)` on `audio.getBytes()` (L298). Place the charge AFTER the try/catch
         (i.e. after `audioBytes` is assigned, L299) so all three stay free and exactly one
         post-charge fallback return remains (L316). If the charge is placed where the prose says
         — "after the size checks" — the IOException return dangles a charge with no refund.
     (2) `voiceAiClient.transcribe` can THROW, and `transcribe` is a controller method with no
         transaction, so `charge` has already committed. Wrap the call in try/catch (or
         try/finally keyed on a `charged` flag) and release on any throw. Otherwise a Sarvam
         outage silently eats 2 credits per attempt.
     (3) CONTRACT BREAK. This route's javadoc (L273) states its contract as "real transcript
         JSON, or a silent {"fallback": true} 200 — never an error status", and the FE voice hook
         is written to that. Letting `charge`'s 402 propagate changes the contract for a route
         the client does not error-handle. Decide explicitly and write it down: either (a) catch
         the 402 and return `ResponseEntity.ok(Map.of("fallback", true, "reason", "credits"))`,
         keeping the contract and letting the chat turn's own 402 be the paywall; or (b) accept
         the 402 and ship the FE hook change in the SAME commit. Default: (a) — the chat turn
         immediately after is charged anyway and will surface the card.
     (4) The cross-reference "(§8.3)" does not resolve — this document has no §8.3. The FE voice
         handling is §10.3's last bullet.
     (5) Adding `CreatorCreditService` to this controller's constructor BREAKS
         `influora-api/src/test/java/com/influora/web/CreatorMeeraControllerTest.java` L70-76,
         which constructs `new CreatorMeeraController(sessionService, creatorContext,
         streamProperties, preferencesService, voiceAiClient, featureProperties)` — six
         positional args, by hand. §11 mentions this test but never says to update the
         constructor call. It must be updated in the same commit or the module will not compile. -->


### 4.3 Brief paste — `CreatorBriefController.paste` → `CreatorBriefService.paste` (SHIPPED in B0)

<!-- PRIYA 2026-09-20 --> **Amendment A2. This section was written against code that did not exist ("Phase B1 `CreatorBriefService.paste` (not yet built)") and is rewritten against the shipped B0 code, which I opened on this branch. Every line number below is from that read.**

**The two files.**
- `influora-api/src/main/java/com/influora/web/CreatorBriefController.java` — `@RequestMapping("/creator/briefs")` L54, class L55. The paste route is `@PostMapping` **L122**, `paste(...)` **L123**, `requireConsentedCreator(principal)` **L126** (which calls `requireConsent` L109 → L97), and `briefService.paste(creatorUserId, body.text())` **L127**. Rate-limited by the `creator-brief-paste` bucket (10 per window, USER-keyed), per its javadoc L117.
- `influora-api/src/main/java/com/influora/service/CreatorBriefService.java` — `paste(String creatorUserId, String rawText)` **L199**; `requireCreatorProfile` L200; `getOrCreatePreferences` L201; `briefWriter.saveRawPaste(profile.getId(), rawText)` **L206** (STEP 2, its own committed transaction); `return analyse(brief, profile, prefs)` **L208**; method closes L209.

**The controller needs NO change.** Its only job here is to produce a consented creator user id, which it already does at L126. The charge goes in the service.

**Where the charge goes: inside `paste`, between L206 and L208.**

```java
CreatorBrief brief = briefWriter.saveRawPaste(profile.getId(), rawText);                 // L206 today
String chargeKey = "brief:" + brief.getId();
creatorCreditService.charge(creatorUserId, creatorCreditService.briefCost(), chargeKey,  // 30 tenths (A1)
        CreditLedgerReason.BRIEF_DEBIT);
```

**Why NOT inside `analyse`, which is where "before the AI call" would naively put it.** `analyse` is declared at **L433** and has **three** callers, not one:
1. `paste` L208 — the paste the creator pays for.
2. `ensurePlatformBrief` **L264** — a brief lifted from an existing collaboration, which the creator never pasted.
3. `readOrReanalyse` **L350**, reached from `get(creatorUserId, briefId)` **L282** — the F1 HIGH re-analysis of a stale `NEW` brief on a plain `GET /creator/briefs/{id}`.

Charging in `analyse` would therefore bill a creator a second 3.0 credits for merely REOPENING her own brief (path 3 is a real ~30s AI call — it is the whole reason the `creator-brief-get` rate-limit bucket exists, `AuthRateLimitFilter` L160 / L718), and would bill for platform briefs she did not paste. `paste` is the only caller that corresponds to a creator action she asked for.

**Where the refund happens: in `paste`, around the `analyse` call, on both failure shapes.**

```java
BriefAnalysisResponse response;
try {
    response = analyse(brief, profile, prefs);                                   // L208 today
} catch (RuntimeException e) {
    creatorCreditService.release(creatorUserId, chargeKey, CreditLedgerReason.BRIEF_REFUND);
    throw e;
}
if (CreatorBrief.EXTRACTION_SOURCE_FALLBACK.equals(response.extractionSource())) {
    creatorCreditService.release(creatorUserId, chargeKey, CreditLedgerReason.BRIEF_REFUND);
}
return response;
```

Both legs are load-bearing, for reasons that are properties of this code rather than defensiveness:

- **The throw leg.** `paste` is **not `@Transactional`** — its own javadoc says so at L196–197, and the class javadoc L58–63 explains why (a throw from the risk or quote step used to roll the raw-text save back). `charge` IS `@Transactional` and is a real proxy call from a different bean, so **it has already committed** by the time `analyse` runs. `analyse` then runs outside any transaction (its javadoc L426–431) and makes four calls that can each throw: `briefAiClient.extract` L436–437 (a blocking HTTP round trip), `dealRiskService.evaluateExtraction` L464–466, `rateQuoteService.quoteForExtraction` L467, and `briefWriter.saveAnalysis` L469. Without the catch, a provider outage debits 3.0 credits per attempt and refunds none — the same defect §4.2 defect (2) records for voice, in a method that is even more exposed because four calls can throw instead of one.
- **The degraded leg.** `analyse` decides at L442: an AI extraction sets `source = EXTRACTION_SOURCE_AI` (L444), and anything else falls to `fallbackExtractor.extract` L447 with `source = EXTRACTION_SOURCE_FALLBACK` (L448) — the constants are `CreatorBrief.java` L52–53 (`"AI"` / `"FALLBACK"`). `source` is returned to the caller as `BriefAnalysisResponse.extractionSource` (`BriefDtos.java` L102) and is also the field persisted by `saveAnalysis` (L475). The creator paid for a model read and got a regex read, so Tejas D7's "technical failures only" is satisfied.

**Test `extractionSource`, not `degradedReason`.** Both are on the response (`degradedReason` at `BriefDtos.java` L103) and the old spec's condition was `extraction_source == FALLBACK` **or** `degraded_reason != null`. Use the first alone: it is the field `saveAnalysis` (L469–475) actually persists, so a later reader or a support query can reproduce the refund decision. **`degradedReason` is NOT persisted at all** — it is a local computed at L445/L449–452 and returned only on the live response — so a refund justified by it leaves no record of its own reason. The two are equivalent today (`degradedReason` is non-null exactly when `source` is FALLBACK), so nothing is lost.

**Open: does a `cap` degradation refund too?** `degradedReason` has two values — `DEGRADED_CAP = "cap"` (`BriefDtos.java` L108, the creator's own monthly USD cap is spent) and `DEGRADED_AI_UNAVAILABLE = "ai_unavailable"` (L111). Testing `extractionSource` refunds **both**. **Default: refund both, awaiting Swapnil.** The argument for it: from the creator's side a spent platform cap is indistinguishable from an outage, and she got a regex read either way. The argument against, which he should see before ruling: once her USD cap is spent, every paste degrades, so every paste refunds, and she gets unlimited free deterministic reads. The loop is bounded — `creator-brief-paste` is 10 per window (`AuthRateLimitFilter` L337, USER-keyed L779) and R7's 500 daily actions still counts each attempt — but it is unbounded in credits. If he rules "outage only", the condition must switch to `!DEGRADED_CAP.equals(response.degradedReason())` and §4.3 must then also say that `degradedReason` needs persisting.

**Three build consequences.**
1. **`charge` MUST catch `AlreadyCompletedException`.** The key `"brief:" + brief.getId()` is STABLE, unlike chat's and voice's freshly-minted keys, so a retried paste against an existing brief id replays. The §3.3 note already requires this catch; §4.3 is the one call site that actually exercises it.
2. **Adding `CreatorCreditService` to `CreatorBriefService` breaks four test files.** The constructor is L150–163 and takes **thirteen** positional arguments; the new one is the fourteenth. Hand-constructed at `CreatorBriefServiceTest` L149 **and** L641, `CreatorBriefServiceRealRiskRulesTest` L114, and `GetBriefExecutorTest` L107. All four must be updated in the same commit or the test module will not compile.
3. **`ensurePlatformBrief` (L231–265) is an UNCHARGED AI read under this design.** It calls `analyse` at L264 for a brief lifted from an on-platform collaboration. That is the correct behaviour for v1 — the creator did not ask for it — but it is a real model call that no credit covers, so it sits behind the USD cap (R4) alone. **Flag to Swapnil**; no default change proposed.

**O2 is NOT settled by this amendment.** The old §4.3 built the 402 (paste with no credits is refused) as the default, and that is unchanged. But the charge site this amendment picks changes what a 402 actually costs her, and the old spec got it wrong in her favour: `charge` fires **after** `saveRawPaste` (L206) and **before** `analyse` (L208), so the raw text is already committed and an exhausted creator's paste is SAVED and then 402s with no analysis, and the brief sits at status `NEW`. That is a better outcome than the old spec's "an exhausted creator still gets… nothing", and it is worth telling Swapnil: her text is not lost, and a later top-up makes `get` re-analyse it through the stale-NEW path at L342–350 (which, per the ruling above, is not charged — so the brief she was 402'd on gets its analysis for free once she has credits). If O2 flips to "free deterministic read", the charge moves after the `aiResult` branch and the shape of this section changes, not just its copy.

---

## 4A. Web search (K12) — new on 2026-09-20

<!-- PRIYA 2026-09-20 --> **Amendment A3 and A4.** New section; nothing in the Phase B original corresponds to it. Placed here, between the other charge points (§4) and the release route (§5), because a search is a fourth charge point and shares §3.3's `charge`/`release` machinery exactly.

**Scope boundary.** Everything below is the **Spring** side: the flag, the route, the charge, the free-weekly counter and the refund. The **AI-service** side — which provider, the Gemini and Claude search routes, the daily router, the pricing rows, and the provider terms that constrain what may be shown and stored — is **PLAN.md §3 step 5**, and is deliberately not restated here so the two cannot drift. Read it there before building either half. The three provider rules that bind the Spring side too are called out in §4A.4.

### 4A.1 Flag — `MEERA_CREATOR_SEARCH_ENABLED`, default false

Search gets its own kill switch, separate from `CREATOR_CREDITS_ENABLED`: credits can be on while search is off.

Add a **third** `@Value` parameter to `MeeraCreatorFeatureProperties` (`influora-api/src/main/java/com/influora/config/MeeraCreatorFeatureProperties.java`, `@Component` L25, class L26, constructor **L31–36**, existing flags `creator-enabled` L32 and `creator-send-enabled` L33):

```java
@Value("${influora.meera.creator-search-enabled:false}") boolean creatorSearchEnabled
```

and a `application.yml` placeholder next to `creator-send-enabled` (**L256**, under `influora.meera:` at L240):

```yaml
    creator-search-enabled: ${MEERA_CREATOR_SEARCH_ENABLED:false}
```

- **Why this class and not `CreatorCreditProperties`.** Its javadoc L16–23 is explicit: a plain `@Value`/`@Component` needs no entry in `InfluoraApiApplication`'s `@EnableConfigurationProperties` list, and this repo has a documented incident where a `@ConfigurationProperties` class compiled fine, was never registered, and crashed boot for every bean that depended on it. A single boolean has no reason to take that risk. (§3.1's note says the same thing about `CreatorCreditProperties`, which is a nine-field class and does need registering.)
- **Name discrepancy, flagged not silently resolved.** PLAN.md §3 calls the env var `CREATOR_SEARCH_ENABLED`. Every flag on this class is `MEERA_CREATOR_*` (`MEERA_CREATOR_ENABLED` L245, `MEERA_CREATOR_SEND_ENABLED` L256). **Default: `MEERA_CREATOR_SEARCH_ENABLED`**, for consistency with its neighbours; if Swapnil or Arjun wants PLAN.md's spelling, change the placeholder only — the yml key and the Java field stay.
- **This breaks five test constructions.** `MeeraCreatorFeatureProperties(boolean, boolean)` is hand-constructed in `influora-api/src/test/java/com/influora/architecture/CreatorSendGateTest.java` at **L285, L288, L294, L297 and L548**. A third constructor parameter must be added to all five in the same commit or the test module will not compile. `CreatorSendGateTest` is itself a gate test that fails when a creator send route appears ungated — read what it asserts before touching it.
- **A guard method on the controller,** beside `requireFeatureEnabled()` (`CreatorMeeraController.java` **L104–109**), returning the same `404 FEATURE_DISABLED` envelope so a disabled search is indistinguishable from an absent route.

### 4A.2 Route — `POST /creator/meera/search`

On `CreatorMeeraController` (`@RequestMapping("/creator/meera")` **L67**, class L68). Guards **in this order**, each copying an existing handler on the same class:

1. `requireFeatureEnabled()` — L104–109, first in every handler on this controller by design (its javadoc L99–103).
2. `requireSearchEnabled()` — new, §4A.1.
3. `CreatorProfile profile = creatorContext.requireCreatorProfile(principal)` — as `transcribe` does at **L282**; `String creatorUserId = profile.getUserId()` L283.
4. `requireConsent(creatorUserId)` — as **L284**. A search sends the creator's question to Google or Anthropic, so consent is not optional here. **Whether the v2 notice already covers that is PLAN.md step 0.4 and is unresolved**; if it does not, the new wording and a version bump ship in the SAME deploy as this route (the G-1 rule).
5. Then the credit decision below.

`SecurityConfig` needs **nothing**: `/creator/**` → `hasRole("CREATOR")` at `SecurityConfig.java` **L296–297** already covers it.

**The credit decision, in order:**

```java
LocalDate weekStart = currentFreeSearchWeekStart();                       // §4A.3
boolean free = creatorCreditService.tryClaimFreeSearch(creatorUserId, weekStart);
String chargeKey = "search:" + Ulids.newUlid();
if (!free) {
    creatorCreditService.charge(creatorUserId, creatorCreditService.searchCost(),  // 25 tenths
            chargeKey, CreditLedgerReason.SEARCH_DEBIT);
}
// ... provider call (PLAN.md §3 step 5) ...
```

- **Free path:** `tryClaimFreeSearch` returned 1. No debit. Write one `FREE_SEARCH` ledger row — `delta = 0`, `bucket = MONTHLY`, `reference_id` = a fresh ULID, `monthly_after`/`purchased_after` = the unchanged balances (§2.2's dated note spells out why each of those values is needed). The row exists so "how many free searches did she use" is answerable from the ledger alone, without trusting the counter column.
- **Paid path:** debit 25 tenths with reason `SEARCH_DEBIT`. `charge` throws 402 `CREDITS_EXHAUSTED` before any provider call, so an exhausted creator never reaches the AI service — the same property R4 gives chat.
- **`ensureInitialized` first.** `tryClaimFreeSearch` is a bare `UPDATE ... WHERE creator_user_id = :id`: it updates **zero rows** when the creator has no row yet, which is indistinguishable from "no free searches left" and would bill a brand-new creator for her first search. This route is a POST, so the lazy grant is legal here (rule 6) — call `ensureInitialized(creatorUserId)` before the claim, exactly as `charge` does.

**Refund rule.** `SEARCH_REFUND` for the full 25 tenths, on the paid path only, whenever the search does not produce an answer. A controller method carries no transaction and `charge` has already committed (the §4.2 defect-2 posture), so:

```java
try {
    result = searchClient.search(creatorUserId, query);
} catch (RuntimeException e) {
    if (!free) creatorCreditService.release(creatorUserId, chargeKey, CreditLedgerReason.SEARCH_REFUND);
    throw e;
}
if (!result.ok()) {
    if (!free) creatorCreditService.release(creatorUserId, chargeKey, CreditLedgerReason.SEARCH_REFUND);
    // fall through to the "search unavailable" response
}
```

Both legs, for the same reason as §4.3: a provider outage must not eat 2.5 credits per attempt, and a degraded-but-200 response is the shape this codebase's AI clients actually return (`MeeraVoiceAiClient` returns a `fallback`, `MeeraBriefAiClient` returns a null extraction). **A free search that fails does NOT get its weekly slot back in v1** — reversing the counter needs a second conditional UPDATE and a way to prove the slot being returned is the one that was taken. Say so in the response copy ("that search didn't work — try again"), and note it as a known rough edge rather than pretending it is a decision.

**Rate limiting: a new bucket, `creator-meera-search`.** This is not optional. `/creator/meera/search` matches **none** of the existing patterns in `AuthRateLimitFilter` — `MEERA_TURN` is `^(/creator)?/meera/sessions/[^/]+/messages$` (**L114–115**), `CREATOR_TOOL` is `^/internal/meera/creator/[^/]+$` (**L144**), `CREATOR_BRIEF_GET` is `^/creator/briefs/[^/]+$` (**L160**), and the voice equality check (L611–614) lists four literal paths. So today it would fall through to `default -> sensitiveLimit` (**L719**) and be **IP-keyed**, because `isUserKeyedBucket` defaults to false (**L760–785**) — the wrong budget on the wrong identity for a per-call AI cost. Three insertions, each beside its nearest sibling:
- `bucketFor`: a `POST`+path check next to the brief-paste one at **L630–632**.
- `limitFor`: `case "creator-meera-search" -> creatorMeeraSearchLimit;` next to **L717**, with a `@Value` field mirroring `creatorBriefPasteLimit` (**L337**).
- `isUserKeyedBucket`: an entry next to `"creator-brief-paste"` at **L779**, for the reason its own comment there gives — the AI-spend identity to bound is the creator's, not her network's.
- A bucket test mirroring `influora-api/src/test/java/com/influora/security/AuthRateLimitFilterBriefPasteBucketTest.java`.

### 4A.3 The weekly free-search counter (A4)

**Columns** (§2.1, in the same migration): `free_searches_used INT NOT NULL DEFAULT 0` and `free_search_week_start DATE NULL`. Same shape as `daily_actions_used` / `daily_actions_date`, which is the brand shape from `V16__daily_action_cap.sql` L5–6.

**Reset rule: lazy, on read — no job, no cron, no new scheduled bean.** The week is not "reset"; the stored `free_search_week_start` is compared to the current week's Monday and a mismatch means the count is zero. That is what the `CASE WHEN ... THEN used + 1 ELSE 1 END` in `tryClaimFreeSearch` (§2.6) does in one statement. The precedent is `AICreditService.tryConsume`'s daily roll — **at L152, with the roll at L167–171 and the cap check at L177** (the old §3.3 cites "L122–142"; that is wrong on this branch) — with the one difference §2.6 explains at length: the brand version reads then decides, and a free allowance cannot afford that race.

**Week boundary: Monday 00:00 IST. Default, awaiting Swapnil (PLAN.md D3).**

```java
static final ZoneId FREE_SEARCH_WEEK_ZONE = ZoneId.of("Asia/Kolkata");
LocalDate currentFreeSearchWeekStart() {
    return LocalDate.now(FREE_SEARCH_WEEK_ZONE)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
}
```

**This is the only non-UTC boundary in the credit system, and that has to be deliberate rather than accidental.** Everything else here is UTC: `cycleStart` and `lastReset` use `LocalDate.now(ZoneOffset.UTC)` (§2.5, §3.3), §8's reset cron is `zone = "UTC"`, `resets_on` is the first of next UTC month (§6, §13 Q11), and the daily action counter rolls on `todayUtc`. IST is nonetheless defensible for a creator-facing week, and there is precedent for both halves of it: `CreatorConnectNudgeJob` **L86** is `@Scheduled(cron = ..., zone = "Asia/Kolkata")` for exactly the "a creator reads this" reason, `User.timezone` defaults to `"Asia/Kolkata"` (`User.java` L120, L146), and `CreatorAgentPreferences.DEFAULT_WORKING_HOURS_TIMEZONE` is the same (L58). **Conditions on keeping it:** one named constant, never a second inline `ZoneId.of`, and the FE copy must say "resets Monday" without a time (the creator's own zone may not be IST). If Swapnil prefers one boundary for everything, switch the constant to `ZoneOffset.UTC` — it is a one-line change and no column changes.

**Who gets the free searches: everyone. Default, awaiting Swapnil (PLAN.md D2).**

**So do NOT build the D1 eligibility predicate yet.** This is the part worth reading twice. PLAN.md's D1 asks how to identify a "free-credit creator" and correctly notes the obvious test is broken; D2's default is that *every* creator gets 2 free searches a week. Under that default **the eligibility question is never asked**, and building a predicate nothing calls is how wrong code ships. Build the counter; leave `existsByCreatorUserIdAndReasonAndCreatedAtGreaterThanEqual` (§2.6) unbuilt, with its comment, until D2 is ruled `no`.

**And when it is asked, `purchased_balance = 0` is the WRONG test — the spec must say so, because it is the test a reader will reach for.** R2 (§1) puts the **signup grant into `purchased_balance`**: `ensureInitialized` (§3.3) sets `purchasedBalance = props.signupGrant` = 300 tenths on the very first session. So a creator who has never paid a rupee has `purchased_balance = 300`, and `purchased_balance = 0` identifies her as a *payer*. It is also wrong in the other direction: a real payer who spends her pack down reaches `purchased_balance = 0` and would be reclassified as free. The bucket is a balance, not a history, and eligibility is a question about history.

**D1's default test, if D2 is ruled `no`:** no `PACK_PURCHASE` ledger row in the current calendar month —
`existsByCreatorUserIdAndReasonAndCreatedAtGreaterThanEqual(creatorUserId, CreditLedgerReason.PACK_PURCHASE, monthStartInstant)`, false meaning "free-credit creator". The ledger is the history, `PACK_PURCHASE` is written only by `creditPack` (§3.3) from a confirmed Razorpay order, and `ADMIN_GRANT` and `SIGNUP_GRANT` are deliberately excluded — a support grant is not a purchase. Use the same month boundary as everything else (UTC, §13 Q11), not the IST week boundary above.

### 4A.4 What the response must carry (provider terms)

The full terms are PLAN.md §3 step 5 and §6; three of them constrain this Spring route directly and are therefore repeated here as requirements, not polish:

1. **A Gemini result is its own card and is never stored, never cached, and never fed into Meera's chat context.** Google's search suggestions must be displayed with it. This means the search response must NOT be persisted as an `ai_messages` row and must not be appended to the conversation the assembler reads.
2. **A Claude result must show its sources.** Citations come back with the answer and the response DTO must carry them.
3. **Search result text is untrusted data and is never treated as instructions** — not by Meera, not by any tool, not by the renderer. It arrives from the open web through a provider; it is on the same footing as a brand's pasted brief.

Logs record counts, never query text (this is also PLAN.md's check on kabir).

### 4A.5 Cost fuse

See **R4** (§1), amended on 2026-09-20: the USD cap must count search fees, and `influora-ai/app/costs/pricing.py` is the file that has to change. Nothing in the Spring side changes for it.

---

## 5. Release route (K4) — `MeeraInternalController.releaseTurnCredit` L354–360

```java
AiConversation conversation = sessionService.resolveConversation(body.conversationId());
var ctx = onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, conversation.getWorkspaceId());
sessionService.releaseTurnCredit(conversation.getWorkspaceId(), body.turnId(), ctx.userType());
```

`resolveForWorkspace` returns `OnBehalfContext(userId, workspaceId, userType, conversationId)` (`security/OnBehalfAuthResolver.java` L78, L86); the writeback route at L337 already reads `ctx.userType()` from it. No Python change: `spring.py release_turn_credit` L344–379 already sends `{conversationId, turnId}` and the on-behalf header for creator turns; today that call ends in `AICreditService.doRelease` guard 1 as a WARN no-op. After this change it refunds the creator ledger.

---

## 6. Read routes and response wiring (K5) — `CreatorMeeraController`

DTOs in a new `com.influora.web.dto.meera.CreatorCreditDtos` (snake_case via `@JsonProperty`, the convention `AdminCreatorAgentDtos` uses):

```java
public record CreatorCreditStatusResponse(
    @JsonProperty("enabled") boolean enabled,
    @JsonProperty("monthly_remaining") BigDecimal monthlyRemaining,
    @JsonProperty("monthly_allotment") BigDecimal monthlyAllotment,
    @JsonProperty("purchased_balance") BigDecimal purchasedBalance,
    @JsonProperty("total") BigDecimal total,
    @JsonProperty("cycle_start") LocalDate cycleStart,
    @JsonProperty("resets_on") LocalDate resetsOn,          // first day of next UTC month
    @JsonProperty("low_balance_threshold") BigDecimal lowBalanceThreshold,
    @JsonProperty("state") String state,                     // DISABLED | OK | LOW | EXHAUSTED
    // <!-- PRIYA 2026-09-20 --> A3/A4 — the search card needs these two (§4A).
    @JsonProperty("free_searches_left") int freeSearchesLeft,        // a COUNT, not tenths
    @JsonProperty("search_cost") BigDecimal searchCost) {}
public record CreditLedgerItem(String id, @JsonProperty("delta") BigDecimal delta, String bucket, String reason, @JsonProperty("reference_id") String referenceId, @JsonProperty("monthly_after") BigDecimal monthlyAfter, @JsonProperty("purchased_after") BigDecimal purchasedAfter, @JsonProperty("created_at") Instant createdAt) {}
```

<!-- PRIYA 2026-09-20 --> **Amendment A1: this is THE conversion point, and it is the only one.**

**One helper, on `CreatorCreditDtos`:**

```java
/** Tenths (§0 rule 11) -> the one-decimal number the creator sees. 375 -> 37.5 */
public static BigDecimal toDisplay(int tenths) {
    return BigDecimal.valueOf(tenths, 1);
}
/** The inverse, for the one inbound number: the admin grant (§9). */
public static int toTenths(BigDecimal credits) { ... }  // scale check, reject > 1 dp
```

`BigDecimal.valueOf(long, int scale)` is exact and needs no rounding mode — 375 with scale 1 *is* 37.5, and the scale is preserved, so Jackson writes `37.5` and `40.0` rather than `37.5` and `40`. **Assert that in the DTO test rather than trusting it**: there is no `ObjectMapper` customiser anywhere in `influora-api/src/main/java/com/influora/config/`, so Spring Boot's defaults apply today, and a future `WRITE_BIGDECIMAL_AS_PLAIN` or number-as-string setting would change the wire shape of every number in this response. A one-line assertion on the serialized JSON is the cheap guard.

**`BigDecimal`, not `double` and not a pre-formatted string.** `double` breaks rule 9 for the same reason it breaks it for money. A string would push formatting into the DTO and force the FE to parse before it can compare (`total <= threshold` for the LOW state is still computed server-side, in tenths, before conversion — see below).

**`state` is still computed in TENTHS, before conversion.** `DISABLED` if `!enabled`; `EXHAUSTED` if `total == 0`; `LOW` if `total <= props.lowBalanceThreshold` (100 tenths); else `OK`. Never compare the `BigDecimal`s — `compareTo` vs `equals` on `BigDecimal` is a classic trap (`new BigDecimal("40.0").equals(new BigDecimal("40"))` is false) and there is no reason to go near it when the integer comparison is right there.

**What `GET /creator/meera/credits` returns now.** A creator who has started a session, used one chat turn and one paid search, and has both free searches left this week:

```json
{
  "data": {
    "enabled": true,
    "monthly_remaining": 36.5,
    "monthly_allotment": 40.0,
    "purchased_balance": 30.0,
    "total": 66.5,
    "cycle_start": "2026-09-01",
    "resets_on": "2026-10-01",
    "low_balance_threshold": 10.0,
    "state": "OK",
    "free_searches_left": 2,
    "search_cost": 2.5
  }
}
```

(Stored: `monthly_remaining = 365`, `monthly_allotment = 400`, `purchased_balance = 300`. 400 − 10 for the chat turn − 25 for the search = 365.)

`free_searches_left` is `max(0, props.freeWeeklySearches − used)` where `used` is `free_searches_used` if `free_search_week_start` equals the current week's Monday (§4A.3) and `0` otherwise — the same lazy roll the claim query does, computed read-only here. It is a **count**, so it stays an `int`; `search_cost` is a credit amount, so it converts.

**The "not yet initialised" case is unchanged in shape and changes in numbers:** empty → `monthly_remaining = toDisplay(props.monthlyAllotment)` = 40.0, `purchased_balance = toDisplay(props.signupGrant)` = 30.0, `total` = 70.0, `free_searches_left = props.freeWeeklySearches`, `state = OK`.

Routes (all: copy of `requireFeatureEnabled()`; `requireCreatorProfile`; **no** consent needed for reading a balance):

| Route | Handler | Notes |
|---|---|---|
| `GET /creator/meera/credits` | `credits(principal)` → `ApiResponse<CreatorCreditStatusResponse>` | `getStatus` (no create). Empty → `monthly_remaining = monthlyAllotment`, `purchased_balance = signupGrant`, `total` = sum, `state = OK` (the grant is real the moment they start a session). `state`: `DISABLED` if `!enabled`; `EXHAUSTED` if total 0; `LOW` if total ≤ threshold; else `OK`. |
| `GET /creator/meera/credits/ledger?limit=50` | `ledger(principal, limit)` → `List<CreditLedgerItem>` | `limit` clamp 1..200. |

<!-- PRIYA 2026-09-20 --> **Amendment A1: the two shared brand DTOs must NOT become decimal, so a creator gets no credit number through either of them.** I opened both.

- **`MeeraDtos.CreditsSummary`** is `(int remaining, boolean unlimited)` at `MeeraDtos.java` **L23**, and it has exactly **one** construction site in the whole tree: `MeeraController.java` **L113**, `new CreditsSummary(credit.getCreditsRemaining(), unlimited)` — the brand path, whole brand credits. `startSession` in `CreatorMeeraController` passes `(CreditsSummary) null` at **L172** (inside `new SessionStartResponse(` at L165).
  **Ruling: keep `null` for creators.** Do not populate it with `ensureInitialized(...).total()`, which the old §6 suggested: 665 tenths rendered into an `int` field the brand FE reads as credits is a number ten times too large on one audience's screen. The field is `@JsonInclude(NON_NULL)` (L22) so it is simply absent, and the creator FE already handles absence. This also removes the only reason `startSession` needed to call `ensureInitialized` for its response — the lazy grant still happens there (R3, rule 6: it must be a write route), it just no longer feeds a response field.
- **`SendTurnResponse.creditsRemaining`** is `int` at `MeeraDtos.java` **L56**. `CreatorMeeraController.sendTurn` passes a literal `0` at **L204** with the comment at L202–203 saying it "is not credits remaining, it is simply unused on this audience".
  **Ruling: keep the `0` and the comment.** The old §6's `result.creditsRemaining() == null ? -1 : ...` and the §4.1 step-5 change that fed it — adding a seventh `Integer creditsRemaining` component to `TurnResult` and threading `ChargeResult.totalAfter` through it — are **both dropped**. In tenths that field would carry 665 into an `int` the FE reads as credits.
  **What replaces it:** the creator FE refreshes the badge from `GET /creator/meera/credits` after a turn. One extra cheap GET per turn, against a route that already exists, in exchange for not putting two units in one shared `int`. §10.3's `turnRes.creditsRemaining >= 0` bullet is therefore also dropped, and §10.4's badge callback fires the GET instead of taking a number.

**Net effect on the build order:** §12 step 3 loses the `TurnResult` change entirely (no new record component, no construction-site update at `MeeraSessionService.java` L402), and §12 step 5 loses the session/turn wiring. Both were pure cost with no product value once credits are decimal.

`SecurityConfig`: nothing. `/creator/**` → `hasRole("CREATOR")` — **`SecurityConfig.java` L296–297** on this branch, not L227 <!-- PRIYA 2026-09-20 --> — already covers the new routes. Rate limiting for the two READ routes: no new bucket, reads are cheap. **The search route is different and does need one — §4A.2.**

---

## 7. Packs (K6)

### 7.1 `CreatorCreditOrderService` (`com.influora.service.meera`)

```java
public static final String RECEIPT_PREFIX = "credits:";
public CreatorCreditOrderService(CreatorCreditOrderRepository orders, CreatorCreditPackRepository packs,
    CreatorCreditService credits, RazorpayClient razorpayClient, CreatorCreditProperties props, AuditLogService audit)
```

`@Transactional public CreatorCreditOrder initiate(String creatorUserId, String packCode, String idempotencyKey)`:
1. `!props.enabled` → 404 `CREDITS_DISABLED`.
2. `idempotencyKey` blank → 400 `IDEMPOTENCY_KEY_REQUIRED` (same message as `WalletController` L100–103).
3. `orders.findByCreatorUserIdAndIdempotencyKey` → return existing (replay).
4. `packs.findByCodeAndActiveTrue(packCode)` → 404 `PACK_NOT_FOUND`.
5. `razorpayClient.isConfigured()` false → 503 `RAZORPAY_MISCONFIGURED` (the code `SubscriptionService.initiateCheckout` uses).
6. Save order `PENDING` with snapshots; `razorpayClient.createOrder(BigDecimal.valueOf(pack.getPricePaise(), 2), "INR", RECEIPT_PREFIX + order.getId())`; `order.setRazorpayOrderId(result.orderId())`; save.

`@Transactional public CreatorCreditOrder confirmCredited(String orderId, String paymentId, Long amountInPaise, String currency)` — mirror `WalletTopUpService.confirmCredited` L176–210: `ORDER_NOT_FOUND`; already `CREDITED` → return; amount/currency null or mismatch vs the order snapshot → `log.error` and throw (manual review, exactly the top-up posture); `credits.creditPack(order.getCreatorUserId(), order.getCredits(), order.getId())`; `order.markCredited(paymentId)`; `audit.recordMoneyEvent(order.getCreatorUserId(), "CREATOR_CREDIT_PACK_CREDITED", BigDecimal.valueOf(amountInPaise, 2), null, null, RECEIPT_PREFIX + order.getId(), Map.of("orderId", …, "packCode", …, "credits", …, "paymentId", …))`. <!-- PRIYA: the "free string" claim is CORRECT and I checked it the only way that matters —
     `V15__audit_log.sql` L11 declares `workspace_id VARCHAR(26) NULL` with a COMMENT that says
     "FK workspaces(id)" but there is NO `FOREIGN KEY` / `CONSTRAINT` clause anywhere in that
     file. Passing a creator user id will not violate anything. Keep the explanatory comment in
     the code; the column's own comment lies and the next reader will need it.
     Signature confirmed: `recordMoneyEvent(String workspaceId, String eventType, BigDecimal
     serverAmount, BigDecimal beforeBalance, BigDecimal afterBalance, String idempotencyKey,
     Map<String,Object> detail)` at AuditLogService.java L110-117, `REQUIRES_NEW`. -->
<!-- PRIYA: `Map.of(...)` REJECTS null values with an NPE. `event.paymentId()` is nullable on the
     webhook path — `dispatchFundingEventIfResolvable` (RazorpayWebhookController L187-196)
     guards only `entityId()`, never `paymentId()`. An `order.paid` payload without a payment id
     would NPE inside the audit call AFTER `creditPack` has already added the credits, losing the
     audit row and 500-ing the webhook so Razorpay retries into a now-CREDITED order. Build the
     detail map with `HashMap` (or coalesce each value to `""`), not `Map.of`. -->
`recordMoneyEvent`'s first parameter is named `workspaceId` (L110) but is a free string; pass the user id and say so in a comment.

<!-- PRIYA: line drift — L158-168 is the METHOD JAVADOC. `private void dispatchFundingEvent(
     WebhookEvent event)` is at **L170**; the `topup:` branch is L172-177 and the
     `escrowService.confirmFunded` fallthrough is L178-179. The insertion instruction itself
     ("before the fallthrough, after the topup: branch") is unambiguous and correct.
     WORTH KNOWING, and the spec does not say it: `payment.captured` reaches the same place —
     `dispatchFundingEventIfResolvable` (L187) delegates to `dispatchFundingEvent` at L195 — so
     this ONE branch covers both event types. No second insertion is needed.
     Receipt length is safe: `credits:` (8) + a 26-char ULID = 34, under Razorpay's 40-char
     receipt limit, and it cannot be confused with `topup:` or a bare 26-char escrow ULID. -->
### 7.2 Webhook branch — `RazorpayWebhookController.dispatchFundingEvent` L170–179

Insert **before** the `escrowService.confirmFunded` fallthrough, after the `topup:` branch:

```java
if (receipt != null && receipt.startsWith(CreatorCreditOrderService.RECEIPT_PREFIX)) {
    String orderId = receipt.substring(CreatorCreditOrderService.RECEIPT_PREFIX.length());
    creatorCreditOrderService.confirmCredited(orderId, event.paymentId(), event.amountInPaise(), event.currency());
    return;
}
```

Inject `CreatorCreditOrderService` into the controller constructor; update `RazorpayWebhookControllerTest`'s construction of the controller (open it; it constructs by hand) and add a routing test: a `order.paid` payload whose receipt is `credits:<id>` reaches `confirmCredited` and never `escrowService`.

### 7.3 Controller `CreatorCreditController` (`com.influora.web`, `@RequestMapping("/creator/credits")`)

| Route | Body | Response |
|---|---|---|
| `GET /creator/credits/packs` | | `List<PackItem(code, name, credits, price_paise, price_display)>`; `price_display` = "₹249 (incl. GST)" formatted server-side so copy is one place. <!-- PRIYA 2026-09-20 --> **A1: `credits` is a `BigDecimal` from `CreatorCreditDtos.toDisplay(pack.getCredits())`** — the column holds tenths (§2.3), so a Starter pack is stored `500` and shown `50.0`. `price_paise` is unchanged. Consider a `credits_display` string ("50 credits") alongside it for the same one-place-for-copy reason `price_display` exists. |
<!-- PRIYA: I opened it. The route EXISTS, so drop `key_id` from the response.
     `getRazorpayKeyId()` (src/lib/razorpay.ts L116-131) calls `api.config.razorpay()`
     (src/lib/api.ts L3462), i.e. `GET /config/razorpay`, and caches the result for the session.
     `PublicConfigController` L63 describes that route as "the Razorpay checkout launcher's only
     source for the key". `OpenCheckoutParams` (razorpay.ts L133-146) has NO key field at all —
     `openRazorpayCheckout` L157 resolves it itself. A `key_id` in `OrderResponse` would be
     dead weight and a second place for the value to drift. Remove it. -->
| `POST /creator/credits/orders` | header `Idempotency-Key`, `CreateOrderRequest(@NotBlank pack_code)` | 201 `OrderResponse(order_id, razorpay_order_id, amount_paise, currency, credits, status)`. **No `key_id`** — the FE already gets it from `GET /config/razorpay` via `getRazorpayKeyId()`. |
| `GET /creator/credits/orders/{id}` | | `OrderResponse`; FE polls this after checkout closes until `CREDITED` (webhook-driven, same as the wallet top-up UX). |

Feature flag copy + `requireCreatorProfile` on every route. No consent required (buying is not using Meera).

---

## 8. Reset job (K7) — `com.influora.job.CreatorCreditResetJob`

Copy `AICreditResetJob` L40–110 structurally:
<!-- PRIYA 2026-09-20 --> **Amendment A1: `monthlyAllotment` is 400 tenths**, so `resetForNewCycle` sets `monthly_remaining = 400`. The cron, the lock, the re-entrancy guard, the UTC month boundary and the ten-minute offset from the brand job are all unchanged. **The weekly free-search counter is NOT touched by this job** — it rolls lazily on read (§4A.3) and needs no scheduled reset, which is why A4 adds no second cron.

```java
@Scheduled(cron = "0 10 2 1 * ?", zone = "UTC")
@SchedulerLock(name = "CreatorCreditResetJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
public void resetAllCreatorCreditsForNewMonth()
```

`AtomicBoolean` re-entrancy guard; `creatorAiCreditRepository.findAllCreatorUserIds()` (only creators who have ever started a session — no `UserRepository` finder needed and no spurious rows, the lesson recorded in `AICreditResetJob`'s javadoc L78–82); `cycleStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)`; per-id try/catch; counters in the log line. Skip entirely when `!props.enabled`. Ten minutes after the brand job so the two never contend for the DB at the same minute. `TaskSchedulerConfig.POOL_SIZE` is 10 today (Phase D asks for 16); one more monthly job is fine.

---

## 9. Admin grant (K8) — `AdminCreatorAgentController`
<!-- PRIYA 2026-09-20 --> **Amendment A1: the admin types CREDITS; the service takes TENTHS.** `GrantCreditsRequest.credits` is a `BigDecimal` with `@DecimalMin("0.1")` / `@DecimalMax("10000.0")` and at most one decimal place; the controller converts once with `CreatorCreditDtos.toTenths(...)` (§6) before calling `CreatorCreditAdminService.grant`, which passes tenths to `CreatorCreditService.adminGrant` (bounds 10..100 000, §3.3). `GrantCreditsResponse` converts the returned new total back with `toDisplay`. Reject more than one decimal place with a 400 rather than rounding it — an operator who types 12.34 must be told, not silently given 12.3.

<!-- PRIYA: L52-59 confirmed (`@PutMapping("/creators/{creatorId}/monthly-cap")` L52, method body
     to L59), and "creatorId is a profile id" confirmed from the method javadoc L45-51.
     MISSING STEP: `AdminCreatorAgentController`'s constructor (L33-37) takes exactly
     `(CreatorAgentBaselineService baselineService, CreatorAgentPreferencesService
     preferencesService)`. The snippet below calls `creatorCreditAdminService`, which is not a
     field on this class. Add the field AND the constructor parameter — the spec never says to.
     `principal.getUserId()` is valid (`AuthPrincipal.getUserId()` L24).
     `CreatorProfileRepository` extends `JpaRepository<CreatorProfile, String>` so `findById`
     works, and `CreatorProfile.getUserId()` is at L232. -->
Add a `CreatorCreditAdminService` field + constructor parameter (the class currently takes only `CreatorAgentBaselineService, CreatorAgentPreferencesService`, L33–37), then add after `setMonthlyCap` L52–59:

```java
@PostMapping("/creators/{creatorId}/credits/grant")
public GrantCreditsResponse grantCredits(@AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable String creatorId, @Valid @RequestBody GrantCreditsRequest req) {
    return new GrantCreditsResponse(creatorCreditAdminService.grant(creatorId, req.credits(), principal.getUserId(), req.note()));
}
```

`creatorId` is a **profile id** on this controller (its javadoc and `adminSetMonthlyCapOverride` say so). `CreatorCreditAdminService.grant` resolves `creatorProfileRepository.findById(profileId)` → 404 `CREATOR_PROFILE_NOT_FOUND` → `profile.getUserId()` → `creatorCreditService.adminGrant(userId, credits, adminUserId, note)`, then `auditLogService.recordAdminAction(adminUserId, "CREATOR_CREDITS_GRANTED", OUTCOME_ALLOWED, detail)`. DTOs in `AdminCreatorAgentDtos`: `GrantCreditsRequest(@Min(1) @Max(10000) int credits, @Size(min = 10, max = 255) String note)` (the ≥10-char reason is the admin billing convention), `GrantCreditsResponse(@JsonProperty("total") int total)`. Bare DTO return, no `ApiResponse` wrapper (admin convention, controller javadoc L23–24). Admin FE: one input on the existing creator-agent admin page; `src/admin/services/api-contracts.ts` gets `grantCredits(creatorId, body)`.

---

## 10. Frontend (K10)

### 10.1 `src/lib/meera-api.ts`

<!-- PRIYA: do NOT overload `getCredits`. It is declared `getCredits: async (): Promise<
     MeeraCreditStatus>` (meera-api.ts L572) and `MeeraCreditStatus` (L99-106) is a DIFFERENT
     shape — `{creditsRemaining, monthlyAllotment, unlimited, unlimitedUntil, cycleStart, state:
     'FREE'|'UNLIMITED'|'EXHAUSTED'}` — with no `enabled`, no `total`, no `purchased_balance`,
     and a `state` union that does not contain 'DISABLED', 'OK' or 'LOW'. Adding a role
     parameter widens the return type to a union and forces every existing brand call site to
     narrow. Worse, the `!isApiLive()` mock branch (L573-582) returns the BRAND shape
     unconditionally, so creator mock mode would render brand numbers.
     FIX: add a separate `getCreatorCredits(): Promise<CreatorCreditStatus>` with its own mock
     branch. Leave `getCredits` untouched. -->
<!-- PRIYA 2026-09-20 --> **Amendment A1 + corrected line numbers.** The note above was written on 2026-09-05 and its references have drifted; I re-opened `src/lib/meera-api.ts` on this branch. `MeeraCreditStatus` is at **L108** (not L99–106), `getCredits` at **L851** (not L572) with its `!isApiLive()` mock branch immediately below, and `MeeraTurnResponse.creditsRemaining` at **L76** (not L67). The *substance* of the note is unchanged and still correct: `MeeraCreditStatus` is the brand shape and `getCredits` must not be overloaded.
- `CreatorCreditStatus`'s credit fields are **`number`**, and they carry DECIMALS (36.5, 40.0) because the server converts tenths once (§6). The FE never divides by ten, never multiplies by ten, and never sees a tenths value. Declare only the fields the Java record actually sends (memory: a TS type asserting a field the record never sends passes `tsc` and renders empty) — that is now twelve fields including `free_searches_left` (an integer count) and `search_cost`.
- Display with **one decimal place always** (`toFixed(1)`), so a balance does not flip between "40" and "37.5" between renders.
- <!-- PRIYA 2026-09-20 --> The `MeeraTurnResponse.creditsRemaining` bullet is **dropped**: §6 rules that a creator turn response carries no credit number, and the badge refreshes from `GET /creator/meera/credits` instead. Leave L76 alone; it is the brand field.

### 10.2 `src/lib/api.ts`

- `MoneyOperation` L69: add `'credit-pack'`. `isMoneyActionBlocked` L119–122 needs no edit: any non-`withdraw` operation is gated by `PAYMENTS_IN_ENABLED`. Add one case to `src/lib/payments-gate.test.tsx`.
- New namespace `creatorCredits`: `getPacks()`, `createOrder(packCode, idempotencyKey)` (calls `requirePaymentsEnabled('credit-pack')` first), `getOrder(id)`, `getLedger(limit)`.

### 10.3 `MeeraCopilotChat.tsx`

<!-- PRIYA: the `kind` variant DOES NOT EXIST in the tree. Messages in MeeraCopilotChat are plain
     `{ id, role, text }` — see the pushes at L271-272 and L312. "Phase B §14.4.c already
     requires it" cites a SPEC, not code; Phase B has not been built. §12 step 9 must state the
     dependency: either B's variant type lands first, or this track introduces it (a ~10-line
     change: widen the message type, default `kind: 'text'`, switch in the renderer). Say which.
     Line numbers confirmed: stream `onError` callback opens at L255 and its cap branch is
     L265-268; the non-stream `.catch` is L300-313. -->
- On `sendTurn` `.catch` (L300–313) and the stream `onError` (L255–273, cap branch L265): add a branch for `err.code === 'CREDITS_EXHAUSTED'` that pushes a message with `kind: 'credits'`. **The `kind` discriminator does not exist yet** — messages are `{ id, role, text }` today. Either take Phase B §14.4.c's variant as a hard prerequisite, or introduce `{ id, role, kind: 'credits' | 'cap' | 'text', text }` in this track (§12 step 9 must say which). Rendered by a small `CreditsExhaustedCard`: Tejas's empty-state copy verbatim, CTA 1 "Buy 100 credits — ₹249" → `/creator/credits`, CTA 2 "See what's still free" → `/creator/how-it-works` (route exists, `App.tsx` L543). Never the word "escrow".
- After a successful turn, `turnRes.creditsRemaining >= 0` → update the badge via a callback prop `onCreditsChanged(total)`.
- Voice: the transcribe hook must surface a 402 as the same card rather than "fallback".

### 10.4 Badge and nudge — `src/pages/creator-copilot.tsx`

Next to `<h1>Co-pilot</h1>` (L121): `CreatorCreditsBadge` reading `meeraApi.getCreatorCredits()` <!-- PRIYA 2026-09-20 --> (**not** `getCredits('creator')` — §10.1's note rules out the overload) on mount and whenever a turn completes. Hidden when `state === 'DISABLED'` or the request 404s (feature flag off). Copy: **"{total} credits"** with `total` at one decimal place, e.g. "36.5 credits" <!-- PRIYA 2026-09-20 --> (A1). `LOW` state shows Tejas's nudge banner above the chat ("You have {X} credits left. Top up now so Meera can keep helping." → `/creator/credits`), dismissible, with `X` at one decimal place too.

<!-- PRIYA 2026-09-20 --> **A1: the badge refreshes by re-fetching, not from the turn response.** §6 drops `SendTurnResponse.creditsRemaining` for creators, so `onCreditsChanged` takes no number — it is a signal to re-run `getCreatorCredits()`. One extra GET per turn against a cheap read route, in exchange for not carrying tenths through a shared brand `int`.

<!-- PRIYA: `useMeeraCredits.ts` is ALSO an orphan, not a live hook — its only importer in the
     whole `src` tree is `CreditMeter.tsx` L21 (`import type { CreditState }`), and nothing
     imports `CreditMeter`. Copying its state names is still fine (that is all the instruction
     asks), but do not treat it as a proven-in-production state machine. -->
Reuse `useMeeraCredits.ts`'s state names where they fit; do not reuse `CreditMeter.tsx` (orphan, brand-shaped). Leave that file alone. (`useMeeraCredits.ts` is itself only imported by `CreditMeter.tsx` — both are dead code today.)

### 10.5 `/creator/credits` page — `src/pages/creator-credits.tsx`

Route in `App.tsx` inside the protected creator block next to `/creator/wallet` (L570). Sections: balance card (monthly / purchased / resets on), three pack cards from `getPacks()` (price_display from server), "Buy" → `createOrder(code, uuid)` → `openRazorpayCheckout` (`src/lib/razorpay.ts` L152; open `brand-wallet.tsx`'s top-up handler and copy its params and its post-checkout poll) → poll `getOrder(id)` until `CREDITED` → refresh balance and toast "100 credits added". Ledger table below (last 50). If `isMoneyActionBlocked('credit-pack')` show `PaymentsUnavailableNotice` (`src/components/payments/payments-unavailable-notice.tsx`). Free-forever list at the bottom: money watch, delivery proof, health flags, weekly note, settings (Tejas §1) — phrased as "always free", no phase names.

<!-- PRIYA: `CREATOR_INCLUDED` is at L73-79 (declaration L73, five string entries L74-78,
     closing `];` L79), not L72-78. The rest of the paragraph checks out: five bullets today,
     and the file header's CTO copy ruling is about FEE digits, which a pack price is not. -->
### 10.6 Pricing page — `src/pages/pricing.tsx` L181–187 <!-- PRIYA 2026-09-20 -->

<!-- PRIYA 2026-09-20 --> **Corrected reference.** `CREATOR_INCLUDED` is at **L181–187** on this branch (declaration L181, five string entries L182–186, closing `];` L187), not L73–79 as the 2026-09-05 note said — a drift of 108 lines. The five existing bullets and the substance of the note below are unchanged.

Add two bullets to `CREATOR_INCLUDED` after the existing five: `'Meera assistant: 40 free credits every month, plus 30 to start'` and `'Extra credits from ₹149, no subscription'`. Matrix and FAQ unchanged. The file header's CTO ruling (no fee digits) is respected; a credit pack price is not a fee. **This needs Tejas/CTO sign-off before merge (O4)**; build it behind the same `VITE_CREATOR_CREDITS_ENABLED` flag as the rest of K10.

<!-- PRIYA 2026-09-20 --> **A1: the COPY says 40, and that is correct.** 40 is what the creator sees; 400 is what the column holds. Marketing copy uses display credits everywhere and never mentions tenths — a creator must never encounter the word. Whole numbers need no decimal point in copy ("40 free credits", not "40.0"); only live balances, which can be fractional, are rendered with `toFixed(1)`. **Search is deliberately NOT in these two bullets** — it ships behind its own flag (§4A.1) and PLAN.md D4 (the $483/month budget) is unresolved, so a third bullet would be a promise we have not funded. Add it when Swapnil funds it.

<!-- PRIYA: WRONG FILE AND A MISSING STEP — as written this flag would bind to nothing and could
     never be turned on.
     (1) There is no `.env.example` at the repo root. The files that exist are `.env.local`,
         `.env.local.example` and `.env.production`.
     (2) The step that actually matters is omitted: `Dockerfile` L54-63 declares
         `ARG VITE_PAYMENTS_IN_ENABLED=true` / `ARG VITE_PAYOUTS_ENABLED=false` and promotes both
         to `ENV`. `src/lib/api.ts` L102-107 (the F-0390 post-mortem) records the rule in so many
         words: "The published image's value comes from the workflow build-arg, NOT from
         .env.production: Vite's loadEnv merges process.env over the .env files, and the
         Dockerfile promotes each build-arg to an ENV." A VITE var added only to
         `.env.production` and the workflow is invisible to the built bundle —
         `import.meta.env.VITE_CREATOR_CREDITS_ENABLED` is `undefined`, `=== 'true'` is false
         forever. That is F-0390 repeating verbatim.
     (3) `publish-images.yml` also passes these as build-args at L227-228 and validates them at
         L165-181; add the new name to the build-args block, not just "the env block". -->
FE flag: `VITE_CREATOR_CREDITS_ENABLED` (default `false`) added to **all four** of: `Dockerfile` (`ARG` + `ENV`, alongside L54–63), `.github/workflows/publish-images.yml` build-args (alongside L227–228), `.env.production`, and `.env.local.example`. There is no `.env.example`. Without the Dockerfile ARG/ENV pair the flag binds to nothing in the published image and can never be flipped on. Used only to hide surfaces before the backend flag flips; the backend flag is the source of truth.

### 10.7 Tests

Vitest with `vi.hoisted` + `vi.mock('@/lib/api')` (Phase B §8.10 pattern): badge hidden on DISABLED; exhausted card renders both CTAs on `CREDITS_EXHAUSTED`; nudge suppression; packs page renders three packs and calls `openRazorpayCheckout` with the server order id; `payments-gate.test.tsx` case for `'credit-pack'`.

---

## 11. Backend tests

- `CreatorCreditServiceTest` (mirror `AICreditServiceTest` L57 construction with mocks): lazy init writes SIGNUP_GRANT + MONTHLY_RESET; split 1 from monthly; split across buckets when monthly is short; 402 when both short; digest round-trips through release; release refuses when never charged; release refuses chat refund after writeback; voice refund does not check the writeback scope; disabled → NOT_CHARGED and no DB writes; reset clamps and is idempotent per cycle.
- `MeeraSessionServiceTest`: creator turn calls `creatorCreditService.charge` with the user id and `TURN_DEBIT`; brand path untouched (existing assertions); writeback stamps `chargedCost`; `releaseTurnCredit(…, CREATOR)` routes to the creator service.
- `CreatorMeeraControllerTest` (exists; direct instantiation): `startSession` returns `credits.remaining == total`; `sendTurn` returns `creditsRemaining`; transcribe refunds on fallback.
- `RazorpayWebhookControllerTest`: `credits:` receipt routes to `confirmCredited`.
- `CreatorCreditOrderServiceTest`: amount mismatch rejects; replay on same idempotency key returns the same order; already CREDITED is a no-op.
- Migration validate: one Docker MySQL boot with `ddl-auto=validate` (blocked locally; run on the VPS staging DB or CI). Compare entity columns to DDL by hand first (memory: Mockito tests skip schema validation). <!-- PRIYA 2026-09-20 --> The hand-diff now covers the two new A4 columns as well, and the migration prefix must be re-checked: the highest on disk in this worktree is **`V20260919100000__platform_stats_source_and_follower_provenance.sql`** (141 files), so §2's "`V20260912*`" is already in the past — use `V20260920*` or later and re-check on build day.

<!-- PRIYA 2026-09-20 --> **Tests the amendments add. Each must go red when its piece is removed — a test that greens its own fix proves nothing.**

**A1, tenths.** These are the tests that catch the whole class of unit bug:
- `CreatorCreditServiceTest`: debiting 25 from 400 leaves **375**, not 15 and not 397.5. (The old spec's plain-English version, "debiting 2.5 from 40.0 leaves exactly 37.5", is now that assertion.)
- A chat debit writes `delta = -10`; a search debit `-25`; the signup grant `+300`.
- `CreatorCreditDtosTest`: `toDisplay(375)` serializes as `37.5` and `toDisplay(400)` as `40.0` — **assert the JSON string**, not the `BigDecimal`, so a future `ObjectMapper` setting that drops the scale fails here (§6).
- `toTenths` rejects two decimal places with a 400 rather than rounding (§9).
- A seed-value test asserting the three packs grant **500 / 1000 / 3000** (§2.3). Nothing else would catch a pack that sells 50 credits and grants 5.
- A grep-style or architecture assertion that `/ 10` and `* 10` appear on no credit value outside `CreatorCreditDtos` (§0 rule 11).
- `dailyActionCap` is still 500 and `freeWeeklySearches` still 2 — the two settings a blanket tenths pass would have multiplied (§3.1).

**A2, brief.** In `CreatorBriefServiceTest` (remember all four hand-constructions, §4.3):
- A successful paste charges 30 tenths once, keyed `"brief:" + briefId`.
- A paste whose `briefAiClient.extract` returns no extraction charges 30 and refunds 30, net zero, with `BRIEF_DEBIT` and `BRIEF_REFUND` rows.
- A paste whose `briefAiClient.extract` **throws** refunds and rethrows — the leg that does not exist without the try/catch.
- **`get(...)` on a stale-NEW brief re-analyses and charges NOTHING.** This is the double-charge guard; it goes red the moment the charge is moved into `analyse`.
- `ensurePlatformBrief` charges nothing.

**A3/A4, search.**
- The free path: `tryClaimFreeSearch` returns 1, no debit, one `FREE_SEARCH` row with `delta = 0` and a non-empty `reference_id`.
- The paid path: the third search in a week debits exactly 25 tenths.
- **A creator whose `free_search_week_start` is `NULL` gets a free search.** This is the NULL-comparison guard from §2.6; write it against a real MySQL row, not a mock, because the bug is in the SQL.
- The week rolls: a row stamped last Monday with `free_searches_used = 2` yields a free search today.
- The refund: a throwing provider refunds 25; a `!ok()` provider refunds 25; a **free** search that fails refunds nothing and does not double-write.
- `ensureInitialized` runs before the claim — a creator with no row gets a FREE first search, not a billed one.
- The rate-limit bucket test (mirroring `AuthRateLimitFilterBriefPasteBucketTest`) proves `/creator/meera/search` is USER-keyed on its own limit and not on `sensitive`.
- `CreatorSendGateTest`'s five `new MeeraCreatorFeatureProperties(...)` sites compile and the search flag defaults false.

**A5, Python.** In `influora-ai`: `estimate_cost_usd` on a usage dict carrying a web-search request count returns the token cost **plus** the per-request fee, and the same dict without the count returns the token cost alone. Run it in a **clean venv built from `requirements.txt`** — local pytest has been running a different `anthropic` than CI installs (memory; and PLAN.md step 0.2).

---

## 12. Build order

1. K1 migrations + entities + repositories; compile; hand-diff columns vs DDL.
2. `CreatorCreditProperties` + yml + `CreatorCreditService` + tests (K2, K9).
3. `MeeraSessionService` changes + constructor sites + `TurnResult` field + tests (K3 chat, K4 service half).
4. `MeeraInternalController` release change (K4).
5. `CreatorMeeraController` read routes, session/turn wiring, voice charge (K5, K3 voice).
6. `CreatorCreditOrderService` + controller + webhook branch + tests (K6).
7. `CreatorCreditResetJob` (K7). Events + listener (§3.5).
8. Admin grant (K8).
9. Frontend (K10), behind `VITE_CREATOR_CREDITS_ENABLED`.
10. <!-- PRIYA 2026-09-20 --> **A2 — brief charge (§4.3).** No longer blocked on Phase B1: `CreatorBriefService.paste` shipped in B0 and this step is buildable now. It stays **after** step 3 (it needs `CreatorCreditService`) and still waits on O2, which A2 does not settle. Remember the four test constructor sites.
11. <!-- PRIYA 2026-09-20 --> **A4 — the weekly counter columns and `tryClaimFreeSearch`** (§2.1, §2.6). Part of step 1's migration, but the repository method and its MySQL-backed NULL test land here.
12. <!-- PRIYA 2026-09-20 --> **A3 — search (§4A):** the `MeeraCreatorFeatureProperties` flag (five test sites), the rate-limit bucket (three insertions), then the route. Behind `MEERA_CREATOR_SEARCH_ENABLED=false`.
13. <!-- PRIYA 2026-09-20 --> **A5 — `influora-ai/app/costs/pricing.py`** (R4, §4A.5). Independent of every Spring step; can run in parallel. Needs PLAN.md step 0.3's real call first, for the usage field name.

<!-- PRIYA 2026-09-20 --> **Two sub-steps are DELETED, not reordered.** Step 3 no longer changes `TurnResult` or the writeback's `credits_charged`, and step 5 no longer wires credits into the session/turn responses — §6 and §4.1 rule that a creator gets no credit number through a shared brand `int`. If a diff on this branch adds an `Integer creditsRemaining` to `TurnResult`, it is building the superseded spec.

Ship with both flags off. Turn on for staff creators first; flip `CREATOR_CREDITS_ENABLED=true` only after Swapnil rules on O1–O4.

---

## 13. Expert questions the engineer will ask (answered)

**Q1. Why not reuse `brand_ai_credits` with a nullable owner?** Its PK is `workspace_id` with an FK to `workspaces`; a creator has no workspace row. `MeeraSessionService` passes the creator's user id where a workspace id is expected, and `ensureInitialized` would try to insert an FK-violating row. A separate table keyed on `users.id` is the only shape that compiles against the existing call path without touching brand code.

**Q2. Why does the release path need the user type?** `AICreditService.release` is keyed on the same `(turnId, ownerId)` and its guard 1 returns before touching the credit row, so calling it with a creator id is *safe* but wrong: it writes a `meera.turn_released` idempotency row and never refunds. The route already resolves an `OnBehalfContext` with `userType` (L78) and throws it away (L358). Keeping it is a one-line change.

**Q3. What if Python refunds a voice charge?** It cannot: Python only ever posts `turnId` = the chat `messageId`. Voice and brief charges use keys `voice:<ulid>` / `brief:<id>` that never appear on the wire to influora-ai, and are refunded by the Java code that made them.

<!-- PRIYA: the last sentence is WRONG ON BOTH HALVES, and it is the sentence the whole
     charge-safety argument rests on.
     (a) "inside the same transaction" — `IdempotencyReservationOps` is a separate bean whose
         three methods are `@Transactional(REQUIRES_NEW)`; its class javadoc (L13-58) exists
         precisely to state that the reservation commits INDEPENDENTLY of the caller's
         transaction. §13 Q5 of this very spec says so correctly. A caller rollback does NOT
         release the reservation.
     (b) There is no outer transaction to roll back anyway. `doSendTurn` carries `@Transactional`
         at MeeraSessionService L308, but it is invoked from `sendTurn` (L280) through
         `idempotencyService.executeOnce(..., () -> doSendTurn(...))` — a lambda that calls the
         method on `this`, i.e. a SELF-INVOCATION that never crosses the Spring proxy. The
         annotation is inert. `sendTurn` itself is deliberately not `@Transactional` (its javadoc
         L274-278 says so). This is the exact trap `IdempotencyReservationOps` was extracted to
         escape, still present one layer up.
     WHAT IS ACTUALLY TRUE: because `charge` is a real proxy call from `MeeraSessionService` into
     the injected `CreatorCreditService` bean, `charge`'s own `@Transactional` DOES take effect
     and commits before `doSendTurn` continues. So the failure mode is a DANGLING CHARGE (creator
     debited, later step fails, no reply) — the safe direction, and the one this codebase's
     invariant asks for. The unsafe direction is the release path, which is why guard 1 must key
     on the ledger row and not the idempotency marker (see the §3.3 `release` comment).
     Rewrite Q4's last sentence accordingly rather than leaving a false reassurance in the spec. -->
**Q4. Race: two chat turns at balance 1.** `tryDebit`'s `WHERE monthlyRemaining >= :m AND purchasedBalance >= :p` is the arbiter, same as `tryDecrement`. The loser re-reads (after a `clearAutomatically` UPDATE — see §2.6), recomputes, and gets a 402. The reservation is `REQUIRES_NEW` and commits independently; `doSendTurn`'s `@Transactional` is a self-invocation and inert. A failure after `charge` therefore leaves a charge with no reply (dangling, the safe direction), never a reply with no charge — and `release` must key on the debit ledger row, not the reservation, so the reverse can never mint credits. <!-- PRIYA 2026-09-20 --> A1: read "balance 1" as **balance 10 tenths**, i.e. one credit — enough for one chat turn and not enough for a 25-tenth search. The race and its arbiter are unchanged; only the numbers are.

**Q5. Does `executeOnce` inside `charge` survive the outer transaction?** `IdempotencyReservationOps` runs `REQUIRES_NEW` (facts §7), so the reservation commits even if the outer transaction later rolls back; that is the documented behaviour for `AICreditService.tryConsumeForTurn` too and the reaper job cleans stale `IN_PROGRESS`. Order inside `charge`: debit → ledger → `executeOnce` last, so a failed debit never leaves a "charged" marker.

**Q6. Where does the USD cap fit now?** Unchanged. Spring's 402 fires first; a creator with credits can still hit Python's 429 `CREATOR_MONTHLY_CAP_REACHED` if their real spend passes `AI_CREATOR_MONTHLY_CAP_USD`. Phase B §14.4.d raises that to 2.00. A creator who buys 300 credits and spends them in one month costs ≈ ₹168 at Rohan's blended rate, under $2.00 at today's rate, so the cap should not bite a paying user. Watch the `ai_spend` log for `creator_month_usd` near cap.

**Q7. Is the 402 code shared with brands a problem?** No. The FE branches on role before it renders the card, and the brand `CreditPaywall` is only mounted in `MeeraChatPanel`. Sharing the code keeps `ApiError` handling uniform.

**Q8. Why is the signup grant in `purchased_balance`?** Because it must survive the monthly reset (Tejas: "never expires, use anytime") and the monthly bucket is overwritten on the 1st. The ledger reason still says `SIGNUP_GRANT`, so reporting can separate it.

**Q9. GST on packs.** Prices are GST-inclusive as displayed (Tejas §3). v1 stores the inclusive paise and the audit event; no invoice PDF and no `Invoice` row (that entity is subscription-shaped: `subscription_id NOT NULL`). Open decision O3 covers issuing a B2C tax invoice via a new series `INF/CRD/<FY>/<seq>`; the number-series machinery (`InvoiceNumberSeriesType`) exists and would take a new enum value.

**Q10. What about a refund of money?** Out of scope. A Razorpay refund would need to debit `purchased_balance` (possibly below zero if credits were spent). Admin path for v1: `adminGrant` with a negative number is rejected; handle money refunds manually and record them in the ledger later via a follow-up `ADMIN_ADJUSTMENT` reason.

**Q11. Reset date shown to the creator.** `resets_on` is the first of next UTC month, same boundary `CreatorCreditResetJob` uses. Python's cap message says "1st of next month" in prose only (Phase B §14.4.c); both now agree on UTC month.

**Q12. Where is the kill switch checked?** Every entry point: `charge` (no-op), `initiate` (404), reset job (skip), `credits` status (`DISABLED`). Migrations apply regardless. Turning the flag on later needs no data migration because `ensureInitialized` is lazy.

---

## 14. Gate: the zero-context tester's ten questions

1. Where and when is a creator chat turn charged, and what happens if the balance is 0? (§4.1 step 2: `doSendTurn`, before any row; 402 `CREDITS_EXHAUSTED`.)
2. What does a new creator have before their first session, and after? (Nothing in the DB; after `POST /sessions`: 40 monthly + 30 purchased, two ledger rows.)
3. A turn fails in influora-ai. How does the creator get the credit back? (Python posts `/turns/release`; controller passes `ctx.userType()`; `CreatorCreditService.release` reads the digest and refunds per bucket.)
4. A turn streams fully and then Python calls release anyway. (Guard 2 on `PERSIST_WRITEBACK_SCOPE` refuses.)
5. What is the monthly reset and can it run twice? (Job on the 1st 02:10 UTC; ledger unique key on `(user, MONTHLY_RESET, MONTHLY, cycle date)` makes the second run a logged no-op.)
6. How does a pack purchase become credits? (Order → Razorpay order with receipt `credits:<id>` → webhook `order.paid` → `confirmCredited` amount check → `creditPack` → ledger `PACK_PURCHASE`.)
7. Webhook arrives twice. (Status `CREDITED` short-circuits; ledger unique key backs it.)
8. Can a creator raise their own balance? (No route; admin grant is under `/admin/**` `hasRole("ADMIN")` and audited.)
9. What is free when credits are gone? (Every non-Meera surface; reads of credits; Phase C/D features. The card lists them.)
10. Flag off in production today: what changes? (Nothing user-visible; migrations applied; every charge returns NOT_CHARGED; status says DISABLED; FE hides.)

---

## 15. Open decisions (build with the default, flag to Swapnil)

| # | Question | Default built |
|---|---|---|
| O1 | 30 one-time + 40 monthly (Tejas) vs 20 total (Swapnil's first idea) | 30 + 40, both config values <!-- PRIYA 2026-09-20 --> — **stored as `signup-grant: 300` and `monthly-allotment: 400`** (A1, §3.1). The product decision is unchanged; only the unit is. |
| O2 | Brief paste with 0 credits: 402, or free deterministic read with `degraded_reason = "credits"` | 402 |
| O3 | Tax invoice for packs (new `INF/CRD` series, PDF) | Not in v1; audit row only |
| O4 | Pricing-page creator bullets | Built behind FE flag; needs Tejas/CTO sign-off |
| O5 | Exhausted notification cadence (event may repeat per 402) | In-app only, may repeat; FE card is the primary surface |
| O6 | Daily action cap value for creators | 500, same as brand |
| O7 | Earning paths (referral, first deal, profile, Meta, streak) | Not v1; ledger reasons can be added without schema change |
| O8 | <!-- PRIYA 2026-09-20 --> Does a `cap` degradation refund the brief charge, or only a provider outage? (§4.3) | Refund both. Swapnil should see that refunding `cap` gives unlimited free deterministic reads once the USD cap is spent |
| O9 | <!-- PRIYA 2026-09-20 --> Does everyone get the 2 free weekly searches, or only creators on free credits? (PLAN.md D2) | Everyone — which is why the D1 eligibility predicate is deliberately NOT built (§4A.3) |
| O10 | <!-- PRIYA 2026-09-20 --> Week boundary Monday 00:00 **IST**, in a system whose every other boundary is UTC (PLAN.md D3) | IST, as one named constant (§4A.3). One line to switch to UTC if he prefers one boundary |
| O11 | <!-- PRIYA 2026-09-20 --> `ensurePlatformBrief` (§4.3) is an uncharged AI read | Leave uncharged in v1; the USD cap is its only bound |
| O12 | <!-- PRIYA 2026-09-20 --> A failed FREE search does not return its weekly slot (§4A.2) | Do not return it in v1; say so in the copy |

---

## 16. Priya review

Reviewed 2026-09-05 against HEAD `eac5e58`, branch `feat/meera-creator-phase-e`, read-only (no Docker, no MySQL, app never booted). Every file and symbol cited in §§0–15 was opened. 69 claims checked, 18 wrong or incomplete. Nothing below is inferred from a name; each item names the file and line I read it at.

### 16.1 Corrections applied

Each is also written inline as a `<!-- PRIYA: … -->` comment above the sentence it corrects.

1. **`V55__seed_plans.sql` does not exist** (§2.3). The file is `influora-api/src/main/resources/db/migration/V55__seed_billing_plans.sql`. The DB-seeded-catalogue precedent itself is sound. *Fixed in place.*

2. **`@ConfigurationProperties` registration was left as "copy that mechanism"** (§3.1) — the one step here that crashes boot rather than failing a test. `RazorpayProperties` (`config/RazorpayProperties.java` L13) is a bare `@ConfigurationProperties` with no stereotype; there is no `@ConfigurationPropertiesScan` in the repo; the only thing that makes it constructible is its entry in `InfluoraApiApplication` L36's `@EnableConfigurationProperties({…})` list (RazorpayProperties at L38). That file's L73–95 comment block is the post-mortem of eleven classes that were unregistered, and `MeeraCreatorFeatureProperties` L17–24 chose `@Value`/`@Component` specifically to avoid it. *Now names the file and line, and names the gate:* `test/java/com/influora/config/ConfigurationPropertiesRegistrationTest.everyConfigurationPropertiesClassIsRegistered` — classpath-scan, no Docker, must be green.

3. **`tryDebit`'s "re-read and retry" is dead code, and the ledger's after-balances would be wrong** (§2.6, §3.3). `BrandAiCreditRepository` annotates all three JPQL updates `@Modifying @Transactional` with no `clearAutomatically`/`flushAutomatically`, and the spec copies that shape. `ensureInitialized` leaves the entity managed; a bulk UPDATE bypasses the persistence context; the "fresh read" returns the same stale instance. The retry recomputes the identical split and 402s. More seriously, `monthly_after`/`purchased_after` sourced from that stale instance make every ledger row's after-balance wrong — the money record diverging from the balance table. *Fixed:* `@Modifying(clearAutomatically = true, flushAutomatically = true)` on all four writers, and re-read after the UPDATE.

4. **`release` guard 1 trusts the wrong artefact, and the mint lands in the money-backed bucket** (§3.3). `IdempotencyReservationOps` is `REQUIRES_NEW` (class javadoc L54–58), so a COMPLETED `meera.creator_charged` marker commits independently of the debit it attests. Guard 1 as specified (`isCompleted(CHARGE_SCOPE)`, mirroring `AICreditService.doRelease` L244–250) would refund a charge that rolled back. `refund`'s monthly leg is clamped to the allotment but its purchased leg is unclamped (§2.6), so the refund mints purchased credits — the ones creators paid cash for. Brand survives the same shape only because its refund is clamped and its credits are not sold. *Fixed:* guard 1 now requires the matching `TURN_DEBIT`/`VOICE_DEBIT`/`BRIEF_DEBIT` ledger row (written inside `charge`'s transaction, so it cannot exist without the debit) and refunds exactly the (bucket, delta) pairs it records. The idempotency marker stays as the double-release guard only.

5. **§13 Q4's last sentence is wrong on both halves** — and it is the sentence the charge-safety argument rests on. "The idempotency reservation is taken after the debit inside the same transaction, so a rollback releases both": (a) the reservation is `REQUIRES_NEW` and never rolls back with the caller — §13 Q5 of this same spec states that correctly, so the document contradicts itself; (b) there is no outer transaction at all — `doSendTurn`'s `@Transactional` (`MeeraSessionService` L308) is reached only via `sendTurn` L280's `executeOnce(..., () -> doSendTurn(...))`, a lambda calling the method on `this`, so the annotation never crosses the proxy and is inert (`sendTurn` is deliberately not transactional, javadoc L274–278). This is the exact self-invocation trap `IdempotencyReservationOps` was extracted to escape, still live one layer up. *Fixed:* Q4 rewritten to state what is actually true — `charge` is a real proxy call and commits on its own, so the failure mode is a dangling charge (safe direction), and the unsafe direction is closed by correction 4.

6. **`charge` never catches `AlreadyCompletedException`/`AlreadyInProgressException`** (§3.2–3.3). `executeOnce` throws both unchecked (`IdempotencyService` L146–152). The `release` row correctly copies `AICreditService.release` L235–241's catch; `charge` has none. Chat and voice keys are freshly minted so it never fires, but §4.3's brief key `"brief:" + brief.getId()` is stable — a retried paste 500s instead of replaying. *Fixed:* wrap it, and on AlreadyCompleted return the split from `findCompletedResultDigest`.

7. **The daily-action refund over-credits** (§3.3). `charge` bumps `dailyActionsUsed` by one per action, mirroring `AICreditService.tryConsume` L141 — the cap counts actions, per R7. `release` as written refunds `m + p`, i.e. the credit cost. Brand gets away with the same line only because `TURN_CREDIT_COST` is 1 (`MeeraSessionService` L80). A voice refund would hand back 2 daily actions, a brief refund 3. *Fixed:* `refundDailyActions(id, 1, todayUtc)`.

8. **§4.2 miscounts the free returns and would dangle a charge.** `CreatorMeeraController.transcribe` has **three** returns before `voiceAiClient.transcribe(...)`, not two: null/empty audio L287, oversize L291, and the `catch (IOException)` on `audio.getBytes()` L298. Placing the charge "after the size checks" as the prose says puts the IOException return after it with no refund. *Fixed:* charge after `audioBytes` is assigned (L299), leaving exactly one post-charge fallback return (L316).

9. **§4.2 leaves an unguarded throw.** `voiceAiClient.transcribe` (`MeeraVoiceAiClient` L307) can throw; `transcribe` is a controller method with no transaction, so `charge` has already committed. A Sarvam outage would eat 2 credits per attempt. *Fixed:* try/catch (or try/finally on a `charged` flag) releasing on any throw.

10. **§4.2 silently breaks this route's stated contract.** `transcribe`'s javadoc (L273) fixes the contract as "real transcript JSON, or a silent `{"fallback": true}` 200 — never an error status", and the FE voice hook is written to it. Letting `charge`'s 402 propagate changes that for a client that does not error-handle it. *Fixed:* default to returning `{"fallback": true, "reason": "credits"}` 200 and letting the chat turn's own 402 be the paywall; the alternative (accept the 402, ship the FE hook change in the same commit) is written down as the explicit second option. The cross-reference "(§8.3)" also does not resolve — this document has no §8.3.

11. **Adding `CreatorCreditService` to `CreatorMeeraController` breaks the build.** `test/java/com/influora/web/CreatorMeeraControllerTest.java` L70–76 constructs the controller by hand with six positional args. §11 mentions the test but never says to update the constructor call. *Fixed.* (`RazorpayWebhookControllerTest` L77–87 constructs by hand too — that one §7.2 *does* call out correctly.)

12. **§9 never adds the dependency it calls.** `AdminCreatorAgentController`'s constructor (L33–37) takes only `(CreatorAgentBaselineService, CreatorAgentPreferencesService)`; the snippet calls `creatorCreditAdminService`, which is not a field. *Fixed:* add the field and constructor parameter. (`principal.getUserId()` L24, `CreatorProfile.getUserId()` L232 and `findById` all check out.)

13. **`Map.of` NPEs on a null value** (§7.1). `event.paymentId()` is nullable — `dispatchFundingEventIfResolvable` (L187–196) guards only `entityId()`. An `order.paid` without a payment id would NPE *after* `creditPack` added the credits, losing the audit row and 500-ing the webhook into a retry against an already-CREDITED order. *Fixed:* build the detail map with `HashMap`, not `Map.of`.

14. **`OrderResponse.key_id` is redundant; dropped** (§7.3). The spec hedged "reuse that source instead if it exists as a route" — it does. `getRazorpayKeyId()` (`src/lib/razorpay.ts` L116–131) calls `api.config.razorpay()` (`src/lib/api.ts` L3462) = `GET /config/razorpay`, and caches it; `PublicConfigController` L63 calls that route "the Razorpay checkout launcher's only source for the key". `OpenCheckoutParams` (razorpay.ts L133–146) has no key field — `openRazorpayCheckout` L157 resolves it itself. *Fixed:* `key_id` removed from the response.

15. **`getCredits(role)` cannot keep one return type** (§10.1). `getCredits` is declared `Promise<MeeraCreditStatus>` (`src/lib/meera-api.ts` L572) and `MeeraCreditStatus` (L99–106) has no `enabled`/`total`/`purchased_balance` and a `state` union of `'FREE'|'UNLIMITED'|'EXHAUSTED'` — none of the creator states. Adding a role parameter widens the return to a union and forces every brand call site to narrow; and the `!isApiLive()` mock branch (L573–582) would serve brand numbers to creator mock mode. *Fixed:* a separate `getCreatorCredits()` with its own mock; `getCredits` untouched.

16. **The `kind` message discriminator does not exist** (§10.3, §12). `MeeraCopilotChat` messages are `{ id, role, text }` (pushes at L271–272 and L312). "Phase B §14.4.c already requires a message variant" cites a spec, not code, and Phase B is unbuilt. *Fixed:* §10.3 now states the choice and §12 step 9 must record which — prerequisite on B, or introduce it here.

17. **FE flag plumbing points at a file that does not exist and omits the only step that matters** (§10.6). There is no `.env.example` at the repo root (`.env.local`, `.env.local.example`, `.env.production`). And `Dockerfile` L54–63 — `ARG VITE_PAYMENTS_IN_ENABLED` / `ARG VITE_PAYOUTS_ENABLED` promoted to `ENV` — is the only path by which a `VITE_*` value reaches the built bundle. `src/lib/api.ts` L102–107 (the F-0390 post-mortem) states the rule outright: the published image's value comes from the workflow build-arg, not `.env.production`, because Vite's `loadEnv` merges `process.env` over the .env files. As written, `VITE_CREATOR_CREDITS_ENABLED` binds to nothing, reads `undefined`, and can never be flipped on — F-0390 repeating verbatim. *Fixed:* Dockerfile ARG+ENV, `publish-images.yml` build-args (alongside L227–228), `.env.production`, `.env.local.example`.

18. **Line-number drift, corrected in place** (no design impact, but the spec's own rule 1 requires it): `doPersistAssistantWriteback`'s `creditsCharged = 0` is L576 not L575 (§4.1.3); the sole `new TurnResult(` site is L402 not L403 (§4.1.5); `dispatchFundingEvent` is L170–179, L158–168 is its javadoc (§7.2); `WalletTopUpService.confirmCredited`'s signature is L182, not L176 (§7.1); `BrandAiCredit`'s builder defaults are L173–190 not L177–188, and use system-zone `LocalDate.now()` which must **not** be copied given §3.3's UTC month boundary (§2.5); `CREATOR_INCLUDED` is L73–79 not L72–78 (§10.6); `recordMoneyEvent` is L110 (§7.1). Also added to §2.5: `@Column(length = n)` is missing from the entity bullets for roughly 20 String fields — under `ddl-auto=validate` an unannotated String defaults to VARCHAR(255) and fails against every narrower column; the full list is now enumerated.

**Claims I checked and confirmed correct** (the load-bearing ones, so the engineer does not re-verify): `MeeraSessionService` constructor L102, exactly ten args in the stated order with `CreatorAgentConversationService` last, so the new one is genuinely the eleventh; `MeeraSessionServiceTest` L81 is the only hand-construction site in the tree; `PERSIST_WRITEBACK_SCOPE` package-visible L89, reachable from `com.influora.service.meera`; `doSendTurn` L309 with `isCreatorTurn` L332 and the brand charge L342, `messageId` minted L324 before it; the creator-`workspaceId`-is-a-user-id comment L326–331; `releaseTurnCredit` L635 two-arg, public, not transactional; `TurnResult` L683 with six components; `UserType {BRAND, CREATOR, ADMIN}`; `OnBehalfContext(userId, workspaceId, userType, conversationId)` L78 and `resolveForWorkspace` L86; `MeeraInternalController` release L354–360 discarding the resolver result while the writeback above it (L325, L337) uses `ctx.userType()`; `CreditsSummary` L23 / `SessionStartResponse` L26 / `SendTurnResponse` L51 shapes and the `NON_NULL` reasoning (keeping `null` when disabled is right; the `int creditsRemaining` slot genuinely cannot be omitted, and the spec knows it); `startSession` L138–173 and `sendTurn` L176–208 with the `0` positional at L197; `requireFeatureEnabled`/`requireConsent` private, copy-not-call; `SecurityConfig` L227–228; `executeOnce` 5-arg L131 (`int[]` + `parts -> parts[0]+":"+parts[1]` compiles), `findCompletedResultDigest` L175, `isCompleted` L191, 128-char composite (the 76-char worst case is right); `IdempotencyReservationOps` REQUIRES_NEW; `AICreditService.release` L219–241 / `doRelease` L243–268 guard order, `tryConsume` L122–142, `CREDITS_EXHAUSTED`/402 L151–154, `AICreditServiceTest` L57; `AICreditResetJob` cron `0 0 2 1 * ?` L53, ShedLock L54, `AtomicBoolean`; `TaskSchedulerConfig.POOL_SIZE = 10` L36; `users.id VARCHAR(26)` with `utf8mb4_unicode_ci` (`V2__core_auth.sql` L4, L19) so the FK and collation are safe; highest migration `V20260903170000`, `V20260912*` free; `RazorpayClient.createOrder(BigDecimal, String, String)` L98; `RazorpayWebhookControllerTest` L77–87 hand-constructs with ten args; `WalletTopUpService.RECEIPT_PREFIX = "topup:"` and the amount-cross-check posture; `WalletController` `IDEMPOTENCY_KEY_REQUIRED` L100–105; `SubscriptionService` `RAZORPAY_MISCONFIGURED` 503 L292; `recordAdminAction` L95 and `OUTCOME_ALLOWED` L32; `audit_log.workspace_id` has a comment claiming an FK but **no** `FOREIGN KEY` clause (`V15__audit_log.sql` L11), so passing a user id is safe; `NotificationEvent` permits with `CreditsExhaustedEvent` L37, `CreditsResetEvent` L38, last entry `CreatorNotConnectedEvent` L49 with no trailing comma, and `workspaceId()` documented nullable L57; `NotificationEventContractTest` exclusions L91/L93; `AdminCreatorAgentController.setMonthlyCap` L52–59 with `creatorId` = profile id per its javadoc L45–51, bare-DTO admin convention, `AdminCreatorAgentDtos` snake_case `@JsonProperty`; `application.yml` `influora.meera` L194 / `creator-enabled` L199, and the proposed `creator-credits:` indentation is right; `MeeraVoiceAiClient.transcribe(String, byte[], String)` L307; `isMoneyActionBlocked` L119–122 needs no edit (any non-`withdraw` op is gated by `PAYMENTS_IN_ENABLED`); `MeeraTurnResponse.creditsRemaining: number` L67; `App.tsx` `/creator/how-it-works` L543 and `/creator/wallet` L570; `creator-copilot.tsx` `<h1>Co-pilot</h1>` L121; `brand-wallet.tsx` order-then-checkout at L617/L642; `payments-unavailable-notice.tsx` and `payments-gate.test.tsx` both exist; `CreditMeter.tsx` orphan; `InvoiceNumberSeriesType` has four values and no credit series; `Ulids.newUlid()` L9; `InfoBarrierTest`'s allowlist is untouched by this spec. The rulings R1 (owner key = `users.id`) and Q1 (a creator has no `workspaces` row, so `brand_ai_credits` cannot hold the balance) are both correct and correctly reasoned. The split arithmetic in `charge` (`p > purchasedBalance` ⟺ `cost > monthly + purchased`) is right. One thing the spec did *not* claim but should know: `payment.captured` reaches `dispatchFundingEvent` through `dispatchFundingEventIfResolvable` L195, so the single `credits:` branch covers both event types.

### 16.2 Remaining risks

**R1 — the schema is unvalidated and cannot be validated here.** No Docker, no MySQL. Twenty-odd `@Column(length)` values and four `CREATE TABLE`s are asserted by hand-diff only. This repo's own record (an unnamed `@Column` passing 77 tests and two reviews before a `ddl-auto=validate` boot crash) says hand-diffing is not proof. A single Docker MySQL boot with `ddl-auto=validate` on CI or the staging DB is the only thing that closes it, and §11's last bullet is right to say so.

**R2 — the dangling-charge window is real, not theoretical, and it is now the designed behaviour.** With `doSendTurn`'s transaction inert (correction 5), any failure between `charge` and the response leaves the creator debited with no reply. That is the correct direction and matches the brand precedent, but nothing sweeps it: there is no reconciliation job comparing COMPLETED `meera.creator_charged` markers against persisted assistant rows. For brands the cost of a stuck credit is nothing; for creators it is a credit they may have paid for. Ship v1 as-is, but a follow-up sweep (or an admin view of `TURN_DEBIT` rows with no writeback) belongs on the Phase E list.

**R3 — `CreatorCreditsExhaustedEvent` publication is unresolved in the spec's own text.** §3.5 talks itself out of `@TransactionalEventListener(AFTER_COMMIT)` (correctly — the brand handlers at `NotificationListener` L647/L660 are `@Async` + `AFTER_COMMIT` and would never fire on a rolled-back transaction), lands on "publish from the controller after catching the 402", then abandons the once-per-cycle question mid-paragraph ("No — keep it simple"). That paragraph should be rewritten before build; as it stands an engineer could reasonably ship either behaviour. O5 covers the cadence but not the publication site.

**R4 — the brief charge (§4.3) is specified against code that does not exist.** `CreatorBriefService.paste` is Phase B1. §12 step 10 sequences it correctly, but the 402-vs-free-deterministic ruling (O2) is a product decision that changes the charge site's shape, not just its copy. Do not build §4.3 until O2 is ruled.

**R5 — two open product dependencies gate the flag, not the build.** O1 (30 + 40 vs 20) and O4 (pricing-page bullets) both need Swapnil/Tejas. Everything is config, so this does not block the merge — only `CREATOR_CREDITS_ENABLED=true`.

**R6 — concurrent-session write collisions.** Another Claude Code session has actively edited this repo and clobbered in-progress edits on service classes before. `MeeraSessionService`, `CreatorMeeraController` and `RazorpayWebhookController` are all touched by this track. Re-verify each symbol survived after every build step; a green `mvn` exit is not evidence your edit is still there.

### 16.3 Verdict

**Buildable as corrected**, with conditions. The architecture is right: separate table keyed on `users.id` (the only shape that compiles against the existing call path), two buckets with the split in the idempotency digest, charge before anything dangles, server-derived receipt and ledger keys, kill switch at every entry point, no partial unique indexes, GET never writes, no `CreatorAgentPreferencesRepository` import, no "escrow" in user copy, money as `int` paise. Nine of the ten §14 tester questions answer cleanly against the corrected text.

It was **not** buildable as originally written — four items would have failed the build or the boot rather than a test (2, 3 in part, 11, 12), one would have shipped a permanently-dead feature flag (17), and two would have silently moved money the wrong way (4, 7). That is the usual shape: the parts that compile were right, and the parts that only fail at runtime were where the errors were.

Conditions, all blocking merge:

1. Corrections 1–18 applied, and the §3.5 event-publication paragraph rewritten (R3).
2. `ConfigurationPropertiesRegistrationTest` green — the gate for correction 2.
3. One `ddl-auto=validate` boot against a real MySQL 8, on CI or staging, before any deploy. Compile-green is not schema-green (R1).
4. A test that proves correction 4: charge, roll the charge back leaving the reservation, call release, assert the balance did **not** move. Write it so it FAILS against the uncorrected `isCompleted(CHARGE_SCOPE)` guard before you make it pass — a gate that greens its own fix proves nothing.
5. `CreatorMeeraControllerTest` L70–76 and `RazorpayWebhookControllerTest` L77–87 updated in the same commits as their controllers.
6. §4.3 (brief) held until O2 is ruled and Phase B1 exists. §10.6 (pricing copy) held until O4 is signed.
7. Ship with `CREATOR_CREDITS_ENABLED=false` and `VITE_CREATOR_CREDITS_ENABLED=false`; flip only after O1–O4.

— Priya, CTO

### 16.4 Amendment review — 2026-09-20 (Priya)

Amended against HEAD `00fe23a`, branch `feat/creator-credits-search`, worktree `influora-credits`, read-only: no Docker, no MySQL, no `mvn`, no `pytest`, no `npm`, nothing committed. `influora-api/`, `influora-ai/` and `src/` were **read only** — another agent is editing them in this worktree right now (`influora-ai/app/prompt/assembler.py` was modified today at 13:25 while I was reading).

**What I re-verified for this amendment**, file and line, so the engineer does not re-open them: `CreatorBriefController` L54/L55/L122/L123/L126/L127; `CreatorBriefService` L150–163 (13-arg constructor), L196–209 (`paste`, not `@Transactional`), L231–265 (`ensurePlatformBrief`, `analyse` at L264), L282 (`get`), L338–351 (`readOrReanalyse`, `analyse` at L350), L433–489 (`analyse`: provider call L436–437, source branch L442–457, risk L464–466, quote L467, `saveAnalysis` L469–475); `CreatorBrief` L52–53; `BriefDtos` L93–105, L108, L111; `CreatorMeeraController` L67/L68, L83–96 (6-arg constructor), L104–109, L138/L165/L172, L176/L197/L204, L212, L245, L277–322; `MeeraSessionService` L89, L125, L133, L386–397, L410, L644–663; `MeeraDtos` L23, L26–30, L51–59, L74–80; `MeeraController` L113; `AiMessage` L36 and `V12__ai_conversations_messages.sql` L21; `AICreditService` L93, L152, L167–185, L309; `MeeraCreatorFeatureProperties` L16–23, L25, L26, L31–36; `application.yml` L240, L245, L256; `SecurityConfig` L296–297; `AuthRateLimitFilter` L114–115, L144, L160, L337, L611–614, L630–632, L705–721, L740–758, L760–786; `CreatorSendGateTest` L285/288/294/297/548; `CreatorBriefServiceTest` L149/L641, `CreatorBriefServiceRealRiskRulesTest` L114, `GetBriefExecutorTest` L107; `V16__daily_action_cap.sql` L5–6; `V20260905190000__abuse_throttle_counters.sql` header; `CreatorConnectNudgeJob` L86; `User` L120/L146; `CreatorAgentPreferences` L58; `pricing.py` L50–53, L103–108, L191, L241, L244–298; `spend_tracker.py` L70–93, L402–429, L476–512, L535; `requirements.txt` L7–8; `gemini.py` (no `GoogleSearch`, no grounding); `pricing.tsx` L181–187; `meera-api.ts` L76, L108, L851; `MeeraCopilotChat.tsx` L87; `Dockerfile` L54–59; the migration directory (141 files, highest `V20260919100000`).

**What I did NOT verify, and nobody should treat as checked:** §10.2, §10.3 and §10.5 (their line numbers are from 2026-09-05 and three of the four §10 references I *did* re-check had drifted, one by 108 lines); §5's `MeeraInternalController` lines; §7.1's and §7.2's Razorpay lines; §3.5's `NotificationListener` lines; and every corrected line number in §16.1, which was verified in 2026-09-05's read and not again. The five amendments are re-verified; the untouched sections are not.

**Verdict on the amendment: buildable, in the order §12 now gives, with six things outstanding.**

1. **PLAN.md D2** (does everyone get the free weekly searches) — decides whether the D1 predicate exists at all. §4A.3 builds the default and explains why building the predicate early would be wrong.
2. **PLAN.md D3** (Monday IST vs UTC) — one constant, but it is the only non-UTC boundary in the system and should be a decision rather than an accident.
3. **PLAN.md step 0.1** (Gemini paid tier) — on the free tier, Google uses creators' searches to improve its products, which is a consent and a contract question, not a cost one.
4. **PLAN.md step 0.4** (does the v2 consent notice cover sending a question to Google or Anthropic) — if not, new wording ships in the SAME deploy as the route, per §4A.2 guard 4.
5. **PLAN.md step 0.3** (the real search call) — A5 cannot be finished without the actual `usage` field name; a fee term keyed on a guessed name silently adds $0, which is exactly how F-04's 5× under-bill hid.
6. **O2** (brief paste with no credits) — A2 re-anchors the charge point and does not settle the product question. §4.3 now records the one thing the old spec got wrong about it in the creator's favour: her raw text is committed at L206 before the 402, so a 402'd paste is saved, not lost.

Two of the Phase B conditions in §16.3 are **discharged** by this amendment, for a reason worth stating: condition 6's "§4.3 held until Phase B1 exists" is satisfied because B0 shipped it, and condition 5's `CreatorMeeraControllerTest` update is narrowed because §6 now adds nothing to that controller's constructor for the chat/session path. Every other condition in §16.3 stands unchanged, including the one that matters most — **compile-green is not schema-green**, and there are now two more columns and a NULL-sensitive `WHERE` clause that only a real MySQL boot can prove.

— Priya, CTO, 2026-09-20

