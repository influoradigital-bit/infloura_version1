/**
 * ToolResultRenderer - Inline tool result display in chat
 * ----------------------------------------------------------------------------
 * P13: Renders tool results (show_creators, calculate_budget, create_campaign)
 * inline in the chat message stream.
 *
 * The full stage components (StageMatching, StageRecommend, etc.) render on
 * the Living Canvas. These are compact inline renderers for the chat panel.
 *
 * Data shapes match 02-API-CONTRACT-BRAND.md section 3:
 *   - show_creators: { creators: [...], matchedTotal: 38 }
 *   - calculate_budget: { pool, perCreator, platformFee, total }
 *   - create_campaign: { campaignId, status: 'DRAFT', serverBudget }
 */

import { Users, Calculator, FileText, CheckCircle, AlertCircle, Loader2 } from 'lucide-react';

import type {
  ShowCreatorsPayload,
  CalculateBudgetPayload,
  CreateCampaignPayload,
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
  const { creators, matchedTotal } = data;
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
        <span>{matchedTotal} creators found</span>
      </div>

      <div className="space-y-1.5">
        {topCreators.map((creator) => (
          <div
            key={creator.creatorId}
            className="flex items-center justify-between text-xs"
          >
            <span className="truncate text-meera-text">{creator.displayName}</span>
            <span className="shrink-0 text-meera-text-muted">
              {(creator.followers / 1000).toFixed(0)}K
              <span className="mx-1 text-meera-border-strong">|</span>
              {creator.engagementRate.toFixed(1)}%
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
  const { pool, perCreator, platformFee, total } = data;

  return (
    <div
      className={cn(
        'rounded-lg border border-meera-border bg-meera-surface-2 p-3',
        className
      )}
    >
      <div className="mb-2 flex items-center gap-2 text-xs font-medium text-meera-text">
        <Calculator className="h-3.5 w-3.5 text-meera-accent" />
        <span>Budget breakdown</span>
      </div>

      <div className="space-y-1 text-xs">
        <div className="flex justify-between">
          <span className="text-meera-text-muted">Creator pool</span>
          <span className="text-meera-text">{formatINR(pool)}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-meera-text-muted">Per creator</span>
          <span className="text-meera-text">{formatINR(perCreator)}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-meera-text-muted">Platform fee (15%)</span>
          <span className="text-meera-text">{formatINR(platformFee)}</span>
        </div>
        <div className="mt-1.5 flex justify-between border-t border-meera-border pt-1.5 font-semibold">
          <span className="text-meera-text">Total</span>
          <span className="text-meera-accent">{formatINR(total)}</span>
        </div>
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
  const { campaignId, status, serverBudget } = data;

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
          <p className="text-xs font-medium text-meera-text">Campaign created</p>
          <p className="text-[10px] text-meera-text-muted">
            {status} | {formatINR(serverBudget)}
          </p>
        </div>
        <span className="rounded-full bg-meera-accent-soft px-2 py-0.5 text-[10px] font-medium text-meera-accent">
          Draft
        </span>
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
  className?: string;
}

export function ToolResultRenderer({
  toolName,
  status,
  data,
  errorMessage,
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
        </>
      )}
    </ToolResultWrapper>
  );
}

export default ToolResultRenderer;
