import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

/**
 * Minimal hand-rolled markdown -> React renderer.
 *
 * No markdown dependency exists in package.json today (checked before writing
 * this — see SHARED_CONTEXT.md handoff note). Rather than add `marked`
 * unreviewed, this covers exactly the constructs Ishaan's 3 launch posts use
 * (headings, paragraphs, bold, links, ordered/unordered lists, tables) with
 * zero new dependencies. If the blog grows richer markdown needs (code
 * blocks, blockquotes, nested lists), that's the trigger to get `marked`
 * approved and swap this out — flagged, not silently avoided.
 */

function slugifyHeading(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, '')
    .trim()
    .replace(/\s+/g, '-');
}

export interface Heading {
  text: string;
  slug: string;
  level: 2 | 3;
}

/** Pulls H2/H3 headings out for a "quick jump" table of contents. */
export function extractHeadings(markdown: string): Heading[] {
  const headings: Heading[] = [];
  for (const line of markdown.split(/\r?\n/)) {
    const match = /^(##|###)\s+(.*)$/.exec(line.trim());
    if (match) {
      const level = match[1].length as 2 | 3;
      const text = match[2].trim();
      headings.push({ text, slug: slugifyHeading(text), level });
    }
  }
  return headings;
}

/** Removes a leading H1 line (the post title, rendered separately from frontmatter). */
export function stripLeadingH1(markdown: string): string {
  const lines = markdown.split(/\r?\n/);
  let i = 0;
  while (i < lines.length && !lines[i].trim()) i += 1;
  if (i < lines.length && /^#\s+/.test(lines[i].trim())) {
    lines.splice(i, 1);
  }
  return lines.join('\n');
}

function parseInline(text: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  // Order matters: bold, then inline code (`...`), then links. Backtick spans
  // are common in legal-doc placeholders (e.g. `[REGISTERED ADDRESS — TBD]`,
  // `info@influora.in`) — rendered as <code>, with a distinct "pending" style
  // when the span itself looks like an unfilled TBD placeholder.
  const pattern = /\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)\]\(([^)]+)\)/g;
  let lastIndex = 0;
  let index = 0;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }

    if (match[1] !== undefined) {
      // Recurse so a bold span containing inline code (common in the legal-doc
      // banners, e.g. "**...Ships `noindex` until validated.**") still renders
      // the nested `code` as <code> instead of literal backticks.
      nodes.push(
        <strong key={`${keyPrefix}-b-${index}`} className="font-semibold text-foreground">
          {parseInline(match[1], `${keyPrefix}-b-${index}`)}
        </strong>,
      );
    } else if (match[2] !== undefined) {
      const codeText = match[2];
      const isPendingPlaceholder = /TBD/i.test(codeText);
      nodes.push(
        <code
          key={`${keyPrefix}-c-${index}`}
          className={
            isPendingPlaceholder
              ? 'rounded bg-amber-500/15 px-1.5 py-0.5 font-mono text-[0.85em] text-amber-700 dark:text-amber-400'
              : 'rounded bg-muted px-1.5 py-0.5 font-mono text-[0.85em] text-foreground'
          }
        >
          {codeText}
        </code>,
      );
    } else {
      const linkText = match[3];
      const href = match[4];
      const isInternal = href.startsWith('/');
      nodes.push(
        isInternal ? (
          <Link
            key={`${keyPrefix}-l-${index}`}
            to={href}
            className="font-medium text-accent-foreground underline underline-offset-2 hover:no-underline"
          >
            {linkText}
          </Link>
        ) : (
          <a
            key={`${keyPrefix}-l-${index}`}
            href={href}
            target="_blank"
            rel="noreferrer"
            className="font-medium text-accent-foreground underline underline-offset-2 hover:no-underline"
          >
            {linkText}
          </a>
        ),
      );
    }

    lastIndex = pattern.lastIndex;
    index += 1;
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }

  return nodes;
}

const HEADING_STYLES: Record<1 | 2 | 3 | 4, string> = {
  1: 'mt-0 text-3xl font-bold tracking-tight text-foreground sm:text-4xl',
  2: 'mt-10 text-2xl font-semibold tracking-tight text-foreground',
  3: 'mt-8 text-xl font-semibold tracking-tight text-foreground',
  4: 'mt-6 text-lg font-semibold tracking-tight text-foreground',
};

function isTableRule(line: string): boolean {
  return /^\|?[\s:|-]+\|?$/.test(line.trim());
}

