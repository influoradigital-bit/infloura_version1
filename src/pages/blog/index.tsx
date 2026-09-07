import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { format } from 'date-fns';
import { ArrowRight, Bell, Briefcase, Clock, Newspaper, Video } from 'lucide-react';
import type { ComponentType, SVGProps } from 'react';

import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { FunnelCta } from '@/components/site/FunnelCta';
import { StickyCta, StickyCtaSpacer } from '@/components/site/StickyCta';
import { BLOG_CATEGORIES, getAllPosts, getPostsByCategory, type BlogPost } from '@/lib/blog/posts';
import { Seo } from '@/lib/seo/Seo';
import { JsonLd, getBreadcrumbListSchema, getWebsiteSchema } from '@/lib/seo/schema';
import { cn } from '@/lib/utils';

/*
  Layout ported from the Stitch design "Influora - Blog & Insights Hub"
  (blog-insights-hub). This route is a REAL blog index over the markdown posts
  in src/content/blog/ — the design's structure and spacing were ported, its
  post list, bylines and numbers were not.

  Deliberately NOT carried over from the design, and why:
    - Every post in the design ("Why 30% Hidden Agency Markups Are Dying...",
      the Section 194J guide, the "72-Hour Blitz" piece, etc.) and all six named
      authors (Rohan Mehta, Pooja Malhotra, Aditya Kashyap, Kavya Sharma, Vikram
      Sengupta, Arjun Sen). None of those posts exist in src/content/blog/, and
      several of those names collide with real Sage Digital staff. This page
      renders only the real posts from getAllPosts()/getPostsByCategory(),
      whose only author on file is "Influora Team" (see frontmatter).
    - "VOL. IV / ISSUE 19", "8 Min Read" style manufactured prestige markers,
      and per-post claim bullets ("Zero hidden intermediary fee deduction",
      "Funds deposited in Protected Settlement Vaults") — none of that is real
      post metadata; the frontmatter schema has no field for it.
    - The category-explorer counts ("14 Playbooks", "9 Guides", "22 Frameworks",
      "11 Case Studies") were invented. The counts below are
      getPostsByCategory(slug).length — real, and they update themselves as
      Ishaan publishes more posts.
    - "Join 4,200+ D2C founders..." newsletter subscriber count, and the
      "Subscribe Free" email capture itself. There is no newsletter subscribe
      endpoint anywhere in src/lib/api.ts — building the input would be a
      control that persists nothing (banned under TECH-STACK.md "UI Honesty").
      The section is omitted rather than shipped fake or disabled.
    - "Join 350+ pioneering Indian consumer brands..." in the closing CTA —
      same unmeasured-traction problem; replaced with the shared FunnelCta.
    - "RBI-Regulated Safety Vault", "ISO 27001 Certified", "RBI Licensed Escrow
      Partner" badges — see the how-it-works pages for why; same rules apply
      here.
    - The hand-rolled header/footer this file used before. SiteHeader/
      SiteFooter are the shared, correct ones (real nav, real COMPANY-sourced
      CIN/GSTIN) — reused here instead of a third copy.
*/

interface CategoryExplorerItem {
  slug: string;
  label: string;
  description: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  image: string;
}

const CATEGORY_EXPLORER: CategoryExplorerItem[] = [
  {
    slug: 'brands',
    label: BLOG_CATEGORIES.brands,
    description: 'Discovery, contracts, and paying creators without the DM chaos.',
    icon: Briefcase,
    image: '/product-shots/brand-dashboard.png',
  },
  {
    slug: 'creators',
    label: BLOG_CATEGORIES.creators,
    description: 'Rate cards, revision limits, and getting paid on time.',
    icon: Video,
    image: '/product-shots/creator-dashboard.png',
  },
  {
    slug: 'industry',
    label: BLOG_CATEGORIES.industry,
    description: 'How the Indian creator economy is actually moving.',
    icon: Newspaper,
    image: '/stitch-media/creator-collective-experience-mumbai-edition-shoot-d-e3d1a2.jpg',
  },
  {
    slug: 'updates',
    label: BLOG_CATEGORIES.updates,
    description: 'What shipped on Influora, and what changed.',
    icon: Bell,
    image: '/product-shots/brand-pipeline.png',
  },
];

