# QA answers — F5 (Priya, CTO). T-CREATORCONNECT-0902, branch `fix/f0390-money-flags-build-pipeline`, 2026-09-03.

Path shorthand as in QA-QUESTIONS.md. Line numbers below are the ones I read today; where the question's
citation drifted I give the real one (Q5.5's `signup_url` is at `AdminCreatorConnectionService.java:244-245`,
not 375-377 — that range is `resolveAnyBusinessDiscoveryCaller`).

## F5 — Join detection hook + brand email

### Q5.1 [IS IT WORKING]
> Show me the full live path for the one call site that carries both id and username: creator completes FACEBOOK_LOGIN → `CreatorMetaOAuthService` L138-148 → `MetaTokenStorage.storeCreatorToken` 8-arg → `ExternalCreatorLinkService.onCreatorIdentified` → `ConnectedCreatorJoinedEvent` → `NotificationListener.on(...)` → in-app row + `brand.connected_creator_joined` email → `joined_notified_at` stamped. Which environment has produced a row with `joined_notified_at IS NOT NULL`?

VERDICT: PARTIAL

The wiring is complete and I can walk every hop in source:
`CreatorMetaOAuthService.java:138-148` passes `igAccount.username()` — real, because
`FacebookPageClient.java:23-24` requests `instagram_business_account{id,username,followers_count}` —
into the 8-arg `MetaTokenStorage.storeCreatorToken` (`MetaTokenStorage.java:285-293`), which calls the
hook after `repository.save(entity)` at `MetaTokenStorage.java:346`. The hook matches by id
(`ExternalCreatorLinkService.java:76-78`), flips the row (`:106`), flips open requests and publishes one
event each (`:109-134`). `NotificationListener.java:671-673` is `@Async @TransactionalEventListener(AFTER_COMMIT)`,
notifies at `:683-695` with template `brand.connected_creator_joined` and
`campaign_url = webBaseUrl + "/brand/campaigns/new?creatorId=" + creatorProfileId`, then stamps
`joined_notified_at` at `:697-700`.

What I cannot show is any execution of it. The only coverage is
`ExternalCreatorLinkServiceTest.java:34` — four pure-Mockito tests, no `EntityManager`, no listener, no
email. Nothing in this repo exercises `NotificationListener.on(ConnectedCreatorJoinedEvent)`, and the whole
task is uncommitted on this branch, so it is not on the Hostinger or Utho image. I found no artefact —
log line, ledger entry, journal entry, or captured row — indicating any environment has ever produced
`joined_notified_at IS NOT NULL`. Code-proven, environment-unproven.

FINDING: none

### Q5.2 [HOW]
> `MetaTokenRefreshService` routes every ~55-day creator refresh through the same 8-arg `storeCreatorToken` with `igUsername=null` but `igBusinessAccountId` present, so `onCreatorIdentified` re-fires on every refresh, matches by id, and `markJoined` re-stamps `joined_at`/`updated_at`. What else moves on each refresh: does the row bubble back to the top of the `updatedAt DESC` Discover list, and what guarantees no second `ConnectedCreatorJoinedEvent` for a request that was reopened after DECLINED post-join?

VERDICT: DEFECT

Confirmed on both counts.

Re-fire: `MetaTokenRefreshService.java:190-201` passes `igUsername=null` with `igBusinessAccountId` present,
so the hook takes the id branch (`ExternalCreatorLinkService.java:76-78`), passes the relink guard
(`:89-102` only refuses a *different* profile id) and reaches `markJoined` unconditionally at `:106`.

What moves: `ExternalCreator.markJoined` (`ExternalCreator.java:231-236`) sets `joinedAt = Instant.now()`
**and** `updatedAt = Instant.now()` every time, and `:107` persists it. `ExternalCreatorService.list`
sorts `Sort.by(DESC, "updatedAt")` (`ExternalCreatorService.java:115`), so yes — every ~55 days a
background token refresh silently promotes that creator to the top of every brand's Discover page. And
`joined_at` — the record of when the creator actually joined — drifts forward forever. This is exactly the
bug `storeCreatorToken`'s own F-0173 comment (`MetaTokenStorage.java:294-301`) exists to prevent for
`createdAt`; the same discipline was not applied here.

