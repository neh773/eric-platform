import { useContext, type CSSProperties } from "react";
import { CoordinateScene, type HmiComponentProps, type JsonSchema, type SceneChild } from "@oip/hmi-runtime";
import { portInFrame, portsFor, type PortExit } from "./ports";
import { routePipe, type RoutePoint } from "./pipeRoute";
import { readBoolean, readNumber, readString } from "./read";

/**
 * Pipe (HMP-128): a flow pipe drawn as a line through a list of points, with joints at the bends, a stroke colour and
 * width, an outline, and optional flow animation. The designer's pipe tool draws it between components of a
 * coordinate container; every property can be bound, so the colour can show what the pipe carries and `flow` can follow
 * a pump's running state.
 *
 * `points` is a list of `{x, y}` (or `[x, y]`) in the component's own space, or JSON text of one. The drawing is scaled
 * to fill the component's frame from the points' bounding box plus the pipe's own thickness, so moving or resizing the
 * component moves or scales the pipe. Flow animation is a moving dash along the pipe; it stops when the user prefers
 * reduced motion.
 *
 * Doc source: FEATURES HMP-128.
 */

export interface PipePoint {
  x: number;
  y: number;
}

const MAX_POINTS = 500;

function pointOf(entry: unknown): PipePoint | null {
  if (Array.isArray(entry) && entry.length >= 2 && typeof entry[0] === "number" && typeof entry[1] === "number") {
    return Number.isFinite(entry[0]) && Number.isFinite(entry[1]) ? { x: entry[0], y: entry[1] } : null;
  }
  if (entry && typeof entry === "object") {
    const { x, y } = entry as { x?: unknown; y?: unknown };
    if (typeof x === "number" && typeof y === "number" && Number.isFinite(x) && Number.isFinite(y)) return { x, y };
  }
  return null;
}

/** The points of a pipe from a list or its JSON text; entries that are not points are skipped. */
export function parsePipePoints(value: unknown): PipePoint[] {
  let list: unknown = value;
  if (typeof value === "string") {
    if (value.trim() === "") return [];
    try {
      list = JSON.parse(value);
    } catch {
      return [];
    }
  }
  if (!Array.isArray(list)) return [];
  const points: PipePoint[] = [];
  for (const entry of list.slice(0, MAX_POINTS)) {
    const point = pointOf(entry);
    if (point) points.push(point);
  }
  return points;
}

export interface PipeGeometry {
  /** The path through the points. */
  path: string;
  viewBox: string;
  /** Total length of the path, in drawing units. */
  length: number;
}

/** How far a pipe's drawing reaches beyond its points on every side: half its width, its outline and a hairline. */
export function pipePadding(width: number, outlineWidth: number): number {
  return width / 2 + outlineWidth + 1;
}

/** The path, view box and length for [points] drawn [width] wide with an outline of [outlineWidth] each side. */
export function pipeGeometry(points: readonly PipePoint[], width: number, outlineWidth: number): PipeGeometry | null {
  if (points.length < 2) return null;
  const pad = pipePadding(width, outlineWidth);
  const xs = points.map((point) => point.x);
  const ys = points.map((point) => point.y);
  const minX = Math.min(...xs) - pad;
  const minY = Math.min(...ys) - pad;
  const spanX = Math.max(Math.max(...xs) - Math.min(...xs) + 2 * pad, 1);
  const spanY = Math.max(Math.max(...ys) - Math.min(...ys) + 2 * pad, 1);
  let length = 0;
  for (let index = 1; index < points.length; index += 1) {
    length += Math.hypot(points[index]!.x - points[index - 1]!.x, points[index]!.y - points[index - 1]!.y);
  }
  return {
    path: points.map((point, index) => `${index === 0 ? "M" : "L"}${String(round(point.x))} ${String(round(point.y))}`).join(" "),
    viewBox: `${String(round(minX))} ${String(round(minY))} ${String(round(spanX))} ${String(round(spanY))}`,
    length,
  };
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

const JOINTS = ["round", "miter", "bevel"] as const;
const COLOR = /^(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20}|(rgb|rgba|hsl|hsla)\([0-9.,%\s/-]+\)|var\(--[A-Za-z0-9_-]+\))$/;

/** A colour as written, or the fallback when it is not a plain colour (no `url(...)`, no free text). */
function colour(value: unknown, fallback: string): string {
  return typeof value === "string" && COLOR.test(value.trim()) ? value.trim() : fallback;
}

export interface PipeEndRef {
  componentId: string;
  port: string;
}

