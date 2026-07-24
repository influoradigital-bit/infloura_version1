#!/usr/bin/env node
/**
 * POST-DEPLOY LIVE SMOKE — drives ONE real Meera turn end-to-end and fails loudly
 * if it comes back a silent blank (the ~28% defect) or an error.
 *
 * WHY: three defects this arc (anyOf-400, invisible drafts, blank turn) passed
 * every unit/contract test and only broke when a REAL Meera turn ran against the
 * REAL Anthropic API + Spring backend. The static CI guards
 * (influora-ai/tests/tools/test_tool_schema_anthropic_valid.py +
 * .../providers/test_stream_truncation_guard.py) catch the schema/truncation
 * classes deterministically pre-deploy. This script catches the INTEGRATION
 * failures they can't: a broken deploy, an unreachable/misconfigured Anthropic
 * key, an on-behalf-scope regression — anything that makes a live turn go blank.
 *
 * It exercises the heaviest path: a single-shot HYPE create_campaign, the exact
 * payload whose tool JSON truncated at the old max_tokens=384.
 *
 * Node 18+ (built-in fetch). No dependencies.
 *
 *   BASE_URL=http://200.141.1.6/api/v1 \
 *   MEERA_SMOKE_EMAIL=demo.brand@influora.com MEERA_SMOKE_PASSWORD=Demo@Brand123 \
 *   node scripts/meera-turn-smoke.mjs
 *
 * Exit 0 = a real reply or an honest recoverable fallback. Exit 1 = a silent
 * blank / error / send failure (the deploy must not be considered healthy).
 * Contract distilled from the harness neha used to verify 28 live turns.
 */

const BASE = process.env.BASE_URL || 'http://200.141.1.6/api/v1';
const CREDS = {
  email: process.env.MEERA_SMOKE_EMAIL || 'demo.brand@influora.com',
  password: process.env.MEERA_SMOKE_PASSWORD || 'Demo@Brand123',
};
const PROMPT =
  'Run a 72-hour hype blitz for my matcha whisk: everyone remixes this reel ' +
  'https://instagram.com/reel/mw001, pay 1500 rupees per creator, open 100 slots, ' +
  'target lifestyle and food creators in Bangalore, and create the draft now.';

const uuid = () => `smoke-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;

function parseFrames(raw) {
  const frames = [];
  for (const block of raw.split(/\r?\n\r?\n/)) {
    if (!block.trim()) continue;
    let event = 'message';
    const dataLines = [];
    for (const line of block.split(/\r?\n/)) {
      if (line === '' || line.startsWith(':')) continue;
      const c = line.indexOf(':');
      const field = c === -1 ? line : line.slice(0, c);
      let val = c === -1 ? '' : line.slice(c + 1);
      if (val.startsWith(' ')) val = val.slice(1);
      if (field === 'event') event = val;
      else if (field === 'data') dataLines.push(val);
    }
    if (dataLines.length) frames.push({ event, data: dataLines.join('\n') });
  }
  return frames;
}

function fail(msg, extra) {
  console.error(`\n❌ MEERA SMOKE FAILED: ${msg}`);
  if (extra !== undefined) console.error(extra);
  process.exit(1);
}

async function main() {
  // 1) login
  const loginRes = await fetch(`${BASE}/auth/brand/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(CREDS),
  }).catch((e) => fail(`login request threw (backend unreachable?): ${e.message}`));
  if (!loginRes.ok) fail(`login HTTP ${loginRes.status}`, await loginRes.text().catch(() => ''));
  const token = (await loginRes.json())?.data?.accessToken;
  if (!token) fail('login returned no accessToken');

  // 2) start a Meera session
  const sessRes = await fetch(`${BASE}/meera/sessions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({}),
  });
  if (!sessRes.ok) fail(`start session HTTP ${sessRes.status}`, await sessRes.text().catch(() => ''));
  const sess = (await sessRes.json())?.data;
  if (!sess?.conversationId) fail('session response missing conversationId', sess);

  // 3) send the turn -> get the per-turn stream + on-behalf tokens
  const sendRes = await fetch(`${BASE}/meera/sessions/${sess.conversationId}/messages`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': uuid(),
    },
    body: JSON.stringify({ content: PROMPT }),
  });
  if (!sendRes.ok) fail(`send message HTTP ${sendRes.status}`, await sendRes.text().catch(() => ''));
  const td = (await sendRes.json())?.data;
  if (!td?.streamToken || !td?.streamUrl) fail('send response missing streamToken/streamUrl', td);

  // 4) open the SSE stream (Python AI service) and read it to completion
  const streamRes = await fetch(td.streamUrl, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${td.streamToken}`,
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({
      workspace_id: td.workspaceId ?? '',
      conversation_id: sess.conversationId,
      turn_id: td.messageId,
      onbehalf_jwt: td.onBehalfToken ?? '',
      conversation: [{ role: 'user', content: PROMPT }],
    }),
  }).catch((e) => fail(`stream request threw: ${e.message}`));
  if (!streamRes.ok) fail(`stream HTTP ${streamRes.status}`, await streamRes.text().catch(() => ''));

  let raw = '';
  const reader = streamRes.body.getReader();
  const dec = new TextDecoder();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    raw += dec.decode(value, { stream: true });
  }

  // 5) classify
  const frames = parseFrames(raw);
  let tokens = 0,
    toolStart = 0,
    toolResult = 0,
    finish = null,
    pv = null,
    hasError = false,
    errData = null;
  for (const f of frames) {
    if (f.event === 'token') tokens++;
    else if (f.event === 'tool_start') toolStart++;
    else if (f.event === 'tool_result') toolResult++;
    else if (f.event === 'prompt_meta') pv = safe(f.data)?.prompt_version;
    else if (f.event === 'done') finish = safe(f.data)?.finish_reason;
    else if (f.event === 'error') {
      hasError = true;
      errData = safe(f.data);
    }
  }
  const contentful = tokens > 0 || toolStart > 0 || toolResult > 0;
  const bytes = Buffer.byteLength(raw);

  console.log(
    `frames: tokens=${tokens} tool_start=${toolStart} tool_result=${toolResult} ` +
      `finish_reason=${finish} bytes=${bytes} prompt_version=${pv}`,
  );

  if (hasError) fail(`stream emitted an error event`, errData);
  if (finish === 'empty_response')
    console.warn('⚠️  turn recovered via the honest fallback (empty_response) — acceptable, but the model produced nothing on a retry');
  else if (!contentful)
    fail(
      `SILENT BLANK TURN — zero tokens, zero tool events, ${bytes}-byte body, finish_reason=${finish}. ` +
        `This is the ~28% blank-turn defect. The deploy is NOT healthy.`,
      raw,
    );

  console.log(`\n✅ MEERA SMOKE PASSED — live turn produced ${contentful ? 'real content' : 'an honest fallback'} (finish_reason=${finish}, prompt_version=${pv}).`);
}

function safe(s) {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

main().catch((e) => fail(`unexpected: ${e?.stack || e}`));
