/**
 * Copies `text` to the clipboard without assuming a secure context (mirrors the pattern already
 * proven in `creator-portfolio-public.tsx`'s `copyTextToClipboard` — CR-01: Influora can be
 * served over plain HTTP, where `navigator.clipboard` is `undefined`).
 *
 * Callers MUST invoke this synchronously inside the click handler, never after an unrelated
 * `await` — mobile Safari only honours `navigator.clipboard.writeText` when it runs in direct
 * response to a user gesture. Because this is an `async` function, everything up to (and
 * including) the `navigator.clipboard.writeText(text)` call itself still runs synchronously the
 * moment it is invoked — only the code after that first `await` is deferred — so `onClick={() =>
 * copyPlainText(text)}` satisfies that requirement.
 *
 * Returns whether the copy actually happened; callers should not assume success.
 */
export async function copyPlainText(text: string): Promise<boolean> {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (err) {
      console.warn('navigator.clipboard.writeText failed, trying execCommand', err);
    }
  }

  if (typeof document === 'undefined') return false;

  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.top = '0';
  textarea.style.left = '-9999px';
  document.body.appendChild(textarea);
  try {
    textarea.focus();
    textarea.select();
    textarea.setSelectionRange(0, text.length);
    return document.execCommand('copy');
  } catch (err) {
    console.error('execCommand copy fallback failed', err);
    return false;
  } finally {
    document.body.removeChild(textarea);
  }
}
