import * as React from 'react';

/**
 * Meera's replies are meant to be plain spoken text, but the model still sometimes writes light
 * markdown: `**bold**`, a `---` divider line, a `## heading`. The chat bubble is a plain
 * `whitespace-pre-wrap` block, so those showed as raw asterisks and dashes on the creator's phone
 * (live screenshots, 2026-09-24). These helpers clean that up without ever changing what she said.
 */

/** A line that is only a divider: `---`, `***`, `___` (any length of 3+). */
const DIVIDER = /^\s*([-*_])\1{2,}\s*$/;
/** A markdown heading marker at the start of a line: `## Title` -> `Title`. */
const HEADING = /^(\s*)#{1,6}\s+/;
/** Bold / strong markers: `**x**` or `__x__` (non-greedy, on one line). */
const BOLD = /\*\*(.+?)\*\*|__(.+?)__/g;

/** Plain text with the markdown markers removed — for parsing a reply into a card. */
export function stripMeeraMarkdown(text: string): string {
  return text
    .replace(/\r\n/g, '\n')
    .split('\n')
    .filter((line) => !DIVIDER.test(line))
    .map((line) => line.replace(HEADING, '$1').replace(BOLD, (_m, a, b) => a ?? b))
    .join('\n');
}

/**
 * The same cleanup for display: divider lines and heading markers go, and `**bold**` becomes a
 * real <strong> instead of showing its asterisks. Returns nodes for a `whitespace-pre-wrap` block.
 */
export function renderMeeraText(text: string): React.ReactNode[] {
  const lines = text
    .replace(/\r\n/g, '\n')
    .split('\n')
    .filter((line) => !DIVIDER.test(line))
    .map((line) => line.replace(HEADING, '$1'));
  const out: React.ReactNode[] = [];
  lines.forEach((line, i) => {
    if (i > 0) out.push('\n');
    let last = 0;
    let n = 0;
    for (const m of line.matchAll(BOLD)) {
      const at = m.index ?? 0;
      if (at > last) out.push(line.slice(last, at));
      out.push(React.createElement('strong', { key: `${i}-${n++}`, className: 'font-semibold' }, m[1] ?? m[2]));
      last = at + m[0].length;
    }
    if (last < line.length) out.push(line.slice(last));
  });
  return out;
}
