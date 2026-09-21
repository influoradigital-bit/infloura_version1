import { BadgeCheck, FileCheck2, Landmark, Receipt, ShieldCheck, Timer } from 'lucide-react';

/**
 * Trust-strip data, split out of TrustBar.tsx so that component file exports
 * only components (react-refresh/only-export-components — a mixed-export module
 * silently breaks Fast Refresh for everything importing it).
 *
 * See TrustBar.tsx for why these are product mechanisms rather than customer
 * logos or testimonials.
 */
export interface TrustBarItem {
  icon: typeof ShieldCheck;
  label: string;
}

export const BRAND_TRUST_ITEMS: TrustBarItem[] = [
  { icon: Landmark, label: 'Payments processed securely by Razorpay' },
  { icon: FileCheck2, label: 'E-signed contracts on every deal' },
  { icon: BadgeCheck, label: 'Platform-verified creator stats' },
  { icon: Receipt, label: 'Invoices generated on every payout' },
];

export const CREATOR_TRUST_ITEMS: TrustBarItem[] = [
  { icon: ShieldCheck, label: 'Brand funds locked before you shoot' },
  { icon: Timer, label: 'Paid within 2 working days of your live link' },
  { icon: Receipt, label: 'Invoice generated for you' },
  { icon: FileCheck2, label: 'Written contract, not a DM promise' },
];
