# QA Report: Brand User Journey
Date: 2026-07-05
Reviewer: Kavya (QA Lead)
Status: FINDINGS DOCUMENTED

---

## Journey Tested
1. Landing page → registration form
2. Registration validation (email format, password strength)
3. OTP/email verification flow
4. Login form → dashboard redirect
5. Workspace creation flow
6. Meera chat panel — send message functionality
7. Voice input (if wired)

---

## CRITICAL ISSUES (must fix before any user testing)

### 1. Missing Root Landing Page Route
**File:** `src/App.tsx:281`
**Issue:** Landing page is at `/` but no route protection exists. Authenticated users hitting `/` see landing instead of being redirected to dashboard.
**Expected:** Protected route logic should redirect logged-in brand users from `/` → `/brand/dashboard`
**Impact:** Confusing UX — signed-in users see marketing page instead of their workspace

### 2. No Email OTP Verification Flow Wired
**File:** `src/pages/brand-register.tsx`
**Issue:** Registration form has NO OTP input or verification step. Email is collected but never verified before account creation.
**File:** `src/components/brand/onboarding/onboarding-steps.tsx:66-68` shows `emailOtpSent`, `emailOtpCode`, `emailOtpVerified` fields exist in type definition but are NOT implemented in the registration page.
**Expected:** After email/password submit, show OTP input → verify → then proceed to onboarding
**Impact:** HIGH SECURITY RISK — no email ownership proof. Spam/fake accounts possible.

### 3. Password Strength Indicator Missing
**File:** `src/pages/brand-register.tsx:259-286`
**Issue:** Password field shows helper text "Min 8 characters with uppercase, lowercase, and a number" but NO visual strength meter or real-time validation feedback.
**Expected:** Live password strength bar (weak/medium/strong) as user types, like GitHub/Google registration.
**Impact:** Poor UX — user only sees error AFTER submitting, not during typing.

### 4. Onboarding Redirect Logic Broken
**File:** `src/pages/brand-register.tsx:107`
**Issue:** Line 107 has redundant ternary: `navigate(hasBrandToken() ? '/brand/onboarding' : '/brand/onboarding');` — both branches go to same route.
**Expected:** Should check `onboardingComplete` status and redirect to `/brand/dashboard` if done, `/brand/onboarding` if not.
**Impact:** Users completing onboarding may be stuck in loop.

### 5. Generic Error Messages
**File:** `src/pages/brand-login.tsx:36-39`, `src/pages/brand-register.tsx:109-111`
**Issue:** Error handling shows generic "Login failed. Please try again." or "Registration failed. Please try again."
**Expected:** Specific errors like "Email already registered", "Invalid credentials", "Email format invalid", "Network error"
**Impact:** Users don't know WHY login/registration failed. Bad UX.

---

## HIGH PRIORITY (fix before delivery)

### 6. No Loading States on Login/Register Forms
**File:** `src/pages/brand-login.tsx:22-42`, `src/pages/brand-register.tsx:83-115`
**Issue:** Forms show disabled button with "Signing in..." or "Creating..." text but NO spinner or skeleton loaders in the page.
**Expected:** Full-page loading state or button spinner animation
**Impact:** User unsure if action is processing on slow networks.

### 7. Missing Aria-Labels on Password Toggle Icons
**File:** `src/pages/brand-register.tsx:274-281`, `src/pages/brand-login.tsx:106-113`
**Issue:** Eye/EyeOff icons have `aria-label` on button but icon itself has `aria-hidden` missing.
**Expected:** Icons should have `aria-hidden="true"` to avoid duplicate announcements.
**Impact:** Accessibility — screen readers announce icon AND button text twice.

### 8. Terms of Service Links Are Non-Functional
**File:** `src/pages/brand-register.tsx:330-332`
**Issue:** "Terms of Service" and "Privacy Policy" are `<button type="button">` elements with NO onClick handler.
**Expected:** Either `<Link to="/terms">` or modal popup.
**Impact:** Users cannot actually read terms before agreeing.

### 9. Company Slug Generation Not Implemented
**File:** `src/components/brand/onboarding/onboarding-steps.tsx:72`
**Issue:** `companySlug` field exists in type but auto-generation from `companyName` is NOT implemented.
**Expected:** As user types company name, slug should auto-populate (e.g., "My Brand Co" → "my-brand-co")
**Impact:** User may leave slug empty or enter invalid characters.

### 10. No Dashboard Data Loading State
**File:** `components/brand/dashboard/dashboard-page.tsx:38-193`
**Issue:** Dashboard shows MOCK data (hardcoded arrays). No API fetch logic, no skeleton loaders while data loads.
**Expected:** Fetch real data from `/api/v1/brand/dashboard`, show skeleton cards while loading.
**Impact:** User sees fake/stale data. Broken in production.

### 11. Voice Input Button Rendered But Hook May Fail Silently
**File:** `src/components/feature/meera/Composer.tsx:87-89`
**Issue:** `voiceInputSupported` is checked, but if `useVoiceInput` hook returns `false` for `supported`, mic button is hidden. NO user feedback explaining WHY (browser limitation, permissions denied, etc.)
**Expected:** If STT unsupported, show tooltip "Voice input not available in this browser" on hover over disabled mic icon.
**Impact:** User confused why voice feature is missing.

---

## MEDIUM PRIORITY (fix when possible)

