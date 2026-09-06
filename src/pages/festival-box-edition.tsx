import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Check, Copy, ExternalLink, Sparkles, Store } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { PageConsentBar, usePageConsent } from '@/components/site/PageConsentBar';
import { cn } from '@/lib/utils';
import { Seo } from '@/lib/seo/Seo';
import {
  JsonLd,
  getBreadcrumbListSchema,
  getHowToSchema,
  getWebPageSchema,
} from '@/lib/seo/schema';
import {
  getEdition,
  sortedSponsors,
  type FestivalEdition,
  type FestivalSponsor,
} from '@/content/festival-editions';

// ============================================================================
// SPONSOR CONSENT + META PIXEL (T-FESTIVALBOX-0905 phase 6)
// ============================================================================

/**
 * [Kabir H-6] One consent record PER EDITION. This was a single shared key for every edition,
 * justified as "the decision being consented to is the same regardless of which edition slug the
 * visitor is on, and there is only one edition today."
 *
 * Both halves of that were wrong. The decision is not the same: each edition has its own
 * sponsors, so what a visitor agrees to is "these named companies may measure this visit", and
 * edition 02's sponsors are different companies from edition 01's. And the "only one edition
 * today" is exactly the condition that made the bug invisible in review while guaranteeing it
 * ships broken on the day edition 02 launches — at which point a returning visitor gets no bar at
 * all and a set of brands they have never seen starts receiving their traffic under a yes given
 * months earlier to someone else.
 *
 * Scoping by slug means each edition asks for itself. The sponsor-pixel-id `scope` passed to
 * {@link usePageConsent} then handles the finer case within one edition: a sponsor added
 * mid-edition is also a party the visitor never agreed to, and re-opens the bar.
 */
export function consentStorageKeyFor(editionSlug: string): string {
  return `influora:festival-box:${editionSlug}:sponsor-pixel-consent`;
}

/**
 * The one external origin this page is allowed to load a script from — see the CSP `map` block in
 * `docker/nginx.conf.template`, which allows `https://connect.facebook.net` for `/festival-box/`
 * and nowhere else. Named so {@link teardownMetaPixel} removes exactly what
 * {@link ensureMetaPixelBase} injected, with no chance of the two drifting apart.
 */
const META_PIXEL_SCRIPT_SRC = 'https://connect.facebook.net/en_US/fbevents.js';

/** Minimal shape of the global `fbq` Meta Pixel installs — see `ensureMetaPixelBase` below. */
interface FbqFunction {
  (command: string, ...args: unknown[]): void;
  callMethod?: (...args: unknown[]) => void;
  queue: unknown[][];
  loaded?: boolean;
  version?: string;
}

declare global {
  interface Window {
    fbq?: FbqFunction;
    _fbq?: FbqFunction;
  }
}

/**
 * Defines `window.fbq` and loads Meta's pixel base script, as plain TS rather than the inline
 * `<script>` snippet Meta publishes. That matters for CSP: an inline snippet would need
 * 'unsafe-inline' in script-src; this doesn't, because it's ordinary code inside this page's own
 * bundle (covered by script-src 'self') that creates one `<script src="https://connect.facebook.net/...">`
 * tag — only that external origin needs allow-listing, which public/_headers scopes to
 * `/festival-box/*` only.
 *
 * Idempotent by construction: `if (window.fbq) return` mirrors Meta's own guard, so calling this
 * from an effect that re-runs (re-render, remount) never injects the loader script twice. [Kabir
 * H-6] That same guard is why withdrawal cannot be handled by simply calling this again — it makes
 * the injected state a one-way door for the lifetime of the page. See {@link teardownMetaPixel}.
 *
 * Must only ever be called from inside a consent-gated `useEffect` — never at module scope, and
 * never from index.html, both of which would fire before (or regardless of) consent.
 */
function ensureMetaPixelBase(): void {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;
  if (window.fbq) return;

  const fbq = ((...args: unknown[]) => {
    if (fbq.callMethod) {
      fbq.callMethod(...args);
    } else {
      fbq.queue.push(args);
    }
  }) as FbqFunction;
  fbq.queue = [];
  fbq.loaded = true;
  fbq.version = '2.0';

  window.fbq = fbq;
  if (!window._fbq) window._fbq = fbq;

  const script = document.createElement('script');
  script.async = true;
  script.src = META_PIXEL_SCRIPT_SRC;
  script.dataset.influoraSponsorPixel = 'true';
  const firstScript = document.getElementsByTagName('script')[0];
  if (firstScript?.parentNode) {
    firstScript.parentNode.insertBefore(script, firstScript);
  } else {
    document.head.appendChild(script);
  }
}

