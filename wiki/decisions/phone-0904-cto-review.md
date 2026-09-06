# PHONE-0904 — CTO review + Required/Optional ruling

> ## ⚠️ PART 1 IS SUPERSEDED — see [Addendum PHONE-0906](#addendum-phone-0906--creator-signup-phone-is-now-required) at the foot of this file
>
> As of **2026-09-06**, creator phone is **REQUIRED at signup** (`AuthService#creatorRegister`),
> by Swapnil's ruling, over the Part 1 ruling below. Part 1 is kept verbatim because its
> *reasoning* — in particular the no-release-path dead-end — is unchanged and is now a shipped,
> accepted risk rather than a hypothetical. **Read Part 1 as the argument, not as the current
> state.** PART 2 (the architecture review, D1–D5) is untouched and still current.

**Author:** Priya (CTO)
**Date:** 2026-09-04
**Ticket:** PHONE-0904 (`TASK_INBOX.md`), handoff in `SHARED_CONTEXT.md`
**Verdict:** **CHANGES REQUIRED** — 1 HIGH, 3 MEDIUM, 2 LOW. Ruling below is final and unblocks Kavya's re-review.

---

## PART 1 — THE RULING (Required vs Optional)

> **Creator onboarding phone stays OPTIONAL. Approved as built, no code change. Documented as intentional.**
> **Brand onboarding phone being REQUIRED is the side that is wrong** — logged as a follow-up defect, explicitly NOT in this diff.

### Reasoning

Ananya's rationale (one field, two rules, because Settings allows clearing) is correct but it is the
*second* reason. The first reason is a hard-failure mode, and it decides the question on its own.

`users.phone_number` is `UNIQUE` (`influora-api/src/main/resources/db/migration/V2__core_auth.sql:6`)
and **is never verified**. There is no SMS OTP anywhere in this platform — OTP is email-only
(`AuthService.brandRegister` → `brandEmailOtpService.requireVerifiedEmail`). So we enforce global
uniqueness on an unauthenticated, unproven string. That combination costs us both ways and pays us
neither:

1. **No release path.** Nothing in the codebase can free a number from an account. `AdminCreatorService`
   *reads* phone (`:185`, `:523`) and never writes it. The only release is `User.softDelete()`
   (`User.java:301-315`) nulling it — i.e. deleting the whole account. A user who hits
   `PHONE_ALREADY_EXISTS` has **no in-app action that resolves it**.
2. **Cross-type collision is real, not hypothetical.** Brand and creator accounts are separate `users`
   rows (`User.java:105` `newBrand`, `:131` `newCreator`). One human who is both a brand user and a
   creator — an exceedingly normal thing on a marketplace — collides with *their own* number.
3. **Shared numbers are normal in this market.** A family line, an agency office line, a managed
   creator whose manager's number is on file. This codebase already models representation
   (`CreatorAgentPreferences.represented` / `agency_name`).
4. **Squatting.** Because the number is unverified, anyone can claim any mobile number at brand signup
   and permanently deny it to its real owner, with no recovery. Making the field REQUIRED upgrades that
   from an annoyance to an account-creation denial-of-service.
5. **The platform has demonstrably never needed it.** `users.phone_number` was NULL for every user
   until PHONE-0829. Nothing depends on it.

A REQUIRED + globally-UNIQUE + UNVERIFIED field is a signup path that can dead-end with no recovery.
That is the same shape as the ADMIN-BOOTSTRAP-0829 lockout already in `TASK_INBOX.md` — a gate whose
own javadoc predicted the deadlock and shipped anyway. I am not signing off a second one.

What the phone is actually FOR — "Brands and Influora use this to reach you about deals" — is
contactability. It is a contact channel, not a credential and not an identity key. Contactability is
served by asking prominently and well; it is not served by refusing to create the account.

### Is the asymmetry defensible?

No — it is an accident. But **the creator side is the correct one**, so the fix runs the opposite
direction to the one Kavya's escalation implied. Do not "align" by making creator required.

### Actions

- **Creator onboarding: OPTIONAL — APPROVED, no code change.** The rationale above is now the record;
  future reviewers should not re-flag it. Ananya's in-file comment at `src/pages/creator-onboarding.tsx:172`
  should gain a one-line pointer to this document. Owner: **Ananya** (comment only).
- **Brand onboarding: REQUIRED is a defect.** `src/components/brand/onboarding/onboarding-steps.tsx:450`
  (`if (!data.phone) e.phone = 'Required'`). Downgrade to optional, keep the field prominent and
  first-class in the layout. **New ticket — NOT this diff.** Owner: **Ananya**, dispatched by **Arjun**.
