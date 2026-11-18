import { styleSubsetToCss } from "./styleSubset";
import type { CSSProperties } from "react";
import type {
  Breakpoint,
  BreakpointName,
  BreakpointSize,
  ColumnPlacement,
  ContainerProps,
  FlexChild,
  Frame,
  SplitBar,
  StyleProps,
  ViewNode,
} from "./types";

/** Default column breakpoints. Public docs describe the mechanism and an example
 * (medium 280, large 500) but not a required default set. These are ours.
 */
export const DEFAULT_COLUMN_BREAKPOINTS: Breakpoint[] = [
  { name: "sm", minWidth: 0 },
  { name: "md", minWidth: 480 },
  { name: "lg", minWidth: 960 },
];

export function activeBreakpoint(width: number, breakpoints: readonly Breakpoint[] = DEFAULT_COLUMN_BREAKPOINTS): BreakpointName {
  const sorted = [...breakpoints].sort((a, b) => a.minWidth - b.minWidth);
  let current = sorted[0]?.name ?? "sm";
  for (const breakpoint of sorted) {
    if (width >= breakpoint.minWidth) current = breakpoint.name;
  }
  return current;
}

export function placementFor(
  width: number,
  placements: readonly ColumnPlacement[],
  breakpoints: readonly Breakpoint[] = DEFAULT_COLUMN_BREAKPOINTS,
): ColumnPlacement {
  const name = activeBreakpoint(width, breakpoints);
  const order: BreakpointName[] = ["lg", "md", "sm"];
  const start = order.indexOf(name);
  for (let i = start; i < order.length; i += 1) {
    const found = placements.find((placement) => placement.name === order[i]);
    if (found) return found;
  }
  return placements[0] ?? { name: "sm", span: 12, rowIndex: 0, colIndex: 0, order: 0 };
}

/** "16:9" -> "16 / 9". Empty or non x:y input disables the ratio. */
export function aspectRatioCss(value: string | undefined): string | undefined {
  if (!value) return undefined;
  const match = /^(\d+(?:\.\d+)?)\s*:\s*(\d+(?:\.\d+)?)$/.exec(value.trim());
  if (!match || match[1] === "0" || match[2] === "0") return undefined;
  return `${match[1]} / ${match[2]}`;
}

export function coordinateChildStyle(frame: Frame, mode: "fixed" | "percent"): CSSProperties {
  const rotate = frame.rotate ? `rotate(${frame.rotate}deg)` : undefined;
  if (mode === "percent") {
    return {
      position: "absolute",
      left: `${frame.x * 100}%`,
      top: `${frame.y * 100}%`,
      width: `${frame.width * 100}%`,
      height: `${frame.height * 100}%`,
      transform: rotate,
    };
  }
  return {
    position: "absolute",
    left: `${frame.x}px`,
    top: `${frame.y}px`,
    width: `${frame.width}px`,
    height: `${frame.height}px`,
    transform: rotate,
  };
}

export function coordinateContainerStyle(props: ContainerProps, width: number, height: number): CSSProperties {
  const mode = props.mode ?? "fixed";
  return {
    position: "relative",
    width: mode === "fixed" ? `${width}px` : "100%",
    height: mode === "fixed" ? `${height}px` : "100%",
    aspectRatio: mode === "percent" ? aspectRatioCss(props.aspectRatio) : undefined,
  };
}

export function flexContainerStyle(props: ContainerProps): CSSProperties {
  return {
    display: "flex",
    flexDirection: props.direction ?? "row",
    flexWrap: props.wrap ?? "nowrap",
    justifyContent: props.justify ?? "flex-start",
    alignItems: props.alignItems ?? "stretch",
    alignContent: props.alignContent ?? "stretch",
    width: "100%",
    height: "100%",
    minHeight: 0,
  };
}

export function flexChildStyle(flex: FlexChild): CSSProperties {
  return {
    flexGrow: flex.grow,
    flexShrink: flex.shrink,
    flexBasis: typeof flex.basis === "number" ? `${flex.basis}px` : flex.basis,
  };
}

