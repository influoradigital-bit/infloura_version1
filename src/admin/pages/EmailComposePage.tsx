/**
 * INFLUORA ADMIN PANEL — Custom Email Compose
 * Owner: Ananya (Frontend)
 * Reference: T-ADMINMAIL-0903 (.proof-os/tasks/T-ADMINMAIL-0903/SPEC.md) — fixed API contract,
 * built against the SPEC while `POST /admin/emails/custom/preview` and `/custom/send` are being
 * built in parallel (Vikram, AdminEmailController). Distinct from the legacy `sendBulk`, which
 * stays 501 and disabled in EmailQueuePage.tsx — this path carries the five controls the SPEC
 * requires (rate limit + cap + audit are server-side; preview/confirm and unsubscribe-skip
 * surface here), so it is wired for real.
 *
 * Mounted at /admin/emails/compose by src/pages/admin-console.tsx.
 *
 * Preview HTML rendering: the returned `html` is a complete branded document
 * (EmailTemplateRegistry's `wrapHtml` shell, per SPEC "Layout"), rendered into a sandboxed
 * `<iframe srcDoc>` with `sandbox=""` (no scripts, no same-origin, no forms, no top navigation).
 * The server HTML-escapes personalization values on the way in, but the iframe sandbox is
 * defense-in-depth regardless — an admin-composed body reaching the DOM never goes through
 * `dangerouslySetInnerHTML` in the admin app's own document.
 */

import { useMemo, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  Eye,
  Loader2,
  Mail,
  RefreshCw,
  Send,
  Sparkles,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useToast } from '@/hooks/use-toast';
import { emailApi } from '../services/api-contracts';
import type { AdminCustomEmailAudience, AdminCustomEmailPreviewResponse } from '../types/admin.types';

// ============================================
// PERSONALIZATION TOKENS (SPEC "Personalization")
// ============================================

type ComposeField = 'subject' | 'body';

const TOKENS: { token: string; label: string; fallback: string }[] = [
  { token: '{{first_name}}', label: 'First name', fallback: 'no first name on file → "there"' },
  { token: '{{name}}', label: 'Display name', fallback: 'no display name → first name → "there"' },
  { token: '{{email}}', label: 'Email', fallback: 'always present — no fallback' },
];

// ============================================
// SMALL PRESENTATIONAL HELPERS
// ============================================

function ErrorNotice({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 p-3 text-sm text-foreground">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive-foreground" aria-hidden="true" />
      <p>{message}</p>
    </div>
  );
}

function Field({ label, htmlFor, hint, error, children }: {
  label: string;
  htmlFor: string;
  hint?: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
      {error ? (
        <p className="text-xs text-destructive-foreground">{error}</p>
      ) : hint ? (
        <p className="text-xs text-muted-foreground">{hint}</p>
      ) : null}
    </div>
  );
}

// ============================================
// PAGE
// ============================================

