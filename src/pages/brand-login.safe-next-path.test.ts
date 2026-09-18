import { describe, expect, it } from 'vitest';
import { safeNextPath } from './brand-login';

// `?next=` comes straight off the URL, so it is an open-redirect surface unless it is pinned to
// plain in-app brand paths. The invite page is the one real caller today.
describe('safeNextPath', () => {
  it('keeps an in-app brand path, query string included', () => {
    expect(safeNextPath('/brand/invite?token=abc123')).toBe('/brand/invite?token=abc123');
    expect(safeNextPath('/brand/campaigns/01HX/edit')).toBe('/brand/campaigns/01HX/edit');
  });

  it('drops anything that could leave the app or the brand area', () => {
    for (const bad of [
      null,
      '',
      'https://evil.example/brand/invite',
      '//evil.example/brand/',
      '/brand//evil.example',
      '/brand/\\evil.example',
      '/creator/dashboard',
      '/admin',
      'brand/invite',
      'javascript:alert(1)',
    ]) {
      expect(safeNextPath(bad), String(bad)).toBeNull();
    }
  });

  // Kavya's review: the first cut only looked at literal characters, so an escaped traversal
  // sailed through — it starts with /brand/ and contains no literal "..", yet names /admin.
  it('drops a traversal however it is escaped', () => {
    for (const bad of [
      '/brand/../admin',
      '/brand/%2e%2e/admin',
      '/brand/%2e%2e%2fadmin',
      '/brand/%2E%2E%2Fadmin',
      '/brand/%252e%252e%252fadmin', // double-encoded
      '/brand/%25252e%25252e/admin', // still escaped after two passes -> not a path we generate
      '/brand/%5cevil.example', // escaped backslash
      '/brand/%2f%2fevil.example', // escaped protocol-relative
      '/brand/%', // does not decode at all
    ]) {
      expect(safeNextPath(bad), bad).toBeNull();
    }
  });

  it('never decodes the query string — an invite token may contain escapes', () => {
    const next = '/brand/invite?token=ab%2Bcd%3D%3D';
    expect(safeNextPath(next)).toBe(next);
  });
});
