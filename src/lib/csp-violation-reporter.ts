/**
 * F-1789 — real-user Content-Security-Policy violation reporting.
 * ----------------------------------------------------------------------------
 * F-1787 ships a CSP to live influora.in in two stages: Report-Only first, enforcing second.
 * There is no `report-uri`/`report-to`, so without this module stage 2 is switched on blind.
 * Browsers fire `securitypolicyviolation` on `document` for BOTH dispositions
 * (`event.disposition` is "report" or "enforce"), so one listener covers both stages.
 *
 * Transport: the EXISTING CR-11 crash-report path (`api.clientErrors.report` →
 * `POST /api/v1/client-errors`, wiki/tech/cr-11-client-error-contract.md, LOCKED). Nothing
 * about that contract changes — each violation is folded into the existing fields:
 *
 *   message        "CSP <disposition>: <effectiveDirective> blocked <blocked>"  — always
 *                  contains "CSP", which the F-1787 runbook's clean-day grep counts on.
 *   stack          compact deterministic key=value lines (see `buildSummary`).
 *   componentStack null.
 *   pathname       `window.location.pathname` ONLY.
 *   buildId/userAgent  filled in by `api.clientErrors.report` itself, exactly as for
 *                  ErrorBoundary.
 *
 * PRIVACY (hard requirement, mirrors the contract's pathname rule): a violation event's
 * `blockedURI`, `sourceFile`, `documentURI` and `referrer` can all carry full URLs with query
 * strings (`?deal=<id>`, OAuth callback params). `documentURI` and `referrer` are never read.
 * `blockedURI` and `sourceFile` are reduced to origin + pathname (or a fixed keyword) by
 * `sanitiseUri` before they reach any field.
 *
 * NOISE / ABUSE: each distinct (disposition, effectiveDirective, sanitised blocked) triple is
 * reported once per page session, with a hard cap of MAX_REPORTS_PER_SESSION sends in total.
 * A violation whose blocked resource IS the crash-report endpoint is never reported — if the
 * CSP blocks our own report POST, reporting that would only produce another blocked POST.
 */
import api from '@/lib/api';

/** Hard cap on reports per page session, across all distinct violations. */
export const MAX_REPORTS_PER_SESSION = 10;

/** Chars of `event.sample` kept — enough to identify WHICH inline script was blocked
 *  (e.g. the GTM Custom HTML tag) and no more. Browsers already cap it at 40. */
const SAMPLE_MAX = 40;

/** Cap on any single short token (directive, disposition) we copy off the event. */
const TOKEN_MAX = 64;

/** blockedURI / sourceFile values browsers emit as bare keywords rather than URLs. Kept as-is. */
const URI_KEYWORDS = new Set([
  '',
  'inline',
  'eval',
  'wasm-eval',
  'trusted-types-policy',
  'trusted-types-sink',
  'data',
  'blob',
  'self',
]);

/** The subset of `SecurityPolicyViolationEvent` this module reads. `documentURI` and
 *  `referrer` are deliberately absent: they are never read, so they can never be sent. */
interface ViolationFields {
  disposition?: unknown;
  effectiveDirective?: unknown;
  violatedDirective?: unknown;
  blockedURI?: unknown;
  sourceFile?: unknown;
  lineNumber?: unknown;
  columnNumber?: unknown;
  statusCode?: unknown;
  sample?: unknown;
}

let uninstallCurrent: (() => void) | null = null;
const reportedKeys = new Set<string>();
let sentCount = 0;

