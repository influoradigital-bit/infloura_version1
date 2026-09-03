-- T-ADMINMAIL-0903 round 3, B1 (REVIEW-R2.md ship-blocker): the persisted rate limit in
-- AdminCustomEmailService.enforceRateLimit() was a bare read-then-decide against
-- admin_email_campaigns.created_at with nothing serializing two concurrent send() calls -- two
-- admins (or one admin double-submitting different copy, or two app instances behind the LB)
-- could both read the same "last campaign was N minutes ago" answer and both pass, because the
-- row that would close the window is not committed until the END of send(). Different
-- subject/body hashes to a different campaignId, so the idempotency UNIQUE constraint does not
-- save this either.
--
-- Fix: a singleton lock row taken with SELECT ... FOR UPDATE
-- (AdminEmailSendLockRepository#lockForUpdate) at the START of send(), held for the whole
-- transaction. MySQL/InnoDB's locking-read semantics mean a transaction only gets this lock AFTER
-- every prior holder has committed (or rolled back) -- but that locking-read guarantee applies
-- only to the row this lock takes, not to enforceRateLimit()'s later PLAIN read of a DIFFERENT
-- table (admin_email_campaigns). Under MySQL/InnoDB's default REPEATABLE READ, this transaction's
-- read view is pinned at its FIRST plain SELECT (requireRoleWithMfaSatisfied on admin_users,
-- before this lock is even acquired) -- so without a second fix, enforceRateLimit() could still
-- read that stale, pre-lock snapshot and miss a campaign the previous holder just committed,
-- even though the lock above genuinely serialized the two send() calls in time.
--
-- The second, load-bearing half of this fix is round 3's A2 (REVIEW-R3.md ship-blocker):
-- AdminCustomEmailService#send is annotated @Transactional(isolation = READ_COMMITTED), so every
-- plain SELECT in the method gets a fresh snapshot as of its own start -- which is what actually
-- makes enforceRateLimit()'s post-lock read see whatever the previous holder just committed. See
-- that method's and AdminEmailSendLock's javadoc for the full reasoning; a comment that only
-- describes the FOR UPDATE lock (as this one used to) is not sufficient on its own to guarantee
-- the property this migration exists for.
--
-- One fixed row, id = 'SINGLETON' -- there is exactly one admin.custom send path to serialize,
-- not one per admin/audience/whatever. VARCHAR(26) id (unused ULID width) matches this codebase's
-- usual PK shape (see AdminEmailCampaign.id) rather than introducing a new numeric-PK pattern.

CREATE TABLE admin_email_send_lock (
  id         VARCHAR(26) PRIMARY KEY,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO admin_email_send_lock (id, updated_at) VALUES ('SINGLETON', CURRENT_TIMESTAMP(3));
