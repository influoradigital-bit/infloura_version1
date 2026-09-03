# QA answers — T-CREATORCONNECT-0902 (Priya, CTO)

Answered against the working tree on `fix/f0390-money-flags-build-pipeline`, 2026-09-03. Path shorthand as in QA-QUESTIONS.md (`api/` = `influora-api/src/main/java/com/influora/`, `fe/` = `src/`).

## F3 — "Connect this creator" request (dialog → POST /connect, idempotency, 409, admin email)

### Q3.1 [IS IT WORKING]
> Show me the admin email actually arriving: `connect` publishes `CreatorConnectionRequestedEvent` (`ExternalCreatorService.java:363-373`) → `NotificationListener.on(CreatorConnectionRequestedEvent)` (`api/service/notification/NotificationListener.java:635-662`) → `msg91EmailClient.sendTemplateEmail(adminNotificationEmail, "admin.creator_connection_requested", …)`. With `ADMIN_NOTIFICATION_EMAIL` blank in `deploy/utho/generate-env.sh:141` the listener WARNs and skips (L638-644). On which environment has this email been received, and what was `admin_url` resolved to (`webBaseUrl + "/admin/creator-connections"`, L651) — does that route exist unauthenticated or does it bounce to admin login?

VERDICT: NOT WORKING

On no environment, and I can produce no evidence that it ever has. The recipient is bound from `influora.admin.notification-email` (`api/service/notification/NotificationListener.java:104`), which is `${ADMIN_NOTIFICATION_EMAIL:}` (`influora-api/src/main/resources/application.yml:282`). Every provisioning path leaves it empty: `deploy/utho/generate-env.sh:141` writes `ADMIN_NOTIFICATION_EMAIL=` deliberately, and both compose files interpolate with no `:-` default (`deploy/hostinger/docker-compose.hostinger.yml:192`, `deploy/utho/docker-compose.utho.yml:226`), so a pre-existing `.env` injects `""`. Blank → WARN + `return` at `NotificationListener.java:638-644`; `sendTemplateEmail` is never reached.

The in-repo proof stops at the publish: `ExternalCreatorServiceTest.java:138-155` verifies one `CreatorConnectionRequestedEvent` is published. No test, log, or captured message covers the listener or the send. The template itself is registered (`api/integration/msg91/EmailTemplateRegistry.java:327-334`).

`admin_url` resolves to `{webBaseUrl}/admin/creator-connections`. That route exists (`fe/pages/admin-console.tsx:64`) but is behind `AdminProtectedRoute`, which redirects to `/admin/login` without an admin token (`fe/App.tsx:157`, route at `fe/App.tsx:628-633`) — correct behaviour, not a defect.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Admin connection-request email is dark on every deploy target (ADMIN_NOTIFICATION_EMAIL blank/unset)","where":"deploy/utho/generate-env.sh:141","issue":"generate-env.sh writes ADMIN_NOTIFICATION_EMAIL= and both compose files pass ${ADMIN_NOTIFICATION_EMAIL} with no :- default, so NotificationListener.java:638-644 WARNs and skips. Step 3 of the flow (enquiry reaches the admin team) never fires; the admin console page is the only way a request is ever seen.","fix":"Set a real address in the deploy .env (and a :-default in both compose files), then prove one delivery end-to-end; until then treat the admin console as the sole notification channel and say so in the handoff."}
```

### Q3.2 [HOW]
> Reopen after DECLINED: `existing.reopen(message)` (`CreatorConnectionRequest.java:189-197`) resets status/notes but keeps the original `requestedByUserId`, while the event is published with `principal.getUserId()` (`ExternalCreatorService.java:365-366`). If a *different* member of the same workspace re-requests, whose inbox gets the `brand.connected_creator_joined` email later (`ExternalCreatorLinkService.java:126-133` uses `request.getRequestedByUserId()`) — and what if that original user has since been removed from the workspace (`emailOf`, `NotificationListener.java:126-131`)?

VERDICT: DEFECT

Confirmed. `reopen` rewrites status, message, notes, handledBy/At and joinedNotifiedAt but never `requestedByUserId` (`api/domain/entity/CreatorConnectionRequest.java:113-121`); the field is set only at build time (`ExternalCreatorService.java:352-358`). So the join email goes to the *original* requester: `ExternalCreatorLinkService.java:128` passes `request.getRequestedByUserId()` into `ConnectedCreatorJoinedEvent`, and the listener resolves the recipient from that id via `emailOf` (`NotificationListener.java:126-131`, used at the `notify(...)` call in `on(ConnectedCreatorJoinedEvent)`). The colleague who actually re-requested — and who saw the "we'll email you when they join" toast (`fe/components/brand/discover/creator-discovery.tsx:2148-2151`) — gets nothing. The admin email is correct, because it is published with `principal.getUserId()` and `workspace.getName()` (`ExternalCreatorService.java:365-373`).

If the original user was removed, `userRepository.findById(...)` returns empty → `emailOf` returns null → `queueEmailIfNotUnsubscribed` logs a warning and queues nothing (`api/service/notification/NotificationService.java:86,128-131`), while the in-app row is still written against the dead user id. `joined_notified_at` is then stamped anyway, so the miss is permanent and silent.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"High","title":"Join notification goes to a stale requester and is silently lost if that user was removed","where":"influora-api/src/main/java/com/influora/domain/entity/CreatorConnectionRequest.java:113-121","issue":"reopen() keeps the original requestedByUserId, so after a DECLINED reopen by a different member the brand.connected_creator_joined email (ExternalCreatorLinkService.java:128) reaches the wrong colleague; if that user is gone, emailOf returns null, NotificationService.java:128-131 queues nothing, and joined_notified_at is stamped regardless — the headline 'creator joined' promise is dropped with only a WARN.","fix":"Take requestedByUserId in reopen(message, userId) and set it from principal; in the listener, fall back to the workspace owner/admins when emailOf(userId) is null, and only stamp joined_notified_at when an email was actually queued."}
```

