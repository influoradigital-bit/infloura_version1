I have everything. Here is the fact sheet.

---

# Phase B build-spec fact sheet — "Meera for Creators" @ HEAD `8c7b18b`

Repo root: `C:\Users\Sage world\Downloads\New Influora Ai\New Influora`
HEAD: `8c7b18b feat(meera-creator): Phase A gate fixes, Priya 10/10 signed off (T-MEERA-CREATOR-PHASE-A)`

---

## 1. `src/lib/api.ts` (6362 lines)

### 1.1 Environment / mode (lines 51–130)

```ts
const API_BASE_URL =
  import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';   // :53

const API_MODE: 'live' | 'mock' =
  import.meta.env?.VITE_API_MODE === 'live' ? 'live' : 'mock';            // :56

/** True when `VITE_API_MODE=live` */
export function isApiLive(): boolean { return API_MODE === 'live'; }      // :60
```

Money flags (`:70`, `:110`) and:
```ts
export type MoneyOperation = 'topup' | 'withdraw' | 'escrow-fund';        // :65
export function isMoneyActionBlocked(operation: MoneyOperation): boolean  // :118
```

```ts
export class PaymentsUnavailableError extends Error {                     // :160
  readonly operation: MoneyOperation;
  constructor(operation: MoneyOperation) {
    super('Payments are not enabled on this environment yet.');
    this.name = 'PaymentsUnavailableError';
    this.operation = operation;
  }
}
```

### 1.2 Tokens / roles (lines 183–201)

```ts
const TOKEN_KEYS   = { brand: 'brand_token',       creator: 'creator_token' } as const;   // :183
const REMEMBER_ME_KEYS = { brand: 'brand_remember_me', creator: 'creator_remember_me' };  // :196
export type Role = 'brand' | 'creator';                                                   // :201
```

### 1.3 Envelope + `ApiError` (lines 216–285)

```ts
export interface ApiErrorPayload {              // :216
  code: string;
  message: string;
  field?: string;
  fields?: Array<{ field: string; message: string }>;
  requiredAmount?: number;
  walletBalance?: number;
  shortfallAmount?: number;
  currency?: string;
  linkedCreatorProfileId?: string;
}

export interface ApiEnvelope<T> {               // :233
  success: boolean;
  data?: T;
  error?: ApiErrorPayload;
  meta?: { page?: number; limit?: number; total?: number; hasMore?: boolean };
}

export interface InsufficientFundsDetails {     // :247
  requiredAmount: number;
  walletBalance: number;
  shortfallAmount: number;
  currency: string;
}

export function extractInsufficientFundsDetails(
  error: ApiErrorPayload | undefined,
): InsufficientFundsDetails | undefined                                    // :256
```

**`ApiError` — verbatim (line 274):**
```ts
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status?: number,
    /** Populated only for `INSUFFICIENT_FUNDS` 402s that carry the server-computed shortfall. */
    public details?: InsufficientFundsDetails,
    public linkedCreatorProfileId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
```
So `new ApiError(code, message, status?)` — positional, **`code` first**. `err instanceof ApiError && err.code === 'X'` is the idiom used everywhere.

### 1.4 The request helpers — `class HttpClient` (line 361), instance `const http = new HttpClient();` (line 763)

All four have the identical opts bag:

```ts
async request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  opts: {
    role?: Role;
    body?: unknown;
    query?: Record<string, string | number | boolean | undefined>;
    idempotencyKey?: string;
  } = {},
): Promise<T>                                                              // :537

async requestWithMeta<T>(...same...): Promise<{ data: T; meta: ApiEnvelope<T>['meta'] }>  // :620
async requestOrNull<T>(...same...): Promise<T | null>                       // :685  (204 → null)
async upload<T>(path: string, file: File, role: Role = 'brand'): Promise<T> // :731
async uploadForm<T>(path: string, formData: FormData, role: Role = 'brand'): Promise<T>  // :742
async downloadBlob(path: string, role: Role = 'brand'): Promise<Blob>       // :767 (approx)
```
Notes that matter for new code: `role` **defaults to `'brand'`** — every creator call must pass `{ role: 'creator' }`. Empty-string/`undefined`/`null` query values are dropped. `credentials: 'include'` always. Non-JSON bodies throw `ApiError('SERVER_UNAVAILABLE'|'BAD_RESPONSE', …)`.

### 1.5 Mock helpers (lines 771–782)

```ts
const delay = (ms = 400) => new Promise((r) => setTimeout(r, ms));
async function mockOr<T>(value: T | Promise<T>): Promise<T> { await delay(); return value; }
const isLive = () => API_MODE === 'live';
```
**The universal namespace pattern** (copy this shape for any new Phase B endpoint):
```ts
methodName: (args) =>
  isLive()
    ? http.request<T>('POST', '/path', { role: 'creator', body })
    : mockOr<T>(MOCK_VALUE),
```

### 1.6 `MOCK_*` / `mock*` constants in api.ts

`SCREAMING_SNAKE` for the newest (Phase A) ones, `camelCase` for older ones:

| Line | Name |
|---|---|
| 3448 | `mockCreatorProfileSelf` |
| 3540 | `mockUserProfileMe` |
| 4414 | `mockMetrics` / 4432 `mockScores` / 4439 `mockDemographics` |
| 5116 | `mockCreatorCoupons` |
| 5296 | `mockCreatorCampaigns` (+ `mockCreatorCampaignDetail(id)` at 5378) |
| 5788 | `mockEligibleDeals` |
| 5910 | `MOCK_TRENDSPARK_NUDGE` |
| 5971 | `MOCK_CREATOR_SUGGESTION` |
| 6087 | `MOCK_CREATOR_AGENT_PREFS` |

Demo-mode routing bypass is **not** in api.ts — it's in `src/App.tsx:98` / `:146`:
```ts
const isDemoMode = import.meta.env.DEV && new URLSearchParams(window.location.search).get('demo') === 'true';
```

### 1.7 `DealTerms` — `src/lib/types.ts:31`

```ts
export type UsageChannel = 'ORGANIC' | 'PAID_ADS' | 'WHITELISTING' | 'WEBSITE' | 'OFFLINE';  // types.ts:19
export type ExclusivityScope = 'NONE' | 'NAMED_BRANDS' | 'CATEGORY';                          // types.ts:22

export interface DealTerms {                                                                  // types.ts:31
  usageMonths: number | null;
  usagePerpetual: boolean;
  usageChannels: UsageChannel[];
  exclusivityDays: number | null;
  exclusivityScope: ExclusivityScope;
  exclusivityBrands: string[];
  maxRevisions: number;
}
```
`DealTerms` is **camelCase** (unlike the snake_case `CreatorAgentPreferences`). It is imported into api.ts via the type-only import block at `api.ts:25–41`.

### 1.8 `Deal` — `api.ts:2060`

```ts
export interface Deal {
  id: string;
  campaignId: string;
  campaignName: string;
  counterpartyId: string;              // creatorId for brand, brandId for creator
  counterpartyProfileId?: string | null;
  counterpartyName: string;
  counterpartyAvatar?: string;
  counterpartyHandle?: string;
  counterpartyVerificationStatus?: VerificationStatus | null;
  status: CollaborationStatus;
  dealValue: number;
  currency: 'INR' | 'USD';
  lastMessage?: string;
  lastMessageAt?: string;              // ISO
  unreadCount: number;
  deliverablesDone: number;
  deliverablesTotal: number;
  nextDeadline?: string;               // ISO
  contractId?: string;
  contractStatus?: ContractStatus;
  escrowFunded: boolean;
  dealTerms?: DealTerms;               // @JsonInclude(NON_NULL) — OMITTED, never null
}
```

Status filters (`api.ts:2038`, `:2058`):
```ts
export type DealStatusFilter =
  | 'all' | 'new' | 'negotiating' | 'contracted' | 'in_progress' | 'review' | 'completed' | 'disputed';
export type DealStatusQuery = DealStatusFilter | `${string},${string}`;
```

### 1.9 `api.deals.*` — `api.ts:2103`

```ts
list:   (role: Role, status: DealStatusQuery = 'all') => Promise<Deal[]>
get:    (role: Role, id: string) => Promise<Deal>            // mock returns null
accept: (id: string, role: Role = 'creator') => Promise<Deal>
reject: (id: string, reason?: string, role: Role = 'creator') => Promise<{ ok: true }>

counter: (
  id: string,
  payload: {
    amount: number;
    message?: string;
    deliverables?: Array<{ type: string; qty: number }>;
    deadline?: string;
    usageRights?: string;
    dealTerms?: DealTerms;
  },
  role: Role = 'creator',
  idempotencyKey?: string,
) => Promise<Deal>

create: (payload: {
  campaignId: string; creatorId: string; amount: number;
  deliverables?: Array<{ type: string; qty: number }>;
  deadline?: string; usageRights?: string; message?: string;
  dealTerms?: DealTerms;
}) => Promise<Deal>            // brand-only, role defaults to 'brand'
```
`counter` is the **only** existing creator-side write that accepts `dealTerms` — the negotiation coach's "send counter" action lands here. Pass a fresh `idempotencyKey` per user action or the server derives one from `dealId + amount` and silently no-ops a same-amount re-counter.

### 1.10 `api.messages.*` — `api.ts:2242`

```ts
export type MessageKind = 'text' | 'system' | 'proposal' | 'contract' | 'deliverable' | 'payment' | 'shipment';  // :2222

export interface DealMessage {   // :2231
  id: string;
  dealId: string;
  kind: MessageKind;
  senderId: string;
  senderType: 'brand' | 'creator' | 'system';
  content?: string;
  metadata?: Record<string, unknown>;
  createdAt: string;
  readBy: string[];
}

list:     (role: Role, dealId: string, before?: string) => Promise<DealMessage[]>       // :2244
send:     (role: Role, dealId: string, content: string, kind: MessageKind = 'text')     // :2251
             => Promise<DealMessage>   // idempotencyKey: `${dealId}-${Date.now()}`
markRead: (role: Role, dealId: string) => Promise<{ ok: true }>                         // :2281
stream:   (role: Role, dealId: string, handlers: DealMessageStreamHandlers)
             => DealMessageStreamHandle                                                 // :2332
```

