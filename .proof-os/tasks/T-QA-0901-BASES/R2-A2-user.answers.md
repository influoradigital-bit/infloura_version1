# Swapnil -> Priya · Round 2 · Area 2 of 5 · USER BASE · answers

### 1. Which fields does the brand register form collect that the register request never sends?
Only confirmPassword. The form holds companyName, industry, teamSize, email, password, confirmPassword and agreeToTerms; the payload maps teamSize onto companySize, industry onto industry, agreeToTerms onto acceptedTerms, and drops confirmPassword entirely because it is a client-side equality check with no server counterpart. The inverse problem is worse: firstName and lastName are not collected at all and are synthesised from the email local part before sending.
`src/pages/brand-register.tsx:38` "confirmPassword, setConfirmPassword"

### 2. Which fields does the creator register form collect that never reach the server?
Same single field, confirmPassword. The creator payload is email, password, displayName (the trimmed name field) and acceptedTerms, so name/email/password/terms all reach the server and only the confirmation copy is discarded client-side after the equality check in validate.
`src/pages/creator-register.tsx:87` "api.auth.creatorRegister"

### 3. Which fields does BrandRegisterRequest validate that no client ever populates?
The premise is close but not exactly right: every component of the record is populated by at least one client. phone is the field the main signup page never sends — brand-register.tsx omits it — but the onboarding wizard's own registration call does send it, so it is not orphaned. The genuinely unpopulated-by-a-human fields are firstName and lastName from brand-register.tsx, which the server validates as @NotBlank while the client fabricates them.
`influora-api/src/main/java/com/influora/web/dto/auth/BrandRegisterRequest.java:27` "String phone"

### 4. Is the password confirmed client-side only, or does the server see both values?
Client-side only. BrandRegisterRequest and CreatorRegisterRequest each declare a single password component, so the confirmation value never leaves the browser; the mismatch check lives in validateStep2 and its sibling in the creator form.
`influora-api/src/main/java/com/influora/web/dto/auth/BrandRegisterRequest.java:22` "String password"

### 5. Is the phone number normalised before it is sent, after it arrives, or both?
After it arrives, only. The onboarding step validates the raw string against a 10-digit Indian-mobile regex but sends data.phone unmodified; AuthService strips spaces, +91 and a leading zero server-side and then revalidates, so a user who types +91 98765 43210 passes the server and fails the client's own regex first.
`src/components/brand/onboarding/onboarding-steps.tsx:450` "Enter valid 10-digit mobile"

### 6. Does registration send a locale, timezone or referral source, and does the server store it?
No to all three on the wire. There is a timezone column on the user row, but it is hard-coded server-side at construction rather than taken from the request, and there is no locale or referral-source column anywhere on the entity.
`influora-api/src/main/java/com/influora/domain/entity/User.java:113` "Asia/Kolkata"

### 7. Does the client send terms-acceptance, and does the server persist it as consent?
The client sends it and the server validates it, but nothing persists it. acceptedTerms is carried by all three registration call sites and enforced by an @AssertTrue, yet the User entity has no consent column and AuthService never writes the value anywhere — the acceptance exists only as a request that was allowed to succeed.
`influora-api/src/main/java/com/influora/web/dto/auth/BrandRegisterRequest.java:26` "Terms must be accepted"

### 8. What does the client do with the registration response — token, redirect, or refetch?
All of the token and the redirect, none of the refetch. mapBrandAuth runs persistBrandSession, which writes brand_token, brand_user_id, brand_email, brand_workspace_id and brand_display_name to localStorage, and the page then seeds the zustand auth store from those same values and navigates to the onboarding wizard. No follow-up GET is issued to confirm the account.
`src/pages/brand-register.tsx:132` "login(buildBrandUser"

### 9. If registration succeeds but the follow-up call fails, what state is the user left in?
Logged in and stranded on the same step. In the onboarding wizard the register call and the company-details save sit in one try block; if the second fails the catch renders an inline error and the wizard stays on step 2, but the token from the first call has already been persisted. The retry is safe because the guard re-reads the token and skips re-registration.
`src/pages/brand-onboarding.tsx:64` "if (!hasBrandToken())"

### 10. Are server-side field validation errors mapped back to the specific form field?
No. The envelope carries field and fields, and the TypeScript payload type declares both, but the ApiError constructor never accepts them — only code, message, status and the insufficient-funds details survive the throw — so every server validation error arrives at the form as one opaque string and lands in the page-level banner.
`src/lib/api.ts:562` "throw new ApiError"

### 11. What does the user see when the email is already registered?
A red inline banner at the top of step 2 reading the server's own sentence, because AuthService throws a 409 with that message and the catch assigns err.message to the form-level error slot. There is no link back to sign-in and no highlight on the email field itself.
`influora-api/src/main/java/com/influora/service/AuthService.java:117` "An account with this email already exists"

### 12. Is the register submit button guarded against a double click?
Yes on both pages, via the loading flag set before the request and cleared in finally. That is the only guard — there is no Idempotency-Key on the register call — so the real protection against a duplicate account is the unique email constraint and its 409 translation server-side.
`src/pages/brand-register.tsx:419` "disabled={loading}"

