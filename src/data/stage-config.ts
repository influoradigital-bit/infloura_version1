/**
 * Stage → {title, canvas} map (spec §4 table). Drives LivingCanvas + StageMorph.
 * Function-call trigger names match the mock glue in useMeeraStage.
 */
import { MEERA_STAGE_TITLES, MEERA_STAGE_SUBTITLES } from '@/data/meera-copy'

export type MeeraStageId = 'snapshot' | 'recommend' | 'matching' | 'funding' | 'live'

export type MeeraFunctionCall =
  | 'analyze_site'
  | 'calculate_budget'
  | 'show_creators'
  | 'create_campaign'
  | 'request_payment'
  | 'confirm_launch'

export interface StageConfigEntry {
  id: MeeraStageId
  order: number
  title: string
  subtitle: string
  trigger: MeeraFunctionCall
}

export const STAGE_CONFIG: Record<MeeraStageId, StageConfigEntry> = {
  snapshot: {
    id: 'snapshot',
    order: 1,
    title: MEERA_STAGE_TITLES.snapshot,
    subtitle: MEERA_STAGE_SUBTITLES.snapshot,
    trigger: 'analyze_site',
  },
  recommend: {
    id: 'recommend',
    order: 2,
    title: MEERA_STAGE_TITLES.recommend,
    subtitle: MEERA_STAGE_SUBTITLES.recommend,
    trigger: 'calculate_budget',
  },
  matching: {
    id: 'matching',
    order: 3,
    title: MEERA_STAGE_TITLES.matching,
    subtitle: MEERA_STAGE_SUBTITLES.matching,
    trigger: 'show_creators',
  },
  funding: {
    id: 'funding',
    order: 4,
    title: MEERA_STAGE_TITLES.funding,
    subtitle: MEERA_STAGE_SUBTITLES.funding,
    trigger: 'request_payment',
  },
  live: {
    id: 'live',
    order: 5,
    title: MEERA_STAGE_TITLES.live,
    subtitle: MEERA_STAGE_SUBTITLES.live,
    trigger: 'confirm_launch',
  },
}

export const STAGE_ORDER: MeeraStageId[] = ['snapshot', 'recommend', 'matching', 'funding', 'live']

export function nextStage(current: MeeraStageId): MeeraStageId | null {
  const idx = STAGE_ORDER.indexOf(current)
  if (idx === -1 || idx === STAGE_ORDER.length - 1) return null
  return STAGE_ORDER[idx + 1]
}

/** Reverse lookup of STAGE_CONFIG's `trigger` field — the single source of truth for call→stage. */
export function stageForFunctionCall(call: MeeraFunctionCall): MeeraStageId | null {
  const entry = STAGE_ORDER.map((id) => STAGE_CONFIG[id]).find((e) => e.trigger === call)
  return entry ? entry.id : null
}
