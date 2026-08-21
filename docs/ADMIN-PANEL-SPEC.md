# INFLUORA ADMIN PANEL SPECIFICATION

> **Strategic Requirements & Technical Specification**  
> C-Suite Collaborative Design Document  
> Date: July 9, 2026  
> Contributors: Swapnil (CEO), Priya (CTO), Rohan (CFO), Arjun (COO), Tejas (CMO)

---

## Executive Summary

This document defines the comprehensive requirements for Influora's Admin Panel — the internal operations dashboard for managing the Creator Collaboration OS platform. The spec consolidates input from all C-suite executives to ensure business, technical, financial, operational, and marketing needs are addressed.

**Platform:** Influora — Escrow-backed Campaign OS connecting Indian brands with micro-creators (1K–100K followers)

**Tech Stack:** React/Vite frontend, Spring Boot/Java backend, MySQL database, Razorpay payments

---

## 1. CEO STRATEGIC VISION (Swapnil)

### 1.1 Business KPIs Dashboard

- **GMV (Gross Merchandise Value)** — Total campaign spend flowing through platform
- **Take Rate / Platform Commission** — Actual revenue (15% of escrow)
- **Campaign Completion Rate** — Healthy marketplace indicator
- **Creator-to-Brand Ratio** — Supply-demand balance tracking
- **MAUs** — Monthly active users (brands + creators separately)

### 1.2 User Base Overview

- Brand acquisition cost vs lifetime value (CAC:LTV)
- Creator onboarding velocity (1K-100K follower segments)
- Churn rates with exit reason analysis
- Geographic distribution (Tier 1/2/3 cities)

### 1.3 Payment & Escrow Monitoring

- **Escrow Float** — Real-time funds sitting in escrow
- **Payment Release Velocity** — Time from approval to payout
- **Failed/Stuck Payments** — Manual intervention queue
- **Revenue Dashboard** — Platform fees collected, projected vs actual

### 1.4 CEO Pulse View

Single-screen executive dashboard showing:
- GMV, Revenue, Active Campaigns, Support Queue Depth
- Week-over-week growth trends
- Red flags requiring CEO attention

---

## 2. CTO TECHNICAL ARCHITECTURE (Priya)

### 2.1 Backend Package Structure

```
com.influora.admin/
├── controller/    → AdminDashboardController, AdminUserController, 
│                    AdminPaymentController, AdminSupportController,
│                    AdminBrandController, AdminCreatorController
├── service/       → AdminAnalyticsService, ErrorMonitoringService, 
│                    EmailService, AuditLogService, ModerationService,
│                    ApprovalWorkflowService
├── repository/    → AdminAuditLogRepository, ErrorLogRepository, 
│                    EmailQueueRepository, ContentFlagRepository
├── security/      → AdminAuthFilter, RoleBasedAccessInterceptor
├── config/        → CacheConfig, AsyncConfig, WebSocketConfig
```

### 2.2 Database Tables

#### Core Admin Tables

| Table | Columns |
|-------|---------|
| `admin_users` | id, email, password_hash, role (SUPER_ADMIN/ADMIN/SUPPORT), mfa_secret, last_login |
| `audit_logs` | id, admin_id, action, entity_type, entity_id, old_value, new_value, ip_address, timestamp, reason |
| `error_logs` | id, severity (ERROR/WARN/CRITICAL), message, stack_trace, endpoint, user_id, resolved, created_at |
| `email_queue` | id, recipient, template_id, payload_json, status, retry_count, scheduled_at, sent_at |
| `email_templates` | id, name, subject_template, body_html, body_text, variables_schema |
| `support_tickets` | id, user_id, user_type, category, subject, status, priority, assigned_to, created_at |

#### Profile & Moderation Tables

| Table | Columns |
|-------|---------|
| `brand_verifications` | id, brand_id, kyc_status (PENDING/APPROVED/REJECTED), gst_verified, pan_verified, reviewed_by, reviewed_at, rejection_reason |
| `creator_applications` | id, creator_id, status (PENDING/APPROVED/REJECTED), reviewed_by, reviewed_at, rejection_reason, quality_score |
| `content_flags` | id, content_type, content_id, flag_reason, flagged_by, status (PENDING/REVIEWED/ACTIONED), action_taken, reviewed_by |
| `account_suspensions` | id, user_id, user_type, reason, suspended_by, suspended_at, appeal_status, reinstated_at |
| `approval_workflows` | id, workflow_type, entity_id, status, submitted_at, reviewed_by, reviewed_at, notes |