- **REQUIRED becomes available to us again only if both ship:** (a) SMS verification of the number, and
  (b) a support/admin release path. Until then no user-facing surface may make phone mandatory.
  **⚠️ OVERRULED TWICE, (a) and (b) still not shipped:** brand signup by PHONE-0904 Q8, creator
  signup by PHONE-0906. Both surfaces are mandatory today with neither precondition met.
- If Swapnil wants brand phone mandatory for commercial reasons, that is his call to overrule — but it
  needs (a) and (b) funded first, not waived. Escalating.
  **⚠️ He did overrule, and waived both preconditions, on both surfaces.** See the addendum.

---

## PART 2 — ARCHITECTURE REVIEW

### D1 — HIGH — `UserPhoneService.applyPhone`'s TOCTOU catch is unreachable dead code

**File:** `influora-api/src/main/java/com/influora/service/UserPhoneService.java:99-106`

```java
user.setPhoneNumber(normalized);
try {
    userRepository.save(user);          // <-- issues no SQL
} catch (DataIntegrityViolationException dup) {
    throw new ApiException("PHONE_ALREADY_EXISTS", ..., HttpStatus.CONFLICT);
}
```

Both callers are `@Transactional` (`CreatorOnboardingService.java:90`, `CreatorProfileService.java:63`)
and `user` is a **managed** entity in both. `SimpleJpaRepository.save` on a managed entity is a merge
that emits no SQL; Hibernate defers the INSERT/UPDATE to flush. Nothing after this call queries `users`,
so flush happens **at commit** — after this catch block's scope has already exited. The
`DataIntegrityViolationException` from a raced unique-constraint violation therefore never enters this
catch, and `PHONE_ALREADY_EXISTS` is never thrown from here.

The javadoc at `:78-81` states the opposite as a guarantee: *"the try/catch below just translates a raced
DataIntegrityViolationException into the same clean 409 the pre-check path returns, instead of letting it
fall through to a raw 500."* That is false.

**This codebase already knows the rule, in the file Vikram edited in this same diff.**
`AuthService.java:178-183`:

> *"saveAndFlush on the user row (the one with the UNIQUE(email) constraint) is what makes the catch block
> below actually able to see the race ... an unflushed save() here would defer the constraint check to the
> surrounding @Transactional's commit, which happens after this catch block's scope has already exited,
> letting the violation escape as an uncaught 500 instead of the intended 409."*

`SubscriptionService.java:669,790`, `CreatorInvoiceCodeService.java:98` and `DisputeService.java:174-178`
all follow the same discipline with the same reasoning spelled out. `UserPhoneService` is the only
constraint-race handler in the service layer that uses plain `save()`. The extraction carried the
*shape* of the guard across and dropped the mechanism that made it work.

**Why every gate missed it.** `CreatorProfileServiceTest.java:357`:

```java
when(userRepository.save(user)).thenThrow(new DataIntegrityViolationException("dup"));
```

A Mockito stub throwing synchronously from `save()` — something real Hibernate never does inside a
transaction. The test greens dead code and asserts a guarantee production does not provide. Same class
of vacuously-passing gate as the `feedback_mockito_tests_skip_schema_validation` and
`feedback_ci_gates_passing_vacuously` records. 172 backend tests and two review passes cannot see this.

**Blast radius today: contained, by luck.** `GlobalExceptionHandler.java:110-115` (Kabir I-1c) maps
`DataIntegrityViolationException` → 409 `DATA_INTEGRITY_VIOLATION`, and both clients branch on
`err.status === 409`, not on the code (`creator-onboarding.tsx:203`, `creator-settings.tsx:197`). So the
user-visible outcome is currently correct for the wrong reason. It breaks the moment any client, Meera
tool, or third-party consumer keys on the `PHONE_ALREADY_EXISTS` code the javadoc promises — and the
error message degrades from actionable to "The request could not be completed due to a data conflict".

**Fix:** `saveAndFlush` at `UserPhoneService.java:100`, carrying the `AuthService.java:178` rationale into
the javadoc. Also correct the false claim at `:78-81`.
**Owner: Vikram.**
**Kavya: reject a re-submission that only moves the Mockito stub from `save` to `saveAndFlush`.** A mock
cannot prove flush timing. This needs a real-DB integration assertion, or the claim must be removed from
the javadoc rather than tested into existence.

---

### D2 — MEDIUM — three contradictory null contracts on one class; blank phone silently clears at onboarding

**Files:** `UserPhoneService.java:48-51, 73-76`; `CreatorOnboardingService.java` (new PHONE-0904 block)

The same class documents `null` three different ways:

