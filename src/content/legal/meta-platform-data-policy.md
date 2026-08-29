# Meta Platform Data & Instagram Data Policy

> ⚠️ **v0 DRAFT — PENDING INDIAN LEGAL COUNSEL REVIEW.**

This policy explains exactly what data Influora receives from Meta when a creator connects an Instagram Business or Creator account, why we receive it, how long we keep it, and how a user gets it deleted.

It is a supplement to our [Privacy Policy](/privacy), not a replacement — the Privacy Policy governs everything else we collect. Where the two overlap, this document is the more specific one for Meta-sourced data.

**Last updated: 2026-08-28**

---

## 1. Who this applies to

This policy applies to any Influora user who connects an Instagram Business or Creator account to Influora, through either:

- **Instagram Login** — connecting your Instagram account directly, or
- **Facebook Login** — connecting through the Facebook Page linked to your Instagram Business account.

Connecting is entirely optional. Influora works without it; connecting unlocks verified audience analytics, deliverable verification, and the AI Copilot's performance suggestions. You may disconnect at any time (Section 8).

**Influora Digital Private Limited** ("Influora," "we," "us"), CIN `U73100MH2024PTC434321`, is the controller / Data Fiduciary for this data. Registered state: Maharashtra, India. Registered office: `[REGISTERED ADDRESS — TBD]`.

## 2. Permissions we request, and why

We request the minimum set of permissions the product actually uses. We do not request permission to post, publish, message, comment, advertise, or manage your account on your behalf, and we cannot do any of those things.

**When you connect via Instagram Login:**

| Permission | What it lets us do | Why we need it |
|---|---|---|
| `instagram_business_basic` | Read your Instagram Business/Creator profile and your own media | Confirm the account is really yours, show your handle and follower count, and verify that a campaign deliverable was actually posted |
| `instagram_business_manage_insights` | Read insights for your account and your own media | Produce the reach, impressions, engagement, and audience-demographic reports brands see on your profile and on campaign results |

**When you connect via Facebook Login:**

| Permission | What it lets us do | Why we need it |
|---|---|---|
| `instagram_basic` | Read your Instagram Business/Creator profile and your own media | Same as above |
| `instagram_manage_insights` | Read insights for your account and your own media | Same as above |
| `pages_show_list` | See the list of Facebook Pages you manage | Instagram Business accounts are reached through their linked Facebook Page — we use this only to find which Page your Instagram account is connected to |

We do not request `pages_read_engagement`, `pages_manage_posts`, `instagram_content_publish`, `instagram_manage_comments`, `instagram_manage_messages`, `ads_management`, or any advertising permission.

You may grant some permissions and decline others. We record only what Meta actually reports as granted, and features that depend on a declined permission are shown as unavailable rather than silently failing.

## 3. What we actually store

| Data | Fields | Source |
|---|---|---|
| Instagram profile | Account ID, username, name, biography, follower count, following count, media count, profile picture URL, website URL | Instagram Graph API |
| Your own media | Media ID, caption, media type, media URL, permalink, timestamp, like count, comment count | Instagram Graph API |
| Account insights | Reach, impressions, profile views, engagement and similar metrics over time | Instagram Graph API |
| Audience demographics | **Aggregated only** — audience counts grouped by country, city, age range and gender | Instagram Graph API |
| Access token | Your long-lived Meta access token, and the list of permissions you granted | Meta OAuth |

**What we never receive or store:** your Instagram or Facebook password, your direct messages, your private contact list, the identity of individual followers, or the personal data of any other Instagram or Facebook user. Audience demographics arrive from Meta already aggregated into counts — we cannot see, and do not receive, which individual accounts make up those numbers.

## 4. How we use it

- Verify that a connected account genuinely belongs to you
- Display your verified handle, follower count and audience profile to brands on Influora
- Verify that a paid campaign deliverable was actually published, and measure its performance
- Generate the analytics, scores and AI Copilot suggestions shown in your dashboard
- Detect fake engagement and fraudulent accounts on the platform

**We do not:** sell Meta Platform Data; share it with data brokers, ad networks or advertising platforms; use it for advertising targeting; use it to build profiles of people other than you; or transfer it to any third party except as described in Section 5.

## 5. Who we share it with

