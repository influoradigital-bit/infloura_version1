import { useCallback, useState } from 'react'

import type { MeeraFunctionCall, MeeraStageId } from '@/data/stage-config'

/**
 * Maps mock function-call results to the active Living Canvas stage.
 * Mock-first (handoff §9 item 9) — real APIs land post-M2; `lib/api.ts` has
 * no AI endpoints today. This hook is the seam: swap `advance` internals for
 * a real function-call dispatcher later without touching consumers.
 */

const CALL_TO_STAGE: Record<MeeraFunctionCall, MeeraStageId> = {
  analyze_site: 'snapshot',
  calculate_budget: 'recommend',
  show_creators: 'matching',
  request_payment: 'funding',
  confirm_launch: 'live',
}

export interface UseMeeraStageResult {
  stage: MeeraStageId
  isPaid: boolean
  isLive: boolean
  advance: (call: MeeraFunctionCall) => void
  setStageDirect: (stage: MeeraStageId) => void
  markPaid: () => void
}

export function useMeeraStage(initial: MeeraStageId = 'snapshot'): UseMeeraStageResult {
  const [stage, setStage] = useState<MeeraStageId>(initial)
  const [isPaid, setIsPaid] = useState(false)

  const advance = useCallback((call: MeeraFunctionCall) => {
    const next = CALL_TO_STAGE[call]
    if (next) setStage(next)
  }, [])

  const setStageDirect = useCallback((next: MeeraStageId) => {
    setStage(next)
  }, [])

  const markPaid = useCallback(() => setIsPaid(true), [])

  return {
    stage,
    isPaid,
    isLive: stage === 'live',
    advance,
    setStageDirect,
    markPaid,
  }
}
