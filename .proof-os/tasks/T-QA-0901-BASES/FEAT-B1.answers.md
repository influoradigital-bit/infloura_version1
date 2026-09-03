# FEAT-B1 Answers — Money & Core Infrastructure

Answered from the code by Priya Sharma (CTO), 2026-09-03. Feature docs in docs/docs/features are treated as a register of intent, not as evidence; where a doc claim contradicts the source, the source wins and the contradiction is named.

---

## Escrow

### 1. WORKS?
Yes. The hold is inserted PENDING and then immediately transitioned by applyFunding in the same transaction, so the row a caller reads back is already FUNDED — funding is not gated on a Razorpay round trip because the wallet balance check has already proved the money is on hand. The ledger movement is a single balanced posting that debits the brand workspace wallet and credits the platform clearing wallet for the gross hold amount, and the milestone is stamped funded. The creator notification is real and wired end to end: applyFunding publishes EscrowFundedEvent and NotificationListener consumes it after commit with a campaign-is-live message. Two caveats that do not change the yes: the contract gate only checks that both signature timestamps are non-null, not the enum status, and the notification is skipped silently when the hold has no resolvable collaboration.
`influora-api/src/main/java/com/influora/service/EscrowService.java:272` ".status(EscrowStatus.PENDING)"
`influora-api/src/main/java/com/influora/service/EscrowService.java:465` "hold.markFunded(outcome.fundTxnId());"
`influora-api/src/main/java/com/influora/service/escrow/LedgerEscrowBackend.java:57` "brandWallet.getId(),"
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:245` "Campaign is live!"

### 2. HOW?
The controller resolves the brand workspace and calls EscrowService.release, which delegates to releaseInternal and then to LedgerEscrowBackend.release. That backend calls PlatformFeeService.deductAtRelease first, which resolves the creator-side fee in basis points from the PlatformFeeConfig singleton and posts a PLATFORM_FEE movement from clearing to the revenue wallet; the net remainder is then posted as an ESCROW_RELEASE movement from clearing to the payee wallet. Note the premise is off by one: there are two ledger postings on this path, not three, each writing a debit leg and a credit leg. Two invoices are created — the creator-leg commission invoice (Doc#3b) inside deductAtRelease, and the creator service invoice (Doc#2) via safelyCreateServiceInvoice after the hold is marked released. Final hold status is RELEASED.
`influora-api/src/main/java/com/influora/service/escrow/LedgerEscrowBackend.java:77` "platformFeeService.deductAtRelease("
`influora-api/src/main/java/com/influora/service/PlatformFeeService.java:106` "commissionInvoiceService.createCreatorLegAtRelease("
`influora-api/src/main/java/com/influora/service/EscrowService.java:842` "hold.markReleased(outcome.releaseTxnId());"
`influora-api/src/main/java/com/influora/service/EscrowService.java:851` "safelyCreateServiceInvoice(hold, collaboration, outcome.releaseTxnId());"

### 3. WHY NOT AS ADVERTISED?
The doc is stale. The column IS mapped on the entity now as an enumerated, non-null field, and release does enforce it — assertReleaseConditionSatisfied runs before any money moves and compares every deliverable on the milestone's collaboration against the statuses that satisfy the condition. What is true is that no service ever writes a non-default value: the only place a milestone is constructed is ContractService, whose builder chain stops at dueDate and never calls releaseCondition, so the builder's fallback to ON_POSTED is what every row gets. Worse for the doc's claim of enforcement, the whole gate is disabled unless a cutover instant is configured, and it ships blank by default, so on a default deployment release checks only the FUNDED status, the cancellation guard and the dispute guard.
`influora-api/src/main/java/com/influora/domain/entity/PaymentMilestone.java:249` "m.releaseCondition = ReleaseCondition.ON_POSTED;"
`influora-api/src/main/java/com/influora/service/ContractService.java:311` ".dueDate(m.dueDate())"
`influora-api/src/main/java/com/influora/service/EscrowService.java:1329` "if (!isPostCutover(milestone)) {"
`influora-api/src/main/java/com/influora/service/EscrowService.java:1378` "if (releaseGateCutoverInstant == null || milestone.getCreatedAt() == null) {"

### 4. WHEN DOES IT BREAK?
The concrete trigger is the row lock, not a race that slips through. The second admin's normal release call cannot proceed at all: releaseInternal calls assertEscrowNotBlockedByDispute, which throws ESCROW_BLOCKED_BY_DISPUTE as soon as either the collaboration is DISPUTED or an OPEN/UNDER_REVIEW dispute row exists, and even if the dispute had already been closed the hold is FROZEN, so the FUNDED-status requirement rejects it with INVALID_ESCROW_STATE. Meanwhile the settlement path re-locks each hold with requireHoldForUpdate and re-checks FROZEN after the lock, so the split transaction serializes rather than double-pays. The hold ends RELEASED with sixty percent of ten thousand rupees in the creator wallet, the remainder refunded to the brand, and Doc#2 issued only for the creator leg. The genuine residual hazard is different from the one the question names: two disputes on the same collaboration use different idempotency key prefixes, so the ledger's uniqueness constraint would not dedupe them — the post-lock status re-check is the only thing preventing a second settlement from paying the same hold again.
`influora-api/src/main/java/com/influora/service/EscrowService.java:815` "assertEscrowNotBlockedByDispute(collaboration);"
`influora-api/src/main/java/com/influora/service/EscrowService.java:1428` "EscrowHold hold = requireHoldForUpdate(snapshot.getId());"
`influora-api/src/main/java/com/influora/service/EscrowService.java:1444` "if (hold.getStatus() == EscrowStatus.FROZEN) {"

### 5. WHAT IS MISSING?
The column exists on the table and is mapped on the entity, but nothing ever writes it — it is initialised to zero at wallet creation and never updated, because funding a hold moves only the balance column through the ledger. The brand dashboard does not read it. The summary derives the locked figure on read as a live SUM over the workspace's holds currently in FUNDED status, and the repository query that backs it says in its own javadoc that the column is dead. What is genuinely missing is any writer or reconciliation for the column, so any consumer that trusts it directly reads a permanent zero.
`influora-api/src/main/java/com/influora/domain/entity/Wallet.java:32` "private BigDecimal escrowBalance;"
`influora-api/src/main/java/com/influora/service/WalletService.java:162` "escrowHoldRepository.sumAmountByWorkspaceIdAndStatus(workspaceId, EscrowStatus.FUNDED);"
`influora-api/src/main/java/com/influora/repository/EscrowHoldRepository.java:34` "is a dead column (never written by any service"

---

## Wallet

### 6. WORKS?
Yes, with one correction to the premise. The top-up endpoint is OWNER/ADMIN gated and mints a Razorpay order whose receipt carries a topup prefix; the wallet is never credited there. The order-paid webhook routes to dispatchFundingEvent, which resolves the receipt prefix back to the top-up row and calls confirmCredited, which re-validates the webhook's captured amount and currency against the persisted row before moving anything. The ledger then writes exactly two rows sharing one group id — but the direction is clearing DEBIT and brand-workspace-wallet CREDIT, which is what the question describes, and the transaction type is DEPOSIT, not two separate ledger calls. The brand wallet balance increments by the full five thousand rupees; no fee is taken on this path.
`influora-api/src/main/java/com/influora/service/WalletTopUpService.java:161` "var order = razorpayClient.createOrder(amount,"
`influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java:99` "-> dispatchFundingEvent(event);"
`influora-api/src/main/java/com/influora/service/WalletTopUpService.java:214` "brandWallet.getId(),"
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:142` "String groupId = Ulids.newUlid();"

