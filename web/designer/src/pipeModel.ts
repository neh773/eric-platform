import type { ComponentNode, ViewDocument } from "@oip/hmi-runtime";
import { componentModules, defaultsFromSchema, parsePipeEnd, parsePipePoints, pipePadding, portInFrame, portsFor, resolvePipeRoute, type PipePoint } from "@oip/components";
import { appendComponent, updateNode } from "./project";

/**
 * Designer model for flow pipes (HMP-128): the places a pipe's ends snap to on the components around it, turning drawn
 * points into a pipe component, and editing a pipe's joints.
 *
 * A pipe is a `pipe` component in a fixed-coordinate view. Its `points` are in the component's own space, with the
 * top-left of the drawing the pipe's padding away from the frame's corner, so the frame is the points' bounding box
 * plus that padding and a point's place in the view is the frame's corner plus the point.
 */

export type Pt = PipePoint;

export interface Anchor extends Pt {
  nodeId: string;
  side: "top" | "right" | "bottom" | "left" | "center" | "up" | "down";
  /** Set when the anchor is a named port rather than an edge midpoint. */
  port?: string;
}

const PIPE_TYPE = "pipe";
/** How close (view pixels) a point has to be to an anchor to snap to it. */
export const SNAP_RADIUS = 14;

/** Why pipes cannot be drawn in [view], or null. Pipes are drawn in the view's own coordinate root. */
export function pipeToolProblem(view: ViewDocument): string | null {
  const root = view.spec.root;
  if (root.layout !== "coordinate") return "Pipes are drawn in a coordinate view. This view uses another layout.";
  if (root.props.mode === "percent") return "Pipes are drawn in a fixed-coordinate view. This view uses percent coordinates.";
  return null;
}

/** The middle of each edge and the centre of every component directly in the coordinate root, except pipes. */
export function pipeAnchors(view: ViewDocument): Anchor[] {
  if (pipeToolProblem(view) !== null) return [];
  const anchors: Anchor[] = [];
  for (const node of view.spec.root.children) {
    if (node.kind === "component" && node.type === PIPE_TYPE) continue;
    const frame = node.frame;
    if (!frame) continue;
    if (node.kind === "component") {
      const ports = portsFor(node.type, node.props);
      if (ports.length > 0) {
        for (const port of ports) {
          const at = portInFrame(port, frame);
          anchors.push({ nodeId: node.id, side: port.exit, port: port.id, x: at.x, y: at.y });
        }
        continue;
      }
    }
    const { x, y, width, height } = frame;
    anchors.push(
      { nodeId: node.id, side: "top", x: x + width / 2, y },
      { nodeId: node.id, side: "right", x: x + width, y: y + height / 2 },
      { nodeId: node.id, side: "bottom", x: x + width / 2, y: y + height },
      { nodeId: node.id, side: "left", x, y: y + height / 2 },
      { nodeId: node.id, side: "center", x: x + width / 2, y: y + height / 2 },
    );
  }
  return anchors;
}

/** [point] moved onto the nearest anchor within [radius], with that anchor. Edge anchors win over a centre at equal distance. */
export function snapToAnchor(point: Pt, anchors: readonly Anchor[], radius = SNAP_RADIUS): { point: Pt; anchor?: Anchor } {
  let best: Anchor | undefined;
  let bestDistance = radius;
  for (const anchor of anchors) {
    const distance = Math.hypot(anchor.x - point.x, anchor.y - point.y) + (anchor.side === "center" ? 0.5 : 0);
    if (distance <= bestDistance) {
      best = anchor;
      bestDistance = distance;
    }
  }
  return best ? { point: { x: best.x, y: best.y }, anchor: best } : { point };
}

/** [point] moved onto the horizontal or vertical line through [from], whichever is nearer (the Shift constraint). */
export function constrainAxis(from: Pt, point: Pt): Pt {
  return Math.abs(point.x - from.x) >= Math.abs(point.y - from.y) ? { x: point.x, y: from.y } : { x: from.x, y: point.y };
}

export interface PipeStyle {
  width: number;
  outlineWidth: number;
}

const DEFAULT_STYLE: PipeStyle = { width: 12, outlineWidth: 2 };

