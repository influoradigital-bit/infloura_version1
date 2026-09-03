# FEAT-B1 Questions: Money & Core Infrastructure Features

Generated: 2026-09-03
Batch: T-QA-0901-BASES
Tester: Kavya Reddy (QA Lead)

---

## Escrow (questions 1-5)

### 1. WORKS?
When a brand with a funded workspace wallet clicks "Fund Escrow" on a milestone whose contract is ACTIVE and signed by both parties, does the `escrow_holds.status` row transition from PENDING → FUNDED, does the brand wallet balance decrement by the gross amount, does the clearing wallet increment by that same amount, and does the creator receive a notification that the campaign is live?

### 2. HOW?
When EscrowController receives `POST /wallet/escrow/release` for a FUNDED hold, which service method computes the platform fee (clearing → revenue), which method writes the net amount to the creator wallet (clearing → creator), which invoice records are created, and what is the final `escrow_holds.status` value after all three ledger postings succeed?

### 3. WHY NOT AS ADVERTISED?
The doc says release is gated on `release_condition` and dispute status. The `payment_milestones` table has a `release_condition` column (V52). Is that column mapped on the PaymentMilestone entity, is it ever written by any service, and does EscrowService.release actually read and enforce any condition other than "dispute exists" and "status is FUNDED"?

### 4. WHEN DOES IT BREAK?
When a brand funds a milestone for ₹10,000, a dispute opens and freezes the hold (FUNDED → FROZEN), the admin resolves the dispute with a 60/40 creator split, and then — before the admin's resolution transaction commits — a second admin tries to release that same frozen hold via the normal release endpoint: which transaction wins, what status does the hold end in, what amount lands in the creator wallet, and which invoice gets issued?

### 5. WHAT IS MISSING?
The doc mentions `escrow_balance` on the `wallets` table as a field that should reflect locked funds. Does the wallet entity have that column, is it ever written when an escrow hold transitions to FUNDED, and does the brand dashboard's "Funds in Escrow" display read from that column or compute it some other way?

---

## Wallet (questions 6-10)

### 6. WORKS?
When a brand workspace member with OWNER role initiates a wallet top-up for ₹5,000, does the POST to `/wallet/topup` create a Razorpay order, does the webhook on `order.paid` write two `wallet_transactions` rows (one DEBIT from clearing, one CREDIT to the brand workspace wallet) sharing the same `group_id`, and does the brand wallet's `balance` column increment by exactly ₹5,000?

### 7. HOW?
When WalletLedgerService.post is called with a debit wallet, a credit wallet, an amount, and an idempotency key, which method locks the two wallet rows, in what order are they locked to prevent deadlock, what balance validation happens before the DEBIT leg is written, and what makes the posting idempotent if the same key is replayed three times?

### 8. WHY NOT AS ADVERTISED?
The feature doc says `wallets.escrow_balance` is "never written" and brand dashboard escrowLocked is always 0.00, yet the doc also claims "funds actually live in the clearing wallet." When a brand has three FUNDED escrow holds totaling ₹30,000, what does `SELECT balance, escrow_balance FROM wallets WHERE owner_type='WORKSPACE'` return for that brand's workspace wallet, and where in the code does the dashboard compute the "Funds in Escrow" number it shows?

### 9. WHEN DOES IT BREAK?
Two concurrent requests arrive at exactly the same time: one is a brand funding escrow for ₹8,000 (brand → clearing), the other is that same brand topping up their wallet for ₹10,000 (clearing → brand). The brand wallet starts at ₹8,500. Both transactions acquire their wallet locks in ascending id order. Which transaction commits first, does either one fail with INSUFFICIENT_BALANCE, and what are the final `balance` values of the brand wallet and clearing wallet after both complete?

### 10. WHAT IS MISSING?
The doc says withdraw has a floor of ₹500, a ceiling of ₹100,000, and a limit of 3 per UTC day. Is there a database table that tracks withdraw attempts per user per day, does WalletService.withdraw actually enforce that 3/day limit before calling RazorpayXClient, and what happens on the 4th withdraw attempt in the same UTC day?

---

## Payouts (questions 11-15)

### 11. WORKS?
When a creator with a bank account on file and a RELEASED milestone worth ₹12,000 net clicks "Withdraw to Bank," does the payout flow deduct ₹12,000 from their wallet, call RazorpayX with the correct fund_account_id, mark the milestone's payout as queued, and does the `payout.processed` webhook eventually set some persistent status to "completed"?

