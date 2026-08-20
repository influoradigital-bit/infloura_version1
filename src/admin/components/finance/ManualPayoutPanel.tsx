/**
 * ManualPayoutPanel — record a bank transfer that has ALREADY been made.
 *
 * <p>The payout rail while RazorpayX is unprovisioned. Nothing here moves money: an operator sends
 * a NEFT/UPI transfer from the company bank account, then records it so the creator's Influora
 * balance stops showing funds already sitting in their bank. Without that, the ledger and the bank
 * statement diverge on the first payout and never re-converge.
 *
 * <p>Two things this form is deliberately strict about:
 *
 * <ul>
 *   <li><b>The idempotency key is minted once per submission and REUSED on retry.</b> A fresh key
 *       on a retry would debit the creator a second time for one transfer. It is only rotated
 *       after a confirmed success, when the next entry is genuinely a different payout.
 *   <li><b>TDS is captured here, not later.</b> The platform has no TDS engine — if the operator
 *       records gross and keeps the deduction in a spreadsheet, commission invoice Doc#3b
 *       disagrees with the bank statement from the first payout onward. Left blank means "no TDS
 *       applied", which the backend stores distinctly from a recorded zero.
 * </ul>
 */
import { type FormEvent, useState } from 'react';
import { AlertTriangle, Banknote, CheckCircle2, Loader2 } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { financeApi } from '../../services/api-contracts';

/** Per-submission key. Not crypto-sensitive — it only needs to be unique per logical payout. */
function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `manual-payout-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

interface RecordedPayout {
  payoutId: string;
  amount: number;
  currency: string;
  bankReference: string;
}

export function ManualPayoutPanel() {
  const [creatorUserId, setCreatorUserId] = useState('');
  const [amount, setAmount] = useState('');
  const [bankReference, setBankReference] = useState('');
  const [tdsAmount, setTdsAmount] = useState('');
  const [note, setNote] = useState('');

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recorded, setRecorded] = useState<RecordedPayout | null>(null);
  // Held across retries of the SAME submission — see the class note above.
  const [idempotencyKey, setIdempotencyKey] = useState<string | null>(null);

  const parsedAmount = Number(amount);
  const parsedTds = tdsAmount.trim() === '' ? null : Number(tdsAmount);
  const canSubmit =
    creatorUserId.trim() !== '' &&
    bankReference.trim() !== '' &&
    Number.isFinite(parsedAmount) &&
    parsedAmount > 0 &&
    (parsedTds === null || (Number.isFinite(parsedTds) && parsedTds >= 0 && parsedTds <= parsedAmount));

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit || submitting) return;

    const key = idempotencyKey ?? newIdempotencyKey();
    setIdempotencyKey(key);
    setSubmitting(true);
    setError(null);

    const res = await financeApi.recordManualPayout(
      {
        creatorUserId: creatorUserId.trim(),
        amount: parsedAmount,
        bankReference: bankReference.trim(),
        tdsAmount: parsedTds,
        note: note.trim() || undefined,
      },
      key,
    );

    setSubmitting(false);

    if (!res.success || !res.data) {
      // Key intentionally NOT rotated — a retry of this same payout must reuse it.
      setError(res.error ?? 'Could not record the payout.');
      return;
    }

    setRecorded({
      payoutId: res.data.payoutId,
      amount: res.data.amount,
      currency: res.data.currency,
      bankReference: res.data.bankReference,
    });
    // Succeeded, so the next entry is a genuinely different payout and needs its own key.
    setIdempotencyKey(null);
    setCreatorUserId('');
    setAmount('');
    setBankReference('');
    setTdsAmount('');
    setNote('');
  };

  return (
    <Card className="gap-4 p-5">
      <div className="flex flex-col gap-1">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Banknote className="size-4" aria-hidden="true" />
          Record a manual payout
        </h3>
        <p className="text-xs leading-5 text-muted-foreground">
          For a transfer you have <strong>already sent</strong> from the company bank account. This
          debits the creator&apos;s Influora balance to match — it does not send any money.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="mp-creator">Creator user ID</Label>
            <Input
              id="mp-creator"
              value={creatorUserId}
              onChange={(e) => setCreatorUserId(e.target.value)}
              placeholder="usr_..."
              autoComplete="off"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="mp-amount">Amount sent (₹)</Label>
            <Input
              id="mp-amount"
              type="number"
              min="0.01"
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="10000.00"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="mp-utr">Bank reference / UTR</Label>
            <Input
              id="mp-utr"
              value={bankReference}
              onChange={(e) => setBankReference(e.target.value)}
              placeholder="UTR from the transfer"
              autoComplete="off"
            />
            <p className="text-xs text-muted-foreground">
              The only link between this record and the actual movement of money.
            </p>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="mp-tds">TDS deducted (₹)</Label>
            <Input
              id="mp-tds"
              type="number"
              min="0"
              step="0.01"
              value={tdsAmount}
              onChange={(e) => setTdsAmount(e.target.value)}
              placeholder="Leave blank if none"
            />
            <p className="text-xs text-muted-foreground">
              Record it now — there is no TDS engine to reconstruct it later.
            </p>
          </div>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="mp-note">Note (optional)</Label>
          <Input
            id="mp-note"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. NEFT sent 20 Aug, creator requested by email"
          />
        </div>

        {error && (
          <p className="flex items-start gap-2 text-sm text-destructive-foreground">
            <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            <span>{error}</span>
          </p>
        )}

        {recorded && (
          <p className="flex items-start gap-2 text-sm">
            <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-600" aria-hidden="true" />
            <span>
              Recorded {recorded.currency} {recorded.amount.toLocaleString('en-IN')} against{' '}
              <code className="text-xs">{recorded.bankReference}</code>. The creator&apos;s balance
              now reflects the transfer.
            </span>
          </p>
        )}

        <div>
          <Button type="submit" disabled={!canSubmit || submitting}>
            {submitting && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
            {submitting ? 'Recording…' : 'Record payout'}
          </Button>
        </div>
      </form>
    </Card>
  );
}