- **Brands on Influora** — only the profile and performance data relevant to a campaign you have chosen to participate in, or the public metrics on your own Influora profile. Brands never receive your access token.
- **Cloud infrastructure providers** — for hosting and storage, under contractual confidentiality obligations, acting only on our instructions.
- **AI infrastructure providers** — where an AI feature you use processes your metrics to generate suggestions, under contractual confidentiality obligations. Meta Platform Data is not used to train third-party foundation models.
- **Authorities** — where compelled by applicable law.

## 6. How long we keep it

| Data | Retention |
|---|---|
| Access token | Kept while your connection is active. Long-lived tokens expire roughly every 60 days and are refreshed automatically while connected. Marked revoked immediately on disconnect and no longer usable. |
| Profile, media, insights and demographics | Kept while your connection is active, and retained after disconnect so that historical campaign records and brand payment records stay accurate — until you request deletion (Section 9), or until the record is no longer needed for a completed campaign. |

We also delete Meta Platform Data when Meta instructs us to, when it is no longer necessary for the purpose it was collected, or when the law requires it.

## 7. Security

Access tokens are encrypted at rest with AES using a dedicated encryption key held separately from the database. Tokens are never exposed to the browser, never returned by any API response, and never logged. All traffic to and from Meta uses HTTPS. Access to production data is restricted and audited — every token issue, refresh and revocation is recorded in our audit log.

## 8. How to disconnect

You can disconnect your Instagram account at any time:

1. Sign in to Influora.
2. Go to **Settings → Connected Accounts**.
3. Select your Instagram account and choose **Disconnect**.

Disconnecting immediately revokes our access token, so we stop collecting any new data from Meta about you at that moment. **Disconnecting alone does not erase the data we have already collected** — historical campaign metrics remain attached to campaigns you have already been paid for. To have that data erased as well, make a deletion request (Section 9).

You can also revoke Influora's access from Meta's side at any time, at **Instagram → Settings → Website Permissions → Apps and Websites**, or **Facebook → Settings → Apps and Websites**.

## 9. How to request deletion of your Meta data

**This section is Influora's Data Deletion Instructions.**

To have all Instagram- and Facebook-sourced data we hold about you erased:

1. Email **`info@influora.in`** from the email address on your Influora account.
2. Use the subject line **"Meta Data Deletion Request"**.
3. Include your Influora account email and your Instagram handle.

What happens next:

- We acknowledge your request within **48 hours**.
- We verify you control the account, then delete your stored Meta access token, Instagram profile data, media records, insights and audience demographics within **30 days**.
- We confirm to you in writing when deletion is complete.
- If we must retain a specific record to comply with Indian tax, anti-money-laundering or accounting law — for example the record of a payout already made to you — we tell you exactly which record, why, and for how long. Everything not covered by that legal duty is deleted.

You can make this request whether or not you keep using Influora, and whether or not your account stays open. Deleting your Influora account entirely also triggers deletion of your Meta Platform Data on the same timeline.

If you are unhappy with how we handle the request, contact our Grievance Officer (Section 12) or escalate to the Data Protection Board of India.

## 10. Your rights

Independently of this policy, the Digital Personal Data Protection Act, 2023 gives you the right to access, correct, and erase your personal data, to withdraw consent, to nominate someone to act for you, and to seek grievance redressal. Section 6 of our [Privacy Policy](/privacy) explains how to exercise each of these.

## 11. Our commitments to Meta's terms

Influora's use of information received from Meta APIs adheres to the [Meta Platform Terms](https://developers.facebook.com/terms/) and the [Meta Developer Policies](https://developers.facebook.com/devpolicy/), including their requirements on limited use, data deletion, and onward transfer. We use Meta Platform Data only to provide and improve the features described in Section 4, we request only the permissions those features require, and we delete the data when it is no longer needed for that purpose.

## 12. Changes and contact

We will update this policy when our data practices or Meta's requirements change, and post the revised version here with a new "Last updated" date. Material changes affecting connected accounts will be notified in-product.

Questions about this policy:

- Support: `info@influora.in`
- Phone: `+91 80 6957 8296`
- Grievance Officer: `[GRIEVANCE OFFICER — NAME — TBD]`, `info@influora.in`
- Registered office: `[REGISTERED ADDRESS — TBD]`, Maharashtra, India
