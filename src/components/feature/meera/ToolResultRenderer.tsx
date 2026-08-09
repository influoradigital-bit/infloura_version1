/**
 * ToolResultRenderer - Inline tool result display in chat
 * ----------------------------------------------------------------------------
 * P13: Renders tool results for all 5 real backend tools (influora-ai/app/
 * tools/schemas.py:32-38) inline in the chat message stream: show_creators,
 * calculate_budget, create_campaign, request_payment, confirm_launch.
 *
 * The full stage components (StageMatching, StageRecommend, etc.) render on
 * the Living Canvas. These are compact inline renderers for the chat panel.
 *
 * Data shapes match the real Spring DTOs (`MeeraToolDtos.java`), corrected
 * 2026-07-17 (QA/Vikram) — 02-API-CONTRACT-BRAND.md was stale against them:
 *   - show_creators: { creators: [{ creatorProfileId, displayName, city?, categories?, totalFollowers, engagementRate?, verified }] }
 *   - calculate_budget: { suggestedPoolTotal, suggestedPerCreatorRate, suggestedCreatorCount, currency, rationale? }
 *   - create_campaign: { campaignId, status: 'DRAFT', serverBudget }
 *   - request_payment: { status, campaignIntentId, serverAmount, currency, confirmActionUrl, replay }
 *   - confirm_launch: { campaignId, status, creatorsInvited, replay }
 */

import { Link } from 'react-router-dom';
import { Users, Calculator, FileText, Wallet, Rocket, AlertCircle, Loader2 } from 'lucide-react';

import {
  isRequestPaymentPayload,
  isConfirmLaunchPayload,
  isOptionsPayload,
  type ShowCreatorsPayload,
  type CalculateBudgetPayload,
  type CreateCampaignPayload,
  type RequestPaymentPayload,
  type ConfirmLaunchPayload,
  type OptionsPayload,
} from '@/lib/meera-api';
import { formatINR, cn } from '@/lib/utils';

// ---------------------------------------------------------------------------
// Show Creators Result
// ---------------------------------------------------------------------------

interface ShowCreatorsResultProps {
  data: ShowCreatorsPayload;
  className?: string;
}

