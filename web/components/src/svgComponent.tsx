import type { HmiComponentProps, JsonSchema } from "@oip/hmi-runtime";
import { DrawingView, type DrawingOverrides } from "./media";
import { readString } from "./read";

/**
 * SVG component (HMV-151): an imported SVG whose groups, shapes and texts are parameters.
 *
 * `svg` holds the markup the designer's import step cleaned. `fills` and `strokes` map an element id to a colour,
 * `visibility` maps an id to true or false, and `texts` maps a text element's id to its text. A group's colour also
 * repaints the shapes in it that colour themselves, so one value recolours a whole group. Every entry is an ordinary
 * property path (`fills.pump_body`), so a binding on it, from a view parameter, a tag or an expression, drives that
 * one part of the drawing. The markup is filtered again when it is drawn, so a hand-edited view cannot smuggle script in.
 *
 * Doc source: FEATURES HMV-151.
 */

const SCHEMA = "https://json-schema.org/draft/2020-12/schema";

const DEFAULT_SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><g id="body"><rect id="shell" x="20" y="10" width="60" height="80" rx="6" fill="#d9dee3" stroke="#4a5560" stroke-width="2"/>' +
  '<rect id="level" x="22" y="50" width="56" height="38" fill="#3d7ab8"/></g><text id="caption" x="50" y="8" text-anchor="middle" font-size="8">Tank</text></svg>';

export const svgComponentSchema: JsonSchema = {
  $schema: SCHEMA,
  type: "object",
  title: "SVG component",
  additionalProperties: false,
  properties: {
    svg: { type: "string", title: "SVG markup", default: DEFAULT_SVG },
    fills: { type: "object", title: "Fill colours (by element id)", description: '{ "body": "#c00" }', default: {} },
    strokes: { type: "object", title: "Stroke colours (by element id)", description: '{ "shell": "#000" }', default: {} },
    visibility: { type: "object", title: "Visibility (by element id)", description: '{ "level": false }', default: {} },
    texts: { type: "object", title: "Texts (by element id)", description: '{ "caption": "Tank 1" }', default: {} },
    alt: { type: "string", title: "Alt text", default: "SVG component" },
    fit: { type: "string", title: "Fit", enum: ["contain", "cover", "fill"], default: "contain" },
  },
};

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

/** The property maps as per-element attribute overrides and texts, as [DrawingView] takes them. */
export function svgParts(props: Record<string, unknown>): { overrides: DrawingOverrides; texts: Record<string, string> } {
  const overrides: DrawingOverrides = {};
  const put = (id: string, name: string, value: unknown) => {
    (overrides[id] ??= {})[name] = value;
  };
  for (const [id, value] of Object.entries(record(props.fills))) if (typeof value === "string") put(id, "fill", value);
  for (const [id, value] of Object.entries(record(props.strokes))) if (typeof value === "string") put(id, "stroke", value);
  for (const [id, value] of Object.entries(record(props.visibility))) {
    if (typeof value === "boolean") put(id, "display", value ? "inline" : "none");
    else if (value === "true" || value === "false") put(id, "display", value === "true" ? "inline" : "none");
  }
  const texts: Record<string, string> = {};
  for (const [id, value] of Object.entries(record(props.texts))) {
    if (typeof value === "string" || typeof value === "number") texts[id] = String(value);
  }
  return { overrides, texts };
}

export function SvgComponent({ props }: HmiComponentProps) {
  const { overrides, texts } = svgParts(props);
  const fit = typeof props.fit === "string" && ["contain", "cover", "fill"].includes(props.fit) ? props.fit : "contain";
  return <DrawingView markup={readString(props, "svg")} overrides={overrides} alt={readString(props, "alt", "SVG component")} fit={fit} extras={{ texts, cascade: true }} />;
}