### Q3.3 [WHY NOT THIS WAY]
> The admin notification bypasses `NotificationService`/`EmailOutbox` and calls `Msg91EmailClient` directly from an `@Async` listener (`NotificationListener.java:652-656`), so a transient SMTP failure is a single `log.error` and the request is lost to admins forever — the console page is the only recovery. Why not queue it through the outbox (with retries/backoff that `EmailWorker` already gives every other template) using a synthetic admin recipient, the way `creator.not_connected` gets special-cased for a non-user recipient?

VERDICT: GAP

The bypass is deliberate and documented (`NotificationListener.java:630-635`): `NotificationService.notify` writes an in-app `Notification` keyed on `event.userId()` (`NotificationService.java:86` and the `createInAppNotification` it calls) and there is no admin user row to own it, so the standard pipeline would either create an in-app notification for the wrong user or need a new admin channel.

The durability consequence is nonetheless real and not mitigated. `Msg91EmailClient.sendTemplateEmail` returns `false` when SMTP is unconfigured or the send throws outside dev (`api/integration/msg91/Msg91EmailClient.java:129,132-148`), and the listener's entire response to `false` is one `log.error` (`NotificationListener.java:657-661`). Nothing retries, nothing marks the request, no metric.

`creator.not_connected` is not the precedent the question suggests: it is special-cased in `EMAIL_ONLY_EVENTS` (`NotificationService.java:42`) to suppress the *in-app* half, but its recipient is still a real `User`. The outbox does support a null `userId` for pre-account recipients (`Msg91EmailClient.java:124-125`), so an outbox row with `userId=null` and the admin address is the correct fix — it inherits `EmailWorker`'s retry/backoff/FAILED handling for free.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Admin connection-request email has no retry: a transient SMTP failure loses the notification permanently","where":"influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:652-661","issue":"The @Async listener calls Msg91EmailClient directly; sendTemplateEmail returning false (Msg91EmailClient.java:132-148) produces one log.error and nothing else — no outbox row, no retry, no marker on creator_connection_requests. The admin console is the only recovery and nothing tells anyone to look.","fix":"Queue an EmailOutbox row with userId=null (the pre-account recipient path the client already supports) so EmailWorker retries with backoff, keeping the in-app half suppressed as today."}
```

### Q3.4 [WHEN WILL IT BREAK]
> Two clicks / two tabs POST `/creators/external/{id}/connect` concurrently for the same (workspace, creator): both `findByWorkspaceIdAndExternalCreatorId` return empty (`ExternalCreatorService.java:335-337`), both build a new row (L351-358), and the second `save` hits `uk_ccr_workspace_creator` (migration L61). Unlike `CreatorDiscoveryService.invite` (L492-498) there is no `DataIntegrityViolationException` catch here — is that a 500 to the brand and a duplicate admin email from the first, and does the "Request sent" FE state survive the 500?

VERDICT: DEFECT

The premise is right about the race and wrong about the 500. There is no local catch in `connect` (`ExternalCreatorService.java:336-361`), unlike `CreatorDiscoveryService.java:493-499` which maps the dup to a 409 `COLLABORATION_EXISTS`. But `GlobalExceptionHandler.java:110-115` catches `DataIntegrityViolationException` globally and returns **409 `DATA_INTEGRITY_VIOLATION`**, not a 500. The ULID is assigned, so the insert flushes at commit — still inside the transactional proxy, still translated, still caught.

Only one admin email: the losing transaction rolls back before commit, and the listener is `AFTER_COMMIT` (`NotificationListener.java:635-637`), so its event is never delivered.

The user-visible defect is the toast. The contract (TASKS.md L87) says a duplicate is idempotent 200 with the existing request; instead the second tab gets a 409 whose message is "The request could not be completed due to a data conflict", rendered by `submitConnect`'s generic catch as a destructive "Could not send request" (`fe/components/brand/discover/creator-discovery.tsx:2154-2159`). That tab never runs `applyConnectionResult` (L2146), so its card stays on "Connect this creator" until a refetch — the brand is told the request failed when it succeeded.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"Concurrent connect returns 409 DATA_INTEGRITY_VIOLATION instead of the contracted idempotent 200","where":"influora-api/src/main/java/com/influora/service/ExternalCreatorService.java:336-361","issue":"Double-click/two-tab POSTs race past findByWorkspaceIdAndExternalCreatorId; the loser violates uk_ccr_workspace_creator and falls through to GlobalExceptionHandler.java:110-115 (409 DATA_INTEGRITY_VIOLATION). The brand sees 'Could not send request' and a card still offering 'Connect this creator' although the request was created. Not a 500, and only one admin email (the loser rolls back before the AFTER_COMMIT listener).","fix":"Wrap the save in try/catch (DataIntegrityViolationException) as CreatorDiscoveryService.java:493-499 does, re-read the row, and return it as the idempotent 200 the contract specifies; also disable the dialog's Send button on the pending promise."}
```