### 12. HOW?
When PayoutService.queuePayout is called with a milestoneId, which repository method loads the milestone and its escrow hold, what ownership validation occurs, which field on `payment_milestones` is set to mark it queued, and what actual value does the code pass as the `fund_account_id` parameter to RazorpayX.initiatePayout?

### 13. WHY NOT AS ADVERTISED?
The doc says there is a `payouts` table (V48) with columns for status tracking, and that payout state lives on that table. Is the Payout entity actually used anywhere in PayoutService, does any code path write a row to that table when a payout is queued, and does the `payout.processed` webhook's `confirmExecuted` method update a row in that table or is it truly a no-op?

### 14. WHEN DOES IT BREAK?
A milestone is RELEASED with net ₹15,000 in the creator wallet. The brand queues a payout via `POST /wallet/escrow/payout`. RazorpayX accepts it and returns 200, but the webhook for `payout.reversed` arrives 2 hours later indicating the bank account was closed. Does any code path revert the creator wallet debit, does the milestone status change, does the creator see an error, and can they retry the payout or is the money now in limbo?

### 15. WHAT IS MISSING?
The doc says `RazorpayFundAccountService` and `CreatorBankAccountService` exist but are orphaned, and that "no creator-facing bank-instrument endpoints" are routed. Does the HTTP layer have any `@PostMapping` or `@GetMapping` method that maps to `/creator/bank-accounts` or similar, does the frontend CreatorWalletPage call any API to list the creator's saved bank accounts, and can a creator add a second bank account via the UI today?

---

## Invoicing & GST (questions 16-20)

### 16. WORKS?
When an escrow release completes for a ₹50,000 milestone with a 15% platform fee, does the system generate Doc#2 (campaign service invoice from creator to brand, no GST, 1% TCS notation) AND Doc#3 (platform commission invoice from Influora to creator, 18% GST split into CGST/SGST or IGST), write both to their respective tables with unique invoice numbers, and make both PDFs available via presigned download URLs?

### 17. HOW?
When InvoiceNumberService.consumeNext is called for Doc#2 with a creator's invoice code "ABC123" in FY 2026-27, which database row is locked with SELECT...FOR UPDATE, what is the format of the generated invoice number (prefix + FY + sequence), and what prevents two concurrent releases for the same creator from getting duplicate sequence numbers?

### 18. WHY NOT AS ADVERTISED?
The doc says GstSplitUtil computes intra-state CGST+SGST (half/half) or inter-state IGST based on supplier and customer GSTINs, and that the platform uses a "placeholder company GSTIN" that forces IGST for everything. When a brand in Maharashtra (GSTIN 27...) receives Doc#3 and Influora's GSTIN is also 27... (same state), does the invoice PDF show CGST ₹X + SGST ₹X, or does it show IGST ₹2X, and is that split persisted in `platform_commission_invoices` columns or recomputed at render?

### 19. WHEN DOES IT BREAK?
Two escrow releases for the same creator happen concurrently at 23:59:58 IST on March 31, 2027 (fiscal year boundary). Both threads call InvoiceNumberService.consumeNext for Doc#2 within the same second. One should get FY2026 sequence and one FY2027 sequence. Which service method determines the fiscal year, is the FY part of the SELECT...FOR UPDATE lock key, and what happens if both threads think they are in FY2026 but the database clock has already rolled to April 1?

### 20. WHAT IS MISSING?
The doc says `CreatorTaxRegistrationStatus` is stored but unused, and that Doc#2 emits no GST for registered creators even when it should. Does the `creator_tax_identity` table have a `registration_status` column, is there any code path in CampaignServiceInvoiceService that reads that status, and when a creator is GST-registered does Doc#2's PDF generation logic add CGST/SGST/IGST line items or does it always render as "No GST"?

---

## Platform Fees (questions 21-25)

### 21. WORKS?
When a brand on the Pro plan (7% brand fee) publishes a campaign with budgetMax ₹100,000, does the platform deduct ₹7,000 from the brand's workspace wallet, credit ₹7,000 to the revenue wallet, generate a brand-leg commission invoice (Doc#3), and prevent the publish if the brand wallet balance is below ₹7,000?

### 22. HOW?
When BrandCampaignFeeService.resolveBrandFeeBps is called for a workspace that has an ACTIVE Pro subscription, which repository or service method is called to determine the plan, what is the numeric `feeBps` value returned (700 or 1000), and what fee does the code apply if the plan-resolution query throws an exception or returns null?