### 7. HOW?
Both rows are locked by requireWalletForUpdate, which issues a select-for-update through the repository. The order is deterministic and independent of direction: the two ids are compared lexicographically and the lower one is always taken first, so two postings touching the same pair in opposite directions cannot deadlock. After the locks, currencies are cross-checked and then the debit wallet's balance must be at least the amount — with one narrow exemption for the platform clearing wallet, which is allowed to go negative because it is a contra account. Idempotency has two layers: an upfront lookup by key, and, because that lookup can race, an insert-first attempt whose unique-constraint violation is caught and re-queried. A key replayed three times therefore posts once and returns the same two legs twice more, and a replay whose wallets, amount or type do not match throws rather than handing back the wrong movement.
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:102` "Wallet first = requireWalletForUpdate(firstId);"
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:99` "debitWalletId.compareTo(creditWalletId) <= 0 ? debitWalletId : creditWalletId;"
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:134` "&& debitWallet.getBalance().compareTo(amount) < 0) {"
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:192` "LedgerPostingResult raced = findExistingPosting(idempotencyKey);"

### 8. WHY NOT AS ADVERTISED?
Both halves of the doc are actually consistent, and both are true. The escrow-balance column returns 0.00 because it is only ever set to zero at wallet creation and never written again; the balance column returns whatever is left after the ledger debited thirty thousand rupees out to the clearing wallet, so the money genuinely sits in clearing. The dashboard number does not come from either column: the backend summary derives it as a live SUM over the workspace's FUNDED escrow holds, and the page renders that field straight into the tile labelled Funds Secured. The only real inconsistency is the label in the doc — the tile in the code says Funds Secured, not Funds in Escrow.
`influora-api/src/main/java/com/influora/domain/entity/Wallet.java:63` "w.escrowBalance = BigDecimal.ZERO;"
`influora-api/src/main/java/com/influora/service/WalletService.java:162` "escrowHoldRepository.sumAmountByWorkspaceIdAndStatus(workspaceId, EscrowStatus.FUNDED);"
`src/pages/brand-wallet.tsx:629` "escrowLocked: walletSummary.escrowLocked,"
`src/pages/brand-wallet.tsx:1075` "{formatCurrency(wallet.escrowLocked)}"

### 9. WHEN DOES IT BREAK?
It does not break here, and the premise slightly misstates the mechanism. Both postings touch the same two wallet rows and both take them in the same lexicographic id order, so the second one blocks on the first's locks rather than interleaving — there is no deadlock and no lost update. Neither fails: if the escrow leg wins, the brand wallet goes to five hundred and the top-up credit then lifts it to ten thousand five hundred; if the top-up wins, the brand wallet goes to eighteen thousand five hundred and the escrow debit brings it to the same ten thousand five hundred. The clearing wallet nets two thousand rupees lower than it started, which the code explicitly permits because the clearing wallet is exempted from the non-negative check. The one genuinely unguarded window is that the affordability check in initiateFund reads the wallet without a lock, so it can be stale — but the locked check inside the ledger post is authoritative and would raise INSUFFICIENT_BALANCE if the balance had actually dropped in between.
`influora-api/src/main/java/com/influora/service/EscrowService.java:250` "if (wallet.getBalance().compareTo(amount) < 0) {"
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:99` "String firstId = debitWalletId.compareTo(creditWalletId) <= 0"
`influora-api/src/main/java/com/influora/service/WalletLedgerService.java:134` "debitWallet.getBalance().compareTo(amount) < 0) {"

### 10. WHAT IS MISSING?
There is no dedicated withdraw-attempt table, and none is needed: the daily count is a COUNT over wallet_transactions filtered to this wallet, type WITHDRAWAL, created after the truncated-to-days instant — which is a UTC midnight boundary, matching the doc. The floor and ceiling are Java constants checked before anything else, and the daily cap is enforced before the bank account lookup and long before RazorpayX is called, under a pessimistic owner-row lock so two concurrent withdrawals cannot both pass the count. A fourth attempt in the same UTC day is rejected with a 429 and the withdrawal-rate-limit code; no ledger row, no Payout row, no gateway call. What is genuinely missing is that the count is derived from ledger rows rather than attempts, so a withdrawal rejected after the count but before the ledger post is not counted.
`influora-api/src/main/java/com/influora/service/WalletService.java:66` "private static final BigDecimal MIN_CREATOR_WITHDRAWAL = new BigDecimal("
`influora-api/src/main/java/com/influora/service/WalletService.java:280` "Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);"
`influora-api/src/main/java/com/influora/service/WalletService.java:282` "walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter("
`influora-api/src/main/java/com/influora/service/WalletService.java:288` "HttpStatus.TOO_MANY_REQUESTS);"

---

## Payouts

### 11. WORKS?
Yes, but the question conflates two rails. A creator clicking Withdraw to Bank hits the withdrawal path, not the milestone-payout path — that path debits the creator wallet through the ledger, resolves a real RazorpayX fund account from the creator's primary on-file instrument, calls initiatePayout, and persists a durable Payout row with a null milestone id, so nothing on payment_milestones is marked queued for a lump-sum withdrawal. The milestone-linked payout is brand-initiated through the escrow payout endpoint instead. Either way the persistent status is real: the processed webhook reaches confirmExecuted, which loads the row by the RazorpayX id and writes the new status onto it. One caveat: the withdrawal ceiling is one hundred thousand rupees, so a twelve-thousand-rupee withdrawal passes but a larger one would not.
`influora-api/src/main/java/com/influora/service/WalletService.java:397` "String fundAccountId = fundAccountService.resolveFundAccountId(userId, bankAccount.getId());"
`influora-api/src/main/java/com/influora/service/WalletService.java:399` "razorpayXClient.initiatePayout(fundAccountId, amount, wallet.getCurrency(), scopedKey);"
`influora-api/src/main/java/com/influora/service/PayoutReconciliationService.java:141` "payoutRepository.save(payout);"

### 12. HOW?
Validation happens in validateForPayout before the idempotency key is ever reserved. It loads the milestone by id from PaymentMilestoneRepository and then loads the escrow hold by the milestone's escrow-hold id from EscrowHoldRepository. Ownership is checked before state — the hold's workspace id must equal the caller's workspace, and a mismatch returns the same not-found code a nonexistent milestone would, so the endpoint is not a cross-tenant oracle. The field that marks it queued is not a status column at all: markPayoutQueued writes the derived payout key into the milestone's idempotency-key column, which is what the replay guard reads back. The fund account id passed to RazorpayX is the value returned by resolveFundAccountId for the creator's primary bank account — a cached Razorpay fund-account id when one is provisioned, otherwise one created lazily.
`influora-api/src/main/java/com/influora/service/PayoutService.java:342` "if (!hold.getWorkspaceId().equals(workspaceId)) {"
`influora-api/src/main/java/com/influora/service/PayoutService.java:517` "milestone.markPayoutQueued(idempotencyKey);"
`influora-api/src/main/java/com/influora/service/PayoutService.java:451` "String fundAccountId = fundAccountService.resolveFundAccountId(creatorUserId, bankAccount.getId());"

### 13. WHY NOT AS ADVERTISED?
The doc is wrong in the other direction — the table and the entity are both live. The migration creates the table with the status, gateway-id, fund-account and webhook-payload columns, and doQueuePayout persists a pending row before the wallet debit and before the gateway call, then saves it again once RazorpayX returns. The webhook method is not a no-op and it no longer lives on PayoutService at all: confirmExecuted is on PayoutReconciliationService, loads the row by the RazorpayX payout id, writes the status, and on a terminal failure triggers the wallet re-credit. What survives from the old world is a stale javadoc inside PayoutService itself, which still tells the reader that no payouts table exists in this slice — that comment contradicts code fifty lines below it and is exactly the kind of thing that produced the doc claim.
`influora-api/src/main/resources/db/migration/V48__payouts.sql:8` "razorpay_payout_id  VARCHAR(64)"
`influora-api/src/main/java/com/influora/service/PayoutService.java:472` "payoutRepository.save(payout);"
`influora-api/src/main/java/com/influora/service/PayoutService.java:382` "no separate {@code payouts} table exists in"

### 14. WHEN DOES IT BREAK?
It does not end in limbo, and the concrete mechanism is the failure-status branch. The reversed webhook reaches confirmExecuted, which records the new status and, because reversed is a terminal failure the row was not already in, calls the re-credit path — a fresh ledger posting from the clearing wallet back to the creator wallet for the payout's own amount, keyed on the RazorpayX payout id so a duplicate delivery cannot double-credit. So the creator's wallet debit is reverted; the milestone status is untouched and stays released, and its idempotency-key column still holds the original payout key, which means the creator or brand cannot simply re-fire the same milestone payout — the replay guard would return the old response. Recovery is admin-side: the finance console calls retryFailedPayout, which issues a new attempt under a fresh per-attempt key. The creator does see it — the payouts list marks rows whose status is in the failure set.
`influora-api/src/main/java/com/influora/service/PayoutReconciliationService.java:157` "reCreditReversedPayout(payout, razorpayPayoutId);"
`influora-api/src/main/java/com/influora/service/PayoutReconciliationService.java:522` "ledgerService.post("
`influora-api/src/main/java/com/influora/service/admin/AdminFinanceService.java:587` "Payout payout = payoutReconciliationService.retryFailedPayout(payoutId);"

### 15. WHAT IS MISSING?
Nothing is missing here; the doc is stale and the premise is false. The routes are not under a creator-bank-accounts path — they are on the wallet controller as payout-methods, with a GET that returns masked instruments, a POST that adds one through the encrypted bank-account service, and a PUT that promotes one to primary. The frontend calls all of them: the creator wallet page loads the list and submits new instruments through the same client. A creator can add a second bank account today; the only friction is that a newly added instrument is not usable for a payout until a twenty-four-hour cool-down elapses, and a new instrument does not become primary automatically.
`influora-api/src/main/java/com/influora/web/WalletController.java:213` "creatorBankAccountService.addInstrument("
`src/pages/creator-wallet.tsx:548` "const methods = await api.wallet.getPayoutMethods("
`src/pages/creator-wallet.tsx:627` "await api.wallet.addPayoutMethod("
`influora-api/src/main/java/com/influora/service/payout/RazorpayFundAccountService.java:69` "if (!bankAccount.isUsableAt(java.time.Instant.now())) {"

---

## Invoicing & GST

### 16. WORKS?
Yes, both documents are produced, though the fee figure in the question is not what the code uses — the creator-side rate comes from the platform fee config singleton, not a fixed fifteen percent. Doc#2 is minted inside createAtRelease with a statutory number, the creator's GSTIN copied onto the row, and a one percent TCS amount computed as a report-only figure that does not change the disbursed net; the PDF renders it as an explicit TCS line and carries no GST line at all. Doc#3's creator leg is minted inside the fee deduction with GST at eighteen percent of the commission. Both rows carry unique invoice numbers enforced at the column level, and both services expose a download that returns a time-limited presigned GET URL over the stored R2 key.
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoiceService.java:209` "BigDecimal tcsAmount = grossAmount.multiply(new BigDecimal("
`influora-api/src/main/java/com/influora/service/CommissionInvoiceService.java:192` "BigDecimal gstAmount = platformFee.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);"
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoicePdfService.java:100` "TCS @ 1% (ECO deduction, report-only):"
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoiceService.java:582` "return r2StorageService.presignGet(invoice.getPdfR2Key()).uploadUrl();"