| Site | Documented meaning of null/blank |
|---|---|
| `normalizeAndValidate` javadoc (`:48-51`) | "no phone supplied" — returns null |
| `applyPhone` javadoc (`:73-76`) | **clears** the stored number |
| `CreatorOnboardingService` new comment | "no phone captured this step ... a no-op, **never an error** — `UserPhoneService#applyPhone`'s own convention" |

The third is wrong about the second. `applyPhone(user, null)` does not no-op, it **destroys** the stored
number. The no-op comes entirely from the caller's `if (req.phone() != null)` guard, not from the service.
A shared seam whose whole purpose is preventing drift must not describe its own contract three ways —
that is the next drift, pre-installed.

The behavioural consequence: `POST /onboarding/creator/profile` with `{"phone": ""}` is non-null, passes
the guard, and **clears** any phone the user already has. Our client cannot trigger it today — it sends
`undefined` (`creator-onboarding.tsx:198-199`, `normalizedPhone || undefined`) — but the server contract
permits a silent destructive write on a step the user experiences as purely additive. "Clear my phone" is
a legitimate intent in Settings; it is not a legitimate intent in an onboarding wizard.

**Fix:** guard on `!isBlank()` rather than `!= null` in `CreatorOnboardingService`, **or** split
`applyPhone` from an explicit `clearPhone` so the destructive path has to be asked for by name. Collapse
the three javadocs to one sentence stated once. **Owner: Vikram.**

---

### D3 — MEDIUM — phone-existence oracle on two unthrottled routes

`POST /onboarding/creator/profile` and `PATCH /me/creator-profile` return a distinguishable 409 when a
number belongs to somebody and 200 when it does not. `AuthRateLimitFilter.bucketFor`
(`influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java:305-402`) matches neither path
and returns `null` → no bucket → **no throttle at all**. Note `/me/password` at `:398` is deliberately
exact-match "so this cannot accidentally throttle other `/me/...` routes", which means `/me/creator-profile`
is knowingly outside every bucket.

One authenticated creator account can therefore answer "does this specific mobile number have an Influora
account?" at unlimited rate. `brandRegister` has the identical oracle but sits behind the `/auth/`
"sensitive" bucket (`:385`), so the unauthenticated surface is covered and the authenticated one is not.

Pre-existing from PHONE-0829 for the Settings route — but PHONE-0904 adds a **second** unthrottled surface
to the same oracle, which puts it in scope.

**Fix:** a `phone-write` bucket covering both paths. **Owner: Vikram**, scope confirmed by **Kabir**.

---

### D4 — MEDIUM (design) — UNIQUE on an unverified field with no release path

The substance is in Part 1 (points 1–4) and is the basis of the ruling; recording it here as a standing
architectural debt rather than a PHONE-0904 defect.

**Is 409 the right answer for a cross-type / shared-number collision?** 409 is the right *status* — the
state genuinely conflicts. It is the wrong *product answer* while the field is required, because there is
no action the user can take that resolves it. On an optional field 409 is fine: the user proceeds without
a phone and contacts support. That is precisely why the ruling and this finding are the same finding.

**Follow-up ticket (not this diff):** either drop the `UNIQUE` constraint and treat phone as an ordinary
contact column, or add SMS verification plus an admin release path. Spec owner: **Priya**. Funding call:
**Swapnil**.

---

### D5 — LOW — the ADR this ticket defers to does not exist

`wiki/decisions/2026-07-10-pii-email-phone-encryption.md` is cited as the governing authority by
`EmailPhonePiiCipher.java:14` and twice in `TASK_INBOX.md` (`:206`, `:244`). **The file is not in
`wiki/decisions/`.** The "standing CTO ruling" everyone has been deferring to is a dangling citation.

**The ruling itself is nevertheless intact and this work does not pre-empt or violate it.** Verified:
this diff adds no new persistence path (`applyPhone` is the single existing writer), does not widen
`users.phone_number` (still `VARCHAR(20)`, `V2__core_auth.sql:6`), introduces no blind index, and stores
phone plaintext exactly as `User.email` already is. `EmailPhonePiiCipher` remains a live-but-unwired
scaffold, untouched. **Ruling reaffirmed: plaintext for now; email and phone migrate together or neither.**

**Fix:** I write the missing ADR. **Owner: Priya.**

---

### D6 — LOW (hygiene) — PHONE-0904 is not separable from this working tree