#### Marketing & Analytics Tables

| Table | Columns |
|-------|---------|
| `acquisition_sources` | id, user_id, user_type, channel, campaign_id, utm_source, utm_medium, created_at |
| `creator_quality_scores` | id, creator_id, engagement_rate, deadline_adherence, revision_rate, dispute_rate, overall_score, updated_at |
| `referral_tracking` | id, referrer_id, referred_id, referral_code, status, revenue_attributed, converted_at |
| `platform_reputation` | id, date, creator_quality_avg, brand_satisfaction_avg, dispute_resolution_speed, overall_score |

### 2.3 Error Monitoring System

- **AOP-based `@ErrorCapture`** annotation on controllers
- Critical errors trigger Slack webhook via `SlackAlertService`
- PagerDuty integration for P1 incidents
- Dashboard polls `/api/admin/errors/recent` with WebSocket for real-time updates

### 2.4 Email System

- Spring `@Async` email processor with Thymeleaf templates
- **Transactional:** password reset, payment confirmation, deliverable status
- **Bulk:** newsletter, campaign milestones, re-engagement via batched queue
- Retry logic: 3 attempts with exponential backoff
- Segmentation by: inactive users, campaign milestones, payment status

### 2.5 Security Architecture

- Spring Security with JWT + MFA (TOTP)
- Role hierarchy: SUPER_ADMIN > ADMIN > SUPPORT
- All mutations logged to `audit_logs` with mandatory reason field
- IP whitelisting for admin endpoints
- Every action logs WHO, WHEN, WHY — audit trail mandatory for legal protection

---

## 3. CFO FINANCIAL REQUIREMENTS (Rohan)

### 3.1 Revenue Dashboard

- **GMV Tracker:** Total campaign value processed (target: track ₹1Cr+ monthly)
- **Platform Fees:** 15% escrow fee breakdown by campaign, creator tier, brand
- **Setup Fees:** ₹20K collections per onboarding, conversion rate tracking
- **Period Views:** Daily/Weekly/Monthly/Quarterly with YoY comparison
- **Cohort Analysis:** Revenue by brand size, campaign type, creator category

### 3.2 Payment Operations

- **Escrow Monitor:** Real-time balance across all active campaigns
- **Payout Queue:** Pending (300+ per Hype campaign), processing, completed counts
- **Failed Transactions:** Auto-retry status, reason codes, stuck payments >48hrs
- **Settlement Cycle:** T+1/T+2/T+3 tracking against SLA

### 3.3 TDS/Compliance Module

- **Section 194C/194R Status:** Per-payout TDS deduction verification
- **PAN Verification:** Valid/Invalid/Pending counts, auto-block for invalid
- **26Q Reports:** Quarterly filing tracker with deadline alerts (7th of following month)
- **TDS Certificates:** 16A generation status

### 3.4 Cost Tracking & Burn Rate

- **API Costs:** Razorpay, SMS, WhatsApp API usage vs budget
- **Infrastructure:** AWS/hosting monthly burn
- **Burn Rate:** 6-month runway calculation, MoM trend

### 3.5 Reconciliation Tools

- **Razorpay vs Ledger:** Daily auto-match, exception queue for mismatches
- **UTR Matching:** Bank statement vs payout records
- **Variance Report:** >₹100 discrepancy alerts

### 3.6 Financial Alerts

| Alert | Threshold |
|-------|-----------|
| Low Escrow | <₹50K balance per brand |
| Unusual Spend | >20% deviation from daily average |
| Threshold Breaches | GST liability >₹20L, single payout >₹5L |

---

## 4. COO OPERATIONS REQUIREMENTS (Arjun)

### 4.1 Campaign Monitoring

- Real-time view of all active campaigns with creator counts
- Deal acceptance rates and stage distribution
- Flag campaigns where >20% deals stuck beyond 48 hours
- SLA breach countdown timers for pending approvals

