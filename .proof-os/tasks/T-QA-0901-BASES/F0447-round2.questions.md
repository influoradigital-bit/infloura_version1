# F-0447 Round 2 QA Questions
## Feature: Creator pending-signature contracts list

1. If a contract shows "Awaiting your signature" in this list but the brand has already cancelled or withdrawn it server-side, does the row still appear, and what happens when the creator clicks through?

2. Does the backend GET /contracts/unsigned query exclude contracts where `creatorSignedAt IS NOT NULL` — or could a contract the creator already signed still appear in this "unsigned" list?

3. If a contract was created as a draft by the brand but never sent/finalized, does it appear in the creator's pending-signature list, or is there a status gate that prevents unsent drafts from showing?

4. What text appears in the row for a contract that has zero milestones defined — does it show "0 milestones", blank space, or does the row fail to render entirely?

5. If the creator has this dashboard page open and the brand sends them a new contract while they're looking at the list, does that new row appear automatically, or must the creator manually refresh the page?

6. Does each row's "View Contract" (or equivalent) link pass the correct contractId, and if the target contract-sign view is still loading or returns 404, what does the creator see — error boundary, infinite spinner, or a fallback message?

7. If a contract's `campaignName` or `brandName` field is null or an empty string in the database, what renders in the list row — does it show "Untitled", blank space, or does the row crash?

8. Is there any other creator-facing surface (notification, email, Meera chat context, profile sidebar) that also displays a count or list of pending contracts, and if so, does that surface pull from the same GET /contracts/unsigned endpoint or a different query?

9. If the creator navigates away from the dashboard and then returns (without a full page reload), does the pending-signature list re-fetch fresh data, or could it show a stale cached snapshot?

10. Does the list order contracts by any particular sort key (creation date, campaign name, brand name), and if two contracts have identical sort values, is the secondary ordering deterministic or does pagination/refresh cause rows to jump around?