`src/lib/api.ts` carries PHONE-0904's `phone?: string` interleaved with T-MEERA-CREATOR-PHASE-A's
`dealTerms`, `contracts.listUnsigned`, `floor_currency`, `working_hours_timezone`, `consent_version`.
`CreatorOnboardingService.java`, `OnboardingDtos.java` and `CreatorOnboardingController.java` additionally
carry the **F-0450 deletion of `POST /onboarding/creator/payout`** — a route removal that has nothing to do
with phone capture and must not land under a phone ticket's commit message.

This is the documented concurrent-session hazard (`project_concurrent_session_write_collisions`).
**Owner: Arjun** — stage by hunk, or land the waves together as a deliberate, separately-described commit.

---

## What is correct, and should not be re-litigated

**`UserPhoneService` is the right seam.** Answering the question directly: `AuthService.brandRegister`
consuming only `normalizeAndValidate` + `isTaken` **does not** re-open the drift risk the extraction was
meant to close. That risk was "what counts as a valid / available number", and that definition now exists
in exactly one place with brandRegister as a consumer of it. What brandRegister keeps is *choreography* —
flush ordering across the user/workspace/member/wallet inserts, and disambiguating a raced duplicate phone
from a raced duplicate email in one catch — which is genuinely local to that method and could not be
hoisted without dragging workspace and wallet knowledge into a phone service. Correct judgement, correctly
explained in the javadoc.

The irony worth naming: the one thing that *did* drift is the flush discipline, and it drifted in the
direction nobody was watching — the shared service lost a rule the special-cased caller kept (D1).

**The transaction-safety reasoning in `CreatorOnboardingService.saveProfile` is sound — verified, not
accepted.** `saveProfile` is `@Transactional` (`CreatorOnboardingService.java:90`), `ApiException extends
RuntimeException` (`ApiException.java:5`), Spring's default rollback rule covers unchecked exceptions, and
no `noRollbackFor` is present. A 400/409 from the phone write genuinely does roll back the
`creatorProfileRepository.save(profile)` above it. That claim is **not** wishful. The wishful claim is the
*409-translation* one (D1) — a different assertion about the same try/catch, which is why it survived
review: the correct half was checked and the incorrect half rode along with it.
`CreatorOnboardingServiceTest.java:67` wiring a **real** `UserPhoneService` onto the mocked repository
rather than mocking the service is good practice and I want it kept.

**No PII leak introduced.** Every reader of `User.getPhoneNumber()` in the backend:
`AdminCreatorService.java:185,523` (admin-authenticated) and `CreatorProfileService.java:230` (self
response only). `PublicCreatorService` never touches it. Newly populating creator phone numbers exposes
them on no public surface. Stating this explicitly because it is exactly the failure a narrowly-scoped
team would create and nobody was assigned to check.

**Kavya's CRITICAL was real, and the fix landed at the right layer.** All four surfaces route through
`src/lib/phone.ts`: `creator-onboarding.tsx:27`, `onboarding-steps.tsx:24`, `creator-settings.tsx:53`,
`brand-onboarding.tsx:16`. I searched for a fifth hand-rolled site; there is none. Client/server
normalization parity holds across the `+91`, leading-`0`, spaces and junk-input cases. Note Kavya's review
document still describes `inputMode="numeric"` — the shipped code is `inputMode="tel"`, correct for a
phone field; her doc predates the revision.

**TECH-STACK.md compliance: clean.** React 18 + Vite + Tailwind + shadcn on the frontend, Java 21 /
Spring Boot 3 / JPA / Flyway on the backend. No new dependency — nothing to approve in
`wiki/tech/approved-deps.md`. No `any`. Errors use `text-destructive-foreground`, not `text-destructive`
(per `reference_semantic_color_tokens`). `role="alert"` on both error messages, label/input properly
associated. No `NEXT_PUBLIC_`-class secret exposure; nothing in this diff touches env or config.

**Meera's out-of-scope call is correct.** The 2 `creator-disputes.test.tsx` failures are a
`user-event` pointer-events assertion in a file with no working-tree changes. Untouched by this diff,
genuinely pre-existing. Do not let it block PHONE-0904; do not let it stay unowned either — **Arjun** to
ticket separately.

**Minor observation, no action required:** `src/lib/phone.test.ts` gives the *mirror* a full accept/reject
matrix while `IndianPhoneUtils` — the source of truth it mirrors — has no direct unit test at all. The
copy is better covered than the original. Worth a test file the next time anyone is in that package.

---

## Sign-off

| Gate | Status |
|---|---|
| Required/Optional ruling | ✅ **RULED** — creator OPTIONAL, final; brand REQUIRED is a separate defect |
| Extraction avoided duplication (ticket AC) | ✅ Yes — `UserPhoneService` is the right seam |
| Phone stored in `users.phone_number`, no new column (ticket AC) | ✅ Confirmed |
| PII ADR not pre-empted | ✅ Confirmed (ADR itself missing — D5, mine to write) |
| TECH-STACK.md compliance | ✅ Pass, no new dependency |
| **CTO sign-off for commit** | ❌ **CHANGES REQUIRED — D1 blocks. D2/D3 before this ships.** |