/**
 * [Kabir H-6] Best-effort removal of everything {@link ensureMetaPixelBase} put in place, called
 * immediately before the reload that does the real work.
 *
 * <b>This function alone does NOT stop the pixel, and must never be relied on as if it did.</b>
 * Removing the `<script>` element does not unload code the browser has already executed, and
 * `fbevents.js` keeps its own internal references to the queue and the initialised pixel ids —
 * `delete window.fbq` removes our handle, not theirs. What this buys is narrow but real: after it
 * runs, nothing in *our* code can dispatch another event, and `ensureMetaPixelBase`'s
 * `if (window.fbq) return` guard no longer blocks a legitimate re-init if the visitor accepts
 * again. The guarantee that no pixel is running comes from the reload at the call site.
 */
function teardownMetaPixel(): void {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;

  document
    .querySelectorAll(`script[data-influora-sponsor-pixel="true"], script[src^="${META_PIXEL_SCRIPT_SRC}"]`)
    .forEach((node) => node.remove());

  delete window.fbq;
  delete window._fbq;
}

// ============================================================================
// COPY-EVENT TRACKING (T-FESTIVALBOX-0905 phase 6, part D)
// ============================================================================

const FESTIVAL_API_BASE_URL: string =
  (import.meta.env?.VITE_API_BASE_URL as string | undefined) || 'http://localhost:8080/api/v1';

/**
 * Fire-and-forget: tells the backend a coupon was copied. `edition`/`sponsorSlug`/`couponCode`
 * only — no visitor data attached, so this is NOT gated on the pixel consent bar (see module
 * docstring below the imports and PageConsentBar.tsx for why the pixel IS gated).
 *
 * Every failure mode is swallowed on purpose: the endpoint 404ing (not deployed yet), a network
 * error, `sendBeacon` being unavailable — none of it may surface to the shopper or delay the
 * clipboard write, because the copy itself is the user's actual goal and this is just a count.
 * Callers must not `await` this before updating copy-success UI.
 */
function reportCouponCopied(editionKey: string, sponsorSlug: string, couponCode: string): void {
  try {
    const url = `${FESTIVAL_API_BASE_URL}/festival/coupon-copied`;
    const payload = JSON.stringify({ edition: editionKey, sponsorSlug, couponCode });

    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      const blob = new Blob([payload], { type: 'application/json' });
      if (navigator.sendBeacon(url, blob)) return;
    }

    void fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      keepalive: true,
    }).catch(() => {
      // Ignored — see function docstring. The copy already succeeded before this ran.
    });
  } catch {
    // Ignored — see function docstring.
  }
}

/**
 * /festival-box/:edition (T-FESTIVALBOX-0905 phase 3) — the public, shopper-facing page.
 *
 * NOT the sponsorship pitch at /festival-box (src/pages/festival-box.tsx). This is where a
 * shopper lands after watching a creator's reel: every sponsor's product side by side, each with
 * a tap-to-copy coupon and a Shop button that opens the brand's own store. Influora never takes
 * payment here — see deck slide 7 ("Brand A — Kurta Set — INFLUORA10 — Shop ›").
 *
 * Data comes from src/content/festival-editions.ts, not an API — see that file's header for why.
 * JSON-LD is derived from the same `sortedSponsors()`/`edition` values the page renders, following
 * the pattern in festival-box.tsx: never re-type copy into a schema literal.
 */
