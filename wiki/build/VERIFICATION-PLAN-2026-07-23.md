# Full Platform Verification Plan — 2026-07-23
**Orchestrator:** Arjun (Engineering Lead)  
**Target:** http://200.141.1.6/ (live influora-test deployment)  
**Source Report:** `wiki/reports/brand-creator-final-report.md`  
**Fix Commit:** `b6b0677` (2026-07-23 00:06)  
**Branch:** `feat/creator-taxonomy-keyword-patch` (verification should happen on the branch containing b6b0677)

---

## Objective
Verify ALL 10 blocking/high issues from the final report AND confirm all 8 claimed fixes from commit b6b0677 work correctly in the LIVE deployment.

---

## Verification Checklist

### Phase 1: Code Review (Kavya - QA Lead)
**Status:** ✅ CLAIMED PASS (per commit message)  
**Re-verify:** Read changed files and confirm:

#### Backend Files
1. **GlobalExceptionHandler.java** (`influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java`)
   - ✅ Maps `NoResourceFoundException` → 404
   - ✅ Maps `MissingServletRequestParameterException` → 400
   - ✅ Maps `HttpRequestMethodNotSupportedException` → 405
   - ✅ Maps `MethodArgumentTypeMismatchException` → 400
   - **Test:** Hit `/api/v1/nonexistent` → expect 404 not 500; hit endpoint with missing param → expect 400 not 500

2. **WalletService.java** (`influora-api/src/main/java/com/influora/service/WalletService.java`)
   - ✅ Added `getOrCreateWorkspaceWallet` method
   - ✅ `getWorkspaceWallet` calls `getOrCreateWorkspaceWallet` (auto-provision)
   - ✅ `getWorkspaceBalance` calls `getOrCreateWorkspaceWallet`
   - ✅ Money-path methods still use `requireWorkspaceWallet` (throws on missing)
   - **Test:** Brand with no wallet → GET `/wallet` should 200 with zero balance, NOT 404

3. **CreatorProfileSpecifications.java** (`influora-api/src/main/java/com/influora/service/CreatorProfileSpecifications.java`)
   - ✅ Cast JSON columns to String before `.lower()` (Hibernate 6 compatibility)
   - ✅ Check lines where `lower()` is applied to JSON fields
   - **Test:** GET `/creators/featured?niche=fashion&minFollowers=10000` → should 200 not 500

#### Frontend Files
4. **campaign-form.tsx** (`src/components/brand/campaigns/campaign-form.tsx`)
   - ✅ End Date popover mutually exclusive with Start Date popover
   - ✅ End Date `onSelect` sets `formData.endDate` not `formData.startDate`
   - **Test:** Create campaign → set Start Date → set End Date → both dates should be different and correct

