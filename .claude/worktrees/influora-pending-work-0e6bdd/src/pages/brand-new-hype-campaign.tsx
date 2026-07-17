import * as React from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { ArrowLeft, Hash, IndianRupee, Link2, Loader2, Music2, Plus, Users, X, Zap } from 'lucide-react';

import { api } from '@/lib/api';
import { cn, formatINR } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { HypeLiveIndicator } from '@/components/ui/hype-live-indicator';
import { SlotProgressBar } from '@/components/ui/slot-progress-bar';

/**
 * Hype Campaign creation — 72-hr blitz where creators remix a source reel at
 * a flat per-reel rate. Spec: docs/FRONTEND-DESIGN-TASK.md.
 *
 * Distinct from the standard wizard: a single focused form because Hype has
 * no negotiation, no per-creator terms — just rate × slots.
 */

const SUGGESTED_LANES = ['Remix the hook', 'Duet reaction', 'Original spin', 'Voiceover take', 'Tutorial angle'];
const WINDOW_HOURS = 72;

interface HypeFormState {
  title: string;
  description: string;
  sourceReelUrl: string;
  audioTrack: string;
  hashtag: string;
  formatLanes: string[];
  perReelRate: string;
  slotCap: string;
}

const initialForm: HypeFormState = {
  title: '',
  description: '',
  sourceReelUrl: '',
  audioTrack: '',
  hashtag: '',
  formatLanes: ['Remix the hook'],
  perReelRate: '',
  slotCap: '100',
};

