/**
 * Loader for the 8 P0 legal-policy v0 drafts (Ishaan, per
 * wiki/website/policy-drafts/*.md — CEO-approved posture in
 * wiki/website/CEO-DECISIONS.md POLICY DECISIONS section).
 *
 * Mirrors src/lib/blog/posts.ts: Vite `import.meta.glob` + `?raw`, no runtime
 * fetch, no backend. Unlike blog posts these files carry no frontmatter — the
 * title is the leading `# H1` and the review date is the literal
 * "**Last updated: YYYY-MM-DD**" line every draft includes, so both are
 * extracted with a couple of small regexes instead of a second schema.
 */

export interface LegalDoc {
  /** Filename (without `.md`) under src/content/legal/ — the stable doc id. */
  slug: string;
  /** Pulled from the leading `# Title` line. */
  title: string;
  /** `YYYY-MM-DD`, pulled from the "Last updated" line. Empty string if absent. */
  lastUpdated: string;
  /** Full raw markdown, including the H1 and the pending-review banner. */
  content: string;
}

const rawModules = import.meta.glob<string>('/src/content/legal/*.md', {
  eager: true,
  query: '?raw',
  import: 'default',
});

function parseLegalDoc(raw: string, sourcePath: string): LegalDoc {
  const slug = sourcePath.split('/').pop()!.replace(/\.md$/, '');

  const titleMatch = /^#\s+(.+)$/m.exec(raw);
  const title = titleMatch ? titleMatch[1].trim() : slug;

  const updatedMatch = /Last updated:\s*(\d{4}-\d{2}-\d{2})/.exec(raw);
  const lastUpdated = updatedMatch ? updatedMatch[1] : '';

  return { slug, title, lastUpdated, content: raw };
}

let cachedDocs: Map<string, LegalDoc> | null = null;

function loadDocs(): Map<string, LegalDoc> {
  if (cachedDocs) return cachedDocs;

  cachedDocs = new Map(
    Object.entries(rawModules).map(([path, raw]) => {
      const doc = parseLegalDoc(raw, path);
      return [doc.slug, doc];
    }),
  );
  return cachedDocs;
}

export function getLegalDoc(slug: string): LegalDoc | undefined {
  return loadDocs().get(slug);
}