### 12. No Forgot Password Flow Wired
**File:** `src/pages/brand-login.tsx:118-123`
**Issue:** "Forgot password?" link goes to `/brand/forgot-password` but that page is NOT implemented (missing component).
**Expected:** Password reset flow with email OTP or magic link.
**Impact:** Users with forgotten passwords cannot recover accounts.

### 13. Mobile Responsiveness Issues in Dashboard
**File:** `components/brand/dashboard/dashboard-page.tsx:218-249`
**Issue:** Stats grid uses `sm:grid-cols-2 lg:grid-cols-4` but cards may overflow on very small screens (<350px width).
**Expected:** Add `overflow-x-auto` or ensure min-width constraints.
**Impact:** Horizontal scroll or layout break on small mobile devices.

### 14. Meera Chat Panel Has No Error Boundary
**File:** `src/components/feature\meera\MeeraChatPanel.tsx:58-100`
**Issue:** If 3D canvas or TTS fails, entire Meera panel crashes. No `<ErrorBoundary>` wrapping the component.
**Expected:** Wrap in error boundary, show "Meera is unavailable" fallback UI.
**Impact:** One component failure breaks entire chat interface.

### 15. No Email Format Validation on Step 1 Field Blur
**File:** `src/pages/brand-register.tsx:240-255`
**Issue:** Email validation only runs on form submit, not on blur. User types invalid email, moves to next field, sees NO feedback until submit.
**Expected:** Validate email on blur event, show inline error immediately.
**Impact:** Minor UX issue — user doesn't know email is invalid until submitting whole form.

### 16. Campaign Cards in Dashboard Use Next.js `Link` Instead of React Router
**File:** `components/brand/dashboard/dashboard-page.tsx:4`
**Issue:** Component imports `Link` from `next/link` but this is a **Vite + React Router** project. Next.js imports will fail at runtime.
**Expected:** Import from `react-router-dom`.
**Impact:** CRITICAL — all dashboard navigation links are broken.

---

## LOW PRIORITY / POLISH

### 17. No Keyboard Navigation in Quick Reply Chips
**File:** `src/components/feature/meera/Composer.tsx:64-69`
**Issue:** Quick reply chips have `onClick` but no `tabIndex` or keyboard event handlers. Keyboard-only users cannot select chips.
**Expected:** Add `tabIndex="0"` and `onKeyDown={(e) => e.key === 'Enter' && ...}`.
**Impact:** Accessibility — keyboard users must type instead of using quick replies.

### 18. File Upload Progress Bar in Onboarding Not Implemented
**File:** `src/components/brand/onboarding/onboarding-steps.tsx:45-50`
**Issue:** `uploadToR2`, `validateFile`, `formatFileSize` imported but NO progress bar shown during upload.
**Expected:** Progress bar component showing upload percentage.
**Impact:** User unsure if upload is working on slow connections.

### 19. No "Remember Me" Checkbox on Login
**File:** `src/pages/brand-login.tsx:70-138`
**Issue:** Login form has no "Remember me" option. Token always expires after session.
**Expected:** Optional checkbox to extend token TTL.
**Impact:** Minor inconvenience — users must log in frequently.

### 20. Landing Page Stats Use Mock CountUp Animation But No Data Source
**File:** `src/pages/landing.tsx:38-42`
**Issue:** Stats show hardcoded values (8915 creators, ₹4.26Cr paid). These should be real-time from backend.
**Expected:** Fetch from `/api/v1/public/stats` endpoint.
**Impact:** Marketing claims not verifiable.

---

## ROUTING ISSUES

### 21. Duplicate App Directories Detected
**Location:** `/app/` and `/src/app/` both exist
**Issue:** `find` command found pages in both `/app/brand/` and `/src/app/brand/`. Only ONE should be active.
**Expected:** Next.js uses `/app/`, but `src/main.tsx` suggests Vite+React Router. CONFLICTING setup.
**Impact:** Deployment confusion — which routing system is actually used?

### 22. ProtectedRoute Demo Bypass is Security Risk
**File:** `src/App.tsx:48`
**Issue:** Line 48 allows `?demo=true` query param to bypass auth in dev mode. Comment says "dev-only" but `import.meta.env.DEV` check can be manipulated if `.env` files misconfigured.
**Expected:** Remove demo bypass OR add IP whitelist check (localhost only).
**Impact:** If `.env` accidentally sets DEV=true in production, auth is bypassable.

---

## SUMMARY

**Total Issues Found:** 22
- CRITICAL: 5
- HIGH: 6
- MEDIUM: 5
- LOW: 6

**Blockers for user testing:**
1. Email OTP verification not implemented (Issue #2)
2. Dashboard navigation links broken (Next.js imports in Vite app) (Issue #16)
3. Onboarding redirect logic broken (Issue #4)
4. No real API data loading in dashboard (Issue #10)
5. Routing system conflict between Next.js and React Router (Issue #21)

**Next Steps:**
Route back to **Ananya** (frontend dev) for fixes to Issues #1, #3, #4, #7, #8, #12, #15, #16, #17, #19, #21, #22.
Route to **Vikram** (backend dev) for Issues #2 (OTP API), #5 (error codes), #10 (dashboard API), #20 (stats API).
Route to **Priya** (CTO) for architectural decision on Issue #21 (routing system conflict).

Re-submit fixed code for QA review.