```ts
export type DealMessageStreamStatus = 'open' | 'reconnecting' | 'closed';   // :2490
export interface DealMessageStreamHandlers {                                // :2492
  onMessage: (message: DealMessage) => void;   // required
  onOpen?: () => void;
  onError?: (error: Error) => void;
  onStatusChange?: (status: DealMessageStreamStatus) => void;
  onReconnect?: () => void;                    // REFETCH here — no Last-Event-ID replay
}
export interface DealMessageStreamHandle { close: () => void }              // :2518
```
This is the send-log's realtime source: an auto-reply Meera sends will come back as an ordinary `DealMessage` on this stream (it has no distinct kind today — `MessageKind` has no `'agent'`/`'auto'` member; you'd need either a new kind or a `metadata` discriminator).

### 1.11 `CreatorAgentPreferences` — `api.ts:6015–6086` (verbatim)

```ts
export type CreatorApprovalLevel = 0 | 1 | 2;                      // :6015
export type CreatorBrandTone = 'FORMAL' | 'FRIENDLY';              // :6016

/** GET/PUT /creator/agent-preferences response shape (SPEC.md §2.2/§2.3). */
export interface CreatorAgentPreferences {                         // :6019
  reel_floor: number | null;
  story_set_floor: number | null;
  post_floor: number | null;
  floor_currency: string;
  excluded_categories: string[];
  blocked_brands: string[];
  approval_level: CreatorApprovalLevel;
  creator_language: string;
  brand_tone: CreatorBrandTone;
  working_hours_start: number | null;
  working_hours_end: number | null;
  working_hours_timezone: string;
  working_days: number[];
  weekly_sponsored_limit: number | null;
  represented: boolean;
  agency_name: string | null;
  consent_accepted: boolean;
  consent_version: string;
}

export type CreatorAgentPreferencesUpdate = Omit<                  // :6059
  CreatorAgentPreferences,
  'consent_accepted' | 'consent_version'
>;

export interface CreatorAgentConsentResponse { consent_accepted_at: string }   // :6064

export interface CreatorAgentConversationItem {                    // :6068
  conversation_id: string;
  started_at: string;
  last_message_at: string;
  message_count: number;
}

export interface CreatorAgentConversationExportMessage {           // :6075
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
}

export interface CreatorAgentConversationExport {                  // :6081
  conversation_id: string;
  started_at: string;
  messages: CreatorAgentConversationExportMessage[];
}
```
**This block is snake_case on purpose** — the file header comment at `:6005` says the DTO shapes are deliberately NOT camelCased so a drift surfaces as a runtime `undefined`. Keep any new Meera-creator DTOs snake_case for consistency.

`api.creatorAgentPrefs` (`:6110`):
```ts
getPreferences:      () => Promise<CreatorAgentPreferences>                       // GET  /creator/agent-preferences
updatePreferences:   (payload: CreatorAgentPreferencesUpdate) => Promise<CreatorAgentPreferences>  // PUT
recordConsent:       () => Promise<CreatorAgentConsentResponse>                   // POST /creator/agent-preferences/consent (empty body)
listConversations:   () => Promise<{ conversations: CreatorAgentConversationItem[] }>
exportConversation:  (conversationId: string) => Promise<CreatorAgentConversationExport>
deleteConversation:  (conversationId: string) => Promise<void>
```
All pass `{ role: 'creator' }`. All 404 with `{ code: 'FEATURE_DISABLED' }` when `MEERA_CREATOR_ENABLED` is off — that's the rollback flag Phase B must keep honouring.

### 1.12 `PublicVerifiedMetrics` — `api.ts:6174`

```ts
export interface PublicVerifiedMetrics {          // :6174
  followers: number;
  reach_30d: number | null;
  engagement_rate: number | null;
  verified_at: string | null;
}

export interface PublicCreatorVerifiedResponse {  // :6181
  username: string;
  display_name: string;
  city: string | null;
  categories: string[];
  verified_metrics: PublicVerifiedMetrics;
  platform_deal_count: number;
  snapshot_date: string;
}

export const publicCreators = {                   // :6188
  getVerifiedMetrics: (username: string) => Promise<PublicCreatorVerifiedResponse>
  // GET /public/creators/:username/verified — NO auth, NO rates, NO floors
};
```
The three nullable fields are load-bearing for the media kit: a Meta-connected creator with no polled metrics omits all three from the JSON.

### 1.13 `CreatorProfile` (public/search shape) — `src/lib/types.ts:542`

```ts
export interface CreatorProfile {
  id: string; userId: string; displayName: string;
  bio?: string; avatarUrl?: string; coverImageUrl?: string; location?: string;
  categories: string[];
  platforms: PlatformStats[];
  totalFollowers: number;
  engagementRate: number;
  averageRate?: number;
  currency?: string;
  isVerified: boolean;
  saved?: boolean;
  portfolioItems: PortfolioItem[];
  languages?: string[];
  contentStyles?: string[];
  scores?: CreatorScoresSummary | null;
}

export interface PlatformStats {   // types.ts:566
  platform: Platform; handle: string; followers: number;
  engagementRate: number; isVerified: boolean; profileUrl?: string;
}
```

**The creator's own profile is a different type** — `api.ts:3400`:
```ts
export interface CreatorProfileSelfResponse {
  id, userId, displayName: string;
  username: string | null; bio: string | null; avatarUrl: string | null;
  coverImageUrl: string | null; city: string | null; phone: string | null;
  categories: string[]; languages: string[]; contentStyles: string[];
  platforms: CreatorPlatformStat[];
  rateMin: number | null; rateMax: number | null; currency: string;
  discoverable: boolean; verified: boolean;
  totalFollowers: number; engagementRate: number;
  onboardingComplete: boolean; profileCompleteness: number;
}
export interface CreatorPlatformStat {   // :3390
  platform: string; handle: string; followers: number;
  engagementRate: number; isVerified: boolean; profileUrl: string | null;
}
export interface CreatorProfilePatchPayload { … all optional … }   // :3429

export const creatorProfile = {          // :3471
  getMe:  () => Promise<CreatorProfileSelfResponse>,   // GET   /me/creator-profile
  patchMe:(payload: CreatorProfilePatchPayload) => Promise<CreatorProfileSelfResponse>, // PATCH
};
```
**Media kit inputs** = `creatorProfile.getMe()` + `publicCreators.getVerifiedMetrics(username)`. There is **no** existing media-kit endpoint or type.

### 1.14 `Campaign` as brands see it — `src/lib/types.ts:244`

```ts
export interface Campaign {
  id, workspaceId, title: string;
  description?: string; objectives?: string[];
  campaignType?: CampaignType;  hype?: HypeConfig;
  status: CampaignStatus;
  budget: BudgetRange;              // { min, max, currency }
  timeline: CampaignTimeline;       // { startDate: Date; endDate: Date; milestones? }
  targetAudience?: TargetAudience;
  platforms: Platform[]; contentTypes: ContentType[];
  requirements?: string[]; hashtags?: string[]; brandGuidelines?: string;
  isPrivate: boolean; maxCollaborators?: number;
  applicationDeadline?: Date;
  endBrandName?: string | null;
  endBrandCategory?: string | null;
  createdBy: string; createdAt: Date; updatedAt: Date;
}
```

**Creators never see `Campaign`.** Creator-side is a separate flat/ISO-string family — `api.ts:5077–5115`:
```ts
export interface CreatorCampaignBrandSummary {
  workspaceId: string; name: string; logoUrl?: string | null;
  verificationStatus?: VerificationStatus | null;
}
export interface CreatorCampaignBudget { min: number; max: number; currency: string }

export interface CreatorCampaignListItem {        // :5089
  id: string; title: string; description: string;
  brand?: CreatorCampaignBrandSummary;
  budget?: CreatorCampaignBudget;
  platforms: string[];
  requirements: string[];
  applicationDeadline: string | null;
  startDate: string | null;
  endDate: string | null;
  maxCollaborators: number | null;
  applicationStatus: string | null;
  createdAt: string;
}

export interface CreatorCampaignDetail extends Omit<CreatorCampaignListItem, 'requirements'> {  // :5104
  objectives: string[];
  contentTypes: string[];
  requirements: string[];
  hashtags: string[];
  brandGuidelines: string | null;
}

export interface CreatorCampaignApplyResponse {   // :5111
  collaborationId: string; status: string; appliedAt: string;
}
export interface CreatorCampaignBrowseParams {    // :5112
  niche?: string; platform?: string; budgetMin?: number; budgetMax?: number;
  page?: number; limit?: number;
}
```

`api.creatorCampaigns` (`:5386`):
```ts
browse: async (params: CreatorCampaignBrowseParams = {})
   => Promise<{ campaigns: CreatorCampaignListItem[]; meta: { hasMore: boolean } }>  // GET /creator/campaigns
get:    (id: string) => Promise<CreatorCampaignDetail>        // mock may return null  // GET /creator/campaigns/:id
apply:  (id: string, body?: { message?: string })
   => Promise<CreatorCampaignApplyResponse>                                          // POST /creator/campaigns/:id/apply
```
Note `get`'s mock branch is typed `CreatorCampaignDetail | null` while the live branch is `CreatorCampaignDetail` — the inferred return is the union, so callers must null-check.

### 1.15 Applications — `api.ts:5171–5288`

```ts
export interface CreatorApplicationRow {          // :5171
  campaignId: string; campaignTitle: string; brandName: string;
  brandLogoUrl?: string; appliedAt: string;
  status: string; statusLabel: string;
  agreedRate?: number; currency?: string;
  dealId: string;                                 // = collaboration id, always present
}
export interface CreatorApplicationsPage {        // :5184
  applications: CreatorApplicationRow[];
  meta: { page: number; limit: number; total: number; hasMore: boolean };
}
export type CreatorApplicationHistoryActorType = 'CREATOR' | 'BRAND' | 'SYSTEM';   // :5189
export interface CreatorApplicationHistoryEvent { // :5201
  historyId, campaignId, applicationId: string;
  dealRoomId?: string | null;
  eventType: string; eventStatus: string;          // deliberately `string`, not a union
  actorType: CreatorApplicationHistoryActorType;
  actorId: string; description: string; createdAt: string;
  metadata?: string;                               // NOT an object; no sibling `reason`
  targetRoute?: string; targetId?: string;
  dealPhase?: 'negotiate' | 'contract' | 'escrow' | 'deliver' | 'pay' | null;
}

export const creatorApplications = {              // :5240
  list:    async (page = 1, limit = 50): Promise<CreatorApplicationsPage>   // GET /creator/applications
  history: (dealId: string) => Promise<CreatorApplicationHistoryEvent[]>    // GET /creator/applications/:dealId/history
};
```

