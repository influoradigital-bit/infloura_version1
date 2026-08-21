# Influora — Backend API Specification
**Version:** 1.0  
**Date:** 2026-05-17  
**Product:** Influora — B2B Influencer Marketing SaaS  
**Frontend:** React 19 · Vite 6 · TypeScript · `src/lib/types.ts` is the source of truth for all data shapes

---

## Table of Contents

1. [Tech Stack Recommendation](#1-tech-stack-recommendation)
2. [Database Schema](#2-database-schema)
3. [Global API Conventions](#3-global-api-conventions)
4. [Authentication APIs](#4-authentication-apis)
5. [User & Workspace APIs](#5-user--workspace-apis)
6. [Campaign APIs](#6-campaign-apis)
7. [Creator Discovery APIs](#7-creator-discovery-apis)
8. [Collaboration APIs](#8-collaboration-apis)
9. [Proposal APIs](#9-proposal-apis)
10. [Contract APIs](#10-contract-apis)
11. [Deliverable APIs](#11-deliverable-apis)
12. [Timeline & Messaging APIs](#12-timeline--messaging-apis)
13. [Wallet & Payment APIs](#13-wallet--payment-apis)
14. [Dispute APIs](#14-dispute-apis)
15. [Notification APIs](#15-notification-apis)
16. [File Upload APIs](#16-file-upload-apis)
17. [Admin APIs](#17-admin-apis)
18. [Real-Time WebSocket Events](#18-real-time-websocket-events)
19. [Error Codes Reference](#19-error-codes-reference)
20. [Rate Limiting](#20-rate-limiting)
21. [Environment Variables](#21-environment-variables)
22. [Frontend Integration Checklist](#22-frontend-integration-checklist)
23. [Creator Onboarding APIs](#23-creator-onboarding-apis)
24. [Creator Platform Connection APIs](#24-creator-platform-connection-apis)
25. [Creator Profile, Badges & Stats APIs](#25-creator-profile-badges--stats-apis)
26. [Creator Ratings & Reviews APIs](#26-creator-ratings--reviews-apis)
27. [Campaign Bids APIs](#27-campaign-bids-apis)
28. [Physical Product & Shipment APIs](#28-physical-product--shipment-apis)
29. [Contract Clause Comments APIs](#29-contract-clause-comments-apis)
30. [Brand Payment Methods & Auto-Recharge APIs](#30-brand-payment-methods--auto-recharge-apis)
31. [Account Security APIs](#31-account-security-apis)
32. [Utility & Miscellaneous APIs](#32-utility--miscellaneous-apis)
33. [Brand Flow API Alignment (src/lib/api.ts)](#33-brand-flow-api-alignment-srclibapits)

---

## 1. Tech Stack Recommendation

> **Locked stack (2026-05-23):** **Spring Boot 3** + **MySQL 8** + **Cloudflare R2** for all media (images, PDFs, **deliverable videos**). See `docs/BACKEND-STACK.md` for implementation detail.

### Recommended Backend Stack

| Layer | Technology | Reason |
|---|---|---|
| **Runtime** | Java 21 LTS | Stable, strong ecosystem for B2B SaaS APIs |
| **Framework** | Spring Boot 3.3+ (Web, Security, Data JPA) | REST, JWT, Flyway, production-ready |
| **ORM / migrations** | Spring Data JPA + **Flyway** | Schema versioning; entities mirror `types.ts` |
| **Primary DB** | **MySQL 8** (local Docker / managed e.g. PlanetScale, RDS, Aiven) | Relational domain; JSON columns for `platforms`, `objectives`, etc. |
| **Cache** | Redis (optional v1; Upstash in prod) | Refresh tokens, rate limiting, pub/sub later |
| **File / video storage** | **Cloudflare R2** (S3-compatible API) | Zero egress to Cloudflare CDN; deliverables up to 500MB (§16) |
| **Auth** | JWT (access) + refresh tokens | Stateless; brand/creator claims in JWT |
| **OTP/SMS** | **MSG91** (`auth-key`, widget) | Creator phone OTP (India-focused) |
| **Email OTP & transactional** | **MSG91 Email API v5** (`token-auth`) + template `otpman` | Brand verify-email OTP; optional SMTP fallback — see `docs/MSG91-EMAIL-OTP.md` |
| **Real-Time** | WebSocket (Spring) or SSE | Timeline / deal room (phase 2) |
| **Queue** | Spring `@Async` + Redis queue later | Thumbnails, PDF contracts, escrow jobs |
| **PDF** | OpenHTML / Puppeteer sidecar | Contract PDF → upload to R2 |
| **Hosting** | Railway / Render / Fly.io / AWS | Containerized Spring Boot JAR |

### Media on Cloudflare R2

- **All deliverable videos** (`video/mp4`, `video/quicktime`) upload via **presigned PUT** to R2 (§16.1), never stored on app server disk.
- **Public URLs** served via custom domain (`R2_PUBLIC_URL`, e.g. `https://r2.influora.com`) or presigned GET for private objects.
- **Object key layout:** `{purpose}/{workspaceId|userId}/{ulid}.{ext}` — e.g. `deliverables/ws_01ABC/reel-01XYZ.mp4`.
- **DB stores metadata only** (`file_uploads` table: `r2_key`, `mime_type`, `size_bytes`, `purpose`, `etag`) — not file bytes.
- **Thumbnails:** async job writes `thumbnails/{fileId}.jpg` to same bucket (§16.2).

### Monorepo Structure (Spring Boot)
```
New Influora/
├── src/                    ← existing React frontend
├── docs/
│   ├── BACKEND-API-SPEC.md
│   └── BACKEND-STACK.md
└── influora-api/           ← Spring Boot backend
    ├── pom.xml
    ├── src/main/java/com/influora/
    │   ├── config/         ← Security, Jwt, R2 (S3 client), Cors
    │   ├── domain/         ← JPA entities
    │   ├── repository/
    │   ├── service/        ← StorageService (R2), business logic
    │   └── web/            ← REST controllers
    └── src/main/resources/
        ├── application.yml
        └── db/migration/   ← Flyway (MySQL DDL from §2)
```

---

## 2. Database Schema

### Tables Overview

```
users                   → core identity
workspaces              → brand/agency accounts
workspace_members       → user ↔ workspace roles
creator_profiles        → creator public profiles
platform_stats          → per-platform follower/engagement data
campaigns               → brand campaign definitions
campaign_invites        → creator invitations to campaigns
collaborations          → brand ↔ creator relationship per campaign
proposals               → versioned deal proposals
contracts               → legal agreements
contract_deliverables   → deliverables per contract
deliverables            → submission tracking
deliverable_revisions   → file submissions per deliverable
timeline_events         → unified deal room activity stream
wallets                 → balance per user/workspace
wallet_transactions     → every financial movement
escrow_holds            → locked funds per collaboration
payment_milestones      → scheduled payment triggers
disputes                → conflict tracking
dispute_evidence        → files/messages as evidence
notifications           → in-app alerts
audit_logs              → every state change recorded
```

### Key Table Definitions

```sql
-- USERS
CREATE TABLE users (
  id            VARCHAR(26) PRIMARY KEY,  -- ULID
  email         VARCHAR(255) UNIQUE,
  phone_number  VARCHAR(20) UNIQUE,
  password_hash VARCHAR(255),
  user_type     ENUM('BRAND','CREATOR','ADMIN') NOT NULL,
  status        ENUM('PENDING_VERIFICATION','ACTIVE','SUSPENDED','DEACTIVATED') DEFAULT 'PENDING_VERIFICATION',
  email_verified    BOOLEAN DEFAULT FALSE,
  phone_verified    BOOLEAN DEFAULT FALSE,
  display_name  VARCHAR(100),
  first_name    VARCHAR(50),
  last_name     VARCHAR(50),
  avatar_url    VARCHAR(500),
  timezone      VARCHAR(50) DEFAULT 'Asia/Kolkata',
  last_login_at TIMESTAMP,
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- WORKSPACES (Brand accounts)
CREATE TABLE workspaces (
  id                    VARCHAR(26) PRIMARY KEY,
  name                  VARCHAR(200) NOT NULL,
  slug                  VARCHAR(100) UNIQUE NOT NULL,
  type                  ENUM('BRAND','AGENCY') DEFAULT 'BRAND',
  logo_url              VARCHAR(500),
  website_url           VARCHAR(500),
  industry              VARCHAR(100),
  company_size          VARCHAR(50),
  description           TEXT,
  verification_status   ENUM('UNVERIFIED','PENDING','VERIFIED','REJECTED') DEFAULT 'UNVERIFIED',
  billing_email         VARCHAR(255),
  gstin                 VARCHAR(20),
  pan                   VARCHAR(20),
  created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- CAMPAIGNS
CREATE TABLE campaigns (
  id                    VARCHAR(26) PRIMARY KEY,
  workspace_id          VARCHAR(26) NOT NULL REFERENCES workspaces(id),
  title                 VARCHAR(300) NOT NULL,
  description           TEXT,
  status                ENUM('DRAFT','PENDING_APPROVAL','ACTIVE','PAUSED','COMPLETED','CANCELLED') DEFAULT 'DRAFT',
  budget_min            DECIMAL(12,2),
  budget_max            DECIMAL(12,2),
  currency              VARCHAR(3) DEFAULT 'INR',
  start_date            DATE,
  end_date              DATE,
  application_deadline  DATE,
  platforms             JSON,          -- ['INSTAGRAM','YOUTUBE']
  content_types         JSON,          -- ['REEL','POST']
  objectives            JSON,
  requirements          JSON,
  hashtags              JSON,
  brand_guidelines      TEXT,
  is_private            BOOLEAN DEFAULT FALSE,
  max_collaborators     INT,
  created_by            VARCHAR(26) NOT NULL REFERENCES users(id),
  created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_workspace (workspace_id),
  INDEX idx_status (status)
);

-- COLLABORATIONS
CREATE TABLE collaborations (
  id              VARCHAR(26) PRIMARY KEY,
  campaign_id     VARCHAR(26) NOT NULL REFERENCES campaigns(id),
  creator_id      VARCHAR(26) NOT NULL REFERENCES users(id),
  status          ENUM('INVITED','APPLIED','SHORTLISTED','IN_NEGOTIATION','TERMS_AGREED',
                  'CONTRACT_PENDING','CONTRACTED','IN_PROGRESS','REVIEW_PENDING',
                  'REVISION_REQUESTED','COMPLETED','CANCELLED','DISPUTED') DEFAULT 'INVITED',
  source          ENUM('INVITATION','APPLICATION') NOT NULL,
  agreed_rate     DECIMAL(12,2),
  currency        VARCHAR(3) DEFAULT 'INR',
  notes           TEXT,
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_campaign_creator (campaign_id, creator_id),
  INDEX idx_creator (creator_id),
  INDEX idx_status (status)
);

-- WALLETS
CREATE TABLE wallets (
  id              VARCHAR(26) PRIMARY KEY,
  owner_id        VARCHAR(26) NOT NULL,
  owner_type      ENUM('USER','WORKSPACE') NOT NULL,
  balance         DECIMAL(14,2) DEFAULT 0.00,
  escrow_balance  DECIMAL(14,2) DEFAULT 0.00,
  currency        VARCHAR(3) DEFAULT 'INR',
  is_active       BOOLEAN DEFAULT TRUE,
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_owner (owner_id, owner_type)
);
```

---

## 3. Global API Conventions

### Base URL
```
Development:  http://localhost:8000/api/v1
Staging:      https://staging-api.influora.com/api/v1
Production:   https://api.influora.com/api/v1
```

### Standard Response Envelope

**Success:**
```json
{
  "success": true,
  "data": { ... },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 143,
    "hasMore": true
  },
  "timestamp": "2026-05-17T10:30:00.000Z"
}
```

**Error:**
```json
{
  "success": false,
  "error": {
    "code": "CAMPAIGN_NOT_FOUND",
    "message": "Campaign with ID xyz does not exist",
    "field": null
  },
  "timestamp": "2026-05-17T10:30:00.000Z"
}
```

**Validation Error:**
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "fields": [
      { "field": "email", "message": "Invalid email format" },
      { "field": "budget.min", "message": "Must be greater than 0" }
    ]
  },
  "timestamp": "2026-05-17T10:30:00.000Z"
}
```

### Authentication Header
```
Authorization: Bearer <access_token>
```

### ID Format
All IDs use **ULID** (Universally Unique Lexicographically Sortable Identifier) — 26-char strings. Example: `01ARZ3NDEKTSV4RRFFQ69G5FAV`

### Pagination
All list endpoints accept:
```
?page=1&limit=20&sortBy=createdAt&sortOrder=desc
```

### Date Format
All dates: **ISO 8601** — `2026-05-17T10:30:00.000Z`

---

## 4. Authentication APIs

### 4.1 Brand — Register
```
POST /auth/brand/register
Content-Type: application/json
Auth: None
```

**Request Body:**
```json
{
  "firstName": "Ananya",
  "lastName": "Sharma",
  "email": "ananya@brandco.com",
  "password": "SecurePass@123",
  "companyName": "BrandCo India",
  "industry": "fashion",
  "companySize": "6-20",
  "acceptedTerms": true
}
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "user": {
      "id": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
      "email": "ananya@brandco.com",
      "displayName": "Ananya Sharma",
      "userType": "BRAND",
      "status": "PENDING_VERIFICATION",
      "emailVerified": false
    },
    "workspace": {
      "id": "01BRZ3NDEKTSV4RRFFQ69G5FAX",
      "name": "BrandCo India",
      "slug": "brandco-india",
      "verificationStatus": "UNVERIFIED"
    },
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "expiresIn": 900
  }
}
```

**Side Effects:**
- Creates `users` row with `userType = BRAND`
- Creates `workspaces` row
- Creates `workspace_members` row with `role = OWNER`
- Creates `wallets` row for workspace
- Sends **6-digit email OTP** via MSG91 Email (template `otpman`, variable `otp`) — not SMS API

---

### 4.2 Brand — Login
```
POST /auth/brand/login
Content-Type: application/json
Auth: None
```

**Request Body:**
```json
{
  "email": "ananya@brandco.com",
  "password": "SecurePass@123"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "user": { "id": "...", "email": "...", "displayName": "...", "userType": "BRAND" },
    "workspace": { "id": "...", "name": "BrandCo India", "slug": "brandco-india" },
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "expiresIn": 900,
    "onboardingCompleted": true
  }
}
```

**Error Cases:**
- `401 INVALID_CREDENTIALS` — wrong email or password
- `403 ACCOUNT_SUSPENDED` — account suspended
- `403 EMAIL_NOT_VERIFIED` — email verification pending

---

### 4.3 Creator — Send OTP
```
POST /auth/creator/send-otp
Content-Type: application/json
Auth: None
```

**Request Body:**
```json
{
  "phoneNumber": "9876543210",
  "countryCode": "+91",
  "purpose": "login"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "message": "OTP sent successfully",
    "expiresIn": 300,
    "maskedPhone": "+91 ****43210"
  }
}
```

**Note:** OTP is 6 digits, expires in 5 minutes, max 3 attempts per phone per 10 minutes.

---

### 4.4 Creator — Verify OTP & Login
```
POST /auth/creator/verify-otp
Content-Type: application/json
Auth: None
```

**Request Body:**
```json
{
  "phoneNumber": "9876543210",
  "countryCode": "+91",
  "otp": "482910",
  "purpose": "login"
}
```

**Response `200` (existing user):**
```json
{
  "success": true,
  "data": {
    "user": { "id": "...", "displayName": "...", "userType": "CREATOR" },
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "expiresIn": 900,
    "isNewUser": false,
    "onboardingCompleted": true
  }
}
```

**Response `201` (new user — auto-registers):**
```json
{
  "success": true,
  "data": {
    "user": { "id": "...", "userType": "CREATOR", "status": "PENDING_VERIFICATION" },
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "expiresIn": 900,
    "isNewUser": true,
    "onboardingCompleted": false
  }
}
```

---

### 4.5 Refresh Access Token
```
POST /auth/refresh
Content-Type: application/json
Auth: None
```

**Request Body:**
```json
{ "refreshToken": "eyJ..." }
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "expiresIn": 900
  }
}
```

---

### 4.6 Logout
```
POST /auth/logout
Auth: Bearer token
```

**Response `200`:**
```json
{ "success": true, "data": { "message": "Logged out successfully" } }
```

**Side Effects:** Invalidates refresh token in Redis.

---

### 4.7 Forgot Password
```
POST /auth/forgot-password
Content-Type: application/json
Auth: None
```

**Request Body:**
```json
{ "email": "ananya@brandco.com" }
```

**Response `200`:**
```json
{
  "success": true,
  "data": { "message": "If this email exists, a reset link has been sent." }
}
```

**Note:** Always return 200 regardless of whether email exists (prevent email enumeration).

---

### 4.8 Reset Password
```
POST /auth/reset-password
Content-Type: application/json
Auth: None
```

**Request Body:**
```json
{
  "token": "reset_token_from_email_link",
  "newPassword": "NewSecurePass@456"
}
```

**Response `200`:**
```json
{ "success": true, "data": { "message": "Password reset successfully" } }
```

---

### 4.9 Verify Email (brand — MSG91 OTP)

> **Provider:** MSG91 Email API v5 (`token-auth`) + template id `otpman` (env: `MSG91_EMAIL_TEMPLATE_ID`). Full flow: `docs/MSG91-EMAIL-OTP.md`.

**`POST /auth/brand/verify-email`** — Auth: None (or Bearer optional)
```json
{ "email": "ananya@brandco.com", "otp": "482910" }
```

**Response `200`:**
```json
{
  "success": true,
  "data": { "emailVerified": true, "message": "Email verified successfully" }
}
```

**Errors:** `400 INVALID_OTP` · `410 OTP_EXPIRED` · `429 TOO_MANY_ATTEMPTS` (max 3 tries per OTP)

**Side effects:** `users.email_verified = true`; if `status = PENDING_VERIFICATION` → `ACTIVE`.

**Optional magic link (transactional template):** `GET /auth/verify-email?token=<signed_token>` — uses MSG91 template variable `magic_link` when `MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID` is set; redirects to `/brand/dashboard?verified=true`.

---

## 5. User & Workspace APIs

### 5.1 Get Current User
```
GET /users/me
Auth: Bearer token
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "id": "...",
    "email": "ananya@brandco.com",
    "displayName": "Ananya Sharma",
    "firstName": "Ananya",
    "lastName": "Sharma",
    "userType": "BRAND",
    "status": "ACTIVE",
    "avatarUrl": "https://r2.influora.com/avatars/01ARZ.jpg",
    "emailVerified": true,
    "phoneVerified": false,
    "timezone": "Asia/Kolkata",
    "createdAt": "2026-01-15T10:00:00.000Z"
  }
}
```

---

### 5.2 Update User Profile
```
PATCH /users/me
Auth: Bearer token
```

**Request Body (all fields optional):**
```json
{
  "firstName": "Ananya",
  "lastName": "Sharma",
  "displayName": "Ananya S.",
  "timezone": "Asia/Kolkata",
  "avatarUrl": "https://r2.influora.com/avatars/new.jpg"
}
```

---

### 5.3 Get Workspace (Brand)
```
GET /workspaces/:workspaceId
Auth: Bearer token (must be workspace member)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "id": "...",
    "name": "BrandCo India",
    "slug": "brandco-india",
    "type": "BRAND",
    "logoUrl": "https://r2.influora.com/logos/brandco.jpg",
    "websiteUrl": "https://brandco.com",
    "industry": "fashion",
    "companySize": "6-20",
    "verificationStatus": "VERIFIED",
    "billingEmail": "billing@brandco.com",
    "members": [
      {
        "userId": "...",
        "displayName": "Ananya Sharma",
        "email": "ananya@brandco.com",
        "role": "OWNER",
        "avatarUrl": "...",
        "joinedAt": "2026-01-15T10:00:00.000Z"
      }
    ]
  }
}
```

---

### 5.4 Update Workspace
```
PATCH /workspaces/:workspaceId
Auth: Bearer token (OWNER or ADMIN role only)
```

**Request Body:**
```json
{
  "name": "BrandCo India Pvt Ltd",
  "websiteUrl": "https://brandco.in",
  "industry": "fashion",
  "description": "Premium fashion brand...",
  "billingEmail": "billing@brandco.in"
}
```

---

### 5.5 Invite Team Member
```
POST /workspaces/:workspaceId/members/invite
Auth: Bearer token (OWNER or ADMIN)
```

**Request Body:**
```json
{
  "email": "colleague@brandco.com",
  "role": "MANAGER"
}
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "inviteId": "...",
    "email": "colleague@brandco.com",
    "role": "MANAGER",
    "expiresAt": "2026-05-24T10:00:00.000Z"
  }
}
```

---

### 5.6 Remove Team Member
```
DELETE /workspaces/:workspaceId/members/:userId
Auth: Bearer token (OWNER or ADMIN)
```

---

### 5.7 Upload Verification Documents
```
POST /workspaces/:workspaceId/verification
Content-Type: multipart/form-data
Auth: Bearer token (OWNER)
```

**Form Fields:**
```
gstinDocument   File (PDF/JPG, max 5MB)
panDocument     File (PDF/JPG, max 5MB)
gstin           "22AAAAA0000A1Z5"
pan             "AAAAA0000A"
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "verificationStatus": "PENDING",
    "submittedAt": "2026-05-17T10:00:00.000Z",
    "reviewExpectedBy": "2026-05-19T10:00:00.000Z"
  }
}
```

---

## 6. Campaign APIs

### 6.1 List Campaigns (Brand)
```
GET /campaigns
Auth: Bearer token
```

**Query Parameters:**
```
page=1&limit=20
status=ACTIVE,DRAFT          (comma-separated)
sortBy=createdAt|updatedAt|title
sortOrder=asc|desc
search=summer                 (search in title, description)
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "...",
      "title": "Summer Fashion 2026",
      "status": "ACTIVE",
      "budget": { "min": 50000, "max": 200000, "currency": "INR" },
      "timeline": { "startDate": "2026-06-01", "endDate": "2026-07-31" },
      "platforms": ["INSTAGRAM", "YOUTUBE"],
      "collaboratorsCount": 8,
      "activeCollaborations": 3,
      "completedCollaborations": 12,
      "totalSpend": 485000,
      "createdAt": "2026-04-01T10:00:00.000Z"
    }
  ],
  "meta": { "page": 1, "limit": 20, "total": 5, "hasMore": false }
}
```

---

### 6.2 Get Single Campaign
```
GET /campaigns/:campaignId
Auth: Bearer token
```

**Response `200`:** Full campaign object including all fields from Campaign type + computed metrics.

---

### 6.3 Create Campaign
```
POST /campaigns
Auth: Bearer token
```

**Request Body:**
```json
{
  "title": "Summer Fashion 2026",
  "description": "Looking for fashion creators for our summer collection launch.",
  "objectives": ["Brand Awareness", "Product Launch"],
  "budget": { "min": 50000, "max": 200000, "currency": "INR" },
  "timeline": {
    "startDate": "2026-06-01",
    "endDate": "2026-07-31"
  },
  "applicationDeadline": "2026-05-25",
  "platforms": ["INSTAGRAM", "YOUTUBE"],
  "contentTypes": ["REEL", "POST", "STORY"],
  "requirements": ["Min 50K followers", "Fashion niche", "India-based"],
  "hashtags": ["#InfluoraSummer", "#FashionForward"],
  "brandGuidelines": "Our brand voice is youthful and aspirational...",
  "isPrivate": false,
  "maxCollaborators": 10,
  "targetAudience": {
    "ageRange": { "min": 18, "max": 35 },
    "genders": ["female"],
    "locations": ["Mumbai", "Delhi", "Bangalore"],
    "interests": ["fashion", "lifestyle", "beauty"]
  }
}
```

**Response `201`:** Created campaign object.

**Validations:**
- `title` required, 5–300 chars
- `budget.min` > 0, `budget.max` >= `budget.min`
- `timeline.endDate` > `timeline.startDate`
- `applicationDeadline` < `timeline.startDate`
- `platforms` must be from Platform enum
- Workspace must have `verificationStatus = VERIFIED` to set `status = ACTIVE` (draft allowed always)

---

### 6.4 Update Campaign
```
PATCH /campaigns/:campaignId
Auth: Bearer token (workspace OWNER, ADMIN, or MANAGER)
```

**Request Body:** Any subset of Campaign fields (partial update).

**Business Rules:**
- Cannot edit `COMPLETED` or `CANCELLED` campaigns
- Changing from `ACTIVE` → `PAUSED` is allowed; `PAUSED` → `ACTIVE` requires re-verification check
- Cannot change `workspaceId`

---

### 6.5 Delete Campaign
```
DELETE /campaigns/:campaignId
Auth: Bearer token (OWNER only)
```

**Business Rules:**
- Only `DRAFT` campaigns can be deleted
- Active/completed campaigns must be cancelled, not deleted (audit trail)

---

### 6.6 Campaign Analytics
```
GET /campaigns/:campaignId/analytics
Auth: Bearer token
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "totalReach": 2400000,
    "totalImpressions": 8700000,
    "totalEngagements": 143000,
    "averageEngagementRate": 5.8,
    "totalSpend": 485000,
    "costPerEngagement": 3.39,
    "collaborationBreakdown": {
      "invited": 8,
      "inNegotiation": 5,
      "contracted": 3,
      "inProgress": 12,
      "completed": 28
    },
    "deliverableCompletion": 87,
    "onTimeDeliveryRate": 91
  }
}
```

---

## 7. Creator Discovery APIs

### 7.1 Search & Filter Creators
```
GET /creators/search
Auth: Bearer token (Brand users)
```

**Query Parameters:**
```
q=priya                       (name search)
platforms=INSTAGRAM,YOUTUBE    (comma-separated)
categories=fashion,beauty      (niche/category)
minFollowers=10000
maxFollowers=1000000
minEngagementRate=3.0
maxEngagementRate=15.0
location=Mumbai,Delhi
languages=hindi,english
isVerified=true
minRate=5000
maxRate=100000
minBudget=10000               (creator's minimum acceptable deal budget)
maxBudget=500000              (creator's maximum acceptable deal budget)
page=1&limit=20
sortBy=followers|engagement|rate|relevance
```

> `minBudget` / `maxBudget` filter corresponds to `budgetRange` in the frontend `useDiscoveryStore` (src/lib/store.ts). Filter against creator's `rateRange` — returns creators whose rate range overlaps the requested budget window.

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "...",
      "displayName": "Priya Sharma",
      "bio": "Fashion & Lifestyle creator from Mumbai",
      "avatarUrl": "https://r2.influora.com/avatars/priya.jpg",
      "location": "Mumbai",
      "categories": ["fashion", "beauty", "lifestyle"],
      "isVerified": true,
      "totalFollowers": 185000,
      "engagementRate": 4.8,
      "averageRate": 45000,
      "currency": "INR",
      "platforms": [
        {
          "platform": "INSTAGRAM",
          "handle": "@priya.fashion",
          "followers": 150000,
          "engagementRate": 5.2,
          "isVerified": true
        },
        {
          "platform": "YOUTUBE",
          "handle": "PriyaStyleDiaries",
          "followers": 35000,
          "engagementRate": 3.8,
          "isVerified": false
        }
      ],
      "recentCollaborations": 12,
      "portfolioPreview": [
        { "thumbnailUrl": "...", "platform": "INSTAGRAM" }
      ]
    }
  ],
  "meta": { "page": 1, "limit": 20, "total": 1247, "hasMore": true }
}
```

---

### 7.2 Get Creator Profile
```
GET /creators/:creatorId
Auth: Bearer token
```

**Response `200`:** Full CreatorProfile object from types.ts + portfolio items + past collaborations summary + review ratings.

---

### 7.3 Get Creator Analytics (for brand)
```
GET /creators/:creatorId/analytics
Auth: Bearer token (Brand)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "audienceDemographics": {
      "ageGroups": { "18-24": 34, "25-34": 41, "35-44": 17, "45+": 8 },
      "genderSplit": { "female": 68, "male": 30, "other": 2 },
      "topLocations": ["Mumbai", "Delhi", "Bangalore", "Pune"]
    },
    "contentPerformance": {
      "avgLikesPerPost": 7200,
      "avgCommentsPerPost": 340,
      "avgViewsPerReel": 85000,
      "avgSavedPerPost": 1200
    },
    "pastCampaignPerformance": {
      "completionRate": 96,
      "onTimeDelivery": 92,
      "revisionRate": 1.2,
      "brandSatisfactionScore": 4.7
    }
  }
}
```

---

### 7.4 Save Creator to Shortlist
```
POST /campaigns/:campaignId/shortlist
Auth: Bearer token
```

**Request Body:**
```json
{ "creatorId": "..." }
```

---

### 7.5 Remove from Shortlist
```
DELETE /campaigns/:campaignId/shortlist/:creatorId
Auth: Bearer token
```

---

## 8. Collaboration APIs

### 8.1 Invite Creator to Campaign
```
POST /collaborations/invite
Auth: Bearer token (Brand)
```

**Request Body:**
```json
{
  "campaignId": "...",
  "creatorId": "...",
  "message": "Hi Priya! We'd love to work with you on our summer campaign..."
}
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "collaborationId": "...",
    "status": "INVITED",
    "createdAt": "2026-05-17T10:00:00.000Z"
  }
}
```

**Side Effects:**
- Creates `collaborations` row
- Creates first `timeline_events` row with tag=`system`
- Sends push notification to creator

---

### 8.2 Creator — List Incoming Invitations
```
GET /collaborations/invitations
Auth: Bearer token (Creator)
Query: status=INVITED,APPLIED&page=1&limit=20
```

---

### 8.3 Creator — Respond to Invitation
```
POST /collaborations/:collaborationId/respond
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "action": "accept",
  "message": "Hi! I'd love to collaborate on this campaign."
}
```

`action`: `"accept"` | `"decline"`

---

### 8.4 Get Collaboration Details
```
GET /collaborations/:collaborationId
Auth: Bearer token (brand workspace member OR collaboration creator)
```

**Response `200`:** Full collaboration object + campaign summary + creator summary + current proposal + contract status.

---

### 8.5 List Active Collaborations (Brand)
```
GET /collaborations
Auth: Bearer token (Brand)
Query: campaignId=...&status=IN_PROGRESS,CONTRACTED&page=1&limit=20
```

---

### 8.6 Update Collaboration Status
```
PATCH /collaborations/:collaborationId/status
Auth: Bearer token
```

**Request Body:**
```json
{
  "status": "CANCELLED",
  "reason": "Creator unresponsive after 3 follow-ups"
}
```

**Allowed Transitions:**
```
Brand can move:   INVITED → SHORTLISTED | CANCELLED
Brand can move:   SHORTLISTED → IN_NEGOTIATION | CANCELLED
Both can move:    CONTRACTED → DISPUTED (raises dispute)
Brand can move:   REVIEW_PENDING → COMPLETED | REVISION_REQUESTED
```

---

## 9. Proposal APIs

### 9.1 Create / Send Proposal
```
POST /collaborations/:collaborationId/proposals
Auth: Bearer token (Brand or Creator)
```

**Request Body:**
```json
{
  "rate": 45000,
  "currency": "INR",
  "message": "Here's our proposal for the summer campaign collaboration.",
  "deliverables": [
    {
      "contentType": "REEL",
      "platform": "INSTAGRAM",
      "quantity": 2,
      "description": "60-second reels featuring the product"
    },
    {
      "contentType": "STORY",
      "platform": "INSTAGRAM",
      "quantity": 4,
      "description": "Story series with swipe-up link"
    }
  ],
  "timeline": {
    "startDate": "2026-06-10",
    "endDate": "2026-06-30",
    "deliveryDates": ["2026-06-15", "2026-06-25"]
  },
  "terms": "Payment to be made in 2 milestones: 50% on contract signing, 50% on content approval.",
  "expiresAt": "2026-05-22T23:59:59.000Z"
}
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "proposalId": "...",
    "version": 1,
    "status": "SENT",
    "proposedBy": "BRAND"
  }
}
```

**Business Rules:**
- Auto-increments `version` (1, 2, 3...)
- Previous proposal version auto-set to `COUNTERED`
- Collaboration status moves to `IN_NEGOTIATION`
- Creates `timeline_events` row with tag=`proposal`
- Triggers notification to the other party

---

### 9.2 Get Proposal
```
GET /proposals/:proposalId
Auth: Bearer token
```

---

### 9.3 Get Proposal History (All Versions)
```
GET /collaborations/:collaborationId/proposals
Auth: Bearer token
```

**Response `200`:** Array of all proposal versions sorted by version number desc.

---

### 9.4 Accept Proposal
```
POST /proposals/:proposalId/accept
Auth: Bearer token (the party that received the proposal)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "proposalId": "...",
    "status": "ACCEPTED",
    "collaborationStatus": "TERMS_AGREED",
    "contractGenerationStarted": true,
    "estimatedContractReady": "2026-05-17T10:05:00.000Z"
  }
}
```

**Side Effects:**
- Proposal status → `ACCEPTED`
- Collaboration status → `TERMS_AGREED`
- Triggers async job to generate PDF contract
- Creates `contracts` row with status `DRAFT`

---

### 9.5 Reject Proposal
```
POST /proposals/:proposalId/reject
Auth: Bearer token
```

**Request Body:**
```json
{ "reason": "The rate is above our current budget for this campaign." }
```

---

### 9.6 Counter Proposal
```
POST /proposals/:proposalId/counter
Auth: Bearer token
```

**Request Body:** Same as Create Proposal — creates a new version with `proposedBy` flipped.

---

## 10. Contract APIs

### 10.1 Get Contract
```
GET /contracts/:contractId
Auth: Bearer token (brand workspace member or collaboration creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "id": "...",
    "collaborationId": "...",
    "version": 1,
    "status": "PENDING_SIGNATURES",
    "pdfUrl": "https://r2.influora.com/contracts/influora-contract-01ARZ.pdf",
    "terms": {
      "totalAmount": 45000,
      "currency": "INR",
      "paymentSchedule": [
        {
          "id": "...",
          "description": "Signing milestone",
          "amount": 22500,
          "dueDate": "2026-06-10",
          "status": "PENDING"
        },
        {
          "id": "...",
          "description": "Completion milestone",
          "amount": 22500,
          "dueDate": "2026-06-30",
          "status": "PENDING"
        }
      ],
      "deliverables": [
        {
          "id": "...",
          "contentType": "REEL",
          "platform": "INSTAGRAM",
          "description": "60-second reel",
          "dueDate": "2026-06-20",
          "requirements": ["No competitor mentions", "Hashtag #BrandSummer"]
        }
      ],
      "exclusivity": { "isExclusive": false },
      "usageRights": { "duration": 12, "territories": ["India"], "canSublicense": false },
      "cancellationTerms": "Either party may cancel with 7 days written notice..."
    },
    "brandSignature": null,
    "creatorSignature": null,
    "effectiveDate": null,
    "expirationDate": "2026-07-31",
    "createdAt": "2026-05-17T10:00:00.000Z"
  }
}
```

---

### 10.2 Sign Contract
```
POST /contracts/:contractId/sign
Auth: Bearer token
```

**Request Body:**
```json
{
  "signatureData": "data:image/png;base64,iVBORw0KGgoAAAANS...",
  "agreedToTerms": true,
  "ipAddress": "auto-captured-server-side"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "contractId": "...",
    "signedBy": "BRAND",
    "signedAt": "2026-05-17T10:30:00.000Z",
    "contractStatus": "PENDING_SIGNATURES",
    "awaitingSignatureFrom": "CREATOR",
    "bothSigned": false
  }
}
```

**When Both Parties Sign:**
- Contract status → `ACTIVE`
- Collaboration status → `CONTRACTED`
- Escrow hold triggered automatically (first milestone amount)
- All `contract_deliverables` rows created as `deliverables`
- Timeline event created: tag=`contract`, metadata.contractStatus=`active`

---

### 10.3 List Contracts (Brand)
```
GET /contracts
Auth: Bearer token
Query: status=ACTIVE,PENDING_SIGNATURES&workspaceId=...&page=1&limit=20
```

---

### 10.4 Download Contract PDF
```
GET /contracts/:contractId/pdf
Auth: Bearer token
```

**Response:** Redirects to presigned R2 URL (valid 15 minutes).

---

## 11. Deliverable APIs

### 11.1 List Deliverables
```
GET /deliverables
Auth: Bearer token
Query: collaborationId=...&status=PENDING,SUBMITTED&page=1&limit=20
```

**Response `200`:** Array of Deliverable objects with current revision summary.

---

### 11.2 Get Deliverable Details
```
GET /deliverables/:deliverableId
Auth: Bearer token
```

**Response `200`:** Full Deliverable + all DeliverableRevision objects.

---

### 11.3 Submit Deliverable (Creator)
```
POST /deliverables/:deliverableId/submit
Content-Type: multipart/form-data
Auth: Bearer token (Creator)
```

**Form Fields:**
```
files[]          multiple files (video/image, max 500MB each, max 5 files)
caption          string (optional)
notes            string (optional)
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "revisionId": "...",
    "version": 1,
    "status": "SUBMITTED",
    "deliverableStatus": "UNDER_REVIEW",
    "files": [
      {
        "id": "...",
        "url": "https://r2.influora.com/deliverables/reel-v1-01ARZ.mp4",
        "filename": "summer-reel-final.mp4",
        "mimeType": "video/mp4",
        "size": 48291839,
        "thumbnailUrl": "https://r2.influora.com/deliverables/thumbnails/reel-v1-01ARZ.jpg"
      }
    ]
  }
}
```

**Side Effects:**
- Collaboration status → `REVIEW_PENDING`
- Timeline event: tag=`deliverable`, deliverableStatus=`submitted`
- Notification to brand

---

### 11.4 Approve Deliverable (Brand)
```
POST /deliverables/:deliverableId/approve
Auth: Bearer token (Brand)
```

**Request Body:**
```json
{
  "revisionId": "...",
  "comments": "Looks great! Approved."
}
```

**Side Effects:**
- Deliverable status → `APPROVED`
- If all deliverables approved → collaboration status → `COMPLETED`
- Milestone payment auto-released from escrow
- Timeline event: tag=`deliverable` + tag=`payment`

---

### 11.5 Request Revision (Brand)
```
POST /deliverables/:deliverableId/request-revision
Auth: Bearer token (Brand)
```

**Request Body:**
```json
{
  "revisionId": "...",
  "comments": "Please adjust the lighting in the first 10 seconds.",
  "requestedChanges": [
    "Fix lighting in 0:00–0:10",
    "Add product close-up shot",
    "Ensure hashtag is visible in caption"
  ]
}
```

**Business Rules:**
- Cannot request revision if `revisionCount >= maxRevisions` (default: 2)
- If limit reached, brand must either approve or raise dispute
- Collaboration status → `REVISION_REQUESTED`

---

### 11.6 Reject Deliverable (Brand)
```
POST /deliverables/:deliverableId/reject
Auth: Bearer token (Brand)
```

**Request Body:**
```json
{
  "revisionId": "...",
  "reason": "Content does not meet brand guidelines — competitor product visible."
}
```

**Side Effects:** Triggers dispute suggestion notification.

---

## 12. Timeline & Messaging APIs

### 12.1 Get Timeline Events
```
GET /collaborations/:collaborationId/timeline
Auth: Bearer token (brand workspace member or creator)
Query: page=1&limit=50&before=<event_id>&tags=message,proposal,payment
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "evt-01ARZ",
      "collaborationId": "...",
      "timestamp": "2026-05-17T10:30:00.000Z",
      "senderId": "user-01XYZ",
      "senderType": "brand",
      "senderName": "Ananya Sharma",
      "senderAvatar": "https://r2.influora.com/avatars/ananya.jpg",
      "tag": "message",
      "content": "Hi Priya! We reviewed your portfolio and think you'd be a great fit.",
      "attachments": [],
      "status": "read"
    },
    {
      "id": "evt-01ARY",
      "collaborationId": "...",
      "timestamp": "2026-05-17T11:00:00.000Z",
      "senderId": "system",
      "senderType": "system",
      "tag": "proposal",
      "metadata": {
        "proposalId": "prop-01ABC",
        "amount": 45000,
        "deliverables": 6,
        "deadline": "2026-06-30",
        "status": "pending"
      },
      "status": "delivered"
    }
  ],
  "meta": { "page": 1, "limit": 50, "total": 28, "hasMore": false }
}
```

---

### 12.2 Send Message
```
POST /collaborations/:collaborationId/messages
Content-Type: multipart/form-data OR application/json
Auth: Bearer token
```

**JSON Body (text only):**
```json
{
  "content": "Just sent you the contract. Please review when you get a chance!",
  "attachments": []
}
```

**Multipart (with files):**
```
content      string
files[]      files (images/PDF, max 25MB each)
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "eventId": "evt-01ARZ",
    "tag": "message",
    "timestamp": "2026-05-17T10:30:00.000Z",
    "status": "delivered"
  }
}
```

**Side Effects:** Broadcasts via WebSocket to all connected clients in the collaboration room.

---

### 12.3 Mark Messages as Read
```
POST /collaborations/:collaborationId/messages/read
Auth: Bearer token
```

**Request Body:**
```json
{ "lastReadEventId": "evt-01ARZ" }
```

---

## 13. Wallet & Payment APIs

### 13.1 Get Wallet Balance
```
GET /wallet
Auth: Bearer token
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "id": "...",
    "balance": 285000,
    "escrowBalance": 450000,
    "pendingPayouts": 75000,
    "currency": "INR",
    "runwayDays": 47,
    "projectedMonthlyBurn": 180000,
    "lastUpdated": "2026-05-17T10:00:00.000Z"
  }
}
```

---

### 13.2 Get Transaction History
```
GET /wallet/transactions
Auth: Bearer token
Query: page=1&limit=20&type=DEPOSIT,ESCROW_HOLD&status=COMPLETED&from=2026-01-01&to=2026-05-17
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "txn-01ARZ",
      "type": "ESCROW_HOLD",
      "amount": 22500,
      "fee": 0,
      "status": "COMPLETED",
      "description": "Escrow hold - Priya Sharma - Signing milestone",
      "referenceType": "COLLABORATION",
      "referenceId": "collab-01XYZ",
      "createdAt": "2026-05-10T14:30:00.000Z",
      "completedAt": "2026-05-10T14:30:01.000Z"
    }
  ],
  "meta": { "page": 1, "limit": 20, "total": 47, "hasMore": true }
}
```

---

### 13.3 Add Funds (Initiate Deposit)
```
POST /wallet/deposit
Auth: Bearer token
```

**Request Body:**
```json
{
  "amount": 200000,
  "currency": "INR",
  "paymentMethod": "UPI",
  "upiId": "brandco@okaxis"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "transactionId": "txn-01ARZ",
    "status": "PENDING",
    "paymentGatewayOrderId": "razorpay_order_01XYZ",
    "amount": 200000,
    "qrCode": "data:image/png;base64,...",
    "upiDeeplink": "upi://pay?pa=influora@okaxis&pn=Influora&am=200000&cu=INR&tn=Wallet+Recharge"
  }
}
```

**Note:** Integrate with Razorpay or Cashfree for Indian payments.

---

### 13.4 Verify Deposit Webhook
```
POST /wallet/deposit/webhook
Auth: Webhook signature header (Razorpay-Signature / Cashfree-Signature)
```

**Payload:** Payment gateway webhook format. On verification:
- Wallet balance updated
- `wallet_transactions` row updated to `COMPLETED`
- Notification sent to brand

---

### 13.5 Release Milestone Payment (Brand)
```
POST /wallet/release-milestone
Auth: Bearer token (Brand — workspace OWNER or ADMIN)
```

**Request Body:**
```json
{
  "milestoneId": "...",
  "collaborationId": "...",
  "confirmAmount": 22500
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "transactionId": "txn-01ARZ",
    "amount": 22500,
    "releasedTo": "Priya Sharma",
    "creatorWalletBalance": 22500,
    "expectedCreatorPayout": "2026-05-19T10:00:00.000Z",
    "platformFee": 1125,
    "creatorReceives": 21375
  }
}
```

**Business Rules:**
- Funds moved from `escrow_balance` → creator wallet
- Platform fee deducted (5% by default)
- Creates `wallet_transactions` for brand (ESCROW_RELEASE) and creator (PAYMENT)
- Timeline event: tag=`payment`, paymentType=`milestone_released`

---

### 13.6 Creator — Withdraw Earnings
```
POST /wallet/withdraw
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "amount": 20000,
  "bankAccountId": "bank-01ARZ"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "transactionId": "txn-01ARZ",
    "amount": 20000,
    "status": "PROCESSING",
    "estimatedArrival": "2026-05-19T18:00:00.000Z",
    "method": "NEFT/IMPS",
    "referenceNumber": "INFLUORA20260517001"
  }
}
```

---

### 13.7 Creator — Add Bank Account
```
POST /wallet/bank-accounts
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "accountHolderName": "Priya Sharma",
  "accountNumber": "1234567890123",
  "ifscCode": "HDFC0001234",
  "accountType": "SAVINGS",
  "isPrimary": true
}
```

---

### 13.8 Creator — Get Payout Records
```
GET /wallet/payouts
Auth: Bearer token (Creator)
Query: page=1&limit=20&from=2026-01-01&to=2026-05-17
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "payout-01ARZ",
      "collaborationId": "collab-01XYZ",
      "campaignTitle": "Summer Fashion 2026",
      "brandName": "BrandCo India",
      "grossAmount": 45000,
      "tds": 450,
      "tdsPercent": 1,
      "platformFee": 4500,
      "platformFeePercent": 10,
      "gstOnPlatformFee": 810,
      "gstPercent": 18,
      "netAmount": 39240,
      "status": "COMPLETED",
      "utrNumber": "NEFT20260510001234",
      "payoutMethod": "BANK",
      "bankLast4": "3456",
      "processedAt": "2026-05-10T18:00:00.000Z"
    }
  ],
  "meta": { "page": 1, "limit": 20, "total": 14, "hasMore": false }
}
```

**Fee Structure (India):**
- TDS: 1% of gross amount (Section 194-O, deducted at source)
- Platform fee: 10% of gross amount
- GST on platform fee: 18% of platform fee
- Creator receives: `grossAmount - TDS - platformFee - GST`

---

### 13.9 Creator — Get Tax Documents
```
GET /wallet/tax-documents
Auth: Bearer token (Creator)
Query: year=2026
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "year": 2026,
    "pan": "AAAAA0000A",
    "documents": [
      {
        "id": "taxdoc-01ARZ",
        "type": "FORM_16A",
        "period": "Q1 (Apr–Jun 2026)",
        "quarter": "Q1",
        "financialYear": "2026-27",
        "totalTdsDeducted": 1200,
        "status": "AVAILABLE",
        "issuedAt": "2026-07-15T00:00:00.000Z",
        "downloadUrl": null
      },
      {
        "id": "taxdoc-01ARY",
        "type": "ANNUAL_STATEMENT",
        "period": "FY 2025-26",
        "financialYear": "2025-26",
        "totalEarnings": 425000,
        "totalTdsDeducted": 4250,
        "status": "AVAILABLE",
        "issuedAt": "2026-06-01T00:00:00.000Z",
        "downloadUrl": null
      }
    ]
  }
}
```

**Document Types:** `FORM_16A` (quarterly TDS certificate) | `ANNUAL_STATEMENT` (full-year earnings statement)

---

### 13.10 Creator — Download Tax Document
```
GET /wallet/tax-documents/:docId/download
Auth: Bearer token (Creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "downloadUrl": "https://r2.influora.com/tax-docs/form16a-q1-2026-priya.pdf?X-Amz-Signature=...",
    "expiresAt": "2026-05-17T10:45:00.000Z",
    "filename": "Form16A_Q1_FY2026-27_AAAAA0000A.pdf"
  }
}
```

**Side Effects:** Redirects to presigned R2 URL (valid 15 minutes). PDF generated by BullMQ job when quarter ends.

---

### 13.11 Creator — Get Payout Settings
```
GET /wallet/payout-settings
Auth: Bearer token (Creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "preferredMethod": "UPI",
    "upiId": "priya@okaxis",
    "preferredBankAccountId": "bank-01ARZ",
    "autoWithdrawEnabled": false,
    "autoWithdrawThreshold": 10000,
    "bankAccounts": [
      {
        "id": "bank-01ARZ",
        "accountHolderName": "Priya Sharma",
        "accountNumber": "****3456",
        "ifscCode": "HDFC0001234",
        "bankName": "HDFC Bank",
        "accountType": "SAVINGS",
        "isPrimary": true,
        "isVerified": true
      }
    ]
  }
}
```

---

### 13.12 Creator — Update Payout Settings
```
PATCH /wallet/payout-settings
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "preferredMethod": "UPI",
  "upiId": "priya@okhdfc",
  "autoWithdrawEnabled": true,
  "autoWithdrawThreshold": 5000
}
```

**Validations:**
- If `upiId` changed → send OTP to registered phone for confirmation before saving
- `autoWithdrawThreshold` must be >= 1000 (minimum payout amount)

---

### 13.14 Escrow Hold (Auto-triggered on Contract Signing)
```
POST /wallet/escrow/hold
Auth: Internal (service-to-service, not direct frontend call)
```

**Request Body:**
```json
{
  "collaborationId": "...",
  "milestoneId": "...",
  "amount": 22500,
  "currency": "INR",
  "reason": "Contract signing milestone escrow"
}
```

**Business Rules:**
- Checks brand wallet has sufficient balance
- If insufficient → returns `402 INSUFFICIENT_FUNDS`, blocks contract activation
- Creates `escrow_holds` row
- Deducts from brand `wallet.balance`, adds to `wallet.escrow_balance`

---

## 14. Dispute APIs

### 14.1 Raise Dispute
```
POST /disputes
Auth: Bearer token (Brand or Creator)
```

**Request Body:**
```json
{
  "collaborationId": "...",
  "type": "DELIVERABLE_QUALITY",
  "title": "Submitted content does not match agreed brief",
  "description": "The creator submitted a reel that prominently features a competitor product despite the exclusivity clause in section 4.2 of the contract. We have screenshots as evidence.",
  "evidenceFiles": []
}
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "disputeId": "disp-01ARZ",
    "status": "OPEN",
    "assignedTo": null,
    "expectedResolutionBy": "2026-05-24T10:00:00.000Z",
    "escrowFrozen": true
  }
}
```

**Side Effects:**
- Collaboration status → `DISPUTED`
- All escrow funds for this collaboration frozen
- Admin notified
- 7-day resolution SLA starts

---

### 14.2 Get Dispute Details
```
GET /disputes/:disputeId
Auth: Bearer token (involved parties or admin)
```

---

### 14.3 Submit Dispute Evidence
```
POST /disputes/:disputeId/evidence
Content-Type: multipart/form-data
Auth: Bearer token
```

**Form Fields:**
```
type         "DOCUMENT" | "IMAGE" | "VIDEO" | "MESSAGE"
title        string
description  string
files[]      files
```

---

### 14.4 Add Dispute Message
```
POST /disputes/:disputeId/messages
Auth: Bearer token
```

**Request Body:**
```json
{ "content": "We can provide the original WhatsApp conversation as evidence." }
```

---

### 14.5 List Disputes
```
GET /disputes
Auth: Bearer token
Query: status=OPEN,UNDER_REVIEW&page=1&limit=20
```

---

## 15. Notification APIs

### 15.1 Get Notifications
```
GET /notifications
Auth: Bearer token
Query: page=1&limit=20&isRead=false
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "notif-01ARZ",
      "type": "PROPOSAL_RECEIVED",
      "title": "New proposal received",
      "message": "Priya Sharma sent a counter-offer on Summer Fashion Campaign",
      "isRead": false,
      "actionUrl": "/brand/chat?collab=collab-01XYZ",
      "data": {
        "collaborationId": "collab-01XYZ",
        "proposalId": "prop-01ABC",
        "amount": 42000
      },
      "createdAt": "2026-05-17T10:30:00.000Z"
    }
  ],
  "meta": { "page": 1, "limit": 20, "total": 7, "hasMore": false, "unreadCount": 4 }
}
```

---

### 15.2 Mark Notification(s) as Read
```
PATCH /notifications/read
Auth: Bearer token
```

**Request Body:**
```json
{
  "notificationIds": ["notif-01ARZ", "notif-01ARY"],
  "markAll": false
}
```

---

### 15.3 Get Unread Count
```
GET /notifications/unread-count
Auth: Bearer token
```

**Response `200`:**
```json
{ "success": true, "data": { "count": 4 } }
```

**This endpoint is polled every 30 seconds by the frontend for the sidebar bell badge.**

---

### 15.4 Update Notification Preferences
```
PATCH /notifications/preferences
Auth: Bearer token
```

**Request Body:**
```json
{
  "email": {
    "proposalReceived": true,
    "contractReady": true,
    "paymentReceived": true,
    "deliverableSubmitted": true,
    "systemAlerts": false
  },
  "push": {
    "proposalReceived": true,
    "contractReady": true,
    "paymentReceived": true
  }
}
```

---

## 16. File Upload APIs

### 16.1 Get Presigned Upload URL (for large files)
```
POST /uploads/presign
Auth: Bearer token
```

**Request Body:**
```json
{
  "filename": "summer-reel-final.mp4",
  "mimeType": "video/mp4",
  "size": 48291839,
  "purpose": "deliverable"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "uploadUrl": "https://influora.r2.cloudflarestorage.com/deliverables/01ARZ.mp4?X-Amz-Signature=...",
    "fileId": "file-01ARZ",
    "key": "deliverables/01ARZ.mp4",
    "expiresAt": "2026-05-17T10:45:00.000Z",
    "maxSize": 524288000
  }
}
```

**Purpose values and allowed types:**
```
"avatar"        → image/jpeg, image/png, image/webp (max 5MB)
"logo"          → image/jpeg, image/png, image/webp (max 5MB)
"deliverable"   → video/mp4, video/mov, image/jpeg, image/png (max 500MB)
"document"      → application/pdf (max 10MB)
"evidence"      → image/jpeg, image/png, video/mp4, application/pdf (max 50MB)
```

---

### 16.2 Confirm Upload Complete
```
POST /uploads/:fileId/confirm
Auth: Bearer token
```

**Request Body:**
```json
{
  "etag": "abc123def456"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "fileId": "file-01ARZ",
    "url": "https://r2.influora.com/deliverables/01ARZ.mp4",
    "thumbnailUrl": "https://r2.influora.com/thumbnails/01ARZ.jpg",
    "status": "ready"
  }
}
```

**Side Effects:** Triggers async thumbnail generation job for video files.

---

### 16.3 Direct Upload (small files, < 10MB)
```
POST /uploads
Content-Type: multipart/form-data
Auth: Bearer token
```

**Form Fields:**
```
file       File
purpose    "avatar" | "logo" | "document" | "evidence"
```

---

## 17. Admin APIs

> All admin endpoints require `userType = ADMIN` in JWT claims.

### 17.1 Platform Dashboard Stats
```
GET /admin/stats
Auth: Bearer token (Admin)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "totalBrands": 523,
    "totalCreators": 51420,
    "activeCampaigns": 187,
    "activeCollaborations": 1243,
    "gmvThisMonth": 18500000,
    "platformFeesThisMonth": 925000,
    "pendingVerifications": 28,
    "openDisputes": 14
  }
}
```

---

### 17.2 Manage User Verification
```
PATCH /admin/workspaces/:workspaceId/verification
Auth: Bearer token (Admin)
```

**Request Body:**
```json
{
  "status": "VERIFIED",
  "notes": "GSTIN and PAN verified. Company registered."
}
```

---

### 17.3 Resolve Dispute
```
POST /admin/disputes/:disputeId/resolve
Auth: Bearer token (Admin)
```

**Request Body:**
```json
{
  "outcome": "FAVOR_BRAND",
  "summary": "Creator violated exclusivity clause. Escrow returned to brand.",
  "actions": [
    "Refund ₹22,500 escrow to brand wallet",
    "Creator profile flagged for review"
  ]
}
```

---

### 17.4 List Pending Verifications
```
GET /admin/verifications/pending
Auth: Bearer token (Admin)
Query: page=1&limit=20
```

---

## 18. Real-Time WebSocket Events

### Connection
```
wss://api.influora.com/ws?token=<access_token>
```

### Client → Server Events

```json
// Subscribe to collaboration deal room
{ "event": "join_room", "data": { "collaborationId": "collab-01XYZ" } }

// Unsubscribe from room
{ "event": "leave_room", "data": { "collaborationId": "collab-01XYZ" } }

// Typing indicator
{ "event": "typing_start", "data": { "collaborationId": "collab-01XYZ" } }
{ "event": "typing_stop",  "data": { "collaborationId": "collab-01XYZ" } }
```

### Server → Client Events

```json
// New timeline event (message, proposal, system update)
{
  "event": "timeline_event",
  "data": {
    "collaborationId": "collab-01XYZ",
    "event": { /* TimelineEvent object */ }
  }
}

// Proposal status changed
{
  "event": "proposal_updated",
  "data": {
    "proposalId": "prop-01ABC",
    "status": "ACCEPTED",
    "collaborationId": "collab-01XYZ"
  }
}

// Contract signed by other party
{
  "event": "contract_signed",
  "data": {
    "contractId": "contract-01DEF",
    "signedBy": "CREATOR",
    "bothSigned": false
  }
}

// Deliverable submitted
{
  "event": "deliverable_submitted",
  "data": {
    "deliverableId": "del-01GHI",
    "collaborationId": "collab-01XYZ",
    "submittedBy": "creator-01XYZ"
  }
}

// Payment released
{
  "event": "payment_released",
  "data": {
    "amount": 22500,
    "currency": "INR",
    "milestoneId": "mile-01JKL",
    "collaborationId": "collab-01XYZ"
  }
}

// Notification
{
  "event": "notification",
  "data": { /* Notification object */ }
}

// Typing indicator from other party
{
  "event": "typing",
  "data": {
    "collaborationId": "collab-01XYZ",
    "userId": "user-01XYZ",
    "isTyping": true
  }
}

// Online presence update
{
  "event": "presence",
  "data": {
    "userId": "user-01XYZ",
    "status": "online",
    "lastSeen": "2026-05-17T10:30:00.000Z"
  }
}
```

---

## 19. Error Codes Reference

| HTTP Status | Error Code | Description |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Request body fails validation |
| 400 | `INVALID_OTP` | Wrong or expired OTP |
| 400 | `INVALID_DATE_RANGE` | End date before start date |
| 400 | `BUDGET_INVALID` | Budget min > max |
| 400 | `REVISION_LIMIT_REACHED` | Max revisions exceeded |
| 401 | `UNAUTHORIZED` | Missing or invalid token |
| 401 | `TOKEN_EXPIRED` | JWT access token expired |
| 401 | `REFRESH_TOKEN_INVALID` | Refresh token invalid/expired |
| 403 | `FORBIDDEN` | Valid token but insufficient permissions |
| 403 | `EMAIL_NOT_VERIFIED` | Email not yet verified |
| 403 | `ACCOUNT_SUSPENDED` | Account suspended by admin |
| 403 | `WORKSPACE_UNVERIFIED` | Action requires verified workspace |
| 403 | `WRONG_USER_TYPE` | Brand using creator endpoint or vice versa |
| 404 | `USER_NOT_FOUND` | User does not exist |
| 404 | `CAMPAIGN_NOT_FOUND` | Campaign does not exist |
| 404 | `COLLABORATION_NOT_FOUND` | Collaboration does not exist |
| 404 | `PROPOSAL_NOT_FOUND` | Proposal does not exist |
| 404 | `CONTRACT_NOT_FOUND` | Contract does not exist |
| 404 | `DELIVERABLE_NOT_FOUND` | Deliverable does not exist |
| 409 | `EMAIL_ALREADY_EXISTS` | Email already registered |
| 409 | `PHONE_ALREADY_EXISTS` | Phone already registered |
| 409 | `DUPLICATE_COLLABORATION` | Creator already in this campaign |
| 409 | `PROPOSAL_ALREADY_RESPONDED` | Proposal already accepted/rejected |
| 409 | `CONTRACT_ALREADY_SIGNED` | User already signed this contract |
| 402 | `INSUFFICIENT_FUNDS` | Wallet balance too low for escrow |
| 422 | `INVALID_TRANSITION` | Status change not allowed (state machine) |
| 422 | `CAMPAIGN_NOT_ACTIVE` | Campaign is not accepting collaborations |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many requests |
| 500 | `INTERNAL_ERROR` | Unexpected server error |

---

## 20. Rate Limiting

| Endpoint Group | Limit | Window |
|---|---|---|
| `POST /auth/*` | 10 requests | per IP per 15 min |
| `POST /auth/*/send-otp` | 3 requests | per phone per 10 min |
| `GET /creators/search` | 100 requests | per user per 1 min |
| `POST /uploads/*` | 20 requests | per user per 5 min |
| All other authenticated | 300 requests | per user per 1 min |
| All other unauthenticated | 60 requests | per IP per 1 min |

**Rate limit headers returned:**
```
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 247
X-RateLimit-Reset: 1716024660
Retry-After: 42
```

---

## 21. Environment Variables

> **Spring Boot** uses `application.yml` + env overrides. Copy `influora-api/.env.example` for local dev.

```bash
# Server (Spring Boot)
SPRING_PROFILES_ACTIVE=dev
SERVER_PORT=8080
# API base path: /api/v1 (see influora-api application.yml)

# MySQL 8 (required)
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/influora?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
SPRING_DATASOURCE_USERNAME=influora
SPRING_DATASOURCE_PASSWORD=<password>
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
# Or single URL: DATABASE_URL=mysql://user:pass@host:3306/influora_prod

# Redis (optional v1)
REDIS_URL=redis://default:pass@upstash.io:6379

# Auth
JWT_ACCESS_SECRET=<256-bit-random-secret>
JWT_REFRESH_SECRET=<256-bit-random-secret>
JWT_ACCESS_EXPIRY=900          # 15 minutes in seconds
JWT_REFRESH_EXPIRY=2592000     # 30 days in seconds

# Cloudflare R2 (required for uploads / deliverable videos)
R2_ACCOUNT_ID=<cloudflare-account-id>
R2_ACCESS_KEY_ID=<r2-access-key>
R2_SECRET_ACCESS_KEY=<r2-secret>
R2_BUCKET_NAME=influora-prod
R2_ENDPOINT=https://<R2_ACCOUNT_ID>.r2.cloudflarestorage.com
R2_PUBLIC_URL=https://r2.influora.com   # custom domain → bucket public access or CDN
R2_PRESIGN_EXPIRY_SECONDS=900            # presigned upload URL TTL (15 min)
R2_MAX_VIDEO_BYTES=524288000             # 500 MB — matches §16 deliverable limit

# MSG91 — see docs/MSG91-EMAIL-OTP.md (email OTP ≠ SMS auth-key)
MSG91_ENABLED=true
MSG91_AUTH_KEY=<sms-and-widget-auth-key>
MSG91_SENDER_ID=INFLRA
MSG91_ROUTE=4
MSG91_WIDGET_ID=<widget-id>
MSG91_TOKEN_AUTH=<email-api-v5-token-from-dashboard>
MSG91_EMAIL_DOMAIN=<verified-sending-domain>
MSG91_FROM_EMAIL=emailer@<your-domain>
MSG91_FROM_NAME=Influora
MSG91_EMAIL_TEMPLATE_ID=otpman
MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID=
MSG91_EMAIL_TEMPLATE_OTP_VARIABLE=otp
MSG91_EMAIL_TRANSACTIONAL_LINK_VARIABLE=magic_link
MSG91_EMAIL_COMPANY_NAME=Influora
MSG91_OTP_TEMPLATE_ID=<sms-otp-template-id-if-used>
MSG91_WELCOME_TEMPLATE_ID=

# Payments (Razorpay)
RAZORPAY_KEY_ID=rzp_live_<key>
RAZORPAY_KEY_SECRET=<secret>
RAZORPAY_WEBHOOK_SECRET=<webhook-secret>

# Platform Config
PLATFORM_FEE_PERCENT=5
ESCROW_HOLD_PERCENT=50         # % of total held on contract sign
MAX_REVISION_COUNT=2
OTP_EXPIRY_SECONDS=300
PASSWORD_RESET_EXPIRY_MINUTES=60

# CORS
ALLOWED_ORIGINS=https://influora.com,https://app.influora.com

# Internal
INTERNAL_API_KEY=<secret-for-service-to-service>
```

---

## 22. Frontend Integration Checklist

Use this checklist when wiring up `src/lib/api.ts` to real backend endpoints.

### Auth Flow

| Action | Current State | API Endpoint |
|---|---|---|
| Brand register | Mock + localStorage | `POST /auth/brand/register` |
| Brand login | Mock + localStorage | `POST /auth/brand/login` |
| Creator OTP send | Mock | `POST /auth/creator/send-otp` |
| Creator OTP verify | Mock | `POST /auth/creator/verify-otp` |
| Forgot password | Broken link | `POST /auth/forgot-password` |
| Logout | localStorage.clear | `POST /auth/logout` |
| Token refresh | None | `POST /auth/refresh` |

### Store Updates Required (`src/lib/store.ts`)

```typescript
// Replace localStorage token pattern with API-backed auth
const useAuthStore = create(
  persist(
    (set) => ({
      user: null,
      workspace: null,
      accessToken: null,
      
      login: async (email, password) => {
        const res = await brandApi.login(email, password);
        set({ 
          user: res.data.user, 
          workspace: res.data.workspace,
          accessToken: res.data.accessToken
        });
        // Store refresh token in httpOnly cookie (not localStorage)
      },
      
      logout: async () => {
        await brandApi.logout();
        set({ user: null, workspace: null, accessToken: null });
      }
    }),
    {
      name: 'influora-auth',
      partialize: (state) => ({
        // DO NOT persist accessToken — re-fetch on app load using refresh token
        user: state.user,      // Only persist non-sensitive display data
        workspace: state.workspace,
      })
    }
  )
);
```

### TanStack Query Keys Convention

```typescript
// src/lib/query-keys.ts — use these throughout the app
export const queryKeys = {
  campaigns: {
    all: ['campaigns'],
    list: (filters) => ['campaigns', 'list', filters],
    detail: (id) => ['campaigns', id],
    analytics: (id) => ['campaigns', id, 'analytics'],
  },
  creators: {
    search: (filters) => ['creators', 'search', filters],
    detail: (id) => ['creators', id],
  },
  collaborations: {
    all: ['collaborations'],
    detail: (id) => ['collaborations', id],
    timeline: (id) => ['collaborations', id, 'timeline'],
  },
  proposals: {
    history: (collabId) => ['proposals', 'history', collabId],
  },
  contracts: {
    detail: (id) => ['contracts', id],
  },
  wallet: {
    balance: ['wallet', 'balance'],
    transactions: (filters) => ['wallet', 'transactions', filters],
  },
  notifications: {
    list: (filters) => ['notifications', filters],
    unreadCount: ['notifications', 'unread-count'],
  },
};
```

### API Client Update (`src/lib/api.ts`)

```typescript
// Replace BASE_URL
const BASE_URL = import.meta.env.VITE_API_URL + '/v1';

// Add token interceptor
apiClient.setToken(useAuthStore.getState().accessToken);

// Add 401 interceptor for automatic token refresh
// When API returns 401 TOKEN_EXPIRED:
//   1. Call POST /auth/refresh with refresh token from cookie
//   2. Update accessToken in store
//   3. Retry original request
//   4. If refresh also fails → logout and redirect to /login
```

### Environment Variables (Frontend)

```bash
# .env.development
VITE_API_URL=http://localhost:8000/api
VITE_WS_URL=ws://localhost:8000/ws
VITE_USE_MOCK=true

# .env.production
VITE_API_URL=https://api.influora.com/api
VITE_WS_URL=wss://api.influora.com/ws
VITE_USE_MOCK=false
```

### WebSocket Setup (Frontend)

```typescript
// src/lib/websocket.ts
const ws = new WebSocket(`${import.meta.env.VITE_WS_URL}?token=${accessToken}`);

ws.onmessage = (event) => {
  const { event: eventType, data } = JSON.parse(event.data);
  
  switch (eventType) {
    case 'timeline_event':
      // Append to TanStack Query cache
      queryClient.setQueryData(
        queryKeys.collaborations.timeline(data.collaborationId),
        (old) => ({ ...old, data: [...(old?.data ?? []), data.event] })
      );
      break;
    case 'notification':
      // Increment unread count in store
      useNotificationStore.getState().addNotification(data);
      break;
    case 'payment_released':
      // Invalidate wallet balance
      queryClient.invalidateQueries(queryKeys.wallet.balance);
      break;
  }
};
```

---

---

## 23. Creator Onboarding APIs

> These endpoints track the 5-step creator onboarding flow: Connect Socials → Verify Identity → Build Profile → Setup Payout → How It Works  
> Source: `src/pages/creator-onboarding.tsx`

### 23.1 Get Onboarding Status
```
GET /onboarding/creator/status
Auth: Bearer token (Creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "currentStep": 2,
    "completedSteps": [1],
    "isComplete": false,
    "steps": [
      { "step": 1, "key": "connect_socials", "label": "Connect Social Accounts", "completed": true },
      { "step": 2, "key": "verify_identity", "label": "Verify Identity", "completed": false },
      { "step": 3, "key": "build_profile", "label": "Build Your Profile", "completed": false },
      { "step": 4, "key": "setup_payout", "label": "Setup Payout", "completed": false },
      { "step": 5, "key": "how_it_works", "label": "How It Works", "completed": false }
    ]
  }
}
```

---

### 23.2 Complete Onboarding Step
```
POST /onboarding/creator/complete-step
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "step": 3,
  "stepKey": "build_profile",
  "data": {
    "displayName": "Priya Creates",
    "bio": "Fashion & lifestyle creator from Mumbai",
    "city": "Mumbai",
    "verticals": ["Fashion & Lifestyle", "Beauty & Skincare"],
    "languages": ["Hindi", "English"],
    "rateMin": 25000,
    "rateMax": 75000
  }
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "completedStep": 3,
    "nextStep": 4,
    "isOnboardingComplete": false
  }
}
```

**Side Effects on step 5 completion:**
- Creator profile status → `ACTIVE`
- `user.onboardingCompleted = true`
- Welcome email sent
- Notification: "Your profile is live!"

---

### 23.3 Submit Identity Verification (Creator)
```
POST /onboarding/creator/verify-identity
Content-Type: multipart/form-data
Auth: Bearer token (Creator)
```

**Form Fields:**
```
aadhaarNumber     string (12 digits)
panNumber         string (10 chars, e.g. AAAAA0000A)
aadhaarDocument   File (PDF/JPG, max 5MB) — front+back
panDocument       File (PDF/JPG, max 5MB)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "verificationId": "kyc-01ARZ",
    "status": "PENDING",
    "message": "Documents submitted. Verification takes 24–48 hours.",
    "submittedAt": "2026-05-17T10:00:00.000Z"
  }
}
```

---

## 24. Creator Platform Connection APIs

> Connects Instagram and YouTube accounts via OAuth.  
> Source: `src/pages/creator-onboarding.tsx`, `src/pages/creator-profile.tsx`

### 24.1 Initiate Platform OAuth
```
GET /creator/platforms/:platform/connect
Auth: Bearer token (Creator)
```

**Path Params:** `platform` = `instagram` | `youtube`

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "oauthUrl": "https://api.instagram.com/oauth/authorize?client_id=...&redirect_uri=...&scope=...",
    "state": "csrf-state-token-01ARZ",
    "expiresIn": 600
  }
}
```

**Note:** Frontend redirects user to `oauthUrl`. Platform redirects back to `GET /creator/platforms/:platform/callback?code=...&state=...`

---

### 24.2 OAuth Callback Handler
```
GET /creator/platforms/:platform/callback
Auth: Query state param (verified server-side against stored state)
Query: code=<auth_code>&state=<csrf_state>
```

**Response:** Redirects to `/creator/onboarding?platform=instagram&status=connected` or `?status=error&reason=...`

**Side Effects:**
- Exchanges `code` for access + refresh tokens
- Fetches follower count, engagement rate, handle from platform API
- Creates/updates `platform_stats` row
- Marks platform connection step complete

---

### 24.3 Get Connected Platforms
```
GET /creator/platforms
Auth: Bearer token (Creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "platform": "INSTAGRAM",
      "handle": "@priya_creates",
      "profileUrl": "https://instagram.com/priya_creates",
      "followers": 125000,
      "engagementRate": 4.2,
      "isVerified": true,
      "connectedAt": "2026-01-15T10:00:00.000Z",
      "lastSyncedAt": "2026-05-17T06:00:00.000Z"
    },
    {
      "platform": "YOUTUBE",
      "handle": "Priya Creates",
      "profileUrl": "https://youtube.com/@priyacreates",
      "followers": 50000,
      "engagementRate": 3.8,
      "avgViews": 25000,
      "isVerified": true,
      "connectedAt": "2026-01-20T10:00:00.000Z",
      "lastSyncedAt": "2026-05-17T06:00:00.000Z"
    }
  ]
}
```

---

### 24.4 Disconnect Platform
```
DELETE /creator/platforms/:platform
Auth: Bearer token (Creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": { "message": "Instagram disconnected successfully" }
}
```

**Business Rules:** Cannot disconnect last platform if creator has active collaborations.

---

### 24.5 Sync Platform Stats (Refresh)
```
POST /creator/platforms/:platform/sync
Auth: Bearer token (Creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "platform": "INSTAGRAM",
    "followersBefore": 124800,
    "followersAfter": 125000,
    "engagementRateBefore": 4.1,
    "engagementRateAfter": 4.2,
    "syncedAt": "2026-05-17T10:30:00.000Z"
  }
}
```

**Rate limit:** Max 1 sync per platform per hour per user.

---

## 25. Creator Profile, Badges & Stats APIs

> Source: `src/pages/creator-profile.tsx`

### 25.1 Get My Creator Profile
```
GET /creator/profile
Auth: Bearer token (Creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "id": "...",
    "displayName": "Priya Creates",
    "name": "Priya Sharma",
    "email": "priya@creator.com",
    "phone": "+91 98765 43210",
    "bio": "Fashion & lifestyle content creator...",
    "city": "Mumbai",
    "avatarUrl": "https://r2.influora.com/avatars/priya.jpg",
    "isVerified": true,
    "verifiedSince": "2025-01-15",
    "verticals": ["Fashion & Lifestyle", "Beauty & Skincare", "Travel & Adventure"],
    "languages": ["Hindi", "English", "Marathi"],
    "rateRange": { "min": 25000, "max": 75000, "currency": "INR" },
    "platforms": [ /* see 24.3 */ ],
    "stats": {
      "totalCollabs": 45,
      "completedOnTime": 43,
      "avgRating": 4.8,
      "totalEarnings": 425000,
      "responseRate": 95,
      "repeatBrands": 12,
      "onTimeDeliveryRate": 95.6
    },
    "badges": [ /* see 25.3 */ ]
  }
}
```

---

### 25.2 Update My Creator Profile
```
PATCH /creator/profile
Auth: Bearer token (Creator)
```

**Request Body (all fields optional):**
```json
{
  "displayName": "Priya Creates",
  "bio": "Updated bio text...",
  "city": "Mumbai",
  "rateMin": 30000,
  "rateMax": 80000,
  "verticals": ["Fashion & Lifestyle", "Travel & Adventure"],
  "languages": ["Hindi", "English"]
}
```

---

### 25.3 Get Creator Badges
```
GET /creators/:creatorId/badges
Auth: Bearer token
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "badge-top-creator",
      "title": "Top Creator",
      "description": "Top 5% engagement rate on platform",
      "iconKey": "star",
      "earnedAt": "2026-03-01T00:00:00.000Z",
      "criteria": "engagementRate > p95"
    },
    {
      "id": "badge-fast-responder",
      "title": "Fast Responder",
      "description": "Average response time under 2 hours",
      "iconKey": "clock",
      "earnedAt": "2026-02-15T00:00:00.000Z",
      "criteria": "avgResponseTimeHours < 2"
    },
    {
      "id": "badge-otd",
      "title": "On-Time Delivery",
      "description": "95%+ on-time delivery rate",
      "iconKey": "check-circle",
      "earnedAt": "2026-04-01T00:00:00.000Z",
      "criteria": "onTimeDeliveryRate >= 95"
    },
    {
      "id": "badge-brand-favorite",
      "title": "Brand Favorite",
      "description": "10+ repeat collaborations with same brands",
      "iconKey": "award",
      "earnedAt": "2026-05-01T00:00:00.000Z",
      "criteria": "repeatBrands >= 10"
    }
  ]
}
```

**Note:** Badges are auto-computed nightly by a BullMQ job that scans all creator stats. No manual grant/revoke except by admin.

---

### 25.4 Get Creator Public Stats
```
GET /creators/:creatorId/stats
Auth: Bearer token
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "totalCollabs": 45,
    "completedOnTime": 43,
    "onTimeDeliveryRate": 95.6,
    "avgRating": 4.8,
    "responseRate": 95,
    "avgResponseTimeHours": 1.8,
    "repeatBrands": 12,
    "totalReviews": 38
  }
}
```

---

## 26. Creator Ratings & Reviews APIs

> Post-collaboration reviews from brands about creators.  
> Source: `src/pages/creator-profile.tsx` (avgRating, totalCollabs displayed)

### 26.1 Submit Review (Brand → Creator)
```
POST /collaborations/:collaborationId/review
Auth: Bearer token (Brand)
```

**Request Body:**
```json
{
  "rating": 5,
  "title": "Excellent collaborator — highly recommend!",
  "review": "Priya delivered exceptional content ahead of schedule. Her audience engagement was outstanding and she understood our brand voice perfectly.",
  "categories": {
    "communication": 5,
    "contentQuality": 5,
    "timeliness": 5,
    "professionalism": 5
  }
}
```

**Validations:**
- `rating` 1–5 integer
- Review can only be submitted once per collaboration
- Only after collaboration status = `COMPLETED`
- Review window closes 30 days after completion

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "reviewId": "review-01ARZ",
    "createdAt": "2026-05-17T10:00:00.000Z"
  }
}
```

**Side Effects:**
- Creator's `avgRating` and `totalReviews` recomputed
- Badge eligibility rechecked
- Creator notified

---

### 26.2 Get Creator Reviews
```
GET /creators/:creatorId/reviews
Auth: Bearer token
Query: page=1&limit=10&sortBy=rating|createdAt&sortOrder=desc
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "review-01ARZ",
      "rating": 5,
      "title": "Excellent collaborator!",
      "review": "Priya delivered...",
      "categories": { "communication": 5, "contentQuality": 5, "timeliness": 5, "professionalism": 5 },
      "brandName": "BrandCo India",
      "brandLogoUrl": "https://r2.influora.com/logos/brandco.jpg",
      "campaignTitle": "Summer Fashion 2026",
      "createdAt": "2026-05-17T10:00:00.000Z"
    }
  ],
  "meta": { "page": 1, "limit": 10, "total": 38, "hasMore": true, "avgRating": 4.8 }
}
```

---

## 27. Campaign Bids APIs

> Creators can discover and apply/bid on public campaigns. Brands review, accept, or reject bids.  
> Source: `src/pages/brand-campaign-detail.tsx` (totalBids, pendingBids stats)

### 27.1 Creator — Browse Open Campaigns
```
GET /campaigns/open
Auth: Bearer token (Creator)
Query: platforms=INSTAGRAM&categories=fashion&minBudget=20000&maxBudget=100000&page=1&limit=20
```

**Response `200`:** List of public (`isPrivate = false`, `status = ACTIVE`) campaigns with their brief details.

---

### 27.2 Creator — Submit Bid/Application
```
POST /campaigns/:campaignId/bids
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "proposedRate": 42000,
  "currency": "INR",
  "coverLetter": "Hi! I'd love to collaborate on the Summer Fashion campaign. I've worked with 5 similar fashion brands and my Fashion Reels average 120K views...",
  "proposedDeliverables": [
    { "contentType": "REEL", "platform": "INSTAGRAM", "quantity": 2 },
    { "contentType": "STORY", "platform": "INSTAGRAM", "quantity": 4 }
  ],
  "availableFrom": "2026-06-10",
  "portfolioLinks": ["https://instagram.com/p/example1", "https://instagram.com/p/example2"]
}
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "bidId": "bid-01ARZ",
    "status": "PENDING",
    "submittedAt": "2026-05-17T10:00:00.000Z"
  }
}
```

**Business Rules:**
- Creator can only bid once per campaign (upsert allowed if `PENDING`)
- Campaign must be `ACTIVE` and not past `applicationDeadline`
- Creates `collaborations` row with `source = APPLICATION` and `status = APPLIED`

---

### 27.3 Brand — List Campaign Bids
```
GET /campaigns/:campaignId/bids
Auth: Bearer token (Brand)
Query: status=PENDING,SHORTLISTED&page=1&limit=20&sortBy=rate|createdAt
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "bidId": "bid-01ARZ",
      "collaborationId": "collab-01XYZ",
      "creator": {
        "id": "...",
        "displayName": "Priya Creates",
        "avatarUrl": "...",
        "totalFollowers": 185000,
        "engagementRate": 4.8,
        "avgRating": 4.8,
        "city": "Mumbai",
        "isVerified": true
      },
      "proposedRate": 42000,
      "coverLetter": "Hi! I'd love to...",
      "proposedDeliverables": [...],
      "portfolioLinks": [...],
      "status": "PENDING",
      "submittedAt": "2026-05-17T10:00:00.000Z"
    }
  ],
  "meta": {
    "page": 1, "limit": 20, "total": 34, "hasMore": true,
    "summary": { "total": 34, "pending": 22, "shortlisted": 8, "accepted": 3, "rejected": 1 }
  }
}
```

---

### 27.4 Brand — Review Bid (Accept / Reject / Shortlist)
```
PATCH /campaigns/:campaignId/bids/:bidId
Auth: Bearer token (Brand)
```

**Request Body:**
```json
{
  "action": "accept",
  "message": "Hi Priya! We'd love to work with you. Moving to negotiation phase.",
  "offeredRate": 45000
}
```

`action`: `"accept"` | `"reject"` | `"shortlist"`

**Side Effects on `accept`:**
- Collaboration status → `IN_NEGOTIATION`
- Bid status → `ACCEPTED`
- Proposal auto-created with `offeredRate` if provided
- Creator notified

**Side Effects on `reject`:**
- Collaboration status → `CANCELLED`
- Creator notified with brand message

---

### 27.5 Creator — Get My Bids / Applications
```
GET /creator/bids
Auth: Bearer token (Creator)
Query: status=PENDING,ACCEPTED&page=1&limit=20
```

**Response `200`:** List of bids with campaign summary and current status.

---

## 28. Physical Product & Shipment APIs

> For campaigns where brand ships physical products to creator before content creation.  
> Source: `src/components/brand/deal-room/shipment-form.tsx`, `src/components/creator/deal-room/receipt-confirmation.tsx`, `src/components/creator/deal-room/shipping-address-form.tsx`

### 28.1 Creator — Save Shipping Address
```
POST /users/me/shipping-address
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "fullName": "Priya Sharma",
  "phone": "+91 98765 43210",
  "addressLine1": "Flat 12B, Sunshine Apartments",
  "addressLine2": "Near Bandra Station",
  "city": "Mumbai",
  "state": "Maharashtra",
  "pincode": "400050",
  "landmark": "Opposite HDFC Bank"
}
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "addressId": "addr-01ARZ",
    "createdAt": "2026-05-17T10:00:00.000Z"
  }
}
```

---

### 28.2 Creator — Get Shipping Address
```
GET /users/me/shipping-address
Auth: Bearer token (Creator)
```

**Response `200`:** Current saved shipping address object, or `null` if not set.

---

### 28.3 Creator — Update Shipping Address
```
PATCH /users/me/shipping-address
Auth: Bearer token (Creator)
```

**Request Body:** Same fields as 28.1, all optional (partial update).

---

### 28.4 Brand — Create Shipment
```
POST /collaborations/:collaborationId/shipments
Auth: Bearer token (Brand)
```

**Request Body:**
```json
{
  "items": [
    { "name": "Summer Collection Dress — Size M", "quantity": 1 },
    { "name": "Brand Lookbook 2026", "quantity": 2 }
  ],
  "courier": "Delhivery",
  "trackingNumber": "DL1234567890",
  "trackingUrl": "https://www.delhivery.com/track/DL1234567890",
  "estimatedDelivery": "2026-05-22",
  "notesForCreator": "Please unbox on camera for authentic reaction content!"
}
```

**Supported couriers:** `Delhivery` | `Blue Dart` | `DTDC` | `Ekart` | `Xpressbees` | `Other`

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "shipmentId": "ship-01ARZ",
    "status": "SHIPPED",
    "createdAt": "2026-05-17T10:00:00.000Z"
  }
}
```

**Side Effects:**
- Timeline event: tag=`shipment`, metadata.trackingNumber, metadata.courier
- Creator notified: "Brand has shipped your product package!"

---

### 28.5 Get Shipment Status
```
GET /collaborations/:collaborationId/shipments
Auth: Bearer token (Brand or Creator)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "shipmentId": "ship-01ARZ",
    "status": "SHIPPED",
    "courier": "Delhivery",
    "trackingNumber": "DL1234567890",
    "trackingUrl": "https://www.delhivery.com/track/DL1234567890",
    "estimatedDelivery": "2026-05-22",
    "items": [
      { "name": "Summer Collection Dress — Size M", "quantity": 1 }
    ],
    "notesForCreator": "Please unbox on camera...",
    "shippedAt": "2026-05-17T10:00:00.000Z",
    "deliveredAt": null,
    "receiptConfirmedAt": null,
    "receiptCondition": null
  }
}
```

---

### 28.6 Creator — Confirm Product Receipt
```
POST /collaborations/:collaborationId/shipments/:shipmentId/receipt
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "condition": "good",
  "notes": "Package arrived in perfect condition. Ready to start creating!"
}
```

`condition`: `"good"` | `"damaged"` | `"wrong_item"`

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "receiptId": "receipt-01ARZ",
    "shipmentStatus": "DELIVERED",
    "collaborationUnpaused": true,
    "confirmedAt": "2026-05-22T14:30:00.000Z"
  }
}
```

