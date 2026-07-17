# Creator Execution Plan — FINAL

> **Orchestrator:** Arjun Kapoor  
> **Based on:** 00_CREATOR_MASTER_PLAN.md + CREATOR_EXEC_PLAN_PRIYA.md + CREATOR_PROGRESS.md  
> **Timeline:** 4 weeks (2026-07-09 → 2026-08-06)  
> **Target:** 100% Development Complete  
> **Swapnil plan:** Not found (`CREATOR_EXEC_PLAN_SWAPNIL.md` does not exist) — Priya architecture is sole source of truth

---

## Architecture Decisions (Merged from Priya CTO Plan — LOCKED)

1. **Stack:** Vite+React frontend, Spring Boot 3/MySQL backend (`influora-api/`), FastAPI sidecar (`influora-ai`). NOT Node/Prisma/Next.js.
2. **Deal model:** Use existing `Collaboration` entity + unified `DealMessage` timeline — do NOT build separate `Bid`/`CampaignApplication`/`Conversation` entities from specs.
3. **Frontend pattern:** Creator pages mirror brand pages 1:1. `api.ts` clients already written; backend must catch up.
4. **Auth:** Add `/auth/creator/*` to existing `AuthController`/`AuthService` (mirror brand methods). `User.newCreator()` + `CreatorProfile` + `Wallet.forUser()` on register.
5. **OAuth:** Meta/Instagram connect is done (Kabir-signed). YouTube/Facebook deferred to post-milestone-1.
6. **Money rails:** Reuse `Wallet`/`EscrowHold`/`PaymentMilestone` — add creator access path via `CreatorContextService` (brand-only 403 today).
7. **Platform fee:** Escalated to Swapnil — no hardcoded fee until business decision.

**Completion baseline (Priya audit):** Backend ~20%, Frontend shells ~75% but ~10% API-wired → **~28% blended**.

---

## Mission

Build a **production-ready** creator platform that enables:
1. Creator signup → profile setup → OAuth connect
2. Campaign discovery → bid submission → negotiation
3. Contract signing → deliverable upload → payment
4. Growth tracking → AI coaching → analytics

**No shortcuts. No mock data in production. Security-first.**

---

## Sprint Schedule

```
Week 1: Auth + Profile + OAuth       [28% → 45%]
Week 2: Campaigns + Deals            [45% → 65%]
Week 3: Contracts + Deliverables     [65% → 85%]
Week 4: Payments + Analytics + QA    [85% → 100%]
```

---

## Week 1: Auth + Profile + OAuth (Target: 70%)

### Goal
Replace all mock auth with real JWT backend. Complete profile CRUD. Wire Instagram/YouTube OAuth.

### Tasks

#### Vikram (Backend)
**Priority: P0**

1. **Read TECH-STACK.md** (mandatory first step)
   - Verify Node + Express + Prisma alignment
   - Check JWT library standards
   - Confirm password hashing approach

2. **Auth Endpoints** (`src/api/auth/creator.ts`)
   - `POST /api/v1/auth/creator/signup/email`
     - Generate OTP via MSG91
     - Store challenge in Redis (5min TTL)
     - Return `challenge_id`
   - `POST /api/v1/auth/creator/signup/phone`
     - Generate OTP via MSG91
     - Store challenge in Redis (5min TTL)
     - Return `challenge_id`
   - `POST /api/v1/auth/creator/signup/verify-otp`
     - Verify OTP against Redis challenge
     - Create User with `UserType.CREATOR`
     - Create empty CreatorProfile
     - Return JWT access + refresh tokens
   - `POST /api/v1/auth/creator/signup/complete`
     - Set password (bcrypt hash)
     - Update name, email if missing
     - Return success
   - `POST /api/v1/auth/creator/login`
     - Verify email/phone + password
     - Return JWT tokens
     - Log login event
   - `POST /api/v1/auth/creator/refresh`
     - Verify refresh token
     - Issue new access token
   - `POST /api/v1/auth/creator/logout`
     - Invalidate refresh token
   - `POST /api/v1/auth/creator/forgot-password`
     - Send OTP for password reset
   - `POST /api/v1/auth/creator/reset-password`
     - Verify OTP + set new password