### 1.16 `export const api` — `api.ts:6313`

Namespaces, in order: `auth, workspaces, workspaceMembers, onboarding, campaigns, campaignTemplates, creators, externalCreators, deals, messages, shipments, contracts, deliverables, config, wallet, creatorProfile, users, me, payments, dashboard, notifications, billing, reports, uploads, portfolio, analytics, creatorAnalytics, contentPerformance, campaignTracking, storeIntegrations, conversionWebhookSecret, creatorReviews, brandReviews, metaOAuth, creatorCoupons, affiliateEarnings, creatorCampaigns, creatorApplications, creatorDeliverables, creatorDisputes, brandDisputes, trendspark, creatorCopilot, creatorAgentPrefs, publicCreators, clientErrors`. Plus `export default api;` at `:6362`.

To add a Phase B namespace you must (a) declare `export const xyz = {…}` above line 6313 and (b) add `xyz,` to this object literal.

`api.creatorCopilot` (`:5985`) is the daily-suggestion namespace — `getTodaySuggestion()`, `dismissSuggestion(id)`, `markSuggestionActed(id)`; types `DailySuggestion` (`:5954`), `CreatorCopilotWireStatus` (`:5964`), `CreatorSuggestionTodayResponse` (`:5966`).

---

## 2. `src/lib/meera-api.ts` (835 lines) + `src/hooks/useMeeraStream.ts` (378 lines)

### 2.1 `MeeraRole`, token, basePath — `meera-api.ts:399–415`

```ts
type MeeraRole = 'brand' | 'creator';                                       // :399  — NOT exported

function getToken(role: MeeraRole = 'brand'): string | null {               // :401
  return localStorage.getItem(role === 'creator' ? 'creator_token' : 'brand_token');
}

function basePath(role: MeeraRole): string {                                // :413
  return role === 'creator' ? '/creator/meera' : '/meera';
}
```
`MeeraRole` is module-private — if Phase B needs it in a component signature, either re-export it or inline `'brand' | 'creator'`. Note `getToken` reads **only `localStorage`**, unlike api.ts's `HttpClient.getToken` which also checks `sessionStorage` (a "remember me = false" creator session will have no Meera token — pre-existing behaviour, worth knowing).

### 2.2 Local `request` helper — `meera-api.ts:416`

```ts
async function request<T>(
  method: 'GET' | 'POST',
  path: string,
  opts: {
    body?: unknown;
    idempotencyKey?: string;
    query?: Record<string, string | number | undefined>;
    role?: MeeraRole;
  } = {}
): Promise<T>
```
`GET`/`POST` only. Throws `ApiError` from `@/lib/api`, carrying `extractInsufficientFundsDetails(envelope.error)`.

### 2.3 `meeraApi` methods — `meera-api.ts:495`

```ts
startSession: async (role: MeeraRole = 'brand'): Promise<MeeraSessionResponse>            // :500
sendTurn: async (conversationId: string, content: string, role: MeeraRole = 'brand')
            : Promise<MeeraTurnResponse>                                                  // :517
getCredits: async (): Promise<MeeraCreditStatus>                                          // brand-only path
getHistory: async (conversationId: string, role: MeeraRole = 'brand')
            : Promise<Array<{ id: string; role: 'USER' | 'ASSISTANT'; content: string }>> // :647
getMessagesAfter: async (conversationId: string, afterMessageId: string, role: MeeraRole = 'brand')
            : Promise<Array<{ id: string; role: 'USER' | 'ASSISTANT'; content: string }>> // :663
speak: async (text: string, lang?: string, role: MeeraRole = 'brand'): Promise<Blob | null>       // :707
transcribe: async (audio: Blob, role: MeeraRole = 'brand'): Promise<MeeraTranscribeResult | null> // :755
logOptionTapped: (sessionId: string, toolName: string, recommended?: boolean): void               // :812
```
`speak`/`transcribe` **never throw** — `null` means "fall back to browser TTS/STT". `sendTurn` mints a fresh `Idempotency-Key` via `safeRandomUUID()` (`:484`) per call.

### 2.4 Response types — `meera-api.ts:42–182`

```ts
export interface MeeraSessionResponse {           // :42
  conversationId: string;
  status: 'ACTIVE' | 'ANALYZING';
  brandProfileStatus: 'PENDING' | 'ANALYZING' | 'READY' | 'ERROR';
  credits: { remaining: number; unlimited: boolean };
}

export interface MeeraTurnResponse {              // :53
  messageId: string;
  assistantMessageId?: string;
  streamToken: string;
  streamUrl: string;
  creditsRemaining: number;
  reply?: string;          // synchronous A4 flow — when present, DO NOT open the stream
  workspaceId?: string;    // stream-first backend only
  onBehalfToken?: string;  // stream-first backend only
}

export interface MeeraTranscribeResult {          // :139
  rawTranscript: string; cleanedText: string; langDetected?: string;
}
```

SSE event payload types (`:156–184`) — these are the ones `useMeeraStream` parses:
```ts
export interface MeeraTokenEvent      { text: string }
export interface MeeraThinkingEvent   { step: string; done: boolean }
export interface MeeraToolStartEvent  { name: string; input: Record<string, unknown> }
export interface MeeraToolResultEvent { name: string; status: 'ok' | 'error'; data?: unknown }
export interface MeeraDoneEvent       { finish_reason: 'stop' | 'tool_use' | 'max_tokens' }
export interface MeeraErrorEvent      { code: string; fallback: 'text'; message?: string }
```

Tool payload types + guards (`:186–397`): `ShowCreatorsPayload`, `CalculateBudgetPayload`, `CreateCampaignPayload`, `RequestPaymentPayload`, `ConfirmLaunchPayload`, `CampaignPerformancePayload`, `OptionsPayload`; guards `isShowCreatorsPayload` (:320), `isCalculateBudgetPayload` (:326), `isRequestPaymentPayload` (:336), `isConfirmLaunchPayload` (:342), `isCampaignPerformancePayload` (:354), `isOptionsPayload` (:381). **All six are brand tools.** There is no creator tool payload type today — Phase B must add them plus guards in this file.

### 2.5 `useMeeraStream` — `src/hooks/useMeeraStream.ts`

```ts
export type StreamStatus = 'idle' | 'connecting' | 'streaming' | 'done' | 'error';   // :45

export interface MeeraStreamHandlers {                                               // :47
  onToken?: (event: MeeraTokenEvent) => void;
  onThinking?: (event: MeeraThinkingEvent) => void;
  onToolStart?: (event: MeeraToolStartEvent) => void;
  onToolResult?: (event: MeeraToolResultEvent) => void;
  onPromptMeta?: (event: { prompt_version: string }) => void;
  onDone?: (event: MeeraDoneEvent) => void;
  onError?: (event: MeeraErrorEvent) => void;
  onHeartbeatTimeout?: () => void;
}

export interface UseMeeraStreamResult {                                              // :108
  status: StreamStatus;
  open: (
    streamUrl: string,
    streamToken: string,
    handlers: MeeraStreamHandlers,
    body?: Record<string, unknown>,
  ) => void;
  close: () => void;
  lastError: MeeraErrorEvent | null;
}

export function useMeeraStream(): UseMeeraStreamResult                                // :129
export default useMeeraStream;
```

Answering your question precisely: the callback is **`onToken`**, not `onText`; there is **`onToolStart`** and **`onToolResult`**, no `onToolUse`.

**Events the `switch` in `dispatchFrame` handles (`:173–222`):** `token`, `thinking`, `tool_start`, `tool_result`, `prompt_meta`, `done` (returns `true` = stop), `error` (returns `true` = stop), `default` (dev-only `console.warn`, ignored). There is **no `tool_use` event** on the wire — the pair is `tool_start` / `tool_result`.

**Error codes synthesized by the hook itself** (`fail()`, `:250`): `'CONNECTION_ERROR'` (fetch throw, `:274`; non-OK with unparsable body, `:284`; no `response.body`, `:300`; read error, `:341`) and `'STREAM_INCOMPLETE'` (`:352`, server closed without `done`/`error`). A non-OK JSON body's `error.code`/`error.message` are used verbatim when present (`:287–291`). Heartbeat threshold: `HEARTBEAT_TIMEOUT_MS = 30000` (`:60`); on timeout it fires `onHeartbeatTimeout` and **does not auto-close** — the caller decides.

### 2.6 `friendlyStreamErrorText` — lives in `MeeraCopilotChat.tsx:56`, not in the hook

```ts
function friendlyStreamErrorText(code: string, message: string | undefined): string {   // MeeraCopilotChat.tsx:56
  switch (code) {
    case 'CONNECTION_ERROR':
      return "Lost the connection to Meera — try again?";
    case 'STREAM_INCOMPLETE':
      return "Meera's reply got cut off — try again?";
    default:
      return message ?? "Didn't catch that — try again?";
  }
}
```
Module-private (not exported). Only 3 references in the repo, all in that file.

Error-code constants in the same file:
```ts
const CONSENT_ERROR_CODE = 'CONSENT_REQUIRED';                    // :34
const CAP_REACHED_ERROR_CODE = 'CREATOR_MONTHLY_CAP_REACHED';     // :38
export function isConsentRequiredError(err: unknown): boolean {   // :45 — EXPORTED
  return err instanceof ApiError && err.code === CONSENT_ERROR_CODE;
}
```

### 2.7 How `tool_result` renders on the brand side today

