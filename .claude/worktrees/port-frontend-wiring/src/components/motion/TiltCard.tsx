import { motion, useReducedMotion, useSpring } from 'framer-motion'
import { useCallback, useRef, type PropsWithChildren } from 'react'

import { cn } from '@/lib/utils'

type TiltCardProps = PropsWithChildren<{
  className?: string
  maxAngle?: number
}>

export function TiltCard({ children, className, maxAngle = 6 }: TiltCardProps) {
  const ref = useRef<HTMLDivElement>(null)
  const reduceMotion = useReducedMotion()
  const rotateX = useSpring(0, { stiffness: 300, damping: 30 })
  const rotateY = useSpring(0, { stiffness: 300, damping: 30 })
  const glareX = useSpring(50, { stiffness: 300, damping: 30 })
  const glareY = useSpring(50, { stiffness: 300, damping: 30 })

  const canHover =
    typeof window !== 'undefined' &&
    window.matchMedia('(hover: hover) and (pointer: fine)').matches

  const handleMove = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (!ref.current || reduceMotion || !canHover) return
      const rect = ref.current.getBoundingClientRect()
      const x = (e.clientX - rect.left) / rect.width
      const y = (e.clientY - rect.top) / rect.height
      rotateX.set((0.5 - y) * maxAngle)
      rotateY.set((x - 0.5) * maxAngle)
      glareX.set(x * 100)
      glareY.set(y * 100)
    },
    [canHover, glareX, glareY, maxAngle, reduceMotion, rotateX, rotateY],
  )

  const handleLeave = useCallback(() => {
    rotateX.set(0)
    rotateY.set(0)
    glareX.set(50)
    glareY.set(50)
  }, [glareX, glareY, rotateX, rotateY])

  if (reduceMotion || !canHover) {
    return (
      <div
        className={cn(
          'rounded-xl border bg-card shadow-sm transition-[box-shadow] duration-200',
          !reduceMotion && canHover === false && 'shadow-md',
          className,
        )}
      >
        {children}
      </div>
    )
  }

  return (
    <motion.div
      ref={ref}
      className={cn('relative rounded-xl border bg-card shadow-sm', className)}
      style={{
        perspective: 1000,
        transformStyle: 'preserve-3d',
        rotateX,
        rotateY,
      }}
      onMouseMove={handleMove}
      onMouseLeave={handleLeave}
    >
      <motion.div
        aria-hidden
        className="pointer-events-none absolute inset-0 rounded-xl opacity-[0.08]"
        style={{
          background: `radial-gradient(circle at ${glareX}% ${glareY}%, white, transparent 60%)`,
        }}
      />
      {children}
    </motion.div>
  )
}
