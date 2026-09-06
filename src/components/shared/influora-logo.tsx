import { cn } from '@/lib/utils';

interface InfluoraLogoProps {
  size?: 'sm' | 'md' | 'lg';
  showName?: boolean;
  className?: string;
}

/**
 * Brand mark asset. Lives in `public/`, so Vite serves it from the site root in
 * both dev and build. 99x99 transparent PNG, orchid #9B58B5 — it carries its own
 * colour and reads on the light and dark grounds without a container behind it.
 *
 * The full lockup (`/brand/logo-lockup-transparent.png`) is deliberately NOT used
 * here: its wordmark is #1A237E navy, which is ~1.05:1 against the dark theme's
 * #2A2838 background. A dark-ground lockup variant does not exist yet.
 */
const MARK_SRC = '/brand/mark.png';

/**
 * `mark` is the rendered box; `px` is the same value as a number so the <img>
 * can carry intrinsic width/height attributes and reserve its space before the
 * asset loads. The header logo is above the fold on every marketing page, so a
 * late-arriving image here is a direct CLS cost.
 */
const sizeConfig = {
  sm: { mark: 'h-7 w-7', px: 28, name: 'text-sm' },
  md: { mark: 'h-8 w-8', px: 32, name: 'text-base' },
  lg: { mark: 'h-10 w-10', px: 40, name: 'text-xl' },
} as const;

export function InfluoraLogo({ size = 'md', showName = true, className }: InfluoraLogoProps) {
  const config = sizeConfig[size];

  return (
    <div className={cn('flex items-center gap-2.5', className)}>
      <img
        src={MARK_SRC}
        /*
         * With the wordmark rendered beside it the mark is decorative — the text
         * already announces the brand. An alt of "Influora" here would make the
         * accessible name "Influora Influora" and break the exact-name queries in
         * creator-layout-logo-home.test.tsx. With showName off the image is the
         * only thing left, so it has to carry the name itself.
         */
        alt={showName ? '' : 'Influora'}
        width={config.px}
        height={config.px}
        loading="eager"
        decoding="async"
        draggable={false}
        className={cn('shrink-0 select-none object-contain', config.mark)}
      />
      {showName && (
        <span className={cn('font-semibold text-foreground tracking-tight', config.name)}>
          Influora
        </span>
      )}
    </div>
  );
}
