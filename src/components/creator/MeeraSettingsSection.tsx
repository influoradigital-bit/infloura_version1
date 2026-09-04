import * as React from 'react';
import { Loader2, Sparkles, Download, Trash2 } from 'lucide-react';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Separator } from '@/components/ui/separator';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import {
  api,
  ApiError,
  type CreatorAgentPreferences,
  type CreatorAgentPreferencesUpdate,
  type CreatorAgentConversationItem,
} from '@/lib/api';
import { toast } from '@/hooks/use-toast';

/**
 * T-MEERA-CREATOR-PHASE-A (A3/A6, SPEC.md §2.2-2.7 / §4.3/§4.5) — the creator-side "Meera"
 * settings section: rate floors, filters, automation level, language/tone, working hours, the
 * represented block, and the DPDP conversations list (export/delete). Rendered as a Card inside
 * creator-settings.tsx, not a separate route — matches every other settings group on that page.
 */

const EXCLUDABLE_CATEGORIES = [
  'Alcohol',
  'Tobacco',
  'Gambling',
  'Adult Content',
  'Political',
  'Pharma/Health Claims',
  'Crypto/Finance',
];

const WORKING_DAY_OPTIONS: { value: number; label: string }[] = [
  { value: 1, label: 'Mon' },
  { value: 2, label: 'Tue' },
  { value: 3, label: 'Wed' },
  { value: 4, label: 'Thu' },
  { value: 5, label: 'Fri' },
  { value: 6, label: 'Sat' },
  { value: 7, label: 'Sun' },
];

/**
 * Gate fix round 2, item 3 (Priya Q8) — short curated list, not every IANA zone (~600 of them).
 * Asia/Kolkata first/default since that's the server-side default (CreatorAgentPreferences
 * .DEFAULT_WORKING_HOURS_TIMEZONE). A persisted value outside this list (set some other way, or
 * a zone we later drop from the list) is still shown correctly — see `withPersistedOption` below.
 */
const CURATED_TIMEZONES: { value: string; label: string }[] = [
  { value: 'Asia/Kolkata', label: 'India — Asia/Kolkata (IST)' },
  { value: 'Asia/Dubai', label: 'UAE — Asia/Dubai (GST)' },
  { value: 'Asia/Karachi', label: 'Pakistan — Asia/Karachi (PKT)' },
  { value: 'Asia/Dhaka', label: 'Bangladesh — Asia/Dhaka (BST)' },
  { value: 'Asia/Singapore', label: 'Singapore — Asia/Singapore (SGT)' },
  { value: 'Europe/London', label: 'UK — Europe/London (GMT/BST)' },
  { value: 'America/New_York', label: 'US Eastern — America/New_York (ET)' },
  { value: 'America/Los_Angeles', label: 'US Pacific — America/Los_Angeles (PT)' },
  { value: 'Australia/Sydney', label: 'Australia — Australia/Sydney (AEST/AEDT)' },
];

/** Gate fix round 2, item 3 (Priya Q8) — curated ISO 4217 codes; INR first/default to match
 *  CreatorAgentPreferences.DEFAULT_FLOOR_CURRENCY. */
const CURATED_CURRENCIES: { value: string; label: string }[] = [
  { value: 'INR', label: 'INR — Indian Rupee' },
  { value: 'USD', label: 'USD — US Dollar' },
  { value: 'EUR', label: 'EUR — Euro' },
  { value: 'GBP', label: 'GBP — British Pound' },
  { value: 'AED', label: 'AED — UAE Dirham' },
  { value: 'SGD', label: 'SGD — Singapore Dollar' },
  { value: 'AUD', label: 'AUD — Australian Dollar' },
];

/** Appends the persisted value as a trailing option when it isn't already in the curated list,
 *  so a value set some other way (admin tooling, a future currency/zone we haven't curated yet)
 *  still round-trips instead of silently snapping to the first option on save. */
function withPersistedOption(
  options: { value: string; label: string }[],
  persisted: string | undefined,
): { value: string; label: string }[] {
  if (!persisted || options.some((o) => o.value === persisted)) return options;
  return [...options, { value: persisted, label: persisted }];
}

