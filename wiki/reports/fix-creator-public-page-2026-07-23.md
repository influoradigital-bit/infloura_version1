# Fix: Creator public-page link (P-1) + Tejas Creater follower gap (C-1) — 2026-07-23

Author: Vikram (Backend). Source finding: `wiki/reports/test-report-creator-side-live-2026-07-23.md` (P-1), `wiki/reports/test-report-brand-side-live-2026-07-23.md` (C-1).

## P-1 (blocking) — `/@creator` 404s

### Root cause

`creator_profiles.username` is nullable, and nothing had ever persisted a value for the
`demo.creator@influora.com` account (it was created directly on the live server, not via a seed
migration — profile completeness was 60%, matching displayName+bio+categories+city+rate, with
username, avatarUrl and platforms all absent).

Two independent frontend call sites both derive the public link from `username`, and both had
gaps that surfaced this null:

1. **`src/pages/creator-profile.tsx:172-173`** (the Profile page banner QA hit) — fell back to
   the **literal string `'creator'`** when `profile.username` was empty:
   ```ts
   const publicUsername =
     profile.username || profile.platforms[0]?.handle.replace(/[@_]/g, '') || 'creator';
   ```
   With no username and no connected platforms, this produced `/@creator` — a link to a handle
   nobody owns. (The `platforms[0]?.handle` branch was also semantically wrong: an Instagram
   handle and an Influora public-page username are different namespaces and are not
   interchangeable.)

2. **`influora-api/.../service/portfolio/PortfolioService.java` `resolveUsername()`** (used by
   the Portfolio editor's own "your public page" link) had the same shape of bug one level down:
   when `profile.getUsername()` was blank it computed a slug from `displayName` on the fly for
   display purposes but **never persisted it**:
   ```java
   private String resolveUsername(CreatorProfile profile) {
       if (profile.getUsername() != null && !profile.getUsername().isBlank()) {
           return profile.getUsername();
       }
       return UsernameUtils.normalize(profile.getDisplayName());
   }
   ```
   So even the Portfolio editor page would have shown a plausible-looking `/@demo_creator` link
   that also 404s, because that value was never written to `creator_profiles.username` and the
   public lookup (`CreatorProfileService.requireProfileByUsername`) is a straight
   `findByUsernameIgnoreCase`.

**Verdict: this is a code bug, not (only) a data-setup gap.** The system had no code path that
ever assigns a real username to a profile that didn't go through an explicit "claim your handle"
PATCH — every read path either fell back to a placeholder or a display-only, unsaved slug.

### Fix

Added one persisting method, `CreatorProfileService.ensureUsername(CreatorProfile profile)`
(`influora-api/src/main/java/com/influora/service/CreatorProfileService.java:119-163`), and wired
it into both read paths that hand a username to the frontend:

- `CreatorProfileService.getMyProfile()` (backs `GET /me/creator-profile`, the API
  `creator-profile.tsx` calls) — `influora-api/.../CreatorProfileService.java:50-55`
- `PortfolioService.getMine()` (backs `GET /me/portfolio`, the API `creator-portfolio-editor.tsx`
  calls) — `influora-api/.../portfolio/PortfolioService.java:165-178`

`ensureUsername` is idempotent (no-ops once a username exists), slugifies `displayName` via the
existing `UsernameUtils.normalize`, walks numeric suffixes on collision, and falls back to an
id-derived suffix after 25 attempts instead of looping unbounded. Both call sites' surrounding
`@Transactional` was changed from `readOnly = true` to a normal read-write transaction (required —
`ensureUsername`'s conditional `save()` would otherwise hit a read-only DB connection and fail
silently/throw, since Spring's `readOnly` flag on `REQUIRED` propagation is inherited from the
outer transaction and self-invocation inside the same bean bypasses the AOP proxy that would
otherwise start a fresh `REQUIRES_NEW` transaction).

As defense-in-depth, also fixed the frontend fallback chain
(`src/pages/creator-profile.tsx:172-195`): it no longer substitutes a platform handle or the
literal `'creator'` for a missing username. If `profile.username` is ever still empty (e.g. a
stale cached response from before this deploys), the banner is hidden entirely instead of linking
to a fabricated handle.

### Diff summary

```
influora-api/src/main/java/com/influora/service/CreatorProfileService.java
  + import org.slf4j.Logger / LoggerFactory
  + private static final Logger log
  ~ getMyProfile(): @Transactional(readOnly = true) -> @Transactional; calls ensureUsername(profile)
  + ensureUsername(CreatorProfile profile): new, persists a generated+deduped username

influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java
  ~ getMine(): @Transactional(readOnly = true) -> @Transactional; calls creatorProfileService.ensureUsername(profile)
  ~ resolveUsername(): javadoc only, now documents it's a defensive fallback (behavior unchanged)

src/pages/creator-profile.tsx
  ~ publicUsername: profile.username || platformHandle || 'creator'  ->  profile.username || null
  ~ banner <a> wrapped in `{publicUsername && (...)}` — hidden instead of linking to a fake handle
```

### Verification done

- `influora-api`: offline Maven compile passed clean (`mvn -o compile -DskipTests`, using the
  vendored `.tools/apache-maven-3.9.10`) — both changed classes rebuilt with no errors.
- Read existing `CreatorProfileServiceTest#getMyProfile...` — it constructs a profile with an
  explicit username already set, so `ensureUsername` no-ops there; no test behavior change.
- **Not run**: no live server hit, per instructions not to redeploy. `/@creator` on
  `http://200.141.1.6` will keep 404ing until the backend is rebuilt and redeployed with this
  change — see Redeploy needed below.

### Redeploy needed

Yes. This is a backend (Java/Spring) + frontend (Vite/React) code change; nothing was applied to
the running containers. To actually fix `http://200.141.1.6/@creator`:

1. Rebuild `influora-api` (the class files above must be part of the deployed jar/image).
2. Rebuild the frontend bundle (creator-profile.tsx change).
3. Redeploy both. On the demo creator's **next** authenticated load of `/creator/profile` (or
   `/creator/portfolio`), `ensureUsername` fires once, persists a real username (predicted value
   for "Demo Creator": `demo_creator`, from `UsernameUtils.normalize("Demo Creator")`), and the
   banner + editor link both start pointing at a resolvable `/@demo_creator` handle. No manual DB
   edit or extra QA step is required beyond one authenticated page load post-deploy.

