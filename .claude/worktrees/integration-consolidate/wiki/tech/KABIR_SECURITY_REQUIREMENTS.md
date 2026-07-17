# KABIR_SECURITY_REQUIREMENTS.md
## Security Specification for Influora Analytics Features
**Author:** Kabir (Security Lead)  
**Date:** 2026-07-06  
**Status:** DRAFT - Requires team review before implementation

---

## 1. OAuth Token Security

### Token Encryption
- **Algorithm:** AES-256-GCM (authenticated encryption)
- **Key Storage:** Environment variable or secrets manager (never in code/repo)
- **IV/Nonce:** Unique per encryption operation, stored alongside ciphertext

### Key Rotation Strategy
- Rotate encryption keys every 90 days
- Support dual-key decryption during rotation window (7 days)
- Old keys archived in secure vault, deleted after 30 days post-rotation
- Automated key rotation with zero-downtime deployment

### Token Storage Rules
- **DO:** Store encrypted in database with separate encryption key
- **DO:** Use parameterized queries for all token operations
- **DO NOT:** Log tokens (access, refresh, or any derivative)
- **DO NOT:** Include tokens in error messages or stack traces
- **DO NOT:** Store tokens in browser localStorage/sessionStorage
- **DO NOT:** Pass tokens in URL query parameters

### Refresh Token Handling
- Refresh tokens encrypted with same AES-256-GCM scheme
- Implement refresh token rotation (new refresh token on each use)
- Detect refresh token reuse as potential compromise, revoke entire grant
- Refresh tokens expire after 60 days of inactivity

### Token Revocation
- User-initiated revocation must be immediate (< 1 second)
- Revoke all tokens when user changes password
- Revoke all tokens when user disconnects Meta integration
- Maintain revocation list checked on every API call
- Notify Meta via Graph API to revoke on their side

---

## 2. API Rate Limiting & Abuse Prevention

### Analytics Endpoints (Per Workspace)
| Endpoint Type | Rate Limit | Window |
|---------------|------------|--------|
| Dashboard fetch | 100 req | 1 min |
| Report generation | 10 req | 1 min |
| Data export | 5 req | 1 hour |
| Real-time metrics | 300 req | 1 min |

### Content Library API (Public Discovery)
| Operation | Rate Limit | Window |
|-----------|------------|--------|
| Creator search | 30 req | 1 min |
| Creator profile fetch | 60 req | 1 min |
| Bulk search | 5 req | 1 min |

### Enumeration Attack Prevention
- Implement exponential backoff on repeated searches with minor variations
- Return consistent response times regardless of match/no-match
- Add random delay (50-150ms) to search responses
- Limit results per query to 50, require pagination token for more
- Log and alert on suspicious search patterns (sequential IDs, common name lists)

### DDoS Protection for Conversion Webhooks
- Cloudflare or equivalent WAF in front of webhook endpoints
- Challenge suspicious IPs with CAPTCHA on web endpoints
- Webhook signature verification (reject unsigned requests immediately)
- Connection pooling limits per source IP
- Auto-scaling with circuit breaker (reject all if queue > 10k)

---

## 3. Money-Adjacent Security (UTM/Coupons)

### Idempotency Requirements
```
Header: Idempotency-Key: <uuid-v4>
```
- Required on: coupon creation, coupon redemption, conversion attribution
- Keys valid for 24 hours, then expire
- Duplicate requests return original response, no side effects
- Store idempotency keys in Redis with TTL

### Atomic Redemption
- Use database transactions with SERIALIZABLE isolation for redemptions
- Implement optimistic locking with version column
- Double-redemption attempts logged as security events
- Redemption state machine: CREATED → RESERVED → REDEEMED (no backwards)

### Coupon Audit Trail
Every coupon operation MUST log:
- `timestamp` (ISO 8601, UTC)
- `operation` (create, reserve, redeem, expire, revoke)
- `coupon_id`
- `campaign_id`
- `workspace_id`
- `actor_id` (user or system)
- `actor_ip`
- `previous_state`
- `new_state`
- `metadata` (order_id, etc.)

