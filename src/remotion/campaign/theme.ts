/**
 * Geometry + type scale for the landscape "How to create a campaign" demo.
 *
 * Colours come from the shared `../theme` (which mirrors `src/app/globals.css`
 * by value). Only the things that differ from the portrait Meera demo live
 * here: this composition is 1920x1080 and draws a desktop browser, not a phone.
 */

/** Composition geometry: landscape 1920x1080 at 30 fps. */
export const CAMPAIGN_VIDEO = {
  width: 1920,
  height: 1080,
  fps: 30,
} as const;

/** Desktop browser window drawn inside the canvas. */
export const BROWSER = {
  width: 1560,
  height: 760,
  radius: 20,
  /** Height of the tab/URL chrome strip above the viewport. */
  chrome: 62,
  /** Top edge of the window within the 1080px canvas. */
  top: 200,
} as const;

export const VIEWPORT = {
  width: BROWSER.width,
  height: BROWSER.height - BROWSER.chrome,
} as const;

/** Width of the left step rail inside the app viewport. */
export const RAIL_WIDTH = 400;

/**
 * Type scale. The video-layout rule (84px headline at 1080 wide) scales to
 * ~150px at 1920, which applies to full-frame title cards. Chrome inside the
 * browser mock is deliberately smaller — it has to read as a real UI — but is
 * still ~2x a real screenshot so it survives compression.
 */
export const TYPE = {
  hookTitle: 132,
  hookSub: 56,
  caption: 54,
  captionKicker: 30,
  stepHeading: 40,
  label: 26,
  input: 30,
  helper: 22,
  chip: 26,
} as const;
