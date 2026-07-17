/**
 * Ambient shim for the Web Speech API's SpeechRecognition interface.
 * Not in the default TS DOM lib. This is the ONE allowed typed exception
 * (Priya's voice handoff §6) — no `as any` casts anywhere else.
 *
 * Covers only the surface `useVoiceInput` actually uses.
 */

interface SpeechRecognitionErrorEvent extends Event {
  readonly error: string
  readonly message: string
}

interface SpeechRecognitionAlternative {
  readonly transcript: string
  readonly confidence: number
}

interface SpeechRecognitionResult {
  readonly isFinal: boolean
  readonly length: number
  item(index: number): SpeechRecognitionAlternative
  [index: number]: SpeechRecognitionAlternative
}

interface SpeechRecognitionResultList {
  readonly length: number
  item(index: number): SpeechRecognitionResult
  [index: number]: SpeechRecognitionResult
}

interface SpeechRecognitionEvent extends Event {
  readonly resultIndex: number
  readonly results: SpeechRecognitionResultList
}

interface SpeechRecognition extends EventTarget {
  lang: string
  continuous: boolean
  interimResults: boolean
  maxAlternatives: number

  onaudiostart: ((this: SpeechRecognition, ev: Event) => void) | null
  onresult: ((this: SpeechRecognition, ev: SpeechRecognitionEvent) => void) | null
  onerror: ((this: SpeechRecognition, ev: SpeechRecognitionErrorEvent) => void) | null
  onend: ((this: SpeechRecognition, ev: Event) => void) | null
  onstart: ((this: SpeechRecognition, ev: Event) => void) | null
  onspeechend: ((this: SpeechRecognition, ev: Event) => void) | null
  onnomatch: ((this: SpeechRecognition, ev: Event) => void) | null

  start(): void
  stop(): void
  abort(): void
}

interface SpeechRecognitionStatic {
  new (): SpeechRecognition
}

interface Window {
  SpeechRecognition?: SpeechRecognitionStatic
  webkitSpeechRecognition?: SpeechRecognitionStatic
}