⚠️ **Correction to a premise in your brief:** `src/pages/brand-chat.tsx` has **no Meera ToolsPanel**. Its `ToolsPanel` (`brand-chat.tsx:120`) is a *deal-room* drawer type:
```ts
type ToolsPanel = 'contract' | 'deliverables' | 'payments' | null;         // brand-chat.tsx:120
const phaseToPanel: Record<DealPhase, ToolsPanel> = { … }                  // :134
```
Meera tool rendering lives in the Living-Canvas surface instead:

- **`src/components/feature/meera/MeeraChatPanel.tsx`** — brand Meera chat.
  - `interface RenderedMessage { id; role: 'meera'|'brand'; text: string; toolResults?: LiveToolResult[] }` (`:75`)
  - `const MEERA_FUNCTION_CALLS: readonly MeeraFunctionCall[] = ['calculate_budget','show_creators','create_campaign','request_payment','confirm_launch','get_campaign_performance']` (`:126`), guarded by `isMeeraFunctionCall(name)` (`:135`).
  - `onToolResult` (`:538`) allow-lists those six **plus `'present_options'`**, builds `{ id: makeId('tool'), name, status, data, errorMessage }`, lazily creates the assistant bubble if the result beat the first token, and appends into `message.toolResults`. Only `status === 'ok' && isMeeraFunctionCall(name)` advances the canvas via `onFunctionCall`.
  - `toolErrorMessage(data: unknown): string | undefined` (`:66`) pulls `.message` then `.error` out of an error payload.
  - Render (`:756–775`): `messages.map(...)` → `<MessageBubble/>` then `message.toolResults?.map(tr => <ToolResultRenderer key={tr.id} toolName={tr.name} status={tr.status} data={tr.data} errorMessage={tr.errorMessage} onOptionPick={…} className="ml-9 mt-1.5" />)`. Only in `live` mode.
  - `handleSend(opt.label)` + `meeraApi.logOptionTapped(conversationId, tr.name, opt.recommended)` on an option tap.
  - Transcript is cached to `localStorage` under `meera:transcript:<conversationId>`, capped at 60 turns (`:150–177`).

- **`src/components/feature/meera/ToolResultRenderer.tsx`** — the card library. **This is the file Phase B should extend for creator action cards.**
  ```ts
  export type ToolResultStatus = 'loading' | 'ok' | 'error';           // :331

  interface ToolResultWrapperProps {                                   // :333
    toolName: string; status: ToolResultStatus;
    children?: React.ReactNode; errorMessage?: string; className?: string;
  }
  export function ToolResultWrapper({...}: ToolResultWrapperProps)      // :341

  interface ToolResultRendererProps {                                  // :397
    toolName: string;
    status: ToolResultStatus;
    data?: unknown;
    errorMessage?: string;
    onOptionPick?: (option: { key: string; label: string; recommended?: boolean }) => void;
    className?: string;
  }
  export function ToolResultRenderer({...}: ToolResultRendererProps)    // :407
  export default ToolResultRenderer;                                    // :448
  ```
  Exported card components: `ShowCreatorsResult` (:45), `CalculateBudgetResult` (:99), `CreateCampaignResult` (:142), `RequestPaymentResult` (:201), `ConfirmLaunchResult` (:249), `OptionsCards` (:289). Dispatch is a flat chain of `{toolName === 'x' && <XResult data={…}/>}` inside `ToolResultWrapper` (`:417–440`).
  `OptionsCards` (`:289`) is the closest existing precedent for an **approve/edit/send action card**: a `onPick` callback per button, `recommended` styling, `why`/`budget_hint` sublines.
  Styling uses a dedicated `meera-*` token family: `border-meera-border`, `bg-meera-surface-2`, `text-meera-text`, `text-meera-text-muted`, `text-meera-accent`, `bg-meera-accent-soft`, `border-meera-border-strong`, `text-meera-danger`, `border-meera-danger/30`. These are **not** the creator-page tokens — `MeeraCopilotChat` uses plain `border-border`/`bg-card`/`bg-muted`/`bg-primary`.

  `formatToolName(name)` (`:386`) turns `snake_case` → `Title Case` for the loading/error copy.

---

## 3. Creator Meera surfaces

### 3.1 `src/components/creator/MeeraCopilotChat.tsx` (421 lines)

```ts
interface ChatMessage {                       // :28  — module-private
  id: string;
  role: 'meera' | 'creator';
  text: string;
}

export interface MeeraCopilotChatProps {      // :76
  firstName: string;
  /** BCP-47 — from CreatorAgentPreferences.creator_language (A5). */
  language: string;
  onClose: () => void;
  onConsentRequired: () => void;
}

export function MeeraCopilotChat({ firstName, language, onClose, onConsentRequired }: MeeraCopilotChatProps)  // :86
export default MeeraCopilotChat;              // :421
```

**State (`:87–96`):** `live` (`useState(() => isApiLive())`), `messages: ChatMessage[]`, `conversationId: string | null`, `connecting: boolean` (init `true`), `connectError: string | null`, `sending: boolean`, `draft: string`, `scrollRef: RefObject<HTMLDivElement>`, `reduceMotion = useReducedMotion()` (framer-motion), `stream = useMeeraStream()`.

**Voice (`:98–111`):**
```ts
const { supported: voiceOutputSupported, enabled: voiceEnabled, setEnabled: setVoiceEnabled,
        speak, stop: stopSpeaking } = useVoiceOutput('creator');
const { supported: voiceInputSupported, isListening, start: startListening, stop: stopListening } =
  useVoiceInput({ onResult: handleVoiceResult, lang: language, role: 'creator' });
```
Signatures: `useVoiceOutput(role: 'brand' | 'creator' = 'brand'): UseVoiceOutputResult` (`src/hooks/useVoiceOutput.ts:120`, result iface `:80`); `useVoiceInput({ …, role = 'brand' }: UseVoiceInputOptions): UseVoiceInputResult` (`src/hooks/useVoiceInput.ts:116`, options `:58`, result `:87`).

**Message rendering (`:357–368`)** — the insertion point for an action card:
```tsx
{messages.map((m) => (
  <div key={m.id} className={cn('flex', m.role === 'creator' ? 'justify-end' : 'justify-start')}>
    <div className={cn(
      'max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm',
      m.role === 'creator' ? 'bg-primary text-primary-foreground' : 'bg-muted text-foreground',
    )}>
      {m.text}
    </div>
  </div>
))}
```
**To add a tool-result / approve-edit-send card:** widen `ChatMessage` with an optional `toolResults?: …[]` field (exactly the `RenderedMessage` shape at `MeeraChatPanel.tsx:75`), attach in a new `onToolResult` handler inside the `stream.open(...)` handlers object at `:231–288` (which currently supplies only `onToken`, `onDone`, `onError`, `onHeartbeatTimeout` — **no `onToolStart`/`onToolResult` today**), and render the card after the bubble `</div>` inside the map above. Because the map's element is already a wrapping `<div key={m.id}>`, this is a purely additive change.

**Send path (`:193–314`):**
1. `!live` → echoes `'This is mock mode — connect a live backend to chat with Meera.'` after 500 ms.
2. `meeraApi.sendTurn(conversationId, text, 'creator')`.
3. If `turnRes.reply != null` → render it directly, `speak(replyText, language)`, `setSending(false)`, **return without opening the stream** (double-generation guard). This is the current live path.
4. Else `stream.open(turnRes.streamUrl, turnRes.streamToken, handlers, body)` where body is `{ workspace_id: turnRes.workspaceId ?? '', conversation_id: conversationId, turn_id: turnRes.messageId, onbehalf_jwt: turnRes.onBehalfToken ?? '', conversation: [...messages.map(m => ({ role: m.role === 'creator' ? 'user' : 'assistant', content: m.text })), { role: 'user', content: text }] }` (`:289–298`).

**Error copy** — empty turn: `"Sorry, I lost my train of thought there. Say that again?"` (`:221`, `:246`); heartbeat: `'Meera stopped responding — try again?'` (`:281`); catch-all: `'Something went wrong sending that — try again?'` (`:311`); connect: `"Couldn't reach Meera."` (`:151`) rendered as `<p className="text-sm text-destructive-foreground">` + a "Try again" outline button (`:349–356`).

**Onboarding greeting (`:70–74`)** — hi/en split via `language.startsWith('hi')`, Hindi string inline.

**Layout shell (`:317`):** `flex h-[32rem] max-h-[75vh] flex-col rounded-xl border border-border bg-card shadow-sm`, header / scroll body (`flex-1 space-y-3 overflow-y-auto px-4 py-4`) / composer (`shrink-0 border-t border-border p-3` with `Textarea` + optional mic `Button` + send `Button`). Enter (no shift) sends.

Mount-effect lint escape at `:171`: `// eslint-disable-next-line react-hooks/exhaustive-deps` with `[live]` deps.

### 3.2 `src/components/meera/ConsentScreen.tsx` (79 lines) — the only file in `src/components/meera/`

```ts
const CONSENT_TEXT: Record<string, { title: string; body: string; accept: string; decline: string }> = {  // :21
  'hi-IN': { title: 'Meera से बात करें', body: '…', accept: 'स्वीकार करें', decline: 'अभी नहीं' },
  'en-IN': { title: 'Talk to Meera',    body: '…', accept: 'Accept',       decline: 'Not now' },
};

export interface ConsentScreenProps {   // :36
  open: boolean;
  /** BCP-47 code — falls back to en-IN for anything not hi-IN. */
  language?: string;
  onAccept: () => void | Promise<void>;
  onDecline: () => void;
}

export function ConsentScreen({ open, language, onAccept, onDecline }: ConsentScreenProps)  // :44
export default ConsentScreen;
```

**i18n pattern (the whole of it):** a module-level `Record<string, {…}>` keyed by BCP-47 with exactly two keys, resolved as `CONSENT_TEXT[language ?? 'en-IN'] ?? CONSENT_TEXT['en-IN']` (`:46`). There is **no i18n library, no `t()`, no locale files** anywhere in `src/`. The other hi/en site is `onboardingGreeting` in `MeeraCopilotChat.tsx:70` which uses `language.startsWith('hi')` instead. Phase B should follow the `ConsentScreen` `Record` shape for anything with more than two strings.

Built on shadcn `Dialog` + `DialogHeader/Title/Description/Footer`; body uses `className="whitespace-pre-line text-left"` for the `\n\n` in the copy. Local `accepting` state wraps `await onAccept()` in try/finally.

