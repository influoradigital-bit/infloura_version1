import * as React from 'react';
import { CheckCircle2, Download, FileText, Loader2, Lock, PenTool } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Input } from '@/components/ui/input';
import { downloadContractPDF, signContract } from '@/lib/contract-generator';
import { api, ApiError, isApiLive, type ContractApiRecord } from '@/lib/api';
import { useToast } from '@/hooks/use-toast';
import { formatINR } from '@/lib/utils';

// 'pending_signature' (F-0250 follow-up): the backend's PENDING_SIGNATURES is reached by
// EITHER party signing first, with no ordering enforced. The coarse mapper
// (mapDealApiContractStatus, src/lib/creator-contract-mappers.ts) is never given
// brandSignedAt/creatorSignedAt, so it cannot resolve which party signed — this member means
// "exactly one signature exists, which party is unknown." Every consumer must keep BOTH
// parties' Sign controls reachable in this state and must never label a specific party as
// having signed. mapApiContractToDealStatus (the timestamp-driven mapper) never returns this
// value — it always resolves the exact party once the full contract record loads.
export type DealContractStatus =
  | 'generated'
  | 'pending_signature'
  | 'brand_signed'
  | 'creator_signed'
  | 'active';

interface DealContractTabProps {
  dealId: string;
  creatorName: string;
  campaignName: string;
  dealValue: number;
  contractId: string;
  status: DealContractStatus;
  onStatusChange: (status: DealContractStatus) => void;
}

const statusSteps = [
  { key: 'generated', label: 'Generated' },
  { key: 'brand_signed', label: 'Brand signed' },
  { key: 'creator_signed', label: 'Creator signed' },
  { key: 'active', label: 'Active' },
] as const;