export function columnContainerStyle(props: ContainerProps): CSSProperties {
  const gutters = props.gutters ?? { vertical: 8, horizontal: 8 };
  return {
    display: "grid",
    gridTemplateColumns: "repeat(12, minmax(0, 1fr))",
    columnGap: `${gutters.horizontal}px`,
    rowGap: `${gutters.vertical}px`,
    alignContent: "start",
    width: "100%",
  };
}

export function columnChildStyle(placement: ColumnPlacement): CSSProperties {
  const span = Math.min(12 - placement.colIndex, placement.span);
  return {
    gridColumn: `${placement.colIndex + 1} / span ${Math.max(1, span)}`,
    gridRow: placement.rowIndex + 1,
    order: placement.order,
    minHeight: placement.height !== undefined ? `${placement.height}px` : undefined,
  };
}

export function styleToCss(style: StyleProps | undefined): CSSProperties {
  return styleSubsetToCss(style);
}

/** Breakpoint container: the large child shows when the measured size is at or above the breakpoint,
 * the small child below it. The public page does not say which side the boundary falls on; ours is ">= large".
 */
export function breakpointSize(measured: number, breakpoint: number): BreakpointSize {
  return measured >= breakpoint ? "large" : "small";
}

/** The child shown for a size: the child whose `size` matches, else by order (first small, second large). */
export function breakpointChild<T extends Pick<ViewNode, "size">>(children: readonly T[], size: BreakpointSize): T | undefined {
  const tagged = children.find((child) => child.size === size);
  if (tagged) return tagged;
  const untagged = children.filter((child) => child.size === undefined);
  const taken = children.some((child) => child.size !== undefined && child.size !== size);
  if (taken) return untagged[0];
  return size === "small" ? untagged[0] : (untagged[1] ?? untagged[0]);
}

/** Resolves a split position (pixels, "120px" or "40%") against the space beside the bar. */
export function resolveSplitPosition(position: number | string, available: number): number {
  if (typeof position === "number") return position;
  const match = /^\s*(\d+(?:\.\d+)?)\s*(%|px)?\s*$/.exec(position);
  if (!match) return available / 2;
  const value = Number(match[1]);
  return match[2] === "%" ? (available * value) / 100 : value;
}

/** Clamps the first pane's size so that neither pane goes under its minimum.
 * `total` is the container size along the split axis, bar included. When both minimums do not fit,
 * the first pane keeps its minimum and the second pane gets what is left.
 * An unknown total (0, not measured yet) only applies the first pane's minimum.
 */
export function clampSplit(position: number, total: number, bar: Pick<SplitBar, "size" | "minFirst" | "minSecond">): number {
  const lower = Math.max(0, bar.minFirst);
  if (!(total > 0)) return Number.isFinite(position) ? Math.max(lower, position) : lower;
  const upper = Math.max(lower, total - bar.size - bar.minSecond);
  return Math.min(upper, Math.max(lower, position));
}

export function splitContainerStyle(props: ContainerProps): CSSProperties {
  return {
    display: "flex",
    flexDirection: props.orientation === "vertical" ? "column" : "row",
    width: "100%",
    height: "100%",
    minWidth: 0,
    minHeight: 0,
    overflow: "hidden",
  };
}

export function splitPaneStyle(first: boolean, position: number, props: ContainerProps): CSSProperties {
  const vertical = props.orientation === "vertical";
  if (!first) return { flex: "1 1 0", minWidth: 0, minHeight: 0, overflow: "auto", position: "relative" };
  return {
    flex: `0 0 ${position}px`,
    [vertical ? "height" : "width"]: `${position}px`,
    minWidth: 0,
    minHeight: 0,
    overflow: "auto",
    position: "relative",
  };
}

export function splitBarStyle(props: ContainerProps): CSSProperties {
  const bar = props.split;
  const vertical = props.orientation === "vertical";
  const size = bar?.visible === false ? 0 : (bar?.size ?? 6);
  return {
    flex: `0 0 ${size}px`,
    cursor: bar?.draggable === false ? "default" : vertical ? "row-resize" : "col-resize",
    touchAction: "none",
  };
}
