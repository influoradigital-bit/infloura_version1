-- Subscription billing: Plan, Subscription, Invoice, UsageCounter
-- Task 11/14: subscription-billing prep batch 1 (subscription-billing-plan.md §1.1, rev. 3 audit-
-- corrected). Next free slot is V54 (highest existing is V53__disputes_version.sql, V40 intentionally
-- skipped). Owner: Vikram (Backend). No trial state (the plan has no time-boxed trial), Free + Pro
-- only (Enterprise deferred). The per-plan fee override + seat invite flow + Razorpay Subscriptions
-- client wrappers + subscription webhook switch-case handling are all net-new scope per CTO audit
-- Finding 1-3; this migration creates the schema skeleton only — the integration that uses it is in
-- Tasks 12–16 (still gated on Swapnil §6 sign-off for the tier matrix itself, but the entities are
-- safe to create now since the column shape doesn't change if the numbers do).

-- Plan: FREE/PRO pricing tiers (no Enterprise yet), defines limits + feature gates + fee override
CREATE TABLE plans (
  id CHAR(26) PRIMARY KEY,
  code VARCHAR(20) NOT NULL UNIQUE COMMENT 'FREE or PRO — enum enforced at app level',
  name VARCHAR(100) NOT NULL,
  price_inr INT NOT NULL COMMENT 'Monthly price in INR cents (0 for Free, 499900 for Pro ₹4,999)',
  billing_cycle VARCHAR(20) NOT NULL DEFAULT 'MONTHLY' COMMENT 'Only MONTHLY supported for now',
  razorpay_plan_id VARCHAR(100) COMMENT 'Razorpay plan_* — null for Free (no Razorpay subscription), set for Pro',
  fee_bps INT COMMENT 'Brand-side fee override in basis points (700 for Pro = 7%); null = use global PlatformFeeConfig.brandFeeBps (10%). Audit correction: no per-plan seam exists in BrandCampaignFeeService today — Task 14 must add a plan-aware resolve signature, not just flip a config value.',
  ai_monthly_allotment INT NOT NULL COMMENT 'AI credits/month (Free = 100→150 after first funded campaign; Pro = 400)',
  seat_limit INT NOT NULL COMMENT 'Max WorkspaceMembers (Free = 1, Pro = 5)',
  tracked_creator_limit INT COMMENT 'Max SavedCreators (Free = 5, Pro = null = unlimited)',
  creator_analytics_monthly_limit INT COMMENT 'Deep-dive analytics views/month (Free = 1, Pro = null = unlimited)',
  export_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  campaign_templates_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Admin-deactivatable without deletion',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_code (code),
  INDEX idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Subscription pricing tiers — defines limits + feature gates + fee overrides';

-- Subscription: 1:1 with workspaces (a workspace can have at most one active subscription)
CREATE TABLE subscriptions (
  id CHAR(26) PRIMARY KEY,
  workspace_id VARCHAR(26) NOT NULL UNIQUE COMMENT '1:1 with workspaces — a workspace has at most one subscription',
  plan_id CHAR(26) NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'ACTIVE/PAST_DUE/HALTED/CANCELLED — no TRIALING (plan has no trial)',
  razorpay_subscription_id VARCHAR(100) UNIQUE COMMENT 'Razorpay sub_* — null for Free, set for Pro',
  current_period_start TIMESTAMP NOT NULL,
  current_period_end TIMESTAMP NOT NULL,
  cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Scheduled cancellation — stays ACTIVE until period ends',
  seats_purchased INT NOT NULL DEFAULT 1 COMMENT 'Additional seats beyond plan limit (Pro only — add-on SKU, deferred)',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
  FOREIGN KEY (plan_id) REFERENCES plans(id),
  INDEX idx_status (status),
  INDEX idx_plan_id (plan_id),
  INDEX idx_razorpay_subscription_id (razorpay_subscription_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Workspace subscriptions — 1:1 with workspaces';

-- Invoice: billing history + PDF archive
CREATE TABLE invoices (
  id CHAR(26) PRIMARY KEY,
  subscription_id CHAR(26) NOT NULL,
  workspace_id VARCHAR(26) NOT NULL COMMENT 'Denormalized for fast workspace-scoped queries',
  razorpay_invoice_id VARCHAR(100) UNIQUE COMMENT 'Razorpay inv_* — null for Free (no Razorpay billing)',
  amount INT NOT NULL COMMENT 'Total invoice amount in INR cents',
  status VARCHAR(20) NOT NULL COMMENT 'DRAFT/ISSUED/PAID/FAILED',
  period_start TIMESTAMP NOT NULL,
  period_end TIMESTAMP NOT NULL,
  pdf_r2_key VARCHAR(500) COMMENT 'R2 object key for invoice PDF — null until generated',
  issued_at TIMESTAMP,
  paid_at TIMESTAMP COMMENT 'Webhook-set when invoice.paid fires',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE,
  FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
  INDEX idx_workspace_id (workspace_id),
  INDEX idx_subscription_id (subscription_id),
  INDEX idx_status (status),
  INDEX idx_razorpay_invoice_id (razorpay_invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Subscription invoices + PDF archive';

-- UsageCounter: per-workspace, per-metric, per-billing-cycle counters for gating
CREATE TABLE usage_counters (
  id CHAR(26) PRIMARY KEY,
  workspace_id VARCHAR(26) NOT NULL,
  metric VARCHAR(50) NOT NULL COMMENT 'TRACKED_CREATOR/EXPORT/CREATOR_ANALYTICS_VIEW — enum enforced at app level',
  period_start DATE NOT NULL COMMENT 'Billing cycle start date this counter applies to',
  used INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
  UNIQUE KEY uk_workspace_metric_period (workspace_id, metric, period_start),
  INDEX idx_workspace_metric (workspace_id, metric)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Per-workspace usage counters for plan limits';