### Q3.5 [WHAT TO ADD]
> `connect` accepts any `id` that exists in `external_creators` regardless of `source`/`status`, and there is no per-workspace cap: a brand can create one request per external creator across the whole table (`list` returns *all* statuses, `ExternalCreatorService.java:111`), each firing an admin email. What rate/volume guard (per-workspace daily cap, or requiring the creator to have been looked up by this workspace) is missing before an admin inbox can be flooded by one brand clicking through 20 pages of imported handles?

VERDICT: GAP

Confirmed, and nothing anywhere on the path limits it. `connect` loads by id and rejects only a JOINED-and-linked creator (`ExternalCreatorService.java:313-328`); `source` and the UNVERIFIED/INVITED statuses are never consulted. `list` passes `null` as the status filter (`ExternalCreatorService.java:111`), so every ADMIN_IMPORT stub in the table is connectable by every workspace, and nothing scopes rows to the workspace that discovered them. The controller carries no rate-limit annotation or filter (`api/web/ExternalCreatorController.java:58-66`) — only `@Valid` on the body. The per-request cost is one row plus one admin email each (`ExternalCreatorService.java:361-373`).

Missing, in the order I would add them: (1) a per-workspace daily cap on new requests (config-driven, 409/429 past it) — the single cheapest guard; (2) a per-workspace de-dup window so reopen-after-DECLINED cannot be looped; (3) digest or batching of `admin.creator_connection_requested` when a workspace fires several within a window; (4) optionally requiring the row to have been looked up or listed by that workspace, though I would not gate on that — it breaks the admin-import discovery case the task exists for.

This is latent while Q3.1's recipient is blank, but ships the moment an address is set.

FINDING:
```json
{"tester":"Priya","type":"Functional","severity":"Medium","title":"No per-workspace cap or rate limit on POST /creators/external/{id}/connect","where":"influora-api/src/main/java/com/influora/web/ExternalCreatorController.java:58-66","issue":"connect accepts any external_creators id regardless of source/status (ExternalCreatorService.java:313-328) and list exposes every row unfiltered (L111); one brand can create a request per creator across the whole table, each publishing an admin email (L361-373). No annotation, filter or counter caps this.","fix":"Add a config-driven per-workspace daily cap on new/reopened requests returning 429, plus digest/batching of admin.creator_connection_requested when several fire inside a window."}
```
