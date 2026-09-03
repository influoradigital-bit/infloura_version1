# SPEC: Meera for Creators — Phase A (Foundation)

**Task**: T-MEERA-CREATOR-PHASE-A  
**Date**: 2026-09-03  
**Approved by**: Swapnil (pending)  
**Revision**: 1  
**Plan reference**: `scratchpad/meera-creator-pr-plan.html` Part 7 Phase A  

## Contract

This spec is the build contract for three parallel work streams (backend, AI service, frontend). Engineers can build from this without talking. Every field name, migration version, endpoint path, and DTO shape is specified exactly.

**Vocabulary rule**: The word "escrow" is BANNED in all user-facing copy, API error messages, prompt text, and notification templates. Use "secured funds" / "Secure Payments" instead. Identifiers (class names, column names, existing code paths) may keep "escrow".

---

## 1. DATABASE SCHEMA (Flyway migrations)

### 1.1 Structured Deal Terms on Collaboration and Campaign

**Migration**: `V72__meera_creator_deal_terms.sql`  
**Tables**: `collaborations`, `campaigns`

```sql
-- COLLABORATIONS: structured deal terms on each deal
ALTER TABLE collaborations
    ADD COLUMN usage_months         INTEGER         NULL,     -- NULL = perpetual
    ADD COLUMN usage_perpetual      BOOLEAN         NOT NULL DEFAULT false,
    ADD COLUMN usage_channels       VARCHAR(255)    NULL,     -- comma-separated: ORGANIC,PAID_ADS,WHITELISTING,WEBSITE,OFFLINE
    ADD COLUMN exclusivity_days     INTEGER         NULL,     -- NULL = no exclusivity
    ADD COLUMN exclusivity_scope    VARCHAR(32)     NOT NULL DEFAULT 'NONE',  -- NONE, NAMED_BRANDS, CATEGORY
    ADD COLUMN exclusivity_brands   TEXT            NULL,     -- JSON array of brand names when scope=NAMED_BRANDS
    ADD COLUMN max_revisions        INTEGER         NOT NULL DEFAULT 2;

-- CAMPAIGNS: end-brand fields (required for new campaigns)
ALTER TABLE campaigns
    ADD COLUMN end_brand_name       VARCHAR(200)    NULL,     -- NULL only on legacy rows
    ADD COLUMN end_brand_category   VARCHAR(100)    NULL;     -- NULL only on legacy rows

-- Safe defaults for existing rows (no validation failures on legacy data)
-- New campaigns (created after this migration) will require these fields via DTO validation.
```

**Java enums** (new files):
- `com.influora.domain.enums.UsageChannel` — `ORGANIC, PAID_ADS, WHITELISTING, WEBSITE, OFFLINE`
- `com.influora.domain.enums.ExclusivityScope` — `NONE, NAMED_BRANDS, CATEGORY`

**Collaboration entity additions**:
```java
private Integer usageMonths;              // nullable
private boolean usagePerpetual;           // default false
private String usageChannels;             // comma-separated enum names, nullable
private Integer exclusivityDays;          // nullable
@Enumerated(EnumType.STRING)
private ExclusivityScope exclusivityScope; // default NONE
@Column(columnDefinition = "TEXT")
private String exclusivityBrands;         // JSON array, nullable
private int maxRevisions;                 // default 2
```

**Campaign entity additions**:
```java
@Column(name = "end_brand_name", length = 200)
private String endBrandName;              // nullable (legacy), required for new
@Column(name = "end_brand_category", length = 100)
private String endBrandCategory;          // nullable (legacy), required for new
```

---

### 1.2 CreatorAgentPreferences Entity

**Migration**: `V73__creator_agent_preferences.sql`

```sql
CREATE TABLE creator_agent_preferences (
    id                      VARCHAR(26)     PRIMARY KEY,
    creator_id              VARCHAR(26)     NOT NULL UNIQUE,
    reel_floor              DECIMAL(12, 2)  NULL,        -- NULL until first GET computes defaults
    story_set_floor         DECIMAL(12, 2)  NULL,
    post_floor              DECIMAL(12, 2)  NULL,
    excluded_categories     TEXT            NULL,        -- JSON array ["Alcohol", "Gambling"]
    blocked_brands          TEXT            NULL,        -- JSON array ["BrandName1", "BrandName2"]
    approval_level          INTEGER         NOT NULL DEFAULT 0,  -- 0=draft-only, 1=routine, 2=auto-decline
    creator_language        VARCHAR(10)     NOT NULL DEFAULT 'hi-IN',  -- BCP-47: hi-IN, en-IN
    brand_tone              VARCHAR(16)     NOT NULL DEFAULT 'FRIENDLY', -- FORMAL, FRIENDLY
    working_hours_start     INTEGER         NULL,        -- 0-23 hour, NULL = not set
    working_hours_end       INTEGER         NULL,        -- 0-23 hour, NULL = not set
    working_days            TEXT            NULL,        -- JSON array [1,2,3,4,5] (Mon-Fri)
    weekly_sponsored_limit  INTEGER         NULL,        -- max sponsored posts per week, NULL = not set
    represented             BOOLEAN         NOT NULL DEFAULT false,
    agency_name             VARCHAR(200)    NULL,        -- required if represented=true
    consent_accepted_at     TIMESTAMP       NULL,        -- DPDP consent timestamp, NULL = not consented
    created_at              TIMESTAMP       NOT NULL,
    updated_at              TIMESTAMP       NOT NULL,
    
    CONSTRAINT fk_creator_agent_prefs_creator FOREIGN KEY (creator_id) 
        REFERENCES creator_profiles(id) ON DELETE CASCADE
);

CREATE INDEX idx_creator_agent_prefs_creator ON creator_agent_preferences (creator_id);
```

**Java entity**: `com.influora.domain.entity.CreatorAgentPreferences`

```java
@Entity
@Table(name = "creator_agent_preferences")
public class CreatorAgentPreferences {
    @Id
    @Column(length = 26)
    private String id;
    
    @Column(name = "creator_id", nullable = false, unique = true, length = 26)
    private String creatorId;
    
    @Column(name = "reel_floor", precision = 12, scale = 2)
    private BigDecimal reelFloor;
    
    @Column(name = "story_set_floor", precision = 12, scale = 2)
    private BigDecimal storySetFloor;
    
    @Column(name = "post_floor", precision = 12, scale = 2)
    private BigDecimal postFloor;
    
    @Column(name = "excluded_categories", columnDefinition = "TEXT")
    private String excludedCategories;  // JSON
    
    @Column(name = "blocked_brands", columnDefinition = "TEXT")
    private String blockedBrands;  // JSON
    
    @Column(name = "approval_level", nullable = false)
    private int approvalLevel;  // 0, 1, 2
    
    @Column(name = "creator_language", nullable = false, length = 10)
    private String creatorLanguage;  // BCP-47
    
    @Column(name = "brand_tone", nullable = false, length = 16)
    private String brandTone;  // FORMAL, FRIENDLY
    
    @Column(name = "working_hours_start")
    private Integer workingHoursStart;  // 0-23, nullable
    
    @Column(name = "working_hours_end")
    private Integer workingHoursEnd;  // 0-23, nullable
    
    @Column(name = "working_days", columnDefinition = "TEXT")
    private String workingDays;  // JSON
    
    @Column(name = "weekly_sponsored_limit")
    private Integer weeklySponsoredLimit;  // nullable
    
    @Column(name = "represented", nullable = false)
    private boolean represented;
    
    @Column(name = "agency_name", length = 200)
    private String agencyName;  // nullable
    
    @Column(name = "consent_accepted_at")
    private Instant consentAcceptedAt;  // nullable
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    // getters, builder, etc.
}
```

