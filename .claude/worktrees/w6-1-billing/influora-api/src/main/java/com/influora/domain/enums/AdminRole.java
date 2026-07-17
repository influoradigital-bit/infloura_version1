package com.influora.domain.enums;

/**
 * RBAC role for {@code admin_users} (V34__admin_tables.sql). Mirrors {@code AdminRole} in
 * {@code src/admin/types/admin.types.ts} (Priya) name-for-name — Jackson serializes this enum as
 * its literal name, so the two must stay in lockstep or the admin SPA's role-gated UI breaks.
 */
public enum AdminRole {
    SUPER_ADMIN,
    ADMIN,
    SUPPORT
}
