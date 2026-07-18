import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, ChevronDown, Menu } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet';

/**
 * Shared marketing-site nav — used on the homepage and every Tier 2/3 page.
 * Extracted from landing.tsx so nav stays identical across the site instead
 * of drifting page-by-page. LOCKED routes per wiki/website/CEO-DECISIONS.md.
 *
 * Below lg the link set moves into a Sheet (hamburger) — previously the links
 * simply vanished on mobile, which made every Tier 2/3 page unreachable
 * without typing the URL.
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

const TOP_LINKS = [
  { label: 'Pricing', href: '/pricing' },
  { label: 'Blog', href: '/blog' },
  { label: 'About', href: '/about' },
];

function MobileNav() {
  const [open, setOpen] = useState(false);

  const close = () => setOpen(false);

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button variant="ghost" size="icon" className="lg:hidden" aria-label="Open navigation menu">
          <Menu className="h-5 w-5" aria-hidden="true" />
        </Button>
      </SheetTrigger>
      <SheetContent side="right" className="w-80 overflow-y-auto">
        <SheetHeader className="text-left">
          <SheetTitle>
            <InfluoraLogo />
          </SheetTitle>
        </SheetHeader>
        <nav className="mt-6 flex flex-col gap-6" aria-label="Mobile">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              How It Works
            </p>
            <div className="mt-2 flex flex-col">
              {HOW_IT_WORKS_LINKS.map((item) => (
                <Link
                  key={item.href}
                  to={item.href}
                  onClick={close}
                  className="rounded-md px-2 py-2 text-sm font-medium hover:bg-accent"
                >
                  {item.label}
                </Link>
              ))}
            </div>
          </div>
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Features
            </p>
            <div className="mt-2 flex flex-col">
              {FEATURES_LINKS.map((item) => (
                <Link
                  key={item.href}
                  to={item.href}
                  onClick={close}
                  className="rounded-md px-2 py-2 text-sm font-medium hover:bg-accent"
                >
                  {item.label}
                </Link>
              ))}
            </div>
          </div>
          <div className="flex flex-col">
            {TOP_LINKS.map((item) => (
              <Link
                key={item.href}
                to={item.href}
                onClick={close}
                className="rounded-md px-2 py-2 text-sm font-medium hover:bg-accent"
              >
                {item.label}
              </Link>
            ))}
          </div>
          <div className="flex flex-col gap-2 border-t border-border/60 pt-5">
            <Button className="bg-accent-foreground text-white hover:bg-accent-foreground/90" asChild>
              <Link to="/brand/login" onClick={close}>
                For brands <ArrowRight className="ml-1 h-4 w-4" aria-hidden="true" />
              </Link>
            </Button>
            <Button variant="outline" asChild>
              <Link to="/creator/login" onClick={close}>
                I'm a creator
              </Link>
            </Button>
          </div>
        </nav>
      </SheetContent>
    </Sheet>
  );
}

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

          {TOP_LINKS.map((item) => (
            <Button
              key={item.href}
              variant="ghost"
              className="text-sm font-medium text-muted-foreground"
              asChild
            >
              <Link to={item.href}>{item.label}</Link>
            </Button>
          ))}
        </div>

        <div className="flex items-center gap-2">
          <Button variant="ghost" className="hidden sm:inline-flex" asChild>
            <Link to="/creator/login">I'm a creator</Link>
          </Button>
          <Button
            className="hidden bg-accent-foreground text-white hover:bg-accent-foreground/90 sm:inline-flex"
            asChild
          >
            <Link to="/brand/login">
              For brands <ArrowRight className="ml-1 h-4 w-4" aria-hidden="true" />
            </Link>
          </Button>
          <MobileNav />
        </div>
      </nav>
    </header>
  );
}
