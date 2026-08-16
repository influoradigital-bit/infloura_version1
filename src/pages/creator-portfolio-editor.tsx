import * as React from 'react';
import {
  Copy,
  ExternalLink,
  Eye,
  EyeOff,
  Loader2,
  Plus,
  RefreshCw,
  TrendingUp,
  Trash2,
  Upload,
  Save,
  Mail,
  ShoppingCart,
  GraduationCap,
  Globe,
  Briefcase,
  CheckCircle2,
} from 'lucide-react';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { toast } from '@/hooks/use-toast';
import { cn, publicProfileUrl } from '@/lib/utils';
import { cssVars } from '@/lib/css-vars';
import {
  api,
  ApiError,
  type PortfolioAnalytics,
  type PortfolioCustomLink,
  type PortfolioPage,
  type PortfolioVisibility,
} from '@/lib/api';

/**
 * Creator's "Public Page" editor.
 *
 * Lives at /creator/portfolio. Controls:
 *  - Public URL display + copy + view
 *  - Section visibility toggles
 *  - Cover photo upload
 *  - Custom links management
 *  - Manual platform sync
 *  - Last-30-day analytics summary
 *
 * Layout themes and accent colours are deferred to v2 (see spec review).
 */

export default function CreatorPortfolioEditorPage() {
  const [page, setPage] = React.useState<PortfolioPage | null>(null);
  const [analytics, setAnalytics] = React.useState<PortfolioAnalytics | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  const [saving, setSaving] = React.useState(false);
  const [syncing, setSyncing] = React.useState(false);
  const [coverUploading, setCoverUploading] = React.useState(false);
  const [dirty, setDirty] = React.useState(false);

  React.useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    Promise.all([api.portfolio.getMine(), api.portfolio.analytics()])
      .then(([p, a]) => {
        if (cancelled) return;
        setPage(p as PortfolioPage);
        setAnalytics(a as PortfolioAnalytics);
      })
      .catch((err) => {
        // Was console.error only — `page` stayed null and the render guard
        // (loading || !page) left the whole editor stuck on an infinite spinner.
        if (!cancelled) setLoadError(err instanceof ApiError ? err.message : 'Could not load your portfolio.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <CreatorLayout>
        <div className="flex items-center justify-center py-24">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      </CreatorLayout>
    );
  }

  if (!page) {
    return (
      <CreatorLayout>
        <div className="flex flex-col items-center justify-center gap-4 py-24 text-center">
          <p className="text-sm font-medium text-destructive-foreground">Could not load your portfolio</p>
          <p className="text-sm text-muted-foreground">{loadError ?? 'Something went wrong.'}</p>
          <Button variant="outline" onClick={() => window.location.reload()}>
            Try again
          </Button>
        </div>
      </CreatorLayout>
    );
  }

  const publicUrl = publicProfileUrl(page.username);

  const update = (patch: Partial<PortfolioPage>) => {
    setPage((prev) => (prev ? { ...prev, ...patch } : prev));
    setDirty(true);
  };

  const updateVisibility = (patch: Partial<PortfolioVisibility>) => {
    if (!page) return;
    setPage({ ...page, visibility: { ...page.visibility, ...patch } });
    setDirty(true);
  };

  const handleSave = async () => {
    if (!page) return;
    setSaving(true);
    try {
      await api.portfolio.update({
        bio: page.bio,
        niches: page.niches,
        visibility: page.visibility,
        customLinks: page.customLinks,
        rateCard: page.rateCard,
        coverUrl: page.coverUrl,
      });
      setDirty(false);
      toast({ title: 'Saved', description: 'Your public page is updated.' });
    } catch {
      toast({ title: 'Couldn\'t save', description: 'Try again in a moment.', variant: 'destructive' });
    } finally {
      setSaving(false);
    }
  };

  const handleCopyLink = async () => {
    try {
      await navigator.clipboard.writeText(publicUrl);
      toast({ title: 'Link copied', description: 'Paste it in your Instagram bio.' });
    } catch {
      toast({
        title: 'Couldn’t copy link',
        description: `Copy it manually: ${publicUrl}`,
        variant: 'destructive',
      });
    }
  };

  /**
   * CR-84 — `syncPlatforms` now does a real Meta Graph API fetch + platform_stats upsert
   * server-side (PortfolioService.java), so this call can genuinely fail for reasons beyond a
   * rate limit — no connected account, an expired token, or a live Meta API error. Map each real
   * error code to an honest message instead of the old one-size-fits-all "Sync limit reached",
   * and re-fetch the page/analytics after a real success so the editor reflects what the sync
   * actually wrote rather than stale client-side numbers.
   */
  const handleSync = async () => {
    setSyncing(true);
    try {
      await api.portfolio.syncPlatforms();
      const [p, a] = await Promise.all([api.portfolio.getMine(), api.portfolio.analytics()]);
      setPage(p as PortfolioPage);
      setAnalytics(a as PortfolioAnalytics);
      toast({ title: 'Synced', description: 'Platform stats refreshed.' });
    } catch (err) {
      const code = err instanceof ApiError ? err.code : undefined;
      const messages: Record<string, string> = {
        NOT_CONNECTED: 'Connect your Instagram account first, then sync.',
        TOKEN_EXPIRED: 'Your Instagram connection expired — reconnect to sync.',
        META_RATE_LIMITED: 'Instagram sync is temporarily rate-limited. Try again shortly.',
      };
      toast({
        title: "Couldn't sync",
        description:
          (code && messages[code]) ||
          (err instanceof ApiError ? err.message : 'Please try again in a moment.'),
        variant: 'destructive',
      });
    } finally {
      setSyncing(false);
    }
  };

  const handleCoverUpload = async (file: File) => {
    setCoverUploading(true);
    try {
      const { url } = await api.portfolio.uploadCover(file);
      update({ coverUrl: url });
      toast({ title: 'Cover updated' });
    } catch {
      toast({ title: 'Upload failed', variant: 'destructive' });
    } finally {
      setCoverUploading(false);
    }
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto px-4 py-6 max-w-3xl">
        {/* Header */}
        <div className="mb-6 flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
          <div>
            <h1 className="text-2xl font-bold">Public Page</h1>
            <p className="text-sm text-muted-foreground mt-1">
              Your living media kit. Share the link from your Instagram bio.
            </p>
          </div>
          {dirty && (
            <Button onClick={handleSave} disabled={saving} className="gap-2 shrink-0">
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              Save changes
            </Button>
          )}
        </div>

        {/* Public URL */}
        <Card className="mb-5">
          <CardContent className="p-4">
            <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2">
              Your public page
            </p>
            <div className="flex flex-col sm:flex-row sm:items-center gap-2">
              <code className="flex-1 truncate text-sm font-medium bg-muted px-3 py-2 rounded-md">
                {publicUrl}
              </code>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" onClick={handleCopyLink} className="gap-1.5">
                  <Copy className="h-3.5 w-3.5" />
                  Copy
                </Button>
                <Button variant="outline" size="sm" asChild className="gap-1.5">
                  <a href={`/@${page.username}`} target="_blank" rel="noreferrer">
                    <ExternalLink className="h-3.5 w-3.5" />
                    View
                  </a>
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Analytics — last 30 days */}
        {analytics && (
          <Card className="mb-5">
            <CardContent className="p-4">
              <div className="flex items-center justify-between mb-3">
                <p className="text-sm font-medium flex items-center gap-1.5">
                  <TrendingUp className="h-4 w-4 text-emerald-600" />
                  Last 30 days
                </p>
              </div>
              <div className="grid grid-cols-3 gap-3">
                <Stat label="Page views" value={analytics.pageViews.last30Days} delta={analytics.pageViews.deltaPercent} />
                {/* CR-71 — profileClicks (PortfolioService.java) is totalFollowers / 100, a rough
                    proxy — no real click-tracking event exists for it, unlike pageViews above.
                    Labeled from the server's own profileClicksEstimated flag rather than a
                    hardcoded note, so this stops claiming "estimated" the moment real tracking
                    ships without needing a matching frontend change. */}
                <Stat
                  label="Profile clicks"
                  value={analytics.profileClicks}
                  note={analytics.profileClicksEstimated ? 'Estimated from follower count' : undefined}
                />
                <Stat label="Brand inquiries" value={analytics.brandInquiries} />
              </div>
            </CardContent>
          </Card>
        )}

        {/* Cover photo */}
        <Card className="mb-5">
          <CardContent className="p-4">
            <Label className="text-sm font-medium">Cover photo</Label>
            <p className="text-xs text-muted-foreground mt-0.5">
              1400×400 recommended. Gradient is used if you skip this.
            </p>
            <div className="mt-3 flex items-stretch gap-3">
              <div
                className={cn(
                  'h-20 sm:h-24 flex-1 rounded-lg overflow-hidden border border-border bg-cover bg-center',
                  page.coverUrl && 'bg-[image:var(--cover-url)]',
                  !page.coverUrl && 'bg-gradient-to-br from-primary/30 via-purple-300/40 to-pink-300/30',
                )}
                ref={page.coverUrl ? cssVars({ '--cover-url': `url(${page.coverUrl})` }) : undefined}
              />
              <div className="flex flex-col gap-1.5">
                <label
                  className={cn(
                    'inline-flex items-center justify-center gap-1.5 rounded-md border border-input bg-background px-3 py-1.5 text-xs font-medium hover:bg-muted cursor-pointer',
                    coverUploading && 'opacity-50 pointer-events-none',
                  )}
                >
                  {coverUploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
                  Upload
                  <input
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={(e) => {
                      const f = e.target.files?.[0];
                      if (f) handleCoverUpload(f);
                    }}
                  />
                </label>
                {page.coverUrl && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="h-7 text-xs gap-1.5 text-destructive-foreground"
                    onClick={() => update({ coverUrl: '' })}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    Remove
                  </Button>
                )}
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Profile basics */}
        <Card className="mb-5">
          <CardContent className="p-4 space-y-4">
            <p className="text-sm font-medium">Profile</p>
            <div className="space-y-1.5">
              <Label htmlFor="bio">Bio</Label>
              <Input
                id="bio"
                value={page.bio}
                onChange={(e) => update({ bio: e.target.value })}
                maxLength={160}
                placeholder="A 1–2 line intro brands will see first"
              />
              <p className="text-[11px] text-muted-foreground text-right">{page.bio.length}/160</p>
            </div>

            <div className="flex items-end gap-2">
              <div className="flex-1">
                <Label className="text-xs">Niches (up to 3, comma separated)</Label>
                <Input
                  value={page.niches.join(', ')}
                  onChange={(e) =>
                    update({
                      niches: e.target.value
                        .split(',')
                        .map((s) => s.trim())
                        .filter(Boolean)
                        .slice(0, 3),
                    })
                  }
                  placeholder="Fashion, Beauty, Travel"
                />
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Visibility controls */}
        <Card className="mb-5">
          <CardContent className="p-4">
            <p className="text-sm font-medium mb-3">What's visible on your page</p>
            <div className="space-y-1">
              <VisibilityRow
                label="Trust signals bar"
                hint={`${page.stats.totalCollabs} collabs · ${page.stats.avgRating}★ · ${page.stats.onTimeRate}% OTD`}
                value={page.visibility.trustBar}
                onChange={(v) => updateVisibility({ trustBar: v })}
              />
              <VisibilityRow
                label="Achievement badges"
                hint={`${page.badges.length} badge${page.badges.length === 1 ? '' : 's'} earned`}
                value={page.visibility.badges}
                onChange={(v) => updateVisibility({ badges: v })}
              />
              {/* CR-119 — hint was "Instagram, YouTube — verified via OAuth", false for YouTube:
                  no YouTube OAuth or data-fetch integration exists anywhere in this codebase, so a
                  YouTube figure here is always creator-reported. Telling a creator their YouTube
                  number is OAuth-verified also mis-sets their expectation of what a brand sees,
                  which now reads "Self-reported" on the public page. */}
              <VisibilityRow
                label="Platform stats"
                hint={`Instagram verified via OAuth · others self-reported`}
                value={page.visibility.platformStats}
                onChange={(v) => updateVisibility({ platformStats: v })}
              />
              <VisibilityRow
                label="Past brand collabs"
                hint={`${page.collabs.length} collab${page.collabs.length === 1 ? '' : 's'} · per-brand controls below`}
                value={page.visibility.pastCollabs}
                onChange={(v) => updateVisibility({ pastCollabs: v })}
              />
              <VisibilityRow
                label="Content portfolio"
                hint={`${page.pinnedPosts.length} pinned post${page.pinnedPosts.length === 1 ? '' : 's'}`}
                value={page.visibility.contentPortfolio}
                onChange={(v) => updateVisibility({ contentPortfolio: v })}
              />
              <VisibilityRow
                label="Custom links"
                hint={`${page.customLinks.length} link${page.customLinks.length === 1 ? '' : 's'}`}
                value={page.visibility.customLinks}
                onChange={(v) => updateVisibility({ customLinks: v })}
              />
              <VisibilityRow
                label="Languages & audience cities"
                value={page.visibility.languages}
                onChange={(v) => updateVisibility({ languages: v })}
              />
              <VisibilityRow
                label="Contact form"
                hint="Visitors can email you (anti-spam-protected)"
                value={page.visibility.contactForm}
                onChange={(v) => updateVisibility({ contactForm: v })}
              />

              {/* Rate card — tri-state */}
              <div className="flex items-center justify-between gap-3 py-3 border-t border-border">
                <div className="min-w-0">
                  <p className="text-sm font-medium">Rate card</p>
                  <p className="text-xs text-muted-foreground">
                    Show your collaboration rates publicly or only to verified brands.
                  </p>
                </div>
                <Select
                  value={page.visibility.rateCard}
                  onValueChange={(v) =>
                    updateVisibility({ rateCard: v as PortfolioVisibility['rateCard'] })
                  }
                >
                  <SelectTrigger className="w-32 h-8 text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="public">Public</SelectItem>
                    <SelectItem value="brands_only">Brands only</SelectItem>
                    <SelectItem value="hidden">Hidden</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Platform sync */}
        <Card className="mb-5">
          <CardContent className="p-4">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-sm font-medium">Connected platforms</p>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Synced daily. Manual refresh limited to once per hour.
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={handleSync}
                disabled={syncing}
                className="gap-1.5 shrink-0"
              >
                {syncing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
                Sync now
              </Button>
            </div>
            <div className="mt-3 space-y-2">
              {page.platforms.map((p) => (
                <div key={p.platform} className="flex items-center justify-between text-sm">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{p.handle}</span>
                    {p.isVerified && (
                      <Badge variant="secondary" className="h-5 px-1.5 text-[10px] gap-0.5">
                        <CheckCircle2 className="h-2.5 w-2.5 text-blue-500" /> Verified
                      </Badge>
                    )}
                  </div>
                  <span className="text-xs text-muted-foreground">{formatN(p.followers)} followers</span>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* Custom links editor */}
        <CustomLinksEditor links={page.customLinks} onChange={(links) => update({ customLinks: links })} />

        {/* Past collabs per-row controls */}
        {page.collabs.length > 0 && (
          <Card className="mb-5">
            <CardContent className="p-4">
              <p className="text-sm font-medium mb-3">Past collabs — what shows on your page</p>
              <div className="space-y-1.5">
                {page.collabs.map((c) => (
                  <div key={c.id} className="flex items-center gap-3 py-2 border-b border-border last:border-0">
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate">{c.brandName}</p>
                      <p className="text-xs text-muted-foreground truncate">{c.campaignTitle}</p>
                    </div>
                    <Select
                      value={c.displayMode}
                      onValueChange={(v) =>
                        update({
                          collabs: page.collabs.map((x) =>
                            x.id === c.id
                              ? { ...x, displayMode: v as typeof c.displayMode }
                              : x,
                          ),
                        })
                      }
                    >
                      <SelectTrigger className="w-36 h-7 text-xs">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="logo">Name + logo</SelectItem>
                        <SelectItem value="name_only">Name only</SelectItem>
                        <SelectItem value="category">Anonymous</SelectItem>
                        <SelectItem value="hidden">Hide</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        )}

        {/* Sticky save bar — only when dirty on small screens */}
        {dirty && (
          <div className="sticky bottom-3 mt-6 sm:hidden">
            <Button onClick={handleSave} disabled={saving} className="w-full gap-2 shadow-lg">
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              Save changes
            </Button>
          </div>
        )}
      </div>
    </CreatorLayout>
  );
}

// ===========================================================================
// Sub-components
// ===========================================================================

function VisibilityRow({
  label,
  hint,
  value,
  onChange,
}: {
  label: string;
  hint?: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3 py-2.5 border-t border-border first:border-t-0">
      <div className="min-w-0 flex items-start gap-2">
        {value ? (
          <Eye className="h-4 w-4 text-foreground mt-0.5 shrink-0" />
        ) : (
          <EyeOff className="h-4 w-4 text-muted-foreground mt-0.5 shrink-0" />
        )}
        <div className="min-w-0">
          <p className="text-sm font-medium">{label}</p>
          {hint && <p className="text-xs text-muted-foreground truncate">{hint}</p>}
        </div>
      </div>
      <Switch checked={value} onCheckedChange={onChange} />
    </div>
  );
}

function Stat({ label, value, delta, note }: { label: string; value: number; delta?: number; note?: string }) {
  return (
    <div>
      <p className="text-xl font-bold leading-none">{formatN(value)}</p>
      <p className="text-[11px] text-muted-foreground mt-1 flex items-center gap-1">
        {label}
        {typeof delta === 'number' && (
          <span className={cn(
            'text-[10px] font-medium',
            delta > 0 ? 'text-emerald-600' : 'text-destructive-foreground',
          )}>
            {delta > 0 ? '+' : ''}{delta}%
          </span>
        )}
      </p>
      {/* CR-71 — profileClicks is a follower-count proxy, not a real click measurement. Labeled
          rather than presented as a real number, same discipline as this file's other real
          fields (pageViews/mediaKitDownloads are genuine portfolio_events counts). */}
      {note && <p className="text-[10px] text-muted-foreground/70 italic">{note}</p>}
    </div>
  );
}

const LINK_ICON_OPTIONS: Array<{ value: string; label: string; icon: React.ComponentType<{ className?: string }> }> = [
  { value: 'Globe',         label: 'Website',  icon: Globe },
  { value: 'ShoppingCart',  label: 'Shop',     icon: ShoppingCart },
  { value: 'GraduationCap', label: 'Course',   icon: GraduationCap },
  { value: 'Mail',          label: 'Booking',  icon: Mail },
  { value: 'Briefcase',     label: 'Work',     icon: Briefcase },
];

function iconForName(name?: string) {
  const found = LINK_ICON_OPTIONS.find((o) => o.value === name);
  return found ? found.icon : Globe;
}

function CustomLinksEditor({
  links,
  onChange,
}: {
  links: PortfolioCustomLink[];
  onChange: (links: PortfolioCustomLink[]) => void;
}) {
  const max = 10;

  const add = () => {
    if (links.length >= max) return;
    onChange([
      ...links,
      { id: `tmp_${Date.now()}`, label: '', url: '', icon: 'Globe' },
    ]);
  };

  const update = (id: string, patch: Partial<PortfolioCustomLink>) => {
    onChange(links.map((l) => (l.id === id ? { ...l, ...patch } : l)));
  };

  const remove = (id: string) => {
    onChange(links.filter((l) => l.id !== id));
  };

  const move = (id: string, dir: -1 | 1) => {
    const idx = links.findIndex((l) => l.id === id);
    if (idx < 0) return;
    const next = idx + dir;
    if (next < 0 || next >= links.length) return;
    const copy = [...links];
    [copy[idx], copy[next]] = [copy[next], copy[idx]];
    onChange(copy);
  };

  return (
    <Card className="mb-5">
      <CardContent className="p-4">
        <div className="flex items-center justify-between mb-3">
          <div>
            <p className="text-sm font-medium">Custom links</p>
            <p className="text-xs text-muted-foreground">Up to {max}. Drag-style reorder via arrows.</p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={add}
            disabled={links.length >= max}
            className="gap-1.5"
          >
            <Plus className="h-3.5 w-3.5" />
            Add
          </Button>
        </div>
        {links.length === 0 ? (
          <p className="text-xs text-muted-foreground py-4 text-center">No links yet. Add Amazon wishlist, a course, your calendly…</p>
        ) : (
          <div className="space-y-2">
            {links.map((link, idx) => {
              const Icon = iconForName(link.icon);
              return (
                <div key={link.id} className="flex items-start gap-2 rounded-lg border border-border p-2">
                  <div className="flex flex-col gap-0.5 pt-0.5">
                    <button
                      type="button"
                      onClick={() => move(link.id, -1)}
                      disabled={idx === 0}
                      className="text-muted-foreground hover:text-foreground disabled:opacity-30"
                      aria-label="Move up"
                    >
                      ▲
                    </button>
                    <button
                      type="button"
                      onClick={() => move(link.id, 1)}
                      disabled={idx === links.length - 1}
                      className="text-muted-foreground hover:text-foreground disabled:opacity-30"
                      aria-label="Move down"
                    >
                      ▼
                    </button>
                  </div>
                  <Select value={link.icon || 'Globe'} onValueChange={(v) => update(link.id, { icon: v })}>
                    <SelectTrigger className="w-12 h-9 px-2">
                      <Icon className="h-4 w-4" />
                    </SelectTrigger>
                    <SelectContent>
                      {LINK_ICON_OPTIONS.map((opt) => {
                        const OptIcon = opt.icon;
                        return (
                          <SelectItem key={opt.value} value={opt.value}>
                            <span className="inline-flex items-center gap-2">
                              <OptIcon className="h-3.5 w-3.5" />
                              {opt.label}
                            </span>
                          </SelectItem>
                        );
                      })}
                    </SelectContent>
                  </Select>
                  <div className="flex-1 grid grid-cols-1 sm:grid-cols-2 gap-2">
                    <Input
                      value={link.label}
                      onChange={(e) => update(link.id, { label: e.target.value })}
                      placeholder="Label (e.g., My Course)"
                      className="h-9"
                    />
                    <Input
                      value={link.url}
                      onChange={(e) => update(link.id, { url: e.target.value })}
                      placeholder="https://…"
                      className="h-9"
                    />
                  </div>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    onClick={() => remove(link.id)}
                    aria-label="Remove link"
                    className="h-9 w-9 text-muted-foreground hover:text-destructive-foreground"
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function formatN(n: number) {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(n >= 10_000 ? 0 : 1)}K`;
  return String(n);
}
