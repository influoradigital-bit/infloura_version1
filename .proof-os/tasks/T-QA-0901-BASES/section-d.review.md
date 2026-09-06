# Priya (CTO) — Section D review

Reviewer: Priya, **fresh context**. No report taken at face value. Every claim below was
re-derived from the real source and re-proved by running the tests myself, plus **four**
independent revert/isolation probes (the brief asked for two).

Branch: `feat/meera-creator-phase-e`. All Section D changes are **uncommitted working-tree
edits**. All six ledger rows are still `status: open`, `promoted_to: ""` — nothing has been
closed.

---

## Verdict summary

| Item | Verdict | One line |
|---|---|---|
| **F-0665 / F-0434** | **NOT FIXED** | The collab round trip is real — but the mechanism it uses silently wipes every custom link and every visibility setting on the next read. Proven. |
| **F-0661** | **FIXED** | The type was made honest. Zero backend diff — nothing was fabricated. Compile-time gate discriminates (TS2578 on revert). |
| **F-0634** | **NOT FIXED** (mislabelled "false") | The finding is factually true. The submission refutes a claim F-0634 never made, and the test pins a different, true proposition. |
| **F-0635** | **FALSE FINDING** (confirmed) | Verified at `UserService.toDto(user)`. The ruling is sound and better-founded than it claims. |
| **F-0663** | **NOT FIXED** | The gate *does* fail on the seeded bad case — but it exits 1 on the live ledger, and 15 of its 23 reds are a design inconsistency inside the gate. Not promotable. |

---

## Exit codes — every command run by me

| Command | Exit |
|---|---|
| `npx tsc -p tsconfig.json --noEmit` | **0** |
| `npm test` (`vitest run`) — 184 files passed | **0** |
| `npm run test:live` — 6 files passed | **0** |
| `mvn -o clean -Dtest=PortfolioServiceCollabDisplayModeTest,PortfolioServiceRateCardTest test` — 9 tests | **0** |
| `mvn -o clean -Dtest=PortfolioServiceTest,PortfolioServiceRateCardTest,PortfolioServiceCollabDisplayModeTest test` — 29 tests | **0** |
| `python .proof-os/gates/F-0663-test-pins-subject.py` (live ledger) | **1** |
| `python .proof-os/gates/F-0663-test-pins-subject.py --ledger <HEAD ledger>` | **1** |

Probes (each reverted, working tree verified byte-identical afterwards):

| Probe | Result | Exit |
|---|---|---|
| P1 — restore `metrics?` to `types.ts` | `TS2578 Unused '@ts-expect-error'` at `portfolio-item-type-fidelity.test.ts:59` | tsc **2** |
| P2 — restore hardcoded `"logo"` in `buildCollabs` | 2/4 fail, incl. `expected: <hidden> but was: <logo>` | mvn **1** |
| P3 — drop `collabs: page.collabs` from `handleSave` | 2/3 fail: `expected { …(6) } to have property "collabs"` | vitest **1** |
| P4 — remove **only** the `rateCard` key from `writeSettings`, keep `collabDisplayModes` | settings still wiped — Section D's own key is an independent cause | mvn **1** |

---

## 1. F-0665 / F-0434 — portfolio collab display-mode persistence

### VERDICT: **NOT FIXED** — the round trip is genuine, the mechanism is a P0 regression

### 1a. The round trip itself is real (this part is good work)

The three things F-0665 asked for all landed:

* `PortfolioDtos.java:141` — `List<PortfolioCollab> collabs` now exists on `PortfolioPatchRequest`.
* `PortfolioService.java:287-292` — an id→displayMode map is persisted, with the same
  never-wipe-if-omitted discipline as `rateCard`.
* `PortfolioService.java:690` — `buildCollabs` reads the persisted map instead of the hardcoded
  `"logo"` at that exact line.

And it is a **genuine round trip, not a request-body echo**.
`PortfolioServiceCollabDisplayModeTest.collabDisplayMode_roundTripsAcrossASeparateRead` PATCHes
`"hidden"` and then calls `service.getMine(principal)` — a second, independent assemble — and
asserts on what comes back. The exact shape the brief demanded.

**Probe P2 confirms it discriminates.** I restored the hardcoded `"logo"` at
`PortfolioService.java:690` and reran:

```
[ERROR] PortfolioServiceCollabDisplayModeTest.collabDisplayMode_roundTripsAcrossASeparateRead
org.opentest4j.AssertionFailedError: expected: <hidden> but was: <logo>
[ERROR] PortfolioServiceCollabDisplayModeTest.collabDisplayMode_patchWithoutCollabs_preservesStoredValue
org.opentest4j.AssertionFailedError: a patch that never mentions collabs must not wipe the
previously stored display mode ==> expected: <name_only> but was: <logo>
```

Two failures, both for the right reason. This is not the F-0434 disease again.

FE side is sound too: `PortfolioCollab` on the wire is 11 String/Double fields
(`PortfolioDtos.java:34-44`) with `platform` typed `String`, so sending the array back verbatim
cannot trip enum coercion; field names match `src/lib/api.ts:4447-4459` one-for-one. Probe P3
confirms the FE assertion discriminates.

### 1b. THE BLOCKER — the persistence mechanism silently wipes the whole settings blob

`writeSettings(PortfolioSettings, List, Map)` at `PortfolioService.java:886-901` merges two extra
top-level keys into the JSON tree:

```java
node.set("rateCard", MAPPER.valueToTree(rateCard != null ? rateCard : List.of()));   // :892
node.set("collabDisplayModes", MAPPER.valueToTree(...));                             // :893
```

`loadSettings` at `PortfolioService.java:854-863` reads that same blob back with:

```java
return MAPPER.readValue(profile.getPortfolioSettingsJson(), PortfolioSettings.class);  // :859
```

`PortfolioSettings` (`PortfolioSettings.java:10`) carries **no** `@JsonIgnoreProperties(ignoreUnknown
= true)`, and `MAPPER` is a bare `new ObjectMapper()` — `FAIL_ON_UNKNOWN_PROPERTIES` defaults to
**true**. So `readValue` throws `UnrecognizedPropertyException` (a `JsonProcessingException`),
which the `catch` at `:860` swallows, returning `new PortfolioSettings()` — **defaults**.

I proved this rather than reasoned it. Temporary probe `PriyaProbeSettingsWipeTest`
(since deleted), one `updateMine` setting a custom link and an all-hidden visibility, then a
`getMine`:

```
PROBE stored json = {"visibility":{...,"rateCard":"hidden",...},
                     "customLinks":[{"id":"l1","label":"My shop",...}],
                     "pinnedPosts":[],"rateCard":[],"collabDisplayModes":{}}
PROBE patch-response customLinks = []
PROBE patch-response visibility  = PortfolioVisibility[trustBar=true, ..., rateCard=brands_only, ...]
PROBE fresh-read   customLinks = []
PROBE fresh-read   visibility  = PortfolioVisibility[trustBar=true, ..., rateCard=brands_only, ...]

AssertionFailedError: custom links must survive a second read ==> expected: <1> but was: <0>
```

Read that output carefully. The JSON on disk is **correct**. The creator's data is written. It is
the *read* that throws it away — and it throws it away so early that **the response to the
creator's own save already shows the defaults**. A creator sets "rate card: hidden", presses Save,
and the very same HTTP response tells them it is `brands_only`. Their custom links vanish. Their
`pastCollabs` / `platformStats` / `contentPortfolio` visibility toggles all snap back to `true`.

**This is not inherited blame from F-0498.** Probe P4: I removed *only* the `rateCard` key from
`writeSettings:892`, leaving Section D's `collabDisplayModes` key alone, and reran the probe —
identical failure, `customLinks` still `[]`. Section D's own key is sufficient to cause it.

Severity: this is a privacy control failing open on a public page, plus silent data loss on a
field the creator explicitly edited. P0.

**Root cause is two independent mistakes, and the second is the dangerous one:** a tree-level
merge that writes keys the target class cannot deserialize, *and* a `catch → return defaults`
fail-open at `:860` that converts a serialization bug into invisible data loss. Fixing only the
first leaves the second primed for the next person who adds a key.

Fix direction (Vikram):
1. `@JsonIgnoreProperties(ignoreUnknown = true)` on `PortfolioSettings` — or move `rateCard` /
   `collabDisplayModes` onto the class as real fields, which is cleaner than the tree merge.
2. `loadSettings`'s catch must `log.error` and, at minimum, be covered by a test. A silent
   fail-open on a field the creator explicitly set is the wrong default.