### 17. HOW?
The public entry point is generateNext, not consumeNext — consumeNext is the increment on the sequence entity, called after the row is already locked. For the campaign-service series the locked row is the per-creator sequence row selected by the triple of series type, fiscal year and creator invoice code, via a repository method whose name declares the for-update intent. The generated string is the creator's invoice code, then a slash, then the fiscal year, then a slash, then the sequence zero-padded to six digits. Two concurrent releases for the same creator are serialized by that row lock: the second blocks until the first commits its incremented counter, so it reads the new value. First-ever issuance for a creator-and-year has no row to lock, and that race is caught by the composite unique constraint and re-fetched rather than duplicated.
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:114` ".findForUpdatePerCreator(seriesType, fiscalYear, creatorInvoiceCode)"
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:79` "int seq = sequence.consumeNext();"
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:83` "return prefix + "
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:128` "return sequenceRepository.saveAndFlush(created);"

### 18. WHY NOT AS ADVERTISED?
If Influora's configured GSTIN really begins with the same two digits as the brand's, the PDF shows CGST and SGST at nine percent each, not IGST — the split is a pure state-code comparison of the first two characters of the two GSTINs, and intra-state wins. The doc's claim only holds on an unconfigured deployment: the shipped default is a placeholder string whose first two characters can never equal a real numeric state code, which forces the inter-state branch for everything. Crucially the split is NOT persisted anywhere — the commission invoice row stores a single gst_amount column and no CGST, SGST or IGST columns — so the breakup is recomputed at render time from whatever the config holds when the PDF is generated, which means changing the company GSTIN silently re-splits already-issued invoices.
`influora-api/src/main/java/com/influora/service/GstSplitUtil.java:30` "boolean intraState = supplierState != null && supplierState.equals(customerState);"
`influora-api/src/main/resources/application.yml:355` "gstin: ${INFLUORA_COMPANY_GSTIN:REPLACE_WITH_REAL_GSTIN}"
`influora-api/src/main/java/com/influora/domain/entity/PlatformCommissionInvoice.java:67` "gst_amount"
`influora-api/src/main/java/com/influora/service/CommissionInvoicePdfService.java:131` "GstSplitUtil.compute(companyTaxProperties.getGstin(), customerGstin, invoice.getGstAmount());"

### 19. WHEN DOES IT BREAK?
The fiscal year is decided by the static helper currentFiscalYear, which reads today's date in Asia/Kolkata and rolls at April 1 — so the boundary is IST wall clock, not the database clock, and the database clock is irrelevant to it. The fiscal year IS part of the lock key: the per-creator lookup takes series type, fiscal year and creator code together, so a thread that computes 2026-27 and a thread that computes 2027-28 lock two different rows and do not serialize against each other at all. That is the concrete failure mode — at the instant of rollover both series can be advanced concurrently with no mutual exclusion, and if one thread computes the fiscal year a few milliseconds before midnight IST and the other just after, the two invoices land in different series with independent counters. Each series is still internally gapless, so this is a classification split rather than a duplicate number.
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:73` "String fiscalYear = currentFiscalYear();"
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:88` "LocalDate today = LocalDate.now(IST);"
`influora-api/src/main/java/com/influora/service/InvoiceNumberService.java:114` "findForUpdatePerCreator(seriesType, fiscalYear, creatorInvoiceCode)"

