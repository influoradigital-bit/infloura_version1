package com.influora.domain.enums;

/**
 * What kind of tenant {@code ai_conversations.workspace_id} points at (F-0751).
 *
 * <p>The column is a polymorphic tenant key: {@code ai_conversations} is shared by both audiences
 * — see the header of {@code V74__meera_creator_conversations.sql}, "the shared (BRAND + CREATOR)
 * ai_conversations table". Before this discriminator existed the two kinds of row were
 * indistinguishable in SQL, which is precisely what a DPDP export or deletion must be able to tell
 * apart.
 *
 * <p>There is deliberately no database default: a row inserted without a tenant type fails rather
 * than silently labelling itself {@code WORKSPACE}. See the migration
 * {@code V20260907120000__ai_conversations_tenant_type.sql} for why.
 */
public enum ConversationTenantType {

    /** {@code workspace_id} holds a {@code workspaces.id}. The brand and agency audience. */
    WORKSPACE,

    /**
     * {@code workspace_id} holds a {@code users.id} — the creator's user id, not their
     * {@code creator_profiles.id}. Enforced by {@code ck_conv_creator_tenant_is_starter}, which
     * requires {@code workspace_id = started_by} for these rows, so the tenant key is transitively
     * FK-checked against {@code users(id)} through {@code fk_conv_user}.
     */
    CREATOR
}
