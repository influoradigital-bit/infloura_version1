import { useState } from 'react';
import { Check, Copy, Link2 } from 'lucide-react';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty';
import { useToast } from '@/hooks/use-toast';
import type { TrackingLinkResponse } from '@/lib/api';

interface TrackingLinksTableProps {
  links: TrackingLinkResponse[];
  loading: boolean;
  error: string | null;
  className?: string;
}

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(
    amount,
  );
}

export function TrackingLinksTable({ links, loading, error, className }: TrackingLinksTableProps) {
  const { toast } = useToast();
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const handleCopy = async (link: TrackingLinkResponse) => {
    try {
      await navigator.clipboard.writeText(link.shortUrl || link.fullTrackingUrl);
      setCopiedId(link.id);
      toast({ title: 'Copied to clipboard' });
      setTimeout(() => setCopiedId(null), 2000);
    } catch {
      toast({ title: 'Copy failed', description: 'Please copy the link manually.', variant: 'destructive' });
    }
  };

  if (loading) {
    return (
      <Card className={className}>
        <CardHeader>
          <Skeleton className="h-5 w-40" />
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {[1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-12 w-full" />
            ))}
          </div>
        </CardContent>
      </Card>
    );
  }

  if (error) {
    return (
      <Card className={className}>
        <CardContent className="py-6 text-center text-sm text-destructive-foreground">
          Couldn't load tracking links. {error}
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>Tracking Links</CardTitle>
        <CardDescription>UTM links generated for creators on this campaign</CardDescription>
      </CardHeader>
      <CardContent>
        {links.length === 0 ? (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <Link2 aria-hidden="true" />
              </EmptyMedia>
              <EmptyTitle>No tracking links yet</EmptyTitle>
              <EmptyDescription>
                Generate a link above to start tracking clicks and conversions for a creator.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : (
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Creator</TableHead>
                  <TableHead>Platform</TableHead>
                  <TableHead className="text-right">Clicks</TableHead>
                  <TableHead className="text-right">Conversions</TableHead>
                  <TableHead className="text-right">Revenue</TableHead>
                  <TableHead>Link</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {links.map((link) => (
                  <TableRow key={link.id}>
                    <TableCell className="font-medium">{link.creatorProfileId}</TableCell>
                    <TableCell className="capitalize">{link.utmMedium}</TableCell>
                    <TableCell className="text-right">{link.clickCount.toLocaleString('en-IN')}</TableCell>
                    <TableCell className="text-right">{link.conversionCount.toLocaleString('en-IN')}</TableCell>
                    <TableCell className="text-right font-medium">
                      {formatCurrency(link.revenueAttributed)}
                    </TableCell>
                    <TableCell>
                      <Button
                        type="button"
                        size="icon"
                        variant="ghost"
                        onClick={() => handleCopy(link)}
                        aria-label={copiedId === link.id ? 'Copied' : 'Copy tracking link'}
                      >
                        {copiedId === link.id ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default TrackingLinksTable;
