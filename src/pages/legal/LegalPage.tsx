import { ShieldAlert } from 'lucide-react';

import { SiteHeader } from '@/components/site/SiteHeader';
import { SiteFooter } from '@/components/site/SiteFooter';
import { Seo } from '@/lib/seo/Seo';
import { MarkdownContent, stripLeadingH1 } from '@/lib/blog/markdown';
import { getLegalDoc } from '@/lib/legal/legal-docs';
import NotFoundPage from '@/pages/not-found';

/**
 * Shared renderer for all 8 P0 legal-policy pages (Terms, Privacy, Refund,
 * Disputes, Grievance, KYC, TDS, Advertising Disclosure). One component, one
 * layout — each route in App.tsx just points a different `docSlug` at a file
 * under src/content/legal/.
 *
 * Every legal page ships `noindex` per wiki/website/CEO-DECISIONS.md
 * ("HARD BLOCKERS" section + POLICY DECISIONS P-11): these are v0 drafts
 * pending Indian legal counsel / CA review, not live legal text yet.
 */
interface LegalPageProps {
  /** Filename (without `.md`) under src/content/legal/. */
  docSlug: string;
  /** Seo meta description — the H1/body come from the markdown itself. */
  description: string;
  /** Site-relative canonical path, e.g. "/terms". */
  canonical: string;
}

export default function LegalPage({ docSlug, description, canonical }: LegalPageProps) {
  const doc = getLegalDoc(docSlug);

  if (!doc) {
    // Missing content file is a build-time content bug, not a real 404 — but
    // failing safe to the existing NotFoundPage beats a blank white screen.
    return <NotFoundPage />;
  }

  // The "Last updated" line is rendered explicitly below, so drop the
  // duplicate bold line from the markdown body to avoid showing it twice.
  const body = stripLeadingH1(doc.content).replace(/^\*\*Last updated:.*\*\*\s*$/m, '');

  return (
    <div className="min-h-screen bg-background text-foreground">
      <Seo title={doc.title} description={description} canonical={canonical} noindex />
      <SiteHeader />

      <main className="mx-auto max-w-3xl px-6 py-14">
        <div className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          <ShieldAlert className="h-3.5 w-3.5 text-amber-500" aria-hidden="true" />
          Pending legal review — v0 draft
        </div>
        <h1 className="mt-3 text-3xl font-bold tracking-tight text-foreground sm:text-4xl">{doc.title}</h1>
        {doc.lastUpdated && (
          <p className="mt-2 text-sm text-muted-foreground">Last updated: {doc.lastUpdated}</p>
        )}

        <div className="mt-8">
          <MarkdownContent content={body} />
        </div>
      </main>

      <SiteFooter />
    </div>
  );
}