### 20. WHAT IS MISSING?
The premise names the wrong table — there is no creator_tax_identity table; the column is tax_registration_status on creator_profiles, defaulting to unregistered and set to GST-registered only when a GSTIN is supplied. And the doc is right that nothing reads it on the invoicing path: the Doc#2 service copies the creator's GSTIN onto the row but never branches on the registration status, and the Doc#2 PDF renderer emits only a service value and the report-only TCS line — there is no CGST, SGST or IGST rendering code in that renderer at all. So a GST-registered creator's Doc#2 is byte-for-byte identical to an unregistered creator's, which is the actual gap: the status is captured and stored but has no consumer anywhere in invoice generation.
`influora-api/src/main/java/com/influora/domain/entity/CreatorProfile.java:162` "tax_registration_status"
`influora-api/src/main/java/com/influora/domain/entity/CreatorProfile.java:216` "p.taxRegistrationStatus = CreatorTaxRegistrationStatus.UNREGISTERED;"
`influora-api/src/main/java/com/influora/service/CreatorTaxIdentityService.java:84` "gstin != null ? CreatorTaxRegistrationStatus.GST_REGISTERED : null;"
`influora-api/src/main/java/com/influora/service/CampaignServiceInvoicePdfService.java:95` "document.add(labeledLine("

---

## Platform Fees

### 21. WORKS?
Yes. Campaign publish calls chargeOnPublish inside the same transaction as the status flip; the fee base is the campaign's budgetMax and the rate is basis points resolved per workspace, so a Pro workspace is charged seven hundred basis points of one hundred thousand rupees, which is seven thousand. The movement is a PLATFORM_FEE posting from the brand workspace wallet to the platform revenue wallet, keyed idempotently per campaign so a double publish cannot double-charge. The publish is blocked when the balance is short — an explicit pre-check raises a payment-required error naming the shortfall, and the authoritative locked check inside the ledger catches anything that races past it. The brand-leg commission invoice is created after the posting succeeds, gated on the posting object rather than on the method having been called.
`influora-api/src/main/java/com/influora/service/CampaignService.java:348` "brandCampaignFeeService.chargeOnPublish(campaign, workspace.getId());"
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:194` "if (availableBalance.compareTo(fee) < 0) {"
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:235` "commissionInvoiceService.createBrandLegAtPublish(campaign, workspaceId, feeBps, fee, posting);"

### 22. HOW?
resolveBrandFeeBps delegates to a private helper that calls SubscriptionService.getActivePlanForWorkspace, which itself only returns the subscribed plan when the subscription row's status is ACTIVE and the plan row is still active, otherwise falling back to Free. The helper then returns the plan's own basis points only when the plan code is PRO and the value is non-null — the seeded Pro value being seven hundred. Anything else, including a Free plan, returns null and the caller falls through to the global config's brand basis points, seeded at one thousand. If the plan lookup throws, the catch logs a distinctive fail-open alert prefix and returns null, so the exception path also lands on the global one thousand — never on a guessed Pro rate. A null return from the lookup is therefore indistinguishable from a Free brand by design.
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:119` "if (plan != null && plan.getCode() == PlanCode.PRO && plan.getFeeBps() != null) {"
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:113` "return requireConfig().getBrandFeeBps();"
`influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:181` ".filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)"

### 23. WHY NOT AS ADVERTISED?
The doc is correct and the divergence is real. The brand fee endpoint is served by BrandPlatformFeeService.getCurrentFee, which reads the global config singleton's brand basis points and returns them alongside a hardcoded source label of GLOBAL_DEFAULT and a fixed ten-percent copy string that is a compile-time constant, not derived from the number it sits next to. A Pro brand therefore sees one thousand in the response while resolveBrandFeeBps charges them seven hundred at publish. The two paths do not share a resolver: the read path never consults SubscriptionService at all, and its own class javadoc says it deliberately bypasses the fee service to stay isolated. The copy string would also be wrong for a Free brand if an admin ever edited the global rate.
`influora-api/src/main/java/com/influora/service/BrandPlatformFeeService.java:53` "int feeBps = requireConfig().getBrandFeeBps();"
`influora-api/src/main/java/com/influora/service/BrandPlatformFeeService.java:58` "return new PlatformFeeResponse(feeBps, feePercent, SOURCE_GLOBAL_DEFAULT, BRAND_FEE_COPY);"
`influora-api/src/main/java/com/influora/service/BrandPlatformFeeService.java:36` "Platform fee (10%) — charged only when your campaign goes live."

### 24. WHEN DOES IT BREAK?
Seven thousand rupees at 14:32, twelve thousand at 14:36. At 14:32 the subscription is still ACTIVE on Pro, so the plan branch returns seven hundred basis points and the admin's edit to the global value is never read. At 14:35 the late webhook writes PAST_DUE; from that moment getActivePlanForWorkspace filters on status equal to ACTIVE and falls back to Free, so the plan code is no longer PRO, the helper returns null, and the publish at 14:36 reads the global config — now twelve hundred basis points — for twelve thousand rupees. The concrete trigger is the status filter, not any caching: the fee is resolved live on every publish, so there is no stale-rate window, only an abrupt one. Note also that a stale delivery is skipped entirely if a newer webhook event was already applied to the row.
`influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:181` "filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)"
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:119` "plan.getCode() == PlanCode.PRO && plan.getFeeBps() != null) {"
`influora-api/src/main/java/com/influora/service/BrandCampaignFeeService.java:113` "requireConfig().getBrandFeeBps();"

### 25. WHAT IS MISSING?
There is no database column for it. The fifteen percent lives as a Spring configuration property on RazorpayProperties with a Java field default of fifteen, bound from a config key that reads an environment variable and falls back to the same literal. It is therefore ops-settable at deploy time but NOT editable from the admin fee panel, which only writes the platform fee config row that drives the basis-point fees. AmountDerivationService is where it is applied: the base is the intent's product price multiplied by the creator count, the fee is that base times the percent divided by one hundred, and the estimate shown to the brand is base plus fee. So the AI preview number and the money actually charged at publish come from two entirely separate rate sources that nothing reconciles.
`influora-api/src/main/java/com/influora/config/RazorpayProperties.java:24` "private java.math.BigDecimal platformFeePercent = new java.math.BigDecimal("
`influora-api/src/main/resources/application.yml:364` "platform-fee-percent: ${PLATFORM_FEE_PERCENT:15.00}"
`influora-api/src/main/java/com/influora/service/AmountDerivationService.java:68` "BigDecimal feePercent = razorpayProperties.getPlatformFeePercent();"
`influora-api/src/main/java/com/influora/service/AmountDerivationService.java:72` "BigDecimal total = base.add(fee).setScale(2, RoundingMode.HALF_UP);"

---

## Billing & Subscriptions

### 26. WORKS?
Yes. The activated event is routed and mapped to an ACTIVE target status with a period re-sync, and the upsert either inserts a row carrying that status or updates the existing one. The plan is resolved from the Razorpay plan id and falls back to Pro when the id does not match a seeded row, so the workspace lands on Pro. Entitlements do take effect immediately, but by two different mechanisms: seats and the brand fee are derived live from the active plan on every read, while AI credits are a stored snapshot that would otherwise lag a month, so the webhook explicitly re-syncs the monthly allotment right after the write. The Pro seed row carries four hundred credits and five seats.
`influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java:127` "handleSubscriptionEvent(rawPayload, SubscriptionStatus.ACTIVE, true, false);"
`influora-api/src/main/resources/db/migration/V55__seed_billing_plans.sql:33` "400, 5, NULL, NULL,"
`influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:582` "reconcileAiCreditAllotment(workspaceId);"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:412` "Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);"

