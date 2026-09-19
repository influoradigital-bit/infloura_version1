import * as React from 'react';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { RiskFlag } from '@/lib/api';

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md §8.4, B0-37) — the read-side render of `DealRiskService`'s
 * findings (`CreatorToolDtos.RiskFlag`, §3.5; the fourteen rules of §5.2).
 *
 * Lives in `components/shared/` rather than `components/creator/meera/` because it has two
 * callers, not one: Meera's `check_deal_risks` tool card AND the creator deal pages
 * (`creator-chat.tsx`'s proposal cards, §8.6). Same reason `deal-terms-summary.tsx` sits here.
 *
 * ## Colour
 * The theme is pale-surface / strong-foreground (`src/app/globals.css` `:root`: `--destructive`
 * is `#ffe5e5`, `--destructive-foreground` is `#a63a3a`). So `text-destructive` ON a destructive
 * surface is very nearly invisible, and every string that sits on `bg-destructive` here uses
 * `text-destructive-foreground`. Note this is also why `<Badge variant="destructive">` is NOT
 * used: that variant is `bg-destructive text-white` (`ui/badge.tsx`), i.e. white on #ffe5e5.
 * The three severity surfaces are `bg-destructive` / `bg-stage-negotiating` / `bg-muted`, each a
 * real token in the Tailwind v4 `@theme inline` block (there is no `tailwind.config` in this
 * repo — adding one would do nothing).
 *
 * ## Dismissal
 * `RiskFlag.dismissible` is a Java primitive `boolean`, so it is always present. A flag that is
 * NOT dismissible renders **no dismiss control at all** — not a disabled one. A disabled button
 * still says "this is dismissable, just not by you", which is the opposite of what a
 * non-dismissible flag means. Three rules pass `false` today, each saying so in its own javadoc:
 * `OffPlatformPaymentRule` (`OFF_PLATFORM_PAYMENT`), `HideDisclosureRule` (`HIDE_DISCLOSURE`) and
 * `REGULATED_CATEGORY`.
 *
 * ## Absent vs null
 * `CheckDealRisksResult` is `@JsonInclude(NON_NULL)`, so an omitted field arrives `undefined`
 * rather than `null` — `cost` is omitted on every rule that has no cost estimate. Nothing here
 * calls `.map()` on a possibly-absent array or compares anything to `null`.
 *
 * `flags` is NOT one of the omitted fields: `DealRiskService.evaluate` ends
 * `return List.copyOf(flags)` (`service/risk/DealRiskService.java` L388), so the wire always
 * carries at least `[]` and `DealRisksResponse.flags` is typed required to match. The prop below
 * stays OPTIONAL anyway, because its callers are the thing that can be mid-flight: the deal pages
 * render this card before the risks request resolves. That is a local loading state, not a wire
 * shape, and `rows` below defends against it.
 */

export type RiskSeverity = RiskFlag['severity'];

/**
 * Descending severity. `RiskSeverity` on the Java side is declared low-to-high so `compareTo` IS
 * the order; this is the same order inverted, because the creator reads the worst news first.
 */
const SEVERITY_RANK: Record<RiskSeverity, number> = {
  CRITICAL: 0,
  WARN: 1,
  INFO: 2,
};

interface SeverityStyle {
  /** The stripe / chip surface. */
  surface: string;
  /** Text that sits ON `surface`. Never `text-destructive` on `bg-destructive`. */
  onSurface: string;
  border: string;
  label: string;
}

const SEVERITY_STYLES: Record<RiskSeverity, SeverityStyle> = {
  CRITICAL: {
    surface: 'bg-destructive',
    onSurface: 'text-destructive-foreground',
    border: 'border-destructive',
    label: 'Critical',
  },
  WARN: {
    surface: 'bg-stage-negotiating',
    onSurface: 'text-stage-negotiating-fg',
    border: 'border-stage-negotiating-border',
    label: 'Warning',
  },
  INFO: {
    surface: 'bg-muted',
    onSurface: 'text-muted-foreground',
    border: 'border-border',
    label: 'Info',
  },
};

/**
 * Both lookups take `string`, not `RiskSeverity`. The union is a TS-side assertion about a wire
 * payload — `RiskFlag.severity` is a plain Java `String` (`CreatorToolDtos` L114), and the Java
 * `RiskSeverity.parse` deliberately tolerates an unrecognised value rather than throwing. Typing
 * these as the union would make the fallback dead code that `tsc` cannot warn about and the
 * runtime still needs.
 */
function styleFor(severity: string): SeverityStyle {
  return SEVERITY_STYLES[severity as RiskSeverity] ?? SEVERITY_STYLES.INFO;
}

function rankOf(severity: string): number {
  return SEVERITY_RANK[severity as RiskSeverity] ?? SEVERITY_RANK.INFO;
}

export interface DealRiskCardProps {
  /**
   * Optional, not `RiskFlag[] | null`: the producing record is `@JsonInclude(NON_NULL)`, so an
   * absent list is an ABSENT KEY on the wire. A `=== null` test here would never fire.
   */
  flags?: RiskFlag[];
  /**
   * U-3: every screen that renders this card supplies it, through `useRiskFlagDismissals`
   * (`src/hooks/`), which hides the flag for the browser session only — no endpoint stores a
   * per-flag dismissal. That is why `hiddenCount` / `onRestoreHidden` below exist: the card says
   * the flags are hidden "for this session" and offers them back, instead of implying they are
   * gone for good. Even when it is supplied, a flag with `dismissible: false` renders no control.
   */
  onDismiss?: (flag: RiskFlag) => void;
  /** U-3: flags from this evaluation the creator has hidden this session. */
  hiddenCount?: number;
  /** U-3: un-hide them. The "Show" control renders only when this and `hiddenCount > 0` are set. */
  onRestoreHidden?: () => void;
  /** Rendered above the rows. Pass `null` to render the rows bare (the deal page does). */
  heading?: React.ReactNode;
  className?: string;
}

/**
 * Renders nothing at all when there are no flags — an empty "no risks found" panel would be a
 * claim the backend has not made (a 403, an unimplemented rule and a genuinely clean deal all
 * reach here as "no flags"). Callers that want a clean-deal message render it themselves.
 *
 * The one exception is a list the creator emptied herself: when every flag is hidden, the card
 * still renders the "hidden for this session" line, so the flags stay one click away.
 */
export function DealRiskCard({
  flags,
  onDismiss,
  hiddenCount = 0,
  onRestoreHidden,
  heading,
  className,
}: DealRiskCardProps) {
  const sorted = React.useMemo(() => {
    if (!flags || flags.length === 0) return [];
    return [...flags].sort((a, b) => rankOf(a.severity) - rankOf(b.severity));
  }, [flags]);

  const showHiddenNote = hiddenCount > 0 && !!onRestoreHidden;

  if (sorted.length === 0 && !showHiddenNote) return null;

  return (
    <div className={cn('space-y-2', className)} data-testid="deal-risk-card">
      {heading !== undefined ? heading : null}
      {sorted.map((flag) => {
        const style = styleFor(flag.severity);
        return (
          <div
            key={flag.code}
            data-testid="deal-risk-row"
            data-severity={flag.severity}
            className={cn(
              'flex items-stretch gap-3 overflow-hidden rounded-lg border bg-card',
              style.border,
            )}
          >
            {/* Severity stripe — a surface, so no text sits on it. */}
            <div
              aria-hidden="true"
              data-testid="deal-risk-stripe"
              className={cn('w-1.5 shrink-0', style.surface)}
            />
            <div className="flex-1 min-w-0 py-2.5 pr-2.5 space-y-1">
              <div className="flex items-start justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2 min-w-0">
                  <span
                    data-testid="deal-risk-severity"
                    className={cn(
                      'rounded px-1.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide',
                      style.surface,
                      style.onSurface,
                    )}
                  >
                    {style.label}
                  </span>
                  <span className="text-sm font-medium break-words">{flag.title}</span>
                </div>
                {/* No control at all when the flag is not dismissible — never a disabled one. */}
                {flag.dismissible && onDismiss ? (
                  <button
                    type="button"
                    aria-label={`Dismiss ${flag.title}`}
                    onClick={() => onDismiss(flag)}
                    className="shrink-0 rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                ) : null}
              </div>
              <p className="text-xs text-muted-foreground break-words">{flag.detail}</p>
              {/* `cost` is omitted (NON_NULL) on every rule with no cost estimate. */}
              {flag.cost ? (
                <p className="text-xs font-medium break-words">{flag.cost}</p>
              ) : null}
              <p className="text-xs break-words">
                <span className="text-muted-foreground">What to do: </span>
                {flag.action}
              </p>
            </div>
          </div>
        );
      })}
      {showHiddenNote ? (
        <p
          data-testid="deal-risk-hidden-note"
          className="flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground"
        >
          <span>
            {hiddenCount} {hiddenCount === 1 ? 'flag' : 'flags'} hidden for this session.
          </span>
          <button
            type="button"
            aria-label="Show hidden flags"
            onClick={onRestoreHidden}
            className="rounded font-medium text-foreground underline underline-offset-2 hover:no-underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          >
            Show
          </button>
        </p>
      ) : null}
    </div>
  );
}

export default DealRiskCard;
