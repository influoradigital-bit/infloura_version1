import { useEffect, useRef, type FC } from 'react';
import { Mesh, Program, Renderer, Triangle, Vec3 } from 'ogl';
import { cn } from '@/lib/utils';

/**
 * Meera's presence orb (WebGL, via ogl). Adapted from a community "voice powered orb" component.
 *
 * Changes from the original, all deliberate:
 * - The microphone is OFF by default and is opened only while `enableVoiceControl` is true, once
 *   (the original opened it on mount, from two effects at once). Pass the app's real listening
 *   state, so the browser permission prompt only ever follows the creator pressing the mic.
 * - `activity` (0..1) animates the orb from app state when the mic is not in use: thinking,
 *   speaking. Nothing here invents audio data.
 * - The WebGL scene is built once; hue / activity / sensitivity are read from refs each frame
 *   instead of rebuilding the renderer on every prop change.
 * - Falls back to a static CSS gradient when WebGL is unavailable, and slows to a gentle drift
 *   under prefers-reduced-motion.
 */
export interface VoicePoweredOrbProps {
  className?: string;
  /** Hue shift in degrees applied to the brand-violet palette. */
  hue?: number;
  /** Open the microphone and let the creator's voice drive the orb. Default false. */
  enableVoiceControl?: boolean;
  /** 0..1 animation level from app state (thinking, speaking) when the mic is not in use. */
  activity?: number;
  voiceSensitivity?: number;
  maxRotationSpeed?: number;
  maxHoverIntensity?: number;
  onVoiceDetected?: (detected: boolean) => void;
}

const VERT = /* glsl */ `
  precision highp float;
  attribute vec2 position;
  attribute vec2 uv;
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = vec4(position, 0.0, 1.0);
  }
`;

