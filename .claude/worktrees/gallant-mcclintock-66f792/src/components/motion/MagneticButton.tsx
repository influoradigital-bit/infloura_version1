/**
 * AUTH HERO ONLY — do not use in app shell, chat, or forms.
 */
import { motion, useReducedMotion } from 'framer-motion'
import { useCallback, useRef, type ComponentProps } from 'react'

import { Button } from '@/components/ui/button'
import { SPRING_MAGNETIC } from '@/lib/motion-config'
import { cn } from '@/lib/utils'

type MagneticButtonProps = ComponentProps<typeof Button> & {
  strength?: number
}

export function MagneticButton({
  strength = 0.35,
  className,
  children,
  ...props
}: MagneticButtonProps) {
  const ref = useRef<HTMLButtonElement>(null)
  const reduceMotion = useReducedMotion()
  const canHover =
    typeof window !== 'undefined' &&
    window.matchMedia('(hover: hover) and (pointer: fine)').matches

  const handleMove = useCallback(
    (e: React.MouseEvent<HTMLButtonElement>) => {
      if (!ref.current || reduceMotion || !canHover) return
      const rect = ref.current.getBoundingClientRect()
      const x = e.clientX - (rect.left + rect.width / 2)
      const y = e.clientY - (rect.top + rect.height / 2)
      ref.current.style.transform = `translate(${x * strength}px, ${y * strength}px) scale(1)`
    },
    [canHover, reduceMotion, strength],
  )

  const handleLeave = useCallback(() => {
    if (!ref.current) return
    ref.current.style.transform = ''
  }, [])

  if (reduceMotion || !canHover) {
    return (
      <Button className={cn('active:scale-[0.97]', className)} {...props}>
        {children}
      </Button>
    )
  }

  return (
    <motion.div
      className="inline-block"
      onMouseMove={handleMove as unknown as React.MouseEventHandler<HTMLDivElement>}
      onMouseLeave={handleLeave}
      transition={SPRING_MAGNETIC}
    >
      <Button
        ref={ref}
        className={cn(
          'transition-[transform,box-shadow,opacity,border-color] duration-150 ease-out active:scale-[0.97]',
          className,
        )}
        {...props}
      >
        {children}
      </Button>
    </motion.div>
  )
}