**Side Effects on `good`:**
- Shipment status → `DELIVERED`
- Timeline event: tag=`shipment`, metadata.condition=`good`
- Brand notified: "Creator confirmed receipt"

**Side Effects on `damaged` or `wrong_item`:**
- Shipment flagged, collaboration paused
- Brand and admin notified
- Brand must respond within 48 hours (re-ship or compensate)
- Timeline event: tag=`system`, priority=`urgent`

---

## 29. Contract Clause Comments APIs

> Inline comments on individual contract clauses during review.  
> Source: `src/components/brand/contracts/contracts-and-deliverables.tsx`

### 29.1 Add Comment to Clause
```
POST /contracts/:contractId/clauses/:clauseId/comments
Auth: Bearer token (Brand or Creator)
```

**Request Body:**
```json
{
  "text": "Can we extend the exclusivity period from 3 months to 6 months?"
}
```

**Response `201`:**
```json
{
  "success": true,
  "data": {
    "commentId": "comment-01ARZ",
    "clauseId": "clause-01XYZ",
    "user": {
      "id": "...",
      "displayName": "Ananya Sharma",
      "userType": "BRAND"
    },
    "text": "Can we extend the exclusivity period from 3 months to 6 months?",
    "resolved": false,
    "createdAt": "2026-05-17T10:00:00.000Z"
  }
}
```

