-- Brand review clock (owner's ruling, 2026-09-21): a brand has 3 working days to act on a
-- submitted draft (2 on each resubmission). If it does not, the deliverable is escalated to the
-- Influora team. It is NEVER auto-approved and NEVER auto-paid.
--
-- review_escalated_at is the once-and-only-once guard for that escalation. It is stamped the
-- moment the team is told, and cleared again by Deliverable.applySubmit — so a new submission
-- (i.e. a revision round) starts a fresh clock that can escalate once more, and a round that has
-- already been escalated can never be escalated twice.
--
-- The durable record of an escalation is the support ticket the escalation opens; this column is
-- only the flag that stops a second one being opened for the same round.

ALTER TABLE deliverables ADD COLUMN review_escalated_at TIMESTAMP NULL;

-- The escalation sweep's candidate query is
--   status IN ('SUBMITTED','RESUBMITTED') AND review_escalated_at IS NULL AND submitted_at < ?
-- idx_deliverable_status alone leaves that scanning every deliverable ever submitted; this makes
-- it a range read over the small, genuinely-awaiting-review slice.
CREATE INDEX idx_deliverable_review_clock ON deliverables (status, review_escalated_at, submitted_at);
