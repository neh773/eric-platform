/**
 * Orthogonal (Manhattan) routing for a pipe (HMP-128). The line leaves each port along that port's exit, then uses
 * at most one bend between stops. Waypoints are corners the author placed; they are kept and the segments between
 * them are squared.
 */

import type { PortExit } from "./ports";

export interface RoutePoint {
  x: number;
  y: number;
  exit?: PortExit;
}

const STUB = 18;

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

function step(point: RoutePoint, distance: number): RoutePoint {
  const exit = point.exit;
  if (exit === "left") return { x: point.x - distance, y: point.y };
  if (exit === "right") return { x: point.x + distance, y: point.y };
  if (exit === "up") return { x: point.x, y: point.y - distance };
  if (exit === "down") return { x: point.x, y: point.y + distance };
  return { x: point.x, y: point.y };
}

/** How far to leave a port before turning: far enough to clear the nozzle, short enough not to pass the next stop. */
function stubLength(from: RoutePoint, toward: RoutePoint): number {
  if (!from.exit) return 0;
  const dx = toward.x - from.x;
  const dy = toward.y - from.y;
  const along = from.exit === "right" ? dx : from.exit === "left" ? -dx : from.exit === "down" ? dy : -dy;
  if (!(along > 8)) return STUB;
  return Math.min(STUB, Math.max(8, along * 0.45));
}

function close(a: number, b: number): boolean {
  return Math.abs(a - b) < 0.75;
}

/** Square every segment, snap near-axis runs onto the axis, and drop colinear or repeated points. */
export function orthogonalRoute(points: readonly RoutePoint[]): RoutePoint[] {
  const expanded: RoutePoint[] = [];
  for (const point of points) {
    const prev = expanded[expanded.length - 1];
    if (!prev) {
      expanded.push({ x: point.x, y: point.y });
      continue;
    }
    if (close(prev.x, point.x) || close(prev.y, point.y)) {
      expanded.push({
        x: close(prev.x, point.x) ? prev.x : point.x,
        y: close(prev.y, point.y) ? prev.y : point.y,
      });
    } else {
      expanded.push({ x: point.x, y: prev.y });
      expanded.push({ x: point.x, y: point.y });
    }
  }
  const slim: RoutePoint[] = [];
  for (const point of expanded) {
    const last = slim[slim.length - 1];
    if (last && close(last.x, point.x) && close(last.y, point.y)) continue;
    const count = slim.length;
    if (count >= 2) {
      const a = slim[count - 2]!;
      const b = slim[count - 1]!;
      const colinear = (close(a.x, b.x) && close(b.x, point.x)) || (close(a.y, b.y) && close(b.y, point.y));
      if (colinear) {
        slim[count - 1] = { x: point.x, y: point.y };
        continue;
      }
    }
    slim.push({ x: round(point.x), y: round(point.y) });
  }
  return slim;
}

/**
 * A pipe from [from] to [to]. Each end may carry an exit. [waypoints] are extra corners in parent coordinates.
 * The result starts on [from] and ends on [to].
 */
export function routePipe(from: RoutePoint, to: RoutePoint, waypoints: readonly RoutePoint[] = []): RoutePoint[] {
  const next = waypoints[0] ?? to;
  const prev = waypoints[waypoints.length - 1] ?? from;
  const start = from.exit ? step(from, stubLength(from, next)) : from;
  const end = to.exit ? step(to, stubLength(to, prev)) : to;
  return orthogonalRoute([from, start, ...waypoints, end, to]);
}
