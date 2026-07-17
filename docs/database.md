# Database

Influora uses **MySQL 8 (InnoDB, utf8mb4)** as its system of record, managed by **Flyway**. This document catalogues the schema by domain: each table's purpose, key columns, relationships, and the migration that owns it.

## Conventions

- **Primary keys**: 26-char ULIDs (`VARCHAR(26)`), app-generated, lexicographically sortable.
- **Money**: `DECIMAL(14,2)` (some `12,2`) in **rupees**. The one exception is `invoices.amount` (subscription), stored as `INT` **paise**.
- **Timestamps**: `created_at` / `updated_at`, some `TIMESTAMP(3)`.
- **JSON columns** are used for flexible lists (platforms, categories, breakdowns, tool metadata).
- **Migrations**: `db/migration/V*.sql`. Numeric versions `V1`–`V64` plus timestamp versions `V20260709...`–`V20260715...`. Flyway runs `out-of-order: true` (timestamp versions sort below numeric ones); prod uses `baseline-on-migrate: false`.

`spring.jpa.hibernate.ddl-auto: validate` — Hibernate never mutates schema; Flyway is the sole schema authority, and a mismatch between entity and DDL fails startup.

---

## Identity & auth

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `users` | V2 | Core account: `email`, `password_hash` (BCrypt-12), `user_type` (BRAND/CREATOR/ADMIN), `status`, `email_verified`, soft-delete (V61) | 1:1 `creator_profiles`, `wallets`; N:M workspaces via members |
| `refresh_tokens` | V2 | Session durability: `token_hash` (SHA-256), `expires_at`, `revoked`. Raw token only in cookie | → `users` |
| `admin_users` | V34 | Platform operators: `admin_role`, encrypted MFA secret (V35), lockout counters (V20260712...) | referenced by admin FKs |
| `admin_refresh_tokens` | V34 | Admin sessions (separate cookie path) | → `admin_users` |
| `password_reset_tokens` | V2 | `token_hash`, `expires_at` (1h), `used` (single-use) | → `users` |
| `email_otp_challenges` | V5 | Email OTP: `otp_hash`, `expires_at` (5m), `attempts` (max 3), `verified` | by email |
| `workspaces` | V2 | Brand tenant: `name`, `slug`, `verification_status` (KYC), suspension (V36) | owns campaigns, wallet |
| `workspace_members` | V2 | Membership + `role` (OWNER/ADMIN/MANAGER/MEMBER/VIEWER) | `users` ↔ `workspaces` |
| `workspace_member_invites` | V59 | Pending seat invites: `status`, token | → `workspaces` |
| KYC docs | V3 | Workspace KYC document storage | → `workspaces` |

See [features/authentication.md](features/authentication.md), [features/workspaces-members.md](features/workspaces-members.md).

---

## Creators, discovery & scoring

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `creator_profiles` | V6 (+V32,V38,V20260715120000) | Creator identity: `username` (unique), `bio`, categories/languages JSON, `rate_min/max`, `is_discoverable`, `engagement_rate`, `total_followers`, `portfolio_settings_json`, moderation (`is_suspended`, `application_status`), tax identity (`gstin`, `pan`, `creator_invoice_code`) | 1:1 `users` |
| `platform_stats` | V6 | Per-platform followers/engagement/handle | → `creator_profiles` |
| `saved_creators` | V6 | Workspace shortlist, `UNIQUE(workspace_id, creator_profile_id)` | `workspaces` ↔ `creator_profiles` |
| `featured_creators` | V20260709163000 | Curated featured slots: `featured_category`, `display_order`, window, `is_active` | → `creator_profiles` |
| `creator_scores` | V22 | Daily computed scores (append-only): fake-follower, quality, brand-safety (nullable), estimated rate; `algorithm_version` | → `creator_profiles` |

See [features/marketplace-discovery.md](features/marketplace-discovery.md), [features/creator-profiles-portfolio.md](features/creator-profiles-portfolio.md).

---

