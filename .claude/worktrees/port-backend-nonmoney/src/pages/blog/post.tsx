import { Link, useParams } from 'react-router-dom';
import { format } from 'date-fns';
import { ArrowRight, Clock, ShieldCheck } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { FadeUp } from '@/components/motion';
import NotFoundPage from '@/pages/not-found';
import { BLOG_CATEGORIES, getPostBySlug, getRelatedPosts } from '@/lib/blog/posts';
import { MarkdownContent, extractHeadings, stripLeadingH1 } from '@/lib/blog/markdown';
import {
  JsonLd,
  getArticleSchema,
  getBreadcrumbListSchema,
  SITE_URL,
  DEFAULT_OG_IMAGE_URL,
} from '@/lib/seo/schema';

export default function BlogPostPage() {
  const { slug } = useParams<{ slug: string }>();
  const post = slug ? getPostBySlug(slug) : undefined;

  if (!post) {
    return <NotFoundPage />;
  }

  const body = stripLeadingH1(post.content);
  const headings = extractHeadings(body).filter((h) => h.level === 2);
  const relatedPosts = getRelatedPosts(post, 3);
  const canonicalPath = `/blog/${post.slug}`;

  const articleSchema = getArticleSchema({
    title: post.title,
    slug: post.slug,
    publishedAt: post.publishedAt,
    modifiedAt: post.updatedAt,
    author: post.author,
    description: post.excerpt,
    image: DEFAULT_OG_IMAGE_URL,
  });
  const breadcrumbSchema = getBreadcrumbListSchema([
    { name: 'Home', url: '/' },
    { name: 'Blog', url: '/blog' },
    { name: post.title, url: canonicalPath },
  ]);

  return (
    <div className="min-h-screen bg-background text-foreground">
      {/* TODO: swap to <Seo/> once src/lib/seo/Seo.tsx lands (Vikram, in progress) */}
      <title>{`${post.title} | Influora Blog`}</title>
      <meta name="description" content={post.excerpt} />
      <meta name="keywords" content={post.keywords.join(', ')} />
      <link rel="canonical" href={`${SITE_URL}${canonicalPath}`} />
      <meta property="og:title" content={post.title} />
      <meta property="og:description" content={post.excerpt} />
      <meta property="og:type" content="article" />
      <meta property="og:url" content={`${SITE_URL}${canonicalPath}`} />
      <meta name="twitter:card" content="summary_large_image" />
      <JsonLd data={[articleSchema, breadcrumbSchema]} />

      <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur">
        <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6" aria-label="Main">
          <Link to="/" aria-label="Influora home">
            <InfluoraLogo />
          </Link>
          <Link to="/blog" className="text-sm font-medium text-muted-foreground hover:text-foreground">
            All posts
          </Link>
        </nav>
      </header>

      <main>
        <article className="mx-auto max-w-3xl px-6 py-14">
          <FadeUp>
            <nav aria-label="Breadcrumb" className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
              <Link to="/" className="hover:text-foreground">
                Home
              </Link>
              <span aria-hidden="true">/</span>
              <Link to="/blog" className="hover:text-foreground">
                Blog
              </Link>
              <span aria-hidden="true">/</span>
              <Link to={`/blog/category/${post.category}`} className="hover:text-foreground">
                {BLOG_CATEGORIES[post.category] ?? post.category}
              </Link>
            </nav>

            <div className="mt-4 flex flex-wrap items-center gap-3">
              <Badge variant="outline" className="text-[10px] font-medium uppercase tracking-wide">
                {BLOG_CATEGORIES[post.category] ?? post.category}
              </Badge>
              <span className="text-xs text-muted-foreground">
                {format(new Date(post.publishedAt), 'MMMM d, yyyy')}
              </span>
              <span className="text-xs text-muted-foreground" aria-hidden="true">
                ·
              </span>
              <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                <Clock className="h-3.5 w-3.5" aria-hidden="true" />
                {post.readingMinutes} min read
              </span>
            </div>

            <h1 className="mt-3 text-3xl font-bold leading-tight tracking-tight sm:text-4xl">{post.title}</h1>
            <p className="mt-2 text-sm text-muted-foreground">By {post.author}</p>
          </FadeUp>

          {headings.length > 1 && (
            <FadeUp delay={0.1}>
              <nav aria-label="Table of contents" className="mt-8 rounded-xl border border-border bg-card/50 p-5">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">In this article</p>
                <ol className="mt-3 space-y-1.5">
                  {headings.map((heading) => (
                    <li key={heading.slug}>
                      <a
                        href={`#${heading.slug}`}
                        className="text-sm text-accent-foreground hover:underline"
                      >
                        {heading.text}
                      </a>
                    </li>
                  ))}
                </ol>
              </nav>
            </FadeUp>
          )}

          <FadeUp delay={0.15} className="mt-6">
            <MarkdownContent content={body} />
          </FadeUp>

          <FadeUp delay={0.2}>
            <Card className="mt-12 border-accent-foreground/20 bg-card/60">
              <CardContent className="flex flex-col items-center gap-4 p-8 text-center">
                <span className="inline-flex h-11 w-11 items-center justify-center rounded-lg bg-accent text-accent-foreground">
                  <ShieldCheck className="h-5 w-5" aria-hidden="true" />
                </span>
                <h2 className="text-xl font-semibold">See escrow-protected deals in action</h2>
                <p className="max-w-md text-sm text-muted-foreground">
                  Every deal on Influora — from a single reel to a 100-creator Hype Campaign — is funded through
                  escrow before work begins.
                </p>
                <div className="flex flex-wrap justify-center gap-3">
                  <Button size="lg" className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
                    <Link to="/brand/register">
                      Launch a campaign <ArrowRight className="ml-1.5 h-4 w-4" aria-hidden="true" />
                    </Link>
                  </Button>
                  <Button size="lg" variant="outline" asChild>
                    <Link to="/creator/register">Join as a creator</Link>
                  </Button>
                </div>
              </CardContent>
            </Card>
          </FadeUp>
        </article>

        {relatedPosts.length > 0 && (
          <section className="border-t border-border/60 bg-card/50 py-16" aria-label="Related posts">
            <div className="mx-auto max-w-6xl px-6">
              <FadeUp>
                <h2 className="text-2xl font-semibold">Related reading</h2>
              </FadeUp>
              <div className="mt-8 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                {relatedPosts.map((related, index) => (
                  <FadeUp key={related.slug} delay={index * 0.08}>
                    <Card className="h-full transition-shadow hover:shadow-md">
                      <CardContent className="flex h-full flex-col p-6">
                        <Badge variant="outline" className="w-fit text-[10px] font-medium uppercase tracking-wide">
                          {BLOG_CATEGORIES[related.category] ?? related.category}
                        </Badge>
                        <h3 className="mt-3 text-base font-semibold leading-snug">
                          <Link to={`/blog/${related.slug}`} className="hover:underline">
                            {related.title}
                          </Link>
                        </h3>
                        <p className="mt-2 line-clamp-2 flex-1 text-sm text-muted-foreground">{related.excerpt}</p>
                        <Link
                          to={`/blog/${related.slug}`}
                          className="mt-4 inline-flex items-center gap-1 text-xs font-medium text-accent-foreground hover:underline"
                        >
                          Read <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
                        </Link>
                      </CardContent>
                    </Card>
                  </FadeUp>
                ))}
              </div>
            </div>
          </section>
        )}
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
