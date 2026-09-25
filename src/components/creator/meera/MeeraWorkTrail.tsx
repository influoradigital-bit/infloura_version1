import * as React from 'react';
import { AlertTriangle, Check, Loader2 } from 'lucide-react';

import { cn } from '@/lib/utils';
import {
  TOOL_TRAIL_LABELS,
  TRAIL_HIDE,
  TRAIL_SHOW,
  creatorKnowledgeTrailLabel,
  pickLang,
  trailSummary,
} from '@/lib/copy/meera-chat';
import type { CreatorTrailToolName } from '@/lib/meera-api';

/**
 * MEERA-CHAT-DESIGN-SPEC.md Part A — "work trail on every answer".
 *
 * One `WorkTrailStep` per REAL tool event this turn received (`onToolStart`/`onToolResult` in
 * `MeeraCopilotChat.tsx`) — never invented. `MeeraCopilotChat` already keeps exactly this shape
 * as `CreatorToolResult` for the tool-result cards; this component takes the same list rather
 * than a second one; only its own subset of fields (`id`/`name`/`status`) is needed here.
 */
export interface WorkTrailStep {
  id: string;
  /** A Spring-backed creator tool, or a LOCAL one (`get_creator_knowledge`) that has no card. */
  name: CreatorTrailToolName;
  status: 'pending' | 'ok' | 'error';
  /** `get_creator_knowledge` only: the topic Meera looked up, from the tool_start input. */
  topic?: string;
}

export interface MeeraWorkTrailProps {
  steps: WorkTrailStep[];
  /**
   * True once this turn's stream has finished (`onDone`/`onError`/`onHeartbeatTimeout` in
   * `MeeraCopilotChat.tsx`). While false the full live list stays open (`aria-live="polite"`
   * so a screen reader hears each step land); once true it collapses to the one-line summary
   * ("Meera did 3 things · Show") and a tap expands/collapses the same list.
   */
  done: boolean;
  language: string;
  className?: string;
}

function stepLabel(step: WorkTrailStep, language: string): string {
  const { name, status } = step;
  const key = status === 'pending' ? 'running' : status === 'ok' ? 'done' : 'failed';
  if (name === 'get_creator_knowledge') return creatorKnowledgeTrailLabel(step.topic, key, language);
  return pickLang(language, TOOL_TRAIL_LABELS[name][key]);
}

function TrailStepIcon({ status }: { status: WorkTrailStep['status'] }) {
  if (status === 'pending') {
    return <Loader2 className="h-3 w-3 shrink-0 animate-spin text-primary" aria-hidden />;
  }
  if (status === 'ok') {
    return <Check className="h-3 w-3 shrink-0 text-success-foreground" aria-hidden />;
  }
  // Failed: a muted warning icon, never a raw error code next to it (§Part A) — the label text
  // itself is the honest "couldn't ..." sentence from TOOL_TRAIL_LABELS.
  return <AlertTriangle className="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden />;
}

/**
 * Turns with zero tool calls render nothing at all (§Part A) — that is the caller's job: only
 * pass `steps` that are real. An empty array here is also treated as "render nothing" as a
 * second line of defence.
 */
export function MeeraWorkTrail({ steps, done, language, className }: MeeraWorkTrailProps) {
  const [expanded, setExpanded] = React.useState(false);

  if (steps.length === 0) return null;

  const list = (
    <ul aria-live="polite" className="space-y-1.5">
      {steps.map((step) => (
        <li
          key={step.id}
          data-testid="work-trail-step"
          data-tool-status={step.status}
          className="flex items-center gap-2 text-xs text-muted-foreground"
        >
          <TrailStepIcon status={step.status} />
          <span>{stepLabel(step, language)}</span>
        </li>
      ))}
    </ul>
  );

  // Still running (or the turn just finished mid-render): show the live list, no collapse yet.
  if (!done) {
    return (
      <div data-testid="work-trail" data-collapsed="false" className={cn('mb-1.5', className)}>
        {list}
      </div>
    );
  }

  return (
    <div data-testid="work-trail" data-collapsed="true" className={cn('mb-1.5', className)}>
      <button
        type="button"
        data-testid="work-trail-toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded((v) => !v)}
        className="flex items-center gap-1 rounded text-xs font-medium text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <span>{trailSummary(language, steps.length)}</span>
        <span className="text-primary">· {pickLang(language, expanded ? TRAIL_HIDE : TRAIL_SHOW)}</span>
      </button>
      {expanded && <div className="mt-1.5">{list}</div>}
    </div>
  );
}

export default MeeraWorkTrail;