function PostCard({ post }: { post: BlogPost }) {
  return (
    <Card className="h-full transition-shadow hover:shadow-md">
      <CardContent className="flex h-full flex-col p-6">
        <div className="flex items-center gap-2">
          <Badge variant="outline" className="text-[10px] font-medium uppercase tracking-wide">
            {BLOG_CATEGORIES[post.category] ?? post.category}
          </Badge>
          <span className="text-xs text-muted-foreground">
            {format(new Date(post.publishedAt), 'MMM d, yyyy')}
          </span>
        </div>
        <h3 className="mt-3 text-lg font-semibold leading-snug text-foreground">
          <Link to={`/blog/${post.slug}`} className="hover:underline">
            {post.title}
          </Link>
        </h3>
        <p className="mt-2 line-clamp-3 flex-1 text-sm text-muted-foreground">{post.excerpt}</p>
        <div className="mt-4 flex items-center justify-between text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1">
            <Clock className="h-3.5 w-3.5" aria-hidden="true" />
            {post.readingMinutes} min read
          </span>
          <Link
            to={`/blog/${post.slug}`}
            className="inline-flex items-center gap-1 font-medium text-accent-foreground hover:underline"
          >
            Read <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
          </Link>
        </div>
      </CardContent>
    </Card>
  );
}

