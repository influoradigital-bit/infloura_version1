# Meera for Creators — Credits build spec (CREDITS-SPEC)

**Task id:** T-MEERA-CREATOR-CREDITS (sub-track of Phase B)
**Written:** 2026-09-05 against HEAD `eac5e58`, branch `feat/meera-creator-phase-e`
**Facts:** `facts/credits-billing.md` (every symbol below was opened there; line numbers are from that read)
**Product inputs:** `finance/credits-gtm-plan.md` (Tejas), `finance/meera-credit-sheet.md` (Rohan)
**Audience:** the engineer who builds it. Every file and every step. Must compile against the current tree.

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
| K9 | Kill switch `CREATOR_CREDITS_ENABLED` (default false) + all costs as config | influora-api | v1 |
| K10 | FE: credits badge, exhausted state with pack CTA, low-balance nudge, `/creator/credits` page, pricing copy | src | v1 |
| K11 | Python: no change to the USD cap gate; it stays as the cost fuse under the credit gate | influora-ai | none |
| — | Creator subscriptions, manager seat, referral / streak / first-deal earning, credit expiry | — | **not v1** (Tejas D1, D2, §4; Phase E) |

**Design rulings this spec makes (defaults; flag to Swapnil, build regardless):**

- **R1 Owner key = `users.id`.** The whole creator Meera path keys on the creator's user id (`doSendTurn` comment L326–331; `releaseTurnCredit` receives `conversation.getWorkspaceId()` which *is* the user id; idempotency scopes partition on it). Using `creator_profiles.id` would force a profile lookup on the release route, which has no principal. `CreatorAgentPreferences` keys on profile id; the admin grant route bridges with `CreatorProfileRepository.findById(profileId).getUserId()`.
- **R2 Two buckets.** `monthly_remaining` resets to `monthly_allotment` on the 1st (not cumulative). `purchased_balance` never expires (Tejas D2 default) and holds packs, the signup grant, and admin grants. Debit takes from monthly first, then purchased. Refund returns each part to its own bucket, monthly clamped to the allotment. The split is recorded in the idempotency digest so release refunds exactly what was charged.
- **R3 Signup grant is lazy, not a signup hook.** Granted once, inside `ensureInitialized`, the first time a creator's row is created. That is triggered by `POST /creator/meera/sessions`. This also gives every existing creator the grant on first use, which is Tejas D3's default ("backfill") without a backfill job.
- **R4 Credits gate in Spring, cap stays in Python.** The 402 fires in `doSendTurn` before a stream token exists, so an exhausted creator never reaches influora-ai. The USD cap (`AI_CREATOR_MONTHLY_CAP_USD`, raise to 2.00 per Phase B §14.4.d) remains as the cost fuse for a creator who bought many packs. Both can be hit; the credits one is the product wall, the cap one is the safety wall.
- **R5 Voice charges once, at transcribe.** `POST /creator/meera/voice/transcribe` charges `voice-cost` (2). The following chat turn charges 1 as usual, so a voice turn costs 3 in total, matching Tejas ("additive"). `speak` is free. Refund when the transcribe returns `fallback`.
- **R6 Packs are one-time orders through `RazorpayClient.createOrder`, receipt prefix `credits:`.** No subscription, no wallet posting: credits are not money and never enter `wallet_transactions`. GST is computed for the invoice line only; v1 stores the GST-inclusive paise and issues no PDF (open decision O3).
- **R7 Daily hard cap 500 actions** mirrors the brand ledger as an abuse guard. Same error code `DAILY_ACTION_LIMIT_EXCEEDED`.

---

## 2. Data model (K1)

All four files go in `influora-api/src/main/resources/db/migration/`. Highest on disk today is `V20260903170000`. Phase B reserves `V20260910100000`–`V20260910100700`. This track uses `V20260912*`. Re-check on build day.

