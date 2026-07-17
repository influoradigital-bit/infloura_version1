import { motion, useReducedMotion } from 'framer-motion'
import type { PropsWithChildren } from 'react'

import {
  DURATION_NORMAL,
  EASE_OUT,
  STAGGER_DEFAULT,
  VIEWPORT_ONCE,
} from '@/lib/motion-config'
import { cn } from '@/lib/utils'

const containerVariants = {
  hidden: {},
  visible: {
    transition: {
      staggerChildren: STAGGER_DEFAULT,
    },
  },
}

const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: DURATION_NORMAL, ease: EASE_OUT },
  },
}

type StaggerContainerProps = PropsWithChildren<{
  className?: string
  delayChildren?: number
}>

export function StaggerContainer({
  children,
  className,
  delayChildren = 0,
}: StaggerContainerProps) {
  const reduceMotion = useReducedMotion()

  if (reduceMotion) {
    return <div className={className}>{children}</div>
  }

  return (
    <motion.div
      className={cn(className)}
      variants={containerVariants}
      initial="hidden"
      whileInView="visible"
      viewport={VIEWPORT_ONCE}
      transition={{ delayChildren }}
    >
      {children}
    </motion.div>
  )
}

type StaggerItemProps = PropsWithChildren<{
  className?: string
}>

export function StaggerItem({ children, className }: StaggerItemProps) {
  const reduceMotion = useReducedMotion()

  if (reduceMotion) {
    return <div className={className}>{children}</div>
  }

  return (
    <motion.div className={cn(className)} variants={itemVariants}>
      {children}
    </motion.div>
  )
}
