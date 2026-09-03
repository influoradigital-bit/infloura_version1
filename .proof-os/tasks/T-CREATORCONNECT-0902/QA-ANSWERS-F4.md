# QA-ANSWERS-F4 — T-CREATORCONNECT-0902 (Priya, CTO)

> Note on citations: the Tester's line numbers for `AdminCreatorConnectionService.java` are offset
> (invite is at 188-237, not 321-369; importHandles at 295-379, not 428-511). All `path:line`
> below are the real lines on `fix/f0390-money-flags-build-pipeline` as of 2026-09-03.

## F4 — Admin Creator connections page

### Q4.1 [IS IT WORKING]
> Show me a real admin invite round-trip: `CreatorConnectionsPage` action dialog → `creatorConnectionsApi.invite` → `AdminCreatorConnectionService.invite` → `sendJoinInvitationEmail` → a `creator.join_invitation` email in a real inbox whose "Join Influora" CTA opens `{webBaseUrl}/creator/register?ref=influora-invite&handle=…`. Include the `AdminAuditLogService` rows for that run.

VERDICT: PARTIAL

The chain is fully wired with no gap: `src/admin/pages/CreatorConnectionsPage.tsx:492` (Invite button) → `:273` → `src/admin/services/api-contracts.ts:725-729` → `AdminCreatorConnectionController.java:72-79` → `AdminCreatorConnectionService.java:188-237` → `sendJoinInvitationEmail` `:239-258` → `Msg91EmailClient.sendTemplateEmail` (`Msg91EmailClient.java:104`), template registered at `EmailTemplateRegistry.java:336-343`. Audit rows are written at `:216-224` (`UPDATE EXTERNAL_CREATOR`) and `:225-233` (`UPDATE CREATOR_CONNECTION_REQUEST`).

I have no evidence of a real run. **Zero** backend tests reference this service (`grep -rl AdminCreatorConnectionService influora-api/src/test/` returns nothing); nothing in `.proof-os/journal.jsonl`. The only test is the FE email gate at `CreatorConnectionsPage.test.tsx:91`, which mocks the API.