/** `pump-1.discharge` or `{ componentId, port }`. */
export function parsePipeEnd(value: unknown): PipeEndRef | null {
  if (typeof value === "string") {
    const dot = value.lastIndexOf(".");
    if (dot <= 0 || dot === value.length - 1) return null;
    return { componentId: value.slice(0, dot), port: value.slice(dot + 1) };
  }
  if (value && typeof value === "object") {
    const record = value as { componentId?: unknown; port?: unknown };
    if (typeof record.componentId === "string" && typeof record.port === "string" && record.componentId && record.port) {
      return { componentId: record.componentId, port: record.port };
    }
  }
  return null;
}

export interface ScenePort {
  x: number;
  y: number;
  exit: PortExit;
}

/** A named port on a sibling, in the coordinate parent's space. */
export function scenePort(children: readonly SceneChild[], ref: PipeEndRef): ScenePort | null {
  const child = children.find((entry) => entry.id === ref.componentId);
  if (!child?.frame) return null;
  const port = portsFor(child.type, child.props).find((entry) => entry.id === ref.port);
  if (!port) return null;
  return { ...portInFrame(port, child.frame), exit: port.exit };
}

function pointValue(value: unknown): RoutePoint | null {
  const point = pointOf(value);
  return point ? { x: point.x, y: point.y } : null;
}

/**
 * Parent-space route for a pipe that names its ends. Null when the pipe is a free point list instead.
 * A missing end falls back to `fromPoint` / `toPoint`.
 */
export function resolvePipeRoute(props: Record<string, unknown>, children: readonly SceneChild[]): RoutePoint[] | null {
  const fromRef = parsePipeEnd(props.from);
  const toRef = parsePipeEnd(props.to);
  const from = (fromRef ? scenePort(children, fromRef) : null) ?? pointValue(props.fromPoint);
  const to = (toRef ? scenePort(children, toRef) : null) ?? pointValue(props.toPoint);
  if (!from || !to) return null;
  return routePipe(from, to, parsePipePoints(props.waypoints));
}

const LINE_STYLES = ["process", "secondary", "signal"] as const;

function flowMotion(value: unknown, direction: string): { flowing: boolean; reverse: boolean } {
  if (typeof value === "number" && Number.isFinite(value)) {
    if (Math.abs(value) <= 0.5) return { flowing: false, reverse: false };
    return { flowing: true, reverse: value < 0 };
  }
  if (value === true) return { flowing: true, reverse: direction === "reverse" };
  return { flowing: false, reverse: false };
}

