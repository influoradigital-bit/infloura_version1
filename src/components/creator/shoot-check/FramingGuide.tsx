/**
 * Rule-of-thirds overlay drawn on top of the live preview. Purely decorative/guidance — it does
 * not read any reading, it just gives the creator a fixed visual reference to frame against.
 * `pointer-events-none` so it never intercepts taps on the video underneath.
 */
export function FramingGuide() {
  return (
    <svg
      viewBox="0 0 300 300"
      preserveAspectRatio="none"
      className="pointer-events-none absolute inset-0 size-full"
      aria-hidden="true"
    >
      <line x1="100" y1="0" x2="100" y2="300" stroke="white" strokeOpacity="0.5" strokeWidth="1" />
      <line x1="200" y1="0" x2="200" y2="300" stroke="white" strokeOpacity="0.5" strokeWidth="1" />
      <line x1="0" y1="100" x2="300" y2="100" stroke="white" strokeOpacity="0.5" strokeWidth="1" />
      <line x1="0" y1="200" x2="300" y2="200" stroke="white" strokeOpacity="0.5" strokeWidth="1" />
    </svg>
  );
}
