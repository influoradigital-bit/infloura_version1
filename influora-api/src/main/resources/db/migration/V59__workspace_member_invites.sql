-- Task 23 (Phase 3c subscription billing, Vikram/Arjun 2026-07-14): seat invite/add-member flow.
-- Zero invite/add-member write path existed anywhere in the codebase before this migration --
-- AuthService.owner() at brand signup was the ONLY place a workspace_members row was ever
-- created. This table is the missing piece: a pending invitation to join a workspace at a given
-- role, gated against Plan.seatLimit at both invite-time and accept-time (WorkspaceMemberService).
--
-- invite_token_hash stores SHA-256(raw token) rather than the raw token itself, mirroring the
-- existing password_reset_tokens (V2__core_auth.sql) / refresh_tokens convention -- the raw,
-- unguessable token (JwtService.createRefreshTokenValue()) is only ever emailed to the invitee,
-- never persisted in plaintext, so a DB read (backup leak, replica access, etc.) cannot mint
-- working invite links.
--
-- Next free Flyway slot after V58 (highest existing before this pass). Owner: Vikram (Backend).

CREATE TABLE workspace_member_invites (
  id CHAR(26) PRIMARY KEY,
  workspace_id VARCHAR(26) NOT NULL,
  email VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT 'MemberRole being offered -- OWNER/ADMIN/MANAGER/MEMBER/VIEWER',
  invite_token_hash CHAR(64) NOT NULL COMMENT 'SHA-256 hex digest of the raw invite token -- raw value is only ever emailed, never stored',
  invited_by_user_id VARCHAR(26) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/EXPIRED/REVOKED',
  expires_at TIMESTAMP NOT NULL COMMENT '7 days from creation (or from most recent resend)',
  accepted_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT fk_wmi_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
  CONSTRAINT fk_wmi_invited_by FOREIGN KEY (invited_by_user_id) REFERENCES users(id),
  CONSTRAINT uk_wmi_token_hash UNIQUE (invite_token_hash),

  -- Backs the seat-limit count query (countByWorkspaceIdAndStatus) and the dedup lookup
  -- (findByWorkspaceIdAndEmailIgnoreCaseAndStatus) -- both are hot paths on every invite POST.
  INDEX idx_wmi_workspace_status (workspace_id, status),
  INDEX idx_wmi_workspace_email_status (workspace_id, email, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pending/accepted/expired/revoked workspace seat invitations -- Task 23 subscription-billing seat gating';
