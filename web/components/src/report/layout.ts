import { defaultCellFormat, type CellFormat, type FormatKind } from "./format";
import type { JsonValue, ReportResult } from "./types";

/**
 * Report `layout` schema, layoutVersion 1, parsed with the same rules as gateway/report-render ReportLayout.kt.
 * The schema is documented on ReportLayout.kt in gateway/report-render.
 */

export const LAYOUT_VERSION = 1;
export const DEFAULT_CHART_HEIGHT = 220;

export class LayoutError extends Error {}

export type Align = "left" | "right" | "center";

export interface TableColumnSpec {
  field: string;
  label?: string;
  unit?: string;
  format: CellFormat;
  align?: Align;
  width: number;
}

export interface YAxisSpec {
  min?: number;
  max?: number;
  label?: string;
  decimals?: number;
  /** `min` / `max` given as a data key such as "@Limit@", resolved at render time (REP-059). */
  minKey?: string;
  maxKey?: string;
}

export interface TableSpec {
  type: "table";
  title?: string;
  dataset: string;
  columns: TableColumnSpec[];
  maxRows?: number;
  summary?: boolean;
  /** Header row of column labels. Default true. */
  showHeader?: boolean;
  /** Shaded alternate detail rows. Default true. */
  zebra?: boolean;
  /** Column whose value groups the rows: a heading row per distinct value (REP-050). */
  groupBy?: string;
  /** With groupBy: a subtotal row after each group. */
  groupSummary?: boolean;
  /** Column widths follow the longest text (REP-016). */
  autoSize?: boolean;
}

export interface TimeSeriesSpec {
  type: "timeSeries";
  title?: string;
  dataset: string;
  series: string[];
  x?: string;
  y: string[];
  height: number;
  yAxis: YAxisSpec;
  timePattern?: string;
}

export interface BarSpec {
  type: "bar";
  title?: string;
  dataset: string;
  category: string;
  values: string[];
  height: number;
  yAxis: YAxisSpec;
  categoryFormat: CellFormat;
}

export interface PieSpec {
  type: "pie";
  title?: string;
  dataset: string;
  category: string;
  value: string;
  height: number;
  categoryFormat: CellFormat;
  showPercent: boolean;
}

export interface XySpec {
  type: "xy";
  title?: string;
  dataset: string;
  x: string;
  y: string[];
  height: number;
  xAxis: YAxisSpec;
  yAxis: YAxisSpec;
  connect: boolean;
}

export interface ImageSpec {
  type: "image";
  title?: string;
  src: string;
  width?: number;
  height?: number;
  align: Align;
  alt?: string;
}

export interface TextSpec {
  type: "text";
  title?: string;
  text: string;
}

export type ComponentSpec = TableSpec | TimeSeriesSpec | BarSpec | PieSpec | XySpec | ImageSpec | TextSpec;

export type PageSizeName = "A4" | "A5" | "Letter" | "Legal";

/** Page sizes in points (portrait), as gateway/report-render PageSize. */
export const PAGE_SIZES: Record<PageSizeName, { width: number; height: number }> = {
  A4: { width: 595.2756, height: 841.8898 },
  A5: { width: 419.5276, height: 595.2756 },
  Letter: { width: 612, height: 792 },
  Legal: { width: 612, height: 1008 },
};

