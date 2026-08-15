import { Suspense, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import {
  Float,
  Html,
  MeshDistortMaterial,
  PerformanceMonitor,
  Sphere,
} from '@react-three/drei';
import { useReducedMotion } from 'framer-motion';
import * as THREE from 'three';
import type { Group, Mesh } from 'three';

import { CanvasFallback } from '@/components/3d/CanvasFallback';

const PRIMARY = '#7c6ae8'; // committed brand violet (2026-07-17 palette recalibration)

// Distinct radius + orbital tilt per satellite so the rings never collapse onto
// one plane — that coplanar clumping is what made the labels pile up on one side.
const SATELLITES = [
  { label: 'Instagram', color: '#E1306C', radius: 1.9, speed: 0.9, tilt: 0.42 },
  { label: 'YouTube', color: '#FF4444', radius: 2.5, speed: 1.1, tilt: -0.55 },
  { label: 'LinkedIn', color: '#0A66C2', radius: 2.9, speed: 0.85, tilt: 0.62 },
  { label: 'Creators', color: '#7ec8e8', radius: 2.3, speed: 1.0, tilt: -0.18 },
] as const;

function CameraParallax() {
  const { camera } = useThree();

  useFrame((state) => {
    camera.position.x = state.pointer.x * 0.3;
    camera.position.y = state.pointer.y * 0.3;
    camera.lookAt(0, 0, 0);
  });

  return null;
}

function CentralHub() {
  const ref = useRef<Mesh>(null);

  useFrame((state) => {
    if (!ref.current) return;
    ref.current.rotation.y = state.clock.elapsedTime * 0.08;
  });

  return (
    <Sphere ref={ref} args={[0.75, 32, 32]}>
      <MeshDistortMaterial
        color={PRIMARY}
        distort={0.35}
        speed={1.8}
        roughness={0.15}
        metalness={0.05}
        clearcoat={0.35}
      />
    </Sphere>
  );
}

type SatelliteProps = {
  label: string;
  color: string;
  radius: number;
  speed: number;
  tilt: number;
  angleOffset: number;
};

function Satellite({ label, color, radius, speed, tilt, angleOffset }: SatelliteProps) {
  const groupRef = useRef<Group>(null);

  useFrame((state) => {
    if (!groupRef.current) return;
    const t = state.clock.elapsedTime * 0.35 * speed + angleOffset;
    const x = Math.cos(t) * radius;
    const z = Math.sin(t) * radius;
    // Rotate the orbital plane by `tilt` so each satellite rides its own ring.
    groupRef.current.position.x = x;
    groupRef.current.position.y = z * Math.sin(tilt);
    groupRef.current.position.z = z * Math.cos(tilt);
  });

  return (
    <group ref={groupRef}>
      <Float speed={1.4} rotationIntensity={0.2} floatIntensity={0.45}>
        <Sphere args={[0.28, 24, 24]}>
          <meshStandardMaterial color={color} roughness={0.3} metalness={0.1} />
        </Sphere>
        <Html
          center
          distanceFactor={8}
          className="pointer-events-none select-none"
        >
          <span className="rounded-full bg-card/90 px-2 py-0.5 text-[10px] font-medium text-foreground shadow-sm backdrop-blur-sm">
            {label}
          </span>
        </Html>
      </Float>
    </group>
  );
}

function OrbitScene({ degraded }: { degraded: boolean }) {
  return (
    <>
      <ambientLight intensity={0.9} />
      <directionalLight position={[4, 6, 4]} intensity={1} color="#f5f0ff" />
      <directionalLight position={[-4, -2, 3]} intensity={0.4} color="#b8d4f0" />
      <pointLight position={[0, 0, 3]} intensity={0.5} color="#e9d5ff" />
      <CameraParallax />
      <CentralHub />
      {SATELLITES.map((sat, i) => (
        <Satellite
          key={sat.label}
          label={sat.label}
          color={sat.color}
          radius={sat.radius}
          speed={sat.speed}
          tilt={sat.tilt}
          angleOffset={(Math.PI * 2 * i) / SATELLITES.length}
        />
      ))}
      {!degraded && <OrbitParticles />}
    </>
  );
}

function OrbitParticles() {
  const ref = useRef<THREE.Points>(null);
  const positions = useMemo(() => {
    const arr = new Float32Array(60 * 3);
    for (let i = 0; i < arr.length; i += 1) {
      arr[i] = (Math.random() - 0.5) * 6;
    }
    return arr;
  }, []);

  useFrame((state) => {
    if (!ref.current) return;
    ref.current.rotation.y = state.clock.elapsedTime * 0.04;
  });

  return (
    <points ref={ref}>
      <bufferGeometry>
        <bufferAttribute attach="attributes-position" args={[positions, 3]} count={60} />
      </bufferGeometry>
      <pointsMaterial size={0.04} color="#c4b5fd" transparent opacity={0.55} depthWrite={false} />
    </points>
  );
}

/** Creator ecosystem orbit — /brand/discover hero (desktop) */
export function DiscoverCanvas() {
  const [degraded, setDegraded] = useState(false);

  return (
    <div className="h-full w-full min-h-[280px]">
      <Canvas
        camera={{ position: [0, 0, 6], fov: 42 }}
        dpr={[1, 1.5]}
        gl={{ alpha: true, antialias: true, powerPreference: 'high-performance' }}
        className="bg-transparent"
      >
        <PerformanceMonitor onDecline={() => setDegraded(true)} onIncline={() => setDegraded(false)} />
        <Suspense fallback={null}>
          <OrbitScene degraded={degraded} />
        </Suspense>
      </Canvas>
    </div>
  );
}

export function DiscoverCanvasGate() {
  const reduceMotion = useReducedMotion();

  if (reduceMotion) {
    return <CanvasFallback variant="discover" className="min-h-[280px]" />;
  }

  return <DiscoverCanvas />;
}