## C-1 (non-blocking) — Tejas Creater shows 0 followers / empty engagement %

### Root cause: data-completeness gap, not a code bug

`creator_profiles.total_followers` / `engagement_rate` are denormalized fields, written only by
`PlatformStatsAggregationJob.aggregateOne()`
(`influora-api/src/main/java/com/influora/job/PlatformStatsAggregationJob.java:150-186`). That job
explicitly, by design, **skips** any creator with no `creator_metrics` row yet rather than
zero-filling:

```java
if (!wroteAny) {
    log.info("... no creator_metrics yet for creator {}, skipping (never fabricating a 0-follower row)", ...);
    return false;
}
```

`creator_metrics` rows only exist for a creator once they've connected a platform (Meta OAuth) and
`MetricsPollingJob` has polled it at least once. The brand-discovery read path
(`CreatorDiscoveryService`) just projects `profile.getTotalFollowers()` /
`profile.getEngagementRate()` straight through (lines 255-256, 320-321, 502-588) — there's no
separate aggregation bug on the read side.

So: **Tejas Creater has never connected/synced a platform** → no `creator_metrics` →
`PlatformStatsAggregationJob` correctly skips them → `totalFollowers`/`engagementRate` stay at
entity defaults (`0` / `null`, rendered as empty %). This matches the creator-side report too:
even **Demo Creator's own profile page** shows "connected accounts (none)" for the `platforms`
list, meaning Demo Creator's 15K/4.5% almost certainly came from an out-of-band manual data seed
directly on `creator_profiles` (for QA/demo purposes) rather than the real aggregation pipeline —
not from a code path Tejas Creater is also entitled to but missing.

### Verdict: no code fix applied

The "never fabricate" behavior in `PlatformStatsAggregationJob` is intentional and correct (its
own javadoc explains why: a zero-filled row would be indistinguishable from a confirmed real zero
and would wrongly pass `minFollowers=0` discovery filters). Fixing C-1 means giving Tejas Creater
either a real connected+polled platform account, or the same kind of manual demo-data seed Demo
Creator apparently has — a data/ops task, not an engineering change. Flagging back to QA/data
setup rather than touching aggregation code.

## Files touched

- `influora-api/src/main/java/com/influora/service/CreatorProfileService.java`
- `influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java`
- `src/pages/creator-profile.tsx`
