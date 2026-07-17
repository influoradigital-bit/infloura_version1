import { motion, useReducedMotion } from 'framer-motion'

import { BrandAvatar } from '@/components/ui/brand-avatar'
import { MEERA_BUBBLE_TRANSITION } from '@/data/motion-tokens'
import { cn } from '@/lib/utils'

export type MessageRole = 'meera' | 'brand'

interface MessageBubbleProps {
  role: MessageRole
  text: string
  brandInitials?: string
  className?: string
}

/** Role-styled chat bubble. Meera = accent-soft, Brand = surface + border. */
export function MessageBubble({ role, text, brandInitials = 'BR', className }: MessageBubbleProps) {
  const reduceMotion = useReducedMotion()
  const isMeera = role === 'meera'

  const content = (
    <div className={cn('flex items-end gap-2', isMeera ? 'justify-start' : 'flex-row-reverse justify-start', className)}>
      {isMeera ? (
        <BrandAvatar initials="M" size="sm" online={false} />
      ) : (
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-meera-surface-2 text-xs font-semibold text-meera-text-muted">
          {brandInitials}
        </span>
      )}
      <div
        className={cn(
          'max-w-[72ch] rounded-xl px-4 py-2.5 text-sm leading-relaxed',
          isMeera
            ? 'bg-meera-accent-soft text-meera-text'
            : 'border border-meera-border bg-meera-surface text-meera-text',
        )}
      >
        {text}
      </div>
    </div>
  )

  if (reduceMotion) return content

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={MEERA_BUBBLE_TRANSITION}
    >
      {content}
    </motion.div>
  )
}
