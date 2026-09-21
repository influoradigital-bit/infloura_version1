/**
 * The brand colour that tints the Meera workspace.
 *
 * Lives here rather than in `MeeraWorkspace.tsx` so that file keeps exporting
 * only components (react-refresh/only-export-components) and so the rule can be
 * unit-tested without rendering anything.
 *
 * THE ONE LIVE SOURCE reachable from the browser is the `analyze_site`
 * tool_result, which `MeeraChatPanel` threads up through `onFunctionCall` ->
 * `advance` into `stagePayloads.snapshot` (MeeraChatPanel.tsx:881,
 * useMeeraStage.ts:43).
 *
 * There is deliberately no second source to fall back on:
 *   - `GET /meera/brand-profile` does NOT carry a colour — `BrandProfileResponse`
 *     is workspaceId/websiteUrl/analysisStatus/nicheTags/productCatalog/
 *     analysisError (MeeraController.java:199);
 *   - `GET /workspaces/me` carries none either (`WorkspaceMeResponse`, api.ts:1290);
 *   - the colour the backend DOES hold (`brand_color` on
 *     `MeeraContextDtos.ContextResponse`) is served only to the AI service over
 *     `MeeraInternalController`, never to a browser.
 *
 * So when no `analyze_site` result has landed this returns `undefined` and the
 * workspace keeps Meera's default indigo. No colour beats a wrong colour — and
 * a MOCK colour (a fictional company's accent on a real brand's page) is the
 * wrongest of all.
 *
 * ONE FLOW, HUMAN OR AI: the colour comes from the same `analyze_site` result
 * either way. A brand saving its website itself and Meera calling the tool land
 * in the same `stagePayloads` slot, through the same endpoint and the same
 * guards. The AI has no private colour source, and with the AI switched off the
 * workspace still renders — just in the default theme.
 */

/**
 * Narrows a raw `analyze_site` payload to a usable brand hex, or `undefined`.
 *
 * Same defensive narrowing as MeeraChatPanel's `analyzeSiteData`
 * (MeeraChatPanel.tsx:206): this is an `unknown` wire payload, never trusted.
 * The hex SHAPE is validated here too, because `useBrandTheme` writes the raw
 * string straight into the `--brand` CSS custom property (useBrandTheme.ts:151),
 * not just into the derived accent — so an unvalidated string would land in the
 * stylesheet verbatim.
 */
export function liveBrandColorHex(snapshotPayload: unknown): string | undefined {
  if (!snapshotPayload || typeof snapshotPayload !== 'object') return undefined;
  const envelope = snapshotPayload as { success?: unknown; data?: unknown };
  if (envelope.success !== true) return undefined;
  if (!envelope.data || typeof envelope.data !== 'object') return undefined;
  const raw = (envelope.data as { brand_color?: unknown }).brand_color;
  if (typeof raw !== 'string') return undefined;
  const hex = raw.trim();
  return /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(hex) ? hex : undefined;
}