### 27. HOW?
The single webhook POST verifies the signature, parses the envelope, and switches on the event type to handleSubscriptionEvent, which parses the subscription-specific payload and refuses to act if either the subscription id or the workspace id from the payload notes is missing — a structurally unroutable shape is acknowledged rather than retried forever. Inside one explicit transaction it calls applySubscriptionWebhookUpdate. That method resolves the local row by Razorpay subscription id first and by workspace id second, because the workspace column is unique and the row may already exist as the lazily created Free-tier row. If a row with that Razorpay id already exists, this is the update branch: it re-links the id, changes the plan if it differs, sets the status, and — before any of that — skips the delivery entirely if a newer webhook event was already applied.
`influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java:310` "subscriptionService.applySubscriptionWebhookUpdate("
`influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:562` ".findByRazorpaySubscriptionId(razorpaySubscriptionId)"
`influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:610` "subscription.linkRazorpaySubscription(razorpaySubscriptionId);"
`influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java:614` "subscription.setStatus(targetStatus);"

### 28. WHY NOT AS ADVERTISED?
The doc is flatly wrong on this branch. There is one POST-mapped receive method on the controller, and its switch has an explicit arm for the activated event; that arm calls handleSubscriptionEvent, which calls applySubscriptionWebhookUpdate inside a transaction. Six subscription event types are routed in total — activated, charged, halted, pending, cancelled and completed. A brand who pays for Pro today therefore has an ACTIVE row five minutes later, assuming Razorpay delivered the webhook and its notes carried the workspace id; those two are the real preconditions, not a missing route. The only subscription event that still lacks a publisher is the one the listener comment names, and even the pending arm is now routed.
`influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java:89` "@PostMapping"
`influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java:127` "handleSubscriptionEvent(rawPayload, SubscriptionStatus.ACTIVE, true, false);"
`influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java:133` "handleSubscriptionEvent(rawPayload, SubscriptionStatus.PAST_DUE, false, false);"

### 29. WHEN DOES IT BREAK?
The premise cannot happen, and the reason is the comparison operator. The seat check counts active members plus unexpired pending invites and refuses when that total is greater than or equal to the plan's seat limit — so a Free workspace with its one OWNER row is already AT the cap and every one of those four invites is rejected with an upgrade-required payment error before an invite row is even created. Nobody accepts anything during the three hours. When the webhook finally lands the workspace becomes Pro with five seats and the OWNER can then invite four more. The count of active members immediately after the webhook is one. The seat limit is also re-checked at accept time, so an invite issued under Pro and accepted after a downgrade would be refused then rather than silently over-filling.
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:420` "if (activeMembers + pendingInvites >= plan.getSeatLimit()) {"
`influora-api/src/main/resources/db/migration/V55__seed_billing_plans.sql:23` "100, 1, 5, 1,"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:241` "enforceSeatLimit(invite.getWorkspaceId());"

### 30. WHAT IS MISSING?
Both listeners exist. NotificationListener has an after-commit async handler for the halted event and another for the payment-failed event, each sending a billing email to the resolved recipient. The doc claim of no listener is stale. The premise about the trigger is wrong though: nothing in this codebase reacts to a charged event carrying a failed status — the charged arm unconditionally maps to ACTIVE and generates an invoice. The failure signal Razorpay actually sends is the pending event, which maps to PAST_DUE and publishes the payment-failed event, and the halted event when retries are exhausted. So a payment failure does reach the workspace, but only via those two events, and the recipient is whoever the billing-recipient resolution picks, not necessarily the OWNER.
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:548` "public void on(SubscriptionHaltedEvent event) {"
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:570` "public void on(SubscriptionPaymentFailedEvent event) {"
`influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java:325` "publishSubscriptionPaymentFailedEmail(event.workspaceId(), event.subscriptionId());"

---

## Authentication

### 31. WORKS?
Yes. The creator login endpoint verifies the BCrypt hash, refuses suspended or deactivated accounts, refuses unverified accounts when verification is required, and additionally refuses a creator whose profile is suspended. It then issues a token pair. The controller writes the refresh token into an HttpOnly, Secure, SameSite cookie path-scoped to the auth routes and strips it from the JSON body before returning, so the response carries the access token and its expiry only. The access token is a signed JWT carrying the user type and email, minted with an HMAC key, and the resource server parses it with the same key — so yes, it authenticates the next call. The SameSite value is configuration-driven rather than a hardcoded Strict.
`influora-api/src/main/java/com/influora/service/AuthService.java:378` "if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {"
`influora-api/src/main/java/com/influora/web/AuthController.java:107` "authCookieService.writeRefreshCookie(response, pair.refreshToken());"
`influora-api/src/main/java/com/influora/web/AuthController.java:108` "pair.withoutRefresh()"
`influora-api/src/main/java/com/influora/security/AuthCookieService.java:53` ".httpOnly(true)"

### 32. HOW?
There is no single login taking a user type — there are two methods, and the creator one is creatorLogin. It loads the row with a case-insensitive email lookup on UserRepository. If that row's user type is not CREATOR it throws a WRONG_USER_TYPE error with a forbidden status, before the password is ever compared — which means the endpoint reveals that the email exists on the other side of the product. The encoder bean is a BCrypt encoder constructed with a cost factor of twelve. The access token is minted by JwtService.createAccessToken, which builds the claims and signs with the HMAC key derived from the configured access secret.
`influora-api/src/main/java/com/influora/service/AuthService.java:365` ".findByEmailIgnoreCase(req.email())"
`influora-api/src/main/java/com/influora/service/AuthService.java:375` "This endpoint is for creator accounts only"
`influora-api/src/main/java/com/influora/config/SecurityConfig.java:222` "return new BCryptPasswordEncoder(12);"
`influora-api/src/main/java/com/influora/security/JwtService.java:38` "return builder.signWith(accessKey()).compact();"

### 33. WHY NOT AS ADVERTISED?
The first half of the doc is stale — auto-refresh IS wired. The fetch wrapper detects a 401 on a request that carried an auth header, calls the refresh endpoint with credentials included so the HttpOnly cookie travels, stores the returned access token, and retries the original request exactly once; concurrent 401s for the same role are deduped into one refresh call. Only if the refresh fails does it clear the stale token and let the 401 surface. The second half is still true: the access token is read from and written to browser storage depending on a remember-me flag, so it remains script-readable and is exposed to XSS — the cookie hardening protects only the refresh token.
`src/lib/api.ts:525` "if (res.status === 401 && hasAuthHeader && !retried) {"
`src/lib/api.ts:448` "const res = await fetch("
`src/lib/api.ts:460` "if (!this.setToken(role, envelope.data.accessToken)) return null;"
`src/lib/api.ts:385` "return localStorage.getItem(TOKEN_KEYS[role]) ?? sessionStorage.getItem(TOKEN_KEYS[role]);"

