# Swapnil -> Priya · Round 2 · Area 2 of 5 · USER BASE · 100 questions

Scope: **argument correctness, response handling, and render wiring** on everything that
is a user — identity, registration, login, session, roles, workspace membership,
onboarding, profile, settings, account state.

Do not answer whether an endpoint merely exists. Ask: does the call send every field the
server reads? Does the client parse what the server returns? Does the screen render it,
and what does the user see when it is missing, stale, or refused?

Answer from the actual code. Cite `path/to/file.ext:LINE` and quote the exact span.
Where wiring breaks, say so plainly and cite the exact line.

## Registration — what reaches the server
1. Which fields does the brand register form collect that the register request never sends?
2. Which fields does the creator register form collect that never reach the server?
3. Which fields does BrandRegisterRequest validate that no client ever populates?
4. Is the password confirmed client-side only, or does the server see both values?
5. Is the phone number normalised before it is sent, after it arrives, or both?
6. Does registration send a locale, timezone or referral source, and does the server store it?
7. Does the client send terms-acceptance, and does the server persist it as consent?
8. What does the client do with the registration response — token, redirect, or refetch?
9. If registration succeeds but the follow-up call fails, what state is the user left in?
10. Are server-side field validation errors mapped back to the specific form field?
11. What does the user see when the email is already registered?
12. Is the register submit button guarded against a double click?
13. Does the client retry a failed registration, and could that create two accounts?
14. Which registration errors surface as a toast versus inline versus not at all?
15. Is there any field the server requires that the form does not collect at all?

## Login and session
16. Does the login call send anything beyond email and password?
17. Is there a remember-me control, and what does it actually change in storage?
18. Where is the token written on login, and is the same key read everywhere?
19. Do all API client layers read the token from the same place?
20. What does the client do with the login response body beyond the token?
21. Is the user record fetched after login, or reconstructed from the token claims?
22. On a hard reload, how is the session restored, and what happens if that call fails?
23. Is the token refreshed proactively before expiry, or only reactively on a 401?
24. Can two tabs refresh concurrently, and what happens to the loser?
25. Does logout clear every storage key that login wrote?
26. Does logout notify the server, and does the client wait for that before redirecting?
27. What does the user see between clicking logout and the redirect completing?
28. Is there any screen that renders before the session check resolves?
29. What renders while the session is being restored — a spinner, the page, or the login form?
30. Does an expired token produce a distinguishable state from an invalid one?

## Roles and route guarding
31. How does the client learn the user's role, and can that value be stale?
32. Is the role read from the token, from a fetched user record, or both?
33. If those two disagree, which wins?
34. Which routes are guarded client-side but not enforced server-side?
35. Which endpoints enforce a role that no client route guards?
36. What does a user see on a 403 from a role-gated endpoint — an error, an empty page, or nothing?
37. Are role-gated UI controls hidden, disabled, or rendered and then rejected on click?
38. Is there any control rendered for a role that cannot actually use it?
39. Does the admin console share the session with the main app, or hold its own?
40. Can an admin token reach non-admin endpoints, and vice versa?

## Workspace and membership
41. How does the client know which workspace is active?
42. Is the active workspace sent on requests, or derived server-side from the principal?
43. If it is sent, can the client send a workspace the user does not belong to?
44. What renders when a user belongs to no workspace?
45. Is there any UI for inviting a member, and if not, what is the intended path?
46. Does the member list render the member role, and is it accurate?
47. What happens in the UI when a member is removed while they are using the app?
48. Is workspace switching reachable, and what does it invalidate when it happens?
49. Does any cached data survive a workspace switch that should not?
50. What does a member see when their workspace is suspended mid-session?

## Onboarding
51. Which onboarding fields are collected by the form but never sent?
52. Which onboarding fields are sent but never persisted by the server?
53. Is onboarding progress written server-side per step, or only at the end?
54. If the user abandons midway and returns, what is restored and what is lost?
55. What marks onboarding complete on the client versus on the server?
56. Can those two disagree, and what does the user see if they do?
57. Does a failed onboarding save block progression, or does the wizard advance anyway?
58. Are onboarding validation errors mapped to the field that caused them?
59. Is any onboarding step skippable in the UI but required by the server?
60. Does the onboarding redirect loop if the completion flag never lands?

## Profile and settings
61. Does the profile save send a full object or only changed fields?
62. Which profile fields does the server accept that the settings UI never sends?
63. Which fields does the settings UI send that the server ignores?
64. After a successful save, does the UI refetch or trust its local state?
65. If the save succeeds partially, what does the user see?
66. Is the email change flow wired, and does it require re-verification?
67. Is the phone change flow wired, and is the new number verified?
68. Does the avatar upload send the file to the server or to storage directly?
69. What file types and sizes does the client enforce, and does the server agree?
70. What does the user see when an upload is rejected by the server?
71. Does the connected-accounts panel reflect real connection state or local optimism?
72. Does disconnecting an account update the panel before the server confirms?
73. Are notification preferences persisted, and does the UI read them back?
74. Is there a settings control that saves nothing?
75. Does the change-password form send the current password, and does the server require it?

## Account state
76. How does the client discover that the account was suspended?
77. What renders for a suspended user — a message, a redirect, or a broken page?
78. Is there any UI for account deletion, and what does it call?
79. What does the client do with a 401 that is actually a suspension?
80. Is last-login or session activity shown to the user anywhere?

## Errors, empty and edge states
81. Which auth errors are shown verbatim from the server and which are replaced?
82. Does any auth error leak whether an email exists?
83. What renders when the user record fetch returns null?
84. Which user-facing screens assume a non-null profile and would crash without one?
85. Is there a global error boundary around the authenticated shell?
86. What happens if the token is present but malformed?
87. What happens if the server returns a role the client has no case for?
88. Are there user fields typed non-null on the client that the server can send null?
89. Are there user fields the client declares that the server never sends?
90. Does the user type in the admin layer agree with the one in the main app?

## Cross-layer and configuration
91. Do all API layers attach the same auth header and base URL?
92. Which layer would break first if the API base URL changed?
93. Is there a shared current-user store, or does each screen fetch its own?
94. Does any component fetch the user on every render or in an unguarded effect?
95. Which user-related calls fire before the session is known to be valid?
96. Is demo or mock mode able to produce a user that looks real?
97. Are there seeded development users reachable in a production build?
98. Which user-related env vars does the client read, and what is the fallback when unset?
99. Is any user identifier, workspace id or token written to a URL or query string?
100. Which single break in this area would a real user hit first on their first day?