3. **Profile Endpoints** (`src/api/creator/profile.ts`)
   - `GET /api/v1/creator/profile/:userId`
     - Return CreatorProfile + portfolio items
   - `PUT /api/v1/creator/profile`
     - Update bio, niche, rates, location, etc.
     - Validate niche against allowed list
   - `POST /api/v1/creator/portfolio/item`
     - Add portfolio item (image/video URL + metrics)
   - `DELETE /api/v1/creator/portfolio/item/:itemId`
     - Remove portfolio item

4. **OAuth Integration** (`src/api/oauth/instagram.ts`, `youtube.ts`)
   - `GET /connect/instagram/creator`
     - Redirect to Instagram OAuth with state token
   - `GET /connect/instagram/creator/callback`
     - Exchange code for access token
     - Fetch Instagram profile + followers
     - Store in ConnectedAccount table
     - Redirect to `/creator/profile`
   - `GET /connect/youtube/creator`
     - Redirect to YouTube OAuth
   - `GET /connect/youtube/creator/callback`
     - Exchange code for access token
     - Fetch YouTube channel + subscribers
     - Store in ConnectedAccount table

5. **Middleware** (`src/middleware/auth.ts`)
   - `authenticateCreator` middleware
     - Verify JWT from `Authorization: Bearer` header
     - Attach `req.user` with creator data
     - Return 401 if invalid/expired

**Definition of Done (Vikram Week 1):**
- [ ] All auth endpoints implemented
- [ ] Profile CRUD endpoints implemented
- [ ] Instagram + YouTube OAuth working
- [ ] JWT middleware protecting routes
- [ ] Passwords bcrypt hashed (cost 12)
- [ ] OTPs expire after 5min
- [ ] All errors return consistent JSON format
- [ ] Code passes Kavya QA review
- [ ] Code passes Kabir security audit (no Critical/High)
- [ ] Meera verifies API tests pass

---

#### Ananya (Frontend)
**Priority: P0**

1. **Read TECH-STACK.md** (mandatory first step)
   - Verify Vite + React Router patterns
   - Check Zustand store conventions
   - Review API integration approach

2. **Update Auth Pages** (wire to real API)
   - `creator-login.tsx`
     - Remove `assertMockAuthAllowed()` and `createMockCreatorUser()`
     - Call `POST /api/v1/auth/creator/login` with email + password
     - Store JWT in `localStorage.setItem('auth_token', token)`
     - Update Zustand store with user data
     - Redirect based on onboarding status
   - `creator-register.tsx`
     - Step 1: Collect email/phone + send OTP
     - Step 2: Verify OTP
     - Step 3: Set password + name
     - Call signup endpoints in sequence
     - Store JWT on success
     - Redirect to `/creator/onboarding`

3. **Profile Editor** (`creator-profile.tsx`)
   - Fetch profile data from `GET /api/v1/creator/profile/:userId`
   - Build form with fields:
     - Bio (textarea, 500 chars max)
     - Niche (multi-select: Fashion, Beauty, Fitness, etc.)
     - Rates (slider: ₹1k - ₹10L)
     - Location (city autocomplete)
     - Instagram/YouTube/Facebook handles
   - Add "Save Profile" button → `PUT /api/v1/creator/profile`
   - Show success toast on save

4. **Portfolio Editor** (`creator-portfolio-editor.tsx`)
   - List existing portfolio items
   - "Add New" button → modal with:
     - Upload image/video (or paste URL)
     - Title + description
     - Metrics (likes, shares, reach)
   - Call `POST /api/v1/creator/portfolio/item`
   - Delete button → `DELETE /api/v1/creator/portfolio/item/:id`

5. **OAuth Connect Buttons**
   - Update `creator-onboarding.tsx` OAuth step
   - "Connect Instagram" → `window.location.href = '/connect/instagram/creator'`
   - "Connect YouTube" → `window.location.href = '/connect/youtube/creator'`
   - Show connected status with profile pic + follower count

