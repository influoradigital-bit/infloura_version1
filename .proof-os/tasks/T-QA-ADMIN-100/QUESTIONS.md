# Swapnil -> Priya · 100 questions on the ADMIN surface
Task T-QA-ADMIN-100 · asked 2026-09-02
Rule for every answer: cite `file:line`. If the answer is "no", say what exists instead.

## BLOCK A — Admin auth, roles, access (A1-A12)
A1. Which admin roles exist and where is the enum defined?
A2. Is MFA enforced for admin login, or optional? Which endpoint enforces it?
A3. Where is the admin MFA secret stored and is it encrypted at rest?
A4. Which admin endpoints are role-gated, and which accept any authenticated admin?
A5. Is there a per-endpoint permission matrix in code, or is it ad-hoc per controller?
A6. Does the frontend `/admin/*` guard check anything beyond the presence of `admin_token`?
A7. Is the admin refresh-token flow implemented on both sides (BE endpoint + FE caller)?
A8. Is there an admin session/idle timeout anywhere in code?
A9. Are admin actions written to an audit log automatically, or does each call site opt in?
A10. What fields does the admin audit log row store?
A11. Is the audit log queryable by actor, entity and date in the API, and is that surfaced in the UI?
A12. Can an admin be created through the API, or only by DB seed/migration?

## BLOCK B — Admin console UI coverage vs backend (B13-B28)
B13. How many admin routes are registered in the FE console, and what are they?
B14. How many admin REST endpoints exist on the backend?
B15. Which admin controllers have NO corresponding FE page or panel?
B16. Which admin endpoints are declared in the FE API client but never called by any component?
B17. `/admin/campaigns/at-risk` — is it rendered anywhere?
B18. `/admin/campaigns/hype/ops` — is it rendered anywhere?
B19. `/admin/escrow/flagged` — is it rendered anywhere?
B20. `/admin/emails/send-bulk` — is there a UI control for it, and is it gated?
B21. `/admin/billing/comp` and `/admin/billing/override` — are both wired to controls?
B22. `/admin/brands/{id}/campaigns/{campaignId}/budget-override` — is it reachable from the UI?
B23. `/admin/creators/{id}/instagram/force-reauth` — is it reachable from the UI?
B24. Does the admin console have a marketing page or route at all?
B25. `AdminSupportStatsController` declares `/admin/support/stats` twice — do those two mappings collide at startup?
B26. `AdminRevenueController` and `AdminFinanceController` share the base `/admin/finance` — is that a conflict?
B27. Are there admin FE pages that call endpoints which do not exist on the backend?
B28. Which admin panels render hardcoded or mock data instead of an API response?

## BLOCK C — Marketing data Tejas needs: is it STORED? (C29-C45)
C29. Is there a table or entity storing traffic source or UTM parameters for a signup?
C30. Is there a table or entity storing referral / invite attribution?
C31. Is signup channel (organic / paid / referral) persisted on the user record?
C32. Is a `profileComplete` flag persisted on brand or creator, or computed on read?
C33. What exactly does `GET /admin/marketing/growth` compute, and from which tables?
C34. Which fields of `GrowthMetrics` are returned live and which are omitted?
C35. What does `GET /admin/marketing/reputation` compute, and from which tables?
C36. Is NPS or any CSAT survey response stored anywhere?
C37. Are email opens / clicks / bounces stored per recipient?
C38. Is there campaign-level marketing attribution (which channel produced which brand)?
C39. Is time-to-first-campaign for a brand derivable from stored timestamps?
C40. Is creator-acquisition cost or any marketing spend figure stored?
C41. Is there any cohort/retention table, or is retention computed on the fly?
C42. Are marketing-site pageviews stored server-side?
C43. Is there a PlatformStats aggregation job, what does it write, and how often?
C44. Are churn events (subscription cancel, creator dormancy) stored as events or inferred?
C45. Is there any event/telemetry table at all, or only entity tables?

## BLOCK D — Marketing data: is it SHOWN in the UI? (D46-D57)
D46. Where in the admin UI is the growth funnel rendered?
D47. Where is the reputation score rendered?
D48. Are the omitted `GrowthMetrics` fields hidden, or rendered as zero/empty?
D49. Does any UI show signup source breakdown?
D50. Does any UI show email engagement (opens/clicks)?
D51. Does any UI show retention or cohort curves?
D52. Does any UI show creator supply metrics (new creators, active creators)?
D53. Is there an export (CSV or report) for any marketing metric?
D54. Is there a date-range selector on the marketing/growth data, or a fixed window?
D55. Does the marketing data refresh on an interval or only at mount?
D56. If `/admin/marketing/growth` returns 500, what does the UI show?
D57. Is any marketing figure in the UI computed client-side from unrelated data?

## BLOCK E — User base: what admin can see about a USER (E58-E67)
E58. Which entity is the root user record and what identity fields does it store?
E59. Is phone number stored, verified, and shown to admin?
E60. Is the user's last-login timestamp stored and shown?
E61. Is user email-verification state stored and shown?
E62. Can admin see a user's login history or IPs?
E63. Is there a single admin user search across brands and creators, or two separate lists?
E64. Can admin impersonate a user, and is that logged?
E65. Are soft-deleted or suspended users still visible to admin?
E66. Is GDPR/DPDP deletion or export implemented for a user?
E67. Where does admin change a user's role or type, if anywhere?

## BLOCK F — Brand base (F68-F79)
F68. What does the admin brand list return per row?
F69. What extra fields does the brand detail endpoint return?
F70. Is brand KYC state stored, and what are its values?
F71. Which admin action verifies brand KYC, and is it in the UI?
F72. Is brand GST/tax identity stored and shown to admin?
F73. Are brand onboarding answers (the fields touched by F-0392..F-0397) persisted and shown to admin?
F74. Can admin edit a brand profile, and which fields are editable?
F75. Is brand suspend/reinstate wired end-to-end (endpoint + UI + effect on login)?
F76. Can admin see a brand's wallet balance and escrow holdings?
F77. Can admin see a brand's subscription plan and billing state?
F78. Is a brand's campaign list visible from the brand detail screen?
F79. Is brand spend-to-date stored or computed?

## BLOCK G — Creator base (G80-G91)
G80. What does the admin creator list return per row?
G81. Is creator application state stored, and what are the statuses?
G82. Where does admin approve or reject a creator application?
G83. Is creator tier stored, and can admin change it in the UI?
G84. Is creator Instagram/Meta connection state stored and shown?
G85. Is creator follower count stored, and how fresh is it?
G86. Is creator bank/UPI payout identity stored, and is it masked for admin?
G87. Is creator PAN/tax identity stored and shown?
G88. Is the creator score stored or computed per request?
G89. Can admin see a creator's earnings and pending payouts?
G90. Is creator suspend/reinstate wired end-to-end?
G91. Are the creator profile fields from `CreatorProfileDtos` all shown on the admin creator detail?

## BLOCK H — Money, moderation, ops (H92-H100)
H92. Can admin see the full escrow ledger, or only flagged rows?
H93. Is the reconciliation panel backed by a real endpoint?
H94. Is manual payout wired end-to-end, and what authorization does it require?
H95. Is the platform fee config change history stored and shown?
H96. What does the moderation flag queue read from, and who writes flags?
H97. Is dispute resolution wired end-to-end with a stored outcome?
H98. Is the error log written by the client crash reporter, the server, or both?
H99. Does the admin dashboard pulse read cached or live numbers, and what is the cache TTL?
H100. Which admin capabilities are exposed by the API but NOT reachable in the admin UI today?