## Campaigns, deals & contracts

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `campaigns` | V4 (+V30,V50) | Brand brief: `title`, `budget_min/max`, `currency`, dates, `status`, `campaign_type` (nullable), JSON facets, `is_private`, `max_collaborators`, `commission_rate` (affiliate override) | → `workspaces` |
| `campaign_intents` | V13 | AI conversation→campaign bridge: `campaign_type`, `product_name/url/price`, `proposed_budget` (advisory), `status`, `campaign_id` | → `ai_conversations`, `campaigns` |
| `campaign_templates` | V20260714150000 | Reusable presets (4 seeded SYSTEM + workspace CUSTOM): `category`, `scope`, campaign fields | → `workspaces` (nullable = SYSTEM) |
| `collaborations` | V6 (+V64) | The campaign↔creator join: `status` (13-state), `source`, `agreed_rate`, `usage_rights`. `UNIQUE(campaign_id, creator_id)`. **`creator_id` → users.id** (not creator_profiles) | `campaigns` ↔ `users` |
| `deal_messages` | V33 | Deal-room chat: `kind`, `sender_type`, `content`, `read_by_json` | → `collaborations` |
| `contracts` | V10 | Agreement: `version`, `status`, `total_amount` (server-computed), `pdf_r2_key`, `terms` (SHA-256 tamper hash), signature timestamps | → `collaborations` |
| `payment_milestones` | V10 (+V52) | Escrow units: `sequence_no`, `amount`, `status`, `escrow_hold_id`, `idempotency_key`, `release_condition` (unmapped in entity) | → `contracts`, `collaborations` |

See [features/campaigns.md](features/campaigns.md), [features/collaborations-deals.md](features/collaborations-deals.md), [features/contracts.md](features/contracts.md).

---

## Deliverables & content

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `deliverables` | V37 | Content per collaboration slot: `slot_index` (unique per collab), `type`, `status` (10-state), `files_json` (R2 keys), `version_number`, `post_url`, timestamps | → `collaborations`, `creator_profiles`, `payment_milestones` |
| `deliverable_metrics` | V19 (+V20260713120000) | Reach/impressions/engagements per milestone: `source` (CREATOR_REPORTED vs PLATFORM_VERIFIED), `platform_media_id`, `verified_at`, `proof_screenshot_r2_key`. 1:1 per `milestone_id` | → `payment_milestones`, `collaborations` |
| `content_flags` | V34 (+V43,V46) | Moderation queue: `content_type` (DELIVERABLE/PROFILE/MESSAGE/REVIEW), `status`, `reason`. `UNIQUE(content_id, flagged_by_user_id)` | polymorphic by `content_id` |
| `file_uploads` | V1 | **Legacy/orphaned** — no JPA entity; superseded by `deliverables.files_json` | — |

See [features/deliverables.md](features/deliverables.md), [features/uploads-storage.md](features/uploads-storage.md).

---

## Reviews & disputes

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `reviews` | V43 | Post-collaboration rating: `reviewer_type` (CREATOR/BRAND), `stars` (1–5 CHECK), `review_text`, `hidden`. `UNIQUE(collaboration_id, reviewer_type)` | → `collaborations`, `users` |
| `disputes` | V45 (+V53) | Arbitration case: `opened_by_type`, `reason`, `status` (OPEN/UNDER_REVIEW/RESOLVED_*), `resolved_by_admin_id`, `version` (optimistic lock) | → `collaborations`, `admin_users` |

See [features/reviews.md](features/reviews.md), [features/disputes.md](features/disputes.md).

---

## Money: wallet, escrow, payouts

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `wallets` | V2 | Balance per owner: `owner_type` (USER/WORKSPACE), `balance`, `escrow_balance` (**never written — see limitations**), `currency`. `UNIQUE(owner_id, owner_type)` | → users/workspaces |
| `wallet_transactions` | V8 | Append-only double-entry ledger: `group_id` (ties DEBIT+CREDIT), `direction`, `type`, `amount` (positive), `balance_after`, `reference_type/id`, `idempotency_key` (per-leg UNIQUE) | → `wallets` |
| `wallet_topups` | V20260709155921 | Razorpay top-up tracking: `amount`, `status` (PENDING/CREDITED), `razorpay_order_id`, `credit_txn_id` | → `workspaces` |
| `escrow_holds` | V9 | Held funds: `amount` (gross), `status` (PENDING/FUNDED/RELEASED/REFUNDED/FROZEN), `hold_txn_id`, `release_txn_id`, `idempotency_key` | → workspace/campaign/collaboration/milestone |
| `payouts` | V48 | **Dead code** — RazorpayX payout state actually lives on `payment_milestones` | — |
| `creator_bank_accounts` | V47 (+V49,V62) | Encrypted bank/UPI instruments: ciphertext fields, `razorpay_fund_account_id`, `is_primary`, `usable_after` (24h cool-down) | → `users` |