### 13. Does the client retry a failed registration, and could that create two accounts?
No automatic retry exists anywhere in the registration path; http.request only ever retries on a 401 for token refresh, and register is issued without an auth header so hasAuthHeader is false and that branch never runs. A manual user retry cannot create two accounts because the email column is unique and the race loser is translated to the same 409.
`src/lib/api.ts:512` "if (res.status === 401 && hasAuthHeader && !retried)"

### 14. Which registration errors surface as a toast versus inline versus not at all?
None are toasts. Client-side validation failures render per-field under the input via FieldError, and every server error — duplicate email, weak password, invalid phone, OTP delivery failure — collapses into the single form-level banner. Nothing is swallowed silently, but nothing is field-targeted either.
`src/pages/brand-register.tsx:138` "setErrors({ form: message })"

### 15. Is there any field the server requires that the form does not collect at all?
Yes — firstName and lastName. Both are @NotBlank on BrandRegisterRequest, and brand-register.tsx never renders an input for either; it splits the email local part on dot, underscore or hyphen and title-cases the pieces, so a user signing up as sales@acme.com is stored as Sales User.
`src/pages/brand-register.tsx:113` "const [firstName, ...rest]"

### 16. Does the login call send anything beyond email and password?
No. LoginPayload is exactly two strings and the server's LoginRequest mirrors it, so no device id, no remember flag, no client version and no captcha token crosses the wire. The remember-me choice is handled purely client-side after the response comes back.
`src/lib/api.ts:770` "export interface LoginPayload"

### 17. Is there a remember-me control, and what does it actually change in storage?
Only the creator login page has one, and it decides whether the access token is written to localStorage or sessionStorage. It is also a live break: unchecking it routes creator_token into sessionStorage, but the creator route guard reads localStorage only, so the user is redirected straight back to the login screen after a successful sign-in.
`src/App.tsx:137` "localStorage.getItem('creator_token')"

### 18. Where is the token written on login, and is the same key read everywhere?
The key is uniform (brand_token / creator_token) but the writer is not. The creator flow goes through api.auth.setToken, which honours the remember-me storage choice and the role-claim guard; the brand flow never calls setToken at all — persistBrandSession writes brand_token to localStorage directly, bypassing both.
`src/lib/auth-session.ts:69` "localStorage.setItem('brand_token', token)"

### 19. Do all API client layers read the token from the same place?
No, there are three different readers. The main client reads localStorage then falls back to sessionStorage for the role's key; the Meera client reads only localStorage brand_token, so a creator using Meera is unauthenticated and a session-only brand token is invisible to it; the admin console reads admin_token.
`src/lib/meera-api.ts:392` "localStorage.getItem('brand_token')"

### 20. What does the client do with the login response body beyond the token?
For creators it keeps the whole identity — userId, email, displayName and onboardingCompleted are persisted and returned to the page. For brands it is lossy: persistBrandSession stores the identity in localStorage but mapBrandAuth returns only token, userId and onboardingComplete, which is why brand-login.tsx has to re-read the display name back out of localStorage.
`src/lib/api.ts:829` "persistBrandSession(data)"

### 21. Is the user record fetched after login, or reconstructed from the token claims?
Neither, strictly. It is reconstructed from the login response body that was persisted to localStorage — buildBrandUser and buildCreatorUser fabricate the structural fields (emailVerified false, status ACTIVE) rather than reading them from the server, even though AuthDtos.UserDto actually carries status and emailVerified on the wire.
`src/lib/auth-session.ts:130` "emailVerified: false"

### 22. On a hard reload, how is the session restored, and what happens if that call fails?
It is not restored by any call. The zustand auth store persists nothing, so user is null on every reload and the guards fall back to a bare localStorage token-presence check. The brand guard additionally fires GET /onboarding/brand/status, and that call failing leaves status undefined, which fails open and lets the dashboard render.
`src/lib/store.ts:49` "partialize: () => ({})"

### 23. Is the token refreshed proactively before expiry, or only reactively on a 401?
Both. ensureFreshToken decodes the exp claim before every authenticated request and renews when it is within a 60-second skew window, and fetchWithAuthRetry still has the reactive single-shot 401 retry behind it.
`src/lib/api.ts:481` "if (exp * 1000 - Date.now() > TOKEN_REFRESH_SKEW_MS) return null"

### 24. Can two tabs refresh concurrently, and what happens to the loser?
Yes. The in-flight dedupe is a per-module field on one HttpClient instance, so it only covers one tab; two tabs each POST /auth/refresh, the server revokes the presented token and issues a new one, and the second tab presents a token that has already been burned. Its refresh returns null, clearToken wipes the slot, and that tab is silently signed out with no message.
`src/lib/api.ts:360` "private refreshPromises"

### 25. Does logout clear every storage key that login wrote?
For creators yes — clearCreatorSession removes the token, user id, email, display name, the onboarding flag and the Meta mirror. For brands no: the sidebar logout removes brand_token only, leaving brand_user_id, brand_email, brand_workspace_id, brand_company, brand_display_name and both onboarding flags behind for the next person on that browser.
`src/components/brand/brand-layout.tsx:218` "localStorage.removeItem('brand_token')"

