-- T-ADMINMAIL-0903 round 3, B5 (REVIEW-R2.md): admin_email_campaigns.recipient_count is the
-- PRE-unsubscribe audience size, but toEnqueue.size() (the number of rows actually written to
-- email_outbox) is what "how many" actually means for control #4 ("who sent what to how many").
-- The audit log's own "queued" detail already records the true figure, so the two records
-- silently disagreed. queued_count stores that same figure on the campaign row itself so
-- AdminCustomEmailService#replaySendResponse can read it back directly instead of
-- reconstructing it as recipient_count - skipped_unsubscribed (which is only correct if nothing
-- else about the audience math ever changes shape).
--
-- Backfill: every existing row's queued_count is set to the same reconstruction the code used to
-- do at read time, so no existing campaign's replay response changes.

ALTER TABLE admin_email_campaigns
  ADD COLUMN queued_count INT NULL AFTER skipped_unsubscribed;

UPDATE admin_email_campaigns
  SET queued_count = GREATEST(recipient_count - skipped_unsubscribed, 0)
  WHERE queued_count IS NULL;

ALTER TABLE admin_email_campaigns
  MODIFY COLUMN queued_count INT NOT NULL;
