import * as React from 'react';

/**
 * Photo check in Meera's chat, SPEC section 3b "Composer on phones".
 *
 * iOS Safari does not shrink the layout viewport when the keyboard opens, so a full-screen chat
 * sized with `100dvh` keeps its composer BEHIND the keyboard. `visualViewport.height` is the part
 * the creator can actually see; this hook writes it to `--chat-vh` on the given element, and the
 * chat root uses `h-[var(--chat-vh,100dvh)]` below `sm` (Android Chrome gets the same result from
 * the viewport meta's `interactive-widget=resizes-content`, which makes 100dvh follow the
 * keyboard; the variable then simply agrees with it).
 *
 * iOS also PANS the visual viewport to keep a focused input in view (`visualViewport.offsetTop`
 * > 0), which would push a `top: 0` fixed chat's header off the top of the screen. The hook writes
 * that offset to `--chat-vv-top`, and the chat root's `top` follows it on phones (from `sm` up the
 * root is `static`, where `top` does nothing).
 *
 * It also reports `keyboardOpen`: the visible viewport is at least 150px shorter than the TALLEST
 * it has been since mount or the last orientation change (or than the window, if that is taller).
 * Comparing with the window alone is not enough: with `interactive-widget=resizes-content`
 * Android Chrome shrinks `innerHeight` together with the keyboard, so that difference stays near
 * 0. The chat hides its trust line and quick-actions row while the keyboard is up, to give the
 * transcript back that space. A width change (rotation) starts the tallest height over.
 *
 * With no `visualViewport` (older browsers, jsdom) it does nothing: the variables stay unset, the
 * CSS falls back to `100dvh` and `top: 0`, and `keyboardOpen` stays false. Jsdom cannot prove the
 * real keyboards: SPEC section 6's device pass (iPhone Safari, Android Chrome at 375px) does.
 */
export const KEYBOARD_OPEN_MIN_DELTA_PX = 150;

export function useVisualViewportHeight(targetRef: React.RefObject<HTMLElement | null>): { keyboardOpen: boolean } {
  const [keyboardOpen, setKeyboardOpen] = React.useState(false);

  React.useEffect(() => {
    if (typeof window === 'undefined') return;
    const vv = window.visualViewport;
    const el = targetRef.current;
    if (!vv || !el) return;

    let frame = 0;
    let tallest = 0;
    let width = window.innerWidth;
    const apply = () => {
      frame = 0;
      if (Math.abs(window.innerWidth - width) > 1) {
        // Rotated (or resized sideways): the old tallest height means nothing any more.
        width = window.innerWidth;
        tallest = 0;
      }
      tallest = Math.max(tallest, vv.height);
      el.style.setProperty('--chat-vh', `${Math.round(vv.height)}px`);
      el.style.setProperty('--chat-vv-top', `${Math.max(0, Math.round(vv.offsetTop || 0))}px`);
      setKeyboardOpen(Math.max(tallest, window.innerHeight) - vv.height >= KEYBOARD_OPEN_MIN_DELTA_PX);
    };
    const schedule = () => {
      if (frame) return;
      frame = window.requestAnimationFrame(apply);
    };

    apply();
    vv.addEventListener('resize', schedule);
    vv.addEventListener('scroll', schedule);
    window.addEventListener('orientationchange', schedule);
    return () => {
      if (frame) window.cancelAnimationFrame(frame);
      vv.removeEventListener('resize', schedule);
      vv.removeEventListener('scroll', schedule);
      window.removeEventListener('orientationchange', schedule);
      el.style.removeProperty('--chat-vh');
      el.style.removeProperty('--chat-vv-top');
    };
  }, [targetRef]);

  return { keyboardOpen };
}