**Side Effects:**
- Other party notified: "New comment on contract clause"
- Timeline event: tag=`contract`, metadata.action=`clause_commented`

---

### 29.2 Resolve Clause Comment
```
PATCH /contracts/:contractId/clauses/:clauseId/comments/:commentId/resolve
Auth: Bearer token
```

**Request Body:**
```json
{
  "resolved": true
}
```

**Business Rules:** Only the comment author or contract owner can resolve.

---

### 29.3 Get All Clause Comments
```
GET /contracts/:contractId/clauses/:clauseId/comments
Auth: Bearer token
```

**Response `200`:** Array of comment objects sorted by `createdAt` asc, with `resolved` flag.

---

## 30. Brand Payment Methods & Auto-Recharge APIs

> Source: `src/pages/brand-settings.tsx` — Payments tab

### 30.1 Get Payment Methods
```
GET /workspaces/:workspaceId/payment-methods
Auth: Bearer token (OWNER or ADMIN)
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "id": "pm-01ARZ",
      "type": "CARD",
      "label": "Visa •••• 4242",
      "brand": "visa",
      "last4": "4242",
      "expiryMonth": 12,
      "expiryYear": 2028,
      "isDefault": true,
      "addedAt": "2026-01-15T10:00:00.000Z"
    },
    {
      "id": "pm-01ARY",
      "type": "BANK_TRANSFER",
      "label": "HDFC Bank — NEFT/RTGS",
      "bankName": "HDFC Bank",
      "accountLast4": "3456",
      "isDefault": false,
      "addedAt": "2026-02-10T10:00:00.000Z"
    }
  ]
}
```

