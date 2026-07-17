import { useCallback, useState } from 'react'

import type { MeeraFunctionCall, MeeraStageId } from '@/data/stage-config'
import { stageForFunctionCall } from '@/data/stage-config'

/**
 * Maps mock function-call results to the active Living Canvas stage.
 * Mock-first (handoff §9 item 9) — real APIs land post-M2; `lib/api.ts` has
 * no AI endpoints today. This hook is the seam: swap `advance` internals for
 * a real function-call dispatcher later without touching consumers.
 */

/**
 * Latest `tool_result.data` seen per stage, keyed by `MeeraStageId`. Stays
 * `unknown` here — narrowing into a typed payload (`ShowCreatorsPayload`,
 * `CalculateBudgetPayload`, ...) is each stage component's job via the
 * guards in `lib/meera-api.ts`, same boundary pattern as `ToolResultRenderer`.
 * Never populated in mock mode (mock turns call `advance` with no `data`).
 */
export type MeeraStagePayloads = Partial<Record<MeeraStageId, unknown>>

export interface UseMeeraStageResult {
  stage: MeeraStageId
  isPaid: boolean
  isLive: boolean
  /** Latest live tool_result payload per stage (04 §4) — empty in mock mode. */
  stagePayloads: MeeraStagePayloads
  advance: (call: MeeraFunctionCall, data?: unknown) => void
  setStageDirect: (stage: MeeraStageId) => void
  markPaid: () => void
}

export function useMeeraStage(initial: MeeraStageId = 'snapshot'): UseMeeraStageResult {
  const [stage, setStage] = useState<MeeraStageId>(initial)
  const [isPaid, setIsPaid] = useState(false)
  const [stagePayloads, setStagePayloads] = useState<MeeraStagePayloads>({})

  const advance = useCallback((call: MeeraFunctionCall, data?: unknown) => {
    const next = stageForFunctionCall(call)
    if (!next) return
    setStage(next)
    if (data !== undefined) {
      setStagePayloads((prev) => ({ ...prev, [next]: data }))
    }
  }, [])

  const setStageDirect = useCallback((next: MeeraStageId) => {
    setStage(next)
  }, [])

  const markPaid = useCallback(() => setIsPaid(true), [])

  return {
    stage,
    isPaid,
    isLive: stage === 'live',
    stagePayloads,
    advance,
    setStageDirect,
    markPaid,
  }
}