export function ShowCreatorsResult({ data, className }: ShowCreatorsResultProps) {
  const { creators } = data;
  const displayCount = Math.min(creators.length, 3);
  const topCreators = creators.slice(0, displayCount);

  return (
    <div
      className={cn(
        'rounded-lg border border-meera-border bg-meera-surface-2 p-3',
        className
      )}
    >
      <div className="mb-2 flex items-center gap-2 text-xs font-medium text-meera-text">
        <Users className="h-3.5 w-3.5 text-meera-accent" />
        <span>{creators.length} creators found</span>
      </div>

      <div className="space-y-1.5">
        {topCreators.map((creator) => (
          <div
            key={creator.creatorProfileId}
            className="flex items-center justify-between text-xs"
          >
            <span className="truncate text-meera-text">{creator.displayName}</span>
            <span className="shrink-0 text-meera-text-muted">
              {(creator.totalFollowers / 1000).toFixed(0)}K
              {creator.engagementRate !== undefined && (
                <>
                  <span className="mx-1 text-meera-border-strong">|</span>
                  {creator.engagementRate.toFixed(1)}%
                </>
              )}
            </span>
          </div>
        ))}
        {creators.length > displayCount && (
          <p className="text-[10px] text-meera-text-muted">
            +{creators.length - displayCount} more on canvas
          </p>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Calculate Budget Result
// ---------------------------------------------------------------------------

interface CalculateBudgetResultProps {
  data: CalculateBudgetPayload;
  className?: string;
}

export function CalculateBudgetResult({ data, className }: CalculateBudgetResultProps) {
  const { suggestedPoolTotal, suggestedPerCreatorRate, suggestedCreatorCount, rationale } = data;

  return (
    <div
      className={cn(
        'rounded-lg border border-meera-border bg-meera-surface-2 p-3',
        className
      )}
    >
      <div className="mb-2 flex items-center gap-2 text-xs font-medium text-meera-text">
        <Calculator className="h-3.5 w-3.5 text-meera-accent" />
        <span>Suggested budget</span>
      </div>

      <div className="space-y-1 text-xs">
        <div className="flex justify-between">
          <span className="text-meera-text-muted">Creator pool</span>
          <span className="text-meera-text">{formatINR(suggestedPoolTotal)}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-meera-text-muted">Per creator</span>
          <span className="text-meera-text">{formatINR(suggestedPerCreatorRate)}</span>
        </div>
        <div className="mt-1.5 flex justify-between border-t border-meera-border pt-1.5 font-semibold">
          <span className="text-meera-text">Creators</span>
          <span className="text-meera-accent">{suggestedCreatorCount}</span>
        </div>
        {rationale && <p className="pt-1 text-meera-text-muted">{rationale}</p>}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Create Campaign Result
// ---------------------------------------------------------------------------

interface CreateCampaignResultProps {
  data: CreateCampaignPayload;
  className?: string;
}

export function CreateCampaignResult({ data, className }: CreateCampaignResultProps) {
  const { campaignId, serverBudget } = data;

  return (
    <div
      className={cn(
        'rounded-lg border border-meera-border bg-meera-surface-2 p-3',
        className
      )}
    >
      <div className="flex items-center gap-2">
        <FileText className="h-4 w-4 text-meera-accent" />
        <div className="flex-1">
          <p className="text-xs font-medium text-meera-text">Draft ready — review & publish</p>
          <p className="text-[10px] text-meera-text-muted">
            {serverBudget == null ? 'Draft · budget not set yet' : `Draft · ${formatINR(serverBudget)}`}
          </p>
        </div>
        <span className="rounded-full bg-meera-accent-soft px-2 py-0.5 text-[10px] font-medium text-meera-accent">
          Draft
        </span>
      </div>
      {/*
        Deep-link routing by type (Meera completion-flow build, 2026-07-23):
        `/brand/campaigns/:id/edit` (BrandEditCampaignPage) fetches the
        campaign and branches on campaignType itself — STANDARD/OPEN/DIRECT
        get the step wizard, HYPE gets the dedicated Hype resume form. So this
        one link is already correct for both draft types; no per-type
        branching needed here.

        `CreateCampaignPayload` (src/lib/meera-api.ts) is only
        { campaignId, status, serverBudget } — it carries no proposed
        budget/date values from Meera, so there is nothing to append as
        `?budgetHint=&start=&end=` without fabricating numbers she never
        sent. If/when the backend DTO grows those fields, append them here
        the same way the STANDARD wizard consumes them (campaign-form.tsx).
      */}
      <Link
        to={`/brand/campaigns/${campaignId}/edit`}
        className="mt-2 inline-block text-[10px] font-medium text-meera-accent underline underline-offset-2"
      >
        Review & publish →
      </Link>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Request Payment Result
// ---------------------------------------------------------------------------

interface RequestPaymentResultProps {
  data: RequestPaymentPayload;
  className?: string;
}

export function RequestPaymentResult({ data, className }: RequestPaymentResultProps) {
  const { status, serverAmount, confirmActionUrl, replay } = data;

  return (
    <div
      className={cn(
        'rounded-lg border border-meera-border bg-meera-surface-2 p-3',
        className
      )}
    >
      <div className="flex items-center gap-2">
        <Wallet className="h-4 w-4 text-meera-accent" />
        <div className="flex-1">
          <p className="text-xs font-medium text-meera-text">Payment requested</p>
          <p className="text-[10px] text-meera-text-muted">
            {status} | {formatINR(serverAmount)}
          </p>
        </div>
        <span className="rounded-full bg-meera-accent-soft px-2 py-0.5 text-[10px] font-medium text-meera-accent">
          Pending
        </span>
      </div>
      {confirmActionUrl && (
        <a
          href={confirmActionUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-2 inline-block text-[10px] font-medium text-meera-accent underline underline-offset-2"
        >
          Confirm payment
        </a>
      )}
      {replay && (
        <p className="mt-1.5 text-[10px] italic text-meera-text-muted opacity-70">Already processed</p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Confirm Launch Result
// ---------------------------------------------------------------------------

interface ConfirmLaunchResultProps {
  data: ConfirmLaunchPayload;
  className?: string;
}

export function ConfirmLaunchResult({ data, className }: ConfirmLaunchResultProps) {
  const { status, creatorsInvited, replay } = data;

  return (
    <div
      className={cn(
        'rounded-lg border border-meera-border bg-meera-surface-2 p-3',
        className
      )}
    >
      <div className="flex items-center gap-2">
        <Rocket className="h-4 w-4 text-meera-accent" />
        <div className="flex-1">
          <p className="text-xs font-medium text-meera-text">Campaign launched</p>
          <p className="text-[10px] text-meera-text-muted">
            {status} | {creatorsInvited} creator{creatorsInvited === 1 ? '' : 's'} invited
          </p>
        </div>
        <span className="rounded-full bg-meera-accent-soft px-2 py-0.5 text-[10px] font-medium text-meera-accent">
          Live
        </span>
      </div>
      {replay && (
        <p className="mt-1.5 text-[10px] italic text-meera-text-muted opacity-70">Already processed</p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Options (present_options pattern) — tappable choice cards
// ---------------------------------------------------------------------------

interface OptionsCardsProps {
  data: OptionsPayload;
  /** Tapping a card sends that choice as the brand's next turn. */
  onPick?: (option: { key: string; label: string; recommended?: boolean }) => void;
  className?: string;
}

export function OptionsCards({ data, onPick, className }: OptionsCardsProps) {
  const { title, options } = data;

  return (
    <div className={cn('space-y-2', className)}>
      {title && <p className="text-xs font-medium text-meera-text">{title}</p>}
      <div className="grid gap-2">
        {options.map((opt) => (
          <button
            key={opt.key}
            type="button"
            onClick={() => onPick?.({ key: opt.key, label: opt.label, recommended: opt.recommended })}
            className={cn(
              'flex flex-col items-start gap-0.5 rounded-lg border p-3 text-left transition-colors',
              opt.recommended
                ? 'border-meera-accent bg-meera-accent-soft'
                : 'border-meera-border bg-meera-surface-2 hover:border-meera-border-strong'
            )}
          >
            <div className="flex w-full items-center justify-between gap-2">
              <span className="text-sm font-semibold text-meera-text">{opt.label}</span>
              {opt.recommended && (
                <span className="shrink-0 rounded-full bg-meera-accent px-2 py-0.5 text-[10px] font-medium text-white">
                  Recommended
                </span>
              )}
            </div>
            <span className="text-xs text-meera-text-muted">{opt.why}</span>
            {opt.budget_hint && (
              <span className="text-[10px] text-meera-text-muted opacity-80">{opt.budget_hint}</span>
            )}
          </button>
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Generic Tool Result Wrapper
// ---------------------------------------------------------------------------

export type ToolResultStatus = 'loading' | 'ok' | 'error';

interface ToolResultWrapperProps {
  toolName: string;
  status: ToolResultStatus;
  children?: React.ReactNode;
  errorMessage?: string;
  className?: string;
}

export function ToolResultWrapper({
  toolName,
  status,
  children,
  errorMessage,
  className,
}: ToolResultWrapperProps) {
  // Loading state
  if (status === 'loading') {
    return (
      <div
        className={cn(
          'flex items-center gap-2 rounded-lg border border-meera-border bg-meera-surface-2 p-3 text-xs text-meera-text-muted',
          className
        )}
      >
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        <span>Running {formatToolName(toolName)}...</span>
      </div>
    );
  }

  // Error state
  if (status === 'error') {
    return (
      <div
        className={cn(
          'flex items-center gap-2 rounded-lg border border-meera-danger/30 bg-meera-danger/5 p-3 text-xs text-meera-danger',
          className
        )}
        role="alert"
      >
        <AlertCircle className="h-3.5 w-3.5" />
        <span>{errorMessage || `Failed to run ${formatToolName(toolName)}`}</span>
      </div>
    );
  }

  // Success - render children
  return <>{children}</>;
}

/**
 * Format tool name for display (snake_case -> Title Case)
 */
function formatToolName(name: string): string {
  return name
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

// ---------------------------------------------------------------------------
// Main Dispatcher
// ---------------------------------------------------------------------------

interface ToolResultRendererProps {
  toolName: string;
  status: ToolResultStatus;
  data?: unknown;
  errorMessage?: string;
  /** For the `present_options` pattern: tapping a card sends that choice as the next turn. */
  onOptionPick?: (option: { key: string; label: string; recommended?: boolean }) => void;
  className?: string;
}

export function ToolResultRenderer({
  toolName,
  status,
  data,
  errorMessage,
  onOptionPick,
  className,
}: ToolResultRendererProps) {
  return (
    <ToolResultWrapper
      toolName={toolName}
      status={status}
      errorMessage={errorMessage}
      className={className}
    >
      {status === 'ok' && !!data && (
        <>
          {toolName === 'show_creators' && (
            <ShowCreatorsResult data={data as ShowCreatorsPayload} />
          )}
          {toolName === 'calculate_budget' && (
            <CalculateBudgetResult data={data as CalculateBudgetPayload} />
          )}
          {toolName === 'create_campaign' && (
            <CreateCampaignResult data={data as CreateCampaignPayload} />
          )}
          {toolName === 'request_payment' && isRequestPaymentPayload(data) && (
            <RequestPaymentResult data={data} />
          )}
          {toolName === 'confirm_launch' && isConfirmLaunchPayload(data) && (
            <ConfirmLaunchResult data={data} />
          )}
          {toolName === 'present_options' && isOptionsPayload(data) && (
            <OptionsCards data={data} onPick={onOptionPick} />
          )}
        </>
      )}
    </ToolResultWrapper>
  );
}

export default ToolResultRenderer;
