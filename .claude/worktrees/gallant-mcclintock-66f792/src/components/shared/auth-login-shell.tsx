import * as React from 'react';
import { Link } from 'react-router-dom';
import { Check } from 'lucide-react';
import { InfluoraLogo } from '@/components/shared/influora-logo';
import { cn } from '@/lib/utils';

export type AuthLoginShellProps = {
  children: React.ReactNode;
  heroTitle: string;
  heroSubtitle: string;
  heroBullets?: string[];
  accent?: 'brand' | 'creator';
  className?: string;
};

export function AuthLoginShell({
  children,
  heroTitle,
  heroSubtitle,
  heroBullets,
  accent = 'brand',
  className,
}: AuthLoginShellProps) {
  const workspaceLabel = accent === 'creator' ? 'Creator workspace' : 'Brand workspace';

  return (
    <div className={cn('flex min-h-screen flex-col auth-gradient', className)}>
      <header className="flex items-center justify-between px-5 py-5 sm:px-8">
        <InfluoraLogo size="lg" />
        <Link
          to="/support"
          className="text-sm text-muted-foreground transition-[color] duration-150 ease-out hover:text-primary"
        >
          Need help?
        </Link>
      </header>

      <main className="flex flex-1 items-center justify-center px-4 pb-10 sm:px-6">
        <div className="grid w-full max-w-5xl items-center gap-10 lg:grid-cols-2 lg:gap-16">
          {/* Form */}
          <div className="w-full justify-self-center lg:max-w-md lg:justify-self-end">
            <div className="rounded-xl border border-border/60 bg-card p-6 shadow-sm sm:p-8">
              <p className="mb-6 text-xs font-medium uppercase tracking-wider text-primary">
                {workspaceLabel}
              </p>
              {children}
            </div>
          </div>

          {/* Static value prop — desktop */}
          <div className="hidden lg:block lg:max-w-md">
            <h2 className="text-3xl font-semibold leading-tight tracking-tight text-foreground">
              {heroTitle}
            </h2>
            <p className="mt-4 text-base leading-relaxed text-muted-foreground">{heroSubtitle}</p>
            {heroBullets && heroBullets.length > 0 && (
              <ul className="mt-8 space-y-3">
                {heroBullets.map((item) => (
                  <li key={item} className="flex items-start gap-3 text-sm text-foreground">
                    <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                      <Check className="h-3 w-3" strokeWidth={2.5} />
                    </span>
                    <span className="leading-snug">{item}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </main>
    </div>
  );
}
