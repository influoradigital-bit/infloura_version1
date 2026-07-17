import { Suspense, useRef, useState } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { Float, MeshDistortMaterial, PerformanceMonitor, Sphere } from '@react-three/drei';
import { useReducedMotion } from 'framer-motion';
import type { Mesh } from 'three';

import { CanvasFallback } from '@/components/3d/CanvasFallback';

type BlobProps = {
  position: [number, number, number];
  scale: number;
  color: string;
  speed?: number;
  distort?: number;
};

function Blob({ position, scale, color, speed = 2, distort = 0.35 }: BlobProps) {
  const ref = useRef<Mesh>(null);

  useFrame((state) => {
    if (!ref.current) return;
    ref.current.rotation.y = state.clock.elapsedTime * 0.12;
    ref.current.rotation.x = Math.sin(state.clock.elapsedTime * 0.08) * 0.15;
  });

  return (
    <Float speed={1.2} rotationIntensity={0.35} floatIntensity={0.6}>
      <Sphere ref={ref} args={[1, 48, 48]} position={position} scale={scale}>
        <MeshDistortMaterial
          color={color}
          attach="material"
          distort={distort}
          speed={speed}
          roughness={0.15}
          metalness={0.05}
          clearcoat={0.4}
          clearcoatRoughness={0.2}
        />
      </Sphere>
    </Float>
  );
}

function Scene({ degraded, accent }: { degraded: boolean; accent: 'brand' | 'creator' }) {
  const primary = accent === 'creator' ? '#7ec8e8' : '#9b8cf2';
  const secondary = accent === 'creator' ? '#a8d8f0' : '#c4b5fd';

  return (
    <>
      <ambientLight intensity={0.85} />
      <directionalLight position={[5, 8, 5]} intensity={1.1} color="#f5f0ff" />
      <directionalLight position={[-6, -2, 4]} intensity={0.45} color="#b8d4f0" />
      <pointLight position={[0, 0, 4]} intensity={0.6} color="#e9d5ff" />
      <Blob position={[-1.2, 0.9, 0]} scale={1.2} color={primary} distort={0.42} />
      <Blob position={[1.4, 0.5, -0.3]} scale={0.9} color={secondary} speed={1.6} />
      {!degraded && (
        <>
          <Blob position={[0.2, 1.6, -0.8]} scale={0.5} color="#7ec8e8" distort={0.28} />
          <Blob position={[-0.6, -0.2, 0.5]} scale={0.45} color="#ddd6fe" speed={2.4} distort={0.5} />
        </>
      )}
    </>
  );
}

type LoginScene3DProps = {
  accent?: 'brand' | 'creator';
};

/** Soft 3D blobs for auth hero panel */
export function LoginScene3D({ accent = 'brand' }: LoginScene3DProps) {
  const [degraded, setDegraded] = useState(false);

  return (
    <div className="h-full w-full min-h-[320px]">
      <Canvas
        camera={{ position: [0, 0.3, 5.5], fov: 40 }}
        dpr={[1, 1.5]}
        gl={{ alpha: true, antialias: false, powerPreference: 'high-performance' }}
        style={{ background: 'transparent' }}
      >
        <PerformanceMonitor onDecline={() => setDegraded(true)} onIncline={() => setDegraded(false)} />
        <Suspense fallback={null}>
          <Scene degraded={degraded} accent={accent} />
        </Suspense>
      </Canvas>
    </div>
  );
}

type LoginScene3DGateProps = {
  accent?: 'brand' | 'creator';
};

/** Respects prefers-reduced-motion — renders static fallback when needed */
export function LoginScene3DGate({ accent = 'brand' }: LoginScene3DGateProps) {
  const reduceMotion = useReducedMotion();

  if (reduceMotion) {
    return <CanvasFallback variant="auth" />;
  }

  return <LoginScene3D accent={accent} />;
}
