# Campaign Brief — Meta API Migration Announcement
**Owner:** Tejas (CMO) · **Date:** 2026-09-02 · **Status:** CLAIMS VERIFIED — ready for Zara → Nisha

---

## 0 · The claim gate (VERIFIED LIVE 2026-09-02 via Meta DevTools MCP)

App: `App-influora` · app_id `850102124044922` · role admin

| Claim | Supportable? | Evidence (live Meta API) |
|---|---|---|
| Approved by Meta for **Advanced Access** to the Instagram Graph API | ✅ **YES** | instagram_basic, instagram_manage_insights, pages_show_list, business_management — all `DEVOPS_APPROVED`, `is_live:true`, `access_level:advanced` |
| Platform compliant, zero violations | ✅ **YES** | `overall_status: compliant`, 0 violations, 0 required actions |
| Built on Meta's official Graph API | ✅ **YES** | real Meta OAuth, connected-accounts.tsx |
| **"Official Meta Partner" / badge partner** | ❌ **NO** | Meta Business Partner is a separate directory program. No endpoint in DevTools MCP, nothing in app data. Approved Advanced Access is a permission grant, NOT a partnership. |

**RULING: use the Advanced Access line. Do not use "partner".**
Meta Platform Terms prohibit implying endorsement, and the exposure is app_id
850102124044922 — the app the entire product runs on.

### Other findings from the same check (route to Priya, not marketing)
- `pages_read_engagement` → **REJECTED**, access_level `none`. Any code path expecting it fails in prod.
- `instagram_creator_marketplace_discovery` → **absent from privileges entirely.** The P1
  Creator Marketplace feature is still ungranted and unsubmitted.
- Last reviewed submission `863018939419907` was **rejected** ~2026-01-21. Current state
  `UNSUBMITTED`, nothing pending. META-IMPLEMENTATION-PLAN-2026-08-20.md:22 is stale —
  advanced access IS granted and live; that doc says it is unresolved.

---

## 1 · POST A — Public feed carousel (3 slides)

### Caption — OPTION A (safe, ship today)

Influora is now approved by Meta for Advanced Access to the Instagram Graph API.

What that means for you:
Creator reach, engagement and audience data now come straight from Instagram's
official API. Not scraped. Not estimated. Brands see verified numbers, and creators
get credit for the audience they actually have.

Two things to know:

1. We are mid-upgrade, so Influora may be slow or unavailable for the next [X HOURS].
   This is planned. Nothing you have built is lost.

2. If you registered with us before today, you will need to reconnect your Instagram
   once we are back. We will email you the link from [hello@influora.in]. That email
   is the only place to do it. We will never ask you to reconnect through a DM or a
   comment reply.

Back shortly. Thank you for your patience.

#Influora #CreatorEconomy #InfluencerMarketing #IndiaCreators #MetaGraphAPI

### Caption — OPTION B — WITHDRAWN
The "official Meta Business Partner" variant is dead. Verified 2026-09-02: no such
status. Do not revive it without a screenshot of the badge in the Meta Business
Partners directory. The Option A line above is the strongest provable claim we have.

### Slide copy for Zara
- Slide 1 — headline: META-APPROVED. ADVANCED ACCESS. · sub: Verified creator data, straight from the source
- Slide 2 — headline: NOT ESTIMATES. NUMBERS. · sub: Reach, engagement and audience demographics pulled from Instagram's own API
- Slide 3 — headline: RECONNECT YOUR INSTAGRAM · sub: Existing members — we are emailing you the link. Check your inbox.

Design notes: brand palette, strong WCAG-AA contrast on the CTA slide (no pale pastel).
Slide 3 must NOT contain a tappable link or a "click here" — it points to email only.

---

## 2 · POST B — Story sequence (3 frames, same day, post 30 min after the carousel)

- Frame 1: "Big upgrade shipping today." + poll sticker "Are you connected yet? Yes / Not yet"
- Frame 2: "Influora may be down for a few hours. Planned. Nothing is lost."
- Frame 3: "Already registered? Watch your inbox for the reconnect link." + no link sticker

---

## 3 · POST C — Email + in-app to REGISTERED USERS (this is where the reconnect ask lives)

Subject: Action needed — reconnect your Instagram to Influora

Hi [FIRST NAME],

We have upgraded Influora onto Meta's official Graph API. Better data for you,
verified numbers for brands.

One step from you: your existing Instagram connection needs to be re-authorised
under the new integration. It takes about 30 seconds.

  [ RECONNECT INSTAGRAM ]  ->  https://app.influora.in/settings/connections

Your profile, campaigns, deals, wallet balance and history are all untouched.
Only the Instagram connection needs redoing.

Security note: this is the only link we will send. Influora will never ask you to
reconnect through an Instagram DM, a comment, or any other domain.

Questions: [hello@influora.in]

--- Team Influora

---

## 4 · Publishing route

Influora's own product CANNOT publish this. Verified: no instagram_content_publishing
scope exists anywhere in the codebase. The Meta app holds READ scopes only
(instagram_basic, instagram_manage_insights, pages_show_list). It reads and verifies
posts; it does not create them.

So the post goes out one of two ways:

  Route 1 (manual, recommended for this one):
    Zara exports slides -> Nisha approves -> post natively from the @influora
    Instagram account on a phone. One post, high stakes, do it by hand.

  Route 2 (Postiz):
    Nisha queues in SHARED_CONTEXT.md -> Dev pushes to Postiz -> scheduled.
    NOTE: Postiz is referenced in team skill docs but is NOT configured in this repo.
    Dev must confirm the @influora account is actually connected before relying on it.

Approval chain: Tejas (this brief) -> Swapnil (partner claim ruling) -> Zara (slides)
-> Nisha (final content approval) -> publish.

---

## 5 · Sequencing (do not reorder)

1. ~~Swapnil rules on Option A vs B.~~ SETTLED 2026-09-02 — Option A, verified live.
2. Send POST C email to all registered users FIRST.
3. Publish POST A carousel 1 hour later.
4. POST B stories 30 min after that.
5. Pin POST A to the profile grid until the migration is fully done.

Rationale: users who read the public post must already have the real email sitting
in their inbox, or the announcement itself becomes the phishing vector.
