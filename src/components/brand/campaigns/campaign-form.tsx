import * as React from 'react';
import { useNavigate, useSearchParams, Link } from 'react-router-dom';
import {
  ArrowLeft,
  ArrowRight,
  Save,
  Loader2,
  Plus,
  X,
  Calendar,
  ImageIcon,
  Video,
  FileText,
  CheckCircle2,
  Info,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import type { Platform, ContentType, CampaignStatus, TargetAudience } from '@/lib/types';
import { useCampaignStore } from '@/lib/store';
import { api, ApiError, type CreatorSuggestionItem } from '@/lib/api';
import { isWorkspaceNotVerified } from '@/lib/api-errors';
import { validateCampaignTitle } from '@/lib/campaign-validation';
import { useToast } from '@/hooks/use-toast';
import { useWorkspaceVerification } from '@/hooks/brand/useWorkspaceVerification';
import { VerificationRequiredBox } from '@/components/brand/VerificationRequiredBox';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Switch } from '@/components/ui/switch';
import { Slider } from '@/components/ui/slider';
import { Calendar as CalendarComponent } from '@/components/ui/calendar';
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';
import { Separator } from '@/components/ui/separator';
import { format } from 'date-fns';

const platformOptions: { value: Platform; label: string; icon: string }[] = [
  { value: 'INSTAGRAM', label: 'Instagram', icon: 'IG' },
  { value: 'YOUTUBE', label: 'YouTube', icon: 'YT' },
  { value: 'TIKTOK', label: 'TikTok', icon: 'TK' },
  { value: 'TWITTER', label: 'X/Twitter', icon: 'X' },
  { value: 'LINKEDIN', label: 'LinkedIn', icon: 'LI' },
  { value: 'FACEBOOK', label: 'Facebook', icon: 'FB' },
  { value: 'TWITCH', label: 'Twitch', icon: 'TW' },
];

const contentTypeOptions: { value: ContentType; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
  { value: 'POST', label: 'Static Post', icon: ImageIcon },
  { value: 'STORY', label: 'Story', icon: ImageIcon },
  { value: 'REEL', label: 'Reel/Short', icon: Video },
  { value: 'VIDEO', label: 'Long Video', icon: Video },
  { value: 'LIVE_STREAM', label: 'Live Stream', icon: Video },
  { value: 'ARTICLE', label: 'Blog/Article', icon: FileText },
  { value: 'PODCAST', label: 'Podcast', icon: FileText },
];

export interface CampaignFormData {
  title: string;
  description: string;
  objectives: string[];
  platforms: Platform[];
  contentTypes: ContentType[];
  budgetMin: number;
  budgetMax: number;
  currency: string;
  startDate: Date | undefined;
  endDate: Date | undefined;
  maxCollaborators: number;
  requirements: string[];
  hashtags: string[];
  brandGuidelines: string;
  isPrivate: boolean;
  targetAudience: TargetAudience;
}

const initialFormData: CampaignFormData = {
  title: '',
  description: '',
  objectives: [],
  platforms: [],
  contentTypes: [],
  budgetMin: 5000,
  budgetMax: 25000,
  currency: 'INR',
  startDate: undefined,
  endDate: undefined,
  maxCollaborators: 10,
  requirements: [],
  hashtags: [],
  brandGuidelines: '',
  isPrivate: false,
  targetAudience: { interests: [] },
};

/**
 * D-14 — flattens the structured `TargetAudience` object into the free-text summary
 * `POST /creators/suggestions` expects (`CreatorSuggestionRequest.targetAudience: String`,
 * consumed by `CreatorDiscoveryService.inferCategories`). Empty when nothing is set.
 */
function describeTargetAudience(ta: TargetAudience): string {
  const parts: string[] = [];
  if (ta.ageRange) parts.push(`ages ${ta.ageRange.min}-${ta.ageRange.max}`);
  if (ta.genders?.length) parts.push(ta.genders.join('/'));
  if (ta.locations?.length) parts.push(ta.locations.join(', '));
  if (ta.interests?.length) parts.push(`interested in ${ta.interests.join(', ')}`);
  return parts.join('; ');
}

const objectiveOptions = [
  'Brand Awareness',
  'Drive Sales',
  'Product Launch',
  'Engagement Growth',
  'Lead Generation',
  'App Downloads',
  'Event Promotion',
  'User-Generated Content',
];

type Step = 'basics' | 'content' | 'budget' | 'requirements' | 'review';

const steps: { id: Step; label: string; description: string }[] = [
  { id: 'basics', label: 'Basics', description: 'Campaign details' },
  { id: 'content', label: 'Content', description: 'Platforms & formats' },
  { id: 'budget', label: 'Budget', description: 'Timeline & budget' },
  { id: 'requirements', label: 'Requirements', description: 'Creator criteria' },
  { id: 'review', label: 'Review', description: 'Final review' },
];

// Slider covers [1000, 500000] in steps of 1000 (see the budget Slider below) —
// clamp + round any hint-derived value into that same grid so it never lands
// off the track.
function clampToBudgetStep(n: number): number {
  const stepped = Math.round(n / 1000) * 1000;
  return Math.min(500000, Math.max(1000, stepped));
}