### Coupon Generation Limits
| Scope | Limit | Window |
|-------|-------|--------|
| Per campaign | 1,000 codes | 1 hour |
| Per workspace | 10,000 codes | 1 day |
| Bulk generation | 500 codes | 1 request |

### Coupon Code Format
- **Length:** 12 characters minimum
- **Character set:** Alphanumeric, excluding ambiguous (0/O, 1/l/I)
- **Generation:** `crypto.randomBytes(16).toString('base64url').slice(0, 12)`
- **Entropy:** Minimum 64 bits
- **DO NOT:** Use sequential numbers, timestamps, or predictable patterns
- **DO NOT:** Include workspace/campaign identifiers in code

---

## 4. Data Privacy

### Workspace Isolation
- Every analytics query MUST include `workspace_id` in WHERE clause
- Row-level security (RLS) enabled at database level
- API layer double-checks workspace membership before returning data
- Cross-workspace queries physically impossible (separate connection pools optional)

### Analytics Data Access Control
```sql
-- Every query pattern:
SELECT * FROM analytics 
WHERE workspace_id = $workspace_id  -- MANDATORY
AND ...
```
- Middleware validates `workspace_id` matches authenticated user's workspaces
- Reject requests where workspace_id is NULL, empty, or array

### Creator PII Handling
| Data Type | Classification | Handling |
|-----------|---------------|----------|
| Name | PII | Encrypt at rest, mask in logs |
| Email | PII | Encrypt at rest, never log |
| Demographics | Sensitive PII | Aggregate only, no individual export |
| Location | PII | Country-level only unless consented |
| Audience data | Business sensitive | Workspace-scoped, no cross-sharing |

### Data Retention
| Data Type | Retention | After Expiry |
|-----------|-----------|--------------|
| Raw analytics | 2 years | Aggregate, delete raw |
| Conversion events | 3 years | Legal/tax requirement |
| Coupon redemptions | 7 years | Financial audit requirement |
| OAuth tokens | Until revoked | Hard delete |
| Audit logs | 5 years | Archive to cold storage |

### GDPR/Privacy Compliance
- Right to erasure: implement `DELETE /api/v1/creators/{id}/data`
- Data portability: implement `GET /api/v1/creators/{id}/export`
- Consent tracking: record basis for each data processing activity
- DPA (Data Processing Agreement) required for EU creator data
- Cookie consent for UTM tracking on EU traffic

---

## 5. Input Validation

### Meta API Response Validation
```typescript
// REQUIRED: Zod schema validation on ALL Meta API responses
const MetaInsightsSchema = z.object({
  data: z.array(z.object({
    id: z.string().max(64),
    impressions: z.number().int().nonnegative(),
    reach: z.number().int().nonnegative(),
    // ... strict typing for all fields
  })),
  paging: z.object({...}).optional()
});

// Reject and alert if validation fails
```
- Log validation failures with sanitized payload sample
- Do not store data that fails validation
- Alert security team on repeated validation failures (possible API change or attack)

### UTM Parameter Sanitization
```typescript
// Allowed characters: alphanumeric, hyphen, underscore, period
const UTM_PATTERN = /^[a-zA-Z0-9_\-\.]{1,256}$/;

function sanitizeUTM(param: string): string {
  if (!UTM_PATTERN.test(param)) {
    throw new ValidationError('Invalid UTM parameter');
  }
  return param;
}
```
- Reject UTM parameters containing: `<`, `>`, `"`, `'`, `;`, `(`, `)`, `{`, `}`
- Maximum length: 256 characters per parameter
- Encode for HTML context before any display

### Coupon Code Validation
```typescript
// Input validation
const COUPON_PATTERN = /^[A-Z0-9]{8,20}$/;

// Prevent SQL injection
// ALWAYS use parameterized queries
const query = 'SELECT * FROM coupons WHERE code = $1';
```
- Normalize to uppercase before comparison
- Constant-time comparison to prevent timing attacks
- Rate limit validation attempts per IP/user

---

## 6. Logging & Audit

### Analytics Access Logging
Every analytics data access MUST log:
```json
{
  "timestamp": "2026-07-06T12:00:00Z",
  "event": "analytics.read",
  "workspace_id": "ws_123",
  "user_id": "usr_456",
  "resource": "campaign_metrics",
  "query_params": { "date_range": "30d" },
  "ip": "192.168.1.1",
  "user_agent": "...",
  "response_size": 1024,
  "response_time_ms": 45
}
```

