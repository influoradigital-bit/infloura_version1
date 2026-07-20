import { FlaskConical } from 'lucide-react';

import { isApiLive } from '@/lib/api';

/**
 * Persistent signal that the app is serving MOCK/demo data (VITE_API_MODE !== 'live').
 *
 * The API layer silently substitutes polished sample data when not live (see
 * `mockOr`/`isLive` in src/lib/api.ts), which is indistinguishable from real data
 * at a glance — wallet balances, campaigns, analytics all look genuine. This badge
 * makes the mode obvious so demo numbers are never mistaken for production data.
 *
 * Renders nothing in live mode, so it is safe to mount unconditionally at the root.
 */
export function DemoModeBanner() {
  if (isApiLive()) return null;

  return (
    <div
      role="status"
      aria-label="Demo data mode"
      className="fixed bottom-3 left-3 z-[100] flex items-center gap-1.5 rounded-full border border-amber-400/60 bg-amber-100 px-3 py-1.5 text-xs font-semibold text-amber-900 shadow-md dark:border-amber-400/40 dark:bg-amber-950 dark:text-amber-200"
    >
      <FlaskConical className="h-3.5 w-3.5" aria-hidden="true" />
      Demo data — not a live backend
    </div>
  );
}
