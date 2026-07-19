/**
 * Strips Markdown formatting so text-to-speech reads the WORDS, not the
 * punctuation. Meera's replies are Markdown (rendered as rich text in the
 * chat bubble), but that same raw string was being handed verbatim to TTS —
 * so Sarvam (and the browser SpeechSynthesis fallback) literally pronounced
 * "**Product & price**" as "asterisk asterisk Product and price asterisk
 * asterisk", and a "---" divider as "dash dash dash". This converts the
 * markup to plain spoken-friendly prose first.
 *
 * Applied at the single TTS chokepoint (`useVoiceOutput.speak`), so every
 * caller — and the browser-voice fallback path inside that hook — benefits
 * without touching the three call sites in MeeraChatPanel.
 *
 * Deliberately light-touch and meaning-preserving (same discipline as
 * `cleanTranscript`): it removes formatting MARKERS and keeps the inner text
 * exactly as written. It never rewrites words, numbers, ₹ amounts, or
 * @handles. The chat bubble still renders the full Markdown visually — this
 * only affects what gets spoken.
 */
export function stripMarkdownForSpeech(input: string): string {
  if (!input) return input

  let text = input

  // Fenced code blocks (```…```) -> drop the fences, keep inner lines. Reading
  // code aloud is rarely useful, but dropping it entirely would silently lose
  // content, so keep the text without the backtick fence.
  text = text.replace(/```[a-zA-Z0-9]*\n?/g, '').replace(/```/g, '')

  // Inline code `x` -> x
  text = text.replace(/`([^`]+)`/g, '$1')

  // Images ![alt](url) -> alt   (before links, same bracket shape)
  text = text.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')

  // Links [text](url) -> text
  text = text.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')

  // Bold/italic: **x** / __x__ / *x* / _x_  -> x  (run longest markers first)
  text = text.replace(/\*\*\*([^*]+)\*\*\*/g, '$1')
  text = text.replace(/___([^_]+)___/g, '$1')
  text = text.replace(/\*\*([^*]+)\*\*/g, '$1')
  text = text.replace(/__([^_]+)__/g, '$1')
  text = text.replace(/\*([^*]+)\*/g, '$1')
  // Single-underscore italics only when it wraps a word — never touch
  // snake_case identifiers or @handles the brand actually said.
  text = text.replace(/(^|\s)_([^_\s][^_]*?)_(?=\s|$|[.,!?])/g, '$1$2')

  // Strikethrough ~~x~~ -> x
  text = text.replace(/~~([^~]+)~~/g, '$1')

  // Process line by line for block-level markers.
  const lines = text.split(/\r?\n/).map((rawLine) => {
    let line = rawLine

    // Horizontal rules (---, ***, ___ on their own line) -> nothing.
    if (/^\s*([-*_]\s*){3,}$/.test(line)) return ''

    // Heading markers (#, ##, …) -> keep the heading text, drop the hashes.
    line = line.replace(/^\s{0,3}#{1,6}\s+/, '')

    // Blockquote markers (>) -> drop the marker, keep the quote text.
    line = line.replace(/^\s{0,3}>\s?/, '')

    // Unordered list bullets (-, *, +) -> drop the marker so it's not read as
    // "dash"/"asterisk"; the natural sentence pause between lines is enough.
    line = line.replace(/^\s{0,3}[-*+]\s+/, '')

    // Ordered list markers stay as-is ("1." reads naturally as "one").

    return line
  })

  text = lines.join('\n')

  // Collapse 3+ newlines to a paragraph break; trim.
  text = text.replace(/\n{3,}/g, '\n\n').trim()

  return text
}
