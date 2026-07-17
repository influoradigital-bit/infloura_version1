# Creator Sprint Kickoff — Week 1

> **Date:** 2026-07-09 00:53 IST  
> **Orchestrator:** Arjun Kapoor  
> **Sprint:** Week 1 — Auth + Profile + OAuth  
> **Target:** 50% → 70% completion

---

## Initial Assessment

**Current Status:** ~50% complete (see `CREATOR_PROGRESS.md`)

**P0 Blockers:**
1. Mock auth in all creator pages (security risk)
2. No real backend API integration
3. No OAuth flows working
4. No campaign discovery system

**Week 1 Focus:** Replace mock auth with real JWT backend, complete profile CRUD, wire Instagram/YouTube OAuth.

---

## First Wave Tasks Assigned

### Vikram (Backend) — 3 Tasks

#### Task #1: Backend Auth System (P0 BLOCKING)
**Deadline:** 2026-07-11  
**Files:** `src/api/auth/creator.ts`, `src/middleware/auth.ts`

**Subtasks:**
1. Read TECH-STACK.md (mandatory first step)
2. Build 9 auth endpoints:
   - Signup (email, phone, verify OTP, complete)
   - Login, refresh, logout
   - Forgot password, reset password
3. JWT middleware for route protection
4. MSG91 OTP integration
5. bcrypt password hashing (cost 12)
6. Redis OTP storage (5min TTL)

**Reference:** `wiki/tech/creator/01_CREATOR_AUTH_SPEC.md`

**Success Criteria:**
- All endpoints working
- JWT tokens issued correctly
- OTP generation + verification working
- Passes Kavya QA + Kabir security audit

**Next:** After done → Ananya Task #4 (wire frontend)

---

#### Task #2: Profile CRUD Backend (P0)
**Deadline:** 2026-07-12  
**Depends on:** Task #1 (auth middleware)  
**Files:** `src/api/creator/profile.ts`

**Subtasks:**
1. Build 4 profile endpoints:
   - GET profile
   - PUT profile (update)
   - POST portfolio item
   - DELETE portfolio item
2. Validate niche against allowed list
3. Handle portfolio media + metrics

**Reference:** `wiki/tech/creator/02_CREATOR_PROFILE_SPEC.md`

**Success Criteria:**
- Profile CRUD working
- Portfolio items add/delete
- Niche validation works
- Passes Kavya QA + Kabir security audit

**Next:** After done → Ananya Task #5 (profile editor UI)

---

#### Task #3: OAuth Integration Backend (P0)
**Deadline:** 2026-07-13  
**Depends on:** Task #1 (auth system)  
**Files:** `src/api/oauth/instagram.ts`, `src/api/oauth/youtube.ts`

**Subtasks:**
1. Instagram OAuth flow (redirect, callback, fetch profile + followers)
2. YouTube OAuth flow (redirect, callback, fetch channel + subscribers)
3. Store tokens securely (encrypted)
4. Token refresh logic

**Reference:** `wiki/tech/creator/03_CREATOR_OAUTH_CONNECT_SPEC.md`

**Success Criteria:**
- Instagram OAuth working
- YouTube OAuth working
- Tokens stored + encrypted
- Passes Kabir security review

**Next:** After done → Ananya Task #6 (OAuth buttons UI)

---

### Ananya (Frontend) — 3 Tasks

#### Task #4: Frontend Auth Pages (P0)
**Deadline:** 2026-07-12  
**Depends on:** Vikram Task #1  
**Files:** `src/pages/creator-login.tsx`, `src/pages/creator-register.tsx`, `src/lib/api.ts`

**Status:** 🟡 Waiting for Vikram Task #1 to complete

**Subtasks:**
1. Read TECH-STACK.md (mandatory first step)
2. Remove mock auth from login/register pages
3. Wire to real backend API
4. Create `apiClient` with JWT auto-attach
5. Handle errors + loading states

**Reference:** `wiki/tech/creator/01_CREATOR_AUTH_SPEC.md`  
**Pattern:** Check `brand-login.tsx` for reference

**Success Criteria:**
- Login calls real API
- Register completes 3-step flow
- JWT stored + auto-attached
- Error handling with toasts
- Passes Kavya QA review

**Next:** After done → Task #5 (profile editor)

---

#### Task #5: Profile Editor UI (P0)
**Deadline:** 2026-07-13  
**Depends on:** Vikram Task #2  
**Files:** `src/pages/creator-profile.tsx`, `src/pages/creator-portfolio-editor.tsx`

**Status:** 🟡 Waiting for Vikram Task #2 to complete

**Subtasks:**
1. Build profile editor form (bio, niche, rates, location, socials)
2. Fetch existing profile
3. Save button → PUT API
4. Build portfolio editor (add, delete items)

