import { lazy, Suspense, useState } from 'react';
import type { CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import {
  ArrowRight,
  Compass,
  Handshake,
  Instagram,
  MessageCircle,
  PenLine,
  Play,
  ScanSearch,
  TrendingUp,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Content as DialogPrimitiveContent } from '@radix-ui/react-dialog';

import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteFooter } from '@/components/site/SiteFooter';
import { SiteHeader } from '@/components/site/SiteHeader';
import { Dialog, DialogClose, DialogDescription, DialogOverlay, DialogPortal, DialogTitle } from '@/components/ui/dialog';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getBreadcrumbListSchema, getWebPageSchema } from '@/lib/seo/schema';

/**
 * /meera — the Meera intro page. A dark, starlit stage whose centrepiece is a 40-second
 * Remotion film (`src/remotion/intro/MeeraIntro.tsx`) that opens in a full-screen popup.
 *
 * The Player, the poster and Remotion itself are lazy-loaded, so none of it lands in the
 * marketing site's main chunk.
 *
 * Copy rules are the same as /meera-for-creators (U-4): Meera advises, the creator decides.
 * No reply drafting, no sending on anyone's behalf, no "PR manager". DM help is labelled
 * coming soon and promises suggestions only. `meera.claims.test.tsx` pins this.
 */

const MeeraIntroPlayer = lazy(() =>
  import('@/components/site/MeeraIntroPlayer').then((m) => ({ default: m.MeeraIntroPlayer })),
);
const MeeraIntroPoster = lazy(() =>
  import('@/components/site/MeeraIntroPlayer').then((m) => ({ default: m.MeeraIntroPoster })),
);

type Feature = { icon: LucideIcon; title: string; body: string; soon?: boolean };

const FEATURES: readonly Feature[] = [
  { icon: Instagram, title: 'Connect your Instagram', body: 'Link your account through Meta’s own login, in one tap.' },
  { icon: ScanSearch, title: 'Meera reads your profile', body: 'Who your audience is, which posts landed, and what is working right now.' },
  { icon: Compass, title: 'One clear next step', body: 'A short note every Monday: one thing to post, one thing to fix.' },
  { icon: TrendingUp, title: 'Grow with a plan', body: 'Small weekly moves instead of guesswork.' },
  { icon: PenLine, title: 'Scripts and hooks', body: 'Reel scripts and hooks in Hinglish, English or Marathi, built around your audience.' },
  { icon: Handshake, title: 'Brands that fit you', body: 'Meera brings the list. You write two lines in your own words and read every pitch before it goes.' },
  { icon: MessageCircle, title: 'DM help', body: 'Suggestions for how to answer brand messages. What you send stays yours to write.', soon: true },
];

const GRADIENT_TEXT = 'bg-gradient-to-r from-[#22D3EE] via-[#8b7bff] to-[#FF5C9E] bg-clip-text text-transparent';

/** Starfield for the stage, drawn with CSS only so it costs nothing to load. */
const STARS: CSSProperties = {
  backgroundImage: [
    'radial-gradient(1px 1px at 12% 18%, rgba(255,255,255,0.8), transparent)',
    'radial-gradient(1px 1px at 72% 12%, rgba(255,255,255,0.6), transparent)',
    'radial-gradient(1.5px 1.5px at 38% 64%, rgba(255,255,255,0.7), transparent)',
    'radial-gradient(1px 1px at 86% 58%, rgba(255,255,255,0.5), transparent)',
    'radial-gradient(1px 1px at 22% 82%, rgba(255,255,255,0.6), transparent)',
    'radial-gradient(1.5px 1.5px at 58% 32%, rgba(255,255,255,0.5), transparent)',
    'radial-gradient(1px 1px at 92% 88%, rgba(255,255,255,0.6), transparent)',
    'radial-gradient(circle at 18% 28%, rgba(109,90,230,0.35), transparent 40%)',
    'radial-gradient(circle at 85% 75%, rgba(255,92,158,0.18), transparent 38%)',
    'radial-gradient(circle at 60% 10%, rgba(34,211,238,0.14), transparent 34%)',
  ].join(','),
  backgroundSize: '420px 420px, 380px 380px, 460px 460px, 340px 340px, 400px 400px, 520px 520px, 360px 360px, 100% 100%, 100% 100%, 100% 100%',
};

