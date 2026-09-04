# T-MEERA-CREATOR-PHASE-E — Build Spec: Meera Finds Work

**Status:** Priya-reviewed twice (§13, §14); buildable as corrected on the conditions in §14.4. **Baseline commit:** `143ca1e` **plus the uncommitted working tree.** <!-- PRIYA (2nd pass, 2026-09-04): the header said `8c7b18b`; that is HEAD~1. HEAD is `143ca1e`. More precisely, this document's Java line citations match the WORKING TREE, not a clean checkout of either commit: `CampaignService.create` is L146 in the tree and L145 at `143ca1e`; `DealService.reject` is L398 in the tree and L399 at `143ca1e`; `src/lib/api.ts`'s aggregate is L6354 in the tree and L6313 at `143ca1e`; `App.tsx`'s `/:handle` is L830 in the tree and L825 at `143ca1e`. Build on the tree, or re-derive every line number. See §14.2. --> plus Phase B as specified in `../T-MEERA-CREATOR-PHASE-B/SPEC.md` (Phase E consumes Phase B's `CreatorBrief`, paste-and-secure, `MeeraDraft`, creator tool controller, `CreatorToolScopes`, and `RoutineReplyService`; where Phase E can be built on Phase A alone, the step says so). **Date:** 2026-09-04.
**Fact sheets:** `facts/email-invites-config.md` (this phase) and `../T-MEERA-CREATOR-PHASE-B/facts/{backend,ai-service,frontend}.md`. If a fact sheet and this spec disagree, the fact sheet wins and this spec has a bug to report.

This document is the contract for three engineers (backend `influora-api`, AI service `influora-ai`, frontend `src/`) plus one ops task (domain and DNS). It names every file, every step, every test, and answers the questions an expert will ask before agreeing to build it (section 11).

---

## 0. Rules that apply to every step

Rules 1 to 12 of the Phase B spec apply unchanged (compile against HEAD, "escrow" banned in copy, info barrier through `CreatorAgentPreferencesService`, numbers rendered by Java, timestamp migrations with the collation clause, feature flag, consent, id spaces, no schema combinators, `PROMPT_VERSION` bump, tests are the deliverable, git hygiene). Phase E adds:

13. **Nothing sends to a brand without a creator tap**, except level-2 auto-declines (E6), which are template text the creator edited and approved once.
14. **Personal data of third parties is minimised.** Meera may store a contact person's name, title, company and public profile URL. She stores a person's email only if the brand published it on its own site or press release. She never stores a personal mobile number, a personal Gmail-type address, or anything from behind a login. Role-based addresses (`partnerships@`, `marketing@`, `hello@`) are not personal data and are preferred.
15. **Outreach mail is isolated.** Outreach never uses `mail.influora.in` or the MSG91 relay. It uses its own domain and its own provider so a spam complaint can never touch OTP or payout mail.
16. **Inbound email is untrusted input.** Every inbound body is wrapped with `wrap_untrusted` (`app/prompt/untrusted.py:47`) before any model sees it, attachments are dropped, HTML is stripped, size is capped. The two related helpers live elsewhere and are easy to reach for by the wrong name: `wrap_untrusted_scrape` is `app/prompt/assembler.py:849` and `strip_active_content` is `app/services/analyze_site.py:78`. <!-- PRIYA (2nd pass, 2026-09-04): module paths added; none were given and all three are in different files. -->
17. **No scraping behind logins.** LinkedIn and Instagram are reached only through public web search results. No cookies, no sessions, no browser automation.

---

## 1. Scope in one table

| Id | Job | Backend | AI service | Frontend | Ops |
|----|-----|---------|------------|----------|-----|
| E1 | Outreach infrastructure: domain, provider, per-creator alias, suppression, bounces | alias entity, `OutreachMailer`, webhooks, suppression | | alias shown in settings | domain, DNS, provider account, warm-up |
| E2 | Brand discovery: web search, verification, contact ladder, on-platform check, shortlist | candidate entity, `OutreachDiscoveryService`, on-platform matcher | Perplexity client, discovery route, contact filter | shortlist card | Perplexity key |
| E3 | Pitch: draft, approve, send from alias, follow-ups, reply routing into paste-and-secure | pitch entity, send path, inbound webhook, brief creation | tools `find_outside_brands`, `draft_pitch`, `send_pitch` | pitch card, send log | inbound route |
| E4 | On-platform route when the brand is already on Influora | `CreatorBrandPitch`, brand inbox, notification event | | brand inbox page, creator status | |
| E5 | Referral attribution when a brand joins through a creator | `brand_referrals`, `ReferralTokenService`, register hook | | `?ref` on brand register, creator "brands you brought" | |
| E6 | Level-2 auto-decline | `MeeraAutoDeclineJob`, decline template on prefs | | template editor, log | |
| E7 | Manager seat | design only until approved (section 9) | | | |

Out of scope: Instagram DMs, LinkedIn messaging or automation, cold calls or WhatsApp, YouTube, the caption classifier, any money movement.

---

## 2. Ops: the outreach domain (do this first, it has a four-week lead time)

### 2.1 Choice

Register a separate second-level domain, `influora-outreach.in`, not a subdomain of `influora.in`. Reason: mailbox providers score reputation per organisational domain; a subdomain shares reputation with its parent under most heuristics, a separate domain does not. This is the whole point of E1.

### 2.2 Provider

MSG91's SMTP relay cannot do what E1 needs: it sends from one fixed address on a domain verified with MSG91, has no reply-to support in our client, and has no inbound. Use **Mailgun** for outreach only:

- Outbound: HTTP API `POST https://api.mailgun.net/v3/{domain}/messages` with per-message `from`, `h:Reply-To`, `h:List-Unsubscribe`, tags, and tracking off.
- Inbound: Mailgun Routes `match_recipient(".*@influora-outreach.in")` forwarding to our webhook.
- Events: bounces, complaints, unsubscribes delivered to a second webhook.
- Region: **US**. Latency to Indian mailbox providers is irrelevant to deliverability; pick EU only if a data-residency requirement appears, and note that EU changes **two** things, not one: the API base becomes `https://api.eu.mailgun.net` (already configurable, 2.5) **and** the inbound MX becomes `mxa.eu.mailgun.org` / `mxb.eu.mailgun.org` (2.3 lists the US hosts).

<!-- PRIYA: two route-level facts the ops task must carry.
     (1) Mailgun evaluates Routes by DESCENDING priority. A single catch-all `match_recipient(".*@influora-outreach.in")` swallows postmaster@ and abuse@ — which 2.3 requires reach a human. Create a HIGHER-priority route matching `^(postmaster|abuse)@influora-outreach\.in$` with a `forward("<ops inbox>")` action and `stop()`, ahead of the catch-all. Without it an abuse complaint lands in outreach_inbound_messages as UNMATCHED and nobody ever reads it — which is how a sending domain gets terminated.
     (2) The two webhooks do NOT share a payload format. See 4.8. -->

**Route priority.** Two routes, in this order: priority 0 = `match_recipient("^(postmaster|abuse)@influora-outreach\\.in$")` → `forward("<ops inbox>")`, `stop()`. priority 10 = `match_recipient(".*@influora-outreach.in")` → `forward("{api}/webhooks/outreach/inbound")`.

Amazon SES with an inbound rule set to S3 plus SNS is the fallback if Mailgun pricing changes. The Java client interface (`OutreachMailer`) is provider-neutral.

### 2.3 DNS records on `influora-outreach.in`

