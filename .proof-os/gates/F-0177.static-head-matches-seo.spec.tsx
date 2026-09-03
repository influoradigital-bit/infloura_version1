/**
 * F-0177 fixture — the static index.html <head> vs the "/" page's rendered <Seo> output.
 *
 * WHAT THE DEFECT IS. index.html is the single static shell every route is served from before
 * the bundle hydrates. For "/" it is also the *page* head: a crawler that does not execute JS,
 * and every real browser for the duration of the first paint, reads index.html's <head>. After
 * hydration React 19 hoists src/pages/landing.tsx's <Seo> tags into the same <head> and they
 * win. When the two disagree, one URL ships two descriptions of itself. F-0177 caught that on
 * the title ("Escrow-Protected Influencer Marketing" statically vs "Escrow-protected influencer
 * deals" after hydration).
 *
 * WHY THIS RENDERS INSTEAD OF GREPPING. `Seo` is not a passthrough: it appends " | Influora"
 * conditionally, resolves site-relative canonicals against SITE_URL, defaults ogImage, and
 * derives og:/twitter: tags from the same two props. Re-implementing that in a gate would let
 * the gate and the component drift apart, and the gate would then be asserting its own copy of
 * the rules. So the real component is mounted with the real props landing.tsx passes it, and the
 * real post-hydration document.head is read back — the same head a browser ends up with.
 *
 * The static side is read through DOMParser, not a regex over the file bytes. Comments and the
 * non-JS <body> fallback become Comment/body nodes that querySelector cannot see, so the
 * "quoted the forbidden string in a comment" failure mode is structurally impossible here.
 *
 * Anything this file cannot see (props that are not string literals, a <head> React never
 * populated) writes .F-0177.unavailable and the shell gate turns that into exit 2, never 1.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { render } from '@testing-library/react';
import { beforeAll, describe, expect, it } from 'vitest';

import { Seo } from '@/lib/seo/Seo';

const ROOT = process.cwd();
const INDEX_HTML = resolve(ROOT, 'index.html');
const LANDING = resolve(ROOT, 'src/pages/landing.tsx');
const UNAVAILABLE_MARKER = resolve(ROOT, '.proof-os/gates/.F-0177.unavailable');

/** Record why the gate could not see something, then abort. The .sh maps the marker to exit 2. */
function unavailable(reason: string): never {
  try {
    writeFileSync(UNAVAILABLE_MARKER, `${reason}\n`, 'utf8');
  } catch {
    /* the marker is best-effort; the throw below still stops the run */
  }
  throw new Error(`UNAVAILABLE: ${reason}`);
}

function norm(value: string | null | undefined): string | undefined {
  if (value == null) return undefined;
  const collapsed = value.replace(/\s+/g, ' ').trim();
  return collapsed.length > 0 ? collapsed : undefined;
}

type Head = Map<string, string>;

/** name/property -> content, plus the two singletons that are not <meta>. */
function harvest(scope: ParentNode & { querySelector: Element['querySelector'] }): Head {
  const out: Head = new Map();
  const title = norm(scope.querySelector('title')?.textContent);
  if (title) out.set('title', title);
  const canonical = norm(scope.querySelector('link[rel="canonical"]')?.getAttribute('href'));
  if (canonical) out.set('canonical', canonical);
  for (const meta of Array.from(scope.querySelectorAll('meta'))) {
    const key = meta.getAttribute('name') ?? meta.getAttribute('property');
    const content = norm(meta.getAttribute('content'));
    if (key && content && !out.has(key)) out.set(key, content);
  }
  return out;
}

// ---------------------------------------------------------------- static side

function staticHead(): Head {
  let raw: string;
  try {
    raw = readFileSync(INDEX_HTML, 'utf8');
  } catch {
    return unavailable(`cannot read ${INDEX_HTML}`);
  }
  const doc = new DOMParser().parseFromString(raw, 'text/html');
  const head = doc.head;
  if (!head) return unavailable('index.html parsed without a <head>');
  const harvested = harvest(head);
  if (!harvested.has('title')) {
    return unavailable('index.html <head> has no <title> — nothing to compare');
  }
  return harvested;
}

// --------------------------------------------------------------- rendered side

interface LandingSeoProps {
  title: string;
  description: string;
  canonical: string;
  ogImage?: string;
  noindex: boolean;
}

/**
 * Read the props landing.tsx hands <Seo>. Only double-quoted string literals are accepted; an
 * expression prop (`title={FOO}`) is something this gate cannot resolve, so it goes unavailable
 * rather than guessing.
 */