### 3.3 `src/components/creator/MeeraSettingsSection.tsx` (717 lines)

```ts
type Draft = CreatorAgentPreferencesUpdate;                        // :125
function toDraft(prefs: CreatorAgentPreferences): Draft {          // :127
  const { consent_accepted: _consent_accepted, consent_version: _consent_version, ...rest } = prefs;
  return rest;
}
export function MeeraSettingsSection()                             // :143  — NO props
export default MeeraSettingsSection;                               // :716
```

**Module constants:** `EXCLUDABLE_CATEGORIES` (`:47`, 7 strings), `WORKING_DAY_OPTIONS: { value: number; label: string }[]` (`:57`, Mon=1…Sun=7), `CURATED_TIMEZONES` (`:73`, 9), `CURATED_CURRENCIES` (`:87`, 7). Helpers: `withPersistedOption(options, persisted)` (`:100`), `currencySymbolFor(code)` (`:112`, via `Intl.NumberFormat.formatToParts`), `formatDateTime(iso)` (`:135`, `toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })`).

**State (`:144–159`):** `loading`(true), `loadError`, `draft: Draft | null`, `blockedBrandsText: string`, `saving`, `saveError`, `featureDisabled`, `conversations`, `conversationsLoading`, `exportingId`, `deleteTarget`, `deletingId`.

**How the PUT is built — `handleSave` (`:235`), verbatim shape:**
```ts
if (draft.represented && !draft.agency_name?.trim()) {
  setSaveError('Agency name is required when you are represented.');   // inline, no toast
  return;
}
const payload: Draft = {
  ...draft,
  blocked_brands: blockedBrandsText.split('\n').map(b => b.trim()).filter(Boolean),
  agency_name: draft.represented ? draft.agency_name?.trim() || null : null,
};
const saved = await api.creatorAgentPrefs.updatePreferences(payload);
setDraft(toDraft(saved));
setBlockedBrandsText(saved.blocked_brands.join('\n'));
toast({ title: 'Meera settings saved' });
// catch: setSaveError(err instanceof ApiError ? err.message : 'Could not save your changes.')
```
Note this file imports `{ toast }` **directly** (`import { toast } from '@/hooks/use-toast';` `:38`), not `useToast()`.

`update(patch: Partial<Draft>)` (`:213`), `toggleCategory` (`:217`), `toggleWorkingDay` (`:227`, re-sorts). `handleExport` (`:263`) builds a `Blob` + object-URL anchor named `meera-conversation-${conversationId}.json`. `handleDelete` (`:287`) behind an `AlertDialog`. `if (featureDisabled) return null;` (`:309`).

Approval-level radio copy (`:435–452`), directly relevant to Phase B level-1 auto replies:
- `0` — "Draft only — I approve every message"
- `1` — "Routine replies — Meera can send simple messages (media kit, availability)"
- `2` — "Auto-decline — Meera can decline excluded categories or blocked brands"

and the disclaimer at `:454` that Phase B must delete/replace:
> "Phase A is conversational only — Meera does not send or decline anything on your behalf yet, regardless of this setting."

Same for the Representation note at `:603`: "Represented mode: Meera warns you only — she never drafts or sends anything to brands."

Mounted at `src/pages/creator-settings.tsx:462` (import at `:51`), as one `<Card className="mb-6">` among the other settings cards.

### 3.4 `src/pages/creator-copilot.tsx` (197 lines)

```ts
function firstNameOf(displayName?: string): string   // :16
export default function CreatorCopilotPage()         // :39
```
Imports: `CreatorLayout`, `CopilotPreviewCard`, `DailySuggestionSection`, `useDailySuggestion`, shadcn `Card*`, `Button`, `ConsentScreen`, `MeeraCopilotChat`, `{ api, ApiError }`, `getCreatorSession` from `@/lib/auth-session`.

**State (`:44–63`):** `const { status } = useDailySuggestion(); const showPreview = status === 'idle';`, `firstName` (memo over `getCreatorSession()?.displayName`), `checkingConsent`, `showConsent`, `chatOpen`, `language` (init `'hi-IN'`), `consentLoadError`, `featureDisabled: boolean | null` (init `null` — tri-state so the entry never flashes).

**Layout (`:117–196`), top to bottom:**
```
<CreatorLayout>
  <div className="container mx-auto px-4 py-6 max-w-3xl">
    <div className="mb-6"> h1 "Co-pilot" / p "Your AI content partner" </div>
    {featureDisabled === true
      ? <Card className="mb-6">  … "Meera for creators isn't available on your account yet." …
      : <Card className="mb-6">  Sparkles + "Talk to Meera" + CardDescription
          {chatOpen ? <MeeraCopilotChat …/> : <Button onClick={openMeera}>Open Meera</Button>
                                              + {consentLoadError && <p className="mt-2 text-sm text-destructive-foreground">}}
        </Card>}
    <ConsentScreen open={showConsent} language={language} onAccept={handleAcceptConsent} onDecline={…} />
    {showPreview && <CopilotPreviewCard className="mb-3" />}
    <DailySuggestionSection />
  </div>
</CreatorLayout>
```
**Where a "paste a brief" entry goes:** the natural slot is a new `<Card className="mb-6">` between the Meera card (`:182`) and `<ConsentScreen>` (`:184`) — inside the `max-w-3xl` container, above the daily-suggestion stack, and gated on the same `featureDisabled !== true`. It would reuse `openMeera`'s consent probe (`:87–109`) so the DPDP gate isn't bypassed.

`openMeera` (`:87`) → `api.creatorAgentPrefs.getPreferences()` → sets `language`, then `consent_accepted ? setChatOpen(true) : setShowConsent(true)`; catches `FEATURE_DISABLED` separately from generic errors. `handleAcceptConsent` (`:111`) → `await api.creatorAgentPrefs.recordConsent(); setShowConsent(false); setChatOpen(true);`.

`DailySuggestionSection` props: `interface DailySuggestionSectionProps { className?: string }` (`src/components/creator/copilot/DailySuggestionSection.tsx:13`), `export function DailySuggestionSection({ className })` (`:32`). `useDailySuggestion(): UseDailySuggestionResult` (`src/hooks/useDailySuggestion.ts:128`, iface `:51`) — react-query-backed, so a second call in the same tree is deduped, not a second request.

Other files in `src/components/creator/copilot/`: `BusinessAccountRequired.tsx`, `CopilotPreviewCard.tsx`, `DailySuggestionCard.tsx`, `IGConnectPrompt.tsx`, `SuggestionEmptyState.tsx`, plus `DailySuggestionSection.test.tsx`.

---

## 4. Deal surfaces

### 4.1 `src/pages/creator-chat.tsx` (3165 lines) — `export default function CreatorChatPage()` at `:672`

**Local types:**
```ts
type CreatorToolsPanel = 'contract' | 'deliverables' | 'payments' | null;      // :130
const CREATOR_VALID_PANELS: CreatorToolsPanel[] = [...];                        // :132
const creatorPhaseToPanel: Record<DealPhase, CreatorToolsPanel> = {...};        // :134
type DealRoom = CreatorChatDealRoom;                                            // :177
interface RevisionContext { deliverableId; title; currentRevision; brandFeedback }  // :185
interface ChatTimelineEvent {                                                   // :192
  id: string;
  type: 'message' | 'proposal' | 'counter_proposal' | 'contract' | 'deliverable' | 'payment' | 'system';
  sender: 'brand' | 'creator' | 'system';
  timestamp: string;
  content?: string;
  metadata?: Record<string, unknown>;
}
function toLibTimelineEvent(event: ChatTimelineEvent): LibTimelineEvent          // :201
function dealAllowsProposalResponse(deal: DealRoom | null | undefined): boolean  // :504
function mayIndicateDealStatusChange(message: DealMessage): boolean              // :531
const MESSAGE_KIND_TO_EVENT_TYPE: Record<MessageKind, ChatTimelineEvent['type']> // :535
function dealMessageToEvent(m: DealMessage): ChatTimelineEvent                   // :546
const PLATFORM_FEE_RATE = 0.15;                                                  // :581
const calculateEarnings = (grossAmount: number) => …                             // :582
interface ProposalActionFeedback { … }                                           // :596
function describeProposalActionError(…)                                          // :621
```

**Data fetching / polling — there is no `setInterval` poll.** Four mechanisms:
1. Deals list — `api.deals.list('creator', 'all')` (`:691`), effect at `:701`.
2. `refreshDeal(id)` — `useCallback` at `:728`, calls `api.deals.get('creator', id)` (`:737`), guarded by a per-deal monotonic token ref (`:707`) for last-write-wins. On failure: `toast({ title: 'Couldn't refresh this deal', … })` (`:760`).
3. `loadMessages(dealId)` — `useCallback` at `:837`, calls `api.messages.list('creator', dealId)` (`:847`). Mount/deal-switch effect at `:890`.
4. **SSE** — effect at `:943`: `const handle = api.messages.stream('creator', dealId, { … })` (`:947`); handlers call `refreshDeal(dealId)` when `mayIndicateDealStatusChange` (`:964`), and on `onReconnect` call **both** `loadMessages(dealId)` and `refreshDeal(dealId)` (`:983–984`). Own-messages are deliberately not appended from the stream (`:920` comment) — `handleSendMessage` appends what `api.messages.send` returned.
5. `visibilitychange` resync effect (`:1001–1011`) → `loadMessages` + `refreshDeal`.
6. `afterDealMutation(dealId)` (`:884`) = `await Promise.all([refreshDeal(dealId), loadMessages(dealId)])` — the one call every mutation handler makes.
7. `api.messages.markRead('creator', selectedDeal.id)` effect at `:903`.

**Reply box (`:2809–2827`)** — a shadcn `Textarea`, not a component:
```tsx
<Textarea
  placeholder="Type a message..."
  value={message}
  onChange={(e) => setMessage(e.target.value)}
  onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSendMessage(); } }}
  className="min-h-[44px] max-h-32 resize-none"
  rows={1}
/>
<Button onClick={handleSendMessage} disabled={!message.trim()} className="shrink-0"><Send className="h-5 w-5" /></Button>
```
Preceded by a permanently-disabled `Paperclip` attach button in a `Tooltip` (`:2795–2807`).

