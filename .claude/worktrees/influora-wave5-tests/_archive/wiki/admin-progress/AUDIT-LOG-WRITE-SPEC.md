# admin_audit_log Writer — Field Spec (guardrail, pre-implementation)

Owner of this doc: Kabir (Red-Team). Written cycle 3 because no writer exists yet
(confirmed: `grep -rn "admin_audit_log" influora-api/src/main/java` → 0 hits, only
the V34 migration and its own comment reference the table name). This is expected,
not a regression — flagging it again in cycle 2's SECURITY-NOTES.md was the trigger
to pre-empt the leak risk before anyone builds the writer under time pressure.

Read this BEFORE writing `AdminAuditLogService`/`POST /admin/audit` (the endpoint
`src/admin/utils/auditLogger.ts` already assumes exists — see its `ASSUMPTION` doc
comment). Do not build the writer as a naive entity-diff serializer.

## Why this doc exists

`auditLogger.ts`'s `auditAction()` helper accepts **arbitrary caller-supplied
`oldValue`/`newValue`** and JSON-serializes whatever object it's given
(`serializeAuditValue`, line 77). Nothing stops a future call site from doing:

```ts
auditAction(admin.id, AuditAction.UPDATE, 'ADMIN_USER', targetAdmin.id, {
  oldValue: targetAdmin,   // whole entity — includes mfa_secret, password_hash
  newValue: updatedAdmin,
});
```

That pattern is a common shortcut ("just diff the entity") and it is exactly how
`admin_users.mfa_secret` (Kabir cycle-2 P0, being fixed by Vikram this cycle) or
`password_hash`/`token_hash` would end up sitting in plaintext inside
`admin_audit_log.old_value`/`new_value` JSON columns — a table that is, by design,
long-retention and broadly readable by SUPPORT/ADMIN/SUPER_ADMIN alike. Encrypting
`mfa_secret` at rest in `admin_users` (cycle 3 fix in progress) does **not** help if
the writer re-leaks the plaintext value into the audit trail on every MFA-related
admin action. The audit log must defend against this at the writer layer — do not
rely on call sites to remember to redact.

## Columns (from V34__admin_tables.sql, lines 48-63 — do not add columns not listed here)

```
id            VARCHAR(26)   PK, server-generated (ULID, match other tables)
admin_id      VARCHAR(26)   FK -> admin_users.id, server-derived from AuthPrincipal, NEVER client-supplied
admin_email   VARCHAR(255)  server-derived (look up admin_users.email by admin_id at write time — do not trust a client-sent value, it could spoof another admin's email in the trail)
action        VARCHAR(100)  allow-listed verb, see below
entity_type   VARCHAR(50)   allow-listed noun, see below
entity_id     VARCHAR(26)   the target row's id — fine to pass through
old_value     JSON          FIELD-ALLOWLISTED SNAPSHOT ONLY — see below
new_value     JSON          FIELD-ALLOWLISTED SNAPSHOT ONLY — see below
reason        TEXT          free text, admin-authored (e.g. suspension reason) — see caveat below
ip_address    VARCHAR(45)   server-derived from the request (X-Forwarded-For / remote addr), NEVER client-supplied — see Rule 1a, this is weaker than it sounds
created_at    TIMESTAMP     DB default, do not set from application code
```

## Rule 1 — `admin_id`, `admin_email`, `ip_address`, `created_at` are server-derived, always

The writer signature should NOT accept these as parameters from the HTTP request
body. Derive `admin_id`/`admin_email` from the authenticated `AuthPrincipal`
(same pattern `AdminContextService.requireRole` already uses), and `ip_address`
from `HttpServletRequest`. If `POST /admin/audit` (the endpoint `auditLogger.ts`
expects) is implemented as a literal client-callable HTTP endpoint, it must ignore
any `adminId`/`adminEmail`/`ipAddress` fields in the request body rather than
trusting them — otherwise any admin can forge audit entries attributing actions to
someone else. Prefer, if feasible given the timeline, converting this to a
server-internal call (service method invoked from each mutating admin action)
rather than a client-POSTable endpoint at all, since the client has no legitimate
need to supply anything but `action`/`entityType`/`entityId`/`reason`/diff-fields.
That's a bigger redesign than this cycle's scope — flagging as a "should" not a
blocking "must," but the field-provenance rule above (never trust client-sent
identity/IP fields) is a MUST regardless of which shape ships.

## Rule 1a — the only IP-capture code that exists in this repo today is not trustworthy for forensic logging; do not copy it as-is (added cycle 4, prep for KYC-verify/suspend/reinstate)