**Repository**: `com.influora.repository.CreatorAgentPreferencesRepository extends JpaRepository<CreatorAgentPreferences, String>`

```java
Optional<CreatorAgentPreferences> findByCreatorId(String creatorId);
```

---

### 1.3 Admin Baselines Endpoint (no migration, read-only query)

**No table changes**. Queries existing tables: `creator_profiles`, `collaborations`, `deal_messages`.

---

### 1.4 Conversation Tracking for DPDP Export/Delete

**Migration**: `V74__meera_creator_conversations.sql`

```sql
-- Track Meera conversations for DPDP compliance (list, export, delete)
-- Links to ai_conversations via conversation_id
CREATE TABLE meera_creator_conversations (
    id                  VARCHAR(26)     PRIMARY KEY,
    creator_id          VARCHAR(26)     NOT NULL,
    conversation_id     VARCHAR(26)     NOT NULL UNIQUE,  -- FK to ai_conversations.id
    started_at          TIMESTAMP       NOT NULL,
    last_message_at     TIMESTAMP       NOT NULL,
    message_count       INTEGER         NOT NULL DEFAULT 0,
    
    CONSTRAINT fk_meera_creator_conv_creator FOREIGN KEY (creator_id)
        REFERENCES creator_profiles(id) ON DELETE CASCADE
);

CREATE INDEX idx_meera_creator_conv_creator ON meera_creator_conversations (creator_id);
CREATE INDEX idx_meera_creator_conv_last_msg ON meera_creator_conversations (creator_id, last_message_at DESC);
```

**Java entity**: `com.influora.domain.entity.MeeraCreatorConversation`

---

## 2. REST API ENDPOINTS

### 2.1 GET /api/admin/creator-agent/baselines

**Auth**: Admin only  
**Purpose**: A1 — pull baseline metrics before approving Phase A

**Response** (200 OK):
```json
{
  "creators_by_tier": {
    "NANO": 145,
    "MICRO": 89,
    "MID": 12,
    "MACRO": 3,
    "MEGA": 0
  },
  "briefs_per_creator_per_month": {
    "median": 2.3,
    "p75": 4.1,
    "p90": 6.8
  },
  "meta_connect_rate": 0.187,
  "median_creator_reply_hours": 18.5,
  "sample_label_compliance": {
    "sample_size": 100,
    "labelled_count": 73,
    "compliance_rate": 0.73
  },
  "computed_at": "2026-09-03T14:22:00Z"
}
```

**Java**: `com.influora.web.admin.AdminCreatorAgentController.getBaselines()`  
**Service**: `com.influora.service.CreatorAgentBaselineService`

SQL queries:
- Creators by tier: `SELECT tier, COUNT(*) FROM creator_profiles WHERE tier IS NOT NULL GROUP BY tier`
- Briefs per creator: compute from `collaborations` count per creator per 30-day window
- Meta connect rate: `(SELECT COUNT(DISTINCT creator_profile_id) FROM meta_oauth_tokens) / (SELECT COUNT(*) FROM creator_profiles)`
- Reply time: median of `(first creator message timestamp - first brand message timestamp)` per deal from `deal_messages`
- Label compliance: hand sample noted in response, not automated yet (Phase D)

---

### 2.2 GET /api/creator/agent-preferences

**Auth**: Creator only  
**Purpose**: A3 — fetch or compute-and-create creator's Meera preferences

**Response** (200 OK):
```json
{
  "reel_floor": 1200.00,
  "story_set_floor": 800.00,
  "post_floor": 1500.00,
  "excluded_categories": ["Alcohol", "Gambling"],
  "blocked_brands": [],
  "approval_level": 0,
  "creator_language": "hi-IN",
  "brand_tone": "FRIENDLY",
  "working_hours_start": 9,
  "working_hours_end": 18,
  "working_days": [1, 2, 3, 4, 5],
  "weekly_sponsored_limit": 3,
  "represented": false,
  "agency_name": null,
  "consent_accepted": false
}
```

**Logic**:
1. If row exists for this creator, return it
2. If not, compute defaults and create row:
   - Floors: from last COMPLETED collaboration's `agreed_rate` (if exists), else from `RateEstimationService.estimate()` low-end
   - `excluded_categories`: empty array
   - `blocked_brands`: empty array
   - `approval_level`: 0
   - `creator_language`: from `CreatorProfile.languagesJson[0]` else "hi-IN"
   - `brand_tone`: "FRIENDLY"
   - Other fields: null/empty
   - `consent_accepted_at`: NULL (not consented yet)
3. Return the computed+saved row

**Java**: `com.influora.web.creator.CreatorAgentController.getPreferences(@AuthPrincipal AuthPrincipal principal)`  
**Service**: `com.influora.service.CreatorAgentPreferencesService.getOrCreatePreferences(String creatorId)`

---

### 2.3 PUT /api/creator/agent-preferences

**Auth**: Creator only  
**Purpose**: A3 — update preferences

**Request**:
```json
{
  "reel_floor": 1500.00,
  "story_set_floor": 900.00,
  "post_floor": 1800.00,
  "excluded_categories": ["Alcohol", "Tobacco"],
  "blocked_brands": ["BrandX"],
  "approval_level": 1,
  "creator_language": "en-IN",
  "brand_tone": "FORMAL",
  "working_hours_start": 10,
  "working_hours_end": 19,
  "working_days": [1, 2, 3, 4, 5, 6],
  "weekly_sponsored_limit": 4,
  "represented": false,
  "agency_name": null
}
```

**Response** (200 OK): same as GET

**Validation**:
- `approval_level`: 0, 1, or 2
- `creator_language`: BCP-47 code (hi-IN, en-IN for Phase A)
- `brand_tone`: "FORMAL" or "FRIENDLY"
- `working_hours_start/end`: 0-23 or null
- If `represented=true`, `agency_name` is required
- All BigDecimal floors >= 0

**Java**: `CreatorAgentController.updatePreferences(@AuthPrincipal AuthPrincipal principal, @RequestBody UpdatePreferencesRequest req)`

---

### 2.4 POST /api/creator/agent-preferences/consent

**Auth**: Creator only  
**Purpose**: A6 — record DPDP consent for Meera

**Request**: (empty body or `{}`)

**Response** (200 OK):
```json
{
  "consent_accepted_at": "2026-09-03T14:30:00Z"
}
```

**Logic**: Set `consent_accepted_at = NOW()` on the creator's preferences row (create if not exists first).

**Java**: `CreatorAgentController.recordConsent(@AuthPrincipal AuthPrincipal principal)`

---

### 2.5 GET /api/creator/agent-preferences/conversations

**Auth**: Creator only  
**Purpose**: A6 — list creator's Meera conversations for DPDP export/delete

**Response** (200 OK):
```json
{
  "conversations": [
    {
      "conversation_id": "01J2ABC...",
      "started_at": "2026-09-01T10:00:00Z",
      "last_message_at": "2026-09-01T10:15:00Z",
      "message_count": 12
    },
    {
      "conversation_id": "01J2DEF...",
      "started_at": "2026-08-28T14:00:00Z",
      "last_message_at": "2026-08-28T14:22:00Z",
      "message_count": 8
    }
  ]
}
```

**Java**: `CreatorAgentController.listConversations(@AuthPrincipal AuthPrincipal principal)`  
**Service**: query `meera_creator_conversations` where `creator_id = principal.userId`, order by `last_message_at DESC`

