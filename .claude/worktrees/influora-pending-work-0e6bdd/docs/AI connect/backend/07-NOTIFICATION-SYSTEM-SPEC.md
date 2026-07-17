# 📬 NOTIFICATION SYSTEM — EMAIL & IN-APP EVENTS

> **Owner:** Priya (CTO) + Swapnil (CEO scope) · **Date:** 2026-07-05
> **Security review:** Kabir (no PII leaks in email bodies; unsubscribe compliance)
> **Build:** Vikram (backend) · **Milestone:** M2 (core events) → M2.5 (AI events)
> **Provider:** MSG91 Email API (already referenced in `BrandEmailOtpService.java:54`)

---

## 1. CURRENT STATE

The codebase has **no notification service yet**. The only email touchpoint is `BrandEmailOtpService` for OTP during brand signup — and even that is a `TODO` stub (logs OTP to console, no actual send). No creator-side email. No event-driven notifications.

This spec defines the complete system.

---

## 2. GOVERNING PRINCIPLES

1. **Email is a courtesy, not a gatekeeper.** Critical actions (payment, contract) must succeed even if email fails. Email is fire-and-forget with retries; failures are logged, not blocking.
2. **In-app notification is the primary channel.** Email is a fallback/reminder for users who aren't online. Every email event also creates an in-app notification.
3. **Unsubscribe compliance.** Every marketing/campaign email has a one-click unsubscribe (GDPR/CAN-SPAM). Transactional emails (OTP, payment receipt) are exempt but minimal.
4. **No PII in email bodies.** Names are fine; no PAN, bank account, KYC docs, or full addresses. Links to the app for details.
5. **Rate-limit per user.** Max 1 email per event type per hour per user (debounce noisy events like multiple bids).

---

## 3. THE NOTIFICATION MATRIX

### 3.1 Brand → Creator (brand activity notifies creator)

| # | Event | Email? | In-app? | Trigger | Template key | Who receives |
|---|-------|--------|---------|---------|--------------|--------------|
| 1 | **Campaign created in creator's category** | ✅ | ✅ | `campaign.created` + category match | `creator.campaign_match` | All verified creators whose `niche[]` overlaps campaign category |
| 2 | **Brand sends first message** | ✅ | ✅ | `message.first` (first message in a new conversation) | `creator.new_conversation` | The creator being messaged |
| 3 | **Brand sends proposal / bid** | ✅ | ✅ | `proposal.sent` | `creator.proposal_received` | The creator receiving the proposal |
| 4 | **Brand accepts creator's counter-bid** | ✅ | ✅ | `bid.accepted` | `creator.bid_accepted` | The creator whose bid was accepted |
| 5 | **Brand funds escrow (campaign goes live)** | ✅ | ✅ | `escrow.funded` | `creator.campaign_live` | All invited creators on that campaign |
| 6 | **Product shipped by brand** | ✅ | ✅ | `shipment.created` | `creator.product_shipped` | The creator receiving the product |
| 7 | **Contract ready for signature** | ✅ | ✅ | `contract.pending_signature` | `creator.sign_contract` | The creator who needs to sign |
| 8 | **Payment released (milestone/final)** | ✅ | ✅ | `payout.released` | `creator.payout_released` | The creator receiving payout |

### 3.2 Creator → Brand (creator activity notifies brand)

| # | Event | Email? | In-app? | Trigger | Template key | Who receives |
|---|-------|--------|---------|---------|--------------|--------------|
| 9 | **Creator applies to campaign** | ✅ | ✅ | `application.created` | `brand.new_application` | Brand owner + workspace admins |
| 10 | **Creator sends counter-bid** | ✅ | ✅ | `bid.countered` | `brand.counter_bid` | Brand owner |
| 11 | **Creator accepts proposal** | ✅ | ✅ | `proposal.accepted` | `brand.proposal_accepted` | Brand owner |
| 12 | **Creator signs contract** | ✅ | ✅ | `contract.signed` | `brand.contract_signed` | Brand owner |
| 13 | **Creator submits deliverable** | ✅ | ✅ | `deliverable.submitted` | `brand.deliverable_ready` | Brand owner |
| 14 | **Creator confirms product received** | ✅ | ✅ | `shipment.received` | `brand.product_received` | Brand owner |
| 15 | **Creator sends first message** | ✅ | ✅ | `message.first` (creator initiates) | `brand.new_conversation` | Brand owner |

