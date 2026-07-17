import { useEffect } from 'react'

import { StaggerContainer, StaggerItem } from '@/components/motion/StaggerContainer'
import { StageLoadingState } from '@/components/feature/meera/StageLoadingState'
import { formatINR } from '@/lib/utils'
import { isApiLive } from '@/lib/api'
import { useBrandProfile } from '@/hooks/useBrandProfile'
import { MOCK_BRAND_SNAPSHOT } from '@/data/meera-mock'
import { cn } from '@/lib/utils'

interface StageSnapshotProps {
  /**
   * Latest `analyze_site` tool_result payload for this session, if any. Its
   * shape isn't part of the documented contract (02 §3 only specs
   * show_creators/calculate_budget/request_payment) — it's used purely as a
   * "the backend just (re)analyzed the site" signal to refetch the
   * persisted brand profile below, never read directly.
   */
  toolResult?: unknown
  className?: string
}

interface CatalogProduct {
  name: string
  price: number
}

/** `productCatalog` is `unknown` on the wire (02 §1.7) — narrow defensively, never trust the shape. */
function extractCatalogProducts(catalog: unknown): CatalogProduct[] {
  if (!catalog || typeof catalog !== 'object') return []
  const products = (catalog as { products?: unknown }).products
  if (!Array.isArray(products)) return []
  return products.filter((p): p is CatalogProduct => {
    if (!p || typeof p !== 'object') return false
    const candidate = p as { name?: unknown; price?: unknown }
    return typeof candidate.name === 'string' && typeof candidate.price === 'number'
  })
}

function siteInitials(siteUrl: string | null): string {
  if (!siteUrl) return '—'
  const host = siteUrl.replace(/^https?:\/\//i, '').replace(/^www\./i, '')
  return host.slice(0, 2).toUpperCase()
}

/** Stage 1 — brand card: logo, site preview, product tiles, brand-color swatch. */
export function StageSnapshot({ toolResult, className }: StageSnapshotProps) {
  const live = isApiLive()
  const { brandProfile, isLoading, error, refetch } = useBrandProfile()

  // An `analyze_site` tool_result means the backend just finished (re)analyzing
  // the site this turn — refresh the persisted profile rather than guessing at
  // the tool_result's own (undocumented) shape.
  useEffect(() => {
    if (live && toolResult !== undefined) refetch()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, toolResult])

  if (!live) {
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

  // Live mode — no fabricated data. Loading covers both the initial fetch and
  // the backend still analyzing the site (READY is the only render-ready state).
  if ((isLoading && !brandProfile) || (brandProfile && brandProfile.analysisStatus !== 'READY' && brandProfile.analysisStatus !== 'ERROR')) {
    return <StageLoadingState label="Reading your site…" className={className} />
  }

  if (error && !brandProfile) {
    return (
      <div
        className={cn(
          'rounded-xl border border-meera-border bg-meera-surface-2 p-6 text-center text-sm text-meera-text-muted',
          className,
        )}
      >
        {error}
      </div>
    )
  }

  if (!brandProfile) {
    return <StageLoadingState label="Reading your site…" className={className} />
  }

  if (brandProfile.analysisStatus === 'ERROR') {
    return (
      <div
        className={cn(
          'rounded-xl border border-meera-danger/30 bg-meera-danger/5 p-6 text-center text-sm text-meera-danger',
          className,
        )}
        role="alert"
      >
        {brandProfile.analysisError || "Couldn't analyze your site."}
      </div>
    )
  }

  const products = extractCatalogProducts(brandProfile.productCatalog)

  return (
    <div className={cn('space-y-4', className)}>
      <div className="flex items-center gap-3 rounded-xl border border-meera-border bg-meera-surface p-4">
        <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-meera-surface-2 text-sm font-bold text-meera-text">
          {siteInitials(brandProfile.websiteUrl)}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-meera-text">
            {brandProfile.websiteUrl || 'No site on file'}
          </p>
          {brandProfile.nicheTags && brandProfile.nicheTags.length > 0 && (
            <p className="truncate text-xs text-meera-text-muted">{brandProfile.nicheTags.join(' · ')}</p>
          )}
        </div>
      </div>

      {products.length > 0 ? (
        <StaggerContainer className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          {products.map((product) => (
            <StaggerItem key={product.name}>
              <div className="rounded-lg border border-meera-border bg-meera-surface-2 p-3 text-center">
                <span className="text-2xl" aria-hidden="true">🛍️</span>
                <p className="mt-1 truncate text-xs font-medium text-meera-text">{product.name}</p>
                <p className="text-xs tabular-nums text-meera-text-muted">{formatINR(product.price)}</p>
              </div>
            </StaggerItem>
          ))}
        </StaggerContainer>
      ) : (
        <p className="rounded-lg border border-dashed border-meera-border bg-meera-surface-2 p-4 text-center text-xs text-meera-text-muted">
          No products detected on this site yet.
        </p>
      )}
    </div>
  )
}
