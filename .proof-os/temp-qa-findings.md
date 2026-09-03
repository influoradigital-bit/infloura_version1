# QA Review: T-CREATORCONNECT-0902 Frontend Half

**Date:** 2026-09-02  
**Reviewer:** Kavya (QA Lead)  
**Files:** src/lib/api.ts, src/admin/{types/admin.types.ts, services/api-contracts.ts, pages/CreatorConnectionsPage.tsx + .test.tsx}, src/pages/{admin-console.tsx, brand-new-campaign.tsx}, src/admin/components/AdminLayout.tsx, src/components/brand/{discover/creator-discovery.tsx + creator-discovery.instagram.test.tsx, campaigns/campaign-form.tsx}, src/lib/__tests__/api-contract.test.ts  

---

## FINDINGS

| # | Severity | File:Line | What is wrong | What would fix it |
|---|----------|-----------|---------------|-------------------|
| 1 | P2 | src/admin/pages/CreatorConnectionsPage.tsx:217 | Admin notes Textarea has no maxLength attribute; contract specifies notes ≤1000 chars (VARCHAR(1000)). Brand message textarea (creator-discovery.tsx:2298) correctly caps at 1000 with both maxLength attr AND slice(0,1000). Server validates, but client should cap consistently. | Add `maxLength={1000}` to Textarea and `.slice(0, 1000)` in onChange handler, matching brand message pattern |

---

## VERIFICATION RESULTS

### Contract Parity ✅
- **ExternalCreator** (TS) matches **ExternalCreatorResponse** (Java) field-for-field, same nullability
- **ConnectionRequest** (TS) matches **ConnectionRequestResponse** (Java) field-for-field, same nullability  
- **AdminConnection** (TS) matches **AdminConnectionDto** (Java) field-for-field, same nullability
- All paths in api.ts + api-contracts.ts match contract exactly
- All endpoints added to api-contract.test.ts as KNOWN_PHANTOM_PATHS (awaiting Vikram's backend)

### Honesty ✅
- No mock creators: 503 → "Instagram lookup isn't connected yet" with data-testid="instagram-unavailable"
- No coercion: followers/engagement null → "—" via externalFollowers/externalEngagement helpers
- Distinct loading/empty/503 states in InstagramCreatorsTab (showUnavailable/showInitialLoading/showEmpty)
- Success toast only after 200: "Request sent" fires in submitConnect's try block, after api call resolves

### Dead Controls ✅
- "Connect this creator" → onClick={() => onConnect(creator)} → openConnect → submitConnect → api.externalCreators.connect
- Admin "Mark contacted" → onClick={() => setActiveAction({connection: c, kind: 'contacted'})} → actionMutation.mutate → creatorConnectionsApi.markContacted
- Admin "Invite" → same pattern → creatorConnectionsApi.invite
- Admin "Decline" → same pattern → creatorConnectionsApi.decline
- Tests prove it: creator-discovery.instagram.test.tsx line 138 clicks "Connect", waits for dialog, clicks "Send request", asserts externalConnect called with correct id

### Regression ✅
- Existing "Influora creators" wrapped in `{sourceTab === 'influora' && (<> ... </>)}` at lines 940, 1265, 1305
- Search bar, filters, grid, load-more all gated on sourceTab
- Diff of moved block vs HEAD (git diff --cached -w) shows pure indentation changes only — logic untouched

### Button States ✅
Contract table vs actual:
- connectionStatus null → "Connect this creator" primary button (line 2026) ✅
- PENDING → disabled secondary "Request sent" (connectionRequestStatusLabel returns it) ✅
- CONTACTED → disabled secondary "Team reached out" ✅
- DECLINED → disabled ghost "Not available" (line 2033) ✅
- JOINED/verifiedWithInfluora → "View profile" outline + "Create campaign" primary with href="/brand/campaigns/new?creatorId={linkedCreatorProfileId}" (line 2020) ✅

### Badge by Status ✅
- UNVERIFIED/INVITED → amber ShieldAlert "Unverified with Influora" (line 1905)
- JOINED → green ShieldCheck "Verified with Influora" (line 1897)

### ?creatorId= Flow ✅
- brand-new-campaign.tsx reads useSearchParams().get('creatorId'), shows dismissible banner with resolved handle
- campaign-form.tsx reads same param (no navigate between screens), shows its own banner
- Invite fires ONLY after successful create: `const saved = await api.campaigns.create(payload); ... if (!isEditing && creatorIdParam) { await api.creators.invite(creatorIdParam, saved.id); }` (line 522-526)
- Invite failure does NOT lose campaign: try/catch around invite, campaign already created and added via addCampaign(saved) before invite attempt
- Banner permission: calls api.creators.getProfile(creatorId) best-effort; on 403/404 catch renders "this creator" not handle — no data leak

### Security/A11y ✅
- Message length: brand Textarea maxLength={1000} + onChange slice(0,1000) + character counter (line 2298)
- Admin notes: no maxLength (P2 finding above)
- Dialog label: DialogTitle present on connect dialog (line 2344) — provides aria-labelledby via Radix
- No dangerouslySetInnerHTML: grep found none
- Admin email: EMAIL_RE validation + emailValid gates submit button (line 159)

### Untracked Files ✅
`git status --porcelain | grep "^??"` shows .f0248-backup.tsx, .proof-os/gates/*.spec.tsx, useDailySuggestion.test.ts — none related to T-CREATORCONNECT-0902

### Build/Tests ✅
- `npx tsc --noEmit` → exit 0
- `npx vitest run src/components/brand/discover src/admin/pages src/lib/__tests__/api-contract.test.ts` → 19 passed (8 files)
- creator-discovery.instagram.test.tsx covers all 3 contract gates: badge rendering, connect button wiring, 503 unavailable state

---

## VERDICT

**PASS-WITH-P2** — one consistency issue (admin notes lacks client-side length cap), no blockers. The P2 is a nice-to-have (server validates at 1000 anyway), not a contract violation. Ready for Meera's local build verification once Ananya applies the fix.

---

## NEXT

Ananya fixes P2 finding → re-submit to Kavya (no full re-review needed, just confirm the maxLength line) → Meera runs build + manual click test on both tabs + admin page.