---

### 2.6 GET /api/creator/agent-preferences/conversations/{conversationId}/export

**Auth**: Creator only (must own conversation)  
**Purpose**: A6 — export one conversation as JSON

**Response** (200 OK):
```json
{
  "conversation_id": "01J2ABC...",
  "started_at": "2026-09-01T10:00:00Z",
  "messages": [
    {
      "role": "user",
      "content": "What deals do I have?",
      "timestamp": "2026-09-01T10:00:00Z"
    },
    {
      "role": "assistant",
      "content": "You have 2 active deals...",
      "timestamp": "2026-09-01T10:00:15Z"
    }
  ]
}
```

**Logic**: Fetch from `ai_conversations` and `ai_messages` tables, return as JSON download.

**Java**: `CreatorAgentController.exportConversation(@PathVariable String conversationId, @AuthPrincipal AuthPrincipal principal)`

---

### 2.7 DELETE /api/creator/agent-preferences/conversations/{conversationId}

**Auth**: Creator only (must own conversation)  
**Purpose**: A6 — delete one conversation

**Response** (204 No Content)

**Logic**: 
1. Delete from `meera_creator_conversations`
2. Delete from `ai_messages` where `conversation_id = ...`
3. Delete from `ai_conversations` where `id = ...`

**Java**: `CreatorAgentController.deleteConversation(@PathVariable String conversationId, @AuthPrincipal AuthPrincipal principal)`

---

### 2.8 GET /api/public/creators/{username}/verified

**Auth**: Public (no auth)  
**Purpose**: A9 — public verified-metrics snapshot

**Response** (200 OK):
```json
{
  "username": "creator_name",
  "display_name": "Creator Name",
  "city": "Mumbai",
  "categories": ["Fashion", "Lifestyle"],
  "verified_metrics": {
    "followers": 12400,
    "reach_30d": 45600,
    "engagement_rate": 3.2,
    "verified_at": "2026-09-01T00:00:00Z"
  },
  "platform_deal_count": 8,
  "snapshot_date": "2026-09-03T14:30:00Z"
}
```

**Logic**:
- Pull from `creator_profiles` and latest `instagram_insights` row
- NO RATES, NO FLOORS, NO PAN/GSTIN
- Only show if creator has `discoverable=true` and has connected Meta account
- `platform_deal_count`: count of `COMPLETED` collaborations for this creator

**Java**: `com.influora.web.PublicCreatorController.getVerifiedMetrics(@PathVariable String username)`

---

### 2.9 POST /internal/meera/context (EXTENDED for CREATOR audience)

**Auth**: Internal service token + on-behalf JWT  
**Purpose**: A4 — return creator context to influora-ai

**Request**:
```json
{
  "workspace_id": "creator_user_id_here",
  "audience": "CREATOR"
}
```

**Response** (200 OK) when audience=CREATOR:
```json
{
  "workspace_id": "01J2...",
  "audience": "CREATOR",
  "display_name": "Priya Shah",
  "first_name": "Priya",
  "city": "Pune",
  "tier": "MICRO",
  "categories": ["Fashion", "Beauty"],
  "creator_language": "hi-IN",
  "brand_tone": "FRIENDLY",
  "floors": {
    "reel_floor": "1,200",
    "story_set_floor": "800",
    "post_floor": "1,500"
  },
  "metrics_summary": {
    "followers": "12,400 followers",
    "reach_30d": "45,600 reach (30 days)",
    "engagement_rate": "3.2% engagement"
  },
  "deals_summary": {
    "active_count": 2,
    "completed_count": 8,
    "total_earned_inr": "18,500"
  },
  "approval_level": 0,
  "represented": false,
  "identity": {
    "kyc_done": true,
    "gstin_present": false
  }
}
```

**CRITICAL — Info Barrier**:
- **NEVER** include PAN, GSTIN value, Aadhaar, dispute text
- **ONLY** two identity booleans: `kyc_done`, `gstin_present`
- **ALL numbers rendered as formatted strings by Java** (e.g. "12,400 followers", "1,200" for floor)
- `first_name`: extracted from `display_name` (first word, fallback to display_name)
- Floors from `creator_agent_preferences` table
- Metrics from `instagram_insights` latest row
- Deals from `collaborations` table

**Java**:
- **Existing**: `MeeraInternalController.getContext()` currently only handles `audience=BRAND`
- **New**: Add `audience=CREATOR` branch that calls `MeeraContextService.assembleCreatorContext(String creatorId)`
- **New**: `com.influora.service.meera.MeeraContextService.assembleCreatorContext(String creatorId)` returns `MeeraContextDtos.CreatorContextResponse`

**New DTO**: `MeeraContextDtos.CreatorContextResponse` (snake_case fields for Python):

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreatorContextResponse(
    @JsonProperty("workspace_id") String workspaceId,
    @JsonProperty("audience") String audience,  // "CREATOR"
    @JsonProperty("display_name") String displayName,
    @JsonProperty("first_name") String firstName,
    @JsonProperty("city") String city,
    @JsonProperty("tier") String tier,
    @JsonProperty("categories") List<String> categories,
    @JsonProperty("creator_language") String creatorLanguage,
    @JsonProperty("brand_tone") String brandTone,
    @JsonProperty("floors") Map<String, String> floors,  // all strings: "1,200"
    @JsonProperty("metrics_summary") Map<String, String> metricsSummary,  // all strings
    @JsonProperty("deals_summary") Map<String, Object> dealsSummary,
    @JsonProperty("approval_level") int approvalLevel,
    @JsonProperty("represented") boolean represented,
    @JsonProperty("identity") Map<String, Boolean> identity  // only kyc_done, gstin_present
) {}
```

**Number rendering** (A8):
- All numbers formatted by Java using `NumberFormat.getInstance(Locale.forLanguageTag(creatorLanguage))`
- Examples: 12400 → "12,400", 1200.00 → "1,200", 3.2 → "3.2%"

---

## 3. PYTHON (influora-ai) CHANGES

### 3.1 Audience Derivation in chat.py

**File**: `influora-ai/app/routes/chat.py`

**Current**: `audience = "BRAND"` is hardcoded

**Change**: Derive audience from on-behalf token (A4):

```python
# In chat() function, after token verification:
audience = _derive_audience_from_token(on_behalf_token)

def _derive_audience_from_token(token: dict) -> str:
    """
    Derive audience from on-behalf token's user_type.
    - BRAND workspace member → "BRAND"
    - CREATOR user → "CREATOR"
    """
    user_type = token.get("userType") or token.get("user_type")  # handle both cases
    if user_type == "CREATOR":
        return "CREATOR"
    elif user_type == "BRAND":
        return "BRAND"
    else:
        # Fallback: if userType is missing, check workspace_id presence
        # Brand requests have workspace_id, creator requests have user_id
        return "BRAND"  # default for now
