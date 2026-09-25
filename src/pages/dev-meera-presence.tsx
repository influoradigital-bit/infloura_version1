import { useState } from 'react';
import { Mic, MicOff } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { VoicePoweredOrb } from '@/components/ui/voice-powered-orb';
import { VoiceChat, type VoiceChatStatus } from '@/components/ui/ia-siri-chat';
import { GlowingInput } from '@/components/ui/glowing-input';
import { MeeraVoiceMode } from '@/components/creator/meera/MeeraVoiceMode';
import { MeeraHero } from '@/components/creator/meera/MeeraHero';
import { cn } from '@/lib/utils';

/**
 * DEV-ONLY preview of Meera's presence (route /dev/meera-presence, mounted only when
 * import.meta.env.DEV). Lets the team judge how the orb and the voice screen look in every state
 * before they are used anywhere else. Nothing here talks to the API.
 */
const STATES: VoiceChatStatus[] = ['idle', 'listening', 'thinking', 'speaking'];
const ACTIVITY: Record<VoiceChatStatus, number> = { idle: 0.08, listening: 0.5, thinking: 0.35, speaking: 0.7 };

export default function DevMeeraPresencePage() {
  const [status, setStatus] = useState<VoiceChatStatus>('idle');
  const [micOn, setMicOn] = useState(false);
  const [voiceDetected, setVoiceDetected] = useState(false);
  const [hue, setHue] = useState(0);
  const [asked, setAsked] = useState<string | null>(null);
  const [voiceOpen, setVoiceOpen] = useState(false);
  const [transcript, setTranscript] = useState('');

  return (
    <main className="min-h-screen bg-background px-4 py-10 text-foreground">
      <div className="mx-auto max-w-5xl space-y-10">
        <header className="space-y-1">
          <h1 className="text-2xl font-semibold">Meera presence (preview)</h1>
          <p className="text-sm text-muted-foreground">
            Dev-only. Pick a state to see how Meera looks. The microphone opens only when you press
            &ldquo;Use my voice&rdquo;.
          </p>
        </header>

        <div className="flex flex-wrap gap-2" role="group" aria-label="Meera state">
          {STATES.map((s) => (
            <Button key={s} variant={status === s ? 'default' : 'outline'} size="sm" onClick={() => setStatus(s)}>
              {s}
            </Button>
          ))}
          <Button variant={micOn ? 'default' : 'outline'} size="sm" onClick={() => setMicOn((v) => !v)}>
            {micOn ? <MicOff className="mr-2 h-4 w-4" /> : <Mic className="mr-2 h-4 w-4" />}
            {micOn ? 'Stop my voice' : 'Use my voice'}
          </Button>
          <Button size="sm" onClick={() => setVoiceOpen(true)}>
            Open voice mode
          </Button>
          <Button size="sm" variant="outline" onClick={() => setTranscript((t) => (t ? '' : 'What should I post this week?'))}>
            {transcript ? 'Clear transcript' : 'Fake a transcript'}
          </Button>
          <label className="ml-2 flex items-center gap-2 text-sm text-muted-foreground">
            Hue
            <input type="range" min={-180} max={180} value={hue} onChange={(e) => setHue(Number(e.target.value))} />
          </label>
        </div>

        <section className="grid gap-8 md:grid-cols-2">
          <div className="space-y-3">
            <h2 className="text-sm font-medium text-muted-foreground">Orb, full size</h2>
            <div className="mx-auto aspect-square w-full max-w-sm rounded-2xl border border-border bg-card">
              <VoicePoweredOrb
                hue={hue}
                enableVoiceControl={micOn}
                activity={ACTIVITY[status]}
                onVoiceDetected={setVoiceDetected}
              />
            </div>
            <p className={cn('text-center text-sm', voiceDetected ? 'text-blue-700' : 'text-muted-foreground')}>
              {micOn ? (voiceDetected ? 'Hearing you' : 'Mic on — say something') : `State: ${status}`}
            </p>
          </div>

          <div className="space-y-3">
            <h2 className="text-sm font-medium text-muted-foreground">Voice screen</h2>
            <VoiceChat
              status={status}
              onToggle={() => setStatus((s) => (s === 'listening' ? 'thinking' : 'listening'))}
              className="min-h-[26rem] rounded-2xl border border-border"
            />
          </div>
        </section>

        <section className="space-y-3">
          <h2 className="text-sm font-medium text-muted-foreground">Hero on the Meera page</h2>
          <MeeraHero firstName="Rohit" onAsk={(q) => setAsked(q)} onOpen={() => setVoiceOpen(true)} />
        </section>

        <MeeraVoiceMode
          open={voiceOpen}
          onOpenChange={setVoiceOpen}
          status={status}
          transcript={transcript}
          lastReply="Try a form-check reel this week: open on the movement, then show the fix."
          micSupported
          onMicToggle={() => setStatus((s) => (s === 'listening' ? 'thinking' : 'listening'))}
          onSend={() => {
            setTranscript('');
            setStatus('thinking');
          }}
          onSpeakAgain={() => {
            setTranscript('');
            setStatus('listening');
          }}
        />

        <section className="space-y-3">
          <h2 className="text-sm font-medium text-muted-foreground">&ldquo;Ask Meera&rdquo; bar</h2>
          <div className="flex flex-col items-center gap-3 overflow-hidden rounded-2xl bg-slate-950 px-4 py-10">
            <GlowingInput
              placeholder="Ask Meera: hook ideas for my next reel…"
              onSubmit={(q) => {
                setAsked(q);
                setStatus('thinking');
              }}
            />
            <p className="text-sm text-slate-300" aria-live="polite">
              {asked ? `You asked: “${asked}”` : 'Type a question and press Enter.'}
            </p>
          </div>
        </section>

        <section className="space-y-3">
          <h2 className="text-sm font-medium text-muted-foreground">In the chat header (40px)</h2>
          <div className="flex items-center gap-3 rounded-xl border border-border bg-card px-4 py-3 sm:max-w-sm">
            <div className="h-10 w-10 shrink-0">
              <VoicePoweredOrb hue={hue} activity={ACTIVITY[status]} />
            </div>
            <div>
              <p className="text-sm font-semibold">Meera</p>
              <p className="text-xs text-muted-foreground">
                {status === 'idle' ? 'Your AI manager' : `${status[0].toUpperCase()}${status.slice(1)}…`}
              </p>
            </div>
          </div>
        </section>

        <section className="space-y-3">
          <h2 className="text-sm font-medium text-muted-foreground">Voice screen, self-running demo</h2>
          <VoiceChat demoMode className="min-h-[22rem] rounded-2xl border border-border" />
        </section>
      </div>
    </main>
  );
}