Second event: nothing guarantees it. `CreatorConnectionRequest.reopen` (`:113-121`) sets status back to
`PENDING` and explicitly nulls `joinedNotifiedAt` at `:119`. The listener's idempotency check
(`NotificationListener.java:675-681`) reads that same field, so it is disarmed. The next refresh re-flips the
reopened request (`ExternalCreatorLinkService.java:109-134`) and re-emails. (In practice the reopen path is
hard to reach post-join because `ExternalCreatorService.java:324-328` throws 409 before `:347` — but that
409 needs `linkedCreatorProfileId != null`, so a JOINED row whose link was never set still reaches it.)

FINDING: {"tester":"Priya","type":"Functional","severity":"Medium","title":"Meta token refresh re-runs the JOINED hook: joined_at drifts, Discover re-sorts, and a reopened request re-emails the brand","where":"influora-api/src/main/java/com/influora/domain/entity/ExternalCreator.java:231-236","issue":"MetaTokenRefreshService.java:190-201 calls the 8-arg storeCreatorToken every ~55 days with igBusinessAccountId set, so ExternalCreatorLinkService.java:106 calls markJoined again on an already-JOINED row. markJoined re-stamps joinedAt and updatedAt unconditionally, so (a) the real join date is lost, and (b) ExternalCreatorService.java:115 sorts updatedAt DESC, so a token refresh bumps that creator to the top of every brand's Discover list. Separately, CreatorConnectionRequest.reopen:119 nulls joinedNotifiedAt, which is the exact field NotificationListener.java:675-681 uses for idempotency — a reopened request will be re-flipped and re-emailed by a background refresh.","fix":"Make markJoined idempotent: no-op (or only set linkedCreatorProfileId) when status is already JOINED and linkedCreatorProfileId equals the incoming id, so joinedAt/updatedAt are set once. Preserve joinedAt the way MetaTokenStorage preserves createdAt (F-0173). Do not clear joinedNotifiedAt in reopen(); keep it as a permanent 'this brand has already been told' marker, or gate the hook on a real identity change rather than every token write."}

### Q5.3 [WHY NOT THIS WAY]
> Why does `PortfolioService.upsertPlatformStat` call the hook with `metric.getUsername()` and `igAccountId=null` when the `igBusinessAccountId` is on the same `MetaOAuthToken` row (`metaOAuthTokenRepository` injected at L105) — and why does `CreatorProfileService.applyUsername` feed an *Influora* username into a matcher that expects an *Instagram* handle at all?

VERDICT: DEFECT

First half — confirmed and cheap to fix. `PortfolioService.syncPlatforms` already loads the token row at
`PortfolioService.java:264-276` and reads `tokenRow.getIgBusinessAccountId()` into a local at `:276`,
then calls `upsertPlatformStat(profile, "INSTAGRAM", metric)` at `:313`. The hook call at `:367-369`
passes `igAccountId = null` anyway. The exact id is not "one repository call away" — it is already in
scope one frame up and is simply not threaded through the private method's signature. The consequence is
that the only fully-trustworthy match key is discarded at the one call site that provably has it from a
real Meta sync, forcing the weak username branch (`ExternalCreatorLinkService.java:79-83`).

Second half — this is the root of Q5.4 and I do not accept the code comment's justification. The comment at
`CreatorProfileService.java:176-179` argues that `ensureUsername`'s auto-slug is "semantically wrong" while
`applyUsername` is a legitimate "claim your handle". That distinction does not hold: both write
`creator_profiles.username`, which is an **Influora vanity handle**, never a verified Instagram identity.
Nothing between `patchMyProfile` (`:62-68`) and the hook call (`:180`) checks that the caller controls the
Instagram account of the same name. Feeding it into a matcher keyed on `ig_username` conflates two
namespaces, and the result is the spoof in Q5.4.

FINDING: {"tester":"Priya","type":"Functional","severity":"Medium","title":"JOINED hook discards the IG business account id at the one site that has it from a real Meta sync","where":"influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:367-369","issue":"syncPlatforms already resolves tokenRow.getIgBusinessAccountId() at PortfolioService.java:276 before calling upsertPlatformStat at :313, but the hook call at :367-369 passes igAccountId=null, so ExternalCreatorLinkService falls back to the case-insensitive ig_username branch (:79-83). That branch cannot match a renamed handle, cannot match a handle containing a dot after normalization, and is the weaker of the two keys. The strongest available identity signal in the whole feature is thrown away.","fix":"Thread the resolved igBusinessAccountId from syncPlatforms (PortfolioService.java:276) into upsertPlatformStat and pass it as the third argument at :367-369, so the hook takes the exact ig_account_id branch. Keep metric.getUsername() as the secondary key."}