6. **API Integration** (`src/lib/api.ts`)
   - Create `apiClient` with JWT interceptor
     - Auto-attach `Authorization: Bearer` header
     - Auto-refresh token on 401
     - Redirect to `/creator/login` if refresh fails
   - Create typed API functions:
     - `creatorAuth.login(email, password)`
     - `creatorAuth.signup(email|phone)`
     - `creatorAuth.verifyOTP(challengeId, otp)`
     - `creatorProfile.get(userId)`
     - `creatorProfile.update(data)`
     - `creatorPortfolio.add(item)`
     - `creatorPortfolio.delete(itemId)`

**Definition of Done (Ananya Week 1):**
- [ ] Login/register pages call real API
- [ ] JWT stored and auto-attached to requests
- [ ] Profile editor saves successfully
- [ ] Portfolio editor adds/deletes items
- [ ] OAuth buttons redirect to OAuth flow
- [ ] Connected accounts display correctly
- [ ] Error states handled (toasts, inline messages)
- [ ] Loading states on all async actions
- [ ] Code passes Kavya QA review
- [ ] Meera verifies `npm run build` and `npm run dev` work

---

### Security Gate (Kabir)
**After Vikram + Ananya complete Week 1**

Audit for:
- [ ] No SQL injection vectors (parameterized queries only)
- [ ] No XSS (React auto-escapes, but check any `dangerouslySetInnerHTML`)
- [ ] Password hashing uses bcrypt cost 12+
- [ ] JWT secret is strong + env-variable only
- [ ] OTP generation is cryptographically random
- [ ] OTP rate limiting (max 3 attempts, then lockout)
- [ ] Auth endpoints have rate limits (5 req/min per IP)
- [ ] No sensitive data in logs or error messages
- [ ] OAuth state tokens validated (CSRF protection)

**If any Critical/High findings: BLOCK Week 2 until fixed.**

---

## Week 2: Campaigns + Bids (Target: 85%)

### Goal
Build campaign discovery for creators. Enable bid submission and counter-offers.

### Tasks

#### Vikram (Backend)
**Priority: P0**

1. **Campaign Browse API** (`src/api/creator/campaigns.ts`)
   - `GET /api/v1/creator/campaigns/browse`
     - Return campaigns with status `OPEN` or `ACCEPTING_BIDS`
     - Support filters: `?niche=fashion&budget_min=50000&location=Mumbai`
     - Support pagination: `?page=1&limit=20`
     - Return campaign cards with:
       - Brand info (name, logo, verified badge)
       - Campaign type (Hype vs Standard)
       - Budget range
       - Required deliverables
       - Deadline
       - Bid count
   - `GET /api/v1/creator/campaigns/:campaignId`
     - Return full campaign details
     - Include deliverable requirements
     - Include brand profile

2. **Bid Submission API** (`src/api/creator/bids.ts`)
   - `POST /api/v1/creator/bids`
     - Body: `{ campaign_id, proposed_rate, message, deliverable_timeline }`
     - Create Collaboration record with status `BID_SUBMITTED`
     - Send notification to brand
     - Return bid ID
   - `GET /api/v1/creator/bids`
     - Return all bids by this creator
     - Include status: `BID_SUBMITTED`, `UNDER_REVIEW`, `ACCEPTED`, `REJECTED`, `COUNTERED`
   - `PUT /api/v1/creator/bids/:bidId/counter`
     - Update proposed rate + message
     - Set status to `COUNTER_OFFER`
     - Notify brand

3. **Negotiation Logic**
   - Track offer/counter-offer history in `CollaborationHistory` table
   - Enforce max 3 counter-offers per side
   - Auto-expire bids after 7 days if no response

**Definition of Done (Vikram Week 2):**
- [ ] Campaign browse API returns filtered results
- [ ] Pagination works correctly
- [ ] Bid submission creates Collaboration record
- [ ] Counter-offer logic implemented
- [ ] Notifications sent to brands
- [ ] Code passes Kavya + Kabir reviews

---

#### Ananya (Frontend)
**Priority: P0**

