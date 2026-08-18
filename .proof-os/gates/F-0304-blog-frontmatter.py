#!/usr/bin/env python3
"""F-0304 gate — blog frontmatter + SERP length. Also closes F-0325
(gate-crashes-on-non-ascii-console): this gate prints "≤" in its own verdict and the
Windows console codec is cp1252, so emitting the verdict raised UnicodeEncodeError and the
gate exited 1 — a false RED saying nothing about the artefact it guards. See the stdout/
stderr reconfigure below.

Closes the blind spot logged as F-0304: gates/meta_length.py reports
"0 description <=160" on src/content/blog/*.md because it looks for a
`description` / `og:description` key. This blog's meta description is the
frontmatter `excerpt` field — src/pages/blog/post.tsx renders

    <meta name="description" content={post.excerpt} />

so every excerpt shipped un-measured and all three published posts were over
the 160-char SERP limit while the gate stayed green.

Two things this gate does deliberately:

  * It parses frontmatter with the SAME rule src/lib/blog/posts.ts uses
    (JSON.parse on the value half of each `key: value` line). A gate that
    parses more permissively than the loader would pass files the site then
    fails to build.
  * It reads BLOG_CATEGORIES out of src/lib/blog/posts.ts rather than
    hardcoding the list, so adding a category to the code cannot silently
    make this gate wrong.

usage: F-0304-blog-frontmatter.py [content_dir] [--posts-ts PATH]
                                  [--title N] [--desc N]
exit 0 = every post valid · 1 = at least one defect · 2 = cannot read state
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

# F-0325 (gate-crashes-on-non-ascii-console): this gate prints "≤" in its own verdict
# text, and the default Windows console codec is cp1252, so emitting the verdict raised
# UnicodeEncodeError and the gate exited 1 -- a false RED that said nothing about the
# artefact it guards. Same root cause as the _strip_comments.py cp1252 crash fixed under
# F-0266, which had been silently exempting every source file containing a rupee sign.
# A gate that cannot print its own verdict is indistinguishable from one reporting a finding.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # pragma: no cover - older/!TextIO streams
        pass

LIMIT_TITLE = 60
LIMIT_DESC = 160

REQUIRED_STRINGS = (
    "title", "slug", "excerpt", "category",
    "author", "publishedAt", "updatedAt", "featuredImageAlt",
)
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SLUG_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


def parse_frontmatter(raw: str):
    """Mirror of parseFrontmatter() in src/lib/blog/posts.ts."""
    m = re.match(r"^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$", raw)
    if not m:
        return None, None
    block, body = m.group(1), m.group(2)
    data = {}
    for line in re.split(r"\r?\n", block):
        if not line.strip():
            continue
        i = line.find(":")
        if i == -1:
            continue
        key = line[:i].strip()
        val = line[i + 1:].strip()
        try:
            data[key] = json.loads(val)
        except Exception:
            data[key] = re.sub(r'^"(.*)"$', r"\1", val)
    return data, body


def load_categories(posts_ts: Path):
    """Read BLOG_CATEGORIES keys from posts.ts so the gate cannot drift."""
    try:
        src = posts_ts.read_text(encoding="utf-8")
    except OSError:
        return None
    m = re.search(r"BLOG_CATEGORIES[^=]*=\s*\{(.*?)\}", src, re.S)
    if not m:
        return None
    return set(re.findall(r"^\s*(\w+)\s*:", m.group(1), re.M))


def main(argv):
    content_dir = Path("src/content/blog")
    posts_ts = Path("src/lib/blog/posts.ts")
    args = list(argv)
    i = 0
    while i < len(args):
        if args[i] == "--posts-ts":
            posts_ts = Path(args[i + 1]); del args[i:i + 2]; continue
        if args[i] == "--title":
            globals()["LIMIT_TITLE"] = int(args[i + 1]); del args[i:i + 2]; continue
        if args[i] == "--desc":
            globals()["LIMIT_DESC"] = int(args[i + 1]); del args[i:i + 2]; continue
        if args[i].startswith("--"):
            print(f"unknown option {args[i]}", file=sys.stderr)
            print(__doc__.strip().splitlines()[-3], file=sys.stderr)
            return 64
        content_dir = Path(args[i]); del args[i]

    if not content_dir.is_dir():
        print(f"no content dir: {content_dir}", file=sys.stderr)
        return 2
    files = sorted(content_dir.glob("*.md"))
    if not files:
        print(f"no posts found in {content_dir}", file=sys.stderr)
        return 2

    categories = load_categories(posts_ts)
    if categories is None:
        print(f"cannot read BLOG_CATEGORIES from {posts_ts}", file=sys.stderr)
        return 2

    fails = []
    seen_slugs = {}
    for f in files:
        rel = f.as_posix()
        try:
            raw = f.read_text(encoding="utf-8")
        except OSError as e:
            fails.append(f"{rel}: unreadable ({e})")
            continue

        data, body = parse_frontmatter(raw)
        if data is None:
            fails.append(f"{rel}:1 missing frontmatter block (expected '---' delimiters)")
            continue

        for field in REQUIRED_STRINGS:
            v = data.get(field)
            if not isinstance(v, str) or not v:
                fails.append(f"{rel}:1 frontmatter '{field}' missing or not a non-empty string")

        if not isinstance(data.get("readingMinutes"), (int, float)) or isinstance(data.get("readingMinutes"), bool):
            fails.append(f"{rel}:1 frontmatter 'readingMinutes' missing or not a number")

        kw = data.get("keywords")
        if not isinstance(kw, list) or not kw or not all(isinstance(k, str) and k for k in kw):
            fails.append(f"{rel}:1 frontmatter 'keywords' must be a non-empty array of strings")

        title = data.get("title")
        if isinstance(title, str) and len(title) > LIMIT_TITLE:
            fails.append(
                f"{rel}:1 title {len(title)}>{LIMIT_TITLE} chars — truncated in the SERP: "
                f"“{title[:57]}…”"
            )

        excerpt = data.get("excerpt")
        if isinstance(excerpt, str) and len(excerpt) > LIMIT_DESC:
            fails.append(
                f"{rel}:1 excerpt {len(excerpt)}>{LIMIT_DESC} chars — this is the meta description "
                f"(post.tsx renders it into <meta name=\"description\">), truncated in the SERP"
            )

        slug = data.get("slug")
        if isinstance(slug, str) and slug:
            if slug != f.stem:
                fails.append(f"{rel}:1 slug \"{slug}\" != filename stem \"{f.stem}\" — the route and the file disagree")
            if not SLUG_RE.match(slug):
                fails.append(f"{rel}:1 slug \"{slug}\" is not lowercase-hyphenated")
            if slug in seen_slugs:
                fails.append(f"{rel}:1 duplicate slug \"{slug}\" — already used by {seen_slugs[slug]}")
            else:
                seen_slugs[slug] = rel

        cat = data.get("category")
        if isinstance(cat, str) and cat and cat not in categories:
            fails.append(
                f"{rel}:1 category \"{cat}\" is not in BLOG_CATEGORIES "
                f"({', '.join(sorted(categories))}) — it renders with no label"
            )

        for df in ("publishedAt", "updatedAt"):
            v = data.get(df)
            if isinstance(v, str) and v and not DATE_RE.match(v):
                fails.append(f"{rel}:1 {df} \"{v}\" is not YYYY-MM-DD — posts sort by string compare")
        pub, upd = data.get("publishedAt"), data.get("updatedAt")
        if isinstance(pub, str) and isinstance(upd, str) and DATE_RE.match(pub or "") and DATE_RE.match(upd or "") and upd < pub:
            fails.append(f"{rel}:1 updatedAt {upd} is before publishedAt {pub}")

        if body is not None and not re.search(r"^#\s+\S", body.strip(), re.M):
            fails.append(f"{rel} body has no H1 heading")

    print(
        f"blog posts measured: {len(files)} "
        f"({len(files)} title ≤{LIMIT_TITLE}, {len(files)} excerpt ≤{LIMIT_DESC} checked) "
        f"· failed: {len(fails)}"
    )
    for line in fails:
        print(f"   {line}")
    print(
        "NOT CHECKED: whether the copy is any good, accurate, or non-duplicated — only its shape and length | "
        "whether the claims in the body match what the product actually does | "
        "the RENDERED <title>, which src/pages/blog/post.tsx builds as `${title} | Influora Blog` — "
        "the 16-char brand suffix is measured nowhere here, so a title at the 60 limit ships a 76-char "
        "SERP string and the suffix truncates (accepted: the keyword-bearing half leads) | "
        "whether the post is prerendered into dist/ at all — that is scripts/prerender.mjs's job (see F-0308) | "
        "whether the post renders correctly, or whether the route resolves at runtime | "
        "keyword targeting, search intent, cannibalisation against existing posts | "
        "images: featuredImageAlt is checked for presence, never that an image exists | "
        "anything outside " + content_dir.as_posix()
    )
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
