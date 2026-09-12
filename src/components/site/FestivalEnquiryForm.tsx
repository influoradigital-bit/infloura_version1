import { useEffect, useId, useState, type FormEvent, type ReactElement } from 'react';
import { CheckCircle2, Loader2, Lock, MailCheck } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { festivalEnquiry, type FestivalEnquiryPayload } from '@/lib/api';
import { FESTIVAL_EDITION, BRAND_TIERS } from '@/content/festival-box';

export type FestivalAudience = 'BRAND' | 'CREATOR';

export interface FestivalEnquiryFormProps {
  /** Which half of the page toggle is active. Decides the fields AND the posted `type`. */
  audience: FestivalAudience;
  /** Pre-selects a tier when the visitor arrived by clicking a specific tier's button. */
  presetTier?: string;
}

/**
 * The enquiry form on /festival-box (T-FESTIVALBOX-0905) — the only thing on the marketing site
 * that writes to the database.
 *
 * <p>Two things here are load-bearing and easy to "tidy" away:
 *
 * 1. `honeypot` — a real input, labelled, in the DOM, hidden from sight AND from assistive tech,
 *    with `tabIndex={-1}` and `autoComplete="off"` so no human and no password manager ever fills
 *    it. The backend treats any value in it as a bot and answers 200 while storing nothing. Deleting
 *    this field silently removes the cheapest spam control on a public endpoint.
 *
 * 2. UTM capture reads `window.location.search` on mount. It is how marketing attributes an enquiry
 *    to the campaign that produced it; the values are length-bounded server-side.
 *
 * Field-level problems render inline and API failures render in the form's error region rather than
 * a toast, per this codebase's convention (toast = API errors elsewhere, but a marketing form the
 * visitor is actively reading should not throw its only failure message into a corner that
 * auto-dismisses).
 */