Worse, the round-trip cannot be observed by the admin either. `apiRequest` never throws on an HTTP error — it returns `{success:false}` (`api-contracts.ts:95-105`) — and `actionMutation.onSuccess` (`CreatorConnectionsPage.tsx:275-284`) closes the dialog at `:282` regardless of `res.success`, with no success toast (TASKS.md L173 requires "Invitation sent to …") and no error surface.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Admin invite/contact/decline failures are silent — dialog closes as if they succeeded","where":"src/admin/pages/CreatorConnectionsPage.tsx:275-284","issue":"apiRequest returns {success:false} instead of throwing (api-contracts.ts:95-105), so onSuccess fires for 404/409/500 too. It skips invalidation but still calls setActiveAction(null) at :282. There is no onError, no success toast, and no failure banner — an invite that never sent looks identical to one that did.","fix":"In onSuccess, branch on res.success: on false surface res.error in the dialog and keep it open; add onError for network failures; add the 'Invitation sent to {email}' success toast the contract requires."}
```

### Q4.2 [HOW]
> Walk me through why the email is sent pre-commit (what happens if the commit then fails on `external_creators.email` length 255) and why the workspace name was dropped when the contract templates `{{brand_name}}`.

VERDICT: DEFECT

**Pre-commit.** `sendJoinInvitationEmail(external)` is the last statement before `return` (`AdminCreatorConnectionService.java:235-236`) inside `@Transactional invite` (`:188`). Spring commits *after* the method returns, so the MSG91 call happens while the transaction is still open. The 255 case is real: `InviteRequest` validates `@NotBlank @Email` only, with no `@Size` (`AdminCreatorConnectionDtos.java:66-68`), while the column is `length = 255` (`ExternalCreator.java:73-74`). A 300-char syntactically valid address passes, `markInvited` sets it (`:210`; entity `:220-227`), and the flush at commit throws `DataIntegrityViolationException` → 409 `DATA_INTEGRITY_VIOLATION` (`GlobalExceptionHandler.java:110-114`). The status flip, `invited_at`, the creator email and **both audit rows** roll back — but the creator already has the invite, and the console still shows UNVERIFIED / never invited.

**brand_name.** Hard-coded `"A brand"` at `:241`, though the request row carries `workspaceId` and `toDtos` already resolves `w.getName()` at `:504`. The registry subject is `{{brand_name}} wants to work with you on Influora` (`EmailTemplateRegistry.java:337`), so every creator receives "A brand wants to work with you on Influora". No comment explains the drop; TASKS.md L146 requires the name.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Invite email hard-codes brand_name to 'A brand' although the workspace name is already resolved","where":"influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:241","issue":"The creator.join_invitation subject templates {{brand_name}} (EmailTemplateRegistry.java:337) but invite() passes the literal 'A brand', so every invite reads 'A brand wants to work with you on Influora'. The request row has workspaceId and toDtos already loads Workspace.getName() at :504 — the data was in hand.","fix":"Pass the CreatorConnectionRequest into sendJoinInvitationEmail, look up the Workspace by request.getWorkspaceId(), and use its name; fall back to 'A brand' only when the workspace row is gone."}
```
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Join-invitation email is sent before commit, so a rolled-back invite still reaches the creator","where":"influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:235","issue":"sendJoinInvitationEmail runs inside @Transactional invite (:188). If the commit fails — e.g. an email longer than the 255-char column (ExternalCreator.java:73-74), which InviteRequest does not bound (AdminCreatorConnectionDtos.java:66-68) — the status flip, invited_at and both audit rows roll back while the creator has already been emailed.","fix":"Add @Size(max=255) to InviteRequest.email, and move the send to an AFTER_COMMIT step (application event or TransactionSynchronization) so no email is sent for a transaction that did not commit."}
```

### Q4.3 [WHY NOT THIS WAY]
> Why not enrich asynchronously so an admin paste doesn't hold a connection through 50 Graph round-trips, a single `MetaRateLimitException` doesn't silently leave 49 stubs un-enriched with no retry, and the rate cost doesn't land on one arbitrary creator's account?

VERDICT: GAP

Every premise checks out, and nothing in the code or TASKS.md justifies the synchronous shape — TASKS.md L119 specifies the semantics only, not the transaction boundary. This is an undocumented gap, not an accepted risk.

- `importHandles` is `@Transactional` (`:295`) and loops up to 50 handles (`:314-366`), each doing a blocking `businessDiscovery` call (`:338-340`). The pooled DB connection is held for the whole sequence.
- `MetaRateLimitException extends MetaApiException` (`MetaRateLimitException.java:6`), so the per-handle catch at `:354-356` swallows it at INFO. Remaining handles re-enter `get()` and immediately re-throw the pre-flight throttle (`MetaGraphApiClient.java:109-113`), so all of them land as un-enriched stubs. `ImportResult` cannot express this: `skipped` collects only blank-after-normalize handles (`:319-322`), so the admin sees `enriched 0` with no cause and no retry path — there is no re-enrich endpoint.
- `resolveAnyBusinessDiscoveryCaller` (`:385-399`) takes `findFirst()` of any FACEBOOK_LOGIN token, so all 50 calls bill one arbitrary account's quota, keyed on its `igBusinessAccountId` (`MetaGraphApiClient.java:109`).

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Bulk import runs up to 50 blocking Graph calls inside one transaction and reports rate-limited handles as ordinary imports","where":"influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:295-366","issue":"@Transactional importHandles holds a pooled DB connection across up to 50 synchronous Business Discovery round-trips. MetaRateLimitException is a MetaApiException subclass (MetaRateLimitException.java:6), so the catch at :354 swallows throttling; once MetaGraphApiClient's pre-flight threshold (:109-113) trips, every remaining handle silently becomes an un-enriched stub and ImportResult still counts it as imported, with no retry surface.","fix":"Persist the stubs in a short transaction, then enrich out of band (a job keyed on lastSyncedAt IS NULL). Catch MetaRateLimitException separately, stop the loop, and return the un-enriched handles so the admin knows to retry."}
```

### Q4.4 [WHEN WILL IT BREAK]
> An admin pastes `foo bar`, `foo)`, or an 81-char line. What does the admin see in each case, and how do they delete a garbage stub given there is no delete endpoint?

VERDICT: DEFECT

Confirmed: `normalizeUsername` (`:553-556`) only trims, lower-cases and strips a leading `@`. `ExternalCreatorService.USERNAME_PATTERN` (`ExternalCreatorService.java:58`) is `private` to that class and never applied here, and there is no length check against the 80-char column (`ExternalCreator.java:44`).