export function DealContractTab({
  creatorName,
  campaignName,
  dealValue,
  contractId,
  status,
  onStatusChange,
}: DealContractTabProps) {
  const [isSigning, setIsSigning] = React.useState(false);
  const [signerName, setSignerName] = React.useState('');
  const [isFetchingPdf, setIsFetchingPdf] = React.useState(false);
  const { toast } = useToast();
  const liveApi = isApiLive();

  // F-0237: the parent (brand-chat.tsx) fetches the real contract but only
  // passes down dealId/contractId/status — never the fetched record — so
  // this panel used to render a hardcoded 5-item clause list under "Terms
  // (read-only)" with the Sign button directly beneath it, regardless of
  // what the contract actually said. Fetch the real record here (GET
  // /contracts/:id, same call brand-chat.tsx's fetchLiveContract makes) so
  // the terms/milestones shown and the Sign gate are both backed by the
  // actual contract. In mock mode `api.contracts.get` resolves `null`
  // (src/lib/api.ts mockOr) — that correctly renders as "unavailable" below
  // rather than inventing clause text.
  const [contractRecord, setContractRecord] = React.useState<ContractApiRecord | null>(null);
  const [contractLoading, setContractLoading] = React.useState(true);
  const [contractError, setContractError] = React.useState<string | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    setContractLoading(true);
    setContractError(null);
    api.contracts
      .get('brand', contractId)
      .then((record) => {
        if (cancelled) return;
        setContractRecord(record);
        if (!record) setContractError('Contract terms are not available yet.');
      })
      .catch((err) => {
        if (cancelled) return;
        setContractRecord(null);
        setContractError(err instanceof ApiError ? err.message : 'Could not load contract terms.');
      })
      .finally(() => {
        if (!cancelled) setContractLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [contractId]);

  const stepDone = (key: string) => {
    // 'pending_signature': one party has signed but which one is unknown, so only the
    // unambiguous "Generated" step can be marked done — marking "Brand signed" done here would
    // assert a signature that may not exist.
    if (status === 'pending_signature') return key === 'generated';
    const order = ['generated', 'brand_signed', 'creator_signed', 'active'];
    return order.indexOf(status) >= order.indexOf(key);
  };

  // FE-6: live mode fetches the real presigned R2 URL (GET
  // /contracts/:id/pdf-download-url) instead of the client-side HTML print.
  // 404 CONTRACT_PDF_NOT_READY is legitimate until both parties have signed.
  const handleDownloadPDF = async () => {
    if (liveApi) {
      setIsFetchingPdf(true);
      try {
        // `downloadUrl`, not `url` — see api.ts. Throwing on an empty value ensures
        // a missing URL is treated as a failure below, not a silent no-op.
        const { downloadUrl } = await api.contracts.pdfDownloadUrl('brand', contractId);
        if (!downloadUrl) throw new Error('No download URL returned');
        window.open(downloadUrl, '_blank', 'noopener,noreferrer');
      } catch (err) {
        // F-0238: previously fell back to `downloadContractPDF` built from
        // invented values (fake brand name, fake deliverables, a fake
        // now+14-day deadline) and toasted "Opened a local copy" — handing
        // the brand a fabricated document that looked like the real
        // contract. A failed fetch must surface as a failure instead.
        toast({
          title: 'Could not download the contract PDF',
          description:
            err instanceof ApiError
              ? err.message
              : 'The PDF is not ready yet. It becomes available once both parties have signed.',
          variant: 'destructive',
        });
      } finally {
        setIsFetchingPdf(false);
      }
      return;
    }

    // Demo mode only (mock API — no server-backed contract exists at all).
    // Built solely from this panel's own display props, not invented
    // financial/legal terms.
    const demoContractData = {
      contractId,
      brandName: 'Your Brand',
      creatorName,
      campaignName,
      amount: dealValue,
      deliverables: [
        { title: 'Instagram Reel', description: 'Campaign content', quantity: 2 },
        { title: 'Instagram Story', description: 'Story series', quantity: 3 },
      ],
      deadline: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
      usageRights: '6 months on social media platforms',
      exclusivity: 'As per campaign brief',
      revisionCap: 2,
      customClauses: [],
      createdAt: new Date(),
    };
    downloadContractPDF(demoContractData, `${contractId}.pdf`);
    toast({ title: 'PDF downloaded', description: 'Review the contract before signing.' });
  };

  const handleSign = async () => {
    const trimmedName = signerName.trim();
    if (!trimmedName) return;
    setIsSigning(true);
    try {
      const result = await signContract(contractId, 'brand', trimmedName);
      if (result.success) {
        onStatusChange('brand_signed');
        toast({
          title: 'Contract signed',
          description: `Sent to ${creatorName} for signature.`,
        });
        setSignerName('');
      }
    } catch (err) {
      toast({
        title: 'Signing failed',
        description: err instanceof ApiError ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsSigning(false);
    }
  };

  const waitingOnCreator = status === 'brand_signed';
  // 'pending_signature': signer unknown, so the brand's Sign control must stay reachable —
  // otherwise a creator-first PENDING_SIGNATURES deadlocks the brand exactly like the old
  // unconditional 'brand_signed' guess deadlocked them (F-0250 follow-up).
  const canBrandSign = status === 'generated' || status === 'pending_signature';
  const isActive = status === 'active' || status === 'creator_signed';

  return (
    <ScrollArea className="h-full">
      <div className="max-w-3xl mx-auto p-6 space-y-6">
        {waitingOnCreator && (
          <Card className="border-primary/30 bg-primary/5">
            <CardContent className="pt-4 flex items-start gap-3">
              <FileText className="h-5 w-5 text-primary shrink-0 mt-0.5" />
              <div>
                <p className="font-medium text-sm">Sent to creator for signature</p>
                <p className="text-sm text-muted-foreground mt-1">
                  {creatorName} will sign in their Deal Room. You&apos;ll be notified when it&apos;s
                  complete.
                </p>
              </div>
            </CardContent>
          </Card>
        )}

        <div>
          <h3 className="font-semibold text-sm mb-4">Signature progress</h3>
          <div className="flex gap-2">
            {statusSteps.map((step, idx) => (
              <React.Fragment key={step.key}>
                <div className="flex flex-col items-center flex-1 min-w-0">
                  <div
                    className={`w-10 h-10 rounded-full flex items-center justify-center mb-2 ${
                      stepDone(step.key)
                        ? 'bg-primary text-primary-foreground'
                        : 'bg-muted text-muted-foreground'
                    }`}
                  >
                    {stepDone(step.key) ? (
                      <CheckCircle2 className="h-5 w-5" />
                    ) : (
                      <span className="text-sm font-semibold">{idx + 1}</span>
                    )}
                  </div>
                  <p className="text-[10px] sm:text-xs text-center text-muted-foreground">
                    {step.label}
                  </p>
                </div>
                {idx < statusSteps.length - 1 && (
                  <div
                    className={`flex-1 h-0.5 mt-5 min-w-[8px] ${
                      stepDone(step.key) ? 'bg-primary' : 'bg-muted'
                    }`}
                  />
                )}
              </React.Fragment>
            ))}
          </div>
        </div>

        <Separator />

        <Card className="border-primary/20">
          <CardHeader className="pb-3">
            <CardTitle className="text-base flex items-center justify-between gap-2">
              <span>Contract summary</span>
              <Badge variant="outline">{contractId}</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-xs text-muted-foreground">Campaign</p>
                <p className="font-medium">{campaignName}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Creator</p>
                <p className="font-medium">{creatorName}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Value</p>
                <p className="font-semibold text-success">{formatINR(dealValue)}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">Status</p>
                <p className="font-medium capitalize">{status.replace(/_/g, ' ')}</p>
              </div>
            </div>

            {/* F-0271/F-0283: this section IS the real terms now — ContractGenerateRequest can
                carry free-text terms (MoneyDtos.java), Contract persists it in a column
                separate from the pre-existing SHA-256 tamper hash, and ContractResponse.terms
                returns whatever was actually captured. No default clause text is invented here:
                when nothing was supplied at generation time, that is stated plainly below
                rather than rendering the fabricated 5-item list F-0237 removed. No FE surface
                yet lets a brand type terms before generating a contract, so today this section
                will usually read "no terms on file" — that is the honest current state, not a
                bug in this panel.

                F-0272 fix-review note: loading/absent-record states used to be rendered TWICE —
                once inside the Terms block and once inside the Payment-schedule block below,
                each with byte-identical copy ("Contract terms are not available yet. Signing is
                disabled until the real terms load."). Both blocks key off the exact same
                contractLoading/contractRecord/contractError state, so showing the same sentence
                twice added no information and read as a rendering glitch — and it broke every
                singular findByText/getByText query in demo-sign-flow-alive.test.tsx with "Found
                multiple elements". There is exactly one fetch and exactly one failure reason, so
                there is exactly one loading/unavailable panel; the Terms and Payment-schedule
                sections only render their own (different) content once a record actually
                exists. */}
            {contractLoading ? (
              <div className="bg-muted/50 p-3 rounded-md text-xs text-muted-foreground flex items-center gap-2">
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                Loading contract terms…
              </div>
            ) : !contractRecord ? (
              <div className="bg-destructive/10 border border-destructive/30 p-3 rounded-md text-xs text-destructive-foreground">
                {contractError ?? 'Contract terms are not available yet.'} Signing is disabled
                until the real terms load.
              </div>
            ) : (
              <>
                <div>
                  <p className="text-xs text-muted-foreground mb-2">
                    Terms (from contract, read-only)
                  </p>
                  {contractRecord.terms ? (
                    <div className="bg-muted/50 p-3 rounded-md text-xs whitespace-pre-wrap max-h-48 overflow-y-auto">
                      {contractRecord.terms}
                    </div>
                  ) : (
                    <div className="bg-muted/50 p-3 rounded-md text-xs text-muted-foreground">
                      No terms are on file for this contract.
                    </div>
                  )}
                </div>

                <div>
                  <p className="text-xs text-muted-foreground mb-2">
                    Payment schedule (from contract, read-only)
                  </p>
                  <div className="bg-muted/50 p-3 rounded-md text-xs space-y-2 max-h-48 overflow-y-auto">
                    {contractRecord.milestones.length === 0 ? (
                      <p className="text-muted-foreground">No milestones on this contract.</p>
                    ) : (
                      <ol className="list-decimal list-inside space-y-1 text-muted-foreground">
                        {contractRecord.milestones
                          .slice()
                          .sort((a, b) => a.sequenceNo - b.sequenceNo)
                          .map((m) => (
                            <li key={m.id ?? m.sequenceNo}>
                              {m.description} — {formatINR(m.amount)}
                              {m.dueDate ? ` (due ${m.dueDate})` : ''}
                            </li>
                          ))}
                      </ol>
                    )}
                    <p className="text-muted-foreground pt-1 border-t border-border/50">
                      Total: {formatINR(contractRecord.totalAmount)} {contractRecord.currency}
                    </p>
                  </div>
                </div>
              </>
            )}
          </CardContent>
        </Card>

        <Card className="border-warning/30 bg-warning/5">
          <CardContent className="pt-4 flex gap-2">
            <Lock className="h-4 w-4 text-warning shrink-0 mt-0.5" />
            <div>
              <p className="text-sm font-medium">Escrow</p>
              <p className="text-xs text-muted-foreground mt-1">
                {isActive
                  ? `${formatINR(dealValue)} is locked and will release on approved deliverables.`
                  : `${formatINR(dealValue)} will be locked when both parties have signed.`}
              </p>
            </div>
          </CardContent>
        </Card>

        {canBrandSign && (
          <div className="space-y-2">
            <label htmlFor="brand-signer-name" className="text-xs font-medium text-muted-foreground">
              Type your full legal name to sign
            </label>
            <Input
              id="brand-signer-name"
              placeholder="Your legal name"
              value={signerName}
              onChange={(e) => setSignerName(e.target.value)}
              disabled={isSigning}
            />
          </div>
        )}

        <div className="flex flex-col sm:flex-row gap-2">
          <Button
            variant="outline"
            className="flex-1 gap-2"
            onClick={handleDownloadPDF}
            disabled={isFetchingPdf}
          >
            {isFetchingPdf ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Download className="h-4 w-4" />
            )}
            Download PDF
          </Button>
          {canBrandSign && (
            <Button
              className="flex-1 gap-2"
              onClick={handleSign}
              // F-0237: never let Sign go active over terms that did not come
              // from the fetched contract (still loading, or the fetch
              // failed/returned nothing).
              disabled={isSigning || !signerName.trim() || !contractRecord}
            >
              {isSigning ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Signing...
                </>
              ) : (
                <>
                  <PenTool className="h-4 w-4" />
                  Sign & send to creator
                </>
              )}
            </Button>
          )}
        </div>
      </div>
    </ScrollArea>
  );
}
