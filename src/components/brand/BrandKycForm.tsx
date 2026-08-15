/**
 * BrandKycForm — brand workspace verification capture (GSTIN, PAN + supporting docs).
 * ----------------------------------------------------------------------------
 * Brand-side mirror of the creator `KycIdentityForm`. Collects the workspace's GSTIN
 * and PAN plus a document image/PDF for each, uploads the two documents first via
 * `uploads.upload(file, 'brand')` (→ `{ url }`), then submits the four values through
 * `useBrandKyc()` → `POST /onboarding/brand/kyc`, which moves the workspace to PENDING.
 *
 * Validation patterns mirror the backend: GSTIN is the standard 15-char format and PAN is
 * the 10-char format (same regex the creator KYC form uses for PAN).
 */

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { AlertTriangle, Loader2, Upload } from 'lucide-react';

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
import { useBrandKyc, type BrandKycResult } from '@/hooks/brand/useBrandKyc';

// 15-char GSTIN: 2-digit state code, 5 letters (PAN prefix), 4 digits, 1 letter,
// 1 entity digit/letter, literal 'Z', 1 checksum digit/letter.
const GSTIN_PATTERN = /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$/;
const PAN_PATTERN = /^[A-Z]{5}[0-9]{4}[A-Z]{1}$/;

const kycSchema = z.object({
  gstin: z
    .string()
    .trim()
    .toUpperCase()
    .refine((v) => GSTIN_PATTERN.test(v), {
      message: 'Invalid GSTIN format (15 characters, e.g. 29ABCDE1234F1Z5)',
    }),
  pan: z
    .string()
    .trim()
    .toUpperCase()
    .refine((v) => PAN_PATTERN.test(v), {
      message: 'Invalid PAN format (e.g. ABCDE1234F)',
    }),
});

type KycFormValues = z.infer<typeof kycSchema>;

interface UploadedDoc {
  url: string | null;
  name: string | null;
  uploading: boolean;
  error: string | null;
}

const EMPTY_DOC: UploadedDoc = { url: null, name: null, uploading: false, error: null };

export interface BrandKycFormProps {
  /** Called with the result after a successful submit — lets the caller flip UI / caches to PENDING. */
  onSubmitted?: (result: BrandKycResult) => void;
}

export function BrandKycForm({ onSubmitted }: BrandKycFormProps) {
  const { submitting, error, submit } = useBrandKyc();

  const [gstinDoc, setGstinDoc] = React.useState<UploadedDoc>(EMPTY_DOC);
  const [panDoc, setPanDoc] = React.useState<UploadedDoc>(EMPTY_DOC);

  const form = useForm<KycFormValues>({
    resolver: zodResolver(kycSchema),
    defaultValues: { gstin: '', pan: '' },
  });

  const uploadDoc = async (
    file: File,
    setDoc: React.Dispatch<React.SetStateAction<UploadedDoc>>,
  ) => {
    setDoc({ url: null, name: null, uploading: true, error: null });
    try {
      const { url } = await uploads.upload(file, 'brand');
      setDoc({ url, name: file.name, uploading: false, error: null });
    } catch (err) {
      setDoc({
        url: null,
        name: null,
        uploading: false,
        error: err instanceof ApiError ? err.message : 'Failed to upload document',
      });
    }
  };

  const onSubmit = async (values: KycFormValues) => {
    let ok = true;
    if (!gstinDoc.url) {
      setGstinDoc((d) => ({ ...d, error: 'Upload your GSTIN document to continue' }));
      ok = false;
    }
    if (!panDoc.url) {
      setPanDoc((d) => ({ ...d, error: 'Upload your PAN document to continue' }));
      ok = false;
    }
    if (!ok || !gstinDoc.url || !panDoc.url) return;

    const result = await submit({
      gstin: values.gstin,
      pan: values.pan,
      gstinDocUrl: gstinDoc.url,
      panDocUrl: panDoc.url,
    });
    if (result) onSubmitted?.(result);
  };

  const busy = submitting || gstinDoc.uploading || panDoc.uploading;

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-5">
        <p className="text-sm text-muted-foreground">
          Verify your workspace so you can publish live campaigns. We ask for your GSTIN and PAN and a
          supporting document for each. Verification is usually reviewed within a couple of business days.
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
                  placeholder="29ABCDE1234F1Z5"
                  maxLength={15}
                  autoCapitalize="characters"
                  autoComplete="off"
                  className="uppercase"
                />
              </FormControl>
              <FormDescription>15-character Goods &amp; Services Tax Identification Number.</FormDescription>
              <FormMessage />
            </FormItem>
          )}
        />

        <DocUpload
          id="kyc-gstin-doc"
          label="GSTIN document"
          hint="A photo or PDF of your GST registration certificate."
          doc={gstinDoc}
          onPick={(file) => uploadDoc(file, setGstinDoc)}
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
              <FormDescription>10-character company/entity Permanent Account Number.</FormDescription>
              <FormMessage />
            </FormItem>
          )}
        />

        <DocUpload
          id="kyc-pan-doc"
          label="PAN document"
          hint="A photo or PDF of your PAN card."
          doc={panDoc}
          onPick={(file) => uploadDoc(file, setPanDoc)}
        />

        {error && (
          <Alert variant="destructive" id="brand-kyc-status">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>Couldn&rsquo;t submit verification</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <div className="flex justify-end pt-1">
          <Button type="submit" disabled={busy}>
            {submitting && <Loader2 className="animate-spin" aria-hidden="true" />}
            Submit for verification
          </Button>
        </div>
      </form>
    </Form>
  );
}

interface DocUploadProps {
  id: string;
  label: string;
  hint: string;
  doc: UploadedDoc;
  onPick: (file: File) => void;
}

function DocUpload({ id, label, hint, doc, onPick }: DocUploadProps) {
  return (
    <div className="space-y-2">
      <FormLabel htmlFor={id}>{label}</FormLabel>
      <div className="flex items-center gap-3">
        <Button
          type="button"
          variant="outline"
          disabled={doc.uploading}
          onClick={() => document.getElementById(id)?.click()}
        >
          {doc.uploading ? (
            <Loader2 className="animate-spin" aria-hidden="true" />
          ) : (
            <Upload aria-hidden="true" />
          )}
          {doc.url ? 'Replace document' : 'Upload document'}
        </Button>
        {doc.name && (
          <span className="max-w-[12rem] truncate text-sm text-muted-foreground">{doc.name}</span>
        )}
      </div>
      <input
        id={id}
        type="file"
        accept="image/*,application/pdf"
        className="sr-only"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) onPick(file);
        }}
      />
      <p className="text-sm text-muted-foreground">{hint}</p>
      {doc.error && <p className="text-sm text-destructive-foreground">{doc.error}</p>}
    </div>
  );
}

export default BrandKycForm;
