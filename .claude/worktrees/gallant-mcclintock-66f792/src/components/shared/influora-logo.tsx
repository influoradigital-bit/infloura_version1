import { Sparkles } from 'lucide-react';
import { cn } from '@/lib/utils';

interface InfluoraLogoProps {
  size?: 'sm' | 'md' | 'lg';
  showName?: boolean;
  className?: string;
}

const sizeConfig = {
  sm: {
    box: 'h-7 w-7 rounded-md',
    icon: 'h-3.5 w-3.5',
    name: 'text-sm',
  },
  md: {
    box: 'h-8 w-8 rounded-lg',
    icon: 'h-4 w-4',
    name: 'text-base',
  },
  lg: {
    box: 'h-10 w-10 rounded-xl',
    icon: 'h-5 w-5',
    name: 'text-xl',
  },
} as const;

export function InfluoraLogo({ size = 'md', showName = true, className }: InfluoraLogoProps) {
  const config = sizeConfig[size];

  return (
    <div className={cn('flex items-center gap-2.5', className)}>
      <div
        className={cn(
          'flex items-center justify-center shrink-0 bg-gradient-to-br from-primary via-primary to-accent',
          'ring-1 ring-primary/20 shadow-[0_4px_14px_-4px] shadow-primary/40',
          config.box,
        )}
      >
        <Sparkles className={cn('text-primary-foreground', config.icon)} strokeWidth={2.35} />
      </div>
      {showName && (
        <span className={cn('font-semibold text-foreground tracking-tight', config.name)}>
          Influora
        </span>
      )}
    </div>
  );
}
