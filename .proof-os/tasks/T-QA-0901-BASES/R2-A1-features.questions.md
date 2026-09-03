# Swapnil -> Priya · Round 2 · Area 1 of 5 · FEATURES BASE · 100 questions

Scope: **argument correctness, response handling, and render wiring.** Whether a frontend
caller merely EXISTS is already settled by an oracle and is not what is asked here.

For each question ask: does the call send every parameter the server reads? Does the
client parse the field the server actually returns? Does the screen render it, and what
does the user see when it is absent, empty, or an error?

Answer from the actual code. Cite `path/to/file.ext:LINE` and quote the exact span.
Where the wiring breaks, say so plainly and cite the exact line.

## Request arguments — does the call send what the server reads
1. For each param CreatorController.search reads, which are never sent by any frontend caller?
2. Does the campaign create request send every field CampaignWriteRequest validates?
3. Does the campaign PATCH send a partial body, and does the server distinguish absent from null?
4. Does the deal counter-offer call send usage rights, and does the server persist what it sends?
5. Does the deliverable submit call send every field the server requires, or does it rely on defaults?
6. Does the escrow fund call send an amount, and does the server trust it or re-derive it?
7. Does the withdrawal call send the Idempotency-Key header on every code path, including retries?
8. Does the topup call reuse an Idempotency-Key across retries, or mint a new one each attempt?
9. Which frontend calls send a pagination cursor or page number that the server ignores?
10. Which frontend calls send a filter the server has no parameter for, so it is silently dropped?
11. Are query params URL-encoded before being sent, and where is that done?
12. Does any call send a number where the server expects a string, or vice versa?
13. Does any call send a date in a format the server does not parse?
14. Does the campaign export call send the format param, and what happens on an unsupported value?
15. Does the conversion webhook registration send the secret, or expect the server to generate it?
16. Which POST bodies are built by hand rather than from a shared type?
17. Does the invite call send the campaign id, and is it validated against the caller workspace?
18. Does the review submit call send a rating within the range the server validates?
19. Does the dispute open call send evidence attachments, and does the server store them?
20. Does the portfolio save call send the full portfolio or a diff?

## Response handling — does the client read what the server returns
21. Which response fields does the server send that no frontend type declares?
22. Which fields does a frontend type declare that the server never sends?
23. Does the creator search mapper read every field the DTO returns?
24. Does the campaign detail page read the response envelope meta, or only data?
25. Does the deal list handle the pagination meta, or assume a single page?
26. Does the wallet response parse the currency, or assume INR?
27. Are money amounts parsed as numbers or strings, and is precision lost?
28. Does any component call toFixed or similar on a value that can be null?
29. Does the score panel handle a null score distinctly from a zero score?
30. Does the analytics response handler cope with an empty metrics array?
31. Does the contract response expose the PDF URL, and does the client use it?
32. Does the deliverable status response carry the deadline, and is it read?
33. Does the notification list read the read/unread flag correctly?
34. Does the messages stream handler parse every event type the server emits?
35. Does the stream handler reconnect after a dropped connection?
36. What does the client do with an event type it does not recognise?
37. Does the Meera stream client handle a truncated or malformed chunk?
38. Does any response handler assume an array where the server can send null?
39. Where does the client cache a response, and can it serve a stale value after a mutation?
40. After a successful mutation, which screens refetch and which keep stale data on screen?

## Render wiring — does the screen actually show it
41. Which fields fetched by the discover page are never rendered?
42. Does the creator profile page render the languages field anywhere?
43. Does the creator card show city, and does it fall back gracefully when city is null?
44. Does the campaign detail page render the deliverable requirements?
45. Does the deal room render the milestone list, and what shows when there are none?
46. Is the escrow status visible to the brand anywhere outside the payments tab?
47. Does the wallet page render the escrow balance separately from the available balance?
48. Does the creator wallet show pending versus available earnings distinctly?
49. Does the invoice list render the document series, and are all four series reachable?
50. Does the analytics page render a distinct state for "no data yet" versus "failed to load"?
51. Which pages render a loading skeleton, and which render nothing while loading?
52. Which pages show an error toast versus an inline error versus failing silently?
53. Is there any screen where a failed fetch is indistinguishable from an empty result?
54. Does the notification bell reflect unread count in real time or only on reload?
55. Does the contract page show which party has signed and which has not?
56. Does the dispute view show the evidence the other party submitted?
57. Does the review page show reviews received as well as reviews given?
58. Does the public portfolio render the same data the authenticated view does?
59. Which admin-visible fields leak into a brand or creator view?
60. Which screens render a currency symbol hardcoded rather than from the response?

## Empty, error and edge states
61. What does discover render when the server returns zero creators?
62. What does the campaign list render for a brand with no campaigns?
63. What does the creator dashboard render on day one with no deals?
64. What happens on the deal room when the collaboration was cancelled mid-view?
65. What does the UI do on a 401 mid-session?
66. What does the UI do on a 403 from a role-gated endpoint?
67. What does the UI do on a 409 conflict from a status transition?
68. What does the UI do on a 500, and is the error body surfaced or swallowed?
69. Is there a global error boundary, and what does it render?
70. Does a network timeout produce a distinguishable state from a server error?
71. Which forms lose user input when a submit fails?
72. Which forms allow a double submit, and is that guarded client-side or server-side?
73. Is there any optimistic update that is never rolled back on failure?
74. Which mutations show no confirmation at all when they succeed?
75. Which destructive actions have no confirm step?

## Cross-layer contract drift
76. Where does a frontend enum disagree with the backend enum it mirrors?
77. Where does a frontend status label differ from the status the server sends?
78. Which status values can the server send that the UI has no label for?
79. Are there two frontend API layers, and do both attach auth and base URL identically?
80. Which endpoints are declared in one API layer and called from the other?
81. Where does a shared type live in two places and disagree?
82. Are backend DTO field names snake_case or camelCase, and is the mapping consistent?
83. Which nullable backend fields are typed non-null on the frontend?
84. Which required backend fields are typed optional on the frontend?
85. Does any frontend type assert a field the Java record never sends?

## Runtime and configuration
86. Which features change behaviour based on VITE_API_MODE, and what breaks when it is unset?
87. Which screens render mock data in a way indistinguishable from real data?
88. Which env vars does the frontend read that no build pipeline supplies?
89. Which backend config keys are read by code but have no placeholder in application.yml?
90. Is the API base URL configurable, and what is the fallback?
91. What happens to every call if the token refresh fails?
92. Are there any hardcoded IDs, workspaces or user references in shipped frontend code?

## The endpoints with no caller
93. GET /contracts/unsigned — what was it built for and what would consume it?
94. GET /creators/search — how does it differ from GET /creators, and which is the intended one?
95. POST /wallet/escrow/payout — how does it differ from escrow release, and is it reachable at all?
96. PUT /deliverables/{id}/metrics — how does it differ from the creator metrics route the UI uses?
97. GET /wallet/balance — what does it return that GET /wallet does not?
98. POST /workspace/members/accept and the invite routes — is workspace invitation usable end to end?
99. POST /notifications/unsubscribe — is email unsubscribe reachable by a recipient?
100. GET /users/{id} and PATCH /users/{id} — what uses them, and are the /me routes their replacement?