See [features/wallet.md](features/wallet.md), [features/escrow.md](features/escrow.md), [features/payouts.md](features/payouts.md).

---

## Billing, subscriptions & platform fees

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `plans` | V54 (+V55 seed,V57) | FREE/PRO: `price_inr` (paise), `fee_bps` (null=global), `ai_monthly_allotment`, `seat_limit`, caps, feature flags | referenced by subscriptions |
| `subscriptions` | V54 (+V56,V63) | Per-workspace (1:1): `plan_id`, `status`, `razorpay_subscription_id`, period bounds, `version`, comp fields | → `workspaces`, `plans` |
| `invoices` | V54 (+V20260715170000) | Subscription GST invoice (Doc#1): `amount` (paise), `invoice_number`, CGST/SGST/IGST, `hsn_sac_code` | → `subscriptions` |
| `usage_counters` | V54 | Plan usage: `metric`, `period_start`, `used`. `UNIQUE(workspace, metric, period)` | → `workspaces` |
| `usage_counter_details` | V58 | Per-entity dedup for usage caps | → `usage_counters` |
| `platform_fee_config` | V41 (+V42,V44) | **Singleton `id='default'`**: `default_fee_bps` (1500), `brand_fee_bps` (1000), min/max, `version` | global |
| `campaign_service_invoices` | V20260715130000 | Doc#2 (creator→brand): `escrow_hold_id` (unique), `gross_amount`, `tcs_amount`, `creator_gstin` | → `escrow_holds` |
| `platform_commission_invoices` | V20260715140000 | Doc#3 (Influora commission): `leg` (BRAND/CREATOR), `commission_amount`, `gst_amount`, `ledger_txn_id` | → campaigns/holds |
| `invoice_number_sequences` | V20260715150000 | Per-series fiscal-year counters | — |
| `hsn_sac_codes` | V20260715160000 | GST HSN/SAC lookup by `applies_to` | — |

See [features/billing-subscriptions.md](features/billing-subscriptions.md), [features/platform-fees.md](features/platform-fees.md), [features/invoicing-gst.md](features/invoicing-gst.md).

---

## Affiliate, coupons & tracking

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `coupon_codes` | V24 | Creator discount codes: `code`, `discount_type/value`, `usage_limit/count`, `expires_at`. `UNIQUE(workspace, code)` and `(campaign, creator)` | → campaign/creator |
| `coupon_redemptions` | V24 | Redemption event: `order_id`, `order_amount`, `discount_applied`, `idempotency_key` (unique) | → `coupon_codes` |
| `affiliate_earnings` | V28 | Commission accrual: `commission_amount`, `status` (PENDING/SETTLED/FAILED), `redemption_id` (unique), `settlement_batch_id` | → redemptions/creator/campaign |
| `affiliate_settlement_batches` | V28 | Monthly batch: `period_year_month`, `status`, totals | groups earnings |
| `utm_campaigns` | V23 | UTM tracking link: params, counters (`click_count`, `conversion_count`, `revenue_attributed`), `full_tracking_url`. `UNIQUE(campaign, creator_profile)` | → campaigns |
| `conversion_webhook_secrets` | V31 | Per-workspace generic webhook secret (encrypted) | → `workspaces` |

See [features/affiliate-coupons.md](features/affiliate-coupons.md), [features/conversion-tracking.md](features/conversion-tracking.md).

---

## Analytics time-series (Meta-sourced)

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `creator_metrics` | V21 | Profile snapshot per 6h poll: followers, engagement rate, reach/impressions, `data_source` | → `creator_profiles` |
| `media_metrics` | V21 (+V26) | Per-media snapshot: impressions/reach/engagement, `caption` (V26, internal brand-safety only, never brand-facing) | → `creator_profiles` |
| `audience_demographics` | V25 | Weekly snapshot: age/gender/country/city/locale JSON maps | → `creator_profiles` |
| `meta_oauth_tokens` | V20 | Encrypted Instagram access token, `expires_at`, `granted_scopes`, `revoked`. `UNIQUE(workspace, creator_profile)` | → `creator_profiles` |

See [features/analytics.md](features/analytics.md), [features/meta-integration.md](features/meta-integration.md).

---

## AI (Meera) & TrendSpark

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `ai_conversations` | V12 | Meera thread: `status`, `UNIQUE(workspace_id, status)` (≤1 ACTIVE) | → `workspaces` |
| `ai_messages` | V12 | Turn: `role` (USER/ASSISTANT/SYSTEM/TOOL), `content`, `metadata` JSON, `credits_charged` | → `ai_conversations` |
| `brand_ai_credits` | V14 (+V16) | Credit balance (PK = workspace): `credits_remaining`, `monthly_allotment`, `unlimited_until`, daily-cap columns | → `workspaces` |
| `meera_tool_calls` | V14 | Tool-call ledger: `idempotency_key` (unique), `request_digest`, `server_amount`, `status`, result ref | → conversation |
| `trends` | V51 | n8n-owned (Java read-only): `trend_text`, `themes` JSON, `campaign_type`, `expires_at` | — |
| `snapsby_catalog_video` | V51 | Catalog videos for nudges: `niche`, `themes`, `price_inr`, `active` | — |
| `nudge_log` | V51 | Nudge flywheel: `match_score`, `mode`, `message_source`, `shown/clicked/purchased_at` | → `workspaces` |
| `brand_profiles` | V11 | Brand marketing profile: `theme_tags`, `last_posted_at` (TrendSpark gap signal) | → `workspaces` |

See [features/meera-ai.md](features/meera-ai.md), [features/trendspark.md](features/trendspark.md), [ai.md](ai.md).

---

## Notifications, support & audit

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `notifications` | V17 | In-app: `event_type`, `title`, `body`, `link`, `is_read` | → `users` |
| `email_outbox` | V18 | Email queue: `template_key`, `template_data` JSON, `status`, `retry_count`, `next_retry_at`, `idempotency_key` (unique) | → `users` |
| `email_preferences` | V18 | Opt-out: `event_type` (or `'*'`), `unsubscribed` | → `users` |
| `support_tickets` | V34 | `category`, `status`, `priority`, `assigned_to` | → users/admin_users |
| `support_ticket_messages` | V34 | Append-only thread: `sender_type`, `content` (PII, never logged) | → `support_tickets` |
| `audit_log` / `admin_audit_logs` | V15/V34 | Metadata-only audit trail | — |
| `idempotency_keys` | V15 | Idempotency records: composite PK `scope:workspace:key`, `status`, `result_digest` (no TTL reaper) | — |

See [features/notifications.md](features/notifications.md), [features/support-tickets.md](features/support-tickets.md).

---

## Store integrations

| Table | Migration | Purpose / key columns | Relationships |
|---|---|---|---|
| `shopify_integrations` | V27 | OAuth store: `shop_domain` (unique), encrypted access token, scopes | → `workspaces` |
| `woocommerce_integrations` | V29 | Receive-only store: `site_url` (unique), encrypted webhook secret | → `workspaces` |

See [features/shopify-integration.md](features/shopify-integration.md), [features/woocommerce-integration.md](features/woocommerce-integration.md).

---

## Migration index (chronological themes)

`V1` uploads · `V2` core auth/wallets · `V3` KYC docs · `V4` campaigns · `V5` email OTP · `V6` creators/collaborations · `V7` seed creators · `V8` wallet txns · `V9` escrow · `V10` contracts/milestones · `V11` brand profiles · `V12` AI conversations · `V13` campaign intents · `V14` AI credits/tool calls · `V15` audit + idempotency · `V16` daily action cap · `V17` notifications · `V18` email outbox · `V19` deliverable metrics · `V20` Meta tokens · `V21` creator metrics · `V22` scores · `V23` UTM · `V24` coupons · `V25` demographics · `V26` media caption · `V27` Shopify · `V28` affiliate · `V29` WooCommerce · `V30` campaign type · `V31` conversion secrets · `V32` portfolio · `V33` deal messages · `V34` admin tables · `V35` encrypt MFA · `V36` workspace suspension/KYC · `V37` deliverables · `V38` creator moderation · `V41`–`V44` platform fee config · `V45`/`V53` disputes · `V47`–`V49`/`V62` bank accounts/payouts · `V50` campaign commission · `V51` TrendSpark · `V52` milestone release condition · `V54`–`V57`/`V63` billing/subscriptions · `V58` usage dedup · `V59` member invites · `V61` user soft-delete · `V64` usage rights · `V20260709`–`V20260715` (top-ups, featured creators, admin lockout, deliverable verification, campaign templates, creator tax identity, GST invoicing series).
