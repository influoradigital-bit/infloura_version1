/** Collision-safe client-side id. Date.now() alone collides when two items are
 *  created in the same millisecond (optimistic send + echo); the random suffix fixes that. */
export function uniqueId(prefix: string): string {
  const rand = Math.random().toString(36).slice(2, 8)
  return `${prefix}-${Date.now().toString(36)}-${rand}`
}
