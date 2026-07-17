import { useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'

import { CreatorCard } from '@/components/ui/creator-card'
import { QuickReplyChip } from '@/components/ui/quick-reply-chip'
import { StatPair } from '@/components/ui/stat-pair'
import { MEERA_STAGGER_ITEM_VARIANTS, meeraStaggerDelay, MEERA_STAGGER_MAX_ITEMS } from '@/data/motion-tokens'
import { MOCK_CREATORS, MOCK_TOTAL_FOUND, MOCK_TOP_MATCHED } from '@/data/meera-mock'
import { cn } from '@/lib/utils'

interface StageMatchingProps {
  className?: string
}

const FILTERS = ['Mumbai', 'Skincare', 'Beauty'] as const

/** Stage 3 — verified creator grid, staggered flight-in, live re-filter (T8). */
export function StageMatching({ className }: StageMatchingProps) {
  const reduceMotion = useReducedMotion()
  const [activeFilter, setActiveFilter] = useState<(typeof FILTERS)[number] | null>(null)

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
