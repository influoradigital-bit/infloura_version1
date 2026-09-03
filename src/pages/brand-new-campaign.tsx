import * as React from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowRight, LayoutTemplate, Loader2, Megaphone, Trash2, UserRoundSearch, X, Zap } from 'lucide-react';

import { cn } from '@/lib/utils';
import type { CampaignType, ContentType, Platform } from '@/lib/types';
import { CampaignForm, type CampaignFormData } from '@/components/brand/campaigns/campaign-form';
import { BrandKycPrompt } from '@/components/brand/campaigns/brand-kyc-prompt';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { HypeLiveIndicator } from '@/components/ui/hype-live-indicator';
import { api, ApiError, type CampaignTemplateResponse } from '@/lib/api';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/**
 * BR-14 Phase 1 — maps a fetched `CampaignTemplateResponse` onto the subset of
 * `CampaignFormData` the template actually carries. Read is client-side prefill only: there is
 * no apply-template REST route, and `templateId` is never sent to `POST /campaigns` (that would
 * duplicate Meera's CreateCampaignExecutor). Fields the template didn't set are omitted rather
 * than zeroed, so `CampaignForm`'s own `initialFormData` defaults fill the gaps.
 */
function templateToInitialValues(t: CampaignTemplateResponse): Partial<CampaignFormData> {
  const values: Partial<CampaignFormData> = {};
  if (t.budgetMin != null) values.budgetMin = t.budgetMin;
  if (t.budgetMax != null) values.budgetMax = t.budgetMax;
  if (t.platforms?.length) values.platforms = t.platforms as Platform[];
  if (t.contentTypes?.length) values.contentTypes = t.contentTypes as ContentType[];
  if (t.objectives?.length) values.objectives = t.objectives;
  if (t.requirements?.length) values.requirements = t.requirements;
  if (t.hashtags?.length) values.hashtags = t.hashtags;
  if (t.targetAudience) values.targetAudience = t.targetAudience;
  if (t.brandGuidelines) values.brandGuidelines = t.brandGuidelines;
  return values;
}

/**
 * Step 0 of campaign creation: pick the campaign type.
 * Open/Direct continue into the standard wizard; Hype has its own
 * dedicated flow (flat rate × slots, 72-hr window).
 */

interface TypeOption {
  type: CampaignType;
  title: string;
  description: string;
  icon: typeof Megaphone;
  hype?: boolean;
}

const TYPE_OPTIONS: TypeOption[] = [
  {
    type: 'OPEN',
    title: 'Open Campaign',
    description: 'Post a brief publicly — creators apply and you shortlist the best fits.',
    icon: Megaphone,
  },
  {
    type: 'DIRECT',
    title: 'Direct Deal',
    description: 'Invite specific creators and negotiate terms one-on-one in the Deal Room.',
    icon: UserRoundSearch,
  },
  {
    type: 'HYPE',
    title: 'Hype Campaign',
    description: 'A 72-hour blitz: many creators remix one reel at a flat per-reel rate.',
    icon: Zap,
    hype: true,
  },
];

/**
 * T-CREATORCONNECT-0902 — a brand arriving from Discover's "Connect this creator" → Create
 * campaign flow carries `?creatorId=` (the linked CreatorProfile id). Read once, from the actual
 * browser URL — CampaignForm mounts on this SAME route (no navigate() happens between the type
 * picker and the wizard), so it sees the same `?creatorId=` via its own `useSearchParams()` and
 * performs the post-create `api.creators.invite` call. This component only resolves the handle
 * for the informational banner shown before a campaign type is picked.
 */
function useCreatorIdParam() {
  const [searchParams] = useSearchParams();
  return searchParams.get('creatorId');
}

/** Q6.5 — Discover's card and every upstream surface say `@igUsername`; the handoff URL now
 * carries `?ig=` (creator-discovery.tsx) so this banner can show that same handle instead of
 * resolving (and possibly showing a different) Influora username. */