| Zone | Record | Value | Purpose |
|---|---|---|---|
| `influora-outreach.in` | `TXT @` | `v=spf1 include:mailgun.org ~all` | SPF. Mailgun documents `~all`; `-all` is stricter and also correct, but it hard-fails anything ever sent from this apex by another tool. Record the choice. |
| `influora-outreach.in` | `TXT <selector>._domainkey` — **selector name comes from the Mailgun dashboard, it is not fixed** | DKIM public key | Select **2048-bit at domain-creation time** (Mailgun's default is 1024 and cannot be changed later without re-adding the domain). A 2048-bit key exceeds one 255-char TXT string and must be published as a multi-string TXT — some registrar UIs get this wrong; verify with `dig TXT`. |
| `influora-outreach.in` | `TXT _dmarc` | `v=DMARC1; p=quarantine; rua=mailto:dmarc@influora.in; pct=100; adkim=s; aspf=s` | quarantine → `p=reject` after 30 clean days |
| **`influora.in`** | `TXT influora-outreach.in._report._dmarc` | `v=DMARC1` | **Cross-domain reporting authorisation.** Without this record on `influora.in`, a conforming reporter refuses to send DMARC reports to `dmarc@influora.in` for `influora-outreach.in` and 2.3's whole reporting loop silently produces nothing. |
| `influora-outreach.in` | `MX @` | `10 mxa.mailgun.org`, `10 mxb.mailgun.org` | inbound (US region; EU is `mxa.eu.mailgun.org` / `mxb.eu.mailgun.org`) |
| `influora-outreach.in` | `CNAME email` | `mailgun.org` | tracking domain; tracking stays off but Mailgun requires the record to verify |
| `influora-outreach.in` | `A @` + `CNAME www` | the IP / host of a box that serves a **301 to `https://influora.in/outreach`** | A record cannot redirect — DNS has no redirect. A CNAME at the apex is illegal (the apex already carries SPF/DMARC TXT and MX). Point `A @` at whatever already fronts `influora.in` and add a server-side 301. |

<!-- PRIYA: three corrections to this table.
     (1) The "TXT @ | Mailgun domain verification" row is REMOVED — it does not exist. Mailgun verifies a sending domain from the SPF TXT + DKIM TXT (+ MX for inbound, + the tracking CNAME). There is no separate verification TXT, and the original table listed TWO rows both named `TXT @`, which reads as a conflict.
     (2) The cross-domain DMARC `_report._dmarc` authorisation on influora.in was missing. RFC 7489 §7.1: rua pointing at a different organisational domain requires it.
     (3) "A/AAAA @ redirect to influora.in/outreach" is not a thing DNS can do. Rewritten as an A record plus a server-side 301. -->

Also: `postmaster@` and `abuse@` must reach a human — that is what the **priority-0 Mailgun route** in 2.2 is for. Without it the catch-all route hands them to `/webhooks/outreach/inbound`, 4.8 step 2 stores them as UNMATCHED, and nobody reads the complaint that gets the domain terminated. Plus a public page at `influora.in/outreach` describing the programme and how to opt out.

### 2.4 Warm-up

Day 1 to 7: 20 mails per day, only to brands that already have a relationship with a creator on the platform (replies expected). Then double weekly: 40, 80, 160, 320. Open to all creators at 320 per day. A `MeeraOutreachWarmupJob` (section 4.9) enforces the daily ceiling from a config value that ops raises; nothing else in the code needs to know about warm-up.

### 2.5 Secrets and config

application.yml block:

```yaml
influora:
  outreach:
    enabled: ${OUTREACH_ENABLED:false}
    domain: ${OUTREACH_DOMAIN:influora-outreach.in}
    provider: ${OUTREACH_PROVIDER:mailgun}
    mailgun-api-key: ${OUTREACH_MAILGUN_API_KEY:REPLACE_WITH_MAILGUN_API_KEY}
    mailgun-base-url: ${OUTREACH_MAILGUN_BASE_URL:https://api.mailgun.net}
    mailgun-webhook-signing-key: ${OUTREACH_MAILGUN_WEBHOOK_SIGNING_KEY:REPLACE_WITH_MAILGUN_WEBHOOK_KEY}
    daily-send-ceiling: ${OUTREACH_DAILY_SEND_CEILING:20}
    per-creator-daily-limit: ${OUTREACH_PER_CREATOR_DAILY_LIMIT:10}
    per-creator-weekly-discovery-runs: ${OUTREACH_PER_CREATOR_WEEKLY_RUNS:2}
    max-follow-ups: ${OUTREACH_MAX_FOLLOW_UPS:2}
    inbound-max-bytes: ${OUTREACH_INBOUND_MAX_BYTES:262144}
    referral-token-secret: ${OUTREACH_REFERRAL_TOKEN_SECRET:dev-referral-token-secret-change-in-production-min-32-chars}
```

<!-- PRIYA: two corrections. (1) ConversionWebhookProperties.class is InfluoraApiApplication.java:107, the LAST entry, and it carries NO trailing comma — appending requires editing L107 as well as adding L108. (2) ConfigurationPropertiesRegistrationTest needs NO extension: it classpath-scans for @ConfigurationProperties (BASE_PACKAGE "com.influora") and asserts every hit is registered, so it goes red on its own the moment OutreachProperties exists unregistered. Do not add a name to it. -->
Bind with a `@ConfigurationProperties(prefix = "influora.outreach")` class `OutreachProperties`, registered in `InfluoraApiApplication`'s `@EnableConfigurationProperties` array — append after `ConversionWebhookProperties.class` (**`InfluoraApiApplication.java:107`, the last entry, with no trailing comma today: add the comma to L107**). `ConfigurationPropertiesRegistrationTest` is a classpath scan and needs **no edit** — it fails on its own until the registration lands. Add the env keys to `influora-api/env.example` and to the `influora-api` service `environment:` in both deploy composes. The AI service gets `PERPLEXITY_API_KEY` (section 5.1).

<!-- PRIYA: `daily-send-ceiling` and the pause flag are bound properties. See 4.9/4.11 — a runtime PUT /admin/outreach/ceiling and a job-driven pause CANNOT write to a bound @ConfigurationProperties value. They need a persisted store, and neither of the two the spec gestures at exists. Resolved in 4.9. -->
**`daily-send-ceiling` and `enabled` are the *floor*, not the live value.** The runtime ceiling and the runtime pause flag live in the store defined in 4.9; these two properties are only their boot defaults.

---

## 3. Data model

<!-- PRIYA: Phase B's §2.7 explicitly says V20260910100600 is NOT created ("Delivered as a comment-only migration is not allowed by Flyway; skip this file"). Phase B's real highest is V20260910100500__meera_send_log.sql. Harmless here — V20260920* is above either — but the stated fact was wrong. Also: HEAD's highest is V20260903170000, and application.yml:51-61 sets `flyway.out-of-order: true`, so version ordering is not a hazard for this phase. -->
All migrations timestamp-versioned above Phase B's highest — which is **`V20260910100500`**, not `V20260910100600` (Phase B §2.7 skips that file deliberately). Use `V20260920...`. Every `CREATE TABLE` ends with `) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;`. `VARCHAR(26)` for every id and `VARCHAR(n)` for every hash — **never `CHAR`** (`V20260718130000__ulid_char26_to_varchar26.sql` and `V20260718150000__char_to_varchar_remaining.sql` exist solely to undo that mistake; `ddl-auto=validate` rejects a `CHAR` column against a `@Column(length=N)` String).

### 3.1 `V20260920100000__creator_outreach_aliases.sql`

```sql
CREATE TABLE creator_outreach_aliases (
    id                 VARCHAR(26)  NOT NULL,
    creator_profile_id VARCHAR(26)  NOT NULL,
    local_part         VARCHAR(64)  NOT NULL,        -- "priya.shah" or "priya.shah.k7"
    domain             VARCHAR(255) NOT NULL,        -- influora-outreach.in
    display_name       VARCHAR(100) NOT NULL,        -- From name shown to brands
    status             VARCHAR(16)  NOT NULL,        -- ACTIVE | SUSPENDED | RETIRED
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at         TIMESTAMP    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coa_local_domain (local_part, domain),
    INDEX idx_coa_creator_status (creator_profile_id, status),
    CONSTRAINT fk_coa_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

<!-- PRIYA: the prose said "remove the second UNIQUE KEY" but the SQL block still contained it — an engineer copying the block ships a key that makes a SECOND retired alias for the same creator throw DataIntegrityViolationException. Fixed in the SQL itself. -->
**`uk_coa_creator_active` is deliberately absent.** MySQL has no partial unique index, and `UNIQUE (creator_profile_id, status)` would also forbid a creator ever having two RETIRED aliases — which 4.1's rotate flow produces on the second rotation. Uniqueness of the ACTIVE alias is enforced in the application: `ensureActive` takes `SELECT ... FOR UPDATE` on the creator's profile row before it reads or inserts (4.1).

`creator_profiles.id` is `VARCHAR(26)` (`V6__creators_collaborations.sql:2`) — the FK types match.

Entity `CreatorOutreachAlias`, enum `OutreachAliasStatus {ACTIVE, SUSPENDED, RETIRED}`, repository `CreatorOutreachAliasRepository`: `Optional<CreatorOutreachAlias> findByCreatorProfileIdAndStatus(String, OutreachAliasStatus)`, `Optional<CreatorOutreachAlias> findByLocalPartAndDomain(String, String)`, `boolean existsByLocalPartAndDomain(String, String)`.

### 3.2 `V20260920100100__outreach_brand_candidates.sql`

```sql
CREATE TABLE outreach_brand_candidates (
    id                    VARCHAR(26)  NOT NULL,
    creator_profile_id    VARCHAR(26)  NOT NULL,
    discovery_run_id      VARCHAR(26)  NOT NULL,
    brand_name            VARCHAR(200) NOT NULL,
    website_domain        VARCHAR(255) NULL,          -- normalised host, no www
    website_url           VARCHAR(500) NULL,
    category              VARCHAR(100) NULL,
    brand_size            VARCHAR(16)  NULL,          -- SMALL | MID | AGENCY | LARGE
    fit_score             INT          NOT NULL,
    fit_reasons_json      TEXT         NOT NULL,      -- JSON array of strings
    evidence_json         TEXT         NOT NULL,      -- JSON array of {url, title, snippet}
    contact_route         VARCHAR(24)  NOT NULL,      -- COLLAB_PAGE | ROLE_EMAIL | AGENCY_PAGE | PERSON_PUBLIC | NONE
    contact_email         VARCHAR(255) NULL,          -- only role-based or brand-published
    contact_person_json   TEXT         NULL,          -- {name, title, public_url, source_url}; never email or phone
    contact_confidence    INT          NOT NULL,      -- 0..100
    on_platform_workspace_id VARCHAR(26) NULL,        -- set when E4 matched
    status                VARCHAR(16)  NOT NULL,      -- NEW | PITCHED | ON_PLATFORM | DISMISSED | SUPPRESSED
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_obc_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_obc_creator_run (creator_profile_id, discovery_run_id),
    INDEX idx_obc_domain (website_domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Entity `OutreachBrandCandidate`, enums `ContactRoute`, `BrandSize`, `CandidateStatus`.

### 3.3 `V20260920100200__outreach_pitches.sql`

```sql
CREATE TABLE outreach_pitches (
    id                   VARCHAR(26)  NOT NULL,
    creator_profile_id   VARCHAR(26)  NOT NULL,
    candidate_id         VARCHAR(26)  NOT NULL,
    alias_id             VARCHAR(26)  NOT NULL,
    draft_id             VARCHAR(26)  NULL,           -- meera_drafts.id (Phase B)
    channel              VARCHAR(16)  NOT NULL,       -- EMAIL | LINKEDIN_ASSISTED | INSTAGRAM_ASSISTED
    to_email             VARCHAR(255) NULL,
    subject              VARCHAR(255) NULL,
    body_text            TEXT         NOT NULL,
    sequence_no          INT          NOT NULL,       -- 1 = pitch, 2..3 = follow-ups
    status               VARCHAR(16)  NOT NULL,       -- APPROVED | SENT | DELIVERED | BOUNCED | COMPLAINED | REPLIED | FAILED | CANCELLED
    provider_message_id  VARCHAR(255) NULL,
    referral_token       VARCHAR(255) NOT NULL,       -- E5 token embedded in the CTA link
    sent_at              TIMESTAMP    NULL,
    replied_at           TIMESTAMP    NULL,
    failure_code         VARCHAR(64)  NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_op_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    CONSTRAINT fk_op_candidate FOREIGN KEY (candidate_id) REFERENCES outreach_brand_candidates(id) ON DELETE CASCADE,
    CONSTRAINT fk_op_alias FOREIGN KEY (alias_id) REFERENCES creator_outreach_aliases(id),
    UNIQUE KEY uk_op_provider_msg (provider_message_id),
    INDEX idx_op_creator_created (creator_profile_id, created_at DESC),
    INDEX idx_op_candidate_seq (candidate_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 3.4 `V20260920100300__outreach_inbound_messages.sql`

```sql
CREATE TABLE outreach_inbound_messages (
    id                   VARCHAR(26)  NOT NULL,
    alias_id             VARCHAR(26)  NULL,           -- null when the recipient alias was unknown
    pitch_id             VARCHAR(26)  NULL,           -- matched by In-Reply-To / References
    creator_profile_id   VARCHAR(26)  NULL,
    provider_message_id  VARCHAR(255) NOT NULL,
    from_email           VARCHAR(255) NOT NULL,
    from_domain          VARCHAR(255) NOT NULL,
    subject              VARCHAR(255) NULL,
    body_text            MEDIUMTEXT   NOT NULL,       -- stripped plain text, capped at inbound-max-bytes
    brief_id             VARCHAR(26)  NULL,           -- creator_briefs.id created from it (Phase B)
    status               VARCHAR(16)  NOT NULL,       -- RECEIVED | ROUTED | UNMATCHED | REJECTED
    received_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purge_after          TIMESTAMP    NOT NULL,       -- received_at + 90 days
    PRIMARY KEY (id),
    UNIQUE KEY uk_oim_provider_msg (provider_message_id),
    INDEX idx_oim_creator_received (creator_profile_id, received_at DESC),
    INDEX idx_oim_purge (purge_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

<!-- PRIYA: body_text was TEXT. MySQL TEXT holds 65,535 BYTES; `inbound-max-bytes` defaults to 262,144 (2.5). A 100 KB reply either truncates silently (non-strict mode) or throws "Data too long" (strict mode, which MySQL 8 defaults to) inside the webhook handler — a 500 back to Mailgun and an eight-hour retry storm. MEDIUMTEXT (16 MB) covers it. The entity field must be `@Column(name="body_text", nullable=false, columnDefinition="MEDIUMTEXT")` or ddl-auto=validate rejects the mismatch. -->
**`body_text` is `MEDIUMTEXT`, not `TEXT`.** `TEXT` is 65,535 **bytes**; `inbound-max-bytes` defaults to 262,144. The entity must declare `columnDefinition = "MEDIUMTEXT"` so `ddl-auto=validate` agrees. Either raise the column or lower the cap — do not ship the pair as written.

No FK constraints on `alias_id` / `pitch_id` / `creator_profile_id` / `brief_id`: all four are nullable by design (an unmatched inbound has none of them), and a hard FK would turn an unroutable message into a webhook 500.

### 3.5 `V20260920100400__outreach_suppression.sql`

```sql
CREATE TABLE outreach_suppression (
    id           VARCHAR(26)  NOT NULL,
    email_hash   VARCHAR(64)  NOT NULL,               -- SHA-256 of lowercased address
    domain       VARCHAR(255) NULL,                   -- set for domain-wide suppressions
    reason       VARCHAR(24)  NOT NULL,               -- BOUNCE | COMPLAINT | UNSUBSCRIBE | MANUAL | REPLIED_NO
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_os_email (email_hash),
    INDEX idx_os_domain (domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 3.6 `V20260920100500__creator_brand_pitches.sql` (E4, on-platform route)

```sql
CREATE TABLE creator_brand_pitches (
    id                   VARCHAR(26)  NOT NULL,
    creator_profile_id   VARCHAR(26)  NOT NULL,
    workspace_id         VARCHAR(26)  NOT NULL,
    candidate_id         VARCHAR(26)  NULL,
    message              VARCHAR(2000) NOT NULL,
    status               VARCHAR(16)  NOT NULL,       -- SENT | VIEWED | ACCEPTED | DECLINED | EXPIRED
    collaboration_id     VARCHAR(26)  NULL,           -- set when the brand accepts and a deal is created
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at           TIMESTAMP    NULL,
    open_marker          VARCHAR(26)  NULL,           -- 'OPEN' while status in (SENT, VIEWED); NULL once terminal
    PRIMARY KEY (id),
    UNIQUE KEY uk_cbp_creator_workspace_open (creator_profile_id, workspace_id, open_marker),
    CONSTRAINT fk_cbp_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    CONSTRAINT fk_cbp_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    INDEX idx_cbp_workspace_status (workspace_id, status, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

<!-- PRIYA: the original key was UNIQUE(creator_profile_id, workspace_id, status) and the justification under it was wrong. It does not only block two open SENT rows — it blocks a second row in ANY given status. The second pitch a brand ever declines collides on (creator, workspace, 'DECLINED') and throws DataIntegrityViolationException out of the accept/decline handler; VIEWED collides the same way. Replaced with a nullable open_marker, which is the standard MySQL substitute for a partial unique index: MySQL permits unlimited NULLs in a unique index, so terminal rows never collide and at most one open row can exist per (creator, workspace). -->
**The unique key is on `open_marker`, not `status`.** `UNIQUE (creator_profile_id, workspace_id, status)` — the original — blocks a second row in *any* status, so the second pitch a brand declines collides on `(creator, workspace, 'DECLINED')`. MySQL allows unlimited NULLs in a unique index, so a nullable `open_marker` set to the literal `'OPEN'` while `status ∈ {SENT, VIEWED}` and nulled on ACCEPTED / DECLINED / EXPIRED gives real partial uniqueness: at most one open pitch per (creator, workspace), unlimited history. The entity mutators (`markViewed`, `accept`, `decline`, `expire`) own the marker; document that in the javadoc and cover it with the `CreatorBrandPitchServiceTest` row in section 7.

### 3.7 `V20260920100600__brand_referrals.sql` (E5)

```sql
CREATE TABLE brand_referrals (
    id                    VARCHAR(26)  NOT NULL,
    workspace_id          VARCHAR(26)  NOT NULL,
    creator_profile_id    VARCHAR(26)  NOT NULL,
    source                VARCHAR(16)  NOT NULL,      -- PITCH_EMAIL | SECURE_LINK | ON_PLATFORM_PITCH
    source_id             VARCHAR(26)  NULL,          -- pitch id or secure link id
    redeemed_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fee_pct_override      DECIMAL(5,2) NULL,          -- null until Swapnil rules; nothing reads it in Phase E
    first_invite_until    DATE         NULL,          -- null until Swapnil rules
    PRIMARY KEY (id),
    UNIQUE KEY uk_br_workspace (workspace_id),        -- one referrer per brand, first wins
    CONSTRAINT fk_br_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_br_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles(id) ON DELETE CASCADE,
    INDEX idx_br_creator (creator_profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 3.8 `V20260920100700__workspaces_website_domain.sql` (E4 matcher)

```sql
ALTER TABLE workspaces ADD COLUMN website_domain VARCHAR(255) NULL;
CREATE INDEX idx_workspaces_website_domain ON workspaces (website_domain);
```

<!-- PRIYA: the hand-wave is resolved, and resolving it exposes a design defect.
     (1) There is NO `Workspace.setWebsiteUrl` and no builder-with-website. The ONE write site is `Workspace.applyCompanyDetails(name, slug, type, industry, companySize, websiteUrl, description, logoUrl)` (Workspace.java:262-279), called from exactly three places: OnboardingService.java:62, WorkspaceService.java:112, AdminBillingServiceTest.java:253. `Workspace.newBrand(id, name, slug, industry, companySize)` (L117) never sets it, and AuthService.brandRegister L185-186 uses newBrand — so a brand that never opens workspace settings has workspaces.website_url NULL forever. `applyAdminProfileEdit` explicitly does not touch it (javadoc L290).
     (2) THE REAL PROBLEM: every `setWebsiteUrl` in this repo is on BrandProfile, not Workspace — BrandProfile.java:87, written by AnalyzeSiteTriggerService.java:115 and :230. That is the site URL a brand actually supplies, on the onboarding analyze-site path. Matching on workspaces.website_domain alone therefore misses the majority of brands, and E4's whole on-platform route degrades to near-zero hit rate. Resolved below by backfilling from BOTH columns.
     (3) `system_flags` DOES NOT EXIST — zero hits across every .sql and .java in the repo. And Redis, while on the classpath (spring-boot-starter-data-redis), is used ONLY through @Cacheable (RedisCacheConfig); there is no RedisTemplate bean in src/main. So neither escape hatch in the original sentence is real. -->

Entity `Workspace`: add `@Column(name = "website_domain", length = 255) private String websiteDomain;` with a getter. **There is no `Workspace.setWebsiteUrl`** — the single write site for `websiteUrl` is `Workspace.applyCompanyDetails(...)` (`Workspace.java:262-279`), reached from `OnboardingService.java:62` and `WorkspaceService.java:112` only; set `websiteDomain` there. `Workspace.newBrand(...)` (L117) has no website parameter and `AuthService.brandRegister` (L185-186) uses it, so a brand that never opens workspace settings has `website_url` NULL.

**Source both columns.** The URL a brand actually supplies lands on **`brand_profiles.website_url`** (`BrandProfile.java:87`, written by `AnalyzeSiteTriggerService.java:115` and `:230` on the onboarding analyse-site path), not on `workspaces.website_url`. Matching only on the workspace column would miss most brands and silently reduce E4 to a near-zero hit rate. So: also set `websiteDomain` in `AnalyzeSiteTriggerService` at both write sites, and have the backfill read `COALESCE(w.website_url, bp.website_url)`.

`WorkspaceRepository`: add `List<Workspace> findByWebsiteDomain(String websiteDomain);`.

**Backfill.** `system_flags` **does not exist** anywhere in this repo, and Redis is on the classpath only behind `@Cacheable` (`RedisCacheConfig`) — there is no `RedisTemplate` bean in `src/main`. So there is no "flip the flag off" store to write to. `WorkspaceDomainBackfillJob` is therefore **idempotent and daily** (recompute is cheap: it only touches rows where `website_domain IS NULL AND COALESCE(...) IS NOT NULL`), guarded by `@SchedulerLock` like every other job in 4.9. No boot-time run, no flag.

### 3.9 `V20260920100800__creator_agent_preferences_phase_e.sql`

```sql
ALTER TABLE creator_agent_preferences
    ADD COLUMN outreach_enabled TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN decline_template TEXT NULL,
    ADD COLUMN decline_template_approved_at TIMESTAMP NULL;
```

Entity fields `outreachEnabled`, `declineTemplate`, `declineTemplateApprovedAt` with explicit `@Column(name=...)`; mutators `applyOutreach(boolean enabled)` and `applyDeclineTemplate(String text, Instant approvedAt)`. `applyPreferences(...)` keeps its 16 parameters (Phase B §2.1); both new values go through their own mutators.

<!-- PRIYA: "list and update every construction site … as Priya's Phase B review did" is not an instruction, it is a deferral. Both records are POSITIONAL Java records; every `new X(...)` breaks at compile time. The counts are known from the Phase B review and are restated here so nobody discovers them at build time. Also: max 600 collides with DealDtos.RejectRequest's @Size(max = 500) — see 4.10. -->
`PreferencesResponse` and `UpdatePreferencesRequest` (`CreatorAgentDtos`) gain `outreach_enabled` (Boolean) and `decline_template` (String, **max 500** — see 4.10; 600 would exceed `RejectRequest`'s `@Size(max = 500)`). Both are positional records:

- `PreferencesResponse`: 18 components at HEAD → **23 after Phase B** → **25 here**. **4** construction sites, enumerated in Phase B §3.10.
- `UpdatePreferencesRequest`: 16 at HEAD → **18 after Phase B** → **20 here**. **9** construction sites, all in tests, enumerated in Phase B §3.10.

Every one must be updated in the same commit or the module does not compile.

<!-- PRIYA: the creator-context addition needs all FOUR drift-test assertions handled, not just the `distinctive` dict. tests/prompt/test_creator_context_drift.py parses the real MeeraContextDtos.java and pytest.fails (never skips) if it is missing, so the Java record and the Python tuple must land in ONE commit. Phase B §2.10 documents the four; Phase E inherits every one. -->
`CreatorContextResponse` gains `outreach_enabled` as component **33** (27 at HEAD → 32 after Phase B → 33). Update the one positional construction site, `MeeraContextService.assembleCreatorContext` (32 args → 33). Python side, **all four** drift-test assertions apply (Phase B §2.10):

1. add `outreach_enabled` to `CREATOR_CONTEXT_PAYLOAD_FIELDS` **in sorted position** (the tuple is asserted equal to its own `sorted(set(...))`);
2. render it in `build_block_b_creator` written literally as **`ctx.get("outreach_enabled")`** — the test greps `assembler.py` for `ctx(?:\.get\(|, )['"]<name>['"]`, so a read against the parameter name `context` fails — **or** add it to `CREATOR_CONTEXT_FIELDS_NOT_RENDERED` and skip the render;
3. give it a `distinctive` value in the drift test's local dict, plus a `_PREREQUISITES` entry only if the render is conditional;
4. add it to `_FORBIDDEN_BRAND_FIELDS` in `assembler.py` (a creator preference must never reach a brand's Block B).

### 3.10 Domain normaliser

New `com.influora.common.WebDomains`:

```java
public static Optional<String> normalise(String urlOrHost)   // lowercases, strips scheme, port, path, leading "www."; rejects IPs, localhost, empty; returns registrable host as typed (no public-suffix list; "shop.glow.in" stays "shop.glow.in")
public static Optional<String> emailDomain(String email)      // part after the last "@", normalised
public static boolean isPersonalMailbox(String domain)        // gmail.com, googlemail.com, yahoo.*, outlook.com, hotmail.*, live.*, icloud.com, protonmail.com, proton.me, rediffmail.com, yandex.*, zoho personal
```

Unit-tested in `WebDomainsTest`.

---

## 4. Backend

### 4.1 Alias allocation: `OutreachAliasService`

```java
@Transactional public CreatorOutreachAlias ensureActive(String creatorProfileId)
@Transactional(readOnly = true) public Optional<CreatorOutreachAlias> resolve(String localPart)
@Transactional public void retire(String creatorProfileId)
```

`ensureActive`:
1. Lock the profile row (`creatorProfileRepository.findById` inside the transaction with `@Lock(PESSIMISTIC_WRITE)` on a new repository method `findByIdForUpdate`, mirroring `CollaborationRepository.findByIdForUpdate` L30).
2. Return the ACTIVE alias if one exists.
3. Otherwise compute `local_part`: `UsernameUtils`-style slug of `profile.getUsername()` if set, else of `profile.getDisplayName()`; lowercase, `[a-z0-9.]`, collapse repeats, trim dots, max 40 chars; reserved words rejected (`postmaster, abuse, admin, noreply, no-reply, support, info, hello, security, billing, dmarc`). On collision append `.` plus a 2-char base36 suffix, retry up to 10 times.
4. `display_name` = `profile.getDisplayName()` sanitised with `TextSanitizer.sanitizePlainText`, max 100.
5. Save ACTIVE, then audit.

<!-- PRIYA: step 5 was drafting scratch — two rejected options argued out loud inside a build contract. Rewritten as one instruction. Verified: AuditLogService has exactly four public methods (recordToolCall L43, recordAuthRejection L70, recordAdminAction L94, recordMoneyEvent L109); there is no recordSystemEvent or anything like it, and ACTOR_SYSTEM is used at exactly one site today (L122, inside recordMoneyEvent). There is no shared private write helper — each method inlines its own repository.save(AuditLogEntry.builder()...). Crucially, audit_log.workspace_id has NO foreign key (V15__audit_log.sql:11 — the "FK workspaces(id)" text is a comment, the only DDL is a plain INDEX) and is nullable, so passing a creator's users.id as the workspace slot inserts cleanly. -->
**Add one method to `AuditLogService`**, and route every Phase E audit row through it:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordSystemEvent(
        String workspaceId, String eventType, String outcome, Map<String, Object> detail) {
    repository.save(
            AuditLogEntry.builder()
                    .id(Ulids.newUlid())
                    .workspaceId(workspaceId)
                    .actorType(ACTOR_SYSTEM)
                    .eventType(eventType)
                    .outcome(outcome)
                    .detailJson(JsonLists.toJsonObject(detail))
                    .build());
}
```

Copy the body shape from `recordMoneyEvent` (`AuditLogService.java:109-130`) — there is no shared private writer to reuse. `audit_log.workspace_id` carries **no FK** (`V15__audit_log.sql:11` is a comment plus a plain `INDEX`) and is nullable, so passing the creator's `users.id` in that slot is safe. `detail` still must never carry PII — counts only.

Here: `auditLogService.recordSystemEvent(creatorUserId, "OUTREACH_ALIAS_ALLOCATED", AuditLogService.OUTCOME_ALLOWED, Map.of("local_part_len", n))`.

<!-- PRIYA: CreatorProfileRepository has NO @Lock method and no LockModeType import — findByIdForUpdate is genuinely new there. The pattern to copy is right: CollaborationRepository.java:28-30, `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query("SELECT c FROM Collaboration c WHERE c.id = :id")` + `Optional<T> findByIdForUpdate(@Param("id") String id)`. Note the concurrency test in §7 cannot be a Mockito unit test — a row lock needs a real transaction, so it belongs in the Testcontainers boot test that is skipped without Docker. -->
Step 1's `CreatorProfileRepository.findByIdForUpdate` is genuinely new — that repository has no `@Lock` method and no `LockModeType` import today. Copy `CollaborationRepository.java:28-30` verbatim, retyped for `CreatorProfile`.

The alias never changes when the creator renames; `retire` plus `ensureActive` creates a new one only when the creator asks (settings action). Retired aliases keep receiving mail for 90 days and route to the same creator.

### 4.2 `OutreachMailer` (provider-neutral) and `MailgunOutreachClient`

```java
public interface OutreachMailer {
    record OutboundMail(String fromLocalPart, String fromDisplayName, String replyToLocalPart, String toEmail, String subject, String textBody, String htmlBody, String listUnsubscribeUrl, String messageIdHint, Map<String,String> tags) {}
    record SendResult(boolean accepted, String providerMessageId, String failureCode) {}
    SendResult send(OutboundMail mail);
    boolean isConfigured();
}
```

<!-- PRIYA: RestClient is available and is the right call — Boot 3.3.5 / Spring 6.1 (pom.xml:10), and three production classes already use it (MetaGraphApiClient:19, MetaOAuthService:14, ShopifyOAuthService:16). httpclient5 5.3.1 is pinned deliberately (pom.xml:30-40) so RestClient prefers Apache over the JDK client. Two things the spec left out: RestClient.create() has NO timeouts — 5s/15s needs an explicit ClientHttpRequestFactory — and RestClient.builder() should be lazy (built on first use, not in the constructor) like MetaGraphApiClient:47-70 does, for the boot-time reason its comment gives. -->
`MailgunOutreachClient implements OutreachMailer` (`com.influora.integration.mailgun`): `RestClient` (available — Boot 3.3.5 / Spring 6.1, already used by `MetaGraphApiClient`, `MetaOAuthService`, `ShopifyOAuthService`) with basic auth `api:{key}`, `POST {baseUrl}/v3/{domain}/messages` as `multipart/form-data` with fields `from = "{display} <{local}@{domain}>"`, `to`, `subject`, `text`, `html`, `h:Reply-To = "{local}@{domain}"`, `h:List-Unsubscribe = "<{url}>"`, `h:List-Unsubscribe-Post = "List-Unsubscribe=One-Click"`, `o:tracking = no`, `o:tag`. Timeouts 5 s connect, 15 s read — **`RestClient.create()` sets none**; pass an explicit `ClientHttpRequestFactory` (`HttpComponentsClientHttpRequestFactory` with `setConnectTimeout`/`setReadTimeout`) to `RestClient.builder().requestFactory(...)`. Build the client **lazily on first use**, not in the constructor, for the same boot-time reason `MetaGraphApiClient:47-70` documents. Never throws; returns `SendResult(false, null, "provider_error")`. Dev without a key: branch on `InfluoraEnvironment.isDev()` exactly as `Msg91EmailClient:131-148` does — log `[MOCK] Outreach email would be sent: to={}, subject={}` and return `accepted=true, providerMessageId="mock-" + ulid`; **outside dev with no key, log an error and return `accepted=false`**, never a false success (that is the precise shape `Msg91EmailClient:142-147` uses).

Never wire `OutreachMailer` into `EmailWorker`, `NotificationService`, or the outbox. Outreach has its own send path (4.5) and its own job.

### 4.3 Discovery: `OutreachDiscoveryService` (E2)

```java
@Transactional public DiscoveryRunResponse run(String creatorUserId, DiscoveryRequest req)      // req: {niche_override?, limit<=5}
@Transactional(readOnly = true) public List<CandidateItem> listCandidates(String creatorUserId, String runId)
@Transactional public void dismiss(String creatorUserId, String candidateId)
```

`run`:
1. Profile, prefs, consent, `prefs.outreachEnabled()` else 403 `OUTREACH_NOT_ENABLED`, represented → 403 `REPRESENTED_WARN_ONLY`, feature flags (`MeeraCreatorFeatureProperties` and `OutreachProperties.enabled`).
<!-- §15 supersedes: the count is over `outreach_discovery_runs` (§15.3, migration V20260920101000), not the candidates table; step 3 also takes `seed_brands`, `local` and the demand-lookalike names (§15.4a-b, §15.1a); step 5 computes `warmth` and upserts `outreach_brand_facts` (§15.1a, §15.3); outside the cold-email cohort the run refuses `COLD_EMAIL_NOT_OPEN` (§15.5a). -->
2. Weekly run limit: count runs in the last 7 days as `COUNT(DISTINCT discovery_run_id)` over `outreach_brand_candidates` for this creator; over `per-creator-weekly-discovery-runs` → 429 `OUTREACH_RUN_LIMIT`.
   <!-- PRIYA (2nd pass, 2026-09-04): **this counter is derived from the wrong table and undercounts.** A run that returns zero surviving candidates — every candidate dropped by the DPDP gate (4.4), or Perplexity returning nothing — writes NO `outreach_brand_candidates` row, so its `discovery_run_id` never appears and the run is invisible to this count. Python has already billed it (§5.2 step 1 reserves and step 7 settles regardless of yield), so a creator can burn their monthly AI cap on unlimited zero-yield runs while this gate reports zero runs used. Fix: count the runs themselves, not their output — either add an `outreach_discovery_runs` row written at step 1 before the AI call (preferred; it also gives the admin panel a real run log and gives §6.2's Shortlist tab an honest empty state for a run that found nothing), or persist a zero-candidate sentinel row. A `COUNT(DISTINCT ...)` over an output table can never be a rate limit on an input. -->
3. Build the fit profile: categories, city, tier, languages, floors as a formatted range (never raw floors on the wire to Python: send `min_budget_hint` = the reel floor rendered as a number rounded to the nearest 500, labelled as such), media kit URL.
4. Call the AI service: `MeeraOutreachAiClient.discover(creatorUserId, profileJson)` → `POST {ai.baseUrl}/internal/outreach/discover`.

<!-- PRIYA: "mirror MeeraVoiceAiClient for token minting" resolves to a specific bean, and that resolution is a scope decision Kabir owns, not an implementation detail. Verified:
   - MeeraVoiceAiClient injects BrandSafetyServiceTokenService and calls tokenService.mint(workspaceId) (MeeraVoiceAiClient.java:203, :317) -> claim `workspace_id` + `scope="service"`.
   - CreatorSuggestionAiClient injects CreatorSuggestionServiceTokenService -> claim `creator_profile_id` + `scope="creator"`, and its javadoc (L26-27) plus service_token.py:44-50 state the segregation is DELIBERATE and BIDIRECTIONAL (Kabir gate F-5, IDOR threat-1): "a creator has no workspace".
   - Phase B §7.5 (Priya correction 13) already ruled that a creator-audience internal route uses verify_creator_token + SCOPE_CREATOR, and that a service token can never satisfy it.
   Phase E §5.2 picks the OTHER side (SCOPE_SERVICE + body_workspace_id = the creator's users.id). That is functional — verify_token only equality-checks the claim against the body, and users.id and workspaces.id are disjoint ULID spaces — but it puts a users.id into a claim named workspace_id on a service-scoped token, which is exactly the shape CreatorSuggestionServiceTokenService was written to avoid. Kabir will bounce it if it is not an explicit, recorded decision.
   The tie-breaker is the spend key: chat.py:528 and :804 pass `workspace_id` (the creator's users.id on a CREATOR turn) into check_creator_spend_gate/record_creator_spend. If outreach bills under creator_profile_id instead, the monthly cap splits into two buckets and is effectively doubled. So whichever token is chosen, the SPEND key stays the creator's users.id. -->
**Token type is a decision, not a detail — record it.** Two precedents exist and they are deliberately incompatible:

| | minter | claim | scope | Python verifier |
|---|---|---|---|---|
| `MeeraVoiceAiClient` / `BrandSafetyAiClient` | `BrandSafetyServiceTokenService.mint(workspaceId)` | `workspace_id` | `service` | `verify_token(..., body_workspace_id=)` |
| `CreatorSuggestionAiClient` | `CreatorSuggestionServiceTokenService.mint(creatorProfileId)` | `creator_profile_id` | `creator` | `verify_creator_token(..., body_creator_profile_id=)` |

`service_token.py:44-50` and `CreatorSuggestionServiceTokenService`'s javadoc both state the segregation is deliberate and bidirectional (Kabir gate F-5) — "a creator has no workspace". Phase B §7.5 already ruled the creator side for `/internal/brief-extract`. §5.2 of this spec picks the **service** side and puts the creator's `users.id` into the `workspace_id` claim. That works (the two id spaces are disjoint ULIDs and `verify_token` only equality-checks claim against body), but it contradicts the recorded doctrine. **Pick one before day 3 and get Kabir's sign-off in writing:**

- **(a) service scope, as §5.2 currently says** — inject `BrandSafetyServiceTokenService`, `mint(creatorUserId)`, body carries `workspace_id = creatorUserId`. Cheapest; needs Kabir to bless a `users.id` in a `workspace_id` claim.
- **(b) creator scope, consistent with Phase B** — inject `CreatorSuggestionServiceTokenService`, `mint(profile.getId())`, body carries `creator_profile_id`, `ENDPOINT_SCOPES["outreach_discover"] = (SCOPE_CREATOR,)`, and §5.2 uses `verify_creator_token` offloaded through `anyio.to_thread.run_sync`.

**Either way the spend key stays the creator's `users.id`**, because `chat.py:528` and `:804` bill `check_creator_spend_gate` / `record_creator_spend` under that id on CREATOR turns. Billing outreach under `creator_profile_id` would split the monthly cap into two buckets and effectively double it.

Transport: mirror `MeeraVoiceAiClient`'s never-throw discipline and its `@Component @Lazy` + lazily-built client shape. 60 s read timeout is **not enough** as §5.2 is currently written — see the correction there.
5. For each returned candidate: apply the Java DPDP gate (4.4), run the on-platform matcher (4.6), persist `OutreachBrandCandidate`, status NEW or ON_PLATFORM.
6. Return the shortlist with `on_platform` flags. Bill nothing on the Java side; the AI side records spend against the creator's cap (section 5).

### 4.4 The contact ladder and the DPDP gate (Java is the final gate; Python filters first)

Accept a `contact_email` only if all of the following hold: syntactically valid; domain equals the candidate's `website_domain` or a subdomain of it; local part is role-based (`partnerships, partnership, collab, collabs, collaboration, collaborations, creators, creator, influencer, influencers, marketing, brand, brands, pr, press, hello, hi, team, contact, info, business, growth`) OR the evidence for it is the brand's own site (`evidence.url` domain equals `website_domain`). Reject if `WebDomains.isPersonalMailbox(domain)`. Reject any string that looks like a phone number anywhere in `contact_person_json` (`\+?\d[\d\s().-]{8,}\d`). Reject `contact_person_json.email` outright (drop the key). Everything rejected is logged as a count in `recordSystemEvent`, never as a value.

Contact route priority: `COLLAB_PAGE` (brand's own collaboration page with an address or form) > `ROLE_EMAIL` > `AGENCY_PAGE` > `PERSON_PUBLIC` (name, title, public URL only, channel becomes assisted) > `NONE` (candidate shown with "no safe contact found", creator can add one).

### 4.5 Pitch: `OutreachPitchService` (E3)

```java
@Transactional public PitchDraftResponse draft(String creatorUserId, String candidateId, String draftText, String subject)    // stores a MeeraDraft(kind PITCH) — add PITCH to Phase B's DraftKind enum
@Transactional public PitchSendResponse approveAndSend(String creatorUserId, String draftId, String editedText, String editedSubject, String idempotencyKey)
@Transactional public PitchSendResponse queueFollowUp(String creatorUserId, String pitchId)     // sequence 2 or 3, only if no reply after 4 and 10 days
@Transactional public void cancel(String creatorUserId, String pitchId)                          // only while status APPROVED and not yet sent
@Transactional(readOnly = true) public List<PitchItem> list(String creatorUserId, int limit)
```

<!-- §15 supersedes in part: seven more refusals — `BRAND_RECENTLY_PITCHED` (30-day platform-wide domain cooldown, §15.1b), `HOOK_REQUIRED` / `HOOK_NOT_IN_BODY` (§15.2a), `PITCH_NOT_SPECIFIC` (§15.2b), `SUBJECT_MENTIONS_PLATFORM` (§15.2c), `LARGE_BRAND_NO_COLD` (§15.4c), `CREATOR_OUTREACH_PAUSED` (§15.2d); body length is 200-900 chars, not 200-1,500; the CTA copy is §15.5b's. -->
`approveAndSend` checks, each a refusal code: outreach enabled; represented; alias ACTIVE; candidate not DISMISSED, SUPPRESSED or ON_PLATFORM (on-platform goes through E4); contact route is `COLLAB_PAGE` or `ROLE_EMAIL` (assisted channels never send); `to_email` not in `outreach_suppression` (hash) and its domain not domain-suppressed; per-creator daily limit; global daily ceiling (`MeeraOutreachWarmupJob` counter, 4.9); `max-follow-ups`; text contains no floor value and no banned word; text length 200 to 1,500 chars; subject 10 to 120 chars. Then: mint the referral token (4.7), render the CTA `"{web-base-url}/join/brand?ref={token}"` and the media kit link into the body, create `OutreachPitch` APPROVED, and hand it to `OutreachSendJob` (4.9) which sends within 60 seconds. Idempotent on `idempotencyKey` through `IdempotencyService` (same wrapper `DealService` uses for `counter`).

The body always ends with a two-line signature: creator display name, "via Influora", and the opt-out sentence with the List-Unsubscribe URL. No tracking pixels, no link tracking.

### 4.6 On-platform matcher (E4)

`OnPlatformBrandMatcher.match(String contactEmail, String websiteDomain) -> Optional<String workspaceId>`:

<!-- PRIYA: step 1 will essentially never fire. 4.4's contact ladder only ever produces a ROLE-BASED address (partnerships@, hello@, collab@ ...) or a brand-published one — by construction, not by accident. `users.email` is a person's login address. A role alias is almost never a User row's login email, so findByEmailIgnoreCase returns empty on virtually every candidate. Step 1 stays as a cheap exact-match shortcut, but the matcher's real hit rate rests entirely on step 2/3, which is why the brand_profiles.website_url backfill in 3.8 is load-bearing rather than cosmetic. -->
1. `contactEmail` non-null → `userRepository.findByEmailIgnoreCase(email)` (the only email finder on that repository); if the user is `UserType.BRAND` and active, resolve their workspace via `workspaceMemberRepository` (owner membership) → match. **Expect this branch to hit almost never** — 4.4 only ever yields a role address, and a role alias is rarely a user's login email. Steps 2 and 3 do the actual work, which is why 3.8's `brand_profiles.website_url` backfill is load-bearing.
2. `websiteDomain` → `workspaceRepository.findByWebsiteDomain(domain)`; exactly one non-suspended brand workspace → match; more than one → no match (log a count).
3. `contactEmail` domain → same lookup as 2.
Only the workspace id leaves this class. The creator-facing response carries `on_platform: true` and the workspace's public name and logo, never the matched email.

When matched, `OutreachDiscoveryService` marks the candidate ON_PLATFORM and `CreatorBrandPitchService.send(creatorUserId, workspaceId, message, candidateId)` is what the creator's tap calls instead of an email:

- Entity `CreatorBrandPitch`, status SENT.
<!-- PRIYA: package and file facts the spec omitted, all verified.
  - NotificationEvent lives in `com.influora.service.notification.event`, NOT `service/notification/`. It is a SEALED interface with 34 permits (NotificationEvent.java:8-49), so a new record MUST be in that exact package or the permits clause will not compile. It also declares four accessors — eventType(), userId(), workspaceId(), entityId() — all of which the record must satisfy.
  - The last permits entry, CreatorNotConnectedEvent (L49), has no trailing comma: appending requires editing L49.
  - EmailTemplateRegistry is `final class` (package-private, L25) with a `private static final Map<String,Spec> SPECS` (L57) populated only inside its static block, and `Spec` is a PRIVATE record. Nothing outside com.influora.integration.msg91 can register a template — the file itself must be edited. The 5-arg convenience constructor is Spec(subject, heading, bodyTemplate, ctaLabel, ctaUrlVar) (L50-52); the spec named only three of the five.
  - The handler shape to copy is the plain one at NotificationListener.java:178-193 (@Async + @TransactionalEventListener(AFTER_COMMIT) + notificationService.notify), not the CreatorConnectionRequestedEvent handler at :677-752, which is a hand-rolled Msg91 retry loop used only because an admin recipient has no users row to hang the EmailOutbox FK off. A brand owner IS a real user, so the normal path applies.
  - There is no NotificationEventPermitsTest in the tree; §7 lists it as if it exists. It has to be written. -->

- Publish `CreatorBrandPitchReceivedEvent(String userId /* brand owner */, String workspaceId, String entityId /* pitch id */, String creatorDisplayName, String creatorUsername, String message)`, `eventType() -> "brand.creator_pitch_received"`. It must live in **`com.influora.service.notification.event`** — `NotificationEvent` is a `sealed interface` there (`NotificationEvent.java:8-49`) and a permitted record outside that package will not compile. Add it to the `permits` list (the current last entry, `CreatorNotConnectedEvent` at L49, has no trailing comma — add one) and implement all four accessors.
- Handler in `NotificationListener`: copy the plain shape at **`NotificationListener.java:178-193`** (`@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)` + `notificationService.notify(...)`), **not** the `CreatorConnectionRequestedEvent` handler at `:677-752` — that one hand-rolls an Msg91 retry loop only because an admin recipient has no `users` row for `EmailOutbox`'s NOT NULL FK. A brand owner is a real user, so the normal path applies. Resolve the recipient with the existing `emailOf(...)` helper (**`NotificationListener.java:145-148`**). <!-- PRIYA (2nd pass, 2026-09-04): :159-173 is `resolveJoinNotificationEmail`, a different helper that falls back to the workspace owner and then any active admin — logic specific to `ConnectedCreatorJoinedEvent`, wrong for a brand-owner recipient who always has a `users` row. `emailOf` itself is L145-148. -->
- Template: `EmailTemplateRegistry` is **package-private** (`EmailTemplateRegistry.java:25`), `SPECS` is `private static final` (L57), and `Spec` is a **private record** — nothing outside `com.influora.integration.msg91` can register a key, so edit the static block directly. The 5-arg form is `Spec(subject, heading, bodyTemplate, ctaLabel, ctaUrlVar)` (L50-52) — supply all five: subject `"{{creator_name}} would like to work with {{workspace_name}}"`, a heading, a body template, CTA label `"View pitch"`, ctaUrlVar `"pitch_url"`.
- Brand routes on a new `BrandPitchController` `@RequestMapping("/brand/pitches")` (brand principal): `GET` list, `GET /{id}` (marks VIEWED), `POST /{id}/accept`, `POST /{id}/decline` → DECLINED, creator notified in-app.

<!-- PRIYA: the accept path was the single most hand-waved paragraph in the spec — a graphify TODO, a rejected alternative, and a wrong method all in one sentence. Resolved by reading the code. Four separate defects:

 (1) "workspace member of any role" is wrong for accept. CampaignService.create (CampaignService.java:146-152) calls brandContext.requireBrandWorkspace(principal) then requireRole(member, OWNER, ADMIN, MANAGER). A MEMBER or VIEWER gets 403 from inside create(), after the pitch row has been read. Gate the route at the same three roles so the refusal is honest; GET/list may stay open to any member.

 (2) CampaignService.create's signature is create(AuthPrincipal, CampaignWriteRequest) -> CampaignResponse. There is no scalar-budget overload. CampaignWriteRequest (CampaignDtos.java:60-94) requires @NotBlank @Size(min=5,max=300) title, @Valid @NotNull BudgetDto budget, @NotNull TimelineDto timeline, and create() throws END_BRAND_NAME_REQUIRED / END_BRAND_CATEGORY_REQUIRED on blanks. A title and an endBrandName alone will not construct it.

 (3) CampaignIntentType.DIRECT does exist (domain/enums/CampaignIntentType.java:5) — but IntegrationHealthService.requiresStoreIntegration(type) returns true for DIRECT and ONLY DIRECT, and CampaignService.java:176 gates on it. A DIRECT campaign on a workspace with no connected store will be REFUSED at create time. Use STANDARD.

 (4) "CollaborationReviveService.reviveOrRefuse" is the wrong tool: it exists because UNIQUE(campaign_id, creator_id) blocks re-inviting on an EXISTING campaign. This flow creates a brand-new campaign, so there is no prior row and nothing to revive. The plain invite entry point is resolved below. -->

**`POST /{id}/accept`, resolved against source.** Gate the route at `OWNER, ADMIN, MANAGER` — `CampaignService.create` (`CampaignService.java:146-152`) enforces exactly those three via `brandContext.requireRole`, so "any role" would 403 from inside `create()` after the pitch row was already read. Then, in one transaction:

1. `campaignService.create(principal, new CampaignWriteRequest(...))` — the signature is `create(AuthPrincipal, CampaignWriteRequest)` returning `CampaignResponse` (`CampaignService.java:146`); there is **no scalar-budget overload**. `CampaignWriteRequest` (`CampaignDtos.java:60-94`) requires a `@NotBlank @Size(min = 5, max = 300)` title, a `@Valid @NotNull BudgetDto budget` and a `@NotNull TimelineDto timeline`, and `create()` throws `END_BRAND_NAME_REQUIRED` / `END_BRAND_CATEGORY_REQUIRED` on blanks (L160-168). So the accept body must carry a budget band and a timeline — either collect them on the accept form, or default budget to the workspace's own band and timeline to today + 30 days and say so in the UI. Title `"{creator display name} × {workspace name}"` is 5-300 chars only if both names are non-trivial; truncate defensively.
2. `campaignType` is **`STANDARD`, not `DIRECT`**. `CampaignIntentType.DIRECT` exists (`CampaignIntentType.java:5`) but `IntegrationHealthService.requiresStoreIntegration` returns true for DIRECT alone, and `CampaignService.java:176` gates on it — a DIRECT campaign on a workspace with no connected store is refused at create time.
3. **The plain brand-to-creator invite entry point is `CreatorDiscoveryService.invite(AuthPrincipal principal, String creatorProfileId, String campaignId, String message)`** — `CreatorDiscoveryService.java:453`, `public @Transactional`, returns `CreatorDtos.InviteResponse`. It is the caller of `Collaboration.invite(String id, String campaignId, String creatorUserId, String message, String currency)` (`Collaboration.java:110`, which has exactly two production callers: `CreatorDiscoveryService.java:500` and `ConfirmLaunchExecutor.java:472`). Call the **service**, not the factory: it also runs `recordInviteOnTimeline` (`CreatorDiscoveryService.java:551`, the F-0291 fix) which seeds the deal room with a visible invitation event — construct `Collaboration.invite(...)` directly and the creator opens an empty thread. `CollaborationReviveService.reviveOrRefuse` is **not** applicable: it exists to work around `UNIQUE(campaign_id, creator_id)` on a pre-existing campaign, and this flow creates a fresh one.
   - **Caveat:** `CreatorDiscoveryService.invite` calls `requireDiscoverableProfile(creatorProfileId)`, so a creator with `is_discoverable = false` cannot be invited even though they sent the pitch. Either flip the pitch to a refusal with a clear code, or extract a discoverability-free variant. Decide, do not discover this in QA.
4. Set `collaboration_id`, status ACCEPTED, `open_marker = NULL`.
5. `brand_referrals` — see the contradiction resolved in 3.7 below; **do not write a referral row on this path.**
- Creator routes on `CreatorOutreachController` (4.8): `GET /creator/outreach/pitches/platform` to list their on-platform pitches and status.

### 4.7 Referral attribution (E5)

`ReferralTokenService` copies `InviteTokenService` exactly (HMAC-SHA256, base64url, 30-day TTL, fail-closed outside dev/test when the secret is default) with payload `creatorProfileId:sourceType:sourceId:expiresAtMillis` and secret `${OUTREACH_REFERRAL_TOKEN_SECRET}`; `issue(String creatorProfileId, String sourceType, String sourceId)` and `verify(String token) -> Optional<Parsed>`.

<!-- PRIYA: three corrections.
  (1) `new BrandRegisterRequest(` has TWO construction sites, not one — AuthServiceTest.java:85 (the static REQUEST field) and AuthServiceTest.java:322 (the brandRequestWithPhone helper). Both break on a 10th component.
  (2) The record has 9 components today (BrandRegisterRequest.java:28-37), so the addition makes it 10.
  (3) L201 is the WALLET save, not the workspace save. Order in AuthService.brandRegister: userRepository.saveAndFlush L198, workspaceRepository.save L199, WorkspaceMember.owner L200, Wallet.forWorkspace L201, all inside one try (L188-216); UserCreatedEvent is published at L222 and issueTokens at L224. brand_referrals FKs workspaces(id), so the hook must run after L199 — put it after L201, before L222, inside the same transaction. -->
Brand registration: `BrandRegisterRequest` (**9 components today**, `BrandRegisterRequest.java:28-37`) gains a trailing `@Size(max = 512) String referralToken` (nullable) → 10. It has **two** construction sites, both in `AuthServiceTest.java` — **L85** (the static `REQUEST` field) and **L322** (`brandRequestWithPhone`). Update both.

`AuthService.brandRegister` calls `referralService.consume(req.referralToken(), workspaceId)` **after L201** — the save order inside the try block is `userRepository.saveAndFlush` L198, `workspaceRepository.save` **L199**, `WorkspaceMember.owner` L200, `Wallet.forWorkspace` L201 — and **before** the `UserCreatedEvent` publish at L222, in the same transaction. (L201 is the wallet, not the workspace; `brand_referrals` FKs `workspaces(id)` so L199 is the real constraint.) `consume` which never throws: verifies, checks `brand_referrals` has no row for the workspace, checks the creator profile exists and is not suspended, inserts `BrandReferral`, publishes `BrandReferredEvent(creatorUserId, workspaceId, referralId, brandName)` (`eventType "creator.brand_you_brought_joined"`, in-app plus email template "A brand you pitched just joined Influora") — add to `permits`, listener, registry. The secure-link redemption in Phase B (`POST /secure-links/{token}/redeem`) also records a referral with source `SECURE_LINK` when the workspace was created in the last 7 days and has no referral yet.

`fee_pct_override` and `first_invite_until` stay null and unread until Swapnil rules. `GET /creator/outreach/referrals` lists the creator's referred brands with join date and status.

<!-- PRIYA: 3.7's `source` enum lists ON_PLATFORM_PITCH, but 4.6 says a referral is recorded "only if the workspace was created after the pitch". E4 matches a workspace that ALREADY EXISTS — that is the definition of the on-platform route — so the two rules together make ON_PLATFORM_PITCH unwritable. Resolved: drop it. Only PITCH_EMAIL and SECURE_LINK can ever be written in Phase E. -->
**`source` is `PITCH_EMAIL | SECURE_LINK` only.** 3.7's third value `ON_PLATFORM_PITCH` is unreachable and must be removed from the enum and the column comment: E4 matches a workspace that already exists, and 4.6's own rule ("only if the workspace was created after the pitch") therefore never fires on that path. Keeping a dead enum value invites a later writer to populate it and silently credit a pre-existing brand as a referral.

<!-- PRIYA: brand-register.tsx facts. It does NOT import useSearchParams today (only useNavigate, L2) — verified, and it is not in the 15-file useSearchParams list. It is a TWO-STEP wizard with an optional EmailOtpGate branch (handleRegister L146-170 sends an OTP and defers submitRegistration), so the token must survive a step change AND the OTP round-trip: hold it in a ref or in the same state the wizard already carries, not in a local. The payload is built at L118-127 via api.auth.brandRegister({...}); its TS payload type has no referralToken, so either widen that type in api.ts or use creator-register.tsx's deliberate untyped-`payload`-const escape (creator-register.tsx L100-115, whose comment documents exactly why the const is untyped). Pick the typed widening — the hack was scoped to a task that could not touch api.ts, and this one can. -->
**Frontend.** `brand-register.tsx` does **not** import `useSearchParams` today (L2 imports only `useNavigate`) — add it. It is a **two-step wizard** with an optional `EmailOtpGate` branch (`handleRegister` L146-170 sends an OTP and defers `submitRegistration`), so the token must survive both a step change and the OTP round-trip. The payload is assembled at **L118-127** and passed to `api.auth.brandRegister({...})`, whose TS payload type has no `referralToken`: **widen that type in `api.ts`**. Do not copy `creator-register.tsx`'s untyped-`payload`-const workaround (L100-115) — its own comment says it exists only because that task could not edit `api.ts`, and this one can.

`brand-login.tsx` likewise does not import `useSearchParams` (L2 imports `useNavigate, Link`); the register link is `<Link to="/brand/register">Create one</Link>` at **L153-161** — rebuild the `to` as `/brand/register${ref ? `?ref=${encodeURIComponent(ref)}` : ''}`. A public landing `/join/brand?ref=` route renders the brand registration page with a banner "Invited by {creator}" resolved from `GET /public/referrals/{token}/preview` (permitAll GET, returns creator display name and avatar only, rate bucket `public-referral-preview` IP-keyed 30).

### 4.8 Inbound: `OutreachInboundController` and routing (E3)

`@RestController @RequestMapping("/webhooks/outreach")`, permitAll POST entries in `SecurityConfig` placed with the other webhooks (before the role gates), rate bucket `tracking`-like `outreach-inbound` IP-keyed 120 per window:

<!-- PRIYA: the two webhooks do NOT share a payload format, and the spec says "same signature check" for both.
  - Routes / inbound forward: `multipart/form-data` (or urlencoded), with `timestamp`, `token`, `signature` as TOP-LEVEL form fields alongside recipient/sender/body-plain. 406 is the documented "do not retry" response for a Route.
  - Event webhooks (delivered/failed/complained/unsubscribed): `application/json`, body `{ "signature": {"timestamp": ..., "token": ..., "signature": ...}, "event-data": {...} }`. The signing inputs are the same (HMAC-SHA256 over timestamp+token with the HTTP webhook signing key) but they are NESTED, and a @RequestParam-based handler will read nothing. Written up separately in the events bullet below.
  Also: 406 is the wrong response to a FORGED call on either endpoint. A genuine Mailgun delivery never fails the signature check, so returning 406 on a signature failure only trains you to lose real retries when a key is mid-rotation. Return 401 and audit; keep 406 for messages the inbound route deliberately refuses (unknown alias, oversize). -->

- `POST /webhooks/outreach/inbound` — the Mailgun **Route** forward, `multipart/form-data`, with `timestamp`, `token` and `signature` as **top-level form fields**. Verify first: `HMAC-SHA256(webhookSigningKey, timestamp + token)` equals `signature` (constant-time compare — reuse `InviteTokenService`'s `MessageDigest.isEqual` shape), timestamp within 5 minutes, `token` unseen (bounded in-memory set of the last 10,000, plus `uk_oim_provider_msg` as the durable arbiter). **A failed signature is 401 and a `recordSystemEvent` row, not 406** — a genuine Mailgun call never fails it, so 406 here only discards real retries during a key rotation. Reserve **406** (the documented Mailgun "do not retry" code for Routes) for messages the route deliberately refuses: unknown alias, oversize body. Then:
  1. Read `recipient`, `sender`, `From`, `subject`, `body-plain` (fall back to `stripped-text`), `Message-Id`, `In-Reply-To`, `References`. Ignore attachments entirely. Cap body at `inbound-max-bytes`. **Apply `TextSanitizer.sanitizePlainText` only to `body-html`, never to `body-plain`** — it is a regex HTML stripper (`TextSanitizer.java:15-31`, `HTML_TAG = <[^>]*>`), so on plain text it silently eats `<partnerships@brand.com>` and any `a < b`. That is the whole of the class's public surface: `sanitizePlainText` and `sanitizeHashtags`, nothing else.

<!-- PRIYA: two things about this endpoint that are not in the spec and matter.
  (1) application.yml:63-67 sets spring.servlet.multipart.max-file-size 500MB / max-request-size 1GB (Kabir H-1, sized for deliverable uploads). Spring parses and spools the ENTIRE multipart body — every attachment — BEFORE the handler runs, which means before the signature check. An unauthenticated permitAll endpoint that buffers up to 1GB per request is a trivial disk/memory DoS. `inbound-max-bytes` caps what we STORE, not what we ACCEPT.
  (2) The fix is not a global multipart change (that would break deliverable uploads). Either put a size limit in front of this path (a servlet filter or the reverse proxy) or, better, use Mailgun's `store()` route action plus a notify URL so Mailgun holds the MIME and we fetch only the stripped fields. Kabir owns the call. -->

  **Body-size DoS.** `application.yml:63-67` sets `spring.servlet.multipart.max-request-size: 1GB` (sized for deliverable uploads, Kabir H-1). Spring parses and spools the whole multipart body — attachments included — **before** the handler runs, therefore before the signature check. `inbound-max-bytes` caps what is *stored*, not what is *accepted*. Cap this path in the reverse proxy or a dedicated filter, or switch the Mailgun route to `store()` + notify so Mailgun holds the MIME and we fetch only the stripped fields. Do not raise or lower the global multipart limits — that would break deliverable uploads. Kabir signs this off.
  2. Resolve the alias: local part of `recipient` → `OutreachAliasService.resolve` (ACTIVE or RETIRED within 90 days). Unknown → store UNMATCHED with no creator and return 200.
  3. Match the pitch by `In-Reply-To` or `References` against `outreach_pitches.provider_message_id` (Mailgun message ids look like `<id@influora-outreach.in>`); else by `from_domain` equals the candidate domain of the creator's most recent pitch.
  4. Auto-replies and bounces (`Auto-Submitted`, `X-Autoreply`, subject starts with `Automatic reply`, `Out of office`, `Undeliverable`, `Delivery Status Notification`) → store REJECTED, do not route.
  5. Negative replies: if the body's first 300 chars match `\b(not interested|no thanks|unsubscribe|remove me|do not contact|stop emailing)\b` → suppress the sender (`REPLIED_NO`), mark the pitch REPLIED, notify the creator "Brand said no", do not create a brief.
  6. Otherwise: mark the pitch REPLIED, create the brief (see below), set `outreach_inbound_messages.brief_id`, status ROUTED, and publish `OutreachReplyReceivedEvent(creatorUserId, null, inboundId, brandName)` (`eventType "creator.outreach_reply"`, in-app plus an email template "{brand} replied to your pitch", CTA to the brief). Same package and permits rules as 4.6.

<!-- PRIYA: step 6 as written does not work. Four separate problems with `CreatorBriefService.paste(creatorUserId, rawText)` on this path:
  (a) ID SPACE. Step 2 resolves an alias, which carries creator_profile_id. `paste` takes a users.id (Phase B §3.8). Nothing in 4.8 converts one to the other — resolve it via creatorProfileRepository (the profile row's getUserId()), which the webhook has already loaded.
  (b) SOURCE. Phase B's factory is `CreatorBrief.paste(String id, String creatorProfileId, String rawText)` and it hard-sets source PASTED. There is no parameter for INBOUND_EMAIL, so adding the enum value alone changes nothing. A second factory is needed.
  (c) CONSENT. `paste`'s step 1 is "Profile, prefs, consent" — it THROWS on a creator who has not accepted (403 CONSENT_REQUIRED). Thrown inside a webhook handler that becomes a 500 to Mailgun, and Mailgun retries a 5xx for eight hours. The reply is also silently lost.
  (d) LATENCY. `paste` makes a synchronous MeeraBriefAiClient call plus risk + quote evaluation. That is a multi-second AI round trip inside a webhook that must ack fast, and if the AI service is down the whole reply is lost behind a retry storm.
  Resolved: persist first, analyse later. -->

  **Do not call `CreatorBriefService.paste` from the webhook.** Four reasons, all verified: it takes a `users.id` while step 2 yields a `creator_profile_id`; `CreatorBrief.paste(id, creatorProfileId, rawText)` hard-sets source `PASTED` with no parameter for a new value; its first step is a consent check that **throws** `CONSENT_REQUIRED` (a 500 to Mailgun and an eight-hour retry storm, reply lost); and it makes a synchronous AI extraction call inside a handler that must acknowledge fast.

  Instead:
  - Add `static CreatorBrief fromInboundEmail(String id, String creatorProfileId, String rawText)` beside Phase B's `paste` factory, setting `source = INBOUND_EMAIL` (add the value to `BriefSource`; the column is `VARCHAR(16)` and `INBOUND_EMAIL` is 13 chars, so no migration) and status `NEW`.
  - Resolve `creator_profile_id → users.id` through the profile row the webhook already loaded (`CreatorProfile.getUserId()`), and store it on the inbound row.
  - Persist the brief NEW and return 200. A **new `@Transactional` method on `CreatorBriefService`** — `analyseExisting(String briefId)` — runs steps 3-6 of Phase B's `paste` flow, invoked from `OutreachSendJob`'s tick or its own short job. **Skip it entirely when consent is absent**, and surface the raw reply to the creator anyway; a brand's reply must never be discarded because Meera is switched off.

<!-- PRIYA: the events webhook does NOT share the inbound payload format, so "same signature check" is wrong. Mailgun's event webhooks POST application/json with the signing triple NESTED under a `signature` object next to `event-data`; a @RequestParam handler binds nothing and every event silently drops. -->
- `POST /webhooks/outreach/events` — **`application/json`, not multipart.** Body shape is `{"signature": {"timestamp": ..., "token": ..., "signature": ...}, "event-data": {...}}`: the signing inputs are the same (`HMAC-SHA256(signingKey, timestamp + token)`) but **nested**, so bind a DTO, not `@RequestParam`. The event name is `event-data.event` and the message id is `event-data.message.headers["message-id"]`.

  <!-- PRIYA (2nd pass, 2026-09-04): **the events webhook has no durable replay defence, unlike inbound.** §4.8's inbound path pairs its bounded in-memory token set with `uk_oim_provider_msg` on `outreach_inbound_messages` as "the durable arbiter" — correct. The events path names no equivalent. A bounded in-memory set is **per-replica**: with two app instances behind the load balancer, Mailgun's own at-least-once retry delivers the same `complained` event to both, each accepts it, and suppression/counters double-count — which is exactly the input `MeeraOutreachHealthJob` thresholds the kill switch on (complaint rate > 0.1 percent). Restarts have the same effect. Give the events table the same treatment: a `UNIQUE (provider_event_id)` — or `UNIQUE (provider_message_id, event_name, timestamp)` where Mailgun supplies no event id — and treat the duplicate-key violation as the accept/reject decision. Note this must be an INSERT that catches the constraint violation at flush, not a read-then-write inside a `try` around `save()`: on a managed entity the violation fires at commit, outside the catch (see the `save() vs saveAndFlush() in a catch` precedent in this repo). --> Map `failed` with `severity == "permanent"` → pitch BOUNCED plus suppression `BOUNCE` (a `temporary` failure is a retry, not a bounce — do not suppress on it); `complained` → COMPLAINED plus suppression `COMPLAINT` plus `recordSystemEvent` plus an ops alert when complaints in 24 h exceed 0.1 percent of sends (`MeeraOutreachHealthJob` computes it); `unsubscribed` → suppression `UNSUBSCRIBE`; `delivered` → DELIVERED. Signature failure → 401 and an audit row.
- `GET /outreach/unsubscribe?token=` (permitAll GET): token = HMAC of the pitch id, adds suppression and renders a plain page. `POST` to the same path for one-click unsubscribe.

Retention: `OutreachInboundPurgeJob` deletes `outreach_inbound_messages` past `purge_after` daily; the brief created from it keeps its own copy under the creator's DPDP export and delete.

### 4.9 Jobs (all `@Component`, `@Scheduled` + `@SchedulerLock`, config-gated, following `CreatorConnectNudgeJob`)

<!-- PRIYA: the runtime-state store is left as an either/or in three places (3.8, 4.9's health row, 4.11's PUT /ceiling) and NEITHER option exists.
  - `system_flags`: zero hits across every .sql and every .java in the repo. It does not exist.
  - Redis: spring-boot-starter-data-redis IS on the classpath (pom.xml:60-63), but it is used ONLY through Spring's @Cacheable abstraction (config/RedisCacheConfig.java — two named caches, adminPulse and discoveryFacets). There is NO RedisTemplate or StringRedisTemplate bean anywhere in src/main. Using Redis as a key/value store here means introducing that wiring, which is new surface, and a cache-backed flag has the wrong durability semantics for a kill switch anyway.
  A bound @ConfigurationProperties value (2.5) cannot be written at runtime, so PUT /ceiling and the health job's pause have nowhere to go as specified. Resolved with one tiny table — the same shape admin_email_send_lock already uses (V20260903130000, a two-column singleton row). -->

**Runtime state store (resolved).** `system_flags` **does not exist** in this repo, and Redis is present only behind `@Cacheable` (`RedisCacheConfig`) with no `RedisTemplate` bean in `src/main` — so neither escape hatch in the original text is real, and a bound `@ConfigurationProperties` value cannot be written at runtime. Add one migration, `V20260920100900__outreach_runtime_state.sql`, modelled on the existing singleton-row precedent `V20260903130000__admin_email_send_lock.sql`:

```sql
CREATE TABLE outreach_runtime_state (
    id                  VARCHAR(26)  NOT NULL,       -- always 'SINGLETON'
    paused              TINYINT(1)   NOT NULL DEFAULT 0,
    pause_reason        VARCHAR(64)  NULL,           -- COMPLAINT_RATE | BOUNCE_RATE | MANUAL
    daily_send_ceiling  INT          NOT NULL,
    sent_today          INT          NOT NULL DEFAULT 0,
    counter_date        DATE         NOT NULL,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO outreach_runtime_state (id, paused, daily_send_ceiling, sent_today, counter_date)
VALUES ('SINGLETON', 0, 20, 0, CURRENT_DATE);
```

`influora.outreach.enabled` and `daily-send-ceiling` (2.5) are the **boot defaults**; this row is the live value. `OutreachSendJob` reads it `FOR UPDATE` when it increments `sent_today`, which also makes the global ceiling correct under multiple replicas — an in-memory counter would not be. That makes it **ten** migrations for this phase, not nine; update section 7 and section 10 question 10 accordingly.

| Job | Schedule | Lock | Does |
|---|---|---|---|
| `OutreachSendJob` | `fixedDelay = 15000` | `PT2M` / `PT10S` | sends `outreach_pitches` in APPROVED created more than 60 s ago, respecting the daily ceiling and per-creator limit; sets SENT with `provider_message_id`, or FAILED with `failure_code` |
| `MeeraOutreachWarmupJob` | daily 00:05 IST | `PT5M` | resets the daily counter; the ceiling itself is config, raised by ops per the warm-up table |
| `OutreachFollowUpJob` | hourly | `PT10M` | creates follow-up drafts (sequence 2 at 4 days, 3 at 10 days after a SENT pitch with no reply) as `MeeraDraft` PENDING for the creator to approve; never sends by itself |
<!-- §15 extends this row: the job also runs the per-creator reputation pass and suspends an alias (§15.2d), writes the 30-day reply rate onto `outreach_runtime_state` (§15.1c), and computes the admin scorecard and kill-rule countdown (§15.5c-d). -->
| `MeeraOutreachHealthJob` | every 30 min | `PT5M` | computes bounce rate, complaint rate, reply rate over 24 h and 7 d; pauses sending by setting `outreach_runtime_state.paused` (the singleton row defined immediately above — `system_flags` and a Redis key are both fiction in this repo, §13.1 correction 24) when complaint rate > 0.1 percent or bounce rate > 5 percent; writes the numbers the admin panel reads |
| `OutreachInboundPurgeJob` | daily 02:00 IST | `PT10M` | purges inbound rows past `purge_after` |
| `MeeraAutoDeclineJob` (E6) | every 15 min | `PT5M` | see 4.10 |
| `WorkspaceDomainBackfillJob` | daily 03:00 IST | `PT10M` | fills `website_domain` where null |

### 4.10 Level-2 auto-decline (E6)

`MeeraAutoDeclineJob.run()`: for every creator at `approvalLevel == 2` with `declineTemplateApprovedAt != null` and not represented, load collaborations with status INVITED created more than 60 minutes ago (grace so the creator can act first); for each, load the campaign; decline when `campaign.endBrandCategory` is in `excluded_categories` OR the workspace name matches `blocked_brands` (case-insensitive, trimmed); skip when the creator has already spoken to this workspace, or when the campaign's workspace has a `brand_referrals` row for this creator. Tag metadata `{"agent":"meera","intent":"AUTO_DECLINE"}`, write a `MeeraSendLog` row (Phase B) with intent `AUTO_DECLINE`, notify the creator in-app ("Meera declined {brand} for you: excluded category").

<!-- PRIYA: four defects in this paragraph, three of them blocking.

 (1) "for every CreatorAgentPreferences with approvalLevel == 2" has no way to be written. CreatorAgentPreferencesService exposes prefs only by users.id (getOrCreatePreferences L77, isConsentAccepted L95) plus Phase B's getByProfileId — there is NO level-2 finder. A job written literally would inject CreatorAgentPreferencesRepository, which is precisely what rule 0.3's info barrier forbids and what Phase B widened InfoBarrierTest's scan roots to service/** and job/** to catch. The job lives in com.influora.job, inside the widened scan, so this WILL fail InfoBarrierTest. Add the finder to the SERVICE.

 (2) `rejectAsCreatorAgent` as "a package-visible sibling" repeats verbatim the error Phase B's review already corrected (Phase B §3.9, correction 35): a package-visible method that self-invokes `reject` runs OUTSIDE the Spring proxy, so the @Transactional boundary the atomicity claim rests on silently does not exist. Phase B's sendMessageAsCreatorAgent is public AND @Transactional for exactly this reason. Same rule here.

 (3) "any DealMessage from this creator to this workspace" is not answerable with any existing query. DealMessage has NO workspace_id and NO creator_id — only collaboration_id, sender_id, sender_type (deal_messages, V33). DealMessageRepository's six finders are all collaboration-scoped. The workspace is three hops away: deal_messages.collaboration_id -> collaborations.campaign_id -> campaigns.workspace_id, the same join CollaborationRepository.findByWorkspaceId:96-99 performs (its javadoc: "collaborations carry no workspace_id of their own"). A new @Query is required.

 (4) DealDtos.RejectRequest is `record RejectRequest(@Size(max = 500) String reason)` — max 500, not the 600 that 3.9 gives decline_template. -->

**(1) Add the finder to the service, not the repository.** `CreatorAgentPreferencesService` exposes prefs only by `users.id` (`getOrCreatePreferences` L77, `isConsentAccepted` L95) plus Phase B's `getByProfileId` — there is no level-2 finder. A job that injects `CreatorAgentPreferencesRepository` breaks rule 0.3 and **will** fail `InfoBarrierTest`, whose scan roots Phase B widens to `service/**` and `job/**` — and `com.influora.job` is inside that scan. Add:

```java
@Transactional(readOnly = true)
public List<String> findAutoDeclineCandidateProfileIds(int limit)   // approvalLevel == 2, declineTemplateApprovedAt != null, !represented
```

Every Phase E class that reads a floor or a preference must sit under `com.influora.service.outreach` or `com.influora.job` and go through `CreatorAgentPreferencesService`: `OutreachDiscoveryService` (`outreachEnabled`), `OutreachPitchService` (the floor check), `MeeraAutoDeclineJob`, `CreatorAgentPreferencesService` itself.

**(2) `rejectAsCreatorAgent` is `public` and `@Transactional`** — not package-visible. This is the identical correction Phase B's review applied to `sendMessageAsCreatorAgent` (Phase B §3.9): a package-visible method self-invoking `reject` runs outside the Spring proxy, so the transaction the atomicity claim depends on silently is not opened. Signature: `public @Transactional void rejectAsCreatorAgent(String creatorUserId, String collaborationId, String templateText)`, building `new AuthPrincipal(userId, email, UserType.CREATOR, null)` and calling `reject(principal, collaborationId, new RejectRequest(templateText), idempotencyKey)` — note `DealService.reject` takes **four** args (`DealService.java:398-400`).

**(3) The prior-contact check needs a new query.** `DealMessage` has **no `workspace_id` and no `creator_id`** — only `collaboration_id`, `sender_id`, `sender_type` (`V33__deal_messages.sql`), and **five of the six** `DealMessageRepository` finders are collaboration-scoped — the sixth, `findFirstMessageTimestampsBySender()` (`DealMessageRepository.java:52-57`), is a **global, parameterless `GROUP BY`** over every message in the table, so it is not a narrower version of what this check needs, it is a different query entirely and must not be reused. <!-- PRIYA (2nd pass, 2026-09-04): §4.10 and §13.1 correction 4 both said "all six ... collaboration-scoped"; five are. --> The workspace is three hops away (`deal_messages → collaborations.campaign_id → campaigns.workspace_id`), the same join `CollaborationRepository.findByWorkspaceId` (L96-99) performs. Add to `DealMessageRepository`:

```java
@Query("SELECT COUNT(m) > 0 FROM DealMessage m, Collaboration c, Campaign ca "
     + "WHERE m.collaborationId = c.id AND c.campaignId = ca.id "
     + "AND ca.workspaceId = :workspaceId AND c.creatorId = :creatorUserId "
     + "AND m.senderType = com.influora.domain.enums.DealSenderType.creator")
boolean existsCreatorMessageToWorkspace(@Param("creatorUserId") String creatorUserId,
                                        @Param("workspaceId") String workspaceId);
```

**(4) The decline template is capped at 500, not 600** — `DealDtos.RejectRequest` is `record RejectRequest(@Size(max = 500) String reason)` (`DealDtos.java:137`). 3.9 is corrected to match. Content validation (no price, no floor, never "below my rate") on save in `CreatorAgentPreferencesService.updatePreferences`, refusal code `DECLINE_TEMPLATE_INVALID`.

### 4.11 Controllers

`CreatorOutreachController` `@RequestMapping("/creator/outreach")`, feature flags (`MeeraCreatorFeatureProperties` and `OutreachProperties.enabled` → 404 `FEATURE_DISABLED`), consent, creator principal:

| Route | Body | Response |
|---|---|---|
| `GET /creator/outreach/alias` | | `AliasResponse{address, display_name, status, created_at}`; creates on first call |
| `POST /creator/outreach/alias/rotate` | | new alias, old RETIRED |
| `POST /creator/outreach/discover` | `DiscoveryRequest{niche_override?, limit}` | `DiscoveryRunResponse{run_id, candidates[], searches_used, credits_charged}` |
| `GET /creator/outreach/candidates?run_id=` | | `CandidateItem[]` |
| `POST /creator/outreach/candidates/{id}/dismiss` | | 204 |
| `POST /creator/outreach/candidates/{id}/contact` | `{contact_email}` creator-supplied, same DPDP gate | updated candidate |
| `POST /creator/outreach/pitches/draft` | `{candidate_id, subject, text}` | `PitchDraftResponse{draft_id, preview_from, preview_subject, preview_text}` |
| `POST /creator/outreach/pitches/{draftId}/send` | `{subject, text}` + `Idempotency-Key` | `PitchSendResponse{pitch_id, status, send_at, refused_code?}` |
| `POST /creator/outreach/pitches/{id}/cancel` | | 204 |
| `GET /creator/outreach/pitches?limit=50` | | `PitchItem[]` with status, delivered, replied, brief_id |
| `POST /creator/outreach/pitches/{id}/follow-up` | | queues a follow-up draft |
| `GET /creator/outreach/pitches/platform` | | on-platform pitches (E4) |
| `GET /creator/outreach/referrals` | | `ReferralItem[]` |

<!-- PRIYA: two corrections.
 (1) `GET /creator/outreach/alias` "creates on first call" is a GET that WRITES. It cannot be @Transactional(readOnly = true), it is not idempotent from a caching proxy's point of view, and every browser prefetch allocates. Make allocation an explicit POST and let GET return 404 ALIAS_NOT_ALLOCATED — or, if the "creator never types anything" promise in §11 must hold, keep the GET but have the settings page and the first discovery call the POST. Do not ship a writing GET.
 (2) The bucket cost is not uniform. AuthRateLimitFilter's edit count is 3 mandatory (a @Value field in the L121-225 block, a branch in bucketFor, a case in limitFor) plus a Pattern constant when the path is regex-shaped and a case in isUserKeyedBucket when user-keyed. GET buckets ARE supported but live in a SEPARATE if-chain (L300-317) that `return null`s for anything unlisted — a GET bucket added only to the POST section is dead code that silently never fires. -->

**Alias allocation is a POST.** `GET /creator/outreach/alias` returns the alias or 404 `ALIAS_NOT_ALLOCATED`; `POST /creator/outreach/alias` allocates. A GET that writes cannot be `@Transactional(readOnly = true)` and allocates on every browser prefetch. The "creator never types anything" promise in §11 still holds — the settings page and the first discovery run call the POST.

Rate buckets: `creator-outreach-discover` (`^/creator/outreach/discover$`, POST, user-keyed, 4 per window), `creator-outreach-send` (`^/creator/outreach/pitches/[^/]+/send$`, POST, user-keyed, 20), `outreach-inbound` (`/webhooks/outreach/inbound|events`, POST, IP-keyed, 120), `public-referral-preview` (**GET**, IP-keyed, 30). Each needs **3 mandatory edits** to `AuthRateLimitFilter` — a `@Value` field in the L121-225 block, a branch in `bucketFor`, a `case` in `limitFor` — plus a `Pattern` constant for regex-shaped paths and a `case` in `isUserKeyedBucket` (L449-466) when user-keyed. **A GET bucket must go in the GET if-chain at L300-317**, which `return null`s for anything unlisted; adding it to the POST section below L319 makes it dead code that never fires (follow `PUBLIC_CREATOR_VERIFIED` at L313, the existing GET-only precedent).

`SecurityConfig` additions, all placed **before** the `/admin/**` and `/creator/**` role gates (L207/L227), following the existing single-segment `*` style: `POST /webhooks/outreach/inbound`, `POST /webhooks/outreach/events`, `GET /outreach/unsubscribe`, `POST /outreach/unsubscribe`, `GET /public/referrals/*/preview` (note `*`, not `{token}` — `requestMatchers` takes Ant patterns). `/creator/outreach/**` and `/admin/outreach/**` need no entry: they inherit the existing role gates.

Admin: `AdminOutreachController` `@RequestMapping("/admin/outreach")`: `GET /health` (sent, delivered, bounced, complained, replied over 24 h and 7 d, current ceiling, paused flag, warm-up day), `POST /pause`, `POST /resume`, `PUT /ceiling {daily_send_ceiling}`, `GET /suppression?limit=`, `POST /suppression {email|domain, reason MANUAL}`. **Returns bare DTOs, not `ApiResponse` envelopes.** <!-- PRIYA (2nd pass, 2026-09-04): this sentence had the convention exactly backwards. Counted across all 19 `Admin*Controller` classes in `com.influora.web`: **16 contain zero occurrences of `ApiResponse<`**. Only `AdminEmailController`, `AdminModerationController` and `AdminSupportController` use it at all, one method each. `AdminCreatorAgentController` — the nearest sibling and the one the original sentence held up as the exception — returns bare DTOs like the other fifteen. The majority convention IS bare DTOs; an `ApiResponse` envelope here would make the admin frontend unwrap this one namespace differently from every other. -->

### 4.12 Meera tools (extend Phase B's `CreatorToolName` and `CreatorMeeraToolController`)

| Tool | Tier | Route | Executor | Notes |
|---|---|---|---|---|
| `find_outside_brands` | R | `POST /internal/meera/creator/find_outside_brands` | `FindOutsideBrandsExecutor` | calls `OutreachDiscoveryService.run`; returns the shortlist with `on_platform` flags and contact routes; refusals as `status="REFUSED"` with codes `OUTREACH_NOT_ENABLED`, `OUTREACH_RUN_LIMIT`, `REPRESENTED_WARN_ONLY` |
| `draft_pitch` | D | `.../draft_pitch` | `DraftPitchExecutor` | persists a `MeeraDraft` kind PITCH for one candidate; input `candidate_id, subject, text`; refuses on-platform candidates with `USE_PLATFORM_PITCH` and returns the platform pitch draft instead |
| `send_pitch` | C | `.../send_pitch` | `SendPitchExecutor` | only from an approve tap; the tool exists so the chat card's "Send" goes through the same executor path as the REST route; scope only in `CreatorToolScopes` level 1 and above, and never in the represented scope |

`CreatorToolScopes`: `SCOPE_LEVEL_0` gains `find_outside_brands draft_pitch`; `SCOPE_LEVEL_1` gains `send_pitch`; `SCOPE_REPRESENTED` gains nothing.

---

## 5. AI service

### 5.1 Perplexity client: `app/clients/perplexity.py`

<!-- PRIYA: three defects.
 (1) `Settings` is @dataclass(frozen=True) (config.py:213-214) and EVERY env-backed field uses field(default_factory=lambda: os.getenv(...)) on purpose: a plain `= os.getenv(...)` is evaluated ONCE at import, so monkeypatch.setenv + get_settings.cache_clear() (the standard test idiom here, get_settings is @lru_cache(maxsize=1) at :562) silently will not see the override. Use default_factory for perplexity_model too.
 (2) `perplexity_base_url = "https://api.perplexity.ai"` has NO type annotation, so it is a plain class attribute, not a dataclass field, and cannot be overridden by env at all. Annotate it — and it must be env-overridable anyway, because 2.2's EU option changes the Mailgun base URL and the same discipline should apply here.
 (3) THE BLOCKING ONE: /readyz computes `ready = all(keys_loaded.values()) and redis_ok and cap_scope.safe` (main.py:213 — corrected 2nd pass; the quote was truncated and the line was off by one, though the conclusion is unchanged: `keys_loaded` is `all()`-ed). Adding "perplexity" to that dict makes any deployment without a Perplexity key return 503 NOT READY for the WHOLE service — which directly contradicts this paragraph's own "not a boot secret". Keep it out of keys_loaded. -->

Settings, all three as `field(default_factory=...)` — `Settings` is `@dataclass(frozen=True)` (`config.py:213`) and every env-backed field uses `default_factory` deliberately: a plain `= os.getenv(...)` is evaluated once at import, so `monkeypatch.setenv` + `get_settings.cache_clear()` (the house test idiom; `get_settings` is `@lru_cache(maxsize=1)`) never sees the override.

```python
perplexity_api_key: str = field(default_factory=lambda: os.getenv("PERPLEXITY_API_KEY", ""))
perplexity_model: str = field(default_factory=lambda: os.getenv("PERPLEXITY_MODEL", "sonar"))
perplexity_base_url: str = field(default_factory=lambda: os.getenv("PERPLEXITY_BASE_URL", "https://api.perplexity.ai"))
```

(`perplexity_base_url = "..."` without an annotation is a plain class attribute, not a dataclass field, and cannot be env-overridden at all.) Timeouts `perplexity_connect 3.0`, `perplexity_read 25.0` on `ProviderTimeouts` (`config.py:168-194`, a frozen dataclass with all-default fields — appending is safe).

**Not a boot secret, and therefore NOT in `/readyz` `keys_loaded`.** `main.py:213` computes `ready = all(keys_loaded.values()) and redis_ok and cap_scope.safe`, so adding a `"perplexity"` entry makes every deployment without a Perplexity key return **503 not_ready for the entire service** — the exact opposite of "optional". Leave `require_boot_secrets()` and `keys_loaded` untouched; expose the flag as a separate non-blocking `optional_keys` field in the `/readyz` body if it needs to be visible. Do add `PERPLEXITY_API_KEY` to `influora-ai/env.example` and the `influora-ai` compose env.

```python
class PerplexityClient:
    def __init__(self, settings: Settings)                       # httpx.AsyncClient(base_url=..., timeout=httpx.Timeout(connect=..., read=..., write=..., pool=...)), Authorization: Bearer
    async def search(self, query: str, *, system: str, max_tokens: int = 700) -> PerplexityResult   # POST /chat/completions, model settings.perplexity_model, messages [{system},{user: query}], return content text + citations list + usage
    async def aclose(self) -> None
@dataclass(frozen=True) class PerplexityResult: ok: bool; text: str; citations: list[str]; usage: dict[str, int]; error: str | None
```

The endpoint is a fixed trusted host, so it uses a plain `httpx.AsyncClient` like `SpringInternalClient`, not `guarded_fetch`. Never raises; returns `ok=False`. Circuit breaker: reuse the pattern in `app/providers/claude.py` (`failure_threshold=5`, `recovery_seconds=30.0`).

<!-- PRIYA: precision the billing path needs.
  - estimate_cost_usd raises ValueError for a model with no PRICING_TABLE row, and it raises BEFORE the `if not usage: return Decimal(0)` check (pricing.py:265-268 calls _resolve_rate first). So estimate_cost_usd("sonar", None) raises — the row is mandatory, not optional.
  - The expensive-rate fallback at pricing.py:222-240 applies ONLY to three env-overridable constants (TREND_TAG_MODEL, BRAND_SAFETY_MODEL, CREATOR_COPILOT_MODEL). "sonar" gets no such grace.
  - ModelRate has no per-request field, so the flat fee cannot live in the table; it must be a module constant consumed by a bespoke estimator, exactly like SARVAM_FLAT_COST_PER_CALL (:133) + estimate_sarvam_flat_cost_usd() (:153).
  - Do NOT copy a rate from this document. Perplexity prices sonar per-MTok AND per-request, and the per-request tier varies by search-context size. Pull the live figures from Perplexity's pricing page on the day of the build and put the verification date in the comment, matching the "verified 2026-07-11/12" convention already on the Anthropic rows (:104). -->

Pricing, `app/costs/pricing.py`:
- Add a `"sonar"` row to `PRICING_TABLE` via `_per_mtok(...)`. **Mandatory, not optional:** `estimate_cost_usd` raises `ValueError` for an unpriced model and raises it **before** the `if not usage` short-circuit (`pricing.py:265-268`), and the most-expensive-rate fallback at `:222-240` covers only `TREND_TAG_MODEL` / `BRAND_SAFETY_MODEL` / `CREATOR_COPILOT_MODEL` — `"sonar"` gets no grace.
- The per-request fee cannot live in the table (`ModelRate` has no per-request field). Add a module constant `PERPLEXITY_FLAT_COST_PER_REQUEST` and a bespoke `estimate_perplexity_cost_usd(usage) -> Decimal`, mirroring `SARVAM_FLAT_COST_PER_CALL` (`:133`) + `estimate_sarvam_flat_cost_usd()` (`:153`).
- **Do not copy rates out of this document.** Perplexity bills `sonar` per-MTok *and* per-request, and the per-request tier varies with search-context size. Read the live figures on build day and stamp the verification date in the comment, matching the `# verified 2026-07-11/12` convention already on the Anthropic rows (`:104`).

### 5.2 Discovery route: `app/routes/outreach.py`

`POST /internal/outreach/discover`. Auth: add `"outreach_discover": (SCOPE_SERVICE,)` to `ENDPOINT_SCOPES`; verify with `await anyio.to_thread.run_sync(lambda: verify_token(token, endpoint="outreach_discover", body_workspace_id=workspace_id))` (the creator's user id is the `workspace_id`, as everywhere on the CREATOR audience). Register the router in `main.py` inside a `try/except` block.

Body:

```json
{"workspace_id": "01J...", "profile": {"display_name": "Priya Shah", "categories": ["BEAUTY","LIFESTYLE"], "city": "Pune", "tier": "MICRO", "languages": ["hi-IN","en-IN"], "min_budget_hint_inr": 1500, "media_kit_url": "https://influora.in/c/priya.shah/kit"}, "limit": 5, "niche_override": null}
```

Flow:
<!-- PRIYA: the two gates have DIFFERENT failure modes and the spec treats them as one.
  - check_spend_gate(workspace_id=None, *, reserve_usd, reserve_ttl_seconds) -> SpendGateResult. NEVER raises; check `.allowed` and settle `.reservation` via record_spend(cost, workspace_id, reservation=...). (check_spend_gate is gate.py:44; record_spend is NOT in gate.py — it is spend_tracker.py:402, alongside check_creator_spend_gate at :535. Corrected 2nd pass.)
  - check_creator_spend_gate(creator_id, audience, *, reserve_usd, reserve_ttl_seconds, cap_usd) -> CreatorReservation | None. RAISES SpendCapExceeded when over cap; the first two args are POSITIONAL. Settle with record_creator_spend(cost, creator_id, reservation=...) or release_creator() on any other exit. (spend_tracker.py:535)
  A single try/except is required, and there are TWO reservations to settle, not one.
  Also cap_usd=None silently falls back to settings.ai_creator_monthly_cap_usd (0.75) and DISCARDS the per-creator admin override. chat.py:533 passes creator_cap_override_from_context(creator_context) for exactly this reason; §4.3's profile payload must carry ai_monthly_cap_usd so this route can do the same. -->

1. Spend gates — **two calls with different contracts, and two reservations to settle:**
   - `gate = await check_spend_gate(workspace_id=<creator users.id>, reserve_usd=Decimal("0.15"))` — **never raises**; branch on `gate.allowed` and settle `gate.reservation` with `record_spend(cost, workspace_id, reservation=gate.reservation)`.
   - `creator_res = await check_creator_spend_gate(<creator users.id>, "CREATOR", reserve_usd=Decimal("0.15"), cap_usd=creator_cap_override_from_context(profile))` — first two args are **positional**, and it **raises `SpendCapExceeded`**; wrap it and map to `{"success": false, "error": {"code": "spend_blocked"}}`. Settle with `record_creator_spend(cost, <creator users.id>, reservation=creator_res)` on success, `release_creator(creator_res)` on every other exit.
   - **`cap_usd=None` is wrong**: it falls back to `settings.ai_creator_monthly_cap_usd` (0.75) and discards the per-creator admin override. `chat.py:533` passes `creator_cap_override_from_context(...)`; §4.3's profile payload must carry `ai_monthly_cap_usd` so this route can do the same.
   - The id in both slots is the creator's **`users.id`**, matching `chat.py:528`/`:804` — see the note in 4.3. A different key splits the monthly cap into two buckets.
<!-- §15 supersedes in part: seed-brand lookalike queries come first (§15.4a); NANO/MICRO default to city-local queries (§15.4b); before step 4, domains already in `outreach_brand_facts` within the TTL skip site analysis (§15.3); step 4 adds the storefront probe (§15.4d) and the 12-month evidence recency filter (§15.4e); fit scoring takes the outcome-loop inputs (§15.4f) and the LARGE/AGENCY penalty (§15.4c). -->
2. **Query plan** (`app/outreach/queries.py`): from the profile build 6 to 8 queries, for example `"{category} D2C brands India running influencer campaigns 2026"`, `"{category} brands collaborate with creators Instagram India small brand"`, `"influencer marketing agencies {city} {category} clients"`, `"site:linkedin.com \"{brand}\" (\"influencer marketing\" OR \"creator partnerships\" OR \"brand manager\")"` (second stage, per candidate), `"\"{brand}\" collaborate creators email partnerships"` (second stage). Each query runs through `PerplexityClient.search` with a system prompt that demands a JSON list of `{brand_name, website, why, evidence_url}` and forbids invented URLs.
3. **Parse** with a `parse_and_validate_candidates` that never raises: JSON only, `website` must be `https` and a syntactically valid host, `evidence_url` must appear in the returned citations list (this is the anti-hallucination check), dedupe by normalised host, drop candidates whose host is a marketplace or social network (amazon, flipkart, instagram, linkedin, facebook, youtube, myntra, nykaa as a host, not as a brand), drop hosts already in `profile.blocked_domains` if provided.
4. **Verify** each remaining candidate with `perform_site_analysis(url, workspace_id, request_id)`; drop on `success=False`; keep `niche_tags` and `product_catalog` size as fit signals.

<!-- PRIYA: step 4 as written cannot run inside its own timeout, and two of its three helper calls do not do what it says.

 (a) TIMEOUT. perform_site_analysis (analyze_site.py:142) does guarded_fetch (ssrf_fetch_timeout_seconds 15s/hop, scrape_total 30.0) then a Gemini classify (gemini_read 20.0). Twelve of those sequentially is minutes, against §4.3's 60s Java read timeout. The run cannot finish. Cap at 5 and run them CONCURRENTLY with asyncio.gather + a semaphore; perform_site_analysis is async and each call already offloads its own blocking fetch to a thread.

 (b) DOUBLE-RESERVING. Every perform_site_analysis call takes its OWN check_spend_gate with its own reservation (analyze_site.py:160-167) and settles it with its own record_spend (:273). Those are IN ADDITION to step 1's 0.15 run reservation. Twelve calls hold 12 x ai_reservation_per_call_usd on top. Size step 1's reservation for the Claude + Perplexity legs only and let site analysis bill itself, which step 7 half-acknowledges — say it once, here.

 (c) extract_opengraph_facts(raw_html) -> dict[str, str] needs RAW HTML. perform_site_analysis returns {"success", "data": {source_url, niche_tags, tone_dial, brand_color, product_catalog}} and NO html. There is no way to get og:site_name out of it. Either fetch once more or drop the canonical-name step and use the Perplexity brand_name validated against the host.

 (d) guarded_fetch(url, *, max_bytes, timeout) -> tuple[bytes, str] does NOT expose a status code. It returns (body, final_url) for a 404 exactly as for a 200, and raises only SsrfBlockedError. "first two that return 200" is not expressible. Also guarded_fetch_async was DELETED on purpose (ssrf_guard.py:246-250) — the offload must be inlined as anyio.to_thread.run_sync(lambda: guarded_fetch(...)), and the lambda is required because run_sync passes no keywords. -->

   **Cap at 5 candidates, not 12, and run them concurrently.** `perform_site_analysis` does a `guarded_fetch` (15 s/hop, `scrape_total` 30 s) then a Gemini classify (`gemini_read` 20 s). Twelve sequentially is minutes against §4.3's 60 s Java read timeout — the run cannot complete. Use `asyncio.gather` with a semaphore of 3; the function is `async` and already offloads its own blocking fetch.

   **Do not size step 1's reservation for these.** Each `perform_site_analysis` call takes its own `check_spend_gate` reservation and settles it with its own `record_spend` (`analyze_site.py:160-167`, `:273`). Step 1's 0.15 covers the Perplexity and Claude legs only; site analysis bills itself.

   **Canonical brand name:** `extract_opengraph_facts(raw_html) -> dict[str, str]` needs raw HTML, and `perform_site_analysis` returns only `{source_url, niche_tags, tone_dial, brand_color, product_catalog}` — no HTML. Either drop the `og:site_name` step and use the Perplexity `brand_name` validated against the host, or accept one extra fetch. Default: **drop it**; it is a nicety, not a gate.

   **Collab-page probe:** `guarded_fetch(url, *, max_bytes, timeout) -> tuple[bytes, str]` returns `(body, final_url)` and **exposes no status code** — a 404 comes back exactly like a 200, and the only exception is `SsrfBlockedError`. "First two that return 200" is not expressible. Probe `/collab`, `/collaborate`, `/creators`, `/influencers`, `/partnerships`, `/contact` and treat a page as present when the body is non-trivial *and* contains a role-address regex match; a soft-404 that yields no match simply falls through. `guarded_fetch_async` was deliberately deleted (`ssrf_guard.py:246-250`), so each probe is `await anyio.to_thread.run_sync(lambda u=u: guarded_fetch(u, timeout=5.0))` — the lambda is mandatory because `run_sync` passes no keyword arguments, and the default-arg binding is mandatory in a loop.
5. **Fit scoring** with one Claude call. `complete_with_forced_tool` is **`async` and fully keyword-only** (bare `*` at `claude.py:364`); it returns `ClaudeToolResult(ok, tool_input, error, usage)` and the parsed payload is **`result.tool_input`** — there is no `.text` on that type (that is `ClaudeTextResult`, from `complete_text`). It never raises, and it populates `usage` even when `ok=False`, so bill on `result.usage` regardless. Call it as `await claude.complete_with_forced_tool(system_blocks=[...], messages=[...], tool_schema=get_brand_fit_schema(), max_tokens=..., model=...)`. `get_brand_fit_schema()` returns a full `{name, description, input_schema}` envelope, mirroring `ANALYZE_CREATOR_CONTENT_SCHEMA` (`schemas.py:508`) — flat schema: input is the profile plus the verified candidate facts wrapped as untrusted; output per candidate `fit_score 0..100`, `fit_reasons[]` (max 3, each ≤ 80 chars), `brand_size`, `category`.
6. **Contact stage** (only for the top `limit` candidates): second-stage Perplexity queries for the person and for published contact addresses; parse into `{contact_email?, person: {name, title, public_url, source_url}?}`; the Python DPDP filter (`app/outreach/contact_filter.py`) drops personal mailbox domains, any phone-like string, any email whose domain is not the candidate's, and any `person.email` key. Confidence: 90 for a brand-published address on the brand's site, 70 role-based on the brand domain from a third-party page, 50 person card with public URL, 0 none.
7. Bill: sum Perplexity, Gemini (site analysis already bills itself), and Claude usage into `record_spend` and `record_creator_spend` with the reservation.
8. Return `{"success": true, "data": {"run_id": ulid, "candidates": [...], "searches_used": n, "usage": {...}}}` or `{"success": false, "error": {"code": "provider_unconfigured"|"spend_blocked"|"no_results"|"provider_error"}}`.

Tests: `tests/routes/test_outreach_discover.py` (auth, unconfigured key, both gates blocked — note `check_creator_spend_gate` raises while `check_spend_gate` returns, hallucinated evidence URL dropped, marketplace hosts dropped, personal mailbox and phone dropped, `person.email` key dropped, happy path with mocked Perplexity and mocked `perform_site_analysis`), `tests/outreach/test_contact_filter.py`, `tests/outreach/test_queries.py`.

<!-- PRIYA: half of this test line is redundant and the other half is understated.
  REDUNDANT: after Phase B's edit, _all_tool_schemas() (test_tool_schema_anthropic_valid.py:59-61) appends `[(t["name"], t) for t in get_creator_tool_schemas(None)]`, so the three new creator tools are covered AUTOMATICALLY the moment they land in CREATOR_TOOL_SCHEMAS. No test edit is needed for them.
  UNDERSTATED: forced-tool schemas sit OUTSIDE get_tool_schemas() by design — that is exactly why ANALYZE_CREATOR_CONTENT_SCHEMA (schemas.py:508, reached via get_analyze_creator_content_schema() :596) has ZERO combinator coverage today, and get_brand_fit_schema() would inherit that hole. Appending it requires a full {name, description, input_schema} envelope or test_tool_has_wellformed_envelope (L64) fails on the missing name/description. Worth fixing ANALYZE_CREATOR_CONTENT_SCHEMA's gap in the same edit — one line, closes a live blind spot on the anyOf-400 class of defect. -->

**`test_tool_schema_anthropic_valid.py`:** the three new *creator* tools need **no test edit** — Phase B already widens `_all_tool_schemas()` (L59-61) to append `get_creator_tool_schemas(None)`, so they are covered the moment they land in `CREATOR_TOOL_SCHEMAS`. What **is** uncovered is `get_brand_fit_schema()`: forced-tool schemas sit outside `get_tool_schemas()` by design, which is why `ANALYZE_CREATOR_CONTENT_SCHEMA` has zero combinator coverage today. Append both to `_all_tool_schemas()` in the same edit — each must be a full `{name, description, input_schema}` envelope or `test_tool_has_wellformed_envelope` (L64) fails on the missing fields. This is the guard against the `anyOf`-400 defect that takes down every Meera turn, brand included.

### 5.3 Creator tool schemas and persona

<!-- PRIYA: naming the three constants is not enough. creator_schemas.py (Phase B §7.1) carries FIVE module-level structures and FOUR of them must be extended together, or the tool is offered but never dispatched:
    CREATOR_TOOL_NAMES        9 -> 12   (order matters; it seeds the path dict)
    CREATOR_TOOL_TO_SPRING_PATH         derived from CREATOR_TOOL_NAMES — free if the tuple is right
    CREATOR_TOOL_SCHEMAS      9 -> 12
    CREATOR_IDEMPOTENT_REQUIRED_TOOLS   += SEND_PITCH  <-- the one that is easy to miss
  send_pitch is a C-tier tool that sends to a brand; omitting it from CREATOR_IDEMPOTENT_REQUIRED_TOOLS makes it silently retryable at loop.py L453-455 AND L472 (allow_retry=), which is a duplicate cold email to a brand. Phase B's review flagged the identical trap for send_routine_reply.
  Also: the Java side needs the matching three additions — CreatorToolName enum 9 -> 12, CreatorToolCallValidator.tierOf, and three handlers on CreatorMeeraToolController. 4.12 says "extend" in its heading; spell it out there. -->

`app/tools/creator_schemas.py` (Phase B) gains `FIND_OUTSIDE_BRANDS = "find_outside_brands"`, `DRAFT_PITCH = "draft_pitch"`, `SEND_PITCH = "send_pitch"` — and **four structures must be extended together**: `CREATOR_TOOL_NAMES` (9 → 12; it seeds `CREATOR_TOOL_TO_SPRING_PATH`), `CREATOR_TOOL_SCHEMAS` (9 → 12), and `CREATOR_IDEMPOTENT_REQUIRED_TOOLS` **must gain `SEND_PITCH`**. Missing that last one leaves `send_pitch` retryable at `loop.py` L453-455 *and* L472 (`allow_retry=`) — a duplicate cold email to a brand. Phase B's review flagged the identical trap for `send_routine_reply`. Java mirrors it: `CreatorToolName` 9 → 12, `CreatorToolCallValidator.tierOf`, three handlers on `CreatorMeeraToolController`.

Flat schemas (`find_outside_brands`: `limit` integer 1..5, `niche_override` string; `draft_pitch`: `candidate_id`, `subject`, `text`, required all; `send_pitch`: `draft_id`, `subject`, `text`, required all) and the loop's scope-decline sentence for `send_pitch`: `"I've saved the pitch as a draft. Sending it needs a tap from you in the pitch card."` with `finish_reason="pitch_scope_declined"`.

Persona additions (`creator_persona.py`, bump `PROMPT_VERSION` to `meera-2026.09.20.1`):

```
Finding work outside Influora:
- When the creator asks for deals and rank_open_campaigns finds nothing, ask
  once: "Nothing on Influora fits right now. Want me to look outside and pitch a
  few brands for you?" Call find_outside_brands only after a clear yes.
- Present at most five brands. For each: why it fits, the evidence, and the
  contact route. Say plainly when a brand is already on Influora; that pitch
  goes inside the platform, not by email.
- Draft pitches from the verified media kit. Never a floor, never a per-unit
  price, never a promise of results. Cold email to brands replies two to five
  times in a hundred; say so before the first send.
- LinkedIn and Instagram contacts are for the creator to message themselves.
  You never send there. Give them the note and the link.
- Never invent a brand, a person, an email address, or an evidence link. Only
  what the tools return.
```

### 5.4 Inbound reply hygiene (Python is not on the inbound path)

Inbound email is handled entirely in Java (4.8) and reaches the model only later as a `CreatorBrief` through Phase B's extraction route, which already wraps the text as untrusted. Add one guard there: `parse_and_validate_extraction` rejects a brief whose `summary_lines` contain an email address or a URL not present in the raw text (link injection).

---

## 6. Frontend

### 6.1 `src/lib/api.ts`

Types (snake_case, mirroring the Java records): `OutreachAlias`, `DiscoveryRun`, `BrandCandidate` (with `contact_route`, `contact_person`, `on_platform`, `on_platform_workspace {name, logo_url}`, `evidence[]`), `PitchDraft`, `PitchItem`, `PlatformPitchItem`, `ReferralItem`, `OutreachHealth` (admin). Namespace `creatorOutreach` with one method per route in 4.11, all `{ role: 'creator' }`, mocks for every one, registered in the `api` object. Brand namespace `brandPitches` (`list`, `get`, `accept`, `decline`). Public `referrals.preview(token)`. `CreatorAgentPreferences` gains `outreach_enabled: boolean`, `decline_template: string | null`.

### 6.2 Creator surfaces

- `src/components/creator/meera/CreatorToolResultRenderer.tsx` (Phase B) gains `BrandShortlistCard` (five rows: brand, fit score, reasons, evidence link, contact route badge, "On Influora" badge; actions "Draft pitch", "Dismiss", and for assisted routes "Copy note" plus "Open profile" which opens the public URL in a new tab), `PitchDraftCard` (From line showing the alias, editable subject and body, the two-line signature shown read-only, "Send" and "Discard", a one-line reply-rate note), `PitchSentCard` (status timeline: approved, sent, delivered, replied; "Cancel" during the 60-second window).
- `src/pages/creator-outreach.tsx` at `/creator/outreach`: tabs Shortlist, Pitches, Replies, Brands you brought. Pitches tab lists `PitchItem[]` with follow-up buttons. Replies tab links to the briefs created from inbound mail.

<!-- PRIYA: routing and nav facts, verified. Phase B §8.9 says "no new guarded creator pages are needed" — Phase E adds one, so the App.tsx work is real and was never named.
  - Creator pages SELF-WRAP <CreatorLayout> (creator-settings.tsx:448 is the pattern); the route wraps only <CreatorProtectedRoute>. Brand pages are the OPPOSITE — the route applies BrandLayoutWrapper and the page renders a bare <div> (brand-notifications.tsx:17-18 documents this explicitly). Getting the two backwards produces a double layout or none.
  - CreatorLayout's nav entry shape is { label, href, icon } inside navGroups (creator-layout.tsx:91-115; :72-81 are the TypeScript interfaces above it, not the array — corrected 2nd pass) — `href`, NOT `to`. AdminLayout's is { label, to, icon } in a FLAT NAV_ITEMS with no groups (AdminLayout.tsx:55-76) — `to`, not `href`. The two shells disagree and the spec uses neither name.
  - /:handle is App.tsx:830 in the working tree (:825 at 143ca1e) and swallows any unregistered single-segment top-level route into a soft-404. /join/brand and /creator/outreach are both 2-segment so they outrank it, but they still must be REGISTERED — Phase B also inserts /secure/:token and /c/:username/kit around L818-825, so cite the catch-all, never a line number. -->

**`App.tsx` routes (three new, none of them currently specified).** Creator pages self-wrap `<CreatorLayout>` and the route supplies only the guard; brand pages are the opposite — the route supplies `BrandLayoutWrapper` and the page renders a bare `<div>` (`brand-notifications.tsx:17-18` documents this). So:

| Path | Wrapper in `App.tsx` | Page |
|---|---|---|
| `/creator/outreach` | `<CreatorProtectedRoute>` only | self-wraps `<CreatorLayout>` |
| `/brand/pitches`, `/brand/pitches/:id` | `<BrandLayoutWrapper>` | bare `<div>`, no layout import |
| `/join/brand` | none (public) | renders the brand register page |

All three are 2-segment, so they outrank the `/:handle` catch-all (`App.tsx:830` in the working tree, `:825` at `143ca1e` — the branch's uncommitted F-0459 export change adds 5 lines above it) — but they must still be registered, and Phase B inserts `/secure/:token` and `/c/:username/kit` in the same region, so **cite the catch-all, never a line number**.

**Nav entry shapes differ between the two shells.** `CreatorLayout` (`src/components/creator/creator-layout.tsx:91-115` — the `navGroups` array; `:72-81` are the interfaces that type it, corrected 2nd pass) uses `{ label, href, icon }` inside `navGroups`; add `{ label: 'Find brands', href: '/creator/outreach', icon: <lucide icon> }` to the `'Manage'` group (`:104-114`). `AdminLayout` (`:55-76`) uses `{ label, to, icon }` in a **flat** `NAV_ITEMS` with no groups — `to`, not `href`.
- `src/pages/creator-settings.tsx`: a new `<Card className="mb-6">` after `<ConnectedAccounts />` (L458) titled "Outreach email" showing the alias address with copy, "Rotate address", the `outreach_enabled` switch with the sentence "Meera can find brands outside Influora and email them from this address after you approve each pitch", and a link to the sends list. `MeeraSettingsSection.tsx` gains the level-2 "Decline template" textarea (**max 500**) with the validation message from the server and the approval checkbox that sets `decline_template_approved`. <!-- PRIYA (2nd pass, 2026-09-04): (a) max was 600 here and 500 in §4.10 — 500 wins, it is `DealDtos.RejectRequest`'s `@Size(max = 500)`. (b) `MeeraSettingsSection.tsx` is NOT a Phase B file: it already exists at HEAD, 716 lines, rendered from `creator-settings.tsx:462`, and already ships the `approval_level` select. E6 is an EDIT of a live Phase A component, not an addition to a new one — and it must **reword** the copy at its L455, "Phase A is conversational only — Meera does not send or decline anything on your behalf yet", which E6 makes false the moment level 2 auto-declines. The two `creator-settings.tsx` anchors this bullet uses are re-verified at `143ca1e` + working tree: `<CreatorLayout>` L448, `<ConnectedAccounts />` L458. -->
- `MeeraCopilotChat.tsx` (`src/components/creator/MeeraCopilotChat.tsx` — **not** under `creator/meera/`; that directory does not exist at HEAD and Phase B creates it only for `CreatorToolResultRenderer.tsx`): no new handlers beyond Phase B. The three new tool names go into the **`meera-api.ts`** `CREATOR_TOOL_NAMES` constant that Phase B §8.2 introduces — a different constant from the Python tuple of the same name in §5.3; both must be extended.

### 6.3 Brand surfaces

- `src/pages/brand-pitches.tsx` at `/brand/pitches` and `/brand/pitches/:id` (brand layout wrapper), listing pitches with creator card, message, "Accept and start a deal", "Decline". Nav entry "Creator pitches" with an unread count from `brandPitches.list`.
- `src/pages/brand-register.tsx`: read `ref` via `useSearchParams`, show the "Invited by {name}" banner from `referrals.preview`, send `referralToken`. `brand-login.tsx`: preserve `?ref=` on the register link. Public route `/join/brand` renders the register page (add to `App.tsx` above `/:handle`).

### 6.4 Admin

<!-- PRIYA: two file-shape facts. (1) api-contracts.ts has NO aggregate object — namespaces are 17 bare `export const`s (authApi:139 … creatorAgentApi:1039) imported by name at call sites. So `outreachApi` needs no registration anywhere, unlike src/lib/api.ts which DOES have an aggregate `export const api = {` that every new namespace in 6.1 must be added to — **L6354 in the working tree**, L6313 at `143ca1e` (the branch's uncommitted +51 lines land at hunks 3587 and 3613, above it). Grep for the declaration, do not trust either number. (2) admin-console.tsx routes are RELATIVE (no leading slash) inside a <Routes> at :62-80, and the `path="*"` redirect at :79 must stay last. -->
`src/admin/services/api-contracts.ts` gains `outreachApi` (`getHealth`, `pause`, `resume`, `setCeiling`, `listSuppression`, `addSuppression`) next to `emailApi` (`:854`) — that file has **no aggregate object**, only 17 bare `export const` namespaces, so nothing else to register. (Contrast §6.1: `src/lib/api.ts` **does** have an aggregate — `export const api = {` at **L6354 in the working tree**, L6313 at `143ca1e` — and every new namespace there must be added to it or it is unreachable as `api.creatorOutreach`. <!-- PRIYA (2nd pass, 2026-09-04): line drift from the branch's uncommitted +51 lines in `api.ts`; grep the declaration. -->) Page `src/admin/pages/OutreachPage.tsx` with the health numbers, warm-up day, pause switch, ceiling input, suppression table; route `<Route path="outreach" element={<OutreachPage />} />` in `admin-console.tsx` — **relative path, no leading slash**, inserted before the `path="*"` redirect at `:79`; nav entry `{ label: 'Outreach', to: '/admin/outreach', icon: <lucide icon> }` in `AdminLayout`'s flat `NAV_ITEMS` (`:61-76`).

### 6.5 Tests

`BrandShortlistCard.test.tsx` (assisted route shows copy, never a send), `PitchDraftCard.test.tsx` (send disabled while text has a rupee amount matching a floor placeholder, cancel window), `creator-outreach.test.tsx`, `brand-pitches.test.tsx`, `brand-register.ref.test.tsx` (token sent, banner shown, no token → no banner), `OutreachPage.test.tsx`.

---

## 7. Backend tests

| Test | Covers |
|---|---|
| `WebDomainsTest` | normalisation, personal mailbox list, email domain |
| `OutreachAliasServiceTest` | slug rules, reserved words, collision suffix, one ACTIVE per creator under concurrent calls (two threads on the same profile row) |
| `MailgunOutreachClientTest` | request shape, headers, mock mode, failure result |
| `OutreachDiscoveryServiceTest` | limits, refusals, DPDP gate drops personal mailbox, phone, off-domain and `person.email`; on-platform match sets ON_PLATFORM |
| `OnPlatformBrandMatcherTest` | email match, domain match, ambiguous domain no match, suspended workspace no match, no email leaves the class |
| `OutreachPitchServiceTest` | every refusal code, suppression by hash and domain, ceiling, per-creator limit, floor leak, idempotent send, follow-up windows |
| `OutreachInboundControllerTest` | signature valid and invalid, replay token, unknown alias UNMATCHED, auto-reply REJECTED, negative reply suppresses and does not create a brief, positive reply creates a brief with source INBOUND_EMAIL and publishes the event, attachments ignored, oversize body capped |
| `OutreachEventsWebhookTest` | bounce, complaint, unsubscribe update pitch and suppression |
| `ReferralTokenServiceTest` | mirrors `InviteTokenServiceTest`; fail-closed outside dev |
| `AuthServiceBrandRegisterReferralTest` | referral consumed once, invalid token ignored, event published |
| `CreatorBrandPitchServiceTest` | unique open pitch, accept creates campaign plus invite, decline, referral only for brands created after the pitch |
| `MeeraAutoDeclineJobTest` | grace period, excluded category, blocked brand, prior-contact skip, referral skip, template validation |
| `OutreachSendJobTest`, `OutreachFollowUpJobTest`, `MeeraOutreachHealthJobTest` (pause thresholds), `OutreachInboundPurgeJobTest` |
| `NotificationEventPermitsTest` | **new file — no such test exists at HEAD.** The three new events are in `permits`, live in `com.influora.service.notification.event`, have `NotificationListener` handlers, and have `EmailTemplateRegistry` keys |
| `SecurityConfigMatcherTest` | the new permitAll webhook and public GET entries sit before the `/admin/**` (L207) and `/creator/**` (L227) role gates |
| `InfoBarrierTest` | no Phase E class imports `CreatorAgentPreferencesRepository`. **Depends on Phase B's scan-root widening to `service/**` and `job/**` actually shipping** (Phase B verdict condition 2) — every Phase E class that touches preferences lives in `service/outreach/**` or `job/**`, so without that widening this row asserts nothing |
<!-- §15 supersedes: thirteen migrations, not ten — 3.1-3.9, `V20260920100900__outreach_runtime_state.sql` (§4.9), `V20260920101000__outreach_discovery_runs.sql`, `V20260920101100__outreach_brand_facts.sql` and `V20260920101200__campaigns_end_brand_category_idx.sql` (§15.3, §15.1a). PRIYA (3rd pass, 2026-09-04): §15.3 and §15.6 both asserted this row already read "twelve". It read "ten", and the count is thirteen. -->
| `MeeraPhaseEBootValidationTest` | Testcontainers, **all thirteen migrations** (3.1–3.9 plus `V20260920100900__outreach_runtime_state.sql` from 4.9 plus the three from §15), `ddl-auto=validate`; **skipped without Docker, which this machine does not have** — so the entity↔column mismatches this phase can produce (`MEDIUMTEXT` on `body_text`, `open_marker`, `website_domain`) are unverified locally and must be checked on staging before merge |
| `MailgunEventsWebhookPayloadTest` | **new** — the events webhook binds JSON with a nested `signature` object, not multipart; a `@RequestParam` handler silently binds nothing (4.8) |
| `CreatorBriefInboundSourceTest` | **new** — a brief created from an inbound reply carries `source = INBOUND_EMAIL`, is persisted before any AI call, and is still created when the creator has not accepted consent (4.8 step 6) |

---

## 8. Build order

<!-- §15.5a supersedes the sequencing below: after Phase B, Wave 1 = E4, E5, E6 + E1's alias and suppression foundation; Wave 2 = E2 + E3 behind `cold-email-enabled` for an allow-listed cohort. The day table still holds as the intra-wave order of work; the twelve migrations (not ten) land on day 1. -->

| Day | Backend | AI service | Frontend | Ops |
|---|---|---|---|---|
| 0 | | | | register the domain, create the Mailgun account, publish DNS, start warm-up mail from a test alias |
| 1 | all **ten** migrations (3.1 to 3.9 **plus `V20260920100900__outreach_runtime_state.sql`** from §4.9), entities, repositories, `WebDomains`, `OutreachProperties`, boot test, **`NotificationEventPermitsTest`** | Perplexity client, settings, pricing row | types and mocks, `creatorOutreach` namespace | |
| 2 | `OutreachAliasService`, `OutreachMailer` + Mailgun client, `OutreachSendJob`, alias routes | discovery route with query plan, parser, verification, fit scoring, contact filter | settings card with alias, `BrandShortlistCard` | |
| 3 | `OutreachDiscoveryService`, `OnPlatformBrandMatcher`, `MeeraOutreachAiClient`, candidate routes, tool executors `find_outside_brands`, `draft_pitch` | creator tool schemas, persona, `PROMPT_VERSION` | `PitchDraftCard`, `PitchSentCard`, `/creator/outreach` page | |
| 4 | `OutreachPitchService`, send route, `send_pitch` executor, inbound and events webhooks, suppression, unsubscribe page | extraction link-injection guard | pitches and replies tabs, admin `OutreachPage` | inbound route in Mailgun pointing at staging |
| 5 | `CreatorBrandPitch` + brand routes + event + template, `ReferralTokenService`, register hook, referral routes | | brand pitches pages, `?ref` on register, `/join/brand` | |
| 6 | `MeeraAutoDeclineJob`, decline template validation, follow-up and health and purge jobs, admin controller | | decline template editor, level-2 log | |
| 7 | full `mvn -o test`, schema diff, Kavya QA, Kabir review of the inbound webhook, the DPDP gate, and the referral token | full pytest | tsc, build, vitest | first real pitches at ceiling 20 |

---

## 9. Manager seat (E7): design only, build after Swapnil approves

A manager is a normal `UserType.CREATOR` account with no profile of its own, linked to up to five creators through `creator_manager_links (manager_user_id, creator_profile_id, status PENDING|ACTIVE|REVOKED, permissions_json, invited_at, accepted_at)`. The creator invites the manager by email (token pattern from `WorkspaceMemberInvite`); the manager accepts. A "switch creator" endpoint mints an access JWT carrying an extra claim `actingForCreatorProfileId`; `AuthPrincipal` gains an optional `actingForCreatorProfileId` and every creator route that reads "the creator" resolves it through a single helper `CreatorContextService.requireCreatorProfile(principal)` (which already exists and is the one place to change). Denied to managers: bank and payout routes, withdrawals, floor changes, consent, alias rotation, `send_pitch` and `send_routine_reply` on brands the manager introduced. Every send by a manager carries `metadata.sent_by_manager_user_id`, and the creator's send log shows the manager's name. Approval level defaults to 0 for manager-driven sessions. Estimated six engineer-days across backend and frontend. Do not start before the ruling.

---

## 10. Acceptance: the zero-context tester's ten questions

1. Show one complete run: creator asks for deals, nothing fits, Meera asks, creator says yes, five brands appear with evidence links that resolve, one is "on Influora". Where does every number and name come from?
2. Where is the line that stops a personal Gmail address, a phone number, or a person's email from being stored or shown? Show the Java gate, the Python filter, and a test for each.
3. A pitch email: what is the From, the Reply-To, the envelope domain, the unsubscribe header, and which domain sends OTPs? Prove the two can never share a provider or a domain.
4. A brand replies. Trace the bytes from Mailgun to the creator's brief. What happens to attachments, HTML, a 2 MB body, an auto-reply, an "unsubscribe" reply, and a forged webhook call?
5. A brand that is already on Influora: what does the creator see, what does the brand see, and what never leaves the server?
6. A brand signs up from the pitch link three weeks later. Where is that recorded, who is notified, and what is deliberately not done until Swapnil rules?
7. What stops a creator from sending 500 pitches, and what stops the platform from sending 5,000 on day one?
8. Complaint rate hits 0.2 percent at 3 am. What pauses, who is told, and how is it resumed?
9. Level 2: show a decline that fires, one that is skipped because the creator spoke to the brand before, and the validation that rejects a template containing a price.
10. Do all **ten** migrations (3.1–3.9 plus `outreach_runtime_state`, 4.9) apply on stock MySQL 8 with `ddl-auto=validate`, and does a creator with no alias, no pitches, and no referrals load every new page with honest empty states?

---

## 11. Expert questions and answers

**Domain**
- *Why a new domain and not `outreach.influora.in`?* Reputation is scored per organisational domain by Gmail and Microsoft. A subdomain drags the parent. OTP mail must survive an outreach blacklist.
- *What if `influora-outreach.in` is taken?* `influora-connect.in`, `hello-influora.in`. Never a `.com` that could be confused with a phishing domain; `.in` matches the brand.
- *Who owns it?* The same registrar account as `influora.in`, auto-renew on, registrar lock on, WHOIS privacy on. Loss of the domain is loss of every alias.
- *DMARC policy?* Start `p=quarantine`, `pct=100`, strict alignment, move to `p=reject` after 30 days of clean reports. Reports go to a mailbox someone reads.

**Alias automation**
- *How does the creator get an address without doing anything?* `POST /creator/outreach/alias` allocates, and the settings page and the first discovery run both call it; `GET` only reads and returns 404 `ALIAS_NOT_ALLOCATED` until then. The creator never types anything. <!-- PRIYA (2nd pass, 2026-09-04): this still said the GET allocates, contradicting §4.11 and §13.1 correction 47. -->
- *Naming collisions?* Slug plus a two-character base36 suffix, unique per domain, reserved words blocked. `priya.shah` and `priya.shah.k7` can coexist.
- *Creator changes their username?* The alias does not change. They can rotate it from settings; the old one still delivers for 90 days.
- *Suspended creator?* Alias status SUSPENDED: outbound refused, inbound stored as UNMATCHED, not routed.
- *Thousands of aliases on one domain?* Mailgun catches all recipients on the domain with one route. No per-alias provisioning.

**Security**
- *Webhook forgery?* HMAC signature check with the Mailgun signing key, timestamp window five minutes, token replay set, unique provider message id. A failed check returns **401** and is audited; **406** is reserved for messages the inbound route deliberately refuses (unknown alias, oversize body). <!-- PRIYA (2nd pass, 2026-09-04): said 406 on a bad signature, contradicting §4.8 and §13.1 correction 37. -->
- *Prompt injection through an inbound email?* The body never reaches a model directly. It becomes a `CreatorBrief` and goes through the Phase B extraction route wrapped as untrusted, with the link-injection guard in 5.4. Meera's persona treats brief text as data.
- *SSRF from evidence URLs?* Every fetch of a candidate site goes through `guarded_fetch`: https only, DNS pinned, private ranges denied, two redirects, five MB. Perplexity is a fixed trusted host.
- *Hallucinated brands or emails?* A candidate is kept only if its evidence URL appears in Perplexity's citations and its site passes `perform_site_analysis`. Email addresses are kept only if role-based on the brand's own domain or published on the brand's own site. Nothing the model invents survives the parser.
- *Creator impersonation?* From name is the creator's display name; the address is on our domain; the signature says "via Influora"; the body carries their media kit link. A creator cannot set an arbitrary From.
- *Floor leakage?* The same floor check Phase B applies to drafts runs on every pitch body and subject before send.
- *Who can read inbound mail?* Only the creator whose alias received it, through their briefs, and admins through the audit trail that stores counts, not bodies. Bodies purge after 90 days.

**Deliverability**
- *Warm-up?* Section 2.4. The ceiling is config, raised weekly by ops. The health job pauses on complaint rate > 0.1 percent or bounce rate > 5 percent.
- *Unsubscribe?* `List-Unsubscribe` and `List-Unsubscribe-Post` headers, a link in the body, one-click handling, suppression by hash. A "not interested" reply suppresses too.
- *Follow-ups?* Two maximum, at four and ten days, each a draft the creator approves. Never automatic.
- *Volume per creator?* Ten pitches a day, two discovery runs a week, both config.

**Privacy and law**
- *DPDP?* Brand contacts are business data when role-based; a named person is stored as name, title, public URL only, with the source URL, and can be deleted on request through the admin suppression tool. Inbound mail purges at 90 days. Creators' own data is under their existing consent and export and delete.
- *LinkedIn and Instagram terms?* We read public search results and never log in, automate, or scrape. Assisted send means the creator uses their own account.
- *Unsolicited commercial email in India?* No specific statute; we follow the international baseline anyway: identification, a working opt-out, honouring it within one send, no deception in the subject.

**Cost**
<!-- §15 supersedes: the creator pays nothing per run (§15.3 step 1); the platform's marginal cost falls below ₹8 once a niche is indexed in `outreach_brand_facts`; the 40-credit figure below is Rohan's model and is the alternative, not the default, in the §15.3 ruling. -->
- *Per discovery run?* Roughly eight Perplexity calls, **up to five** site analyses on Gemini (capped and run concurrently under a semaphore), one Claude fit call. The 25-to-40-rupee figure was costed at twelve analyses and is therefore an **upper bound**; re-cost it at five before quoting it to Rohan. <!-- PRIYA (2nd pass, 2026-09-04): §11 still said twelve after §13.1 correction 25 capped it at five — twelve sequential `perform_site_analysis` calls cannot finish inside §4.3's own 60 s read timeout. --> Charged against the creator's monthly cap and credits (Rohan's sheet: a 40-credit action).
- *Mailgun?* Flat plan at this volume; less than the Perplexity spend.

**Deliverables checklist for sign-off**
- Domain registered, DNS verified in Mailgun, DMARC reports arriving, `influora.in/outreach` page live.
- **Thirteen** migrations, boot-validated on Docker — this machine has none, so staging is the gate. <!-- PRIYA (3rd pass, 2026-09-04): this line still read "Ten" after §15 claimed §10 Q10 had been updated; §15 added three, not two (see §16.1 C14). -->
- **Two** unsubscribe systems exist and both must be demonstrated: `EmailTemplateRegistry`'s notification footer (`email_preferences`) and `OutreachMailer`'s `List-Unsubscribe` (`outreach_suppression`), plus §15.2e's brand-initiated `POST /outreach/optout`. <!-- PRIYA (3rd pass): §14.4 item 4 asked for this sentence and it was never added; §15.2e adds a third entry point to the second system. -->
- Alias, discovery, pitch, inbound, events, unsubscribe, referral, brand pitch, admin health all wired end to end on staging with one real reply routed into a brief.
- Kabir review of the inbound webhook, the DPDP gate, the SSRF path, and the referral token.
- Kavya QA. Meera full verification. Priya's ten questions all positive.
- Ops runbook: pause and resume, raising the ceiling, handling a complaint spike, rotating the Mailgun keys.

---

## 12. Decisions still open (build with the default, flag to Swapnil)

1. **Referral incentive.** `fee_pct_override` and `first_invite_until` exist and stay null. Nothing reads them.
2. **Manager seat.** Designed in section 9, not built.
3. **Discovery credits.** <!-- §15.3 reframes this ruling: default is free discovery, 5-credit refundable sends with 3 free per month, +40 credits to the creator per brand joined; Rohan's 40-per-run is the alternative. Two further rulings added by §15: kill-rule thresholds (§15.5d) and cold-email cohort size (§15.5a). --> Rohan's sheet prices a run at 40 credits; whether the first run each month is free is a pricing call.
4. **Domain name.** `influora-outreach.in` is the default; ops confirms availability on day 0.

---

## 13. Priya review

**Reviewer:** Priya (CTO). **Baseline:** HEAD `8c7b18b` plus the Phase B spec as corrected by its own §13. **Method:** every symbol, signature, line reference, record arity, column type, DNS record and provider contract this document names was checked against the actual source, not against the fact sheets. Corrections are applied inline above, each tagged `<!-- PRIYA: ... -->`.

Two fact-sheet errors surfaced en route and are corrected here: ShedLock is **`V68__shedlock.sql`**, not V66 (`facts/email-invites-config.md` §4); and `NotificationEvent` lives in **`com.influora.service.notification.event`**, not `service/notification/` (§1 of the same sheet).

### 13.1 Corrections applied

**Would not compile (11)**

| # | § | Was | Is |
|---|---|---|---|
| 1 | 4.6 | `CampaignService.create` "with `endBrandName = workspace name`" | Signature is `create(AuthPrincipal, CampaignWriteRequest)` returning `CampaignResponse` (`CampaignService.java:146`). No scalar-budget overload. `CampaignWriteRequest` (`CampaignDtos.java:60-94`) requires `@NotBlank @Size(min=5,max=300) title`, `@Valid @NotNull BudgetDto budget`, `@NotNull TimelineDto timeline`; `create()` throws `END_BRAND_NAME_REQUIRED` / `END_BRAND_CATEGORY_REQUIRED` (L160-168). A title plus an end-brand name does not construct it. |
| 2 | 4.6 | "use `CollaborationReviveService.reviveOrRefuse` … confirm the plain invite entry point with graphify" | Resolved. The entry point is **`CreatorDiscoveryService.invite(AuthPrincipal, String creatorProfileId, String campaignId, String message)`** (`CreatorDiscoveryService.java:453`, public `@Transactional`), the caller of `Collaboration.invite(id, campaignId, creatorUserId, message, currency)` (`Collaboration.java:110`; two production callers total). `reviveOrRefuse` exists only to work around `UNIQUE(campaign_id, creator_id)` on a **pre-existing** campaign — inapplicable when the flow creates a fresh one. Call the service, not the factory: it also runs `recordInviteOnTimeline` (`:551`, the F-0291 fix), without which the creator opens an empty deal room. |
| 3 | 4.10 | `dealService.rejectAsCreatorAgent` "a package-visible sibling" | **Must be `public` and `@Transactional`.** This repeats verbatim the error Phase B's review already corrected (Phase B §3.9 / correction 35): a package-visible method self-invoking `reject` runs outside the Spring proxy, so the transaction the atomicity claim rests on silently never opens. Also `DealService.reject` takes **four** args (`DealService.java:398-400`). |
| 4 | 4.10 | "skip when any `DealMessage` from this creator to this workspace exists" | Unanswerable with any existing query. `DealMessage` has **no `workspace_id` and no `creator_id`** — only `collaboration_id`, `sender_id`, `sender_type` (`V33__deal_messages.sql`), and **five of six** `DealMessageRepository` finders are collaboration-scoped (the sixth, `findFirstMessageTimestampsBySender()` `:52-57`, is a global parameterless `GROUP BY` — corrected in the 2nd pass, §14.1). The workspace is three hops away. A new `@Query` is supplied inline. |
| 5 | 4.10 | "for every `CreatorAgentPreferences` with `approvalLevel == 2`" | No such accessor. `CreatorAgentPreferencesService` exposes prefs only by `users.id` (L77, L95) plus Phase B's `getByProfileId`. Written literally, the job injects `CreatorAgentPreferencesRepository` — the exact violation Phase B widened `InfoBarrierTest`'s scan roots to `service/**` and `job/**` to catch, and `com.influora.job` is inside that scan. A service-level finder is specified. |
| 6 | 3.9 | `PreferencesResponse` / `UpdatePreferencesRequest` "list and update every construction site … as Priya's Phase B review did" | A deferral, not an instruction. Both are positional records: `PreferencesResponse` 18 → 23 (Phase B) → **25**, **4** construction sites; `UpdatePreferencesRequest` 16 → 18 → **20**, **9** sites. All enumerated in Phase B §3.10 and restated inline. |
| 7 | 4.8 step 6 | `CreatorBriefService.paste(creatorUserId, rawText)` | Four independent breaks: it takes a `users.id` while step 2 yields a `creator_profile_id`; `CreatorBrief.paste(id, creatorProfileId, rawText)` hard-sets `source = PASTED` with no parameter for a new value, so adding `INBOUND_EMAIL` to the enum changes nothing; its first step is a consent check that **throws**; and it makes a synchronous AI call inside a webhook. Replaced with a `fromInboundEmail` factory plus a deferred `analyseExisting`. |
| 8 | 4.6 | `CreatorBrandPitchReceivedEvent` with no package named | `NotificationEvent` is a **sealed interface** in `com.influora.service.notification.event` (`NotificationEvent.java:8-49`). A permitted record outside that package does not compile. The last permits entry (`CreatorNotConnectedEvent`, L49) has no trailing comma. |
| 9 | 4.6 | "Add the template key to `EmailTemplateRegistry.SPECS`" | The class is **package-private** (`:25`), `SPECS` is `private static final` (`:57`), and `Spec` is a **private record**. Nothing outside `com.influora.integration.msg91` can register a key — the file itself must be edited — and the 5-arg form is `Spec(subject, heading, bodyTemplate, ctaLabel, ctaUrlVar)` (`:50-52`); the spec supplied three of five. |
| 10 | 4.7 | `BrandRegisterRequest` "has one construction site in tests" | **Two**: `AuthServiceTest.java:85` (the static `REQUEST` field) and `:322` (`brandRequestWithPhone`). The record has 9 components today (`:28-37`), so the addition makes 10. |
| 11 | 5.2 step 5 | `complete_with_forced_tool(...)` used positionally | `async` and **fully keyword-only** (bare `*` at `claude.py:364`); returns `ClaudeToolResult(ok, tool_input, error, usage)` and the payload is `.tool_input` — `.text` exists only on `ClaudeTextResult`. The same correction Phase B's review had to make (its #10). |

**Would compile but fail at boot, at runtime, or in CI (10)**

| # | § | Defect |
|---|---|---|
| 12 | 3.4 | `body_text TEXT` (65,535 **bytes**) against `inbound-max-bytes` default **262,144**. A 100 KB reply truncates silently in non-strict mode or throws "Data too long" in strict mode — which MySQL 8 defaults to — inside the webhook, producing a 500 to Mailgun and an eight-hour retry storm. Changed to `MEDIUMTEXT` with a matching `columnDefinition` so `ddl-auto=validate` agrees. |
| 13 | 3.1 | The prose said "remove the second UNIQUE KEY" but **the SQL block still contained it**. An engineer copying the block ships `UNIQUE (creator_profile_id, status)`, which also forbids a creator ever having two RETIRED aliases — thrown on the second rotation. Removed from the SQL, `idx_coa_creator_status` added. |
| 14 | 3.6 | `UNIQUE (creator_profile_id, workspace_id, status)` with the justification "accepted or declined rows do not block a later pitch because their status differs". Wrong: it blocks a second row in **any** status, so the second pitch a brand ever declines collides on `(creator, workspace, 'DECLINED')`. Replaced with a nullable `open_marker` — MySQL's standard substitute for a partial unique index. |
| 15 | 5.1 | Adding `perplexity` to `/readyz` `keys_loaded` makes any deployment without the key return **503 not_ready for the whole service** — `main.py:213` is `ready = all(keys_loaded.values()) and redis_ok and cap_scope.safe` (line and full expression corrected 2nd pass). Directly contradicts the same paragraph's "not a boot secret". |
| 16 | 5.1 | `perplexity_model: str = os.getenv(...)` — a plain `=` in a `frozen=True` dataclass is evaluated once at import, so `monkeypatch.setenv` + `get_settings.cache_clear()` (the house idiom; `get_settings` is `@lru_cache(maxsize=1)`) never sees it. And `perplexity_base_url = "..."` with no annotation is not a dataclass field at all. |
| 17 | 5.1 | `"sonar"` must be in `PRICING_TABLE` — `estimate_cost_usd` raises `ValueError` for an unpriced model and raises it **before** the `if not usage` short-circuit (`pricing.py:265-268`). The expensive-rate fallback (`:222-240`) covers only three env-overridable constants; `"sonar"` gets none. |
| 18 | 5.2 step 4 | `guarded_fetch` "first two that return 200" is not expressible: it returns `(bytes, final_url)` for a 404 exactly as for a 200 and raises only `SsrfBlockedError`. `guarded_fetch_async` was **deliberately deleted** (`ssrf_guard.py:246-250`), so the offload must be `anyio.to_thread.run_sync(lambda: guarded_fetch(...))` — the lambda is mandatory because `run_sync` passes no keywords. |
| 19 | 5.2 step 4 | `extract_opengraph_facts(raw_html)` needs raw HTML; `perform_site_analysis` returns `{source_url, niche_tags, tone_dial, brand_color, product_catalog}` and no HTML. The `og:site_name` step as written cannot run. |
| 20 | 4.8 | The events webhook is **`application/json` with the signing triple nested under a `signature` object**, not multipart with top-level fields. "Same signature check" means a `@RequestParam` handler binds nothing and every delivery, bounce and complaint event silently drops — so suppression never populates and the sending domain burns. |
| 21 | 3.9 / 4.10 | `decline_template` max **600** vs `DealDtos.RejectRequest`'s `@Size(max = 500)` (`DealDtos.java:137`). Capped at 500. |

**Understated or unbuildable work (9)**

| # | § | Was | Is |
|---|---|---|---|
| 22 | 3.8 | "set it wherever `websiteUrl` is set (find every `setWebsiteUrl` with graphify; there are few)" | There is **no `Workspace.setWebsiteUrl`** and no builder. The single write site is `Workspace.applyCompanyDetails(...)` (`:262-279`), reached from `OnboardingService:62` and `WorkspaceService:112`. `newBrand` (L117) has no website parameter, and `brandRegister` uses `newBrand` — so `workspaces.website_url` is NULL for any brand that never opens workspace settings. |
| 23 | 3.8 / 4.6 | E4 matches on `workspaces.website_domain` | **Design defect.** Every `setWebsiteUrl` in this repo is on **`BrandProfile`** (`:87`), written by `AnalyzeSiteTriggerService:115` and `:230` — that is the URL a brand actually supplies, on the onboarding analyse-site path. Matching only the workspace column misses most brands and reduces E4 to a near-zero hit rate. Backfill now reads `COALESCE(workspaces.website_url, brand_profiles.website_url)`. |
| 24 | 3.8 / 4.9 / 4.11 | "a `system_flags` row if such a table exists … or a Redis key" | Neither is real. `system_flags` has **zero hits** across every `.sql` and `.java`. Redis is on the classpath but used **only** through `@Cacheable` (`RedisCacheConfig`) — there is no `RedisTemplate` bean in `src/main`. And a bound `@ConfigurationProperties` value cannot be written at runtime, so `PUT /admin/outreach/ceiling` and the health job's pause had nowhere to go. Resolved with a tenth migration, `outreach_runtime_state`, on the `admin_email_send_lock` singleton-row precedent — which also makes the global daily ceiling correct across replicas, where an in-memory counter would not be. |
| 25 | 5.2 step 4 | 12 sequential `perform_site_analysis` calls behind §4.3's 60 s read timeout | Each does a `guarded_fetch` (15 s/hop, `scrape_total` 30 s) plus a Gemini classify (20 s read). Twelve sequentially is minutes — **the run cannot complete**. Capped at 5, run concurrently under a semaphore. Each call also takes its **own** spend reservation (`analyze_site.py:160-167`), so step 1's 0.15 was double-reserving. |
| 26 | 5.2 step 1 | "the daily gate … and the creator monthly gate" as one bullet | Different contracts. `check_spend_gate` **never raises** (check `.allowed`); `check_creator_spend_gate(creator_id, audience, *, ...)` takes its first two args **positionally** and **raises `SpendCapExceeded`**. Two reservations to settle, not one. And `cap_usd=None` silently discards the per-creator admin override that `chat.py:533` passes. |
| 27 | 5.3 | Names three new constants | Four structures must move together: `CREATOR_TOOL_NAMES` (9→12), `CREATOR_TOOL_SCHEMAS` (9→12), the derived path dict, and **`CREATOR_IDEMPOTENT_REQUIRED_TOOLS += SEND_PITCH`**. Missing the last leaves `send_pitch` retryable at `loop.py` L453-455 *and* L472 — a duplicate cold email to a brand. Phase B's review flagged the identical trap for `send_routine_reply`. |
| 28 | 3.9 | "add to `CREATOR_CONTEXT_PAYLOAD_FIELDS` plus the drift test's `distinctive` dict" | `test_creator_context_drift.py` breaks in **four** places (Phase B §2.10): sorted insertion, a render written literally as `ctx.get("outreach_enabled")` (the test greps for that exact form — `context.get` fails), the `distinctive` value, and `_FORBIDDEN_BRAND_FIELDS`. It parses the real Java file and `pytest.fail`s rather than skipping, so Java and Python must land in one commit. |
| 29 | 6.2–6.4 | Frontend surfaces named, routes not | `/creator/outreach`, `/brand/pitches`, `/brand/pitches/:id` were never specified in `App.tsx`, and Phase B §8.9 explicitly says "no new guarded creator pages are needed" — so this is unclaimed work. The two shells also invert: creator pages self-wrap `<CreatorLayout>`, brand pages get `BrandLayoutWrapper` from the route and render a bare `<div>` (`brand-notifications.tsx:17-18`). Nav shapes differ too — `{label, href, icon}` in `CreatorLayout` groups vs `{label, to, icon}` in `AdminLayout`'s flat array. |
| 30 | 5.2 tests | "`test_tool_schema_anthropic_valid.py` extended for the three new tool schemas and `get_brand_fit_schema()`" | Half redundant, half understated. After Phase B's `_all_tool_schemas()` edit the three creator tools are covered **automatically**. What is uncovered is `get_brand_fit_schema()` — forced-tool schemas sit outside `get_tool_schemas()` by design, which is why `ANALYZE_CREATOR_CONTENT_SCHEMA` has zero combinator coverage today. Appending it needs a full `{name, description, input_schema}` envelope or `test_tool_has_wellformed_envelope` fails. |

**Ops, DNS and provider contract (7)**

| # | § | Correction |
|---|---|---|
| 31 | 2.3 | The "`TXT @` — Mailgun domain verification" row **does not exist**. Mailgun verifies from SPF TXT plus DKIM TXT (plus MX for inbound and the tracking CNAME); there is no separate verification TXT. The table also listed two rows both named `TXT @`, reading as a conflict. Removed. |
| 32 | 2.3 | **Cross-domain DMARC reporting was missing.** `rua=mailto:dmarc@influora.in` on `influora-outreach.in` requires `influora-outreach.in._report._dmarc.influora.in TXT "v=DMARC1"` published on **influora.in**, or conforming reporters refuse to send. Without it §11's "reports go to a mailbox someone reads" produces nothing. |
| 33 | 2.3 | `A/AAAA @` "redirect to influora.in/outreach" — DNS has no redirect, and a CNAME at an apex carrying TXT and MX is illegal. Rewritten as an A record plus a server-side 301, with `www` as a CNAME. |
| 34 | 2.2 / 2.3 | The catch-all route `match_recipient(".*@influora-outreach.in")` swallows `postmaster@` and `abuse@`, which §2.3 requires reach a human. Added a **priority-0** route forwarding those two to the ops inbox ahead of the catch-all. As written, abuse complaints land in `outreach_inbound_messages` as UNMATCHED and nobody reads the message that gets the domain terminated. |
| 35 | 2.2 / 2.3 | The EU option changes **two** things, not one: the API base (`api.eu.mailgun.net`, already configurable) **and** the inbound MX (`mxa.eu.mailgun.org`). §2.2's region sentence was also garbled mid-clause. |
| 36 | 2.3 | DKIM 2048-bit must be chosen **at domain-creation time** (Mailgun defaults to 1024 and it cannot be changed later without re-adding the domain), and a 2048-bit key exceeds one 255-char TXT string — it must be published as a multi-string TXT, which several registrar UIs get wrong. SPF changed to Mailgun's documented `~all` with the `-all` trade-off recorded rather than assumed. |
| 37 | 4.8 | Returning **406 on a failed signature** is wrong on both endpoints. A genuine Mailgun call never fails the check, so 406 there only discards real retries during a key rotation. 401 plus an audit row for a forged call; 406 stays for messages the inbound route deliberately refuses. |

**Wrong references, resolved (6)**

| # | § | Correction |
|---|---|---|
| 38 | 3 | "Phase B's highest (`V20260910100600`)" — Phase B §2.7 explicitly **skips** that file. Phase B's highest is `V20260910100500`. Harmless for ordering (`flyway.out-of-order: true` is on), wrong as a fact. |
| 39 | 2.5 | "extend `ConfigurationPropertiesRegistrationTest`" — that test **classpath-scans** for `@ConfigurationProperties` and asserts every hit is registered. It needs no edit; it goes red on its own until `OutreachProperties.class` lands in `@EnableConfigurationProperties`. And `ConversionWebhookProperties.class` is `InfluoraApiApplication.java:107`, the last entry, **with no trailing comma**. |
| 40 | 4.7 | "after the workspace is saved (L201)" — L201 is the **wallet** save. Order is `saveAndFlush` L198, workspace **L199**, member L200, wallet L201; `UserCreatedEvent` L222. `brand_referrals` FKs `workspaces(id)`, so L199 is the real constraint. |
| 41 | 6.2 | `MeeraCopilotChat.tsx` is at `src/components/creator/`, **not** `creator/meera/`; that directory does not exist at HEAD and Phase B creates it only for `CreatorToolResultRenderer`. `CREATOR_TOOL_NAMES` is a `meera-api.ts` constant (Phase B §8.2), distinct from the Python tuple of the same name — both need extending. |
| 42 | 4.2 | `RestClient` is available and correct (Boot 3.3.5 / Spring 6.1; three production users), but `RestClient.create()` has **no timeouts** — the 5 s/15 s needs an explicit `ClientHttpRequestFactory` — and the client should be built lazily, per `MetaGraphApiClient:47-70`. The dev-mock branch also needs `Msg91EmailClient:142-147`'s outside-dev arm, which returns `false` rather than a false success. |
| 43 | 6.4 | `api-contracts.ts` has **no aggregate object** (17 bare `export const`s), so `outreachApi` registers nowhere — but `src/lib/api.ts` **does** have one (**L6354** in the working tree, L6313 at `143ca1e` — drift corrected 2nd pass), and §6.1's namespaces are unreachable as `api.creatorOutreach` without being added to it. `admin-console.tsx` routes are relative and the `path="*"` redirect must stay last. |

**Design defects flagged and corrected (4)**

| # | § | Defect |
|---|---|---|
| 44 | 3.7 / 4.6 | **`source = ON_PLATFORM_PITCH` is unwritable.** 3.7 declares it; 4.6 says a referral is recorded "only if the workspace was created after the pitch" — but E4 by definition matches a workspace that already exists. The two rules together make the value dead. Removed, so a later writer cannot populate it and silently credit a pre-existing brand as a referral. |
| 45 | 4.6 | Brand routes gated at "workspace member of any role", but `CampaignService.create` calls `requireRole(OWNER, ADMIN, MANAGER)` (`:152`). A MEMBER or VIEWER gets a 403 from inside `create()` after the pitch row was already read. Accept is gated at the same three roles; list and read stay open. |
| 46 | 4.6 | `CampaignIntentType.DIRECT` exists — but `IntegrationHealthService.requiresStoreIntegration` returns true for **DIRECT alone**, and `CampaignService.java:176` gates on it. A DIRECT campaign on a workspace with no connected store is refused at create time. Changed to STANDARD. |
| 47 | 4.11 | `GET /creator/outreach/alias` "creates on first call" is a **GET that writes**: it cannot be `@Transactional(readOnly = true)`, and every browser prefetch allocates. Split into `GET` (read or 404) and `POST` (allocate). The rate-limit note also understated the work and missed that GET buckets live in a **separate if-chain** (`AuthRateLimitFilter:300-317`) that `return null`s for anything unlisted — a GET bucket added to the POST section is dead code that never fires. |

**Hand-waves resolved by reading the code (5)**

| # | § | Resolution |
|---|---|---|
| 48 | 4.6 | "confirm the plain invite entry point with `graphify`" → `CreatorDiscoveryService.invite`, `:453`. See correction 2. |
| 49 | 3.8 | "find every `setWebsiteUrl` with `graphify`; there are few" → there are **none** on `Workspace`; every one is on `BrandProfile`. See corrections 22-23. |
| 50 | 3.8 / 4.9 | "if such a table exists" → `system_flags` does not exist. See correction 24. |
| 51 | 4.3 | "mirror `MeeraVoiceAiClient` for token minting" → `BrandSafetyServiceTokenService.mint(workspaceId)`, i.e. `scope=service` plus a `workspace_id` claim. Functional, but it puts a `users.id` into a claim named `workspace_id`, contradicting the deliberate bidirectional segregation documented at `service_token.py:44-50` and in `CreatorSuggestionServiceTokenService`'s javadoc, and contradicting Phase B §7.5's ruling for the creator audience. Both options and the tie-breaker (the spend key must stay the creator's `users.id`, per `chat.py:528`/`:804`) are now written into 4.3. **This one needs Kabir's signature, not an engineer's judgement.** |
| 52 | 4.6 | `CreatorDiscoveryService.invite` calls `requireDiscoverableProfile(...)`, so a creator with `is_discoverable = false` cannot be invited even though they sent the pitch. Flagged inline as a decision, not a QA discovery. |

### 13.2 Remaining risks

1. **The whole phase rests on Phase B shipping first, and on two of Phase B's own conditions.** Phase E consumes `MeeraDraft`, `CreatorBrief`, `BriefSource`, `DraftKind`, `CreatorToolScopes`, `CreatorToolName`, `CreatorMeeraToolController`, `creator_schemas.py`, `CreatorToolResultRenderer` and `getByProfileId`. If Phase B's `InfoBarrierTest` scan-root widening (its verdict condition 2) is skipped, §7's `InfoBarrierTest` row here asserts nothing and five Phase E classes that read floors go unchecked.
2. **The Docker gap is now load-bearing.** This phase produces three entity-to-column pairs that only `ddl-auto=validate` catches — `MEDIUMTEXT` on `body_text`, the nullable `open_marker`, and `website_domain`. `MeeraPhaseEBootValidationTest` skips without Docker, which this machine does not have. Staging boot is the only real gate; a green local suite proves nothing about the schema.
3. **`brand_profiles.website_url` coverage is the ceiling on E4.** Correction 23 makes the matcher viable, but the hit rate is still whatever fraction of brands completed analyse-site. Measure it on real data before promising creators an on-platform route; under roughly 30 percent, E4 is a nice-to-have rather than a feature.
4. **The referral token TTL is 30 days and cold outreach converts slower.** §10 Q6 imagines a brand joining "three weeks later"; real cold-email conversion routinely exceeds a month. After expiry the token silently fails and the referral is lost with no record and no fallback. Either lengthen the TTL for this token type or persist the pitch-to-token mapping so a late signup can still be attributed by hand.
5. **The inbound endpoint accepts up to 1 GB before it authenticates.** The correction in 4.8 names the fix but leaves the choice (proxy limit vs Mailgun `store()`) to Kabir. Until one lands, an unauthenticated permitAll endpoint will spool arbitrary attachments to disk on request.
6. **`TextSanitizer.sanitizePlainText` is a regex HTML stripper.** It is the house tool and correct for `body-html`, but it eats anything in angle brackets. Applying it to `body-plain` destroys `<partnerships@brand.com>` and `a < b`. The spec now says html-only; a future edit that "simplifies" by sanitising both will corrupt every reply that quotes an address.
7. **Twelve new event, template and permits touchpoints ship in one PR**, and there is no `NotificationEventPermitsTest` at HEAD to catch a missed handler. A permitted event with no `NotificationListener` handler compiles and silently notifies nobody. Write that test on day 1, not day 5.
8. **Two `CREATOR_TOOL_NAMES` constants now exist** — one in Python, one in `meera-api.ts` — plus a Java `CreatorToolName` enum and `CreatorToolScopes` strings. Four lists, one concept, no test that cross-checks them. Phase B created the divergence; Phase E widens it to twelve entries. Worth one drift test before it becomes fifteen.
9. **Tracked example-env files contain what appear to be live credentials, and §5.1 sends an engineer straight into one of them.** `influora-ai/env.example` is tracked, not gitignored, and its `ANTHROPIC_API_KEY` carries the full live `sk-ant-api03-` shape (108 chars) with `SARVAM_API_KEY` looking equally real, despite the file's own header saying otherwise. **Widened on the 2nd pass:** `influora-api/env.example` is in the same state and carries real-looking Cloudflare R2 values (account id, access key, and a 64-hex secret); and `.env.production`, though gitignored at `.gitignore:87`, is **still tracked**, so the ignore rule does nothing for it. §5.1 instructs adding `PERPLEXITY_API_KEY` to `influora-ai/env.example` — do not add a real value. Before this phase touches any of these: rotate the Anthropic, Sarvam and R2 credentials, `git rm --cached` the two `env.example` files and `.env.production`, and replace them with placeholder-only templates. Unrelated to Phase E's design; directly in its path, and it blocks §5.1's very first instruction.
10. **The Perplexity dependency is a new single point of failure with no fallback.** §5.2's `provider_unconfigured` / `provider_error` codes degrade the route, but E2 is the entry point for E3, E4 and E5 — if Perplexity is down or the key lapses, the entire outreach feature is dark with no alternative discovery path. Acceptable for a first cut; put it in the runbook rather than discovering it at 3 am.

### 13.3 Verdict

**Buildable as written: no.** An engineer following §4.6, §4.10, §3.9, §4.8, §5.1 or §5.2 literally would hit eleven compile errors, one `ddl-auto=validate` boot failure, two unique-key violations that fire only on the *second* user action of their kind, a `/readyz` change that 503s the entire AI service, an events webhook that binds nothing and silently drops every bounce and complaint, and a discovery run that cannot finish inside its own timeout. Three of these — the package-visible `@Transactional` sibling, the unenumerated positional-record arities, and the forced-tool result field — are defects Phase B's review had already found and written down. Repeating them is the strongest signal in this review: §13 of a reviewed spec is not being read forward into the next one.

**Buildable as corrected: yes**, on four conditions:

1. **Phase B lands first and complete**, including its own verdict conditions 1 and 2. Phase E has no standalone build.
2. **Kabir rules on the token scope (correction 51) before day 3**, in writing, in `wiki/tech/`. Both options work; picking one silently is what produces the audit finding.
3. **The tenth migration (`outreach_runtime_state`, 4.9) ships with the other nine.** Without it, `PUT /ceiling`, the health job's pause and the multi-replica daily ceiling have no store — and the kill switch that protects the sending domain is the last thing that should be discovered missing.
4. **Staging boot with `ddl-auto=validate` gates the merge**, since Docker is unavailable locally (risk 2). No exceptions for "the tests are green".

The four items in §12 remain genuinely open and correctly flagged. Risk 5 (inbound body size) and correction 52 (discoverability on accept) are the two that need a decision rather than an implementation; everything else in §13.2 is a risk to carry, not a blocker.

---

## 14. Priya review, second pass: compatibility with HEAD `143ca1e` and the working tree

**Reviewer:** Priya (CTO). **Date:** 2026-09-04. **Baseline:** HEAD `143ca1e` on `fix/f0390-money-flags-build-pipeline`, **plus 41 uncommitted changed files** (`git diff --stat HEAD`: 5,632 insertions, 131 deletions) and 44 untracked. **Method:** §13 checked this spec against source and is unchanged in its conclusions. This pass asks three different questions — does the spec still hold against a *newer* commit than the one in its header, does it survive the *uncommitted* work sitting on the branch, and what would an engineer still have to invent. ~90 of §13's file:line claims were re-verified; roughly 85 are exact, and the misses are recorded below.

**The single most important finding is in §14.2:** this document's line citations were never against `8c7b18b`, and they are not against `143ca1e` either. They match the **working tree**. That is fine — it is where the build will happen — but the header said otherwise and every future reader would have been off by one to five lines in four separate files.

### 14.1 Corrections applied in this pass

| # | § | Was | Is |
|---|---|---|---|
| 53 | header | Baseline `8c7b18b`, status "ready for Priya review" | Baseline `143ca1e` **plus the uncommitted working tree**, with the four line-drift pairs named. `8c7b18b` is HEAD~1. |
| 54 | 4.9 | Health-job row still paused via "a `system_flags` row or a Redis key" | Sets `outreach_runtime_state.paused`. The row §4.9's own correction 24 introduced, twelve lines above the sentence that ignored it. |
| 55 | 11 | "`GET /creator/outreach/alias` allocates on first call" | `POST` allocates, `GET` 404s `ALIAS_NOT_ALLOCATED`. §11 still described the writing GET that correction 47 removed. |
| 56 | 11 | "A failed check returns 406" | **401** on a bad signature; 406 stays for deliberate refusals. §11 contradicted §4.8 and correction 37. |
| 57 | 11 | "up to twelve site analyses on Gemini" | **Five**, capped and concurrent (correction 25). The 25-40 rupee estimate is flagged as an upper bound costed at twelve. |
| 58 | 6.2 | Decline-template textarea "max 600" | **500** (`DealDtos.RejectRequest`). §6.2 still carried the 600 that §4.10 corrected. |
| 59 | 6.2 | `MeeraSettingsSection.tsx` treated as new surface | **It already exists at HEAD** — 716 lines, rendered from `creator-settings.tsx:462`, already shipping the `approval_level` select. E6 is an edit, and it must reword the L455 copy "Phase A is conversational only — Meera does not send or decline anything on your behalf yet", which level-2 auto-decline makes false. |
| 60 | 8 | Day 1 lists "migrations 3.1 to 3.9" — nine | All **ten**, including `V20260920100900__outreach_runtime_state.sql`. §4.9 said "update section 7 and section 10"; §8 was the third place and was missed. `NotificationEventPermitsTest` moved to day 1 per §13.2 risk 7. |
| 61 | 4.11 | "Returns `ApiResponse` envelopes … follow the majority convention" | **Inverted.** Counted across all 19 `Admin*Controller` classes in `com.influora.web`: **16 contain zero `ApiResponse<`**. Only `AdminEmailController`, `AdminModerationController` and `AdminSupportController` use it, one method each. `AdminCreatorAgentController` — held up as the exception — is with the majority. Bare DTOs. |
| 62 | 4.6 | `emailOf` cited at `NotificationListener:159-173` | That range is `resolveJoinNotificationEmail`, a different helper with an owner-then-admin fallback specific to `ConnectedCreatorJoinedEvent`. `emailOf` is **`:145-148`**. |
| 63 | 4.10 / 13.1 #4 | "all six `DealMessageRepository` finders are collaboration-scoped" | **Five of six.** `findFirstMessageTimestampsBySender()` (`:52-57`) is a global, parameterless `GROUP BY` over the whole table — not a narrower form of the needed query, a different one. |
| 64 | 5.1 / 13.1 #15 | `/readyz` quoted as `ready = all(keys_loaded.values())` at `main.py:212` | `main.py:**213**`, and the full expression is `ready = all(keys_loaded.values()) and redis_ok and cap_scope.safe`. Conclusion unchanged — `keys_loaded` is still `all()`-ed — but a truncated quote invites an engineer to "fix" it by adding a third conjunct. |
| 65 | 0 / 5.2 | `wrap_untrusted`, `record_spend` given without module paths | `wrap_untrusted` = `app/prompt/untrusted.py:47`; `wrap_untrusted_scrape` = `app/prompt/assembler.py:849`; `strip_active_content` = `app/services/analyze_site.py:78`; `record_spend` = **`spend_tracker.py:402`, not `gate.py`** (only `check_spend_gate` is `gate.py:44`). Four helpers, four files, one of them named next to the wrong one. |
| 66 | 6.1 / 6.4 / 13.1 #43 | `src/lib/api.ts` aggregate at L6313 | **L6354 in the working tree** (L6313 at `143ca1e`; the branch's uncommitted +51 lines land at hunks 3587 and 3613, above it). Grep `export const api = {`. |
| 67 | 6.2 | `creator-layout.tsx:72-115` for `navGroups` | **`:91-115`** is the array; `:72-81` are the TypeScript interfaces that type it. The `'Manage'` group at `:104-114` was correct. |
| 68 | 6.2 | `/:handle` catch-all at `App.tsx:825` | **`:830` in the working tree** (`:825` at `143ca1e`; the uncommitted F-0459 export change adds 5 lines above it). The spec's own rule — cite the catch-all, not a number — is now the operative instruction. |
| 69 | 4.3 | Weekly run limit = `COUNT(DISTINCT discovery_run_id)` on `outreach_brand_candidates` | **Undercounts, and the direction favours the abuser.** A run yielding zero surviving candidates — everything dropped by the DPDP gate, or Perplexity returning nothing — writes no candidate row, so its run id never exists and the run is invisible to the limit. Python bills it regardless (§5.2 step 1 reserves, step 7 settles). A creator can burn their whole monthly AI cap on unlimited zero-yield runs while this gate reports zero used. A `COUNT(DISTINCT …)` over an *output* table cannot rate-limit an *input*. |
| 70 | 4.8 | Events webhook replay defence unspecified | Inbound pairs its in-memory token set with `uk_oim_provider_msg` as "the durable arbiter". Events names no equivalent, and a bounded in-memory set is **per-replica** — two instances behind the LB both accept the same retried `complained` event and suppression plus the health job's complaint rate double-count, which is the exact input the kill switch thresholds on. A unique constraint is specified inline, with the `save()`-vs-`saveAndFlush()`-in-a-catch trap called out. |
| 71 | 13.2 #9 | Credential risk scoped to `influora-ai/env.example` | Widened to `influora-api/env.example` (real-looking Cloudflare R2 account id, access key, 64-hex secret) and `.env.production` (gitignored at `.gitignore:87` but **still tracked**, so the rule does nothing). No values reproduced here. |

### 14.2 Working-tree collision matrix

`git status --short`: 41 changed files staged or unstaged, 44 untracked. Of the fourteen files Phase E or Phase B edits, **four** are touched by the in-flight work. **None of the four is a semantic conflict.** All four are line drift, and one carries a behaviour change Phase E must know about but does not collide with.

| File | Phase E/B needs it for | In-flight change | Verdict |
|---|---|---|---|
| `CampaignService.java` | §4.6 E4 accept → `create(principal, req)` | F-0503: `+1` import line at L6, `requireFundedEscrow` call in **`patch()`** at L346-368, private helper at L693-712 | **Line drift + a downstream precondition, not a conflict.** `create()` is untouched; it moves 145 → **146**, `requireRole` 151 → **152**, `requiresStoreIntegration` 178 → **179** — which is exactly what this spec cites, confirming the citations are working-tree-based. The behaviour note: the new gate fires only on a PATCH transition to ACTIVE, so an E4-created campaign now lands DRAFT and cannot go live until the brand secures funds. That is correct and desirable, but §4.6 must say so or the brand's first pitch-accept looks broken. |
| `DealService.java` | §4.10 E6 `rejectAsCreatorAgent` wrapping `reject` | F-0476 (`requireWithinRemainingBudget` rewrite + new `committedValue`, L1725-1795), F-0653 (`resolveCurrentContract` at ~L2030), one removed import | **Line drift only.** Every hunk is ≥ L1725; `reject` is untouched and sits at **L398** in the tree (L399 at `143ca1e`) — again matching the spec. Secondary note for E4, not E6: a rate-less collaboration is now valued at `budgetMax` rather than zero on accept, so an E4 pitch-accept must keep creating its **own fresh campaign** (as §4.6 already says). Reusing an existing campaign that already has one commitment would now fail `AMOUNT_EXCEEDS_BUDGET`. |
| `src/lib/api.ts` | §6.1 `creatorOutreach`, `brandPitches`, `referrals` namespaces + the aggregate | +51 lines at hunks 3587 and 3613 (`me`, `payments`) | **Line drift only.** The aggregate moves 6313 → **6354**. Correction 66. |
| `src/App.tsx` | §6.2 three new routes | F-0459: `readAuthToken` and `CreatorProtectedRoute` exported, +5 lines | **Line drift only, and mildly helpful** — `CreatorProtectedRoute` is now exported, which the new `/creator/outreach` route can import in tests. `/:handle` moves 825 → **830**. Correction 68. |

**Untouched by the in-flight work** (all confirmed absent from `git status`), so every §13 citation in them stands as written: `AuthService.java`, `Workspace.java`, `SecurityConfig.java`, `AuthRateLimitFilter.java`, `NotificationListener.java`, `application.yml`, `InfluoraApiApplication.java`, `CreatorAgentDtos.java`, `MeeraContextDtos.java`, `creator-settings.tsx`.

**Migrations.** No migration is added or modified in the working tree — tracked or untracked. The highest dated file is still `V20260903170000__creator_agent_preferences_timezone_currency.sql` (119 total), so the whole `V20260920*` block this phase reserves is clear, and `flyway.out-of-order: true` is unchanged. **No collision.**

**One process risk that is not a file collision.** The branch carries 5,632 uncommitted insertions across money-path services (`EscrowService`, `ContractService`, `AffiliateSettlementWriter`, `MoneyDtos`) plus a `ProvisionSuperAdminRunner` and a `scripts/provision-super-admin.sh`. A recorded property of this repo is that **another session edits it concurrently and has silently clobbered in-progress work three times**, including in `DealService.java`. Phase E is a seven-day build across three engineers touching two of those same files. Land or stash this wave before day 1; do not start Phase E on top of an uncommitted 5,600-line diff.

### 14.3 Phase B re-validation at `143ca1e`

Phase B was baselined at `8c7b18b`. The `8c7b18b → 143ca1e` commit is large (92 files, +12,925) and touches `AuthService`, `CreatorOnboardingService`, `CreatorProfileService`, `UserDtos`, `SupportDtos`, `api.ts` (+88) and `creator-settings.tsx`. Every Phase B fact Phase E leans on hardest was re-checked. **Nothing was invalidated.**

| Phase B fact | Phase E depends on it in | Status at `143ca1e` + tree |
|---|---|---|
| `PreferencesResponse` — **4** construction sites (Phase B §3.10) | §3.9, correction 6 | **Holds.** 5 raw hits; one (`NotificationController.java:262`) is the unrelated `notification.NotificationDtos.PreferencesResponse`. The creator-agent record has 4: `CreatorAgentPreferencesService`, `CreatorAgentControllerTest`, `CreatorMeeraControllerTest`. Untouched by `143ca1e`. |
| `UpdatePreferencesRequest` — **9** construction sites | §3.9, correction 6 | **Holds.** Exactly 9: `CreatorAgentPreferencesServiceTest` ×7, `CreatorAgentControllerTest` ×2. |
| `CreatorContextResponse` arity **27** | §3.9, §5.3, the drift test | **Holds.** `MeeraContextDtos.java:166`, unchanged by `143ca1e`. |
| `assembleCreatorContext` | §3.9 | **Holds**, and note it is **`private`** at `MeeraContextService.java:219`, reached only through the audience branch at `:153`. Phase E adds `outreach_enabled` inside it; there is no public entry point to call instead. |
| `InfoBarrierTest` scan-root widening | §7, §4.10, §13.2 risk 1 | **Still not shipped**, as expected — `InfoBarrierTest.java:69` scans `com/influora/service/meera` and `com/influora/web` only. `143ca1e` did not touch it. §13.2 risk 1 is unchanged and still load-bearing: `com.influora.job` is outside today's scan, so `MeeraAutoDeclineJob` would not be checked. |
| `creator-settings.tsx` insertion points | §6.2 | **Holds.** `143ca1e` edited this file (phone normalisation at L50, L175-206, L747), but the anchors survived: `<CreatorLayout>` **L448**, `<ConnectedAccounts />` **L458**, `<MeeraSettingsSection />` **L462**. |

**Verdict on B:** the newer commit invalidated **none** of Phase B's Phase-E-facing facts. It was overwhelmingly phone verification, support tickets and tests. The Phase B spec's own status ("ready to build, not built") is unchanged, and none of its symbols exist at `143ca1e` — re-confirmed for `CreatorBrief`, `MeeraDraft`, `DraftKind`, `BriefSource`, `CreatorToolScopes`, `CreatorToolName`, `CreatorMeeraToolController`, `CreatorBriefService`, `RoutineReplyService`, `MeeraSendLog`, `getByProfileId`, `creator_schemas.py`.

### 14.4 Completeness gaps, with a recommended default for each

Everything below is something an engineer would otherwise have to invent. Each has a default; the three marked **RULING** need a name on them before the day they are hit.

**(1) Token scope for `outreach_discover` — RULING, Kabir, before day 3.** Unchanged from correction 51: two incompatible precedents exist and §5.2 silently picks the one that contradicts the recorded doctrine.
> **Recommended default: option (b), creator scope.** Inject `CreatorSuggestionServiceTokenService`, `mint(profile.getId())`, body carries `creator_profile_id`, `ENDPOINT_SCOPES["outreach_discover"] = (SCOPE_CREATOR,)`, Python uses `verify_creator_token` offloaded through `anyio.to_thread.run_sync`. Reason: `service_token.py:53-69` and `CreatorSuggestionServiceTokenService`'s javadoc both state the segregation is deliberate and bidirectional, Phase B §7.5 already ruled the creator side for the sibling route, and option (a) puts a `users.id` into a claim literally named `workspace_id` on a service-scoped token. Option (a) is cheaper by one class and is defensible, but it is the one that generates an audit finding, and the cost of (b) is a single injected bean. **The spend key stays the creator's `users.id` either way** (`chat.py:528`, `:804`) — that half is not open.

**(2) Inbound 1 GB pre-authentication spool — RULING, Kabir, before the inbound route is pointed at staging (day 4).** `application.yml:62-67` sets multipart at 500 MB / 1 GB, and `/webhooks/outreach/inbound` is `permitAll`, so an unauthenticated caller can make the app spool ~1 GB to disk before the HMAC check runs.
> **Recommended default: the proxy limit, as the primary control.** `client_max_body_size 25m` on the two `/webhooks/outreach/*` locations in nginx — one line, rejects with a 413 before Spring allocates a byte, and it cannot be bypassed by a bug in the handler. Mailgun's own inbound message size ceiling is comfortably under that for the `forward()` route. Mailgun `store()` is the right **second** step, not the first: it changes the payload to a URL the app fetches back, which removes the body from the request entirely — but it costs a round trip on every reply and needs its own SSRF-safe fetch path, so it is a follow-up, not the fix that has to land before day 4. Add a Spring-side `spring.servlet.multipart.max-request-size` override scoped to these endpoints as defence in depth if it can be done without loosening the global 500 MB the upload path needs.

**(3) Accept-on-pitch for a non-discoverable creator — RULING, product (Swapnil or Tejas), before day 5.** Correction 52: `CreatorDiscoveryService.invite` calls `requireDiscoverableProfile` (`:456`), so a creator with `is_discoverable = false` who sends an E4 on-platform pitch cannot be invited to the campaign their own pitch produced. The brand taps "Accept and start a deal" and gets a refusal naming a setting the brand cannot see.
> **Recommended default: accept bypasses the discoverability gate.** Add an explicit sibling path — `inviteFromAcceptedPitch(...)` — that runs every other gate `invite` runs (`recordInviteOnTimeline` included, the F-0291 fix) and skips only `requireDiscoverableProfile`. Rationale: `is_discoverable` is a **marketplace-listing** preference, meaning "do not surface me in brand search". It is not a consent signal about a conversation the creator started themselves. Refusing here punishes the creator for a setting that has nothing to do with the action. The alternative — surfacing "turn on discoverability to accept" to the creator before they pitch — is defensible but adds a step to the funnel Phase E exists to build. Do **not** silently flip `is_discoverable` to true; that is a settings change the creator did not make.

**(4) `EmailTemplateRegistry.render` and the two new creator-facing templates — no new code path needed.** The mechanism already exists: `NO_UNSUBSCRIBE_FOOTER` (`EmailTemplateRegistry.java:394-395`, currently `auth.otp`, `otpman`, `auth.password_reset`) and the `showUnsubscribe` branch at `:411`.
> **Recommended default: add neither new key to `NO_UNSUBSCRIBE_FOOTER`.** `creator.outreach_reply` and `brand.creator_pitch_received` are ordinary notifications a user may legitimately opt out of, so they should carry the footer like every other `SPECS` entry. The set exists for security/account-access mail a user cannot opt out of, which these are not. Note the distinction that matters: the **pitch email itself** never goes through this registry at all — it is sent by `OutreachMailer` through Mailgun with its own `List-Unsubscribe` and `List-Unsubscribe-Post` headers (§2.2, §4.2), which is a different unsubscribe system with a different suppression store. Two unsubscribe mechanisms now exist in the product; §11's deliverables checklist should say so explicitly.

**(5) `NotificationService.notify` idempotency key for the two new events — works, with one trap.** The key is `eventType + ":" + entityId + ":" + userId` (`NotificationService.java:181-183`), enforced by `email_outbox.idempotency_key` and checked at `:137`.
- `brand.creator_pitch_received` with `entityId` = the pitch id: **correct**. One pitch, one notification, forever.
- `creator.outreach_reply` with `entityId` = the **inbound message id**: **correct, and the pitch id would be wrong.** The key dedupes permanently, so keying on the pitch would collapse a brand's second and third replies into the first and the creator would never be told. Use the inbound row id.
> **The trap is not the key, it is the two event-type sets.** `notify` branches on `EMAIL_ONLY_EVENTS` and `IN_APP_ONLY_EVENTS` (`:83-89`) and then on `isUnsubscribed(userId, eventType)` (`:169-179`). A new `eventType` in neither set gets **both** channels by default, and `isUnsubscribed` returns false for an event with no `email_preferences` row — so both new events will email on first fire whether or not anyone intended it. Decide the channel per event explicitly and add the row to whatever seeds email preferences, rather than inheriting the default. §7's `NotificationEventPermitsTest` should assert channel membership too, not only that a handler exists.

**(6) HMAC verifier reuse — copy the algorithm, not the class.** `integration/razorpay/WebhookSignatureVerifier.java` is a `@Component` constructor-injected with `RazorpayProperties`, and its `verify(rawPayload, signatureHeader)` hashes the **payload**. Mailgun hashes `timestamp + token` and keys on a different secret. The contract does not match and the bean is bound to the wrong properties class.
> **Recommended default: a new `com.influora.integration.mailgun.MailgunSignatureVerifier`**, lifting `computeHmac` verbatim (HmacSHA256 → lowercase hex) keyed on `OutreachProperties.webhookSigningKey`, with input `timestamp + token`, and the same fail-closed-on-missing-secret branch. **For the comparison use `MessageDigest.isEqual`, not Razorpay's hand-rolled `constantTimeEquals`** — three existing precedents (`InviteTokenService:182`, `UnsubscribeTokenService:72`, `InternalRequestVerifier:122`) versus one, it is the JDK primitive, and the hand-rolled version leaks length through its early `a.length() != b.length()` return. §4.8 already says "reuse `InviteTokenService`'s `MessageDigest.isEqual` shape"; this confirms it and rules out the closer-looking Razorpay class an engineer would find first.

**(7) `IdempotencyService.executeOnce` for `approveAndSend` — fits, with two constraints.** Signature: `<T> T executeOnce(String idempotencyKey, String workspaceId, String scope, Supplier<T> action)` (`:116`), plus a 5-arg overload (`:131`) and `runExclusive` (`:266`).
> **Recommended default: use it, scoped `outreach_pitch_send`, with `workspaceId` = the creator's `users.id`** — the same disjoint-ULID reasoning §4.3 applies to the token claim, and it must be documented in the call, because the parameter name will otherwise read as a bug to the next person. **Two constraints.** (a) The class javadoc (`:42-53`) records that these methods are `@Transactional` with REQUIRED propagation and join the caller's ambient transaction — that is the intended shape, so `approveAndSend` must itself be transactional and must not self-invoke. (b) **Do not put the Mailgun HTTP call inside it.** §4.9 already has `OutreachSendJob` picking up APPROVED rows 60 seconds later; that is the correct division — `executeOnce` guards the APPROVED row insert (the thing that must happen once), and the job does the network call with its own `provider_message_id` uniqueness as the second line of defence. An HTTP call inside an idempotency-guarded transaction is how a slow provider turns into a lock-held-across-the-network incident.

**(8) `ddl-auto=validate` against §3's migrations — three house patterns, one real failure mode.** Compared against the two nearest precedents, `V73__creator_agent_preferences.sql` and `V20260903130000__admin_email_send_lock.sql`.
- **`TINYINT(1)` ↔ `boolean`: style, not a failure.** The house pattern is `BOOLEAN NOT NULL DEFAULT false` (`V73:24`, `represented`). `TINYINT(1)` appears **once in 119 migrations** (`V51__trendspark.sql:33`). MySQL stores both as `TINYINT(1)` so `validate` accepts either against a Java `boolean` — but §4.9's `outreach_runtime_state.paused TINYINT(1)` should be changed to `BOOLEAN` to match the other 118 files. Same for any other flag column in §3.
- **`DATE` ↔ `LocalDate`: a real `validate` failure if got wrong.** `DATE` is well established (`V14:5`/`:7` `cycle_start`, `last_reset`; `V54:80` and `V58:19` `period_start`) and always maps to `LocalDate`. §4.9's `counter_date DATE` **must** be a `LocalDate` field. An `Instant` or `LocalDateTime` there fails `validate` at boot — and per this repo's own recorded lesson, Mockito tests do not catch it, so it surfaces only on staging, which §13.2 risk 2 already says is the only real gate.
- **`TIMESTAMP … DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`: house pattern (29 migrations use it), but pick one writer.** `V73` uses the *other* shape — bare `TIMESTAMP NOT NULL` with no default, Hibernate writing `created_at`/`updated_at` from the entity. §4.9's block uses the DB clause. Both are fine; **combining them is not.** If the DDL clause stays, the entity field needs `@Column(insertable = false, updatable = false)` or Hibernate and MySQL both write `updated_at` and the value Hibernate flushed is silently replaced. State which shape each of the ten tables uses, in the migration comment, on day 1.

### 14.5 Compatibility score by scope

| Scope | Score | Why |
|---|---|---|
| **E1** Outreach infrastructure (domain, alias, mailer, suppression, bounces) | **COMPATIBLE** | Consumes no Phase B symbol. Its only shared files — `SecurityConfig`, `AuthRateLimitFilter`, `application.yml`, `InfluoraApiApplication`, `creator-settings.tsx` — are all untouched by the working tree. Blocked in practice only by §5.1's instruction to edit a credentialed `env.example` (§13.2 risk 9 as widened) and by the events-webhook durable-replay gap now written into §4.8. Both are fixes, not redesigns. |
| **E2** Brand discovery | **INCOMPLETE** | Compatible with the code, but two things are genuinely unspecified rather than merely wrong: the token scope (§14.4 item 1, Kabir's) and the weekly run counter, which as written is derived from an output table and does not count zero-yield runs (correction 69). The counter needs either a new `outreach_discovery_runs` table or a sentinel row, and neither is in §3's ten migrations. Also needs Phase B for the tool surface. <!-- §15 supersedes this grade in part: §15.3 adds `V20260920101000__outreach_discovery_runs.sql`, which closes the counter gap (§14.6 condition 5). The token-scope RULING (§14.4 item 1) is still open, and §15.3 adds a SECOND, DIFFERENT auth path — Python→Java `POST /internal/outreach/brand-facts` — which §14.4 item 1 does NOT cover (§16.1 C5). E2 stays INCOMPLETE, now on the ruling and the new spend arithmetic in §16.1 C19, not on the counter. --> |
| **E3** Pitch, send, follow-up, reply routing | **COMPATIBLE-AFTER-PHASE-B** | The heaviest Phase B consumer — `MeeraDraft`, `CreatorBrief`, `BriefSource`, `DraftKind`, `CreatorToolName`, `CreatorToolScopes`, `CreatorMeeraToolController`, `RoutineReplyService`, `MeeraSendLog`. None exists at `143ca1e`. No working-tree collision. Fully specified once §13's corrections and §14.4 items 6 and 7 are applied. |
| **E4** On-platform route | **COMPATIBLE-AFTER-PHASE-B** | Not a conflict with the in-flight `CampaignService`/`DealService` work, but the closest call in the document. Two behaviour notes must be written into §4.6 before build: (a) F-0503 means an E4-created campaign lands DRAFT and cannot go ACTIVE until the brand secures funds; (b) F-0476 means the accept must keep creating its **own fresh campaign**, because a rate-less collaboration is now valued at `budgetMax` and a second commitment on a shared campaign would fail `AMOUNT_EXCEEDS_BUDGET`. Also carries the §14.4 item 3 ruling and §13.2 risk 3 (the `brand_profiles.website_url` hit rate ceiling). |
| **E5** Referral attribution | **COMPATIBLE** | `AuthService`, `Workspace`, `brand-register.tsx`, `brand-login.tsx` all untouched by the working tree; every §13 citation in them stands. `App.tsx` is line drift only. `ReferralTokenService` mirrors `InviteTokenService`, which exists. §13.2 risk 4 (30-day TTL vs cold-email conversion) is a carried risk, not a gap. |
| **E6** Level-2 auto-decline | **COMPATIBLE-AFTER-PHASE-B** | `DealService.reject` is untouched by the in-flight hunks (all ≥ L1725) and sits at L398 in the tree. Needs Phase B's `MeeraSendLog` and `getByProfileId`, and needs Phase B's `InfoBarrierTest` widening or its §7 row asserts nothing. Newly: E6 edits a live 716-line `MeeraSettingsSection.tsx` rather than a Phase B one, and must reword its L455 copy (correction 59). |
| **E7** Manager seat | **INCOMPLETE — by design** | §9 is design-only pending Swapnil. Unchanged. Do not start. |

### 14.6 Verdict, second pass

**Compatible with the code as it exists today: yes, and more cleanly than expected.** Of the fourteen files Phase E and Phase B edit, only four are touched by the branch's 5,632 uncommitted lines, and **all four are line drift** — not one semantic conflict. No migration collides; the entire `V20260920*` block is clear. Phase B lost nothing to `143ca1e`, which was a phone-verification and support-ticket commit that happened to be large. The one real correction to the baseline is that this document's line numbers were never against `8c7b18b` at all — they match the working tree, which is where the build will happen, so the fix was to say so rather than to renumber.

**Complete enough to build: not quite, and the shortfall is small and named.** §13 left four conditions; they all still stand. This pass adds three, and none of them is a redesign:

5. **The weekly discovery-run counter needs a table.** Correction 69 — as specified it cannot count a zero-yield run, and Python bills those. This is the only place in the document where a limit does not limit. An eleventh migration, or a sentinel row; either is an hour's work, but it is not currently in §3 and it is a spend control.
6. **The events webhook needs the same durable replay defence inbound already has.** Correction 70 — a per-replica in-memory set double-counts the exact complaint events that arm the kill switch.
7. **Land or stash the branch's uncommitted wave before day 1.** Not a spec defect. This repo has a recorded history of a concurrent session clobbering in-progress edits in these very files, and Phase E is seven days of three engineers across two of them.

And one item that is not a condition but should not wait for one: **rotate the credentials in the two tracked `env.example` files and untrack `.env.production`** (correction 71). §5.1's first instruction is to edit one of those files.

Six of this pass's nineteen corrections — the alias GET, the 406, the twelve site analyses, the 600-character textarea, the nine migrations, the `system_flags` health job — are places where **§13's own corrections were applied in one section and not in the section that repeated them.** That is the same failure mode §13's verdict named about Phase B: a review is written, and the forward propagation into the rest of the document does not happen. Before build, one person should read this spec end to end **once**, looking for nothing except sentences that disagree with each other. That is a two-hour job and it is the highest-yield two hours available.

**Buildable as corrected: yes**, on the four conditions in §13.3 plus the three above, and with the three §14.4 rulings named before the days they land — token scope by day 3, inbound body size by day 4, accept-on-pitch discoverability by day 5.

---

## 15. Product pass (2026-09-04): making outreach worth doing

**Why this section exists.** §13 and §14 established that the plan compiles and is compatible with the code. This pass answers a different question the owner asked: *is it good for creators and for the platform's standing with brands?* Five objections were raised against Phase E as designed, and each is resolved below with a mechanism, not a sentence. Where a fix changes an earlier section, that section carries a `<!-- §15 supersedes -->` pointer, so the contradiction pattern §14.6 warned about does not recur. **Nothing here changes §13/§14's conditions; it adds two migrations, six refusal codes, eight config keys and one sequencing rule.** Decisions that are Swapnil's are marked **RULING** with a default the build proceeds on.

The five objections, in one line each: (1) cold email is the channel brands hate and the reply maths gives most creators nothing for weeks; (2) AI-drafted pitches converge into a recognisable "via Influora" shape that brands filter and that endangers the sending domain; (3) creators pay per discovery run and may earn nothing; (4) web-search discovery surfaces large agency-managed brands that never answer, so the signal is weakest for the smallest creators; (5) lead generation is not the moat — payment safety and clean briefs (Phase B) are.

### 15.1 Volume and expectations: warm before cold, one pitch per brand per month, live reply rate

**(a) Warmth ladder.** Every candidate gets a `warmth` band computed in `OutreachDiscoveryService.run` step 5, and the shortlist is ranked by it before fit score:

| Band | Meaning | Source | Channel |
|---|---|---|---|
| `W0_ON_PLATFORM` | brand already has an Influora workspace | `OnPlatformBrandMatcher` (E4) | in-platform pitch, never email |
| `W1_DEMAND_LOOKALIKE` | resembles brands currently hiring the creator's niche on Influora | server-side lookalike seed from `campaigns.end_brand_name` of ACTIVE campaigns whose `end_brand_category` is in the creator's categories, last 90 days — **the names never leave the server**, see the correction below | email if COLLAB_PAGE or ROLE_EMAIL |

<!-- PRIYA (3rd pass, 2026-09-04): the W1 row asserted two things without a citation and BOTH are wrong. Corrected in the row above and here.

 (1) DISCLOSURE — the claim "end-brand names on ACTIVE campaigns are already visible to creators in listings, so nothing new leaves the server" is FALSE. The creator-facing DTOs carry no end-brand field at all: `CreatorCampaignDtos.CreatorCampaignListItem` (L33-47) and `CreatorCampaignDetailResponse` (L50-67) expose `BrandSummary(workspaceId, name, logoUrl, verificationStatus)` (L28) — the WORKSPACE, which for an agency is the agency. `endBrandName`/`endBrandCategory` appear ONLY on the brand-side `CampaignDtos` (CreateRequest L93, PatchRequest L127, CampaignResponse L158). Sending an end-brand name to another creator, or to Python, or onto a card, would be the FIRST disclosure of one brand's commercial relationship to unrelated creators — and it is exactly the agency-transparency data Phase A added. RULE: the names are read inside `OutreachDiscoveryService` and used only to build query TEXT; what crosses to Python is the derived descriptor ("brands similar to established {category} advertisers in India"), never a name, and `warmth = W1_DEMAND_LOOKALIKE` is a band, not a provenance. Nothing named is ever returned in `CandidateItem` or logged.

 (2) SIGNATURE — `findRecentActiveEndBrandNamesByCategoryIn(...)` is not a derivable query name ("Recent" and an `EndBrandNames` projection are not Spring Data keywords). It must be an explicit `@Query`, and the 90-day cutoff must name a column: `Campaign` has `createdAt`/`updatedAt` as `Instant` (L129, L132) and `startDate`/`endDate` as `LocalDate` (L48, L51) — use `createdAt`. `CampaignStatus.ACTIVE` is confirmed (`CampaignStatus.java`). `CampaignRepository` has status finders to mirror for style (`findByStatus`, `findByCampaignTypeAndStatus`) but NO category finder — `end_brand_category` is free-text `VARCHAR(100)` (Campaign L125-126) with no enum and no index. Write:

     @Query("select distinct c.endBrandName from Campaign c "
          + "where c.status = :status and c.endBrandCategory in :categories "
          + "and c.createdAt > :since and c.endBrandName is not null")
     List<String> findRecentActiveEndBrandNames(@Param("status") CampaignStatus status,
                                                @Param("categories") Collection<String> categories,
                                                @Param("since") Instant since,
                                                Pageable pageable);

 and add a THIRTEENTH migration, `V20260920101200__campaigns_end_brand_category_idx.sql`:
     ALTER TABLE campaigns ADD INDEX idx_campaigns_end_brand_cat_status (end_brand_category, status, created_at);
 `campaigns` is a LIVE table, so unlike 3.1/3.2/3.3 this cannot be folded into an unbuilt file. -->

| `W2_ASKED_TO_BE_PITCHED` | brand publishes a collaboration page | `contact_route == COLLAB_PAGE` | email |
| `W3_COLD` | role address found by search only | `contact_route == ROLE_EMAIL` without a collab page | email, **capped** |

**Per-run cap on cold:** at most **2 of 5** shortlist rows may be `W3_COLD`. Fill the rest from W0-W2 or return fewer than five and say why. `BrandCandidate` gains `warmth` (frontend `BrandShortlistCard` shows it as the first badge). `OutreachBrandCandidate` gains column `warmth VARCHAR(24) NOT NULL` — in the same migration as 3.2, since nothing is built yet.

**(b) Platform-wide brand cooldown.** A brand domain receives **one pitch per 30 days from the whole platform**, not one per creator. `OutreachPitchService.approveAndSend` refuses **`BRAND_RECENTLY_PITCHED`** with `next_available_at` when `outreach_brand_facts.last_pitched_at > now - domain-cooldown-days` for the candidate's `website_domain`.

<!-- PRIYA (3rd pass, 2026-09-04): the check as first written — "no `outreach_pitches` row ... whose candidate has the same `website_domain`" — is a cross-creator join through a creator-scoped table. `outreach_brand_candidates` (3.2) is scoped to one creator, so a PLATFORM-wide cooldown has to join `outreach_pitches -> outreach_brand_candidates` across every creator's rows on an unindexed `website_domain`, on the hot send path. Since §15.3 introduces `outreach_brand_facts` keyed by domain with `last_pitched_at`, read the cooldown straight off that row: one primary/unique-key lookup instead of a fan-out join. `outreach_pitches` remains the audit trail; the facts row is the gate. Keep `idx_op_sent_at` for the health job's windows, but it is not what serves this check. -->
 Discovery surfaces the same fact before the creator drafts: `CandidateItem.cooldown_until` (computed, no column) and Meera says *"another creator reached this brand this month; I can give you a note for LinkedIn instead"*. Query needs `idx_op_sent_at` on `outreach_pitches (sent_at)` — add to 3.3. This single rule is what stops a brand's inbox from receiving ten Influora pitches in a week, and it forces the platform to send its best-fit creator first rather than its fastest.

**(c) Live reply rate, shown before money or effort is spent.** Replace the hard-coded "two to five in a hundred" in §5.3's persona with a number the platform measures. `MeeraOutreachHealthJob` already computes reply rate over 7 d; extend it to write `reply_rate_30d_bp INT NOT NULL DEFAULT 0` and `reply_sample_30d INT NOT NULL DEFAULT 0` (basis points; sample = pitches SENT in the window) onto `outreach_runtime_state` (fold into 4.9's migration). `PitchDraftResponse` and the `draft_pitch` tool result gain `reply_rate_30d_bp`, `reply_sample_30d`, and the creator's own `my_reply_rate_bp` / `my_sample`. Rendering rule (Java renders the numbers, rule 0.4): sample < 200 → *"Too early for Influora's own number; cold email to brands usually gets 2-5 replies per 100."*; otherwise `int n = Math.round(replyRate30dBp / 100.0f)` formatted as *"On Influora, %d of 100 pitches got a reply in the last 30 days."* — integer, half-up, never a decimal, and the frontend never divides.

<!-- PRIYA (3rd pass, 2026-09-04): two things this paragraph left to the engineer.
 (1) LOCK CONTENTION. `outreach_runtime_state` is the singleton row `OutreachSendJob` takes FOR UPDATE on every 15 seconds to increment `sent_today` (§4.9). `MeeraOutreachHealthJob` runs every 30 minutes and must NOT join that pattern: an unconditional single-column write needs no read lock and cannot lose a send count. Write it as its own short transaction —
     @Modifying @Transactional
     @Query("update OutreachRuntimeState s set s.replyRate30dBp = :bp, s.replySample30d = :n where s.id = 'SINGLETON'")
 — never load-modify-save, and never FOR UPDATE. The two INT columns are fine against `ddl-auto=validate`; §14.4 item 8's `TINYINT(1)` -> `BOOLEAN` fix on `paused` still applies to the same file.
 (2) `my_reply_rate_bp` / `my_sample` are PER-CREATOR and have no home on a singleton row — compute them at read time in `OutreachPitchService` from that creator's own `outreach_pitches` window. Only the platform pair is persisted. -->
 `PitchDraftCard` shows this line above the Send button on every draft, not only the first. Persona line changes to: *"Tell the creator the reply rate the tool returns, before the first send, in their language, without softening it."*

**(d) Reframe the goal in copy.** The creator-facing tab is "Find brands", never "Get deals". A pitch's success state in `PitchSentCard` is *replied*, and the empty state after 14 days reads *"No reply yet. That is normal for cold email; two follow-ups are queued for your approval."*

### 15.2 Convergence and reputation: a human line in every pitch, evidence in every pitch, per-creator reputation, brand-side opt-out

**(a) The creator's own line is mandatory.** `outreach_pitches` gains `creator_hook VARCHAR(400) NOT NULL` (3.3). `PitchDraftCard` has a labelled field *"One or two sentences in your own words: why this brand, why you"* (min 40 chars, max 400). `draft_pitch` weaves it verbatim into paragraph one; `approveAndSend` refuses **`HOOK_REQUIRED`** when it is blank or shorter than 40 chars, and **`HOOK_NOT_IN_BODY`** when the sent body no longer contains it (the creator edited it out). This is the anti-convergence lever that does not need a fuzzy similarity metric: every pitch carries a passage no model wrote.

**Follow-ups.** `OutreachFollowUpJob` (§4.9) creates sequence 2 and 3 as `MeeraDraft` PENDING and never sends. The job satisfies `creator_hook NOT NULL` by copying the value **verbatim from the sequence-1 `outreach_pitches` row for the same `candidate_id`**; the creator is never asked again. `HOOK_REQUIRED` and `HOOK_NOT_IN_BODY` are evaluated **only on sequence 1** — a follow-up is two sentences and must not be forced to repeat the hook. <!-- PRIYA (3rd pass, 2026-09-04): "reuse the hook" was unspecified against a NOT NULL column written by a background job, and `HOOK_NOT_IN_BODY` applied to sequence 2/3 would refuse every reasonable follow-up. Named the source row and scoped both refusals to sequence 1. -->

<!-- PRIYA (3rd pass, 2026-09-04): WHERE THE EIGHT NEW CODES GO. This repo has no error-code enum and no code registry: `com.influora.common.ApiException` holds a plain `private final String code` (ApiException.java:7, constructor :10) and every code in the codebase is a string literal at the throw site. So these are literals thrown from `OutreachPitchService.approveAndSend` and `OutreachDiscoveryService.run` — do not invent an `ErrorCode` enum for them. Collision check run against all of `influora-api/src` and `influora-ai/app`: `BRAND_RECENTLY_PITCHED`, `HOOK_REQUIRED`, `HOOK_NOT_IN_BODY`, `PITCH_NOT_SPECIFIC`, `SUBJECT_MENTIONS_PLATFORM`, `LARGE_BRAND_NO_COLD`, `CREATOR_OUTREACH_PAUSED`, `COLD_EMAIL_NOT_OPEN` — zero existing occurrences each. All eight are free. -->


**(b) Evidence-anchored body.** `approveAndSend` refuses **`PITCH_NOT_SPECIFIC`** unless the body contains the candidate's `brand_name` (case-insensitive) **and** at least one non-stopword token of four or more letters from an `evidence_json[].title`. Deterministic, unit-testable, and it makes "I love your brand" pitches impossible to send.

**(c) No platform tell in the subject.** Refuse **`SUBJECT_MENTIONS_PLATFORM`** if the subject contains "influora" (case-insensitive). The word appears once, in the signature line the law wants (§4.5), and nowhere a filter keys on.

**(d) Per-creator reputation and pause.** `MeeraOutreachHealthJob` adds a per-creator pass over 7 d: **any** complaint, or bounces ≥ 2 when sends ≤ 10, or bounce rate ≥ 20 % → `OutreachAliasService.suspend(creatorProfileId, reason)` (alias `SUSPENDED`, new `suspended_reason VARCHAR(64)` on 3.1), creator notified in-app *"Outreach paused: {reason}. Fix the addresses on your shortlist and ask support to resume."*, admin `POST /admin/outreach/creators/{profileId}/resume`. Negative replies (`REPLIED_NO`) ≥ 2 in 30 d → warning card; ≥ 4 → 30-day pause. `approveAndSend` refuses **`CREATOR_OUTREACH_PAUSED`** while suspended. The platform-wide kill switch in 4.9 protects the domain; this protects the domain *from one creator* before the platform number moves.

**(e) Brand-side opt-out that is easy and total.** The unsubscribe page (§4.8) gains a second button: *"Stop all Influora creators emailing anyone at {domain}"* → domain-wide suppression, reason `UNSUBSCRIBE`, allowed only through a pitch-bound token so a stranger cannot suppress a competitor's domain. The public page at `influora.in/outreach` gains a *"Do not contact our company"* form: enter a role address on the domain, receive a verification link (sent through `OutreachMailer`, tagged `optout-verify`), click → domain suppression reason `MANUAL`, source `BRAND_SELF`. Both are one-click for the brand and permanent.

**Ordering, ceiling and plumbing for the verify mail (all three were unstated).**
- The verify mail is **transactional, not outreach**. `OutreachMailer.send(..., tag = "optout-verify")` **skips the `outreach_suppression` check entirely** and is **not** counted against `outreach_runtime_state.sent_today` or the daily ceiling. The address most likely to use this form is one already suppressed by a bounce; checking suppression first would make the opt-out unreachable for exactly the brands that most want it. It is also exempt from the platform pause flag.
- Its own limits instead: one verify mail per `(domain, 24 h)`, token single-use with a 24-hour TTL, and the rate bucket below.
- **§15.6 did not list these and §4.11's rules require them.** `SecurityConfig`: `POST /outreach/optout` and `GET /outreach/optout/verify` both `permitAll`, placed **before** the `/admin/**` and `/creator/**` role gates (L207/L227), beside the existing `/outreach/unsubscribe` pair. `AuthRateLimitFilter`: bucket `outreach-optout` (POST, **IP-keyed**, 5 per window) with the 3 mandatory edits §4.11 enumerates, plus a `Pattern` constant and an `isUserKeyedBucket` case only if user-keyed (it is not). The **GET** verify bucket must go in the GET if-chain at **L300-317** — added below L319 it is dead code that never fires (§4.11, `PUBLIC_CREATOR_VERIFIED` at L313 is the precedent).

<!-- PRIYA (3rd pass, 2026-09-04): §15.2e also makes a latent 3.5 defect load-bearing. `outreach_suppression` declares `email_hash VARCHAR(64) NOT NULL` with `UNIQUE KEY uk_os_email (email_hash)` and a nullable `domain` — so a DOMAIN-WIDE suppression row has no email to hash and cannot be inserted without a fabricated hash, and two domain suppressions would collide on the unique key. Phase E already had one domain path (§4.8); §15.2e adds two more. Fix in 3.5, which is unbuilt: make `email_hash` NULLable, drop `uk_os_email`, add `UNIQUE KEY uk_os_target (email_hash, domain)`, and enforce "exactly one of the two is set" in `OutreachSuppressionService` (MySQL CHECK constraints are honoured from 8.0.16, but the service invariant is the one the tests assert). -->


**(f) Persona.** Add to §5.3: *"Never open with praise. Open with the creator's own line. Name one specific thing from the evidence. Keep it under 120 words before the signature. No exclamation marks, no 'I'd love to', no 'reaching out'."*

**The enforced ceiling is characters, not words: body 200 to 900 characters**, replacing §4.5's 200-1,500. "Under 120 words" is persona guidance only and is never a refusal.

<!-- PRIYA (3rd pass, 2026-09-04): two corrections.
 (1) The paragraph gave two different ceilings ("under 120 words" AND "200 to 900 chars") without saying which one refuses. Rule 0.4 and §4.5's existing check are character-based; a word count is not a check anyone will write the same way twice. One enforced rule, one guideline.
 (2) MEASUREMENT POINT. §4.5 mints the referral token and renders BOTH the CTA (`{web-base-url}/join/brand?ref={token}`, plus §15.5b's sentence, ~180 chars) and the two-line signature INTO the body AFTER the length check runs today. §15.5b's CTA plus the signature plus the media-kit link is comfortably 250+ characters, so measuring after assembly would fail a 900-char ceiling on almost every pitch. The 200-900 band is measured on the creator-authored body BEFORE the CTA, signature, and opt-out sentence are appended. Say so in §4.5. -->

**PROMPT_VERSION.** Rule 0.10 covers every persona line §15 adds. Confirmed: `PROMPT_VERSION = "meera-2026.08.10.1"` lives in `app/config.py:69`, `app/prompt/creator_persona.py:22` imports it and returns it at `:147`, and `persona.py:9` states the bump rule. <!-- PRIYA (3rd pass, 2026-09-04): one trap worth writing down — PROMPT_VERSION is a SINGLE constant shared by the brand persona (`persona.py:15`) and the creator persona (`creator_persona.py:22`). Bumping it for §15's creator lines restamps every brand turn too. That is the existing design and `redaction.py` / `loop.py` / `main.py` all read the same constant; do not "fix" it by forking the constant for Phase E. -->

### 15.3 Pricing: discovery is free, sending is cheap and refundable, bringing a brand pays the creator

**Fact first: there is no *creator* credit ledger at HEAD — but the grep is not empty.** A full **brand** credit ledger exists and works: `BrandAiCredit` (`domain/entity/BrandAiCredit.java`, table `brand_ai_credits`, PK = `workspace_id`, "the PK IS the FK, no surrogate id", L14-16), `BrandAiCreditRepository`, `AICreditService`, `AICreditResetJob`. It is **workspace-keyed 1:1**, and a creator has no workspace (`service_token.py`, `CreatorSuggestionServiceTokenService` javadoc) — so it cannot be reused, only mirrored. What does **not** exist is any `creator_credits` entity, ledger or deduction path. <!-- PRIYA (3rd pass, 2026-09-04): §15.3 opened with "grep is empty", and it is not — `grep -ril credit influora-api/src/main/java` returns 20 files including a real entity. The true and narrower statement is above. A spec that overstates an absence gets built on. When the creator ledger lands it should copy `BrandAiCreditRepository`'s atomic `@Modifying @Query("UPDATE ... WHERE credits_remaining >= :cost")` shape (tryDecrement/refundCredits), not a load-modify-save service. -->

Rohan's sheet (`../T-MEERA-CREATOR-PHASE-B/finance/meera-credit-sheet.md`) prices a discovery run at 40 credits against a ledger that does not exist for creators. The only spend control that exists today is the AI monthly cap: `ai_creator_monthly_cap_usd` (`app/config.py:455`, default **0.75 USD/month**), per-creator admin override via `creator_agent_preferences.ai_monthly_cap` (`V20260903150000`), enforced in Python by `check_creator_spend_gate` / `record_creator_spend` / `release_creator` (`spend_tracker.py:535`, `:476`, `:1047`). Confirmed as the enforcement path step 1 relies on. So pricing ships in two steps:

**Step 1 — no charges at all, limits only (Phase E as built).**
- `per-creator-weekly-discovery-runs` **1**, not 2. `per-creator-daily-limit` **5**, not 10. Discovery cost stays inside the creator's existing AI cap; nothing is billed to the creator.

<!-- PRIYA (3rd pass, 2026-09-04): "stays inside the creator's existing AI cap" is the sentence that needs the arithmetic, and §15 did not do it. The cap is 0.75 USD PER MONTH (config.py:455). One discovery run costs: §5.2 step 1's 0.15 reservation for the Perplexity + Claude legs, PLUS each `perform_site_analysis` taking its own separate check_spend_gate reservation and its own record_spend (analyze_site.py:160-167, :273 — §13 correction (b)), at up to 5 analyses per run (§13 correction 25's cap). Even at `ai_reservation_per_call_usd` alone that is materially more than 0.15, so ONE run plausibly consumes a large fraction of a 0.75 monthly cap and a handful exhausts it — and the same cap funds the creator's Meera CHAT. As written, a creator who uses discovery loses Meera for the rest of the month, silently, via `SpendCapExceeded`.
 This is a decision, not a bug, and it must be made explicitly. Options: (a) raise `ai_creator_monthly_cap_usd` for outreach-enabled creators through the existing per-creator override — one admin write, no new code; (b) give outreach its own cap key and its own reservation bucket in `spend_tracker`; (c) accept the shared cap and SAY SO in the UI before the first run ("a discovery run uses about a third of this month's Meera budget"). Default to (a) with the override set at build time for the Wave 2 cohort, and measure the real per-run cost on staging before choosing a number. Whatever is chosen, `per-creator-weekly-discovery-runs: 1` is doing more work than §15 credits it with: it is not a fairness limit, it is the thing standing between discovery and the creator's chat budget. -->

<!-- PRIYA (3rd pass, 2026-09-04): the three credit config keys in §15.6's 2.5 row (`send-credit-cost`, `free-sends-per-month`, `referral-credit-bonus`) are marked "inert until the ledger exists". Inert config that nothing reads is how a value drifts out of agreement with the sheet it was copied from. Either bind them and have the (absent) ledger path assert `cost == 0` at boot, or leave them OUT of `OutreachProperties` and keep them in the finance sheet until Phase C. Recommend the latter: do not ship bound properties with no reader. -->

- Platform cost per run drops with scale via the brand index (below): the marginal run for a well-covered niche is one Perplexity call plus one Claude call, under ₹8.

**Step 2 — when the credit ledger lands (Phase C / finance sheet), RULING, Swapnil.** Default to build against:

| Event | Credits | Why |
|---|---|---|
| Discovery run | **0** | The creator pays nothing to look. Removes the "paid for unconverted leads" problem entirely. |
| Pitch send | **5** (≈ ₹12 retail on Rohan's ₹2.49/credit), first **3 sends per month free** | A small price on sending rations spam better than any filter: creators send fewer, better pitches. Sending costs the platform nothing (Mailgun flat), so this is a behaviour price, not a cost recovery. |
| Bounce or FAILED | **refund 5** automatically in `OutreachEventsWebhook` | The creator should never pay for a bad address the platform found. |
| Reply routed to a brief | **0** | Never tax good news. |
| Brand joins through the creator's referral (E5) | **+40 to the creator** | The creator did the platform's acquisition work; pay them in the currency they spend. Turns outreach from an expense into a possible income line. |

Alternative for the ruling: charge 40 credits per run with the first run free (Rohan's model). Rejected as default because it charges for the step with the least creator control over outcome.

**Brand index (cost and signal in one table).** New migration **`V20260920101100__outreach_brand_facts.sql`** — a platform-wide, domain-keyed cache of everything discovery learns, so the second creator in a niche does not pay for the first creator's site analyses:

```sql
CREATE TABLE outreach_brand_facts (
    id                      VARCHAR(26)  NOT NULL,       -- Ulids.newUlid(), house pattern
    website_domain          VARCHAR(255) NOT NULL,       -- WebDomains.normalise
    brand_name              VARCHAR(200) NOT NULL,
    category                VARCHAR(100) NULL,
    brand_size              VARCHAR(16)  NULL,           -- SMALL | MID | AGENCY | LARGE
    storefront              VARCHAR(16)  NULL,           -- SHOPIFY | WOOCOMMERCE | NULL (15.4d)
    niche_tags_json         TEXT         NULL,           -- from perform_site_analysis
    contact_route           VARCHAR(24)  NOT NULL,
    contact_email           VARCHAR(255) NULL,           -- role/brand-published only (rule 14)
    contact_person_json     TEXT         NULL,           -- name/title/public_url/source_url; purged at person_purge_after
    person_purge_after      TIMESTAMP    NULL,           -- verified_at + 90 days
    evidence_json           TEXT         NOT NULL,
    on_platform_workspace_id VARCHAR(26) NULL,
    pitches_sent            INT          NOT NULL DEFAULT 0,
    replies                 INT          NOT NULL DEFAULT 0,
    negative_replies        INT          NOT NULL DEFAULT 0,
    complaints              INT          NOT NULL DEFAULT 0,   -- 15.4f keys exclusion on "any complaint"
    bounces                 INT          NOT NULL DEFAULT 0,
    excluded_until          TIMESTAMP    NULL,                 -- 15.4f's 180-day exclusion, set when it starts
    last_pitched_at         TIMESTAMP    NULL,
    verified_at             TIMESTAMP    NOT NULL,
    created_at              TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_obf_domain (website_domain),
    INDEX idx_obf_category_size (category, brand_size),
    INDEX idx_obf_person_purge (person_purge_after),
    INDEX idx_obf_excluded (excluded_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

<!-- PRIYA (3rd pass, 2026-09-04): four corrections to this DDL, all applied above.

 (1) PRIMARY KEY. `website_domain VARCHAR(255)` as PK is 1020 bytes under utf8mb4 — comfortably under InnoDB's 3072-byte limit, so it WOULD work. It is still wrong for this codebase. Every table in §3 and essentially every table in the 119 existing migrations uses a `VARCHAR(26)` ULID surrogate (`common/Ulids`). The single natural-key precedent is `brand_ai_credits`, whose PK is `workspace_id` — and that is a 1:1 extension of an existing ULID row, explicitly documented as "the PK IS the FK, no surrogate id" (BrandAiCredit.java:14). This table is neither 1:1 with anything nor keyed on an id we already mint. Surrogate `id` + `UNIQUE (website_domain)`: the uniqueness and the lookup path are unchanged, and nothing downstream has to carry a mutable natural key in an FK or a log line.

 (2) COUNTER CONCURRENCY. `OutreachInboundController` and the events webhook both increment these, concurrently, across replicas. Entity load-modify-save LOSES UPDATES — two replies in the same second become one. Use the atomic pattern this repo already mandates for exactly this (`BrandAiCreditRepository.tryDecrement`/`refundCredits`, "Guardrail 5"), one method per counter:

     @Modifying @Transactional
     @Query("update OutreachBrandFacts f set f.replies = f.replies + 1 where f.websiteDomain = :domain")
     int incrementReplies(@Param("domain") String domain);

 Never `findByWebsiteDomain` + setter + `save` for a counter. `last_pitched_at` and `verified_at` are last-writer-wins and may be set normally.

 (3) MISSING COLUMNS. §15.4f keys the outcome loop on "`negative_replies >= 2` or any complaint" and a 180-day exclusion; there was no `complaints` column and no way to know when an exclusion started (deriving it from `negative_replies` alone loses the date, so the exclusion would never expire, or would expire on the wrong day). Added `complaints`, `excluded_until` and `created_at`. So the answer to "are the two migrations enough" is no — not because a third file is needed here, but because this file was short three columns and the `campaigns` index (§15.1a) genuinely does need a third file.

 (4) WRITER. `storefront` is DETERMINED by Python (§15.4d) but this table is written by Java. Python returns it in the discovery payload; `OutreachDiscoveryService` persists it at step 5 with the rest of the upsert. Say which side owns each column in the migration comment, per §14.4 item 8's third bullet. -->


Flow change in §5.2: after step 3 (parse), Python calls back to Java **`POST /internal/outreach/brand-facts`** with body `{"domains": [...]}` (max 50) and **skips step 4 site analysis for any domain with `verified_at` newer than `brand-facts-ttl-days` (30)**.

<!-- PRIYA (3rd pass, 2026-09-04): this was written as `GET /internal/outreach/brand-facts?domains=` "(service token, same scope decision as 4.3)". Both halves are wrong.

 (1) IT CANNOT BE A GET, AND A GET WOULD BE UNSIGNED WHERE IT MATTERS. `SpringInternalClient` has no GET path at all: `call_tool_endpoint` hardcodes `method="POST"` and `self._client.post` (spring.py:157, :163), and every helper POSTs despite its name — `get_meera_context` is `POST /internal/meera/context` (spring.py:296). More seriously, `InternalServiceTokenFilter:82` signs `stripContext(request.getRequestURI())`, which EXCLUDES the query string, and `InternalRequestVerifier:84` recomputes `method + path + sha256(body) + timestamp + nonce`. A GET has an empty body, so `?domains=` would sit entirely OUTSIDE the HMAC — a replayed request with a swapped domain list would verify. POST with a JSON body puts the list inside `sha256(body)`. Copy `get_meera_context`'s shape exactly.

 (2) "SAME SCOPE DECISION AS 4.3" IS THE WRONG AUTH PATH. §4.3 is Java -> Python: Java mints a `BrandSafetyServiceTokenService` / `CreatorSuggestionServiceTokenService` JWT for `POST {ai.baseUrl}/internal/outreach/discover`, and §14.4 item 1's open RULING is about WHICH of those two beans. This call is Python -> Java, guarded by a completely different mechanism: `mint_service_token()` (spring.py:113) plus `_build_signed_headers`' `X-Meera-Service-Token` / `X-Meera-Signature` / `X-Meera-Timestamp` / `X-Meera-Nonce` / `X-Meera-Key-Id`, verified by `InternalServiceTokenFilter` (aud `influora-internal`, iss `meera-python`, <=60s TTL) + `InternalRequestVerifier`. Different key, different direction, different code. §14.4 item 1's ruling does NOT apply here and this endpoint does not wait on it. Corrected sentence: "*service token and HMAC per the existing Python->Java internal path (`SpringInternalClient`), which is a different mechanism from §4.3's Java->Python token and carries no creator or workspace scope.*"

 (3) ONE PLUMBING DETAIL. `_build_signed_headers` takes `onbehalf_jwt` as a MANDATORY keyword argument. `InternalServiceTokenFilter` itself does NOT require `X-Onbehalf-Authorization` — it checks only the service token and the HMAC — so a service-only lookup is legitimate. Either pass the on-behalf JWT the discovery route already holds, or add a service-only variant of the header builder. Decide it in code review; do not leave it to the engineer to discover that the parameter is required by the client but not by the server.

 (4) SCOPE. This is platform-wide, domain-keyed data with no creator scope, so the handler must NOT filter by creator and MUST cap the request: `@Size(max = 50)` on the domain list, and a per-caller rate limit. An uncapped domain list on an internal endpoint is an unbounded `IN (...)`. -->
 Java upserts facts after step 5 in `OutreachDiscoveryService`. `OutreachInboundController` and the events webhook increment the counters. `OutreachInboundPurgeJob` also nulls `contact_person_json` past `person_purge_after` (rule 14: a named person is not kept indefinitely; a role address is not personal data and stays). Per-creator candidates (3.2) remain the creator-scoped view; the facts table is the shared substrate. The eleventh migration Priya required in §14.6 (run counter) is **`V20260920101000__outreach_discovery_runs.sql`**: `(id VARCHAR(26) PK, creator_profile_id VARCHAR(26) NOT NULL, started_at TIMESTAMP NOT NULL, candidates_returned INT NOT NULL DEFAULT 0, searches_used INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL, INDEX idx_odr_creator_started (creator_profile_id, started_at))`; §4.3 step 2 counts rows here, and Python's billing and this row are written by the same Java call, so a zero-yield run is counted. <!-- PRIYA (3rd pass, 2026-09-04): the row must be INSERTed in `OutreachDiscoveryService.run` BEFORE the `MeeraOutreachAiClient.discover` call and updated after, or a run that times out or throws is still uncounted and the §14.6 condition-5 gap reopens in a new place. The index is required — the rate check is `count where creator_profile_id = ? and started_at > ?` on every run. -->

**Thirteen migrations total**, not twelve: 3.1-3.9, `V20260920100900__outreach_runtime_state.sql`, `V20260920101000__outreach_discovery_runs.sql`, `V20260920101100__outreach_brand_facts.sql`, and `V20260920101200__campaigns_end_brand_category_idx.sql` (§15.1a — `campaigns` is a live table, so that index cannot be folded into an unbuilt file the way §15's additions to 3.1/3.2/3.3 and 4.9 correctly can). §7, §8 and §10's deliverables now read "thirteen"; pointers were added there, because they still read "ten". <!-- PRIYA (3rd pass, 2026-09-04): §15.3 asserted "§7, §8, §10 Q10 read twelve" and §15.6 asserted they were "updated". §8's pointer existed; §7 (`MeeraPhaseEBootValidationTest`) and §10's deliverables checklist still said "ten" and had no pointer. This is precisely the forward-propagation failure §14.6 named — §15 repeated it in the same paragraph that claimed to have avoided it. Fixed in all three places, and the count corrected to thirteen. -->

### 15.4 Signal quality: seeds, local mode, size rule, storefront signal, outcome loop

**(a) Creator-supplied seeds.** `DiscoveryRequest` gains `seed_brands: string[]` (≤ 3, each ≤ 60 chars, sanitised). The strongest query in the plan becomes *"brands similar to {seed} in India, {category}, that work with creators"*, one per seed, ahead of the generic category queries. `find_outside_brands` tool schema gains the same optional array; Meera asks *"Name one or two brands you'd love to work with and I'll find ones like them"* before the first run.

**(b) Local mode.** For **effective** tier `NANO` and `MICRO` the default is `local: true` (overridable in the request):

<!-- PRIYA (3rd pass, 2026-09-04): the enum names are right and the read is wrong. `CreatorTier` is exactly `NANO, MICRO, MID, MACRO` (domain/enums/CreatorTier.java) — confirmed. But `CreatorProfile.tierOverride` (L141-142, `tier_override`, V20260718160000) is an ADMIN OVERRIDE that is NULL for essentially every creator; its own javadoc says "This codebase persists NO tier concept otherwise: the admin surface derives tier from total_followers at read time". Reading `tier_override` for this gate would select almost nobody, and the ones it selects are the ones an admin happened to touch.
 Use the effective tier, exactly as `AdminCreatorService.resolveTier` (:624-629) does: `tierOverride != null ? tierOverride.name() : deriveTier(totalFollowers)`, where `deriveTier` (:611-616) is `>=500_000 MACRO, >=50_000 MID, >=10_000 MICRO, else NANO`. There is NO shared utility for this — the three-line bucket is duplicated privately in four places (`AdminCreatorService:611`, `CreatorAgentBaselineService:84`, `MeeraContextService:414`, `BrandContextAssembler:388`, the last of which documents the duplication as deliberate). Follow the house pattern: a private static copy in `OutreachDiscoveryService`, not a new shared class and not a repository read of `tier_override`. -->
 queries target the creator's city — *"{city} {category} boutique OR cafe OR studio OR salon OR store Instagram"* — and the fit scorer treats `brand_size == SMALL` as +15. Local businesses are who actually pays nano creators, in cash and barter, and they answer their own email. Persona: *"For a nano or micro creator, look in their city first."*

**(c) Size rule.** `brand_size IN (LARGE, AGENCY)` → `contact_route` may never be `ROLE_EMAIL`;

<!-- PRIYA (3rd pass, 2026-09-04): `brand_size` has no source. `perform_site_analysis` returns `{source_url, niche_tags, tone_dial, brand_color, product_catalog}` and nothing about size (§13's correction on `extract_opengraph_facts` established the same shape). As written, `LARGE_BRAND_NO_COLD` never fires because nothing ever sets LARGE. Specify the derivation or delete the rule. Default: classify in the SAME Claude fit call that already runs (§5.2 step 5) — one enum field in its JSON output, informed by `product_catalog` size and the Perplexity evidence — persist to `outreach_brand_facts.brand_size`, and treat NULL as SMALL. Fail-OPEN on the penalty is correct here: fail-closed would suppress every unclassified brand, which on day 1 is all of them. -->
 it becomes `PERSON_PUBLIC` (assisted note) or `NONE`, fit score −30, and `approveAndSend` refuses **`LARGE_BRAND_NO_COLD`** if a creator supplies an address by hand (§4.11 `/contact`). Large brands buy through agencies and do not answer cold role mail; sending to them burns the domain for nothing.

**(d) Storefront signal.** Python step 4 adds one probe per surviving candidate: `await asyncio.to_thread(guarded_fetch, f"https://{domain}/products.json", max_bytes=200_000, timeout=5.0)`; a body that parses as JSON with a top-level `products` list → `storefront = SHOPIFY`; else `/wp-json/wc/store/v1/products` → `WOOCOMMERCE`. (`guarded_fetch` exposes no status code — §13 correction 18 — so "parses as expected JSON" is the test.)

<!-- PRIYA (3rd pass, 2026-09-04): verified against `app/security/ssrf_guard.py`. Three of the four assertions hold; one does not.

 HOLDS — kwargs. `def guarded_fetch(url: str, *, max_bytes: int | None = None, timeout: float | None = None) -> tuple[bytes, str]` (:150). Both are keyword-only, both named as §15 calls them.
 HOLDS — max_bytes is a CAP and a smaller value is honoured. `max_bytes = max_bytes or settings.ssrf_max_response_bytes` (:157) takes the caller's value whenever it is truthy; the 5 MB default (`ssrf_max_response_bytes`, config.py:300) is only the fallback. The cap is enforced DURING the stream (:212-224, the F-11 fix) and against a declared `content-length` (:207-211), so a 200 KB ceiling really does abort a large body mid-read.
 HOLDS — it raises only `SsrfBlockedError`. Every `httpx.HTTPError` and `httpx.InvalidURL` is wrapped (:228-230); scheme, hostname, DNS, blocked-address, size and redirect violations all raise the same type (:87-125, :215, :224, :235-238). A bare `except SsrfBlockedError` around the probe is complete.
 HOLDS — SSRF sufficiency. `ssrf_allowed_schemes = ("https",)` (config.py:298) so http is rejected at :87; `resolve_and_pin` validates EVERY resolved address and connects to the pinned IP with the original Host header and SNI, re-validating each of up to 2 redirect hops. For a probe of an arbitrary candidate domain that is enough.

 DOES NOT HOLD — `guarded_fetch` IS SYNCHRONOUS. It is `def`, not `async def`, and it drives a blocking `httpx.Client` (:181). Called bare from the async discovery route it blocks the event loop for the whole fetch, which is why `perform_site_analysis` already offloads its own call to a thread. Corrected to `asyncio.to_thread` above (or `anyio.to_thread.run_sync` with `functools.partial`, matching the house idiom).

 AND ONE BUDGET PROBLEM §15 did not cost. `timeout=5.0` is PER HOP and `ssrf_max_redirects` is 2, so a worst-case probe is ~15 s. Two probes per candidate (Shopify then WooCommerce) times 5 candidates is up to 150 s against §4.3's 60 s Java read timeout — the same failure §13 correction 25 fixed for site analysis. Run the probes inside the SAME `asyncio.gather` + semaphore as the site analyses, give the whole probe stage a 10 s wall-clock budget, and on timeout set `storefront = NULL`. A storefront badge is a nice-to-have; it must never fail a run.

 ORDERING — confirmed correct, and worth stating so nobody reorders it. §5.2 step 3's parser drops marketplace and social hosts (amazon, flipkart, instagram, linkedin, facebook, youtube, myntra, nykaa) BEFORE step 4, so `instagram.com/products.json` is never probed. The probe belongs in step 4 and must stay there. -->
 +15 fit, badge "Runs a store" on the card, and the pitch CTA for these brands adds *"Influora connects to your store"*: a Shopify or WooCommerce D2C brand is exactly the workspace the platform's existing integrations were built for.

**(e) Recency.** Where an evidence citation carries a parseable date, older than 12 months → drop the evidence line; a candidate with no evidence left is dropped. Queries include the current year.

**(f) Outcome loop.** Fit score inputs from `outreach_brand_facts`: `replies > 0` → +20; `negative_replies ≥ 2` or any complaint → excluded from every creator's shortlist for 180 days; `pitches_sent ≥ 3 AND replies == 0` → −20. This is the flywheel: discovery gets better with every reply the platform sees, and no creator is sent to a brand that has already said no twice.

### 15.5 Position against the moat: sequence, reframe the ask, measure, and a kill rule

**(a) Sequence — supersedes §8's day order.** Phase B first (unchanged). Then **Wave 1: E4, E5, E6, and E1's alias + suppression foundation.** These are the parts that are clearly good, carry no reputation risk, and sit on Phase B's briefs and secured funds. **Wave 2: E2 + E3 (discovery and cold email) behind `influora.outreach.cold-email-enabled: false`** for an allow-listed cohort: `cold-email-cohort-max` (default 100) creators. `OutreachDiscoveryService.run` refuses **`COLD_EMAIL_NOT_OPEN`** outside the cohort; E4's on-platform matching still runs for everyone because it needs no email.

**The cohort predicate, exactly** (evaluated in `OutreachDiscoveryService.run`, all three required):

```
resolveTier(profile) in (MICRO, MID, MACRO)          // effective tier per 15.4b's correction, i.e. totalFollowers >= 10_000 unless overridden
AND prefs.rateCardShareable == true                   // Phase B B6 — the media kit's own precondition
    AND at least one rate-card value is set
AND collaborationRepository.countByCreatorProfileIdAndStatus(
        profileId, CollaborationStatus.COMPLETED) >= 1
```

<!-- PRIYA (3rd pass, 2026-09-04): "with a media kit" was not a checkable predicate.
 - "Completed collaboration" IS checkable: `CollaborationStatus.COMPLETED` exists (domain/enums/CollaborationStatus.java, 13 values). Confirmed.
 - "A media kit" is NOT. There is no `has_media_kit` flag anywhere at HEAD. The only media-kit symbol in the Java tree is `PortfolioDtos:95` / `PortfolioService:446` `mediaKitDownloads`, which is a DOWNLOAD COUNTER, not a possession flag — gating on it would mean "someone downloaded your kit", which is backwards. The media kit itself is Phase B B6 (`MediaKitService`, public `/c/:username/kit`, Phase B §10 day 7) and does not exist yet. Proxy it with the prefs fields Phase B B6 introduces alongside it (`rate_card_shareable` plus a set rate), which is what actually makes a kit worth linking.
 - CONSEQUENCE, and it is a sequencing fact §15.5a should state outright: Wave 2 cannot start before Phase B day 7 ships, because two of its three cohort predicates live in Phase B. §15.5a already puts Phase B first, so this is a tightening, not a conflict — but "Wave 2 needs Phase B B6 specifically, not just Phase B" is the buildable version. -->


**(b) Reframe the ask.** The pitch does not say "hire me". Its CTA is *"Post your brief on Influora — funds are secured before I start, you approve every deliverable — and I'll answer within 24 hours"* with the E5 referral link. <!-- PRIYA (3rd pass, 2026-09-04): rule 0.2 checked — grepped all of §15 for "escrow", case-insensitive: zero hits. "funds are secured" is the approved vocabulary (Secure Payments / secured funds). The CTA is clean. Note only that this sentence plus the referral URL plus the two-line signature is 250+ characters and must therefore sit OUTSIDE the 200-900 body measurement — see §15.2f's correction. --> The brand can still reply by email, and that reply lands in paste-and-secure (Phase B). Either way the conversation ends up inside the part of the product that is the moat: a clean brief and secured payment. Cold email becomes a brand-acquisition channel with the creator as the messenger, which is why 15.3 pays the creator when it works.

**(c) Scorecard, on the admin `OutreachPage`, computed by `MeeraOutreachHealthJob`, all per 100 sends over 30 d:** replies, briefs created, brands joined (E5), complaints; plus cost per reply in rupees from the AI spend records. Targets at day 60 of Wave 2: replies ≥ 3, briefs ≥ 1, brands joined ≥ 0.5, complaints < 0.1.

**(d) Kill rule — RULING, Swapnil, default written here.** After 60 days of Wave 2 at a ceiling of 160 or more per day: if replies per 100 < 2 **or** briefs per 100 < 0.5, cold email is switched off (`cold-email-enabled: false`) and E4-E6 stay. The scorecard shows the days remaining and the two numbers against their thresholds so the decision is a reading, not a debate.

### 15.6 Deltas this section makes to earlier sections

| § | Change |
|---|---|
| 2.5 | Config keys: `per-creator-weekly-discovery-runs: 1`, `per-creator-daily-limit: 5`, `domain-cooldown-days: 30`, `brand-facts-ttl-days: 30`, `cold-email-enabled: false`, `cold-email-cohort-max: 100`, `send-credit-cost: 5`, `free-sends-per-month: 3`, `referral-credit-bonus: 40` (last three inert until the ledger exists) |
| 3.1 | `suspended_reason VARCHAR(64) NULL` |
| 3.2 | `warmth VARCHAR(24) NOT NULL` |
| 3.3 | `creator_hook VARCHAR(400) NOT NULL`; `INDEX idx_op_sent_at (sent_at)` |
| 3 / 4.9 | `outreach_runtime_state` + `reply_rate_30d_bp`, `reply_sample_30d`; **three** new migrations `V20260920101000__outreach_discovery_runs.sql`, `V20260920101100__outreach_brand_facts.sql`, `V20260920101200__campaigns_end_brand_category_idx.sql` → **thirteen**; §7 boot test, §8, §10 deliverables corrected (they still read "ten") |
| 3.5 | `email_hash` NULLable, `uk_os_email` replaced by `uk_os_target (email_hash, domain)` — a domain-wide suppression row cannot satisfy `email_hash NOT NULL` (§15.2e correction) |
| 4.3 | step 2 counts `outreach_discovery_runs`; step 3 adds seeds, local flag, demand lookalike names; step 5 computes `warmth`, upserts brand facts; cohort refusal `COLD_EMAIL_NOT_OPEN` |
| 4.4 | LARGE/AGENCY never ROLE_EMAIL |
| 4.5 | refusals `BRAND_RECENTLY_PITCHED`, `HOOK_REQUIRED`, `HOOK_NOT_IN_BODY`, `PITCH_NOT_SPECIFIC`, `SUBJECT_MENTIONS_PLATFORM`, `LARGE_BRAND_NO_COLD`, `CREATOR_OUTREACH_PAUSED`; body 200-900 chars; CTA copy per 15.5b |
| 4.8 | unsubscribe page domain button; events webhook increments brand-facts counters and refunds send credits on bounce (step 2 only) |
| 4.9 | health job: per-creator pass, 30-d reply rate, scorecard; purge job also purges `contact_person_json` in brand facts |
| 4.11 | `POST /admin/outreach/creators/{profileId}/resume`; **`POST`** `/internal/outreach/brand-facts` (not GET — §15.3 correction); public `POST /outreach/optout` + `GET /outreach/optout/verify` **with their `SecurityConfig` `permitAll` entries and the `outreach-optout` rate bucket (§15.2e correction — §15 omitted both, which 4.11's own rules require)**; `DiscoveryRequest.seed_brands`, `.local`; `CandidateItem.warmth`, `.cooldown_until`, `.storefront`; `PitchDraftResponse.reply_rate_30d_bp` etc.; send body gains `creator_hook` |
| 5.2 | brand-facts lookup before site analysis; seed and local queries; storefront probe; recency filter; outcome inputs to fit score |
| 5.3 | schema fields; persona lines in 15.1c, 15.2f, 15.4a-b; reply-rate sentence replaced by the tool's number |
| 6.2 | `BrandShortlistCard` warmth + store badges, cooldown state; `PitchDraftCard` hook field + reply-rate line; "experimental" label on cold email |
| 6.4 | scorecard, kill-rule countdown, per-creator pause list on `OutreachPage` |
| 7 | tests: `OutreachPitchServiceTest` for every new refusal; `BrandCooldownTest`; `OutreachBrandFactsServiceTest` (ttl, purge, counters); `MeeraOutreachHealthJobTest` per-creator pause thresholds; Python `test_storefront_probe.py`, `test_seed_queries.py`, `test_brand_facts_skip.py` |
| 8 | sequencing per 15.5a |
| 11 | Cost answer restated: ₹0 to the creator in step 1; platform marginal run under ₹8 once the niche is indexed |
| 12 | RULINGS added: pricing model (15.3 step 2), kill-rule thresholds (15.5d), cohort size (15.5a) |

**What this does not change.** Every rule in §0 (creator tap before any send, DPDP minimisation, isolated mail domain, untrusted inbound, no scraping behind logins) is untouched and in several places tightened. The three §14.4 rulings stand. The Docker/staging gate stands. The credential rotation stands, and is still the first thing to do. <!-- PRIYA (3rd pass, 2026-09-04): one exception to "untouched" — §15.1a's W1_DEMAND_LOOKALIKE did change a §0-adjacent boundary, by proposing to move end-brand names into the discovery path on a false premise about what creators can already see. Corrected in place: the names stay server-side. Rule 14 (third-party data minimisation) is otherwise genuinely tightened by §15.3's `person_purge_after`. -->

---

## 16. Priya review, third pass: §15 against the code

**Reviewer:** Priya (CTO). **Scope:** §15 only (product pass, 2026-09-04), plus the seven `<!-- §15 supersedes -->` pointers and the end-to-end contradiction read §14.6 asked for. **Baseline:** `143ca1e` plus the uncommitted working tree, as §14.2 established. **Method:** as §13 and §14 — every symbol, column, signature, enum value, config key and behaviour claim checked against the source. §15 asserted a great deal without file citations; each such claim was resolved to a file and a line. Corrections are applied inline above, tagged `<!-- PRIYA (3rd pass, 2026-09-04): ... -->`.

**The headline.** §15's product reasoning is sound and, in three places, it is the best thinking in this document — the platform-wide brand cooldown, the mandatory creator hook, and the kill rule are all mechanisms rather than intentions, and all three are buildable. But §15 is the least *verified* section of the spec. It was written as a product argument and its supporting claims about the code were asserted, not checked; six of them are wrong, and one of those six would have shipped a data disclosure.

### 16.1 Corrections

| # | § | Severity | Correction |
|---|---|---|---|
| C1 | 15.1a | **BLOCKING** | "end-brand names on ACTIVE campaigns are already visible to creators in listings" is **false**. `CreatorCampaignListItem` (L33-47) and `CreatorCampaignDetailResponse` (L50-67) carry `BrandSummary(workspaceId, name, logoUrl, verificationStatus)` (L28) and **no end-brand field**; `endBrandName`/`endBrandCategory` exist only on brand-side `CampaignDtos` (L93/L127/L158). W1 as written would be the first disclosure of one brand's end-brand relationship to unrelated creators. Fixed: names stay server-side as query-text seed; only a derived descriptor crosses to Python; nothing named is returned or logged. |
| C2 | 15.1a | HIGH | `findRecentActiveEndBrandNamesByCategoryIn(...)` is not a derivable Spring Data name. Replaced with an explicit `@Query`; cutoff column named (`Campaign.createdAt`, `Instant`, L129 — not `startDate`, `LocalDate`, L48). `CampaignStatus.ACTIVE` ✓. Status finders exist to mirror (`findByStatus`, `findByCampaignTypeAndStatus`); **no category finder exists** and `end_brand_category` is unindexed free text — needs its own migration. |
| C3 | 15.4b, 15.5a | HIGH | `NANO`/`MICRO` are correct enum names (`CreatorTier`: NANO, MICRO, MID, MACRO) but `tier_override` is an admin override that is **null for essentially every creator**. Must use the effective tier (`AdminCreatorService.resolveTier` :624-629 over `deriveTier` :611-616); no shared util exists — the bucket is privately duplicated in four classes, so duplicate a fifth. |
| C4 | 15.4d | HIGH | `guarded_fetch` is **synchronous** (`ssrf_guard.py:150`, blocking `httpx.Client`) — must be `asyncio.to_thread`. kwargs ✓, `SsrfBlockedError`-only ✓, `max_bytes` is a cap and 200 KB is honoured ✓ (`:157`), https-only + DNS pinning ✓. Budget: `timeout` is **per hop** × 2 redirects × 2 probes × 5 candidates ≈ 150 s against a 60 s read timeout — probes join the existing gather+semaphore with a 10 s stage budget. |
| C5 | 15.3 | **BLOCKING** | `GET /internal/outreach/brand-facts?domains=` is unbuildable **and unsafe**. `SpringInternalClient` is POST-only (`spring.py:157,163`; `get_meera_context` is `POST /internal/meera/context` :296), and `InternalServiceTokenFilter:82` signs `getRequestURI()` **without the query string** — the domain list would sit outside the HMAC. Changed to `POST` with a JSON body. "Same scope decision as 4.3" is also wrong: 4.3 is Java→Python; this is Python→Java via `mint_service_token()` + HMAC — a different mechanism that §14.4 item 1's ruling does not govern. Sentence rewritten. |
| C6 | 15.3 | HIGH | "no `creator_credits` … grep is empty" — the grep is **not** empty. `BrandAiCredit` + `BrandAiCreditRepository` + `AICreditService` + `AICreditResetJob` are a working brand ledger (workspace-keyed 1:1, unusable for creators). Corrected to "no *creator* ledger"; the future one should mirror `BrandAiCreditRepository`'s atomic pattern. `ai_creator_monthly_cap_usd` + `check_creator_spend_gate` confirmed as the only enforcement path ✓. |
| C7 | 15.2 | MEDIUM | Refusal codes: no enum, no registry — `ApiException` holds a plain `String code` (`:7`) and every code is a literal at the throw site. Said so, so nobody builds an `ErrorCode` enum. **All eight new codes verified collision-free** (0 occurrences each across `influora-api/src` and `influora-ai/app`). |
| C8 | 15.2e | HIGH | Opt-out verify mail: ordering, ceiling and plumbing all unstated. Specified — suppression check **skipped** for `optout-verify` (the likeliest applicant is already suppressed), **not** counted against the daily ceiling or the pause flag, own per-domain 24 h limit. Added the missing `SecurityConfig` `permitAll` entries and the `outreach-optout` IP-keyed bucket, with §4.11's GET-if-chain trap called out. |
| C9 | 3.5 | HIGH | §15.2e makes a latent defect load-bearing: `outreach_suppression.email_hash` is `NOT NULL` with `UNIQUE KEY uk_os_email`, so a **domain-wide** row cannot be inserted and two would collide. Fixed in 3.5 (unbuilt): nullable hash, `uk_os_target (email_hash, domain)`, service invariant. |
| C10 | 15.3 DDL | HIGH | `outreach_brand_facts`: natural-key PK is legal (1020 B < 3072 B) but off-pattern — every table uses a ULID surrogate; the one natural-key precedent (`brand_ai_credits`) is a 1:1 FK extension. Changed to `id VARCHAR(26)` PK + `UNIQUE (website_domain)`. |
| C11 | 15.3 DDL | HIGH | Counters must be atomic. Load-modify-save loses updates across replicas; use `@Modifying @Transactional @Query("update … set f.replies = f.replies + 1 …")`, the repo's own Guardrail-5 pattern from `BrandAiCreditRepository.tryDecrement`/`refundCredits`. |
| C12 | 15.3 DDL | MEDIUM | **The table was short three columns §15's own rules need**: `complaints` (15.4f keys exclusion on "any complaint"), `excluded_until` (the 180-day window has no start date otherwise), `created_at`. Added, plus `idx_obf_excluded`. Also named which side writes `storefront` (Python determines, Java persists). |
| C13 | 15.1c | MEDIUM | The reply-rate columns sit on the singleton row `OutreachSendJob` takes `FOR UPDATE` every 15 s. The health job must write them with a short unconditional `@Modifying` update and **not** `FOR UPDATE`. Rendering rule made explicit (`Math.round(bp/100.0)`, integer, Java-side, rule 0.4). `my_reply_rate_bp` has no home on a singleton — computed per creator at read time. |
| C14 | 15.3, 15.6 | HIGH | **The count is thirteen, not twelve**, and the propagation claim was false. `campaigns` is a live table, so C2's index needs its own file (`V20260920101200`); §15's additions to 3.1/3.2/3.3/4.9 correctly fold into unbuilt files ✓. §15 claimed "§7, §8, §10 Q10 read twelve" — §8's pointer existed, **§7 (L913) and §10's deliverables still read "ten" with no pointer**. Both fixed. |
| C15 | 15.2f | MEDIUM | Two ceilings given ("under 120 words" and "200-900 chars") with no statement of which refuses. Characters are enforced; words are persona guidance. And the band is measured **before** §4.5 appends the CTA, signature and opt-out sentence — otherwise §15.5b's own CTA fails it. |
| C16 | 15.4c | MEDIUM | `brand_size` has no producer. `perform_site_analysis` returns no size signal, so `LARGE_BRAND_NO_COLD` would never fire. Derivation specified (one enum field on the existing Claude fit call, persisted to brand facts, NULL treated as SMALL, fail-open). |
| C17 | 15.5a | MEDIUM | "with a media kit" was not a checkable predicate — no `has_media_kit` exists; `mediaKitDownloads` is a download counter and gating on it is backwards. Exact three-clause predicate written. `CollaborationStatus.COMPLETED` ✓. Consequence stated: **Wave 2 needs Phase B day 7 (B6) specifically**, not just Phase B. |
| C18 | 15.1b | MEDIUM | The cooldown check as written was a cross-creator join through the creator-scoped `outreach_brand_candidates` on an unindexed column, on the send path. Re-pointed at `outreach_brand_facts.last_pitched_at` — one unique-key lookup. |
| C19 | 15.3 step 1 | **HIGH — new risk §15 did not name** | "Discovery cost stays inside the creator's existing AI cap" is arithmetic nobody did. The cap is **0.75 USD/month**; a run costs step 1's 0.15 reservation **plus** each `perform_site_analysis`'s own separate reservation (up to 5). One run is a large fraction of the month, a few exhaust it — **and it is the same cap that funds Meera chat**. A creator who uses discovery silently loses Meera for the month. Three options given; default is the existing per-creator override, with the real per-run cost measured on staging first. |
| C20 | 15.6 | LOW | The three credit config keys are "inert until the ledger exists". Do not ship bound `@ConfigurationProperties` with no reader; keep them in the finance sheet until Phase C. |
| C21 | 15.2a | MEDIUM | "Follow-ups reuse the hook" left a `NOT NULL` column to a background job with no source. Specified: `OutreachFollowUpJob` copies `creator_hook` verbatim from the sequence-1 row for the same candidate; `HOOK_REQUIRED`/`HOOK_NOT_IN_BODY` apply to **sequence 1 only** — on a follow-up, `HOOK_NOT_IN_BODY` would refuse every reasonable one. |
| C22 | 15.2f | LOW | `PROMPT_VERSION` bump rule (0.10) confirmed to cover §15's persona lines (`config.py:69`, `creator_persona.py:22`/`:147`, rule stated at `persona.py:9`). Trap recorded: it is **one constant shared by both personas**, so a creator-side bump restamps brand turns; do not fork it. |

**Verified without correction:** the eight refusal-code names (all free); `CreatorTier`'s member names; `guarded_fetch`'s kwargs, exception contract, size-cap semantics and https/DNS-pinning sufficiency; the probe's position **after** §5.2 step 3's marketplace/social filter, so `instagram.com/products.json` is never fetched; `CampaignStatus.ACTIVE`; `CollaborationStatus.COMPLETED`; `Campaign.endBrandName`/`endBrandCategory` columns and entity fields; `check_creator_spend_gate` as the sole spend control; **and rule 0.2 — zero occurrences of "escrow" anywhere in §15**, with §15.5b's CTA using the approved "funds are secured".

**Contradiction read (the §14.6 job, done for §15's changes).** Seven pointers were inserted; six are correct and well placed (4.3 step 2, 4.5 refusals, 4.9 follow-up job row, 5.2 step 4, 8's day order, 11's cost, 12's rulings). The failures were not in the pointers but in the sentences §15 *claimed* to have updated directly: §7's boot test and §10's deliverables checklist both still said "ten migrations", and §14.5's E2 row still graded E2 INCOMPLETE on a gap §15.3 closes. All three now carry pointers. **§15 reproduced, inside the paragraph asserting it had avoided it, the exact failure mode §14.6 named.** That is not a reason to distrust §15; it is a reason to keep the end-to-end read on the pre-build checklist permanently rather than treating it as done.

### 16.2 Verdict on each mechanism

| § | Mechanism | Verdict | Why |
|---|---|---|---|
| **15.1** | Warmth ladder, platform brand cooldown, live reply rate | **WORKS-AS-CORRECTED** | The cooldown and the reply-rate line are the strongest additions in §15 and both build. W1's premise was false (C1) and its repository method was not a real signature (C2); with the names kept server-side and the cooldown read off the facts row (C18), the whole ladder stands. |
| **15.2** | Mandatory creator hook, evidence anchor, no platform tell, per-creator pause, brand opt-out | **WORKS-AS-CORRECTED** | The anti-convergence design is deterministic and unit-testable exactly as claimed — this is good engineering. Gaps were mechanical: follow-up hook provenance (C21), where codes live (C7), and an opt-out path that needed its ordering, its ceiling exemption, its `SecurityConfig` and rate-bucket entries (C8) and a schema fix to be insertable at all (C9). |
| **15.3** | Free discovery, refundable sends, referral bonus, brand-facts index | **WORKS-AS-CORRECTED**, with one open risk | The two-step pricing is right and the brand index is the correct shape for both cost and signal. But the internal call was unbuildable and unsigned (C5), the ledger claim overstated an absence (C6), the DDL was off-pattern, race-prone and three columns short (C10-C12), and the count was wrong (C14). **C19 is not corrected, it is escalated**: the premise that discovery fits inside the existing AI cap does not survive the arithmetic, and the cap it shares is Meera chat's. |
| **15.4** | Seeds, local mode, size rule, storefront probe, recency, outcome loop | **WORKS-AS-CORRECTED** | Seeds, recency and the outcome loop are clean. The tier read selected the wrong population (C3), the probe blocked the event loop and blew the timeout budget (C4), and the size rule had no producer so its refusal could never fire (C16). All three are fixes, not redesigns. |
| **15.5** | Wave sequencing, reframed ask, scorecard, kill rule | **WORKS-AS-CORRECTED** | Sequencing E4/E5/E6 ahead of cold email is the single best decision in this document and it costs nothing to adopt. The kill rule is a real rule with real thresholds. Only the cohort predicate needed making checkable (C17), which also surfaced that Wave 2 depends on Phase B **day 7**, not Phase B generally. |

### 16.3 New conditions and rulings

Conditions 1-4 (§13.3) and 5-7 (§14.6) stand unchanged. §15 adds two:

8. **Name the AI-cap decision before Wave 2's first run (C19).** Not a spec defect and not a redesign — it is a number nobody has computed. Measure the true per-run spend on staging (step 1's reservation plus the per-analysis reservations at the 5-analysis cap), then either raise the outreach cohort's `creator_agent_preferences.ai_monthly_cap`, give outreach its own bucket, or tell the creator in the UI that a run spends a third of the month's Meera budget. Shipping without deciding means a creator's chat dies mid-month with a `SpendCapExceeded` they cannot connect to anything they did.
9. **The end-to-end contradiction read is a standing gate, not a one-off (C14).** §14.6 asked for it once and §15 was written before it happened, so §15 both fixed old drift and added new. Do it after §16, and again after any section is added.

New **RULING** (Priya's, exercised here rather than deferred): **`outreach_brand_facts` uses a ULID surrogate PK with a unique domain key** (C10), and **all its counters are atomic `@Modifying` updates** (C11). Both are house pattern; neither is a judgement call and neither should reach Swapnil.

§15's own three RULINGS (pricing model, kill-rule thresholds, cohort size) are Swapnil's and stand as written, with the defaults §15 gives. C19 is **not** one of them — it is an engineering measurement that must precede the pricing ruling, because the pricing ruling is meaningless if step 1 already exhausts the creator's cap.

### 16.4 Final verdict

**Does each §15 mechanism work against the current code?** Four of five: **WORKS-AS-CORRECTED**. None is **DOES-NOT-WORK** — every defect found is a fix, not a redesign, and the two blocking ones (C1, C5) are each a paragraph of rewriting. §15's product judgement was better than its code verification, which is the expected shape for a section written as an argument; the argument survives the audit.

**Is §15 internally consistent and consistent with §0-§14?** Now yes. It was not: it contradicted **no** §13 or §14 correction (checked — the §13 site-analysis cap, the `guarded_fetch` no-status-code finding, the `system_flags` resolution, the alias-POST fix, the `RejectRequest` 500-char limit and the working-tree baseline are all respected, and §15.2f's 900-char band correctly narrows §4.5 rather than fighting it), but it **asserted three propagations that had not happened** and left §7, §10 and §14.5 disagreeing with it. Fixed. Against the seven conditions: §15 closes condition 5 (the run counter, now with the write-before-the-AI-call ordering it needed), touches none of the others, and adds two.

**Is it buildable and honest?** Buildable, yes, as corrected — thirteen migrations, one new internal POST endpoint, one new public opt-out pair, and no new architecture. **Honest, after C1 and C6.** Those two matter more than their severity ratings suggest: C1 justified a data flow with a claim about what creators can already see that was simply not true, and C6 declared an absence that a two-second grep contradicts. Both would have been believed. A spec that says "grep is empty" or "already visible" is asking the reader to skip the check — and in this document, three passes running, the claims most confidently asserted without a citation have been the ones most likely to be wrong.

**Buildable as corrected: yes**, on the four §13.3 conditions, the three §14.6 conditions, and the two above — with C19 measured before Wave 2 opens and the §14.4 rulings still due on their original days.
