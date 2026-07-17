import * as React from 'react';
import { Link } from 'react-router-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { ArrowRight, PlayCircle, Sparkles, X } from 'lucide-react';

import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { BrandAvatar } from '@/components/ui/brand-avatar';
import { cn, formatINR } from '@/lib/utils';
import { DURATION_NORMAL, EASE_OUT } from '@/lib/motion-config';
import { useTrendSparkNudge } from '@/hooks/trendspark/useTrendSparkNudge';
import type { TrendSparkVideoCard } from '@/lib/api';

/**
 * Trend-Spark AI soft nudge card (T7 — Snapsby-TrendSpark-AI-Spec.md §5b).
 *
 * Config placeholder: the nudge voice's display name is a single constant, not
 * hardcoded per-string in JSX, so it can be renamed later without touching markup
 * (meera-tone-guide.md §1 — "Meera" is a placeholder persona name; AI-generated copy
 * itself never hardcodes it either — that's Ash's prompt layer, T8).
 */
const PERSONA_NAME = 'Meera';

const SESSION_DISMISS_PREFIX = 'trendspark_nudge_dismissed_';

function isDismissedThisSession(nudgeId: string): boolean {
  try {
    return (
      typeof window !== 'undefined' &&
      window.sessionStorage.getItem(SESSION_DISMISS_PREFIX + nudgeId) === '1'
    );
  } catch {
    return false;
  }
}

function rememberDismiss(nudgeId: string): void {
  try {
    window.sessionStorage.setItem(SESSION_DISMISS_PREFIX + nudgeId, '1');
  } catch {
    /* sessionStorage unavailable (private mode, etc.) — dismiss just won't persist */
  }
}

interface VideoRowProps {
  video: TrendSparkVideoCard;
  onPreview: (video: TrendSparkVideoCard) => void;
}

/** One SNAPSBY-mode catalog video — thumbnail, title, price, and the preview handoff. */
function VideoRow({ video, onPreview }: VideoRowProps) {
  return (
    <li className="flex items-center gap-3 rounded-lg border border-meera-border bg-meera-surface p-2">
      <img
        src={video.previewUrl}
        alt={video.title}
        width={56}
        height={56}
        loading="lazy"
        className="h-14 w-14 shrink-0 rounded-md bg-meera-surface-2 object-cover"
      />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-meera-text">{video.title}</p>
        <p className="text-xs text-meera-text-muted">{formatINR(video.priceInr)}</p>
      </div>
      <Button
        type="button"
        size="sm"
        onClick={() => onPreview(video)}
        className="shrink-0 bg-meera-accent text-white hover:bg-meera-accent-hover active:bg-meera-accent-press"
      >
        <PlayCircle className="h-4 w-4" aria-hidden="true" />
        Preview
      </Button>
    </li>
  );
}

export interface TrendSparkNudgeCardProps {
  className?: string;
}

/**
 * Dismissible soft card — NOT a modal/popup — meant to sit inline in a brand page
 * (e.g. dashboard, campaigns list). Renders nothing while loading, on error, when the
 * backend returns 204 (nothing to say), or once dismissed for the session. This is the
 * anti-spam gate (spec §5b): silence is the correct, expected default state.
 *
 * Two modes, driven entirely by `nudge.mode` from the backend — never inferred here:
 *  - OWN_CONTENT: helpful nudge only, no marketplace CTA.
 *  - SNAPSBY: up to 3 ready catalog videos with a "Preview" handoff per video.
 */
export function TrendSparkNudgeCard({ className }: TrendSparkNudgeCardProps) {
  const { nudge, isLoading, recordClick } = useTrendSparkNudge();
  const reduceMotion = useReducedMotion();
  const [dismissedId, setDismissedId] = React.useState<string | null>(null);

  const visible =
    !isLoading &&
    !!nudge &&
    nudge.nudgeId !== dismissedId &&
    !isDismissedThisSession(nudge.nudgeId);

  const dismiss = React.useCallback(() => {
    if (!nudge) return;
    rememberDismiss(nudge.nudgeId);
    setDismissedId(nudge.nudgeId);
  }, [nudge]);

  const handlePreview = React.useCallback(
    (video: TrendSparkVideoCard) => {
      recordClick();
      window.open(video.previewUrl, '_blank', 'noopener,noreferrer');
    },
    [recordClick],
  );

  return (
    <AnimatePresence>
      {visible && nudge && (
        <motion.div
          key={nudge.nudgeId}
          initial={reduceMotion ? false : { opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          exit={reduceMotion ? { opacity: 0 } : { opacity: 0, y: -8 }}
          transition={{ duration: DURATION_NORMAL, ease: EASE_OUT }}
        >
          <Card
            className={cn('border-meera-border bg-meera-surface', className)}
            role="region"
            aria-label={`Suggestion from ${PERSONA_NAME}`}
          >
            <CardContent className="p-4 sm:p-5">
              <div className="flex items-start gap-3">
                <BrandAvatar initials={PERSONA_NAME.charAt(0)} online={false} size="sm" />
                <div className="min-w-0 flex-1">
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-1.5">
                      <Sparkles className="h-3.5 w-3.5 text-meera-accent" aria-hidden="true" />
                      <span className="text-xs font-medium text-meera-text-muted">
                        {PERSONA_NAME} noticed a trend
                      </span>
                    </div>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7 shrink-0 text-meera-text-muted hover:text-meera-text"
                      onClick={dismiss}
                      aria-label="Dismiss suggestion"
                    >
                      <X className="h-4 w-4" aria-hidden="true" />
                    </Button>
                  </div>

                  <p className="mt-1.5 text-sm leading-relaxed text-meera-text">{nudge.message}</p>

                  {nudge.mode === 'SNAPSBY' && nudge.videos.length > 0 ? (
                    <ul className="mt-3 space-y-2">
                      {nudge.videos.slice(0, 3).map((video) => (
                        <VideoRow key={video.videoId} video={video} onPreview={handlePreview} />
                      ))}
                    </ul>
                  ) : (
                    // OWN_CONTENT mode — and the defensive fallback for a malformed
                    // SNAPSBY payload with no videos — never shows the marketplace.
                    <div className="mt-3">
                      <Button
                        asChild
                        size="sm"
                        onClick={recordClick}
                        className="bg-meera-accent text-white hover:bg-meera-accent-hover active:bg-meera-accent-press"
                      >
                        <Link to="/brand/campaigns/new">
                          Plan a campaign
                          <ArrowRight className="h-4 w-4" aria-hidden="true" />
                        </Link>
                      </Button>
                    </div>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

export default TrendSparkNudgeCard;
