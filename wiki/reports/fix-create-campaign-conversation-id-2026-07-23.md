# Fix: `create_campaign` 409 (`Column 'conversation_id' cannot be null`) — 2026-07-23

Author: Vikram (Backend). Status: code fixed, offline-verified, **not deployed**.

## Live error

```
POST /api/v1/internal/meera/create_campaign -> 409
org.springframework.dao.DataIntegrityViolationException: could not execute statement
  [Column 'conversation_id' cannot be null]
  at CreateCampaignExecutor.doExecute(CreateCampaignExecutor.java:172)
  at MeeraInternalController.createCampaign(MeeraInternalController.java:179)
```

Surfaced immediately after the M-1 on-behalf-scope fix let Meera's on-behalf token actually reach
`create_campaign` (`OnBehalfTokenService.SCOPE_DEFAULT` now includes it). `calculate_budget`
returning 200 confirmed M-1 itself (scope claim) is fine — this is a separate, independent bug one
layer down.

## Root cause

`campaign_intents.conversation_id` is `NOT NULL` with an FK to `ai_conversations(id)`
(`influora-api/src/main/resources/db/migration/V13__campaign_intents.sql:3,19`). `CampaignIntent`
(`influora-api/src/main/java/com/influora/domain/entity/CampaignIntent.java:24`) mirrors that with
`@Column(name = "conversation_id", nullable = false, ...)`.

`CreateCampaignExecutor.doExecute` (`influora-api/src/main/java/com/influora/service/meera/tool/CreateCampaignExecutor.java:171-184`)
already builds the `CampaignIntent` with `.conversationId(conversationId)` — the executor was never
the bug. The `conversationId` parameter it receives, though, came from
`MeeraInternalController.createCampaign` → `conversationIdOf(body)`
(`influora-api/src/main/java/com/influora/web/MeeraInternalController.java`, old line 180 /
`conversationIdOf` at old line 371), which just reads `body.get("conversation_id")`.

Per the controller's own class javadoc (lines 48-52): the `create_campaign` tool-call body is
*"the raw tool input Claude proposed, plus `workspace_id` merged in by Python's tool loop; nothing
else lives in the body."* `influora-ai/app/clients/spring.py`'s wire contract for tool calls never
puts `conversation_id` in that body — it's not part of the `create_campaign` tool's input schema.
So `conversationIdOf(body)` was **always** `null` on this specific route, and the insert always
violated the `NOT NULL` constraint the instant the scope fix let a real call reach it. (Existing
`CreateCampaignExecutorTest` unit tests never caught this because they call
`executor.execute(WORKSPACE_ID, CONVERSATION_ID, ...)` directly with a hand-supplied
`CONVERSATION_ID` — they exercise the executor, not the controller's (broken) sourcing of that
argument.)

## Where a real conversationId *is* available — and why it's tenant-safe

The on-behalf JWT already carries a `conversationId` claim, minted per-turn:

- `OnBehalfTokenService.mint` (`influora-api/src/main/java/com/influora/service/meera/OnBehalfTokenService.java:82-110`)
  sets `.claim("conversationId", conversationId)` — called from `MeeraSessionService#doSendTurn`
  with the real, FK-valid `ai_conversations.id` for that turn (same call site that mints the SSE
  stream token).
- `OnBehalfAuthResolver.resolveForWorkspace` (`influora-api/src/main/java/com/influora/security/OnBehalfAuthResolver.java`)
  already fully verifies this token (ES256 signature against Spring's own JWKS, `iss`, `aud`,
  `exp`, and cross-checks `workspaceId` against the request body) before any tool executes — but
  the returned `OnBehalfContext` record only exposed `(userId, workspaceId, userType)`, dropping
  the `conversationId` claim on the floor even though it had already been verified.

This is exactly the source the task called for: **prefer the JWT/context conversationId over a
client-body value.** The request body is not signed/covered in a way that makes a `conversation_id`
field trustworthy for tenant purposes the way `workspace_id` is (which the resolver explicitly
cross-checks against the token) — a body-sourced `conversation_id` would be an unverified,
client-influenced string with no cross-tenant check, i.e. exactly the cross-check risk flagged
during review. The JWT claim, by contrast, is server-minted at turn-start from the authenticated
conversation and already passes through the resolver's full verification path.

## Fix

1. **`influora-api/src/main/java/com/influora/security/OnBehalfAuthResolver.java`**
   - `OnBehalfContext` record gains a 4th component: `conversationId` (line 78).
   - `resolveForWorkspace` now reads `claims.get("conversationId", String.class)` and returns it
     on the context (lines ~96-110). Every other `resolveForWorkspace*` variant delegates through
     this method, so all of them now carry `conversationId` for free — no other resolver method
     needed a change.

2. **`influora-api/src/main/java/com/influora/web/MeeraInternalController.java`** (`createCampaign`,
   ~line 168-186)
   - Changed the executor call from `conversationIdOf(body)` to `ctx.conversationId()` — the
     JWT-verified value from the already-resolved `OnBehalfContext`. Added an inline comment
     explaining why (see diff below). No other route was touched: `request_payment` and
     `confirm_launch` still use `conversationIdOf(body)`, which is out of scope for this fix (they
     don't hit this NOT-NULL column and weren't reported broken) — flagging as a latent
     same-shape risk below, not fixing it here.