export default function BrandNewHypeCampaignPage() {
  const navigate = useNavigate();
  const [form, setForm] = React.useState<HypeFormState>(initialForm);
  const [errors, setErrors] = React.useState<Partial<Record<keyof HypeFormState, string>>>({});
  const [submitting, setSubmitting] = React.useState(false);
  const [customLane, setCustomLane] = React.useState('');

  const update = (patch: Partial<HypeFormState>) => {
    setForm((f) => ({ ...f, ...patch }));
    setErrors((e) => {
      const next = { ...e };
      (Object.keys(patch) as Array<keyof HypeFormState>).forEach((k) => delete next[k]);
      return next;
    });
  };

  const rate = Number(form.perReelRate) || 0;
  const slots = Number(form.slotCap) || 0;
  const totalBudget = rate * slots;

  const toggleLane = (lane: string) => {
    update({
      formatLanes: form.formatLanes.includes(lane)
        ? form.formatLanes.filter((l) => l !== lane)
        : [...form.formatLanes, lane],
    });
  };

  const addCustomLane = () => {
    const lane = customLane.trim();
    if (lane && !form.formatLanes.includes(lane)) {
      update({ formatLanes: [...form.formatLanes, lane] });
    }
    setCustomLane('');
  };

  const validate = (): boolean => {
    const next: Partial<Record<keyof HypeFormState, string>> = {};
    if (!form.title.trim()) next.title = 'Give your Hype campaign a name';
    if (!form.sourceReelUrl.trim()) next.sourceReelUrl = 'The source reel is what creators remix';
    else if (!/^https?:\/\/\S+$/i.test(form.sourceReelUrl.trim())) next.sourceReelUrl = 'Enter a valid URL';
    if (!form.hashtag.trim()) next.hashtag = 'Campaign hashtag is required';
    if (form.formatLanes.length === 0) next.formatLanes = 'Pick at least one format lane';
    if (rate <= 0) next.perReelRate = 'Set the flat rate creators earn per reel';
    if (slots <= 0) next.slotCap = 'Set how many creator slots are available';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setSubmitting(true);
    try {
      const hashtag = form.hashtag.startsWith('#') ? form.hashtag : `#${form.hashtag}`;
      await api.campaigns.create({
        title: form.title.trim(),
        description: form.description.trim() || undefined,
        campaignType: 'HYPE',
        status: 'ACTIVE',
        budget: { min: totalBudget, max: totalBudget, currency: 'INR' },
        platforms: ['INSTAGRAM'],
        contentTypes: ['REEL'],
        isPrivate: false,
        maxCollaborators: slots,
        hashtags: [hashtag],
        hype: {
          sourceReelUrl: form.sourceReelUrl.trim(),
          audioTrack: form.audioTrack.trim() || undefined,
          hashtag,
          formatLanes: form.formatLanes,
          perReelRate: rate,
          currency: 'INR',
          slotCap: slots,
          slotsFilled: 0,
          liveUntil: new Date(Date.now() + WINDOW_HOURS * 60 * 60 * 1000),
        },
        timeline: {
          startDate: new Date(),
          endDate: new Date(Date.now() + WINDOW_HOURS * 60 * 60 * 1000),
        },
      });
      navigate('/brand/campaigns');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mx-auto max-w-3xl p-6">
      <Button variant="ghost" size="sm" asChild className="mb-4 -ml-2 gap-1">
        <Link to="/brand/campaigns/new">
          <ArrowLeft className="h-4 w-4" aria-hidden="true" /> Campaign types
        </Link>
      </Button>

      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <Badge className="gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype">
              <Zap className="h-3 w-3" aria-hidden="true" /> Hype Campaign
            </Badge>
            <HypeLiveIndicator hoursLeft={WINDOW_HOURS} />
          </div>
          <h1 className="mt-2 text-2xl font-semibold">Launch a 72-hour blitz</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Creators remix your source reel at a flat rate until slots fill or the window closes.
            First-come, one-tap accept — no negotiation.
          </p>
        </div>
      </div>

      <form onSubmit={handleSubmit} noValidate>
        <div className="space-y-6">
          <Card className="border-hype-border">
            <CardHeader>
              <CardTitle className="text-base">Source content</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="hype-title">Campaign title</Label>
                <Input
                  id="hype-title"
                  placeholder="e.g., Glow Drop Challenge"
                  value={form.title}
                  onChange={(e) => update({ title: e.target.value })}
                  className={cn(errors.title && 'border-destructive-foreground')}
                />
                {errors.title && <p className="text-xs text-destructive-foreground">{errors.title}</p>}
              </div>

              <div className="space-y-2">
                <Label htmlFor="hype-source">Source reel URL</Label>
                <div className="relative">
                  <Link2 className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                  <Input
                    id="hype-source"
                    type="url"
                    inputMode="url"
                    placeholder="https://instagram.com/reel/…"
                    value={form.sourceReelUrl}
                    onChange={(e) => update({ sourceReelUrl: e.target.value })}
                    className={cn('pl-9', errors.sourceReelUrl && 'border-destructive-foreground')}
                  />
                </div>
                {errors.sourceReelUrl && <p className="text-xs text-destructive-foreground">{errors.sourceReelUrl}</p>}
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-2">
                  <Label htmlFor="hype-audio">Audio track (optional)</Label>
                  <div className="relative">
                    <Music2 className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                    <Input
                      id="hype-audio"
                      placeholder="Track creators must use"
                      value={form.audioTrack}
                      onChange={(e) => update({ audioTrack: e.target.value })}
                      className="pl-9"
                    />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="hype-hashtag">Campaign hashtag</Label>
                  <div className="relative">
                    <Hash className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                    <Input
                      id="hype-hashtag"
                      placeholder="GlowDropChallenge"
                      value={form.hashtag}
                      onChange={(e) => update({ hashtag: e.target.value })}
                      className={cn('pl-9', errors.hashtag && 'border-destructive-foreground')}
                    />
                  </div>
                  {errors.hashtag && <p className="text-xs text-destructive-foreground">{errors.hashtag}</p>}
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="hype-desc">Brief (optional)</Label>
                <Textarea
                  id="hype-desc"
                  rows={3}
                  placeholder="Anything creators should know — brand do's and don'ts, product angle, vibe…"
                  value={form.description}
                  onChange={(e) => update({ description: e.target.value })}
                />
              </div>
            </CardContent>
          </Card>

          <Card className="border-hype-border">
            <CardHeader>
              <CardTitle className="text-base">Format lanes</CardTitle>
              <p className="text-sm text-muted-foreground">
                The remix styles creators can pick from.
              </p>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex flex-wrap gap-2">
                {SUGGESTED_LANES.map((lane) => (
                  <Badge
                    key={lane}
                    variant={form.formatLanes.includes(lane) ? 'default' : 'outline'}
                    className={cn(
                      'cursor-pointer',
                      form.formatLanes.includes(lane) &&
                        'border-hype-border bg-hype text-hype-foreground hover:bg-hype',
                    )}
                    onClick={() => toggleLane(lane)}
                  >
                    {lane}
                  </Badge>
                ))}
                {form.formatLanes
                  .filter((lane) => !SUGGESTED_LANES.includes(lane))
                  .map((lane) => (
                    <Badge
                      key={lane}
                      className="cursor-pointer gap-1 border-hype-border bg-hype text-hype-foreground hover:bg-hype"
                      onClick={() => toggleLane(lane)}
                    >
                      {lane}
                      <X className="h-3 w-3" aria-hidden="true" />
                    </Badge>
                  ))}
              </div>
              <div className="flex gap-2">
                <Input
                  placeholder="Add your own lane…"
                  value={customLane}
                  onChange={(e) => setCustomLane(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      addCustomLane();
                    }
                  }}
                  className="max-w-xs"
                />
                <Button type="button" variant="outline" size="icon" onClick={addCustomLane} aria-label="Add format lane">
                  <Plus className="h-4 w-4" />
                </Button>
              </div>
              {errors.formatLanes && <p className="text-xs text-destructive-foreground">{errors.formatLanes}</p>}
            </CardContent>
          </Card>

          <Card className="border-hype-border">
            <CardHeader>
              <CardTitle className="text-base">Rate &amp; slots</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-2">
                  <Label htmlFor="hype-rate">Per-reel rate (INR)</Label>
                  <div className="relative">
                    <IndianRupee className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                    <Input
                      id="hype-rate"
                      type="number"
                      inputMode="numeric"
                      min={0}
                      step={100}
                      placeholder="3500"
                      value={form.perReelRate}
                      onChange={(e) => update({ perReelRate: e.target.value })}
                      className={cn('pl-9', errors.perReelRate && 'border-destructive-foreground')}
                    />
                  </div>
                  {errors.perReelRate && <p className="text-xs text-destructive-foreground">{errors.perReelRate}</p>}
                </div>
                <div className="space-y-2">
                  <Label htmlFor="hype-slots">Slot cap</Label>
                  <div className="relative">
                    <Users className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                    <Input
                      id="hype-slots"
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={1000}
                      placeholder="100"
                      value={form.slotCap}
                      onChange={(e) => update({ slotCap: e.target.value })}
                      className={cn('pl-9', errors.slotCap && 'border-destructive-foreground')}
                    />
                  </div>
                  {errors.slotCap && <p className="text-xs text-destructive-foreground">{errors.slotCap}</p>}
                </div>
              </div>

              {slots > 0 && (
                <div className="rounded-lg border border-hype-border bg-hype/40 p-4">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">Escrow required (rate × slots)</span>
                    <span className="font-semibold">{formatINR(totalBudget)}</span>
                  </div>
                  <SlotProgressBar filled={0} total={slots} className="mt-3" />
                  <p className="mt-2 text-xs text-muted-foreground">
                    Full budget is locked in escrow at launch. Unfilled slots are refunded when the
                    {' '}{WINDOW_HOURS}-hour window closes.
                  </p>
                </div>
              )}
            </CardContent>
          </Card>

          <div className="flex items-center justify-end gap-3">
            <Button type="button" variant="ghost" onClick={() => navigate('/brand/campaigns')}>
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={submitting}
              className="gap-1.5 bg-hype-solid text-white hover:bg-hype-solid/90"
            >
              {submitting ? (
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              ) : (
                <Zap className="h-4 w-4" aria-hidden="true" />
              )}
              Launch Hype Campaign
            </Button>
          </div>
        </div>
      </form>
    </div>
  );
}