5. **dashboard-page.tsx** (`src/components/brand/dashboard/dashboard-page.tsx`)
   - ✅ Uses `Promise.allSettled` instead of `Promise.all`
   - ✅ No `mockActionItems`, `mockWallet`, `mockPipeline` seeds in code
   - ✅ Handles partial failure gracefully (doesn't discard successful loads)
   - **Test:** Brand dashboard with wallet 404 → should show real actions/pipeline, only wallet section should error

6. **creator-profile.tsx** (`src/pages/creator-profile.tsx`)
   - ✅ Makes API call to `GET /me/creator-profile`
   - ✅ No `mockProfile` hardcoded data
   - ✅ Renders real profile data from API
   - **Test:** Creator profile page → should show real user data (demo.creator@influora.com identity), not "Priya Sharma"

7. **creator-wallet.tsx** (`src/pages/creator-wallet.tsx`)
   - ✅ Renders real wallet data from `GET /wallet`
   - ✅ No `mockEarningsData` or `mockPayouts` (BoAt/Mamaearth/Nykaa fake data)
   - ✅ Shows real balance and transaction history
   - **Test:** Creator wallet → should show ₹0 if no transactions, NOT ₹4,25,000 fake earnings

8. **creator-deals.tsx** (`src/pages/creator-deals.tsx`)
   - ✅ Removed `demoHypeCampaign` from live render
   - ✅ No "Glow Drop Challenge" fake deal in inbox
   - **Test:** Creator deals page → should show real deals from API (empty state if none), no Hype card

9. **creator-login.tsx** (`src/pages/creator-login.tsx`)
   - ✅ Badge shows `accent="creator"` or correct label
   - **Test:** Creator login page → badge should say "Creator workspace" not "Brand workspace"

---

### Phase 2: Build Verification (Meera - Build Engineer)
**Assignee:** Meera  
**Tasks:**

1. **Frontend Build**
   ```bash
   cd C:\Users\Sage world\Downloads\New Influora Ai\New Influora
   git checkout feat/creator-taxonomy-keyword-patch
   git pull origin feat/creator-taxonomy-keyword-patch
   cd influora-ui
   npm run build
   # Should PASS with no errors
   ```
   - ✅ `npm run build` exits 0
   - ✅ No TypeScript errors
   - ✅ No linting errors
   - ✅ Dist bundle created successfully

2. **Backend Build**
   ```bash
   # (Currently blocked: local Maven/Docker unavailable per commit message)
   # Verification deferred to CI or VPS rebuild
   ```
   - ⏳ DEFERRED to CI pipeline
   - Alternative: Check Hostinger VPS logs for backend startup errors

3. **Write Results**
   - Write build output to `wiki/build/verification-meera-build-2026-07-23.md`
   - Include: build time, exit code, any warnings, bundle size comparison

---

### Phase 3: Live Endpoint Testing (Meera - Verification)
**Assignee:** Meera  
**Method:** `curl` + browser inspection  
**Target:** http://200.141.1.6/

#### 3.1 Backend API Tests
**Setup:**
```bash
# Login to get tokens
BRAND_TOKEN=$(curl -X POST http://200.141.1.6/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo.brand@influora.com","password":"YOUR_PASSWORD"}' \
  | jq -r '.token')

CREATOR_TOKEN=$(curl -X POST http://200.141.1.6/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo.creator@influora.com","password":"YOUR_PASSWORD"}' \
  | jq -r '.token')
```

**Tests:**

1. **Issue #7 - 4xx Handling**
   ```bash
   # Missing endpoint → should 404 not 500
   curl -i http://200.141.1.6/api/v1/nonexistent
   # Expected: HTTP/1.1 404 Not Found
   
   # Missing required param → should 400 not 500
   curl -i "http://200.141.1.6/api/v1/creators/search" \
     -H "Authorization: Bearer $BRAND_TOKEN"
   # Expected: HTTP/1.1 400 Bad Request (if param required)
   
   # Wrong method → should 405 not 500
   curl -i -X DELETE http://200.141.1.6/api/v1/auth/login
   # Expected: HTTP/1.1 405 Method Not Allowed
   ```

2. **Issue #2 - Brand Wallet Auto-Provision**
   ```bash
   # Brand wallet read → should 200 with zero balance, NOT 404
   curl -i http://200.141.1.6/api/v1/wallet \
     -H "Authorization: Bearer $BRAND_TOKEN"
   # Expected: HTTP/1.1 200 OK
   # Expected body: {"balance": 0, ...} or similar
   
   curl -i http://200.141.1.6/api/v1/wallet/balance \
     -H "Authorization: Bearer $BRAND_TOKEN"
   # Expected: HTTP/1.1 200 OK
   ```

3. **Issue #5 - Featured Creators 500**
   ```bash
   # Featured creators with valid params → should 200 not 500
   curl -i "http://200.141.1.6/api/v1/creators/featured?niche=fashion&minFollowers=10000" \
     -H "Authorization: Bearer $BRAND_TOKEN"
   # Expected: HTTP/1.1 200 OK
   # Expected: JSON array of creators (may be empty)
   ```

4. **Issue #3 - Creator Profile Real Data**
   ```bash
   # Creator profile → should return demo.creator data
   curl -i http://200.141.1.6/api/v1/me/creator-profile \
     -H "Authorization: Bearer $CREATOR_TOKEN"
   # Expected: HTTP/1.1 200 OK
   # Expected: email = demo.creator@influora.com (NOT Priya Sharma)
   ```

5. **Issue #4 - Creator Wallet Real Data**
   ```bash
   # Creator wallet → should return real empty wallet, NOT mock data
   curl -i http://200.141.1.6/api/v1/wallet \
     -H "Authorization: Bearer $CREATOR_TOKEN"
   # Expected: HTTP/1.1 200 OK
   # Expected: balance = 0 (or real value), NOT ₹4,25,000
   
   curl -i http://200.141.1.6/api/v1/wallet/transactions \
     -H "Authorization: Bearer $CREATOR_TOKEN"
   # Expected: empty array or real transactions, NOT BoAt/Mamaearth/Nykaa
   ```

#### 3.2 Browser UI Tests
**Account:** demo.brand@influora.com / demo.creator@influora.com  
**Browser:** Chrome with DevTools open (Network + Console tabs)

**Brand Side:**

1. **Issue #1 - Campaign End Date Bug**
   - Navigate to `/brand/campaigns/new`
   - Fill Step 1 (details)
   - Fill Step 2 (audience)
   - Fill Step 3 (budget):
     - Click "Start Date" → select a date (e.g., 2026-08-01)
     - Click "End Date" → select a different date (e.g., 2026-08-15)
     - **Verify:** Start Date = 2026-08-01, End Date = 2026-08-15 (NOT both same)
   - Proceed to Step 4
   - **Expected:** Can advance past budget step (no longer stuck)

2. **Issue #2 - Dashboard Promise.allSettled**
   - Navigate to `/brand/dashboard`
   - Open DevTools Network tab
   - Check:
     - `/api/v1/actions` → 200 (shows real data on screen)
     - `/api/v1/pipeline` → 200 (shows real data on screen)
     - `/api/v1/wallet` → 200 (auto-provisioned, shows ₹0)
   - **Verify:** Dashboard shows real actions + pipeline + wallet
   - **Verify:** No mock data ("₹1,48,500 TDS" should NOT appear)
   - **Verify:** If one API fails, others still render

**Creator Side:**

3. **Issue #3 - Creator Profile Real Data**
   - Navigate to `/creator/profile`
   - **Verify:** Shows demo.creator@influora.com identity
   - **Verify:** Does NOT show "Priya Sharma / 125K / 45 collabs"
   - Check DevTools Network: `GET /me/creator-profile` → 200

4. **Issue #4 - Creator Wallet Real Data**
   - Navigate to `/creator/wallet`
   - **Verify:** Shows ₹0 earned (or real value if transactions exist)
   - **Verify:** Does NOT show "₹4,25,000 earned"
   - **Verify:** Payouts section is empty or real (NOT BoAt/Mamaearth/Nykaa fake payouts)

5. **Issue #9 - No Fake Hype Deal**
   - Navigate to `/creator/deals`
   - **Verify:** Shows real deals from API (empty state if none)
   - **Verify:** Does NOT show "Glow Drop Challenge" Hype card

6. **Issue #10 - Login Badge**
   - Navigate to `/creator/login`
   - **Verify:** Badge says "Creator workspace" (NOT "Brand workspace")

---

### Phase 4: Security Review (Kabir - Red Team)
**Status:** ✅ CLAIMED PASS (per commit message)  
**Re-verify:** Run OWASP checks on changed endpoints

**Assignee:** Kabir  
**Focus Areas:**

1. **Auto-Provisioned Wallet Authz**
   - Verify: Brand can only auto-provision their OWN workspace wallet
   - Verify: Cannot trigger wallet creation for other workspaces via manipulated request
   - Test: Try to read `/wallet` with creator token → should 403 or return creator wallet only

2. **4xx Error Info Leakage**
   - Verify: 404/400/405 responses do NOT leak sensitive info (stack traces, DB paths, etc.)
   - Check: Error messages are user-safe, not raw exceptions

3. **Creator Profile Data Isolation**
   - Verify: `/me/creator-profile` returns only the authenticated creator's data
   - Test: Cannot access other creators' profiles via manipulated `creatorId` param

4. **Demo Data Removal**
   - Verify: No hardcoded mock data can be exploited (e.g., fake deals triggering real actions)
   - Verify: Removed demo Hype campaign cannot be accepted/exploited

**Deliverable:** Write results to `wiki/build/verification-kabir-security-2026-07-23.md`

---

### Phase 5: Outstanding Issues (NOT Fixed in b6b0677)
**Assignee:** TBD (Route to Vikram/Infra/DevOps per category)

These were NOT claimed as fixed in b6b0677 and still need work:

#### Backend Issues
6. **Issue #6 - Email Delivery Dead** (🟠 High)
   - **Problem:** MSG91 SMTP returns `535 IP not whitelisted`
   - **File:** `Msg91EmailClient` / MSG91 account config
   - **Owner:** Infra/DevOps
   - **Fix:** Whitelist `200.141.1.6` in MSG91 account settings
   - **Test:** Trigger welcome email → check MSG91 logs + user inbox

8. **Issue #8 - Shopify OAuth Broken** (🟡 Med)
   - **Problem:** `/shopify/oauth/authorize` → 500
   - **File:** `ShopifyOAuthController`
   - **Owner:** Vikram (Backend)
   - **Fix:** Configure Shopify app credentials OR disable feature if unused
   - **Test:** Hit `/shopify/oauth/authorize` → should 200 or redirect, NOT 500

#### Security/Seed Data
- **Seed Creators in Production**
  - **Problem:** `V7__seed_discoverable_creators.sql` runs in all envs; 5 creators with shared password `Password@123`; cleanup migration `V72__remove_seed_creators.sql` never written
  - **File:** `db/migration/V7__*.sql` + `DevSeedCreatorsRunner.java`
  - **Owner:** DevOps + Vikram
  - **Fix:** Write `V72__remove_seed_creators.sql`, rotate seed password, ensure `@Profile("dev")` enforced
  - **Risk:** ⚠️ Security — live prod has shared-password accounts

---

## Phase 6: Priya Final Approval (CTO Sign-Off)
**Assignee:** Priya (CTO)  
**Pre-requisites:**
- ✅ Kavya code review PASS
- ✅ Meera build PASS
- ✅ Meera live tests PASS (all 9 fixed issues verified)
- ✅ Kabir security review PASS

**Deliverable:**
- Priya reviews all verification docs
- Signs off on deploy-readiness OR flags blockers
- Updates `SHARED_CONTEXT.md` with final verdict

---

## Routing Map

| Issue # | Severity | Owner | File(s) | Status |
|---------|----------|-------|---------|--------|
| 1 | 🔴 Blocking | Ananya | `campaign-form.tsx` | ✅ FIXED in b6b0677 |
| 2 | 🟠 High | Vikram + Ananya | `WalletService.java`, `dashboard-page.tsx` | ✅ FIXED in b6b0677 |
| 3 | 🟠 High | Ananya | `creator-profile.tsx` | ✅ FIXED in b6b0677 |
| 4 | 🟠 High | Ananya | `creator-wallet.tsx` | ✅ FIXED in b6b0677 |
| 5 | 🟠 High | Vikram | `CreatorProfileSpecifications.java` | ✅ FIXED in b6b0677 |
| 6 | 🟠 High | Infra | MSG91 account | ⏳ NOT FIXED |
| 7 | 🟡 Med | Vikram | `GlobalExceptionHandler.java` | ✅ FIXED in b6b0677 |
| 8 | 🟡 Med | Vikram | `ShopifyOAuthController` | ⏳ NOT FIXED |
| 9 | 🟡 Med | Ananya | `creator-deals.tsx` | ✅ FIXED in b6b0677 |
| 10 | 🔵 Low | Ananya | `creator-login.tsx` | ✅ FIXED in b6b0677 |
| Seed Data | ⚠️ Security | DevOps + Vikram | `V7__*.sql`, `V72__*.sql` | ⏳ NOT FIXED |

---

## Timeline

| Phase | Owner | Duration | Status |
|-------|-------|----------|--------|
| 1. Code Review | Kavya | 1 hour | ✅ CLAIMED |
| 2. Build | Meera | 30 min | ⏳ PENDING |
| 3. Live Tests | Meera | 2 hours | ⏳ PENDING |
| 4. Security | Kabir | 1 hour | ✅ CLAIMED |
| 5. Outstanding Issues | Vikram/Infra | TBD | ⏳ NOT STARTED |
| 6. CTO Approval | Priya | 30 min | ⏳ PENDING |

**Total Estimated:** 5 hours for phases 1–4, 6 (verification of b6b0677 fixes)

---

## Success Criteria

### PASS Conditions
- ✅ All 8 claimed fixes in b6b0677 verified working in LIVE deployment
- ✅ Build passes with no errors
- ✅ Kabir security review finds no Critical/High issues
- ✅ Priya signs off

### FAIL Conditions (Block Deploy)
- ❌ Any claimed fix does NOT work in live
- ❌ Build fails
- ❌ Kabir finds Critical/High security issue
- ❌ New regressions introduced

### Outstanding Work (Post-Verification)
- Issue #6 (email) + #8 (Shopify) + seed data cleanup remain open
- These do NOT block verification of b6b0677 fixes, but DO block production launch

---

## Documentation Outputs

All verification results written to:

1. `wiki/build/verification-meera-build-2026-07-23.md` (Meera build log)
2. `wiki/build/verification-meera-live-2026-07-23.md` (Meera live test results)
3. `wiki/build/verification-kabir-security-2026-07-23.md` (Kabir security review)
4. `wiki/build/verification-priya-approval-2026-07-23.md` (Priya final sign-off)
5. `SHARED_CONTEXT.md` (pipeline status updates from Arjun)

---

## Arjun's Next Actions

1. ✅ Write this verification plan
2. Dispatch to Meera: build + live tests (Phases 2 & 3)
3. Dispatch to Kabir: security re-check (Phase 4)
4. Monitor progress, unblock agents
5. Escalate to Priya when phases 1–4 complete
6. Route outstanding issues (#6, #8, seed data) to appropriate owners
7. Write daily log to `wiki/processes/daily-log.md`
8. Update `SHARED_CONTEXT.md` with pipeline status

---

**Status as of 2026-07-23 07:00 IST:** Plan written, ready for execution. Awaiting team dispatch.