export function FestivalEnquiryForm({ audience, presetTier }: FestivalEnquiryFormProps): ReactElement {
  const formId = useId();
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [utm, setUtm] = useState<{ source?: string; medium?: string; campaign?: string }>({});
  // Step two. The filled-in payload is parked here while the visitor fetches the code from their
  // inbox, so the form's own fields can stay mounted-but-hidden and nothing they typed is lost if
  // the code is wrong. `otp` is omitted from what we park precisely because it is the one thing
  // still to be collected.
  const [pending, setPending] = useState<Omit<FestivalEnquiryPayload, 'otp'> | null>(null);
  const [code, setCode] = useState('');
  const [resending, setResending] = useState(false);

  useEffect(() => {
    // Guarded for the prerenderer, which executes this module without a browser `window`.
    if (typeof window === 'undefined') return;
    const params = new URLSearchParams(window.location.search);
    setUtm({
      source: params.get('utm_source') ?? undefined,
      medium: params.get('utm_medium') ?? undefined,
      campaign: params.get('utm_campaign') ?? undefined,
    });
  }, []);

  // Switching audience must clear a previous result: leaving a brand "thank you" on screen while
  // the creator form is showing tells the visitor their application went through when it did not.
  useEffect(() => {
    setDone(null);
    setError(null);
  }, [audience]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;

    const form = event.currentTarget;
    const data = new FormData(form);
    const value = (name: string) => {
      const raw = data.get(name);
      const trimmed = typeof raw === 'string' ? raw.trim() : '';
      return trimmed === '' ? undefined : trimmed;
    };

    const rawFollowers = value('followers');
    // Omit<..., 'otp'> deliberately: at this point the visitor has not seen a code yet. The `otp`
    // is added in confirmCode(), which is the only place it can honestly exist.
    const payload: Omit<FestivalEnquiryPayload, 'otp'> = {
      type: audience,
      edition: FESTIVAL_EDITION,
      name: value('name') ?? '',
      email: value('email') ?? '',
      phone: value('phone'),
      company: audience === 'BRAND' ? value('company') : undefined,
      website: audience === 'BRAND' ? value('website') : undefined,
      tier: audience === 'BRAND' ? value('tier') : undefined,
      productCategory: audience === 'BRAND' ? value('productCategory') : undefined,
      instagramHandle: audience === 'CREATOR' ? value('instagramHandle') : undefined,
      // Digits only: the server rejects a negative or implausible count, and sending "" or NaN
      // would fail bean validation with a message the visitor cannot act on.
      followers:
        audience === 'CREATOR' && rawFollowers && /^\d+$/.test(rawFollowers)
          ? Number(rawFollowers)
          : undefined,
      city: audience === 'CREATOR' ? value('city') : undefined,
      message: value('message'),
      utmSource: utm.source,
      utmMedium: utm.medium,
      utmCampaign: utm.campaign,
      honeypot: value('company_website_confirm'),
    };

    setSubmitting(true);
    setError(null);
    try {
      // Step one of two. Nothing is stored yet: this only mails a code to the address typed above,
      // and the enquiry is POSTed in confirmCode() once that code comes back. The server refuses a
      // submission without it, so a visitor who mistypes their address — the whole reason this step
      // exists — never reaches a row rather than reaching an unreachable one.
      await festivalEnquiry.sendOtp(payload.email);
      setPending(payload);
      setCode('');
    } catch (e) {
      setError(
        e instanceof Error && e.message
          ? e.message
          : 'We could not send the code. Please try again, or email info@influora.in.',
      );
    } finally {
      setSubmitting(false);
    }
  }

  /** Step two: the code came back, so send the enquiry we parked. */
  async function confirmCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting || !pending) return;

    setSubmitting(true);
    setError(null);
    try {
      const result = await festivalEnquiry.submit({ ...pending, otp: code.trim() });
      setDone(
        result.message ??
          'Thanks — we have your enquiry. Our team will be in touch within 2 working days.',
      );
      setPending(null);
      setCode('');
    } catch (e) {
      // Stay on this panel on a wrong code — the server bounds the guesses, and dropping the
      // visitor back to a blank form would lose everything they typed.
      setError(
        e instanceof Error && e.message
          ? e.message
          : 'That code did not work. Check it and try again.',
      );
    } finally {
      setSubmitting(false);
    }
  }

  async function resendCode() {
    if (!pending || resending) return;
    setResending(true);
    setError(null);
    try {
      await festivalEnquiry.sendOtp(pending.email);
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : 'Could not resend the code.');
    } finally {
      setResending(false);
    }
  }

  if (done) {
    return (
      <div
        className="rounded-2xl border border-border/60 bg-card/50 p-8 text-center"
        role="status"
        aria-live="polite"
      >
        <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-accent">
          <CheckCircle2 className="h-6 w-6 text-accent-foreground" aria-hidden="true" />
        </span>
        <h3 className="mt-4 text-xl font-semibold">
          {audience === 'BRAND' ? 'Enquiry received' : 'Application received'}
        </h3>
        <p className="mx-auto mt-2 max-w-md text-sm text-muted-foreground">{done}</p>
        <Button variant="outline" className="mt-6" onClick={() => setDone(null)}>
          Send another
        </Button>
      </div>
    );
  }

  if (pending) {
    return (
      <form
        onSubmit={confirmCode}
        className="rounded-2xl border border-border/60 bg-card/50 p-6 sm:p-8"
        noValidate
      >
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-accent">
          <MailCheck className="h-6 w-6 text-accent-foreground" aria-hidden="true" />
        </span>
        <h3 className="mt-4 text-xl font-semibold">Confirm your email</h3>
        <p className="mt-2 text-sm text-muted-foreground">
          We sent a 6-digit code to <span className="font-medium text-foreground">{pending.email}</span>.
          Enter it to send your {audience === 'BRAND' ? 'enquiry' : 'application'}.
        </p>

        <div className="mt-5 max-w-xs">
          <Label htmlFor={`${formId}-otp`}>6-digit code</Label>
          <Input
            id={`${formId}-otp`}
            name="otp"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder="000000"
            className="mt-1.5 tracking-[0.4em]"
            required
          />
        </div>

        {error ? (
          <p className="mt-4 text-sm text-destructive-foreground" role="alert">
            {error}
          </p>
        ) : null}

        <div className="mt-6 flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={submitting || code.length < 6}>
            {submitting ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                Sending…
              </>
            ) : (
              `Send ${audience === 'BRAND' ? 'enquiry' : 'application'}`
            )}
          </Button>
          <Button type="button" variant="outline" onClick={resendCode} disabled={resending}>
            {resending ? 'Resending…' : 'Resend code'}
          </Button>
          {/* Back, not a reset: `pending` still holds everything they typed, so returning to the
              form must not clear it — a visitor who mistyped one character of their email should
              not have to refill the whole thing. */}
          <button
            type="button"
            className="text-sm text-muted-foreground underline underline-offset-4"
            onClick={() => {
              setPending(null);
              setError(null);
            }}
          >
            Use a different email
          </button>
        </div>
      </form>
    );
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="rounded-2xl border border-border/60 bg-card/50 p-6 sm:p-8"
      noValidate
    >
      <div className="grid gap-5 sm:grid-cols-2">
        <Field id={`${formId}-name`} name="name" label="Your name" required autoComplete="name" />
        <Field
          id={`${formId}-email`}
          name="email"
          label="Work email"
          type="email"
          required
          autoComplete="email"
        />

        {audience === 'BRAND' ? (
          <>
            <Field
              id={`${formId}-company`}
              name="company"
              label="Brand / company"
              required
              autoComplete="organization"
            />
            <Field
              id={`${formId}-website`}
              name="website"
              label="Website"
              placeholder="yourbrand.in"
              autoComplete="url"
            />
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`${formId}-tier`}>Tier you're interested in</Label>
              {/* A native select, not the Radix one: this form is prerendered and must work for a
                  visitor whose JS is still loading, and a native control needs no listbox portal. */}
              <select
                id={`${formId}-tier`}
                name="tier"
                defaultValue={presetTier ?? 'UNDECIDED'}
                className="h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 md:text-sm"
              >
                {BRAND_TIERS.map((tier) => (
                  <option key={tier.value} value={tier.value}>
                    {tier.name} — {tier.price}
                  </option>
                ))}
                <option value="UNDECIDED">Not sure yet — let's discuss</option>
              </select>
            </div>
            <Field
              id={`${formId}-category`}
              name="productCategory"
              label="Product category"
              placeholder="Apparel, beauty, jewellery…"
            />
          </>
        ) : (
          <>
            <Field
              id={`${formId}-handle`}
              name="instagramHandle"
              label="Instagram handle"
              required
              placeholder="@yourhandle"
            />
            <Field
              id={`${formId}-followers`}
              name="followers"
              label="Followers"
              type="text"
              inputMode="numeric"
              placeholder="25000"
            />
            <Field id={`${formId}-city`} name="city" label="City" placeholder="Mumbai" />
          </>
        )}

        <Field
          id={`${formId}-phone`}
          name="phone"
          label="Phone (optional)"
          type="tel"
          autoComplete="tel"
        />
      </div>

      <div className="mt-5 flex flex-col gap-1.5">
        <Label htmlFor={`${formId}-message`}>
          {audience === 'BRAND' ? 'Anything we should know?' : 'Tell us about your content'}
        </Label>
        <Textarea
          id={`${formId}-message`}
          name="message"
          rows={4}
          maxLength={2000}
          placeholder={
            audience === 'BRAND'
              ? 'What you sell, what a good outcome looks like, and any dates you need to hit.'
              : 'What you make, the brands you have worked with, and why this edition fits you.'
          }
        />
      </div>

      {/*
        Honeypot. Hidden from sight and from assistive tech, unfocusable, and never autofilled — so
        only a bot that fills every field reaches it. The server answers those with the same 200 a
        human gets and stores nothing. Do not remove, and do not make it visible.
      */}
      <div aria-hidden="true" className="pointer-events-none absolute h-0 w-0 overflow-hidden opacity-0">
        <label htmlFor={`${formId}-hp`}>Do not fill this in</label>
        <input
          id={`${formId}-hp`}
          name="company_website_confirm"
          type="text"
          tabIndex={-1}
          autoComplete="off"
        />
      </div>

      {error && (
        <p
          className="mt-4 rounded-lg border border-border/60 bg-muted px-3 py-2 text-sm text-destructive-foreground"
          role="alert"
        >
          {error}
        </p>
      )}

      <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <Button
          type="submit"
          size="lg"
          disabled={submitting}
          className="bg-accent-foreground text-white hover:bg-accent-foreground/90"
        >
          {submitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />}
          {audience === 'BRAND' ? 'Request the Festival Box deck' : 'Apply for the roster'}
        </Button>
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Lock className="h-3 w-3" aria-hidden="true" />
          We use this only to reply about the edition. No list, no resale.
        </p>
      </div>
    </form>
  );
}

interface FieldProps {
  id: string;
  name: string;
  label: string;
  type?: string;
  required?: boolean;
  placeholder?: string;
  autoComplete?: string;
  inputMode?: 'numeric' | 'text';
}

/** One labelled input. Every field is labelled — a placeholder is not a label. */
function Field({ id, name, label, type = 'text', required, ...rest }: FieldProps): ReactElement {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>
        {label}
        {required && (
          <span className="ml-0.5 text-muted-foreground" aria-hidden="true">
            *
          </span>
        )}
      </Label>
      <Input id={id} name={name} type={type} required={required} {...rest} />
    </div>
  );
}