1. **Campaign Browser** (`creator-inbox.tsx`)
   - Tab: "Browse Campaigns"
   - Fetch campaigns from `GET /api/v1/creator/campaigns/browse`
   - Show campaign cards in grid layout
   - Each card shows:
     - Brand logo + name
     - Campaign title
     - Budget range (e.g., "₹50k - ₹1L")
     - Deliverables (e.g., "2 Reels + 1 Story")
     - Deadline
     - "View Details" button

2. **Campaign Detail Modal**
   - Click card → open modal with full details
   - Show deliverable requirements
   - Show brand profile + past campaigns
   - "Submit Bid" button (if not already bid)

3. **Bid Submission Form**
   - Modal with:
     - Proposed rate (number input)
     - Message to brand (textarea)
     - Deliverable timeline (date picker)
   - Submit → `POST /api/v1/creator/bids`
   - Show success toast
   - Close modal + refresh bids list

4. **My Bids List** (`creator-deals.tsx`)
   - Tab: "My Bids"
   - Fetch from `GET /api/v1/creator/bids`
   - Show status badges: "Submitted", "Under Review", "Accepted", "Countered"
   - If status = "Countered", show brand's counter-offer
   - "Accept" or "Counter" buttons

5. **Counter-Offer UI**
   - Modal with brand's offer
   - Allow creator to adjust rate + message
   - Submit → `PUT /api/v1/creator/bids/:bidId/counter`

**Definition of Done (Ananya Week 2):**
- [ ] Campaign browser displays campaigns
- [ ] Filters work (niche, budget, location)
- [ ] Bid submission form submits successfully
- [ ] Bids list shows all creator bids with status
- [ ] Counter-offer UI works
- [ ] Code passes Kavya review
- [ ] Meera verifies build + dev server

---

### Security Gate (Kabir)
Audit for:
- [ ] No bid amount manipulation (server-side validation)
- [ ] No unauthorized bid access (creator can only see their bids)
- [ ] No campaign data leaks (filter by creator permissions)
- [ ] Rate limiting on bid submission (max 10 bids/day per creator)

---

## Week 3: Contracts + Deliverables (Target: 95%)

### Goal
Enable contract review + e-sign. Build deliverable upload system.

### Tasks

#### Vikram (Backend)
**Priority: P0**

1. **Contract API** (`src/api/creator/contracts.ts`)
   - `GET /api/v1/creator/contracts/:collaborationId`
     - Return contract PDF URL (already generated in brand flow)
     - Return signing status
   - `POST /api/v1/creator/contracts/:collaborationId/sign`
     - Record creator signature
     - Update Collaboration status to `CONTRACT_SIGNED`
     - Trigger escrow hold
     - Send notification to brand
   - `GET /api/v1/creator/contracts/unsigned`
     - Return list of contracts awaiting creator signature

2. **Deliverable Upload API** (`src/api/creator/deliverables.ts`)
   - `POST /api/v1/creator/deliverables`
     - Body: `{ collaboration_id, milestone_id, file_url, description, metrics: { views, likes, shares } }`
     - Create Deliverable record
     - Notify brand for review
   - `GET /api/v1/creator/deliverables/:collaborationId`
     - Return all deliverables for this collab
     - Include approval status: `PENDING_REVIEW`, `APPROVED`, `REVISION_REQUESTED`
   - `PUT /api/v1/creator/deliverables/:deliverableId/metrics`
     - Update self-reported metrics after content goes live

3. **Milestone Tracking**
   - `GET /api/v1/creator/milestones/:collaborationId`
     - Return milestones with status: `PENDING`, `IN_PROGRESS`, `SUBMITTED`, `APPROVED`, `PAID`

**Definition of Done (Vikram Week 3):**
- [ ] Contract sign API works
- [ ] Escrow hold triggered on sign
- [ ] Deliverable upload creates record
- [ ] Metrics reporting works
- [ ] Notifications sent to brand
- [ ] Code passes Kavya + Kabir reviews

---

#### Ananya (Frontend)
**Priority: P0**

