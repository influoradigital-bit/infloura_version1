import * as React from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  ArrowUpRight,
  Award,
  BadgeCheck,
  Briefcase,
  CheckCircle2,
  Clock,
  GraduationCap,
  Heart,
  Image as ImageIcon,
  Instagram,
  Loader2,
  Mail,
  MapPin,
  Play,
  RefreshCw,
  Send,
  Share2,
  ShoppingCart,
  Sparkles,
  Star,
  Trophy,
  UserPlus,
  Youtube,
  Globe,
  AlertCircle,
} from 'lucide-react';

import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

import { cn, formatINR, publicProfileLabel } from '@/lib/utils';
import {
  api,
  ApiError,
  type PortfolioPage,
  type PortfolioBadge,
  type PortfolioCustomLink,
  type PortfolioPlatformStats,
} from '@/lib/api';
import { toast } from '@/hooks/use-toast';

/**
 * Public Creator Portfolio Page  —  <origin>/@:username (influora.in in prod)
 * Spec: docs/CREATOR-PORTFOLIO-PAGE.md
 *
 * No auth required. Indexable. Mobile-first.
 *
 * v1 sections shipped:
 *   Hero · Trust Bar · Badges · Platform Stats · Past Collabs
 *   · Content Portfolio · Custom Links · Rate Card (optional)
 *   · Languages & Audience · Contact CTA
 *
 * Deferred to v2: layout themes, accent colours, custom domains.
 */

/** Outcome of the last "Share page" press. `failed` is sticky on purpose. */
type ShareState = 'idle' | 'copied' | 'failed';

/**
 * Copies `text` to the clipboard without assuming a secure context.
 *
 * CR-01: Influora is currently served over plain HTTP on a bare IP, where
 * `navigator.share` AND `navigator.clipboard` are both `undefined` — they are
 * secure-context-only APIs. The old code called `navigator.clipboard.writeText`
 * unguarded, threw a TypeError on `undefined`, and swallowed it in an empty
 * catch, so Share page did nothing at all with no error and no feedback.
 *
 * Until HTTPS lands (CR-15, blocked on a domain decision) we fall back to the
 * legacy `document.execCommand('copy')` path, which carries no secure-context
 * requirement. Returns whether the copy ACTUALLY happened — callers must not
 * assume success, because on some locked-down browsers neither path works and
 * the visible URL field is the only remaining route.
 */
async function copyTextToClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (err) {
      // Permission denied, or an insecure-origin rejection that slipped past the
      // feature check. Log it, then try the legacy path rather than giving up.
      console.warn('navigator.clipboard.writeText failed, trying execCommand', err);
    }
  }

  // execCommand('copy') acts on the current selection, so the source node has to
  // be in the document and actually selectable — hence off-screen positioning
  // rather than `display:none`/`hidden`, which cannot hold a selection.
  //
  // KNOWN LIMITATION (iOS Safari): `readonly` + `select()` does not establish a
  // copyable selection there — iOS needs a contentEditable node plus a Range. We
  // deliberately do NOT special-case it: iOS is a secure-context-only browser for
  // our purposes and gets `navigator.clipboard` the moment HTTPS lands (CR-15),
  // which makes the whole branch moot. Until then iOS falls through to `false`
  // and lands on the always-visible readonly input below, which is why that input
  // is not optional.
  const previousActive = document.activeElement as HTMLElement | null;
  const previousSelection = window.getSelection();
  const previousRange =
    previousSelection && previousSelection.rangeCount > 0
      ? previousSelection.getRangeAt(0).cloneRange()
      : null;

  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.top = '0';
  textarea.style.left = '-9999px';
  document.body.appendChild(textarea);
  try {
    // focus() before select() is not optional: Chrome happens to focus implicitly
    // but that is not spec-guaranteed, and without focus execCommand copies
    // whatever the document selection already was — i.e. silently copies the
    // wrong thing rather than failing.
    textarea.focus();
    textarea.select();
    textarea.setSelectionRange(0, text.length);
    return document.execCommand('copy');
  } catch (err) {
    console.error('execCommand copy fallback failed', err);
    return false;
  } finally {
    document.body.removeChild(textarea);
    // Hijacking the selection and focus is a side effect of the copy, not an
    // intended outcome — put the visitor's back where it was.
    if (previousRange && previousSelection) {
      previousSelection.removeAllRanges();
      previousSelection.addRange(previousRange);
    }
    previousActive?.focus?.();
  }
}