```

**Request to Spring**: 
```python
context_req = {
    "workspace_id": workspace_id,  # or user_id for creators
    "audience": audience
}
context = await _fetch_context(context_req)  # calls POST /internal/meera/context
```

---

### 3.2 Creator Block B in assembler.py

**File**: `influora-ai/app/prompt/assembler.py`

**New function**: `build_block_b_creator(context: dict) -> list[Block]`

```python
def build_block_b_creator(context: dict) -> list[Block]:
    """
    Build Block B for CREATOR audience.
    Similar structure to brand Block B, but:
    - Peer voice (first name, informal)
    - "I work for you here" positioning
    - Never brand vocabulary, never "escrow"
    - Every number from context only (already formatted by Java)
    - Floor never shown to brands
    - Brand budget never lowers an ask
    """
    parts = []
    
    # Identity
    parts.append(f"Creator: {context['display_name']} ({context['first_name']})")
    parts.append(f"City: {context['city']}, Tier: {context['tier']}")
    parts.append(f"Categories: {', '.join(context['categories'])}")
    
    # Metrics (all pre-formatted strings from Java)
    if context.get('metrics_summary'):
        m = context['metrics_summary']
        parts.append(f"Followers: {m.get('followers', 'not connected')}")
        parts.append(f"Reach (30d): {m.get('reach_30d', 'not available')}")
        parts.append(f"Engagement: {m.get('engagement_rate', 'not available')}")
    
    # Deals summary
    if context.get('deals_summary'):
        d = context['deals_summary']
        parts.append(f"Active deals: {d['active_count']}, Completed: {d['completed_count']}")
        parts.append(f"Total earned: ₹{d['total_earned_inr']}")
    
    # Floors (for pricing, NEVER shown to brands)
    if context.get('floors'):
        f = context['floors']
        parts.append(f"Your rate floors: Reel {f['reel_floor']}, Story set {f['story_set_floor']}, Post {f['post_floor']}")
    
    # Settings
    parts.append(f"Language: {context['creator_language']}, Tone: {context['brand_tone']}")
    parts.append(f"Approval level: {context['approval_level']} (0=draft-only, 1=routine, 2=auto-decline)")
    if context['represented']:
        parts.append("REPRESENTED: warn-only mode, never draft to brands")
    
    # Identity booleans only (A7 info barrier)
    if context.get('identity'):
        i = context['identity']
        parts.append(f"KYC: {'done' if i['kyc_done'] else 'not done'}, GST: {'registered' if i['gstin_present'] else 'not registered'}")
    
    return [{"type": "text", "text": "\n".join(parts), "cache_control": {"type": "ephemeral"}}]
```

**Update `assemble_prompt()` to route by audience**:

```python
def assemble_prompt(...):
    # ...
    if audience == "CREATOR":
        block_b = build_block_b_creator(context)
        persona_module = creator_persona  # new module
    else:  # BRAND
        block_b = build_block_b(context)
        persona_module = persona
    # ...
```

---

### 3.3 Creator Persona (new module)

**File**: `influora-ai/app/prompt/creator_persona.py` (NEW, fork of `persona.py`)

```python
"""
Creator persona for Meera — Phase A (conversational + profile/deals summary only).

Voice: Peer, first-name, "I work for you here", never brand vocabulary.
Banned words: "escrow" (use "secured funds" / "Secure Payments").
Every number: from context/tool only, never hallucinated.
Floor: never shown to brands.
Brand budget: never lowers an ask.
Tools: NO money tools, NO brand tools in Phase A (conversational only).
"""

CREATOR_PERSONA = """
You are Meera, the personal manager for {first_name}, an influencer on Influora.

**Your role**: I work for you here. I help you understand your deals, track your money, and make good decisions. You always make the final call.

**Voice**:
- Use {first_name}'s first name
- Peer voice: "you", "your deals", "let's check"
- Never: "my client", "the creator", brand jargon
- {brand_tone} tone (FORMAL = professional, FRIENDLY = casual)

**Language**: Always reply in {creator_language} unless the user asks otherwise.

**What you know** (from Block B):
- Your metrics, categories, tier, city
- Your active and completed deals
- Your rate floors (NEVER show these to brands)
- Your approval level and settings

**What you do** (Phase A — conversational only):
- Answer questions about your profile and deals
- Explain your metrics and earnings
- Help you understand Influora's platform

**What you NEVER do**:
- Never state a number not in the context or tool result
- Never show your floor to a brand
- Never lower your ask based on a brand's budget
- Never say "escrow" (say "secured funds" / "Secure Payments")
- Never give legal or tax advice (explain, then refer to CA/lawyer)

**Phase A scope**: You have NO tools yet. If asked to create/negotiate/message, explain "I'm learning your profile first. This feature is coming soon."
"""

def get_creator_persona(context: dict) -> str:
    return CREATOR_PERSONA.format(
        first_name=context.get('first_name', context['display_name']),
        brand_tone=context.get('brand_tone', 'FRIENDLY'),
        creator_language=context.get('creator_language', 'hi-IN')
    )
```

---

### 3.4 Consent Gate in chat.py (A6)

**File**: `influora-ai/app/routes/chat.py`

**Add check before assembling prompt**:

```python
# After fetching context, before assemble_prompt
if audience == "CREATOR":
    if not context.get('consent_accepted'):
        # Return 403 with error code
        raise HTTPException(
            status_code=403,
            detail={
                "code": "CONSENT_REQUIRED",
                "message": "You must accept Meera's terms before using this feature.",
                "action": "show_consent_screen"
            }
        )
```

Frontend will catch this 403 and show consent screen.

---

### 3.5 Language Plumbing for Sarvam (A5)

**File**: `influora-ai/app/clients/sarvam.py` (or wherever Sarvam client lives)

**Change**: Accept `language` parameter for both STT and TTS:

```python
async def speech_to_text(audio_data: bytes, language: str = "hi-IN") -> str:
    """
    Sarvam STT with language.
    Phase A: hi-IN, en-IN
    """
    # Pass language to Sarvam API
    ...

async def text_to_speech(text: str, language: str = "hi-IN") -> bytes:
    """
    Sarvam TTS with language.
    Phase A: hi-IN, en-IN
    """
    # Pass language to Sarvam API
    ...
```

**Voice route** (`influora-ai/app/routes/voice.py`):
- Extract `creator_language` from context
- Pass to both `speech_to_text()` and `text_to_speech()`

---

### 3.6 Spend Tracker Per-Creator Cap (A8)

**File**: `influora-ai/app/costs/spend_tracker.py`

**Add per-creator monthly cap** (default USD 0.75 = ~60 INR):

```python
CREATOR_MONTHLY_CAP_USD = 0.75

async def check_creator_spend_gate(creator_id: str, audience: str) -> None:
    """
    Enforce per-creator monthly spend cap for CREATOR audience.
    Active creator costs 25-45 INR/month against 500-750 INR commission on one deal.
    """
    if audience != "CREATOR":
        return  # Only applies to creator turns
    
    # Query spend this month for this creator
    month_start = datetime.now().replace(day=1, hour=0, minute=0, second=0)
    spend_this_month = await get_creator_spend_since(creator_id, month_start)
    
    if spend_this_month >= CREATOR_MONTHLY_CAP_USD:
        raise SpendCapExceeded(
            f"You've reached your monthly Meera usage limit. This resets on the 1st of next month. "
            f"If you need more, contact support."
        )
```

Call this in `chat()` after deriving audience and before provider call.

---

### 3.7 Info Barrier Tests (A7 Python side)

**File**: `influora-ai/tests/security/test_info_barrier.py` (NEW)

**Test A7(c)**: Assert creator Block B never contains PAN/GSTIN/aadhaar, brand Block B never contains floors

```python
def test_creator_block_b_never_exposes_sensitive_identity():
    """A7(c) — creator Block B has only kyc_done/gstin_present booleans, never PAN/GSTIN/aadhaar."""
    context = {
        "display_name": "Test Creator",
        "first_name": "Test",
        "identity": {"kyc_done": True, "gstin_present": True},
        # Simulate if someone accidentally added these:
        "pan": "ABCDE1234F",
        "gstin": "27ABCDE1234F1Z5",
        "aadhaar_last4": "1234"
    }
    
    block_b = build_block_b_creator(context)
    block_text = json.dumps(block_b)  # serialize to search
    
    assert "ABCDE1234F" not in block_text
    assert "27ABCDE1234F1Z5" not in block_text
    assert "1234" not in block_text  # aadhaar last 4
    assert "kyc_done" not in block_text  # field name shouldn't leak either
    # Only the rendered boolean state should appear
    assert "KYC: done" in block_text or "not done" in block_text