export interface PageMargins {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

export const DEFAULT_MARGIN = 40;
export const MIN_MARGIN = 24;
export const MAX_MARGIN = 200;

export interface ReportLayout {
  page: { size: PageSizeName; orientation: "portrait" | "landscape"; margins: PageMargins };
  timezone: string;
  header: { title?: string; subtitle?: string; showParameters: boolean; showGeneratedAt: boolean };
  components: ComponentSpec[];
}

type Obj = Record<string, JsonValue>;

function isObj(v: unknown): v is Obj {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function str(o: Obj | undefined, key: string): string | undefined {
  const v = o?.[key];
  return typeof v === "string" ? v : undefined;
}

function num(o: Obj | undefined, key: string): number | undefined {
  const v = o?.[key];
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}

function bool(o: Obj | undefined, key: string, fallback: boolean): boolean {
  const v = o?.[key];
  return typeof v === "boolean" ? v : fallback;
}

function strings(v: JsonValue | undefined): string[] {
  return Array.isArray(v) ? v.map((x) => (typeof x === "string" ? x : JSON.stringify(x))) : [];
}

function validZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

function parseFormat(v: JsonValue | undefined, at: string): CellFormat {
  if (!isObj(v)) return defaultCellFormat;
  const type = (str(v, "type") ?? "auto").toLowerCase();
  if (!["auto", "text", "number", "timestamp", "boolean"].includes(type)) {
    throw new LayoutError(`${at}.type must be auto, text, number, timestamp or boolean`);
  }
  const decimals = num(v, "decimals");
  if (decimals !== undefined && (decimals < 0 || decimals > 10)) throw new LayoutError(`${at}.decimals must be 0..10`);
  return {
    type: type as FormatKind,
    decimals: decimals === undefined ? undefined : Math.trunc(decimals),
    grouping: bool(v, "grouping", false),
    pattern: str(v, "pattern"),
    nullText: str(v, "nullText") ?? "",
  };
}

function parseColumn(c: JsonValue, at: string): TableColumnSpec {
  if (typeof c === "string") return { field: c, format: defaultCellFormat, width: 1 };
  if (!isObj(c)) throw new LayoutError(`${at} must be a string or an object`);
  const field = str(c, "field");
  if (!field) throw new LayoutError(`${at}.field is required`);
  const width = num(c, "width") ?? 1;
  if (width <= 0) throw new LayoutError(`${at}.width must be > 0`);
  const align = str(c, "align")?.toLowerCase();
  if (align !== undefined && align !== "left" && align !== "right" && align !== "center") {
    throw new LayoutError(`${at}.align must be left, right or center`);
  }
  return { field, label: str(c, "label"), unit: str(c, "unit"), format: parseFormat(c.format, `${at}.format`), align, width };
}

function parseHeight(c: Obj, at: string): number {
  const h = num(c, "height") ?? DEFAULT_CHART_HEIGHT;
  if (h < 80 || h > 600) throw new LayoutError(`${at}.height must be 80..600 points`);
  return h;
}

function parseYAxis(v: JsonValue | undefined): YAxisSpec {
  const o = isObj(v) ? v : undefined;
  return { min: num(o, "min"), max: num(o, "max"), label: str(o, "label"), decimals: num(o, "decimals"), minKey: str(o, "min"), maxKey: str(o, "max") };
}

const MAX_IMAGE_SRC = 4_000_000;
const DATA_KEY = /^@[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?@$/;

function parseImage(c: Obj, title: string | undefined, at: string): ImageSpec {
  const src = str(c, "src");
  if (src === undefined) throw new LayoutError(`${at}.src is required`);
  if (src.length > MAX_IMAGE_SRC) throw new LayoutError(`${at}.src is too long`);
  if (!DATA_KEY.test(src) && !src.startsWith("data:image/")) {
    throw new LayoutError(`${at}.src must be a data:image/png or data:image/jpeg URI or a data key such as @Logo@`);
  }
  const size = (name: string): number | undefined => {
    const v = c[name];
    if (v === undefined || v === null) return undefined;
    if (typeof v !== "number" || v < 8 || v > 2000) throw new LayoutError(`${at}.${name} must be 8..2000 points`);
    return v;
  };
  const align = str(c, "align")?.toLowerCase() ?? "left";
  if (align !== "left" && align !== "right" && align !== "center") throw new LayoutError(`${at}.align must be left, right or center`);
  return { type: "image", title, src, width: size("width"), height: size("height"), align, alt: str(c, "alt") };
}

function parseComponent(c: JsonValue, at: string): ComponentSpec {
  if (!isObj(c)) throw new LayoutError(`${at} must be an object`);
  const title = str(c, "title");
  const type = str(c, "type") ?? "";
  if (type === "text") {
    const text = str(c, "text");
    if (text === undefined) throw new LayoutError(`${at}.text is required`);
    if (text.length > 8000) throw new LayoutError(`${at}.text is too long`);
    return { type: "text", title, text };
  }
  if (type === "image") return parseImage(c, title, at);
  const dataset = str(c, "dataset");
  if (!dataset) throw new LayoutError(`${at}.dataset is required`);
  switch (type) {
    case "table": {
      const maxRows = num(c, "maxRows");
      if (maxRows !== undefined && maxRows < 1) throw new LayoutError(`${at}.maxRows must be >= 1`);
      const columns = Array.isArray(c.columns) ? c.columns.map((col, i) => parseColumn(col, `${at}.columns[${i}]`)) : [];
      return {
        type,
        title,
        dataset,
        columns,
        maxRows,
        summary: c.summary === true,
        showHeader: c.showHeader !== false,
        zebra: c.zebra !== false,
        groupBy: str(c, "groupBy"),
        groupSummary: c.groupSummary === true,
        autoSize: c.autoSize === true,
      };
    }
    case "timeSeries":
      return {
        type,
        title,
        dataset,
        series: strings(c.series),
        x: str(c, "x"),
        y: strings(c.y),
        height: parseHeight(c, at),
        yAxis: parseYAxis(c.yAxis),
        timePattern: str(c, "timePattern"),
      };
    case "bar": {
      const category = str(c, "category");
      if (!category) throw new LayoutError(`${at}.category is required for a bar chart`);
      const values = strings(c.values);
      if (values.length === 0) throw new LayoutError(`${at}.values needs at least one column`);
      return {
        type,
        title,
        dataset,
        category,
        values,
        height: parseHeight(c, at),
        yAxis: parseYAxis(c.yAxis),
        categoryFormat: parseFormat(c.categoryFormat, `${at}.categoryFormat`),
      };
    }
    case "pie": {
      const category = str(c, "category");
      if (!category) throw new LayoutError(`${at}.category is required for a pie chart`);
      const value = str(c, "value");
      if (!value) throw new LayoutError(`${at}.value is required for a pie chart`);
      return {
        type,
        title,
        dataset,
        category,
        value,
        height: parseHeight(c, at),
        categoryFormat: parseFormat(c.categoryFormat, `${at}.categoryFormat`),
        showPercent: c.showPercent !== false,
      };
    }
    case "xy": {
      const x = str(c, "x");
      if (!x) throw new LayoutError(`${at}.x is required for an xy chart`);
      const y = strings(c.y);
      if (y.length === 0) throw new LayoutError(`${at}.y needs at least one column`);
      return { type, title, dataset, x, y, height: parseHeight(c, at), xAxis: parseYAxis(c.xAxis), yAxis: parseYAxis(c.yAxis), connect: c.connect === true };
    }
    default:
      throw new LayoutError(`${at}.type must be table, timeSeries, bar, pie, xy, image, or text, not '${type}'`);
  }
}

function parseMargins(v: JsonValue | undefined): PageMargins {
  const one = (value: JsonValue | undefined, at: string): number => {
    if (typeof value !== "number" || !Number.isFinite(value)) throw new LayoutError(`${at} must be a number of points`);
    if (value < MIN_MARGIN || value > MAX_MARGIN) throw new LayoutError(`${at} must be ${MIN_MARGIN}..${MAX_MARGIN} points`);
    return value;
  };
  if (v === undefined || v === null) return { top: DEFAULT_MARGIN, right: DEFAULT_MARGIN, bottom: DEFAULT_MARGIN, left: DEFAULT_MARGIN };
  if (typeof v === "number") {
    const m = one(v, "page.margins");
    return { top: m, right: m, bottom: m, left: m };
  }
  if (!isObj(v)) throw new LayoutError("page.margins must be a number or an object");
  const extra = Object.keys(v).filter((k) => !["top", "right", "bottom", "left"].includes(k));
  if (extra.length > 0) throw new LayoutError(`page.margins: unknown fields ${extra.sort().join(", ")}`);
  const side = (name: keyof PageMargins) => (name in v ? one(v[name], `page.margins.${name}`) : DEFAULT_MARGIN);
  return { top: side("top"), right: side("right"), bottom: side("bottom"), left: side("left") };
}

export function parseLayout(raw: unknown): ReportLayout {
  const n = isObj(raw) ? raw : undefined;
  const version = num(n, "layoutVersion") ?? LAYOUT_VERSION;
  if (version !== LAYOUT_VERSION) throw new LayoutError(`layoutVersion ${version} is not supported (expected ${LAYOUT_VERSION})`);
  const page = isObj(n?.page) ? n.page : undefined;
  const sizeText = (str(page, "size") ?? "A4").toUpperCase();
  const size = (Object.keys(PAGE_SIZES) as PageSizeName[]).find((name) => name.toUpperCase() === sizeText);
  if (!size) throw new LayoutError(`page.size must be ${Object.keys(PAGE_SIZES).join(", ")}, not ${sizeText}`);
  const orientation = str(page, "orientation") ?? "portrait";
  if (orientation !== "portrait" && orientation !== "landscape") throw new LayoutError("page.orientation must be portrait or landscape");
  const margins = parseMargins(page?.margins);
  const dims = PAGE_SIZES[size];
  const [w, h] = orientation === "landscape" ? [dims.height, dims.width] : [dims.width, dims.height];
  if (w - margins.left - margins.right < 100) throw new LayoutError("page.margins leave less than 100 points of width");
  if (h - margins.top - margins.bottom < 100) throw new LayoutError("page.margins leave less than 100 points of height");
  const timezone = str(n, "timezone") ?? "UTC";
  if (!validZone(timezone)) throw new LayoutError(`timezone: unknown zone ${timezone}`);
  const header = isObj(n?.header) ? n.header : undefined;
  const components = Array.isArray(n?.components) ? n.components.map((c, i) => parseComponent(c, `components[${i}]`)) : [];
  return {
    page: { size, orientation, margins },
    timezone,
    header: {
      title: str(header, "title"),
      subtitle: str(header, "subtitle"),
      showParameters: bool(header, "showParameters", true),
      showGeneratedAt: bool(header, "showGeneratedAt", true),
    },
    components,
  };
}

/** Layout components, or one table per dataset when the layout lists none (same default as the PDF). */
export function effectiveComponents(layout: ReportLayout, result: ReportResult): ComponentSpec[] {
  if (layout.components.length > 0) return layout.components;
  return result.datasets.map((d) => ({ type: "table", title: d.name, dataset: d.name, columns: [] }));
}
