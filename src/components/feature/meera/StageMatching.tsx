import { useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { Users } from 'lucide-react'

import { CreatorCard } from '@/components/ui/creator-card'
import { QuickReplyChip } from '@/components/ui/quick-reply-chip'
import { StatPair } from '@/components/ui/stat-pair'
import { StageLoadingState } from '@/components/feature/meera/StageLoadingState'
import { MEERA_STAGGER_ITEM_VARIANTS, meeraStaggerDelay, MEERA_STAGGER_MAX_ITEMS } from '@/data/motion-tokens'
import { MOCK_CREATORS, MOCK_TOTAL_FOUND, MOCK_TOP_MATCHED } from '@/data/meera-mock'
import { isApiLive } from '@/lib/api'
import { isShowCreatorsPayload, type ShowCreatorsPayload } from '@/lib/meera-api'
import { cn } from '@/lib/utils'

interface StageMatchingProps {
  /** Latest `show_creators` tool_result payload for this session, if any. */
  toolResult?: unknown
  className?: string
}

const FILTERS = ['Mumbai', 'Skincare', 'Beauty'] as const

/** Compact row for a live-matched creator — `show_creators` (02 §3) doesn't carry
 * city/niche/avatar/verified, so this intentionally isn't the mock CreatorCard. */
function LiveCreatorRow({ creator }: { creator: ShowCreatorsPayload['creators'][number] }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-meera-border bg-meera-surface p-3">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-meera-surface-2 text-meera-text-muted">
        <Users className="h-4 w-4" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-meera-text">{creator.displayName}</p>
      </div>
      <span className="shrink-0 text-xs font-medium tabular-nums text-meera-text-muted">
        {(creator.followers / 1000).toFixed(creator.followers % 1000 === 0 ? 0 : 1)}k
        <span className="mx-1 text-meera-border-strong">|</span>
        {creator.engagementRate.toFixed(1)}%
      </span>
    </div>
  )
}

/** Stage 3 — verified creator grid, staggered flight-in, live re-filter (T8). */
export function StageMatching({ toolResult, className }: StageMatchingProps) {
  const reduceMotion = useReducedMotion()
  const live = isApiLive()
  const [activeFilter, setActiveFilter] = useState<(typeof FILTERS)[number] | null>(null)

  if (!live) {
    const filtered = activeFilter
      ? MOCK_CREATORS.filter((c) => c.city === activeFilter || c.niche === activeFilter)
      : MOCK_CREATORS
    const capped = filtered.slice(0, MEERA_STAGGER_MAX_ITEMS)

    return (
      <div className={cn('space-y-4', className)}>
        <div className="grid grid-cols-2 gap-3">
          <StatPair label="Total found" value={MOCK_TOTAL_FOUND} formatFn={(n) => `${Math.round(n)}`} />
          <StatPair label="Top matched" value={MOCK_TOP_MATCHED} formatFn={(n) => `${Math.round(n)}`} />
        </div>

        <div className="flex flex-wrap gap-2" role="group" aria-label="Filter creators">
          {FILTERS.map((filter) => (
            <QuickReplyChip
              key={filter}
              label={filter}
              selected={activeFilter === filter}
              onClick={() => setActiveFilter((prev) => (prev === filter ? null : filter))}
            />
          ))}
        </div>

        <div className="grid grid-cols-1 gap-2">
          {reduceMotion ? (
            capped.map((creator) => (
              <CreatorCard
                key={creator.id}
                name={creator.name}
                handle={creator.handle}
                city={creator.city}
                niche={creator.niche}
                followers={creator.followers}
                avatarEmoji={creator.avatarEmoji}
                verified={creator.verified}
              />
            ))
          ) : (
            <AnimatePresence mode="popLayout">
              {capped.map((creator, i) => (
                <motion.div
                  key={creator.id}
                  layout
                  variants={MEERA_STAGGER_ITEM_VARIANTS}
                  initial="hidden"
                  animate="visible"
                  exit={{ opacity: 0, scale: 0.95 }}
                  transition={{ delay: meeraStaggerDelay(i) }}
                >
                  <CreatorCard
                    name={creator.name}
                    handle={creator.handle}
                    city={creator.city}
                    niche={creator.niche}
                    followers={creator.followers}
                    avatarEmoji={creator.avatarEmoji}
                    verified={creator.verified}
                  />
                </motion.div>
              ))}
            </AnimatePresence>
          )}
        </div>
      </div>
    )
  }

  // Live mode
  if (!isShowCreatorsPayload(toolResult)) {
    return <StageLoadingState label="Matching creators…" className={className} />
  }

  const capped = toolResult.creators.slice(0, MEERA_STAGGER_MAX_ITEMS)

  return (
    <div className={cn('space-y-4', className)}>
      <div className="grid grid-cols-2 gap-3">
        <StatPair label="Total found" value={toolResult.matchedTotal} formatFn={(n) => `${Math.round(n)}`} />
        <StatPair label="Top matched" value={toolResult.creators.length} formatFn={(n) => `${Math.round(n)}`} />
      </div>

      {capped.length > 0 ? (
        <div className="grid grid-cols-1 gap-2">
          {reduceMotion ? (
            capped.map((creator) => <LiveCreatorRow key={creator.creatorId} creator={creator} />)
          ) : (
            <AnimatePresence mode="popLayout">
              {capped.map((creator, i) => (
                <motion.div
                  key={creator.creatorId}
                  layout
                  variants={MEERA_STAGGER_ITEM_VARIANTS}
                  initial="hidden"
                  animate="visible"
                  exit={{ opacity: 0, scale: 0.95 }}
                  transition={{ delay: meeraStaggerDelay(i) }}
                >
                  <LiveCreatorRow creator={creator} />
                </motion.div>
              ))}
            </AnimatePresence>
          )}
        </div>
      ) : (
        <p className="rounded-lg border border-dashed border-meera-border bg-meera-surface-2 p-4 text-center text-xs text-meera-text-muted">
          No matching creators found yet.
        </p>
      )}
    </div>
  )
}
