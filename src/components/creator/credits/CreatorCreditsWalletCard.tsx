import { useEffect, useState } from 'react';
import { Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { BuyCreditsSheet } from '@/components/creator/credits/BuyCreditsSheet';
import { useCreatorCredits } from '@/hooks/useCreatorCredits';
import { api, type CreatorCreditOrderHistoryItem } from '@/lib/api';
import { creditsCopy } from '@/lib/copy/creator-credits';

/**
 * "Meera credits" on the creator Wallet page: the balance (free vs bought), today's use against
 * the daily cap, when the next monthly 15 arrive, which bought credits expire when, the
 * "Buy 60 credits — ₹249" button, and the creator's own purchases with their invoice numbers.
 *
 * Everything shown comes from GET /creator/credits and GET /creator/credits/orders. There is no
 * per-message usage endpoint, so this lists purchases, not every message. Renders nothing while
 * CREATOR_CREDITS_ENABLED is off.
 */
function formatDate(iso: string | null | undefined, language?: string): string {
  if (!iso) return '';
  const locale = language?.toLowerCase().startsWith('hi') ? 'hi-IN' : 'en-IN';
  return new Date(iso).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Asia/Kolkata' });
}

function formatRupees(paise: number): string {
  return `₹${(paise / 100).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
}

function statusKey(
  status: CreatorCreditOrderHistoryItem['status'],
): 'wallet.status.credited' | 'wallet.status.pending' | 'wallet.status.failed' {
  if (status === 'CREDITED') return 'wallet.status.credited';
  if (status === 'FAILED') return 'wallet.status.failed';
  return 'wallet.status.pending';
}

export function CreatorCreditsWalletCard({ language }: { language?: string }) {
  const credits = useCreatorCredits();
  const [sheetOpen, setSheetOpen] = useState(false);
  const [orders, setOrders] = useState<CreatorCreditOrderHistoryItem[] | null>(null);
  const [ordersError, setOrdersError] = useState(false);

  const enabled = credits.enabled;
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    api.creatorCredits
      .listOrders()
      .then((rows) => {
        if (!cancelled) setOrders(rows);
      })
      .catch(() => {
        if (!cancelled) setOrdersError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [enabled]);

  if (!enabled || !credits.balance) return null;
  const b = credits.balance;
  const t = (key: Parameters<typeof creditsCopy>[0], vars?: Parameters<typeof creditsCopy>[2]) => creditsCopy(key, language, vars);

  const refreshAll = () => {
    void credits.refresh();
    api.creatorCredits.listOrders().then(setOrders).catch(() => setOrdersError(true));
  };

  return (
    <Card className="mb-6" data-testid="creator-credits-wallet-card">
      <CardHeader className="pb-3">
        <div className="flex items-center gap-2">
          <Sparkles className="h-5 w-5 text-primary" aria-hidden />
          <CardTitle className="text-base">{t('wallet.title')}</CardTitle>
        </div>
        <CardDescription>{t('wallet.subtitle')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="text-2xl font-bold">{t('wallet.total', { total: b.total ?? 0 })}</p>
            <p className="text-sm text-muted-foreground">{t('wallet.split', { free: b.free ?? 0, paid: b.paid ?? 0 })}</p>
            <p className="text-sm text-muted-foreground">{t('wallet.today', { n: b.dailyUsed ?? 0, total: b.dailyCap ?? 30 })}</p>
          </div>
          <Button onClick={() => setSheetOpen(true)} className="min-h-11">
            {t('buy.cta')}
          </Button>
        </div>

        {b.nextMonthlyGrantAt ? (
          <p className="text-sm">{t('wallet.nextMonthly', { date: formatDate(b.nextMonthlyGrantAt, language) })}</p>
        ) : null}

        {b.paidExpiring && b.paidExpiring.length > 0 ? (
          <ul className="space-y-1 text-sm" aria-label="Expiry dates">
            {b.paidExpiring.map((lot) => (
              <li key={`${lot.expiresAt}-${lot.credits}`}>
                {t('wallet.expiring', { n: lot.credits, date: formatDate(lot.expiresAt, language) })}
              </li>
            ))}
          </ul>
        ) : null}

        <div className="space-y-2">
          <p className="text-sm font-medium">{t('wallet.history')}</p>
          {ordersError ? (
            <p className="text-sm text-muted-foreground">{t('wallet.loadError')}</p>
          ) : orders === null ? null : orders.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('wallet.historyEmpty')}</p>
          ) : (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {orders.map((o) => (
                <li key={o.orderId} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
                  <div className="min-w-0">
                    <p className="font-medium">
                      {o.credits} credits · {formatRupees(o.amountPaise)}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {formatDate(o.paidAt, language)}
                      {o.invoiceNumber ? ` · ${o.invoiceNumber}` : ''}
                    </p>
                  </div>
                  <span className="text-xs font-medium">{t(statusKey(o.status))}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </CardContent>
      <BuyCreditsSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        language={language}
        balance={b}
        onCredited={refreshAll}
      />
    </Card>
  );
}

export default CreatorCreditsWalletCard;
