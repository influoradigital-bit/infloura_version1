import { useRef } from 'react';
import { motion, useReducedMotion, useScroll, useTransform } from 'framer-motion';
import { CheckCircle2, Lock, Wallet } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * Scroll-pinned payment-protection explainer: a sticky panel whose three stages
 * (Funded → Locked → Released) light up as the visitor scrolls through a
 * 300vh track. Scrubbed with useScroll/useTransform — no GSAP context to
 * manage, and reduced-motion renders the three steps statically.
 */

const STAGES = [
  {
    icon: Wallet,
    title: 'Brand funds the deal',
    body: 'The full deal amount is deposited before any work starts. Creators see "Payment secured" on every invite.',
  },
  {
    icon: Lock,
    title: 'Funds lock during the work',
    body: 'While content is created and reviewed, the money is locked — neither side can touch it.',
  },
  {
    icon: CheckCircle2,
    title: 'Released on approval',
    body: 'Deliverable approved → payout releases automatically, TDS handled, invoice generated.',
  },
] as const;

function Stage({
  index,
  progress,
  reduce,
}: {
  index: number;
  progress: ReturnType<typeof useScroll>['scrollYProgress'];
  reduce: boolean;
}) {
  const start = index / STAGES.length;
  const end = (index + 1) / STAGES.length;
  const opacity = useTransform(progress, [start, start + 0.08, end - 0.04, end], [0.25, 1, 1, 0.35]);
  const y = useTransform(progress, [start, start + 0.08], [16, 0]);

  const { icon: Icon, title, body } = STAGES[index];

  const content = (
    <div className="flex items-start gap-4">
      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent text-accent-foreground">
        <Icon className="h-5 w-5" aria-hidden="true" />
      </span>
      <div>
        <h3 className="font-semibold">{title}</h3>
        <p className="mt-1 text-sm text-muted-foreground">{body}</p>
      </div>
    </div>
  );

  if (reduce) return <div>{content}</div>;

  return <motion.div style={{ opacity, y }}>{content}</motion.div>;
}

export function PaymentFlowAnimation({ className }: { className?: string }) {
  const trackRef = useRef<HTMLDivElement>(null);
  const reduce = useReducedMotion() ?? false;
  const { scrollYProgress } = useScroll({
    target: trackRef,
    offset: ['start start', 'end end'],
  });

  const barScale = useTransform(scrollYProgress, [0, 1], [0, 1]);

  return (
    <section
      ref={trackRef}
      className={cn('relative', !reduce && 'h-[300vh]', className)}
      aria-label="How payment protection works for both sides"
    >
      <div className={cn(!reduce && 'sticky top-0 flex min-h-screen items-center')}>
        <div className="mx-auto grid w-full max-w-5xl gap-10 px-6 py-16 lg:grid-cols-[1fr_1.2fr] lg:gap-16">
          <div>
            <p className="text-sm font-medium text-accent-foreground">Protection-first payments</p>
            <h2 className="mt-2 text-3xl font-semibold leading-tight">
              Money moves only when both sides are protected
            </h2>
            <p className="mt-3 text-muted-foreground">
              Every Influora deal — from a single reel to a 100-creator Hype blitz — runs on the
              same protected payment rail.
            </p>
            {!reduce && (
              <div className="mt-8 h-1.5 w-full max-w-xs overflow-hidden rounded-full bg-muted" aria-hidden="true">
                <motion.div
                  className="h-full origin-left rounded-full bg-primary"
                  style={{ scaleX: barScale }}
                />
              </div>
            )}
          </div>
          <div className="space-y-8">
            {STAGES.map((_, i) => (
              <Stage key={i} index={i} progress={scrollYProgress} reduce={reduce} />
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