function landingSeoProps(): LandingSeoProps {
  let src: string;
  try {
    src = readFileSync(LANDING, 'utf8');
  } catch {
    return unavailable(`cannot read ${LANDING}`);
  }
  const opens = src.split(/<Seo[\s/>]/).length - 1;
  if (opens !== 1) {
    return unavailable(`expected exactly one <Seo …/> in landing.tsx, found ${opens}`);
  }
  const start = src.search(/<Seo[\s/>]/);
  const end = src.indexOf('/>', start);
  if (end === -1) return unavailable('the <Seo …/> element in landing.tsx is not self-closing');
  const block = src.slice(start, end);

  const attr = (name: string): string | undefined => {
    const m = new RegExp(`\\b${name}\\s*=\\s*"([^"]*)"`).exec(block);
    return m ? m[1] : undefined;
  };
  const title = attr('title');
  const description = attr('description');
  const canonical = attr('canonical');
  const missing = [
    ['title', title],
    ['description', description],
    ['canonical', canonical],
  ]
    .filter(([, v]) => v === undefined)
    .map(([k]) => k);
  if (missing.length > 0) {
    return unavailable(
      `landing.tsx <Seo> props not readable as string literals: ${missing.join(', ')} — ` +
        `block was: ${block.replace(/\s+/g, ' ').trim()}`,
    );
  }
  return {
    title: title as string,
    description: description as string,
    canonical: canonical as string,
    ogImage: attr('ogImage'),
    noindex: /\bnoindex\b/.test(block),
  };
}

function renderedHead(props: LandingSeoProps): Head {
  render(
    <Seo
      title={props.title}
      description={props.description}
      canonical={props.canonical}
      {...(props.ogImage ? { ogImage: props.ogImage } : {})}
      noindex={props.noindex}
    />,
  );
  const harvested = harvest(document.head);
  if (!harvested.has('title')) {
    return unavailable(
      'React did not hoist <Seo>’s <title> into document.head under jsdom — the rendered ' +
        'head cannot be read, so no comparison is possible',
    );
  }
  return harvested;
}

// --------------------------------------------------------------------- the gate

// Tags Seo emits verbatim on both sides. `robots` is deliberately absent: index.html carries
// extra crawler directives (max-image-preview, max-snippet) that Seo does not emit, and that is
// a legitimate superset, not drift — only the index/noindex polarity is compared, below.
const VERBATIM = [
  'title',
  'description',
  'canonical',
  'og:type',
  'og:site_name',
  'og:title',
  'og:description',
  'og:url',
  'og:image',
  'twitter:card',
  'twitter:title',
  'twitter:description',
  'twitter:image',
] as const;

const TITLE_TAGS = ['title', 'og:title', 'twitter:title'] as const;

describe('F-0177 — one URL, one head: index.html vs the "/" page <Seo>', () => {
  let stat: Head;
  let live: Head;

  // The render happens here, not at describe scope: RTL's auto-cleanup unmounts after every
  // test, and a head harvested at collection time would be read from a tree vitest has not
  // finished setting up.
  beforeAll(() => {
    stat = staticHead();
    live = renderedHead(landingSeoProps());
  });

  const overlapping = (keys: readonly string[]) =>
    keys.filter((k) => stat.has(k) && live.has(k));

  const diff = (keys: readonly string[]) =>
    overlapping(keys)
      .filter((k) => stat.get(k) !== live.get(k))
      .map((k) => `\n  ${k}\n    index.html : ${stat.get(k)}\n    <Seo>      : ${live.get(k)}`)
      .join('');

  it('the pre-hydration title tags equal the title the "/" page renders', () => {
    expect(overlapping(TITLE_TAGS).length, 'no title tag is present on both sides').toBeGreaterThan(
      0,
    );
    expect(diff(TITLE_TAGS), `title drift between index.html and landing.tsx <Seo>:`).toBe('');
  });

  it('every other head tag both sides emit carries the same value', () => {
    const rest = VERBATIM.filter((k) => !TITLE_TAGS.includes(k as (typeof TITLE_TAGS)[number]));
    expect(overlapping(rest).length, 'no comparable head tag is present on both sides').toBeGreaterThan(
      0,
    );
    expect(diff(rest), `head drift between index.html and landing.tsx <Seo>:`).toBe('');
  });

  it('both heads agree on whether "/" is indexable', () => {
    const staticNoindex = /noindex/i.test(stat.get('robots') ?? '');
    const liveNoindex = /noindex/i.test(live.get('robots') ?? '');
    expect(staticNoindex, 'index.html and <Seo> disagree on indexability of "/"').toBe(liveNoindex);
  });
});