### 3.3 System → Both (transactional / lifecycle)

| # | Event | Email? | In-app? | Trigger | Template key | Who receives |
|---|-------|--------|---------|---------|--------------|--------------|
| 16 | **OTP for signup/login** | ✅ | ❌ | `auth.otp` | `auth.otp` | The user requesting OTP |
| 17 | **Password reset** | ✅ | ❌ | `auth.reset` | `auth.password_reset` | The user requesting reset |
| 18 | **Welcome after signup** | ✅ | ✅ | `user.created` | `welcome.{brand\|creator}` | New user |
| 19 | **KYC approved** | ✅ | ✅ | `kyc.approved` | `creator.kyc_approved` | Creator |
| 20 | **KYC rejected** | ✅ | ✅ | `kyc.rejected` | `creator.kyc_rejected` | Creator |
| 21 | **Wallet low balance warning** | ✅ | ✅ | `wallet.low_balance` (< ₹500) | `brand.low_balance` | Brand owner |
| 22 | **Monthly statement** | ✅ | ❌ | `cron.monthly_statement` | `user.monthly_statement` | All active users |

### 3.4 Meera AI → Brand (AI-driven, M2.5)

| # | Event | Email? | In-app? | Trigger | Template key | Who receives |
|---|-------|--------|---------|---------|--------------|--------------|
| 23 | **Website analysis complete** | ❌ | ✅ | `ai.site_analyzed` | — | Brand (in-app only, Meera shows it live) |
| 24 | **Campaign recommendation ready** | ❌ | ✅ | `ai.campaign_recommended` | — | Brand (in-app) |
| 25 | **Credit exhausted (free tier)** | ✅ | ✅ | `ai.credits_exhausted` | `brand.credits_exhausted` | Brand owner |
| 26 | **Credits reset (after go-live)** | ❌ | ✅ | `ai.credits_reset` | — | Brand (in-app) |

---

## 4. ARCHITECTURE

```
┌────────────────────────────────────────────────────────────────┐
│  SPRING BOOT (event source)                                    │
│  ───────────────────────────                                   │
│  Domain services emit ApplicationEvent → NotificationListener  │
│  NotificationListener writes to `notifications` table (in-app) │
│                + publishes to `email_outbox` (transactional)   │
└─────────────────────────────┬──────────────────────────────────┘
                              │
              ┌───────────────▼───────────────┐
              │  email_outbox (MySQL table)   │
              │  status: PENDING → SENT/FAILED│
              │  retry_count, next_retry_at   │
              └───────────────┬───────────────┘
                              │
        ┌─────────────────────▼─────────────────────┐
        │  EmailWorker (scheduled, every 30s)       │
        │  - picks PENDING rows (batch 50)          │
        │  - calls MSG91 API                        │
        │  - updates status                         │
        │  - exponential backoff on failure (max 5) │
        └───────────────────────────────────────────┘
```

**Why outbox pattern?**
- Email send is not in the critical path — domain transaction commits regardless.
- Retries are automatic; no lost emails on transient MSG91 failures.
- Easy to audit: every email attempt is logged.

---

## 5. DATA MODEL (Flyway V15, V16)

### V15 — `notifications` (in-app)

```sql
CREATE TABLE notifications (
  id              VARCHAR(26) PRIMARY KEY,           -- ULID
  user_id         VARCHAR(26) NOT NULL,
  workspace_id    VARCHAR(26),                       -- nullable for user-level
  event_type      VARCHAR(64) NOT NULL,              -- e.g., 'campaign.created'
  title           VARCHAR(255) NOT NULL,
  body            TEXT,
  link            VARCHAR(512),                      -- deep link into app
  is_read         BOOLEAN DEFAULT FALSE,
  created_at      TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_user_unread (user_id, is_read, created_at DESC),
  FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### V16 — `email_outbox`

```sql
CREATE TABLE email_outbox (
  id              VARCHAR(26) PRIMARY KEY,           -- ULID
  user_id         VARCHAR(26) NOT NULL,
  to_email        VARCHAR(255) NOT NULL,
  template_key    VARCHAR(64) NOT NULL,              -- e.g., 'creator.proposal_received'
  template_data   JSON NOT NULL,                     -- merge fields
  status          ENUM('PENDING','SENT','FAILED') DEFAULT 'PENDING',
  retry_count     TINYINT DEFAULT 0,
  next_retry_at   TIMESTAMP(3),
  sent_at         TIMESTAMP(3),
  error_message   VARCHAR(512),
  created_at      TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),
  idempotency_key VARCHAR(128) UNIQUE,               -- event_type:entity_id:user_id
  INDEX idx_pending (status, next_retry_at),
  FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**Idempotency:** `idempotency_key` prevents duplicate emails for the same event (e.g., if the event is replayed). Format: `{event_type}:{entity_id}:{user_id}`.

