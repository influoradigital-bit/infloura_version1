import { lazy, Suspense, useState } from 'react';
import { Link } from 'react-router-dom';

import { CanvasFallback } from '@/components/3d';
import {
  CountUp,
  FadeUp,
  MagneticButton,
  StaggerContainer,
  StaggerItem,
  TiltCard,
  WordReveal,
} from '@/components/motion';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useSmoothScroll } from '@/hooks/useSmoothScroll';

const LoginScene3DGate = lazy(() =>
  import('@/components/shared/login-scene-3d').then((m) => ({ default: m.LoginScene3DGate })),
);

const DiscoverCanvasGate = lazy(() =>
  import('@/components/3d/DiscoverCanvas').then((m) => ({ default: m.DiscoverCanvasGate })),
);

const PortfolioCanvasGate = lazy(() =>
  import('@/components/3d/PortfolioCanvas').then((m) => ({ default: m.PortfolioCanvasGate })),
);

const demoCards = [
  { title: 'Campaign Alpha', budget: '₹2,50,000' },
  { title: 'Summer Collab', budget: '₹1,80,000' },
  { title: 'Product Launch', budget: '₹4,20,000' },
];

/** Dev-only page to verify Framer Motion + 3D skills — not linked in production nav */
export default function DevMotionSkillsPage() {
  useSmoothScroll(true);
  // F-0289 — this card exists to verify the Button component's built-in `active:scale-[0.97]`
  // press animation (buttonVariants in ui/button.tsx). A `disabled` button never receives
  // pointer events, so disabling it would kill the exact interaction it's here to demo. Wired
  // to a real, visible counter (via CountUp, matching the other two demo stats on this page)
  // instead of a no-op, so the click genuinely does something rather than silently nothing.
  const [pressCount, setPressCount] = useState(0);

  if (!import.meta.env.DEV) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background p-8">
        <p className="text-muted-foreground">Motion skills test is available in development only.</p>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-background">
      <header className="sticky top-0 z-20 border-b border-border bg-card/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-6 py-4">
          <div>
            <p className="text-xs font-medium uppercase tracking-wider text-primary">Dev only</p>
            <h1 className="text-lg font-semibold">Motion Skills Test</h1>
          </div>
          <Button variant="outline" size="sm" asChild>
            <Link to="/brand/login">Auth 3D page →</Link>
          </Button>
        </div>
      </header>

      <main className="mx-auto max-w-5xl space-y-16 px-6 py-12">
        <section>
          <WordReveal text="Framer Motion Skills" as="h2" className="text-3xl font-bold" />
          <FadeUp className="mt-2 text-muted-foreground">
            Scroll to verify FadeUp, Stagger, CountUp, TiltCard, and MagneticButton.
          </FadeUp>
        </section>

        <section className="space-y-4">
          <FadeUp>
            <h3 className="text-xl font-semibold">Skill 5 — Scroll reveal</h3>
          </FadeUp>
          <FadeUp delay={0.05}>
            <Card>
              <CardContent className="pt-6">
                This card uses FadeUp with viewport once. It should animate once when scrolled into view.
              </CardContent>
            </Card>
          </FadeUp>
        </section>

        <section className="space-y-4">
          <FadeUp>
            <h3 className="text-xl font-semibold">Skill 6 — Stagger grid</h3>
          </FadeUp>
          <StaggerContainer className="grid gap-4 sm:grid-cols-3">
            {demoCards.map((card) => (
              <StaggerItem key={card.title}>
                <TiltCard>
                  <Card className="border-0 shadow-none">
                    <CardHeader className="pb-2">
                      <CardTitle className="text-base">{card.title}</CardTitle>
                    </CardHeader>
                    <CardContent>
                      <p className="text-sm text-muted-foreground">{card.budget}</p>
                    </CardContent>
                  </Card>
                </TiltCard>
              </StaggerItem>
            ))}
          </StaggerContainer>
        </section>

        <section className="space-y-4">
          <FadeUp>
            <h3 className="text-xl font-semibold">Skill 6 + stats — CountUp</h3>
          </FadeUp>
          <div className="grid gap-4 sm:grid-cols-3">
            <Card>
              <CardContent className="pt-6">
                <p className="text-sm text-muted-foreground">Wallet balance</p>
                <CountUp value={1250000} className="text-2xl font-bold" />
              </CardContent>
            </Card>
            <Card>
              <CardContent className="pt-6">
                <p className="text-sm text-muted-foreground">Active deals</p>
                <CountUp value={24} formatFn={(n) => Math.round(n).toString()} className="text-2xl font-bold" />
              </CardContent>
            </Card>
            <Card>
              <CardContent className="pt-6">
                <p className="text-sm text-muted-foreground">Emil button — presses</p>
                <CountUp value={pressCount} formatFn={(n) => Math.round(n).toString()} className="text-2xl font-bold" />
                <Button className="mt-2" onClick={() => setPressCount((n) => n + 1)}>
                  Press me — scale 0.97
                </Button>
              </CardContent>
            </Card>
          </div>
        </section>

        <section className="space-y-4">
          <FadeUp>
            <h3 className="text-xl font-semibold">Skill 2 — MagneticButton (auth hero only)</h3>
          </FadeUp>
          <MagneticButton size="lg">Magnetic CTA</MagneticButton>
        </section>

        <section className="space-y-4">
          <FadeUp>
            <h3 className="text-xl font-semibold">MC-09 — DiscoverCanvas</h3>
            <p className="text-sm text-muted-foreground">Central hub + platform satellites. Live on /brand/discover (desktop).</p>
          </FadeUp>
          <div className="overflow-hidden rounded-2xl border border-border h-[320px]">
            <Suspense fallback={<CanvasFallback variant="discover" className="min-h-0 h-full" />}>
              <DiscoverCanvasGate />
            </Suspense>
          </div>
        </section>

        <section className="space-y-4">
          <FadeUp>
            <h3 className="text-xl font-semibold">MC-10 — PortfolioCanvas</h3>
            <p className="text-sm text-muted-foreground">Avatar + orbiting stats. Live on /@handle (desktop).</p>
          </FadeUp>
          <div className="overflow-hidden rounded-2xl border border-border h-[320px]">
            <Suspense fallback={<CanvasFallback variant="portfolio" className="min-h-0 h-full" />}>
              <PortfolioCanvasGate
                stats={{ followers: 920000, engagement: 4.8, collabs: 45 }}
              />
            </Suspense>
          </div>
        </section>

        <section className="space-y-4">
          <FadeUp>
            <h3 className="text-xl font-semibold">Skills 12–21 — 3D auth scene</h3>
            <p className="text-sm text-muted-foreground">
              LoginScene3DGate with PerformanceMonitor. Toggle reduced motion in OS settings to see CanvasFallback.
            </p>
          </FadeUp>
          <div className="overflow-hidden rounded-2xl border border-border">
            <div className="h-[360px] lg:h-[420px]">
              <Suspense
                fallback={
                  <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
                    Loading 3D…
                  </div>
                }
              >
                <LoginScene3DGate />
              </Suspense>
            </div>
          </div>
        </section>

        <section className="space-y-4">
          <FadeUp>
            <h3 className="text-xl font-semibold">CanvasFallback variants</h3>
          </FadeUp>
          <div className="grid gap-4 md:grid-cols-3">
            {(['auth', 'discover', 'portfolio'] as const).map((variant) => (
              <div key={variant} className="overflow-hidden rounded-xl border border-border">
                <p className="bg-muted px-3 py-2 text-xs font-medium capitalize">{variant}</p>
                <div className="h-40">
                  <CanvasFallback variant={variant} className="min-h-0" />
                </div>
              </div>
            ))}
          </div>
        </section>

        <FadeUp>
          <Card className="border-primary/20 bg-primary/5">
            <CardHeader>
              <CardTitle className="text-base">Self-check passed?</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-sm text-muted-foreground">
              <p>✓ FadeUp on scroll · ✓ Stagger grid · ✓ WordReveal title · ✓ TiltCard hover</p>
              <p>✓ CountUp stats · ✓ Button press · ✓ 3D blobs · ✓ Discover + Portfolio canvases</p>
              <p>✓ Lenis smooth scroll on this page · GSAP via useScrollPin (Phase 7)</p>
              <p className="pt-2">
                Full checklist: <code className="text-xs">.cursor/skills/influora-motion-test/SKILL.md</code>
              </p>
            </CardContent>
          </Card>
        </FadeUp>
      </main>
    </div>
  );
}