function useIgParam() {
  const [searchParams] = useSearchParams();
  return searchParams.get('ig');
}

/** Dismissible banner — informational only; dismissing it does not cancel the invite. */
function CreatorHandoffBanner({
  creatorId,
  ig,
  onDismiss,
}: {
  creatorId: string;
  ig: string | null;
  onDismiss: () => void;
}) {
  const [resolvedHandle, setResolvedHandle] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (ig) return; // already have the handle Discover handed off — no need to resolve.
    let cancelled = false;
    api.creators
      .getProfile(creatorId)
      .then((profile) => {
        if (!cancelled && profile) setResolvedHandle(profile.username);
      })
      .catch(() => {
        // Best-effort — the banner still reads fine without a resolved handle.
      });
    return () => {
      cancelled = true;
    };
  }, [creatorId, ig]);

  const handle = ig ?? resolvedHandle;

  return (
    <div className="mb-4 flex items-start justify-between gap-3 rounded-lg border border-primary/20 bg-primary/5 p-3 text-sm">
      <p className="text-foreground">
        Creating this campaign for {handle ? `@${handle}` : 'this creator'} — they&apos;ll be invited
        when you publish an Open or Direct campaign.{' '}
        {/* Q6.4 — Hype is always one of the tiles below and cannot invite a specific creator (see
            the honest banner on that page instead); this promise must not read as unconditional
            when the very next click could be the one tile that can't keep it. */}
        <span className="text-muted-foreground">Hype campaigns can&apos;t invite a specific creator.</span>
      </p>
      <button
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss"
        className="shrink-0 rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
      >
        <X className="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
  );
}