**Reference:** `wiki/tech/creator/02_CREATOR_PROFILE_SPEC.md`

**Success Criteria:**
- Profile form loads data
- Save updates successfully
- Portfolio add/delete works
- Form validation
- Passes Kavya QA review

**Next:** After done → Task #6 (OAuth buttons)

---

#### Task #6: OAuth Connect UI (P0)
**Deadline:** 2026-07-14  
**Depends on:** Vikram Task #3  
**Files:** `src/pages/creator-onboarding.tsx`, `src/pages/creator-profile.tsx`

**Status:** 🟡 Waiting for Vikram Task #3 to complete

**Subtasks:**
1. Wire OAuth buttons to redirect URLs
2. Display connected status after callback
3. Show profile pic + follower count
4. Add disconnect button

**Reference:** `wiki/tech/creator/03_CREATOR_OAUTH_CONNECT_SPEC.md`

**Success Criteria:**
- OAuth buttons redirect correctly
- Connected accounts display
- Disconnect works
- Passes Kavya QA review

**Next:** Week 1 complete → Arjun reports progress

---

## Pipeline Flow

```
Week 1 Pipeline:

Vikram Task #1 (Auth Backend) → Ananya Task #4 (Auth Frontend) → Kavya QA → Kabir Security
    ↓
Vikram Task #2 (Profile Backend) → Ananya Task #5 (Profile Frontend) → Kavya QA
    ↓
Vikram Task #3 (OAuth Backend) → Ananya Task #6 (OAuth Frontend) → Kavya QA → Kabir Security
    ↓
Meera: Build verification (npm run build, test, lint)
    ↓
Priya: Final architecture review
    ↓
Week 1 Complete (50% → 70%)
```

---

## Quality Gates

### Kavya (QA Review)
Runs after each task completion:
- Code follows TECH-STACK.md standards
- No console.logs or debug code
- Proper error handling
- TypeScript types strict
- Test coverage adequate

**If issues found:** Route back to Vikram/Ananya to fix.

---

### Kabir (Security Audit)
Runs after auth + OAuth tasks:
- No SQL injection vectors
- No XSS risks
- Password hashing correct
- JWT secret strong
- OTP generation secure
- Rate limiting implemented
- OAuth state tokens validated

**If Critical/High findings:** BLOCK Week 2 until fixed.

---

### Meera (Build Verification)
Runs at end of Week 1:
```bash
npm run build   # Must pass
npm run dev     # Must start
npm run test    # Must pass
npm run lint    # No errors
```

**If build fails:** Route back to Vikram/Ananya to fix.

---

### Priya (Architecture Review)
Final review before Week 2:
- Code follows TECH-STACK.md
- No architectural anti-patterns
- Performance acceptable
- Maintainable code

**If changes needed:** Route back with specific feedback.

---

## Progress Tracking

**Arjun updates CREATOR_PROGRESS.md after each task completion.**

Update format:
```markdown
### [Date Time] — [Task] Complete
- **Who:** [Agent name]
- **Files changed:** [List]
- **New %:** [Updated percentage]
- **Next:** [Next action]
```

---

## Communication

### Daily Standup (Async via SHARED_CONTEXT.md)
Each agent writes:
```markdown
### [Agent Name] — [Date]
**Yesterday:** [What I completed]
**Today:** [What I'm working on]
**Blockers:** [Any issues]
```

### Escalation to Arjun
- Task ambiguous → Arjun clarifies
- Dependency blocked → Arjun routes
- Need help → Arjun coordinates

### Escalation to Priya
- Architecture question → Priya decides
- Performance issue → Priya investigates
- TECH-STACK.md clarification → Priya answers

---

## Success Criteria (Week 1 End)

**Functionality:**
- [ ] Creator can login with real JWT auth
- [ ] Creator can register with email/phone OTP
- [ ] Creator can setup profile + portfolio
- [ ] Creator can connect Instagram + YouTube via OAuth

**Quality:**
- [ ] All code passes Kavya QA review
- [ ] No Critical/High security findings (Kabir)
- [ ] All builds green (Meera)
- [ ] Architecture approved (Priya)

**Progress:**
- [ ] CREATOR_PROGRESS.md updated to 70%
- [ ] TASK_INBOX.md updated with Week 2 tasks

---

## Next Steps After Week 1

1. Arjun reports Week 1 completion to Swapnil
2. Arjun creates Week 2 tasks in TASK_INBOX.md
3. Arjun updates CREATOR_PROGRESS.md with new %
4. Loop continues checking progress every 30min

---

**End of Kickoff Document**

**Status:** Week 1 tasks assigned. Waiting for Vikram to start Task #1.