1. **Contract Signing UI** (`creator-active.tsx`)
   - Show list of unsigned contracts
   - Click → open contract PDF in modal
   - "Review Contract" button opens PDF viewer
   - "Sign" button → `POST /api/v1/creator/contracts/:id/sign`
   - Signature capture (Canvas.js or typed name)
   - Show signed timestamp after success

2. **Deliverable Upload UI** (new page or modal in `creator-active.tsx`)
   - Show milestones for each active collaboration
   - For each milestone:
     - Upload file (image/video) or paste URL
     - Add description
     - Submit → `POST /api/v1/creator/deliverables`
   - Show submission status: "Pending Review", "Approved", "Revisions Requested"

3. **Metrics Reporting UI**
   - After deliverable goes live, show "Report Metrics" button
   - Form with: Views, Likes, Shares, Comments
   - Submit → `PUT /api/v1/creator/deliverables/:id/metrics`

4. **Active Campaign Dashboard**
   - Update `creator-active.tsx` with:
     - Contract status
     - Milestone progress bars
     - Deliverable upload status
     - Next action item highlighted

**Definition of Done (Ananya Week 3):**
- [ ] Contract PDF displays correctly
- [ ] E-sign submission works
- [ ] Deliverable upload succeeds
- [ ] Metrics reporting works
- [ ] Active dashboard shows real-time status
- [ ] Code passes Kavya review
- [ ] Meera verifies build + dev

---

### Security Gate (Kabir)
Audit for:
- [ ] No contract manipulation (signed contract is immutable)
- [ ] File uploads are validated (type, size, virus scan)
- [ ] No deliverable data leaks (creator only sees own)
- [ ] Metrics can't be edited after brand approval

---

## Week 4: Payments + Analytics + QA (Target: 100%)

### Goal
Complete wallet + withdrawal flow. Add growth analytics. Full QA + security audit.

### Tasks

#### Vikram (Backend)
**Priority: P0 + P1**

1. **Wallet & Withdrawal API** (`src/api/creator/wallet.ts`)
   - `GET /api/v1/creator/wallet`
     - Return wallet balance (net earnings after 15% fee)
     - Return transaction history
   - `POST /api/v1/creator/wallet/withdraw`
     - Body: `{ amount, bank_account_id }`
     - Validate minimum withdrawal (₹1000)
     - Create withdrawal request
     - Update wallet balance (pending withdrawal)
     - Trigger payout via Razorpay/Stripe
   - `GET /api/v1/creator/wallet/transactions`
     - Return paginated transaction list
     - Include: Milestone payments, withdrawals, affiliate earnings

2. **Affiliate Earnings API** (`src/api/creator/affiliate.ts`)
   - `POST /api/v1/creator/affiliate/refer`
     - Body: `{ referred_user_email }`
     - Generate referral link
   - `GET /api/v1/creator/affiliate/earnings`
     - Return affiliate earnings breakdown
     - Show referred users + earnings per referral

3. **Growth Analytics API** (`src/api/creator/analytics.ts`)
   - `GET /api/v1/creator/analytics/overview`
     - Return:
       - Total earnings (lifetime, this month)
       - Campaign count (completed, active, pending)
       - Avg campaign value
       - Growth chart data (monthly earnings)
   - `GET /api/v1/creator/analytics/ai-insights`
     - Call Meera AI to analyze creator's performance
     - Return personalized growth tips

**Definition of Done (Vikram Week 4):**
- [ ] Wallet API returns correct balance
- [ ] Withdrawal flow works end-to-end
- [ ] Affiliate tracking implemented
- [ ] Analytics endpoints return data
- [ ] AI insights integrated
- [ ] Code passes Kavya + Kabir reviews

---

#### Ananya (Frontend)
**Priority: P0 + P1**

1. **Wallet UI** (`creator-wallet.tsx`)
   - Show wallet balance (large card at top)
   - "Withdraw" button → modal with:
     - Amount input (validate min ₹1000)
     - Bank account selector (from saved accounts)
     - Confirm button → `POST /api/v1/creator/wallet/withdraw`
   - Show transaction history table with pagination

2. **Affiliate Earnings UI** (`creator-affiliate-earnings.tsx`)
   - Show referral link (copy button)
   - Show referred users list
   - Show earnings per referral
   - Show total affiliate earnings

