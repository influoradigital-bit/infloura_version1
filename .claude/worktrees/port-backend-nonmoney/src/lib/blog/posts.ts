/**
 * Markdown-based blog loader (CEO-DECISIONS.md #3: markdown files in repo, no
 * backend blog API for MVP). Posts live in `src/content/blog/*.md` with the
 * frontmatter schema below (see wiki/website/CEO-DECISIONS.md build scope and
 * the task brief for the exact field list Ishaan writes to).
 *
 * Loaded eagerly at build time via Vite's `import.meta.glob` + `?raw` query —
 * no runtime fetch, no CMS, ships fully static.
 */

export interface BlogFrontmatter {
  title: string;
  slug: string;
  excerpt: string;
  category: string;
  author: string;
  publishedAt: string;
  updatedAt: string;
  readingMinutes: number;
  keywords: string[];
  featuredImageAlt: string;
}

export interface BlogPost extends BlogFrontmatter {
  /** Raw markdown body (frontmatter stripped). Render with `MarkdownContent`. */
  content: string;
}

/** Category slug -> display label, per wiki/website/content-map.md §4.2. */
export const BLOG_CATEGORIES: Record<string, string> = {
  brands: 'For Brands',
  creators: 'For Creators',
  industry: 'Industry News',
  updates: 'Platform Updates',
};

const REQUIRED_STRING_FIELDS = [
  'title',
  'slug',
  'excerpt',
  'category',
  'author',
  'publishedAt',
  'updatedAt',
  'featuredImageAlt',
] as const;

/**
 * Minimal frontmatter parser. The schema Ishaan writes to only ever uses
 * double-quoted strings, bare numbers, and double-quoted-string arrays — all
 * valid JSON value literals — so `JSON.parse` on the value half of each
 * `key: value` line is sufficient without pulling in a YAML dependency.
 */
function parseFrontmatter(raw: string, sourcePath: string): BlogPost {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(raw);
  if (!match) {
    throw new Error(`[blog] ${sourcePath}: missing frontmatter block (expected "---" delimiters)`);
  }
  const [, frontmatterBlock, body] = match;

  const data: Record<string, unknown> = {};
  for (const line of frontmatterBlock.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const separatorIndex = line.indexOf(':');
    if (separatorIndex === -1) continue;
    const key = line.slice(0, separatorIndex).trim();
    const rawValue = line.slice(separatorIndex + 1).trim();
    try {
      data[key] = JSON.parse(rawValue);
    } catch {
      // Fallback for unquoted scalars — strip surrounding quotes if present.
      data[key] = rawValue.replace(/^"(.*)"$/, '$1');
    }
  }

  for (const field of REQUIRED_STRING_FIELDS) {
    if (typeof data[field] !== 'string' || !data[field]) {
      throw new Error(`[blog] ${sourcePath}: frontmatter field "${field}" is missing or not a string`);
    }
  }
  if (typeof data.readingMinutes !== 'number') {
    throw new Error(`[blog] ${sourcePath}: frontmatter field "readingMinutes" is missing or not a number`);
  }
  if (!Array.isArray(data.keywords) || !data.keywords.every((k) => typeof k === 'string')) {
    throw new Error(`[blog] ${sourcePath}: frontmatter field "keywords" must be an array of strings`);
  }

  return {
    title: data.title as string,
    slug: data.slug as string,
    excerpt: data.excerpt as string,
    category: data.category as string,
    author: data.author as string,
    publishedAt: data.publishedAt as string,
    updatedAt: data.updatedAt as string,
    readingMinutes: data.readingMinutes as number,
    keywords: data.keywords as string[],
    featuredImageAlt: data.featuredImageAlt as string,
    content: body.trim(),
  };
}

const rawModules = import.meta.glob<string>('/src/content/blog/*.md', {
  eager: true,
  query: '?raw',
  import: 'default',
});

let cachedPosts: BlogPost[] | null = null;

function loadPosts(): BlogPost[] {
  if (cachedPosts) return cachedPosts;

  const posts = Object.entries(rawModules).map(([path, raw]) => parseFrontmatter(raw, path));
  posts.sort((a, b) => (a.publishedAt < b.publishedAt ? 1 : a.publishedAt > b.publishedAt ? -1 : 0));

  cachedPosts = posts;
  return posts;
}

/** All posts, sorted by `publishedAt` descending. */
export function getAllPosts(): BlogPost[] {
  return loadPosts();
}

export function getPostBySlug(slug: string): BlogPost | undefined {
  return loadPosts().find((post) => post.slug === slug);
}

export function getPostsByCategory(category: string): BlogPost[] {
  return loadPosts().filter((post) => post.category === category);
}

/** Same-category posts excluding the given one, for a post's "related" rail. */
export function getRelatedPosts(post: BlogPost, limit = 3): BlogPost[] {
  return loadPosts()
    .filter((candidate) => candidate.slug !== post.slug && candidate.category === post.category)
    .slice(0, limit);
}
