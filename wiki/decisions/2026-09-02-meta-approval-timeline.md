# Meta Approval — Blockers, Timeline, and the Path to the Badge
**For:** Swapnil (CEO) · **From:** Tejas (CMO) · **Date:** 2026-09-02
**Basis:** live Meta DevTools MCP queries against app_id `850102124044922`, not documentation.

---

## TL;DR

We are not waiting on Meta. We are blocked by a stuck draft submission on our own
dashboard, plus six unfilled app settings. Both are days of work. The Creator
Marketplace API is ~6-9 weeks out once unblocked. The "Official Meta Partner"
badge is 6-12 months out and is NOT the near-term prize.

---

## 1 · The hard blocker

    can_submit: false
    cannot_submit_reason: "Cannot submit to App Review while a previous submission is in review."

Contradicted by our own history: the ONLY completed submission is `863018939419907`
(ACTIONED, closed ~2026-01-21). The status endpoint names a DIFFERENT submission id:

    submission_id: 887626066959194   <-- stuck in limbo

Meta believes this is in review. Nothing is actually being reviewed. **Until a human
cancels or completes 887626066959194 in the App Review dashboard, we cannot submit
anything at all** — including instagram_creator_marketplace_discovery.

ACTION: open https://developers.facebook.com/apps/850102124044922/review/
        cancel or complete submission 887626066959194. Owner: Swapnil or Priya. Day 1.

### Correcting the record
The January submission was NOT a failure. It requested 5 privileges; 4 were granted at
ADVANCED level and are live today. Only `pages_read_engagement` was rejected. The
`is_approved:false` flag means "not all five", not "we were turned down."

---

## 2 · URGENT — live signups against a broken compliance surface

App is `live_mode` / `is_live:true` and creators are registering right now.

| Field | Current value | Risk |
|---|---|---|
| `data_deletion_url` | `https://www.facebook.com/` | **PLACEHOLDER.** Hard review fail AND a live data-protection exposure now that real creator data exists. FIX TODAY. |
| `contact_email_verified` | `false` | unverified, and a personal gmail (shinde111ms@gmail.com) |
| `terms_of_service_url` | `null` | missing |
| `description` / `short_description` | `null` | App Review reviewers read these |
| `support_url` | `null` | missing |
| `base_domains` | `null` | not set |
| `privacy_policy_url` | `https://influora.in/privacy` | OK — but note **.in**, while comms use **.ai**. Pick one. |

We are currently `compliant` with 0 violations. That is a real asset and it is what a
placeholder deletion URL puts at risk. Owner: Vikram. Days 1-2.

---

## 3 · Timeline

| Phase | Duration | Gated on |
|---|---|---|
| Cancel stuck submission 887626066959194 | 1 day | opening the dashboard |
| Fix the 6 settings in section 2 | 1-2 days | Vikram |
| Build + record the marketplace demo screencast | ~1 week | working discovery flow |
| Submit App Review | — | above complete |
| Meta review decision | **2-6 weeks** | Meta |
| **Creator Marketplace API live** | **~6-9 weeks from today** | |
| Badge application (separate program) | +6-12 months | usage volume + case studies |

Most of that is Meta's clock. Which is exactly why the stuck submission costs us more
than it looks like it does — every day it sits, the 2-6 week clock has not started.

---

## 4 · The badge — set expectations

Two tiers, and only one is the badge:
- **Member** — tools and collateral. Explicitly NOT badged, may NOT claim partner status.
- **Badged Partner** — directory listing + badge + right to promote the status.

Meta requires a verified business at time of application (we pass), and judges on
"the quantity and quality of your company's work across Meta technologies." That
second bar is demonstrated real usage. We do not have it yet.

NOTE: the "Tech Provider -> Tech Partner" ladder in Meta's developer docs is
**WhatsApp Business Platform specific**. It is not our route. Do not let anyone
send the team down it.

NOTE: the "$1M ad spend / 2 Blueprint certs" figures circulating online are from
SEO blogs, not Meta, and describe the agency/media-buying track, not ours.

Path: unblock -> marketplace scope approved -> real usage volume -> 2-3 case studies
with numbers -> apply -> 4-12 week review.

---

## 5 · The creator surge is an asset — instrument it

We went live for a domain test and took unexpected creator signups. That is precisely
the evidence both App Review and the badge program want. Start capturing now:
signups/day, accounts successfully connected, connection completion rate, 7/30-day
retention. This becomes the case-study data and it costs nothing to collect.

Counter-risk: those creators registered into an app that is about to require Instagram
reconnection. The reconnect email (campaigns/2026-09-02-meta-api-migration-announcement.md
section 3) must go out BEFORE the migration or we lose the cohort we just acquired.

---

## 6 · This week

| # | Action | Owner | Day |
|---|---|---|---|
| 1 | Cancel/complete stuck submission 887626066959194 | Swapnil / Priya | 1 |
| 2 | Replace the placeholder data_deletion_url | Vikram | 1 |
| 3 | Verify contact email; move off personal gmail | Swapnil | 1 |
| 4 | Add ToS URL, support URL, base domains, descriptions | Vikram | 2 |
| 5 | Settle influora.in vs influora.ai | Swapnil | 2 |
| 6 | Send the reconnect email to existing creators | Nisha | 2 |
| 7 | Stand up signup/connection analytics | Vikram | 3 |
| 8 | Re-run devtools_app_review requirements; confirm can_submit:true | Tejas | 4 |
