# T-ADMINMAIL-0903 — Admin custom email send

**Routed by:** arjun · **Build:** vikram (backend), ananya (admin UI) · **Review:** kabir (security), priya (verdict)

## Why this is not "just unblock sendBulk"

`AdminEmailController.sendBulk` returns 501 on purpose. Its javadoc names five controls that must
exist *before* any real enqueue. They are requirements, not suggestions — a build that skips one is
rejected:

1. per-send rate limiting
2. a hard recipient cap
3. dry-run/preview + explicit confirmation
4. an audit trail of who sent what to how many
5. unsubscribe/consent enforcement

`sendBulk` keeps its 501 and its current signature. This feature ships as new endpoints.

## Fixed API contract — do not diverge

Both sides build against this. It is settled; raise a concern rather than changing it unilaterally.

```
POST /admin/emails/custom/preview
  req  { subject, bodyText, ctaLabel?, ctaUrl?, audience, sampleUserId? }
  res  { subject, html, recipientCount, capped, cap, sampleRecipientEmail }

POST /admin/emails/custom/send
  req  { subject, bodyText, ctaLabel?, ctaUrl?, audience, confirmRecipientCount }
  res  { campaignId, queued, skippedUnsubscribed }

audience = { userType: "CREATOR"|"BRAND"|"ALL", onlyVerified: boolean, registeredWithinDays: int|null }
```

Both are `SUPER_ADMIN` + MFA via `adminContext.requireRoleWithMfaSatisfied`.

**`confirmRecipientCount` is control #3 and is load-bearing.** Send recomputes the audience and
returns **409 `RECIPIENT_COUNT_CHANGED`** when it does not match what preview showed. That is what
makes an accidental blast impossible: you cannot send without having previewed, and you cannot send
a set that shifted under you.

## Personalization

Tokens in `subject` and `bodyText`, substituted per recipient at send time:

| token | source | fallback |
|---|---|---|
| `{{first_name}}` | `User.firstName` | `there` |
| `{{name}}` | `displayName` else `firstName` | `there` |
| `{{email}}` | `User.email` | — |

An unknown `{{token}}` is a **400 at preview and send**, never a literal `{{foo}}` delivered to a
person. Values are HTML-escaped on the way into the HTML part.

## Layout

`bodyText` is **plain text, never raw HTML** — admins do not hand-write markup and we do not
distribute unescaped admin input into mail clients. Blank-line-separated paragraphs render as
paragraphs, in the existing branded shell.

Reuse `EmailTemplateRegistry`'s shell (`wrapHtml`) so a custom send looks identical to every
transactional email — same header, gradient rule, CTA button, footer. Add a package-private
`renderCustom(...)` entry point beside `render(...)`; do **not** fork the shell markup, and do not
paragraph-wrap twice (`wrapHtml` takes a complete block; `para()` is the wrapper).

## Sending

- Enqueue one `EmailOutbox` row per recipient. Do not send inline — `EmailWorker` already drains,
  retries with backoff, and fails after 5 attempts.
- Idempotency key: `admin.custom:<campaignId>:<userId>`. The table has `UNIQUE (idempotency_key)`,
  so a double-submitted send is a no-op rather than a second copy.
- Template key `admin.custom`; the rendered subject/body ride in `templateData`.
- **Unsubscribe (control #5):** skip recipients unsubscribed via `EmailPreferenceRepository`, and
  report how many were skipped in `skippedUnsubscribed`. This is marketing mail — it must carry the
  unsubscribe footer, so `admin.custom` must NOT be added to `NO_UNSUBSCRIBE_FOOTER`.
- **Cap (control #2):** `influora.admin-custom-email.recipient-cap`, default 5000. Over the cap the
  send is refused, not silently truncated.
- **Rate limit (control #1):** `influora.admin-custom-email.min-interval-minutes`, default 10,
  enforced across all admins, persisted — not an in-memory counter that a restart clears.
- **Audit (control #4):** persist a campaign row (who, subject, audience, recipient count, when)
  and write `AuditLogService`. "Who sent what to how many" must be answerable from the database
  after a restart.

## Definition of done

- `mvn -o test` green for everything you touched, **plus a test that fails if the control is
  removed** — a test that passes with and without the code under it is worse than no test.
- Cover at minimum: the 409 mismatch path, unsubscribe skipping, the cap refusal, the rate limit,
  unknown-token rejection, and that personalization substitutes per recipient (two recipients get
  two different names).
- `npx tsc --noEmit` clean for the frontend.
- No secrets, no `console.log`, no raw admin HTML reaching the mail body.