3. **Analytics Dashboard** (new tab in `creator-inbox.tsx`)
   - Summary cards: Total earnings, active campaigns, avg rate
   - Monthly earnings chart (line chart with Recharts)
   - AI insights section (fetch from `/api/v1/creator/analytics/ai-insights`)
   - Growth tips + recommendations

**Definition of Done (Ananya Week 4):**
- [ ] Wallet displays correct balance
- [ ] Withdrawal form submits successfully
- [ ] Affiliate UI shows referrals + earnings
- [ ] Analytics dashboard renders charts
- [ ] AI insights display
- [ ] Code passes Kavya review
- [ ] Meera verifies build + dev

---

### QA Gate (Kavya)
**Full Test Pass — Week 4**

Run full QA checklist from `13_CREATOR_QA_SPEC.md`:

1. **Auth Tests**
   - [ ] Signup with email OTP works
   - [ ] Signup with phone OTP works
   - [ ] Login with email + password works
   - [ ] Password reset works
   - [ ] JWT refresh works
   - [ ] Invalid credentials rejected

2. **Profile Tests**
   - [ ] Profile editor saves data
   - [ ] Portfolio items add/delete
   - [ ] OAuth connect works (Instagram + YouTube)
   - [ ] Profile displays on public page

3. **Campaign Tests**
   - [ ] Campaign browser loads campaigns
   - [ ] Filters work correctly
   - [ ] Bid submission succeeds
   - [ ] Counter-offer flow works
   - [ ] Bid status updates correctly

4. **Contract Tests**
   - [ ] Contract PDF displays
   - [ ] E-sign captures signature
   - [ ] Signed contract is immutable
   - [ ] Escrow hold triggered

5. **Deliverable Tests**
   - [ ] Deliverable upload succeeds
   - [ ] Metrics reporting works
   - [ ] Brand can approve/reject
   - [ ] Milestone progress updates

6. **Payment Tests**
   - [ ] Wallet shows correct balance
   - [ ] Withdrawal request succeeds
   - [ ] Transaction history accurate
   - [ ] Affiliate earnings tracked

7. **Analytics Tests**
   - [ ] Dashboard displays data
   - [ ] Charts render correctly
   - [ ] AI insights load

**Test Coverage Target: 80%+**
- Unit tests for business logic
- Integration tests for API endpoints
- E2E tests for critical flows (signup → bid → contract → payment)

**If test coverage < 80%: Write more tests before sign-off.**

---

### Security Gate (Kabir)
**Final OWASP Audit — Week 4**

Full security audit against `12_CREATOR_SECURITY_SPEC.md`:

1. **Authentication**
   - [ ] No hardcoded secrets
   - [ ] JWT secret is strong (32+ chars)
   - [ ] Password hashing uses bcrypt cost 12+
   - [ ] OTP is cryptographically random
   - [ ] OTP expires after 5min
   - [ ] Rate limiting on auth endpoints

2. **Authorization**
   - [ ] Creator can only access own data
   - [ ] No privilege escalation vectors
   - [ ] Admin endpoints are protected

3. **Input Validation**
   - [ ] All inputs validated server-side (Zod schemas)
   - [ ] No SQL injection (parameterized queries)
   - [ ] No XSS (React auto-escapes)
   - [ ] File uploads validated (type, size)

4. **Data Protection**
   - [ ] Sensitive data not logged
   - [ ] PII encrypted at rest (if applicable)
   - [ ] HTTPS enforced
   - [ ] CORS properly configured

5. **Business Logic**
   - [ ] No payment amount manipulation
   - [ ] No race conditions in transactions
   - [ ] No workflow bypass attempts

**If any Critical/High findings: BLOCK production deploy until fixed.**

---

### Build Gate (Meera)
**Final Build Verification — Week 4**

Run full build checklist:

```bash
# Frontend
npm run build          # Must succeed with no errors
npm run dev            # Must start on :3000
npm run test           # All tests pass
npm run lint           # No linting errors

# Backend (if separate)
npm run test:api       # API tests pass
curl localhost:4000/health  # Health check responds

# Database
npx prisma migrate deploy   # Migrations apply
npx prisma db push          # Schema syncs
```