/** Resolves the currency SYMBOL (₹, $, €, …) for a persisted ISO 4217 code, instead of the
 *  hardcoded "₹" the floor labels used to carry regardless of floor_currency. Falls back to the
 *  code itself if the runtime's Intl data doesn't recognize it (shouldn't happen for the curated
 *  list, but a persisted value could be an edge case). */
function currencySymbolFor(code: string): string {
  try {
    const part = new Intl.NumberFormat('en', { style: 'currency', currency: code })
      .formatToParts(0)
      .find((p) => p.type === 'currency');
    return part?.value ?? code;
  } catch {
    return code;
  }
}

/** Draft shape mirrors the PUT body exactly (CreatorAgentPreferencesUpdate) plus a couple of
 *  string-input scratch fields (blockedBrandsDraft) that get parsed on save/change. */
type Draft = CreatorAgentPreferencesUpdate;

function toDraft(prefs: CreatorAgentPreferences): Draft {
  // Both consent_accepted and consent_version are server-owned (recordConsent only, never this
  // PUT) — UpdatePreferencesRequest on the Java side has no field for either, so neither belongs
  // in the draft/payload.
  const { consent_accepted: _consent_accepted, consent_version: _consent_version, ...rest } = prefs;
  return rest;
}

function formatDateTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
  } catch {
    return iso;
  }
}

