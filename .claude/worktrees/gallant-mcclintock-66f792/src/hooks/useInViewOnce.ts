import { useInView, useReducedMotion } from 'framer-motion'
import { useRef } from 'react'

export function useInViewOnce(options?: { margin?: string }) {
  const ref = useRef<HTMLElement>(null)
  const reduceMotion = useReducedMotion()
  const isInViewRaw = useInView(ref, {
    once: true,
    margin: (options?.margin ?? '-80px') as `${number}px`,
  })

  return {
    ref,
    isInView: reduceMotion ? true : isInViewRaw,
  }
}