**Send function (`:1366`):**
```ts
const handleSendMessage = async () => {
  const text = message.trim();
  if (!text || !selectedDeal) return;
  setMessage('');
  if (liveApi) {
    try {
      const sent = await api.messages.send('creator', selectedDeal.id, text);
      setLiveMessages((prev) => [...prev, sent]);
    } catch (err) {
      console.error('Failed to send message', err);
      setMessagesError('Failed to send your message. Please try again.');
    }
  } else {
    addPersistedMessage(selectedDeal.id, text, 'creator');
    setMessageRefreshKey((k) => k + 1);
  }
};
```
**This is the hook point for the send log** — an auto-reply that Meera sends would go through `api.messages.send('creator', dealId, text)` and needs the same `setLiveMessages` append.

**Other handlers:** `handleAcceptProposal(proposalId)` (`:1382` → `api.deals.accept(selectedDeal.id, 'creator')` + `afterDealMutation`), decline (`:1430` → `api.deals.reject(selectedDeal.id, undefined, 'creator')`), `handleSendCounter()` (`:1460`, just closes the legacy dialog), and:

```ts
const handleSubmitCounterForm = async (data: CounterProposalFormData) => {   // :1466
  …
  const message = [data.message, data.terms && `Terms: ${data.terms}`].filter(Boolean).join('\n\n');
  await api.deals.counter(
    selectedDeal.id,
    { amount: data.proposedAmount, message: message || undefined, deadline: data.deadline || undefined },
    'creator',
    `${selectedDeal.id}-counter-${Date.now()}`,
  );
  await afterDealMutation(selectedDeal.id);
  …
  // catch: toast({ title: 'Could not send counter offer', description: err instanceof ApiError ? err.message : 'Please try again.', variant: 'destructive' })
};
```
⚠️ Note it does **not** send `dealTerms` today. The negotiation coach adding structured terms to a counter must extend both `CounterProposalFormData` and this call.

**`<DealTermsSummary>` render sites — two, both identical in shape:**
- `:2437` — inside the **Brand Proposal** card, gated `{event.metadata?.status === 'pending' && selectedDeal.dealTerms && …}` with wrapper `<div className="mt-3 pt-3 border-t border-stage-outreach-border space-y-2"><p className="text-xs text-muted-foreground mb-1">Deal Terms</p>…`
- `:2642` — inside the **Counter Proposal** card, same gate, wrapper `border-stage-negotiating-border`.

The proposal card's own rows above it (`:2412–2426`) are `Amount` (`formatINR(Number(event.metadata?.amount))`), `Deliverables` (`deliverableCountLabel(event.metadata, 'item') ?? 'Not specified'`), `Usage Rights` (`String(event.metadata?.usageRights ?? 'Not specified')`). Then an "Your Earnings Breakdown" block using `calculateEarnings`. **A "deal warning card" fits naturally between the Deal Terms block and the Earnings Breakdown**, on the same `event.metadata?.status === 'pending'` gate.

### 4.2 `src/components/creator/deal-room/counter-proposal-form.tsx`

```ts
export interface CounterProposalFormData {          // :14
  proposedAmount: number;
  deadline: string;
  terms: string;
  message: string;
}

interface CounterProposalFormProps {                // :21  — NOT exported
  brandName: string;
  originalAmount: number;
  deliverables: Array<{ title: string; quantity: number }>;   // note: title/quantity, NOT type/qty
  onSubmit: (data: CounterProposalFormData) => void;
  onClose: () => void;
  isSubmitting?: boolean;
}

export function CounterProposalForm({ brandName, originalAmount, deliverables, onSubmit, onClose, isSubmitting = false })  // :30
```
4-step wizard (`totalSteps = 4`, `:44`). Fetches the live platform fee via `api.wallet.platformFee()` (`:59`), defaulting `feeBps = 1500`. Deadline validated inline against `todayStr` (`:46–48`) — `deadlineInPast` drives inline field text, no toast. `onSubmit(formData)` at `:102`.

Imported in creator-chat as: `import { CounterProposalForm, type CounterProposalFormData } from '@/components/creator/deal-room/counter-proposal-form';` (`creator-chat.tsx:69`).

Sibling `counter-proposal-card.tsx` — `interface CounterProposalCardProps` (`:10`, private), `export function CounterProposalCard` (`:22`).

Other files in `src/components/creator/deal-room/`: `creator-contract-card.tsx`, `creator-contract-panel.tsx`, `creator-deal-contract-tab.tsx`, `deliverable-card.tsx`, `deliverable-lifecycle-panel.tsx`, `deliverable-submission.tsx`, `receipt-confirmation.tsx`, `revision-handler.tsx`, `shipping-address-form.tsx`.

### 4.3 `src/components/shared/deal-terms-summary.tsx`

```ts
export interface DealTermsSummaryProps {   // :57
  terms: DealTerms;                        // required, non-null — callers gate on presence
  /** Compact = inline label/value rows … Default. */
  variant?: 'compact' | 'default';
  className?: string;
}
export function DealTermsSummary({ terms, variant = 'compact', className }: DealTermsSummaryProps)   // :63
```
No default export. Module-private helpers: `USAGE_CHANNEL_LABELS: Record<UsageChannel, string>` (`:21`), `formatUsageWindow` (`:29`), `formatUsageChannels` (`:34`), `formatExclusivity` (`:39`). Always renders 4 rows: Usage window / Usage channels / Exclusivity / Max revisions, each falling back to `'Not specified'` (never zeros). `variant: 'default'` adds a `<p className="text-sm font-medium mb-2">Deal terms</p>` heading and a `rounded-lg border bg-muted/30 p-3` box.

### 4.4 `src/lib/creator-deal-mappers.ts`

Exports:
```ts
export type { DealStage } from '@/lib/deal-stage';                       // :86
export { mapCollaborationStatusToDealStage } from '@/lib/deal-stage';    // :87
export function parseDealAmount(value: unknown): number                  // :92
export type CreatorDealsPageStatus = DealStage;                          // :99
export type CreatorChatDealStatus = DealStage;                           // :102
export interface CreatorDealsPageRow { … dealTerms?: DealTerms }         // :104
export interface CreatorChatDealRoom { … dealTerms?: DealTerms }         // :133
export interface CreatorChatTimelineEvent { … }                          // :182
export function mapDealToDealsPageRow(deal: Deal): CreatorDealsPageRow   // :198
export function mapDealToChatRoom(deal: Deal): CreatorChatDealRoom       // :235
export function mapDealMessageToTimelineEvent(msg: DealMessage): CreatorChatTimelineEvent  // :276
```

`CreatorDealsPageRow` (`:104`) fields: `id, brandId, brandName, brandLogo?, brandVerified, brandRating?, brandPaymentSpeed?, campaignTitle, status, budget, deliverables: Array<{type;count}>, deadline, lastMessage?, lastMessageAt?: Date, unreadCount, deliverablesDone, deliverablesTotal, receivedAt?: Date, expiresAt?: Date, escrowFunded, dealTerms?`.

`CreatorChatDealRoom` (`:133`) fields: `id, brandName, brandLogo, brandInitials, brandVerified, campaignName, status, dealAmount, lastMessage, lastMessageTime, unreadCount, deliverablesDone, deliverablesTotal, contractId?, contractStatus?, escrowFunded, collaborationStatus?: CollaborationStatus, dealTerms?`.

Both mappers already forward `dealTerms: deal.dealTerms` (`:225`, `:270`) — the deal-warning card gets it for free from either shape.

Related: `src/lib/deal-stage.ts` — `DealStage` union (7 values) + `mapCollaborationStatusToDealStage` + `allowsProposalResponse` (imported at `creator-chat.tsx:94`).

### 4.5 `src/pages/creator-deals.tsx`

```ts
type StatusChip = { … };                                       // :51
type DealRoom = CreatorDealsPageRow;                           // :77
const STATUS_CHIPS: StatusChip[] = [ … ];                      // :79  (each has an `apiFilter`)
export const mockDeals: DealRoom[] = [ … ];                    // :96  — exported for tests
export default function CreatorDealsPage()                     // :206
interface DealRowProps { deal; actionLoading; onOpen; onAccept; onCounter; onReject }  // :553
function DealRow({ … }: DealRowProps)                          // :562
function StatusPill({ status }: { status: DealRoom['status'] })// :717
function EmptyState({ filter }: { filter: DealStatusFilter })  // :741
```
It is a **single list page with expandable rows**, not list+detail — "detail" is the deal room at `/creator/chat`. Calls: `api.deals.list('creator', chip?.apiFilter ?? activeFilter)` (`:250`) and a second `'all'` fetch for counts (`:293`); `api.deals.accept(invite.id)` (`:357`), `api.deals.accept(deal.id)` (`:373`), `api.deals.reject(deal.id)` (`:404`) — note these three **omit the `role` arg**, relying on `accept`/`reject`'s `role: Role = 'creator'` default. `<DealTermsSummary …>` renders inside `DealRow` at `:652`. Header doc block at `:44–47` lists the four endpoints the page owns.

---

## 5. Campaign surfaces for creators

### 5.1 `src/pages/creator-campaigns.tsx`
State (`:50–65`): `searchQuery`, `selectedNiche`, `selectedPlatform: Platform | null`, `budgetRange: [number, number]`, `draftBudgetRange`, `isFilterOpen`, `campaigns: CreatorCampaignListItem[]`, `page`, `hasMore`, `loading`, `loadingMore`, `error`. Fetch at `:82` — `api.creatorCampaigns.browse({ … })`; page-1 failure sets `error` (inline), load-more failure keeps the page and fires `toast({ title: 'Couldn't load more campaigns', description: message, variant: 'destructive' })` (`:105`). `applyFilters()` at `:148`. Cards are `CreatorBrowseCampaignCard`. Uses `const { toast } = useToast()` (`:49`).