function PosterSkeleton() {
  return <div className="aspect-video w-full animate-pulse bg-white/5" aria-hidden="true" />;
}

function isPortraitViewport() {
  return typeof window !== 'undefined' && window.innerWidth < 768 && window.innerHeight > window.innerWidth;
}

export default function MeeraPage() {
  const [open, setOpen] = useState(false);
  const [portrait, setPortrait] = useState(false);

  function watch() {
    setPortrait(isPortraitViewport());
    setOpen(true);
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo
        title="Meera — one AI for your creator life"
        description="Meera reads your Instagram, suggests your next step, writes scripts and hooks in your language, and finds brands that fit. Coming soon on Influora."
        canonical="/meera"
      />
      <JsonLd
        data={getWebPageSchema({
          name: 'Meera',
          description:
            'An introduction to Meera, the creator-side AI coming to Influora: profile reading, weekly next steps, scripts and hooks, and brand discovery.',
          url: '/meera',
        })}
      />
      <JsonLd
        data={getBreadcrumbListSchema([
          { name: 'Home', url: '/' },
          { name: 'Meera', url: '/meera' },
        ])}
      />

      <SiteHeader />

      <main className="bg-[#05040f] text-[#f5f3ff]" style={STARS}>
        {/* Hero */}
        <section className="relative overflow-hidden">
          <div className="mx-auto grid max-w-6xl items-center gap-12 px-4 py-20 sm:px-6 sm:py-28 lg:grid-cols-[1fr_1.05fr]">
            <FadeUp>
              <span className="inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/5 px-3 py-1 text-xs font-medium tracking-wide text-[#c9c3ea]">
                <span className="h-1.5 w-1.5 rounded-full bg-[#22D3EE]" aria-hidden="true" />
                In the works · coming soon
              </span>
              <h1 className="mt-5 text-4xl font-semibold leading-[1.05] tracking-tight sm:text-6xl">
                Meera. One AI for your <span className={GRADIENT_TEXT}>whole creator life.</span>
              </h1>
              <p className="mt-5 max-w-xl text-lg leading-relaxed text-[#c9c3ea]">
                Connect Instagram, and Meera reads your profile, suggests your next step, writes scripts and
                hooks in your language, and finds brands that fit. You stay in charge of every decision.
              </p>
              <div className="mt-9 flex flex-wrap gap-3">
                <button
                  type="button"
                  onClick={watch}
                  className="inline-flex h-12 items-center gap-2 rounded-full bg-gradient-to-r from-[#6D5AE6] to-[#4c3bc2] px-6 text-base font-semibold text-white shadow-[0_0_40px_rgba(109,90,230,0.55)] transition hover:brightness-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#22D3EE] focus-visible:ring-offset-2 focus-visible:ring-offset-[#05040f]"
                >
                  <Play className="h-4 w-4 fill-current" aria-hidden="true" />
                  Watch the intro
                </button>
                <Link
                  to="/creator/register"
                  className="inline-flex h-12 items-center gap-1.5 rounded-full border border-white/25 px-6 text-base font-semibold text-white transition hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#22D3EE]"
                >
                  Join the waitlist <ArrowRight className="h-4 w-4" aria-hidden="true" />
                </Link>
              </div>
            </FadeUp>

            <FadeUp delay={0.1}>
              <button
                type="button"
                onClick={watch}
                aria-label="Play the Meera intro film"
                className="group relative block w-full overflow-hidden rounded-3xl border border-white/15 bg-white/5 shadow-[0_40px_120px_rgba(109,90,230,0.35)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#22D3EE]"
              >
                <Suspense fallback={<PosterSkeleton />}>
                  <MeeraIntroPoster />
                </Suspense>
                <span className="absolute inset-0 flex items-center justify-center bg-black/10 transition group-hover:bg-black/0">
                  <span className="flex h-20 w-20 items-center justify-center rounded-full border border-white/40 bg-white/15 backdrop-blur-md transition group-hover:scale-110">
                    <Play className="ml-1 h-8 w-8 fill-white text-white" aria-hidden="true" />
                  </span>
                </span>
                <span className="absolute bottom-4 left-4 rounded-full bg-black/40 px-3 py-1 text-xs font-medium text-white/90 backdrop-blur">
                  0:40 · sound on
                </span>
              </button>
            </FadeUp>
          </div>
        </section>

        {/* What Meera does */}
        <section className="border-t border-white/10 py-20 sm:py-24">
          <div className="mx-auto max-w-6xl px-4 sm:px-6">
            <FadeUp>
              <p className="text-center text-sm font-semibold uppercase tracking-[0.2em] text-[#8f88b8]">What Meera does</p>
              <h2 className="mx-auto mt-3 max-w-2xl text-center text-3xl font-semibold tracking-tight sm:text-4xl">
                Less guessing. <span className={GRADIENT_TEXT}>More creating.</span>
              </h2>
            </FadeUp>
            <StaggerContainer className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {FEATURES.map((f) => (
                <StaggerItem key={f.title}>
                  <div className="h-full rounded-2xl border border-white/10 bg-white/[0.04] p-6 backdrop-blur-sm transition hover:border-white/25 hover:bg-white/[0.07]">
                    <div className="flex items-center justify-between">
                      <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-gradient-to-br from-[#6D5AE6]/40 to-[#FF5C9E]/20 text-white">
                        <f.icon className="h-5 w-5" aria-hidden="true" />
                      </span>
                      {f.soon ? (
                        <span className="rounded-full border border-[#FF5C9E]/60 bg-[#FF5C9E]/15 px-2.5 py-0.5 text-xs font-semibold text-white">
                          Coming soon
                        </span>
                      ) : null}
                    </div>
                    <h3 className="mt-5 text-lg font-semibold text-white">{f.title}</h3>
                    <p className="mt-2 leading-relaxed text-[#c9c3ea]">{f.body}</p>
                  </div>
                </StaggerItem>
              ))}
            </StaggerContainer>
            <p className="mt-10 text-center text-[#c9c3ea]">
              Meera advises. <span className="font-semibold text-white">You decide.</span>
            </p>
          </div>
        </section>

        {/* Close */}
        <section className="border-t border-white/10 py-24 text-center">
          <FadeUp>
            <h2 className="px-4 text-4xl font-semibold tracking-tight sm:text-6xl">
              One tool. <span className={GRADIENT_TEXT}>Everything changes.</span>
            </h2>
            <div className="mt-9 flex flex-wrap justify-center gap-3 px-4">
              <Link
                to="/creator/register"
                className="inline-flex h-12 items-center gap-1.5 rounded-full bg-gradient-to-r from-[#6D5AE6] to-[#4c3bc2] px-6 text-base font-semibold text-white shadow-[0_0_40px_rgba(109,90,230,0.55)] transition hover:brightness-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#22D3EE]"
              >
                Join the waitlist <ArrowRight className="h-4 w-4" aria-hidden="true" />
              </Link>
              <Link
                to="/meera-for-creators"
                className="inline-flex h-12 items-center rounded-full border border-white/25 px-6 text-base font-semibold text-white transition hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#22D3EE]"
              >
                See Meera read a brand brief
              </Link>
            </div>
          </FadeUp>
        </section>
      </main>

      <SiteFooter />

      {/* The film, full screen */}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogPortal>
          <DialogOverlay className="bg-[#05040f]/85 backdrop-blur-md" />
          <DialogPrimitiveContent
            className={`fixed left-1/2 top-1/2 z-50 w-full -translate-x-1/2 -translate-y-1/2 px-4 outline-none ${
              portrait ? 'max-w-[min(420px,calc(85vh*9/16))]' : 'max-w-[min(1200px,calc(85vh*16/9))]'
            }`}
          >
            <DialogTitle className="sr-only">Meera intro film</DialogTitle>
            <DialogDescription className="sr-only">
              A 40-second introduction to Meera, with music. Use the player controls to pause or mute.
            </DialogDescription>
            <div className="overflow-hidden rounded-[20px] border border-white/15 shadow-[0_40px_160px_rgba(109,90,230,0.45)]">
              {open ? (
                <Suspense
                  fallback={<div className={`${portrait ? 'aspect-[9/16]' : 'aspect-video'} w-full animate-pulse bg-white/5`} />}
                >
                  <MeeraIntroPlayer portrait={portrait} />
                </Suspense>
              ) : null}
            </div>
            <DialogClose className="mx-auto mt-4 flex items-center gap-2 rounded-full border border-white/25 px-4 py-2 text-sm font-medium text-white transition hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#22D3EE]">
              Close
            </DialogClose>
          </DialogPrimitiveContent>
        </DialogPortal>
      </Dialog>
    </div>
  );
}
