1. Can a campaign creator trigger escrow release multiple times for the same deliverable by racing concurrent requests, or does the new outcome tracking definitively prevent double-release?

2. When a contract is canceled mid-flight with partial deliverables approved, does the escrow release calculation correctly account for what was already paid out versus what should be refunded, or can funds get stuck in an unreleased state?

3. If a brand tries to activate a campaign with maxCollaborators set to 50 but only funded for 10, does the escrow-funded gate actually block activation, and what happens to creators who were already hired before the gate was added?

4. Can a non-party to a contract (not the brand, not the creator) invoke the contract cancel or contract amend endpoint by guessing or manipulating the contractId, or are caller checks enforced at the controller layer?

5. When affiliate earnings are settled and a creator's portfolio rate card has been updated between deal creation and settlement, which rate is used for commission calculation, and can the timing of that update be exploited to inflate payouts?

6. If a deliverable has been revised 3 times (hitting the new revision limit), and then the contract is amended to extend the deadline, does the revision counter reset or does the creator stay locked out of further revisions?

7. For the admin bootstrap first-admin provisioning flow, if two users simultaneously try to claim the first admin role on a fresh install, can both succeed, or is there a database-level uniqueness constraint preventing duplicate admin grants?

8. When MFA is reset for an admin account, does the reset immediately invalidate all active sessions for that admin, or can a compromised session persist after the reset?

9. Are any of the "fixed" claims in these areas verified only by unit tests that mock the repository layer, or has every fix been tested against a real database where constraint violations and transaction rollbacks can actually occur?

10. Which of the following are explicitly NOT addressed in this batch: idempotency keys for escrow operations, audit trails for contract amendments, rate-limit enforcement on MFA reset attempts, and rollback behavior when an affiliate settlement partially fails mid-batch?