- **`foo bar` / `foo)`** — interpolated raw into the Graph path (`InstagramInsightsClient.java:117-122`). Meta rejects it, `MetaApiException` is caught at `:354` and logged at INFO, and the row is **still saved** at `:359` as an ADMIN_IMPORT stub with the garbage handle and counted in `imported`. The admin sees only "Imported 2, enriched 0" (`CreatorConnectionsPage.tsx:577-581`). The stub then appears in the brand-facing Discover list.
- **81 chars** — passes every check; the flush at commit throws, and `GlobalExceptionHandler.java:110-114` returns **409 `DATA_INTEGRITY_VIOLATION`, not a 500** (correcting the question's assumption). `@Transactional` rolls the whole batch back and the FE shows "The request could not be completed due to a data conflict" (`:297`, `:583`) without naming the offending handle.
- **Deletion** — confirmed impossible. `AdminExternalCreatorController.java` exposes only GET (`:35-43`) and POST `/import` (`:45-51`); no `@DeleteMapping` in either admin controller. Garbage stubs are permanent and brand-visible.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Admin import accepts unvalidated handles: garbage is persisted brand-visibly and one long line rolls back the whole batch","where":"influora-api/src/main/java/com/influora/service/admin/AdminCreatorConnectionService.java:318-359","issue":"normalizeUsername (:553-556) applies neither ExternalCreatorService.USERNAME_PATTERN (ExternalCreatorService.java:58) nor the 80-char column limit (ExternalCreator.java:44). Invalid handles are interpolated raw into the Graph path (InstagramInsightsClient.java:117-122) and, after Meta rejects them, still saved at :359 and counted as imported; an 81-char handle fails at flush, returning 409 and discarding all 50. There is no delete endpoint (AdminExternalCreatorController.java has only GET and POST /import), so a bad stub is permanent and shows in brand Discover.","fix":"Promote USERNAME_PATTERN to a shared validator and apply it plus a length<=80 check in the import loop, routing rejects into ImportResult.skipped instead of persisting them; add an audit-logged DELETE /admin/external-creators/{id} guarded when connection requests reference the row."}
```

### Q4.5 [WHAT TO ADD]
> Beyond fixing the `AdminConnection` type, what is missing for this page to be operable at volume?

VERDICT: GAP

The type mismatch is confirmed and wider than reported: `src/admin/types/admin.types.ts:617` `brandName`, `:619` `requestedByEmail`, `:621` `igUsername` and `:626` `creatorStatus` are all non-null `string`, while `toDtos` emits `null` for each (`AdminCreatorConnectionService.java:504, 506, 508, 513`). `creator_connection_requests` carries an FK only on `external_creator_id` (TASKS.md L73), so `brandName`/`requestedByEmail` genuinely go null once a workspace or user row is deleted; those render blank at `:442`/`:188` rather than crashing. `igUsername` is protected only by that FK — `CreatorConnectionsPage.tsx:447` calls `c.igUsername.charAt(0)` unguarded, so the lie is one schema change away from a render crash.

Also missing for volume:
- `search` loads **every** matching workspace and external creator into memory via unpaginated `findAll` (`:434-445`) to build an unbounded `IN` list (`:421-427`).
- The external-creators table is pinned to `page: 1, pageSize: 20` (`CreatorConnectionsPage.tsx:266`) with no pager and neither the `status` nor `q` filter wired, though the endpoint accepts both (`AdminExternalCreatorController.java:38-41`).
- No backend test exists for this service at all; the sole FE test is the email gate (`CreatorConnectionsPage.test.tsx:91`).

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"AdminConnection TS type declares four fields non-null that the Java record emits as null","where":"src/admin/types/admin.types.ts:617-626","issue":"brandName (:617), requestedByEmail (:619), igUsername (:621) and creatorStatus (:626) are typed `string`, but toDtos emits null for each when the workspace, user or external-creator row is missing (AdminCreatorConnectionService.java:504,506,508,513). Only external_creator_id has an FK (TASKS.md L73). CreatorConnectionsPage.tsx:447 calls c.igUsername.charAt(0) with no guard — the same FE-asserts-a-field-the-record-may-not-send class as F1/PHONE-0829 P3.","fix":"Widen all four to `| null` in admin.types.ts and render explicit fallbacks (an em dash for brandName at :442 and :188, a safe initial at :447); separately, page the external-creators table and wire its status/q filters, and replace the in-memory search IN-list (:434-445) with a join."}
```