---

### 30.2 Add Payment Method
```
POST /workspaces/:workspaceId/payment-methods
Auth: Bearer token (OWNER or ADMIN)
```

**Request Body:**
```json
{
  "type": "CARD",
  "gatewayToken": "razorpay_token_abc123",
  "setAsDefault": true
}
```

**Note:** Payment method tokenization handled by Razorpay.js on the frontend. Backend only stores the gateway-issued token, never raw card numbers.

---

### 30.3 Delete Payment Method
```
DELETE /workspaces/:workspaceId/payment-methods/:paymentMethodId
Auth: Bearer token (OWNER or ADMIN)
```

**Business Rules:** Cannot delete the default payment method if other methods exist. Cannot delete if there are pending transactions.

---

### 30.4 Set Default Payment Method
```
PATCH /workspaces/:workspaceId/payment-methods/:paymentMethodId/default
Auth: Bearer token (OWNER or ADMIN)
```

**Response `200`:**
```json
{
  "success": true,
  "data": { "defaultPaymentMethodId": "pm-01ARY" }
}
```

---

### 30.5 Get Auto-Recharge Settings
```
GET /workspaces/:workspaceId/auto-recharge
Auth: Bearer token (OWNER or ADMIN)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "enabled": false,
    "triggerBalance": 50000,
    "rechargeAmount": 200000,
    "paymentMethodId": "pm-01ARZ"
  }
}
```

