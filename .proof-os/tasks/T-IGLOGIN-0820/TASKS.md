# T-IGLOGIN-0820 — Add Instagram Login so creators can connect without a Facebook Page

Opened 2026-08-20. Owner: vikram (backend) + ananya (frontend). Reviewer: kabir.

## The rule, verified

Source: Meta, Instagram Platform Overview (updated 2026-03-09), read via DevTools MCP
`devtools_discovery` on 2026-08-20. NOT from memory, NOT from a blog.

| Component | Instagram Login | Facebook Login (what Influora uses today) |
|---|---|---|
| Facebook Page | **x — not required** | **Required** |
| Access token type | Instagram User | Facebook User or Page |
| Host | graph.instagram.com | graph.facebook.com |
| Insights | yes | yes |
| Messaging | native | via Messenger Platform |
| Hashtag search | x | yes |
| Product tagging | x | yes |
| Partnership Ads | x | yes |

Verbatim: "This API setup does not require a Facebook Page to be linked to the Instagram
professional account." And for Facebook Login: "your app user must also be able to perform
admin-equivalent tasks on the linked Facebook Page."

**Conclusion: the Page requirement is a property of the CONFIGURATION, not a rule that
changed.** It disappears only by adding the Instagram Login path. Nothing in the current
code is wrong about this; it is a product-coverage gap.

## done_when

`gates/build.mvn.sh` exits 0 AND a creator with an Instagram professional account and NO
Facebook Page completes connect end-to-end on a live app, with insights persisted.
(The live half is blocked on F-0365 — the app is in Development Mode.)

## Why not just switch

Instagram Login loses Partnership Ads, hashtag search and product tagging. Partnership
Ads is the branded-content/whitelisting surface this product would plausibly sell.
Recommended shape: **support BOTH**, creator chooses; Facebook Login stays for creators
who want ads features.

## Code changes

### Backend
1. `config/MetaApiProperties.java` — add `instagramAppId`, `instagramAppSecret`,
   `instagramRedirectUri`. The Instagram app credentials are DISTINCT from the Facebook
   app id already configured; do not reuse.
2. `integration/meta/oauth/MetaOAuthService.java` — second authorize-URL builder against
   `https://www.instagram.com/oauth/authorize` with scopes
   `instagram_business_basic,instagram_business_manage_insights`; token exchange POSTs to
   `https://api.instagram.com/oauth/access_token`, then long-lived exchange via
   `graph.instagram.com/access_token?grant_type=ig_exchange_token`.
   Do NOT send the Facebook-Login scopes on this path — the names differ.
3. `integration/meta/client/MetaGraphApiClient.java` — host is currently hardcoded to the
   Facebook graph. It must select `graph.instagram.com` vs `graph.facebook.com` from the
   token's path. This is the single highest-risk edit: every existing call routes through it.
4. `domain/entity/MetaOAuthToken.java` + Flyway migration — add an `auth_path` discriminator
   (`FACEBOOK_LOGIN` | `INSTAGRAM_LOGIN`). Existing rows backfill to `FACEBOOK_LOGIN`.
5. `job/MetaTokenRefreshService.java` — Instagram long-lived tokens refresh via
   `graph.instagram.com/refresh_access_token`, a different endpoint and cadence from the
   Facebook path. Branch on `auth_path`.
6. `integration/meta/client/FacebookPageClient.java` — must be SKIPPED on the Instagram
   path. There is no `/me/accounts` and no Page; the IG user id comes straight from the
   token exchange.
7. `service/MetaConnectionService.java` — connection status must report which path a
   creator used, so the UI and support can tell them apart.
8. `web/MetaOAuthController.java` — `/meta/oauth/authorize` takes a path parameter, and
   the callback must resolve which path the state belongs to (extend `MetaOAuthStateStore`).

### Frontend
9. `src/components/creator/connected-accounts.tsx` — replace the single "Connect Instagram"
   button with a choice, and state the prerequisite BEFORE the click (this also closes
   **F-0357**): Instagram-only = no Facebook Page needed; Facebook = required, and needed
   for ads features later.
10. `src/pages/creator-meta-callback.tsx` — handle both callback shapes.

### Tests
11. Cover: IG-Login authorize URL and scopes; host selection in `MetaGraphApiClient` for
    both paths; `FacebookPageClient` never invoked on the IG path; refresh branching.

## RULING (Swapnil, 2026-08-20) — ship both paths, creator self-selects

The connect flow ASKS the creator whether they have a Facebook Page linked to their
Instagram account, then routes:

- **"Yes"**  -> Facebook Login for Business (existing path, existing scopes, full feature
  set incl. Partnership Ads later)
- **"No"**   -> Instagram Login (no Page needed, loses ads/hashtag/product-tagging)

### Required: the answer is a HINT, not a fact
Creators frequently do not know whether their Instagram is linked to a Page. A wrong
"yes" must NOT dead-end.

- If the creator answers "yes" and the Facebook path returns no page carrying an
  `instagram_business_account` (or `/me/accounts` is empty), the UI must say so plainly
  and offer the Instagram-Login path in the same screen — never a bare error.
- If the creator answers "no" but later wants ads features, connecting via Facebook must
  remain reachable from settings; the choice is not permanent.
- Log which branch was taken and whether it fell back, so onboarding drop-off is
  measurable rather than inferred.

### Still open
- Does an Instagram-Login creator get a visibly different profile badge, given they
  cannot be used for whitelisted ads? (Affects brand expectations at offer time.)

## Blocked by / related
- **F-0365** — app is in Development Mode; no live verification possible until it is Live.
- **F-0357** — pre-connect prerequisite copy, closed by item 9.
- **F-0355** — audience demographics migration still open; the IG path has the same
  `follower_demographics` shape, so do that migration ONCE, not per path.

## NOT CHECKED
Whether Meta grants Advanced Access for `instagram_business_manage_insights` on the same
review as the existing Facebook-Login permissions, or requires a separate submission.