export default function FestivalBoxEditionPage(): ReactElement {
  const { edition: slug } = useParams<{ edition: string }>();
  const edition = getEdition(slug);
  const sponsors = useMemo(() => (edition ? sortedSponsors(edition.sponsors) : []), [edition]);

  // The exact set of third parties this page wants to run, and therefore the exact set the
  // visitor is being asked about. Sorted+joined into a stable identity so the hook is not re-read
  // on every render by a fresh array.
  const pixelIds = useMemo(
    () =>
      sponsors
        .map((sponsor) => sponsor.metaPixelId)
        .filter((id): id is string => Boolean(id))
        .sort(),
    [sponsors],
  );

  const { consent, accept, decline, reset } = usePageConsent(
    consentStorageKeyFor(slug ?? ''),
    pixelIds,
  );

  // Fires the Meta Pixel base script + one `fbq('init', id)` per sponsor with a pixel, plus a
  // single PageView — but ONLY once consent is 'accepted'. `hasFiredRef` guards against
  // double-firing on a re-render (this effect's own dependencies can change every render, since
  // `sponsors` is a fresh array each time) while still re-firing on a genuine new acceptance
  // (declined -> accepted again resets the ref to false first).
  const hasFiredPixelRef = useRef(false);
  useEffect(() => {
    if (consent !== 'accepted') {
      // [Kabir H-6] WITHDRAWAL MUST ACTUALLY STOP THE TRACKING.
      //
      // This branch used to only reset the ref, which stopped nothing. Once `fbevents.js` has
      // loaded it holds its own references and keeps its initialised pixels; clearing React state
      // and localStorage left the script in the document, `window.fbq` callable, every pixel still
      // initialised, and `ensureMetaPixelBase`'s `if (window.fbq) return` guard permanently
      // preventing any future teardown. A visitor who accepted, then used "Manage sponsor tracking
      // preferences" to decline, was told their choice was recorded while the tag went on running
      // for the rest of the session. That is the failure mode the whole consent bar exists to
      // prevent, and it is the one that is actually actionable under DPDP/GDPR.
      //
      // A RELOAD IS THE FIX, and deliberately so. There is no supported way to un-initialise a
      // Meta Pixel or unload fbevents.js: deleting `window.fbq` and removing the <script> tag does
      // not undo what the loaded script already wired up (it captures its own handles, and any
      // in-flight or queued dispatch continues). Anything short of tearing down the JS realm is
      // theatre. So: best-effort local cleanup, then a real navigation, which is the only thing
      // that provably leaves no pixel running.
      //
      // Only when a pixel was actually fired in THIS page life — otherwise every ordinary "no"
      // and every first visit would pointlessly reload the page, and the reload could loop.
      if (hasFiredPixelRef.current) {
        hasFiredPixelRef.current = false;
        teardownMetaPixel();
        window.location.reload();
        return;
      }
      hasFiredPixelRef.current = false;
      return;
    }
    if (hasFiredPixelRef.current) return;
    if (pixelIds.length === 0) return; // No sponsor has a pixel id yet — nothing to fire.

    hasFiredPixelRef.current = true;
    ensureMetaPixelBase();
    pixelIds.forEach((id) => window.fbq?.('init', id));
    window.fbq?.('track', 'PageView');
  }, [consent, pixelIds]);

  if (!edition) {
    return <EditionNotFound slug={slug} />;
  }

  const hasSponsors = sponsors.length > 0;
  const hasCreators = edition.creators.length > 0;
  const canonicalPath = `/festival-box/${edition.slug}`;
  const titleSponsor = hasSponsors && sponsors[0].tier === 'TITLE' ? sponsors[0] : null;
  const restSponsors = titleSponsor ? sponsors.slice(1) : sponsors;

  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title={edition.name}
        description={edition.standfirst}
        canonical={canonicalPath}
      />
      <JsonLd
        data={getWebPageSchema({
          name: edition.name,
          description: edition.standfirst,
          url: canonicalPath,
        })}
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'Festival Box', url: '/festival-box' },
          { name: edition.name, url: canonicalPath },
        ])}
      />
      {/*
        Gated to the populated state: the three steps describe using a coupon a shopper can
        actually see on screen, so publishing this HowTo while the page has no sponsors yet would
        advertise a flow with nothing behind it.
      */}
      {hasSponsors && (
        <JsonLd
          data={getHowToSchema({
            name: 'How to use a Festival Box coupon',
            description:
              'Tap a brand’s code to copy it, shop on that brand’s own store, and use the code at checkout.',
            url: canonicalPath,
            steps: HOW_IT_WORKS.map((s) => ({ name: s.title, text: s.body })),
          })}
        />
      )}

      <SiteHeader />

      <main>
        <section className="border-b border-border/60 py-16">
          <div className="mx-auto max-w-3xl px-6 text-center">
            <FadeUp>
              <Badge variant="outline" className="gap-1.5">
                <Sparkles className="h-3 w-3" aria-hidden="true" /> {edition.dateLabel}
              </Badge>
              <h1 className="mt-4 text-4xl font-bold leading-tight tracking-tight sm:text-5xl">
                {edition.heroLine}
              </h1>
              <p className="mt-4 text-lg text-muted-foreground">{edition.standfirst}</p>
            </FadeUp>
          </div>
        </section>

        {hasSponsors ? (
          <SponsorGrid
            titleSponsor={titleSponsor}
            restSponsors={restSponsors}
            editionKey={edition.editionKey}
          />
        ) : (
          <EmptyEditionState />
        )}

        {hasSponsors && <HowThisWorksStrip />}

        {hasCreators && <CreatorCreditsSection creators={edition.creators} />}
      </main>

      {/*
        Always available, per PageConsentBar.tsx's design rules — a visitor who accepted or
        declined earlier can still come back and change their mind. Page-scoped (not in the
        shared SiteFooter) since only this page has anything to consent to.
      */}
      <div className="border-t border-border/60 py-4 text-center">
        <button
          type="button"
          onClick={reset}
          className="rounded text-xs text-muted-foreground underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          Manage sponsor tracking preferences
        </button>
      </div>

      <SiteFooter />

      {/* Does not reappear once answered: this stops rendering the instant `consent` is non-null. */}
      {consent === null && <PageConsentBar onAccept={accept} onDecline={decline} />}
    </div>
  );
}

