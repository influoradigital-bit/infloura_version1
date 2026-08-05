/**
 * KycIdentityForm — creator PAN / Aadhaar-last-4 / selfie KYC capture (C32 / KYC)
 * ----------------------------------------------------------------------------
 * Product design defers KYC to first withdrawal, but the capture surface lives
 * in Creator Settings so a creator can complete it ahead of time. This form
 * submits to the real `POST /onboarding/creator/kyc` endpoint via
 * `useCreatorKyc()` and renders the returned `kycStatus` (PENDING / VERIFIED)
 * on success, or the server's error on failure.
 *
 * The selfie is uploaded first via `uploads.upload(file, 'creator')` (→ `{ url }`),
 * and the resulting URL is submitted as `selfieUrl`. Validation patterns mirror
 * the backend `OnboardingDtos.KycRequest` `@Pattern` regexes (PAN + 4-digit
 * Aadhaar tail).
 */

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { AlertTriangle, CheckCircle2, Loader2, Upload } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form';
import { ApiError, uploads } from '@/lib/api';
import { useCreatorKyc } from '@/hooks/creator/useCreatorKyc';

const PAN_PATTERN = /^[A-Z]{5}[0-9]{4}[A-Z]{1}$/;
const AADHAAR_LAST4_PATTERN = /^[0-9]{4}$/;

const kycSchema = z.object({
  pan: z
    .string()
    .trim()
    .toUpperCase()
    .refine((v) => PAN_PATTERN.test(v), {
      message: 'Invalid PAN format (e.g. ABCDE1234F)',
    }),
  aadhaarLast4: z
    .string()
    .trim()
    .refine((v) => AADHAAR_LAST4_PATTERN.test(v), {
      message: 'Enter the last 4 digits of your Aadhaar',
    }),
});

type KycFormValues = z.infer<typeof kycSchema>;

export interface KycIdentityFormProps {
  /** Called after a submit attempt completes, success or not — lets the caller close a dialog. */
  onSubmitted?: () => void;
}

export function KycIdentityForm({ onSubmitted }: KycIdentityFormProps) {
  const { submitting, saved, error, submit, reset: resetSubmitState } = useCreatorKyc();

  const [selfieUrl, setSelfieUrl] = React.useState<string | null>(null);
  const [selfieName, setSelfieName] = React.useState<string | null>(null);
  const [uploading, setUploading] = React.useState(false);
  const [selfieError, setSelfieError] = React.useState<string | null>(null);

  const form = useForm<KycFormValues>({
    resolver: zodResolver(kycSchema),
    defaultValues: { pan: '', aadhaarLast4: '' },
  });

  const handleSelfie = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setSelfieError(null);
    setUploading(true);
    try {
      const { url } = await uploads.upload(file, 'creator');
      setSelfieUrl(url);
      setSelfieName(file.name);
    } catch (err) {
      setSelfieUrl(null);
      setSelfieName(null);
      setSelfieError(err instanceof ApiError ? err.message : 'Failed to upload selfie');
    } finally {
      setUploading(false);
    }
  };

  const onSubmit = async (values: KycFormValues) => {
    if (!selfieUrl) {
      setSelfieError('Upload a selfie to continue');
      return;
    }
    await submit({
      pan: values.pan,
      aadhaarLast4: values.aadhaarLast4,
      selfieUrl,
    });
    onSubmitted?.();
  };

  const clearAll = () => {
    form.reset();
    resetSubmitState();
    setSelfieUrl(null);
    setSelfieName(null);
    setSelfieError(null);
  };

  if (saved) {
    return (
      <div className="space-y-4">
        <Alert id="kyc-status">
          <CheckCircle2 className="text-stage-approved-fg" aria-hidden="true" />
          <AlertTitle>KYC submitted</AlertTitle>
          <AlertDescription>
            <div className="flex justify-between mt-1">
              <span className="text-muted-foreground">Status</span>
              <span className="font-medium">
                {saved.kycStatus === 'VERIFIED' ? 'Verified' : 'Pending review'}
              </span>
            </div>
          </AlertDescription>
        </Alert>
        <div className="flex justify-end">
          <Button type="button" variant="outline" onClick={clearAll}>
            Edit
          </Button>
        </div>
      </div>
    );
  }

  return (
    <Form {...form}>
      <form
        onSubmit={form.handleSubmit(onSubmit)}
        className="space-y-4"
        aria-describedby={error ? 'kyc-status' : undefined}
      >
        <p className="text-sm text-muted-foreground">
          Verify your identity so payouts can be released. We ask for your PAN, the last four
          digits of your Aadhaar, and a selfie. This is required before your first withdrawal.
        </p>

        <FormField
          control={form.control}
          name="pan"
          render={({ field }) => (
            <FormItem>
              <FormLabel>PAN</FormLabel>
              <FormControl>
                <Input
                  {...field}
                  placeholder="ABCDE1234F"
                  maxLength={10}
                  autoCapitalize="characters"
                  autoComplete="off"
                  className="uppercase"
                />
              </FormControl>
              <FormDescription>10-character Permanent Account Number.</FormDescription>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="aadhaarLast4"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Aadhaar (last 4 digits)</FormLabel>
              <FormControl>
                <Input
                  {...field}
                  placeholder="1234"
                  maxLength={4}
                  inputMode="numeric"
                  autoComplete="off"
                />
              </FormControl>
              <FormDescription>
                Only the last 4 digits — we never store your full Aadhaar number.
              </FormDescription>
              <FormMessage />
            </FormItem>
          )}
        />

        <div className="space-y-2">
          <FormLabel htmlFor="kyc-selfie">Selfie</FormLabel>
          <div className="flex items-center gap-3">
            <Button
              type="button"
              variant="outline"
              disabled={uploading}
              onClick={() => document.getElementById('kyc-selfie')?.click()}
            >
              {uploading ? (
                <Loader2 className="animate-spin" aria-hidden="true" />
              ) : (
                <Upload aria-hidden="true" />
              )}
              {selfieUrl ? 'Replace selfie' : 'Upload selfie'}
            </Button>
            {selfieName && (
              <span className="text-sm text-muted-foreground truncate max-w-[12rem]">
                {selfieName}
              </span>
            )}
          </div>
          <input
            id="kyc-selfie"
            type="file"
            accept="image/*"
            capture="user"
            className="sr-only"
            onChange={handleSelfie}
          />
          <p className="text-sm text-muted-foreground">
            A clear photo of your face, used to match your identity documents.
          </p>
          {selfieError && <p className="text-sm text-destructive-foreground">{selfieError}</p>}
        </div>

        {error && (
          <Alert variant="destructive" id="kyc-status">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>Couldn&rsquo;t submit</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <div className="flex justify-end gap-2 pt-1">
          <Button type="button" variant="outline" onClick={clearAll} disabled={submitting}>
            Clear
          </Button>
          <Button type="submit" disabled={submitting || uploading}>
            {submitting && <Loader2 className="animate-spin" aria-hidden="true" />}
            Submit KYC
          </Button>
        </div>
      </form>
    </Form>
  );
}

export default KycIdentityForm;