def test_brand_block_b_never_exposes_creator_floors():
    """A7(c) — brand Block B never contains creator floor rates."""
    # Simulate a brand context that somehow got contaminated with creator floors
    context = {
        "display_name": "Brand Inc",
        "floors": {"reel_floor": "9,999", "story_set_floor": "8,888"},  # should never be here
        # ... rest of brand context
    }
    
    block_b = build_block_b(context)  # brand block
    block_text = json.dumps(block_b)
    
    assert "9,999" not in block_text
    assert "9999" not in block_text
    assert "8,888" not in block_text
    assert "8888" not in block_text
```

---

## 4. FRONTEND (src/) CHANGES

### 4.1 Campaign Form — End-Brand Fields Required (A2)

**File**: `src/pages/brand-campaigns.tsx` or wherever campaign create/edit form lives

**Add fields**:
```tsx
<FormField name="endBrandName" label="End Brand Name" required />
<FormField name="endBrandCategory" label="Category" required />
```

**Validation**: Both required for new campaigns (legacy campaigns may have null).

---

### 4.2 Offer/Collaboration Form — Deal Terms Fields (A2)

**File**: wherever brand creates a deal proposal (offer form)

**Add fields**:
```tsx
<FormField name="usageMonths" label="Usage Duration (months)" type="number" />
<Checkbox name="usagePerpetual" label="Perpetual usage rights" />
<MultiSelect name="usageChannels" label="Usage Channels" 
  options={["Organic", "Paid Ads", "Whitelisting", "Website", "Offline"]} />
<FormField name="exclusivityDays" label="Exclusivity (days)" type="number" />
<Select name="exclusivityScope" label="Exclusivity Scope" 
  options={["None", "Named Brands", "Category"]} />
{exclusivityScope === "Named Brands" && (
  <TextArea name="exclusivityBrands" label="Excluded Brand Names (comma-separated)" />
)}
<FormField name="maxRevisions" label="Max Revisions" type="number" defaultValue={2} />
```

**DTO types** (match Java DTOs exactly):
```ts
interface DealTerms {
  usageMonths: number | null;
  usagePerpetual: boolean;
  usageChannels: string[];  // ["ORGANIC", "PAID_ADS", ...]
  exclusivityDays: number | null;
  exclusivityScope: "NONE" | "NAMED_BRANDS" | "CATEGORY";
  exclusivityBrands: string[];  // parsed from comma-separated
  maxRevisions: number;
}
```

---

### 4.3 Creator Settings — Meera Tab (A3)

**File**: `src/pages/creator-settings.tsx` (or new tab in existing settings page)

**New section/tab**: "Meera"

```tsx
function MeeraSettingsTab() {
  const { data: prefs, isLoading } = useQuery(['creator-agent-prefs'], 
    () => api.get('/creator/agent-preferences'));
  
  const updateMutation = useMutation(
    (updates: Partial<CreatorAgentPreferences>) => 
      api.put('/creator/agent-preferences', updates)
  );
  
  return (
    <div>
      <h2>Meera Settings</h2>
      
      <Section title="Rate Floors">
        <FormField name="reelFloor" label="Reel Floor (₹)" type="number" 
          defaultValue={prefs?.reel_floor} />
        <FormField name="storySetFloor" label="Story Set Floor (₹)" type="number" 
          defaultValue={prefs?.story_set_floor} />
        <FormField name="postFloor" label="Post Floor (₹)" type="number" 
          defaultValue={prefs?.post_floor} />
        <HelpText>Your minimum rates. Defaults from your last paid deal or platform estimates.</HelpText>
      </Section>
      
      <Section title="Filters">
        <MultiSelect name="excludedCategories" label="Excluded Categories" 
          options={["Alcohol", "Tobacco", "Gambling", ...]} />
        <TextArea name="blockedBrands" label="Blocked Brands (one per line)" />
      </Section>
      
      <Section title="Automation Level">
        <RadioGroup name="approvalLevel">
          <Radio value={0} label="Draft only — I approve every message" />
          <Radio value={1} label="Routine replies — Meera can send simple messages" />
          <Radio value={2} label="Auto-decline — Meera can decline excluded categories" />
        </RadioGroup>
      </Section>
      
      <Section title="Language & Tone">
        <Select name="creatorLanguage" label="My Language" 
          options={[{value: "hi-IN", label: "हिन्दी"}, {value: "en-IN", label: "English"}]} />
        <Select name="brandTone" label="Tone to Brands" 
          options={[{value: "FORMAL", label: "Formal"}, {value: "FRIENDLY", label: "Friendly"}]} />
      </Section>
      
      <Section title="Working Hours">
        <FormField name="workingHoursStart" label="Start (hour)" type="number" min={0} max={23} />
        <FormField name="workingHoursEnd" label="End (hour)" type="number" min={0} max={23} />
        <MultiSelect name="workingDays" label="Working Days" 
          options={[{value: 1, label: "Mon"}, ..., {value: 7, label: "Sun"}]} />
        <FormField name="weeklySponsoredLimit" label="Max sponsored posts per week" type="number" />
      </Section>
      
      <Section title="Representation" condition={/* if manager seat is approved */}>
        <Checkbox name="represented" label="I am represented by an agency" />
        {represented && (
          <FormField name="agencyName" label="Agency Name" required />
        )}
        <HelpText>Represented mode: Meera warns only, never sends to brands.</HelpText>
      </Section>
      
      <Button onClick={() => updateMutation.mutate(formData)}>Save</Button>
    </div>
  );
}
```

**TS types**:
```ts
interface CreatorAgentPreferences {
  reel_floor: number | null;
  story_set_floor: number | null;
  post_floor: number | null;
  excluded_categories: string[];
  blocked_brands: string[];
  approval_level: 0 | 1 | 2;
  creator_language: string;
  brand_tone: "FORMAL" | "FRIENDLY";
  working_hours_start: number | null;
  working_hours_end: number | null;
  working_days: number[];
  weekly_sponsored_limit: number | null;
  represented: boolean;
  agency_name: string | null;
  consent_accepted: boolean;
}
```

---

### 4.4 Consent Screen (A6)

**File**: `src/components/meera/ConsentScreen.tsx` (NEW)

**Languages**: hi-IN and en-IN strings

```tsx
const CONSENT_TEXT = {
  "hi-IN": {
    title: "Meera से बात करें",
    body: "Meera आपकी AI मैनेजर है। वह आपके डील्स, पेमेंट, और मेट्रिक्स को समझने में मदद करती है।\n\nMeera आपके:\n- प्रोफाइल डेटा\n- डील हिस्ट्री\n- पेमेंट स्टेटस\n- मेट्रिक्स\n\nको access करेगी। आप कभी भी अपनी conversations को export या delete कर सकते हैं।",
    accept: "स्वीकार करें",
    decline: "अभी नहीं"
  },
  "en-IN": {
    title: "Talk to Meera",
    body: "Meera is your AI manager. She helps you understand your deals, payments, and metrics.\n\nMeera will access your:\n- Profile data\n- Deal history\n- Payment status\n- Metrics\n\nYou can export or delete your conversations anytime.",
    accept: "Accept",
    decline: "Not now"
  }
};

