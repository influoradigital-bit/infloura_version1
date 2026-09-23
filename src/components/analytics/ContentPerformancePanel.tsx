import { useState } from 'react';
import { AlertTriangle, ExternalLink, Image as ImageIcon } from 'lucide-react';

import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyHeader, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import type { ContentPerformanceItem } from '@/lib/api';

interface ContentPerformancePanelProps {
  data: ContentPerformanceItem[] | null;
  loading?: boolean;
  error?: string | null;
  notImplemented?: boolean;
  className?: string;
  /** CR-67 — lets the error state offer a retry instead of requiring a full page reload. */
  onRetry?: () => void;
}

/**
 * `n` is `number | null | undefined` because the wire DTO omits `reach`/
 * `impressions` entirely when Meta didn't report them (`@JsonInclude(NON_NULL)`
 * on `AnalyticsDtos.ContentPerformanceResponse`) rather than sending JSON
 * `null` — so both the explicit-null and omitted-key cases land here and
 * must render the same "no data" fallback instead of the literal text
 * "undefined" (Priya's brand-fixes review, fix #4).
 */
function formatCompact(n: number | null | undefined): string {
  if (n == null) return '—';
  // Indian scale (K -> L -> Cr), matching formatNumber on brand-creator-profile.tsx — the
  // same brands read both pages. The previous K-only form rendered 12,345,678 views as
  // "12345.7K". Deliberately NOT src/lib/scroll/format-compact-number.ts: that one mixes
  // systems (999,999 -> "10.0L" but 1,000,000 -> "1.0M").
  if (n >= 10_000_000) return `${(n / 10_000_000).toFixed(1)}Cr`;
  if (n >= 100_000) return `${(n / 100_000).toFixed(1)}L`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

const POST_COUNTS = [
  { key: 'likes', label: 'Likes' },
  { key: 'comments', label: 'Comments' },
  { key: 'saves', label: 'Saves' },
  { key: 'shares', label: 'Shares' },
] as const satisfies ReadonlyArray<{ key: keyof ContentPerformanceItem; label: string }>;

/**
 * F-1784: Meta's media_type/media_product_type enums are never shown raw. An
 * enum we have not mapped reads "Post" rather than leaking e.g. "CAROUSEL_ALBUM".
 */
const MEDIA_TYPE_LABELS: Record<string, string> = {
  VIDEO: 'Video',
  CAROUSEL_ALBUM: 'Carousel',
  IMAGE: 'Photo',
  REELS: 'Reel',
  REEL: 'Reel',
  STORY: 'Story',
};

function humaniseMediaType(mediaType: string | null | undefined): string {
  if (!mediaType) return 'Post';
  return MEDIA_TYPE_LABELS[mediaType.toUpperCase()] ?? 'Post';
}

/**
 * F-1786: en-IN "30 Mar 2026". A missing or unparseable postedAt renders "—",
 * never "Invalid Date".
 */
function formatPostedAt(postedAt: string | null | undefined): string {
  if (!postedAt) return '—';
  const d = new Date(postedAt);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

/**
 * F-1784: `permalink` is data from Meta, stored server-side and passed through
 * untouched — never put it in an href unchecked (a `javascript:` URL would
 * execute on click). Only an https URL on instagram.com (or a subdomain of it),
 * with no embedded credentials, becomes a link; anything else renders no link.
 */
function safeInstagramUrl(permalink: string | null | undefined): string | null {
  if (!permalink) return null;
  let url: URL;
  try {
    url = new URL(permalink);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:') return null;
  if (url.username || url.password) return null;
  const host = url.hostname.toLowerCase();
  if (host !== 'instagram.com' && !host.endsWith('.instagram.com')) return null;
  return url.href;
}

const PREVIEW_IMAGE_HOSTS = ['cdninstagram.com', 'fbcdn.net'] as const;

/**
 * `previewImageUrl` is a Meta CDN link passed through by the backend, which
 * already allow-lists the host (MediaMetricMapper). Checked again here as
 * defence in depth: only an https URL on cdninstagram.com / fbcdn.net (or a
 * subdomain), with no embedded credentials, is rendered; anything else shows
 * the placeholder icon.
 */
function safePreviewImageUrl(raw: string | null | undefined): string | null {
  if (!raw) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:') return null;
  if (url.username || url.password) return null;
  const host = url.hostname.toLowerCase();
  const allowed = PREVIEW_IMAGE_HOSTS.some((h) => host === h || host.endsWith(`.${h}`));
  return allowed ? url.href : null;
}

function PostThumbnailPlaceholder() {
  return (
    <div
      className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-muted"
      data-testid="post-thumbnail-placeholder"
    >
      <ImageIcon className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
    </div>
  );
}

/**
 * The post's cover image, same 40px box as the placeholder. The CDN links are
 * signed and expire in ~4 days, so a creator whose polling stopped has dead
 * links: onError swaps to the placeholder and the state keeps it swapped
 * across re-renders (no broken-image icon). Keyed by URL at the call site, so a
 * fresh link after the next poll gets a fresh attempt. alt="" because the row
 * title already names the post; no-referrer so the CDN is not told which of our
 * pages the viewer was on.
 */
function PostThumbnail({ src }: { src: string | null }) {
  const [failed, setFailed] = useState(false);
  if (!src || failed) return <PostThumbnailPlaceholder />;
  return (
    <img
      src={src}
      alt=""
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      width={40}
      height={40}
      onError={() => setFailed(true)}
      className="h-10 w-10 shrink-0 rounded-md bg-muted object-cover"
      data-testid="post-thumbnail"
    />
  );
}

/** F-1785: per-post rate is a different formula from the profile rate — say so. */
const ENG_PER_REACH_EXPLANATION =
  "Eng./reach is a post's interactions ÷ that post's reach. It is not the profile " +
  'engagement rate, which is likes + comments ÷ followers.';

/**
 * Content-performance panel — Wave B task B5. There is no brand-facing
 * per-post media-metrics endpoint on the backend today (checked
 * AnalyticsController directly: only /metrics, /scores, /demographics
 * exist) — see the gap note above `contentPerformance` in src/lib/api.ts.
 *
 * Built against the proposed shape, same "API not yet available" amber
 * banner convention already established by CreatorCouponsPage (A4) for an
 * identical honest-gap situation, rather than a plain "coming soon" stub —
 * this reflects that the underlying data (MediaMetric rows) already exists
 * server-side from B1, it's only the brand-facing read surface that's
 * missing.
 */
export function ContentPerformancePanel({
  data,
  loading = false,
  error,
  notImplemented = false,
  className,
  onRetry,
}: ContentPerformancePanelProps) {
  if (loading) {
    return (
      <Card className={className}>
        <CardHeader className="pb-2">
          <Skeleton className="h-4 w-40" />
        </CardHeader>
        <CardContent className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-14 w-full" />
          ))}
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          Content Performance
        </CardTitle>
      </CardHeader>
      <CardContent>
        {notImplemented && (
          <Alert className="mb-4 border-amber-300 bg-amber-50">
            <AlertTriangle className="h-4 w-4 text-amber-700" />
            <AlertTitle className="text-amber-900">API not yet available</AlertTitle>
            <AlertDescription className="text-amber-800">
              The backend endpoint that lists a creator's per-post performance
              (<code className="rounded bg-amber-100 px-1 py-0.5 font-mono text-xs">
                GET /analytics/creators/{'{id}'}/media
              </code>
              ) has not been built yet — per-post metrics are already persisted server-side
              (B1's media polling), but there's no brand-facing read surface for them yet. This
              panel is a UI shell so the design can be reviewed early; it will light up once that
              endpoint ships.
            </AlertDescription>
          </Alert>
        )}

        {!notImplemented && error && (
          <Alert className="mb-4" variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>Couldn't load content performance</AlertTitle>
            <AlertDescription>
              {error}
              {onRetry && (
                <div className="mt-2">
                  <Button size="sm" variant="outline" onClick={onRetry}>
                    Retry
                  </Button>
                </div>
              )}
            </AlertDescription>
          </Alert>
        )}

        {!error && data && data.length === 0 && (
          <Empty className="border-0 p-0">
            <EmptyHeader>
              <ImageIcon className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
              <EmptyTitle>No posts yet</EmptyTitle>
              <EmptyDescription>
                Per-post performance will appear once media metrics have been polled.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        )}

        {data && data.length > 0 && (
          <>
          <div className="space-y-2" role="list" aria-label="Per-post content performance">
            {data.map((item) => {
              const typeLabel = humaniseMediaType(item.mediaType);
              // No caption: the API never sends one (ADR 2026-07-06, brand-safety input only),
              // so the row is titled by its media type and dated underneath.
              const title = typeLabel;
              const href = safeInstagramUrl(item.permalink);
              const postedLabel = formatPostedAt(item.postedAt);
              const previewSrc = safePreviewImageUrl(item.previewImageUrl);
              return (
              <div
                key={item.mediaId}
                role="listitem"
                // F-1783: below sm the row stacks (post info on top with the full width,
                // stats in a 4-column grid underneath). From sm up it is the single-line
                // layout. The old unconditional `shrink-0` on the stats block is why its
                // flex-wrap never wrapped and it overhung a 375px card by 90px.
                className="relative flex flex-col gap-3 rounded-lg border border-border p-3 sm:flex-row sm:items-center sm:justify-between sm:gap-4"
              >
                <div className="flex min-w-0 items-center gap-3 sm:flex-1">
                  <PostThumbnail key={previewSrc ?? 'none'} src={previewSrc} />
                  <div className="min-w-0 flex-1">
                    <div className="flex min-w-0 items-center gap-1">
                      {href ? (
                        // Stretched link: the ::after box covers the whole row, so the row
                        // opens the post, while the link keeps one accessible name.
                        <a
                          href={href}
                          target="_blank"
                          rel="noopener noreferrer"
                          aria-label={`${title} — open on Instagram in a new tab`}
                          className="min-w-0 truncate text-sm font-medium hover:underline focus-visible:outline-none after:absolute after:inset-0 after:rounded-lg focus-visible:after:ring-2 focus-visible:after:ring-ring"
                        >
                          {title}
                        </a>
                      ) : (
                        <p className="min-w-0 truncate text-sm font-medium">
                          {title}
                        </p>
                      )}
                      {href && (
                        <ExternalLink
                          className="h-3.5 w-3.5 shrink-0 text-muted-foreground"
                          aria-hidden="true"
                          data-testid="post-external-link-icon"
                        />
                      )}
                    </div>
                    <p className="truncate text-xs text-muted-foreground">
                      {postedLabel}
                    </p>
                  </div>
                </div>
                <div className="grid grid-cols-4 gap-x-2 gap-y-3 text-sm sm:flex sm:shrink-0 sm:items-center sm:justify-end sm:gap-4">
                  {/* F-0952: likes/comments/saves/shares were sent by the API and never shown.
                      A value Meta did not report renders as "—", never as 0. There is no
                      separate views column: MediaMetricMapper stores Meta's `views` count in
                      `impressions` and leaves the retired `video_views` null on purpose. */}
                  {POST_COUNTS.map(({ key, label }) => (
                    <div key={key} className="min-w-0 sm:text-right">
                      <p className="font-medium">{formatCompact(item[key])}</p>
                      <p className="text-xs text-muted-foreground">{label}</p>
                    </div>
                  ))}
                  <div className="min-w-0 sm:text-right">
                    <p className="font-medium">{formatCompact(item.reach)}</p>
                    <p className="text-xs text-muted-foreground">Reach</p>
                  </div>
                  <div className="min-w-0 sm:text-right">
                    <p className="font-medium">{formatCompact(item.impressions)}</p>
                    <p className="text-xs text-muted-foreground">Views</p>
                  </div>
                  <div className="min-w-0 sm:text-right">
                    {/* F-1785: no trend arrow — nothing computes a trend for a post. Loose
                        null check: an omitted (NON_NULL) key arrives as `undefined`, not
                        `null` — `!== null` never caught that case (Priya's brand-fixes
                        review, fix #4). */}
                    <p className="font-medium">
                      {item.engagementRate != null ? `${item.engagementRate}%` : '—'}
                    </p>
                    <p className="text-xs text-muted-foreground" title={ENG_PER_REACH_EXPLANATION}>
                      Eng./reach
                    </p>
                  </div>
                </div>
              </div>
              );
            })}
          </div>
          <p className="mt-3 text-xs text-muted-foreground" data-testid="eng-per-reach-note">
            {ENG_PER_REACH_EXPLANATION}
          </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}

export default ContentPerformancePanel;
