import { VerifiedBadge } from '@/components/ui/verified-badge'
import { cn } from '@/lib/utils'

interface CreatorCardProps {
  name: string
  handle: string
  city: string
  niche: string
  followers: number
  avatarEmoji: string
  verified?: boolean
  className?: string
}

function formatFollowers(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(n % 1000 === 0 ? 0 : 1)}k`
  return `${n}`
}

/** Verified-creator tile for Stage 3 (Matching) + Stage 5 (Live) grids. */
export function CreatorCard({
  name,
  handle,
  city,
  niche,
  followers,
  avatarEmoji,
  verified = true,
  className,
}: CreatorCardProps) {
  return (
    <div
      className={cn(
        'flex items-center gap-3 rounded-lg border border-meera-border bg-meera-surface p-3',
        className,
      )}
    >
      <span
        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-meera-surface-2 text-lg"
        aria-hidden="true"
      >
        {avatarEmoji}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1">
          <p className="truncate text-sm font-medium text-meera-text">{name}</p>
          {verified && <VerifiedBadge tone="escrow" label="Instagram-verified stats" />}
        </div>
        <p className="truncate text-xs text-meera-text-muted">
          {handle} · {city} · {niche}
        </p>
      </div>
      <span className="shrink-0 text-xs font-medium tabular-nums text-meera-text-muted">
        {formatFollowers(followers)}
      </span>
    </div>
  )
}
