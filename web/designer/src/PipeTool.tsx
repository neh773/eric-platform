import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent, type PointerEvent } from "react";
import type { ComponentNode, ViewDocument } from "@oip/hmi-runtime";
import { SNAP_RADIUS, constrainAxis, dedupe, insertJoint, moveJoint, pipeAnchors, pipeEnds, pipeToolProblem, pipeViewPoints, removeJoint, snapToAnchor, type Anchor, type Pt } from "./pipeModel";

/**
 * The pipe tool (HMP-128). While it is on, a layer over the canvas takes the clicks: each click adds a joint, a point
 * near the edge of a component snaps to it, Shift keeps the segment horizontal or vertical, Backspace removes the last
 * joint, double click or Enter finishes the pipe and Esc cancels. The finished pipe is a `pipe` component.
 */
export function PipeDrawLayer({
  view,
  width,
  height,
  onFinish,
  onCancel,
}: {
  view: ViewDocument;
  width: number;
  height: number;
  onFinish: (points: Pt[]) => void;
  onCancel: () => void;
}) {
  const anchors = useMemo(() => pipeAnchors(view), [view]);
  const [points, setPoints] = useState<Pt[]>([]);
  const [cursor, setCursor] = useState<{ point: Pt; anchor?: Anchor } | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => ref.current?.focus(), []);

  function at(event: { clientX: number; clientY: number; shiftKey: boolean }): { point: Pt; anchor?: Anchor } {
    const box = ref.current!.getBoundingClientRect();
    const scaleX = box.width > 0 ? width / box.width : 1;
    const scaleY = box.height > 0 ? height / box.height : 1;
    let point = { x: Math.round((event.clientX - box.left) * scaleX), y: Math.round((event.clientY - box.top) * scaleY) };
    const last = points[points.length - 1];
    if (event.shiftKey && last) point = constrainAxis(last, point);
    return snapToAnchor(point, anchors, SNAP_RADIUS);
  }

  function finish(list: Pt[]) {
    const clean = dedupe(list);
    if (clean.length >= 2) onFinish(clean);
  }

  function onKey(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onCancel();
    } else if (event.key === "Enter") {
      event.preventDefault();
      finish(points);
    } else if (event.key === "Backspace") {
      event.preventDefault();
      setPoints((list) => list.slice(0, -1));
    }
  }

  const preview = cursor && points.length > 0 ? [...points, cursor.point] : points;
  return (
    <div
      ref={ref}
      className="oip-pipe-layer"
      role="application"
      aria-label="Pipe drawing"
      aria-description="Click to add a joint. Double click or Enter finishes the pipe. Backspace removes the last joint. Escape cancels."
      data-points={points.length}
      tabIndex={0}
      onKeyDown={onKey}
      onPointerMove={(event: PointerEvent<HTMLDivElement>) => setCursor(at(event))}
      onClick={(event: MouseEvent<HTMLDivElement>) => {
        const placed = at(event).point;
        const last = points[points.length - 1];
        const next = last && last.x === placed.x && last.y === placed.y ? points : [...points, placed];
        setPoints(next);
        if (event.detail >= 2) finish(next);
      }}
    >
      <svg width={width} height={height} viewBox={`0 0 ${String(width)} ${String(height)}`} aria-hidden="true" focusable="false">
        {anchors.map((anchor) => (
          <circle key={`${anchor.nodeId}-${anchor.side}`} className="oip-pipe-anchor" data-snapped={cursor?.anchor === anchor ? "yes" : undefined} cx={anchor.x} cy={anchor.y} r={cursor?.anchor === anchor ? 5 : 3} />
        ))}
        {preview.length > 1 ? <polyline className="oip-pipe-preview" fill="none" points={preview.map((point) => `${point.x},${point.y}`).join(" ")} /> : null}
        {points.map((point, index) => (
          <circle key={index} className="oip-pipe-vertex" cx={point.x} cy={point.y} r={4} />
        ))}
      </svg>
    </div>
  );
}

/** The Add tab's pipe section: a button that turns the pipe tool on or off, and why it cannot be on. */
export function PipeToolPanel({ view, active, onToggle }: { view: ViewDocument; active: boolean; onToggle: () => void }) {
  const problem = pipeToolProblem(view);
  return (
    <section className="oip-library oip-pipe-tool" aria-label="Pipe tool" data-testid="pipe-tool">
      <p>Pipes</p>
      <button type="button" aria-pressed={active} disabled={problem !== null && !active} onClick={onToggle}>
        {active ? "Stop drawing pipe" : "Draw pipe"}
      </button>
      {problem ? (
        <p className="oip-binding-muted" role="status">
          {problem}
        </p>
      ) : (
        <p className="oip-binding-muted">Click a port, then the port it runs to. A click near a port snaps to it. Clicks between the ends are waypoints. Double click or Enter finishes.</p>
      )}
    </section>
  );
}

/** A pipe's joints in the Inspector: each point's position in the view, with insert and remove. */
export function PipePointsEditor({ view, node, onView }: { view: ViewDocument; node: ComponentNode; onView: (view: ViewDocument) => void }) {
  const points = pipeViewPoints(node);
  const ends = pipeEnds(view, node.id);
  const name = (anchor?: Anchor) => (anchor ? view.spec.root.children.find((child) => child.id === anchor.nodeId)?.name : undefined);
  const move = (index: number, axis: "x" | "y", raw: string) => {
    const value = Number(raw);
    if (raw.trim() === "" || !Number.isFinite(value)) return;
    onView(moveJoint(view, node.id, index, { ...points[index]!, [axis]: Math.round(value) }));
  };
  return (
    <section className="oip-pipe-points" aria-label="Pipe joints" data-testid="pipe-points">
      <h3>Pipe joints</h3>
      <p className="oip-binding-muted">
        From {name(ends.start) ?? "free end"} to {name(ends.end) ?? "free end"}.
      </p>
      <ol>
        {points.map((point, index) => (
          <li key={index} aria-label={`Joint ${index + 1}`} className="oip-binding-grid">
            <label>
              <span>X</span>
              <input aria-label={`Joint ${index + 1} x`} type="number" value={Math.round(point.x)} onChange={(event) => move(index, "x", event.target.value)} />
            </label>
            <label>
              <span>Y</span>
              <input aria-label={`Joint ${index + 1} y`} type="number" value={Math.round(point.y)} onChange={(event) => move(index, "y", event.target.value)} />
            </label>
            <button type="button" aria-label={`Insert joint after joint ${index + 1}`} onClick={() => onView(insertJoint(view, node.id, index))}>
              Insert after
            </button>
            <button type="button" aria-label={`Remove joint ${index + 1}`} disabled={points.length <= 2} onClick={() => onView(removeJoint(view, node.id, index))}>
              Remove
            </button>
          </li>
        ))}
      </ol>
    </section>
  );
}
