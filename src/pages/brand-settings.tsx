import * as React from 'react';
import { useNavigate } from 'react-router-dom';
import { Settings, Bell, Lock, Users, LogOut, Save, Crown, ArrowRight, UserPlus, Loader2, Plug } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { StoreIntegrationSetup } from '@/components/brand/settings/StoreIntegrationSetup';
import { TeamMembersPanel } from '@/components/brand/settings/team-members-panel';
import { ConversionWebhookSecretCard } from '@/components/brand/settings/ConversionWebhookSecretCard';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import { api, isApiLive, ApiError, type NotificationPreference, type WorkspaceMeResponse } from '@/lib/api';
import { useAuthStore } from '@/lib/store';
import { toast } from '@/hooks/use-toast';
import { normalizePhone, isValidPhone, filterPhoneInput } from '@/lib/phone';

/**
 * Verified against influora-api: NotificationService#isUnsubscribed (service/notification/
 * NotificationService.java) keys the per-event lookup on `event.eventType()` — the literal string
 * each NotificationEvent record returns from its own `eventType()` method — NOT the "creator.x" /
 * "brand.x" strings NotificationListener also passes as `templateKey` for email-template selection.
 * Those templateKey strings (e.g. "creator.campaign_match", "brand.new_application",
 * "creator.bid_accepted", "brand.counter_bid", "brand.proposal_accepted",
 * "creator.proposal_received") are never read by isUnsubscribed, so binding a toggle to one would
 * be a dead preference row. Further restricted to events where the BRAND is the actual recipient
 * (NotificationListener's "Creator -> Brand events" block, `emailOf(event.userId())` resolving to
 * the brand's own user) — "campaign.created" (CampaignCreatedEvent) and "bid.accepted"
 * (BidAcceptedEvent) are real, emitted eventTypes, but both only ever notify the *creator* side, so
 * a brand's preference row for them would never be matched by isUnsubscribed either. Grepped and
 * cross-checked against every *Event.java under service/notification/event/ on 2026-07-30.
 *
 * UPDATE (2026-07-30, Vikram recipient-trace): expanded both groups to the complete brand-recipient
 * eventType sets — traced by which emailOf(...) resolution each event actually hits, not by
 * comment/file grouping. `message.first` is a real brand-recipient eventType too but is
 * DELIBERATELY EXCLUDED from Campaign Alerts (Priya ruling) — a first-message email isn't a
 * "campaign alert", and folding it in would mean disabling Campaign Alerts silently kills message
 * notifications, which is surprising. It stays controlled only by the global "*" switch until a
 * dedicated "Messages" toggle exists. Contract/billing eventTypes (contract.signed,
 * contract.ready_for_escrow, billing.invoice_ready, billing.payment_failed,
 * billing.subscription_halted) are also real brand-recipient events but are intentionally NOT
 * bound to any toggle — see the transactional-notice copy in the Notifications tab below.
 */
const CAMPAIGN_ALERT_EVENT_TYPES = ['application.created', 'deliverable.submitted', 'shipment.received'] as const;
const BID_NOTIFICATION_EVENT_TYPES = ['bid.countered', 'proposal.accepted'] as const;
const CATEGORY_EVENT_TYPES = {
  campaignAlerts: CAMPAIGN_ALERT_EVENT_TYPES,
  bidNotifications: BID_NOTIFICATION_EVENT_TYPES,
} as const;
type CategoryPrefKey = keyof typeof CATEGORY_EVENT_TYPES;

// OFF only when every eventType in the group is unsubscribed; a missing row defaults to
// subscribed (mirrors NotificationService#isUnsubscribed's own default), so any missing/false row
// keeps the group ON. Deterministic and idempotent regardless of preference-row ordering.
function isCategoryGroupOff(prefs: NotificationPreference[], eventTypes: readonly string[]): boolean {
  return eventTypes.every((eventType) => prefs.find((p) => p.eventType === eventType)?.unsubscribed === true);
}

/**
 * F-0636 — GET /users/me (the account Mobile Number card below) used to render the same
 * "Could not load your mobile number." for every failure mode: an expired session, a real
 * server error, and a dropped network connection all looked identical and offered the same
 * dead-end Retry button. They need different reactions from the user:
 *  - auth: `fetchWithAuthRetry` (src/lib/api.ts) already attempted one silent refresh+retry
 *    before this ever throws, so a 401/403 here means the session is genuinely gone — retrying
 *    the same request will just 401 again. The user needs to sign back in, not press Retry.
 *  - server: a 5xx (or the `SERVER_UNAVAILABLE` code `parseEnvelope` raises for a 502/503/504
 *    non-JSON body) is the backend's problem, not the user's — Retry is the right affordance and
 *    the copy says so rather than implying something is wrong with their account.
 *  - offline: `fetch()` itself rejects (a plain `TypeError`, e.g. "Failed to fetch") when the
 *    request never reaches the network at all — this is NEVER wrapped in `ApiError` (see
 *    `HttpClient#request`), so it is the only branch that must be reached without one.
 * `unknown` is the pre-existing generic message, kept as the fallback for anything else (a 4xx
 * this endpoint doesn't otherwise return, etc.) so no failure mode goes unhandled.
 */
type AccountPhoneLoadFailure = {
  kind: 'auth' | 'server' | 'offline' | 'unknown';
  message: string;
  /** Whether Retry can plausibly help — false for 'auth', where the same request just 401s again. */
  retryable: boolean;
};

