import { useReducedMotion } from 'framer-motion'

import { cn } from '@/lib/utils'

type CanvasFallbackProps = {
  variant?: 'auth' | 'discover' | 'portfolio'
  className?: string
}

const variantGradients: Record<NonNullable<CanvasFallbackProps['variant']>, string> = {
  auth: 'from-[#ede9fe]/90 via-[#f0ebfa] to-[#e8f4fc]/80',
  discover: 'from-[#ddd6fe]/85 via-[#f0ebfa] to-[#c4b5fd]/60',
  portfolio: 'from-[#f0ebfa] via-[#ede9fe]/70 to-[#7ec8e8]/40',
}

/** Static Lilac Mist fallback when WebGL or motion is unavailable */
export function CanvasFallback({ variant = 'auth', className }: CanvasFallbackProps) {
  const reduceMotion = useReducedMotion()
  const staticImage = `/images/${variant}/hero-static.png`

  return (
    <div
      className={cn('relative h-full w-full min-h-[320px] overflow-hidden', className)}
      aria-hidden
    >
      <div className={cn('absolute inset-0 bg-gradient-to-br', variantGradients[variant])} />
      <div className="absolute inset-0 bg-auth-mesh opacity-80" />
      <img
        src={staticImage}
        alt=""
        className="absolute inset-0 h-full w-full object-cover opacity-0"
        onLoad={(e) => {
          e.currentTarget.classList.remove('opacity-0')
          e.currentTarget.classList.add('opacity-40')
        }}
        onError={(e) => {
          e.currentTarget.remove()
        }}
      />
      {!reduceMotion && (
        <>
          <div className="absolute -left-[10%] -top-[15%] h-[55%] w-[55%] rounded-full bg-[#9b8cf2]/35 blur-[100px]" />
          <div className="absolute -right-[5%] top-[10%] h-[50%] w-[50%] rounded-full bg-[#c4b5fd]/30 blur-[100px]" />
          <div className="absolute bottom-[-20%] left-[20%] h-[60%] w-[60%] rounded-full bg-[#7ec8e8]/25 blur-[100px]" />
        </>
      )}
    </div>
  )
}
