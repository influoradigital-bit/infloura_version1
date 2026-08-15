import { useLocation, Link } from 'react-router-dom';
import { AlertTriangle, Clock, XCircle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useWorkspaceVerification } from '@/hooks/brand/useWorkspaceVerification';

/** The brand-workspace verification screen (GSTIN/PAN + docs → submitBrandKyc → PENDING). */
const VERIFY_HREF = '/brand/settings/verification';

type VisibleStatus = 'UNVERIFIED' | 'PENDING' | 'REJECTED';

const CONFIG: Record<
  VisibleStatus,
  { tone: string; icon: typeof AlertTriangle; title: string; sub: string; cta: string }
> = {
  UNVERIFIED: {
    tone: 'border-amber-400/60 bg-amber-50 text-amber-900 dark:border-amber-400/30 dark:bg-amber-950/40 dark:text-amber-100',
    icon: AlertTriangle,
    title: 'Verify your workspace to launch campaigns.',
    sub: 'You can build and save drafts now — they go live the moment you’re verified.',
    cta: 'Start verification',
  },
  PENDING: {
    tone: 'border-blue-400/50 bg-blue-50 text-blue-900 dark:border-blue-400/30 dark:bg-blue-950/40 dark:text-blue-100',
    icon: Clock,
    title: 'Verification in review.',
    sub: 'We’ll email you when it’s approved. Your drafts publish as soon as it clears.',
    cta: 'View status',
  },
  REJECTED: {
    tone: 'border-red-400/50 bg-red-50 text-red-900 dark:border-red-400/30 dark:bg-red-950/40 dark:text-red-100',
    icon: XCircle,
    title: 'Verification couldn’t be completed.',
    sub: 'Fix the flagged details and resubmit to start publishing.',
    cta: 'See what’s needed',
  },
};

/**
 * Proactive, persistent status banner for the workspace verification gate. Mounted once in
 * BrandLayout; self-gates to campaign routes and to the non-verified states, so it renders
 * nothing (no flash) while loading and disappears entirely once the workspace is VERIFIED.
 */
export function WorkspaceVerificationBanner() {
  const location = useLocation();
  const { status, isLoading, isVerified, canVerify } = useWorkspaceVerification();

  // The gate is about publishing campaigns — only surface it on campaign routes.
  if (!location.pathname.startsWith('/brand/campaigns')) return null;
  // No amber flash before the status is known; nothing to say once verified.
  if (isLoading || isVerified || status === null) return null;

  const cfg = CONFIG[status as VisibleStatus];
  if (!cfg) return null;
  const Icon = cfg.icon;

  return (
    <div
      role="status"
      className={cn(
        'mx-4 mt-3 flex items-center gap-3 rounded-lg border px-4 py-2.5 text-sm sm:mx-6',
        cfg.tone,
      )}
    >
      <Icon className="h-5 w-5 flex-none" aria-hidden="true" />
      <p className="min-w-0 flex-1">
        <span className="font-semibold">{cfg.title}</span>{' '}
        <span className="opacity-90">{cfg.sub}</span>
      </p>
      {canVerify ? (
        <Link
          to={VERIFY_HREF}
          className="flex-none rounded-md border border-current px-3 py-1.5 text-xs font-semibold transition-colors hover:bg-black/5 dark:hover:bg-white/10"
        >
          {cfg.cta}
        </Link>
      ) : (
        <span className="flex-none text-xs font-medium opacity-80">
          Ask a workspace admin to verify
        </span>
      )}
    </div>
  );
}