### Coupon Audit Trail
See Section 3 for required fields. Additional requirements:
- Logs MUST be immutable (append-only storage)
- Logs MUST be replicated to separate security logging system
- Logs MUST be available for 7 years (financial compliance)

### Token Operation Logging
```json
{
  "timestamp": "2026-07-06T12:00:00Z",
  "event": "token.refresh",
  "workspace_id": "ws_123",
  "user_id": "usr_456",
  "token_id": "tok_789",  // Opaque ID, NOT the token
  "ip": "192.168.1.1",
  "success": true,
  "failure_reason": null
}
```
- **NEVER log:** access_token, refresh_token, token signatures, encryption keys
- **DO log:** token operation type, anonymized token identifier, success/failure

### Conversion Event Logging
- Log all conversion events for reconciliation with payment systems
- Include idempotency key in logs for deduplication audits
- Retain for 3 years minimum

---

## 7. API Security

### Authentication Requirements
| Endpoint Type | Auth Required | Method |
|--------------|---------------|--------|
| Dashboard API | Yes | JWT (workspace scoped) |
| Analytics API | Yes | JWT (workspace scoped) |
| Conversion webhooks | Yes | HMAC signature |
| Public discovery | Yes | API key + rate limit |
| Internal polling | Yes | Service account JWT |

### JWT Requirements
- Algorithm: RS256 (asymmetric)
- Expiry: 15 minutes for access tokens
- Claims MUST include: `sub`, `workspace_ids`, `iat`, `exp`, `jti`
- Validate signature on every request
- Check `exp` with 30-second clock skew tolerance
- Reject tokens with `jti` in revocation list

### Workspace Membership Verification
```typescript
// REQUIRED middleware on all analytics endpoints
async function verifyWorkspaceMembership(req, res, next) {
  const { workspace_id } = req.params;
  const { workspace_ids } = req.jwt;
  
  if (!workspace_ids.includes(workspace_id)) {
    // Log as security event
    log.security('unauthorized_workspace_access', { ... });
    return res.status(403).json({ error: 'Forbidden' });
  }
  next();
}
```

### Public Discovery API Security
- Require API key in header: `X-API-Key: <key>`
- API keys scoped to specific operations (read-only for discovery)
- API keys rotatable without downtime
- Rate limits enforced per API key (see Section 2)

### Internal Service Accounts
- Polling jobs use service account with minimal permissions
- Service account tokens short-lived (5 minutes), auto-refreshed
- Service accounts cannot access user-facing endpoints
- Audit all service account operations separately

---

## 8. Red Team Checklist

**Before any analytics feature goes live, I (Kabir) will personally test:**

### OAuth Security
- [ ] **CSRF protection:** Verify `state` parameter is validated, tied to user session
- [ ] **Token extraction:** Grep all logs, error handlers, and responses for token leakage
- [ ] **Redirect URI validation:** Test for open redirect via OAuth callback
- [ ] **Token scope escalation:** Attempt to use token for unauthorized Graph API calls

### Data Isolation
- [ ] **Cross-workspace access:** Attempt to access workspace B's data with workspace A's token
- [ ] **IDOR on analytics:** Enumerate campaign IDs, attempt access to non-owned campaigns
- [ ] **Parameter pollution:** Submit multiple workspace_id parameters

### Money-Adjacent
- [ ] **Coupon brute-force:** Test entropy - can codes be guessed in < 10M attempts?
- [ ] **Double redemption:** Race condition testing on coupon redemption
- [ ] **Idempotency bypass:** Test idempotency key collision and reuse

### Injection & SSRF
- [ ] **Conversion tracking injection:** XSS via UTM parameters, SQL injection via coupon codes
- [ ] **SSRF via callbacks:** Submit internal URLs as conversion webhook destinations
- [ ] **Meta API response poisoning:** What if Meta returns malicious data?

### Rate Limiting
- [ ] **Rate limit bypass:** Test distributed attacks, header manipulation
- [ ] **Resource exhaustion:** Large payloads, slow loris attacks on webhooks

