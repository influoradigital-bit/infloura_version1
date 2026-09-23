/**
 * F-1789 — CSP violation reporter.
 *
 * jsdom (25.x) does not implement `SecurityPolicyViolationEvent`, so these tests dispatch a
 * plain `Event('securitypolicyviolation')` with the same fields defined on it — the listener
 * only reads fields off the event, so the shape is what matters.
 *
 * `@/lib/api` is mocked like ErrorBoundary.test.tsx: spread the real module, override only
 * `clientErrors.report`, and assert on what the reporter hands it.
 *
 * Run: npx vitest run src/lib/csp-violation-reporter.test.ts
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const { reportMock } = vi.hoisted(() => ({ reportMock: vi.fn().mockResolvedValue(undefined) }));

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    default: {
      ...actual.default,
      clientErrors: {
        ...actual.default.clientErrors,
        report: reportMock,
      },
    },
  };
});

import {
  installCspViolationReporter,
  sanitiseUri,
  MAX_REPORTS_PER_SESSION,
} from './csp-violation-reporter';

interface Fields {
  disposition?: string;
  effectiveDirective?: string;
  blockedURI?: string;
  sourceFile?: string;
  documentURI?: string;
  referrer?: string;
  lineNumber?: number;
  columnNumber?: number;
  statusCode?: number;
  sample?: string;
}

const DOC_SECRET = 'https://influora.in/deals?deal=DOCSECRET123#docfrag';
const REF_SECRET = 'https://accounts.example.com/cb?code=REFSECRET456';

function fire(overrides: Fields = {}): void {
  const fields: Fields = {
    disposition: 'report',
    effectiveDirective: 'script-src-elem',
    blockedURI: 'inline',
    sourceFile: 'https://www.googletagmanager.com/gtm.js?id=GTM-K7LNG26G',
    documentURI: DOC_SECRET,
    referrer: REF_SECRET,
    lineNumber: 12,
    columnNumber: 34,
    statusCode: 200,
    sample: 'window.dataLayer=window.dataLayer||[];fun',
    ...overrides,
  };
  const event = new Event('securitypolicyviolation');
  for (const [k, v] of Object.entries(fields)) {
    Object.defineProperty(event, k, { value: v, enumerable: true });
  }
  document.dispatchEvent(event);
}

function sentBodies(): string[] {
  return reportMock.mock.calls.map((c) => JSON.stringify(c[0]));
}

let uninstall: () => void = () => {};

describe('installCspViolationReporter (F-1789)', () => {
  beforeEach(() => {
    reportMock.mockReset();
    reportMock.mockResolvedValue(undefined);
    window.history.pushState({}, '', '/deals/42?deal=QUERYSECRET#hashsecret');
    uninstall = installCspViolationReporter();
  });

  afterEach(() => {
    uninstall();
  });

  it('sends one report whose message contains "CSP"', () => {
    fire();
    expect(reportMock).toHaveBeenCalledTimes(1);
    const payload = reportMock.mock.calls[0][0];
    expect(payload.message).toMatch(/CSP/);
    expect(payload.message).toBe('CSP report: script-src-elem blocked inline');
    expect(payload.componentStack).toBeNull();
    expect(Object.keys(payload).sort()).toEqual(
      ['componentStack', 'message', 'pathname', 'stack'].sort(),
    );
  });

  it('builds a deterministic key=value stack summary', () => {
    fire();
    expect(reportMock.mock.calls[0][0].stack).toBe(
      [
        'disposition=report',
        'effectiveDirective=script-src-elem',
        'blocked=inline',
        'source=https://www.googletagmanager.com/gtm.js:12:34',
        'statusCode=200',
        // 41-char sample truncated to 40.
        'sample="window.dataLayer=window.dataLayer||[];fu"',
      ].join('\n'),
    );
  });

  it('keeps the "inline" keyword as-is', () => {
    fire({ blockedURI: 'inline' });
    expect(reportMock.mock.calls[0][0].message).toContain('blocked inline');
  });

  it('strips query and fragment from blockedURI', () => {
    fire({ blockedURI: 'https://evil.example.com/x.js?token=abc#frag' });
    const body = sentBodies()[0];
    expect(reportMock.mock.calls[0][0].message).toBe(
      'CSP report: script-src-elem blocked https://evil.example.com/x.js',
    );
    expect(body).not.toContain('token=abc');
    expect(body).not.toContain('frag');
    expect(body).not.toContain('?');
  });

  it('strips query and fragment from sourceFile', () => {
    fire({ sourceFile: 'https://cdn.example.com/app.js?token=abc#frag' });
    const body = sentBodies()[0];
    expect(reportMock.mock.calls[0][0].stack).toContain('source=https://cdn.example.com/app.js:12:34');
    expect(body).not.toContain('token=abc');
    expect(body).not.toContain('#frag');
    expect(body).not.toContain('GTM-K7LNG26G');
  });

  it('never sends documentURI or referrer', () => {
    fire({ blockedURI: 'https://a.example.com/one.js' });
    fire({ blockedURI: 'eval', effectiveDirective: 'script-src' });
    expect(reportMock).toHaveBeenCalledTimes(2);
    for (const body of sentBodies()) {
      expect(body).not.toContain('DOCSECRET123');
      expect(body).not.toContain('docfrag');
      expect(body).not.toContain('REFSECRET456');
      expect(body).not.toContain('accounts.example.com');
      expect(body).not.toContain('documentURI');
      expect(body).not.toContain('referrer');
    }
  });

  it('sends location.pathname only', () => {
    fire();
    const payload = reportMock.mock.calls[0][0];
    expect(payload.pathname).toBe('/deals/42');
    const body = sentBodies()[0];
    expect(body).not.toContain('QUERYSECRET');
    expect(body).not.toContain('hashsecret');
  });

  it('reports the same violation twice only once', () => {
    fire();
    fire();
    expect(reportMock).toHaveBeenCalledTimes(1);
  });

  it('caps a page session at 10 reports across 11 distinct violations', () => {
    for (let i = 0; i < 11; i++) {
      fire({ blockedURI: `https://host${i}.example.com/s.js` });
    }
    expect(MAX_REPORTS_PER_SESSION).toBe(10);
    expect(reportMock).toHaveBeenCalledTimes(10);
  });

  it('installing twice does not double-report', () => {
    const second = installCspViolationReporter();
    expect(second).toBe(uninstall);
    fire();
    expect(reportMock).toHaveBeenCalledTimes(1);
  });

  it('a send that throws synchronously does not escape the listener', () => {
    reportMock.mockImplementation(() => {
      throw new Error('send exploded');
    });
    const onError = vi.fn();
    window.addEventListener('error', onError);
    try {
      expect(() => fire()).not.toThrow();
      expect(reportMock).toHaveBeenCalledTimes(1);
      expect(onError).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('error', onError);
    }
  });

  it('a send that rejects is swallowed', async () => {
    reportMock.mockRejectedValue(new Error('network down'));
    expect(() => fire()).not.toThrow();
    await Promise.resolve();
    await Promise.resolve();
    expect(reportMock).toHaveBeenCalledTimes(1);
  });

  it('does not report a violation that blocked the crash-report endpoint itself', () => {
    fire({
      effectiveDirective: 'connect-src',
      blockedURI: 'https://api.influora.in/api/v1/client-errors',
    });
    expect(reportMock).not.toHaveBeenCalled();
  });
});

describe('sanitiseUri', () => {
  it.each([
    ['inline', 'inline'],
    ['eval', 'eval'],
    ['wasm-eval', 'wasm-eval'],
    ['trusted-types-policy', 'trusted-types-policy'],
    ['trusted-types-sink', 'trusted-types-sink'],
    ['data', 'data'],
    ['blob', 'blob'],
    ['self', 'self'],
    ['', ''],
    ['https://x.example.com/a/b.js?q=1#h', 'https://x.example.com/a/b.js'],
    ['wss://x.example.com/sock?token=1', 'wss://x.example.com/sock'],
    ['data:text/javascript,alert(1)?q=1', 'data:'],
    ['blob:https://influora.in/uuid-1234', 'blob:'],
    ['not a url ?token=abc', 'unparseable'],
  ])('%j -> %j', (input, expected) => {
    expect(sanitiseUri(input)).toBe(expected);
  });

  it('non-string input becomes the empty keyword', () => {
    expect(sanitiseUri(undefined)).toBe('');
    expect(sanitiseUri(42)).toBe('');
  });

  it('drops matrix parameters, which carry session state in the path', () => {
    expect(sanitiseUri('https://host.example/a;jsessionid=SECRET?x=1#f')).toBe('https://host.example/a');
  });
});

// ---------------------------------------------------------------------------------------------
// Review fixes: stage distinguishability, early buffer hand-off, origin-level dedupe.
// ---------------------------------------------------------------------------------------------
type CspWindow = Window & {
  __INFLUORA_CSP_BUFFER__?: unknown[];
  __INFLUORA_CSP_REPORTER_READY__?: boolean;
};

describe('F-1789 review fixes', () => {
  let off: () => void = () => {};

  beforeEach(() => {
    reportMock.mockReset();
    reportMock.mockResolvedValue(undefined);
    delete (window as CspWindow).__INFLUORA_CSP_BUFFER__;
    delete (window as CspWindow).__INFLUORA_CSP_REPORTER_READY__;
  });

  afterEach(() => {
    off();
    off = () => {};
  });

  it('keeps stage 1 (report) and stage 2 (enforce) distinguishable', () => {
    off = installCspViolationReporter();
    fire({ disposition: 'report' });
    fire({ disposition: 'enforce' });

    const messages = reportMock.mock.calls.map((c) => c[0].message as string);
    expect(messages).toHaveLength(2);
    expect(messages[0]).toMatch(/^CSP report: /);
    expect(messages[1]).toMatch(/^CSP enforce: /);
    expect(reportMock.mock.calls[1][0].stack).toContain('disposition=enforce');
  });

  it('drains violations buffered before install, then stops the early buffer', () => {
    const w = window as CspWindow;
    w.__INFLUORA_CSP_BUFFER__ = [
      {
        disposition: 'report',
        effectiveDirective: 'script-src-elem',
        blockedURI: 'inline',
        sourceFile: 'https://www.googletagmanager.com/gtm.js?id=GTM-K7LNG26G',
        sample: '!function(b,e,f,g,a,c,d){b.fbq||(a=b.fbq=',
      },
    ];

    off = installCspViolationReporter();

    expect(reportMock).toHaveBeenCalledTimes(1);
    expect(reportMock.mock.calls[0][0].message).toBe('CSP report: script-src-elem blocked inline');
    expect(reportMock.mock.calls[0][0].stack).not.toContain('GTM-K7LNG26G');
    expect(w.__INFLUORA_CSP_BUFFER__).toHaveLength(0);
    expect(w.__INFLUORA_CSP_REPORTER_READY__).toBe(true);
  });

  it('dedupes by origin, so many paths on one host use one slot, not all ten', () => {
    off = installCspViolationReporter();
    for (let i = 0; i < 12; i++) {
      fire({ effectiveDirective: 'img-src', blockedURI: `https://cdn.blocked.example/img/${i}.jpg` });
    }
    fire({ effectiveDirective: 'script-src-elem', blockedURI: 'inline' });

    expect(reportMock).toHaveBeenCalledTimes(2);
    expect(reportMock.mock.calls[1][0].message).toBe('CSP report: script-src-elem blocked inline');
  });
});

describe('public/csp-violation-buffer.js (F-1789 early buffer)', () => {
  const load = () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const fs = require('node:fs') as typeof import('node:fs');
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const path = require('node:path') as typeof import('node:path');
    const src = fs.readFileSync(path.resolve(__dirname, '../../public/csp-violation-buffer.js'), 'utf8');
    new Function(src)();
  };

  beforeEach(() => {
    delete (window as CspWindow).__INFLUORA_CSP_BUFFER__;
    delete (window as CspWindow).__INFLUORA_CSP_REPORTER_READY__;
  });

  it('buffers without ever copying documentURI or referrer, and caps at 20', () => {
    load();
    for (let i = 0; i < 25; i++) fire({ blockedURI: `https://h${i}.example/x` });
    const buf = (window as CspWindow).__INFLUORA_CSP_BUFFER__ as Record<string, unknown>[];

    expect(buf).toHaveLength(20);
    const serialised = JSON.stringify(buf);
    expect(serialised).not.toContain('DOCSECRET123');
    expect(serialised).not.toContain('REFSECRET456');
    expect(Object.keys(buf[0])).not.toContain('documentURI');
    expect(Object.keys(buf[0])).not.toContain('referrer');
  });

  it('stops buffering once the reporter has taken over', () => {
    load();
    fire();
    (window as CspWindow).__INFLUORA_CSP_REPORTER_READY__ = true;
    fire({ blockedURI: 'https://later.example/x' });
    expect((window as CspWindow).__INFLUORA_CSP_BUFFER__).toHaveLength(1);
  });
});