### 26. Does logout notify the server, and does the client wait for that before redirecting?
The creator sidebar and the brand settings page both await POST /auth/logout before navigating. The brand sidebar logout — the one a brand actually reaches — never calls the server at all, so the refresh-token rows are never revoked and the HttpOnly refresh cookie stays valid for its full window.
`src/components/brand/brand-layout.tsx:216` "const handleLogout = () =>"

### 27. What does the user see between clicking logout and the redirect completing?
For a brand, nothing — it is synchronous and the login page appears immediately. For a creator the confirm dialog's action button awaits a network round trip with no pending state, no spinner and no disabled attribute, so on a slow or hanging /auth/logout the dialog just sits there looking unresponsive.
`src/components/creator/creator-layout.tsx:555` "onClick={handleLogout}"

### 28. Is there any screen that renders before the session check resolves?
Yes — /brand/onboarding is registered as a bare route with no ProtectedRoute wrapper, so it mounts for anyone, signed in or not. It then branches on hasBrandToken to decide whether to start at step 1 or step 2, which means an unauthenticated visitor gets the full account-creation wizard rather than a redirect.
`src/App.tsx:197` "element={<BrandOnboardingPage />}"

### 29. What renders while the session is being restored — a spinner, the page, or the login form?
A blank screen. While the onboarding-status query is in flight the brand guard returns null, so there is no spinner and no skeleton — just an empty document until the round trip finishes.
`src/App.tsx:119` "if (shouldCheckOnboarding && checkingOnboarding) return null"

### 30. Does an expired token produce a distinguishable state from an invalid one?
No, at either end. AuthService.refresh filters on the expiry inside the same Optional chain that handles a missing or revoked row and throws one INVALID_REFRESH_TOKEN for all of them, and the client collapses every non-ok refresh response to null before the caller ever sees a code.
`src/lib/api.ts:440` "if (!res.ok) return null"

### 31. How does the client learn the user's role, and can that value be stale?
It fetches GET /workspace/members and matches the row whose userId equals the brand_user_id in localStorage — there is no "my role" endpoint. It absolutely can be stale: the query carries a five-minute staleTime, so a demotion from ADMIN to VIEWER keeps the old capabilities in that tab until the window expires or the page reloads.
`src/hooks/brand/useWorkspaceVerification.ts:66` "members.find((m) => m.userId === myId)?.role"

### 32. Is the role read from the token, from a fetched user record, or both?
Two different things are called role here. The access token's userType claim (BRAND/CREATOR/ADMIN) is decoded client-side, but only to decide which storage slot a token may occupy — never to gate a screen. The workspace MemberRole that actually gates controls comes purely from the fetched member list.
`src/lib/api.ts:353` "const userType = decodeJwtClaims(token)?.userType"

### 33. If those two disagree, which wins?
They cannot meaningfully disagree because they answer different questions, and the code treats each as authoritative in its own lane: a userType mismatch makes setToken refuse the write outright and assertTokenRole fail the whole login, while the member-role lane simply degrades. The one asymmetry worth naming is that the userType check fails OPEN when the claim is unreadable.
`src/lib/api.ts:354` "if (typeof userType !== 'string') return true"

### 34. Which routes are guarded client-side but not enforced server-side?
The onboarding gate. ProtectedRoute bounces a brand to /brand/onboarding whenever GET /onboarding/brand/status reports incomplete, but no server endpoint refuses a request because onboardingCompleted is false — the flag is written and read and never enforced, so a direct API call from an un-onboarded account succeeds.
`influora-api/src/main/java/com/influora/service/OnboardingService.java:93` "user.setOnboardingCompleted(true)"

### 35. Which endpoints enforce a role that no client route guards?
All the workspace MemberRole gates. Campaign delete, escrow fund and release, payouts, contracts and platform-fee writes all call requireRole with an OWNER/ADMIN or OWNER/ADMIN/MANAGER allow-list, and none of these has a route-level guard on the client — the pages are reachable by any member and only the individual control is conditionally disabled.
`influora-api/src/main/java/com/influora/service/EscrowService.java:195` "requireRole(member, MemberRole.OWNER, MemberRole.ADMIN)"

### 36. What does a user see on a 403 from a role-gated endpoint — an error, an empty page, or nothing?
It depends entirely on the call site, and only one place does it well. Campaign delete detects status 403 and shows a specific destructive toast naming the missing role and the alternative; almost everywhere else the same 403 falls into a generic catch and surfaces the server's raw sentence, which for these gates is a bare permissions message with no explanation of who to ask.
`src/components/brand/campaigns/campaigns-list.tsx:443` "Only the workspace Owner or Admin can delete a campaign"

### 37. Are role-gated UI controls hidden, disabled, or rendered and then rejected on click?
All three patterns coexist. Campaign delete is rendered but disabled with an explanatory title attribute, the verification CTA is swapped for an ask-an-admin note, and every other role-gated action is rendered live and only discovers the gate when the server answers 403.
`src/components/brand/campaigns/campaigns-list.tsx:845` "Only the workspace Owner or Admin can delete a campaign"