export default function CreatorPortfolioPublicPage() {
  // The route captures the whole first path segment (e.g. "@priyacreates").
  // A valid portfolio handle MUST start with "@" — otherwise this is some other
  // unknown single-segment URL and we render the not-found state.
  const { handle = '' } = useParams<{ handle: string }>();
  const username = handle.startsWith('@') ? handle.slice(1) : '';

  const [page, setPage] = React.useState<PortfolioPage | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [contactOpen, setContactOpen] = React.useState(false);
  const [shareState, setShareState] = React.useState<ShareState>('idle');
  const shareInputRef = React.useRef<HTMLInputElement>(null);

  const shareUrl = `${window.location.origin}/@${username}`;

  const sharePage = React.useCallback(async () => {
    const title = page ? `${page.displayName} on Influora` : 'Influora creator';
    // Native share sheet first where it exists (mobile, secure context only).
    if (navigator.share) {
      try {
        await navigator.share({ title, url: shareUrl });
        setShareState('idle');
        return;
      } catch (err) {
        // An AbortError is the user closing the sheet on purpose — expected, not a
        // fault. Anything else is a real failure worth a console line. Either way
        // we fall through to the copy path, but nothing is swallowed silently.
        if (!(err instanceof DOMException && err.name === 'AbortError')) {
          console.warn('navigator.share failed, falling back to clipboard copy', err);
        }
      }
    }

    const copied = await copyTextToClipboard(shareUrl);
    if (copied) {
      setShareState('copied');
      toast({ title: 'Link copied', description: shareUrl });
      window.setTimeout(() => setShareState('idle'), 2500);
      return;
    }
    // No auto-reset here: if copying is impossible the creator needs the
    // manual-select instruction to stay on screen until they've got the link.
    setShareState('failed');
    toast({
      title: 'Could not copy automatically',
      description: 'Your browser blocked it. Select the link below and copy it manually.',
      variant: 'destructive',
    });
    shareInputRef.current?.select();
  }, [shareUrl, page]);

  React.useEffect(() => {
    let cancelled = false;
    if (!username) {
      setLoading(false);
      setError('Page not found');
      return;
    }
    setLoading(true);
    setError(null);
    api.portfolio
      .getPublic(username)
      .then((data) => {
        if (!cancelled) setPage(data as PortfolioPage);
      })
      .catch((err: unknown) => {
        // Same no-silent-failures standard as sharePage: the visitor gets the
        // friendly line, we keep the real reason in the console.
        console.error('Failed to load public portfolio', err);
        if (!cancelled) setError('We could not find this creator.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [username]);

  // Set document title + meta for SEO (also re-rendered server-side in prod)
  React.useEffect(() => {
    if (!page) return;
    const title = `${page.displayName} — ${page.niches.join(' & ')} Creator${page.city ? ' | ' + page.city : ''} | Influora`;
    document.title = title;

    const metaDesc = document.querySelector("meta[name='description']") ||
      Object.assign(document.createElement('meta'), { name: 'description' });
    metaDesc.setAttribute(
      'content',
      `${page.displayName} is a ${page.verified ? 'verified ' : ''}${page.niches[0]?.toLowerCase() || 'content'} creator${page.city ? ' based in ' + page.city : ''} with ${formatFollowers(totalFollowers(page))} followers and ${page.stats.totalCollabs} brand collaborations.`,
    );
    if (!metaDesc.parentElement) document.head.appendChild(metaDesc);
  }, [page]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (error || !page) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background px-6">
        <div className="text-center max-w-sm">
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-muted">
            <AlertCircle className="h-6 w-6 text-muted-foreground" />
          </div>
          <h1 className="text-lg font-semibold">{error || 'Page not found'}</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            The handle <span className="font-medium">@{username}</span> doesn't seem to belong to anyone on Influora.
          </p>
          <Button asChild className="mt-4" variant="outline">
            <Link to="/">Go to Influora</Link>
          </Button>
        </div>
      </div>
    );
  }

  const totalRepeat = page.stats.repeatBrands;

  // Brand-acquisition deep-link: if not logged in, brand-login then back here with intent
  const inviteUrl = `/brand/login?inviteCreator=${encodeURIComponent(page.username)}`;

  return (
    <div className="min-h-screen bg-background">
      {/* Top utility bar — always reminds the visitor this is an Influora page */}
      <div className="border-b border-border bg-card">
        <div className="mx-auto max-w-3xl px-4 py-2.5 flex items-center justify-between">
          <Link to="/" className="flex items-center gap-2 text-xs text-muted-foreground hover:text-foreground transition-colors">
            <div className="h-5 w-5 rounded-md bg-primary flex items-center justify-center">
              <span className="text-[9px] font-bold text-primary-foreground">IN</span>
            </div>
            <span className="font-medium">Influora</span>
          </Link>
          <Button asChild size="sm" variant="ghost" className="h-7 text-xs">
            <Link to={inviteUrl}>For brands · Sign in</Link>
          </Button>
        </div>
      </div>

      <main className="mx-auto max-w-3xl pb-16">
        {/* ============================================================
            SECTION 1 — HERO
        ============================================================ */}
        <section className="relative">
          {/* Hero cover — soft branded gradient with decorative glow (pure CSS, no WebGL) */}
          <div
            className={cn(
              'relative h-36 sm:h-52 w-full overflow-hidden',
              !page.coverUrl && 'bg-gradient-to-br from-primary/25 via-accent/40 to-primary/10',
            )}
            style={
              page.coverUrl
                ? { backgroundImage: `url(${page.coverUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' }
                : undefined
            }
          >
            {!page.coverUrl && (
              <div aria-hidden className="pointer-events-none absolute inset-0">
                <div className="absolute -top-16 -left-12 h-56 w-56 rounded-full bg-primary/30 blur-3xl" />
                <div className="absolute -bottom-24 right-0 h-64 w-64 rounded-full bg-accent/50 blur-3xl" />
                <div className="absolute inset-0 bg-[radial-gradient(120%_80%_at_20%_0%,rgba(255,255,255,0.35),transparent_55%)]" />
              </div>
            )}
          </div>

          <div className="px-4 sm:px-6 -mt-12 sm:-mt-16">
            <Avatar className="h-24 w-24 sm:h-32 sm:w-32 ring-4 ring-background shadow-md">
              <AvatarImage src={page.avatarUrl} alt={page.displayName} />
              <AvatarFallback className="text-2xl bg-primary/10 text-primary font-semibold">
                {initials(page.displayName)}
              </AvatarFallback>
            </Avatar>

            <div className="mt-3">
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <h1 className="text-2xl sm:text-3xl font-bold tracking-tight">{page.displayName}</h1>
                {page.verified && (
                  <span className="inline-flex items-center gap-1 rounded-full bg-blue-100 text-blue-700 px-2 py-0.5 text-xs font-medium">
                    <BadgeCheck className="h-3.5 w-3.5" /> Verified
                  </span>
                )}
              </div>

              <p className="mt-1 text-sm text-muted-foreground flex flex-wrap items-center gap-1.5">
                <span>{page.niches.join(' · ')}</span>
                {page.city && (
                  <>
                    <span>·</span>
                    <span className="inline-flex items-center gap-1">
                      <MapPin className="h-3 w-3" /> {page.city}
                    </span>
                  </>
                )}
              </p>

              {page.bio && (
                <p className="mt-3 text-sm leading-relaxed max-w-xl">{page.bio}</p>
              )}

              {/* CR-119 — was "live, OAuth-verified numbers", which is false: only Instagram has
                  an OAuth integration at all, so a YouTube/TikTok/X pill here carries a
                  creator-reported figure. Each pill states its own provenance. This block also
                  renders when `visibility.platformStats` is off, in which case these pills are
                  the ONLY per-platform surface on the page — so provenance cannot live solely in
                  the PlatformStatCard grid below. */}
              <div className="mt-4 flex flex-wrap gap-2">
                {page.platforms.map((p) => (
                  <PlatformPill key={p.platform} stats={p} />
                ))}
              </div>

              {/* Primary CTAs */}
              <div className="mt-5 flex flex-wrap gap-2.5">
                <Button asChild size="lg" className="gap-2">
                  <Link to={inviteUrl}>
                    <UserPlus className="h-4 w-4" />
                    Invite to Campaign
                  </Link>
                </Button>
                <Button size="lg" variant="outline" className="gap-2" onClick={sharePage}>
                  {shareState === 'copied' ? (
                    <>
                      <CheckCircle2 className="h-4 w-4 text-success" />
                      Link copied
                    </>
                  ) : (
                    <>
                      <Share2 className="h-4 w-4" />
                      Share page
                    </>
                  )}
                </Button>
              </div>

              {/* CR-01 — the actual guarantee. Web Share and Clipboard are both
                  secure-context-only and this app is still on plain HTTP, so no
                  programmatic copy path can be relied on. The link is therefore
                  ALWAYS on screen, readonly and selectable: even when every copy
                  mechanism fails the visitor can select it by hand and paste it.
                  Keep this even after HTTPS (CR-15) — it costs one row and it is
                  the only thing here that cannot break.

                  Copy is written for an ANONYMOUS VISITOR, not the creator: this
                  is the public page, and the CTA directly above it is "Invite to
                  Campaign". Second-person "your portfolio" would be addressing the
                  wrong reader on the page creators send to brands. */}
              <div className="mt-3 max-w-md">
                <Label htmlFor="portfolio-share-url" className="sr-only">
                  Link to this portfolio
                </Label>
                <Input
                  id="portfolio-share-url"
                  ref={shareInputRef}
                  readOnly
                  value={shareUrl}
                  onFocus={(e) => e.currentTarget.select()}
                  onClick={(e) => e.currentTarget.select()}
                  className="h-9 font-mono text-xs"
                />
                {/* Deliberately NOT a live region. The Radix toast is the announcer
                    and it fires on both the copied and the failed path, so putting
                    role="status"/aria-live here would duplicate it — and on the idle
                    default it made some screen readers announce boilerplate on page
                    load. This text keeps its visual and navigational value (a screen
                    reader user who tabs to the input reads it on demand via the
                    label + adjacent text); it just stops competing for the
                    announcement. One announcement per outcome. */}
                <p
                  className={cn(
                    'mt-1.5 text-[11px]',
                    shareState === 'failed' ? 'text-destructive' : 'text-muted-foreground',
                  )}
                >
                  {shareState === 'copied'
                    ? 'Copied to your clipboard.'
                    : shareState === 'failed'
                      ? 'Your browser blocked automatic copying — tap the link above to select it, then copy.'
                      : 'Anyone with this link can view this portfolio.'}
                </p>
              </div>
            </div>
          </div>
        </section>

        {/* ============================================================
            SECTION 2 — TRUST SIGNALS BAR
            (hidden if visibility off OR creator has <3 collabs and chose to hide)
        ============================================================ */}
        {page.visibility.trustBar && page.stats.totalCollabs >= 1 && (
          <section className="px-4 sm:px-6 mt-8">
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <TrustStat value={String(page.stats.totalCollabs)} label="Brand Collabs" />
              <TrustStat
                value={
                  <span className="inline-flex items-center gap-1">
                    {page.stats.avgRating != null ? page.stats.avgRating.toFixed(1) : '—'}
                    <Star className="h-4 w-4 fill-amber-400 text-amber-400" />
                  </span>
                }
                label="Avg Brand Rating"
              />
              <TrustStat value={`${page.stats.onTimeRate}%`} label="On-Time Delivery" />
              <TrustStat value={String(totalRepeat)} label="Repeat Brands" />
            </div>
            <p className="mt-2 text-[11px] text-muted-foreground flex items-center gap-1 justify-center">
              <BadgeCheck className="h-3 w-3 text-blue-500" />
              Verified by Influora — pulled live from real campaign data
            </p>
          </section>
        )}

        {/* ============================================================
            SECTION 3 — BADGES
        ============================================================ */}
        {page.visibility.badges && page.badges.length > 0 && (
          <section className="px-4 sm:px-6 mt-8">
            <SectionHeading title="Achievements" />
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
              {page.badges.map((b) => (
                <BadgeCard key={b} badge={b} />
              ))}
            </div>
          </section>
        )}

        {/* ============================================================
            SECTION 4 — PLATFORM STATS (VERIFIED)
        ============================================================ */}
        {page.visibility.platformStats && page.platforms.length > 0 && (
          <section className="px-4 sm:px-6 mt-8">
            <SectionHeading title="Platform Stats" />
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {page.platforms.map((p) => (
                <PlatformStatCard key={p.platform} stats={p} />
              ))}
            </div>
            <p className="mt-2 text-[11px] text-muted-foreground">
              Numbers synced directly from each platform's API. Updated daily.
            </p>
          </section>
        )}

        {/* ============================================================
            SECTION 5 — PAST BRAND COLLABORATIONS
        ============================================================ */}
        {page.visibility.pastCollabs && visibleCollabs(page).length > 0 && (
          <section className="px-4 sm:px-6 mt-8">
            <SectionHeading title="Worked With" subtitle={`${page.stats.totalCollabs} brand collaborations`} />
            <CollabsSection collabs={visibleCollabs(page)} />
          </section>
        )}

        {/* ============================================================
            SECTION 6 — CONTENT PORTFOLIO
        ============================================================ */}
        {page.visibility.contentPortfolio && page.pinnedPosts.length > 0 && (
          <section className="px-4 sm:px-6 mt-8">
            <SectionHeading title="Content Highlights" />
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
              {page.pinnedPosts.map((post) => (
                <PinnedPostCard key={post.id} post={post} />
              ))}
            </div>
          </section>
        )}

        {/* ============================================================
            SECTION 7 — CUSTOM LINKS
        ============================================================ */}
        {page.visibility.customLinks && page.customLinks.length > 0 && (
          <section className="px-4 sm:px-6 mt-8">
            <SectionHeading title="Links" />
            <div className="space-y-2">
              {page.customLinks.map((link) => (
                <CustomLinkRow key={link.id} link={link} />
              ))}
            </div>
          </section>
        )}

        {/* ============================================================
            SECTION 8 — RATE CARD (optional)
        ============================================================ */}
        {page.visibility.rateCard === 'public' && page.rateCard.length > 0 && (
          <section className="px-4 sm:px-6 mt-8">
            <SectionHeading title="Collaboration Rates" />
            <Card>
              <CardContent className="p-4 space-y-2">
                {page.rateCard.map((row) => (
                  <div key={row.id} className="flex items-center justify-between py-1.5 border-b border-border last:border-0 last:pb-0 first:pt-0">
                    <span className="text-sm">{row.label}</span>
                    <span className="text-sm font-medium">
                      {formatINR(row.min)} – {formatINR(row.max)}
                    </span>
                  </div>
                ))}
                <p className="pt-2 text-[11px] text-muted-foreground">
                  Rates in INR · Exclude GST · Negotiable
                </p>
              </CardContent>
            </Card>
          </section>
        )}
        {page.visibility.rateCard === 'brands_only' && (
          <section className="px-4 sm:px-6 mt-8">
            <Card className="border-dashed">
              <CardContent className="p-4 flex items-center justify-between gap-3">
                <div>
                  <p className="text-sm font-medium">Rates visible to brands only</p>
                  <p className="text-xs text-muted-foreground">Sign in as a brand to view this creator's rate card.</p>
                </div>
                <Button asChild size="sm" variant="outline">
                  <Link to={inviteUrl}>Sign in</Link>
                </Button>
              </CardContent>
            </Card>
          </section>
        )}

        {/* ============================================================
            SECTION 9 — LANGUAGES & AUDIENCE
        ============================================================ */}
        {page.visibility.languages && (page.languages.length > 0 || page.topAudienceCities.length > 0) && (
          <section className="px-4 sm:px-6 mt-8">
            {page.languages.length > 0 && (
              <div>
                <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2">
                  Languages
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {page.languages.map((l) => (
                    <Badge key={l} variant="secondary" className="rounded-full px-3 py-0.5">
                      {l}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
            {page.topAudienceCities.length > 0 && (
              <div className="mt-4">
                <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2">
                  Top Audience Cities
                </p>
                <p className="text-sm">{page.topAudienceCities.join(' · ')}</p>
              </div>
            )}
          </section>
        )}

        {/* ============================================================
            SECTION 10 — CONTACT CTA
        ============================================================ */}
        <section className="px-4 sm:px-6 mt-10">
          <Card className="bg-gradient-to-br from-primary/5 via-accent/40 to-primary/5 border-primary/20">
            <CardContent className="p-6 sm:p-8 text-center">
              <h2 className="text-xl sm:text-2xl font-semibold">
                Ready to collaborate with {firstName(page.displayName)}?
              </h2>
              <p className="mt-2 text-sm text-muted-foreground max-w-md mx-auto">
                Brands using Influora get verified deliverables, escrow protection, and a built-in deal room.
              </p>
              <div className="mt-5 flex flex-col sm:flex-row gap-2.5 justify-center">
                <Button asChild size="lg" className="gap-2">
                  <Link to={inviteUrl}>
                    <UserPlus className="h-4 w-4" />
                    Invite to Campaign
                  </Link>
                </Button>
                {page.visibility.contactForm && (
                  <Button size="lg" variant="outline" className="gap-2" onClick={() => setContactOpen(true)}>
                    <Send className="h-4 w-4" />
                    Send a Message
                  </Button>
                )}
              </div>
              <p className="mt-4 text-[11px] text-muted-foreground">
                For brands already on Influora · No login? Sign up free and invite from there.
              </p>
            </CardContent>
          </Card>
        </section>

        {/* Footer */}
        <footer className="px-4 sm:px-6 mt-12 pt-6 border-t border-border text-center">
          <p className="text-xs text-muted-foreground">
            This portfolio is powered by{' '}
            <Link to="/" className="text-primary hover:underline font-medium">
              Influora
            </Link>{' '}
            — verified creator profiles for brands.
          </p>
          <p className="mt-1 text-[10px] text-muted-foreground">
            {publicProfileLabel(page.username)}
          </p>
        </footer>
      </main>

      {/* Contact dialog */}
      {page.visibility.contactForm && (
        <ContactDialog
          open={contactOpen}
          onOpenChange={setContactOpen}
          username={page.username}
          displayName={page.displayName}
        />
      )}
    </div>
  );
}

// ===========================================================================
// Sub-components
// ===========================================================================

function SectionHeading({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div className="mb-3">
      <h2 className="text-base sm:text-lg font-semibold">{title}</h2>
      {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
    </div>
  );
}

function TrustStat({ value, label }: { value: React.ReactNode; label: string }) {
  return (
    <Card className="border-border">
      <CardContent className="p-3 sm:p-4 text-center">
        <p className="text-xl sm:text-2xl font-bold leading-tight">{value}</p>
        <p className="mt-0.5 text-[11px] sm:text-xs text-muted-foreground">{label}</p>
      </CardContent>
    </Card>
  );
}

const BADGE_META: Record<PortfolioBadge, { label: string; sub: string; icon: React.ComponentType<{ className?: string }>; color: string }> = {
  top_creator:    { label: 'Top Creator',     sub: 'Top 5% engagement',   icon: Trophy,    color: 'text-amber-600 bg-amber-50' },
  fast_responder: { label: 'Fast Responder',  sub: '<2hr reply time',     icon: Clock,     color: 'text-emerald-600 bg-emerald-50' },
  on_time:        { label: 'On-Time Delivery', sub: '95%+ on time',       icon: CheckCircle2, color: 'text-blue-600 bg-blue-50' },
  brand_favorite: { label: 'Brand Favorite',  sub: '10+ repeat brands',   icon: Heart,     color: 'text-rose-600 bg-rose-50' },
  rising_star:    { label: 'Rising Star',     sub: 'New + 5 deals done',  icon: Sparkles,  color: 'text-violet-600 bg-violet-50' },
  premium:        { label: 'Premium Creator', sub: '50+ deals · 4.5★+',   icon: Award,     color: 'text-purple-600 bg-purple-50' },
};

function BadgeCard({ badge }: { badge: PortfolioBadge }) {
  const meta = BADGE_META[badge];
  const Icon = meta.icon;
  return (
    <Card className="border-border">
      <CardContent className="p-3 flex items-start gap-2.5">
        <div className={cn('h-9 w-9 rounded-lg flex items-center justify-center shrink-0', meta.color)}>
          <Icon className="h-4.5 w-4.5" />
        </div>
        <div className="min-w-0">
          <p className="text-sm font-medium leading-tight">{meta.label}</p>
          <p className="text-[11px] text-muted-foreground mt-0.5">{meta.sub}</p>
        </div>
      </CardContent>
    </Card>
  );
}

function PlatformPill({ stats }: { stats: PortfolioPlatformStats }) {
  const Icon = stats.platform === 'INSTAGRAM' ? Instagram : stats.platform === 'YOUTUBE' ? Youtube : Globe;
  return (
    <a
      href={stats.url}
      target="_blank"
      rel="noreferrer"
      className="group inline-flex items-center gap-2 rounded-full border border-border bg-card px-3 py-1.5 text-xs sm:text-sm hover:bg-muted transition-colors"
    >
      <Icon className="h-3.5 w-3.5" />
      <span className="font-medium">{formatFollowers(stats.followers)}</span>
      <span className="text-muted-foreground">{platformLabel(stats.platform)}</span>
      {/* CR-119 — same two-state wording as PlatformStatCard below. Badge-presence alone left a
          self-reported YouTube pill indistinguishable from a Meta-verified Instagram one. */}
      {stats.verified ? (
        <span className="inline-flex items-center gap-0.5 text-[10px] text-blue-500">
          <BadgeCheck className="h-3 w-3" />
          Followers verified
        </span>
      ) : (
        <span className="text-[10px] text-muted-foreground">Self-reported</span>
      )}
      <ArrowUpRight className="h-3 w-3 opacity-0 group-hover:opacity-100 transition-opacity" />
    </a>
  );
}

function PlatformStatCard({ stats }: { stats: PortfolioPlatformStats }) {
  const Icon = stats.platform === 'INSTAGRAM' ? Instagram : stats.platform === 'YOUTUBE' ? Youtube : Globe;
  const reachLabel = stats.platform === 'YOUTUBE' ? 'Avg Views per Video' : 'Avg Reel Views';
  const followerLabel = stats.platform === 'YOUTUBE' ? 'Subscribers' : 'Followers';
  const syncedLabel = relativeTime(stats.lastSyncedAt);
  return (
    <Card>
      <CardContent className="p-4">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <Icon className="h-4 w-4" />
            <span className="font-medium text-sm">{platformLabel(stats.platform)}</span>
            {/* CR-119 — same two-state provenance wording as brand-creator-profile.tsx. This page
                is publicly linkable and brand-viewable, and badge-presence alone was too quiet a
                way to say "this number is self-reported": a missing mark reads as an oversight,
                not as a claim. YouTube/TikTok/X have no OAuth or data-fetch integration at all, so
                their figures are always creator-reported and must say so. */}
            {stats.verified ? (
              <span className="inline-flex items-center gap-1 text-[11px] text-blue-500">
                <BadgeCheck className="h-3.5 w-3.5" />
                Followers verified
              </span>
            ) : (
              <span className="text-[11px] text-muted-foreground">Self-reported</span>
            )}
          </div>
          <a
            href={stats.url}
            target="_blank"
            rel="noreferrer"
            className="text-xs text-primary hover:underline inline-flex items-center gap-0.5"
          >
            View <ArrowUpRight className="h-3 w-3" />
          </a>
        </div>
        <p className="text-xs text-muted-foreground mb-3">{stats.handle}</p>
        <div className="space-y-2">
          <div className="flex items-baseline justify-between">
            <span className="text-xs text-muted-foreground">{followerLabel}</span>
            <span className="text-sm font-semibold">{formatFollowers(stats.followers)}</span>
          </div>
          <div className="flex items-baseline justify-between">
            <span className="text-xs text-muted-foreground">Engagement Rate</span>
            <span className="text-sm font-semibold">{stats.engagementRate != null ? `${stats.engagementRate.toFixed(1)}%` : '—'}</span>
          </div>
          {typeof stats.avgReach === 'number' && (
            <div className="flex items-baseline justify-between">
              <span className="text-xs text-muted-foreground">{reachLabel}</span>
              <span className="text-sm font-semibold">{formatFollowers(stats.avgReach)}</span>
            </div>
          )}
        </div>
        {/* CR-14 — the whole line is dropped when there is no usable sync
            timestamp; it never falls through to "Synced NaNd ago". */}
        {syncedLabel && (
          <p className="mt-3 text-[10px] text-muted-foreground flex items-center gap-1">
            <RefreshCw className="h-2.5 w-2.5" />
            Synced {syncedLabel}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

function CollabsSection({ collabs }: { collabs: ReturnType<typeof visibleCollabs> }) {
  const [expanded, setExpanded] = React.useState(false);

  return (
    <>
      {/* Default: logo wall */}
      {!expanded && (
        <>
          <div className="flex flex-wrap gap-2.5">
            {collabs.slice(0, 12).map((c) => (
              <div
                key={c.id}
                className="rounded-lg border border-border bg-card px-3 py-2 text-sm font-medium hover:bg-muted transition-colors"
                title={`${c.brandName} — ${c.campaignTitle}`}
              >
                {c.displayMode === 'category' ? 'Fashion Brand' : c.brandName}
              </div>
            ))}
          </div>
          {collabs.length > 0 && (
            <Button
              variant="ghost"
              size="sm"
              className="mt-3 text-xs"
              onClick={() => setExpanded(true)}
            >
              See details ↓
            </Button>
          )}
        </>
      )}

      {/* Expanded: cards with deliverable, rating, optional quote */}
      {expanded && (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {collabs.map((c) => (
              <Card key={c.id}>
                <CardContent className="p-4">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="font-medium text-sm truncate">
                        {c.displayMode === 'category' ? 'Fashion Brand' : c.brandName}
                      </p>
                      <p className="text-xs text-muted-foreground truncate">
                        {c.campaignTitle} · {monthYear(c.completedAt)}
                      </p>
                    </div>
                    {typeof c.rating === 'number' && (
                      <div className="flex items-center gap-0.5 text-amber-500 shrink-0">
                        {Array.from({ length: c.rating }).map((_, i) => (
                          <Star key={i} className="h-3 w-3 fill-current" />
                        ))}
                      </div>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground mt-2">{c.deliverables} · {platformLabel(c.platform)}</p>
                  {c.publicQuote && (
                    <p className="mt-2 text-xs italic text-muted-foreground border-l-2 border-border pl-2">
                      "{c.publicQuote}"
                    </p>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>
          <Button
            variant="ghost"
            size="sm"
            className="mt-3 text-xs"
            onClick={() => setExpanded(false)}
          >
            ↑ Collapse
          </Button>
        </>
      )}
    </>
  );
}

function PinnedPostCard({ post }: { post: { platform: string; thumbnailUrl?: string; caption?: string; views?: number; likes?: number; embedUrl: string } }) {
  return (
    <a
      href={post.embedUrl}
      target="_blank"
      rel="noreferrer"
      className="group relative aspect-[4/5] rounded-lg overflow-hidden border border-border bg-gradient-to-br from-muted to-muted-foreground/10 block"
    >
      {post.thumbnailUrl ? (
        <img src={post.thumbnailUrl} alt={post.caption || 'pinned post'} className="absolute inset-0 h-full w-full object-cover" />
      ) : (
        <div className="absolute inset-0 flex items-center justify-center">
          {post.platform === 'YOUTUBE' || post.platform === 'INSTAGRAM' ? (
            <Play className="h-7 w-7 text-muted-foreground/60" />
          ) : (
            <ImageIcon className="h-7 w-7 text-muted-foreground/60" />
          )}
        </div>
      )}
      <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 to-transparent p-2 text-white text-[10px] flex items-center justify-between">
        <span>
          {typeof post.views === 'number' && `${formatFollowers(post.views)} views`}
          {typeof post.likes === 'number' && `${formatFollowers(post.likes)} likes`}
        </span>
        <ArrowUpRight className="h-3 w-3 opacity-0 group-hover:opacity-100 transition-opacity" />
      </div>
    </a>
  );
}

const LINK_ICONS: Record<string, React.ComponentType<{ className?: string }>> = {
  ShoppingCart, GraduationCap, Mail, Briefcase, Globe, Instagram, Youtube,
};

function CustomLinkRow({ link }: { link: PortfolioCustomLink }) {
  const Icon = (link.icon && LINK_ICONS[link.icon]) || Globe;
  return (
    <a
      href={link.url}
      target="_blank"
      rel="noreferrer"
      className="flex items-center gap-3 rounded-lg border border-border bg-card px-4 py-3 hover:bg-muted hover:border-primary/30 transition-colors group"
    >
      <Icon className="h-4 w-4 text-muted-foreground shrink-0" />
      <span className="text-sm font-medium flex-1 truncate">{link.label}</span>
      <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground group-hover:text-foreground transition-colors" />
    </a>
  );
}

// ===========================================================================
// Contact dialog — public, anti-spam protected on backend (captcha, rate limit)
// ===========================================================================

interface ContactDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  username: string;
  displayName: string;
}

function ContactDialog({ open, onOpenChange, username, displayName }: ContactDialogProps) {
  const [name, setName] = React.useState('');
  const [email, setEmail] = React.useState('');
  const [message, setMessage] = React.useState('');
  const [submitting, setSubmitting] = React.useState(false);
  const [sent, setSent] = React.useState(false);

  const reset = () => {
    setName('');
    setEmail('');
    setMessage('');
    setSent(false);
  };

  const submit = async () => {
    if (!name.trim() || !email.trim() || !message.trim()) return;
    setSubmitting(true);
    try {
      await api.portfolio.contact(username, { name, email, message });
      setSent(true);
    } catch (e) {
      toast({
        title: 'Message not sent',
        description: e instanceof ApiError ? e.message : 'Please try again in a moment.',
        variant: 'destructive',
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        onOpenChange(o);
        if (!o) reset();
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Message {firstName(displayName)}</DialogTitle>
          <DialogDescription>
            Your message will be forwarded to the creator's email. They'll reply if interested — no spam, no resharing.
          </DialogDescription>
        </DialogHeader>

        {sent ? (
          <div className="py-8 text-center">
            <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-success/15">
              <CheckCircle2 className="h-6 w-6 text-success" />
            </div>
            <p className="font-medium">Message sent</p>
            <p className="text-sm text-muted-foreground mt-1">{firstName(displayName)} usually replies within 24 hours.</p>
            <Button className="mt-4" onClick={() => onOpenChange(false)}>Close</Button>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="contact-name">Your name</Label>
              <Input id="contact-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g., Aman from BoAt" />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="contact-email">Email</Label>
              <Input id="contact-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="aman@boat-lifestyle.com" />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="contact-message">Message</Label>
              <Textarea
                id="contact-message"
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                placeholder="Tell them about your brand, campaign goals, and timeline."
                rows={5}
              />
            </div>
            <p className="text-[11px] text-muted-foreground">
              For paid collaborations on Influora, use <span className="font-medium">Invite to Campaign</span> instead — payments are escrow-protected.
            </p>
            <DialogFooter className="mt-2">
              <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button onClick={submit} disabled={!name || !email || !message || submitting} className="gap-1.5">
                {submitting ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" />
                    Sending…
                  </>
                ) : (
                  <>
                    <Send className="h-4 w-4" />
                    Send
                  </>
                )}
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

// ===========================================================================
// Utils
// ===========================================================================

function initials(name: string) {
  return name
    .split(/\s+/)
    .map((w) => w[0])
    .filter(Boolean)
    .slice(0, 2)
    .join('')
    .toUpperCase();
}

function firstName(name: string) {
  return name.split(/\s+/)[0] || name;
}

function platformLabel(p: string) {
  switch (p) {
    case 'INSTAGRAM': return 'Instagram';
    case 'YOUTUBE': return 'YouTube';
    case 'TIKTOK': return 'TikTok';
    case 'TWITTER': return 'X';
    case 'LINKEDIN': return 'LinkedIn';
    default: return p;
  }
}

function formatFollowers(n: number) {
  if (n >= 10_000_000) return `${(n / 10_000_000).toFixed(1)}Cr`;
  if (n >= 100_000)    return `${(n / 100_000).toFixed(1)}L`;
  if (n >= 1_000)      return `${(n / 1_000).toFixed(n >= 10_000 ? 0 : 1)}K`;
  return String(n);
}

function totalFollowers(page: PortfolioPage) {
  return page.platforms.reduce((sum, p) => sum + p.followers, 0);
}

function visibleCollabs(page: PortfolioPage) {
  return page.collabs.filter((c) => c.displayMode !== 'hidden');
}

function monthYear(iso: string) {
  const d = new Date(iso);
  return d.toLocaleDateString('en-IN', { month: 'short', year: 'numeric' });
}

/**
 * CR-14 — renders a relative "x ago" label, or `null` when the timestamp is
 * missing/unparseable.
 *
 * The bug: a missing or invalid `lastSyncedAt` made `new Date(iso).getTime()`
 * return NaN, which propagated through the day-difference arithmetic and
 * shipped the literal string "Synced NaNd ago" onto the public page creators
 * send to brands. Returning `null` lets the caller drop the line entirely
 * rather than print arithmetic wreckage.
 *
 * A future timestamp (clock skew between the sync job and the viewer) also
 * lands here — negative `ms` floors to a negative hour count, so it is clamped
 * to 'just now' rather than rendering "-1h ago".
 */
function relativeTime(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (!Number.isFinite(then)) return null;
  const ms = Date.now() - then;
  const hours = Math.floor(ms / (1000 * 60 * 60));
  if (hours < 1) return 'just now';
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}
