/**
 * TaxIdentityForm — creator GSTIN/PAN capture (D14-B / C32)
 * ----------------------------------------------------------------------------
 * Schema-side, `CreatorProfile.gstin`/`.pan`/`.taxRegistrationStatus` exist
 * (D14-B migration, `CreatorTaxRegistrationStatus`). Vikram shipped the real
 * `POST /me/tax-identity` endpoint 2026-07-24 — this form submits to it via
 * `useCreatorTaxIdentity()` and renders the persisted, masked record
 * (GSTIN + masked PAN) on success, or the server's field/format error on a
 * 400 (`INVALID_GSTIN` / `INVALID_PAN` / `TAX_IDENTITY_REQUIRED`).
 *
 * Validation patterns mirror the backend exactly — `OnboardingDtos.KycRequest`
 * / `MoneyDtos.WalletTopUpRequest` `@Pattern` regexes.
 */

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { AlertTriangle, CheckCircle2, Loader2 } from 'lucide-react';

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
import { useCreatorTaxIdentity } from '@/hooks/creator/useCreatorTaxIdentity';

const GSTIN_PATTERN = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$/;
const PAN_PATTERN = /^[A-Z]{5}[0-9]{4}[A-Z]{1}$/;

const taxIdentitySchema = z
  .object({
    gstin: z
      .string()
      .trim()
      .toUpperCase()
      .refine((v) => v === '' || GSTIN_PATTERN.test(v), {
        message: 'Invalid GSTIN format (e.g. 27ABCDE1234F1Z5)',
      }),
    pan: z
      .string()
      .trim()
      .toUpperCase()
      .refine((v) => v === '' || PAN_PATTERN.test(v), {
        message: 'Invalid PAN format (e.g. ABCDE1234F)',
      }),
  })
  .refine((data) => data.gstin !== '' || data.pan !== '', {
    message: 'Enter your PAN or GSTIN to continue',
    path: ['pan'],
  });

type TaxIdentityFormValues = z.infer<typeof taxIdentitySchema>;

export interface TaxIdentityFormProps {
  /** Called after a submit attempt completes, success or not — lets the caller close a dialog. */
  onSubmitted?: () => void;
}

export function TaxIdentityForm({ onSubmitted }: TaxIdentityFormProps) {
  const { submitting, saved, error, submit, reset: resetSubmitState } = useCreatorTaxIdentity();

  const form = useForm<TaxIdentityFormValues>({
    resolver: zodResolver(taxIdentitySchema),
    defaultValues: { gstin: '', pan: '' },
  });

  const onSubmit = async (values: TaxIdentityFormValues) => {
    await submit({
      gstin: values.gstin || undefined,
      pan: values.pan || undefined,
    });
    onSubmitted?.();
  };

  if (saved) {
    return (
      <div className="space-y-4">
        <Alert id="tax-identity-status">
          <CheckCircle2 className="text-stage-approved-fg" aria-hidden="true" />
          <AlertTitle>Tax details saved</AlertTitle>
          <AlertDescription>
            <div className="space-y-1 mt-1">
              {saved.gstin && (
                <div className="flex justify-between">
                  <span className="text-muted-foreground">GSTIN</span>
                  <span className="font-medium">{saved.gstin}</span>
                </div>
              )}
              {saved.maskedPan && (
                <div className="flex justify-between">
                  <span className="text-muted-foreground">PAN</span>
                  <span className="font-medium">{saved.maskedPan}</span>
                </div>
              )}
            </div>
          </AlertDescription>
        </Alert>
        <div className="flex justify-end">
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              form.reset();
              resetSubmitState();
            }}
          >
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
        aria-describedby={error ? 'tax-identity-status' : undefined}
      >
        <p className="text-sm text-muted-foreground">
          Add your GST registration and PAN so brands can bill you correctly and your invoices carry
          the right tax details. Both are optional if you're not GST-registered — you can still add
          your PAN alone.
        </p>

        <FormField
          control={form.control}
          name="gstin"
          render={({ field }) => (
            <FormItem>
              <FormLabel>GSTIN</FormLabel>
              <FormControl>
                <Input
                  {...field}
                  placeholder="27ABCDE1234F1Z5"
                  maxLength={15}
                  autoCapitalize="characters"
                  autoComplete="off"
                  className="uppercase"
                />
              </FormControl>
              <FormDescription>15-character GST Identification Number, if registered.</FormDescription>
              <FormMessage />
            </FormItem>
          )}
        />

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

        {error && (
          <Alert variant="destructive" id="tax-identity-status">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>Couldn&rsquo;t save</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <div className="flex justify-end gap-2 pt-1">
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              form.reset();
              resetSubmitState();
            }}
            disabled={submitting}
          >
            Clear
          </Button>
          <Button type="submit" disabled={submitting}>
            {submitting ? (
              <>
                <Loader2 className="h-4 w-4 mr-2 animate-spin" aria-hidden="true" />
                Saving&hellip;
              </>
            ) : (
              'Save tax details'
            )}
          </Button>
        </div>
      </form>
    </Form>
  );
}

export default TaxIdentityForm;
