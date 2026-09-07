# Ops — provision the Instagram lookup system caller (F-0697)

**Status:** open · blocks F-0696 · owner: Swapnil (credentials) + whoever holds VPS root

## Why this exists

`ExternalCreatorService#resolveBusinessDiscoveryCaller` picks who Influora talks to Meta *as* when a
brand looks up an Instagram handle. It tries three callers in order:

1. the requesting brand's own connected Meta token — most brands have never connected one
2. **Influora's own system account** — `META_SYSTEM_IG_USER_ID` + `META_SYSTEM_IG_ACCESS_TOKEN`
3. an arbitrary connected **creator's** `FACEBOOK_LOGIN` token — last resort

Step 2 has never been provisioned. Both halves default to empty
(`influora-api/src/main/resources/application.yml:438-439`) and both compose files forward them as
`${VAR:-}` (`deploy/utho/docker-compose.utho.yml:261-262`,
`deploy/hostinger/docker-compose.hostinger.yml:243-244`). So in production every brand handle lookup
either falls to step 3 or returns `503 INSTAGRAM_LOOKUP_UNAVAILABLE`.

Step 3 is not a safe steady state. It consumes *that creator's* Graph rate limit — `MetaGraphApiClient`
throttles on the same `igBusinessAccountId`, so their own `MetricsPollingJob` starves — and the code
itself records that cross-user token reuse is awaiting a platform-terms ruling and was "escalated, not
resolved."

Since 2026-09-07 the app logs a WARN at boot naming this state
(`InstagramLookupCallerStartupValidator`). The warning is not the fix; this checklist is.

## What to provision

An Influora-owned **Instagram Business or Creator account**, linked to a Facebook Page the Influora
Meta app can administer. This is a real account we own — not a personal one, not a creator's.

The Graph edge it needs to call is
`/{ig-user-id}?fields=business_discovery.username(<target>){...}`
(`InstagramInsightsClient#businessDiscovery`), whose permission is **`instagram_basic`**.

> App `850102124044922` already holds Advanced Access on `instagram_basic` (verified live 2026-09-02).
> **No App Review submission is required for this.** That is what makes F-0697 unblockable today,
> unlike F-0696 — see the bottom of this file.

## Checklist

- [ ] **1. Create/choose the account.** An Instagram Business (or Creator) account for Influora.
      A personal account will not work — Business Discovery only answers for professional accounts,
      on both the caller and the target side.
- [ ] **2. Link it to a Facebook Page** that the Influora Meta app can administer. Business Discovery
      is unavailable on the `graph.instagram.com` host, so this must be the Facebook-Login path — an
      Instagram-Login-only connection will not produce a usable caller.
- [ ] **3. Get the IG user id.** `GET /me/accounts?fields=id,name,instagram_business_account{id}` with
      a Page token. The `instagram_business_account.id` (a 17-digit number, e.g. `1784140…`) is
      `META_SYSTEM_IG_USER_ID`.
- [ ] **4. Mint a LONG-LIVED token.** A short-lived user token expires in ~1 hour and will look like
      an intermittent outage. Exchange it for a long-lived one (~60 days) and record the expiry.
- [ ] **5. Set both vars on the VPS.** Live stack is `/usr/local/App/docker-compose.prod.yml` on Utho
      (NOT `deploy/utho/`, which is the template). Add to the `.env` beside it:
      ```
      META_SYSTEM_IG_USER_ID=<the 17-digit id from step 3>
      META_SYSTEM_IG_ACCESS_TOKEN=<the long-lived token from step 4>
      ```
      **Set both or neither.** Setting one is treated identically to setting none — see the
      half-configured warning.
- [ ] **6. Check the `.env` file mode.** It was `644` (world-readable, 9 secrets) until 2026-09-06.
      Confirm `600` before adding a Meta token to it. `chmod 600 .env`.
- [ ] **7. Restart the API container and read the boot log.** The
      `InstagramLookupCallerStartupValidator` WARN block must be **absent**. If it still warns, the
      variable did not reach Spring — the compose file forwards these two explicitly, so a var set
      only in the shell will not arrive.
- [ ] **8. Smoke test as a brand with no Meta connection.**
      `GET /creators/external/lookup?username=<a real IG professional account>` must return a
      creator, not `503 INSTAGRAM_LOOKUP_UNAVAILABLE`.
- [ ] **9. Diarise the token expiry.** A long-lived token still expires. When it does, lookups fall
      silently back to step 3 — the borrow path — and the boot warning will NOT re-fire, because the
      variable is still set, just stale. This is the one failure mode the startup check cannot see.

## Decision still owed (Swapnil)

**Should step 3 — borrowing a connected creator's token — exist at all?**

Recommendation: **remove it** once step 2 is provisioned. It is a platform-terms exposure and a
silent rate-limit theft from a creator who never agreed to it, and once the system caller works it
should never execute anyway. Keeping it means keeping an untested path that only ever runs when
something else is already broken.

Until that ruling, the code keeps it, deliberately, so lookup degrades to *something* rather than a
hard 503 — that was the right call while no system caller existed. It stops being the right call the
day step 5 is done.

## Relationship to F-0696 (the Instagram tab)

F-0696 is **not** unblocked by this checklist, and the tempting one-line "fix" is a trap.

`META_CREATOR_MARKETPLACE_ENABLED=true` makes `ExternalCreatorService#list` call
`/{ig-user-id}/creator_marketplace_creators` (`CreatorMarketplaceClient:47`). That edge is gated on
**Meta Business Partner** status, which Influora does not hold — verified live 2026-09-02: marketplace
scope absent, `pages_read_engagement` rejected, not a Business Partner. Flipping the flag makes that
call fail on every Discover page load; the code swallows it and falls back to the table, so the brand
sees the same empty tab plus added latency and error noise. **Leave the flag off.**

What this checklist *does* unblock is the only population path that works today: once the system
caller exists, `POST /admin/external-creators/import` enriches imported handles through Business
Discovery instead of storing bare `ADMIN_IMPORT` stubs. That is how the Instagram tab gets real rows
without Business Partner status.

Sequence: **F-0697 → admin import → decide what to do about the tab.** Not the reverse.