export function Pipe({ props, placement }: HmiComponentProps) {
  const scene = useContext(CoordinateScene);
  const linked = scene ? resolvePipeRoute(props, scene.children) : null;
  const points = linked ?? parsePipePoints(props.points);
  const lineStyle = LINE_STYLES.find((entry) => entry === readString(props, "lineStyle", "process")) ?? "process";
  const width = Math.min(200, Math.max(1, readNumber(props, "width", lineStyle === "signal" ? 2 : lineStyle === "secondary" ? 4 : 6)));
  const outlineWidth = Math.min(20, Math.max(0, readNumber(props, "outlineWidth", lineStyle === "signal" ? 0 : 1)));
  const motion = flowMotion(props.flow, readString(props, "flowDirection", "forward"));
  const flowing = motion.flowing && (props.flow === true || readBoolean(props, "flow", false) || typeof props.flow === "number");
  const speed = Math.min(2000, Math.max(1, readNumber(props, "flowSpeed", 36)));
  const local = !linked;
  const geometry = local ? pipeGeometry(points, width, outlineWidth) : null;
  const requested = readString(props, "joints", "round");
  const joint = JOINTS.find((entry) => entry === requested) ?? "round";
  const fill = colour(props.stroke, lineStyle === "signal" ? "#9ec9ef" : "#7a8794");
  const outline = colour(props.outline, "#3c4650");
  const flowColour = colour(props.flowColor, "#d8e8f5");
  const label = readString(props, "label", "Pipe");
  const dash = Math.max(2, width * 0.8);
  const gap = Math.max(4, width * 1.2);
  const cycle = dash + gap;
  const flowStyle = { "--oip-pipe-cycle": `${String(cycle)}px`, animationDuration: `${String(cycle / speed)}s` } as CSSProperties;
  const signalDash = lineStyle === "signal" ? "6 4" : undefined;
  const tee = readString(props, "tee", "none");
  const path = linked
    ? points.map((point, index) => `${index === 0 ? "M" : "L"}${String(round(point.x))} ${String(round(point.y))}`).join(" ")
    : geometry?.path;
  const teeAt = (which: "start" | "end") => (tee === which || tee === "both") && points.length > 1;
  const origin = placement ?? { x: 0, y: 0 };
  const draw = path ? (
    <svg
      width={linked && scene ? scene.width : "100%"}
      height={linked && scene ? scene.height : "100%"}
      viewBox={linked && scene ? `0 0 ${String(scene.width)} ${String(scene.height)}` : geometry?.viewBox}
      preserveAspectRatio={linked ? "xMinYMin meet" : "none"}
      aria-hidden="true"
      focusable="false"
      style={linked ? { position: "absolute", left: -origin.x, top: -origin.y, overflow: "visible", pointerEvents: "none" } : { overflow: "visible", pointerEvents: "none" }}
    >
      {outlineWidth > 0 ? (
        <path d={path} fill="none" stroke={outline} strokeWidth={width + outlineWidth * 2} strokeLinejoin={joint} strokeLinecap="butt" vectorEffect="non-scaling-stroke" />
      ) : null}
      <path className="oip-pipe-body" d={path} fill="none" stroke={fill} strokeWidth={width} strokeLinejoin={joint} strokeLinecap="butt" strokeDasharray={signalDash} vectorEffect="non-scaling-stroke" />
      {flowing ? (
        <path
          className="oip-pipe-flow"
          d={path}
          fill="none"
          stroke={flowColour}
          strokeWidth={Math.max(1, width * 0.45)}
          strokeLinejoin={joint}
          strokeLinecap="round"
          strokeDasharray={`${String(dash)} ${String(gap)}`}
          vectorEffect="non-scaling-stroke"
          style={{ ...flowStyle, animationDirection: motion.reverse ? "reverse" : "normal" }}
        />
      ) : null}
      {readBoolean(props, "showJoints", false) && joint === "round"
        ? points.slice(1, -1).map((point, index) => (
            <circle key={index} className="oip-pipe-joint" cx={point.x} cy={point.y} r={width / 2} fill={fill} stroke={outline} strokeWidth={outlineWidth} />
          ))
        : null}
      {teeAt("start") ? <circle className="oip-pipe-tee" cx={points[0]!.x} cy={points[0]!.y} r={Math.max(width * 0.7, 3)} fill={fill} /> : null}
      {teeAt("end") ? <circle className="oip-pipe-tee" cx={points[points.length - 1]!.x} cy={points[points.length - 1]!.y} r={Math.max(width * 0.7, 3)} fill={fill} /> : null}
    </svg>
  ) : (
    <span className="oip-media-empty">Pipe needs two points</span>
  );
  return (
    <div className="oip-pipe" role="img" aria-label={label} data-flowing={flowing && path ? "true" : "false"} data-direction={motion.reverse ? "reverse" : "forward"} data-points={points.length} data-line={lineStyle} data-linked={linked ? "true" : "false"}>
      {draw}
    </div>
  );
}

const SCHEMA = "https://json-schema.org/draft/2020-12/schema";

export const pipeSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Pipe",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Pipe" },
    points: { type: "string", title: "Points (JSON list of {x, y}; used when From and To are empty)", default: "[{\"x\":8,\"y\":8},{\"x\":8,\"y\":88},{\"x\":128,\"y\":88}]" },
    from: { type: "string", title: "From port (componentId.port)", default: "" },
    to: { type: "string", title: "To port (componentId.port)", default: "" },
    waypoints: { type: "string", title: "Waypoints (JSON list of {x, y} in the view)", default: "" },
    lineStyle: { type: "string", title: "Line style", enum: ["process", "secondary", "signal"], default: "process" },
    tee: { type: "string", title: "Tee dot", enum: ["none", "start", "end", "both"], default: "none" },
    width: { type: "number", title: "Pipe width", minimum: 1, maximum: 200, default: 6 },
    stroke: { type: "string", title: "Pipe colour", default: "#7a8794" },
    outline: { type: "string", title: "Outline colour", default: "#3c4650" },
    outlineWidth: { type: "number", title: "Outline width", minimum: 0, maximum: 20, default: 1 },
    joints: { type: "string", title: "Joint style", enum: [...JOINTS], default: "round" },
    showJoints: { type: "boolean", title: "Show joint caps (round joints)", default: false },
    flow: { type: "boolean", title: "Animate flow", default: false },
    flowDirection: { type: "string", title: "Flow direction", enum: ["forward", "reverse"], default: "forward" },
    flowSpeed: { type: "number", title: "Flow speed (px/s)", minimum: 1, maximum: 2000, default: 40 },
    flowColor: { type: "string", title: "Flow colour", default: "#d8e8f5" },
  },
};