### 23. WHY NOT AS ADVERTISED?
The doc says brand/creator fee view endpoints report a hardcoded 10% / GLOBAL_DEFAULT even for a Pro brand actually charged 7%. When a Pro brand calls `GET /brand/platform-fee`, what does the response `feeBps` field contain (700 or 1000), where in BrandPlatformFeeController or PlatformFeeService does that value come from, and is it the same value used by resolveBrandFeeBps during actual charge?

### 24. WHEN DOES IT BREAK?
A brand is on the Pro plan (7% fee). An admin edits the global `brand_fee_bps` from 1000 to 1200 at 14:30. At 14:32, the brand publishes a campaign with budgetMax ₹100,000. At 14:35, the Pro plan's subscription webhook fires (late delivery) and marks the subscription PAST_DUE, so the workspace effectively loses Pro status. What fee was charged at 14:32 (₹7,000, ₹10,000, or ₹12,000), and if the brand tries to publish another campaign at 14:36, what fee is charged then?

### 25. WHAT IS MISSING?
The doc mentions an "AI campaign-intent fee" of 15% that AmountDerivationService adds to the AI budget preview, and that this is a "separate percent, not the bps fee." Is there a database column anywhere that stores this 15% value, is it configurable via the admin fee panel or is it hardcoded, and when Meera shows a brand "Estimated cost ₹23,000" for a campaign, where in the code does that 15% get added to the base price?

---

## Billing & Subscriptions (questions 26-30)

### 26. WORKS?
When a brand workspace OWNER clicks "Subscribe to Pro" on the pricing page, completes the Razorpay hosted checkout, and Razorpay fires the `subscription.activated` webhook, does the code write a row to the `subscriptions` table with status ACTIVE, does it set the workspace's current plan to PRO, and do the new entitlements (400 AI credits, 5 seats) take effect immediately?

### 27. HOW?
When RazorpayWebhookController receives a `subscription.activated` event payload, which method in SubscriptionService is called, how does it map the `notes.workspaceId` from the Razorpay payload to the local workspace, what status transition occurs on the local subscription row, and what happens if a subscription row with that `razorpay_subscription_id` already exists?

### 28. WHY NOT AS ADVERTISED?
The doc says "subscription `*` webhooks are not routed in RazorpayWebhookController" and that "real Pro purchases never create a local ACTIVE row." Does RazorpayWebhookController have a method annotated with `@PostMapping` that handles event type `subscription.activated`, does that method call SubscriptionService.applySubscriptionWebhookUpdate, and if a brand pays for Pro today, what is the actual subscription status (`SELECT status FROM subscriptions WHERE workspace_id=?`) 5 minutes after payment?

### 29. WHEN DOES IT BREAK?
A brand on the FREE plan has 1 active member. They subscribe to Pro (5 seat limit). The `subscription.activated` webhook is delayed by 3 hours due to Razorpay downtime. During those 3 hours, the OWNER invites 4 more members (total 5). All 4 accept. Then the webhook finally fires and the plan transitions to Pro. Are all 5 members active, does the 5th invite fail because the seat-limit check ran while still on FREE (1 seat), and what does `SELECT COUNT(*) FROM workspace_members WHERE active=true` return after the webhook lands?

### 30. WHAT IS MISSING?
The doc says `SubscriptionHaltedEvent` and `SubscriptionPaymentFailedEvent` both "currently have no listener." Is there an `@EventListener` method anywhere in the codebase (NotificationService, EmailService, or otherwise) that handles those two event types, and when a Pro subscription payment fails and Razorpay fires `subscription.charged` with `status=failed`, does any notification reach the brand workspace OWNER?

---

## Authentication (questions 31-35)

### 31. WORKS?
When a creator enters their email and password on the login page, clicks "Sign In," the backend verifies the BCrypt hash matches, the account is email-verified and not suspended, does the response include a valid HS256 access token in the JSON body and a HttpOnly SameSite=Strict refresh token in a Set-Cookie header, and can that access token authenticate the next API call to `GET /creator/profile`?

### 32. HOW?
When AuthService.login is called with email, password, and userType CREATOR, which repository method loads the user row, what exception is thrown if the email exists but `user_type='BRAND'`, what BCrypt configuration (cost factor) is used for the hash comparison, and which JwtService method mints the access token that goes in the response body?

### 33. WHY NOT AS ADVERTISED?
The doc says "frontend `/auth/refresh` not called (sessions break on expiry)" and "localStorage token XSS exposure." When the access token in localStorage expires (default TTL), does the frontend `api.ts` HTTP interceptor detect the 401, call `POST /auth/refresh` with the httpOnly cookie, get a new access token, retry the original request, and persist the new token, or does the user just get logged out and redirected to `/login`?