3. A round-trip test asserting **visibility + customLinks + pinnedPosts** survive
   `updateMine → getMine`. `PortfolioServiceTest` (20 tests), `PortfolioServiceRateCardTest`
   (5) and `PortfolioServiceCollabDisplayModeTest` (4) are all green — **29 tests and not one of
   them reads a settings field back after a write.** That is the blind spot that let this land.

### 1c. Second defect — "Hide" and "Anonymous" are client-side-only privacy

`buildCollabs` at `PortfolioService.java:654-693` applies the display mode to **nothing**. It
returns every completed collab with the real `workspace.getName()` and `workspace.getLogoUrl()`
regardless of mode; the only server-side gate is the blanket
`publicView && !settings.getVisibility().pastCollabs()` at `:661`.

The hiding happens exclusively in the browser:

* `src/pages/creator-portfolio-public.tsx:1091` — `page.collabs.filter((c) => c.displayMode !== 'hidden')`
* `src/pages/creator-portfolio-public.tsx:822,849` — `c.displayMode === 'category' ? 'Fashion Brand' : c.brandName`

`GET /portfolio/{username}` is **unauthenticated** (`PortfolioController.java:39-41`). So any
`curl` of a creator's public page returns the brand name and logo URL of every collab the creator
marked **Hide**, and the real brand name behind every **Anonymous** row.

Before this change the mode was always `"logo"`, so the filter never fired and no creator could
express the preference — the leak was unreachable. Making the control real is what exposes it.
Re-enabling the Select without server-side enforcement re-creates the exact class F-0665 was
opened for: a control that reports success while not doing what it says. `buildCollabs` must drop
`hidden` rows and null `brandName`/`brandLogoUrl` for `category` rows when `publicView`.

### 1d. Third defect — null `displayMode` is a 500, not a 400

`validateCollabDisplayModes` at `PortfolioService.java:1064-1075` does
`ALLOWED_COLLAB_DISPLAY_MODES.contains(collab.displayMode())` at `:1069`.
`ALLOWED_COLLAB_DISPLAY_MODES` is a `Set.of(...)`, whose `contains(null)` throws NPE by contract.
Probe output:

```
PROBE null displayMode -> java.lang.NullPointerException: null
```

A PATCH body of `{"collabs":[{"id":"c1"}]}` yields an uncaught NPE → 500, not the intended
`INVALID_COLLAB_DISPLAY_MODE` / 400. The author clearly anticipated a null `displayMode` —
`extractCollabDisplayModes:986` guards `collab.displayMode() != null` — but the validator runs
first at `:250`, so that guard is **dead code**. Not reachable from our own UI; reachable from any
other client.

### 1e. Fourth defect (residual) — the 12-collab cap loses stored modes

`buildCollabs:664` caps at `completed.stream().limit(12)`. The editor sends back exactly what GET
returned (`creator-portfolio-editor.tsx:164`), and `extractCollabDisplayModes` **replaces** the
whole map. A creator with more than 12 completed collabs loses the stored mode for every collab
outside the returned 12 on their next save. `findByCreatorIdAndStatus` has no ordering guarantee,
so a previously-hidden collab can resurface as `logo` after an unrelated bio edit.

### 1f. On the brief's specific question — is the frontend correctly disabled?

The backend did **not** come back partial in the sense meant: the DTO field and the service
persistence both landed, and the round trip is proved. Re-enabling the Select
(`creator-portfolio-editor.tsx:545-566`) is defensible **in principle**.

In practice it should stay disabled until 1b and 1c are fixed, because as shipped the control is
dishonest again by two separate routes: the save silently destroys the creator's other settings,
and "Hide" does not hide on the public endpoint.

One documentation nit: the in-file comment says "Previously this Select was disabled". At
`git show HEAD:src/pages/creator-portfolio-editor.tsx` it was **not** disabled — the comment
describes an intermediate uncommitted state. Harmless, but it will read as false to the next
person who checks HEAD.

### 1g. On the FE regression test's third case

`creator-portfolio-editor.f0434-collabs-drop.test.tsx:222-257` is billed as a "save → reload round
trip … the one thing neither of the two previous tests could have caught". Half of it is:
the `updateMock.mock.calls[0][0].collabs` assertion at `:242` genuinely discriminates (P3 proved
it). The other half does not — `getMineMock.mockResolvedValueOnce(portfolioPage('hidden'))` at
`:229` hardcodes the second response, so `expect(within(reloadedTrigger).getByText(/^Hide$/i))` at
`:254` asserts only that the component renders whatever `getMine` returns. No src regression can
make it fail. The real round trip lives in the Java test, which is the right place for it — but
the header's claim overstates what the FE file proves.

