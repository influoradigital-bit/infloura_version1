import { Link } from 'react-router-dom';
import { Mail, Phone } from 'lucide-react';

import { InfluoraLogo } from '@/components/shared/influora-logo';
import { COMPANY } from '@/lib/company';

/**
 * Shared marketing-site footer — used on the homepage and every Tier 2/3
 * page. Extracted from landing.tsx. LOCKED routes per
 * wiki/website/CEO-DECISIONS.md §URL structure.
 *
 * Company/legal block sources every value from `COMPANY` (src/lib/company.ts)
 * — never hardcode CIN/GSTIN/contact details here.
 */

const FOOTER_NAV: Array<{ heading: string; links: Array<{ label: string; href: string }> }> = [
  {
    heading: 'Product',
    links: [
      { label: 'How It Works — Brands', href: '/how-it-works/brands' },
      { label: 'How It Works — Creators', href: '/how-it-works/creators' },
      { label: 'Pricing', href: '/pricing' },
      { label: 'Escrow Protection', href: '/features/escrow' },
      { label: 'Deal Room', href: '/features/deal-room' },
      { label: 'Hype Campaigns', href: '/features/hype' },
    ],
  },
  {
    heading: 'Resources',
    links: [
      { label: 'Blog', href: '/blog' },
      { label: 'Support / FAQ', href: '/contact' },
      { label: 'KYC Policy', href: '/kyc' },
      { label: 'TDS Policy', href: '/tds' },
    ],
  },
  {
    heading: 'Legal',
    links: [
      { label: 'Terms of Service', href: '/terms' },
      { label: 'Privacy Policy', href: '/privacy' },
      { label: 'Refund & Escrow Policy', href: '/refund-policy' },
      { label: 'Dispute Resolution', href: '/disputes' },
      { label: 'Grievance Redressal', href: '/grievance' },
      { label: 'Advertising Disclosure', href: '/disclosure' },
    ],
  },
  {
    heading: 'Company',
    links: [
      { label: 'About Us', href: '/about' },
      { label: 'Contact Us', href: '/contact' },
    ],
  },
];

export function SiteFooter() {
  return (
    <footer className="border-t border-border/60 py-14">
      <div className="mx-auto max-w-6xl px-6">
        <div className="grid gap-8 sm:grid-cols-2 lg:grid-cols-5">
          <div className="sm:col-span-2 lg:col-span-1">
            <InfluoraLogo size="sm" />
            <p className="mt-3 max-w-[220px] text-xs text-muted-foreground">
              Escrow-protected influencer marketing for Indian brands and creators.
            </p>
          </div>
          {FOOTER_NAV.map((column) => (
            <div key={column.heading}>
              <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                {column.heading}
              </p>
              <ul className="mt-3 space-y-2.5">
                {column.links.map((link) => (
                  <li key={link.href + link.label}>
                    <Link to={link.href} className="text-sm text-muted-foreground hover:text-foreground">
                      {link.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        {/* Registered-entity block — CIN/GSTIN are a legal display requirement for
            Indian companies. Every value sourced from COMPANY (src/lib/company.ts);
            registeredAddress falls back to the state until Swapnil provides the
            full registered office address. */}
        <div className="mt-10 border-t border-border/60 pt-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between">
            <div className="text-xs text-muted-foreground">
              <p className="font-medium text-foreground">{COMPANY.legalName}</p>
              <p className="mt-1">
                CIN: <span className="font-mono">{COMPANY.cin}</span>
              </p>
              <p className="mt-0.5">
                GSTIN: <span className="font-mono">{COMPANY.gstin}</span>
              </p>
              <p className="mt-0.5">{COMPANY.registeredAddress || `Registered in ${COMPANY.state}, India`}</p>
            </div>
            <div className="flex flex-col gap-1.5 text-xs text-muted-foreground sm:items-end">
              <a
                href={`mailto:${COMPANY.email}`}
                className="inline-flex items-center gap-1.5 hover:text-foreground"
              >
                <Mail className="h-3.5 w-3.5" aria-hidden="true" />
                {COMPANY.email}
              </a>
              <a href={`tel:${COMPANY.phoneHref}`} className="inline-flex items-center gap-1.5 hover:text-foreground">
                <Phone className="h-3.5 w-3.5" aria-hidden="true" />
                {COMPANY.phone}
              </a>
            </div>
          </div>
        </div>

        <div className="mt-6 flex flex-col items-center justify-between gap-4 border-t border-border/60 pt-6 sm:flex-row">
          <p className="text-xs text-muted-foreground">© 2026 {COMPANY.legalName}</p>
          <nav className="flex flex-wrap justify-center gap-x-5 gap-y-2 text-xs text-muted-foreground" aria-label="Footer legal">
            <Link to="/terms" className="hover:text-foreground">Terms</Link>
            <Link to="/privacy" className="hover:text-foreground">Privacy</Link>
            <Link to="/refund-policy" className="hover:text-foreground">Refund/Escrow</Link>
            <Link to="/disputes" className="hover:text-foreground">Disputes</Link>
            <Link to="/grievance" className="hover:text-foreground">Grievance</Link>
            <Link to="/kyc" className="hover:text-foreground">KYC</Link>
            <Link to="/tds" className="hover:text-foreground">TDS</Link>
            <Link to="/disclosure" className="hover:text-foreground">Ad Disclosure</Link>
          </nav>
        </div>
      </div>
    </footer>
  );
}
