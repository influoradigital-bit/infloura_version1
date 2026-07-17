# 🗄️ DATA MODEL — Brand + Meera AI Backend (Flyway V8 →)

> **Author:** Vikram (Backend) · **Date:** 2026-07-05 · **Milestone:** M2 (money rails) + M2.5 (Meera)
> **Grounded in:** `BACKEND-ARCHITECTURE-DECISION.md`, `PRD-MEERA-AI-COFOUNDER.md` §5/§7, real schema in `influora-api/src/main/resources/db/migration/V1–V7`.
> **Companion:** `02-API-CONTRACT-BRAND.md` (endpoints), `03-SECURITY-SPEC.md` (Kabir — money controls).

---

## 0. NON-NEGOTIABLE CONVENTIONS (matching the real codebase)

Every rule below is lifted from the existing app, not invented. New tables MUST follow them or they won't join.

| Rule | Value | Source |
|---|---|---|
| Primary keys | **`VARCHAR(26)` ULID**, app-generated via `com.influora.common.Ulids.newUlid()` | `Ulids.java`, every V1–V7 table |
| **NOT** BIGINT AUTO_INCREMENT | The PRD §5/§7 DDL is WRONG (`BIGINT AUTO_INCREMENT`). Rewritten here to ULID. | `BACKEND-ARCHITECTURE-DECISION.md:95` |
| FKs to existing tables | `VARCHAR(26)` → `workspaces(id)`, `campaigns(id)`, `users(id)`, `collaborations(id)` | V2, V4, V6 |
| Engine / charset | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` | every migration |
| Enums | MySQL `ENUM(...)` in DDL ↔ `@Enumerated(EnumType.STRING)` in JPA | `Campaign.status` |
| JSON columns | `JSON` in DDL ↔ `@JdbcTypeCode(SqlTypes.JSON)` `String` field in JPA | `Campaign.platformsJson` |
| Timestamps | `TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP` (+ `ON UPDATE` for `updated_at`) ↔ `Instant` in JPA | `Campaign.createdAt` |
| Money | `DECIMAL(14,2)` for balances, `DECIMAL(12,2)` for line amounts, `currency VARCHAR(3) DEFAULT 'INR'` | `wallets`, `campaigns` |
| Idempotency | Every **money-mutating** table carries `idempotency_key VARCHAR(64)` with a **UNIQUE** index | Architecture ruling §Guardrail 1, sequencing §1 |
| Entity package | `com.influora.domain.entity` · enums in `com.influora.domain.enums` | existing tree |
| Repository package | `com.influora.repository`, `extends JpaRepository<E, String>` | `CampaignRepository` |

---

## 1. WHAT ALREADY EXISTS vs WHAT MEERA NEEDS

I audited V1–V7 before writing a line. Here is the truth on the ground:

| Table | Exists? | Migration | Notes |
|---|---|---|---|
| `users`, `workspaces`, `workspace_members` | ✅ | V2 | brand identity Meera hangs off |
| `wallets` (`balance`, `escrow_balance`) | ✅ | V2 | balance columns only — **no ledger, no holds** |
| `campaigns` | ✅ | V4 | Meera's `create_campaign` target |
| `creator_profiles`, `platform_stats`, `collaborations`, `saved_creators` | ✅ | V6 | matching pool for `show_creators` |
| `wallet_transactions` (double-entry ledger) | ❌ **MISSING** | **V8** | Meera `request_payment` depends on it |
| `escrow_holds` | ❌ **MISSING** | **V9** | "go live = fund escrow" (PRD §8) depends on it |
| `contracts` | ❌ **MISSING** | **V10** | milestone release references it |
| `payment_milestones` | ❌ **MISSING** | **V10** | milestone-driven escrow release |
| `brand_profiles` | ❌ **MISSING** | **V11** | website analyzer output |
| `ai_conversations` | ❌ **MISSING** | **V12** | Meera chat sessions |
| `ai_messages` | ❌ **MISSING** | **V12** | turn log + prompt-version audit |
| `campaign_intents` | ❌ **MISSING** | **V13** | conversation → campaign bridge |
| `brand_ai_credits` | ❌ **MISSING** | **V14** | credit model (PRD §7) |
| `meera_tool_calls` | ❌ **MISSING** | **V14** | idempotency ledger for internal executors |

**Dependency order (Vikram's sequencing, ruling §SEQUENCING):**
`V8 (ledger) → V9 (escrow) → V10 (contracts+milestones)` are **M2**, must land first — Meera's money tool-calls are dead without them. `V11–V13` (**M2.5**) have **no money dependency** and ship in parallel (website analyzer + read-only chat). `V14` credit-reset hook wires into the V9 escrow-funded event **after** M2 lands.

```
V8  wallet_transactions ──┐
V9  escrow_holds ─────────┼─ M2 (money rails — build first, idempotency baked in)
V10 contracts, payment_milestones ┘
V11 brand_profiles ───────┐
V12 ai_conversations, ai_messages ┼─ M2.5 (no money — parallel)
V13 campaign_intents ─────┘
V14 brand_ai_credits, meera_tool_calls ── M2.5 (wires into V9 escrow event)
```

---

## 2. MIGRATION V8 — `wallet_transactions` (double-entry ledger) · **M2**

**Purpose:** The single append-only ledger of every rupee movement. Double-entry: each logical money event writes paired rows (debit one wallet, credit another) sharing a `group_id`. `wallets.balance`/`escrow_balance` are derived/maintained from this; the ledger is the source of truth. Meera's `request_payment` never writes money directly — it triggers a Spring service that writes here inside `@Transactional`.

```sql
-- V8__wallet_transactions.sql
CREATE TABLE wallet_transactions (
  id                VARCHAR(26) PRIMARY KEY,                 -- ULID
  wallet_id         VARCHAR(26) NOT NULL,                    -- FK wallets(id)
  group_id          VARCHAR(26) NOT NULL,                    -- ties the two legs of one movement
  direction         ENUM('DEBIT','CREDIT') NOT NULL,
  type              ENUM('DEPOSIT','WITHDRAWAL','ESCROW_HOLD','ESCROW_RELEASE',
                    'ESCROW_REFUND','PLATFORM_FEE','PAYOUT','ADJUSTMENT') NOT NULL,
  amount            DECIMAL(14,2) NOT NULL,                  -- always positive; direction carries sign
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  balance_after     DECIMAL(14,2) NOT NULL,                  -- wallet balance snapshot post-apply (audit)
  status            ENUM('PENDING','COMPLETED','FAILED','REVERSED') NOT NULL DEFAULT 'COMPLETED',
  reference_type    ENUM('COLLABORATION','ESCROW_HOLD','MILESTONE','CAMPAIGN','DEPOSIT_ORDER','MANUAL') NULL,
  reference_id      VARCHAR(26) NULL,
  description       VARCHAR(300),
  idempotency_key   VARCHAR(64) NOT NULL,                    -- one key per logical event; UNIQUE
  gateway_ref       VARCHAR(120) NULL,                       -- razorpay order/payment id
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_wtx_idem (idempotency_key),
  INDEX idx_wtx_wallet (wallet_id),
  INDEX idx_wtx_group (group_id),
  INDEX idx_wtx_reference (reference_type, reference_id),
  CONSTRAINT fk_wtx_wallet FOREIGN KEY (wallet_id) REFERENCES wallets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- **Idempotency:** `uq_wtx_idem` is the launch-blocker guardrail. Every writer (deposit webhook, escrow hold, milestone release, Meera `request_payment`) supplies a caller-derived key; a duplicate insert throws → caught → treated as "already applied", returns the prior result. No double-charge.
- **JPA:** `WalletTransaction` (entity) + `WalletTransactionRepository extends JpaRepository<WalletTransaction, String>` with `Optional<WalletTransaction> findByIdempotencyKey(String key)` and `List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(...)`. Enums: `TxnDirection`, `WalletTransactionType`, `TransactionStatus`, `TxnReferenceType`.

---

## 3. MIGRATION V9 — `escrow_holds` · **M2**

**Purpose:** Locked funds per collaboration/milestone. Funding a hold = "go live" for Meera (PRD §8: *"go live = fund escrow"*). The **escrow-funded event on this table is the hook that resets brand AI credits** (see V14 / PRD §7). Moves money from `wallets.balance` → `wallets.escrow_balance`.

```sql
-- V9__escrow_holds.sql
CREATE TABLE escrow_holds (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) — brand paying
  collaboration_id  VARCHAR(26) NULL,                        -- FK collaborations(id) (null for campaign-level pool)
  campaign_id       VARCHAR(26) NULL,                        -- FK campaigns(id) — Meera funds a whole campaign pool
  milestone_id      VARCHAR(26) NULL,                        -- FK payment_milestones(id) (V10)
  amount            DECIMAL(14,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  status            ENUM('PENDING','FUNDED','RELEASED','REFUNDED','FROZEN') NOT NULL DEFAULT 'PENDING',
  hold_txn_id       VARCHAR(26) NULL,                        -- FK wallet_transactions(id) — the DEBIT leg
  release_txn_id    VARCHAR(26) NULL,                        -- FK wallet_transactions(id) — set on release
  idempotency_key   VARCHAR(64) NOT NULL,
  funded_at         TIMESTAMP NULL,
  released_at       TIMESTAMP NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_escrow_idem (idempotency_key),
  INDEX idx_escrow_workspace (workspace_id),
  INDEX idx_escrow_campaign (campaign_id),
  INDEX idx_escrow_status (status),
  CONSTRAINT fk_escrow_workspace  FOREIGN KEY (workspace_id)     REFERENCES workspaces(id),
  CONSTRAINT fk_escrow_collab      FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_escrow_campaign    FOREIGN KEY (campaign_id)      REFERENCES campaigns(id),
  CONSTRAINT fk_escrow_holdtxn     FOREIGN KEY (hold_txn_id)      REFERENCES wallet_transactions(id),
  CONSTRAINT fk_escrow_releasetxn  FOREIGN KEY (release_txn_id)   REFERENCES wallet_transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- **Depends on:** V8 (`wallet_transactions`). `milestone_id` FK is added by V10 (declared here as nullable column; the FK is created in V10 to respect ordering — see note in V10).
- **Credit hook:** on transition `PENDING → FUNDED`, `EscrowService` publishes `EscrowFundedEvent(workspaceId, campaignId, campaignEndDate)`; `AICreditService` listens and resets credits (V14). This is the **only** legal writer of `unlimited_until`.
- **JPA:** `EscrowHold` + `EscrowHoldRepository` with `findByIdempotencyKey`, `findByWorkspaceIdAndStatus`, `findByCampaignId`. Enum `EscrowStatus`.

---

## 4. MIGRATION V10 — `contracts` + `payment_milestones` · **M2**

**Purpose:** `contracts` = the signed agreement per collaboration (BACKEND-API-SPEC §10) with total amount + payment schedule. `payment_milestones` = individual scheduled releases; each drives one `escrow_holds` release. Meera's `confirm_launch` and milestone approvals resolve against these.

```sql
-- V10__contracts_and_milestones.sql
CREATE TABLE contracts (
  id                VARCHAR(26) PRIMARY KEY,
  collaboration_id  VARCHAR(26) NOT NULL,                    -- FK collaborations(id)
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) (denormalized for scoping)
  version           INT NOT NULL DEFAULT 1,
  status            ENUM('DRAFT','PENDING_SIGNATURES','ACTIVE','COMPLETED','CANCELLED') NOT NULL DEFAULT 'DRAFT',
  total_amount      DECIMAL(12,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  pdf_r2_key        VARCHAR(500) NULL,                       -- R2 object key (file bytes never in DB)
  terms             JSON NULL,                               -- exclusivity, usage rights, deliverable summary
  brand_signed_at   TIMESTAMP NULL,
  creator_signed_at TIMESTAMP NULL,
  effective_date    DATE NULL,
  expiration_date   DATE NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_contract_collab (collaboration_id),
  INDEX idx_contract_workspace (workspace_id),
  INDEX idx_contract_status (status),
  CONSTRAINT fk_contract_collab    FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_contract_workspace FOREIGN KEY (workspace_id)     REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_milestones (
  id                VARCHAR(26) PRIMARY KEY,
  contract_id       VARCHAR(26) NOT NULL,                    -- FK contracts(id)
  collaboration_id  VARCHAR(26) NOT NULL,                    -- FK collaborations(id) (denormalized)
  sequence_no       INT NOT NULL,                            -- 1,2,3 ordering
  description       VARCHAR(300),
  amount            DECIMAL(12,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  due_date          DATE NULL,
  status            ENUM('PENDING','FUNDED','RELEASED','REFUNDED','FROZEN') NOT NULL DEFAULT 'PENDING',
  escrow_hold_id    VARCHAR(26) NULL,                        -- FK escrow_holds(id)
  released_txn_id   VARCHAR(26) NULL,                        -- FK wallet_transactions(id)
  idempotency_key   VARCHAR(64) NULL,                        -- set when a release is executed
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_milestone_seq (contract_id, sequence_no),
  UNIQUE KEY uq_milestone_release_idem (idempotency_key),
  INDEX idx_milestone_contract (contract_id),
  INDEX idx_milestone_status (status),
  CONSTRAINT fk_milestone_contract FOREIGN KEY (contract_id)      REFERENCES contracts(id),
  CONSTRAINT fk_milestone_collab   FOREIGN KEY (collaboration_id) REFERENCES collaborations(id),
  CONSTRAINT fk_milestone_escrow   FOREIGN KEY (escrow_hold_id)   REFERENCES escrow_holds(id),
  CONSTRAINT fk_milestone_txn      FOREIGN KEY (released_txn_id)  REFERENCES wallet_transactions(id)
);

-- Back-fill the deferred FK from V9 now that payment_milestones exists.
ALTER TABLE escrow_holds
  ADD CONSTRAINT fk_escrow_milestone FOREIGN KEY (milestone_id) REFERENCES payment_milestones(id);
```

- **Idempotency:** `uq_milestone_release_idem` guards double-release. `payment_milestones.amount` is the **server-side source of truth** for any release — the AI service's proposed amount is never trusted (see `02-API-CONTRACT`, §internal executors).
- **JPA:** `Contract` + `ContractRepository`; `PaymentMilestone` + `PaymentMilestoneRepository` (`findByContractId`, `findByIdAndCollaborationId`, `findByIdempotencyKey`). Enums `ContractStatus`, `MilestoneStatus`.

---

## 5. MIGRATION V11 — `brand_profiles` · **M2.5** (no money)

**Purpose:** Output of the Python website analyzer (Playwright scrape + Gemini classify). One row per workspace. Feeds the **sanitized** brand-context Spring hands to Python each turn (allow-listed fields only — PRD §3, Guardrail 3). No PII.

```sql
-- V11__brand_profiles.sql
CREATE TABLE brand_profiles (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id)
  website_url       VARCHAR(500),
  analysis_status   ENUM('PENDING','ANALYZING','READY','FAILED') NOT NULL DEFAULT 'PENDING',
  scraped_at        TIMESTAMP NULL,
  product_catalog   JSON,                                    -- [{name, price, url, image_key}]
  brand_aesthetic   JSON,                                    -- {accent_color, target_demo}
  tone_profile      JSON,                                    -- {formality, energy, emoji_ok, cultural_context}
  niche_tags        JSON,                                    -- ['beauty','skincare']
  competitor_urls   JSON,
  analysis_error    VARCHAR(500) NULL,                       -- for the "paste a link" fallback (PRD §9)
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_brandprofile_workspace (workspace_id),
  INDEX idx_brandprofile_status (analysis_status),
  CONSTRAINT fk_brandprofile_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- **JPA:** `BrandProfile` (JSON fields as `@JdbcTypeCode(SqlTypes.JSON) String`, per `Campaign.platformsJson`) + `BrandProfileRepository extends JpaRepository<BrandProfile,String>` with `Optional<BrandProfile> findByWorkspaceId(String)`. Enum `AnalysisStatus`.

---

## 6. MIGRATION V12 — `ai_conversations` + `ai_messages` · **M2.5** (no money)

**Purpose:** Meera chat session + turn log. `ai_messages.metadata` stores `prompt_version`, `tool_use` proposals, and tool-call outcomes — the audit trail that ties every money-affecting recommendation to the exact prompt/tool schema that produced it (ruling §PROMPT CUSTOMIZATION — "versioning").

```sql
-- V12__ai_conversations.sql
CREATE TABLE ai_conversations (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) (tenant key — Guardrail 4)
  started_by        VARCHAR(26) NOT NULL,                    -- FK users(id)
  status            ENUM('ACTIVE','CAMPAIGN_CREATED','DORMANT','PAUSED_CREDITS') NOT NULL DEFAULT 'ACTIVE',
  title             VARCHAR(200) NULL,                       -- derived from first intent
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_message_at   TIMESTAMP NULL,
  UNIQUE KEY uq_conv_active (workspace_id, status),          -- at most one ACTIVE per workspace (partial semantics enforced in service)
  INDEX idx_conv_workspace (workspace_id),
  CONSTRAINT fk_conv_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
  CONSTRAINT fk_conv_user      FOREIGN KEY (started_by)   REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_messages (
  id                VARCHAR(26) PRIMARY KEY,
  conversation_id   VARCHAR(26) NOT NULL,                    -- FK ai_conversations(id)
  role              ENUM('USER','ASSISTANT','SYSTEM','TOOL') NOT NULL,
  content           TEXT,
  metadata          JSON,                                    -- {prompt_version, tool_use[], actions[], token_usage}
  credits_charged   INT NOT NULL DEFAULT 0,                  -- 1 per exchange, 10 for analysis (PRD §7)
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_msg_conversation (conversation_id, created_at),
  CONSTRAINT fk_msg_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

> **Note on `uq_conv_active`:** MySQL can't do partial uniques; the "one ACTIVE conversation per workspace" invariant is enforced in `AICofoundService` (close prior ACTIVE before opening a new one). The composite index still serves lookups. Documented so nobody mistakes it for a hard DB guarantee.

- **JPA:** `AiConversation` + `AiConversationRepository` (`findByWorkspaceIdAndStatus`, `findFirstByWorkspaceIdAndStatusOrderByLastMessageAtDesc`); `AiMessage` + `AiMessageRepository` (`findByConversationIdOrderByCreatedAtAsc`). Enums `ConversationStatus`, `MessageRole`.

---

## 7. MIGRATION V13 — `campaign_intents` · **M2.5**

**Purpose:** The bridge from conversation → real campaign. Holds the proposed campaign shape Meera assembled; on `confirm_launch`, Spring re-derives budget server-side and creates the real `campaigns` row, stamping `campaign_id` here. This is where "Python proposes, Spring disposes" is persisted.

```sql
-- V13__campaign_intents.sql
CREATE TABLE campaign_intents (
  id                VARCHAR(26) PRIMARY KEY,
  conversation_id   VARCHAR(26) NOT NULL,                    -- FK ai_conversations(id)
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) (scoping)
  campaign_type     ENUM('HYPE','DIRECT','REVIEW','STANDARD') NOT NULL,
  product_name      VARCHAR(255),
  product_url       VARCHAR(500),
  product_price     DECIMAL(12,2) NULL,                      -- basis for server-side budget re-derivation
  proposed_budget   DECIMAL(12,2) NULL,                      -- AI proposal — NOT authoritative
  creator_count     INT,
  location_filter   JSON,                                    -- ['Mumbai']
  status            ENUM('DRAFTING','READY','CONFIRMED','ABANDONED') NOT NULL DEFAULT 'DRAFTING',
  confirmed         BOOLEAN NOT NULL DEFAULT FALSE,
  campaign_id       VARCHAR(26) NULL,                        -- FK campaigns(id) — set on confirm_launch
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_intent_conversation (conversation_id),
  INDEX idx_intent_workspace (workspace_id),
  CONSTRAINT fk_intent_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id),
  CONSTRAINT fk_intent_workspace    FOREIGN KEY (workspace_id)    REFERENCES workspaces(id),
  CONSTRAINT fk_intent_campaign     FOREIGN KEY (campaign_id)     REFERENCES campaigns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- **Money safety:** `proposed_budget` is the AI's suggestion only. On `confirm_launch`, `CampaignAutoCreatorService` recomputes the authoritative amount from `product_price` + `creator_count` via `BudgetCalculatorService` and ignores `proposed_budget` for any charge. (See `02-API-CONTRACT` internal `confirm_launch`.)
- **JPA:** `CampaignIntent` + `CampaignIntentRepository` (`findByConversationId`, `findByIdAndWorkspaceId`). Enums `CampaignIntentType`, `IntentStatus`.

---

## 8. MIGRATION V14 — `brand_ai_credits` + `meera_tool_calls` · **M2.5**

**Purpose:** `brand_ai_credits` = the credit meter (PRD §7) — the hard cost circuit-breaker (Guardrail 5). `meera_tool_calls` = the idempotency + audit ledger for every internal tool-call executor Python calls back into.

```sql
-- V14__ai_credits.sql
CREATE TABLE brand_ai_credits (
  workspace_id      VARCHAR(26) PRIMARY KEY,                 -- FK workspaces(id) — 1:1, PK is the FK
  credits_remaining INT NOT NULL DEFAULT 100,
  monthly_allotment INT NOT NULL DEFAULT 100,                -- bumps to 150 after first funded campaign (loyalty)
  cycle_start       DATE NOT NULL,
  unlimited_until   TIMESTAMP NULL,                          -- = campaign_end + 3d when funded (escrow event)
  last_reset        DATE NOT NULL,
  first_campaign_at TIMESTAMP NULL,                          -- set once; gates the 150 loyalty bump
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_credits_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Idempotency + audit ledger for /internal/meera/* executors.
CREATE TABLE meera_tool_calls (
  id                VARCHAR(26) PRIMARY KEY,
  workspace_id      VARCHAR(26) NOT NULL,                    -- FK workspaces(id) (tenant isolation)
  conversation_id   VARCHAR(26) NULL,                        -- FK ai_conversations(id)
  tool_name         ENUM('create_campaign','request_payment','confirm_launch',
                    'show_creators','calculate_budget') NOT NULL,
  idempotency_key   VARCHAR(64) NOT NULL,                    -- supplied by Python per tool_use id
  status            ENUM('RECEIVED','EXECUTED','REJECTED','FAILED') NOT NULL DEFAULT 'RECEIVED',
  request_digest    VARCHAR(128) NULL,                       -- sha256 of request args (replay-mismatch detect)
  result_ref_type   ENUM('CAMPAIGN','ESCROW_HOLD','MILESTONE','INTENT','NONE') NOT NULL DEFAULT 'NONE',
  result_ref_id     VARCHAR(26) NULL,                        -- the real row Spring produced
  server_amount     DECIMAL(12,2) NULL,                      -- amount Spring RE-DERIVED (never from AI)
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_tool_idem (idempotency_key),
  INDEX idx_tool_workspace (workspace_id),
  INDEX idx_tool_conversation (conversation_id),
  CONSTRAINT fk_tool_workspace    FOREIGN KEY (workspace_id)    REFERENCES workspaces(id),
  CONSTRAINT fk_tool_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- **Credit enforcement (PRD §7):** `AICreditService.tryConsume(workspaceId)` runs in Spring **before** Python is ever called (Guardrail 5): `if unlimited_until > now → allow, no decrement; else if credits_remaining > 0 → decrement, allow; else → pause (soft paywall)`. Decrement is an atomic `UPDATE ... WHERE credits_remaining > 0` to avoid races.
- **Escrow reset hook:** `@EventListener EscrowFundedEvent` → set `unlimited_until = campaign_end + 3d`, `credits_remaining = monthly_allotment`, and if `first_campaign_at IS NULL` set it + `monthly_allotment = 150`.
- **Monthly cron (1st):** `credits_remaining = monthly_allotment` for non-live brands.
- **`meera_tool_calls` idempotency:** `uq_tool_idem` makes every internal executor safe to retry. `server_amount` records what Spring re-derived — the audit proof that the AI's proposed amount was never trusted.
- **JPA:** `BrandAiCredit` (`@Id workspaceId`) + `BrandAiCreditRepository` (`findByWorkspaceId`, custom `@Modifying` atomic decrement); `MeeraToolCall` + `MeeraToolCallRepository` (`findByIdempotencyKey`). Enums `MeeraToolName`, `ToolCallStatus`, `ToolResultRefType`.

---

## 9. ENTITY / REPOSITORY MAP (summary)

| Table | Migration | Milestone | Entity (`com.influora.domain.entity`) | Repository (`com.influora.repository`) |
|---|---|---|---|---|
| `wallet_transactions` | V8 | M2 | `WalletTransaction` | `WalletTransactionRepository` |
| `escrow_holds` | V9 | M2 | `EscrowHold` | `EscrowHoldRepository` |
| `contracts` | V10 | M2 | `Contract` | `ContractRepository` |
| `payment_milestones` | V10 | M2 | `PaymentMilestone` | `PaymentMilestoneRepository` |
| `brand_profiles` | V11 | M2.5 | `BrandProfile` | `BrandProfileRepository` |
| `ai_conversations` | V12 | M2.5 | `AiConversation` | `AiConversationRepository` |
| `ai_messages` | V12 | M2.5 | `AiMessage` | `AiMessageRepository` |
| `campaign_intents` | V13 | M2.5 | `CampaignIntent` | `CampaignIntentRepository` |
| `brand_ai_credits` | V14 | M2.5 | `BrandAiCredit` | `BrandAiCreditRepository` |
| `meera_tool_calls` | V14 | M2.5 | `MeeraToolCall` | `MeeraToolCallRepository` |

All entities: ULID `@Id @Column(length = 26)`, `Instant` timestamps, `@Enumerated(EnumType.STRING)`, JSON via `@JdbcTypeCode(SqlTypes.JSON) String`, builder pattern + `touch()` — identical to `Campaign.java`. IDs minted with `Ulids.newUlid()` in the service layer, never DB-generated.

---

## 10. INVARIANTS (for Kabir's spec + tests)

1. **No money row without an idempotency key.** `wallet_transactions`, `escrow_holds`, `payment_milestones` (on release), `meera_tool_calls` all carry a UNIQUE `idempotency_key`.
2. **Python never touches these tables.** MySQL sole writer is Spring (ruling §What lives where). No DB creds in the Python container.
3. **`workspace_id` on every AI/credit/tool row** — tenant isolation (Guardrail 4). Every read filters by the JWT's `workspaceId`; add a cross-tenant leakage test.
4. **Amounts are server-derived.** `campaign_intents.proposed_budget` and any AI-supplied amount are advisory; `payment_milestones.amount` / `meera_tool_calls.server_amount` are authoritative.
5. **Ledger is append-only.** `wallet_transactions` rows are never updated except `status` transitions to `REVERSED` (which writes a compensating pair, not an in-place edit).