function ConsentScreen({ language, onAccept, onDecline }: Props) {
  const text = CONSENT_TEXT[language] || CONSENT_TEXT["en-IN"];
  
  return (
    <Dialog open>
      <DialogContent>
        <DialogTitle>{text.title}</DialogTitle>
        <DialogBody>{text.body}</DialogBody>
        <DialogActions>
          <Button onClick={onDecline} variant="ghost">{text.decline}</Button>
          <Button onClick={onAccept} variant="primary">{text.accept}</Button>
        </DialogActions>
      </DialogContent>
    </Dialog>
  );
}
```

**Logic**: 
1. First creator Meera turn → API returns 403 CONSENT_REQUIRED
2. Frontend shows ConsentScreen
3. On accept → POST `/api/creator/agent-preferences/consent` → retry chat
4. On decline → close dialog, don't open Meera

---

### 4.5 Conversation List/Export/Delete (A6)

**File**: `src/pages/creator-settings.tsx` (in Meera tab)

**Add subsection**:
```tsx
<Section title="My Meera Conversations">
  <ConversationList />
</Section>

function ConversationList() {
  const { data: convs } = useQuery(['meera-conversations'], 
    () => api.get('/creator/agent-preferences/conversations'));
  
  return (
    <Table>
      <thead>
        <tr>
          <th>Started</th>
          <th>Last Message</th>
          <th>Messages</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        {convs?.conversations.map(conv => (
          <tr key={conv.conversation_id}>
            <td>{formatDate(conv.started_at)}</td>
            <td>{formatDate(conv.last_message_at)}</td>
            <td>{conv.message_count}</td>
            <td>
              <Button onClick={() => exportConv(conv.conversation_id)}>Export</Button>
              <Button onClick={() => deleteConv(conv.conversation_id)} variant="destructive">
                Delete
              </Button>
            </td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function exportConv(id: string) {
  // GET /creator/agent-preferences/conversations/{id}/export
  // Download as JSON file
}

function deleteConv(id: string) {
  // DELETE /creator/agent-preferences/conversations/{id}
  // Confirm first
}
```

---

### 4.6 Public Verified Metrics Route (A9)

**Route**: `/c/:username/verified`

**File**: `src/pages/creator-verified-metrics.tsx` (NEW)

```tsx
export default function CreatorVerifiedMetrics() {
  const { username } = useParams();
  const { data, isLoading } = useQuery(['verified-metrics', username],
    () => fetch(`/api/public/creators/${username}/verified`).then(r => r.json())
  );
  
  if (isLoading) return <Spinner />;
  if (!data) return <NotFound />;
  
  return (
    <PublicLayout>
      <Card>
        <h1>{data.display_name}</h1>
        <p>@{data.username} · {data.city}</p>
        <Tags>{data.categories.map(c => <Tag key={c}>{c}</Tag>)}</Tags>
        
        <Section title="Verified Metrics">
          <Metric label="Followers" value={data.verified_metrics.followers.toLocaleString()} />
          <Metric label="30-Day Reach" value={data.verified_metrics.reach_30d.toLocaleString()} />
          <Metric label="Engagement Rate" value={`${data.verified_metrics.engagement_rate}%`} />
          <Small>Verified on {formatDate(data.verified_metrics.verified_at)}</Small>
        </Section>
        
        <Section title="Platform Activity">
          <Metric label="Deals Completed" value={data.platform_deal_count} />
        </Section>
        
        <Footer>
          <Small>Snapshot from {formatDate(data.snapshot_date)}</Small>
          <Small>No rates shown. Contact creator for pricing.</Small>
        </Footer>
      </Card>
    </PublicLayout>
  );
}
```

**Server-side rendering**: If using SSR, fetch this at build/request time (data is public, no auth).

---

### 4.7 Meera Chat Entry on Creator Co-Pilot Page (A10)

**File**: `src/pages/creator-copilot.tsx`

**Add Meera chat section**:
```tsx
function CreatorCopilot() {
  const [showConsent, setShowConsent] = useState(false);
  const [chatOpen, setChatOpen] = useState(false);
  
  const handleOpenMeera = async () => {
    try {
      // Ping Meera to check consent
      await api.post('/chat', { message: "hi", audience: "CREATOR" });
      setChatOpen(true);
    } catch (err) {
      if (err.response?.data?.code === "CONSENT_REQUIRED") {
        setShowConsent(true);
      }
    }
  };
  
  const handleAcceptConsent = async () => {
    await api.post('/creator/agent-preferences/consent');
    setShowConsent(false);
    setChatOpen(true);
  };
  
  return (
    <Layout>
      <Section title="Talk to Meera">
        <Button onClick={handleOpenMeera}>Open Meera</Button>
        <HelpText>Your AI manager — ask about deals, earnings, metrics.</HelpText>
      </Section>
      
      {showConsent && (
        <ConsentScreen 
          language={userLanguage} 
          onAccept={handleAcceptConsent} 
          onDecline={() => setShowConsent(false)} 
        />
      )}
      
      {chatOpen && (
        <MeeraChatPanel 
          audience="CREATOR" 
          voiceEnabled={true} 
          language={preferences?.creator_language || "hi-IN"}
          onClose={() => setChatOpen(false)} 
        />
      )}
      
      {/* Existing co-pilot trend suggestion */}
      <Section title="Today's Trend">
        <TrendSuggestion />
      </Section>
    </Layout>
  );
}
```

**MeeraChatPanel**: Reuse existing Meera chat component, pass `audience="CREATOR"`, enable voice with `creatorLanguage`.

**Day-one onboarding message**: First turn from Meera should be:
```
"Hi {first_name}! I'm Meera, your manager here on Influora. I can help you track your deals, understand your earnings, and answer questions about the platform. What would you like to know?"
```

(Sent from backend as first assistant message if conversation is new.)

---

## 5. TESTS (A7)

### 5.1 Java Architecture Test (A7a)

**File**: `influora-api/src/test/java/com/influora/architecture/InfoBarrierTest.java` (NEW)

```java
@Test
void brandToolClassesNeverImportCreatorAgentPreferencesRepository() {
    // Ensure no class under service/meera/brand* or web/MeeraInternalController
    // imports CreatorAgentPreferencesRepository
    
    JavaClasses classes = new ClassFileImporter().importPackages("com.influora");
    
    ArchRule rule = noClasses()
        .that().resideInAnyPackage("..service.meera.brand..", "..web..")
        .and().haveSimpleNameContaining("Brand")
        .should().dependOnClassesThat()
        .haveSimpleName("CreatorAgentPreferencesRepository");
    
    rule.check(classes);
}
```

Alternatively, if ArchUnit is not present, scan source files:
```java
@Test
void brandServiceFilesNeverImportCreatorPreferences() throws Exception {
    Path serviceDir = Paths.get("src/main/java/com/influora/service/meera");
    List<Path> brandFiles = Files.walk(serviceDir)
        .filter(p -> p.toString().contains("Brand"))
        .filter(p -> p.toString().endsWith(".java"))
        .toList();
    
    for (Path file : brandFiles) {
        String content = Files.readString(file);
        assertThat(content)
            .withFailMessage("File %s imports CreatorAgentPreferencesRepository", file)
            .doesNotContain("CreatorAgentPreferencesRepository");
    }
}
```

---

### 5.2 Java Runtime Test (A7b)

**File**: `influora-api/src/test/java/com/influora/service/meera/InfoBarrierRuntimeTest.java` (NEW)

```java
@SpringBootTest
class InfoBarrierRuntimeTest {
    
    @Autowired
    private CreatorAgentPreferencesService prefsService;
    
    @Autowired
    private MeeraContextService contextService;
    
    @Test
    void brandContextNeverExposesCreatorFloor() {
        // Seed a creator with reel_floor = 9999.00
        String creatorId = "01J2TESTCREATOR";
        CreatorProfile creator = createTestCreator(creatorId);
        CreatorAgentPreferences prefs = new CreatorAgentPreferences();
        prefs.setCreatorId(creatorId);
        prefs.setReelFloor(new BigDecimal("9999.00"));
        prefsRepo.save(prefs);
        
        // Fetch BRAND context for a workspace
        String workspaceId = "01J2TESTWORKSPACE";
        MeeraContextDtos.ContextResponse brandContext = contextService.assembleBrandContext(workspaceId);
        
        // Serialize to JSON
        String json = objectMapper.writeValueAsString(brandContext);
        
        // Assert "9999" and "9,999" never appear
        assertThat(json).doesNotContain("9999");
        assertThat(json).doesNotContain("9,999");
    }
    
    @Test
    void creatorChatResponseNeverExposesCreatorFloor() {
        // Similar: seed creator with floor 9999, make a CREATOR chat request,
        // assert the response JSON (and any tool payloads) never contain "9999" or "9,999"
        ...
    }
}
```

---

### 5.3 Python Tests (A7c)

Already specified in section 3.7.

---

## 6. WORK SPLIT BY AREA

### BACKEND (influora-api, Spring Boot/Java, Flyway migrations, JUnit)

**Owner**: Vikram (or assigned backend engineer)

**Tasks**:
- **A1**: Admin baselines endpoint (`GET /api/admin/creator-agent/baselines`)
  - `AdminCreatorAgentController`
  - `CreatorAgentBaselineService` (SQL queries for creators by tier, briefs/creator/month, Meta connect rate, reply time)
- **A2**: Structured deal terms
  - Flyway V72 migration
  - `Collaboration` entity: add 7 fields (usageMonths, usagePerpetual, usageChannels, exclusivityDays, exclusivityScope, exclusivityBrands, maxRevisions)
  - `Campaign` entity: add 2 fields (endBrandName, endBrandCategory)
  - New enums: `UsageChannel`, `ExclusivityScope`
  - DTOs for campaign create/update (require end-brand fields)
  - DTOs for deal proposal (include terms fields)
- **A3**: CreatorAgentPreferences entity + API
  - Flyway V73 migration (table + FK + index)
  - `CreatorAgentPreferences` entity
  - `CreatorAgentPreferencesRepository`
  - `CreatorAgentPreferencesService.getOrCreatePreferences()` (compute defaults from RateEstimationService + last deal)
  - `CreatorAgentController`: GET/PUT `/api/creator/agent-preferences`
- **A4**: Spring side — creator context
  - `MeeraContextService.assembleCreatorContext(String creatorId)` (NEW method)
  - `MeeraContextDtos.CreatorContextResponse` (NEW record, snake_case fields)
  - Update `MeeraInternalController.getContext()` to branch on `audience=CREATOR`
  - Extract first name from display_name
  - Format all numbers as strings (A8): use `NumberFormat.getInstance(Locale.forLanguageTag(creatorLanguage))`
  - Pull floors from `creator_agent_preferences`
  - Pull metrics from `instagram_insights`
  - Pull deals summary from `collaborations`
  - Only 2 identity booleans: `kyc_done` (from `CreatorProfile.kycStatus`), `gstin_present` (check `CreatorProfile.gstin != null`)
- **A6**: Consent + conversations API
  - Flyway V74 migration (`meera_creator_conversations` table)
  - `MeeraCreatorConversation` entity + repository
  - `POST /api/creator/agent-preferences/consent` — set `consent_accepted_at`
  - `GET /api/creator/agent-preferences/conversations` — list
  - `GET /api/creator/agent-preferences/conversations/{id}/export` — export as JSON
  - `DELETE /api/creator/agent-preferences/conversations/{id}` — delete (cascade to `ai_messages`, `ai_conversations`)
- **A7**: Info barrier tests (Java side)
  - A7(a): Architecture test (ArchUnit or source scan) — `InfoBarrierTest.java`
  - A7(b): Runtime test seeding floor=9999, asserting brand context never contains it — `InfoBarrierRuntimeTest.java`
- **A8**: Java number rendering
  - All numbers in `MeeraContextDtos.CreatorContextResponse` are strings, formatted by Java
- **A9**: Public verified-metrics endpoint
  - `PublicCreatorController.getVerifiedMetrics(@PathVariable String username)`
  - Pull from `creator_profiles` + `instagram_insights`
  - No rates, no floors, no PAN/GSTIN
  - Only if `discoverable=true` and Meta connected

**Deliverables**:
- 3 Flyway migrations (V72, V73, V74)
- 2 new entities (CreatorAgentPreferences, MeeraCreatorConversation)
- 2 new enums (UsageChannel, ExclusivityScope)
- 1 extended entity (Collaboration + 7 fields, Campaign + 2 fields)
- 5 new REST endpoints (admin baselines, creator prefs GET/PUT, consent, conversations list/export/delete, public verified)
- 1 extended endpoint (POST /internal/meera/context with CREATOR audience)
- 2 test classes (architecture test, runtime test)
- All compiles, all tests pass

---

### AI SERVICE (influora-ai, FastAPI/Python, pytest)

**Owner**: Dev (or assigned AI engineer)

**Tasks**:
- **A4**: Python side — audience derivation, creator Block B, persona fork
  - `chat.py`: derive `audience` from on-behalf token's `userType` field
  - `assembler.py`: `build_block_b_creator(context)` (NEW function)
  - Update `assemble_prompt()` to route by audience
  - `creator_persona.py` (NEW module, fork of `persona.py`)
    - Peer voice, first-name, "I work for you here"
    - Never "escrow" (use "secured funds")
    - Every number from context only
    - Floor never shown
    - Brand budget never lowers ask
    - NO tools in Phase A (conversational only)
- **A5**: Sarvam language plumbing
  - `sarvam.py`: accept `language` param for STT and TTS (hi-IN, en-IN)
  - `voice.py`: extract `creator_language` from context, pass to both calls
- **A6**: Consent 403 passthrough
  - `chat.py`: check `context.get('consent_accepted')` for CREATOR audience
  - Raise `HTTPException(403, {"code": "CONSENT_REQUIRED", ...})` if not consented
- **A7**: Info barrier tests (Python side)
  - `tests/security/test_info_barrier.py` (NEW)
  - A7(c): Assert creator Block B never contains PAN/GSTIN/aadhaar strings
  - A7(c): Assert brand Block B never contains floor values (test with floor=9999, search for "9,999" or "9999")
- **A8**: Spend tracker per-creator cap
  - `spend_tracker.py`: `check_creator_spend_gate(creator_id, audience)`
  - Default USD 0.75/month for CREATOR audience
  - Friendly over-cap message
  - Call in `chat()` before provider

**Deliverables**:
- 1 new module (`creator_persona.py`)
- 3 updated modules (`chat.py`, `assembler.py`, `sarvam.py`, `voice.py`, `spend_tracker.py`)
- 1 new test file (`test_info_barrier.py`)
- All pytest passes

---

### FRONTEND (src, React/TS/Vite, vitest)

**Owner**: Ananya (or assigned frontend engineer)

**Tasks**:
- **A2**: Campaign form + offer form
  - Campaign form: add `endBrandName`, `endBrandCategory` (required)
  - Offer form: add deal terms fields (usageMonths, usagePerpetual, usageChannels, exclusivityDays, exclusivityScope, exclusivityBrands, maxRevisions)
  - TS types matching Java DTOs exactly
- **A3**: Meera settings section
  - `creator-settings.tsx`: new "Meera" tab
  - Form for all fields: floors, excluded_categories, blocked_brands, approval_level, creator_language, brand_tone, working_hours, weekly_sponsored_limit, represented, agency_name
  - Default values from GET, save via PUT
  - TS interface `CreatorAgentPreferences` matching DTO
- **A6**: Consent screen + conversations list
  - `ConsentScreen.tsx` component (hi/en strings)
  - Show on first Meera turn if API returns 403 CONSENT_REQUIRED
  - On accept: POST `/api/creator/agent-preferences/consent`, retry chat
  - Conversations list in settings: GET `/api/creator/agent-preferences/conversations`
  - Export button: GET `/api/creator/agent-preferences/conversations/{id}/export`, download JSON
  - Delete button: DELETE `/api/creator/agent-preferences/conversations/{id}`, confirm first
- **A9**: Public verified-metrics route
  - `/c/:username/verified` route
  - `creator-verified-metrics.tsx` page
  - Fetch from `/api/public/creators/{username}/verified`
  - Display: username, display_name, city, categories, verified_metrics (followers, reach_30d, engagement_rate, verified_at), platform_deal_count
  - No rates, no floors
  - Server-side or static rendering for SEO (public page)
- **A10**: Meera chat entry on creator-copilot
  - `creator-copilot.tsx`: add "Talk to Meera" button
  - Consent gate flow (show ConsentScreen on 403)
  - Open `MeeraChatPanel` with `audience="CREATOR"`, voice enabled, language from preferences
  - Day-one onboarding first message (backend sends it)

**Deliverables**:
- 4 updated pages (brand-campaigns, offer form, creator-settings, creator-copilot)
- 2 new components (ConsentScreen, ConversationList)
- 1 new route page (`/c/:username/verified`)
- TS types for all new DTOs (match Java exactly — do NOT declare fields the Java record doesn't send)
- `npx tsc --noEmit` passes (no type errors)
- Vitest tests pass (if coverage required)

---

## 7. OUT OF SCOPE (Phase A)

NOT approved yet, do NOT build:
- Manager seat (multi-creator login) — pending Swapnil decision (Part 8 decision #3)
- Any Meera tools for CREATOR audience (no money tools, no brand tools) — Phase B/C
- WhatsApp notifications — Phase C
- Push notifications — Phase C
- Media kit generation — Phase B
- Deal warnings (DealRiskService) — Phase B
- Growth coach / account health — Phase D
- Level 1/2 auto-send — Phase B/E
- YouTube connect — after Google OAuth review (Phase E)
- TDS/GST computation — platform-wide gap, not Meera-specific
- Auto-release on brand silence — pending Swapnil ruling
- Meta app review filing for page-less IG accounts — manual, out of build scope

---

## 8. ACCEPTANCE CRITERIA

Phase A is DONE when:

1. **All 3 migrations** (V72, V73, V74) applied, schema matches spec
2. **All Java endpoints** work:
   - Admin baselines returns real data
   - Creator prefs GET computes defaults on first call, PUT saves
   - Consent POST sets timestamp
   - Conversations list/export/delete work
   - Public verified-metrics returns data for discoverable+connected creators
   - POST /internal/meera/context with audience=CREATOR returns creator context JSON
3. **All Python changes** deployed:
   - Audience derived from token
   - Creator Block B built correctly
   - Creator persona loaded for CREATOR turns
   - Consent 403 raised if not consented
   - Sarvam takes language from context
   - Spend tracker enforces per-creator cap
4. **All frontend pages** work:
   - Campaign form requires end-brand fields
   - Offer form captures deal terms
   - Meera settings tab saves all fields
   - Consent screen shows, POST consent works
   - Conversations list shows, export downloads JSON, delete removes
   - `/c/:username/verified` renders publicly
   - Meera chat opens on creator-copilot with consent gate
5. **All tests pass**:
   - A7(a): Architecture test — brand classes don't import CreatorAgentPreferencesRepository
   - A7(b): Runtime test — brand context never contains "9999"
   - A7(c): Python test — creator Block B never has PAN/GSTIN, brand Block B never has floors
6. **`mvn clean install` passes** (all JUnit tests)
7. **`npx tsc --noEmit` passes** (no TS errors)
8. **`pytest` passes** (all Python tests)
9. **Local E2E smoke test**:
   - Create creator account
   - GET `/api/creator/agent-preferences` → defaults computed
   - PUT prefs → saved
   - POST consent → timestamp set
   - Open Meera on creator-copilot → consent already accepted, chat loads
   - First turn as CREATOR audience → gets creator context, replies in Hindi (or chosen language)
   - Voice works with creator's language
10. **Code review** by Kavya (standards) and Kabir (security — info barrier)

---

## 9. RISKS & MITIGATIONS

| Risk | Impact | Mitigation |
|------|--------|------------|
| Floor computation fails if no deals and RateEstimationService returns 0 for unconnected creators | Floors show 0, bad UX | Fallback: hardcoded minimums (₹500 reel, ₹300 story, ₹600 post) if service returns 0 |
| Concurrent session writing same creator_agent_preferences row | Lost updates | Use optimistic locking (`@Version` on entity) or DB-level UPSERT |
| Python can't derive audience if token shape changes | All turns fail | Defensive: default to BRAND if userType missing, log warning |
| Sarvam API doesn't support en-IN yet | English voice fails | Fallback: use hi-IN for both if language not supported |
| Public verified endpoint exposes creators who don't want to be found | Privacy issue | Only show if `discoverable=true` AND Meta connected |
| Info barrier test A7(b) false-positives if "9999" appears in unrelated field | Test fails on valid code | Use a unique value like "99999.99" and check only numeric fields |

---

## 10. MIGRATION PLAN

1. **Deploy order**:
   - Backend first (migrations + APIs)
   - AI service second (context contract ready)
   - Frontend last (APIs exist)

2. **Feature flag** (optional):
   - `MEERA_CREATOR_ENABLED` flag in `application.yml`
   - Gate creator context assembly and frontend Meera button behind it
   - Enable in staging first, prod after smoke test

3. **Rollback**:
   - If critical bug found, set feature flag to `false`
   - Migrations are additive (nullable columns, new tables) — safe to keep
   - Drop tables only if catastrophic failure

4. **Data backfill**:
   - None required (all new columns nullable, defaults safe)
   - Existing campaigns/collaborations work unchanged

---

## 11. FOLLOW-UP (Phase B and beyond)

After Phase A is DONE and shipped, the next phases build on this foundation:

- **Phase B** (Weeks 2-4): Paste-and-secure, deal warnings, negotiation coach, creator tools (my_deals, get_brief, estimate_rate, draft_reply), Level 1 auto-send
- **Phase C** (Week 5): Money watch, delivery proof, WhatsApp/push notifications
- **Phase D** (Week 6): Growth coach, account health warnings
- **Phase E** (Week 7+): Level 2, brand invites, manager seat (if approved)

Each phase has its own spec, written after the prior one ships.

---

**END OF SPEC**

Questions or ambiguities → escalate to Arjun (orchestrator) or Priya (CTO for architecture).