const FRAG = /* glsl */ `
  precision highp float;
  uniform float iTime;
  uniform vec3 iResolution;
  uniform float hue;
  uniform float hover;
  uniform float rot;
  uniform float hoverIntensity;
  varying vec2 vUv;

  vec3 rgb2yiq(vec3 c) {
    float y = dot(c, vec3(0.299, 0.587, 0.114));
    float i = dot(c, vec3(0.596, -0.274, -0.322));
    float q = dot(c, vec3(0.211, -0.523, 0.312));
    return vec3(y, i, q);
  }
  vec3 yiq2rgb(vec3 c) {
    return vec3(c.x + 0.956 * c.y + 0.621 * c.z,
                c.x - 0.272 * c.y - 0.647 * c.z,
                c.x - 1.106 * c.y + 1.703 * c.z);
  }
  vec3 adjustHue(vec3 color, float hueDeg) {
    float hueRad = hueDeg * 3.14159265 / 180.0;
    vec3 yiq = rgb2yiq(color);
    float cosA = cos(hueRad);
    float sinA = sin(hueRad);
    float i = yiq.y * cosA - yiq.z * sinA;
    float q = yiq.y * sinA + yiq.z * cosA;
    yiq.y = i;
    yiq.z = q;
    return yiq2rgb(yiq);
  }
  vec3 hash33(vec3 p3) {
    p3 = fract(p3 * vec3(0.1031, 0.11369, 0.13787));
    p3 += dot(p3, p3.yxz + 19.19);
    return -1.0 + 2.0 * fract(vec3(p3.x + p3.y, p3.x + p3.z, p3.y + p3.z) * p3.zyx);
  }
  float snoise3(vec3 p) {
    const float K1 = 0.333333333;
    const float K2 = 0.166666667;
    vec3 i = floor(p + (p.x + p.y + p.z) * K1);
    vec3 d0 = p - (i - (i.x + i.y + i.z) * K2);
    vec3 e = step(vec3(0.0), d0 - d0.yzx);
    vec3 i1 = e * (1.0 - e.zxy);
    vec3 i2 = 1.0 - e.zxy * (1.0 - e);
    vec3 d1 = d0 - (i1 - K2);
    vec3 d2 = d0 - (i2 - K1);
    vec3 d3 = d0 - 0.5;
    vec4 h = max(0.6 - vec4(dot(d0, d0), dot(d1, d1), dot(d2, d2), dot(d3, d3)), 0.0);
    vec4 n = h * h * h * h * vec4(dot(d0, hash33(i)), dot(d1, hash33(i + i1)),
                                  dot(d2, hash33(i + i2)), dot(d3, hash33(i + 1.0)));
    return dot(vec4(31.316), n);
  }
  vec4 extractAlpha(vec3 colorIn) {
    float a = max(max(colorIn.r, colorIn.g), colorIn.b);
    return vec4(colorIn.rgb / (a + 1e-5), a);
  }
  // Brand violets (60-30-10: the orb is part of the 10% accent): #7c6af0, #b0a3f5, #2a1f6b.
  const vec3 baseColor1 = vec3(0.486275, 0.415686, 0.941176);
  const vec3 baseColor2 = vec3(0.690196, 0.639216, 0.960784);
  const vec3 baseColor3 = vec3(0.164706, 0.121569, 0.419608);
  const float innerRadius = 0.6;
  const float noiseScale = 0.65;
  float light1(float intensity, float attenuation, float dist) {
    return intensity / (1.0 + dist * attenuation);
  }
  float light2(float intensity, float attenuation, float dist) {
    return intensity / (1.0 + dist * dist * attenuation);
  }
  vec4 draw(vec2 uv) {
    vec3 color1 = adjustHue(baseColor1, hue);
    vec3 color2 = adjustHue(baseColor2, hue);
    vec3 color3 = adjustHue(baseColor3, hue);
    float ang = atan(uv.y, uv.x);
    float len = length(uv);
    float invLen = len > 0.0 ? 1.0 / len : 0.0;
    float n0 = snoise3(vec3(uv * noiseScale, iTime * 0.5)) * 0.5 + 0.5;
    float r0 = mix(mix(innerRadius, 1.0, 0.4), mix(innerRadius, 1.0, 0.6), n0);
    float d0 = distance(uv, (r0 * invLen) * uv);
    float v0 = light1(1.0, 10.0, d0);
    v0 *= smoothstep(r0 * 1.05, r0, len);
    float cl = cos(ang + iTime * 2.0) * 0.5 + 0.5;
    float a = iTime * -1.0;
    vec2 pos = vec2(cos(a), sin(a)) * r0;
    float d = distance(uv, pos);
    float v1 = light2(1.5, 5.0, d);
    v1 *= light1(1.0, 50.0, d0);
    float v2 = smoothstep(1.0, mix(innerRadius, 1.0, n0 * 0.5), len);
    float v3 = smoothstep(innerRadius, mix(innerRadius, 1.0, 0.5), len);
    vec3 col = mix(color1, color2, cl);
    col = mix(color3, col, v0);
    col = (col + v1) * v2 * v3;
    col = clamp(col, 0.0, 1.0);
    return extractAlpha(col);
  }
  vec4 mainImage(vec2 fragCoord) {
    vec2 center = iResolution.xy * 0.5;
    float size = min(iResolution.x, iResolution.y);
    vec2 uv = (fragCoord - center) / size * 2.0;
    float s = sin(rot);
    float c = cos(rot);
    uv = vec2(c * uv.x - s * uv.y, s * uv.x + c * uv.y);
    uv.x += hover * hoverIntensity * 0.1 * sin(uv.y * 10.0 + iTime);
    uv.y += hover * hoverIntensity * 0.1 * sin(uv.x * 10.0 + iTime);
    return draw(uv);
  }
  void main() {
    vec2 fragCoord = vUv * iResolution.xy;
    vec4 col = mainImage(fragCoord);
    gl_FragColor = vec4(col.rgb * col.a, col.a);
  }
`;