### Q5.4 [WHEN WILL IT BREAK]
> Identity spoof via the username path: any creator can PATCH their Influora username to equal a targeted external handle, and `onCreatorIdentified` will mark that row JOINED, link *their* profile id, flip the brand's request to JOINED and email the brand with a `creatorId` pointing at the impostor. Conversely, a real handle containing a dot can never match through this path. What prevents the first, and what is the intended match path for the second?

VERDICT: DEFECT

**Nothing prevents the first.** I traced it end to end and every link holds:

1. Reachable with no Meta connection whatsoever. `CreatorProfileService.patchMyProfile:62-68` calls
   `applyUsername` for any authenticated creator principal; `PortfolioService.updateMine:204-210` is a
   second, equally open entry point. Neither consults `MetaOAuthToken`.
2. `applyUsername:165-174` validates shape and Influora-uniqueness only — `UsernameUtils.VALID`
   (`UsernameUtils.java:8`) is `[a-z0-9][a-z0-9_]{1,28}[a-z0-9]`, no ownership proof anywhere.
3. `:180` calls `onCreatorIdentified(profile.getId(), username, null)` — attacker-chosen string, `igAccountId` null.
4. `ExternalCreatorLinkService.java:79-83` matches it against `ig_username` case-insensitively.
5. The only guard, `:89-102`, refuses relink **only when `linkedCreatorProfileId` is already set to a
   different profile**. Every UNVERIFIED/INVITED row — i.e. every row this feature is about — has that
   field NULL, so the guard is inert exactly where it matters.
6. `:106` `markJoined(impostorId)`; `:123-134` flips every PENDING/CONTACTED request to JOINED and publishes
   `ConnectedCreatorJoinedEvent` carrying the impostor's `creatorProfileId`.
7. `NotificationListener.java:683-695` emails the brand "@handle joined Influora" with
   `campaign_url = /brand/campaigns/new?creatorId=<impostor>`, which F6 turns into a real `api.creators.invite`.

So a targeted handle is a first-come land grab that ends in the brand being told to start a paid
collaboration with the wrong person.

**Dots.** Correct, and worse than stated: `UsernameUtils.normalize` (`:16`) runs `SlugUtils.slugify`, whose
`NON_LATIN = [^\w-]` (`SlugUtils.java:9,22`) **strips** the dot rather than mapping it, so `foodie.mumbai`
becomes `foodiemumbai` — it can never equal the stored `foodie.mumbai`. The intended path for dotted
handles is the id/handle-from-Meta route: `CreatorMetaOAuthService.java:138-148` (id + real username) or
`PortfolioService.java:367-369` (real handle from a Meta sync). Worth noting the unit test fixture uses
`foodie.mumbai` (`ExternalCreatorLinkServiceTest.java:64,116,119`) — a handle that is unreachable through
the very call site this defect lives on.

FINDING: {"tester":"Priya","type":"Security","severity":"Critical","title":"Identity spoof: claiming an Influora username equal to an external Instagram handle links the impostor to the external creator row and emails the brand to hire them","where":"influora-api/src/main/java/com/influora/service/CreatorProfileService.java:180","issue":"applyUsername feeds an unverified Influora vanity handle into ExternalCreatorLinkService.onCreatorIdentified, which matches it against external_creators.ig_username (:79-83). Reachable by any authenticated creator via PATCH profile (CreatorProfileService.java:62-68) or portfolio update (PortfolioService.java:204-210) with no Meta connection and no proof of controlling the Instagram account. The relink guard at ExternalCreatorLinkService.java:89-102 only fires when linkedCreatorProfileId is already set, which is never true for an UNVERIFIED/INVITED row. Result: the external row flips to JOINED with the impostor's linked_creator_profile_id, every open connection request flips to JOINED, and the brand receives 'X joined Influora' with campaign_url carrying the impostor's creatorId (NotificationListener.java:683-695) — an impersonation that terminates in a paid collaboration.","fix":"Remove the onCreatorIdentified call at CreatorProfileService.java:180 — an Influora username is not an Instagram identity and must never be a match key. Restrict the hook to Meta-verified sources only: MetaTokenStorage.java:346 (id + username from the OAuth exchange) and PortfolioService.java:367-369 (handle from a real Meta sync, with the ig_account_id threaded through per Q5.3). If a username-only link is still wanted, gate it on the caller holding a non-revoked MetaOAuthToken whose igBusinessAccountId resolves to that handle. Additionally harden ExternalCreatorLinkService.java:89-102 to require an ig_account_id match before ever setting linked_creator_profile_id on a row that has none."}