### 34. WHEN DOES IT BREAK?
The backend accepts R1. Refresh tokens are independent rows keyed by hash, and the lookup only requires that the presented token is not revoked and not expired — logging in on Device B inserts a second row and revokes nothing, so there is no newest-token-wins rule and no reuse-detection family. R1 is therefore validated, the account-state gates re-run, and only then is R1 burned and a replacement minted, so Device A gets a new access token and a rotated cookie while Device B stays live. Mass revocation happens only on logout and password reset. The concrete break is elsewhere: because rotation revokes the presented token, two tabs on Device A racing a refresh will have one of them present an already-revoked token and be logged out — the frontend per-role in-flight dedupe is what prevents that, and it does not span tabs.
`influora-api/src/main/java/com/influora/service/AuthService.java:427` ".findByTokenHashAndRevokedFalse(hash)"
`influora-api/src/main/java/com/influora/service/AuthService.java:505` "stored.revoke();"
`influora-api/src/main/java/com/influora/service/AuthService.java:523` "refreshTokenRepository.revokeAllForUser(userId);"

### 35. WHAT IS MISSING?
There is no unlock endpoint — no admin route maps to anything that clears a lockout, and the only method that clears the MFA counter is an entity method invoked on a successful code verification, which a locked-out admin cannot reach. But the doc overstates the consequence: the lockout is not a sticky flag, it is an expiry instant set to now plus a configured cooldown, and the login path only rejects while that instant is still in the future. So the actual recovery procedure is to wait out the cooldown and then log in with a valid code; a direct database update is only needed if the cooldown is configured long or the TOTP secret itself is lost. Note the lockout is also indistinguishable from bad credentials in the response, so an admin gets no signal that waiting is the remedy.
`influora-api/src/main/java/com/influora/domain/entity/AdminUser.java:212` "this.mfaLockedUntil = now.plus(cooldown);"
`influora-api/src/main/java/com/influora/domain/entity/AdminUser.java:220` "public void resetFailedMfaAttempts() {"
`influora-api/src/main/java/com/influora/service/admin/AdminAuthService.java:134` "if (admin.isMfaLockedOut(now)) {"
`influora-api/src/main/java/com/influora/service/admin/AdminAuthService.java:205` "Duration.ofSeconds(adminSecurityProperties.getMfaLockoutCooldownSeconds()),"

---

## Workspaces & Members

### 36. WORKS?
Yes, on a plan with a free seat — and the path is the singular workspace prefix, not the plural one the question names. The invite method requires OWNER or ADMIN, refuses OWNER as an invited role, dedupes an existing pending invite into a resend rather than a second row, enforces the seat limit, and then saves a PENDING invite row carrying a hashed token and an expiry. The email is queued through the transactional outbox with an idempotency key when the invitee already has an account, and sent directly when they do not. Acceptance requires an authenticated caller whose own email matches the invite, re-checks the seat limit, and creates a member row whose active flag is set true with the invited role — so an ADMIN invite lands as an active ADMIN member.
`influora-api/src/main/java/com/influora/web/WorkspaceMemberController.java:51` "@PostMapping("
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:182` "queueInviteEmail(invite, rawToken, workspace, invitedUser);"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:243` "WorkspaceMember member ="
`influora-api/src/main/java/com/influora/domain/entity/WorkspaceMember.java:61` "m.active = true;"

### 37. HOW?
There is no separate role-checking service method — the check is BrandContextService.requireRole called inline at the top of inviteMember with OWNER and ADMIN as the permitted roles, after the workspace and the acting membership are both resolved from the principal rather than from a client-supplied workspace id. The seat check is the private enforceSeatLimit, called just before the invite row is inserted and again at accept time. That method loads the plan by calling SubscriptionService.getActivePlanForWorkspace — so the limit comes from the plan row reached through the ACTIVE subscription, not from the subscription row itself, and a lapsed subscription silently resolves to the Free plan's limit.
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:130` "brandContext.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN);"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:168` "enforceSeatLimit(workspaceId);"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:412` "Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);"

### 38. WHY NOT AS ADVERTISED?
The doc is out of date. The controller's PATCH handler reads the phone field off the request DTO and passes it as the third argument into the update service, which sanity-checks the format and rejects a malformed value with a validation error before writing. The service then calls updatePhone on the entity, which is mapped to a real nullable phone column added by a dedicated migration that explicitly says the field previously had no backing column and was deliberately left unpersisted until it was added. So the field is neither silently dropped nor UI-only; it round-trips exactly like the billing email does.
`influora-api/src/main/java/com/influora/web/WorkspaceController.java:73` "body.phone(),"
`influora-api/src/main/java/com/influora/service/WorkspaceService.java:122` "workspace.updatePhone(phone);"
`influora-api/src/main/java/com/influora/domain/entity/Workspace.java:60` "private String phone;"
`influora-api/src/main/resources/db/migration/V20260718180000__workspace_phone.sql:12` "ADD COLUMN phone VARCHAR(30) NULL AFTER billing_email;"

### 39. WHEN DOES IT BREAK?
The premise is false at the first step, and the answer to the parenthetical is no. The check is greater-than-or-equal, not greater-than, and it counts active members plus unexpired pending invites — a Free workspace with one OWNER already satisfies one is greater than or equal to one, so the ADMIN invite is rejected with an upgrade-required payment error and no invite row is created. There is therefore no second invite to evaluate and nothing to accept. The scenario only becomes possible on a plan with spare seats, and there the accounting is correct because pending invites occupy a seat at creation and the limit is re-checked at accept time — so an over-fill would be caught at whichever of the two points is reached second.
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:420` "activeMembers + pendingInvites >= plan.getSeatLimit()) {"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:168` "enforceSeatLimit(workspaceId);"
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:241` "enforceSeatLimit(invite.getWorkspaceId());"

### 40. WHAT IS MISSING?
The column exists on workspaces as a non-null enumerated verification status defaulting to unverified at creation, and the enforcement is real: CampaignValidator refuses to set a campaign ACTIVE unless the workspace is VERIFIED, returning a forbidden workspace-not-verified error. Requesting verification is the brand KYC submission in OnboardingService, which calls applyKyc and moves the workspace to PENDING; the decision is admin-only through AdminBrandService. The frontend does surface the state — a dedicated hook reads the status off the workspace endpoint and drives the KYC prompt. So nothing here is missing; what is thin is that there is no self-service resubmission after a rejection, only the same KYC endpoint.
`influora-api/src/main/java/com/influora/domain/entity/Workspace.java:47` "verification_status"
`influora-api/src/main/java/com/influora/service/CampaignValidator.java:63` "if (status == CampaignStatus.ACTIVE"
`influora-api/src/main/java/com/influora/service/OnboardingService.java:141` "workspace.applyKyc("
`src/hooks/brand/useWorkspaceVerification.ts:71` "const status = me.data?.verificationStatus ?? null;"

---

## Contracts & Milestones