### 4.2 Support Queue

- Unified ticket view sorted by priority and age
- **Creator lane:** Payment issues, content rejection disputes
- **Brand lane:** Deliverable disputes, timeline concerns
- Escalation triggers when tickets exceed 4-hour response SLA

### 4.3 Deliverable Review Pipeline

- Queue depth with AI pre-screen pass/fail counts
- Manual review backlog by reviewer
- Average review cycle time
- Auto-flag reviews pending >24 hours

### 4.4 Hype Campaign Operations

- Slot fill percentage across cohorts
- 72-hour window countdown per active Hype
- Auto-approve pipeline health (approvals per hour, rejection spikes)
- Cohort scheduling conflicts

### 4.5 Critical Operational Alerts

| Alert Condition | Threshold |
|-----------------|-----------|
| Campaign at Risk | >30% SLA breach rate |
| Review Backlog | >50 pending items |
| Payout Delay | Beyond T+3 |
| Hype Slot Fill | <60% at T-24 hours |
| Support Ticket Aging | Beyond 8 hours |

---

## 5. CMO MARKETING REQUIREMENTS (Tejas)

### 5.1 Acquisition Dashboard

- **CAC by Channel:** Paid/organic/referral for both brands and creators
- **Creator Application Funnel:** Application-to-approval conversion rate with rejection reasons
- **Brand Signup Attribution:** Source tracking with first-campaign-launch lag time
- **Creator Quality Distribution:** Engagement rate, content consistency by tier

### 5.2 Campaign Performance Analytics

- **Viral K-Factor:** Hype campaign invites sent / conversions
- **Content Benchmarks:** Avg reach, engagement rate by niche/follower tier
- **Brand Safety Incidents:** Rate per 100 campaigns
- **Creator Reliability Score:** Deadline adherence, revision requests, dispute rate

### 5.3 Growth Metrics

- **Funnel Tracking:** Signup → Profile Complete → First Campaign → Repeat Campaign
- **Cohort Retention:** 30/60/90-day curves (separate for brands/creators)
- **Referral Program:** Invites sent, conversion rate, revenue attributed
- **NPS Scores:** Segmented by user type and campaign volume

### 5.4 Platform Reputation Score

Aggregate metric combining:
- Creator quality average
- Brand satisfaction average
- Dispute resolution speed
- Overall platform health score

> *"This predicts churn better than any single metric."* — Tejas

### 5.5 Brand Safety & Content Moderation

- Flagged content queue with AI-detected violations
- Creator content audit sampling (random 5% quality review)
- Warning/strike system tracking per creator

---

## 6. BRAND PROFILE MANAGEMENT

### 6.1 Admin View Access

- Company details (name, industry, size, contact)
- Billing information and payment history
- Campaign history with performance metrics
- Team members and permissions

### 6.2 Admin Actions

| Action | Description | Required Role |
|--------|-------------|---------------|
| **View/Edit Profile** | Update company details, billing info | ADMIN |
| **KYC Verification** | Approve/reject GST, PAN, incorporation docs | ADMIN |
| **Suspend Account** | Fraud, payment defaults (documented reason required) | SUPER_ADMIN |
| **Reinstate Account** | Restore suspended account | SUPER_ADMIN |
| **Budget Override** | Modify campaign budget during dispute | SUPER_ADMIN |

### 6.3 KYC Verification Workflow

```
Brand Submits KYC → Admin Review Queue → Verify Documents
    ↓                                          ↓
[PENDING]                              [APPROVED] → Campaign Launch Enabled
                                              ↓
                                       [REJECTED] → Rejection Reason → Resubmit
```

**Rule:** No campaign launch without verified KYC documents.

---

## 7. CREATOR PROFILE MANAGEMENT

### 7.1 Admin View Access

- Profile information (name, bio, niche, location)
- Platform stats (followers, engagement rate, content samples)
- Instagram OAuth verification status
- Collaboration history with ratings
- Quality score and tier

### 7.2 Admin Actions