---

## 9. Launch Blockers

### MUST FIX Before Go-Live (P0)

| Item | Owner | Status |
|------|-------|--------|
| Encrypted token storage (AES-256-GCM) | Backend | ⬜ Not Started |
| Idempotency on coupon endpoints | Backend | ⬜ Not Started |
| Idempotency on conversion tracking | Backend | ⬜ Not Started |
| Workspace isolation tests (100% pass) | QA + Security | ⬜ Not Started |
| Rate limiting on all new endpoints | Backend | ⬜ Not Started |
| Input validation (Zod schemas) | Backend | ⬜ Not Started |
| Audit logging implementation | Backend | ⬜ Not Started |

### SHOULD FIX Before Go-Live (P1)

| Item | Owner | Status |
|------|-------|--------|
| Key rotation automation | DevOps | ⬜ Not Started |
| GDPR data export endpoint | Backend | ⬜ Not Started |
| Security monitoring alerts | Security | ⬜ Not Started |

### Post-Launch (P2)

| Item | Owner | Status |
|------|-------|--------|
| Penetration test by external firm | Security | ⬜ Not Started |
| SOC 2 compliance documentation | Compliance | ⬜ Not Started |

---

## Sign-Off Required

Before analytics features launch, this document requires approval from:

- [ ] **Kabir (Security Lead)** - Red team testing complete
- [ ] **Priya (CTO)** - Architecture approved
- [ ] **Arjun (Engineering Lead)** - Implementation complete
- [ ] **Swapnil (CEO)** - Business risk accepted

---

*Last Updated: 2026-07-06*  
*Next Review: Before each analytics feature milestone*

---

# ADDENDUM: New Security Requirements (2026-07-06 Update)

---

## 10. Shopify Webhook Security

### HMAC Verification (REQUIRED)

```java
public boolean verifyShopifyHmac(String rawBody, String hmacHeader, String secret) {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
    byte[] hash = mac.doFinal(rawBody.getBytes(UTF_8));
    String calculated = Base64.getEncoder().encodeToString(hash);
    return MessageDigest.isEqual(calculated.getBytes(), hmacHeader.getBytes());
}
```

### Webhook Secret Storage
- Store per-workspace webhook secrets encrypted (AES-256-GCM)
- Never log webhook secrets
- Rotate on brand request

---

## 11. WooCommerce Webhook Security

Same HMAC verification pattern as Shopify.

---

## 12. Affiliate Commission Security

### Anti-Fraud Requirements

| Risk | Mitigation |
|------|------------|
| Fake orders to inflate commission | Order ID idempotency, order value validation |
| Self-purchasing | Detect creator IP/email matching order |
| Commission manipulation | Commission calculated server-side from campaign config, never from webhook payload |
| Double-pay on settlement | Settlement idempotency by (creator_id, period_start, period_end) |

### Settlement Security
- Monthly settlement job runs with service account, not user context
- All settlement payouts go through existing escrow flow
- Audit trail for every commission calculation

---

## 13. Coupon Code Security (Updated)

### Unique Per Creator
- Coupon code format: `{CREATOR_SLUG}_{CAMPAIGN_PREFIX}` 
- Prevents attribution confusion
- Still validate against brute-force (rate limit redemption attempts)

### Redemption Idempotency
- Unique constraint on `(workspace_id, order_id)` in redemptions table
- Prevents double-credit from webhook retries

---

## 14. Store Integration Security

### OAuth Token Security
- Shopify/WooCommerce access tokens encrypted at rest
- Token scopes limited to read_orders, read_products (minimum required)
- Token revocation on brand disconnect

### Webhook Endpoint Security
- Rate limit: 100 requests/minute per workspace
- Body size limit: 1MB
- HMAC verification required (no anonymous webhooks)

---

## Red Team Additions

Before launch, I (Kabir) will test:

- [ ] Shopify HMAC bypass attempts
- [ ] WooCommerce signature forgery
- [ ] Affiliate commission inflation via fake orders
- [ ] Self-purchase detection bypass
- [ ] Settlement double-pay attempts
- [ ] Cross-workspace coupon redemption
- [ ] OAuth token extraction from logs/errors

---

**End of Addendum**