---

## 2. F-0661 — PortfolioItem DTO drift

### VERDICT: **FIXED**

**The direction of the fix is correct — nothing was fabricated.** This was the brief's specific
worry and the answer is unambiguous: `git status` shows **zero** diff under
`influora-api/.../web/dto/creator/` or `CreatorMapper.java`. No server payload was invented to
satisfy a TypeScript type. The type was made to match the server.

Every factual claim in the fix I verified against the real source:

* `CreatorDtos.java:49-55` — `record PortfolioItemResponse(String id, String title, String
  description, String thumbnailUrl, String mediaUrl, String platform)`. **No `metrics` field.**
* `CreatorMapper.java:78-81` — the only producer:
  `new PortfolioItemResponse(post.id(), post.caption(), null, post.thumbnailUrl(), post.embedUrl(), post.platform())`.
  `description` is a literal `null`, and `caption` is already spent on `title`.
* No `@JsonInclude` on the record or its class, and no `spring.jackson.default-property-inclusion`
  anywhere under `influora-api/src/main/resources/` or `config/` — I grepped both. So the key is
  always on the wire. `description: string | null` (present, nullable) is the right shape;
  `description?: string` was not.
* "every `portfolioItems` array in the codebase is a literal `[]`" — true:
  `creator-discovery.tsx` ×9, `demo-data.ts` ×3, `api.ts:1839` passthrough. And `api.ts`'s `row:
  CreatorProfile` does import from `./types` (`api.ts:38`), so this is the same type, not a
  parallel one.

**The compile-time gate genuinely discriminates.** `tsconfig.json` includes `src/**/*.ts` with
`strict: true`, so the test file *is* typechecked. Probe P1 — I restored the `metrics?: {...}`
block to `types.ts` and reran:

```
src/lib/__tests__/portfolio-item-type-fidelity.test.ts(59,5): error TS2578: Unused '@ts-expect-error' directive.
TSC_EXIT=2
```

Fails for exactly the right reason. Restored; `tsc` back to 0.

The file is honest about being compile-time-only (`vitest` cannot fail it) and says so in its own
header. That is the correct disclosure, not a weakness.

---

## 3. F-0634 — "every UpdateProfileRequest field has a frontend caller"

### VERDICT: **NOT FIXED** — the "false finding" label is wrong

The submission rules F-0634 false in a comment at `src/pages/brand-settings.tsx:345-354`. I do
not accept that ruling. **The finding as written is factually true**, and I verified every clause:

* `UserService.updateProfile` (`UserService.java:36-105`) applies six fields.
* `grep` for callers of `api.users.updateMe` across `src/` returns exactly **one** non-test site:
  `brand-settings.tsx:376`, `api.users.updateMe({ phone: normalized })`.
* No brand surface anywhere sets `firstName` / `lastName` / `displayName` / `timezone` /
  `avatarUrl`. Confirmed.

F-0634's symptom is *"the other five … have no UI path to set them for a brand user"*, class
`dto-field-partially-bound`. That is exactly what is on disk. The rebuttal answers a different
question — "could a phone-only PATCH *wipe* the other five?" — and correctly shows it cannot,
because `UserService.updateProfile:45-59` guards each field with `if (req.x() != null)`. But
**F-0634 never claimed a wipe.** That claim belongs to F-0462, the workspace full-replace bug, and
the comment says so itself before answering it anyway.

The regression test `brand-settings-account-phone-partial-merge.test.tsx` inherits the problem. It
is a *good* test of a *true* property — its in-memory fake server mirrors the real `!= null`
guards, and the documented falsification (send all six as empty strings) is the right shape — but
it proves partial-merge safety, not field reachability. F-0634's own `missed_by` asks for *"a test
asserting every field UpdateProfileRequest accepts has at least one frontend caller"*, and no such
test exists.

A green test filed under an id whose defect it cannot fail against is **precisely the F-0663
class**, arriving in the same wave that opened F-0663. (The F-0663 gate does not catch it — CHECK B
only asks whether the test *reaches* `brand-settings.tsx`, which it does. Blind spot #1 in the
gate's own `NOT CHECKED` list covers this honestly.)

The scope argument — "inventing a profile-editor as a side effect would be scope creep" — is
**correct and I endorse it**. Brand users genuinely have no avatar upload or timezone picker, and
building one off the back of a phone ticket would be wrong. But that makes F-0634 **DEFERRED**,
not **FALSE**. Requested resolution: leave the row open with a `fix:` note recording the scope
decision, re-file the missing-UI half as its own row, and either retitle the test to what it
actually proves (phone-only PATCH is merge-safe) or file it under F-0462's family instead.

---

## 4. F-0635 — UI repaints from the PATCH response instead of re-fetching

### VERDICT: **FALSE FINDING** (confirmed — and better-founded than the submission claims)

I checked the server rather than the argument. `UserService.updateProfile` ends at
`UserService.java:105` with `return toDto(user);`, and `toDto` (`UserService.java:107-121`) reads
the **managed entity after mutation and after the write** — `userPhoneService.applyPhone(user,
req.phone())` for a BRAND caller (`:100`), which normalizes, dup-checks and `saveAndFlush`es before
`toDto` runs. So the response body is the canonicalized, persisted value, not a request echo.

That makes the submission's own justification — *"if the server ever canonicalizes/reformats the
phone on write, trusting the response is the only way the UI shows the true stored value"* —
literally true on this path today, not hypothetical. A forced re-GET would return the same value
one round trip later. F-0635's premise ("the backend might echo a value it did not persist") does
not hold here.

Test 1 (`brand-settings-account-phone-response-trust.test.tsx:144-167`) genuinely discriminates:
the mocked server returns `'98765 43210'` while the client sent `'9876543210'`, and the test
asserts the card shows the server's string and *not* the locally-typed one. An implementation that
echoed local state fails it. That is the only shape that can tell the two apart.

Two caveats, neither changing the verdict:

* `toDto(user)` reads the in-memory entity, not a re-read from the database. A DB-side transform
  (column truncation, a trigger) would not be reflected. No such transform exists on
  `users.phone_number` today; worth remembering before this reasoning is reused elsewhere.
* Test 2 (`:169-189`) asserts `usersGetMe` is called exactly once — it **forbids** the very fix
  F-0635 asked for. That is legitimate as a deliberate policy pin, but it must be labelled one:
  if the ruling is ever reversed, that test has to be deleted with it, and nothing in the file
  says so.

---

## 5. F-0663 — the new "test pins wrong subject" gate

### VERDICT: **NOT FIXED** — the gate works, but cannot pass, and a third of its reds are its own design

The brief asked three questions. Answers, in order:

**(a) Does it actually fail on a seeded bad case? YES.** I reproduced the gate's own documented
falsification #1 exactly, against the real historical ledger rather than a fixture:

```
$ git show HEAD:.proof-os/ledger/failures.jsonl > old.jsonl
$ python .proof-os/gates/F-0663-test-pins-subject.py --ledger old.jsonl
  BROKEN 9 actionable row(s) whose `where` names a file that does not exist:
      F-0640   open   src/components/creator/CreatorDealContractTab.tsx
  BROKEN 1 row(s) whose `where` is a DIRECTORY while a test is already filed under the id:
      F-0440   src/components/brand/deal-room   filed test(s): …deal-room-dashboard-accept-rollback.test.tsx,
                                                               src/pages/brand-chat-accept-recovery.test.tsx
