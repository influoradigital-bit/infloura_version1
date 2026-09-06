import { useEffect, useId, useState, type FormEvent, type ReactElement } from 'react';
import { CheckCircle2, Loader2, Lock } from 'lucide-react';

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
    const payload: FestivalEnquiryPayload = {
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
      const result = await festivalEnquiry.submit(payload);
      setDone(
        result.message ??
          'Thanks — we have your enquiry. Our team will be in touch within 2 working days.',
      );
      form.reset();
    } catch (e) {
      setError(
        e instanceof Error && e.message
          ? e.message
          : 'Something went wrong sending that. Please try again, or email info@influora.in.',
      );
    } finally {
      setSubmitting(false);
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