export default function EmailComposePage() {
  const { toast } = useToast();

  // --- Compose fields ---
  const [subject, setSubject] = useState('');
  const [bodyText, setBodyText] = useState('');
  const [ctaLabel, setCtaLabel] = useState('');
  const [ctaUrl, setCtaUrl] = useState('');
  const [userType, setUserType] = useState<AdminCustomEmailAudience['userType']>('ALL');
  const [onlyVerified, setOnlyVerified] = useState(false);
  const [registeredWithinDays, setRegisteredWithinDays] = useState('');

  // --- Token-insert caret tracking ---
  // `activeField` drives the "Insert into subject/body" label — it's state, not a bare ref read
  // during render, so the label actually re-renders when focus moves. `lastFieldRef` /
  // `lastCursorRef` back it for `insertToken`, which needs the caret position synchronously in a
  // click handler and has no reason to wait on a state update for that.
  const subjectRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const lastFieldRef = useRef<ComposeField>('body');
  const lastCursorRef = useRef<{ start: number; end: number }>({ start: 0, end: 0 });
  const [activeField, setActiveField] = useState<ComposeField>('body');

  // --- Preview state ---
  const [preview, setPreview] = useState<AdminCustomEmailPreviewResponse | null>(null);
  const [previewedKey, setPreviewedKey] = useState<string | null>(null);
  const [isPreviewing, setIsPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  // --- Send / confirm state ---
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [isSending, setIsSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [recipientCountChanged, setRecipientCountChanged] = useState(false);
  const [sendResult, setSendResult] = useState<{
    campaignId: string;
    queued: number;
    skippedUnsubscribed: number;
  } | null>(null);

  const audience: AdminCustomEmailAudience = useMemo(
    () => ({
      userType,
      onlyVerified,
      registeredWithinDays: registeredWithinDays.trim() === '' ? null : Number(registeredWithinDays),
    }),
    [userType, onlyVerified, registeredWithinDays],
  );

  // CTA label and URL travel together — either both set or both empty.
  const ctaLabelFilled = ctaLabel.trim().length > 0;
  const ctaUrlFilled = ctaUrl.trim().length > 0;
  const ctaMismatched = ctaLabelFilled !== ctaUrlFilled;

  const subjectValid = subject.trim().length > 0;
  const bodyValid = bodyText.trim().length > 0;
  const canPreview = subjectValid && bodyValid && !ctaMismatched && !isPreviewing;

  const currentKey = useMemo(
    () => JSON.stringify({ subject, bodyText, ctaLabel: ctaLabel.trim(), ctaUrl: ctaUrl.trim(), audience }),
    [subject, bodyText, ctaLabel, ctaUrl, audience],
  );
  // A field changed since the last successful preview — the recipient count and rendered HTML on
  // screen no longer necessarily match what a send would do. Send stays gated on a fresh preview
  // (SPEC control #3 is enforced server-side too, but the UI shouldn't invite a doomed attempt).
  const previewIsStale = preview !== null && previewedKey !== currentKey;

  function handleSubjectSelect() {
    lastFieldRef.current = 'subject';
    setActiveField('subject');
    const el = subjectRef.current;
    if (el) {
      lastCursorRef.current = {
        start: el.selectionStart ?? el.value.length,
        end: el.selectionEnd ?? el.value.length,
      };
    }
  }

  function handleBodySelect() {
    lastFieldRef.current = 'body';
    setActiveField('body');
    const el = bodyRef.current;
    if (el) {
      lastCursorRef.current = {
        start: el.selectionStart ?? el.value.length,
        end: el.selectionEnd ?? el.value.length,
      };
    }
  }

  function insertToken(token: string) {
    const field = lastFieldRef.current;
    const { start, end } = lastCursorRef.current;

    if (field === 'subject') {
      const next = subject.slice(0, start) + token + subject.slice(end);
      setSubject(next);
      const pos = start + token.length;
      requestAnimationFrame(() => {
        subjectRef.current?.focus();
        subjectRef.current?.setSelectionRange(pos, pos);
      });
      lastCursorRef.current = { start: pos, end: pos };
    } else {
      const next = bodyText.slice(0, start) + token + bodyText.slice(end);
      setBodyText(next);
      const pos = start + token.length;
      requestAnimationFrame(() => {
        bodyRef.current?.focus();
        bodyRef.current?.setSelectionRange(pos, pos);
      });
      lastCursorRef.current = { start: pos, end: pos };
    }
  }

  function buildRequestBase() {
    return {
      subject,
      bodyText,
      ctaLabel: ctaLabelFilled ? ctaLabel.trim() : undefined,
      ctaUrl: ctaUrlFilled ? ctaUrl.trim() : undefined,
      audience,
    };
  }

  async function handlePreview() {
    setIsPreviewing(true);
    setPreviewError(null);
    setSendError(null);
    setRecipientCountChanged(false);

    const res = await emailApi.previewCustom(buildRequestBase());
    setIsPreviewing(false);

    if (!res.success || !res.data) {
      // Includes the unknown-{{token}} 400 (SPEC "Personalization") — shown inline, next to the
      // fields it's about, not as a toast (field validation convention).
      setPreviewError(res.error ?? 'Failed to generate preview.');
      setPreview(null);
      setPreviewedKey(null);
      return;
    }

    setPreview(res.data);
    setPreviewedKey(currentKey);
  }

  function openConfirm() {
    if (!preview || previewIsStale) return;
    setSendError(null);
    setConfirmOpen(true);
  }

  async function handleConfirmSend() {
    if (!preview) return;
    setIsSending(true);
    setSendError(null);

    const res = await emailApi.sendCustom({
      ...buildRequestBase(),
      confirmRecipientCount: preview.recipientCount,
    });
    setIsSending(false);

    if (!res.success || !res.data) {
      if (res.recipientCountChanged) {
        // 409 RECIPIENT_COUNT_CHANGED — a real, expected path (SPEC), not a generic error toast.
        // The audience shifted under the admin; force a fresh preview before letting them retry.
        setConfirmOpen(false);
        setRecipientCountChanged(true);
        setPreview(null);
        setPreviewedKey(null);
        return;
      }
      setConfirmOpen(false);
      const message = res.error ?? 'Failed to send.';
      setSendError(message);
      toast({ title: 'Send failed', description: message, variant: 'destructive' });
      return;
    }

    setConfirmOpen(false);
    setPreview(null);
    setPreviewedKey(null);
    setSendResult(res.data);
    toast({
      title: 'Email queued',
      description: `${res.data.queued} queued · ${res.data.skippedUnsubscribed} skipped (unsubscribed).`,
    });
  }

  function startNewSend() {
    setSubject('');
    setBodyText('');
    setCtaLabel('');
    setCtaUrl('');
    setUserType('ALL');
    setOnlyVerified(false);
    setRegisteredWithinDays('');
    setPreview(null);
    setPreviewedKey(null);
    setPreviewError(null);
    setSendError(null);
    setRecipientCountChanged(false);
    setSendResult(null);
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <Link
          to="/admin/emails"
          className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-3.5" aria-hidden="true" />
          Back to Email Queue
        </Link>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <Mail className="size-6" aria-hidden="true" />
          Compose Custom Email
        </h2>
        <p className="text-sm text-muted-foreground">
          Preview against the live recipient list, then confirm the exact count before sending.
        </p>
      </div>

      {sendResult && (
        <Card className="gap-2 border-success-foreground/30 bg-card p-4">
          <p className="text-sm font-semibold text-foreground">Last send queued</p>
          <p className="text-sm text-muted-foreground">
            Campaign <span className="font-mono text-foreground">{sendResult.campaignId}</span> —{' '}
            <span className="font-semibold text-foreground">{sendResult.queued}</span> queued,{' '}
            <span className="font-semibold text-foreground">{sendResult.skippedUnsubscribed}</span> skipped
            (unsubscribed).
          </p>
          <Button type="button" variant="outline" size="sm" className="w-fit" onClick={startNewSend}>
            Compose another
          </Button>
        </Card>
      )}

      {recipientCountChanged && (
        <div className="flex items-start gap-2 rounded-lg border border-warning-foreground/30 bg-warning-foreground/10 p-3 text-sm text-foreground">
          <RefreshCw className="mt-0.5 size-4 shrink-0 text-warning-foreground" aria-hidden="true" />
          <p>
            The audience changed since you last previewed — the recipient count no longer matches, so the
            send was refused. Preview again to see the current count before sending.
          </p>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* ===================== COMPOSE ===================== */}
        <Card className="gap-4 p-5">
          <h3 className="text-sm font-semibold text-foreground">Message</h3>

          <Field label="Subject" htmlFor="compose-subject" error={!subjectValid && subject.length > 0 ? 'Subject is required.' : undefined}>
            <Input
              id="compose-subject"
              ref={subjectRef}
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              onSelect={handleSubjectSelect}
              onFocus={handleSubjectSelect}
              placeholder="e.g. Hey {{first_name}}, a quick update from Influora"
              aria-invalid={!subjectValid && subject.length > 0}
            />
          </Field>

          <Field
            label="Body"
            htmlFor="compose-body"
            hint="Plain text — blank-line-separated paragraphs render as paragraphs in the branded shell."
            error={!bodyValid && bodyText.length > 0 ? 'Body is required.' : undefined}
          >
            <Textarea
              id="compose-body"
              ref={bodyRef}
              value={bodyText}
              onChange={(e) => setBodyText(e.target.value)}
              onSelect={handleBodySelect}
              onFocus={handleBodySelect}
              placeholder={'Hi {{first_name}},\n\nWrite your update here as plain paragraphs.\n\nThanks,\nThe Influora team'}
              className="min-h-40"
              aria-invalid={!bodyValid && bodyText.length > 0}
            />
          </Field>

          <div className="flex flex-col gap-1.5">
            <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
              <Sparkles className="size-3.5" aria-hidden="true" />
              Insert into {activeField}
            </div>
            <div className="flex flex-wrap gap-2">
              {TOKENS.map((t) => (
                <Button
                  key={t.token}
                  type="button"
                  variant="outline"
                  size="sm"
                  title={`${t.label} — ${t.fallback}`}
                  onClick={() => insertToken(t.token)}
                >
                  {t.token}
                </Button>
              ))}
            </div>
            <ul className="flex flex-col gap-0.5 text-xs text-muted-foreground">
              {TOKENS.map((t) => (
                <li key={t.token}>
                  <span className="font-mono">{t.token}</span> — {t.fallback}
                </li>
              ))}
            </ul>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="CTA button label (optional)" htmlFor="compose-cta-label" error={ctaMismatched && ctaUrlFilled ? 'Add a label for the CTA URL below.' : undefined}>
              <Input
                id="compose-cta-label"
                value={ctaLabel}
                onChange={(e) => setCtaLabel(e.target.value)}
                placeholder="View my dashboard"
                aria-invalid={ctaMismatched && ctaUrlFilled}
              />
            </Field>
            <Field label="CTA URL (optional)" htmlFor="compose-cta-url" error={ctaMismatched && ctaLabelFilled ? 'Add the URL this button should open.' : undefined}>
              <Input
                id="compose-cta-url"
                type="url"
                value={ctaUrl}
                onChange={(e) => setCtaUrl(e.target.value)}
                placeholder="https://app.influora.in/..."
                aria-invalid={ctaMismatched && ctaLabelFilled}
              />
            </Field>
          </div>
        </Card>

        {/* ===================== AUDIENCE ===================== */}
        <Card className="gap-4 p-5">
          <h3 className="text-sm font-semibold text-foreground">Audience</h3>

          <div className="flex flex-col gap-1.5">
            <Label>Send to</Label>
            <RadioGroup value={userType} onValueChange={(v) => setUserType(v as AdminCustomEmailAudience['userType'])}>
              <div className="flex items-center gap-2">
                <RadioGroupItem value="ALL" id="audience-all" />
                <Label htmlFor="audience-all" className="font-normal">All users</Label>
              </div>
              <div className="flex items-center gap-2">
                <RadioGroupItem value="CREATOR" id="audience-creator" />
                <Label htmlFor="audience-creator" className="font-normal">Creators only</Label>
              </div>
              <div className="flex items-center gap-2">
                <RadioGroupItem value="BRAND" id="audience-brand" />
                <Label htmlFor="audience-brand" className="font-normal">Brands only</Label>
              </div>
            </RadioGroup>
          </div>

          <div className="flex items-center gap-2">
            <Checkbox
              id="audience-verified"
              checked={onlyVerified}
              onCheckedChange={(checked) => setOnlyVerified(checked === true)}
            />
            <Label htmlFor="audience-verified" className="font-normal">Only verified accounts</Label>
          </div>

          <Field
            label="Registered within (days, optional)"
            htmlFor="audience-registered-days"
            hint="Leave blank to include users regardless of when they registered."
          >
            <Input
              id="audience-registered-days"
              type="number"
              min={1}
              inputMode="numeric"
              value={registeredWithinDays}
              onChange={(e) => setRegisteredWithinDays(e.target.value)}
              placeholder="e.g. 30"
              className="max-w-40"
            />
          </Field>

          <div className="mt-2 flex flex-col gap-3 border-t border-border pt-4">
            {previewError && <ErrorNotice message={previewError} />}
            {sendError && <ErrorNotice message={sendError} />}

            <div className="flex flex-wrap items-center gap-2">
              <Button type="button" variant="outline" onClick={() => void handlePreview()} disabled={!canPreview}>
                {isPreviewing ? (
                  <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                ) : (
                  <Eye aria-hidden="true" />
                )}
                {preview ? 'Preview again' : 'Preview'}
              </Button>

              <Button type="button" onClick={openConfirm} disabled={!preview || previewIsStale || isSending}>
                <Send aria-hidden="true" />
                Review &amp; send
              </Button>
            </div>

            {preview && previewIsStale && (
              <p className="text-xs text-muted-foreground">
                Fields changed since this preview — preview again before sending.
              </p>
            )}

            {preview && !previewIsStale && (
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Badge variant="outline">{preview.recipientCount.toLocaleString('en-IN')} recipients</Badge>
                {preview.capped && (
                  <Badge variant="outline" className="border-warning-foreground/40 text-warning-foreground">
                    Capped at {preview.cap.toLocaleString('en-IN')}
                  </Badge>
                )}
                {preview.sampleRecipientEmail && (
                  <span className="text-muted-foreground">Sample: {preview.sampleRecipientEmail}</span>
                )}
              </div>
            )}
          </div>
        </Card>
      </div>

      {/* ===================== HTML PREVIEW ===================== */}
      {preview && (
        <Card className="gap-3 p-5">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-foreground">Rendered preview</h3>
            <span className="text-xs text-muted-foreground">Subject: {preview.subject}</span>
          </div>
          {/*
            Sandboxed, scriptless iframe — see file header for the full rationale. `srcDoc` never
            touches this document's DOM the way `dangerouslySetInnerHTML` would; `sandbox=""`
            (no `allow-scripts`, no `allow-same-origin`) means even a script tag in the payload
            cannot execute, read `localStorage`, or reach the parent frame.
          */}
          <iframe
            title="Email preview"
            srcDoc={preview.html}
            sandbox=""
            className="h-[520px] w-full rounded-lg border border-border bg-white"
          />
        </Card>
      )}

      {/* ===================== CONFIRM DIALOG ===================== */}
      <Dialog open={confirmOpen} onOpenChange={(open) => !isSending && setConfirmOpen(open)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Send className="size-4.5 shrink-0 text-muted-foreground" aria-hidden="true" />
              Confirm send
            </DialogTitle>
            <DialogDescription>
              This will enqueue one email per recipient. It cannot be recalled once queued.
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-3">
            <div className="rounded-lg border border-border bg-muted/40 p-3 text-sm">
              <p className="text-foreground">
                Sending to{' '}
                <span className="font-semibold">{preview?.recipientCount.toLocaleString('en-IN')} recipients</span>
                {preview?.capped ? ` (capped at ${preview.cap.toLocaleString('en-IN')})` : ''}.
              </p>
              <p className="mt-1 text-muted-foreground">Subject: {preview?.subject}</p>
            </div>
            {sendError && <ErrorNotice message={sendError} />}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setConfirmOpen(false)} disabled={isSending}>
              Cancel
            </Button>
            <Button type="button" onClick={() => void handleConfirmSend()} disabled={isSending}>
              {isSending ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : <Send aria-hidden="true" />}
              {isSending ? 'Sending…' : `Confirm — send to ${preview?.recipientCount.toLocaleString('en-IN') ?? 0}`}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