**Behaviour:** When workspace wallet balance drops below `triggerBalance`, automatically charges `rechargeAmount` to the saved payment method.

---

### 30.6 Update Auto-Recharge Settings
```
PATCH /workspaces/:workspaceId/auto-recharge
Auth: Bearer token (OWNER or ADMIN)
```

**Request Body:**
```json
{
  "enabled": true,
  "triggerBalance": 100000,
  "rechargeAmount": 300000,
  "paymentMethodId": "pm-01ARZ"
}
```

**Validations:**
- `rechargeAmount` >= 10000 (minimum recharge)
- `triggerBalance` < `rechargeAmount`
- `paymentMethodId` must belong to this workspace

---

## 31. Account Security APIs

> Source: `src/pages/creator-settings.tsx`, `src/pages/brand-settings.tsx`

### 31.1 Change Password (Authenticated)
```
PATCH /users/me/password
Auth: Bearer token
```

**Request Body:**
```json
{
  "currentPassword": "OldSecurePass@123",
  "newPassword": "NewSecurePass@456",
  "confirmNewPassword": "NewSecurePass@456"
}
```

**Validations:**
- `currentPassword` must match stored hash
- `newPassword` must be min 8 chars, include uppercase, lowercase, digit, special char
- `newPassword` cannot match `currentPassword`
- All active refresh tokens except current session are invalidated on success

