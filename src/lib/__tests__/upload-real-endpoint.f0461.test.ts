/**
 * F-0461 (mock-in-production-path) — `uploadToR2` real-endpoint spec.
 *
 * SYMPTOM: `uploadToR2` (src/lib/upload.ts) used to be a local mock — it `setTimeout`-slept to
 * fake latency and returned a hand-built `https://r2.influora.com/<folder>/<timestamp>-<name>`
 * string that never touched the network. Brand onboarding (`onboarding-steps.tsx`) persists that
 * return value straight into `workspaces.logo_url`, so every brand that onboarded got a
 * `logo_url` pointing at a host that serves nothing.
 *
 * FIX: `uploadToR2` now delegates to `api.uploads.upload(file, 'brand')` — the same
 * `POST /uploads` PUBLIC-upload path (no `purpose`) the creator avatar upload already uses — and
 * returns the server's real `{ url, key }`.
 *
 * Deliberately does NOT `vi.mock('@/lib/api', ...)` — that would hoist file-wide and replace the
 * very HTTP call this test needs to observe. Stubs `fetch` instead (same technique as
 * `src/lib/__tests__/release-payout-xor.f0489.test.ts` and `src/lib/payments-gate.test.tsx`) so
 * the real `HttpClient.uploadForm` runs and the request that actually reaches the wire, plus the
 * response value that flows back out of `uploadToR2`, can both be inspected.
 *
 * Run: npx vitest run src/lib/__tests__/upload-real-endpoint.f0461.test.ts
 */

import { describe, it, expect, vi, afterEach } from 'vitest';

describe('uploadToR2 — F-0461 real upload endpoint', () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  const SERVER_URL = 'https://real-bucket.r2.cloudflarestorage.com/uploads/brand/logo-9f3a.png';
  const SERVER_KEY = 'uploads/brand/logo-9f3a.png';

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  function stubFetchOk() {
    fetchSpy = vi.fn(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({ success: true, data: { url: SERVER_URL, key: SERVER_KEY } }),
          { status: 200 },
        ),
      ),
    );
    vi.stubGlobal('fetch', fetchSpy);
  }

  async function loadLiveUpload() {
    vi.resetModules();
    vi.stubEnv('VITE_API_MODE', 'live');
    return import('@/lib/upload');
  }

  function makeFile(): File {
    return new File(['fake-image-bytes'], 'logo.png', { type: 'image/png' });
  }

  it('issues a real POST /uploads request instead of never touching the network', async () => {
    stubFetchOk();
    const { uploadToR2 } = await loadLiveUpload();

    // The OLD mock implementation never called fetch at all — it just `setTimeout`'d and
    // returned a fabricated string. This assertion alone fails against that old behaviour.
    await uploadToR2(makeFile(), 'brand-logos');

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(String(url)).toContain('/uploads');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
  });

  it('returns the server-issued URL, not a fabricated r2.influora.com string', async () => {
    stubFetchOk();
    const { uploadToR2 } = await loadLiveUpload();

    const result = await uploadToR2(makeFile(), 'brand-logos');

    expect(result.url).toBe(SERVER_URL);
    expect(result.url.startsWith('https://r2.influora.com/')).toBe(false);
    expect(result.key).toBe(SERVER_KEY);
    expect(result.success).toBe(true);
  });

  it('uses the PUBLIC upload variant — no `purpose` field — since a brand logo is not a KYC document', async () => {
    stubFetchOk();
    const { uploadToR2 } = await loadLiveUpload();

    await uploadToR2(makeFile(), 'brand-logos');

    const [, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    const formData = init.body as FormData;
    expect(formData.has('file')).toBe(true);
    // A `purpose` field would route this through the PRIVATE/presigned-preview path
    // (api.uploads.upload's `purpose` param) — wrong for a public brand-logo asset.
    expect(formData.has('purpose')).toBe(false);
  });

  it('preserves the original filename, size and mime type on the returned UploadResult', async () => {
    stubFetchOk();
    const { uploadToR2 } = await loadLiveUpload();
    const file = makeFile();

    const result = await uploadToR2(file, 'brand-logos');

    expect(result.filename).toBe('logo.png');
    expect(result.mimeType).toBe('image/png');
    expect(result.size).toBe(file.size);
    expect(typeof result.uploadedAt).toBe('string');
  });
});