export function CampaignForm({
  campaignId,
  initialValues,
}: {
  campaignId?: string;
  /**
   * BR-14 Phase 1 — client-side prefill from a campaign template (`api.campaignTemplates.get`).
   * There is no apply-template REST route: the brand still walks Basics → Review like any other
   * campaign, just starting from the template's saved values instead of blank defaults. Ignored
   * when `campaignId` is set — an edit's fetched values always win over a stale template seed.
   */
  initialValues?: Partial<CampaignFormData>;
}) {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { addCampaign } = useCampaignStore();
  const [searchParams] = useSearchParams();
  const [currentStep, setCurrentStep] = React.useState<Step>('basics');
  const [formData, setFormData] = React.useState<CampaignFormData>(() =>
    initialValues ? { ...initialFormData, ...initialValues } : initialFormData,
  );
  const [errors, setErrors] = React.useState<Record<string, string>>({});

  // Meera completion-flow hints (Ash §7.2): a create_campaign draft can deep-link
  // here with `budgetHint`/`start`/`end`. Dates are safe to auto-fill — the human
  // still walks through Continue/Publish. Budget is NOT: a hint must never
  // masquerade as a human-confirmed number, so we seed the slider with it but
  // gate the budget step on an explicit touch/confirm (see budgetConfirmed below).
  const budgetHint = React.useMemo(() => {
    const raw = searchParams.get('budgetHint');
    const n = raw ? Number(raw) : NaN;
    return Number.isFinite(n) && n > 0 ? n : undefined;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const dateHints = React.useMemo(() => {
    const parse = (key: string) => {
      const raw = searchParams.get(key);
      if (!raw) return undefined;
      const d = new Date(raw);
      return Number.isNaN(d.getTime()) ? undefined : d;
    };
    return { start: parse('start'), end: parse('end') };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const [budgetConfirmed, setBudgetConfirmed] = React.useState(!budgetHint);
  const confirmBudgetTouch = () => setBudgetConfirmed(true);
  const [isSubmitting, setIsSubmitting] = React.useState(false);
  // Set when a publish (ACTIVE) is refused because the workspace isn't verified — renders the
  // persistent inline box instead of a disappearing toast. Cleared on the next submit attempt.
  const [verificationBlocked, setVerificationBlocked] = React.useState(false);
  const { canVerify } = useWorkspaceVerification();
  const [newRequirement, setNewRequirement] = React.useState('');
  const [newHashtag, setNewHashtag] = React.useState('');
  const [newInterest, setNewInterest] = React.useState('');

  // D-14 — POST /creators/suggestions (CreatorController.suggestions), a backend-complete
  // endpoint with no prior FE consumer (wiki/errors/BRAND-BUG-TRACKER.md). The review step is the
  // natural slot: it's the first point every field the request body needs (objectives,
  // targetAudience, budget, platforms) is fully collected. Fetches once the brand reaches Review;
  // best-effort — a failed/empty fetch just hides the card, since this is a secondary aid, not
  // part of campaign submission. `api.creators.suggestions` itself no-ops to `[]` in mock mode.
  const [suggestedCreators, setSuggestedCreators] = React.useState<CreatorSuggestionItem[]>([]);
  const [suggestionsLoading, setSuggestionsLoading] = React.useState(false);
  React.useEffect(() => {
    if (currentStep !== 'review') return;
    let cancelled = false;
    setSuggestionsLoading(true);
    api.creators
      .suggestions({
        campaignGoals: formData.objectives.join(', ') || undefined,
        targetAudience: describeTargetAudience(formData.targetAudience) || undefined,
        budget: formData.budgetMax || undefined,
        platforms: formData.platforms.length ? formData.platforms : undefined,
      })
      .then(({ suggestions }) => {
        if (!cancelled) setSuggestedCreators(suggestions);
      })
      .catch(() => {
        if (!cancelled) setSuggestedCreators([]);
      })
      .finally(() => {
        if (!cancelled) setSuggestionsLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentStep]);
  // Defensive hardening only: keeps the Start/End date Popovers mutually exclusive so their
  // portals can never overlap. Bindings below are unchanged (start→startDate, end→endDate).
  const [startDateOpen, setStartDateOpen] = React.useState(false);
  const [endDateOpen, setEndDateOpen] = React.useState(false);

  const isEditing = !!campaignId;
  const currentStepIndex = steps.findIndex((s) => s.id === currentStep);

  // When editing, we must fetch the real campaign and prefill before the form is
  // submittable — otherwise a save (PATCH) would overwrite the record with empty
  // defaults. `isLoading` gates the render until the fetch resolves.
  const [isLoading, setIsLoading] = React.useState<boolean>(!!campaignId);
  const [loadError, setLoadError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!campaignId) return;
    let cancelled = false;
    setIsLoading(true);
    setLoadError(null);
    (async () => {
      try {
        const c = await api.campaigns.get(campaignId);
        if (cancelled) return;
        // Mock/non-live mode resolves to null: leave a blank form, allow editing.
        if (c) {
          setFormData({
            title: c.title,
            description: c.description ?? '',
            objectives: c.objectives ?? [],
            platforms: c.platforms,
            contentTypes: c.contentTypes,
            // Meera-created drafts have no budget yet — fall back to the same
            // defaults a brand-new campaign starts with, so the wizard opens
            // editable instead of crashing on `undefined.min`.
            budgetMin: c.budget?.min ?? initialFormData.budgetMin,
            budgetMax: c.budget?.max ?? initialFormData.budgetMax,
            currency: c.budget?.currency ?? initialFormData.currency,
            startDate: c.timeline.startDate ? new Date(c.timeline.startDate) : undefined,
            endDate: c.timeline.endDate ? new Date(c.timeline.endDate) : undefined,
            maxCollaborators: c.maxCollaborators ?? initialFormData.maxCollaborators,
            requirements: c.requirements ?? [],
            hashtags: c.hashtags ?? [],
            brandGuidelines: c.brandGuidelines ?? '',
            isPrivate: c.isPrivate,
            targetAudience: c.targetAudience ?? { interests: [] },
          });
        }
        setIsLoading(false);
      } catch (e) {
        if (cancelled) return;
        setLoadError(e instanceof ApiError ? e.message : 'Could not load this campaign.');
        setIsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [campaignId]);

  // Seed the budget/date hints once the (possible) campaign fetch above has
  // settled, so we never clobber a real loaded value with the query-param hint.
  // Dates fill in directly (advisory, safe per Ash §7.2). Budget is seeded onto
  // the slider so the human sees it, but `budgetConfirmed` stays false until
  // they actively touch it — see validateStep('budget') below.
  const hintsAppliedRef = React.useRef(false);
  React.useEffect(() => {
    if (isLoading) return;
    if (hintsAppliedRef.current) return;
    hintsAppliedRef.current = true;
    if (!budgetHint && !dateHints.start && !dateHints.end) return;
    setFormData((prev) => ({
      ...prev,
      ...(budgetHint
        ? {
            budgetMin: clampToBudgetStep(budgetHint * 0.85),
            budgetMax: clampToBudgetStep(budgetHint * 1.15),
          }
        : {}),
      ...(dateHints.start && !prev.startDate ? { startDate: dateHints.start } : {}),
      ...(dateHints.end && !prev.endDate ? { endDate: dateHints.end } : {}),
    }));
  }, [isLoading, budgetHint, dateHints]);

  const updateFormData = (updates: Partial<CampaignFormData>) => {
    setFormData((prev) => ({ ...prev, ...updates }));
    // Clear related errors
    Object.keys(updates).forEach((key) => {
      setErrors((prev) => {
        const newErrors = { ...prev };
        delete newErrors[key];
        return newErrors;
      });
    });
  };

  const toggleArrayItem = <T,>(array: T[], item: T): T[] => {
    return array.includes(item)
      ? array.filter((i) => i !== item)
      : [...array, item];
  };

  // Toggle a value in one of the multi-select array fields. Uses the functional
  // setState form so several toggles landing in the same render batch can't clobber
  // each other — the previous inline `updateFormData({ field: toggleArrayItem(formData.field, x) })`
  // read a stale `formData` snapshot, dropping all-but-the-last selection when
  // toggles were batched (e.g. picking Reel + Story in quick succession).
  const toggleArrayField = (
    field: 'objectives' | 'platforms' | 'contentTypes',
    item: string,
  ) => {
    setFormData(
      (prev) =>
        ({
          ...prev,
          [field]: toggleArrayItem(prev[field] as string[], item),
        }) as CampaignFormData,
    );
    setErrors((prev) => {
      if (!prev[field]) return prev;
      const next = { ...prev };
      delete next[field];
      return next;
    });
  };

  const validateStep = (step: Step): boolean => {
    const newErrors: Record<string, string> = {};

    switch (step) {
      case 'basics': {
        const titleError = validateCampaignTitle(formData.title);
        if (titleError) {
          newErrors.title = titleError;
        }
        if (!formData.description.trim()) {
          newErrors.description = 'Description is required';
        }
        if (formData.objectives.length === 0) {
          newErrors.objectives = 'Select at least one objective';
        }
        break;
      }

      case 'content':
        if (formData.platforms.length === 0) {
          newErrors.platforms = 'Select at least one platform';
        }
        if (formData.contentTypes.length === 0) {
          newErrors.contentTypes = 'Select at least one content type';
        }
        break;

      case 'budget':
        if (!formData.startDate) {
          newErrors.startDate = 'Start date is required';
        }
        if (!formData.endDate) {
          newErrors.endDate = 'End date is required';
        }
        if (formData.startDate && formData.endDate && formData.startDate >= formData.endDate) {
          newErrors.endDate = 'End date must be after start date';
        }
        if (formData.budgetMin > formData.budgetMax) {
          newErrors.budget = 'Minimum budget cannot exceed maximum';
        }
        // Masquerade guard (Ash §7.2): a Meera budgetHint pre-loads the slider so
        // the human sees a starting point, but it must never silently pass as a
        // confirmed number. Block Continue/Publish until they've actively touched
        // the slider (or hit "Use this") — see confirmBudgetTouch.
        if (budgetHint && !budgetConfirmed) {
          newErrors.budget = "Confirm your budget before continuing — Meera's number is just a starting point.";
        }
        break;
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleNext = () => {
    if (!validateStep(currentStep)) return;

    const nextIndex = currentStepIndex + 1;
    if (nextIndex < steps.length) {
      setCurrentStep(steps[nextIndex].id);
    }
  };

  const handleBack = () => {
    const prevIndex = currentStepIndex - 1;
    if (prevIndex >= 0) {
      setCurrentStep(steps[prevIndex].id);
    }
  };

  const handleSubmit = async (status: CampaignStatus = 'DRAFT') => {
    if (!validateStep(currentStep)) return;

    setIsSubmitting(true);
    setErrors({});
    setVerificationBlocked(false);

    const payload = {
      title: formData.title.trim(),
      description: formData.description,
      objectives: formData.objectives,
      status,
      budget: {
        min: formData.budgetMin,
        max: formData.budgetMax,
        currency: formData.currency,
      },
      timeline: {
        startDate: formData.startDate!,
        endDate: formData.endDate!,
      },
      platforms: formData.platforms,
      contentTypes: formData.contentTypes,
      requirements: formData.requirements,
      hashtags: formData.hashtags,
      brandGuidelines: formData.brandGuidelines,
      isPrivate: formData.isPrivate,
      maxCollaborators: formData.maxCollaborators,
      targetAudience: formData.targetAudience,
    };

    try {
      const saved = isEditing && campaignId
        ? await api.campaigns.update(campaignId, payload)
        : await api.campaigns.create(payload);

      addCampaign(saved);
      navigate('/brand/campaigns');
    } catch (err) {
      // Publishing from an unverified workspace is handled inline (persistent box), not as a
      // toast — it's a workspace-state gate the user needs to keep reading, and their work is
      // safe as a draft. Every other failure stays a toast.
      if (isWorkspaceNotVerified(err)) {
        setVerificationBlocked(true);
        return;
      }
      const message =
        err instanceof ApiError ? err.message : 'Failed to save campaign';
      toast({
        title: status === 'ACTIVE' ? 'Could not publish campaign' : 'Could not save draft',
        description: message,
        variant: 'destructive',
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  const addRequirement = () => {
    if (newRequirement.trim()) {
      updateFormData({
        requirements: [...formData.requirements, newRequirement.trim()],
      });
      setNewRequirement('');
    }
  };

  const removeRequirement = (index: number) => {
    updateFormData({
      requirements: formData.requirements.filter((_, i) => i !== index),
    });
  };

  const addHashtag = () => {
    if (newHashtag.trim()) {
      const tag = newHashtag.startsWith('#') ? newHashtag : `#${newHashtag}`;
      if (!formData.hashtags.includes(tag)) {
        updateFormData({
          hashtags: [...formData.hashtags, tag.trim()],
        });
      }
      setNewHashtag('');
    }
  };

  const removeHashtag = (index: number) => {
    updateFormData({
      hashtags: formData.hashtags.filter((_, i) => i !== index),
    });
  };

  const addInterest = () => {
    const interests = formData.targetAudience.interests ?? [];
    if (newInterest.trim()) {
      if (!interests.includes(newInterest.trim())) {
        updateFormData({
          targetAudience: { ...formData.targetAudience, interests: [...interests, newInterest.trim()] },
        });
      }
      setNewInterest('');
    }
  };

  const removeInterest = (index: number) => {
    const interests = formData.targetAudience.interests ?? [];
    updateFormData({
      targetAudience: { ...formData.targetAudience, interests: interests.filter((_, i) => i !== index) },
    });
  };

  // While an edit fetch is in flight (or failed), never show a submittable form —
  // that is exactly what let a save overwrite the campaign with empty defaults.
  if (isEditing && (isLoading || loadError)) {
    return (
      <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4 p-6 text-center">
        {loadError ? (
          <>
            <p className="text-sm font-medium text-destructive-foreground">Could not load this campaign</p>
            <p className="text-sm text-muted-foreground">{loadError}</p>
            <Button variant="outline" asChild>
              <Link to="/brand/campaigns">
                <ArrowLeft className="mr-2 h-4 w-4" />
                Back to campaigns
              </Link>
            </Button>
          </>
        ) : (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading campaign…
          </div>
        )}
      </div>
    );
  }

  return (
    <TooltipProvider>
      <div className="flex flex-col gap-6 p-4 lg:p-6">
        {/* Header */}
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" asChild>
            <Link to="/brand/campaigns">
              <ArrowLeft className="h-4 w-4" />
            </Link>
          </Button>
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">
              {isEditing ? 'Edit Campaign' : 'Create Campaign'}
            </h1>
            <p className="text-muted-foreground">
              {isEditing
                ? 'Update your campaign details'
                : 'Set up a new influencer marketing campaign'}
            </p>
          </div>
        </div>

        <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
          {/* Steps Navigation */}
          <Card className="w-full lg:sticky lg:top-6 lg:w-64 lg:shrink-0">
            <CardContent className="p-4">
              <nav className="flex flex-row gap-2 overflow-x-auto lg:flex-col lg:gap-1">
                {steps.map((step, index) => {
                  const isActive = currentStep === step.id;
                  const isCompleted = index < currentStepIndex;

                  return (
                    <button
                      key={step.id}
                      onClick={() => {
                        if (isCompleted || index === currentStepIndex) {
                          setCurrentStep(step.id);
                        }
                      }}
                      className={cn(
                        'flex items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors lg:w-full',
                        isActive && 'bg-primary text-primary-foreground',
                        isCompleted && !isActive && 'text-muted-foreground hover:bg-muted',
                        !isActive && !isCompleted && 'cursor-not-allowed opacity-50'
                      )}
                      disabled={!isCompleted && index !== currentStepIndex}
                    >
                      <div
                        className={cn(
                          'flex h-7 w-7 shrink-0 items-center justify-center rounded-full border-2 text-xs font-medium',
                          isActive && 'border-primary-foreground',
                          isCompleted && !isActive && 'border-primary bg-primary text-primary-foreground',
                          !isActive && !isCompleted && 'border-muted-foreground/50'
                        )}
                      >
                        {isCompleted && !isActive ? (
                          <CheckCircle2 className="h-4 w-4" />
                        ) : (
                          index + 1
                        )}
                      </div>
                      <div className="hidden lg:block">
                        <p className="text-sm font-medium">{step.label}</p>
                        <p className={cn(
                          'text-xs',
                          isActive ? 'text-primary-foreground/80' : 'text-muted-foreground'
                        )}>
                          {step.description}
                        </p>
                      </div>
                    </button>
                  );
                })}
              </nav>
            </CardContent>
          </Card>

          {/* Form Content */}
          <Card className="flex-1">
            <CardContent className="p-6">
              {/* Step 1: Basics */}
              {currentStep === 'basics' && (
                <div className="space-y-6">
                  <div>
                    <h2 className="text-lg font-semibold">Campaign Basics</h2>
                    <p className="text-sm text-muted-foreground">
                      Start with the fundamental details of your campaign
                    </p>
                  </div>

                  <div className="space-y-4">
                    <div className="space-y-2">
                      <Label htmlFor="title">Campaign Title</Label>
                      <Input
                        id="title"
                        placeholder="e.g., Summer Collection Launch 2024"
                        value={formData.title}
                        onChange={(e) => updateFormData({ title: e.target.value })}
                        className={cn(errors.title && 'border-destructive-foreground')}
                      />
                      {errors.title && (
                        <p className="text-xs text-destructive-foreground">{errors.title}</p>
                      )}
                    </div>

                    <div className="space-y-2">
                      <Label htmlFor="description">Description</Label>
                      <Textarea
                        id="description"
                        placeholder="Describe your campaign goals, target audience, and what you're looking for from creators..."
                        value={formData.description}
                        onChange={(e) => updateFormData({ description: e.target.value })}
                        rows={4}
                        className={cn(errors.description && 'border-destructive-foreground')}
                      />
                      {errors.description && (
                        <p className="text-xs text-destructive-foreground">{errors.description}</p>
                      )}
                    </div>

                    <div className="space-y-2">
                      <div className="flex items-center gap-2">
                        <Label>Campaign Objectives</Label>
                        <Tooltip>
                          <TooltipTrigger>
                            <Info className="h-4 w-4 text-muted-foreground" />
                          </TooltipTrigger>
                          <TooltipContent>
                            <p>Select the main goals for this campaign</p>
                          </TooltipContent>
                        </Tooltip>
                      </div>
                      <div className="flex flex-wrap gap-2">
                        {objectiveOptions.map((objective) => (
                          <Badge
                            key={objective}
                            variant={
                              formData.objectives.includes(objective)
                                ? 'default'
                                : 'outline'
                            }
                            className="cursor-pointer"
                            onClick={() => toggleArrayField('objectives', objective)}
                          >
                            {objective}
                          </Badge>
                        ))}
                      </div>
                      {errors.objectives && (
                        <p className="text-xs text-destructive-foreground">{errors.objectives}</p>
                      )}
                    </div>

                    <div className="flex items-center justify-between rounded-lg border border-border p-4">
                      <div className="space-y-0.5">
                        <Label htmlFor="isPrivate">Private Campaign</Label>
                        <p className="text-sm text-muted-foreground">
                          Only invited creators can see this campaign
                        </p>
                      </div>
                      <Switch
                        id="isPrivate"
                        checked={formData.isPrivate}
                        onCheckedChange={(checked) => updateFormData({ isPrivate: checked })}
                      />
                    </div>
                  </div>
                </div>
              )}

              {/* Step 2: Content */}
              {currentStep === 'content' && (
                <div className="space-y-6">
                  <div>
                    <h2 className="text-lg font-semibold">Platforms & Content</h2>
                    <p className="text-sm text-muted-foreground">
                      Choose where and what type of content you need
                    </p>
                  </div>

                  <div className="space-y-4">
                    <div className="space-y-2">
                      <Label>Target Platforms</Label>
                      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
                        {platformOptions.map((platform) => (
                          <div
                            key={platform.value}
                            onClick={() => toggleArrayField('platforms', platform.value)}
                            className={cn(
                              'flex cursor-pointer items-center gap-3 rounded-lg border-2 p-3 transition-colors',
                              formData.platforms.includes(platform.value)
                                ? 'border-primary bg-primary/5'
                                : 'border-border hover:border-primary/50'
                            )}
                          >
                            <div
                              className={cn(
                                'flex h-10 w-10 items-center justify-center rounded-lg text-sm font-bold',
                                formData.platforms.includes(platform.value)
                                  ? 'bg-primary text-primary-foreground'
                                  : 'bg-muted text-muted-foreground'
                              )}
                            >
                              {platform.icon}
                            </div>
                            <span className="text-sm font-medium">{platform.label}</span>
                          </div>
                        ))}
                      </div>
                      {errors.platforms && (
                        <p className="text-xs text-destructive-foreground">{errors.platforms}</p>
                      )}
                    </div>

                    <Separator />

                    <div className="space-y-2">
                      <Label>Content Types</Label>
                      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
                        {contentTypeOptions.map((content) => (
                          <div
                            key={content.value}
                            onClick={() => toggleArrayField('contentTypes', content.value)}
                            className={cn(
                              'flex cursor-pointer flex-col items-center gap-2 rounded-lg border-2 p-4 text-center transition-colors',
                              formData.contentTypes.includes(content.value)
                                ? 'border-primary bg-primary/5'
                                : 'border-border hover:border-primary/50'
                            )}
                          >
                            <content.icon
                              className={cn(
                                'h-6 w-6',
                                formData.contentTypes.includes(content.value)
                                  ? 'text-primary'
                                  : 'text-muted-foreground'
                              )}
                            />
                            <span className="text-sm font-medium">{content.label}</span>
                          </div>
                        ))}
                      </div>
                      {errors.contentTypes && (
                        <p className="text-xs text-destructive-foreground">{errors.contentTypes}</p>
                      )}
                    </div>
                  </div>
                </div>
              )}

              {/* Step 3: Budget */}
              {currentStep === 'budget' && (
                <div className="space-y-6">
                  <div>
                    <h2 className="text-lg font-semibold">Timeline & Budget</h2>
                    <p className="text-sm text-muted-foreground">
                      Set your campaign duration and budget range
                    </p>
                  </div>

                  <div className="space-y-6">
                    {(dateHints.start || dateHints.end) && (
                      <p className="text-xs text-muted-foreground">
                        Dates below are pre-filled from your Meera conversation — adjust if needed.
                      </p>
                    )}
                    <div className="grid gap-4 sm:grid-cols-2">
                      <div className="space-y-2">
                        <Label>Start Date</Label>
                        <Popover
                          open={startDateOpen}
                          onOpenChange={(open) => {
                            setStartDateOpen(open);
                            if (open) setEndDateOpen(false);
                          }}
                        >
                          <PopoverTrigger asChild>
                            <Button
                              variant="outline"
                              className={cn(
                                'w-full justify-start text-left font-normal',
                                !formData.startDate && 'text-muted-foreground',
                                errors.startDate && 'border-destructive-foreground'
                              )}
                            >
                              <Calendar className="mr-2 h-4 w-4" />
                              {formData.startDate
                                ? format(formData.startDate, 'PPP')
                                : 'Select date'}
                            </Button>
                          </PopoverTrigger>
                          <PopoverContent className="w-auto p-0" align="start">
                            <CalendarComponent
                              mode="single"
                              selected={formData.startDate}
                              onSelect={(date) => {
                                updateFormData({ startDate: date });
                                setStartDateOpen(false);
                              }}
                              disabled={(date) => date < new Date()}
                              initialFocus
                            />
                          </PopoverContent>
                        </Popover>
                        {errors.startDate && (
                          <p className="text-xs text-destructive-foreground">{errors.startDate}</p>
                        )}
                      </div>

                      <div className="space-y-2">
                        <Label>End Date</Label>
                        <Popover
                          open={endDateOpen}
                          onOpenChange={(open) => {
                            setEndDateOpen(open);
                            if (open) setStartDateOpen(false);
                          }}
                        >
                          <PopoverTrigger asChild>
                            <Button
                              variant="outline"
                              className={cn(
                                'w-full justify-start text-left font-normal',
                                !formData.endDate && 'text-muted-foreground',
                                errors.endDate && 'border-destructive-foreground'
                              )}
                            >
                              <Calendar className="mr-2 h-4 w-4" />
                              {formData.endDate
                                ? format(formData.endDate, 'PPP')
                                : 'Select date'}
                            </Button>
                          </PopoverTrigger>
                          <PopoverContent className="w-auto p-0" align="start">
                            <CalendarComponent
                              mode="single"
                              selected={formData.endDate}
                              onSelect={(date) => {
                                updateFormData({ endDate: date });
                                setEndDateOpen(false);
                              }}
                              disabled={(date) =>
                                date < (formData.startDate || new Date())
                              }
                              initialFocus
                            />
                          </PopoverContent>
                        </Popover>
                        {errors.endDate && (
                          <p className="text-xs text-destructive-foreground">{errors.endDate}</p>
                        )}
                      </div>
                    </div>

                    <Separator />

                    <div className="space-y-4">
                      <div className="flex items-center justify-between">
                        <Label>Budget Range</Label>
                        <div className="flex items-center gap-2">
                          <Select
                            value={formData.currency}
                            onValueChange={(value) => updateFormData({ currency: value })}
                          >
                            <SelectTrigger className="w-20">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="INR">INR</SelectItem>
                              <SelectItem value="USD">USD</SelectItem>
                              <SelectItem value="EUR">EUR</SelectItem>
                              <SelectItem value="GBP">GBP</SelectItem>
                            </SelectContent>
                          </Select>
                        </div>
                      </div>

                      {budgetHint && (
                        <div
                          className={cn(
                            'flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3 text-xs',
                            budgetConfirmed
                              ? 'border-border bg-muted/40 text-muted-foreground'
                              : 'border-primary/40 bg-primary/5 text-foreground'
                          )}
                        >
                          <span>
                            Meera suggested a pool of{' '}
                            <span className="font-semibold">₹{budgetHint.toLocaleString('en-IN')}</span> —
                            not confirmed yet. Drag the slider or use her number below.
                          </span>
                          {!budgetConfirmed && (
                            <Button
                              type="button"
                              size="sm"
                              variant="outline"
                              onClick={() => {
                                updateFormData({
                                  budgetMin: clampToBudgetStep(budgetHint * 0.85),
                                  budgetMax: clampToBudgetStep(budgetHint * 1.15),
                                });
                                confirmBudgetTouch();
                              }}
                            >
                              Use Meera&apos;s suggestion
                            </Button>
                          )}
                        </div>
                      )}

                      <div className="rounded-lg border border-border p-4">
                        <div className="flex items-center justify-between mb-4">
                          <span className="text-2xl font-bold">
                            {formData.currency === 'INR' ? '₹' : formData.currency === 'USD' ? '$' : formData.currency === 'EUR' ? '€' : '£'}
                            {formData.budgetMin.toLocaleString('en-IN')} –{' '}
                            {formData.currency === 'INR' ? '₹' : formData.currency === 'USD' ? '$' : formData.currency === 'EUR' ? '€' : '£'}
                            {formData.budgetMax.toLocaleString('en-IN')}
                          </span>
                          {budgetHint && !budgetConfirmed && (
                            <Badge variant="outline" className="text-[10px] text-muted-foreground">
                              Suggested — unconfirmed
                            </Badge>
                          )}
                        </div>
                        <Slider
                          min={1000}
                          max={500000}
                          step={1000}
                          value={[formData.budgetMin, formData.budgetMax]}
                          onValueChange={([min, max]) => {
                            updateFormData({ budgetMin: min, budgetMax: max });
                            confirmBudgetTouch();
                          }}
                          className="mb-2"
                        />
                        <div className="flex justify-between text-xs text-muted-foreground">
                          <span>₹1,000</span>
                          <span>₹5,00,000</span>
                        </div>
                      </div>
                      {errors.budget && (
                        <p className="text-xs text-destructive-foreground">{errors.budget}</p>
                      )}
                    </div>

                    <Separator />

                    <div className="space-y-2">
                      <div className="flex items-center justify-between">
                        <Label>Maximum Collaborators</Label>
                        <span className="text-sm font-medium">{formData.maxCollaborators}</span>
                      </div>
                      <Slider
                        min={1}
                        max={50}
                        step={1}
                        value={[formData.maxCollaborators]}
                        onValueChange={([value]) =>
                          updateFormData({ maxCollaborators: value })
                        }
                      />
                      <p className="text-xs text-muted-foreground">
                        Number of creators you want to work with
                      </p>
                    </div>
                  </div>
                </div>
              )}

              {/* Step 4: Requirements */}
              {currentStep === 'requirements' && (
                <div className="space-y-6">
                  <div>
                    <h2 className="text-lg font-semibold">Creator Requirements</h2>
                    <p className="text-sm text-muted-foreground">
                      Define what you need from creators
                    </p>
                  </div>

                  <div className="space-y-6">
                    <div className="space-y-2">
                      <Label>Content Requirements</Label>
                      <div className="flex gap-2">
                        <Input
                          placeholder="Add a requirement (e.g., Must mention brand name)"
                          value={newRequirement}
                          onChange={(e) => setNewRequirement(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                              e.preventDefault();
                              addRequirement();
                            }
                          }}
                        />
                        <Button type="button" onClick={addRequirement} variant="secondary">
                          <Plus className="h-4 w-4" />
                        </Button>
                      </div>
                      {formData.requirements.length > 0 && (
                        <div className="space-y-2 mt-3">
                          {formData.requirements.map((req, index) => (
                            <div
                              key={`${req}-${index}`}
                              className="flex items-center justify-between rounded-lg border border-border p-3"
                            >
                              <span className="text-sm">{req}</span>
                              <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                className="h-7 w-7"
                                onClick={() => removeRequirement(index)}
                              >
                                <X className="h-4 w-4" />
                              </Button>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>

                    <Separator />

                    <div className="space-y-2">
                      <Label>Campaign Hashtags</Label>
                      <div className="flex gap-2">
                        <Input
                          placeholder="Add hashtag (e.g., #SummerVibes)"
                          value={newHashtag}
                          onChange={(e) => setNewHashtag(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                              e.preventDefault();
                              addHashtag();
                            }
                          }}
                        />
                        <Button type="button" onClick={addHashtag} variant="secondary">
                          <Plus className="h-4 w-4" />
                        </Button>
                      </div>
                      {formData.hashtags.length > 0 && (
                        <div className="flex flex-wrap gap-2 mt-3">
                          {formData.hashtags.map((tag, index) => (
                            <Badge
                              key={`${tag}-${index}`}
                              variant="secondary"
                              className="gap-1 pl-3 pr-1"
                            >
                              {tag}
                              <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                className="h-4 w-4 p-0 hover:bg-transparent"
                                onClick={() => removeHashtag(index)}
                              >
                                <X className="h-3 w-3" />
                              </Button>
                            </Badge>
                          ))}
                        </div>
                      )}
                    </div>

                    <Separator />

                    <div className="space-y-2">
                      <Label>Target Audience</Label>
                      <p className="text-xs text-muted-foreground">
                        Who is this campaign for? (e.g. skincare enthusiasts, new parents)
                      </p>
                      <div className="flex gap-2">
                        <Input
                          placeholder="Add an interest (e.g., skincare enthusiasts)"
                          value={newInterest}
                          onChange={(e) => setNewInterest(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                              e.preventDefault();
                              addInterest();
                            }
                          }}
                        />
                        <Button type="button" onClick={addInterest} variant="secondary">
                          <Plus className="h-4 w-4" />
                        </Button>
                      </div>
                      {(formData.targetAudience.interests?.length ?? 0) > 0 && (
                        <div className="flex flex-wrap gap-2 mt-3">
                          {formData.targetAudience.interests!.map((interest, index) => (
                            <Badge
                              key={`${interest}-${index}`}
                              variant="secondary"
                              className="gap-1 pl-3 pr-1"
                            >
                              {interest}
                              <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                className="h-4 w-4 p-0 hover:bg-transparent"
                                onClick={() => removeInterest(index)}
                              >
                                <X className="h-3 w-3" />
                              </Button>
                            </Badge>
                          ))}
                        </div>
                      )}
                    </div>

                    <Separator />

                    <div className="space-y-2">
                      <Label htmlFor="brandGuidelines">Brand Guidelines</Label>
                      <Textarea
                        id="brandGuidelines"
                        placeholder="Share any specific brand guidelines, dos and don'ts, tone of voice requirements..."
                        value={formData.brandGuidelines}
                        onChange={(e) =>
                          updateFormData({ brandGuidelines: e.target.value })
                        }
                        rows={4}
                      />
                    </div>
                  </div>
                </div>
              )}

              {/* Step 5: Review */}
              {currentStep === 'review' && (
                <div className="space-y-6">
                  <div>
                    <h2 className="text-lg font-semibold">Review Campaign</h2>
                    <p className="text-sm text-muted-foreground">
                      Review your campaign details before publishing
                    </p>
                  </div>

                  <div className="space-y-4">
                    <Card>
                      <CardHeader className="pb-2">
                        <CardTitle className="text-base">Campaign Details</CardTitle>
                      </CardHeader>
                      <CardContent className="space-y-3 text-sm">
                        <div className="flex justify-between">
                          <span className="text-muted-foreground">Title</span>
                          <span className="font-medium">{formData.title}</span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-muted-foreground">Visibility</span>
                          <Badge variant={formData.isPrivate ? 'secondary' : 'outline'}>
                            {formData.isPrivate ? 'Private' : 'Public'}
                          </Badge>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-muted-foreground">Objectives</span>
                          <span className="font-medium text-right">
                            {formData.objectives.join(', ')}
                          </span>
                        </div>
                      </CardContent>
                    </Card>

                    <Card>
                      <CardHeader className="pb-2">
                        <CardTitle className="text-base">Platforms & Content</CardTitle>
                      </CardHeader>
                      <CardContent className="space-y-3">
                        <div>
                          <p className="text-sm text-muted-foreground mb-2">Platforms</p>
                          <div className="flex flex-wrap gap-1.5">
                            {formData.platforms.map((p) => (
                              <Badge key={p} variant="outline">
                                {platformOptions.find((o) => o.value === p)?.label}
                              </Badge>
                            ))}
                          </div>
                        </div>
                        <div>
                          <p className="text-sm text-muted-foreground mb-2">Content Types</p>
                          <div className="flex flex-wrap gap-1.5">
                            {formData.contentTypes.map((c) => (
                              <Badge key={c} variant="outline">
                                {contentTypeOptions.find((o) => o.value === c)?.label}
                              </Badge>
                            ))}
                          </div>
                        </div>
                      </CardContent>
                    </Card>

                    <Card>
                      <CardHeader className="pb-2">
                        <CardTitle className="text-base">Budget & Timeline</CardTitle>
                      </CardHeader>
                      <CardContent className="space-y-3 text-sm">
                        <div className="flex justify-between">
                          <span className="text-muted-foreground">Budget Range</span>
                          <span className="font-medium">
                            {formData.currency === 'INR'
                              ? `₹${formData.budgetMin.toLocaleString('en-IN')} – ₹${formData.budgetMax.toLocaleString('en-IN')}`
                              : `${formData.currency} ${formData.budgetMin.toLocaleString()} – ${formData.budgetMax.toLocaleString()}`}
                          </span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-muted-foreground">Duration</span>
                          <span className="font-medium">
                            {formData.startDate && format(formData.startDate, 'MMM d')} -{' '}
                            {formData.endDate && format(formData.endDate, 'MMM d, yyyy')}
                          </span>
                        </div>
                        <div className="flex justify-between">
                          <span className="text-muted-foreground">Max Collaborators</span>
                          <span className="font-medium">{formData.maxCollaborators} creators</span>
                        </div>
                      </CardContent>
                    </Card>

                    {(formData.requirements.length > 0 || formData.hashtags.length > 0) && (
                      <Card>
                        <CardHeader className="pb-2">
                          <CardTitle className="text-base">Requirements</CardTitle>
                        </CardHeader>
                        <CardContent className="space-y-3">
                          {formData.requirements.length > 0 && (
                            <div>
                              <p className="text-sm text-muted-foreground mb-2">
                                Content Requirements
                              </p>
                              <ul className="text-sm space-y-1">
                                {formData.requirements.map((req, i) => (
                                  <li key={i} className="flex items-start gap-2">
                                    <CheckCircle2 className="h-4 w-4 mt-0.5 text-primary shrink-0" />
                                    {req}
                                  </li>
                                ))}
                              </ul>
                            </div>
                          )}
                          {formData.hashtags.length > 0 && (
                            <div>
                              <p className="text-sm text-muted-foreground mb-2">Hashtags</p>
                              <div className="flex flex-wrap gap-1.5">
                                {formData.hashtags.map((tag, i) => (
                                  <Badge key={i} variant="secondary">
                                    {tag}
                                  </Badge>
                                ))}
                              </div>
                            </div>
                          )}
                        </CardContent>
                      </Card>
                    )}

                    {/* D-14 — AI-suggested creators for this campaign's goals/audience/budget/
                        platforms. Purely informational (no selection wired into submission);
                        hidden entirely while loading or empty rather than showing an empty
                        state, since it's a secondary aid on the review step. */}
                    {!suggestionsLoading && suggestedCreators.length > 0 && (
                      <Card>
                        <CardHeader className="pb-2">
                          <CardTitle className="text-base">Suggested Creators</CardTitle>
                        </CardHeader>
                        <CardContent className="space-y-2">
                          {suggestedCreators.map((s) => (
                            <div
                              key={s.creator.id}
                              className="flex items-center gap-3 rounded-lg border bg-card p-3"
                            >
                              <Avatar className="h-9 w-9 shrink-0">
                                <AvatarImage src={s.creator.avatarUrl || undefined} />
                                <AvatarFallback>{s.creator.displayName.charAt(0)}</AvatarFallback>
                              </Avatar>
                              <div className="min-w-0 flex-1">
                                <p className="truncate text-sm font-medium">{s.creator.displayName}</p>
                                {s.reasons.length > 0 && (
                                  <p className="truncate text-xs text-muted-foreground">
                                    {s.reasons.join(' · ')}
                                  </p>
                                )}
                              </div>
                              <Badge variant="outline" className="shrink-0 text-xs">
                                {Math.round(s.matchScore)}% match
                              </Badge>
                            </div>
                          ))}
                        </CardContent>
                      </Card>
                    )}
                  </div>
                </div>
              )}

              {currentStep === 'review' && verificationBlocked && (
                <div className="mt-6">
                  <VerificationRequiredBox
                    canVerify={canVerify}
                    savingDraft={isSubmitting}
                    onSaveDraft={() => handleSubmit('DRAFT')}
                  />
                </div>
              )}

              {/* Navigation */}
              <div className="flex items-center justify-between mt-8 pt-6 border-t border-border">
                {currentStepIndex > 0 ? (
                  <Button type="button" variant="outline" onClick={handleBack}>
                    <ArrowLeft className="mr-2 h-4 w-4" />
                    Back
                  </Button>
                ) : (
                  <div />
                )}

                <div className="flex items-center gap-3">
                  {currentStep === 'review' ? (
                    <>
                      <Button
                        type="button"
                        variant="outline"
                        onClick={() => handleSubmit('DRAFT')}
                        disabled={isSubmitting}
                      >
                        <Save className="mr-2 h-4 w-4" />
                        Save Draft
                      </Button>
                      <Button
                        type="button"
                        onClick={() => handleSubmit('ACTIVE')}
                        disabled={isSubmitting}
                      >
                        {isSubmitting ? (
                          <>
                            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                            Publishing...
                          </>
                        ) : (
                          <>
                            Publish Campaign
                            <CheckCircle2 className="ml-2 h-4 w-4" />
                          </>
                        )}
                      </Button>
                    </>
                  ) : (
                    <Button type="button" onClick={handleNext}>
                      Continue
                      <ArrowRight className="ml-2 h-4 w-4" />
                    </Button>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </TooltipProvider>
  );
}
