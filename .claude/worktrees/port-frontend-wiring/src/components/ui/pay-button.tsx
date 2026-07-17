import { useState } from 'react'
import { Check, Loader2 } from 'lucide-react'

import { MEERA_PAY_BUTTON_LABEL } from '@/data/meera-copy'
import { cn } from '@/lib/utils'

interface PayButtonProps {
  label: string
  onPay: () => Promise<void> | void
  disabled?: boolean
  className?: string
}

/**
 * Accent CTA for the money moment. Press = scale(0.97) ease-out (NOT a spring,
 * per spec §5) — handled by the shared button press utility already in the
 * repo's button.tsx (active:scale-[0.97]); this component adds loading→success.
 */
export function PayButton({ label, onPay, disabled, className }: PayButtonProps) {
  const [status, setStatus] = useState<'idle' | 'loading' | 'success'>('idle')

  const handleClick = async () => {
    if (status !== 'idle' || disabled) return
    setStatus('loading')
    await onPay()
    setStatus('success')
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={disabled || status !== 'idle'}
      className={cn(
        'inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-meera-accent px-6 text-sm font-semibold text-white transition-[background-color,box-shadow,opacity] duration-150 ease-out active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-60',
        'hover:bg-meera-accent-hover focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-[var(--meera-accent-glow)]',
        status === 'success' && 'bg-meera-escrow hover:bg-meera-escrow',
        className,
      )}
    >
      {status === 'loading' && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
      {status === 'success' && <Check className="h-4 w-4" aria-hidden="true" />}
      <span aria-live="polite">
        {status === 'idle'
          ? label
          : status === 'loading'
            ? MEERA_PAY_BUTTON_LABEL.loading
            : MEERA_PAY_BUTTON_LABEL.success}
      </span>
    </button>
  )
}
