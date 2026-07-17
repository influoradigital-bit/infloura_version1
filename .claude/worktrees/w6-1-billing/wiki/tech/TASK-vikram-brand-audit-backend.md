# TASK — Vikram (Backend) — Brand UX Audit Follow-ups

> **From:** Priya (CTO)
> **Date:** 2026-07-06
> **Source:** Tejas CMO first-time brand UX audit (17 questions)
> **Priority:** Read the CTO ruling FIRST. It changes what is actually actionable.

---

## ⚠️ CTO RULING — READ BEFORE YOU TOUCH ANYTHING

The audit flagged **"Registration form broken (Question 6)"** as a CRITICAL launch blocker. **I have verified this is a FALSE POSITIVE. Do NOT rewrite the registration or login forms. They work.**

### Proof (I tested this myself in the live browser)
- `src/pages/brand-register.tsx` uses standard controlled React inputs: `useState` + `onChange={(e) => setEmail(e.target.value)}` (lines 33-41, 322-434). Selects use Radix `onValueChange`. This is textbook-correct.
- The auditor filled fields with `element.value = 'x'` + a synthetic `input` event. React installs its own value-setter interceptor (`_valueTracker`) on inputs, so that method does **not** update React state — validation then fires "required." **This is a test-automation artifact, not a product bug.**
- I re-ran the flow using React's **native value setter** (what real human typing / `userEvent` triggers). Result: Step 1 validated cleanly and advanced to Step 2 (progressbar `aria-valuenow="2"`, email field rendered). **The form works for real users.**

**Action on Question 6:** None. If you want belt-and-suspenders, see the OPTIONAL hardening item below — but it is NOT a blocker and NOT required for launch.

---

## WHAT IS ACTUALLY YOURS (real backend items from the audit)

### 1. Q9 — Campaign analytics / goal tracking (VERIFY, then report)
The auditor couldn't confirm whether a brand can see reach / engagement / ROI after a campaign runs.
- **Check:** Does `src/pages/brand-campaign-detail.tsx` render real analytics (reach, impressions, engagement), and is there a backend endpoint feeding it? Search `influora-api` for campaign-analytics / metrics endpoints.
- **Report back:** (a) endpoint exists + wired, (b) endpoint exists + NOT wired to UI, or (c) doesn't exist. If (c), that's a real product gap — flag it to me and I'll scope with Swapnil. Do not build a new analytics subsystem without sign-off.

### 2. Q13 — Contract delivery to email (VERIFY, then report)
Auditor couldn't tell if a signed contract is emailed as a PDF to brand + creator.
- **Check:** Is there a contract-PDF generation + email path in `influora-api`? Cross-reference `docs/MSG91-EMAIL-OTP.md` (we use MSG91 for email). Look for any contract/document email template.
- **Report back:** current behavior (in-app-only vs email notification vs email PDF). If PDF delivery is missing and Swapnil wants it, I'll open a separate ticket. **Do not** wire real MSG91 credentials — placeholders only (`REPLACE_WITH_YOUR_*`), per standing security rule.

### 3. Q17 — Wallet runway calculation (VERIFY)
Dashboard shows "47d runway." Confirm the burn-rate math is real (backed by actual spend data) and not a hardcoded/mock number. If mock, label it clearly in code and tell me.

---

## OPTIONAL — form hardening (NOT a blocker, do only if items 1-3 are done)

Modern browser **autofill** (Chrome password manager) occasionally sets an input's value without firing a React-visible `input` event on some component versions. Our form is standard and this is an edge case, but if you want to bulletproof it: add an `onInput`/`onAnimationStart` autofill-detection guard on the register/login inputs, or re-read values on submit via refs as a fallback. **Verify a real bug reproduces with actual autofill before writing any code** — do not fix a hypothetical.

---

## RULES
- Verify-and-report first. Only two of these (analytics gap, PDF delivery) might become build work, and only with Swapnil sign-off.
- No new npm packages without logging in `wiki/tech/approved-deps.md` and my approval.
- Real secrets stay out — placeholders only.
- All code goes through Kavya (QA) then Meera (build verify).

## REPORT BACK TO PRIYA
One block: for Q9/Q13/Q17 — what exists, what's wired, what's missing. Flag anything that needs Swapnil's product decision. Do NOT report the registration form as fixed — report it as **verified working (false positive), no change made.**