// ============================================================================
// NOT FOUND
// ============================================================================

function EditionNotFound({ slug }: { slug: string | undefined }): ReactElement {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Edition not found"
        description="This Festival Box edition could not be found."
        canonical={`/festival-box/${slug ?? ''}`}
        noindex
      />
      <SiteHeader />
      <main className="flex min-h-[60vh] flex-col items-center justify-center px-6 py-24 text-center">
        <Badge variant="outline">Festival Box</Badge>
        <h1 className="mt-4 text-3xl font-bold sm:text-4xl">This edition isn&apos;t live</h1>
        <p className="mt-3 max-w-md text-muted-foreground">
          We couldn&apos;t find a Festival Box edition at this address. It may have been retired,
          or the link may be mistyped.
        </p>
        <Button
          asChild
          size="lg"
          className="mt-8 bg-accent-foreground text-white hover:bg-accent-foreground/90"
        >
          <Link to="/festival-box">See the current Festival Box</Link>
        </Button>
      </main>
      <SiteFooter />
    </div>
  );
}

// ============================================================================
// EMPTY STATE — what ships for mumbai-2026 today
// ============================================================================

function EmptyEditionState(): ReactElement {
  return (
    <section className="py-16">
      <div className="mx-auto max-w-2xl px-6 text-center">
        <FadeUp>
          <div className="rounded-2xl border border-dashed border-border/60 bg-card/50 p-10">
            <Badge variant="outline">Sponsors coming soon</Badge>
            <h2 className="mt-4 text-2xl font-semibold sm:text-3xl">
              This edition is announced, not stocked yet
            </h2>
            <p className="mt-3 text-muted-foreground">
              Brands are still signing on for this edition, so there are no products or codes to
              show here yet. Check back once the room is confirmed, or if you&apos;re a brand
              interested in a slot, take a look at what&apos;s on offer.
            </p>
            <Button
              asChild
              size="lg"
              className="mt-6 bg-accent-foreground text-white hover:bg-accent-foreground/90"
            >
              <Link to="/festival-box">See sponsor tiers</Link>
            </Button>
          </div>
        </FadeUp>
      </div>
    </section>
  );
}

// ============================================================================
// SPONSOR GRID
// ============================================================================

function SponsorGrid({
  titleSponsor,
  restSponsors,
  editionKey,
}: {
  titleSponsor: FestivalSponsor | null;
  restSponsors: FestivalSponsor[];
  editionKey: string;
}): ReactElement {
  return (
    <section className="py-16">
      <div className="mx-auto max-w-6xl px-6">
        {titleSponsor && (
          <FadeUp className="mb-10">
            <SponsorCard sponsor={titleSponsor} editionKey={editionKey} featured />
          </FadeUp>
        )}

        {restSponsors.length > 0 && (
          <StaggerContainer className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {restSponsors.map((sponsor) => (
              <StaggerItem key={sponsor.slug}>
                <SponsorCard sponsor={sponsor} editionKey={editionKey} />
              </StaggerItem>
            ))}
          </StaggerContainer>
        )}
      </div>
    </section>
  );
}

type CopyStatus = 'idle' | 'copied' | 'selected' | 'failed';