### 41. WORKS?
Yes, with one honest caveat about ordering. Both signatures land through the same private write path, and the entity advances its own status, so the contract is ACTIVE the moment the second signature is recorded. Only then does the code generate the PDF, and only if R2 is configured does it store the bytes, set the R2 key column and mint a presigned link — the whole block is best-effort and swallows failures so a storage problem cannot roll back a recorded signature. The download endpoint exists and branches on the caller's user type so both parties can reach it, returning a freshly minted presigned URL rather than a stored one; it 404s until the PDF actually exists. If R2 is unconfigured the contract is still ACTIVE but the key column stays null and the download never succeeds.
`influora-api/src/main/java/com/influora/service/ContractService.java:782` "generateAndDeliverContractPdf(contract, milestones);"
`influora-api/src/main/java/com/influora/service/ContractService.java:869` "contract.setPdfR2Key(objectKey);"
`influora-api/src/main/java/com/influora/service/ContractService.java:871` "downloadUrl = r2StorageService.presignGet(objectKey).uploadUrl();"
`influora-api/src/main/java/com/influora/web/ContractController.java:120` "pdf-download-url"

### 42. HOW?
The fields that track who signed are the two signature timestamps plus the two signer-name fields; there is no signed-by set. Each signature setter calls a private advance method that recomputes the status from those two timestamps, so the sequence is DRAFT at build, PENDING_SIGNATURES after the first signature, and ACTIVE once both are non-null — and because the status is recomputed rather than incremented, it is self-correcting. The hash is produced by a private SHA-256 helper at generation time and it does not go into a terms column: it is written into termsJson, wrapped as a small JSON object. What is hashed is the request record's own toString, so the digest covers whatever fields that record renders — milestones and free-text terms included — and is therefore sensitive to the record's field order and formatting, not to a canonical document.
`influora-api/src/main/java/com/influora/domain/entity/Contract.java:215` "this.status = ContractStatus.PENDING_SIGNATURES;"
`influora-api/src/main/java/com/influora/domain/entity/Contract.java:213` "this.status = ContractStatus.ACTIVE;"
`influora-api/src/main/java/com/influora/service/ContractService.java:290` ".termsJson(sha256TamperHash(req))"
`influora-api/src/main/java/com/influora/service/ContractService.java:1071` "byte[] hash = digest.digest(req.toString().getBytes(StandardCharsets.UTF_8));"

### 43. WHY NOT AS ADVERTISED?
The doc is stale on the first half and right on the second. The entity does map the column — it is an enumerated non-null field annotated with that exact column name, and the release gate reads it. But no code path ever writes a non-default value: the only construction site is ContractService, whose builder chain ends at dueDate, and the milestone write DTO carries no condition field for a caller to supply. The builder's fallback then stamps ON_POSTED on every row. So a contract generated with milestones described as upon approval or upon delivery stores that phrasing only in the milestone description text; the machine-readable condition is uniformly ON_POSTED.
`influora-api/src/main/java/com/influora/domain/entity/PaymentMilestone.java:70` "release_condition"
`influora-api/src/main/java/com/influora/service/ContractService.java:311` ".dueDate(m.dueDate())"
`influora-api/src/main/java/com/influora/domain/entity/PaymentMilestone.java:249` "m.releaseCondition = ReleaseCondition.ON_POSTED;"

### 44. WHEN DOES IT BREAK?
The ACTIVE transition succeeds and nothing prevents the mismatch. The only amount validation in this whole flow runs once, at generation, and it does not compare against the campaign budget at all — it compares the milestone total against the collaboration's agreed rate, and only when that rate is non-null, and only as a not-to-exceed rather than an equality. The signature path performs no amount validation whatsoever: it checks the signer role, the already-signed short-circuit, and whether the collaboration was cancelled, then records the timestamp. So the concrete trigger is the unvalidated later write — editing the campaign budget in a different flow touches no contract row and fires no revalidation, leaving a fifty-thousand-rupee contract permanently attached to a sixty-thousand-rupee campaign with no reconciliation anywhere.
`influora-api/src/main/java/com/influora/service/ContractService.java:277` "&& totalAmount.compareTo(collaboration.getAgreedRate()) > 0) {"
`influora-api/src/main/java/com/influora/service/ContractService.java:723` "if (collaboration.getStatus() == CollaborationStatus.CANCELLED) {"
`influora-api/src/main/java/com/influora/domain/entity/Contract.java:213` "this.status = ContractStatus.ACTIVE;"