Checked whether the codebase has a reliable way to get real client IP behind
whatever proxy/LB sits in front of it, since that directly bears on what
`ip_address` above is actually worth. Answer: **no**. The only existing
IP-extraction code is `AuthRateLimitFilter#clientIp`
(`influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java:240-247`):

```java
private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
        int comma = forwarded.indexOf(',');
        return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
}
```

This trusts a caller-supplied `X-Forwarded-For` header unconditionally, with no
trusted-proxy allowlist and no `server.forward-headers-strategy` (or equivalent)
configured anywhere — confirmed by grep, zero hits for
`forward-headers|trusted-prox|ForwardedHeaderFilter` in `influora-api`. The
filter's own class javadoc already flags this as a known, tracked limitation
(`task_568d968e`) — but only in the context of rate-limit bucket keying, where
the worst case of a spoofed value is "attacker gets a fresh rate-limit bucket."

For `admin_audit_log.ip_address` the stakes are different and worse: this column
exists specifically so that a destructive action (KYC reject, suspend, reinstate,
future escrow release) has a forensic record of *where the acting admin actually
was*. An admin whose own credentials or session are compromised — or a malicious
insider — can trivially set `X-Forwarded-For` on their own HTTP request (it's
just a header) and have the audit trail record an IP address of their choosing.
That silently defeats the one piece of this table an incident responder would
reach for first ("was this suspend action really performed from the admin's
usual location/network"). A false forensic record is worse than an honestly
absent one, because it actively misleads instead of just under-informing.

**Action for whoever builds `AdminAuditLogService`:**

1. Do NOT import/reuse `AuthRateLimitFilter#clientIp` verbatim for the audit
   writer. Write a separate helper (or accept that duplication is fine here —
   these two call sites have different trust requirements, so sharing code
   would just make it harder to fix one without affecting the other).
2. Default to `HttpServletRequest#getRemoteAddr()` only, unless/until
   infra actually documents a trusted reverse-proxy hop (no such doc exists in
   `wiki/` today, confirmed by grep). `getRemoteAddr()` is not client-spoofable —
   if this app sits behind a load balancer/reverse proxy in prod, every request
   will show that proxy's IP rather than the true client IP, which is a
   *visibility* gap (all rows look same-IP), not a spoofing hole. That's an
   acceptable degraded state for a first cut and strictly better than trusting
   an attacker-controlled header for a forensic column.
3. If/when infra (Meera/Priya) documents the actual proxy topology and adds a
   trusted-proxy allowlist + `ForwardedHeaderFilter`/equivalent at the Spring
   config layer, the audit writer can switch to the resolved (trusted) remote
   address at that point — but that's an infra prerequisite, not something the
   audit writer should approximate by parsing `X-Forwarded-For` itself.
4. Either way, do not block the triggering admin action if IP resolution fails
   or returns null/empty — same fire-and-forget posture as Rule 5 below. Log
   `null`/empty rather than throwing.

This does not block shipping `AdminBrandController`'s KYC-verify/suspend/reinstate
actions this cycle — logging `getRemoteAddr()` (possibly "the LB's IP" in prod) is
still strictly better than logging a value an attacker can forge. It's a gap to
carry forward, not a P0 blocker for cycle 4.

## Rule 2 — `old_value` / `new_value` must go through a field allow-list per `entity_type`, never a raw entity dump

Never call `objectMapper.writeValueAsString(entity)` or accept an arbitrary
client-supplied object for these columns. Instead, the writer must maintain a
per-`entity_type` allow-list of field names that are safe to snapshot, and build
`old_value`/`new_value` by picking only those fields.

### NEVER log these fields, under any entity_type, in old_value/new_value/reason:

- `mfa_secret` (admin_users) — TOTP seed; logging it defeats MFA entirely even
  after cycle-3's AES-256-GCM-at-rest fix lands, since the audit copy would still
  be plaintext.
- `password_hash` (admin_users, users) — bcrypt/etc. hash; not "safe because
  it's hashed" — it's still a credential artifact with no reason to ever appear
  in an audit trail, and hashes can be attacked offline once exfiltrated.
- `token_hash` (admin_refresh_tokens) — session token hash; same reasoning as
  password_hash, plus a live one could plausibly still be replayed if the hash
  scheme (or a downstream bug) ever made it invertible.
- Any raw request body / raw entity serialization — the actual leak vector this
  spec exists to prevent. "Log the whole payload for debugging" is the failure
  mode. If more context is needed than the allow-list provides, expand the
  allow-list deliberately (with a comment saying why), don't fall back to
  dumping the object.
- Any OAuth/API secret columns from other tables an admin action might touch
  (e.g. Meta/Shopify/WooCommerce connection secrets, if an admin action ever
  touches those rows) — same logic, allow-list only.

### Safe to log (allow-list starting point, expand deliberately per entity_type as real mutating endpoints get built):

| entity_type    | safe fields |
|----------------|-------------|
| `ADMIN_USER`   | `id`, `email`, `role`, `is_active`, `mfa_enabled` (the boolean flag, NOT `mfa_secret`) |
| `SUPPORT_TICKET` | `id`, `status`, `priority`, `assigned_to`, `category` |
| `CONTENT_FLAG` | `id`, `status`, `action_taken`, `content_type`, `content_id` |
| `BRAND` / `CREATOR` (via `users`/profile tables) | `id`, `is_active`/suspension status, `kyc_status` if applicable — NOT `password_hash` |
| `CAMPAIGN` / `ESCROW` / `WALLET` (future budget-override/escrow-release actions) | monetary amounts, status, ids — no payment-method secrets/tokens if any exist on those rows |

This table is a starting point, not exhaustive — whoever builds the writer should
add entity types as real mutating admin endpoints (suspend, KYC approve/reject,
escrow release, budget override, ticket reassignment) get built, and each addition
should explicitly enumerate allowed fields rather than defaulting to "everything."

## Rule 3 — `action` and `entity_type` should be validated against the enums already defined client-side

`src/admin/utils/auditLogger.ts`'s `AuditAction` const (LOGIN, LOGOUT,
SESSION_EXPIRED, VIEW, CREATE, UPDATE, DELETE, APPROVE, REJECT, SUSPEND,
REINSTATE, ESCROW_RELEASE, ESCROW_HOLD, ESCROW_REFUND, PAYOUT_RETRY,
BUDGET_OVERRIDE, TIER_ADJUST, PERMISSION_DENIED) is a reasonable starting
allow-list for `action`; `entity_type` should match the table names in the "safe
to log" section above. The client-side type permits free-form strings too
(`AuditActionType = ... | string`) for forward-compat, but the server should not
silently accept and store arbitrary unbounded strings here — reject or truncate
outside a known set/length to avoid the column becoming an unstructured log
dumping ground.