### 38. Is there any control rendered for a role that cannot actually use it?
Yes, by deliberate fail-open. Both role gates enable the control whenever the role is unknown — a null myRole counts as permitted — so a VIEWER whose member-list fetch failed, or who has no brand_user_id in storage, sees a fully enabled Delete button that will 403 on click.
`src/components/brand/campaigns/campaigns-list.tsx:297` "myRole == null"

### 39. Does the admin console share the session with the main app, or hold its own?
Its own, and a more isolated one than it looks. It keys off admin_token, uses a relative /api/v1/admin base rather than VITE_API_BASE_URL, and its fetch wrapper never sets credentials include — so the admin HttpOnly refresh cookie is never transmitted and the refreshToken helper expects a raw refresh token the server deliberately never sends.
`src/admin/services/api-contracts.ts:73` "localStorage.getItem('admin_token')"

### 40. Can an admin token reach non-admin endpoints, and vice versa?
A brand or creator token cannot reach /admin/** — the filter chain requires the ADMIN role on that whole prefix. The reverse is only half-blocked: an admin token is minted by the same JwtService and would authenticate on ordinary endpoints, where requireBrand then rejects it with a 403 at the service layer rather than at the filter.
`influora-api/src/main/java/com/influora/config/SecurityConfig.java:200` "/admin/**"

### 41. How does the client know which workspace is active?
It effectively does not track one. persistBrandSession writes brand_workspace_id at login and nothing in the app ever reads that key again — it is a write-only value. Every screen that needs workspace data calls GET /workspaces/me and takes whatever the server resolves from the principal.
`src/lib/auth-session.ts:75` "localStorage.setItem('brand_workspace_id', workspaceId)"

### 42. Is the active workspace sent on requests, or derived server-side from the principal?
Derived server-side on every call. BrandContextService reads the workspaceId claim off the access token and, when that claim is absent, falls back to the caller's first active membership; no ordinary request body or query string carries a workspace id.
`influora-api/src/main/java/com/influora/service/BrandContextService.java:45` "principal.getWorkspaceId()"

### 43. If it is sent, can the client send a workspace the user does not belong to?
There is exactly one endpoint that accepts a client-supplied workspace id, POST /workspace/members/switch, and it is safe: it looks up an active membership row for that workspace and this user and throws 403 when there is none before minting the new token. It is also unreachable — src/lib/api.ts has no wrapper for it at all.
`influora-api/src/main/java/com/influora/service/WorkspaceMemberService.java:376` "You are not a member of this workspace"

### 44. What renders when a user belongs to no workspace?
Login itself fails. brandLogin resolves the caller's oldest active membership and throws a 404 carrying the message that no workspace was found when there is none, so the user never receives a token — they stay on the login form with that server sentence in the red banner and no way forward.
`src/pages/brand-login.tsx:49` "setError(message)"

### 45. Is there any UI for inviting a member, and if not, what is the intended path?
Yes, in brand settings: an Invite Member dialog collecting an email and one of ADMIN/MANAGER/MEMBER/VIEWER, posting to /workspace/members/invite. The gap is on the other side — there is no accept-invite screen wired anywhere, even though POST /workspace/members/accept exists on the controller.
`src/pages/brand-settings.tsx:382` "api.workspaceMembers.invite(email, inviteRole)"

### 46. Does the member list render the member role, and is it accurate?
It renders the raw role string straight from MemberResponse, so it is accurate. What it cannot render is who the person is: the DTO carries only id, workspaceId, userId, role and active, so every row other than your own is labelled by a truncated user id.
`src/pages/brand-settings.tsx:349` "role: r.role"

### 47. What happens in the UI when a member is removed while they are using the app?
Nothing until they act. The deactivate endpoint flips the membership row inactive but does not revoke the access token, so the tab keeps rendering; the next brand call fails requireMember with a 403, which the interceptor deliberately does not retry, and the user gets whatever generic error that particular screen shows.
`influora-api/src/main/java/com/influora/service/BrandContextService.java:91` "You are not a member of this workspace"

### 48. Is workspace switching reachable, and what does it invalidate when it happens?
Not reachable. The server implements switching and returns a fresh access token carrying the new workspaceId, but no client wrapper, hook, or control calls it — grep for the path across src returns nothing — so the question of what it invalidates never arises in the running app.
`influora-api/src/main/java/com/influora/web/WorkspaceMemberController.java:105` "switchWorkspace"

### 49. Does any cached data survive a workspace switch that should not?
The premise cannot be exercised today because no switch control exists, but the hazard is pre-loaded: the workspace and member-role react-query entries are keyed only on the literal strings workspace and my-role with a five-minute staleTime and no workspace id in the key, so the first switch that ever ships would serve the previous workspace's name, verification status and role from cache.
`src/hooks/brand/useWorkspaceVerification.ts:62` "queryKey: ['workspace', 'my-role']"

### 50. What does a member see when their workspace is suspended mid-session?
Nothing distinguishable, because workspace suspension is not modelled. The Workspace entity carries only a VerificationStatus; suspension lives on the User row, and that gate is only evaluated at login and refresh, never on an ordinary request — so a suspended member keeps working normally until their access token expires.
`influora-api/src/main/java/com/influora/domain/entity/Workspace.java:48` "private VerificationStatus verificationStatus"

### 51. Which onboarding fields are collected by the form but never sent?
Four of the brand wizard's OnboardingData fields never leave the browser: confirmPassword, and the three OTP bookkeeping fields emailOtpSent, emailOtpCode and emailOtpVerified, which drive local screen state rather than any payload. The logo is a partial case — logoFile and logoPreview stay local and only logoUpload.url is transmitted.
`src/pages/brand-onboarding.tsx:96` "logoUrl: data.logoUpload?.url"

### 52. Which onboarding fields are sent but never persisted by the server?
None on the brand side — applyCompanyDetails writes all eight components of BrandCompanyRequest. On the creator side every sent field also lands, with verticals and languages serialised to JSON columns; the mismatch is the other direction, since applySelfEdit takes eleven arguments and onboarding passes null for four of them.
`influora-api/src/main/java/com/influora/service/CreatorOnboardingService.java:102` "profile.applySelfEdit"

### 53. Is onboarding progress written server-side per step, or only at the end?
Per step, but coarsely. The brand wizard writes the company record when leaving step 2 and the completion flag at step 3; the creator wizard writes the profile when leaving step 2 and the flag at step 3. Step 1 of each writes nothing beyond the account itself.
`src/pages/creator-onboarding.tsx:193` "setCurrentStep(3)"

### 54. If the user abandons midway and returns, what is restored and what is lost?
Only what already reached the server. Both wizards hold their form state in plain useState with no draft persistence, so every unsent keystroke is gone on reload; the brand wizard's only restoration is the token check that jumps a returning user from step 1 to step 2, which means the company form comes back empty even though the workspace row may already hold those values.
`src/pages/brand-onboarding.tsx:37` "hasBrandToken() ? 2 : 1"

### 55. What marks onboarding complete on the client versus on the server?
On the server, a single boolean on the user row set by completeBrand or completeCreator. On the client, three separate things: two localStorage flags plus a react-query cache entry, and the wizard writes all of them only after the server call resolves.
`src/pages/brand-onboarding.tsx:117` "localStorage.setItem('brand_onboarding_complete', 'true')"

### 56. Can those two disagree, and what does the user see if they do?
Yes, in both directions. getBrandOnboardingComplete ORs the two localStorage flags, so a stale true on a shared browser sends the user to the dashboard, where the guard's server check then bounces them back to onboarding; conversely a completed account on a fresh device has no flags and relies entirely on the login response. The visible symptom is a bounce between dashboard and wizard rather than an error.
`src/lib/auth-session.ts:218` "localStorage.getItem(ONBOARDING_KEY) === 'true'"

### 57. Does a failed onboarding save block progression, or does the wizard advance anyway?
It blocks, correctly, in both wizards — nextStep and setCurrentStep(3) sit after the awaited call inside the try, so a rejection skips them. The difference is how it is reported: the brand wizard renders an inline banner above the step, the creator wizard fires a destructive toast.
`src/pages/creator-onboarding.tsx:199` "Couldn’t save your profile"

### 58. Are onboarding validation errors mapped to the field that caused them?
Client-side yes, server-side no. Each step keeps its own errors record keyed by field name and renders the message under the matching input, but anything the server rejects — a duplicate slug at commit time, a rate-range violation, a bad company size — arrives as one message and goes to the page-level banner or a toast.
`src/components/brand/onboarding/onboarding-steps.tsx:917` "setErrors(e)"

### 59. Is any onboarding step skippable in the UI but required by the server?
No, and the one place that looked like it is deliberately the opposite. Creator step 1 gates Continue on having a connected social, but a Skip control appears precisely when none is connected, and the server never checks socials at completion — so the UI is stricter than the server, not looser.
`src/pages/creator-onboarding.tsx:364` "Skip for now — connect from Settings later"

### 60. Does the onboarding redirect loop if the completion flag never lands?
It cannot loop on a failed completion, because navigation only happens after the call succeeds and /brand/onboarding is itself unguarded. The loop risk is the cache: the guard holds the status query for five minutes, which is exactly why completeBrand invalidates it before navigating — remove that invalidation and a freshly-onboarded brand bounces back to the wizard until the cache expires.
`src/pages/brand-onboarding.tsx:125` "invalidateQueries({ queryKey: ['brand-onboarding-status'] })"

### 61. Does the profile save send a full object or only changed fields?
Both patterns exist, each matched to its endpoint's semantics. The brand workspace save deliberately sends the full object because PATCH /workspaces/me is full-replace and an omitted field is cleared server-side; the creator profile PATCH is a genuine partial merge, so the phone dialog sends only phone and the avatar handler only avatarUrl.
`src/pages/brand-settings.tsx:188` "api.workspaces.updateMe"

### 62. Which profile fields does the server accept that the settings UI never sends?
On the workspace side, industry, companySize, description and logoUrl are all in WorkspaceMeUpdatePayload but the settings card sends only name, email, phone and websiteUrl — and because that PATCH is full-replace, saving the card is what clears the other four. On the creator side, coverImageUrl and contentStyles are patchable and no settings control writes them.
`src/lib/api.ts:1174` "http.request<WorkspaceMeResponse>('PATCH', '/workspaces/me'"

### 63. Which fields does the settings UI send that the server ignores?
None. Every field either page sends maps onto a real record component, and the creator patch request and its TypeScript payload agree field for field including phone. The dishonesty in this area is in controls that send nothing at all, not in fields the server discards.
`influora-api/src/main/java/com/influora/web/dto/creator/CreatorProfileDtos.java:63` "String phone) {}"

### 64. After a successful save, does the UI refetch or trust its local state?
Neither exactly — it adopts the response body, which is better than both. Both PATCH endpoints return the persisted record and both pages write that returned object straight into state, so a cleared field comes back null and is rendered as empty rather than reverting to what the user typed.
`src/pages/creator-settings.tsx:188` "setSavedPhone(updated.phone)"

### 65. If the save succeeds partially, what does the user see?
The question does not arise for these endpoints — both PATCHes are single transactional writes that either apply wholly or throw. The nearest real hazard is the brand card's full-replace behaviour: a save that succeeds completely still silently blanks industry, companySize, description and logo, and the user sees a success toast.
`src/pages/brand-settings.tsx:203` "Your workspace information has been saved."

### 66. Is the email change flow wired, and does it require re-verification?
There is no login-email change flow at all — no endpoint, no client method, no control. The email field in brand settings writes workspaces.billing_email, a different column from the users.email you sign in with, and it is saved with no verification of any kind.
`src/pages/brand-settings.tsx:190` "email: settings.email"

### 67. Is the phone change flow wired, and is the new number verified?
Wired and round-tripping for creators — a dialog validates a 10-digit Indian mobile, PATCHes it, and shows the persisted value back, with a 409 duplicate rendered inline next to the field. It is not verified: no OTP is sent, and the phoneVerified column on the user row is never set by this path.
`src/pages/creator-settings.tsx:187` "api.creatorProfile.patchMe({ phone: stripped })"

### 68. Does the avatar upload send the file to the server or to storage directly?
The creator avatar goes to the server: POST /uploads multipart, then the returned URL is PATCHed onto the profile. The brand logo in onboarding does neither — uploadToR2 is a mock that sleeps ten times and returns a fabricated URL under r2.influora.com, so the workspace stores a link to a file that was never uploaded anywhere.
`src/lib/upload.ts:65` "https://r2.influora.com/"

### 69. What file types and sizes does the client enforce, and does the server agree?
They disagree in both directions. The onboarding logo drop zone accepts PNG, JPEG, WebP and SVG at up to 2MB, while UploadService caps at 10MB and rejects anything that is not an image or PDF — so SVG passes the client and is refused by the server, and the creator avatar picker enforces nothing client-side at all.
`influora-api/src/main/java/com/influora/service/UploadService.java:41` "MAX_BYTES = 10_485_760L"

### 70. What does the user see when an upload is rejected by the server?
A destructive toast carrying the server's own sentence — for a bad type that is the message about only images and PDFs being allowed. For the brand logo there is no server rejection to see at all, because the mock upload cannot fail; the only failure path there is the toast in handleLogoSelect that can never fire in practice.
`influora-api/src/main/java/com/influora/service/UploadService.java:242` "only images and PDF documents are allowed"

### 71. Does the connected-accounts panel reflect real connection state or local optimism?
Real state, with the localStorage mirror used only as a seed. useMetaConnection re-verifies against GET /meta/oauth/status on mount, and the card deliberately holds a neutral verifying state rather than flashing Not connected off a stale seed. The residual honesty gap is that a failed status call keeps showing last-known state.
`src/components/creator/connected-accounts.tsx:107` "verifying && isConnected"

### 72. Does disconnecting an account update the panel before the server confirms?
No — it awaits the revoke call and then awaits a refresh of the real status, so nothing changes on screen until the server has confirmed. A failure leaves the panel exactly as it was and raises a destructive toast.
`src/components/creator/connected-accounts.tsx:69` "await refresh()"

### 73. Are notification preferences persisted, and does the UI read them back?
Partly. The global email opt-out is loaded on mount from GET /notifications/preferences and written back through POST on toggle, with an optimistic flip that reverts on failure. The four category switches on the creator page are pure local state with no backend concept to bind to.
`src/pages/creator-settings.tsx:220` "revert on failure"

### 74. Is there a settings control that saves nothing?
Several, and they are honest about it only on the brand page. Creator settings renders New Proposals, Deadline Reminders, Payment Updates, Marketing and SMS as live switches that persist nothing; brand settings at least renders its two unbacked switches disabled with a title explaining why.
`src/pages/brand-settings.tsx:669` "Push notifications are not available yet"

### 75. Does the change-password form send the current password, and does the server require it?
Yes on both counts. The client posts currentPassword and newPassword to /me/password with an explicit role so the right token is attached, and changePassword re-runs the same BCrypt match login uses, rejecting with 401 rather than 400 so a stolen session alone cannot rotate the password.
`src/pages/creator-settings.tsx:286` "api.auth.changePassword('creator'"

### 76. How does the client discover that the account was suspended?
Only at a token boundary, never mid-session. The JWT filter builds the principal purely from the signed claims with no database lookup, so nothing re-reads UserStatus on an ordinary request; the suspension gate lives in login and refresh alone and therefore first bites when the current access token expires.
`influora-api/src/main/java/com/influora/security/JwtAuthenticationFilter.java:39` "new AuthPrincipal(userId, email, userType, workspaceId)"

### 77. What renders for a suspended user — a message, a redirect, or a broken page?
On a fresh sign-in, the server's suspension sentence in the login form's red banner. Mid-session it is worse than any of the three options offered: the refresh call fails, the token is cleared, and the next guarded navigation silently redirects to the login screen with no explanation of what happened.
`src/lib/api.ts:518` "this.clearToken(role)"

### 78. Is there any UI for account deletion, and what does it call?
Creators have one — a confirm dialog calling DELETE /me/account, a server-side soft delete that anonymises PII and revokes refresh tokens. Brands have none: the Danger Zone contains only Logout from All Devices, and the workspace-deletion control was deliberately not built.
`src/pages/creator-settings.tsx:328` "api.me.deleteAccount('creator')"

### 79. What does the client do with a 401 that is actually a suspension?
The premise is inverted — suspension is answered as a 403, not a 401, and the interceptor deliberately never retries a 403 because refreshing cannot change the answer. The damage happens one level down: when a suspended session's refresh returns that 403, refreshAccessToken collapses every non-ok response to null, so the code and message are discarded before any caller could distinguish suspension from an ordinary expiry.
`src/lib/api.ts:440` "if (!res.ok) return null"

### 80. Is last-login or session activity shown to the user anywhere?
No. The user row records lastLoginAt and brandLogin updates it on every sign-in, but no DTO in the brand or creator surface exposes it and no screen renders it — the only lastLogin the UI knows about is the admin console's view of admin accounts.
`influora-api/src/main/java/com/influora/domain/entity/User.java:69` "private Instant lastLoginAt"

### 81. Which auth errors are shown verbatim from the server and which are replaced?
Almost all are verbatim: every auth page uses the same pattern of taking err.message when the throw is an ApiError and only substituting a generic sentence otherwise. The deliberate replacements are the ones where the server's phrasing is unhelpful — a 403 on workspace save becomes an owners-and-admins sentence, and a 403 on campaign delete becomes a named-role explanation.
`src/pages/brand-settings.tsx:208` "Only workspace owners/admins can change these settings."

### 82. Does any auth error leak whether an email exists?
Login does not — a missing user and a wrong password both throw the same invalid-credentials 401. Registration does, unavoidably and by design, with a distinct 409 naming the duplicate; and forgot-password is careful, returning the same non-committal sentence whether or not the address resolved.
`influora-api/src/main/java/com/influora/service/AuthService.java:116` "An account with this email already exists"

### 83. What renders when the user record fetch returns null?
The identity hook is built for exactly this: every field normalises empty and whitespace to null and the shell renders a neutral skeleton rather than a placeholder name, deliberately, because the old fallback strings were another creator's handle. The profile page instead renders an explicit failure state with a Retry button.
`src/hooks/use-creator-identity.ts:41` "const orNull = (v: string | null | undefined)"

### 84. Which user-facing screens assume a non-null profile and would crash without one?
None that I can find in the identity path, so the premise does not hold as stated — the greeting surfaces all use optional chaining down to a neutral fallback, and the profile page short-circuits on a null profile before rendering. The exposure is the error boundary being a genuine last line rather than an unused one.
`src/pages/creator-dashboard.tsx:364` "user?.displayName?.split(/\s+/)[0]"

### 85. Is there a global error boundary around the authenticated shell?
Yes, and it is positioned correctly. RoutedErrorBoundary sits inside BrowserRouter and takes the current pathname as its reset key, so a throw kills only the current route's render and navigating away recovers — the earlier arrangement outside the Router permanently destroyed the routing tree on one transient throw.
`src/App.tsx:177` "<ErrorBoundary resetKey={location.pathname}>"

### 86. What happens if the token is present but malformed?
The client treats it as usable and lets the server decide. decodeJwtClaims returns null for anything that is not a three-part JWT, tokenMatchesRole then fails open, the route guard sees a non-empty string and renders the shell, and the first API call 401s into a refresh attempt. Only the admin layer pre-validates structure and clears a malformed token without a round trip.
`src/admin/hooks/useAdminAuth.ts:222` "localStorage.removeItem('admin_token')"

### 87. What happens if the server returns a role the client has no case for?
It degrades to no permissions rather than throwing. The admin matrix indexes ROLE_PERMISSIONS by the returned role and coalesces an undefined lookup to false, so an unknown AdminRole renders a console with every capability withheld and no error explaining why.
`src/admin/hooks/useAdminAuth.ts:253` "ROLE_PERMISSIONS[user.role]?.includes(permission) ?? false"

### 88. Are there user fields typed non-null on the client that the server can send null?
Yes — engagementRate on the creator self-profile is the clearest. The TypeScript declares it as a plain number, but the column is nullable and is only ever written by the aggregated-stats path, so a creator who has never synced a platform receives null into a field the client believes is always a number.
`influora-api/src/main/java/com/influora/domain/entity/CreatorProfile.java:90` "private BigDecimal engagementRate"

### 89. Are there user fields the client declares that the server never sends?
Two clear cases. The admin login response type declares refreshToken as a required string, but that component is @JsonIgnore'd server-side precisely so it never reaches JS. And the shared User type declares locale, which has no column on the entity and no field on any DTO.
`src/admin/types/admin.types.ts:94` "refreshToken: string"

### 90. Does the user type in the admin layer agree with the one in the main app?
No, and they are not meant to — they describe different populations. AdminUser is id, email, role, mfaEnabled, lastLogin and createdAt; the app's User is a much wider record with userType, status, both verification booleans, names, timezone and locale. Nothing converts between them and no screen consumes both.
`src/admin/types/admin.types.ts:77` "export interface AdminUser"

### 91. Do all API layers attach the same auth header and base URL?
The header shape is uniform — Bearer on an Authorization header everywhere — but neither the token source nor the base URL is. The main client and the Meera client both read VITE_API_BASE_URL with the same localhost fallback, yet the Meera client only ever reads brand_token; the admin layer uses neither the variable nor those keys.
`src/lib/meera-api.ts:28` "http://localhost:8080/api/v1"

### 92. Which layer would break first if the API base URL changed?
The admin console, immediately and silently. Its base is a hardcoded relative path that resolves against whatever origin serves the SPA, so moving the API to a different host leaves every admin call pointing at the frontend origin while brand, creator and Meera calls follow the new value.
`src/admin/services/api-contracts.ts:67` "const API_BASE = '/api/v1/admin'"

### 93. Is there a shared current-user store, or does each screen fetch its own?
There is a store, but it is not the source of truth. The zustand auth store holds a user and is seeded at login, and because it persists nothing it is empty after every reload — so each surface re-derives identity for itself, from localStorage or its own fetch.
`src/lib/store.ts:32` "setUser: (user) => set({ user, isAuthenticated: !!user })"

### 94. Does any component fetch the user on every render or in an unguarded effect?
Not per render — the identity hook guards with a ref precisely to stop the store back-fill from re-triggering its own effect. The duplication is across components instead: GET /me/creator-profile has three independent mount-effect call sites, in the identity hook, the settings page and the profile page, none of them sharing a cache.
`src/pages/creator-settings.tsx:144` ".getMe()"

### 95. Which user-related calls fire before the session is known to be valid?
Every brand shell query. GET /workspaces/me and GET /workspace/members are issued from BrandLayout and useWorkspaceVerification with no enabled guard at all — the only precondition is that ProtectedRoute saw a non-empty string in localStorage, which is presence, not validity, so a stale or malformed token still fires both.
`src/components/brand/brand-layout.tsx:164` "queryKey: ['workspace', 'me']"

### 96. Is demo or mock mode able to produce a user that looks real?
Yes, and deliberately so — the demo panel mints a complete brand session for Glow Naturals through the same persistBrandSession the live path uses, and the creator side seeds a fully-formed Priya Creator into the auth store. Nothing on screen distinguishes those from a real session once you are past the login page.
`src/components/shared/demo-access-panel.tsx:25` "growth@glownaturals.in"

### 97. Are there seeded development users reachable in a production build?
Not reachable, but the guard is one layer thinner than it reads. The panel hides itself whenever the API mode is live, and assertMockAuthAllowed throws on a production build that is not live — which means a misconfigured production deploy still renders the Demo access panel and only fails when a button is pressed.
`src/components/shared/demo-access-panel.tsx:37` "if (isApiLive()) return null"

### 98. Which user-related env vars does the client read, and what is the fallback when unset?
Three matter here. VITE_API_BASE_URL falls back to a localhost address, so an unset value in a deployed build sends every auth call to the user's own machine; VITE_API_MODE falls back to mock, which is the exact state the fail-closed mock guard exists to catch; VITE_MEERA_STREAM_URL falls back to an internal hostname.
`src/lib/api.ts:53` "http://localhost:8080/api/v1"

### 99. Is any user identifier, workspace id or token written to a URL or query string?
Yes — the admin realtime client puts the full admin bearer token in a query parameter, because the browser WebSocket API cannot set headers. That places a live admin credential in referrer headers, proxy logs and browser history. The password-reset link also carries its token in the query string, which is unavoidable for that flow.
`src/admin/services/websocket.ts:269` "token=${encodeURIComponent(token)}"

### 100. Which single break in this area would a real user hit first on their first day?
A brand signing up through the main CTA is given a name they never entered. The register page derives firstName and lastName by splitting the email local part, so sales@acme.com becomes Sales User, and that fabricated name is what greets them on the onboarding completion screen and the dashboard — with no field anywhere in brand settings to correct it. The sharper functional break sits one screen earlier for creators: unchecking Remember me stores the token in sessionStorage while the route guard reads only localStorage, so a successful login bounces straight back to the login form.
`src/pages/brand-register.tsx:116` "const lastName = rest.length > 0"