**Response `200`:**
```json
{
  "success": true,
  "data": { "message": "Password updated successfully. Other sessions have been logged out." }
}
```

---

### 31.2 Initiate Phone Number Change (Creator)
```
POST /users/me/phone/change
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "newPhoneNumber": "9876500000",
  "countryCode": "+91"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "message": "OTP sent to new phone number",
    "maskedPhone": "+91 ****00000",
    "expiresIn": 300
  }
}
```

---

### 31.3 Verify Phone Number Change (Creator)
```
POST /users/me/phone/verify
Auth: Bearer token (Creator)
```

**Request Body:**
```json
{
  "newPhoneNumber": "9876500000",
  "countryCode": "+91",
  "otp": "482910"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": { "phoneNumber": "+91 98765 00000", "phoneVerified": true }
}
```

---

### 31.4 Setup 2FA (TOTP)
```
POST /auth/2fa/setup
Auth: Bearer token
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "secret": "JBSWY3DPEHPK3PXP",
    "qrCodeUrl": "otpauth://totp/Influora:ananya@brandco.com?secret=JBSWY3DPEHPK3PXP&issuer=Influora",
    "qrCodeImage": "data:image/png;base64,...",
    "backupCodes": [
      "ABCD-EFGH", "IJKL-MNOP", "QRST-UVWX",
      "YZAB-CDEF", "GHIJ-KLMN", "OPQR-STUV"
    ]
  }
}
```

