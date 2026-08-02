export const DURATION_NORMAL = 0.25

export const EASE_OUT = [0.23, 1, 0.32, 1] as const

export const STAGGER_DEFAULT = 0.05

export const SPRING_MAGNETIC = {
  type: 'spring' as const,
  duration: 0.5,
  bounce: 0.2,
}

export const VIEWPORT_ONCE = {
  once: true,
  margin: '-80px',
} as const
