/**
 * "Ask Meera" pre-seed contract (SaaS help layer #3).
 *
 * BLOCKED per TASK-ananya-saas-help-layer.md: "Confirm with me the mechanism
 * for pre-seeding Meera (query param, route state, or store action) before
 * wiring — I want it consistent with how Meera already receives context."
 *
 * Audit finding: /brand/meera (MeeraWorkspace -> MeeraChatPanel) currently
 * runs a FIXED scripted turn engine off MEERA_CONVERSATION_SCRIPT
 * (data/meera-mock.ts) — it does not accept a freeform starter prompt today,
 * and there is no existing pre-seed mechanism (no query param / route state
 * / store action reads context in). So "reuse THAT mechanism" has nothing to
 * reuse yet — this is a real product decision, not a wiring detail:
 *   (a) add a query-param entry point that injects a real freeform turn
 *       ahead of the scripted script, or
 *   (b) map the starter prompt to the closest existing scripted turn, or
 *       (c) something else Priya/the Meera spec owner prefers.
 *
 * This constant is the query-param CONTRACT the Help entry points use today
 * (brand-help.tsx, and the Help menu in brand-layout.tsx) so both call sites
 * agree on one shape. /brand/meera does not yet read it — MeeraWorkspace
 * needs to be updated once Priya confirms the mechanism, per the task rule
 * "don't invent a second entry path."
 */
export const MEERA_HELP_PRESEED_PARAM = 'ask';

export const MEERA_HELP_PRESEED_PROMPT =
  'How does Influora work — walk me through campaigns, deal rooms, and payments.';
