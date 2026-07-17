import { useCallback, useState } from 'react';
import { useReducedMotion, motion } from 'framer-motion';
import { Check, Copy, Loader2, Tag } from 'lucide-react';

import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useToast } from '@/hooks/use-toast';
import type { CreateCouponPayload } from '@/lib/api';

interface CouponCodeGeneratorProps {
  onCreate: (payload: CreateCouponPayload) => Promise<unknown>;
  creating: boolean;
  className?: string;
}

/**
 * Creates a coupon code for a creator on this campaign. POST
 * /campaigns/{campaignId}/coupons (see TrackingDtos.CreateCouponRequest).
 *
 * The actual code string is minted server-side by CouponCodeService — this
 * form does not generate or suggest codes client-side (the spec's
 * "AI-powered" suggestion list has no backing endpoint, so it isn't built
 * here). usageLimit/expiresAt are optional per the backend contract (null =
 * unlimited / no expiry).
 */
export function CouponCodeGenerator({ onCreate, creating, className }: CouponCodeGeneratorProps) {
  const { toast } = useToast();
  const shouldReduceMotion = useReducedMotion();

  const [creatorProfileId, setCreatorProfileId] = useState('');
  const [discountType, setDiscountType] = useState<'percentage' | 'fixed'>('percentage');
  const [discountValue, setDiscountValue] = useState('15');
  const [usageLimit, setUsageLimit] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [generatedCode, setGeneratedCode] = useState('');
  const [copied, setCopied] = useState(false);

  const parsedValue = Number(discountValue);
  const canSubmit =
    Boolean(creatorProfileId) &&
    Number.isFinite(parsedValue) &&
    parsedValue > 0 &&
    !creating;

  const handleGenerate = useCallback(async () => {
    if (!creatorProfileId || !Number.isFinite(parsedValue) || parsedValue <= 0) {
      toast({
        title: 'Missing information',
        description: 'Creator ID and a valid discount value are required.',
        variant: 'destructive',
      });
      return;
    }
    try {
      const result = await onCreate({
        creatorProfileId,
        discountType,
        discountValue: parsedValue,
        usageLimit: usageLimit ? Number(usageLimit) : undefined,
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : undefined,
      });
      setGeneratedCode((result as { code: string }).code);
      toast({ title: 'Coupon created' });
    } catch (err) {
      toast({
        title: 'Could not create coupon',
        description: err instanceof Error ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    }
  }, [creatorProfileId, discountType, parsedValue, usageLimit, expiresAt, onCreate, toast]);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(generatedCode);
      setCopied(true);
      toast({ title: 'Copied to clipboard' });
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast({
        title: 'Copy failed',
        description: 'Please copy the code manually.',
        variant: 'destructive',
      });
    }
  }, [generatedCode, toast]);

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Tag className="h-5 w-5" aria-hidden="true" />
          Coupon Code Generator
        </CardTitle>
        <CardDescription>Add a coupon code for a creator on this campaign.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="coupon-creator-id">Creator Profile ID</Label>
          <Input
            id="coupon-creator-id"
            placeholder="cr_xxxxxxxx"
            value={creatorProfileId}
            onChange={(e) => setCreatorProfileId(e.target.value)}
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="discount-type">Discount Type</Label>
            <Select value={discountType} onValueChange={(v) => setDiscountType(v as 'percentage' | 'fixed')}>
              <SelectTrigger id="discount-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="percentage">Percentage (%)</SelectItem>
                <SelectItem value="fixed">Fixed Amount</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="discount-value">{discountType === 'percentage' ? 'Percentage' : 'Amount'}</Label>
            <Input
              id="discount-value"
              type="number"
              min="1"
              max={discountType === 'percentage' ? '100' : undefined}
              value={discountValue}
              onChange={(e) => setDiscountValue(e.target.value)}
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="usage-limit">Usage Limit (optional)</Label>
            <Input
              id="usage-limit"
              type="number"
              min="1"
              placeholder="Unlimited"
              value={usageLimit}
              onChange={(e) => setUsageLimit(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="expires-at">Expires (optional)</Label>
            <Input
              id="expires-at"
              type="date"
              value={expiresAt}
              onChange={(e) => setExpiresAt(e.target.value)}
            />
          </div>
        </div>

        {generatedCode && (
          <motion.div
            initial={shouldReduceMotion ? {} : { opacity: 0, y: 8 }}
            animate={shouldReduceMotion ? {} : { opacity: 1, y: 0 }}
            className="space-y-2"
          >
            <Label>Generated Code</Label>
            <div className="flex gap-2">
              <div className="flex flex-1 items-center justify-center rounded-lg border border-primary/20 bg-primary/10 p-4">
                <span className="font-mono text-2xl font-bold tracking-wider">{generatedCode}</span>
              </div>
              <Button
                type="button"
                size="icon"
                variant="outline"
                onClick={handleCopy}
                aria-label={copied ? 'Copied' : 'Copy to clipboard'}
              >
                {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
              </Button>
            </div>
          </motion.div>
        )}
      </CardContent>
      <CardFooter>
        <Button type="button" onClick={handleGenerate} disabled={!canSubmit} className="w-full">
          {creating ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
              Creating...
            </>
          ) : (
            <>
              <Tag className="mr-2 h-4 w-4" aria-hidden="true" />
              Create Coupon
            </>
          )}
        </Button>
      </CardFooter>
    </Card>
  );
}

export default CouponCodeGenerator;