---

## 6. TEMPLATE DESIGN (MSG91)

Each `template_key` maps to a MSG91 template ID. Templates are stored in MSG91's dashboard, not in code — we pass merge fields only.

Example merge fields for `creator.proposal_received`:
```json
{
  "creator_name": "Priya",
  "brand_name": "Spice Route",
  "campaign_title": "Turmeric Launch",
  "proposed_amount": "₹2,500",
  "cta_link": "https://app.influora.com/creator/proposals/01HWXYZ..."
}
```

**No PII beyond name.** No PAN, bank details, addresses, KYC docs.

---

## 7. KABIR'S SECURITY CONSTRAINTS

| Constraint | Enforcement |
|---|---|
| No PII in email body | Template review before approval; `template_data` schema validated server-side |
| Unsubscribe link on marketing emails | MSG91 auto-injects; we track `email_preferences` per user |
| Rate-limit per user per event type | `idempotency_key` + 1-hour debounce in `NotificationListener` |
| Email outbox access is internal-only | No public endpoint; worker runs in-process |
| No email to unverified addresses | `to_email` must match a verified `users.email` |

---

## 8. MEERA AI SCOPE (cross-reference `06-MEERA-PERMISSIONS-MATRIX.md`)

Meera can **trigger** notification events for actions she stages (e.g., `proposal.sent` after human confirms), but she cannot:
- Send arbitrary emails (no `send_email` tool)
- Access the email outbox
- Read notification history (no PII leak vector)

Notification dispatch is a **side-effect of domain events**, not an AI tool-call.

---

## 9. VIKRAM WORK TASKS (append to `05-VIKRAM-WORK-TASKS.md`)

| ID | Task | Phase | Depends on |
|---|------|-------|------------|
| T6.1 | Migration V15 `notifications` + entity + repo | M2 | — |
| T6.2 | Migration V16 `email_outbox` + entity + repo | M2 | T6.1 |
| T6.3 | `NotificationService` — create in-app + queue email | M2 | T6.1, T6.2 |
| T6.4 | `NotificationListener` — listen to domain events, call `NotificationService` | M2 | T6.3 |
| T6.5 | `EmailWorker` scheduled task (30s poll, MSG91 send, retry logic) | M2 | T6.2 |
| T6.6 | MSG91 integration (`integration/msg91/Msg91EmailClient.java`) | M2 | — |
| T6.7 | Public endpoint: `GET /notifications` (paginated, user-scoped), `POST /notifications/{id}/read` | M2 | T6.1 |
| T6.8 | Wire all 22 domain events (§3.1–3.3) to `NotificationListener` | M2 | T6.4 |
| T6.9 | Wire Meera AI events (#23–26) to `NotificationListener` | M2.5 | T6.4, Phase 3 |
| T6.10 | `email_preferences` table + unsubscribe endpoint | M2 | T6.2 |

---

## 10. DEFINITION OF DONE

- [ ] In-app notifications appear for all 22 core events
- [ ] Emails send via MSG91 for all email-enabled events
- [ ] Idempotency: replaying an event does not duplicate email
- [ ] Unsubscribe link works; unsubscribed users don't receive marketing emails
- [ ] No PII beyond name in any email body (Kabir audit)
- [ ] Rate-limit: max 1 email per event type per user per hour
- [ ] Email failures retry with exponential backoff (max 5 attempts)
- [ ] `GET /notifications` returns paginated, unread-first list
- [ ] Meera AI events (#23–26) trigger in-app notifications in M2.5