function webglAvailable(): boolean {
  // No WebGL constructor at all (old browsers, jsdom in tests): skip touching a canvas.
  if (typeof WebGLRenderingContext === 'undefined') return false;
  try {
    const canvas = document.createElement('canvas');
    return !!(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}

export const VoicePoweredOrb: FC<VoicePoweredOrbProps> = ({
  className,
  hue = 0,
  enableVoiceControl = false,
  activity = 0,
  voiceSensitivity = 1.5,
  maxRotationSpeed = 1.2,
  maxHoverIntensity = 0.8,
  onVoiceDetected,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const fallbackRef = useRef<HTMLDivElement>(null);

  // Live values read every frame, so prop changes never rebuild the WebGL scene.
  const live = useRef({ hue, activity, voiceSensitivity, maxRotationSpeed, maxHoverIntensity, onVoiceDetected });
  live.current = { hue, activity, voiceSensitivity, maxRotationSpeed, maxHoverIntensity, onVoiceDetected };

  const analyserRef = useRef<AnalyserNode | null>(null);
  const dataRef = useRef<Uint8Array<ArrayBuffer> | null>(null);

  // WebGL scene: built once per mount.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    if (!webglAvailable()) {
      if (fallbackRef.current) fallbackRef.current.hidden = false;
      return;
    }

    let renderer: Renderer;
    try {
      renderer = new Renderer({ alpha: true, premultipliedAlpha: false, antialias: true, dpr: window.devicePixelRatio || 1 });
    } catch {
      if (fallbackRef.current) fallbackRef.current.hidden = false;
      return;
    }
    const gl = renderer.gl;
    gl.clearColor(0, 0, 0, 0);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    const canvas = gl.canvas as HTMLCanvasElement;
    canvas.setAttribute('aria-hidden', 'true');
    container.appendChild(canvas);

    const program = new Program(gl, {
      vertex: VERT,
      fragment: FRAG,
      uniforms: {
        iTime: { value: 0 },
        iResolution: { value: new Vec3(canvas.width, canvas.height, canvas.width / canvas.height) },
        hue: { value: live.current.hue },
        hover: { value: 0 },
        rot: { value: 0 },
        hoverIntensity: { value: 0 },
      },
    });
    const mesh = new Mesh(gl, { geometry: new Triangle(gl), program });

    const resize = () => {
      const width = container.clientWidth;
      const height = container.clientHeight;
      if (width === 0 || height === 0) return;
      const dpr = window.devicePixelRatio || 1;
      renderer.setSize(width * dpr, height * dpr);
      canvas.style.width = `${width}px`;
      canvas.style.height = `${height}px`;
      program.uniforms.iResolution.value.set(canvas.width, canvas.height, canvas.width / canvas.height);
    };
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(resize) : null;
    observer?.observe(container);
    window.addEventListener('resize', resize);
    resize();

    const reduced = prefersReducedMotion();
    const timeScale = reduced ? 0.25 : 1;
    let rafId = 0;
    let last = 0;
    let rot = 0;
    let lastDetected = false;

    const frame = (t: number) => {
      rafId = requestAnimationFrame(frame);
      const dt = last ? (t - last) * 0.001 : 0;
      last = t;
      const v = live.current;

      let level: number;
      const analyser = analyserRef.current;
      const data = dataRef.current;
      if (analyser && data) {
        analyser.getByteFrequencyData(data);
        let sum = 0;
        for (let i = 0; i < data.length; i++) {
          const x = data[i] / 255;
          sum += x * x;
        }
        level = Math.min(Math.sqrt(sum / data.length) * v.voiceSensitivity * 3, 1);
        const detected = level > 0.1;
        if (detected !== lastDetected) {
          lastDetected = detected;
          v.onVoiceDetected?.(detected);
        }
      } else {
        level = Math.max(0, Math.min(v.activity, 1));
      }

      if (level > 0.05) rot += dt * (0.3 + level * v.maxRotationSpeed * 2) * timeScale;
      program.uniforms.iTime.value = t * 0.001 * timeScale;
      program.uniforms.hue.value = v.hue;
      program.uniforms.rot.value = rot;
      program.uniforms.hover.value = reduced ? 0 : Math.min(level * 2, 1);
      program.uniforms.hoverIntensity.value = reduced ? 0 : Math.min(level * v.maxHoverIntensity * 0.8, v.maxHoverIntensity);

      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
      renderer.render({ scene: mesh });
    };
    rafId = requestAnimationFrame(frame);

    return () => {
      cancelAnimationFrame(rafId);
      observer?.disconnect();
      window.removeEventListener('resize', resize);
      if (container.contains(canvas)) container.removeChild(canvas);
      gl.getExtension('WEBGL_lose_context')?.loseContext();
    };
  }, []);

  // Microphone: opened only while enableVoiceControl is true, closed as soon as it turns false.
  useEffect(() => {
    if (!enableVoiceControl || !navigator.mediaDevices?.getUserMedia) return;
    let cancelled = false;
    let stream: MediaStream | null = null;
    let ctx: AudioContext | null = null;

    (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
        });
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        const AudioCtx = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
        ctx = new AudioCtx();
        if (ctx.state === 'suspended') await ctx.resume();
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 512;
        analyser.smoothingTimeConstant = 0.3;
        analyser.minDecibels = -90;
        analyser.maxDecibels = -10;
        ctx.createMediaStreamSource(stream).connect(analyser);
        analyserRef.current = analyser;
        dataRef.current = new Uint8Array(new ArrayBuffer(analyser.frequencyBinCount));
      } catch {
        // Permission denied or no mic: the orb simply keeps animating from `activity`.
      }
    })();

    return () => {
      cancelled = true;
      analyserRef.current = null;
      dataRef.current = null;
      stream?.getTracks().forEach((t) => t.stop());
      if (ctx && ctx.state !== 'closed') void ctx.close();
      live.current.onVoiceDetected?.(false);
    };
  }, [enableVoiceControl]);

  return (
    <div ref={containerRef} className={cn('relative h-full w-full', className)}>
      <div
        ref={fallbackRef}
        hidden
        aria-hidden="true"
        className="absolute inset-[12%] rounded-full bg-[radial-gradient(circle_at_35%_35%,#b0a3f5_0%,#7c6af0_45%,#2a1f6b_100%)] opacity-90"
      />
    </div>
  );
};
