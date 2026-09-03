# Swapnil -> Priya · Area 2 of 5 · USER BASE · 100 questions

Answer every question from the actual code. Cite `path/to/file.ext:LINE` and quote the
exact span. If something is not wired end to end, say so plainly and cite the break.

## Identity & the User record
1. What fields does the User entity actually carry, and which are nullable?
2. Is there one User table for brands and creators, or separate tables?
3. How is userType decided at registration, and can it ever change afterwards?
4. What is the primary key format for users, and where is it generated?
5. Is email stored case-sensitively, and can two users differ only by case?
6. Is phone number stored, normalised, and unique?
7. Is there an Indian phone normalisation utility, and where is it applied?
8. What happens if a user registers with an email that already exists?
9. Is there a soft-delete or deactivation flag on User?
10. What timestamps does User track, and are they set automatically?

## Registration
11. What is the exact brand registration request payload?
12. What is the exact creator registration request payload?
13. Which registration fields are validated server-side versus only in the browser?
14. Is password strength enforced, and by what rule?
15. Is email verified before the account can be used?
16. Is there an OTP or phone verification step anywhere in signup?
17. Can a user register and immediately access the dashboard, or is onboarding blocking?
18. Is there any invite-only or waitlist gate on registration?
19. What does registration return — a token, a session, or just a user?
20. Are registration attempts rate limited?

## Login, sessions, tokens
21. How is login authenticated, and what is compared against what?
22. What token format is issued, and what claims does it carry?
23. What is the access token lifetime, and where is it configured?
24. Is there a refresh token, and how is it rotated?
25. Where does the frontend store the token, and is that choice XSS-safe?
26. Is there a logout endpoint, and does it invalidate anything server-side?
27. Are login attempts rate limited, and per what key?
28. Is there account lockout after repeated failures?
29. How does the frontend know a token expired, and what does it do?
30. Is there a JWKS endpoint, and what consumes it?

## Password lifecycle
31. How is the password hashed, and with what algorithm and cost?
32. What is the forgot-password flow end to end?
33. How long is a reset token valid, and is it single-use?
34. Is the reset token stored hashed or in plaintext?
35. Can a logged-in user change their password, and is the current password required?
36. Does changing the password invalidate existing sessions?
37. Is there any password history or reuse prevention?

## Roles & authorisation
38. What roles exist in the system, and where are they enumerated?
39. How is a role checked on a backend endpoint — annotation, filter, or in-service?
40. Is there a role hierarchy, or are roles flat?
41. Can a user hold more than one role at once?
42. How does the frontend decide which routes a user may see?
43. Is frontend route guarding backed by a server check on every request?
44. What happens when a user hits an endpoint above their role — 401, 403, or empty?
45. Is there an admin role that is separate from the brand/creator user model?
46. Can roles be changed through an API, or only in the database?
47. Where is the mapping from role to permitted actions defined?

## Workspaces & members
48. What is a Workspace and which users belong to one?
49. Can one user belong to multiple workspaces?
50. What member roles exist inside a workspace?
51. How is a member invited, and what does the invite record look like?
52. Can a member be removed, and what happens to their work?
53. Is there an owner-transfer path for a workspace?
54. Are workspace-scoped queries actually filtered by workspace on the server?
55. Can a member see another member's private data within the same workspace?
56. Is there a seat limit per workspace, and is it enforced?
57. What happens to a workspace when it is suspended?

## Onboarding
58. What are the steps of brand onboarding, and where are they defined?
59. What are the steps of creator onboarding?
60. Is onboarding progress persisted server-side or held in browser state?
61. Can a user skip onboarding and still use the product?
62. Which onboarding fields actually reach the database?
63. Is there a resume point if the user abandons onboarding halfway?
64. What marks onboarding as complete?
65. Is onboarding re-enterable after completion?

## Profile & account settings
66. What can a user edit on their own profile, and through which endpoint?
67. Is profile update partial (patch) or full replace?
68. Can a user change their email, and is re-verification required?
69. Can a user change their phone, and is re-verification required?
70. What does the account settings page actually save?
71. Is there an avatar/image upload, and where do files go?
72. What file types and sizes are accepted on upload, and is that enforced server-side?
73. Are uploaded files served from a public URL, and is that URL guessable?
74. Is there a notification-preferences record per user?
75. Can a user set a preferred language or locale?

## Account state & lifecycle
76. Can a user be suspended, and by whom?
77. What does a suspended user see when they try to log in?
78. Can a suspended user be reinstated, and is that recorded?
79. Is account deletion offered to the user, and is it hard or soft?
80. What happens to a user's data when their account is deleted?
81. Is there an audit log of account state changes?
82. Is last-login tracked anywhere?

## Security & privacy
83. Is there CSRF protection, and is it needed given the token strategy?
84. What CORS origins are permitted, and where is that configured?
85. Are passwords or tokens ever written to logs?
86. Is PII masked anywhere in logs or error reports?
87. Is there a client-side error reporter, and what does it transmit?
88. Are API errors leaking stack traces or internal identifiers to the client?
89. Is there input sanitisation against injection on user-supplied text?
90. Are user IDs sequential or opaque, and does that matter for enumeration?
91. Is there any IDOR risk in the /users or /me endpoints as written?
92. Is there consent capture for terms and privacy at signup?

## Cross-cutting
93. What does the /me endpoint return, and does it differ by userType?
94. Is there a single source of truth in the frontend for the current user?
95. How does the frontend recover the session on a hard page reload?
96. Are there two separate API client layers, and do both attach auth?
97. Is there a demo or mock mode, and how does it affect user data?
98. What seed users exist for development, and could they exist in production?
99. Is there any test or backdoor authentication path in the codebase?
100. What user-facing feature is most obviously incomplete in the user/account surface?