function str(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function num(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function token(value: unknown, fallback: string): string {
  const s = str(value).replace(/[^A-Za-z0-9_-]/g, '').slice(0, TOKEN_MAX);
  return s || fallback;
}

/**
 * Reduce a `blockedURI` / `sourceFile` value to something that can never carry a query
 * string or fragment:
 *   - a browser keyword ("inline", "eval", "data", …, or empty) is kept verbatim;
 *   - an http(s)/ws(s) URL becomes `origin + pathname`;
 *   - any other parseable URL (data:, blob:, chrome-extension:, …) becomes its scheme only —
 *     a `data:`/`blob:` URL's "pathname" IS its payload, so it must not be sent;
 *   - anything `new URL()` rejects becomes "unparseable".
 */
export function sanitiseUri(raw: unknown): string {
  const value = str(raw).trim();
  if (URI_KEYWORDS.has(value)) return value;
  try {
    const url = new URL(value);
    // Matrix parameters (`/a;jsessionid=SECRET`) are dropped too: they are session state
    // hiding in the path, the same class of leak as a query string.
    if (/^(https?|wss?):$/.test(url.protocol)) return url.origin + url.pathname.split(';')[0];
    return url.protocol;
  } catch {
    return 'unparseable';
  }
}

/**
 * The part of a sanitised blocked value used for DEDUPE: the origin for a URL, the value itself
 * for a keyword or scheme. Keying on the full path let violations with many distinct paths (one
 * blocked host serving unique image/R2 paths, a browser extension) use up every one of the
 * MAX_REPORTS_PER_SESSION slots before a more important violation arrived.
 */
function dedupeTarget(sanitisedBlocked: string): string {
  const m = /^((?:https?|wss?):\/\/[^/]+)/.exec(sanitisedBlocked);
  return m ? m[1] : sanitisedBlocked;
}

/** True when the blocked resource is our own crash-report endpoint (recursion guard). */
function isReportEndpoint(sanitisedBlocked: string): boolean {
  return /\/client-errors\/?$/.test(sanitisedBlocked);
}

/** Deterministic `key=value` summary for the `stack` field. Fixed key order; free text
 *  (`sample`) is JSON-quoted so it cannot inject extra lines/keys. */
function buildSummary(
  fields: ViolationFields,
  disposition: string,
  effectiveDirective: string,
  blocked: string,
): string {
  const source = sanitiseUri(fields.sourceFile);
  const sample = str(fields.sample).slice(0, SAMPLE_MAX);
  return [
    `disposition=${disposition}`,
    `effectiveDirective=${effectiveDirective}`,
    `blocked=${blocked}`,
    `source=${source}:${num(fields.lineNumber)}:${num(fields.columnNumber)}`,
    `statusCode=${num(fields.statusCode)}`,
    `sample=${JSON.stringify(sample)}`,
  ].join('\n');
}

function onViolation(event: Event): void {
  try {
    const fields = event as unknown as ViolationFields;
    const disposition = token(fields.disposition, 'unknown');
    const effectiveDirective = token(
      str(fields.effectiveDirective) || str(fields.violatedDirective),
      'unknown',
    );
    const blocked = sanitiseUri(fields.blockedURI);

    if (isReportEndpoint(blocked)) return;
    if (sentCount >= MAX_REPORTS_PER_SESSION) return;

    const key = JSON.stringify([disposition, effectiveDirective, dedupeTarget(blocked)]);
    if (reportedKeys.has(key)) return;
    reportedKeys.add(key);
    sentCount += 1;

    const pathname = typeof window !== 'undefined' ? window.location.pathname : '';
    // Fire-and-forget. A synchronous throw lands in the outer catch; a rejection lands here.
    void api.clientErrors
      .report({
        message: `CSP ${disposition}: ${effectiveDirective} blocked ${blocked}`,
        stack: buildSummary(fields, disposition, effectiveDirective, blocked),
        componentStack: null,
        pathname,
      })
      .catch(() => {
        // Silent: `api.clientErrors.report` never rejects by contract, and a failed send
        // must never surface or trigger another report.
      });
  } catch {
    // A reporter must never throw out of an event listener.
  }
}

/**
 * Start reporting CSP violations. Idempotent: a second call while installed returns the same
 * uninstaller and adds no second listener. The returned function removes the listener and
 * clears the per-session dedupe/cap state (used by tests; the app never uninstalls).
 */
export function installCspViolationReporter(): () => void {
  if (uninstallCurrent) return uninstallCurrent;
  if (typeof document === 'undefined') return () => {};

  document.addEventListener('securitypolicyviolation', onViolation);

  // Take over from public/csp-violation-buffer.js, which has been buffering since the top of
  // <head>. Order matters: the listener above is attached FIRST, then the flag stops the early
  // buffer, then the backlog is drained — so no event falls in a gap. An event that lands in both
  // (between attach and flag) is reported once, because it has the same dedupe key.
  if (typeof window !== 'undefined') {
    const w = window as unknown as {
      __INFLUORA_CSP_REPORTER_READY__?: boolean;
      __INFLUORA_CSP_BUFFER__?: unknown;
    };
    w.__INFLUORA_CSP_REPORTER_READY__ = true;
    const backlog = w.__INFLUORA_CSP_BUFFER__;
    if (Array.isArray(backlog)) {
      for (const item of backlog.splice(0, backlog.length)) {
        if (item && typeof item === 'object') onViolation(item as Event);
      }
    }
  }

  const uninstall = (): void => {
    document.removeEventListener('securitypolicyviolation', onViolation);
    reportedKeys.clear();
    sentCount = 0;
    if (typeof window !== 'undefined') {
      delete (window as unknown as { __INFLUORA_CSP_REPORTER_READY__?: boolean })
        .__INFLUORA_CSP_REPORTER_READY__;
    }
    if (uninstallCurrent === uninstall) uninstallCurrent = null;
  };
  uninstallCurrent = uninstall;
  return uninstall;
}