export default function BrandNewCampaignPage() {
  const navigate = useNavigate();
  const [selectedType, setSelectedType] = React.useState<CampaignType | null>(null);
  const creatorId = useCreatorIdParam();
  const igHandle = useIgParam();
  const [creatorBannerDismissed, setCreatorBannerDismissed] = React.useState(false);

  // BR-14 Phase 1 — template picker dialog + client-side prefill.
  const [templatesOpen, setTemplatesOpen] = React.useState(false);
  const [templates, setTemplates] = React.useState<CampaignTemplateResponse[]>([]);
  const [templatesLoading, setTemplatesLoading] = React.useState(false);
  const [templatesError, setTemplatesError] = React.useState<string | null>(null);
  const [applyingTemplateId, setApplyingTemplateId] = React.useState<string | null>(null);
  // Delete a CUSTOM template (F: DELETE /campaign-templates/:id).
  const [deletingTemplateId, setDeletingTemplateId] = React.useState<string | null>(null);

  const handleDeleteTemplate = async (t: CampaignTemplateResponse) => {
    setDeletingTemplateId(t.id);
    setTemplateApplyError(null);
    try {
      await api.campaignTemplates.remove(t.id);
      setTemplates((prev) => prev.filter((x) => x.id !== t.id));
    } catch (err) {
      setTemplateApplyError(err instanceof ApiError ? err.message : `Could not delete "${t.name}".`);
    } finally {
      setDeletingTemplateId(null);
    }
  };
  const [templateApplyError, setTemplateApplyError] = React.useState<string | null>(null);
  const [templateInitialValues, setTemplateInitialValues] = React.useState<Partial<CampaignFormData> | null>(
    null,
  );

  React.useEffect(() => {
    if (!templatesOpen || templates.length > 0 || templatesLoading) return;
    setTemplatesLoading(true);
    setTemplatesError(null);
    api.campaignTemplates
      .list()
      .then(setTemplates)
      .catch((err) => {
        console.error('Failed to load campaign templates', err);
        setTemplatesError(err instanceof ApiError ? err.message : 'Could not load templates.');
      })
      .finally(() => setTemplatesLoading(false));
  }, [templatesOpen, templates.length, templatesLoading]);

  const choose = (type: CampaignType) => {
    if (type === 'HYPE') {
      // Q6.4 — this used to drop `?creatorId=` (and `?ig=`) on the floor, so the "they'll be
      // invited when you publish" banner shown above (creatorId && !creatorBannerDismissed) made
      // a promise the Hype flow then silently broke. Forward the handoff through so the Hype page
      // can act on it — see brand-new-hype-campaign.tsx, which shows its own honest banner rather
      // than a promise Hype's flat-rate/slots model can't fulfil the same way Open/Direct can.
      const hypeParams = new URLSearchParams();
      if (creatorId) hypeParams.set('creatorId', creatorId);
      if (igHandle) hypeParams.set('ig', igHandle);
      const query = hypeParams.toString();
      navigate(query ? `/brand/campaigns/new/hype?${query}` : '/brand/campaigns/new/hype');
      return;
    }
    setSelectedType(type);
  };

  const applyTemplate = async (templateId: string) => {
    setApplyingTemplateId(templateId);
    setTemplateApplyError(null);
    try {
      const full = await api.campaignTemplates.get(templateId);
      setTemplateInitialValues(templateToInitialValues(full));
      setTemplatesOpen(false);
    } catch (err) {
      console.error('Failed to load campaign template', err);
      setTemplateApplyError(err instanceof ApiError ? err.message : 'Could not load this template. Try again.');
    } finally {
      setApplyingTemplateId(null);
    }
  };

  if (selectedType || templateInitialValues) {
    // F-0240: the type the brand picked on this page (Open/Direct) must reach CampaignForm, or a
    // Direct Deal silently gets created as a public OPEN campaign — see CampaignFormData.campaignType
    // and the CAMPAIGN_TYPE_TO_API translation table in src/lib/api.ts. Template-applied campaigns
    // never went through `choose()`, so selectedType is null in that branch and the template's own
    // values (if any) are used as-is.
    // CampaignForm owns its own full-width layout (no max-width wrapper here — it isn't a
    // narrow-column page like the type picker below it) and reads `?creatorId=` off this same
    // URL itself (useSearchParams — no navigate() happens between this screen and the wizard),
    // rendering its own banner + performing the post-create invite. See campaign-form.tsx.
    return (
      <CampaignForm
        initialValues={templateInitialValues ?? (selectedType ? { campaignType: selectedType } : undefined)}
      />
    );
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">New campaign</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          How do you want to work with creators?
        </p>
      </div>

      {creatorId && !creatorBannerDismissed && (
        <CreatorHandoffBanner
          creatorId={creatorId}
          ig={igHandle}
          onDismiss={() => setCreatorBannerDismissed(true)}
        />
      )}

      {/* B-5: optional, dismissible KYC prompt (never blocks campaign creation). */}
      <BrandKycPrompt />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {TYPE_OPTIONS.map((option) => {
          const Icon = option.icon;
          return (
            <Card
              key={option.type}
              role="button"
              tabIndex={0}
              onClick={() => choose(option.type)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  choose(option.type);
                }
              }}
              className={cn(
                'group cursor-pointer transition-all hover:-translate-y-0.5 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring motion-reduce:hover:translate-y-0',
                option.hype && 'border-hype-border hover:hype-glow',
              )}
            >
              <CardContent className="flex h-full flex-col gap-3 p-5">
                <div
                  className={cn(
                    'flex h-10 w-10 items-center justify-center rounded-lg',
                    option.hype ? 'bg-hype text-hype-foreground' : 'bg-accent text-accent-foreground',
                  )}
                >
                  <Icon className="h-5 w-5" aria-hidden="true" />
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <h2 className="font-semibold">{option.title}</h2>
                    {option.hype && (
                      <Badge className="gap-1 border-hype-border bg-hype text-[10px] text-hype-foreground hover:bg-hype">
                        <Zap className="h-2.5 w-2.5" aria-hidden="true" /> New
                      </Badge>
                    )}
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground">{option.description}</p>
                </div>
                <div className="flex items-center justify-between">
                  {option.hype ? (
                    <HypeLiveIndicator hoursLeft={72} />
                  ) : (
                    <span />
                  )}
                  <ArrowRight
                    className="h-4 w-4 text-muted-foreground transition-transform group-hover:translate-x-0.5 motion-reduce:group-hover:translate-x-0"
                    aria-hidden="true"
                  />
                </div>
              </CardContent>
            </Card>
          );
        })}

        {/* BR-14 Phase 1 — 4th card: pick a preset template instead of starting blank.
            Zero backend for "apply" — this opens a picker, fetches the template, and prefills
            CampaignForm client-side. Free to every plan tier (read is not @RequiresPlan). */}
        <Card
          role="button"
          tabIndex={0}
          onClick={() => setTemplatesOpen(true)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              setTemplatesOpen(true);
            }
          }}
          className="group cursor-pointer transition-all hover:-translate-y-0.5 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring motion-reduce:hover:translate-y-0"
        >
          <CardContent className="flex h-full flex-col gap-3 p-5">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-accent text-accent-foreground">
              <LayoutTemplate className="h-5 w-5" aria-hidden="true" />
            </div>
            <div className="flex-1">
              <h2 className="font-semibold">Start from a template</h2>
              <p className="mt-1 text-sm text-muted-foreground">
                Pick one of Influora&apos;s preset campaign templates and prefill the wizard.
              </p>
            </div>
            <div className="flex items-center justify-between">
              <span />
              <ArrowRight
                className="h-4 w-4 text-muted-foreground transition-transform group-hover:translate-x-0.5 motion-reduce:group-hover:translate-x-0"
                aria-hidden="true"
              />
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Template picker dialog */}
      <Dialog open={templatesOpen} onOpenChange={setTemplatesOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Choose a template</DialogTitle>
            <DialogDescription>
              Start from a preset and adjust anything before you publish.
            </DialogDescription>
          </DialogHeader>
          <div className="max-h-[50vh] space-y-2 overflow-y-auto py-2">
            {templatesLoading && (
              <p className="text-sm text-muted-foreground">Loading templates…</p>
            )}
            {!templatesLoading && templatesError && (
              <p className="text-sm text-destructive-foreground">{templatesError}</p>
            )}
            {!templatesLoading && !templatesError && templates.length === 0 && (
              <p className="text-sm text-muted-foreground">No templates available yet.</p>
            )}
            {!templatesLoading &&
              !templatesError &&
              templates.map((t) => (
                <div key={t.id} className="flex items-stretch gap-1">
                  <button
                    type="button"
                    disabled={applyingTemplateId !== null || deletingTemplateId !== null}
                    onClick={() => void applyTemplate(t.id)}
                    className="flex flex-1 flex-col gap-1 rounded-lg border p-3 text-left transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium">{t.name}</span>
                      {applyingTemplateId === t.id ? (
                        <Loader2 className="h-4 w-4 shrink-0 animate-spin text-muted-foreground" aria-hidden="true" />
                      ) : (
                        t.scope === 'SYSTEM' && (
                          <Badge variant="outline" className="shrink-0 text-[10px]">
                            Preset
                          </Badge>
                        )
                      )}
                    </div>
                    {t.description && (
                      <p className="text-sm text-muted-foreground">{t.description}</p>
                    )}
                  </button>
                  {t.scope === 'CUSTOM' && (
                    <button
                      type="button"
                      aria-label={`Delete template ${t.name}`}
                      disabled={deletingTemplateId !== null || applyingTemplateId !== null}
                      onClick={() => void handleDeleteTemplate(t)}
                      className="flex w-10 shrink-0 items-center justify-center rounded-lg border text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive-foreground disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      {deletingTemplateId === t.id ? (
                        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                      ) : (
                        <Trash2 className="h-4 w-4" aria-hidden="true" />
                      )}
                    </button>
                  )}
                </div>
              ))}
          </div>
          {templateApplyError && (
            <p className="text-xs text-destructive-foreground">{templateApplyError}</p>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