function classifyAccountPhoneLoadFailure(err: unknown): AccountPhoneLoadFailure {
  if (err instanceof ApiError) {
    if (err.status === 401 || err.status === 403) {
      return {
        kind: 'auth',
        message: 'Your session has expired. Please sign in again to view your mobile number.',
        retryable: false,
      };
    }
    if ((err.status !== undefined && err.status >= 500) || err.code === 'SERVER_UNAVAILABLE') {
      return {
        kind: 'server',
        message: "Something went wrong on our end. This isn't a problem with your account — please try again in a moment.",
        retryable: true,
      };
    }
    return { kind: 'unknown', message: 'Could not load your mobile number.', retryable: true };
  }
  // Not an ApiError at all: the request never reached (or heard back from) the server —
  // fetch() rejecting outright, e.g. no network, DNS failure, or a CORS/mixed-content block.
  return {
    kind: 'offline',
    message: "You appear to be offline. Check your connection and try again.",
    retryable: true,
  };
}

export default function BrandSettingsPage() {
  const navigate = useNavigate();
  const { logout: clearAuthStore } = useAuthStore();
  const liveApi = isApiLive();
  const [activeTab, setActiveTab] = React.useState('general');
  // F-0249 — these four fields must never start as the mock seed in live mode. If GET
  // /workspaces/me fails, a stale seed sitting in these inputs with an enabled Save button
  // would let one click PATCH fabricated values over the brand's real name/billing email.
  // Mock mode keeps the seed (the "local defaults are the whole story" contract used
  // throughout this file); live mode starts these four fields honestly empty until the
  // server responds — see workspaceInfoLoaded below, which gates the Save control on it.
  const [settings, setSettings] = React.useState({
    workspaceName: liveApi ? '' : 'Tech Brands Co.',
    email: liveApi ? '' : 'admin@techbrands.in',
    phone: liveApi ? '' : '+91 98765 43210',
    website: liveApi ? '' : 'www.techbrands.in',
    emailNotifications: true,
    pushNotifications: true,
    weeklyDigest: false,
    campaignAlerts: true,
    bidNotifications: true,
  });
  const [emailPrefLoading, setEmailPrefLoading] = React.useState(false);
  const [emailPrefSaving, setEmailPrefSaving] = React.useState(false);
  const [emailPrefError, setEmailPrefError] = React.useState<string | null>(null);

  // F-0262 — this used to be one blanket disclaimer ("Settings sync isn't available yet —
  // changes apply to this session only") rendered under a disabled "Save Preferences" button
  // for the WHOLE notifications card. That was true when this comment was first written, but
  // campaignAlerts/bidNotifications got real backend emitters on 2026-07-30 (see the UPDATE
  // comment on the effect below) and now PATCH the server the instant their switch is flipped
  // — same as emailNotifications always has. Only Push Notifications and Weekly Digest are
  // still genuinely session-only (no backend to hit at all, both switches stay `disabled`), so
  // the caption below names those two specifically instead of claiming nothing on this screen
  // persists.
  const PUSH_AND_DIGEST_UNAVAILABLE = "Not available yet — this toggle has no effect.";

  // UPDATE (2026-07-18): GET/PATCH /workspaces/me now exist (WorkspaceController, L-9) and
  // cover name/email(-> billing_email)/phone/websiteUrl for this "Workspace Information" card.
  // `email` is the workspace's billing/contact email server-side, not a login email. `phone`
  // shipped 2026-07-18 (migration + GET/PATCH, Vikram) — full-replace, blank clears it
  // server-side; server validates `+ ( ) - space` + 7-15 digits, so we don't duplicate that
  // validation client-side beyond a light hint.
  const [workspaceInfoLoading, setWorkspaceInfoLoading] = React.useState(false);
  const [workspaceInfoLoadError, setWorkspaceInfoLoadError] = React.useState<string | null>(null);
  const [workspaceInfoSaving, setWorkspaceInfoSaving] = React.useState(false);
  const [workspaceInfoSaveError, setWorkspaceInfoSaveError] = React.useState<string | null>(null);
  // F-0249 — true once we hold an authoritative snapshot of the four fields above: either a
  // real GET /workspaces/me response (live mode) or immediately in mock mode, where the local
  // seed is never sent to a real server so it IS the authoritative value. Save stays disabled
  // (below) until this is true, so a load failure can never leave a submittable mock seed.
  const [workspaceInfoLoaded, setWorkspaceInfoLoaded] = React.useState(!liveApi);
  // F-0462 — the last-loaded (or last-saved) full workspace record, held alongside `settings`
  // so a save can merge the edited fields over it. PATCH /workspaces/me is full-replace: an
  // omitted field is CLEARED server-side, and this form only edits name/email/phone/website —
  // it never surfaces industry/companySize/logoUrl. Without this snapshot, saving would silently
  // wipe those fields. Stays null in mock mode (loadWorkspaceInfo never runs there), which is
  // fine — mock mode's local seed is already the whole story.
  const [loadedWorkspace, setLoadedWorkspace] = React.useState<WorkspaceMeResponse | null>(null);

  // F-0249 — extracted from the effect so a failed load can be retried from the UI.
  const loadWorkspaceInfo = React.useCallback(() => {
    if (!liveApi) return; // mock mode: local defaults are the whole story
    let cancelled = false;
    setWorkspaceInfoLoading(true);
    setWorkspaceInfoLoadError(null);
    api.workspaces
      .getMe()
      .then((ws) => {
        if (cancelled) return;
        setLoadedWorkspace(ws);
        setSettings((prev) => ({
          ...prev,
          workspaceName: ws.name,
          // Live-mode hydration: a null field means the workspace genuinely has no
          // value on file — fall back to '' (honest empty), NOT prev.* which could be the
          // mock seed (admin@techbrands.in / +91 98765 43210 / www.techbrands.in) or a
          // stale value left over from a previous failed load.
          email: ws.email ?? '',
          phone: ws.phone ?? '',
          website: ws.websiteUrl ?? '',
        }));
        setWorkspaceInfoLoaded(true);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error('Failed to load workspace information', err);
        setWorkspaceInfoLoadError('Could not load workspace information.');
      })
      .finally(() => {
        if (!cancelled) setWorkspaceInfoLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [liveApi]);

  React.useEffect(() => loadWorkspaceInfo(), [loadWorkspaceInfo]);

  const handleSaveWorkspaceInfo = async () => {
    // F-0249 — defense in depth behind the disabled Save button below: refuse to PATCH
    // whenever we don't hold an authoritative snapshot of these fields (still loading, load
    // failed and never succeeded since, or a save is already in flight). The button's
    // disabled state is the primary guard; this exists so the handler stays safe on its own.
    if (workspaceInfoLoading || workspaceInfoSaving || (liveApi && (!workspaceInfoLoaded || workspaceInfoLoadError))) {
      return;
    }
    if (!settings.workspaceName.trim()) {
      setWorkspaceInfoSaveError('Workspace name is required.');
      return;
    }
    setWorkspaceInfoSaving(true);
    setWorkspaceInfoSaveError(null);
    try {
      // F-0249 — deliberately a full-object PATCH, not a dirty-fields-only diff:
      // WorkspaceMeUpdatePayload is documented as full-replace server-side (an omitted field
      // is CLEARED, not left alone), so partial submission would risk wiping fields the user
      // never touched. That's safe here because by the time Save is enabled, every one of
      // these four fields is guaranteed to be either the server's own loaded value or
      // something the user typed after that load — never the mock seed and never an
      // unloaded/stale value (see workspaceInfoLoaded and the guard above).
      // F-0462 — PATCH /workspaces/me is full-replace, and this form only edits four fields,
      // so sending those four alone silently cleared every field it does not surface:
      // industry, companySize, description and logoUrl. Carry them all forward from the
      // last-loaded workspace (undefined when it had no value, which JSON-serializes to the
      // same "omitted" a never-set field already was). `description` also required adding the
      // field to WorkspaceReadResponse — it was write-only, so there was nothing to echo back.
      const updated = await api.workspaces.updateMe({
        industry: loadedWorkspace?.industry ?? undefined,
        companySize: loadedWorkspace?.companySize ?? undefined,
        description: loadedWorkspace?.description ?? undefined,
        logoUrl: loadedWorkspace?.logoUrl ?? undefined,
        name: settings.workspaceName,
        email: settings.email,
        phone: settings.phone,
        websiteUrl: settings.website,
      });
      setLoadedWorkspace(updated);
      setSettings((prev) => ({
        ...prev,
        workspaceName: updated.name,
        // Same honest-empty rule as the load path — a cleared field comes back null
        // and must not fall back to the mock seed.
        email: updated.email ?? '',
        phone: updated.phone ?? '',
        website: updated.websiteUrl ?? '',
      }));
      toast({ title: 'Workspace updated', description: 'Your workspace information has been saved.' });
    } catch (err) {
      console.error('Failed to save workspace information', err);
      const message =
        err instanceof ApiError && err.status === 403
          ? 'Only workspace owners/admins can change these settings.'
          : 'Could not save workspace information. Please try again.';
      setWorkspaceInfoSaveError(message);
      toast({ title: 'Save failed', description: message, variant: 'destructive' });
    } finally {
      setWorkspaceInfoSaving(false);
    }
  };

  // PHONE-0904 Q1 — GET/PATCH /users/me (UserController -> UserService), the brand's OWN account
  // mobile number. Deliberately a SEPARATE field from the "Phone" input inside the Workspace
  // Information card above: that one is `workspaces.phone` (optional, blank-clears, `+ ( ) -
  // space` 7-15 digit validation via /workspaces/me). This one is `users.phone_number` — the
  // number collected at brand registration, now REQUIRED (Q8), Indian-mobile-validated via the
  // shared src/lib/phone.ts helpers (same rules as creator/onboarding phone fields). Do not merge
  // these two — different columns, different owners, different validation.
  const [savedPhone, setSavedPhone] = React.useState<string | null>(null);
  const [phoneLoading, setPhoneLoading] = React.useState(true);
  const [phoneLoadError, setPhoneLoadError] = React.useState<AccountPhoneLoadFailure | null>(null);
  const [showPhoneDialog, setShowPhoneDialog] = React.useState(false);
  const [phoneDraft, setPhoneDraft] = React.useState('');
  const [phoneError, setPhoneError] = React.useState<string | null>(null);
  const [isSavingPhone, setIsSavingPhone] = React.useState(false);

  const loadAccountPhone = React.useCallback(() => {
    let cancelled = false;
    setPhoneLoading(true);
    setPhoneLoadError(null);
    api.users
      .getMe()
      .then((profile) => {
        if (cancelled) return;
        setSavedPhone(profile.phone);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error('Failed to load account mobile number', err);
        setPhoneLoadError(classifyAccountPhoneLoadFailure(err));
      })
      .finally(() => {
        if (!cancelled) setPhoneLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  React.useEffect(() => loadAccountPhone(), [loadAccountPhone]);

  const openPhoneDialog = () => {
    setPhoneDraft(savedPhone ?? '');
    setPhoneError(null);
    setShowPhoneDialog(true);
  };

  const closePhoneDialog = (open: boolean) => {
    setShowPhoneDialog(open);
    if (!open) setPhoneError(null);
  };

  // Brand phone is REQUIRED (PHONE-0904 Q8) — unlike creator Settings' own phone field, there is
  // no "clear it" convention here (PHONE_REQUIRED on a blank PATCH). Block a blank/invalid submit
  // client-side with the same reason the server would give, rather than round-tripping to learn
  // it. The Save button below is also disabled on an empty normalized draft (primary guard); this
  // check keeps the handler safe even if that's ever bypassed.
  //
  // F-0634 — UpdateProfileRequest/UsersMeUpdatePayload carry firstName/lastName/displayName/
  // timezone/avatarUrl/phone, and this is the only call site, sending `{ phone }` alone. That is
  // NOT the F-0462 bug: UsersMeUpdatePayload's own doc comment (src/lib/api.ts) and
  // UserService#updateProfile (influora-api) both apply each field only `if (x != null)` — a
  // genuine partial-merge PATCH, unlike WorkspaceMeUpdatePayload's full-replace. Omitting the
  // other five fields here cannot wipe them. This page's Security tab simply has no inputs for
  // first/last/display name, timezone, or avatar — that's the profile-editing UI not existing
  // yet, not a defect in the phone-editing UI that does. Not adding those five fields here: this
  // ticket is about the phone flow, and inventing a profile-editor as a side effect would be
  // scope creep the page doesn't otherwise support (no avatar upload, no timezone picker, etc.).
  //
  // F-0635 — `setSavedPhone(updated.phone)` trusts the PATCH response instead of re-fetching via
  // GET /users/me. That's correct here, not a shortcut: `updated` IS the server's fresh
  // UserProfileMeResponse for this exact write, over the same authenticated connection, in the
  // same request/response pair — there is no separate "did it really persist" question a second
  // GET could answer that the 2xx response doesn't already answer. A forced re-fetch would only
  // add a second round trip and a second failure mode (what does a GET failure right after a
  // successful PATCH even mean to the user?) for zero added correctness.
  const handleSavePhone = async () => {
    const normalized = normalizePhone(phoneDraft);
    if (!normalized) {
      setPhoneError('Mobile number is required and cannot be removed.');
      return;
    }
    if (!isValidPhone(normalized)) {
      setPhoneError('Enter a valid 10-digit mobile number');
      return;
    }
    setPhoneError(null);
    setIsSavingPhone(true);
    try {
      const updated = await api.users.updateMe({ phone: normalized });
      setSavedPhone(updated.phone);
      setShowPhoneDialog(false);
      toast({ title: 'Mobile number updated' });
    } catch (err) {
      // Branch on the machine-readable `code` (src/lib/api.ts), never a bare `err.status` —
      // PHONE_ALREADY_EXISTS/INVALID_PHONE/PHONE_REQUIRED are all 400/409 and must render their
      // own distinct message next to this field rather than falling into a generic toast.
      if (err instanceof ApiError && err.code === 'PHONE_ALREADY_EXISTS') {
        setPhoneError('This mobile number is already registered');
      } else if (err instanceof ApiError && err.code === 'INVALID_PHONE') {
        setPhoneError('Enter a valid 10-digit mobile number');
      } else if (err instanceof ApiError && err.code === 'PHONE_REQUIRED') {
        setPhoneError('Mobile number is required and cannot be removed.');
      } else {
        toast({
          title: 'Could not save changes',
          description: err instanceof ApiError ? err.message : 'Please try again.',
          variant: 'destructive',
        });
      }
    } finally {
      setIsSavingPhone(false);
    }
  };

  // UPDATE (2026-07-18): notifications.getPreferences/setPreference (src/lib/api.ts) now hit a
  // real, auth-scoped route (GET/POST /notifications/preferences, NotificationController.java),
  // backed by the existing email_preferences table. "Email Notifications" below is wired to the
  // global opt-out (eventType "*"), which NotificationService#isUnsubscribed checks FIRST and
  // short-circuits on — if the master switch is off, per-category rows are moot regardless of
  // their own value, so that stays the master switch, unchanged.
  // UPDATE (2026-07-30): campaignAlerts/bidNotifications ARE wired for real now — isUnsubscribed
  // falls through to a genuine per-eventType lookup (findByUserIdAndEventType) once the global
  // check passes, and that lookup is honored by the real event emitters. See
  // CATEGORY_EVENT_TYPES above for the exact eventType groups and why each string was kept/dropped.
  // weeklyDigest and pushNotifications remain unbacked (no weekly-digest job — only
  // "cron.monthly_statement" exists — and no push infra at all) and stay UI-only/disabled.
  React.useEffect(() => {
    let cancelled = false;
    setEmailPrefLoading(true);
    api.notifications
      .getPreferences('brand')
      .then((prefs) => {
        if (cancelled) return;
        const globalPref = prefs.find((p) => p.eventType === '*');
        setSettings((prev) => ({
          ...prev,
          emailNotifications: !globalPref?.unsubscribed,
          campaignAlerts: !isCategoryGroupOff(prefs, CAMPAIGN_ALERT_EVENT_TYPES),
          bidNotifications: !isCategoryGroupOff(prefs, BID_NOTIFICATION_EVENT_TYPES),
        }));
      })
      .catch((err) => {
        if (cancelled) return;
        console.error('Failed to load notification preferences', err);
        setEmailPrefError('Could not load your email notification preference.');
      })
      .finally(() => {
        if (!cancelled) setEmailPrefLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleEmailPrefChange = async (checked: boolean) => {
    const previous = settings.emailNotifications;
    setSettings({ ...settings, emailNotifications: checked });
    setEmailPrefError(null);

    if (!liveApi) return; // mock mode: local state is the whole story

    setEmailPrefSaving(true);
    try {
      await api.notifications.setPreference('brand', '*', checked);
    } catch (err) {
      console.error('Failed to save notification preference', err);
      setSettings((prev) => ({ ...prev, emailNotifications: previous })); // revert on failure
      setEmailPrefError('Could not save. Please try again.');
      toast({
        title: 'Save failed',
        description: 'Could not update your email notification preference.',
        variant: 'destructive',
      });
    } finally {
      setEmailPrefSaving(false);
    }
  };

  // Category toggles (campaignAlerts/bidNotifications) — one setPreference call per eventType in
  // the group (no bulk endpoint; same one-call-per-item precedent as markAllRead in
  // useNotifications.ts). A partial failure would leave the group's eventTypes in an inconsistent
  // subscribed state, so this reverts the whole optimistic toggle on ANY failure rather than trying
  // to reconcile per-eventType, same as handleEmailPrefChange above.
  const [categoryPrefSaving, setCategoryPrefSaving] = React.useState<Record<CategoryPrefKey, boolean>>({
    campaignAlerts: false,
    bidNotifications: false,
  });
  const [categoryPrefError, setCategoryPrefError] = React.useState<Record<CategoryPrefKey, string | null>>({
    campaignAlerts: null,
    bidNotifications: null,
  });

  const handleCategoryPrefChange = async (key: CategoryPrefKey, checked: boolean) => {
    const previous = settings[key];
    setSettings((prev) => ({ ...prev, [key]: checked }));
    setCategoryPrefError((prev) => ({ ...prev, [key]: null }));

    if (!liveApi) return; // mock mode: local state is the whole story

    setCategoryPrefSaving((prev) => ({ ...prev, [key]: true }));
    try {
      await Promise.all(
        CATEGORY_EVENT_TYPES[key].map((eventType) => api.notifications.setPreference('brand', eventType, checked)),
      );
    } catch (err) {
      console.error(`Failed to save ${key} notification preference`, err);
      setSettings((prev) => ({ ...prev, [key]: previous })); // revert on failure
      setCategoryPrefError((prev) => ({ ...prev, [key]: 'Could not save. Please try again.' }));
      toast({
        title: 'Save failed',
        description: 'Could not update your notification preference.',
        variant: 'destructive',
      });
    } finally {
      setCategoryPrefSaving((prev) => ({ ...prev, [key]: false }));
    }
  };

  // [F-0443] The member roster and invite handler that lived here were removed with the
  // duplicate Workspace Members card below: TeamMembersPanel owns both now, and keeping a
  // second loader would have left this page fetching a roster nothing renders.


  // Change Password dialog — POST /me/password (Vikram, landing in parallel with this change).
  const [isPasswordOpen, setIsPasswordOpen] = React.useState(false);
  const [currentPassword, setCurrentPassword] = React.useState('');
  const [newPassword, setNewPassword] = React.useState('');
  const [confirmPassword, setConfirmPassword] = React.useState('');
  const [passwordSubmitting, setPasswordSubmitting] = React.useState(false);
  const [passwordError, setPasswordError] = React.useState<string | null>(null);

  const handleChangePassword = async () => {
    if (!currentPassword || !newPassword) {
      setPasswordError('Current and new password are both required.');
      return;
    }
    if (newPassword.length < 8) {
      setPasswordError('New password must be at least 8 characters.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('New passwords do not match.');
      return;
    }
    setPasswordSubmitting(true);
    setPasswordError(null);
    try {
      await api.auth.changePassword('brand', { currentPassword, newPassword });
      toast({ title: 'Password changed', description: 'Your password has been updated.' });
      setIsPasswordOpen(false);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      console.error('Failed to change password', err);
      setPasswordError(err instanceof ApiError ? err.message : 'Could not change your password. Try again.');
    } finally {
      setPasswordSubmitting(false);
    }
  };

  // Logout from All Devices — POST /auth/logout (revokes every refresh token server-side).
  const [loggingOutAllDevices, setLoggingOutAllDevices] = React.useState(false);
  const handleLogoutAllDevices = async () => {
    setLoggingOutAllDevices(true);
    try {
      await api.auth.logout('brand');
    } catch (err) {
      // `api.auth.logout` clears the local token in its own `finally` (CR-91: after the
      // request, so the server can revoke refresh tokens), and `clearAuthStore()` below
      // runs regardless — a failed network call still leaves this session logged out.
      console.error('Logout-all-devices request failed', err);
    } finally {
      clearAuthStore();
      setLoggingOutAllDevices(false);
      navigate('/brand/login');
    }
  };

  return (
    <div className="flex-1 overflow-auto">
      <div className="p-8 max-w-5xl">
        {/* Header */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold tracking-tight flex items-center gap-2">
            <Settings className="h-8 w-8" />
            Settings
          </h1>
          <p className="text-muted-foreground mt-2">Manage your workspace preferences and account</p>
        </div>

        <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-6">
          <TabsList className="grid w-full grid-cols-6 lg:w-auto">
            <TabsTrigger value="general" className="gap-2">
              <Users className="h-4 w-4" />
              <span className="hidden sm:inline">General</span>
            </TabsTrigger>
            <TabsTrigger value="notifications" className="gap-2">
              <Bell className="h-4 w-4" />
              <span className="hidden sm:inline">Notifications</span>
            </TabsTrigger>
            {/* [F-0443] Team management had no surface at all until this tab. */}
            <TabsTrigger value="team" className="gap-2">
              <UserPlus className="h-4 w-4" />
              <span className="hidden sm:inline">Team</span>
            </TabsTrigger>
            <TabsTrigger value="billing" className="gap-2">
              <Crown className="h-4 w-4" />
              <span className="hidden sm:inline">Billing</span>
            </TabsTrigger>
            <TabsTrigger value="security" className="gap-2">
              <Lock className="h-4 w-4" />
              <span className="hidden sm:inline">Security</span>
            </TabsTrigger>
            <TabsTrigger value="integrations" className="gap-2">
              <Plug className="h-4 w-4" />
              <span className="hidden sm:inline">Integrations</span>
            </TabsTrigger>
          </TabsList>

          {/* [F-0443] Team */}
          <TabsContent value="team" className="space-y-6">
            <TeamMembersPanel />
          </TabsContent>

          {/* General Settings */}
          <TabsContent value="general" className="space-y-6">
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Workspace Information</h3>
              {workspaceInfoLoading && (
                <p className="text-xs text-muted-foreground mb-4">Loading workspace information…</p>
              )}
              {workspaceInfoLoadError && (
                <div className="flex items-center justify-between gap-3 mb-4">
                  <p className="text-xs text-destructive-foreground">{workspaceInfoLoadError}</p>
                  <Button type="button" variant="outline" size="sm" onClick={loadWorkspaceInfo}>
                    Retry
                  </Button>
                </div>
              )}
              <div className="space-y-4">
                <div>
                  <Label htmlFor="workspace-name">Workspace Name</Label>
                  <Input
                    id="workspace-name"
                    value={settings.workspaceName}
                    onChange={(e) => setSettings({ ...settings, workspaceName: e.target.value })}
                    disabled={workspaceInfoLoading}
                    className="mt-2"
                  />
                </div>
                <div>
                  <Label htmlFor="email">Email</Label>
                  <Input
                    id="email"
                    type="email"
                    value={settings.email}
                    onChange={(e) => setSettings({ ...settings, email: e.target.value })}
                    disabled={workspaceInfoLoading}
                    placeholder="No email on file"
                    className="mt-2"
                  />
                  <p className="text-xs text-muted-foreground mt-1">Used for billing & workspace contact</p>
                </div>
                <div>
                  {/* PHONE-0904 re-run item 1 (Priya) — this is workspaces.phone: the
                      business/workspace contact number, optional, blank-clears, loose
                      international format. Label, placeholder, and helper copy are all
                      deliberately distinct from the "Mobile Number" field on the Security
                      tab below (users.phone_number: your own account number, required,
                      strict Indian-mobile) so the two are never mistaken for each other. */}
                  <Label htmlFor="phone">Workspace Phone</Label>
                  <Input
                    id="phone"
                    type="tel"
                    value={settings.phone}
                    onChange={(e) => setSettings({ ...settings, phone: e.target.value })}
                    disabled={workspaceInfoLoading}
                    placeholder="e.g. +1 415 555 0100"
                    className="mt-2"
                  />
                  <p className="text-xs text-muted-foreground mt-1">
                    Your workspace&apos;s business contact number (any country) — shown to
                    partners, not your personal number. Optional; leave blank to clear.
                  </p>
                </div>
                <div>
                  <Label htmlFor="website">Website</Label>
                  <Input
                    id="website"
                    value={settings.website}
                    onChange={(e) => setSettings({ ...settings, website: e.target.value })}
                    disabled={workspaceInfoLoading}
                    className="mt-2"
                  />
                </div>
                <Button
                  onClick={handleSaveWorkspaceInfo}
                  disabled={
                    workspaceInfoLoading ||
                    workspaceInfoSaving ||
                    (liveApi && (!workspaceInfoLoaded || !!workspaceInfoLoadError))
                  }
                  title={
                    liveApi && workspaceInfoLoadError
                      ? 'Workspace information failed to load — retry above before saving.'
                      : liveApi && !workspaceInfoLoaded
                        ? 'Waiting for workspace information to load…'
                        : undefined
                  }
                  className="gap-2"
                >
                  <Save className="h-4 w-4" />
                  {workspaceInfoSaving ? 'Saving…' : 'Save Changes'}
                </Button>
                {workspaceInfoSaveError && (
                  <p className="text-xs text-destructive-foreground">{workspaceInfoSaveError}</p>
                )}
              </div>
            </Card>

          {/* [F-0443] Team management moved to the Team tab (TeamMembersPanel). The roster and
              Invite dialog that used to sit here were a SECOND copy of the same two calls, so
              the page briefly shipped two invite forms; the Team tab is the single home now,
              and it also carries the pending-invite list, revoke and remove that this section
              never had. */}
          </TabsContent>

          {/* Notification Settings */}
          <TabsContent value="notifications" className="space-y-6">
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Notification Preferences</h3>
              <div className="space-y-5">
                <div className="p-4 border rounded-lg">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="font-medium text-sm">Email Notifications</p>
                      <p className="text-xs text-muted-foreground">Receive updates via email</p>
                    </div>
                    <Switch
                      checked={settings.emailNotifications}
                      disabled={emailPrefLoading || emailPrefSaving}
                      onCheckedChange={handleEmailPrefChange}
                    />
                  </div>
                  {(emailPrefLoading || emailPrefSaving || emailPrefError) && (
                    <p
                      className={cn(
                        'text-xs mt-1',
                        emailPrefError ? 'text-destructive-foreground' : 'text-muted-foreground',
                      )}
                    >
                      {emailPrefError ?? (emailPrefLoading ? 'Loading preference…' : 'Saving…')}
                    </p>
                  )}
                </div>

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Push Notifications</p>
                    <p className="text-xs text-muted-foreground">Browser notifications</p>
                  </div>
                  {/* No push channel exists server-side — disabled rather than a switch that
                      silently does nothing. */}
                  <Switch checked={false} disabled title="Push notifications are not available yet" />
                </div>

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Campaign Alerts</p>
                    <p className="text-xs text-muted-foreground">
                      New applications, submitted deliverables, and shipment updates on your campaigns.
                    </p>
                  </div>
                  <Switch
                    checked={settings.campaignAlerts}
                    disabled={categoryPrefSaving.campaignAlerts}
                    onCheckedChange={(e) => handleCategoryPrefChange('campaignAlerts', e)}
                  />
                </div>
                {categoryPrefError.campaignAlerts && (
                  <p className="text-xs text-destructive-foreground -mt-3">{categoryPrefError.campaignAlerts}</p>
                )}

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Bid Notifications</p>
                    <p className="text-xs text-muted-foreground">
                      When a creator counters your offer or accepts your proposal.
                    </p>
                  </div>
                  <Switch
                    checked={settings.bidNotifications}
                    disabled={categoryPrefSaving.bidNotifications}
                    onCheckedChange={(e) => handleCategoryPrefChange('bidNotifications', e)}
                  />
                </div>
                {categoryPrefError.bidNotifications && (
                  <p className="text-xs text-destructive-foreground -mt-3">{categoryPrefError.bidNotifications}</p>
                )}

                <div className="flex items-center justify-between p-4 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">Weekly Digest</p>
                    <p className="text-xs text-muted-foreground">Summary of activities</p>
                  </div>
                  <Switch
                    checked={settings.weeklyDigest}
                    onCheckedChange={(e) => setSettings({ ...settings, weeklyDigest: e })}
                    disabled
                    title="Weekly digest isn't available yet"
                  />
                </div>

                {/* Priya ruling: contract/escrow/billing emails (contract.signed,
                    contract.ready_for_escrow, billing.invoice_ready, billing.payment_failed,
                    billing.subscription_halted) are transactional and always-on — a brand must
                    not be able to switch off a payment-failed or contract-signed email, so this
                    is disclosure copy, not a control. */}
                <p className="text-xs text-muted-foreground">
                  Essential contract, payment, and billing emails are always sent and can&apos;t be
                  turned off individually — only the Email Notifications switch above affects them.
                </p>

                {/* F-0262 — no "Save Preferences" button here anymore: Email Notifications,
                    Campaign Alerts, and Bid Notifications above already PATCH the server the
                    instant their switch is flipped, so there is nothing left to batch-save.
                    Only the two disabled switches above (Push Notifications, Weekly Digest)
                    are still genuinely unavailable — this note names those, not the whole card. */}
                <p className="text-xs text-muted-foreground text-center">
                  Email Notifications, Campaign Alerts, and Bid Notifications save immediately
                  when you flip them. Push Notifications and Weekly Digest above: {PUSH_AND_DIGEST_UNAVAILABLE}
                </p>
              </div>
            </Card>
          </TabsContent>

          {/* Payment Settings — REMOVED (BR-05, Priya 2026-07-30): both cards here were either
              hardcoded fake data (Credit Card ****4242) or need a Razorpay mandate that doesn't
              exist yet (auto-recharge). brand-billing-settings.tsx is the single honest owner of
              payment UI; this tab duplicated it with fabricated state. Per UI Honesty, "Absent"
              beats a control that lies. */}

          {/* Billing & Subscription — links out to the dedicated page (live plan/usage/invoices) */}
          <TabsContent value="billing" className="space-y-6">
            <Card className="p-6">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10">
                    <Crown className="h-5 w-5 text-primary" />
                  </div>
                  <div>
                    <h3 className="font-semibold">Plan & Subscription</h3>
                    <p className="text-sm text-muted-foreground">
                      View your current plan, usage meters, and invoices
                    </p>
                  </div>
                </div>
                <Button onClick={() => navigate('/brand/settings/billing')} className="gap-2">
                  Manage Billing
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            </Card>
          </TabsContent>

          {/* Security Settings */}
          <TabsContent value="security" className="space-y-6">
            {/* Mobile Number — PHONE-0904 Q1. Your OWN account number (GET/PATCH /users/me),
                distinct from the workspace contact "Phone" field in the General tab above
                (workspaces.phone, optional, blank-clears). This one is required. */}
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Mobile Number</h3>
              {phoneLoading && <p className="text-xs text-muted-foreground">Loading…</p>}
              {!phoneLoading && phoneLoadError && (
                <div className="flex items-center justify-between gap-3">
                  <p role="alert" className="text-xs text-destructive-foreground">
                    {phoneLoadError.message}
                  </p>
                  {/* F-0636 — an expired session (retryable: false) needs a fresh sign-in, not
                      another attempt at the same request that will just 401 again; every other
                      failure mode (server error, offline) is worth an actual Retry. */}
                  {phoneLoadError.retryable ? (
                    <Button type="button" variant="outline" size="sm" onClick={loadAccountPhone}>
                      Retry
                    </Button>
                  ) : (
                    <Button type="button" variant="outline" size="sm" onClick={() => navigate('/brand/login')}>
                      Sign In
                    </Button>
                  )}
                </div>
              )}
              {!phoneLoading && !phoneLoadError && (
                <div className="flex items-center justify-between p-3 border rounded-lg">
                  <div>
                    <p className="font-medium text-sm">
                      {savedPhone ? `+91 ${savedPhone}` : 'No mobile number on file'}
                    </p>
                    <p className="text-xs text-muted-foreground mt-1">
                      Required for your account. Used to reach you about deals and campaigns.
                    </p>
                  </div>
                  <Button variant="outline" size="sm" onClick={openPhoneDialog}>
                    Edit
                  </Button>
                </div>
              )}
            </Card>

            {/* Two-Factor Authentication — REMOVED (P1 security fix, Priya 2026-07-30). The
                Switch here was interactive and persisted nothing: no user-facing 2FA backend
                exists (only the separate /admin/auth realm). A brand owner flipping this on
                would believe their account was protected when it was not — a security
                misrepresentation, not a stub. Real 2FA goes to backlog with its own spec. */}
            <Card className="p-6">
              <h3 className="font-semibold mb-6">Security</h3>
              <div className="space-y-4">
                <Button variant="outline" className="w-full" onClick={() => setIsPasswordOpen(true)}>
                  Change Password
                </Button>

                {/* "View Active Sessions" removed — needs a session registry we don't have.
                    Never ship a button that 404s. */}
              </div>
            </Card>

            <Card className="p-6">
              <h3 className="font-semibold mb-6">Danger Zone</h3>
              <div className="space-y-3">
                <Button
                  variant="destructive"
                  className="w-full gap-2"
                  onClick={handleLogoutAllDevices}
                  disabled={loggingOutAllDevices}
                >
                  {loggingOutAllDevices ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <LogOut className="h-4 w-4" />
                  )}
                  {loggingOutAllDevices ? 'Logging out…' : 'Logout from All Devices'}
                </Button>
                {/* "Delete Workspace" removed — deliberately not built. Account deletion does not
                    cascade into workspaces, and workspace deletion touches escrow holds, signed
                    contracts, and GST invoices. That's a data-lifecycle project with legal
                    surface, not a settings toggle. */}
              </div>
            </Card>
          </TabsContent>

          {/* Integrations — F-0377. Both cards below existed as code with no route to them:
              StoreIntegrationSetup was imported by nothing, and the webhook signing secret had
              no issuing surface at all, which silently disabled the whole conversion pipeline.
              They belong together: the secret is only useful once a store is connected, and a
              connected store reports nothing without the secret. */}
          <TabsContent value="integrations" className="space-y-6">
            <StoreIntegrationSetup />
            <ConversionWebhookSecretCard />
          </TabsContent>
        </Tabs>


        {/* Change Password dialog */}
        <Dialog
          open={isPasswordOpen}
          onOpenChange={(open) => {
            setIsPasswordOpen(open);
            if (!open) {
              setPasswordError(null);
              setCurrentPassword('');
              setNewPassword('');
              setConfirmPassword('');
            }
          }}
        >
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Change password</DialogTitle>
              <DialogDescription>Enter your current password and choose a new one.</DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-2">
              <div>
                <Label htmlFor="current-password">Current password</Label>
                <Input
                  id="current-password"
                  type="password"
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                  className="mt-2"
                  disabled={passwordSubmitting}
                />
              </div>
              <div>
                <Label htmlFor="new-password">New password</Label>
                <Input
                  id="new-password"
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  className="mt-2"
                  disabled={passwordSubmitting}
                />
              </div>
              <div>
                <Label htmlFor="confirm-password">Confirm new password</Label>
                <Input
                  id="confirm-password"
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  className="mt-2"
                  disabled={passwordSubmitting}
                />
              </div>
              {passwordError && <p className="text-xs text-destructive-foreground">{passwordError}</p>}
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setIsPasswordOpen(false)} disabled={passwordSubmitting}>
                Cancel
              </Button>
              <Button onClick={handleChangePassword} disabled={passwordSubmitting}>
                {passwordSubmitting ? 'Saving…' : 'Change Password'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {/* Mobile Number dialog — PHONE-0904 Q1. This is users.phone_number: YOUR OWN
            account number (login/security/notifications), distinct from the "Workspace
            Phone" business-contact field on the Workspace Information tab. Copy below
            says so explicitly per PHONE-0904 re-run item 1 (Priya). */}
        <Dialog open={showPhoneDialog} onOpenChange={closePhoneDialog}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Your Mobile Number</DialogTitle>
              <DialogDescription>
                Your personal account number, used to reach you about deals and campaigns —
                not your workspace&apos;s business phone. Required; can be updated but not
                removed.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-4">
              {phoneError && (
                <p role="alert" className="text-sm text-destructive-foreground">
                  {phoneError}
                </p>
              )}
              <div className="space-y-2">
                <Label htmlFor="account-mobile">Mobile number</Label>
                <div className="flex gap-2">
                  <div className="flex h-9 items-center rounded-md border border-input bg-muted px-3 text-sm text-muted-foreground">
                    +91
                  </div>
                  <Input
                    id="account-mobile"
                    inputMode="tel"
                    placeholder="Your 10-digit Indian mobile"
                    value={phoneDraft}
                    // PHONE-0904 — allow '+' and spaces through on keystroke (don't strip them)
                    // so pasting '+91 9876543210' isn't mangled; normalizePhone() handles it in
                    // handleSavePhone above.
                    onChange={(e) => setPhoneDraft(filterPhoneInput(e.target.value))}
                    className="flex-1"
                    maxLength={17}
                    disabled={isSavingPhone}
                  />
                </div>
                <p className="text-xs text-muted-foreground">
                  Your personal account number — required for account security and deal
                  notifications, and can&apos;t be saved empty.
                </p>
              </div>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => closePhoneDialog(false)} disabled={isSavingPhone}>
                Cancel
              </Button>
              {/* Disabled on an empty normalized draft — the field cannot be cleared and saved.
                  This is the primary guard; handleSavePhone's own check is defense in depth. */}
              <Button onClick={handleSavePhone} disabled={isSavingPhone || !normalizePhone(phoneDraft)}>
                {isSavingPhone ? (
                  <>
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                    Saving…
                  </>
                ) : (
                  'Save'
                )}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </div>
  );
}
