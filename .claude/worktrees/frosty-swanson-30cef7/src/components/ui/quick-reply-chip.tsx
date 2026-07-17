import { motion, useReducedMotion } from 'framer-motion'

import { cn } from '@/lib/utils'

interface QuickReplyChipProps {
  label: string
  onClick?: () => void
  selected?: boolean
  className?: string
}

/** Quick-reply chip row above the composer. Hover: border/bg to accent-soft. No scale. */
export function QuickReplyChip({ label, onClick, selected, className }: QuickReplyChipProps) {
  const reduceMotion = useReducedMotion()
  const Comp = reduceMotion ? 'button' : motion.button

  return (
    <Comp
      type="button"
      onClick={onClick}
      className={cn(
        'shrink-0 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors duration-150',
        selected
          ? 'border-transparent bg-meera-accent-soft text-meera-accent'
          : 'border-meera-border bg-meera-surface text-meera-text-muted hover:border-meera-accent hover:bg-meera-accent-soft hover:text-meera-accent',
        className,
      )}
      {...(!reduceMotion ? { whileTap: { opacity: 0.8 } } : {})}
    >
      {label}
    </Comp>
  )
}