### 34. WHEN DOES IT BREAK?
A brand user logs in on Device A, gets refresh token R1 (stored in cookie), and access token A1 (in localStorage). Then they log in on Device B, which issues refresh token R2. One hour later, on Device A, the access token expires. The frontend does NOT have auto-refresh wired. The user manually calls `POST /auth/refresh` with cookie R1. Does the backend accept R1 (it's still valid, not yet rotated), issue a new access token A3, and rotate R1 to R1', or does it reject R1 because a newer token R2 was issued from a different device?

### 35. WHAT IS MISSING?
The doc says "admin lockout recovery is DB-only" after too many failed TOTP attempts, and "no in-app recovery." Is there an HTTP endpoint (e.g. `POST /admin/auth/unlock`) that an admin can call to reset their own MFA lockout counter, does AdminAuthService have a method to clear the lockout flag, and if an admin is locked out, what is the actual procedure to regain access without direct database UPDATE?

---

## Workspaces & Members (questions 36-40)

### 36. WORKS?
When a brand workspace with 1 OWNER and 0 other members receives an invite for a new ADMIN via `POST /workspaces/members/invite`, does the code create a `workspace_member_invites` row with status PENDING, send an email to the invitee with an acceptance link, and when the invitee clicks that link and accepts, does a `workspace_members` row get created with role ADMIN and active true?

### 37. HOW?
When WorkspaceMemberService.inviteMember is called with a role and email, which service method checks that the current user is OWNER or ADMIN, which method checks that adding one more member does not exceed the plan's `seatLimit`, and where does the code load the workspace's current subscription to read that limit?

### 38. WHY NOT AS ADVERTISED?
The doc says `PATCH /workspaces/me` persists "name/email/websiteUrl" but "no `phone` column exists anywhere, stays UI-only." Does the WorkspaceController method that handles PATCH read a `phone` field from the request DTO, does it silently drop that field or does it return an error, and is there a `phone` column on the `workspaces` table (check the latest migration)?

### 39. WHEN DOES IT BREAK?
A workspace on FREE plan (seatLimit 1) has 1 OWNER. The OWNER invites an ADMIN. The seat-limit check passes (1 active + 1 pending = 1 occupied, limit 1... does it?). The invite is created. The OWNER then invites a MANAGER before the ADMIN accepts. Does the second invite creation pass the seat-limit check, and when the ADMIN finally accepts, does the workspace now have 1 OWNER + 1 ADMIN + 1 pending MANAGER, or does one of those transitions fail?

### 40. WHAT IS MISSING?
The doc says the workspace must be VERIFIED to set a campaign ACTIVE. Is there a database column `workspaces.verification_status` (or similar), does the campaign-publish or campaign-activate code path actually read and enforce that a workspace must be VERIFIED before allowing ACTIVE status, and what UI or API allows a workspace to request verification or shows them their current verification state?

---

## Contracts & Milestones (questions 41-45)

### 41. WORKS?
When a brand generates a contract with 3 milestones totaling ₹75,000, signs it, the creator signs it, does the contract transition to ACTIVE, does a PDF get generated and uploaded to R2, does the `contracts.pdf_r2_key` column get populated, and can both parties download that PDF via `GET /contracts/{id}/pdf-download-url` which returns a presigned R2 URL?

### 42. HOW?
When ContractService.recordSignature is called twice (brand signs, creator signs), which entity field tracks who has signed, what is the exact status transition sequence (DRAFT → PENDING_SIGNATURES → ACTIVE?), and which method generates the SHA-256 tamper hash that goes into `contracts.terms`, and what text is actually hashed?

### 43. WHY NOT AS ADVERTISED?
The doc says `release_condition` (V52) exists on `payment_milestones` but is "unmapped on the entity." Does the PaymentMilestone JPA entity have a field annotated `@Column(name = "release_condition")`, and when a contract is generated with milestones that have conditions like "upon approval" or "upon delivery," does any code path write a non-null value to that column?

### 44. WHEN DOES IT BREAK?
A brand generates a contract for ₹50,000 with 2 milestones (₹30k, ₹20k). The brand signs it. Before the creator signs, the brand edits the campaign budget to ₹60,000 in a different flow. The creator then signs the contract. Does the contract ACTIVE transition succeed, does the sum of milestone amounts (₹50k) still match the original campaign budget (now ₹60k), and does any validation prevent this mismatch?

### 45. WHAT IS MISSING?
The doc says `ContractReadyForEscrowEvent` is emitted when both parties sign, but "this event currently has no listener." Is there a method annotated `@EventListener` or `@TransactionalEventListener` anywhere in the codebase that consumes ContractReadyForEscrowEvent, and when a contract goes ACTIVE, does any notification or UI prompt actually appear telling the brand "Your contract is ready — fund escrow now"?

---

## Disputes (questions 46-50)

### 46. WORKS?
When a creator opens a dispute on a collaboration that has a FUNDED escrow hold of ₹40,000, does the `escrow_holds.status` immediately change to FROZEN, does the `collaborations.status` change to DISPUTED, does a `disputes` row get created with status OPEN, and does the brand receive a notification that a dispute was opened?

### 47. HOW?
When an admin resolves a dispute by selecting "60% to creator" on a ₹40,000 frozen hold, which EscrowService method is called, what are the three wallet transactions written (clearing → revenue for fee, clearing → creator for net share, clearing → brand for remainder), and what is the final `disputes.status` after the resolution commits?

### 48. WHY NOT AS ADVERTISED?
The doc says `UNDER_REVIEW` status is "defined but unreached" and "no endpoint triggers it." Is there a `DisputeStatus.UNDER_REVIEW` enum value, does any controller method ever set `dispute.status = UNDER_REVIEW`, and is there a UI or API path that is supposed to move a dispute from OPEN → UNDER_REVIEW before an admin resolves it, or is that status truly dead code?

### 49. WHEN DOES IT BREAK?
A collaboration has a ₹50,000 FUNDED hold. A creator opens a dispute at 10:00am (hold → FROZEN). At 10:05am, unaware of the dispute, a brand member calls `POST /wallet/escrow/release` on that same hold. Does the release endpoint check for an open dispute BEFORE attempting the clearing→creator transfer, does it return `ESCROW_BLOCKED_BY_DISPUTE` (409), or does the release partially succeed and then roll back when it discovers the FROZEN status?

### 50. WHAT IS MISSING?
The doc says there is no `GET /admin/disputes/{id}` endpoint. Does AdminDisputeController have a method that accepts a path variable `{id}` and returns the full dispute details (collaboration, parties, escrow amount, reason, opened date), or does the admin UI only have access to the dispute list (`GET /admin/disputes`) and must open a modal with client-side filtering to view one dispute's details?

---

## Uploads & Storage (questions 51-55)

### 51. WORKS?
When a creator uploads a 45MB video file as a deliverable via the deal room UI, does the file pass the size cap check (500MB), get MIME-sniffed for magic bytes, stream to R2 without buffering the whole file in memory, and land in `deliverables.files_json` as a JSON array entry with an R2 key, and can the brand then download that video via a presigned GET URL valid for 15 minutes?

### 52. HOW?
When CreatorDeliverableService receives a multipart upload, which class wraps the InputStream to enforce the size limit (LimitedInputStream?), which method calls MediaMimeSniffer to validate the content type, and which R2StorageService method is invoked to stream the bytes to Cloudflare, and what is the naming convention for the R2 object key (prefix + userId + timestamp + random?)?

### 53. WHY NOT AS ADVERTISED?
The doc says `src/lib/upload.ts` is mock code and `POST /uploads` has no backend controller, yet the frontend has an `http.upload` method in `src/lib/api.ts`. Does the real upload flow POST to `/uploads` or to a domain-specific endpoint like `/creator/deliverables/{id}/upload`, is there a controller method annotated `@PostMapping("/uploads")` anywhere, and if so, does it route to a generic UploadController or is it dead?

### 54. WHEN DOES IT BREAK?
A creator uploads a 480MB deliverable video at 23:58 UTC. The upload takes 3 minutes. The LimitedInputStream allows it through (under 500MB). The stream reaches R2 at 00:01 UTC. R2 accepts the PUT and returns 200. The service writes the key to `files_json`. But then the database transaction rolls back due to an unrelated constraint violation (e.g. a concurrent update). What is the state of the R2 object (is it stored?), what is in `files_json`, and does the cleanup job eventually delete that orphaned R2 key?

### 55. WHAT IS MISSING?
The doc says `presignPut` is dead code with "no client-PUT flow." Does R2StorageService have a method named `presignPut` that generates a presigned PUT URL, is there any controller method that returns such a URL to the client, and if the intent was to let clients upload directly to R2 (bypassing the backend), is that flow completely unimplemented or just unused?

---

END OF QUESTIONS
