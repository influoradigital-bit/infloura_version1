import { StaggerContainer, StaggerItem } from '@/components/motion/StaggerContainer'
import { formatINR } from '@/lib/utils'
import { MOCK_BRAND_SNAPSHOT } from '@/data/meera-mock'
import { cn } from '@/lib/utils'

interface StageSnapshotProps {
  className?: string
}

/** Stage 1 — brand card: logo, site preview, product tiles, brand-color swatch. */
export function StageSnapshot({ className }: StageSnapshotProps) {
  return (
    <div className={cn('space-y-4', className)}>
      <div className="flex items-center gap-3 rounded-xl border border-meera-border bg-meera-surface p-4">
        <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-meera-surface-2 text-sm font-bold text-meera-text">
          {MOCK_BRAND_SNAPSHOT.logoInitials}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-meera-text">{MOCK_BRAND_SNAPSHOT.name}</p>
          <p className="truncate text-xs text-meera-text-muted">{MOCK_BRAND_SNAPSHOT.siteUrl}</p>
        </div>
        <span
          className="h-6 w-6 shrink-0 rounded-full border border-meera-border-strong"
          style={{ backgroundColor: MOCK_BRAND_SNAPSHOT.brandColorHex }}
          aria-label="Detected brand colour"
        />
      </div>

      <StaggerContainer className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        {MOCK_BRAND_SNAPSHOT.products.map((product) => (
          <StaggerItem key={product.name}>
            <div className="rounded-lg border border-meera-border bg-meera-surface-2 p-3 text-center">
              <span className="text-2xl" aria-hidden="true">{product.imageEmoji}</span>
              <p className="mt-1 truncate text-xs font-medium text-meera-text">{product.name}</p>
              <p className="text-xs tabular-nums text-meera-text-muted">{formatINR(product.price)}</p>
            </div>
          </StaggerItem>
        ))}
      </StaggerContainer>
    </div>
  )
}