function SponsorCard({
  sponsor,
  editionKey,
  featured = false,
}: {
  sponsor: FestivalSponsor;
  editionKey: string;
  featured?: boolean;
}): ReactElement {
  const [status, setStatus] = useState<CopyStatus>('idle');
  const codeRef = useRef<HTMLElement>(null);
  const resetTimer = useRef<number | undefined>(undefined);

  const handleCopy = useCallback(async () => {
    const result = await copyCoupon(sponsor.coupon, codeRef.current);
    setStatus(result);
    window.clearTimeout(resetTimer.current);
    if (result === 'copied' || result === 'failed') {
      // "selected" is left standing — clearing the selection out from under a shopper
      // who is about to press Ctrl/Cmd+C would defeat the fallback it exists for.
      resetTimer.current = window.setTimeout(() => setStatus('idle'), 2500);
    }
    if (result === 'copied') {
      // Fire-and-forget, never awaited — the copy above has already succeeded and the UI is
      // already updating; this must not be able to delay or fail it. See reportCouponCopied.
      reportCouponCopied(editionKey, sponsor.slug, sponsor.coupon);
    }
  }, [sponsor.coupon, sponsor.slug, editionKey]);

  const shopHref = sponsor.trackingUrl ?? sponsor.shopUrl;
  const copyLabel =
    status === 'copied'
      ? 'Copied'
      : status === 'selected'
        ? 'Code selected — press Ctrl/Cmd+C'
        : status === 'failed'
          ? "Couldn't copy — select the code manually"
          : `Copy code ${sponsor.coupon}`;

  return (
    <article
      className={cn(
        'flex h-full flex-col overflow-hidden rounded-2xl border border-border/60 bg-card/50',
        featured && 'border-accent-foreground/50 shadow-md sm:flex-row',
      )}
    >
      {sponsor.tier === 'TITLE' && (
        <div
          className={cn(
            'flex items-center justify-between gap-2 px-6 pt-6',
            featured && 'sm:hidden',
          )}
        >
          <Badge className="bg-accent-foreground text-white hover:bg-accent-foreground">
            Title sponsor
          </Badge>
        </div>
      )}

      <div
        className={cn(
          'flex aspect-square w-full items-center justify-center bg-muted/40',
          featured && 'sm:aspect-auto sm:w-2/5',
        )}
      >
        {sponsor.imageUrl ? (
          <img
            src={sponsor.imageUrl}
            alt={`${sponsor.product} by ${sponsor.brand}`}
            className="h-full w-full object-cover"
          />
        ) : (
          <div className="flex h-full w-full flex-col items-center justify-center gap-1 p-6 text-center">
            <span className="text-2xl font-bold tracking-tight">{sponsor.brand}</span>
            <span className="text-sm text-muted-foreground">{sponsor.product}</span>
          </div>
        )}
      </div>

      <div className="flex flex-1 flex-col gap-4 p-6">
        {sponsor.tier === 'TITLE' && featured && (
          <Badge className="hidden w-fit bg-accent-foreground text-white hover:bg-accent-foreground sm:inline-flex">
            Title sponsor
          </Badge>
        )}
        <div>
          <p className="text-sm font-medium text-muted-foreground">{sponsor.brand}</p>
          <h3 className={cn('font-semibold', featured ? 'text-2xl' : 'text-lg')}>
            {sponsor.product}
          </h3>
          <p className="mt-1.5 text-sm text-muted-foreground">{sponsor.blurb}</p>
        </div>

        <div className="mt-auto flex flex-col gap-3">
          <div className="flex items-center justify-between gap-3 rounded-xl border border-border/60 bg-background px-4 py-3">
            <div className="flex items-baseline gap-2">
              <code ref={codeRef} className="font-mono text-base font-bold tracking-wide">
                {sponsor.coupon}
              </code>
              <span className="text-sm text-muted-foreground">{sponsor.couponValue}</span>
            </div>
            <button
              type="button"
              onClick={handleCopy}
              aria-label={copyLabel}
              className="inline-flex h-8 shrink-0 items-center justify-center gap-1.5 rounded-md border border-border/60 px-2.5 text-xs font-medium transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              {status === 'copied' ? (
                <Check className="h-3.5 w-3.5" aria-hidden="true" />
              ) : (
                <Copy className="h-3.5 w-3.5" aria-hidden="true" />
              )}
              {status === 'copied' ? 'Copied' : 'Copy'}
            </button>
          </div>
          <span role="status" aria-live="polite" className="sr-only">
            {status === 'copied'
              ? `Copied ${sponsor.coupon} to clipboard.`
              : status === 'selected'
                ? `Couldn't copy automatically. ${sponsor.coupon} is selected — press Ctrl or Cmd plus C to copy it.`
                : status === 'failed'
                  ? "Couldn't copy the code. Please select it manually."
                  : ''}
          </span>

          <Button
            asChild
            size="lg"
            className="w-full bg-accent-foreground text-white hover:bg-accent-foreground/90"
          >
            <a href={shopHref} target="_blank" rel="noopener noreferrer">
              Shop {sponsor.brand}
              <ExternalLink className="ml-1.5 h-4 w-4" aria-hidden="true" />
            </a>
          </Button>
        </div>
      </div>
    </article>
  );
}