VERDICT: broken — 25 subject(s) …
EXIT=1
```

F-0640 caught by CHECK A, F-0440 by CHECK C — the two documented root causes, for the right
reasons. This is **not** a gate that cannot fail. That was the main risk and it is cleared.

**(b) Does it produce false reds over the real ledger? YES — 15 of 23.**

```
$ python .proof-os/gates/F-0663-test-pins-subject.py
VERDICT: broken — 23 subject(s) …
EXIT=1
```

The gate is **red on HEAD**. It cannot be promoted (`promote.py` needs a passing gate), and the
ledger row F-0663 still reads `promoted_to: ""`.

Of the 23:

* **CHECK A — 6 reds, all true positives.** I verified each path on disk:
  `DeliverableService.java`, `DeliverableMetricController.java` and
  `CreatorAffiliateEarningService.java` do **not** exist (the services were split into
  `Brand`/`Creator` pairs; `AffiliateEarningsService.java` is the nearest survivor), and
  `.proof-os/gates/endpoint_reachability.py` now lives under
  `.proof-os/_archive/superseded-gates-0902/`. F-0380, F-0407, F-0418, F-0423, F-0430, F-0473 are
  genuinely stale pointers on genuinely open rows. Real, actionable, and owned by other waves.
* **CHECK B — 17 reds, 15 of them on CLOSED rows.** I pulled the status of every id the gate
  named: F-0177, F-0222, F-0225 ×2, F-0250 ×2, F-0392, F-0400, F-0432, F-0440, F-0450, F-0459,
  F-0479, F-0535, F-0653 are all `closed`. Only F-0275 and F-0280 are open.

That 15 is an **internal inconsistency in the gate, not a finding about the repo**. CHECK A
explicitly exempts closed rows, and states why in its own docstring: *"a fix that renames or
deletes its own subject leaves a dead `where` legitimately."* The identical reasoning applies to
CHECK B — a closed row whose fix moved to a sibling class (F-0225's fix moved to
`CollaborationReviveService`; F-0400's to `DealService`) legitimately leaves a `where` its test no
longer imports. CHECK B applies no such exemption.

Proven, not asserted. I copied the gate, added a three-line closed-row skip to the CHECK B loop,
ran it, and deleted the copy:

```
  BROKEN 6 actionable row(s) whose `where` names a file that does not exist:
  BROKEN 2 test(s) filed under an F-id that never reach the row's subject:
