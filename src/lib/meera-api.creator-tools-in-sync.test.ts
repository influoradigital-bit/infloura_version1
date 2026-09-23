/**
 * The app's creator-tool list must match the one influora-ai offers Meera.
 *
 * WHY THIS EXISTS. `isCreatorToolName` silently drops any tool name the app does not know, so a
 * tool added on the AI/backend side alone produces a chat that shows NOTHING while Meera is
 * working — no result card, and (since the redesign) no step in the work trail either. That is
 * exactly what happened when `get_todays_topics` and `plan_my_week` were added: both services
 * knew about them, the app did not, and every test on both sides still passed.
 *
 * The Python file is the source of truth here because it is what the model is actually offered.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { CREATOR_TOOL_NAMES, isCreatorToolName } from './meera-api';

const SCHEMAS = join(process.cwd(), 'influora-ai', 'app', 'tools', 'creator_schemas.py');

/** The names inside influora-ai's `CREATOR_TOOL_NAMES` tuple, resolved through its constants. */
function pythonCreatorToolNames(): string[] {
  const source = readFileSync(SCHEMAS, 'utf8');

  const constants = new Map<string, string>();
  for (const [, name, value] of source.matchAll(/^([A-Z_]+)\s*=\s*"([a-z_]+)"/gm)) {
    constants.set(name, value);
  }

  const tuple = source.match(/CREATOR_TOOL_NAMES:[^=]*=\s*\(([\s\S]*?)\)/);
  expect(tuple, 'CREATOR_TOOL_NAMES tuple not found in creator_schemas.py').toBeTruthy();

  return tuple![1]
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0 && !entry.startsWith('#'))
    .map((entry) => constants.get(entry) ?? entry.replace(/^["']|["']$/g, ''));
}

describe('creator tool names: app vs influora-ai', () => {
  it('knows every tool the AI service offers Meera', () => {
    const python = pythonCreatorToolNames();
    expect(python.length).toBeGreaterThanOrEqual(6);
    const unknownToTheApp = python.filter((name) => !isCreatorToolName(name));
    expect(unknownToTheApp, 'these tool calls would show as nothing in the chat').toEqual([]);
  });

  it('claims no tool the AI service does not offer', () => {
    const python = new Set(pythonCreatorToolNames());
    const extra = CREATOR_TOOL_NAMES.filter((name) => !python.has(name));
    expect(extra, 'the app would render a card for a tool Meera is never given').toEqual([]);
  });
});
