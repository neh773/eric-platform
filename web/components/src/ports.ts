/**
 * Named connection ports on process symbols (HMP-128). Positions are in the symbol's 100 by 100 grid, before
 * direction or orientation. A pipe terminates on a port, and the port's exit is the direction the line leaves the
 * symbol so the first bend stays outside the artwork.
 */

export type PortExit = "left" | "right" | "up" | "down";

export interface SymbolPort {
  id: string;
  /** Position in the 100 by 100 symbol grid. */
  x: number;
  y: number;
  exit: PortExit;
}

export interface FrameBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

const PUMP: SymbolPort[] = [
  { id: "suction", x: 17, y: 54, exit: "left" },
  { id: "discharge", x: 90, y: 34, exit: "right" },
];

const VALVE: SymbolPort[] = [
  { id: "in", x: 8, y: 62, exit: "left" },
  { id: "out", x: 92, y: 62, exit: "right" },
];

const TANK: SymbolPort[] = [
  { id: "inlet", x: 50, y: 8, exit: "up" },
  { id: "outlet", x: 84, y: 50, exit: "right" },
  { id: "overflow", x: 84, y: 20, exit: "right" },
  { id: "bottom", x: 50, y: 92, exit: "down" },
];

const VESSEL: SymbolPort[] = [
  { id: "inlet", x: 50, y: 8, exit: "up" },
  { id: "outlet", x: 74, y: 48, exit: "right" },
  { id: "overflow", x: 74, y: 16, exit: "right" },
  { id: "bottom", x: 50, y: 88, exit: "down" },
];

const SENSOR: SymbolPort[] = [
  { id: "tap", x: 50, y: 98, exit: "down" },
  { id: "side", x: 78, y: 36, exit: "right" },
];

const MOTOR: SymbolPort[] = [{ id: "shaft", x: 94, y: 50, exit: "right" }];

const CATALOG: Record<string, SymbolPort[]> = {
  pump: PUMP,
  valve: VALVE,
  "cylindrical-tank": TANK,
  vessel: VESSEL,
  sensor: SENSOR,
  motor: MOTOR,
};

function flipExit(exit: PortExit): PortExit {
  if (exit === "left") return "right";
  if (exit === "right") return "left";
  if (exit === "up") return "down";
  return "up";
}

function rotateExit(exit: PortExit): PortExit {
  if (exit === "left") return "up";
  if (exit === "up") return "right";
  if (exit === "right") return "down";
  return "left";
}

/** Clockwise 90 degrees around (cx, cy), matching an SVG `rotate(90 cx cy)`. */
function rotate90(x: number, y: number, cx: number, cy: number): { x: number; y: number } {
  const dx = x - cx;
  const dy = y - cy;
  return { x: cx - dy, y: cy + dx };
}

/** Ports for a component, after its direction or orientation. Unknown types have none. */
export function portsFor(type: string, props: Record<string, unknown> | undefined): SymbolPort[] {
  const ports = CATALOG[type];
  if (!ports) return [];
  const record = props ?? {};
  if (type === "pump" || type === "motor") {
    if (record.direction === "left") {
      return ports.map((port) => ({ ...port, x: 100 - port.x, exit: flipExit(port.exit) }));
    }
    return ports.map((port) => ({ ...port }));
  }
  if (type === "valve" && record.orientation === "vertical") {
    return ports.map((port) => {
      const at = rotate90(port.x, port.y, 50, 62);
      return { ...port, x: at.x, y: at.y, exit: rotateExit(port.exit) };
    });
  }
  return ports.map((port) => ({ ...port }));
}

/**
 * Where a port sits in its parent's coordinates. The symbol is a 100 by 100 drawing fitted inside the frame with
 * `xMidYMid meet`, so a non-square frame letterboxes and the port follows the artwork rather than the frame edge.
 */
export function portInFrame(port: SymbolPort, frame: FrameBox): { x: number; y: number } {
  const scale = Math.min(frame.width / 100, frame.height / 100);
  const ox = frame.x + (frame.width - 100 * scale) / 2;
  const oy = frame.y + (frame.height - 100 * scale) / 2;
  return { x: round(ox + port.x * scale), y: round(oy + port.y * scale) };
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
