import * as React from 'react';
import { format } from 'date-fns';
import {
  AlertTriangle,
  CheckCircle2,
  Loader2,
  ShoppingBag,
  Store,
  Unplug,
} from 'lucide-react';

import { cn } from '@/lib/utils';
import { api, ApiError, type StoreProvider } from '@/lib/api';
import { useStoreIntegration } from '@/hooks/brand/useStoreIntegration';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Alert, AlertTitle, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';

type Platform = 'shopify' | 'woocommerce';

/**
 * Brand-facing store connection settings — Wave D task D5
 * (ANANYA_FRONTEND_IMPLEMENTATION_SPEC.md §16). Backed by the real,
 * confirmed endpoints from Wave D tasks D1 (Shopify)/D2 (WooCommerce):
 *
 *   - Shopify: GET /shopify/oauth/authorize?shop= returns an authorization
 *     URL the browser navigates to directly (ShopifyConnectController).
 *   - WooCommerce: POST /woocommerce/connect, body { siteUrl, webhookSecret }
 *     (WooCommerceConnectController) — no OAuth, the brand pastes a webhook
 *     secret they generated themselves in their own WooCommerce admin.
 *
 * Status + disconnect are backed by the real, QA-passed endpoints from P2-9
 * (see src/lib/api.ts `storeIntegrations.status`/`disconnect` and
 * StoreIntegrationStatusController.java): `GET /integrations/store/status`
 * reports whichever of Shopify/WooCommerce is connected for the caller's
 * workspace, and `DELETE /integrations/store/disconnect?provider=` revokes
 * it (soft-delete).
 */