/** The most recent post gets the large "Latest" treatment at the top of the grid. */
function FeaturedPostCard({ post }: { post: BlogPost }) {
  return (
    <Card className="border-primary/30">
      <CardContent className="p-6 sm:p-8">
        <div className="flex flex-wrap items-center gap-2">
          <Badge className="gap-1">Latest</Badge>
          <Badge variant="outline" className="text-[10px] font-medium uppercase tracking-wide">
            {BLOG_CATEGORIES[post.category] ?? post.category}
          </Badge>
          <span className="text-xs text-muted-foreground">
            {format(new Date(post.publishedAt), 'MMM d, yyyy')}
          </span>
        </div>
        <h2 className="mt-4 text-2xl font-bold leading-snug tracking-tight sm:text-3xl">
          <Link to={`/blog/${post.slug}`} className="hover:underline">
            {post.title}
          </Link>
        </h2>
        <p className="mt-3 text-muted-foreground">{post.excerpt}</p>
        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
          <span>
            {post.author} · <Clock className="mb-0.5 inline h-3.5 w-3.5" aria-hidden="true" />{' '}
            {post.readingMinutes} min read
          </span>
          <Link
            to={`/blog/${post.slug}`}
            className="inline-flex items-center gap-1 font-medium text-accent-foreground hover:underline"
          >
            Read the guide <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </Link>
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * Shared component for `/blog` and `/blog/category/:category` — the CEO-locked
 * URL structure (wiki/website/CEO-DECISIONS.md) treats category filtering as a
 * distinct route rather than a query param, so this reads `:category` from
 * useParams when mounted under that route and renders the full index otherwise.
 */
export default function BlogIndexPage() {
  const { category } = useParams<{ category?: string }>();

  const posts = useMemo(() => (category ? getPostsByCategory(category) : getAllPosts()), [category]);
  const categoryLabel = category ? BLOG_CATEGORIES[category] : undefined;
  const [featured, ...rest] = posts;

  const pageTitle = categoryLabel
    ? `${categoryLabel} — Blog`
    : 'Blog — Influencer Marketing Guides for Indian Brands & Creators';
  const pageDescription = categoryLabel
    ? `${categoryLabel} articles on protected payments, contracts, and running influencer deals in India — from the Influora team.`
    : 'Guides on protected payments, creator pricing, contracts, and how to run brand-creator deals in India, written by the Influora team.';
  const canonicalPath = category ? `/blog/category/${category}` : '/blog';

  const breadcrumbSchema = getBreadcrumbListSchema(
    category
      ? [
          { name: 'Home', url: '/' },
          { name: 'Blog', url: '/blog' },
          { name: categoryLabel ?? category, url: canonicalPath },
        ]
      : [
          { name: 'Home', url: '/' },
          { name: 'Blog', url: '/blog' },
        ],
  );

  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo title={pageTitle} description={pageDescription} canonical={canonicalPath} />
      <JsonLd data={[getWebsiteSchema(), breadcrumbSchema]} />

      <SiteHeader />

      <main>
        {/* ---------------------------------------------------------------- Hero */}
        <section className="border-b border-border/60 py-16">
          <FadeUp className="mx-auto max-w-3xl px-6 text-center">
            <Badge variant="outline" className="gap-1.5">
              Influora Insights
            </Badge>
            <h1 className="mt-4 text-3xl font-bold tracking-tight sm:text-4xl">
              {categoryLabel ? categoryLabel : 'The Influora Blog'}
            </h1>
            <p className="mt-3 text-muted-foreground">
              {categoryLabel
                ? `Guides on ${categoryLabel.toLowerCase()}, from running cleaner, payment-protected deals.`
                : 'Guides on payments, pricing, contracts, and running brand-creator deals in India — no fluff, just what actually matters before you sign.'}
            </p>
          </FadeUp>

          <div className="mx-auto mt-8 flex max-w-3xl flex-wrap justify-center gap-2 px-6">
            <Link
              to="/blog"
              className={cn(
                'rounded-full border px-3.5 py-1.5 text-sm font-medium transition-colors',
                !category
                  ? 'border-transparent bg-accent-foreground text-white'
                  : 'border-border text-muted-foreground hover:border-accent-foreground/40 hover:text-foreground',
              )}
            >
              All posts
            </Link>
            {Object.entries(BLOG_CATEGORIES).map(([slug, label]) => (
              <Link
                key={slug}
                to={`/blog/category/${slug}`}
                className={cn(
                  'rounded-full border px-3.5 py-1.5 text-sm font-medium transition-colors',
                  category === slug
                    ? 'border-transparent bg-accent-foreground text-white'
                    : 'border-border text-muted-foreground hover:border-accent-foreground/40 hover:text-foreground',
                )}
              >
                {label}
              </Link>
            ))}
          </div>
        </section>

        {/* ------------------------------------------------------------- Posts */}
        <section className="py-16" aria-label="Blog posts">
          <div className="mx-auto max-w-6xl px-6">
            {posts.length === 0 ? (
              <p className="text-center text-muted-foreground">
                No posts in this category yet — check back soon, or{' '}
                <Link to="/blog" className="font-medium text-accent-foreground hover:underline">
                  browse all posts
                </Link>
                .
              </p>
            ) : (
              <>
                <FadeUp>
                  <FeaturedPostCard post={featured} />
                </FadeUp>
                {rest.length > 0 && (
                  <StaggerContainer className="mt-8 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                    {rest.map((post) => (
                      <StaggerItem key={post.slug}>
                        <PostCard post={post} />
                      </StaggerItem>
                    ))}
                  </StaggerContainer>
                )}
              </>
            )}
          </div>
        </section>

        {/* ------------------------------------------------- Explore by category */}
        {!category && (
          <section className="border-t border-border/60 bg-card/50 py-20">
            <div className="mx-auto max-w-6xl px-6">
              <FadeUp className="mx-auto max-w-xl text-center">
                <h2 className="text-3xl font-semibold">Explore by topic</h2>
                <p className="mt-3 text-muted-foreground">
                  Every guide, organized the way brands and creators actually search for it.
                </p>
                {/*
                  Disclosure at section level rather than per card: three of these four
                  thumbnails are real Influora screens (ci/product-shots.mjs) cropped to
                  4:3, which crops out the in-app "Demo data" pill. Four separate captions
                  would clutter a category grid, so the grid carries one.
                */}
                <p className="mt-2 text-xs text-muted-foreground/80">
                  Product screens shown are real Influora screens with sample data.
                </p>
              </FadeUp>
              <StaggerContainer className="mt-10 grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
                {CATEGORY_EXPLORER.map((c) => {
                  const Icon = c.icon;
                  const count = getPostsByCategory(c.slug).length;
                  return (
                    <StaggerItem key={c.slug}>
                      <Link to={`/blog/category/${c.slug}`} className="group block">
                        <Card className="h-full overflow-hidden transition-shadow group-hover:shadow-md">
                          <div className="aspect-[4/3] w-full overflow-hidden">
                            <img
                              src={c.image}
                              alt=""
                              loading="lazy"
                              className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-105"
                            />
                          </div>
                          <CardContent className="p-5">
                            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                              <Icon className="h-4 w-4" aria-hidden="true" />
                            </span>
                            <h3 className="mt-3 font-semibold">{c.label}</h3>
                            <p className="mt-1 text-sm text-muted-foreground">{c.description}</p>
                            <p className="mt-3 text-xs font-medium text-accent-foreground">
                              {count} {count === 1 ? 'post' : 'posts'}
                            </p>
                          </CardContent>
                        </Card>
                      </Link>
                    </StaggerItem>
                  );
                })}
              </StaggerContainer>
            </div>
          </section>
        )}

        <FunnelCta
          heading="Ready to run a payment-protected campaign?"
          sub="Free to start for brands and creators, with a contract and Secure Payments on every deal."
          primary={{ label: 'Start your first campaign', to: '/brand/register' }}
          secondary={{ label: "I'm a creator — show me how I get paid", to: '/how-it-works/creators' }}
          reassurances={['Free to start', 'Contracts included', 'Payment-protected deals']}
          className="border-t border-border/60 py-20"
        />
      </main>

      <SiteFooter />
      <StickyCta label="Start your first campaign" to="/brand/register" note="Free to start" />
      <StickyCtaSpacer />
    </div>
  );
}
