-- Admin Panel Phase 1 (docs/ADMIN-PANEL-SPEC.md, src/admin/TASK_ASSIGNMENTS.md)
-- Owner: Meera (DevOps). Column names aligned to src/admin/types/admin.types.ts
-- where sensible (AdminUser, AuditLogEntry, SupportTicket/TicketDetail/TicketMessage,
-- ContentFlag).
--
-- Design note: admin operators are modeled as a separate `admin_users` table rather
-- than reusing `users` (V2__core_auth.sql). The existing `users.user_type` ENUM
-- already has an 'ADMIN' value, but that table has no room for admin-specific
-- concerns (SUPER_ADMIN/ADMIN/SUPPORT role granularity, MFA secret/flag) without an
-- ALTER that would touch the brand/creator auth path. Keeping admin auth in its own
-- table avoids any risk to existing login/session logic. If Priya prefers unifying
-- these later, `admin_users.id` can be backfilled to match a `users.id` FK.

CREATE TABLE admin_users (
  id                  VARCHAR(26) PRIMARY KEY,
  email               VARCHAR(255) NOT NULL UNIQUE,
  password_hash       VARCHAR(255) NOT NULL,
  role                ENUM('SUPER_ADMIN','ADMIN','SUPPORT') NOT NULL DEFAULT 'SUPPORT',
  mfa_enabled         BOOLEAN NOT NULL DEFAULT FALSE,
  mfa_secret          VARCHAR(255),
  is_active           BOOLEAN NOT NULL DEFAULT TRUE,
  last_login_at       TIMESTAMP NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE admin_refresh_tokens (
  id            VARCHAR(26) PRIMARY KEY,
  admin_id      VARCHAR(26) NOT NULL,
  token_hash    VARCHAR(64) NOT NULL,
  expires_at    TIMESTAMP NOT NULL,
  revoked       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_admin_token_hash (token_hash),
  INDEX idx_admin_refresh_admin (admin_id),
  CONSTRAINT fk_art_admin FOREIGN KEY (admin_id) REFERENCES admin_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AuditLogEntry (admin.types.ts): every mutating admin action, entity-agnostic.
-- NOTE: named `admin_audit_log`, NOT `audit_log` — V15__audit_log.sql already owns
-- `audit_log` for a different purpose (AI tool-call / money-movement audit trail:
-- actor_type MEERA_AI|HUMAN|SYSTEM|SERVICE, tool_name, server_amount, before/after
-- balance). That table has no admin_id/admin_email/entity_id/ip_address columns and
-- is written by AuditLogService for a specific set of executors — wrong shape and
-- wrong writer for admin-panel CRUD actions. Caught by live-applying V1-V34 in
-- Flyway order against a throwaway schema (`ERROR 1050 Table 'audit_log' already
-- exists` at V34) before this migration shipped. Do not rename back to `audit_log`.
CREATE TABLE admin_audit_log (
  id            VARCHAR(26) PRIMARY KEY,
  admin_id      VARCHAR(26) NOT NULL,
  admin_email   VARCHAR(255) NOT NULL,
  action        VARCHAR(100) NOT NULL,
  entity_type   VARCHAR(50) NOT NULL,
  entity_id     VARCHAR(26) NOT NULL,
  old_value     JSON,
  new_value     JSON,
  reason        TEXT,
  ip_address    VARCHAR(45) NOT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_admin_audit_admin_time (admin_id, created_at),
  INDEX idx_admin_audit_entity (entity_type, entity_id),
  CONSTRAINT fk_admin_audit_admin FOREIGN KEY (admin_id) REFERENCES admin_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- SupportTicket / TicketDetail (admin.types.ts). user_id references the existing
-- brand/creator `users` table (V2__core_auth.sql); user_type disambiguates since
-- there's no single polymorphic profile table.
CREATE TABLE support_tickets (
  id              VARCHAR(26) PRIMARY KEY,
  user_id         VARCHAR(26) NOT NULL,
  user_type       ENUM('BRAND','CREATOR') NOT NULL,
  category        VARCHAR(100) NOT NULL,
  subject         VARCHAR(255) NOT NULL,
  status          ENUM('OPEN','IN_PROGRESS','WAITING_USER','RESOLVED','CLOSED') NOT NULL DEFAULT 'OPEN',
  priority        ENUM('LOW','MEDIUM','HIGH','URGENT') NOT NULL DEFAULT 'MEDIUM',
  assigned_to     VARCHAR(26),
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  resolved_at     TIMESTAMP NULL,
  INDEX idx_ticket_user (user_id),
  INDEX idx_ticket_status_priority (status, priority),
  INDEX idx_ticket_assigned (assigned_to),
  CONSTRAINT fk_ticket_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_ticket_assigned_admin FOREIGN KEY (assigned_to) REFERENCES admin_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- TicketMessage (admin.types.ts): sender can be the ticket's own user OR an admin.
CREATE TABLE support_ticket_messages (
  id          VARCHAR(26) PRIMARY KEY,
  ticket_id   VARCHAR(26) NOT NULL,
  sender_id   VARCHAR(26) NOT NULL,
  sender_type ENUM('USER','ADMIN') NOT NULL,
  content     TEXT NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ticket_msg_ticket_time (ticket_id, created_at),
  CONSTRAINT fk_ticket_msg_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ContentFlag (admin.types.ts): moderation queue for flagged deliverables/profiles/messages.
-- content_id is intentionally not FK'd — it points at rows across several tables
-- (deliverables, creator profiles, deal_messages) depending on content_type.
CREATE TABLE content_flags (
  id                VARCHAR(26) PRIMARY KEY,
  content_type      ENUM('DELIVERABLE','PROFILE','MESSAGE') NOT NULL,
  content_id        VARCHAR(26) NOT NULL,
  content_preview   TEXT,
  flag_reason       VARCHAR(255) NOT NULL,
  flagged_by        ENUM('AI','USER','ADMIN') NOT NULL,
  status            ENUM('PENDING','REVIEWED','ACTIONED') NOT NULL DEFAULT 'PENDING',
  action_taken      VARCHAR(100),
  reviewed_by       VARCHAR(26),
  reviewed_at       TIMESTAMP NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_flag_status (status),
  INDEX idx_flag_content (content_type, content_id),
  CONSTRAINT fk_flag_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES admin_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