/**
 * Copies `code` to the clipboard, falling back to selecting its on-screen text when the
 * Clipboard API is unavailable, blocked (insecure context, denied permission), or rejects.
 *
 * Never reports "copied" unless the write actually succeeded — a shopper who thinks a code is on
 * their clipboard when it isn't will paste nothing at checkout.
 */
async function copyCoupon(code: string, node: HTMLElement | null): Promise<CopyStatus> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(code);
      return 'copied';
    }
    throw new Error('Clipboard API unavailable');
  } catch {
    if (!node) return 'failed';
    try {
      const range = document.createRange();
      range.selectNodeContents(node);
      const selection = window.getSelection();
      if (!selection) return 'failed';
      selection.removeAllRanges();
      selection.addRange(range);
      return 'selected';
    } catch {
      return 'failed';
    }
  }
}

// ============================================================================
// HOW THIS WORKS
// ============================================================================

const HOW_IT_WORKS = [
  {
    icon: Copy,
    title: 'Tap a code',
    body: 'Every brand in the room has its own coupon. Tap it once to copy — no sign-up, no app.',
  },
  {
    icon: Store,
    title: 'Shop on the brand’s own store',
    body: 'The Shop button takes you straight to that brand’s website. Influora never takes payment or holds your order.',
  },
  {
    icon: Sparkles,
    title: 'The code is exclusive to this page',
    body: 'These codes are minted for the Festival Box only — paste it in at checkout on the brand’s store.',
  },
] as const;

function HowThisWorksStrip(): ReactElement {
  return (
    <section className="border-t border-border/60 bg-card/50 py-16">
      <div className="mx-auto max-w-4xl px-6">
        <FadeUp className="text-center">
          <Badge variant="outline">How this works</Badge>
          <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">From reel to checkout</h2>
        </FadeUp>
        <StaggerContainer className="mt-10 grid gap-6 sm:grid-cols-3">
          {HOW_IT_WORKS.map((step) => {
            const Icon = step.icon;
            return (
              <StaggerItem key={step.title}>
                <div className="h-full rounded-2xl border border-border/60 bg-background p-6 text-center">
                  <span className="mx-auto flex h-11 w-11 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                    <Icon className="h-5 w-5" aria-hidden="true" />
                  </span>
                  <h3 className="mt-3 font-semibold">{step.title}</h3>
                  <p className="mt-1.5 text-sm text-muted-foreground">{step.body}</p>
                </div>
              </StaggerItem>
            );
          })}
        </StaggerContainer>
      </div>
    </section>
  );
}

// ============================================================================
// CREATOR CREDITS
// ============================================================================

function CreatorCreditsSection({
  creators,
}: {
  creators: FestivalEdition['creators'];
}): ReactElement {
  return (
    <section className="py-16">
      <div className="mx-auto max-w-4xl px-6 text-center">
        <FadeUp>
          <Badge variant="outline">Shot by</Badge>
          <h2 className="mt-3 text-2xl font-semibold sm:text-3xl">The creators in the room</h2>
        </FadeUp>
        <StaggerContainer className="mt-8 flex flex-wrap justify-center gap-3">
          {creators.map((creator) => (
            <StaggerItem key={creator.handle}>
              <span className="inline-flex items-center gap-2 rounded-full border border-border/60 bg-card/50 px-4 py-2 text-sm">
                <span className="font-medium">{creator.handle}</span>
                <span className="text-muted-foreground">{creator.band}</span>
              </span>
            </StaggerItem>
          ))}
        </StaggerContainer>
      </div>
    </section>
  );
}