**Note:** 2FA is not active yet — must be confirmed with `POST /auth/2fa/verify` first.

---

### 31.5 Confirm & Activate 2FA
```
POST /auth/2fa/verify
Auth: Bearer token
```

**Request Body:**
```json
{
  "totpCode": "482910"
}
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "twoFaEnabled": true,
    "enabledAt": "2026-05-17T10:30:00.000Z"
  }
}
```

---

### 31.6 Disable 2FA
```
DELETE /auth/2fa
Auth: Bearer token
```

**Request Body:**
```json
{
  "totpCode": "482910",
  "password": "CurrentPassword@123"
}
```

Both `totpCode` and `password` required to disable. Prevents unauthorized disabling if session is compromised.

---

### 31.7 Delete Account
```
DELETE /users/me
Auth: Bearer token
```

**Request Body:**
```json
{
  "password": "CurrentPassword@123",
  "confirmText": "DELETE MY ACCOUNT",
  "reason": "No longer using the platform"
}
```

**Validations:**
- `confirmText` must exactly equal `"DELETE MY ACCOUNT"`
- `password` must match (or OTP for creator accounts with phone-only auth)
- Cannot delete if there are active collaborations with pending payments or open disputes

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "message": "Account scheduled for deletion.",
    "scheduledAt": "2026-05-17T10:30:00.000Z",
    "completionDate": "2026-05-31T10:30:00.000Z",
    "cancellationDeadline": "2026-05-20T10:30:00.000Z"
  }
}
```

**Business Rules:**
- Soft delete — account marked `status = DEACTIVATED`, not immediately purged
- 14-day grace period: user can cancel deletion by logging back in
- After grace period: PII purged, content anonymized, financial records retained 7 years (GST compliance)
- All active sessions invalidated immediately

---

## 32. Utility & Miscellaneous APIs

### 32.1 Check Workspace Slug Availability
```
GET /workspaces/slug-check
Auth: None
Query: slug=brandco-india
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "slug": "brandco-india",
    "available": false,
    "suggestions": ["brandco-india-pvt", "brandco-in", "brandco-india-2026"]
  }
}
```

**Used during brand registration** to validate slug uniqueness in real-time (debounced 400ms).

---

### 32.2 Resend Email Verification (MSG91 OTP)
```
POST /auth/resend-verification
Auth: Bearer token (or email in body for unauthenticated flow)
```

**Request Body:**
```json
{ "email": "ananya@brandco.com" }
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "message": "Verification code sent to your email.",
    "nextAllowedAt": "2026-05-17T10:35:00.000Z"
  }
}
```

**Side effects:** New 6-digit OTP stored (hashed); previous OTP invalidated; email sent via **MSG91 Email** template (`MSG91_EMAIL_TEMPLATE_ID` / `otpman`) with variable `MSG91_EMAIL_TEMPLATE_OTP_VARIABLE` (default `otp`).

**Rate limit:** Max 3 resends per email per hour.

---

### 32.3 Delete Uploaded File
```
DELETE /uploads/:fileId
Auth: Bearer token (file owner only)
```

**Response `200`:**
```json
{ "success": true, "data": { "message": "File deleted" } }
```

**Business Rules:**
- Soft-deletes DB record, schedules R2 object for deletion in 24h (grace period for in-flight references)
- Cannot delete files referenced by active contracts or signed deliverables

---

### 32.4 Generate Contract PDF (Server-Side)
```
POST /contracts/:contractId/generate-pdf
Auth: Internal (auto-triggered after proposal acceptance) OR Bearer token (manual re-generate)
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "jobId": "job-pdf-01ARZ",
    "status": "QUEUED",
    "estimatedReadyAt": "2026-05-17T10:32:00.000Z"
  }
}
```

**Side Effects:**
- Enqueues BullMQ job: Puppeteer renders contract HTML → PDF → uploads to R2 at `contracts/collab-{id}/contract-{id}.pdf`
- Updates `contracts.pdfUrl` on completion
- WebSocket event `contract_pdf_ready` sent to both parties

---

### 32.5 Creator — Get Inbox (Invitations + Applications Feed)
```
GET /creator/inbox
Auth: Bearer token (Creator)
Query: type=all|invitation|application&status=pending|active|closed&page=1&limit=20
```

**Response `200`:**
```json
{
  "success": true,
  "data": [
    {
      "type": "INVITATION",
      "collaborationId": "collab-01XYZ",
      "collaborationStatus": "INVITED",
      "campaign": {
        "id": "...",
        "title": "Summer Fashion 2026",
        "brandName": "BrandCo India",
        "brandLogoUrl": "...",
        "budget": { "min": 50000, "max": 200000 },
        "platforms": ["INSTAGRAM"],
        "deadline": "2026-05-25"
      },
      "message": "Hi Priya! We'd love to work with you...",
      "receivedAt": "2026-05-17T10:00:00.000Z",
      "expiresAt": "2026-05-24T23:59:59.000Z"
    },
    {
      "type": "APPLICATION",
      "collaborationId": "collab-01XYA",
      "collaborationStatus": "APPLIED",
      "bidId": "bid-01ARZ",
      "campaign": {
        "id": "...",
        "title": "Beauty Launch Q2",
        "brandName": "GlowCo",
        "brandLogoUrl": "...",
        "budget": { "min": 30000, "max": 80000 },
        "platforms": ["INSTAGRAM", "YOUTUBE"]
      },
      "proposedRate": 45000,
      "appliedAt": "2026-05-16T14:00:00.000Z"
    }
  ],
  "meta": { "page": 1, "limit": 20, "total": 12, "hasMore": false }
}
```

---

### 32.6 Export Collaboration Summary (PDF)
```
GET /collaborations/:collaborationId/export
Auth: Bearer token
Query: format=pdf
```

**Response `200`:**
```json
{
  "success": true,
  "data": {
    "downloadUrl": "https://r2.influora.com/exports/collab-summary-01XYZ.pdf?X-Amz-Signature=...",
    "expiresAt": "2026-05-17T10:45:00.000Z",
    "filename": "Influora_Collaboration_BrandCo_PriyaSharma_2026.pdf"
  }
}
```

**PDF contents:** Collaboration timeline, contract summary, deliverables list with status, payments breakdown, signatures. Generated by BullMQ job using Puppeteer.

---

## 33. Brand Flow API Alignment (`src/lib/api.ts`)

> **Why this section exists.** `src/lib/api.ts` is the *single source of truth for the endpoints the app actually calls* (see its file header). This section reconciles that live client with the spec above for the **brand flow**. **Where a path or shape here differs from an earlier section, this section is canonical** — the backend must implement these exact routes/shapes, or `src/lib/api.ts` must be changed in lockstep. Sections 23–32 remain the design reference for the underlying domain model (collaborations, proposals, milestones).
>
> Audit date: 2026-05-23. Mock mode is active until `VITE_API_MODE=live`.

### 33.0 Alignment Summary

| `api.ts` call | Live method + path | Spec section | Status |
|---|---|---|---|
| `auth.brandLogin` | `POST /auth/brand/login` | 4.2 | ✅ aligned (JSDoc `/auth/login` is stale, cosmetic) |
| `auth.brandRegister` | `POST /auth/brand/register` | 4.1 | ✅ aligned |
| `auth.forgotPassword` | `POST /auth/forgot-password` | 4.7 | ✅ aligned |
| `auth.logout` | `POST /auth/logout` | 4.6 | ✅ aligned |
| `onboarding.saveBrandCompany` | `POST /onboarding/brand/company` | **33.1** | ➕ added here (was missing) |
| `onboarding.completeBrand` | `POST /onboarding/brand/complete` | **33.1** | ➕ added here |
| `onboarding.submitBrandKyc` | `POST /onboarding/brand/kyc` | **33.1** | ➕ added here (supersedes 5.7 for brand flow) |
| `campaigns.list/get/create/update/delete` | `/campaigns*` | 6.1–6.5 | ✅ aligned |
| `campaigns.duplicate` | `POST /campaigns/:id/duplicate` | **33.2** | ➕ added here |
| `creators.search` | `GET /creators` | 7.1 | ⚠️ canonical path is `/creators` (not `/creators/search`) — see 33.5 |
| `creators.get` | `GET /creators/:id` | 7.2 | ✅ aligned |
| `creators.toggleSaved` | `POST /creators/:id/save` | 7.4/7.5 | ⚠️ global bookmark, distinct from campaign shortlist — see 33.5 |
| `creators.invite` | `POST /creators/:creatorId/invite` | 8.1 | ⚠️ alias of `/collaborations/invite` — see 33.5 |
| `deals.list/get/create/accept/reject/counter` | `/deals*` | **33.3** | ➕ unified resource added here |
| `messages.list/send/markRead` | `/deals/:dealId/messages*` | **33.3** | ➕ deal-scoped, added here |
| `contracts.generate` | `POST /contracts` | **33.4** | ➕ added here |
| `contracts.get` | `GET /contracts/:id` | 10.1 | ✅ aligned |
| `contracts.list` | `GET /contracts?dealId=` | 10.3 | ⚠️ filter by `dealId` — see 33.5 |
| `contracts.sign` | `POST /contracts/:id/sign` | 10.2 | ⚠️ typed signature `{name, agreedAt}` — see 33.5 |
| `deliverables.list` | `GET /deals/:dealId/deliverables` | 11.1 | ⚠️ deal-scoped — see 33.3 |
| `deliverables.submit` | `POST /deliverables/:id/submit` | 11.3 | ⚠️ JSON `{fileUrls,notes}` — see 33.5 |
| `deliverables.approve` | `POST /deliverables/:id/approve` | 11.4 | ⚠️ no body in client — see 33.5 |
| `deliverables.requestRevision` | `POST /deliverables/:id/revise` | 11.5 | ⚠️ path is `/revise` not `/request-revision` — see 33.5 |
| `wallet.get` | `GET /wallet` | 13.1 | ⚠️ field names `availableBalance/escrowLocked` — see 33.5 |
| `wallet.recharge` | `POST /wallet/recharge` | 13.3 | ⚠️ canonical path `/wallet/recharge` (not `/wallet/deposit`) — see 33.5 |
| `wallet.transactions` | `GET /wallet/transactions` | 13.2 | ✅ aligned |
| `payments.fundEscrow` | `POST /deals/:dealId/escrow/fund` | **33.3** | ➕ brand-initiated, added here |
| `payments.releasePayout` | `POST /deals/:dealId/payout/release` | **33.3** | ➕ deal-scoped alias of 13.5 |
| `dashboard.actions` | `GET /dashboard/actions` | **33.6** | ➕ added here |
| `dashboard.pipeline` | `GET /dashboard/pipeline` | **33.6** | ➕ added here |
| `notifications.list` | `GET /notifications` | 15.1 | ✅ aligned |
| `notifications.markAllRead` | `POST /notifications/read-all` | 15.2 | ⚠️ path/method differ — see 33.5 |
| `uploads.upload` | `POST /uploads` | 16.3 | ✅ aligned (client omits `purpose`; send it) |

---

### 33.1 Brand Onboarding APIs *(new — was entirely missing)*

> Powers the 3-step brand onboarding in `src/components/brand/onboarding/onboarding-steps.tsx`. KYC is deferred until the first campaign that needs a verified workspace.

**`POST /onboarding/brand/company`** — Auth: Bearer (brand)
```json
{
  "companyName": "BrandCo India",
  "companySlug": "brandco-india",
  "workspaceType": "BRAND",
  "industry": "fashion",
  "companySize": "6-20",
  "websiteUrl": "https://brandco.com",
  "description": "Premium fashion brand...",
  "logoUrl": "https://r2.influora.com/logos/brandco.jpg"
}
```
Response `200`: `{ "success": true, "data": { "workspaceId": "ws_..." } }`
Side effects: updates the workspace created at registration (4.1) with company details; validates `companySlug` via 32.1.

**`POST /onboarding/brand/complete`** — Auth: Bearer (brand)
Response `200`: `{ "success": true, "data": { "ok": true } }`
Side effect: sets `user.onboardingCompleted = true`. Frontend then routes to `/brand/dashboard`.

**`POST /onboarding/brand/kyc`** *(deferred — called before first ACTIVE campaign)* — Auth: Bearer (brand)
```json
{ "gstin": "22AAAAA0000A1Z5", "pan": "AAAAA0000A", "gstinDocUrl": "https://r2.../gstin.pdf", "panDocUrl": "https://r2.../pan.pdf" }
```
Response `200`: `{ "success": true, "data": { "kycStatus": "PENDING" } }` → `"VERIFIED"` after review.
Note: This is the JSON/URL-based variant the client uses. It supersedes the multipart `POST /workspaces/:workspaceId/verification` (5.7) for the brand onboarding flow — docs are uploaded first via `POST /uploads`, then their URLs submitted here.

---

### 33.2 Campaign Duplicate *(new)*

**`POST /campaigns/:campaignId/duplicate`** — Auth: Bearer (brand; OWNER/ADMIN/MANAGER)
Response `201`: `{ "success": true, "data": { "id": "c_dup" } }`
Behaviour: deep-copies title (`"… (Copy)"`), description, objectives, platforms, content types, requirements, hashtags, budget and target audience into a new `DRAFT` campaign. Does **not** copy collaborations, bids, or analytics.

---

### 33.3 Unified Deal Resource (`/deals`) *(new — canonical brand + creator deal-room API)*

> The frontend treats a "deal" as the single object behind the Deal Room (`/brand/chat`). `/deals` is a **facade/projection over `collaborations` + their latest `proposal` + contract/escrow state**. The detailed lifecycle still lives in §8 (collaborations) and §9 (proposals); `/deals` is what the UI actually calls. `role=brand|creator` selects perspective; the same routes serve both apps.

**Deal object shape** (from `api.ts`):
```jsonc
{
  "id": "deal-01XYZ",                 // == collaborationId
  "campaignId": "...", "campaignName": "Summer Fashion 2026",
  "counterpartyId": "...",            // creatorId for brand, brandId for creator
  "counterpartyName": "Priya Sharma",
  "counterpartyAvatar": "...", "counterpartyHandle": "@priya_creates",
  "status": "IN_NEGOTIATION",         // CollaborationStatus
  "dealValue": 50000, "currency": "INR",
  "lastMessage": "...", "lastMessageAt": "2026-05-17T10:30:00.000Z",
  "unreadCount": 2,
  "deliverablesDone": 2, "deliverablesTotal": 3,
  "nextDeadline": "2026-06-20T00:00:00.000Z",
  "contractId": "CTR-...", "contractStatus": "ACTIVE",
  "escrowFunded": true
}
```

| Method + path | Role | Maps to | Notes |
|---|---|---|---|
| `GET /deals?role=&status=` | brand+creator | `GET /collaborations` (8.5) | `status` ∈ `all,new,negotiating,contracted,in_progress,review,completed` |
| `GET /deals/:id` | brand+creator | `GET /collaborations/:id` (8.4) | Returns the Deal projection above |
| `POST /deals` | brand | `POST /collaborations/:id/proposals` (9.1) | Brand creates a deal+first proposal in one call (see body below) |
| `POST /deals/:id/accept` | creator | `POST /proposals/:proposalId/accept` (9.4) | Accepts the latest proposal on the deal |
| `POST /deals/:id/reject` | creator | `POST /proposals/:proposalId/reject` (9.5) | Body `{ "reason"?: string }` |
| `POST /deals/:id/counter` | brand+creator | `POST /proposals/:proposalId/counter` (9.6) | Body `{ amount, message?, deliverables?: [{type, qty}] }` |

**`POST /deals` request body** (brand creates proposal from Discover invite or campaign detail):
```json
{
  "campaignId": "...", "creatorId": "...",
  "amount": 50000,
  "deliverables": [{ "type": "REEL", "qty": 2 }, { "type": "STORY", "qty": 4 }],
  "deadline": "2026-06-30",
  "usageRights": "3_MONTHS",
  "exclusivity": false,
  "message": "We'd love to work with you..."
}
```
Side effects mirror 9.1: upserts the `collaborations` row (status → `IN_NEGOTIATION`), creates proposal v1, emits a `proposal` timeline event, notifies the creator.

#### Deal-scoped messages *(maps to §12 timeline)*
| Method + path | Maps to | Notes |
|---|---|---|
| `GET /deals/:dealId/messages?before=` | `GET /collaborations/:id/timeline` (12.1) | Returns `DealMessage[]` |
| `POST /deals/:dealId/messages` | `POST /collaborations/:id/messages` (12.2) | Body `{ content, kind }`; sends `Idempotency-Key` |
| `POST /deals/:dealId/messages/read` | `POST /collaborations/:id/messages/read` (12.3) | No body (marks all read for caller) |

`DealMessage.kind` ∈ `text | system | proposal | contract | deliverable | payment | shipment` — the frontend renders proposal/contract/deliverable/payment/shipment cards inline in the chat feed, so the timeline event `tag` (§12) must map 1:1 to `kind`.

#### Deal-scoped deliverables, escrow & payout
| Method + path | Maps to | Notes |
|---|---|---|
| `GET /deals/:dealId/deliverables` | `GET /deliverables?collaborationId=` (11.1) | Deal-nested list |
| `POST /deals/:dealId/escrow/fund` | `POST /wallet/escrow/hold` (13.14) | **Brand-initiated** in the UI (not purely auto). Returns `{ status: "FUNDED" }`. Must still verify wallet balance → `402 INSUFFICIENT_FUNDS` |
| `POST /deals/:dealId/payout/release` | `POST /wallet/release-milestone` (13.5) | Brand releases the next due milestone; body optional. Returns `{ payoutId, status }` |

---

### 33.4 Contract Generation *(new)*

**`POST /contracts`** — Auth: Bearer (brand)
```json
{ "dealId": "deal-01XYZ" }
```
Response `201`: `{ "success": true, "data": { "contractId": "CTR_..." } }`
Reconciliation: §9.4 auto-creates a `DRAFT` contract on proposal acceptance; this endpoint lets the brand **explicitly (re)generate** the contract for a deal and is what `api.ts contracts.generate` calls. Internally it should be idempotent with the 9.4 side effect and enqueue the PDF job (32.4).

---

### 33.5 Misaligned routes — canonical rulings for the brand flow

For each, the **left** (api.ts) is canonical for the current app; the **right** is the earlier spec section to update/treat as alias.

1. **Creator search** — canonical `GET /creators?q=&platforms=&city=&minFollowers=&maxFollowers=&minRate=&maxRate=&verticals=&page=&limit=` (NOT `/creators/search`). Param names: `city` (≡ `location`), `verticals` (≡ `categories`). Update 7.1 path + param names, or add `/creators` as the primary route with `/creators/search` as an alias.
2. **Save creator** — `POST /creators/:id/save` body `{ "saved": boolean }` is a **global, brand-level bookmark** (the bookmark icon in Discover), distinct from the **campaign-scoped shortlist** in 7.4/7.5. Both exist; keep them separate and document the save toggle here.
3. **Invite creator** — canonical `POST /creators/:creatorId/invite` body `{ campaignId, message? }`. Functionally equals 8.1 `POST /collaborations/invite { campaignId, creatorId, message }`; treat the `/creators/:id/invite` form as the brand alias.
4. **Wallet recharge** — canonical `POST /wallet/recharge` body `{ amount, paymentMethod: "upi"|"card"|"netbanking" }`. This is the same operation as 13.3 `POST /wallet/deposit`; standardize on `/wallet/recharge` for the brand UI (or alias).
5. **Wallet balance fields** — `GET /wallet` must return `{ availableBalance, escrowLocked, pendingPayouts, runwayDays }` for the brand client. Map: `availableBalance` ≡ 13.1 `balance`, `escrowLocked` ≡ `escrowBalance`. Either add these aliases server-side or update 13.1.
6. **Mark notifications read** — canonical `POST /notifications/read-all` (no body). 15.2's `PATCH /notifications/read { notificationIds, markAll }` covers selective reads; add `POST /notifications/read-all` as the "mark all" shortcut the client uses.
7. **Request revision** — canonical path `POST /deliverables/:id/revise` body `{ feedback }` (NOT `/request-revision`). Update 11.5 path, or expose `/revise` as an alias; keep the `requestedChanges[]` array from 11.5 as optional.
8. **Contract sign** — the client sends a **typed** signature `{ name, agreedAt }`, not the drawn-image `{ signatureData, agreedToTerms, ipAddress }` of 10.2. Backend should accept either; capture IP server-side regardless.
9. **Submit deliverable** — client sends JSON `{ fileUrls: string[], notes? }` (files are uploaded first via `POST /uploads`/presign, §16), not multipart `files[]` (11.3). Support the pre-uploaded-URL form.
10. **Approve deliverable** — client calls `POST /deliverables/:id/approve` with **no body**; `{ revisionId, comments }` from 11.4 are optional. Default to approving the latest revision.
11. **Contracts list** — client filters by `GET /contracts?dealId=` (≡ `collaborationId`). Add `dealId`/`collaborationId` as a supported filter alongside 10.3's `status`/`workspaceId`.

---

### 33.6 Dashboard APIs *(new — was entirely missing)*

> Powers `src/components/brand/dashboard/dashboard-page.tsx` (the "Now" action list and the Pipeline card).

**`GET /dashboard/actions`** — Auth: Bearer (`role`) — priority items needing attention
```json
{
  "success": true,
  "data": [
    {
      "id": "act-01",
      "type": "deliverable_review",          // | counter_proposal | payment_release | sign_contract
      "title": "Review Reel #2 from Priya Sharma",
      "subtitle": "Summer Fashion 2026",
      "deadline": "2026-05-25T00:00:00.000Z",
      "priority": "urgent",                  // | high | medium
      "amount": 50000,
      "link": "/brand/chat?deal=deal-01XYZ&tab=deliverables"
    }
  ]
}
```

**`GET /dashboard/pipeline`** — Auth: Bearer (`role`) — collaboration counts per stage
```json
{ "success": true, "data": [ { "stage": "Negotiating", "count": 4 }, { "stage": "Contracted", "count": 2 }, { "stage": "In Progress", "count": 6 } ] }
```

---

### 33.7 Open items / not yet in `api.ts`

These are documented in §§27–32 and used by brand pages **via mock data**, but `src/lib/api.ts` has no method yet — wire these when those screens go live:
- Campaign bids review (§27.3/27.4) — `brand-campaign-detail.tsx`
- Shipments (§28.4/28.5) — `deal-room/shipment-form.tsx`
- Contract clause comments (§29) — `contracts-and-deliverables.tsx`
- Payment methods & auto-recharge (§30) — `brand-settings.tsx`
- Account security / 2FA / delete account (§31) — `brand-settings.tsx`

Cross-cutting: the §22 TanStack query-key map keys off `collaborations`/`proposals`; add a `deals` key group to match the unified resource in 33.3.

---

*Backend API Specification v1.3 — 2026-05-23 (Updated)*  
*§1 locked: Spring Boot 3 + MySQL 8 + Cloudflare R2 (videos via presigned upload)*  
*Implementation guide: `docs/BACKEND-STACK.md` · scaffold: `influora-api/`*  
*Added Section 33: brand-flow alignment between `src/lib/api.ts` and the spec (missing endpoints + canonical rulings)*  
*Source of truth for all data types: `src/lib/types.ts`*  
*Frontend mock API: `src/lib/api.ts` (replace all mock functions with calls to endpoints above)*
