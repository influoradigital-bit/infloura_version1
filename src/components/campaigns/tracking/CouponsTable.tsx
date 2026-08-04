import { Tag } from 'lucide-react';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty';
import type { CouponResponse } from '@/lib/api';

interface CouponsTableProps {
  coupons: CouponResponse[];
  loading: boolean;
  error: string | null;
  className?: string;
}

function formatDiscount(coupon: CouponResponse): string {
  return coupon.discountType === 'percentage'
    ? `${coupon.discountValue}%`
    : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(
        coupon.discountValue,
      );
}

export function CouponsTable({ coupons, loading, error, className }: CouponsTableProps) {
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
          Couldn't load coupons. {error}
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>Coupon Redemptions</CardTitle>
        <CardDescription>Coupon codes issued to creators on this campaign</CardDescription>
      </CardHeader>
      <CardContent>
        {coupons.length === 0 ? (
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <Tag aria-hidden="true" />
              </EmptyMedia>
              <EmptyTitle>No coupons yet</EmptyTitle>
              <EmptyDescription>
                Create a coupon above to give a creator a code their audience can redeem.
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : (
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Code</TableHead>
                  <TableHead>Creator</TableHead>
                  <TableHead>Discount</TableHead>
                  <TableHead className="text-right">Uses</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {coupons.map((coupon) => {
                  const expired = coupon.expiresAt ? new Date(coupon.expiresAt).getTime() < Date.now() : false;
                  const atLimit = coupon.usageLimit != null && coupon.usageCount >= coupon.usageLimit;
                  const isActive = !expired && !atLimit;
                  return (
                    <TableRow key={coupon.id}>
                      <TableCell className="font-mono font-medium">{coupon.code}</TableCell>
                      <TableCell>{coupon.creatorProfileId}</TableCell>
                      <TableCell>{formatDiscount(coupon)}</TableCell>
                      <TableCell className="text-right">
                        {coupon.usageCount}
                        {coupon.usageLimit != null ? ` / ${coupon.usageLimit}` : ''}
                      </TableCell>
                      <TableCell>
                        <Badge variant={isActive ? 'default' : 'secondary'}>
                          {expired ? 'Expired' : atLimit ? 'Limit reached' : 'Active'}
                        </Badge>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default CouponsTable;
