import { motion, useReducedMotion } from 'framer-motion';

const blobs = [
  {
    className: 'left-[-10%] top-[-15%] h-[55vh] w-[55vh] bg-[#7c6ae8]/45',
    animate: { x: [0, 80, 40, 0], y: [0, 50, 90, 0], scale: [1, 1.08, 0.95, 1] },
    duration: 22,
  },
  {
    className: 'right-[-5%] top-[10%] h-[50vh] w-[50vh] bg-[#c4b5fd]/40',
    animate: { x: [0, -60, -30, 0], y: [0, 70, 30, 0], scale: [1, 0.92, 1.06, 1] },
    duration: 26,
  },
  {
    className: 'bottom-[-20%] left-[20%] h-[60vh] w-[60vh] bg-[#7ec8e8]/35',
    animate: { x: [0, 50, -40, 0], y: [0, -60, -20, 0], scale: [1, 1.05, 0.98, 1] },
    duration: 24,
  },
  {
    className: 'bottom-[5%] right-[15%] h-[40vh] w-[40vh] bg-[#ddd6fe]/50',
    animate: { x: [0, -70, 20, 0], y: [0, -40, 50, 0], scale: [1, 1.1, 1, 1] },
    duration: 20,
  },
];

/** Animated aurora blobs — Lilac Mist palette */
export function AuroraBackground() {
  const reduceMotion = useReducedMotion();

  return (
    <motion.div
      aria-hidden
      className="pointer-events-none fixed inset-0 z-0 overflow-hidden bg-background"
    >
      <div className="absolute inset-0 bg-auth-mesh opacity-90" />
      {reduceMotion ? (
        <div className="absolute inset-0 bg-gradient-to-br from-[#ede9fe]/80 via-background to-[#e8f4fc]/70" />
      ) : (
        blobs.map((blob, i) => (
          <motion.div
            key={i}
            className={`absolute rounded-full blur-[100px] ${blob.className}`}
            animate={blob.animate}
            transition={{
              duration: blob.duration,
              repeat: Infinity,
              repeatType: 'reverse',
              ease: 'easeInOut',
            }}
          />
        ))
      )}
    </motion.div>
  );
}