### 45. WHAT IS MISSING?
Nothing — the doc is wrong. The event is published from the private prompt method that runs right after both signatures land, and NotificationListener has an after-commit async handler for it that sends a secure-the-funds notification to the resolved brand owner with a link to the contract. So the brand does get told to fund. Two real limitations remain: the prompt is skipped entirely when the collaboration already has a FUNDED hold, and the whole publish is wrapped in a catch that logs and swallows, so a failure to resolve the brand owner silently produces no prompt at all while the signature still succeeds.
`influora-api/src/main/java/com/influora/service/ContractService.java:821` "new ContractReadyForEscrowEvent("
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:373` "public void on(ContractReadyForEscrowEvent event) {"
`influora-api/src/main/java/com/influora/service/notification/NotificationListener.java:376` "Secure the funds to get started"

---

## Disputes

### 46. WORKS?
Mostly, but the last clause fails. Opening a dispute first refuses outright when there is no funded unreleased escrow, then refuses a second active dispute, then freezes every funded hold BEFORE the dispute row is saved — deliberately, so a rollback between the two can never leave an OPEN dispute over releasable money — and finally transitions the collaboration to DISPUTED. So the hold moves to FROZEN, the collaboration to DISPUTED, and an OPEN dispute row is written. The brand notification does NOT happen: openDispute publishes no event, there is no dispute event type in the notification event package, and NotificationListener has no dispute handler. The counterparty learns about it only by looking.
`influora-api/src/main/java/com/influora/service/DisputeService.java:141` "escrowService.freezeUnreleasedForDispute(collaboration.getId());"
`influora-api/src/main/java/com/influora/service/DisputeService.java:142` "disputeRepository.save(dispute);"
`influora-api/src/main/java/com/influora/service/DisputeService.java:144` "collaboration.transitionTo(CollaborationStatus.DISPUTED);"

### 47. HOW?
The admin resolve path switches on the resolution and calls EscrowService.adminSplitForDispute with the creator percentage. The premise about three transactions is wrong — for each frozen hold there are at most two ledger movements plus the fee, and they are not the three the question lists. The creator share is computed and released through the same backend release primitive, which itself first posts the platform fee from clearing to the revenue wallet and then posts the net from clearing to the creator; the brand remainder is posted separately as a refund from clearing to the brand wallet. So on a forty-thousand-rupee hold at sixty percent there are three postings in total but the fee is taken out of the creator's twenty-four thousand, not the gross. Settlement runs before the status flip and the resolution is refused if fewer holds moved than were frozen; the dispute then ends in whichever terminal resolved status the admin chose.
`influora-api/src/main/java/com/influora/service/DisputeService.java:259` "escrowService.adminSplitForDispute("
`influora-api/src/main/java/com/influora/service/EscrowService.java:1135` "BigDecimal brandAmount = hold.getAmount().subtract(creatorAmount);"
`influora-api/src/main/java/com/influora/service/DisputeService.java:295` "if (settlements.size() < frozenHoldsBefore) {"

### 48. WHY NOT AS ADVERTISED?
The doc is accurate. The enum value exists, and the entity even has a guarded transition method that refuses to move anything but an OPEN dispute into it — but that method has zero callers anywhere in the service or web layers. No controller sets it, and the admin controller exposes only a list and a resolve. What the value IS used for is read-side: it appears in every active-dispute set, so anything that blocks on an active dispute would honour it if it could ever be reached. It is dead as a transition and live as a predicate, which is why a grep for the constant looks busier than the behaviour is.
`influora-api/src/main/java/com/influora/domain/enums/DisputeStatus.java:6` "UNDER_REVIEW,"
`influora-api/src/main/java/com/influora/domain/entity/Dispute.java:102` "public void markUnderReview() {"
`influora-api/src/main/java/com/influora/web/AdminDisputeController.java:73` "resolve"

### 49. WHEN DOES IT BREAK?
It checks first and moves no money. releaseInternal calls assertEscrowNotBlockedByDispute before the FUNDED-status requirement and long before any ledger call, and that guard throws on either of two independent signals — the collaboration being DISPUTED, or an OPEN or UNDER_REVIEW dispute row existing for it. The error is the blocked-by-dispute code with a conflict status, so the brand member gets a 409 and nothing partially succeeds; there is no roll-back path to reach because the frozen status check would also have refused. The ordering matters and is deliberate: putting the dispute guard before the status requirement is what produces the specific blocked-by-dispute code instead of a generic invalid-state one.
`influora-api/src/main/java/com/influora/service/EscrowService.java:815` "assertEscrowNotBlockedByDispute(collaboration);"
`influora-api/src/main/java/com/influora/service/EscrowService.java:1591` "if (disputeRepository.existsByCollaborationIdAndStatusIn("
`influora-api/src/main/java/com/influora/service/EscrowService.java:1599` "ESCROW_BLOCKED_BY_DISPUTE"

### 50. WHAT IS MISSING?
Correct — there is no such endpoint. The admin dispute controller exposes exactly two routes, a list and a resolve by path variable; nothing maps a bare id to a detail read. The admin UI is built around that gap explicitly rather than faking it: the list component's own header says the endpoint does not exist, and the modal renders the summary row the admin clicked plus a comment marking that as the only detail available before resolving. The richer record — reason, resolution notes, resolver and timestamp — only arrives as the response to the resolve call, so an admin cannot read a dispute's full reason before deciding it.
`influora-api/src/main/java/com/influora/web/AdminDisputeController.java:44` "@GetMapping"
`src/admin/components/disputes/DisputeList.tsx:234` "endpoint (see file header)"
`src/admin/components/disputes/DisputeList.tsx:11` "there is no"

---

## Uploads & Storage

### 51. WORKS?
Yes. The batch is rejected up front if the summed size exceeds one gigabyte, and each individual file is rejected above the configured per-file video cap, which defaults to exactly five hundred megabytes — so forty-five megabytes passes both. The declared content type is checked against an allowlist and then the actual bytes are sniffed and required to be both an allowed media type and compatible with what was declared. The upload streams: a limited stream wraps the raw multipart stream inside a digest stream and is handed to R2 directly, and the code never calls the multipart getBytes. The resulting key is stored in the deliverable's files JSON column, and the brand download re-mints a presigned GET whose lifetime is the configured presign expiry, defaulting to nine hundred seconds — fifteen minutes.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:955` "LimitedInputStream limited = new LimitedInputStream(raw, maxBytes);"
`influora-api/src/main/java/com/influora/config/R2Properties.java:15` "private long maxVideoBytes = 524_288_000L;"
`influora-api/src/main/java/com/influora/config/R2Properties.java:14` "private int presignExpirySeconds = 900;"
`influora-api/src/main/java/com/influora/domain/entity/Deliverable.java:64` "files_json"

### 52. HOW?
The wrapper is LimitedInputStream, constructed with the per-file cap and composed inside a DigestInputStream so the MD5 is computed during the same pass. The MIME validation is a private validateMime, which screens the declared type against an allowlist prefix set and then calls MediaMimeSniffer.detectMimeType on a fresh stream, requiring the sniffed type to be allowed and compatible with the declared one. The R2 call is putStream, taking the key, the digest-wrapped stream, the declared length and the content type. The key convention is not what the question guesses: it is the literal deliverables segment, then the deliverable id, then a v-prefixed version number, then a fresh ULID joined to the sanitised original filename — no user id and no timestamp.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:978` "String sniffed = MediaMimeSniffer.detectMimeType(file.getInputStream());"
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:957` "r2StorageService.putStream(key, digest, size, contentType);"
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:920` "deliverables/%s/v%d/%s-%s"

### 53. WHY NOT AS ADVERTISED?
Both doc claims are stale. There IS a controller mapped to the uploads path with a POST handler taking a multipart file and an optional purpose string, delegating to UploadService, and its own comment records that no route existed before that pass. And the frontend upload module is no longer a mock — its header documents that the fabricated R2 URL was replaced and that it now goes through the same uploads client call. So both endpoints are real and distinct: the generic uploads route is for profile and logo assets, while a deliverable goes to its own multipart route under the creator deliverables path. Neither is dead.
`influora-api/src/main/java/com/influora/web/UploadController.java:42` "@PostMapping"
`influora-api/src/main/java/com/influora/web/UploadController.java:33` "@RequestMapping("
`src/lib/upload.ts:11` "public-upload path the creator"
`influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java:72` "consumes = MediaType.MULTIPART_FORM_DATA_VALUE)"

### 54. WHEN DOES IT BREAK?
The object stays in R2 forever and nothing reclaims it. The upload method is transactional, so a rollback undoes the files JSON write — the column keeps whatever the previous version held — but the R2 PUT already happened outside the database and has no compensating delete on the failure path. The cleanup job does not help, and the reason is precise: it only ever deletes keys it reads back out of files JSON, and the orphan's key is by definition absent from it; that job's own javadoc already records that files JSON holds only the current version's keys and that per-version cleanup consequently finds nothing. It also defaults to dry-run. So the answer is stored, absent, and no. Note the scenario's own numbers hold — four hundred and eighty megabytes is under the five hundred megabyte per-file cap, so the limited stream is not what stops it.
`influora-api/src/main/java/com/influora/service/CreatorDeliverableService.java:218` "@Transactional"
`influora-api/src/main/java/com/influora/job/DeliverableCleanupJob.java:358` "r2StorageService.deleteObject(key);"
`influora-api/src/main/java/com/influora/job/DeliverableCleanupJob.java:55` "overwrites it on every new upload"

### 55. WHAT IS MISSING?
The method exists on R2StorageService with a key, content type and content length, and it builds a real presigned PUT. Nothing calls it: a repository-wide search finds references only inside that class — the method itself, its internal presign call, and a javadoc cross-reference from the sibling presigned-GET method saying it is not a client-upload flow. No controller returns a PUT URL to any client. So the direct-to-R2 upload intent is not partially built and unused; it is entirely unimplemented above the storage layer — there is no request DTO, no route, no client call, and no server-side completion step to record the key after a client-side PUT would have finished. Every upload in the product goes through the backend instead.
`influora-api/src/main/java/com/influora/integration/storage/R2StorageService.java:54` "public PresignResult presignPut(String objectKey, String contentType, long contentLength) {"
`influora-api/src/main/java/com/influora/integration/storage/R2StorageService.java:93` "this is not a client-upload flow"
`influora-api/src/main/java/com/influora/integration/storage/R2StorageService.java:143` "public PresignResult presignGet(String objectKey) {"
