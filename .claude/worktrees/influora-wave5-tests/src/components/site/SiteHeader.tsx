import { Link } from 'react-router-dom';
import { ArrowRight, ChevronDown } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

/**
 * Shared marketing-site nav — used on the homepage and every Tier 2/3 page.
 * Extracted from landing.tsx so nav stays identical across the site instead
 * of drifting page-by-page. LOCKED routes per wiki/website/CEO-DECISIONS.md.
 */

const HOW_IT_WORKS_LINKS = [
  { label: 'For Brands', href: '/how-it-works/brands' },
  { label: 'For Creators', href: '/how-it-works/creators' },
];

const FEATURES_LINKS = [
  { label: 'Escrow Protection', href: '/features/escrow' },
  { label: 'Deal Room', href: '/features/deal-room' },
  { label: 'Hype Campaigns', href: '/features/hype' },
];

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur">
      <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6" aria-label="Main">
        <Link to="/" aria-label="Influora home">
          <InfluoraLogo />
        </Link>

        <div className="hidden items-center gap-1 lg:flex">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" className="gap-1 text-sm font-medium text-muted-foreground">
                How It Works <ChevronDown className="h-3.5 w-3.5" aria-hidden="true" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-48">
              {HOW_IT_WORKS_LINKS.map((item) => (
                <DropdownMenuItem key={item.href} asChild>
                  <Link to={item.href}>{item.label}</Link>
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" className="gap-1 text-sm font-medium text-muted-foreground">
                Features <ChevronDown className="h-3.5 w-3.5" aria-hidden="true" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-48">
              {FEATURES_LINKS.map((item) => (
                <DropdownMenuItem key={item.href} asChild>
                  <Link to={item.href}>{item.label}</Link>
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <Button variant="ghost" className="text-sm font-medium text-muted-foreground" asChild>
            <Link to="/pricing">Pricing</Link>
          </Button>
          <Button variant="ghost" className="text-sm font-medium text-muted-foreground" asChild>
            <Link to="/blog">Blog</Link>
          </Button>
          <Button variant="ghost" className="text-sm font-medium text-muted-foreground" asChild>
            <Link to="/about">About</Link>
          </Button>
        </div>

        <div className="flex items-center gap-2">
          <Button variant="ghost" className="hidden sm:inline-flex" asChild>
            <Link to="/creator/login">I'm a creator</Link>
          </Button>
          <Button className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
            <Link to="/brand/login">
              For brands <ArrowRight className="ml-1 h-4 w-4" aria-hidden="true" />
            </Link>
          </Button>
        </div>
      </nav>
    </header>
  );
}