### Q5.5 [WHAT TO ADD]
> The invite email's `signup_url` carries `?ref=influora-invite&handle={igUsername}` but `fe/pages/creator-register.tsx` never reads `searchParams`, so the handle is dropped. Also `onCreatorIdentified`'s `catch (Exception)` cannot catch a constraint violation raised at flush/commit by the surrounding `@Transactional` proxy. What is missing: an invite-token-based claim at registration, and a test that a flush-time violation inside the hook does not 500 the Meta OAuth callback?

VERDICT: GAP

Both halves confirmed.

**Handle dropped.** `AdminCreatorConnectionService.java:244-245` builds
`webBaseUrl + "/creator/register?ref=influora-invite&handle=" + external.getIgUsername()`.
`src/pages/creator-register.tsx` is 321 lines and imports only `useNavigate, Link` from
`react-router-dom` (`:2`) — no `useSearchParams`, and no `handle`/`ref` identifier anywhere in the file.
The parameters are inert. An invited creator who registers by email and stops before connecting Meta never
links, so step 5 of Swapnil's flow (brand emailed on join) silently never fires for the invite path it was
built for. What is missing is a signed, single-use invite token (bound to the `external_creators` row, not
to a guessable handle) carried into `creatorRegister` and consumed server-side — a `?handle=` query param
must never itself be a claim, or it reproduces Q5.4 with an even lower bar.

**Flush-time failure.** The nuance matters: `externalCreatorRepository.save` at
`ExternalCreatorLinkService.java:107` merges a managed entity, so the INSERT/UPDATE is not issued there. The
repository query at `:109-112` triggers a Hibernate auto-flush, so a `uk_external_creators_ig_account`
violation would in fact surface *inside* the try block. But catching it does not help: a failed flush marks
the persistence context rollback-only, so the `@Transactional` proxy at `:63` throws
`UnexpectedRollbackException` at commit — after `catch (Exception)` at `:135-143` has already logged
"caller's own write is unaffected". The Meta OAuth callback 500s anyway. The comment's promise is false for
this class, and untested: `ExternalCreatorLinkServiceTest.java:34` is pure Mockito with no
`EntityManager`, so no test can ever reach a flush.

FINDING: {"tester":"Priya","type":"Functional","severity":"Medium","title":"Invite signup_url's handle/ref params are never read at registration, and the hook's 'never fail the caller' catch cannot survive a flush-time constraint violation","where":"src/pages/creator-register.tsx:2","issue":"AdminCreatorConnectionService.java:244-245 emits /creator/register?ref=influora-invite&handle={igUsername}, but creator-register.tsx imports only useNavigate and Link from react-router-dom and never reads searchParams, so the invite context is discarded — the JOINED flip depends entirely on the creator later connecting Meta. Separately, ExternalCreatorLinkService.java:135-143 swallows the exception but a flush failure has already marked the transaction rollback-only, so the @Transactional proxy at :63 throws UnexpectedRollbackException at commit and the Meta OAuth callback 500s regardless. ExternalCreatorLinkServiceTest.java:34 is pure Mockito with no EntityManager, so this is untested.","fix":"Issue a signed single-use invite token bound to the external_creators row (never trust a bare ?handle=), read it in creator-register.tsx, pass it to the register call, and consume it server-side to link the row — the claim must be server-verified, not URL-supplied. For the hook, run it in REQUIRES_NEW (or via an AFTER_COMMIT listener on the caller's transaction) so its failure cannot poison the caller's transaction, and add a @DataJpaTest that inserts a conflicting ig_account_id row and asserts the caller's write still commits."}
