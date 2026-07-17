import { motion, useReducedMotion } from 'framer-motion'
import type { ComponentPropsWithoutRef, ElementType } from 'react'

import {
  DURATION_NORMAL,
  EASE_OUT,
  VIEWPORT_ONCE,
} from '@/lib/motion-config'
import { cn } from '@/lib/utils'

type FadeUpProps<T extends ElementType = 'div'> = {
  as?: T
  children: React.ReactNode
  className?: string
  delay?: number
  y?: number
} & Omit<ComponentPropsWithoutRef<T>, 'as' | 'children' | 'className'>

export function FadeUp<T extends ElementType = 'div'>({
  as,
  children,
  className,
  delay = 0,
  y = 24,
  ...rest
}: FadeUpProps<T>) {
  const Tag = (as ?? 'div') as ElementType
  const reduceMotion = useReducedMotion()

  if (reduceMotion) {
    return (
      <Tag className={className} {...rest}>
        {children}
      </Tag>
    )
  }

  const MotionTag = motion.create(Tag)

  return (
    <MotionTag
      className={cn(className)}
      initial={{ opacity: 0, y }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={VIEWPORT_ONCE}
      transition={{ duration: DURATION_NORMAL, ease: EASE_OUT, delay }}
      {...rest}
    >
      {children}
    </MotionTag>
  )
}
