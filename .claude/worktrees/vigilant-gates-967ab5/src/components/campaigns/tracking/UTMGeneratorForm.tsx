import { useCallback, useState } from 'react';
import { useReducedMotion, motion } from 'framer-motion';
import { Check, Copy, Link2, Loader2 } from 'lucide-react';

import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { useToast } from '@/hooks/use-toast';
import type { CreateTrackingLinkPayload } from '@/lib/api';

const PLATFORMS = ['INSTAGRAM', 'YOUTUBE', 'TIKTOK', 'TWITTER'] as const;

interface UTMGeneratorFormProps {
  onCreate: (payload: CreateTrackingLinkPayload) => Promise<unknown>;
  creating: boolean;
  className?: string;
}

/**
 * Creates a brand-facing UTM tracking link for a creator on this campaign.
 * POST /campaigns/{campaignId}/tracking-links (see TrackingDtos.CreateTrackingLinkRequest).
 *
 * There is no "creators on this campaign" listing endpoint yet, so the
 * collaboration/creator are entered by id rather than a fabricated dropdown —
 * a brand copies these from the campaign's collaborators list. Server-side
 * creation is idempotent per (campaign, creator) pair, matching the backend
 * contract, so resubmitting the same pair is safe.
 */
export function UTMGeneratorForm({ onCreate, creating, className }: UTMGeneratorFormProps) {
  const { toast } = useToast();
  const shouldReduceMotion = useReducedMotion();

  const [collaborationId, setCollaborationId] = useState('');
  const [creatorProfileId, setCreatorProfileId] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [platform, setPlatform] = useState<string>('INSTAGRAM');
  const [generatedUrl, setGeneratedUrl] = useState('');
  const [copied, setCopied] = useState(false);

  const canSubmit = Boolean(collaborationId && creatorProfileId && baseUrl) && !creating;

  const handleGenerate = useCallback(async () => {
    if (!collaborationId || !creatorProfileId || !baseUrl) {
      toast({
        title: 'Missing information',
        description: 'Collaboration ID, creator ID, and destination URL are all required.',
        variant: 'destructive',
      });
      return;
    }
    try {
      const result = await onCreate({ collaborationId, creatorProfileId, baseUrl, platform });
      setGeneratedUrl((result as { fullTrackingUrl: string }).fullTrackingUrl);
      toast({ title: 'Tracking link ready' });
    } catch (err) {
      toast({
        title: 'Could not generate tracking link',
        description: err instanceof Error ? err.message : 'Please try again.',
        variant: 'destructive',
      });
    }
  }, [collaborationId, creatorProfileId, baseUrl, platform, onCreate, toast]);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(generatedUrl);
      setCopied(true);
      toast({ title: 'Copied to clipboard' });
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast({
        title: 'Copy failed',
        description: 'Please copy the link manually.',
        variant: 'destructive',
      });
    }
  }, [generatedUrl, toast]);

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Link2 className="h-5 w-5" aria-hidden="true" />
          UTM Link Generator
        </CardTitle>
        <CardDescription>
          Create a unique tracking link for a creator to measure their campaign performance.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor="collaboration-id">Collaboration ID</Label>
            <Input
              id="collaboration-id"
              placeholder="col_xxxxxxxx"
              value={collaborationId}
              onChange={(e) => setCollaborationId(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="creator-profile-id">Creator Profile ID</Label>
            <Input
              id="creator-profile-id"
              placeholder="cr_xxxxxxxx"
              value={creatorProfileId}
              onChange={(e) => setCreatorProfileId(e.target.value)}
            />
          </div>
        </div>

        <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
          <div className="space-y-2">
            <Label htmlFor="destination-url">Destination URL</Label>
            <Input
              id="destination-url"
              type="url"
              placeholder="https://yourbrand.com/landing-page"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="platform-select">Platform</Label>
            <Select value={platform} onValueChange={setPlatform}>
              <SelectTrigger id="platform-select">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PLATFORMS.map((p) => (
                  <SelectItem key={p} value={p}>
                    {p.charAt(0) + p.slice(1).toLowerCase()}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        {generatedUrl && (
          <motion.div
            initial={shouldReduceMotion ? {} : { opacity: 0, y: 8 }}
            animate={shouldReduceMotion ? {} : { opacity: 1, y: 0 }}
            className="space-y-2"
          >
            <Label>Generated Tracking Link</Label>
            <div className="flex gap-2">
              <Input
                value={generatedUrl}
                readOnly
                className="font-mono text-xs"
                aria-label="Generated tracking URL"
              />
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
              Generating...
            </>
          ) : (
            <>
              <Link2 className="mr-2 h-4 w-4" aria-hidden="true" />
              Generate Tracking Link
            </>
          )}
        </Button>
      </CardFooter>
    </Card>
  );
}

export default UTMGeneratorForm;