## Rule 4 — `reason` is free text but still admin-authored, not client-entity data

`reason` is fine as free text (e.g. "Suspended for repeated ToS violations") since
it's something the acting admin types, not a copy of sensitive entity state. Cap
its length server-side (matches the `TEXT` column but still worth a sane app-level
max, e.g. 2000 chars) and do not let a caller stuff serialized entity data into
`reason` as a workaround for the old_value/new_value allow-list (i.e. this field
is not an escape hatch from Rule 2).

## Rule 5 — failures must not block the underlying admin action, but must not be silent either

`auditLogger.ts`'s client-side helper is already fire-and-forget by design
(`logAdminAction`'s doc comment: "must never block or break the admin action it is
recording"). Match that on the server: if the audit INSERT fails, the triggering
admin action (suspend, approve, etc.) should still complete, but the failure
should be logged server-side (application log, not silently swallowed) since a
gap in the audit trail for privileged actions is itself a security-relevant event
worth knowing about.

## Suggested method shape (illustrative, not binding)

```java
// AdminAuditLogService
void record(
    AuthPrincipal actingAdmin,      // -> admin_id, admin_email (looked up server-side)
    HttpServletRequest request,     // -> ip_address
    String action,                 // validated against allow-list
    String entityType,             // validated against allow-list
    String entityId,
    Map<String, Object> oldValueAllowed,  // caller pre-filters via a per-entity allow-list helper, NOT the raw entity
    Map<String, Object> newValueAllowed,
    String reason
);
```

Each call site (e.g. `AdminUserService.suspend(...)`) is responsible for building
`oldValueAllowed`/`newValueAllowed` via a small per-entity "toAuditSnapshot()"
helper that whitelists fields — not by passing the entity/DTO directly.

## Revisit trigger

Re-review this spec, and the writer once it exists, when: (1) the writer is first
implemented — confirm it matches Rules 1-5 (incl. 1a), especially that it does
NOT accept `adminId`/`adminEmail`/`ipAddress` from the client body if
`POST /admin/audit` ships as a client-callable endpoint, and that `ip_address` is
populated via `getRemoteAddr()` per Rule 1a, not a naive `X-Forwarded-For` parse
copied from `AuthRateLimitFilter`; (2) any new mutating admin entity type is
added to the allow-list table above — confirm the added fields don't include
secrets; (3) `mfa_secret` encryption-at-rest ships (this cycle, Vikram) — confirm
no code path decrypts it just to log it anywhere near an audit call; (4) infra
ever documents a trusted reverse-proxy topology — revisit whether the audit
writer should switch from `getRemoteAddr()` to a trusted-proxy-resolved header.