Document results in SHARED_CONTEXT.md:
```markdown
## Final Build Verification — Creator Platform
Date: 2026-08-06
Status: ✅ PASS

- ✅ npm run build: PASS (build time: 12s, bundle size: 450 KB)
- ✅ npm run dev: PASS (starts on :3000)
- ✅ npm run test: PASS (128/128 tests)
- ✅ npm run lint: PASS (0 errors, 3 warnings)
- ✅ API health check: PASS (200 OK)
- ✅ DB migrations: PASS (23 migrations applied)

**Result:** Ready for Priya's final sign-off.
```

**If any check fails: Route back to Vikram/Ananya to fix.**

---

### Final Approval (Priya)
**Architecture + Code Review — Week 4**

Priya reviews:
1. Code follows TECH-STACK.md standards
2. No architectural anti-patterns
3. Performance is acceptable (page load < 2s, API response < 500ms)
4. Code is maintainable (no spaghetti, clear separation of concerns)
5. All quality gates passed (Kavya, Kabir, Meera)

**If approved: Creator platform is DONE. Report to Swapnil.**
**If changes needed: Route back with specific feedback.**

---

## Communication Protocol

### Daily Standup (Async via SHARED_CONTEXT.md)
Each agent writes daily update:
```markdown
### [Agent Name] — [Date]
**Yesterday:** [What I completed]
**Today:** [What I'm working on]
**Blockers:** [Any issues]
```

### Escalation Rules
- **Arjun escalates to Priya** if:
  - Architecture decision needed
  - Performance issue
  - TECH-STACK.md clarification needed

- **Arjun escalates to Swapnil** if:
  - Timeline at risk
  - Scope change needed
  - Resource conflict

- **Vikram/Ananya escalate to Arjun** if:
  - Task ambiguous
  - Dependency blocked
  - Need help from other agent

- **Kavya/Kabir escalate to Arjun** if:
  - Code quality issues persist
  - Security findings not being fixed

### Definition of Done (Per Task)
Every task must:
1. Code written (Vikram or Ananya)
2. Kavya QA reviewed (no blocking issues)
3. Kabir security reviewed (no Critical/High findings)
4. Meera build verified (npm run build + test passes)
5. Arjun updates CREATOR_PROGRESS.md with new %

---

## Success Metrics (Week 4 End)

### Functionality ✅
- [ ] Creator can complete full journey: signup → profile → campaign → bid → contract → deliverable → payment
- [ ] All 13 spec files are 100% implemented
- [ ] Zero mock data in production code
- [ ] All OAuth integrations working

### Quality ✅
- [ ] Test coverage ≥ 80%
- [ ] Zero Critical/High security findings
- [ ] All builds green
- [ ] Zero console errors in browser

### Documentation ✅
- [ ] API docs complete (OpenAPI/Swagger)
- [ ] User guide for creators (Ishaan)
- [ ] Security audit report (Kabir)
- [ ] Test coverage report (Kavya)

---

## Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| OAuth integration complex | High | High | Start Week 1, test early |
| Payment gateway delays | Medium | High | Use sandbox mode initially |
| Security audit finds Criticals | Medium | Critical | Daily security reviews, not just Week 4 |
| Test coverage low | Medium | Medium | Write tests alongside features |
| Spec drift from code | Low | Medium | Daily sync with specs |

---

## Maintenance Plan (Post-100%)

After creator platform is 100% done:

1. **Monitoring** (Meera)
   - Set up error tracking (Sentry)
   - Set up performance monitoring (Datadog)
   - Alert on 5xx errors

2. **User Feedback** (Tejas + Ishaan)
   - Collect creator feedback
   - Prioritize bugs/enhancements

3. **Iterative Improvements**
   - P0 bugs: Fix immediately
   - P1 features: Next sprint
   - P2 features: Backlog

---

**End of Execution Plan**

**Next Action:** Arjun creates TASK_INBOX.md with Week 1 tasks and kicks off loop.
