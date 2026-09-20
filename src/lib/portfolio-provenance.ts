import type { PortfolioPage } from '@/lib/api';

/**
 * EV-008 — follower provenance for the public portfolio page (/c/:username, the creator's media
 * kit). Kept out of the page module so the page file exports components only (fast refresh).
 */

export function formatFollowers(n: number) {
  if (n >= 10_000_000) return `${(n / 10_000_000).toFixed(1)}Cr`;
  if (n >= 100_000)    return `${(n / 100_000).toFixed(1)}L`;
  if (n >= 1_000)      return `${(n / 1_000).toFixed(n >= 10_000 ? 0 : 1)}K`;
  return String(n);
}

/**
 * EV-008 — the search-engine description of this public page. It used to sum EVERY platform,
 * including creator-declared (self-reported) ones, and print that as "with N followers" next to
 * "verified". Only platform-verified followers are counted now; with none, no count is claimed.
 */
export function verifiedFollowers(page: PortfolioPage) {
  return page.platforms.filter((p) => p.isVerified).reduce((sum, p) => sum + p.followers, 0);
}

export function metaDescription(page: PortfolioPage): string {
  const who = `${page.displayName} is a ${page.verified ? 'verified ' : ''}${page.niches[0]?.toLowerCase() || 'content'} creator${page.city ? ' based in ' + page.city : ''}`;
  const followers = verifiedFollowers(page);
  // [F-0972] `stats` is null when the creator hid their trust bar. This string is the
  // page's public meta description, so a withheld collab count must drop out of the
  // sentence entirely — never appear as "0 brand collaborations".
  const collabs = page.stats != null ? `${page.stats.totalCollabs} brand collaborations` : null;
  const audience =
    followers > 0 ? `${formatFollowers(followers)} platform-verified followers` : null;
  const tail = [audience, collabs].filter(Boolean).join(' and ');
  return tail ? `${who} with ${tail}.` : `${who}.`;
}

/** EV-008 — the Platform Stats footnote may only claim "synced from the API" when every card is. */
export function platformStatsFootnote(page: PortfolioPage): string {
  return page.platforms.every((p) => p.isVerified)
    ? "Numbers synced directly from each platform's API. Updated daily."
    : "Figures marked 'Followers verified' are synced from the platform's API; 'Self-reported' figures are the creator's own and have not been verified.";
}