VERDICT: broken — 8 subject(s) …
```

23 → 8. The honest actionable count is **8**, of which 6 are stale ledger pointers belonging to
other waves.

**(c) Is it the same disease it was written to cure? No — but it is a cousin.** The gate does not
green its own subject. It is, however, currently unfalsifiable *in the pass direction*: the
docstring's falsification #2 only claims exit 0 is reachable on a **hypothetically repointed**
ledger, and never claims the gate passes on this repo. Shipping a gate that has never once been
green on HEAD means nobody knows what its pass state looks like.

Also worth noting for the record: the docstring cites F-0434 as its motivating example, and lists
that exact shape as blind spot #2 (*"asserting a request field the backend DTO never accepts …
owned by gates/dto-drift.py, not implemented here"*). Honest, but it means the gate would not have
caught the finding it opens with.

To close F-0663: exempt closed rows from CHECK B for the reason CHECK A already gives, fix the six
stale `where` pointers (a ledger edit, not a code change), then get the gate to exit 0 once before
promoting it.

---

## What must happen before any of this ships

**Blocking (P0):**

1. `PortfolioSettings` must tolerate the extra keys, and `loadSettings:860` must stop failing open
   silently. Today every custom link and visibility toggle a creator saves is destroyed on the
   next read — including the response to their own save. `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:854-863, 886-901`
2. `buildCollabs` must enforce `hidden` / `category` server-side. `GET /portfolio/{username}` is
   public and currently leaks the brand names the creator asked to hide.
   `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:654-693`
3. Keep the per-collab Select disabled until 1 and 2 land.
   `src/pages/creator-portfolio-editor.tsx:545-566`

**Blocking (P1):**

4. `validateCollabDisplayModes:1069` — null-safe check, so a null `displayMode` is a 400 and not a
   500. Then `extractCollabDisplayModes:986`'s null guard stops being dead code.
5. A backend round-trip test covering visibility + customLinks + pinnedPosts across
   `updateMine → getMine`. 29 portfolio tests pass today and none of them reads a settings field
   back after a write.

**Non-blocking:**

6. F-0634 — reclassify DEFERRED, not FALSE; re-file the missing-brand-profile-UI half; retitle the
   partial-merge test to what it proves.
7. F-0663 — closed-row exemption in CHECK B; fix the 6 stale `where` pointers; get one green run
   before promotion.
8. Residual: the `limit(12)` + full-array-replace interaction loses stored display modes for a
   creator with more than 12 completed collabs.
   `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:664`
9. Label F-0635's second test as a deliberate policy pin.
10. Correct the "previously this Select was disabled" comment — untrue against HEAD.

**Clean to land as-is:** F-0661 only.

---

### Working-tree hygiene

All four probes were reverted and verified. `PortfolioService.java` diffs byte-identical to its
pre-probe backup; `types.ts` and `creator-portfolio-editor.tsx` restored; the temporary
`PriyaProbeSettingsWipeTest.java` and the patched gate copy were deleted. `git status` shows no
probe artifacts. `tsc`, `npm test`, `npm run test:live` and the Java portfolio suite were all
re-run green after cleanup.