### Exact diff (conceptual, matches what was applied)

```diff
--- a/influora-api/src/main/java/com/influora/security/OnBehalfAuthResolver.java
-    public record OnBehalfContext(String userId, String workspaceId, UserType userType) {}
+    public record OnBehalfContext(String userId, String workspaceId, UserType userType, String conversationId) {}
@@ resolveForWorkspace(...)
-        return new OnBehalfContext(claims.getSubject(), tokenWorkspaceId, userType);
+        String conversationId = claims.get("conversationId", String.class);
+        return new OnBehalfContext(claims.getSubject(), tokenWorkspaceId, userType, conversationId);

--- a/influora-api/src/main/java/com/influora/web/MeeraInternalController.java
@@ createCampaign(...)
         requireTool(MeeraToolName.create_campaign, workspaceId);
+        // BUG FIX (2026-07-23 live 409): body never carries conversation_id for this route;
+        // ctx.conversationId() is the JWT-verified, tenant-safe source.
         var result =
                 createCampaignExecutor.execute(
-                        ctx.workspaceId(), conversationIdOf(body), ctx.userId(), idempotencyKey, body);
+                        ctx.workspaceId(), ctx.conversationId(), ctx.userId(), idempotencyKey, body);
```

3. `CampaignIntent` / `CreateCampaignExecutor` were **not** modified — the executor's persistence
   logic was already correct (it just received a bad argument). The `NOT NULL` constraint on
   `campaign_intents.conversation_id` (V13 migration) is correct and was left untouched, per the
   task instruction — this is a code fix, not a schema relaxation. No new Flyway migration needed.

### Callers updated for the new `OnBehalfContext` arity (compile-breaking, not behavioral)

- `influora-api/src/test/java/com/influora/web/MeeraInternalControllerContextTest.java:84` — added a
  4th constructor arg (`"conv-1"`).

## Test added

- **`influora-api/src/test/java/com/influora/web/MeeraInternalControllerCreateCampaignTest.java`**
  (new file) — controller-level regression test, since the pre-existing `CreateCampaignExecutorTest`
  structurally cannot catch this class of bug (it calls the executor directly with a hand-supplied
  `conversationId`, bypassing the controller entirely):
  - `testCreateCampaignSourcesConversationIdFromJwtWhenBodyOmitsIt` — body has **no**
    `conversation_id` key at all (the real production shape) → asserts
    `createCampaignExecutor.execute(...)` is invoked with the JWT-derived `CONVERSATION_ID`, not
    `null`.
  - `testCreateCampaignIgnoresBodyConversationIdEvenWhenPresent` — body carries a different/spoofed
    `conversation_id` → asserts the JWT-derived value from `OnBehalfContext` still wins (pins the
    tenant-safety property, not just the null-fix).
- **`influora-api/src/test/java/com/influora/security/OnBehalfAuthResolverTest.java`** — added
  `testResolveForWorkspaceExposesConversationIdFromJwtClaim` and extended
  `testAcceptsValidOnBehalfToken` to assert `ctx.conversationId()` — pins that the resolver actually
  surfaces the claim, the mechanism the controller fix depends on.

## Offline build/test result

JDK 21 (Eclipse Adoptium `jdk-21.0.9.10-hotspot`), Maven 3.9.6, `-o` (offline).

```
mvn -o -q compile                                    -> BUILD SUCCESS (no output = clean)

mvn -o -q test -Dtest=OnBehalfAuthResolverTest,OnBehalfTokenServiceTest,\
  MeeraInternalControllerContextTest,MeeraInternalControllerCreateCampaignTest,\
  CreateCampaignExecutorTest -DfailIfNoTests=false

OnBehalfAuthResolverTest:               Tests run: 14, Failures: 0, Errors: 0
OnBehalfTokenServiceTest:               Tests run: 9,  Failures: 0, Errors: 0
MeeraInternalControllerContextTest:     Tests run: 2,  Failures: 0, Errors: 0
MeeraInternalControllerCreateCampaignTest: Tests run: 2, Failures: 0, Errors: 0
CreateCampaignExecutorTest:             Tests run: 8,  Failures: 0, Errors: 0
```

35/35 pass. Not deployed to the VPS (200.141.1.6) — per task instructions, this pass is code +
offline verification only.

## Follow-up not done here (flagging, not fixing)

`request_payment` and `confirm_launch` (`MeeraInternalController.java`) still source
`conversationId` via the same `conversationIdOf(body)` body-read as the broken `create_campaign`
was. Neither is reported broken today and neither writes into a `NOT NULL` FK column the way
`create_campaign` does, so left untouched to keep this fix minimal — but they have the same
tenant-safety shape issue (trusting an unverified body field where a JWT-verified
`ctx.conversationId()` now exists) and should be looked at as a fast-follow.
