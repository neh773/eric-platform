import type { CSSProperties } from "react";
import { EmbeddedView } from "@oip/hmi-runtime";
import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { FlexRepeater, listError, readList } from "./containers";

/**
 * Template canvas (HMV-133): a container that repeats one reusable view (a template) once for each entry of a list of
 * parameter sets, and positions the copies either absolutely or with flex layout.
 *
 * Each entry of `instances` is an object whose fields become the template's params, plus `index` (the entry's
 * position) unless the entry sets its own. An entry may carry three reserved fields that are not params:
 * `instanceFrame` (`x`, `y`, `width`, `height` in pixels, for absolute layout), `instanceView` (a different template
 * path for this entry) and, in flex layout, `instancePosition` and `instanceStyle` as in the flex repeater.
 * Absolute layout puts an entry without a frame in a grid of `columns` cells of `cellWidth` by `cellHeight` pixels
 * with `gap` between them, so a list of parameter sets lays out with no positions at all. Flex layout is the flex
 * repeater's. The list can be bound, so the set of copies follows the data.
 *
 * Doc source: FEATURES HMV-133.
 */

type Json = Record<string, unknown>;

export interface CanvasFrame {
  x: number;
  y: number;
  width?: number;
  height?: number;
}

export interface CanvasInstance {
  key: string;
  /** The template to show; empty means the component's `path`. */
  view: string;
  params: Json;
  frame?: Partial<CanvasFrame>;
}

const RESERVED = new Set(["instanceFrame", "instanceView", "instancePosition", "instanceStyle"]);

function isRecord(value: unknown): value is Json {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function finite(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function readFrame(value: unknown): Partial<CanvasFrame> | undefined {
  if (!isRecord(value)) return undefined;
  const frame: Partial<CanvasFrame> = {};
  for (const key of ["x", "y", "width", "height"] as const) {
    const number = finite(value[key]);
    if (number !== undefined) frame[key] = key === "width" || key === "height" ? Math.max(0, number) : number;
  }
  return Object.keys(frame).length > 0 ? frame : undefined;
}

/** One instance per entry of the list. A non-object entry is passed as the `value` param. */
export function canvasInstances(value: unknown): CanvasInstance[] {
  return readList(value).map((entry, index) => {
    if (!isRecord(entry)) return { key: String(index), view: "", params: { index, value: entry } };
    const params: Json = { index };
    for (const [key, field] of Object.entries(entry)) if (!RESERVED.has(key)) params[key] = field;
    return {
      key: String(index),
      view: typeof entry.instanceView === "string" ? entry.instanceView : "",
      params,
      frame: readFrame(entry.instanceFrame),
    };
  });
}

/** Where entry [index] goes in the grid that places entries without a frame. */
export function gridFrame(index: number, columns: number, cellWidth: number, cellHeight: number, gap: number): CanvasFrame {
  const perRow = Math.max(1, Math.trunc(columns));
  return { x: (index % perRow) * (cellWidth + gap), y: Math.floor(index / perRow) * (cellHeight + gap), width: cellWidth, height: cellHeight };
}

function number(value: unknown, fallback: number, minimum: number): number {
  const read = finite(typeof value === "string" && value.trim() !== "" ? Number(value) : value);
  return read === undefined ? fallback : Math.max(minimum, read);
}

export function TemplateCanvas(props: HmiComponentProps) {
  if (props.props.layout === "flex") return <FlexRepeater {...props} />;
  const source = props.props;
  const path = typeof source.path === "string" ? source.path : "";
  const instances = canvasInstances(source.instances);
  const columns = number(source.columns, 3, 1);
  const cellWidth = number(source.cellWidth, 160, 1);
  const cellHeight = number(source.cellHeight, 120, 1);
  const gap = number(source.gap, 8, 0);
  const error = listError(source.instances);
  return (
    <>
      {error ? (
        <div className="oip-unknown" role="alert">
          Instances {error}
        </div>
      ) : null}
      <div
        className="oip-template-canvas"
        role="list"
        aria-label={typeof source.label === "string" ? source.label : "Template canvas"}
        data-layout="absolute"
        data-instance-count={instances.length}
        style={{ position: "relative", overflow: typeof source.overflow === "string" && ["auto", "visible", "scroll", "hidden"].includes(source.overflow) ? (source.overflow as CSSProperties["overflow"]) : "auto" }}
      >
        {instances.map((instance, index) => {
          const frame = { ...gridFrame(index, columns, cellWidth, cellHeight, gap), ...instance.frame };
          const sized = frame.width !== undefined && frame.height !== undefined;
          return (
            <div
              key={instance.key}
              role="listitem"
              className="oip-template-item"
              data-instance-index={instance.key}
              style={{ position: "absolute", left: frame.x, top: frame.y, width: frame.width, height: frame.height }}
            >
              <EmbeddedView
                path={instance.view || path}
                params={instance.params}
                instanceKey={`${props.nodeId}-${instance.key}`}
                useDefaultViewWidth={!sized}
                useDefaultViewHeight={!sized}
              />
            </div>
          );
        })}
      </div>
    </>
  );
}

const SCHEMA = "https://json-schema.org/draft/2020-12/schema";

export const templateCanvasSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "Template canvas",
  additionalProperties: false,
  properties: {
    label: { type: "string", title: "Accessible label", default: "Template canvas" },
    path: { type: "string", title: "Template (view path)", default: "" },
    instances: { type: "string", title: "Parameter sets (JSON array of param objects; or bind an array)", default: "[]" },
    layout: { type: "string", title: "Positioning", enum: ["absolute", "flex"], default: "absolute" },
    columns: { type: "integer", title: "Absolute: grid columns for entries without a frame", minimum: 1, default: 3 },
    cellWidth: { type: "number", title: "Absolute: grid cell width", minimum: 1, default: 160 },
    cellHeight: { type: "number", title: "Absolute: grid cell height", minimum: 1, default: 120 },
    gap: { type: "number", title: "Absolute: grid gap", minimum: 0, default: 8 },
    direction: { type: "string", title: "Flex: direction", enum: ["row", "row-reverse", "column", "column-reverse"], default: "row" },
    wrap: { type: "string", title: "Flex: wrap", enum: ["nowrap", "wrap", "wrap-reverse"], default: "wrap" },
    justify: { type: "string", title: "Flex: justify", enum: ["flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly"], default: "flex-start" },
    alignItems: { type: "string", title: "Flex: align items", enum: ["flex-start", "flex-end", "center", "baseline", "stretch"], default: "flex-start" },
    useDefaultViewWidth: { type: "boolean", title: "Flex: use default view width", default: true },
    useDefaultViewHeight: { type: "boolean", title: "Flex: use default view height", default: true },
  },
};