export function StoreIntegrationSetup() {
  const { data: status, loading: statusLoading, error: statusError, refresh } =
    useStoreIntegration();

  const [platform, setPlatform] = React.useState<Platform | null>(null);
  const [shopDomain, setShopDomain] = React.useState('');
  const [siteUrl, setSiteUrl] = React.useState('');
  const [webhookSecret, setWebhookSecret] = React.useState('');
  const [submitting, setSubmitting] = React.useState(false);
  const [formError, setFormError] = React.useState<string | null>(null);
  const [wooSuccess, setWooSuccess] = React.useState<string | null>(null);
  const [disconnecting, setDisconnecting] = React.useState(false);

  const startShopifyOAuth = async () => {
    const shop = shopDomain.trim();
    if (!shop) {
      setFormError('Enter your Shopify store subdomain first.');
      return;
    }
    setFormError(null);
    setSubmitting(true);
    try {
      const { authorizationUrl } = await api.storeIntegrations.authorizeShopify(shop);
      window.location.href = authorizationUrl;
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : 'Could not start Shopify connection.');
      setSubmitting(false);
    }
  };

  const submitWooCommerce = async () => {
    const url = siteUrl.trim();
    const secret = webhookSecret.trim();
    if (!url || !secret) {
      setFormError('Site URL and webhook secret are both required.');
      return;
    }
    setFormError(null);
    setWooSuccess(null);
    setSubmitting(true);
    try {
      const result = await api.storeIntegrations.connectWooCommerce({ siteUrl: url, webhookSecret: secret });
      setWooSuccess(`Connected to ${result.siteUrl}.`);
      setWebhookSecret('');
      refresh();
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : 'Could not connect WooCommerce.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDisconnect = async (provider: StoreProvider) => {
    setDisconnecting(true);
    try {
      await api.storeIntegrations.disconnect(provider);
      refresh();
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : 'Could not disconnect.');
    } finally {
      setDisconnecting(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Connect Your Store</h2>
        <p className="text-muted-foreground">
          Track coupon redemptions and conversions automatically for sale-driven campaigns.
        </p>
      </div>

      {/* Connection status */}
      {statusLoading ? (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          Checking connection status…
        </div>
      ) : statusError ? (
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>Couldn't load connection status</AlertTitle>
          <AlertDescription>{statusError}</AlertDescription>
        </Alert>
      ) : status?.connected ? (
        <Alert className="border-emerald-300 bg-emerald-50">
          <CheckCircle2 className="h-4 w-4 text-emerald-700" />
          <AlertTitle className="text-emerald-900">
            Connected · {status.provider === 'SHOPIFY' ? 'Shopify' : 'WooCommerce'}
          </AlertTitle>
          <AlertDescription className="text-emerald-800">
            <p>
              {status.shopDomainOrSiteUrl}
              {status.connectedAt ? ` · connected ${format(new Date(status.connectedAt), 'dd MMM yyyy')}` : ''}
            </p>
            <Button
              variant="outline"
              size="sm"
              className="mt-2"
              disabled={disconnecting}
              onClick={() => status.provider && handleDisconnect(status.provider)}
            >
              {disconnecting ? (
                <Loader2 className="mr-2 h-3.5 w-3.5 animate-spin" />
              ) : (
                <Unplug className="mr-2 h-3.5 w-3.5" />
              )}
              Disconnect
            </Button>
          </AlertDescription>
        </Alert>
      ) : (
        <Alert className="border-amber-300 bg-amber-50">
          <AlertTriangle className="h-4 w-4 text-amber-700" />
          <AlertTitle className="text-amber-900">Not connected</AlertTitle>
          <AlertDescription className="text-amber-800">
            Required for sale-driven campaigns ("Drive Sales" / "Product Launch" objectives).
          </AlertDescription>
        </Alert>
      )}

      {/* Platform selection */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Card
          role="button"
          tabIndex={0}
          className={cn(
            'cursor-pointer p-4 transition-colors hover:border-primary',
            platform === 'shopify' && 'border-primary bg-primary/5',
          )}
          onClick={() => setPlatform('shopify')}
          onKeyDown={(e) => e.key === 'Enter' && setPlatform('shopify')}
        >
          <div className="flex flex-col items-center gap-2">
            <Store className="h-8 w-8 text-primary" />
            <span className="font-medium">Shopify</span>
            <span className="text-xs text-muted-foreground">OAuth — one click</span>
          </div>
        </Card>
        <Card
          role="button"
          tabIndex={0}
          className={cn(
            'cursor-pointer p-4 transition-colors hover:border-primary',
            platform === 'woocommerce' && 'border-primary bg-primary/5',
          )}
          onClick={() => setPlatform('woocommerce')}
          onKeyDown={(e) => e.key === 'Enter' && setPlatform('woocommerce')}
        >
          <div className="flex flex-col items-center gap-2">
            <ShoppingBag className="h-8 w-8 text-primary" />
            <span className="font-medium">WooCommerce</span>
            <span className="text-xs text-muted-foreground">Manual webhook secret</span>
          </div>
        </Card>
      </div>

      {formError && (
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      )}

      {/* Shopify setup */}
      {platform === 'shopify' && (
        <Card className="p-6">
          <h3 className="mb-4 font-semibold">Connect Shopify</h3>
          <div className="space-y-4">
            <div>
              <Label htmlFor="shop-domain">Your Shopify Store URL</Label>
              <div className="mt-1 flex items-center gap-2">
                <Input
                  id="shop-domain"
                  placeholder="your-store"
                  value={shopDomain}
                  onChange={(e) => setShopDomain(e.target.value)}
                />
                <span className="whitespace-nowrap text-sm text-muted-foreground">.myshopify.com</span>
              </div>
            </div>
            <Button onClick={startShopifyOAuth} disabled={submitting}>
              {submitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Connect Shopify
            </Button>
            <p className="text-xs text-muted-foreground">
              You'll be redirected to Shopify to approve access, then brought back here.
            </p>
          </div>
        </Card>
      )}

      {/* WooCommerce setup */}
      {platform === 'woocommerce' && (
        <Card className="p-6">
          <h3 className="mb-4 font-semibold">Connect WooCommerce</h3>
          <ol className="list-inside list-decimal space-y-2 text-sm text-muted-foreground">
            <li>In your WooCommerce admin, go to Settings → Advanced → Webhooks</li>
            <li>Click "Add webhook"</li>
            <li>Topic: any order event (e.g. "Order updated")</li>
            <li>
              Delivery URL:{' '}
              <code className="rounded bg-muted px-2 py-1 text-xs">
                https://api.influora.com/webhooks/woocommerce
              </code>
            </li>
            <li>Set (or copy) the webhook Secret, then paste your site URL and that secret below</li>
          </ol>

          <div className="mt-4 space-y-4">
            <div>
              <Label htmlFor="woo-site-url">Your Store URL</Label>
              <Input
                id="woo-site-url"
                placeholder="https://your-store.com"
                value={siteUrl}
                onChange={(e) => setSiteUrl(e.target.value)}
                className="mt-1"
              />
            </div>
            <div>
              <Label htmlFor="woo-secret">Webhook Secret</Label>
              <Input
                id="woo-secret"
                type="password"
                placeholder="whsec_..."
                value={webhookSecret}
                onChange={(e) => setWebhookSecret(e.target.value)}
                className="mt-1"
              />
            </div>
            <Button onClick={submitWooCommerce} disabled={submitting}>
              {submitting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Verify & Save
            </Button>
            {wooSuccess && (
              <Badge variant="outline" className="border-emerald-300 bg-emerald-50 text-emerald-800">
                <CheckCircle2 className="h-3 w-3" />
                {wooSuccess}
              </Badge>
            )}
          </div>
        </Card>
      )}
    </div>
  );
}

export default StoreIntegrationSetup;
