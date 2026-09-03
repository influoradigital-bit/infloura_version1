# Swapnil -> Priya · Area 1 of 5 · FEATURES BASE · 100 questions

Answer every question from the actual code. Cite `path/to/file.ext:LINE` and quote the
exact span. If a feature is not wired end to end, say so plainly and cite the break.

## Discovery & search
1. Can a brand filter the creator base by city, and is the filter applied server-side or client-side?
2. Can a brand filter by language, and does the selected language reach the API?
3. Does the creator search support multi-select on city, or only one city at a time?
4. What exactly does the free-text `q` parameter match against on the server?
5. Can a brand filter creators by engagement rate, and is that range sent from the UI?
6. Can a brand filter by verified-only, and is that control present in the frontend?
7. How is the follower range translated into a query, and what are the default bounds?
8. Does the price/rate filter exclude creators who have not set a rate?
9. What sort orders does creator search support, and which is the default?
10. Is the "Showing N creators" count on discover the server total or the current page length?
11. Are discovery facets (available cities, languages, categories) served from the API or hardcoded in the UI?
12. Is creator discovery paginated, and what is the page size?
13. Can a brand save a creator to a list, and does that state survive a page reload?
14. Is there a "similar creators" recommendation, and what signals does it use?
15. Does discovery exclude suspended creators from results?

## Campaigns
16. What are the campaign statuses defined in the code, and which transitions are legal?
17. Can a brand edit a campaign after it goes active, and what is gated?
18. Is campaign deletion allowed, and under what conditions?
19. What is a HYPE campaign and how does it differ from a standard campaign in the model?
20. Are campaign templates real (persisted) or a UI-only convenience?
21. Can a brand duplicate an existing campaign?
22. How does a creator discover campaigns to apply to, and what filters exist there?
23. What happens when a creator applies to a campaign — what row is written?
24. Can a brand invite a specific creator to a campaign directly?
25. Is there a cap on how many creators can be attached to one campaign?
26. What campaign fields are required at creation time versus optional?
27. Is campaign budget enforced anywhere, or is it a display-only number?
28. Does campaign tracking (UTM/links) exist as a real endpoint?
29. Can a campaign be paused, and what does pausing actually change?
30. How are campaign deliverable requirements defined and stored?

## Deals & collaborations
31. What is the difference between a Deal and a Collaboration in the data model?
32. What are the deal lifecycle states and where are they enumerated?
33. Who can move a deal forward — brand, creator, or both?
34. Is there a negotiation/counter-offer flow, and is it persisted?
35. What happens to a deal when the brand cancels mid-flight?
36. Is there a deal expiry or auto-decline after N days?
37. Can a creator decline a deal with a reason, and is the reason stored?
38. What does the brand pipeline view actually query?

## Contracts
39. Is the contract a real persisted entity or a UI mock?
40. How is two-party e-signature implemented, and what is stored as proof?
41. Can a contract be amended after both parties sign?
42. Are milestones part of the contract, and are they enforced against payouts?
43. What happens if only one party signs and the other never does?
44. Is there a PDF or document artifact generated for a signed contract?
45. Is the contract linked to the escrow record, and how?

## Escrow, wallet, payouts
46. How does escrow funding work, and which payment provider is called?
47. What triggers an escrow release, and who is authorised to trigger it?
48. Is escrow release idempotent, and what enforces that?
49. Is there a UI for escrow release, or is it API/admin only?
50. How is the brand wallet balance computed — stored column or derived from a ledger?
51. Can a brand top up the wallet, and through what flow?
52. How does a creator withdraw earnings, and what are the minimums?
53. Is creator withdrawal idempotent?
54. Is TDS deducted anywhere in the payout path?
55. Is GST handled on invoices, and which invoice documents are generated?
56. What is the platform fee, where is it configured, and when is it taken?
57. Is there a payout reconciliation job, and what does it reconcile against?
58. What happens to escrowed funds if a dispute is opened?
59. Are affiliate/coupon earnings a separate money path from campaign payouts?
60. Is there a refund path back to the brand?

## Deliverables & approvals
61. How does a creator submit a deliverable, and what is stored?
62. Can a brand request revisions, and is there a revision limit?
63. Is there an approval workflow with more than one approver?
64. What happens to payment when a deliverable is approved?
65. Are deliverable metrics (views, likes) pulled automatically or entered manually?
66. Is post-URL verification implemented, and how does it confirm the post is real?
67. Can a deliverable be rejected outright, and what state does the deal enter?
68. Is there a deadline on deliverables and any consequence for missing it?

## Analytics & tracking
69. What does the brand analytics page actually query, and is any of it mocked?
70. What does creator analytics show, and where does the data originate?
71. Is platform-stats aggregation a scheduled job, and how often does it run?
72. How is the creator score computed and what are its components?
73. Is fake-follower / authenticity scoring real, and what feeds it?
74. Is brand-safety scoring implemented, and is it configurable?
75. Is there conversion tracking via webhook, and how is the webhook authenticated?
76. Can a report be exported, and in what format?
77. Is ROI or CPM calculated anywhere in the backend?
78. Are analytics cached, and with what TTL?

## Meera (AI)
79. What tools can Meera actually call, and where are they registered?
80. Can Meera create a campaign on the brand's behalf, and is that gated?
81. Does Meera stream responses, and what transport is used?
82. Is voice input/output wired, and through which vendor?
83. What is the creator copilot, and does it share Meera's tool layer?
84. Is there an outcome digest feature, and is it wired to the live path?
85. How is Meera's scope/permission decided per request?
86. Is there a rate limit or cost cap on Meera calls?

## Platform connections
87. What does the Meta OAuth flow connect, and what scopes are requested?
88. What happens after the Meta callback — what is persisted?
89. Which social platforms can a creator connect, and are all of them functional?
90. Is Instagram data refreshed on a schedule or only on connect?
91. Is there a disconnect path that revokes the token?

## Store integrations
92. What does the Shopify connect flow do, and what is stored?
93. What Shopify webhooks are handled, and are they signature-verified?
94. Is WooCommerce integration at parity with Shopify?
95. What does store integration status report, and where is it surfaced in the UI?

## Notifications, messaging, misc
96. How are notifications generated, and is delivery in-app only or also email?
97. Is brand-to-creator messaging real-time or polled?
98. Can a dispute be raised by both sides, and what evidence can be attached?
99. Are reviews two-way (brand reviews creator and vice versa), and are they gated on completion?
100. What does the public creator portfolio page expose to an unauthenticated visitor?