### 2.1 `V20260912100000__creator_ai_credits.sql`

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
  CONSTRAINT fk_creator_ai_credits_user FOREIGN KEY (creator_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Verify `users.id` is `VARCHAR(26)` in the base migration before writing the FK (the collation mismatch in `1792c37`'s V73/V74 was exactly this class of error).

### 2.2 `V20260912100100__creator_credit_ledger.sql`

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
('01K4CRPACK0000000000STARTR', 'STARTER',  'Starter',  50, 14900, 1, TRUE, NOW(6), NOW(6)),
('01K4CRPACK0000000000STANDR', 'STANDARD', 'Standard', 100, 24900, 2, TRUE, NOW(6), NOW(6)),
('01K4CRPACK0000000000POWERR', 'POWER',    'Power',    300, 64900, 3, TRUE, NOW(6), NOW(6));
```

<!-- PRIYA: WRONG FILENAME. There is no `V55__seed_plans.sql` on disk. The file is
     `influora-api/src/main/resources/db/migration/V55__seed_billing_plans.sql`. The precedent
     itself (DB-seeded catalogue, no yml plan config) is correct. -->
Catalogue lives in the DB by precedent (`plans` is seeded by `V55__seed_billing_plans.sql`; there is no yml plan config). Prices are Tejas §3 (₹149 / ₹249 / ₹649). The ids must be 26 chars of Crockford base32; generate real ULIDs at build time rather than the placeholders above.

### 2.4 `V20260912100300__creator_credit_orders.sql`

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
  TURN_DEBIT, TURN_REFUND, VOICE_DEBIT, VOICE_REFUND, BRIEF_DEBIT, BRIEF_REFUND
}
public enum CreatorCreditOrderStatus { PENDING, CREDITED }
```

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

---

## 3. `CreatorCreditService` (K2, K9)

Package **`com.influora.service.meera`** — same package as `AICreditService`, so `MeeraSessionService.PERSIST_WRITEBACK_SCOPE` (package-visible, L89) is reachable for the release guard.

### 3.1 Config: `com.influora.config.CreatorCreditProperties`

```java
@ConfigurationProperties(prefix = "influora.meera.creator-credits")
public class CreatorCreditProperties {
  private boolean enabled = false;
  private int signupGrant = 30;
  private int monthlyAllotment = 40;
  private int turnCost = 1;
  private int voiceCost = 2;
  private int briefCost = 3;
  private int dailyActionCap = 500;
  private int lowBalanceThreshold = 10;
  // getters/setters
}
```

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
      signup-grant: ${CREATOR_CREDITS_SIGNUP_GRANT:30}
      monthly-allotment: ${CREATOR_CREDITS_MONTHLY_ALLOTMENT:40}
      turn-cost: ${CREATOR_CREDITS_TURN_COST:1}
      voice-cost: ${CREATOR_CREDITS_VOICE_COST:2}
      brief-cost: ${CREATOR_CREDITS_BRIEF_COST:3}
      daily-action-cap: ${CREATOR_CREDITS_DAILY_ACTION_CAP:500}
      low-balance-threshold: ${CREATOR_CREDITS_LOW_BALANCE_THRESHOLD:10}
```

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
| `public record ChargeResult(int cost, int monthlyPart, int purchasedPart, int totalAfter)` with `static final ChargeResult NOT_CHARGED = new ChargeResult(0,0,0,-1)` | | `totalAfter == -1` means "credits disabled"; controllers map it to `-1` on the wire only where the field is `int` (`SendTurnResponse.creditsRemaining`), and FE treats negative as "hide". |

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

## 4. Charging the three actions (K3)

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
3. `doPersistAssistantWriteback` L576: replace `creditsCharged = 0` with `creditsCharged = creatorCreditService.chargedCost(workspaceId, turnId)`. Keep the brand branch.
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


### 4.3 Brief paste — Phase B1 `CreatorBriefService.paste` (not yet built)

Insert between §3.8 step 2 (brief row saved as NEW) and step 3 (AI call):

```java
creatorCreditService.charge(creatorUserId, creatorCreditService.briefCost(), "brief:" + brief.getId(), CreditLedgerReason.BRIEF_DEBIT);
```

After step 3, if `extraction_source == FALLBACK` or `degraded_reason != null` (Phase B §14.4.a), call `release(creatorUserId, "brief:" + brief.getId(), BRIEF_REFUND)`: the creator paid for a model read and got a regex read. Tejas D7 default ("technical failures only") is satisfied: fallback is a technical failure, a wrong answer is not refunded. `charge` throws 402 before the AI call, so an exhausted creator still gets… nothing. **Decision O2:** should paste degrade to the free deterministic path instead of 402? Default here: **402** (Tejas's table lists briefs as credit-drawing; the deterministic extractor is a fallback, not a free tier). Build the 402; the FE shows the pack CTA with "Rule-based read stays free" only if O2 flips.

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
    @JsonProperty("monthly_remaining") int monthlyRemaining,
    @JsonProperty("monthly_allotment") int monthlyAllotment,
    @JsonProperty("purchased_balance") int purchasedBalance,
    @JsonProperty("total") int total,
    @JsonProperty("cycle_start") LocalDate cycleStart,
    @JsonProperty("resets_on") LocalDate resetsOn,          // first day of next UTC month
    @JsonProperty("low_balance_threshold") int lowBalanceThreshold,
    @JsonProperty("state") String state) {}                  // DISABLED | OK | LOW | EXHAUSTED
public record CreditLedgerItem(String id, int delta, String bucket, String reason, @JsonProperty("reference_id") String referenceId, @JsonProperty("monthly_after") int monthlyAfter, @JsonProperty("purchased_after") int purchasedAfter, @JsonProperty("created_at") Instant createdAt) {}
```

Routes (all: copy of `requireFeatureEnabled()`; `requireCreatorProfile`; **no** consent needed for reading a balance):

| Route | Handler | Notes |
|---|---|---|
| `GET /creator/meera/credits` | `credits(principal)` → `ApiResponse<CreatorCreditStatusResponse>` | `getStatus` (no create). Empty → `monthly_remaining = monthlyAllotment`, `purchased_balance = signupGrant`, `total` = sum, `state = OK` (the grant is real the moment they start a session). `state`: `DISABLED` if `!enabled`; `EXHAUSTED` if total 0; `LOW` if total ≤ threshold; else `OK`. |
| `GET /creator/meera/credits/ledger?limit=50` | `ledger(principal, limit)` → `List<CreditLedgerItem>` | `limit` clamp 1..200. |

`startSession` L164–172: replace `(CreditsSummary) null` with `new CreditsSummary(total, false)` where `total` comes from `ensureInitialized(creatorUserId).total()` (this route is a POST, so the lazy grant here is legal) or `-1`… no: `CreditsSummary.remaining` is `int`; when credits are disabled pass `new CreditsSummary(-1, false)` and let FE hide on negative, or keep `null`. **Keep `null` when disabled** (the field is `NON_NULL`; the FE already handles absence).

`sendTurn` L196–207: the `0` positional becomes `result.creditsRemaining() == null ? -1 : result.creditsRemaining()`.

`SecurityConfig`: nothing. `/creator/**` → `hasRole("CREATOR")` L227 already covers the new routes. Rate limiting: no new bucket; reads are cheap.

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
| `GET /creator/credits/packs` | | `List<PackItem(code, name, credits, price_paise, price_display)>`; `price_display` = "₹249 (incl. GST)" formatted server-side so copy is one place. |
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

```java
@Scheduled(cron = "0 10 2 1 * ?", zone = "UTC")
@SchedulerLock(name = "CreatorCreditResetJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
public void resetAllCreatorCreditsForNewMonth()
```

`AtomicBoolean` re-entrancy guard; `creatorAiCreditRepository.findAllCreatorUserIds()` (only creators who have ever started a session — no `UserRepository` finder needed and no spurious rows, the lesson recorded in `AICreditResetJob`'s javadoc L78–82); `cycleStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)`; per-id try/catch; counters in the log line. Skip entirely when `!props.enabled`. Ten minutes after the brand job so the two never contend for the DB at the same minute. `TaskSchedulerConfig.POOL_SIZE` is 10 today (Phase D asks for 16); one more monthly job is fine.

---

## 9. Admin grant (K8) — `AdminCreatorAgentController`

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
- Add a **separate** `getCreatorCredits(): Promise<CreatorCreditStatus>` → `request<CreatorCreditStatus>('GET', '/creator/meera/credits')`, with its own `!isApiLive()` mock returning the creator shape. Leave `getCredits` (L572, brand-only) alone. Add a `CreatorCreditStatus` interface matching §6 (snake_case). **Do not** declare fields the Java record does not send (memory: FE type asserting a missing DTO field renders the empty state forever).
- `MeeraTurnResponse.creditsRemaining` (L67) is already `number`; treat `< 0` as unknown.

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

Next to `<h1>Co-pilot</h1>` (L121): `CreatorCreditsBadge` reading `meeraApi.getCredits('creator')` on mount and on `onCreditsChanged`. Hidden when `state === 'DISABLED'` or the request 404s (feature flag off). Copy: "{total} credits". `LOW` state shows Tejas's nudge banner above the chat ("You have {X} credits left. Top up now so Meera can keep helping." → `/creator/credits`), dismissible, suppression per Tejas §5: after dismissal do not show again until total ≤ 5, never more than once per session (sessionStorage key, wrapped in try/catch).

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
### 10.6 Pricing page — `src/pages/pricing.tsx` L73–79

Add two bullets to `CREATOR_INCLUDED` after the existing five: `'Meera assistant: 40 free credits every month, plus 30 to start'` and `'Extra credits from ₹149, no subscription'`. Matrix and FAQ unchanged. The file header's CTO ruling (no fee digits) is respected; a credit pack price is not a fee. **This needs Tejas/CTO sign-off before merge (O4)**; build it behind the same `VITE_CREATOR_CREDITS_ENABLED` read the badge uses.

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
- Migration validate: one Docker MySQL boot with `ddl-auto=validate` (blocked locally; run on the VPS staging DB or CI). Compare entity columns to DDL by hand first (memory: Mockito tests skip schema validation).

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
10. Brief charge (§4.3) lands inside Phase B1's `CreatorBriefService`, not before it exists.

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
**Q4. Race: two chat turns at balance 1.** `tryDebit`'s `WHERE monthlyRemaining >= :m AND purchasedBalance >= :p` is the arbiter, same as `tryDecrement`. The loser re-reads (after a `clearAutomatically` UPDATE — see §2.6), recomputes, and gets a 402. The reservation is `REQUIRES_NEW` and commits independently; `doSendTurn`'s `@Transactional` is a self-invocation and inert. A failure after `charge` therefore leaves a charge with no reply (dangling, the safe direction), never a reply with no charge — and `release` must key on the debit ledger row, not the reservation, so the reverse can never mint credits.

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
| O1 | 30 one-time + 40 monthly (Tejas) vs 20 total (Swapnil's first idea) | 30 + 40, both config values |
| O2 | Brief paste with 0 credits: 402, or free deterministic read with `degraded_reason = "credits"` | 402 |
| O3 | Tax invoice for packs (new `INF/CRD` series, PDF) | Not in v1; audit row only |
| O4 | Pricing-page creator bullets | Built behind FE flag; needs Tejas/CTO sign-off |
| O5 | Exhausted notification cadence (event may repeat per 402) | In-app only, may repeat; FE card is the primary surface |
| O6 | Daily action cap value for creators | 500, same as brand |
| O7 | Earning paths (referral, first deal, profile, Meta, streak) | Not v1; ledger reasons can be added without schema change |

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
