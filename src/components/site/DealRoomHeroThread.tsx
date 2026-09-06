import { useEffect, useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import {
  ArrowLeftRight,
  BadgeCheck,
  FileCheck2,
  MessageSquareText,
  Wallet,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { DURATION_NORMAL, EASE_OUT } from '@/lib/motion-config';
import {
  DealRoomStepProgress,
  type DealPhase,
} from '@/components/brand/deal-room/deal-room-step-progress';

/**
 * DOM replacement for the WebGL hero globe (T-FRONTEND-REWORK-0905 W1).
 *
 * Renders the real Deal Room lifecycle — proposal, counter-offer, contract
 * signed, deliverable approved, payment released — as a static card, cycling
 * which stage is highlighted. Every stage's text is always mounted; only the
 * highlight styling changes, so the resting/first-paint DOM (what a headless,
 * GPU-less prerender captures) already carries the full, real content.
 *
 * The amount and handle here are illustrative — labelled "Example" so no
 * visitor mistakes this for their own data. `DealRoomStepProgress` is the
 * same component the live Deal Room uses, imported rather than re-implemented,
 * so this preview cannot drift from the product it depicts.
 */

interface Stage {
  phase: DealPhase;
  icon: typeof MessageSquareText;
  title: string;
  detail: string;
  amount?: string;
}

const STAGES: Stage[] = [
  {
    phase: 'negotiate',
    icon: MessageSquareText,
    title: 'Proposal sent',
    detail: '2 Reels + 1 Story',
    amount: '₹18,000',
  },
  {
    phase: 'negotiate',
    icon: ArrowLeftRight,
    title: 'Counter-offer',
    detail: '6-month usage rights',
    amount: '₹22,000',
  },
  {
    phase: 'contract',
    icon: FileCheck2,
    title: 'Contract signed',
    detail: 'Both parties e-signed · funds secured',
  },
  {
    phase: 'deliver',
    icon: BadgeCheck,
    title: 'Deliverable approved',
    detail: 'Reel submitted → approved',
  },
  {
    phase: 'pay',
    icon: Wallet,
    title: 'Payment released',
    detail: 'Released to creator',
    amount: '₹22,000',
  },
];

const CYCLE_MS = 2800;

export function DealRoomHeroThread() {
  const reduceMotion = useReducedMotion();
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    if (reduceMotion) return undefined;
    const id = setInterval(() => {
      setActiveIndex((i) => (i + 1) % STAGES.length);
    }, CYCLE_MS);
    return () => clearInterval(id);
  }, [reduceMotion]);

  // Reduced motion: every stage renders as complete and equally weighted —
  // never a frozen mid-cycle frame implying only one stage happened.
  const currentPhase: DealPhase = reduceMotion ? 'pay' : STAGES[activeIndex].phase;

  return (
    <div
      className="flex h-full w-full flex-col rounded-2xl border border-border/60 bg-card p-5 shadow-sm"
      role="group"
      aria-label="Example Deal Room: proposal, counter-offer, contract signed, deliverable approved, payment released"
    >
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-semibold">Deal Room</p>
        <span className="rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium text-muted-foreground">
          Example
        </span>
      </div>

      <DealRoomStepProgress currentPhase={currentPhase} className="mt-4" />

      <ol className="mt-5 flex flex-1 flex-col justify-between gap-2">
        {STAGES.map((stage, index) => {
          const Icon = stage.icon;
          const isActive = !reduceMotion && index === activeIndex;
          const isDone = !reduceMotion && index < activeIndex;

          const row = (
            <div
              className={cn(
                'flex items-center gap-3 rounded-xl border px-3.5 py-2.5 transition-colors',
                isActive
                  ? 'border-primary/50 bg-primary/5'
                  : reduceMotion || isDone
                    ? 'border-border/50 bg-background/60'
                    : 'border-border/30 bg-background/30',
              )}
            >
              <span
                aria-hidden="true"
                className={cn(
                  'flex h-7 w-7 shrink-0 items-center justify-center rounded-full',
                  isActive || reduceMotion || isDone
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-muted text-muted-foreground',
                )}
              >
                <Icon className="h-3.5 w-3.5" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium leading-tight">{stage.title}</p>
                <p className="mt-0.5 truncate text-xs text-muted-foreground">{stage.detail}</p>
              </div>
              {stage.amount && (
                <span className="shrink-0 text-sm font-semibold tabular-nums">{stage.amount}</span>
              )}
            </div>
          );

          if (reduceMotion) {
            return <li key={stage.title}>{row}</li>;
          }

          return (
            <motion.li
              key={stage.title}
              animate={{ opacity: isActive ? 1 : 0.65, scale: isActive ? 1 : 0.985 }}
              transition={{ duration: DURATION_NORMAL, ease: EASE_OUT }}
            >
              {row}
            </motion.li>
          );
        })}
      </ol>

      <p className="mt-4 text-center text-[11px] text-muted-foreground">
        Illustrative deal — amounts shown are an example, not live data.
      </p>
    </div>
  );
}