/** Renders a markdown body (frontmatter already stripped) as styled React content. */
export function MarkdownContent({ content }: { content: string }) {
  const lines = content.split(/\r?\n/);
  const blocks: ReactNode[] = [];
  let i = 0;
  let blockKey = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i += 1;
      continue;
    }

    const headingMatch = /^(#{1,4})\s+(.*)$/.exec(line.trim());
    if (headingMatch) {
      const level = headingMatch[1].length as 1 | 2 | 3 | 4;
      const text = headingMatch[2].trim();
      const Tag = `h${level}` as 'h1' | 'h2' | 'h3' | 'h4';
      blocks.push(
        <Tag key={`h-${blockKey}`} id={slugifyHeading(text)} className={HEADING_STYLES[level]}>
          {parseInline(text, `h-${blockKey}`)}
        </Tag>,
      );
      blockKey += 1;
      i += 1;
      continue;
    }

    if (/^-{3,}$/.test(line.trim())) {
      blocks.push(<hr key={`hr-${blockKey}`} className="my-8 border-border" />);
      blockKey += 1;
      i += 1;
      continue;
    }

    if (line.trim().startsWith('>')) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        quoteLines.push(lines[i].trim().replace(/^>\s?/, ''));
        i += 1;
      }
      const quoteText = quoteLines.join(' ').trim();
      // Legal-doc v0 banners use 🔴 for a hard publishing blocker (e.g. missing
      // Grievance Officer name) vs ⚠️ for the general "pending review" notice —
      // give the hard blocker a more urgent (red) treatment.
      const isUrgent = quoteText.includes('🔴');
      blocks.push(
        <div
          key={`bq-${blockKey}`}
          className={
            isUrgent
              ? 'my-6 rounded-lg border-l-4 border-red-500 bg-red-500/10 px-4 py-3 text-sm leading-relaxed text-red-700 dark:text-red-400'
              : 'my-6 rounded-lg border-l-4 border-amber-500 bg-amber-500/10 px-4 py-3 text-sm leading-relaxed text-amber-800 dark:text-amber-300'
          }
        >
          {parseInline(quoteText, `bq-${blockKey}`)}
        </div>,
      );
      blockKey += 1;
      continue;
    }

    if (line.trim().startsWith('|')) {
      const tableLines: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        tableLines.push(lines[i].trim());
        i += 1;
      }
      const rows = tableLines
        .filter((row) => !isTableRule(row))
        .map((row) =>
          row
            .replace(/^\|/, '')
            .replace(/\|$/, '')
            .split('|')
            .map((cell) => cell.trim()),
        );
      const [headerRow, ...bodyRows] = rows;
      blocks.push(
        <div key={`t-${blockKey}`} className="my-6 overflow-x-auto rounded-lg border border-border">
          <table className="w-full min-w-[480px] border-collapse text-sm">
            {headerRow && (
              <thead>
                <tr className="bg-card/60">
                  {headerRow.map((cell, cellIndex) => (
                    <th
                      key={cellIndex}
                      className="border-b border-border px-4 py-2.5 text-left font-semibold text-foreground"
                    >
                      {parseInline(cell, `t-${blockKey}-h-${cellIndex}`)}
                    </th>
                  ))}
                </tr>
              </thead>
            )}
            <tbody>
              {bodyRows.map((row, rowIndex) => (
                <tr key={rowIndex} className="border-b border-border/60 last:border-0">
                  {row.map((cell, cellIndex) => (
                    <td key={cellIndex} className="px-4 py-2.5 align-top text-muted-foreground">
                      {parseInline(cell, `t-${blockKey}-r-${rowIndex}-${cellIndex}`)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      );
      blockKey += 1;
      continue;
    }

    if (/^-\s+/.test(line.trim())) {
      const items: string[] = [];
      while (i < lines.length && /^-\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^-\s+/, ''));
        i += 1;
      }
      blocks.push(
        <ul key={`ul-${blockKey}`} className="my-4 list-disc space-y-2 pl-6 text-muted-foreground marker:text-accent-foreground">
          {items.map((item, itemIndex) => (
            <li key={itemIndex} className="leading-relaxed">
              {parseInline(item, `ul-${blockKey}-${itemIndex}`)}
            </li>
          ))}
        </ul>,
      );
      blockKey += 1;
      continue;
    }

    if (/^\d+\.\s+/.test(line.trim())) {
      const items: string[] = [];
      while (i < lines.length && /^\d+\.\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^\d+\.\s+/, ''));
        i += 1;
      }
      blocks.push(
        <ol key={`ol-${blockKey}`} className="my-4 list-decimal space-y-2 pl-6 text-muted-foreground marker:font-semibold marker:text-foreground">
          {items.map((item, itemIndex) => (
            <li key={itemIndex} className="leading-relaxed">
              {parseInline(item, `ol-${blockKey}-${itemIndex}`)}
            </li>
          ))}
        </ol>,
      );
      blockKey += 1;
      continue;
    }

    const paragraphLines: string[] = [];
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^#{1,4}\s+/.test(lines[i].trim()) &&
      !lines[i].trim().startsWith('|') &&
      !lines[i].trim().startsWith('>') &&
      !/^-{3,}$/.test(lines[i].trim()) &&
      !/^-\s+/.test(lines[i].trim()) &&
      !/^\d+\.\s+/.test(lines[i].trim())
    ) {
      paragraphLines.push(lines[i].trim());
      i += 1;
    }
    blocks.push(
      <p key={`p-${blockKey}`} className="my-4 leading-relaxed text-muted-foreground">
        {parseInline(paragraphLines.join(' '), `p-${blockKey}`)}
      </p>,
    );
    blockKey += 1;
  }

  return <div className="text-base">{blocks}</div>;
}