| Action | Description | Required Role |
|--------|-------------|---------------|
| **View/Edit Profile** | Update profile, engagement metrics | ADMIN |
| **Instagram OAuth** | Verify status, force re-authentication | ADMIN |
| **Application Review** | Approve/reject new creator applications | ADMIN |
| **Suspend/Ban** | Fake followers, policy violations | SUPER_ADMIN |
| **Tier Adjustment** | Manual quality score/tier override | ADMIN |
| **Reinstate Account** | Restore suspended account | SUPER_ADMIN |

### 7.3 Creator Application Workflow

```
Creator Applies → Quality Gate Review → Instagram OAuth Verification
       ↓                    ↓                        ↓
  [PENDING]          [Manual Review]          [Auto-Verify Stats]
                           ↓                        ↓
                    [APPROVED] → Platform Access Granted
                           ↓
                    [REJECTED] → Rejection Reason → Can Reapply (30 days)
```

**Rule:** Mandatory manual review before platform access.

---

## 8. APPROVAL & REJECTION WORKFLOWS

### 8.1 Critical Workflows

| Workflow | Trigger | Admin Action | Audit Required |
|----------|---------|--------------|----------------|
| **Creator Applications** | New signup | Approve/Reject with reason | ✅ |
| **Brand KYC** | Document submission | Verify/Reject with reason | ✅ |
| **Deliverable Disputes** | Brand/Creator disagree | Override approval/rejection | ✅ |
| **Escrow Releases** | Flagged transaction | Approve/Hold/Refund | ✅ |
| **Content Moderation** | AI flag or report | Remove/Warn/Escalate | ✅ |
| **Account Suspensions** | Policy violation | Suspend with documented reason | ✅ |

### 8.2 Deliverable Dispute Resolution

```
Creator Submits → Brand Reviews → [APPROVED] → Escrow Release
                       ↓
                [DISPUTED] → Admin Queue → Manual Review
                                   ↓
                    [Admin Approves] → Force Escrow Release
                    [Admin Rejects] → Revision Required / Partial Refund
```

### 8.3 Escrow Override Workflow

```
Normal Flow: Deliverable Approved → Auto Escrow Release

Flagged Flow:
  - Unusual amount (>₹5L)
  - Dispute raised
  - Fraud detection trigger
       ↓
  Admin Review Queue → [APPROVE] → Release funds
                     → [HOLD] → Pending investigation
                     → [REFUND] → Return to brand (partial/full)
```

### 8.4 Account Suspension Workflow

```
Violation Detected → Document Evidence → Admin Review
       ↓                                      ↓
  [Warning Issued] ← Minor Violation    [SUSPEND] ← Serious Violation
                                              ↓
                                    User Notified + Appeal Option
                                              ↓
                                    Appeal Review → [Reinstate/Uphold]
```

**Rule:** Every suspension requires documented reason and appeal pathway.

---

## 9. ADMIN ROLE HIERARCHY

| Role | Access Level | Capabilities |
|------|--------------|--------------|
| **SUPER_ADMIN** | Full system access | User CRUD, System config, All reports, Escrow override, Account suspension/reinstatement |
| **ADMIN** | Operations access | Reports, Campaigns, Payments, User verification, Profile edits, Tier adjustments |
| **SUPPORT** | Limited read + tickets | Support tickets, User lookup (read-only), Basic profile view |

### 9.1 Permission Matrix

| Feature | SUPER_ADMIN | ADMIN | SUPPORT |
|---------|-------------|-------|---------|
| View Dashboards | ✅ | ✅ | ✅ |
| Edit User Profiles | ✅ | ✅ | ❌ |
| Approve KYC/Applications | ✅ | ✅ | ❌ |
| Suspend Accounts | ✅ | ❌ | ❌ |
| Override Escrow | ✅ | ❌ | ❌ |
| System Configuration | ✅ | ❌ | ❌ |
| View Audit Logs | ✅ | ✅ | ❌ |
| Manage Admin Users | ✅ | ❌ | ❌ |
| Handle Support Tickets | ✅ | ✅ | ✅ |

---

## 10. IMPLEMENTATION ROADMAP

### Phase 1: Foundation (Weeks 1-2)
- [ ] Database migrations for all admin tables
- [ ] Admin authentication with JWT + MFA
- [ ] Basic role-based access control
- [ ] Audit logging infrastructure

