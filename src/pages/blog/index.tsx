import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { format } from 'date-fns';
import { ArrowRight, Clock } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { FadeUp, StaggerContainer, StaggerItem } from '@/components/motion';
import { BLOG_CATEGORIES, getAllPosts, getPostsByCategory, type BlogPost } from '@/lib/blog/posts';
import { JsonLd, getBreadcrumbListSchema, getWebsiteSchema, SITE_URL } from '@/lib/seo/schema';
import { cn } from '@/lib/utils';

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

  const pageTitle = categoryLabel
    ? `${categoryLabel} — Influora Blog`
    : 'Blog — Influencer Marketing Guides for Indian Brands & Creators | Influora';
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
      {/* TODO: swap to <Seo/> once src/lib/seo/Seo.tsx lands (Vikram, in progress) */}
      <title>{pageTitle}</title>
      <meta name="description" content={pageDescription} />
      <link rel="canonical" href={`${SITE_URL}${canonicalPath}`} />
      <meta property="og:title" content={pageTitle} />
      <meta property="og:description" content={pageDescription} />
      <meta property="og:type" content="website" />
      <meta property="og:url" content={`${SITE_URL}${canonicalPath}`} />
      <JsonLd data={[getWebsiteSchema(), breadcrumbSchema]} />

      <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur">
        <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6" aria-label="Main">
          <Link to="/" aria-label="Influora home">
            <InfluoraLogo />
          </Link>
          <Link to="/" className="text-sm font-medium text-muted-foreground hover:text-foreground">
            Back to home
          </Link>
        </nav>
      </header>

      <main>
        <section className="border-b border-border/60 py-16">
          <FadeUp className="mx-auto max-w-3xl px-6 text-center">
            <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">
              {categoryLabel ? categoryLabel : 'The Influora Blog'}
            </h1>
            <p className="mt-3 text-muted-foreground">
              {categoryLabel
                ? `Guides ${categoryLabel.toLowerCase()} on running cleaner, payment-protected deals.`
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
              <StaggerContainer className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                {posts.map((post) => (
                  <StaggerItem key={post.slug}>
                    <PostCard post={post} />
                  </StaggerItem>
                ))}
              </StaggerContainer>
            )}
          </div>
        </section>
      </main>

      <footer className="border-t border-border/60 py-10">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 px-6 sm:flex-row">
          <InfluoraLogo size="sm" />
          <p className="text-xs text-muted-foreground">© 2026 Influora</p>
        </div>
      </footer>
    </div>
  );
}
