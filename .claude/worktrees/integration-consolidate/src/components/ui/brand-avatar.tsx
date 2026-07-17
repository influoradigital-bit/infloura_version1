import { cn } from '@/lib/utils'

interface BrandAvatarProps {
  initials: string
  online?: boolean
  size?: 'sm' | 'md' | 'lg'
  className?: string
}

const SIZE_CLASS: Record<NonNullable<BrandAvatarProps['size']>, string> = {
  sm: 'h-7 w-7 text-xs',
  md: 'h-9 w-9 text-sm',
  lg: 'h-12 w-12 text-base',
}

/**
 * Brand-gradient ring avatar with an online dot. The gradient uses the
 * --brand CSS var (2-stop: --brand → +12% L) injected by useBrandTheme —
 * a flat brand color still reads dimensional.
 */
export function BrandAvatar({ initials, online = true, size = 'md', className }: BrandAvatarProps) {
  return (
    <span className={cn('relative inline-flex shrink-0', className)}>
      <span
        className={cn(
          'flex items-center justify-center rounded-full font-semibold text-white',
          SIZE_CLASS[size],
        )}
        style={{
          background: 'linear-gradient(135deg, var(--meera-accent), var(--meera-accent-hover))',
        }}
        aria-hidden="true"
      >
        {initials}
      </span>
      {online && (
        <span
          className="absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full border-2 border-meera-surface bg-meera-escrow"
          aria-hidden="true"
        />
      )}
    </span>
  )
}
