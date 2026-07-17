import { Suspense, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame, useLoader } from '@react-three/fiber';
import { Float, Html, PerformanceMonitor, Ring } from '@react-three/drei';
import { useReducedMotion } from 'framer-motion';
import * as THREE from 'three';
import type { Group } from 'three';

import { CanvasFallback } from '@/components/3d/CanvasFallback';
import { formatCompactNumber } from '@/lib/scroll/format-compact-number';

export type PortfolioCanvasStats = {
  followers?: number;
  engagement?: number;
  collabs?: number;
};

type PortfolioCanvasProps = {
  avatarUrl?: string;
  stats?: PortfolioCanvasStats;
};

type StatOrbitProps = {
  label: string;
  value: string;
  angleOffset: number;
  radius: number;
};

function AvatarDisc({ avatarUrl }: { avatarUrl?: string }) {
  const texture = avatarUrl ? useLoader(THREE.TextureLoader, avatarUrl) : null;

  return (
    <group>
      <Ring args={[0.92, 1.05, 64]} rotation={[Math.PI / 2, 0, 0]}>
        <meshBasicMaterial color="#7ec8e8" transparent opacity={0.85} side={THREE.DoubleSide} />
      </Ring>
      <mesh rotation={[-Math.PI / 2, 0, 0]}>
        <circleGeometry args={[0.88, 64]} />
        {texture ? (
          <meshBasicMaterial map={texture} toneMapped={false} />
        ) : (
          <meshStandardMaterial color="#9b8cf2" roughness={0.4} metalness={0.05} />
        )}
      </mesh>
    </group>
  );
}

function StatOrbit({ label, value, angleOffset, radius }: StatOrbitProps) {
  const groupRef = useRef<Group>(null);

  useFrame((state) => {
    if (!groupRef.current) return;
    const t = state.clock.elapsedTime * 0.22 + angleOffset;
    groupRef.current.position.x = Math.cos(t) * radius;
    groupRef.current.position.z = Math.sin(t) * radius;
    groupRef.current.position.y = Math.sin(t * 1.2) * 0.15;
  });

  return (
    <group ref={groupRef}>
      <Float speed={1.1} floatIntensity={0.35} rotationIntensity={0.15}>
        <Html center distanceFactor={9} className="pointer-events-none select-none">
          <div className="rounded-xl border border-border bg-card/95 px-3 py-2 text-center shadow-sm backdrop-blur-sm">
            <p className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</p>
            <p className="text-sm font-semibold text-foreground">{value}</p>
          </div>
        </Html>
      </Float>
    </group>
  );
}

function PortfolioScene({
  avatarUrl,
  stats,
  degraded,
}: PortfolioCanvasProps & { degraded: boolean }) {
  const rootRef = useRef<Group>(null);

  const statItems = useMemo(
    () => [
      {
        label: 'Followers',
        value: formatCompactNumber(stats?.followers ?? 0),
        angleOffset: 0,
      },
      {
        label: 'Engagement',
        value: `${(stats?.engagement ?? 0).toFixed(1)}%`,
        angleOffset: (Math.PI * 2) / 3,
      },
      {
        label: 'Collabs',
        value: String(stats?.collabs ?? 0),
        angleOffset: ((Math.PI * 2) / 3) * 2,
      },
    ],
    [stats],
  );

  useFrame((state) => {
    if (!rootRef.current) return;
    rootRef.current.rotation.y = state.clock.elapsedTime * 0.06;
  });

  return (
    <group ref={rootRef}>
      <ambientLight intensity={0.85} />
      <directionalLight position={[3, 5, 4]} intensity={0.9} color="#f5f0ff" />
      <directionalLight position={[-3, -1, 2]} intensity={0.35} color="#7ec8e8" />
      <Suspense fallback={null}>
        <AvatarDisc avatarUrl={avatarUrl} />
      </Suspense>
      {!degraded &&
        statItems.map((item) => (
          <StatOrbit
            key={item.label}
            label={item.label}
            value={item.value}
            angleOffset={item.angleOffset}
            radius={2.4}
          />
        ))}
    </group>
  );
}

export function PortfolioCanvas({ avatarUrl, stats }: PortfolioCanvasProps) {
  const [degraded, setDegraded] = useState(false);

  return (
    <div className="h-full w-full min-h-[240px]">
      <Canvas
        camera={{ position: [0, 2.2, 5.5], fov: 40 }}
        dpr={[1, 1.5]}
        gl={{ alpha: true, antialias: false, powerPreference: 'high-performance' }}
        style={{ background: 'transparent' }}
      >
        <PerformanceMonitor onDecline={() => setDegraded(true)} onIncline={() => setDegraded(false)} />
        <Suspense fallback={null}>
          <PortfolioScene avatarUrl={avatarUrl} stats={stats} degraded={degraded} />
        </Suspense>
      </Canvas>
    </div>
  );
}

export function PortfolioCanvasGate(props: PortfolioCanvasProps) {
  const reduceMotion = useReducedMotion();

  if (reduceMotion) {
    return <CanvasFallback variant="portfolio" className="min-h-[240px]" />;
  }

  return <PortfolioCanvas {...props} />;
}