export function MeeraSettingsSection() {
  const [loading, setLoading] = React.useState(true);
  const [loadError, setLoadError] = React.useState<string | null>(null);
  const [draft, setDraft] = React.useState<Draft | null>(null);
  const [blockedBrandsText, setBlockedBrandsText] = React.useState('');
  const [saving, setSaving] = React.useState(false);
  const [saveError, setSaveError] = React.useState<string | null>(null);
  // Gate review fix (item 3) — MEERA_CREATOR_ENABLED rollback flag. GET /creator/agent-preferences
  // 404s with { code: 'FEATURE_DISABLED' } when it's off; this section just disappears (no card,
  // no error text, no toast, no retry) rather than showing a broken-looking settings block.
  const [featureDisabled, setFeatureDisabled] = React.useState(false);

  const [conversations, setConversations] = React.useState<CreatorAgentConversationItem[]>([]);
  const [conversationsLoading, setConversationsLoading] = React.useState(true);
  const [exportingId, setExportingId] = React.useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = React.useState<string | null>(null);
  const [deletingId, setDeletingId] = React.useState<string | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    api.creatorAgentPrefs
      .getPreferences()
      .then((prefs) => {
        if (cancelled) return;
        setDraft(toDraft(prefs));
        setBlockedBrandsText(prefs.blocked_brands.join('\n'));
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.code === 'FEATURE_DISABLED') {
          setFeatureDisabled(true);
          return;
        }
        setLoadError(err instanceof ApiError ? err.message : 'Could not load Meera settings.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Gate fix round 2, item 3 (Priya Q8) — options list is the curated set plus the persisted
  // value trailing on if it's not already in it, so an out-of-list value still round-trips.
  const timezoneOptions = React.useMemo(
    () => withPersistedOption(CURATED_TIMEZONES, draft?.working_hours_timezone),
    [draft?.working_hours_timezone],
  );
  const currencyOptions = React.useMemo(
    () => withPersistedOption(CURATED_CURRENCIES, draft?.floor_currency),
    [draft?.floor_currency],
  );
  const currencySymbol = draft ? currencySymbolFor(draft.floor_currency) : '';

  const loadConversations = React.useCallback(() => {
    setConversationsLoading(true);
    api.creatorAgentPrefs
      .listConversations()
      .then((res) => setConversations(res.conversations))
      .catch(() => setConversations([]))
      .finally(() => setConversationsLoading(false));
  }, []);

  React.useEffect(() => {
    loadConversations();
  }, [loadConversations]);

  const update = (patch: Partial<Draft>) => {
    setDraft((prev) => (prev ? { ...prev, ...patch } : prev));
  };

  const toggleCategory = (category: string) => {
    if (!draft) return;
    const active = draft.excluded_categories.includes(category);
    update({
      excluded_categories: active
        ? draft.excluded_categories.filter((c) => c !== category)
        : [...draft.excluded_categories, category],
    });
  };

  const toggleWorkingDay = (day: number) => {
    if (!draft) return;
    const active = draft.working_days.includes(day);
    update({
      working_days: active ? draft.working_days.filter((d) => d !== day) : [...draft.working_days, day].sort(),
    });
  };

  const handleSave = async () => {
    if (!draft) return;
    if (draft.represented && !draft.agency_name?.trim()) {
      setSaveError('Agency name is required when you are represented.');
      return;
    }
    setSaveError(null);
    setSaving(true);
    try {
      const payload: Draft = {
        ...draft,
        blocked_brands: blockedBrandsText
          .split('\n')
          .map((b) => b.trim())
          .filter(Boolean),
        agency_name: draft.represented ? draft.agency_name?.trim() || null : null,
      };
      const saved = await api.creatorAgentPrefs.updatePreferences(payload);
      setDraft(toDraft(saved));
      setBlockedBrandsText(saved.blocked_brands.join('\n'));
      toast({ title: 'Meera settings saved' });
    } catch (err) {
      setSaveError(err instanceof ApiError ? err.message : 'Could not save your changes.');
    } finally {
      setSaving(false);
    }
  };

  const handleExport = async (conversationId: string) => {
    setExportingId(conversationId);
    try {
      const data = await api.creatorAgentPrefs.exportConversation(conversationId);
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `meera-conversation-${conversationId}.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      toast({
        title: 'Could not export conversation',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setExportingId(null);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeletingId(deleteTarget);
    try {
      await api.creatorAgentPrefs.deleteConversation(deleteTarget);
      setConversations((prev) => prev.filter((c) => c.conversation_id !== deleteTarget));
      toast({ title: 'Conversation deleted' });
    } catch (err) {
      toast({
        title: 'Could not delete conversation',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setDeletingId(null);
      setDeleteTarget(null);
    }
  };

  // Gate review fix (item 3) — feature off for this account: hide the whole section. No card,
  // no message, no toast, no retry loop; creator-copilot.tsx's chat entry carries the one calm
  // "not available yet" message so it isn't duplicated here.
  if (featureDisabled) return null;

  return (
    <Card className="mb-6">
      <CardHeader>
        <div className="flex items-center gap-2">
          <Sparkles className="h-5 w-5 text-muted-foreground" />
          <CardTitle className="text-base">Meera</CardTitle>
        </div>
        <CardDescription>Your AI manager's guardrails — floors, filters, and how she talks to brands.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        {loading && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading Meera settings…
          </div>
        )}

        {!loading && loadError && (
          <p className="text-sm text-destructive-foreground">{loadError}</p>
        )}

        {!loading && !loadError && draft && (
          <>
            {/* Rate Floors */}
            <div className="space-y-3">
              <div>
                <p className="text-sm font-medium">Rate Floors</p>
                <p className="text-xs text-muted-foreground">
                  Your minimum rates. Defaults from your last paid deal or platform estimates. Never shown to brands.
                </p>
              </div>
              <div className="grid gap-4 sm:grid-cols-3">
                <div className="space-y-1.5">
                  <Label htmlFor="reelFloor" className="text-xs">Reel ({currencySymbol})</Label>
                  <Input
                    id="reelFloor"
                    type="number"
                    min="0"
                    value={draft.reel_floor ?? ''}
                    onChange={(e) => update({ reel_floor: e.target.value ? Number(e.target.value) : null })}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="storySetFloor" className="text-xs">Story Set ({currencySymbol})</Label>
                  <Input
                    id="storySetFloor"
                    type="number"
                    min="0"
                    value={draft.story_set_floor ?? ''}
                    onChange={(e) => update({ story_set_floor: e.target.value ? Number(e.target.value) : null })}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="postFloor" className="text-xs">Post ({currencySymbol})</Label>
                  <Input
                    id="postFloor"
                    type="number"
                    min="0"
                    value={draft.post_floor ?? ''}
                    onChange={(e) => update({ post_floor: e.target.value ? Number(e.target.value) : null })}
                  />
                </div>
              </div>
              <div className="space-y-1.5 sm:max-w-[220px]">
                <Label className="text-xs">Currency</Label>
                <Select
                  value={draft.floor_currency}
                  onValueChange={(val) => update({ floor_currency: val })}
                >
                  <SelectTrigger aria-label="Rate floor currency">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {currencyOptions.map((c) => (
                      <SelectItem key={c.value} value={c.value}>
                        {c.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            <Separator />

            {/* Filters */}
            <div className="space-y-3">
              <p className="text-sm font-medium">Filters</p>
              <div className="space-y-2">
                <Label className="text-xs">Excluded categories</Label>
                <div className="flex flex-wrap gap-2">
                  {EXCLUDABLE_CATEGORIES.map((category) => (
                    <Badge
                      key={category}
                      variant={draft.excluded_categories.includes(category) ? 'default' : 'outline'}
                      className="cursor-pointer"
                      onClick={() => toggleCategory(category)}
                    >
                      {category}
                    </Badge>
                  ))}
                </div>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="blockedBrands" className="text-xs">Blocked brands (one per line)</Label>
                <Textarea
                  id="blockedBrands"
                  value={blockedBrandsText}
                  onChange={(e) => setBlockedBrandsText(e.target.value)}
                  placeholder="Brand X&#10;Brand Y"
                  rows={3}
                />
              </div>
            </div>

            <Separator />

            {/* Automation Level */}
            <div className="space-y-3">
              <p className="text-sm font-medium">Automation Level</p>
              <RadioGroup
                value={String(draft.approval_level)}
                onValueChange={(val) => update({ approval_level: Number(val) as 0 | 1 | 2 })}
              >
                <div className="flex items-start gap-2">
                  <RadioGroupItem value="0" id="approval-0" className="mt-1" />
                  <Label htmlFor="approval-0" className="font-normal">
                    Draft only — I approve every message
                  </Label>
                </div>
                <div className="flex items-start gap-2">
                  <RadioGroupItem value="1" id="approval-1" className="mt-1" />
                  <Label htmlFor="approval-1" className="font-normal">
                    Routine replies — Meera can send simple messages (media kit, availability)
                  </Label>
                </div>
                <div className="flex items-start gap-2">
                  <RadioGroupItem value="2" id="approval-2" className="mt-1" />
                  <Label htmlFor="approval-2" className="font-normal">
                    Auto-decline — Meera can decline excluded categories or blocked brands
                  </Label>
                </div>
              </RadioGroup>
              <p className="text-xs text-muted-foreground">
                Phase A is conversational only — Meera does not send or decline anything on your behalf yet, regardless of this setting.
              </p>
            </div>

            <Separator />

            {/* Language & Tone */}
            <div className="space-y-3">
              <p className="text-sm font-medium">Language &amp; Tone</p>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label className="text-xs">My language</Label>
                  <Select
                    value={draft.creator_language}
                    onValueChange={(val) => update({ creator_language: val })}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="hi-IN">हिन्दी (Hindi)</SelectItem>
                      <SelectItem value="en-IN">English</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1.5">
                  <Label className="text-xs">Tone to brands</Label>
                  <Select
                    value={draft.brand_tone}
                    onValueChange={(val) => update({ brand_tone: val as 'FORMAL' | 'FRIENDLY' })}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="FORMAL">Formal</SelectItem>
                      <SelectItem value="FRIENDLY">Friendly</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
            </div>

            <Separator />

            {/* Working Hours */}
            <div className="space-y-3">
              <p className="text-sm font-medium">Working Hours</p>
              <div className="grid gap-4 sm:grid-cols-3">
                <div className="space-y-1.5">
                  <Label className="text-xs">Start (hour)</Label>
                  <Input
                    type="number"
                    min="0"
                    max="23"
                    value={draft.working_hours_start ?? ''}
                    onChange={(e) =>
                      update({ working_hours_start: e.target.value ? Number(e.target.value) : null })
                    }
                  />
                </div>
                <div className="space-y-1.5">
                  <Label className="text-xs">End (hour)</Label>
                  <Input
                    type="number"
                    min="0"
                    max="23"
                    value={draft.working_hours_end ?? ''}
                    onChange={(e) =>
                      update({ working_hours_end: e.target.value ? Number(e.target.value) : null })
                    }
                  />
                </div>
                <div className="space-y-1.5">
                  <Label className="text-xs">Max sponsored posts / week</Label>
                  <Input
                    type="number"
                    min="0"
                    value={draft.weekly_sponsored_limit ?? ''}
                    onChange={(e) =>
                      update({ weekly_sponsored_limit: e.target.value ? Number(e.target.value) : null })
                    }
                  />
                </div>
              </div>
              <div className="space-y-1.5 sm:max-w-xs">
                <Label className="text-xs">Timezone</Label>
                <Select
                  value={draft.working_hours_timezone}
                  onValueChange={(val) => update({ working_hours_timezone: val })}
                >
                  <SelectTrigger aria-label="Working hours timezone">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {timezoneOptions.map((tz) => (
                      <SelectItem key={tz.value} value={tz.value}>
                        {tz.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-xs text-muted-foreground">
                  Start/end hours above are in this timezone.
                </p>
              </div>
              <div className="space-y-2">
                <Label className="text-xs">Working days</Label>
                <div className="flex flex-wrap gap-2">
                  {WORKING_DAY_OPTIONS.map((day) => (
                    <Badge
                      key={day.value}
                      variant={draft.working_days.includes(day.value) ? 'default' : 'outline'}
                      className="cursor-pointer"
                      onClick={() => toggleWorkingDay(day.value)}
                    >
                      {day.label}
                    </Badge>
                  ))}
                </div>
              </div>
            </div>

            <Separator />

            {/* Representation */}
            <div className="space-y-3">
              <p className="text-sm font-medium">Representation</p>
              <div className="flex items-center gap-3">
                <Checkbox
                  id="represented"
                  checked={draft.represented}
                  onCheckedChange={(checked) => update({ represented: checked === true })}
                />
                <Label htmlFor="represented" className="font-normal">
                  I am represented by an agency or manager
                </Label>
              </div>
              {draft.represented && (
                <div className="space-y-1.5">
                  <Label htmlFor="agencyName" className="text-xs">Agency name</Label>
                  <Input
                    id="agencyName"
                    value={draft.agency_name ?? ''}
                    onChange={(e) => update({ agency_name: e.target.value })}
                  />
                </div>
              )}
              <p className="text-xs text-muted-foreground">
                Represented mode: Meera warns you only — she never drafts or sends anything to brands.
              </p>
            </div>

            {saveError && <p className="text-sm text-destructive-foreground">{saveError}</p>}

            <Button onClick={handleSave} disabled={saving}>
              {saving ? (
                <>
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  Saving…
                </>
              ) : (
                'Save Meera Settings'
              )}
            </Button>
          </>
        )}

        <Separator />

        {/* My Meera Conversations (A6 — DPDP export/delete) */}
        <div className="space-y-3">
          <div>
            <p className="text-sm font-medium">My Meera Conversations</p>
            <p className="text-xs text-muted-foreground">
              Export or delete any conversation you've had with Meera.
            </p>
          </div>
          {conversationsLoading && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" />
              Loading conversations…
            </div>
          )}
          {!conversationsLoading && conversations.length === 0 && (
            <p className="text-sm text-muted-foreground">No conversations with Meera yet.</p>
          )}
          {!conversationsLoading && conversations.length > 0 && (
            <div className="overflow-x-auto rounded-md border border-border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Started</TableHead>
                    <TableHead>Last Message</TableHead>
                    <TableHead>Messages</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {conversations.map((conv) => (
                    <TableRow key={conv.conversation_id}>
                      <TableCell className="whitespace-nowrap text-sm">{formatDateTime(conv.started_at)}</TableCell>
                      <TableCell className="whitespace-nowrap text-sm">{formatDateTime(conv.last_message_at)}</TableCell>
                      <TableCell className="text-sm">{conv.message_count}</TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-1.5">
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-8 w-8"
                            title="Export as JSON"
                            disabled={exportingId === conv.conversation_id}
                            onClick={() => handleExport(conv.conversation_id)}
                          >
                            {exportingId === conv.conversation_id ? (
                              <Loader2 className="h-4 w-4 animate-spin" />
                            ) : (
                              <Download className="h-4 w-4" />
                            )}
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            className="h-8 w-8 text-stage-disputed-fg hover:text-red-700"
                            title="Delete conversation"
                            disabled={deletingId === conv.conversation_id}
                            onClick={() => setDeleteTarget(conv.conversation_id)}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </div>
      </CardContent>

      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this conversation?</AlertDialogTitle>
            <AlertDialogDescription>
              This permanently deletes the conversation and its messages. This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={!!deletingId}>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-red-600 hover:bg-red-700" disabled={!!deletingId}>
              {deletingId ? 'Deleting…' : 'Delete'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

export default MeeraSettingsSection;