/** The frame that holds [points] drawn with [style], and the points in that frame's space. */
export function pipeFrame(points: readonly Pt[], style: PipeStyle = DEFAULT_STYLE): { frame: { x: number; y: number; width: number; height: number }; local: Pt[] } {
  const pad = pipePadding(style.width, style.outlineWidth);
  const minX = Math.min(...points.map((point) => point.x));
  const minY = Math.min(...points.map((point) => point.y));
  const maxX = Math.max(...points.map((point) => point.x));
  const maxY = Math.max(...points.map((point) => point.y));
  const x = Math.round(minX - pad);
  const y = Math.round(minY - pad);
  return {
    frame: { x, y, width: Math.max(1, Math.round(maxX - minX + 2 * pad)), height: Math.max(1, Math.round(maxY - minY + 2 * pad)) },
    local: points.map((point) => ({ x: round(point.x - x), y: round(point.y - y) })),
  };
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

/** Drops a point equal to the one before it, so a double click does not add a zero-length segment. */
export function dedupe(points: readonly Pt[]): Pt[] {
  return points.filter((point, index) => index === 0 || point.x !== points[index - 1]!.x || point.y !== points[index - 1]!.y);
}

export interface PipeEnds {
  start?: Anchor;
  end?: Anchor;
}

/** Adds a pipe through [points] (view pixels) to the view's coordinate root. Snapped ports are stored and the line is re-routed onto them. */
export function appendPipe(view: ViewDocument, points: readonly Pt[], style: PipeStyle = DEFAULT_STYLE, ends?: PipeEnds): { view: ViewDocument; id: string } {
  const problem = pipeToolProblem(view);
  if (problem) throw new Error(problem);
  const clean = dedupe(points);
  if (clean.length < 2) throw new Error("A pipe needs at least two points");
  const module = componentModules.find((entry) => entry.type === PIPE_TYPE);
  if (!module) throw new Error("the pipe component is not registered");
  const from = ends?.start?.port ? `${ends.start.nodeId}.${ends.start.port}` : "";
  const to = ends?.end?.port ? `${ends.end.nodeId}.${ends.end.port}` : "";
  const waypoints = from && to ? clean.slice(1, -1) : [];
  const linked = from && to ? resolvePipeRoute({ from, to, waypoints }, sceneChildren(view)) : null;
  const drawn = linked && linked.length >= 2 ? linked : clean;
  const { frame, local } = pipeFrame(drawn, style);
  const props = {
    ...defaultsFromSchema(module.schema),
    points: local,
    width: style.width,
    outlineWidth: style.outlineWidth,
    from,
    to,
    waypoints,
    showJoints: false,
  };
  const added = appendComponent(view, PIPE_TYPE, "Pipe", props);
  return { view: updateNode(added.view, added.id, { frame }), id: added.id };
}

function sceneChildren(view: ViewDocument) {
  return view.spec.root.children.flatMap((child) =>
    child.kind === "component" ? [{ id: child.id, type: child.type, name: child.name, frame: child.frame, props: child.props }] : [],
  );
}

/** Re-routes every pipe that names ports, so a moved pump or valve takes its pipes with it. */
export function followPipes(view: ViewDocument): ViewDocument {
  let next = view;
  for (const child of view.spec.root.children) {
    if (child.kind !== "component" || child.type !== PIPE_TYPE) continue;
    if (!parsePipeEnd(child.props.from) && !parsePipeEnd(child.props.to) && !child.props.fromPoint && !child.props.toPoint) continue;
    const routed = resolvePipeRoute(child.props, sceneChildren(view));
    if (!routed || routed.length < 2) continue;
    next = setPipePoints(next, child.id, routed);
  }
  return next;
}

function pipeNode(view: ViewDocument, id: string): ComponentNode {
  const node = view.spec.root.children.find((child): child is ComponentNode => child.id === id && child.kind === "component" && child.type === PIPE_TYPE);
  if (!node) throw new Error(`pipe ${id} not found in the view's root`);
  return node;
}

/** The pipe's points in its own space. */
export function pipeLocalPoints(node: ComponentNode): Pt[] {
  return parsePipePoints(node.props.points);
}

function styleOf(node: ComponentNode): PipeStyle {
  const width = typeof node.props.width === "number" ? node.props.width : DEFAULT_STYLE.width;
  const outline = typeof node.props.outlineWidth === "number" ? node.props.outlineWidth : DEFAULT_STYLE.outlineWidth;
  return { width: Math.min(200, Math.max(1, width)), outlineWidth: Math.min(20, Math.max(0, outline)) };
}

/** The pipe's points in the view, from its frame (which may have been moved or resized since they were drawn). */
export function pipeViewPoints(node: ComponentNode): Pt[] {
  const local = pipeLocalPoints(node);
  const frame = node.frame;
  if (!frame || local.length === 0) return local;
  const style = styleOf(node);
  const pad = pipePadding(style.width, style.outlineWidth);
  const minX = Math.min(...local.map((point) => point.x));
  const minY = Math.min(...local.map((point) => point.y));
  const maxX = Math.max(...local.map((point) => point.x));
  const maxY = Math.max(...local.map((point) => point.y));
  const naturalW = Math.max(maxX - minX + 2 * pad, 1);
  const naturalH = Math.max(maxY - minY + 2 * pad, 1);
  const sx = frame.width / naturalW;
  const sy = frame.height / naturalH;
  return local.map((point) => ({ x: frame.x + (point.x - (minX - pad)) * sx, y: frame.y + (point.y - (minY - pad)) * sy }));
}

/**
 * The view with pipe [id] drawn through [points] (view pixels): its frame becomes their bounding box and its points
 * are written in the frame's space. The pipe's width and outline decide the padding.
 */
export function setPipePoints(view: ViewDocument, id: string, points: readonly Pt[]): ViewDocument {
  const node = pipeNode(view, id);
  const clean = dedupe(points);
  if (clean.length < 2) throw new Error("A pipe needs at least two points");
  const { frame, local } = pipeFrame(clean, styleOf(node));
  return updateNode(view, id, { frame, props: { points: local } });
}

/** The view with a new joint inserted after point [index], halfway to the next point, or past the end for the last. */
export function insertJoint(view: ViewDocument, id: string, index: number): ViewDocument {
  const points = pipeViewPoints(pipeNode(view, id));
  const at = Math.min(Math.max(index, 0), points.length - 1);
  const here = points[at]!;
  const next = points[at + 1];
  const joint = next ? { x: round((here.x + next.x) / 2), y: round((here.y + next.y) / 2) } : { x: here.x + 24, y: here.y };
  return setPipePoints(view, id, [...points.slice(0, at + 1), joint, ...points.slice(at + 1)]);
}

/** The view with joint [index] removed; a pipe keeps at least its two ends. */
export function removeJoint(view: ViewDocument, id: string, index: number): ViewDocument {
  const points = pipeViewPoints(pipeNode(view, id));
  if (points.length <= 2) return view;
  return setPipePoints(view, id, points.filter((_, at) => at !== index));
}

/** The view with point [index] of pipe [id] moved to [to] (view pixels). A port-linked pipe keeps its ends and stores the middle as waypoints. */
export function moveJoint(view: ViewDocument, id: string, index: number, to: Pt): ViewDocument {
  const node = pipeNode(view, id);
  const points = pipeViewPoints(node);
  if (index < 0 || index >= points.length) return view;
  const next = points.map((point, at) => (at === index ? to : point));
  if (parsePipeEnd(node.props.from) || parsePipeEnd(node.props.to)) {
    const waypoints = next.slice(1, -1);
    const routed = resolvePipeRoute({ ...node.props, waypoints }, sceneChildren(view));
    if (!routed || routed.length < 2) return view;
    return updateNode(setPipePoints(view, id, routed), id, { props: { waypoints } });
  }
  return setPipePoints(view, id, next);
}

/** The components a pipe's two ends sit on (an end within the snap radius of an anchor), for the Inspector's summary. */
export function pipeEnds(view: ViewDocument, id: string): { start?: Anchor; end?: Anchor } {
  const points = pipeViewPoints(pipeNode(view, id));
  const anchors = pipeAnchors(view);
  const first = points[0];
  const last = points[points.length - 1];
  return {
    start: first ? snapToAnchor(first, anchors, 1.5).anchor : undefined,
    end: last ? snapToAnchor(last, anchors, 1.5).anchor : undefined,
  };
}

