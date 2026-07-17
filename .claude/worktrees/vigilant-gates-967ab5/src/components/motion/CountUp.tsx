import { useReducedMotion } from 'framer-motion'
import { useEffect, useState } from 'react'

import { useInViewOnce } from '@/hooks/useInViewOnce'
import { formatINR } from '@/lib/utils'
import { cn } from '@/lib/utils'

type CountUpProps = {
  value: number
  duration?: number
  prefix?: string
  suffix?: string
  className?: string
  formatFn?: (n: number) => string
  /** Viewport margin for the in-view trigger — use '0px' for edge-hugging elements */
  viewMargin?: string
}

export function CountUp({
  value,
  duration = 1.2,
  prefix,
  suffix,
  className,
  formatFn,
  viewMargin,
}: CountUpProps) {
  const { ref, isInView } = useInViewOnce(viewMargin ? { margin: viewMargin } : undefined)
  const reduceMotion = useReducedMotion()
  const [display, setDisplay] = useState(reduceMotion ? value : 0)

  const formatter =
    formatFn ??
    ((n: number) => {
      if (prefix === '₹' || prefix === undefined) {
        return formatINR(n)
      }
      return `${prefix ?? ''}${Math.round(n).toLocaleString('en-IN')}${suffix ?? ''}`
    })

  useEffect(() => {
    if (reduceMotion) {
      setDisplay(value)
      return
    }
    if (!isInView) return

    let frame: number
    const start = performance.now()

    const tick = (now: number) => {
      const progress = Math.min((now - start) / (duration * 1000), 1)
      const eased = 1 - Math.pow(1 - progress, 3)
      setDisplay(value * eased)
      if (progress < 1) {
        frame = requestAnimationFrame(tick)
      }
    }

    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [duration, isInView, reduceMotion, value])

  return (
    <span ref={ref as React.RefObject<HTMLSpanElement>} className={cn(className)}>
      {formatter(display)}
    </span>
  )
}
