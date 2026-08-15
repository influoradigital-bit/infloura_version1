import { useReducedMotion } from 'framer-motion'

import { cn } from '@/lib/utils'
import { cssVars } from '@/lib/css-vars'

interface LockFallbackProps {
  /** true once the lock has fully snapped shut (drives the shackle-down pose) */
  locked: boolean
  className?: string
}

/**
 * SVG/CSS padlock — the DEFAULT escrow-lock visual (handoff §4, spec §5).
 * No WebGL required. Reduced motion renders the final locked pose immediately,
 * no shackle-drop animation.
 */
export function LockFallback({ locked, className }: LockFallbackProps) {
  const reduceMotion = useReducedMotion()
  const showLocked = locked || reduceMotion

  return (
    <div className={cn('relative flex items-center justify-center', className)} aria-hidden="true">
      <svg
        viewBox="0 0 120 120"
        className="h-24 w-24"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
      >
        {/* Shackle */}
        <path
          d="M40 52V40a20 20 0 0 1 40 0v12"
          stroke={showLocked ? 'var(--meera-escrow)' : 'var(--meera-border-strong)'}
          strokeWidth={7}
          strokeLinecap="round"
          fill="none"
          ref={
            reduceMotion
              ? undefined
              : cssVars({
                  '--lock-shackle-transform': showLocked ? 'translateY(0px)' : 'translateY(-6px)',
                  '--lock-shackle-transition':
                    'transform 420ms cubic-bezier(0.23,1,0.32,1), stroke 300ms ease-out',
                })
          }
          className={cn(
            !reduceMotion &&
              '[transform-origin:60px_52px] [transform:var(--lock-shackle-transform)] [transition:var(--lock-shackle-transition)]',
          )}
        />
        {/* Body */}
        <rect
          x="28"
          y="50"
          width="64"
          height="50"
          rx="10"
          fill={showLocked ? 'var(--meera-escrow-soft)' : 'var(--meera-surface-2)'}
          stroke={showLocked ? 'var(--meera-escrow)' : 'var(--meera-border-strong)'}
          strokeWidth={3}
          className={cn(!reduceMotion && '[transition:fill_300ms_ease-out,stroke_300ms_ease-out]')}
        />
        {/* Keyhole */}
        <circle cx="60" cy="72" r="6" fill={showLocked ? 'var(--meera-escrow)' : 'var(--meera-border-strong)'} />
        <rect x="57" y="76" width="6" height="12" rx="2" fill={showLocked ? 'var(--meera-escrow)' : 'var(--meera-border-strong)'} />
      </svg>
    </div>
  )
}
