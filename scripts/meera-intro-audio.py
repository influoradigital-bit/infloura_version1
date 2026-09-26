"""
Synthesises the audio for the Meera intro film (src/remotion/intro/).

Everything is generated from sine waves and filtered noise, so there is no
licensed track to track down later. Writes WAV to a temp dir and encodes to
MP3 with ffmpeg into public/meera-intro/:

  music.mp3   40 s ambient pad, chord change on every beat, swell into the finale
  whoosh.mp3  1.2 s filtered-noise sweep, one per feature beat
  chime.mp3   1.6 s soft bell, when the Meera symbol completes
  riser.mp3   2.4 s shimmer rising into the finale

The beat times below mirror src/remotion/intro/timeline.ts (30 fps). If the
timeline changes, update BEAT_STARTS_S / FINALE_S here and re-run:

  python scripts/meera-intro-audio.py
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "public", "meera-intro")

TOTAL_S = 40.0
GENESIS_S = 6.0
BEAT_S = 3.8
BEAT_COUNT = 7
BEAT_STARTS_S = [GENESIS_S + i * BEAT_S for i in range(BEAT_COUNT)]
FINALE_S = GENESIS_S + BEAT_COUNT * BEAT_S  # 32.6

rng = np.random.default_rng(7)


def t_axis(seconds):
    return np.arange(int(seconds * SR)) / SR


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def pad_voice(freq, seconds):
    """Soft detuned saw-ish pad: a few sine harmonics, two slightly detuned copies."""
    t = t_axis(seconds)
    out = np.zeros_like(t)
    for detune in (-0.12, 0.12):
        f = freq * 2 ** (detune / 12)
        for h, amp in ((1, 1.0), (2, 0.35), (3, 0.15), (4, 0.06)):
            out += amp * np.sin(2 * np.pi * f * h * t + rng.uniform(0, 2 * np.pi))
    return out


def envelope(n, attack, release):
    env = np.ones(n)
    a = int(attack * SR)
    r = int(release * SR)
    if a:
        env[:a] = np.linspace(0, 1, a) ** 2
    if r:
        env[-r:] *= np.linspace(1, 0, r) ** 2
    return env


def lowpass(x, cutoff):
    """One-pole low-pass, cheap and smooth enough for pads."""
    alpha = 1 - np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc += alpha * (v - acc)
        y[i] = acc
    return y


def music():
    n = int(TOTAL_S * SR)
    out = np.zeros(n)
    # D major-ish progression, open voicings (MIDI). Genesis holds a single drone.
    progression = [
        [50, 57, 62, 66],  # D
        [47, 54, 62, 66],  # Bm
        [43, 50, 59, 62],  # G
        [45, 52, 57, 64],  # A
        [50, 57, 64, 69],  # D add9
        [47, 54, 59, 66],  # Bm
        [43, 55, 62, 71],  # G high
    ]
    # Genesis: low drone with a slow fade-in
    drone = pad_voice(hz(38), GENESIS_S + 1.0) * 0.5 + pad_voice(hz(50), GENESIS_S + 1.0) * 0.25
    drone *= envelope(len(drone), 3.5, 1.0)
    out[: len(drone)] += drone
    # One chord per beat, overlapping by a second for a smooth crossfade
    for start, chord in zip(BEAT_STARTS_S, progression):
        seg_s = BEAT_S + 1.2
        seg = sum(pad_voice(hz(m), seg_s) for m in chord) / len(chord)
        seg *= envelope(len(seg), 0.9, 1.2)
        i = int(start * SR)
        out[i : i + len(seg)] += seg[: n - i]
    # Finale: big open D chord that swells and rings out
    fin_s = TOTAL_S - FINALE_S
    fin_chord = [38, 50, 57, 62, 66, 69, 74]
    fin = sum(pad_voice(hz(m), fin_s) for m in fin_chord) / len(fin_chord) * 1.4
    fin *= envelope(len(fin), 1.2, 2.5)
    i = int(FINALE_S * SR)
    out[i : i + len(fin)] += fin[: n - i]
    # Slow tremolo for air, then soften the top end
    t = t_axis(TOTAL_S)
    out *= 0.85 + 0.15 * np.sin(2 * np.pi * 0.25 * t)
    out = lowpass(out, 2200)
    # A gentle shimmer an octave up, very quiet
    shimmer = np.sin(2 * np.pi * hz(86) * t) * 0.02 * (0.5 + 0.5 * np.sin(2 * np.pi * 0.13 * t))
    out += shimmer * envelope(n, 6.0, 3.0)
    out *= envelope(n, 0.5, 2.0)
    return out


def whoosh():
    seconds = 1.2
    t = t_axis(seconds)
    noise = rng.standard_normal(len(t))
    # Sweep a resonant band from low to high by crossfading two low-passes
    lo = lowpass(noise, 400)
    hi = noise - lowpass(noise, 1800)
    sweep = np.clip(t / seconds, 0, 1)
    out = lo * (1 - sweep) + hi * sweep * 0.5
    env = np.sin(np.pi * np.clip(t / seconds, 0, 1)) ** 1.5
    return out * env


def chime():
    seconds = 1.6
    t = t_axis(seconds)
    out = np.zeros_like(t)
    for ratio, amp, decay in ((1.0, 1.0, 2.2), (2.76, 0.4, 3.5), (5.4, 0.2, 5.0), (2.0, 0.3, 2.8)):
        out += amp * np.sin(2 * np.pi * hz(81) * ratio * t) * np.exp(-decay * t)
    return out * envelope(len(t), 0.004, 0.2)


def riser():
    seconds = 2.4
    t = t_axis(seconds)
    f = hz(62) * 2 ** (2 * t / seconds)  # up two octaves
    phase = 2 * np.pi * np.cumsum(f) / SR
    tone = np.sin(phase) * 0.5 + np.sin(phase * 2) * 0.2
    air = rng.standard_normal(len(t))
    air = air - lowpass(air, 3000)
    out = tone + air * 0.25
    return out * (t / seconds) ** 2 * envelope(len(t), 0.0, 0.08)


def normalise(x, peak):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    tracks = {
        "music": (normalise(music(), 0.7), "96k"),
        "whoosh": (normalise(whoosh(), 0.8), "96k"),
        "chime": (normalise(chime(), 0.6), "96k"),
        "riser": (normalise(riser(), 0.6), "96k"),
    }
    with tempfile.TemporaryDirectory() as tmp:
        for name, (samples, bitrate) in tracks.items():
            wav = os.path.join(tmp, f"{name}.wav")
            write_wav(wav, samples)
            mp3 = os.path.abspath(os.path.join(OUT_DIR, f"{name}.mp3"))
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-codec:a", "libmp3lame", "-b:a", bitrate, mp3],
                check=True,
            )
            print(f"{name}: {len(samples) / SR:.1f}s -> {mp3}")


if __name__ == "__main__":
    main()
