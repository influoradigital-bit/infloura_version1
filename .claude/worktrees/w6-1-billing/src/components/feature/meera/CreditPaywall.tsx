import { Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { MEERA_PAYWALL } from '@/data/meera-copy'
import { cn } from '@/lib/utils'

interface CreditPaywallProps {
  onFund: () => void
  className?: string
}

/** Soft empty-state wall (PRD §7). An invitation, not an apology. */
export function CreditPaywall({ onFund, className }: CreditPaywallProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center gap-3 rounded-xl border border-meera-border bg-meera-surface-2 p-6 text-center',
        className,
      )}
    >
      <span className="flex h-10 w-10 items-center justify-center rounded-full bg-meera-accent-soft text-meera-accent">
        <Sparkles className="h-5 w-5" aria-hidden="true" />
      </span>
      <div>
        <p className="text-sm font-semibold text-meera-text">{MEERA_PAYWALL.title}</p>
        <p className="text-sm text-meera-text-muted">{MEERA_PAYWALL.body}</p>
      </div>
      <Button onClick={onFund} className="bg-meera-accent text-white hover:bg-meera-accent-hover">
        {MEERA_PAYWALL.cta}
      </Button>
    </div>
  )
}