### Phase 2: Core Dashboards (Weeks 3-4)
- [ ] CEO Pulse dashboard
- [ ] User management (Brands + Creators)
- [ ] Campaign monitoring
- [ ] Basic analytics charts

### Phase 3: Profile Management (Weeks 5-6)
- [ ] Brand profile admin view/edit
- [ ] Creator profile admin view/edit
- [ ] KYC verification workflow
- [ ] Creator application workflow

### Phase 4: Financial Module (Weeks 7-8)
- [ ] Revenue dashboard
- [ ] Escrow monitoring + override
- [ ] Razorpay reconciliation
- [ ] TDS/compliance reporting

### Phase 5: Operations & Support (Weeks 9-10)
- [ ] Support ticket system
- [ ] Deliverable review queue
- [ ] Dispute resolution workflow
- [ ] Email system with templates

### Phase 6: Marketing & Moderation (Weeks 11-12)
- [ ] Acquisition analytics (CAC, funnels)
- [ ] Content moderation queue
- [ ] Platform reputation score
- [ ] Referral tracking

### Phase 7: Polish & Alerts (Weeks 13-14)
- [ ] All critical alert thresholds
- [ ] PagerDuty/Slack integration
- [ ] Performance optimization (Redis caching)
- [ ] Security hardening + penetration testing

---

## 11. API ENDPOINTS

### Dashboard APIs
```
GET  /api/admin/dashboard/pulse          # CEO overview
GET  /api/admin/dashboard/financial      # CFO metrics
GET  /api/admin/dashboard/operations     # COO metrics
GET  /api/admin/dashboard/marketing      # CMO metrics
```

### User Management APIs
```
GET  /api/admin/brands                   # List brands
GET  /api/admin/brands/{id}              # Brand detail
PUT  /api/admin/brands/{id}              # Update brand
POST /api/admin/brands/{id}/verify-kyc   # KYC action
POST /api/admin/brands/{id}/suspend      # Suspend brand

GET  /api/admin/creators                 # List creators
GET  /api/admin/creators/{id}            # Creator detail
PUT  /api/admin/creators/{id}            # Update creator
POST /api/admin/creators/{id}/approve    # Approve application
POST /api/admin/creators/{id}/reject     # Reject application
POST /api/admin/creators/{id}/suspend    # Suspend creator
PUT  /api/admin/creators/{id}/tier       # Adjust tier
```

### Workflow APIs
```
GET  /api/admin/workflows/pending        # All pending approvals
POST /api/admin/workflows/{id}/approve   # Approve workflow
POST /api/admin/workflows/{id}/reject    # Reject workflow

GET  /api/admin/escrow/flagged           # Flagged transactions
POST /api/admin/escrow/{id}/release      # Force release
POST /api/admin/escrow/{id}/refund       # Process refund

GET  /api/admin/moderation/queue         # Content flags
POST /api/admin/moderation/{id}/action   # Take action
```

### Support APIs
```
GET  /api/admin/tickets                  # List tickets
GET  /api/admin/tickets/{id}             # Ticket detail
PUT  /api/admin/tickets/{id}             # Update ticket
POST /api/admin/tickets/{id}/escalate    # Escalate ticket
```

---

## 12. NON-NEGOTIABLES

Per CEO directive:

1. **Audit Trail** — Every admin action logs WHO, WHEN, WHY
2. **Documented Reasons** — All rejections/suspensions require written justification
3. **Appeal Pathway** — Suspended users must have appeal option
4. **MFA Required** — All admin accounts must use two-factor authentication
5. **IP Whitelist** — Admin access restricted to approved IPs
6. **KYC Gate** — No brand campaign without verified documents
7. **Quality Gate** — No creator access without application approval

---

## Approval

| Role | Name | Status |
|------|------|--------|
| CEO | Swapnil Maruti | ✅ APPROVED |
| CTO | Priya | ✅ APPROVED |
| CFO | Rohan | ✅ APPROVED |
| COO | Arjun | ✅ APPROVED |
| CMO | Tejas | ✅ APPROVED |

---

*"Build it. Ship it."* — Swapnil Maruti, CEO

---

**Document Version:** 1.0  
**Last Updated:** July 9, 2026  
**Confidential — Sage Digital / Influora**
