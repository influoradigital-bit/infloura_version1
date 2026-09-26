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

import {
  CREATOR_KNOWLEDGE_TOPICS,
  CREATOR_LOCAL_TOOL_NAMES,
  CREATOR_TOOL_NAMES,
  isCreatorToolName,
} from './meera-api';

const SCHEMAS = join(process.cwd(), 'influora-ai', 'app', 'tools', 'creator_schemas.py');
const KNOWLEDGE = join(process.cwd(), 'influora-ai', 'app', 'prompt', 'content_knowledge.py');

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

  // Lookup review (2026-09-25, Priya): the local knowledge tool and its topics are hand copies
  // on this side. A topic added only in Python would show the generic "Influora's notes" label.
  it('knows the local knowledge tool and every lookup topic, in the same order', () => {
    const schemas = readFileSync(SCHEMAS, 'utf8');
    const constants = new Map<string, string>();
    for (const [, name, value] of schemas.matchAll(/^([A-Z_]+)\s*=\s*"([a-z_]+)"/gm)) {
      constants.set(name, value);
    }
    const local = schemas.match(/CREATOR_LOCAL_TOOL_NAMES:[^=]*=\s*\(([\s\S]*?)\)/);
    expect(local, 'CREATOR_LOCAL_TOOL_NAMES tuple not found in creator_schemas.py').toBeTruthy();
    const pythonLocal = local![1]
      .split(',')
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0 && !entry.startsWith('#'))
      .map((entry) => constants.get(entry) ?? entry.replace(/^["']|["']$/g, ''));
    expect([...CREATOR_LOCAL_TOOL_NAMES]).toEqual(pythonLocal);

    const knowledge = readFileSync(KNOWLEDGE, 'utf8');
    const block = knowledge.match(/^LOOKUP_TOPICS:[^=]*=\s*\{([\s\S]*?)^\}/m);
    expect(block, 'LOOKUP_TOPICS dict not found in content_knowledge.py').toBeTruthy();
    const literalTopics = [...block![1].matchAll(/^\s{4}"([a-z_]+)":/gm)].map((m) => m[1]);
    // Shoot guide spec v2: the framing topics are appended from FRAMING_TOPICS, in its order.
    expect(knowledge, 'LOOKUP_TOPICS no longer appends FRAMING_TOPICS').toMatch(
      /^LOOKUP_TOPICS\.update\(\{[\s\S]*?for topic, \([^)]*\) in FRAMING_TOPICS\.items\(\)/m,
    );
    const framing = knowledge.match(/^FRAMING_TOPICS:[^=]*=\s*\{([\s\S]*?)^\}/m);
    expect(framing, 'FRAMING_TOPICS dict not found in content_knowledge.py').toBeTruthy();
    const framingTopics = [...framing![1].matchAll(/^\s{4}"([a-z_]+)":/gm)].map((m) => m[1]);
    expect(framingTopics.length).toBeGreaterThanOrEqual(11);
    // Topics added later by `LOOKUP_TOPICS["x"] = (...)` assignments, in file order.
    const assignedTopics = [...knowledge.matchAll(/^LOOKUP_TOPICS\["([a-z_]+)"\]\s*=/gm)].map((m) => m[1]);
    const pythonTopics = [...literalTopics, ...framingTopics, ...assignedTopics];
    expect(pythonTopics.length).toBeGreaterThanOrEqual(3);
    expect([...CREATOR_KNOWLEDGE_TOPICS]).toEqual(pythonTopics);
  });

  it('claims no tool the AI service does not offer', () => {
    const python = new Set(pythonCreatorToolNames());
    const extra = CREATOR_TOOL_NAMES.filter((name) => !python.has(name));
    expect(extra, 'the app would render a card for a tool Meera is never given').toEqual([]);
  });
});
