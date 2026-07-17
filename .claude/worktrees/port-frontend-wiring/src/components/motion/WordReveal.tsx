import { motion, useReducedMotion } from 'framer-motion'
import { createElement, type ComponentType, type ElementType, type ReactNode } from 'react'

import { DURATION_NORMAL, EASE_OUT, VIEWPORT_ONCE } from '@/lib/motion-config'
import { cn } from '@/lib/utils'

const WORD_STAGGER = 0.04

type WordRevealProps = {
  text: string
  className?: string
  as?: 'h1' | 'h2' | 'h3' | 'p'
}

export function WordReveal({ text, className, as = 'h1' }: WordRevealProps) {
  const Tag = as as ElementType
  const reduceMotion = useReducedMotion()
  const words = text.split(' ')

  if (reduceMotion) {
    return createElement(Tag, { className }, text)
  }

  const MotionTag = motion.create(Tag) as ComponentType<Record<string, unknown> & { children?: ReactNode }>

  return (
    <MotionTag
      className={cn(className)}
      initial="hidden"
      whileInView="visible"
      viewport={VIEWPORT_ONCE}
      variants={{
        hidden: {},
        visible: {
          transition: { staggerChildren: WORD_STAGGER },
        },
      }}
    >
      {words.map((word, i) => (
        <motion.span
          key={`${word}-${i}`}
          className="inline-block"
          variants={{
            hidden: { opacity: 0, y: 12 },
            visible: {
              opacity: 1,
              y: 0,
              transition: { duration: DURATION_NORMAL, ease: EASE_OUT },
            },
          }}
        >
          {word}
          {i < words.length - 1 ? '\u00A0' : ''}
        </motion.span>
      ))}
    </MotionTag>
  )
}