**Re-submission path:** Vikram fixes D1 + D2 (+ D3, or Arjun splits it into its own ticket) → Kavya
re-reviews with the D1 test caveat above in hand → Meera re-verifies → back to me. The ruling in Part 1
does **not** block re-review and does not require a code change; it requires a comment pointer and a new
brand-side ticket.

---

## Addendum PHONE-0906 — creator signup phone is now REQUIRED

**Author:** Priya (CTO)
**Date:** 2026-09-06
**Decided by:** Swapnil (overruling the Part 1 ruling above)
**Status:** SHIPPED (code + tests, this diff)

### What changed

`POST /auth/creator/register` now rejects a registration with no mobile number. Creator signup
gained a required **Mobile Number** field, mirroring brand signup. Creator onboarding step 2 and
creator Settings keep their optional phone fields — they are now **edit** surfaces for a number
that already exists, never the first capture.

| Surface | Before | After |
|---|---|---|
| Brand signup | REQUIRED (PHONE-0904 Q8, Swapnil) | REQUIRED (unchanged) |
| Creator signup | not collected at all | **REQUIRED** |
| Creator onboarding step 2 | optional (first capture) | optional (edit) |
| Creator Settings | optional (edit, clearable) | optional (unchanged) |

### The asymmetry Part 1 identified is closed — in the direction Part 1 argued against

Part 1's ruling was "align by making brand optional." The business chose "align by making creator
required." That is a legitimate call to make; what follows is the cost it buys, recorded so nobody
has to rediscover it from a support ticket.

### Accepted risk 1 — the dead-end is now on the creator funnel too

Unchanged from Part 1 point 1 and D4: `users.phone_number` is `UNIQUE` **across user types**, is
never SMS-verified, and has **no release path**. A creator whose number already sits on a brand
account — an agency owner, a brand employee who also creates, a shared family or office line — now
gets `PHONE_ALREADY_EXISTS`/409 **and cannot create an account at all**. Before this change they
left the field blank and proceeded.

Mitigation shipped: the client message names support explicitly rather than saying "already
registered" and stopping there (`src/pages/creator-register.tsx`). That is a signpost, not a fix.
**The fix remains an admin/support phone-release path, and it is still unfunded.** This is now the
single highest-value item in the phone workstream, because it sits on both signup funnels.

### Accepted risk 2 — India-only signup

`IndianPhoneUtils.isValid` is `^[6-9]\d{9}$`. Required + India-only means a creator with a
non-Indian mobile **cannot register**, full stop. Previously they simply skipped the field. If
international creators are ever in scope, this is a hard blocker, not a polish item.

### Defects prevented while implementing

- **Burnt OTP / rate-limit trap.** A phone conflict is only knowable server-side, i.e. *after* the
  email OTP is verified. Naively, every retry re-sent a code and walked the user into
  `BrandEmailOtpService`'s per-email hourly `RATE_LIMITED` on a form they could not yet submit.
  `creator-register.tsx` now tracks the email that already cleared the gate in-session and skips
  the second send; `requireVerifiedEmail` reads the latest challenge, which stays verified, so this
  is safe. Pinned by a test.
- **Misattributed race-loser 409.** `creatorRegister`'s `DataIntegrityViolationException` catch
  translated *every* constraint violation into `EMAIL_ALREADY_EXISTS`. With phone now written on
  the same row it would have told a creator their email was taken when it was their mobile. Now
  re-checks `isTaken` first, matching what `brandRegister` already did (PHONE-0829 Gap A).
- **Three codes, not one.** `PHONE_REQUIRED`/400 (missing), `INVALID_PHONE`/400 (malformed),
  `PHONE_ALREADY_EXISTS`/409 (duplicate) stay separate all the way to an inline message on the
  field, and the client branches on `code` — never bare HTTP status, which this endpoint also
  returns 409 for on `EMAIL_ALREADY_EXISTS`.

### Known cosmetic residue (not fixed, deliberately)

Creator onboarding step 2 still asks for a phone with an empty input, one screen after signup
captured one. It cannot *erase* the signup value — `CreatorOnboardingService#saveProfile` guards
on `!isBlank()` before `applyPhone` (D2 above) — so this is a redundant ask, not a data-loss bug.
Prefilling it needs an extra `GET /me/creator-profile` read on that page. **Follow-up, owner:
Ananya.**