### 5.2 `src/pages/creator-campaign-detail.tsx`
State (`:62–69`): `campaign: CreatorCampaignDetail | null`, `loading`, `error`, `applyOpen`, `applyMessage`, `applying`, `applySuccess`. Load: `api.creatorCampaigns.get(id)` (`:91`). Deep-link auto-open: `searchParams.get('apply') === '1' && campaign && !campaign.applicationStatus` (`:115`).

**Apply flow (`:124`) — exact request shape:**
```ts
await api.creatorCampaigns.apply(id, { message: applyMessage.trim() || undefined });
setCampaign((prev) => (prev ? { ...prev, applicationStatus: 'APPLIED' } : prev));   // :128
toast({ … });                                                                       // :129
// catch: toast({ title: 'Application failed', description: message, variant: 'destructive' })  // :136
```
`const hasApplied = Boolean(campaign.applicationStatus);` (`:187`). Dialog at `:389`, textarea `id="apply-message"` with a `{applyMessage.length}/2000 characters` counter (`:437`). Badge via `getApplicationStatusLabel(campaign.applicationStatus!)` (`:366`).

**This is the pattern an "on-platform pitching" flow must extend** — apply's only payload field today is `message?: string`.

### 5.3 `src/pages/creator-applications.tsx`
`api.creatorApplications.list(pageNum, PAGE_SIZE)` (`:47`); load-more failure → toast (`:58`). Filter tabs from `APPLICATION_BUCKETS` + `bucketOf(a.status)` (`:78`, `:86`), `role="tablist"` (`:132`).

### 5.4 `src/lib/application-status.ts` — status vocabulary (the source of truth)

```ts
export type ApplicationBucket = 'applied' | 'shortlisted' | 'in_negotiation' | 'active' | 'completed' | 'closed';
export interface ApplicationBucketDef { id: ApplicationBucket; label: string }
export const APPLICATION_BUCKETS: ApplicationBucketDef[]      // 6 entries, display order
export type { DeclineWordingVariant }; export { DECLINE_WORDING_VARIANT };
export interface DeclineWording { statusLabel: string; eventLabel: string; icon: 'neutral' | 'explicit' }
export const DECLINE_WORDING: Record<DeclineWordingVariant, DeclineWording>
export function getDeclineWording(): DeclineWording
export function getApplicationStatusLabel(status: string): string
export function bucketOf(status: string): ApplicationBucket
export interface ApplicationStatusBadgeProps {
  variant?: 'default' | 'secondary' | 'destructive' | 'outline';
  className?: string;
  label: string;
}
export function getApplicationStatusBadgeProps(status: string): ApplicationStatusBadgeProps
```

`STATUS_LABELS` (private): `APPLIED`→"Applied", `SHORTLISTED`→"Shortlisted", `IN_NEGOTIATION`→"In negotiation", `TERMS_AGREED`→"Accepted", `CONTRACT_PENDING`→"Contract pending", `CONTRACTED`/`IN_PROGRESS`/`REVIEW_PENDING`/`REVISION_REQUESTED`→"Active", `COMPLETED`→"Completed", `DISPUTED`→"In dispute", `INVITED`→"Invited". `CANCELLED` is deliberately absent — resolved through `getDeclineWording()`.

`STATUS_BUCKETS` (private): APPLIED→applied, SHORTLISTED→shortlisted, IN_NEGOTIATION→in_negotiation, TERMS_AGREED/CONTRACT_PENDING/CONTRACTED/IN_PROGRESS/REVIEW_PENDING/REVISION_REQUESTED/DISPUTED→active, COMPLETED→completed, CANCELLED→closed; unknown → `'closed'`.

Companion module `src/lib/decline-wording-variant.ts` holds the single switch constant.

---

## 6. Routing — `src/App.tsx` (838 lines)

**No lazy imports anywhere.** Every page is a plain top-level `import` (`:1–89`); there is no `React.lazy` / `Suspense` in this file. The creator page imports are `:59–82`.

Shell (`:189–192`, `:828–838`):
```tsx
<QueryClientProvider client={queryClient}>   // queryClient = new QueryClient()  :169
  <BrowserRouter>
    <RoutedErrorBoundary>                     // :182
      <Routes> … </Routes>
      <Toaster />                             // :832 — mounted INSIDE the Router (CR-10)
      <DemoModeBanner />                      // :833
    </RoutedErrorBoundary>
  </BrowserRouter>
</QueryClientProvider>
```

Guards:
```ts
const ProtectedRoute        = ({ children }: { children: React.ReactNode }) => …   // :92
const CreatorProtectedRoute = ({ children }: { children: React.ReactNode }) => {   // :143
  const isAuthenticated = readAuthToken('creator_token');
  const isDemoMode = import.meta.env.DEV && new URLSearchParams(window.location.search).get('demo') === 'true';
  return isAuthenticated || isDemoMode ? <>{children}</> : <Navigate to="/creator/login" />;
};
const AdminProtectedRoute   = …                                                    // :156
```
There is **no `CreatorLayout` wrapper route** — `CreatorProtectedRoute` wraps the bare page and *each page renders `<CreatorLayout>` itself* (see `creator-copilot.tsx:118`). This differs from the brand side, which has a `BrandLayoutWrapper` (`:136`).

**Creator route table (`:441–620`), exact order:**

| Path | Element | Guard |
|---|---|---|
| `/creator/login` `/creator/register` `/creator/forgot-password` | Login/Register/ForgotPassword | none |
| `/creator/settings/meta/callback` | `CreatorMetaCallbackPage` | **none** (deliberate, `:444–455`) |
| `/creator/onboarding` | `CreatorOnboardingPage` | guarded |
| `/creator/dashboard` | `CreatorDashboardPage` | guarded |
| `/creator/how-it-works` | `CreatorHowItWorksPage` | guarded |
| `/creator/deals` | `CreatorDealsPage` | guarded |
| `/creator/inbox` → `Navigate /creator/deals?status=new` | — | — |
| `/creator/active` → `Navigate /creator/deals?status=in_progress` | — | — |
| `/creator/copilot` | `CreatorCopilotPage` | guarded (`:501`) |
| `/creator/wallet` `/creator/profile` `/creator/settings` `/creator/chat` `/creator/portfolio` `/creator/analytics` | … | guarded |
| `/creator/campaigns` | `CreatorCampaignsPage` | guarded |
| `/creator/campaigns/:id` | `CreatorCampaignDetailPage` | guarded |
| `/creator/applications` | `CreatorApplicationsPage` | guarded |
| `/creator/disputes` `/creator/reviews` `/creator/notifications` `/creator/coupons` `/creator/affiliate` | … | guarded |

**Adding a creator route — the exact 3-step recipe:**
1. `import CreatorXPage from '@/pages/creator-x';` in the "Creator Pages" block (`:58–82`).
2. Insert, keeping the block ordering:
```tsx
<Route
  path="/creator/x"
  element={
    <CreatorProtectedRoute>
      <CreatorXPage />
    </CreatorProtectedRoute>
  }
/>
```
3. Have the page render `<CreatorLayout>…</CreatorLayout>` itself, and (if it should be navigable) add `{ label: 'X', href: '/creator/x', icon: SomeIcon }` to the nav group in `src/components/creator/creator-layout.tsx` — `Main` group at `:93–101`, `Manage` group at `:105–112`. Item shape is `{ label: string; href: string; icon: typeof Home }` (`:73–75`). Current `Main`: Home `/creator/dashboard`, Deals `/creator/deals`, Campaigns `/creator/campaigns`, Applications `/creator/applications`, Co-pilot `/creator/copilot` (icon `Sparkles`), Analytics, Wallet.

**Most recent route example — `/c/:username/verified` (`App.tsx:815–818`):**
```tsx
{/* T-MEERA-CREATOR-PHASE-A (A9) — influora.com/c/username/verified — no auth, no rates,
    no floors. Placed ahead of the /:handle catch-all below for clarity, though React
    Router's ranking would resolve this correctly either way (more path segments win). */}
<Route path="/c/:username/verified" element={<CreatorVerifiedMetricsPage />} />
```
Immediately followed by `<Route path="/:handle" element={<CreatorPortfolioPublicPage />} />` (`:824`) and `<Route path="*" element={<NotFoundPage />} />` (`:825`). **Any new public media-kit route must sit above `/:handle`.**

Its page, `src/pages/creator-verified-metrics.tsx`, is the model for a public media-kit page: `useParams<{ username: string }>()`, `React.useState` triad (`data`/`loading`/`notFound`), a cancelled-ref effect calling `api.publicCreators.getVerifiedMetrics(username)`, `const NOT_AVAILABLE_YET = 'Not available yet'` for every nullable field (`:31`), and `formatDate(iso)` via `toLocaleDateString('en-IN', { year:'numeric', month:'long', day:'numeric' })` (`:18`). No `CreatorLayout` (public page).

---

## 7. Conventions

### 7.1 Toast

- Hook: **`src/hooks/use-toast.ts`** (shadcn, heavily annotated). `export { useToast, toast }` at the bottom — **no default export**.
- Two call styles, both live in the codebase:
  - `import { useToast } from '@/hooks/use-toast'; const { toast } = useToast();` — pages (`creator-chat.tsx:45`, `creator-campaigns.tsx:28`, `creator-applications.tsx:16`, `creator-campaign-detail.tsx:36`).
  - `import { toast } from '@/hooks/use-toast';` — components (`MeeraSettingsSection.tsx:38`).
- Shape: `toast({ title, description?, variant?: 'destructive' })`.
- `TOAST_LIMIT = 2` (`:26`), `TOAST_REMOVE_DELAY = 4000` (`:38`). `useToast` uses `React.useSyncExternalStore(subscribe, getSnapshot, getSnapshot)` (`:302`-ish).
- Mount: `<Toaster />` from `@/components/ui/toaster` at `src/App.tsx:832`, inside `RoutedErrorBoundary` inside `BrowserRouter`.

**"Toast for API errors, inline for field validation" — the convention is real and consistently applied, but it is NOT written down in `CLAUDE.md`** (that file, 9 lines, covers only graphify). Evidence from the code:

| Kind | Treatment | Example |
|---|---|---|
| Field validation | inline state + `<p className="text-sm text-destructive-foreground">` | `MeeraSettingsSection.tsx:238` `setSaveError('Agency name is required when you are represented.')` → rendered `:608`; `counter-proposal-form.tsx:48` `deadlineInPast` |
| Load failure (first page) | inline `error` state | `creator-campaigns.tsx` page-1 branch; `MeeraSettingsSection.tsx:178` `loadError` → `:329` |
| API mutation error / load-more failure | `toast({ variant: 'destructive' })` | `creator-chat.tsx:1497`, `creator-campaigns.tsx:105`, `creator-applications.tsx:58`, `creator-campaign-detail.tsx:136`, `MeeraSettingsSection.tsx:277`/`:295` |
| Feature-disabled | **neither** — calm static card, explicitly asserted "no toast" in tests | `creator-copilot.tsx:129`, `MeeraSettingsSection.tsx:309` |
| Mutation success | `toast({ title: '…' })` | `MeeraSettingsSection.tsx:255`, `:293` |

Message-send failure in `creator-chat.tsx:1374` is the one exception — it sets `setMessagesError(...)` inline rather than toasting.

### 7.2 Design tokens

`src/app/globals.css` (there is **no `src/index.css` and no `tailwind.config.*`** — Tailwind v4 CSS-first config):
```css
/* light — :28 */   --destructive: #ffe5e5;  --destructive-foreground: #a63a3a;
/* dark  — :148 */  --destructive: #5c3535;  --destructive-foreground: #ffb8b8;
/* :208 */          --color-destructive: var(--destructive);
/* :209 */          --color-destructive-foreground: var(--destructive-foreground);
```
So `--destructive` is a **surface/background** and `--destructive-foreground` is the **readable text** colour. That is exactly why error text uses **`text-destructive-foreground`**, not `text-destructive`. Confirmed at `MeeraCopilotChat.tsx:351`, `MeeraSettingsSection.tsx:329` & `:608`, `creator-copilot.tsx:176`. Phase B error text must follow this.

Other token families in the same file: `--success` / `--success-foreground` (`:30`, `:150`; `--color-success*` `:210`), and per-stage triples e.g. `--stage-outreach` / `-fg` / `-border` (`:66–68`), `--stage-negotiating` / `-fg` / `-border` (`:69–71`) — used as `bg-stage-outreach`, `text-stage-outreach-fg`, `border-stage-negotiating-border` in the deal cards. `MeeraSettingsSection.tsx:678` uses `text-stage-disputed-fg`. The brand Meera panel has its own `meera-*` family (see §2.7).

### 7.3 Formatters

`src/lib/utils.ts`:
```ts
export function cn(...inputs: ClassValue[])                     // :4   clsx + twMerge
export function formatINR(amount?: number | null): string {     // :19
  if (amount == null || Number.isNaN(amount)) return '—'
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(amount)
}
export function publicProfileUrl(username: string): string       // :37   `${origin}/@${username}`
export function publicProfileLabel(username: string): string     // :47
export function isSafeHttpUrl(url?: string | null): boolean      // :58
```
`formatINR` returns `'—'` for null/NaN — safe everywhere.

**There is no shared date-helper module.** Date formatting is per-file, always via `toLocaleString`/`toLocaleDateString` with `'en-IN'`:
- `MeeraSettingsSection.tsx:135` — `formatDateTime(iso)`, `{ dateStyle: 'medium', timeStyle: 'short' }`
- `creator-verified-metrics.tsx:18` — `formatDate(iso)`, `{ year: 'numeric', month: 'long', day: 'numeric' }`
- `creator-deal-mappers.ts` — `formatTimeAgo`, `formatMessageTimestamp`, `getInitials` (module-private, defined above line 86)
All wrap in `try/catch` returning the raw `iso` on failure. Copy that shape for anything new.

Also useful: `src/lib/deliverable-slots.ts` — `deliverableCountOf`, `deliverableSlotsLabel`, `deliverableCountLabel(metadata, noun)` (used at `creator-chat.tsx:2419`); never read `metadata.deliverables` directly (it can be `DeliverableSlot[] | number`).

### 7.4 Lint / TS

`eslint.config.js` — flat config, `tseslint.config(...)`. Ignores `['dist','build','node_modules','app','components','hooks','lib','.claude','**/.venv/**']`; files `['src/**/*.{ts,tsx}']`; `parserOptions.tsconfigRootDir = import.meta.dirname`.

**`eslint-plugin-react-hooks` is `^7.1.1`** (react `^19`). The React-Compiler ruleset it ships at `error` is deliberately downgraded to **`'warn'`**:
```js
'react-hooks/set-state-in-effect': 'warn',
'react-hooks/set-state-in-render': 'warn',
'react-hooks/purity':             'warn',
'react-hooks/immutability':       'warn',
// (plus preserve-manual-memoization / static-components / refs in the same block)
```
with `rules-of-hooks`, `exhaustive-deps`, `use-memo`, `error-boundaries`, `globals`, `gating`, `config` left at recommended severity. Practical consequence: the standard `setLoading/setData` fetch-in-effect pattern used by every page here is a warning, not an error — Phase B can keep using it.

Scripts (`package.json`): `"typecheck": "tsc -p tsconfig.json --noEmit"`, `"lint": "eslint ."`, `"test": "vitest run"`, `"test:live": "vitest run --config vitest.live.config.ts"`, `"test:all"`.

### 7.5 Tests — vitest + testing-library

`vitest.config.ts`:
- `environment: 'jsdom'`, `globals: true`, `setupFiles: ['./src/test/setup.ts']`
- `exclude: ['**/node_modules/**','**/dist/**','**/e2e/**','**/.claude/**','**/*.live.test.ts','**/*.live.test.tsx','**/.proof-os/**']`
- `testTimeout: 30_000`, `hookTimeout: 30_000`
- `test.env: { VITE_API_MODE: 'mock' }` **and** `define: { 'import.meta.env.VITE_API_MODE': JSON.stringify('mock') }` — `define` wins over a local `.env.local` saying `live`. So `isApiLive()` is `false` in every unit test.
- `resolve.alias: { '@': path.resolve(__dirname, './src') }`
- `src/test/setup.ts` pre-patches jsdom's Radix gaps (needed to drive shadcn `Select` in tests).

**The canonical api-mocking pattern** — identical in both files you named. Because `vi.mock` is hoisted above imports, mock fns must come from `vi.hoisted()`:

```ts
const { getPreferences, updatePreferences, listConversations } = vi.hoisted(() => ({
  getPreferences: vi.fn(), updatePreferences: vi.fn(), listConversations: vi.fn(),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorAgentPrefs: {
        ...actual.api.creatorAgentPrefs,
        getPreferences: (...args: unknown[]) => getPreferences(...args),
        updatePreferences: (...args: unknown[]) => updatePreferences(...args),
        listConversations: (...args: unknown[]) => listConversations(...args),
      },
    },
  };
});

import { ApiError } from '@/lib/api';        // imports AFTER the vi.mock block
import { MeeraSettingsSection } from './MeeraSettingsSection';
```
Key points: spread `actual` so `ApiError`/`isApiLive` stay real; spread `actual.api` and only override the one namespace; wrap each mock in an arrow so identity stays stable.

`creator-copilot-feature-disabled.test.tsx` additionally shows:
- mocking the layout: `vi.mock('@/components/creator/creator-layout', () => ({ CreatorLayout: ({ children }: { children: React.ReactNode }) => <div data-testid="creator-layout">{children}</div> }))`
- stubbing unrelated data hooks: `vi.mock('@/hooks/useDailySuggestion', () => ({ useDailySuggestion: () => ({ status: 'idle' }) }))`
- mocking toast to assert absence: `vi.mock('@/hooks/use-toast', () => ({ toast: (...args: unknown[]) => toastMock(...args) }))` + `expect(toastMock).not.toHaveBeenCalled()`
- a full literal `CreatorAgentPreferences` fixture (all 18 fields) — reuse it verbatim for Phase B tests
- `render(<MemoryRouter initialEntries={['/creator/copilot']}><CreatorCopilotPage /></MemoryRouter>)`
- error fixture: `new ApiError('FEATURE_DISABLED', 'Meera for creators is disabled', 404)`
- `beforeEach(() => { vi.clearAllMocks(); })`
- Each test file's header comment carries its own run line, e.g. `Run: npx vitest run src/pages/creator-copilot-feature-disabled.test.tsx`.

`MeeraSettingsSection.roundtrip.test.tsx` uses `userEvent` from `@testing-library/user-event` to drive real Radix `Select`s and asserts the PUT payload shape.

Existing creator-Meera tests: `src/components/creator/MeeraCopilotChat.test.tsx`, `src/components/creator/MeeraSettingsSection.roundtrip.test.tsx`, `src/pages/creator-copilot-feature-disabled.test.tsx`, `src/pages/creator-copilot-meera-consent.test.tsx`, `src/pages/creator-verified-metrics.null-fields.test.tsx`.

---

## Gaps Phase B will have to create from scratch (nothing exists at HEAD)

1. **No creator-side Meera tools.** `MEERA_FUNCTION_CALLS` (MeeraChatPanel.tsx:126) and every payload type/guard in `meera-api.ts:186–397` are brand tools. `MeeraCopilotChat` registers no `onToolStart`/`onToolResult` at all.
2. **No `dealTerms` on the creator counter.** `handleSubmitCounterForm` (`creator-chat.tsx:1466`) sends `{amount, message, deadline}` only; `CounterProposalFormData` has no structured-terms fields.
3. **No agent/auto-reply message discriminator.** `MessageKind` (`api.ts:2222`) has 7 members, none for an agent-sent reply; the send log would need a new kind or a `metadata` convention.
4. **No media-kit endpoint or type.** Closest inputs are `creatorProfile.getMe()` + `publicCreators.getVerifiedMetrics()`.
5. **No brief-parsing endpoint.** `api.creatorCampaigns.apply` takes `{ message?: string }` only.
6. **Two copy strings actively contradict Phase B** and must be removed: `MeeraSettingsSection.tsx:454` ("Phase A is conversational only — Meera does not send or decline anything on your behalf yet, regardless of this setting.") and `creator-copilot.tsx:125–126`'s comment plus `:150` CardDescription.
7. **`MeeraRole` is not exported** from `meera-api.ts:399` — export it or inline the union in any new component prop.