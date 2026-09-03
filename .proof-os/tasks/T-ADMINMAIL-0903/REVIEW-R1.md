# T-ADMINMAIL-0903 — zero-context review, round 1

Ten questions from a tester given the artifact only — no build reasoning, no author's summary.
Two were verified against the code before this file was written and are **confirmed defects**, not
open questions. The rest are unverified and may be fine; answer or fix each on the code.

## CONFIRMED — must fix before this touches an inbox

**C1 · A retried send delivers a second full copy to every recipient.**
`AdminCustomEmailService:224` mints `campaignId = Ulids.newUlid()` per request, and the idempotency
key is `admin.custom:<campaignId>:<userId>`. A double-clicked or retried `POST /custom/send`
therefore produces an entirely different key set, and `UNIQUE(idempotency_key)` never fires. The
comment at :248-250 claims the opposite in as many words — a comment that states a guarantee the
code does not provide is worse than no comment.
*This is a defect in the SPEC I wrote, not a deviation from it. The key format was specified without
working through that `campaignId` is per-request.* The fix must make the key deterministic for the
same logical send — derive it from the admin + subject + body + audience + confirmed count, or take
a client-supplied idempotency key — so a retry collapses instead of duplicating.

**C2 · A 5,000-row marketing blast delays every OTP behind it by ~50 minutes.**
`EmailOutboxRepository.findPendingForSend` orders `createdAt ASC` across *all* template keys with no
priority, and `EmailWorker` drains `BATCH_SIZE = 50` per 30-second poll with 50 sequential blocking
MSG91 calls. So ~100 rows/minute: an `auth.otp` or `auth.password_reset` row that lands behind a full
`admin.custom` send waits for the whole blast. Login and password reset are the two flows that must
never queue behind marketing. Needs a priority lane (transactional keys drain first) or a per-batch
cap on `admin.custom` rows — not merely a bigger batch size.

## UNVERIFIED — answer from the code, fix what is real

3. **Rate-limit TOCTOU.** `enforceRateLimit()` reads `findTopByOrderByCreatedAtDesc()` at the top of
   the same transaction that inserts its campaign row at the end, with no lock. Two concurrent
   requests, or two app instances, may both read the same stale "last campaign" and both pass.
4. **Nothing binds a send to a preview.** Control #3 compares only `confirmRecipientCount`. A caller
   hitting `/custom/send` directly with a guessed count ships a body nobody previewed.
5. **`recipientCount` vs rows actually enqueued.** The cap check, the 409 check and the audit row all
   use `countForCustomEmailAudience`, but recipients come from `findForCustomEmailAudience(...)`
   ordered by non-unique `u.createdAt` with no tiebreak. Nothing asserts the two agree; if they
   disagree the audit row is wrong.
6. **`ctaLabel` / `ctaUrl` are unvalidated.** `validateTokens` and `substituteTokens` cover only
   `subject` and `bodyText`. A `{{first_name}}` in `ctaLabel` ships literally, and `ctaUrl` is
   HTML-escaped but never scheme-checked before it becomes an `href` — `javascript:` and `data:`
   should be rejected.
7. **A serialization failure queues a guaranteed-blank email.** `serializeTemplateData` catches
   `JsonProcessingException`, logs, returns `"{}"`, and the row is still enqueued — `renderCustom`
   then renders an empty subject and empty body. Abort the send instead.
8. **Unsubscribe is resolved once at enqueue and never re-checked at dispatch.** The outbox can take
   a long time to drain, so someone who unsubscribes in that window still receives the mail. Also:
   the confirm dialog and `recipient_count` show the pre-unsubscribe number.
9. **`/custom/preview` is an arbitrary userId-to-email lookup.** `resolveSample` calls
   `findById(sampleUserId)` with no check that the user is in the audience or even ACTIVE, returns
   their address in `sampleRecipientEmail`, and mints a real working unsubscribe token for them.
   Preview writes no `AuditLogService` entry at all.
10. **No recall, and unclear rollback.** `AdminEmailCampaign` is insert-only with no endpoint to
    cancel PENDING `admin.custom` rows. If `saveAll` or `recordAdminAction` throws mid-method, is the
    campaign row / outbox rows / audit log left consistent?

## Bar for done

Every fix needs a test that goes RED when the fix is reverted. Report the injection and the failure
for C1 and C2 at minimum. "All 8 tests still pass" is not evidence a new control works.
