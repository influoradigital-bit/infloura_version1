import { Suspense, useMemo, useRef, useState } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { PerformanceMonitor } from '@react-three/drei';
import { useReducedMotion } from 'framer-motion';
import * as THREE from 'three';
import type { Group } from 'three';

import { CanvasFallback } from '@/components/3d/CanvasFallback';

/**
 * Landing hero globe — a point-sphere "creator network" with arc routes
 * between hubs (brand ↔ creator trade routes). Procedural only: no textures,
 * no GLTF, so it stays light enough for Lighthouse mobile ≥ 85.
 * One WebGL context per page — the landing page mounts only this canvas.
 */

const GLOBE_RADIUS = 2;
const POINT_COUNT = 700;
const PRIMARY = '#7c6ae8'; // deeper than the --primary token so nodes read on the light lavender bg
const CYAN = '#06b6d4'; // --hype-solid: active hub nodes + trade routes
const ATMOSPHERE = '#8b7ae8'; // inner glow — a tint DARKER than the page bg so the globe has body, not grey mud

/** Fibonacci-sphere distribution — even creator "nodes" across the globe. */
function useSpherePoints(count: number, radius: number) {
  return useMemo(() => {
    const positions = new Float32Array(count * 3);
    const golden = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < count; i += 1) {
      const y = 1 - (i / (count - 1)) * 2;
      const r = Math.sqrt(1 - y * y);
      const theta = golden * i;
      positions[i * 3] = Math.cos(theta) * r * radius;
      positions[i * 3 + 1] = y * radius;
      positions[i * 3 + 2] = Math.sin(theta) * r * radius;
    }
    return positions;
  }, [count, radius]);
}

/** A handful of fixed hub locations (unit sphere) that arcs connect. */
const HUBS: Array<[number, number, number]> = [
  [0.2, 0.55, 0.81], [-0.75, 0.3, 0.59], [0.9, -0.1, 0.42],
  [-0.35, -0.6, 0.72], [0.55, 0.75, -0.36], [-0.85, -0.25, -0.47],
];

const ARCS: Array<[number, number]> = [
  [0, 1], [0, 2], [1, 3], [2, 4], [3, 5], [1, 4],
];

function arcCurve(a: [number, number, number], b: [number, number, number]) {
  const va = new THREE.Vector3(...a).multiplyScalar(GLOBE_RADIUS);
  const vb = new THREE.Vector3(...b).multiplyScalar(GLOBE_RADIUS);
  const mid = va.clone().add(vb).multiplyScalar(0.5);
  mid.setLength(GLOBE_RADIUS * 1.35); // lift the midpoint off the surface
  return new THREE.QuadraticBezierCurve3(va, mid, vb);
}

function TradeArcs() {
  const geometries = useMemo(
    () =>
      ARCS.map(([ai, bi]) => {
        const curve = arcCurve(HUBS[ai], HUBS[bi]);
        return new THREE.TubeGeometry(curve, 32, 0.012, 8, false);
      }),
    [],
  );

  return (
    <group>
      {geometries.map((geometry, i) => (
        <mesh key={i} geometry={geometry}>
          <meshBasicMaterial color={i % 2 === 0 ? PRIMARY : CYAN} transparent opacity={0.7} />
        </mesh>
      ))}
      {HUBS.map((hub, i) => (
        <mesh key={`hub-${i}`} position={new THREE.Vector3(...hub).multiplyScalar(GLOBE_RADIUS)}>
          <sphereGeometry args={[0.07, 16, 16]} />
          <meshBasicMaterial color={CYAN} />
        </mesh>
      ))}
    </group>
  );
}

function GlobeCore({ degraded }: { degraded: boolean }) {
  const groupRef = useRef<Group>(null);
  const positions = useSpherePoints(degraded ? Math.floor(POINT_COUNT / 2) : POINT_COUNT, GLOBE_RADIUS);

  useFrame((state, delta) => {
    if (!groupRef.current) return;
    groupRef.current.rotation.y += delta * 0.08;
    groupRef.current.rotation.x = Math.sin(state.clock.elapsedTime * 0.1) * 0.05;
  });

  return (
    <group ref={groupRef}>
      <points>
        <bufferGeometry key={positions.length}>
          <bufferAttribute attach="attributes-position" args={[positions, 3]} count={positions.length / 3} />
        </bufferGeometry>
        <pointsMaterial size={0.05} color={PRIMARY} transparent opacity={0.95} depthWrite={false} sizeAttenuation />
      </points>
      {/* Inner atmosphere — BackSide tint darker than the page bg gives the
          network a solid body and depth instead of a flat grey disc. */}
      <mesh>
        <sphereGeometry args={[GLOBE_RADIUS * 0.92, 48, 48]} />
        <meshBasicMaterial color={ATMOSPHERE} transparent opacity={0.16} side={THREE.BackSide} depthWrite={false} />
      </mesh>
      {/* Faint outer glow rim for a glass-globe silhouette. */}
      <mesh>
        <sphereGeometry args={[GLOBE_RADIUS * 1.04, 48, 48]} />
        <meshBasicMaterial color={PRIMARY} transparent opacity={0.06} side={THREE.BackSide} depthWrite={false} />
      </mesh>
      <TradeArcs />
    </group>
  );
}

function CameraParallax() {
  const { camera } = useThree();
  useFrame((state) => {
    camera.position.x += (state.pointer.x * 0.4 - camera.position.x) * 0.05;
    camera.position.y += (state.pointer.y * 0.25 - camera.position.y) * 0.05;
    camera.lookAt(0, 0, 0);
  });
  return null;
}

export function HeroGlobe() {
  const [degraded, setDegraded] = useState(false);

  return (
    <div className="h-full w-full min-h-[320px]" aria-hidden="true">
      <Canvas
        camera={{ position: [0, 0, 5.4], fov: 45, near: 0.1, far: 100 }}
        dpr={[1, 1.5]}
        gl={{ alpha: true, antialias: true, powerPreference: 'high-performance' }}
        className="bg-transparent"
      >
        <PerformanceMonitor onDecline={() => setDegraded(true)} onIncline={() => setDegraded(false)} />
        <Suspense fallback={null}>
          <ambientLight intensity={0.8} />
          <CameraParallax />
          <GlobeCore degraded={degraded} />
        </Suspense>
      </Canvas>
    </div>
  );
}

/** Reduced-motion / low-power gate — renders the static fallback instead. */
export function HeroGlobeGate() {
  const reduceMotion = useReducedMotion();
  if (reduceMotion) {
    return <CanvasFallback variant="portfolio" className="min-h-[320px]" />;
  }
  return <HeroGlobe />;
}
