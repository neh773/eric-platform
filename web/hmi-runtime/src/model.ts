import { parseRange } from "./transforms";
import { linkTarget } from "./links";
import { METHOD_NAME, METHOD_PARAM_NAME } from "./methods";
import { parseStyleObject, StyleError } from "./styleSubset";
import { HISTORY_AGGREGATES, MAX_HISTORY_WINDOWS, MIN_EXPRESSION_POLL_MS, MIN_HTTP_POLL_MS } from "./types";
import type {
  Action,
  Binding,
  ColumnPlacement,
  ComponentEvent,
  ComponentMeta,
  MessageHandler,
  MethodDef,
  MethodMap,
  MessageScope,
  ComponentNode,
  ContainerNode,
  DropRule,
  ContainerProps,
  FlexChild,
  FormatTransform,
  Frame,
  MapEntry,
  HistoryAggregateName,
  HistoryBinding,
  QueryParameter,
  SplitBar,
  TagBinding,
  ScriptTransform,
  Transform,
  ViewCustom,
  ViewDocument,
  ViewNode,
  ViewSecurity,
} from "./types";

export class ViewModelError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ViewModelError";
  }
}

const PATH_RE = /^[a-z0-9][a-z0-9-]*$/;
const LAYOUTS = new Set(["coordinate", "flex", "column", "tab", "breakpoint", "split"]);
const BREAKPOINTS = new Set(["sm", "md", "lg"]);

export interface ParseOptions {
  allowedTypes?: ReadonlySet<string>;
}

export function parseView(input: unknown, options: ParseOptions = {}): ViewDocument {
  const doc = asRecord(input, "view");
  if (doc.apiVersion !== "oip.hmi/v1") {
    throw new ViewModelError(`unsupported apiVersion: ${String(doc.apiVersion)}`);
  }
  if (doc.kind !== "View") throw new ViewModelError(`unsupported kind: ${String(doc.kind)}`);
  const metadata = asRecord(doc.metadata, "metadata");
  const name = requiredString(metadata, "name", "metadata.name");
  const path = requiredString(metadata, "path", "metadata.path");
  if (!PATH_RE.test(path)) throw new ViewModelError(`metadata.path is not a slug: ${path}`);
  const spec = asRecord(doc.spec, "spec");
  const width = requiredNumber(spec, "width", "spec.width");
  const height = requiredNumber(spec, "height", "spec.height");
  if (width <= 0 || height <= 0) throw new ViewModelError("spec width and height must be positive");
  const params = parseParams(spec.params);
  const custom = parseCustom(spec.custom);
  const ids = new Set<string>();
  const root = parseContainer(spec.root, "spec.root", ids, null, options);
  const view: ViewDocument = {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name, path },
    spec: { params, custom, width, height, root },
  };
  if (spec.events !== undefined) view.spec.events = parseEvents(spec.events, "spec.events");
  if (spec.methods !== undefined) view.spec.methods = parseMethods(spec.methods, "spec.methods");
  if (spec.security !== undefined) view.spec.security = parseSecurity(spec.security, "spec.security");
  if (spec.dropConfig !== undefined) view.spec.dropConfig = parseDropConfig(spec.dropConfig, options);
  return view;
}

const ROLE_RE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/;

const LEVEL_SEGMENT = "[A-Za-z0-9][A-Za-z0-9 _.-]{0,62}[A-Za-z0-9_.-]|[A-Za-z0-9]";
/** A security level path (SEC-017): up to eight `/`-separated names, as the gateway defines them. */
export const LEVEL_PATH_RE = new RegExp(`^(${LEVEL_SEGMENT})(/(${LEVEL_SEGMENT})){0,7}$`);

function parseSecurity(input: unknown, where: string): ViewSecurity {
  const record = asRecord(input, where);
  const names = (key: string, pattern: RegExp, what: string): string[] => {
    const raw = record[key] ?? [];
    if (!Array.isArray(raw)) throw new ViewModelError(`${where}.${key} must be a list of ${what}s`);
    const out: string[] = [];
    for (const [index, entry] of raw.entries()) {
      if (typeof entry !== "string" || !pattern.test(entry.trim())) throw new ViewModelError(`${where}.${key}[${index}] is not a ${what}`);
      if (!out.includes(entry.trim())) out.push(entry.trim());
    }
    return out;
  };
  const security: ViewSecurity = { roles: names("roles", ROLE_RE, "role name") };
  const levels = names("levels", LEVEL_PATH_RE, "security level");
  if (levels.length > 0) security.levels = levels;
  if (record.requireAll !== undefined) {
    if (typeof record.requireAll !== "boolean") throw new ViewModelError(`${where}.requireAll must be true or false`);
    if (record.requireAll) security.requireAll = true;
  }
  return security;
}

/** True when [security] names no role and no level, so it restricts nobody. */
export function securityIsEmpty(security: ViewSecurity | undefined): boolean {
  return !security || (security.roles.length === 0 && (security.levels ?? []).length === 0);
}

/** Data types a drop rule can name, in the gateway's spelling, plus `*` for any. */
export const DROP_DATA_TYPES = ["*", "boolean", "int32", "int64", "float", "double", "string", "datetime"] as const;

function parseDropConfig(input: unknown, options: ParseOptions): DropRule[] {
  if (!Array.isArray(input)) throw new ViewModelError("spec.dropConfig must be a list");
  return input.map((entry, index) => {
    const where = `spec.dropConfig[${index}]`;
    const record = asRecord(entry, where);
    const dataType = requiredString(record, "dataType", `${where}.dataType`).toLowerCase();
    if (!(DROP_DATA_TYPES as readonly string[]).includes(dataType)) throw new ViewModelError(`${where}.dataType is not a known data type: ${dataType}`);
    if (record.view !== undefined) {
      const view = requiredString(record, "view", `${where}.view`);
      if (!PATH_RE.test(view)) throw new ViewModelError(`${where}.view is not a view path: ${view}`);
      const param = requiredString(record, "param", `${where}.param`);
      return { dataType, view, param };
    }
    const component = requiredString(record, "component", `${where}.component`);
    if (options.allowedTypes && !options.allowedTypes.has(component)) throw new ViewModelError(`${where}.component is not a component type: ${component}`);
    const prop = requiredString(record, "prop", `${where}.prop`);
    const rule: DropRule = { dataType, component, prop };
    if (record.bidirectional !== undefined) {
      if (typeof record.bidirectional !== "boolean") throw new ViewModelError(`${where}.bidirectional must be a boolean`);
      rule.bidirectional = record.bidirectional;
    }
    return rule;
  });
}

export function walkNodes(node: ViewNode, visit: (node: ViewNode, parent: ContainerNode | null) => void, parent: ContainerNode | null = null): void {
  visit(node, parent);
  if (node.kind === "container") {
    for (const child of node.children) walkNodes(child, visit, node);
  }
}

export function findNode(root: ViewNode, id: string): ViewNode | undefined {
  let found: ViewNode | undefined;
  walkNodes(root, (node) => {
    if (node.id === id) found = node;
  });
  return found;
}

export function collectNodeMap(root: ViewNode): Map<string, ViewNode> {
  const map = new Map<string, ViewNode>();
  walkNodes(root, (node) => map.set(node.id, node));
  return map;
}

function parseParams(input: unknown): ViewDocument["spec"]["params"] {
  if (input === undefined) return {};
  const record = asRecord(input, "spec.params");
  const params: ViewDocument["spec"]["params"] = {};
  for (const [key, value] of Object.entries(record)) {
    const entry = asRecord(value, `spec.params.${key}`);
    if (!("value" in entry)) throw new ViewModelError(`spec.params.${key} requires value`);
    const param: ViewDocument["spec"]["params"][string] = { value: entry.value };
    if (entry.direction !== undefined) {
      const direction = requiredString(entry, "direction", `spec.params.${key}.direction`);
      if (direction !== "input" && direction !== "output" && direction !== "inout") {
        throw new ViewModelError(`spec.params.${key}.direction must be input, output, or inout`);
      }
      param.direction = direction;
    }
    if (entry.binding !== undefined) param.binding = parseBinding(entry.binding, `spec.params.${key}.binding`);
    params[key] = param;
  }
  return params;
}

function parseCustom(input: unknown): Record<string, ViewCustom> {
  if (input === undefined) return {};
  const record = asRecord(input, "spec.custom");
  const custom: Record<string, ViewCustom> = {};
  for (const [key, value] of Object.entries(record)) {
    const entry = asRecord(value, `spec.custom.${key}`);
    if (!("value" in entry)) throw new ViewModelError(`spec.custom.${key} requires value`);
    const prop: ViewCustom = { value: entry.value };
    if (entry.binding !== undefined) prop.binding = parseBinding(entry.binding, `spec.custom.${key}.binding`);
    custom[key] = prop;
  }
  return custom;
}

function parseContainer(
  input: unknown,
  where: string,
  ids: Set<string>,
  parent: ParentPlacement | null,
  options: ParseOptions,
): ContainerNode {
  const record = asRecord(input, where);
  if (record.kind !== "container") throw new ViewModelError(`${where} is not a container`);
  const layout = requiredString(record, "layout", `${where}.layout`);
  if (!LAYOUTS.has(layout)) throw new ViewModelError(`${where}.layout is unknown: ${layout}`);
  const props = parseContainerProps(record.props, `${where}.props`, layout as ContainerNode["layout"]);
  const node: ContainerNode = {
    id: parseId(record, where, ids),
    kind: "container",
    name: requiredString(record, "name", `${where}.name`),
    layout: layout as ContainerNode["layout"],
    props,
    children: [],
    ...parsePlacement(record, where, parent),
  };
  if (record.security !== undefined) node.security = parseSecurity(record.security, `${where}.security`);
  if (!Array.isArray(record.children)) throw new ViewModelError(`${where}.children must be an array`);
  const childParent: ParentPlacement = { layout: node.layout, mode: props.mode ?? "fixed" };
  node.children = record.children.map((child, index) =>
    parseNode(child, `${where}.children[${index}]`, ids, childParent, options),
  );
  if ((node.layout === "breakpoint" || node.layout === "split") && node.children.length > 2) {
    throw new ViewModelError(`${where}.children: a ${node.layout} container holds at most two children`);
  }
  if (node.layout === "breakpoint") {
    const sizes = node.children.map((child) => child.size).filter((size) => size !== undefined);
    if (new Set(sizes).size !== sizes.length) throw new ViewModelError(`${where}.children: each size (small, large) may be used once`);
  }
  return node;
}

interface ParentPlacement {
  layout: ContainerNode["layout"];
  mode: "fixed" | "percent";
}

function parseNode(
  input: unknown,
  where: string,
  ids: Set<string>,
  parent: ParentPlacement,
  options: ParseOptions,
): ViewNode {
  const record = asRecord(input, where);
  if (record.kind === "container") return parseContainer(input, where, ids, parent, options);
  if (record.kind !== "component") throw new ViewModelError(`${where}.kind must be container or component`);
  const type = requiredString(record, "type", `${where}.type`);
  if (options.allowedTypes && !options.allowedTypes.has(type)) {
    throw new ViewModelError(`${where}.type is not registered: ${type}`);
  }
  const props = asRecord(record.props ?? {}, `${where}.props`);
  const node: ComponentNode = {
    id: parseId(record, where, ids),
    kind: "component",
    name: requiredString(record, "name", `${where}.name`),
    type,
    props,
    ...parsePlacement(record, where, parent),
  };
  if (record.bindings !== undefined) node.bindings = parseBindings(record.bindings, `${where}.bindings`);
  if (record.events !== undefined) node.events = parseEvents(record.events, `${where}.events`);
  if (record.variant !== undefined) {
    const variant = requiredString(record, "variant", `${where}.variant`);
    if (!/^[a-z][a-z0-9-]{0,63}$/.test(variant)) throw new ViewModelError(`${where}.variant is not a variant name: ${variant}`);
    node.variant = variant;
  }
  if (record.methods !== undefined) node.methods = parseMethods(record.methods, `${where}.methods`);
  if (record.messageHandlers !== undefined) node.messageHandlers = parseMessageHandlers(record.messageHandlers, `${where}.messageHandlers`);
  if (record.custom !== undefined) node.custom = parseComponentCustom(record.custom, `${where}.custom`);
  if (record.meta !== undefined) node.meta = parseMeta(record.meta, `${where}.meta`);
  if (record.security !== undefined) node.security = parseSecurity(record.security, `${where}.security`);
  return node;
}

/** Custom property names: letters, digits and `_`, starting with a letter or `_` (they are read as `custom.<name>`). */
export const CUSTOM_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;

function parseComponentCustom(input: unknown, where: string): Record<string, unknown> {
  const record = asRecord(input, where);
  const custom: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(record)) {
    if (!CUSTOM_NAME.test(key)) throw new ViewModelError(`${where}: "${key}" is not a custom property name`);
    if (value === undefined || typeof value === "function") throw new ViewModelError(`${where}.${key} has no value`);
    custom[key] = value;
  }
  return custom;
}

function parseMeta(input: unknown, where: string): ComponentMeta {
  const record = asRecord(input, where);
  const meta: ComponentMeta = {};
  for (const key of Object.keys(record)) {
    if (key !== "visible" && key !== "tooltip") throw new ViewModelError(`${where}.${key} is not a meta property`);
  }
  if (record.visible !== undefined) meta.visible = requiredBoolean(record, "visible", `${where}.visible`);
  if (record.tooltip !== undefined) {
    if (typeof record.tooltip !== "string") throw new ViewModelError(`${where}.tooltip must be a string`);
    meta.tooltip = record.tooltip;
  }
  return meta;
}

function parsePlacement(
  record: Record<string, unknown>,
  where: string,
  parent: ParentPlacement | null,
): Pick<ViewNode, "frame" | "flex" | "columns" | "style" | "styleClasses" | "tabLabel" | "size"> {
  const placed: Pick<ViewNode, "frame" | "flex" | "columns" | "style" | "styleClasses" | "tabLabel" | "size"> = {};
  if (record.style !== undefined) placed.style = parseStyle(record.style, `${where}.style`);
  if (record.styleClasses !== undefined) placed.styleClasses = parseStyleClasses(record.styleClasses, `${where}.styleClasses`);
  if (parent === null) return placed;
  if (parent.layout === "coordinate") {
    placed.frame = parseFrame(record.frame, `${where}.frame`, parent.mode);
  } else if (parent.layout === "flex") {
    placed.flex = parseFlexChild(record.flex, `${where}.flex`);
  } else if (parent.layout === "tab") {
    if (record.tabLabel !== undefined) placed.tabLabel = requiredString(record, "tabLabel", `${where}.tabLabel`);
  } else if (parent.layout === "breakpoint") {
    if (record.size !== undefined) {
      const size = requiredString(record, "size", `${where}.size`);
      assertEnum(size, ["small", "large"], `${where}.size`);
      placed.size = size as "small" | "large";
    }
  } else if (parent.layout === "split") {
    // Split panes take their side from their order: the first child is left (or top).
  } else {
    placed.columns = parseColumns(record.columns, `${where}.columns`);
  }
  return placed;
}

function parseFrame(input: unknown, where: string, mode: "fixed" | "percent"): Frame {
  const record = asRecord(input, where);
  const frame: Frame = {
    x: requiredNumber(record, "x", `${where}.x`),
    y: requiredNumber(record, "y", `${where}.y`),
    width: requiredNumber(record, "width", `${where}.width`),
    height: requiredNumber(record, "height", `${where}.height`),
  };
  if (frame.width <= 0 || frame.height <= 0) throw new ViewModelError(`${where} width and height must be positive`);
  if (mode === "percent") {
    for (const key of ["x", "y", "width", "height"] as const) {
      const value = frame[key];
      if (value < 0 || value > 1) throw new ViewModelError(`${where}.${key} must be between 0 and 1 in percent mode`);
    }
  }
  if (record.rotate !== undefined) {
    frame.rotate = requiredNumber(record, "rotate", `${where}.rotate`);
  }
  return frame;
}

function parseFlexChild(input: unknown, where: string): FlexChild {
  if (input === undefined) return { grow: 0, shrink: 1, basis: "auto", display: true };
  const record = asRecord(input, where);
  const basis = record.basis;
  if (basis !== undefined && typeof basis !== "string" && typeof basis !== "number") {
    throw new ViewModelError(`${where}.basis must be a string or number`);
  }
  return {
    grow: record.grow === undefined ? 0 : requiredNumber(record, "grow", `${where}.grow`),
    shrink: record.shrink === undefined ? 1 : requiredNumber(record, "shrink", `${where}.shrink`),
    basis: (basis as string | number | undefined) ?? "auto",
    display: record.display === undefined ? true : requiredBoolean(record, "display", `${where}.display`),
  };
}

function parseColumns(input: unknown, where: string): ColumnPlacement[] {
  if (input === undefined) {
    return [
      { name: "sm", span: 12, rowIndex: 0, colIndex: 0, order: 0 },
      { name: "md", span: 12, rowIndex: 0, colIndex: 0, order: 0 },
      { name: "lg", span: 12, rowIndex: 0, colIndex: 0, order: 0 },
    ];
  }
  if (!Array.isArray(input) || input.length === 0) throw new ViewModelError(`${where} must be a non-empty array`);
  return input.map((entry, index) => {
    const record = asRecord(entry, `${where}[${index}]`);
    const name = requiredString(record, "name", `${where}[${index}].name`);
    if (!BREAKPOINTS.has(name)) throw new ViewModelError(`${where}[${index}].name must be sm, md, or lg`);
    const span = requiredNumber(record, "span", `${where}[${index}].span`);
    if (!Number.isInteger(span) || span < 1 || span > 12) {
      throw new ViewModelError(`${where}[${index}].span must be an integer from 1 to 12`);
    }
    const colIndex = requiredNumber(record, "colIndex", `${where}[${index}].colIndex`);
    if (!Number.isInteger(colIndex) || colIndex < 0 || colIndex > 11) {
      throw new ViewModelError(`${where}[${index}].colIndex must be an integer from 0 to 11`);
    }
    const placement: ColumnPlacement = {
      name: name as ColumnPlacement["name"],
      span,
      rowIndex: nonNegativeInt(record, "rowIndex", `${where}[${index}].rowIndex`),
      colIndex,
      order: record.order === undefined ? 0 : requiredNumber(record, "order", `${where}[${index}].order`),
    };
    if (record.height !== undefined) placement.height = requiredNumber(record, "height", `${where}[${index}].height`);
    return placement;
  });
}

function parseContainerProps(input: unknown, where: string, layout: ContainerNode["layout"]): ContainerProps {
  const record = asRecord(input ?? {}, where);
  const props: ContainerProps = {};
  if (layout === "coordinate") {
    const mode = record.mode === undefined ? "fixed" : requiredString(record, "mode", `${where}.mode`);
    if (mode !== "fixed" && mode !== "percent") throw new ViewModelError(`${where}.mode must be fixed or percent`);
    props.mode = mode;
    if (record.aspectRatio !== undefined) props.aspectRatio = requiredString(record, "aspectRatio", `${where}.aspectRatio`);
  }
  if (layout === "flex") {
    props.direction = (record.direction as ContainerProps["direction"]) ?? "row";
    props.wrap = (record.wrap as ContainerProps["wrap"]) ?? "nowrap";
    props.justify = (record.justify as ContainerProps["justify"]) ?? "flex-start";
    props.alignItems = (record.alignItems as ContainerProps["alignItems"]) ?? "stretch";
    props.alignContent = (record.alignContent as ContainerProps["alignContent"]) ?? "stretch";
    assertEnum(props.direction, ["row", "row-reverse", "column", "column-reverse"], `${where}.direction`);
    assertEnum(props.wrap, ["nowrap", "wrap", "wrap-reverse"], `${where}.wrap`);
    assertEnum(props.justify, ["flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly"], `${where}.justify`);
    assertEnum(props.alignItems, ["flex-start", "flex-end", "center", "baseline", "stretch"], `${where}.alignItems`);
    assertEnum(props.alignContent, ["flex-start", "flex-end", "center", "space-between", "space-around", "stretch"], `${where}.alignContent`);
  }
  if (layout === "column") {
    props.gutters = {
      vertical: 8,
      horizontal: 8,
    };
    if (record.gutters !== undefined) {
      const gutters = asRecord(record.gutters, `${where}.gutters`);
      props.gutters = {
        vertical: requiredNumber(gutters, "vertical", `${where}.gutters.vertical`),
        horizontal: requiredNumber(gutters, "horizontal", `${where}.gutters.horizontal`),
      };
    }
    if (record.breakpoints !== undefined) {
      if (!Array.isArray(record.breakpoints)) throw new ViewModelError(`${where}.breakpoints must be an array`);
      props.breakpoints = record.breakpoints.map((entry, index) => {
        const bp = asRecord(entry, `${where}.breakpoints[${index}]`);
        const name = requiredString(bp, "name", `${where}.breakpoints[${index}].name`);
        if (!BREAKPOINTS.has(name)) throw new ViewModelError(`${where}.breakpoints name must be sm, md, or lg`);
        return { name: name as Breakpoint["name"], minWidth: requiredNumber(bp, "minWidth", `${where}.breakpoints[${index}].minWidth`) };
      });
    }
  }
  if (layout === "tab") {
    if (record.currentTab !== undefined) {
      const current = requiredNumber(record, "currentTab", `${where}.currentTab`);
      if (!Number.isInteger(current) || current < 0) throw new ViewModelError(`${where}.currentTab must be an integer >= 0`);
      props.currentTab = current;
    }
    if (record.tabs !== undefined) {
      if (!Array.isArray(record.tabs)) throw new ViewModelError(`${where}.tabs must be an array`);
      props.tabs = record.tabs.map((entry, index) => {
        const tab = asRecord(entry, `${where}.tabs[${index}]`);
        return { label: requiredString(tab, "label", `${where}.tabs[${index}].label`) };
      });
    }
  }
  if (layout === "breakpoint") {
    props.breakpoint = record.breakpoint === undefined ? 640 : requiredNumber(record, "breakpoint", `${where}.breakpoint`);
    if (props.breakpoint < 0) throw new ViewModelError(`${where}.breakpoint must be >= 0`);
    props.determinant = (record.determinant as ContainerProps["determinant"]) ?? "width";
    assertEnum(props.determinant, ["width", "height"], `${where}.determinant`);
  }
  if (layout === "split") {
    props.orientation = (record.orientation as ContainerProps["orientation"]) ?? "horizontal";
    assertEnum(props.orientation, ["horizontal", "vertical"], `${where}.orientation`);
    props.split = parseSplitBar(record.split, `${where}.split`);
  }
  if (record.width !== undefined) props.width = requiredNumber(record, "width", `${where}.width`);
  if (record.height !== undefined) props.height = requiredNumber(record, "height", `${where}.height`);
  return props;
}

function parseSplitBar(input: unknown, where: string): SplitBar {
  const record = asRecord(input ?? {}, where);
  const position = record.position ?? "50%";
  if (typeof position === "number") {
    if (!Number.isFinite(position) || position < 0) throw new ViewModelError(`${where}.position must be >= 0`);
  } else if (typeof position !== "string" || !/^\s*\d+(?:\.\d+)?\s*(%|px)?\s*$/.test(position)) {
    throw new ViewModelError(`${where}.position must be pixels or a percentage such as "40%"`);
  }
  const nonNegative = (key: string, fallback: number) => {
    if (record[key] === undefined) return fallback;
    const value = requiredNumber(record, key, `${where}.${key}`);
    if (value < 0) throw new ViewModelError(`${where}.${key} must be >= 0`);
    return value;
  };
  return {
    position: position as number | string,
    size: nonNegative("size", 6),
    visible: record.visible === undefined ? true : requiredBoolean(record, "visible", `${where}.visible`),
    draggable: record.draggable === undefined ? true : requiredBoolean(record, "draggable", `${where}.draggable`),
    minFirst: nonNegative("minFirst", 0),
    minSecond: nonNegative("minSecond", 0),
  };
}

interface Breakpoint {
  name: "sm" | "md" | "lg";
  minWidth: number;
}

function parseStyle(input: unknown, where: string) {
  try {
    return parseStyleObject(asRecord(input, where), where);
  } catch (error) {
    if (error instanceof StyleError) throw new ViewModelError(error.message);
    throw error;
  }
}

function parseStyleClasses(input: unknown, where: string): string[] {
  if (!Array.isArray(input)) throw new ViewModelError(`${where} must be an array`);
  return input.map((entry, index) => {
    if (typeof entry !== "string" || entry.length === 0) {
      throw new ViewModelError(`${where}[${index}] must be a non-empty string`);
    }
    return entry;
  });
}

function parseBindings(input: unknown, where: string): Record<string, Binding> {
  const record = asRecord(input, where);
  const bindings: Record<string, Binding> = {};
  for (const [prop, value] of Object.entries(record)) bindings[prop] = parseBinding(value, `${where}.${prop}`);
  return bindings;
}

/** Validate one binding the way a view load does. Throws ViewModelError. The Designer uses it before committing an edit. */
export function parseBinding(input: unknown, where: string): Binding {
  const binding = asRecord(input, where);
  const kind = requiredString(binding, "kind", `${where}.kind`);
  if (kind === "tag") return parseTagBinding(binding, where);
  if (kind === "property") return parsePropertyBinding(binding, where);
  if (kind === "expression") return parseExpressionBinding(binding, where);
  if (kind === "expression-structure") return parseExpressionStructureBinding(binding, where);
  if (kind === "query") return parseQueryBinding(binding, where);
  if (kind === "history") return parseHistoryBinding(binding, where);
  if (kind === "http") return parseHttpBinding(binding, where);
  throw new ViewModelError(`${where}.kind is unknown: ${kind}`);
}

function parseTagBinding(binding: Record<string, unknown>, where: string): TagBinding {
  const mode = requiredString(binding, "mode", `${where}.mode`);
  if (mode !== "direct" && mode !== "indirect") throw new ViewModelError(`${where}.mode must be direct or indirect`);
  const parsed: TagBinding = { kind: "tag", mode, path: requiredString(binding, "path", `${where}.path`) };
  if (binding.bidirectional !== undefined) parsed.bidirectional = requiredBoolean(binding, "bidirectional", `${where}.bidirectional`);
  if (binding.enabled !== undefined) parsed.enabled = requiredBoolean(binding, "enabled", `${where}.enabled`);
  if (binding.overlayOptOut !== undefined) parsed.overlayOptOut = requiredBoolean(binding, "overlayOptOut", `${where}.overlayOptOut`);
  if (binding.publishInitialUncertain !== undefined) {
    parsed.publishInitialUncertain = requiredBoolean(binding, "publishInitialUncertain", `${where}.publishInitialUncertain`);
  }
  if (binding.references !== undefined) parsed.references = parseReferences(binding.references, `${where}.references`);
  if (binding.transforms !== undefined) parsed.transforms = parseTransforms(binding.transforms, `${where}.transforms`);
  return parsed;
}

function parsePropertyBinding(binding: Record<string, unknown>, where: string): Binding {
  const source = asRecord(binding.source, `${where}.source`);
  const scope = requiredString(source, "scope", `${where}.source.scope`);
  let parsedSource: Extract<Binding, { kind: "property" }>["source"];
  if (scope === "prop") {
    parsedSource = {
      scope: "prop",
      nodeId: requiredString(source, "nodeId", `${where}.source.nodeId`),
      prop: requiredString(source, "prop", `${where}.source.prop`),
    };
  } else if (scope === "param" || scope === "custom") {
    parsedSource = { scope, name: requiredString(source, "name", `${where}.source.name`) };
  } else if (scope === "session" || scope === "page") {
    parsedSource = { scope, path: requiredString(source, "path", `${where}.source.path`) };
  } else {
    throw new ViewModelError(`${where}.source.scope is unknown: ${scope}`);
  }
  const parsed: Binding = { kind: "property", source: parsedSource };
  if (binding.bidirectional !== undefined) parsed.bidirectional = requiredBoolean(binding, "bidirectional", `${where}.bidirectional`);
  if (binding.enabled !== undefined) parsed.enabled = requiredBoolean(binding, "enabled", `${where}.enabled`);
  if (binding.overlayOptOut !== undefined) parsed.overlayOptOut = requiredBoolean(binding, "overlayOptOut", `${where}.overlayOptOut`);
  if (binding.transforms !== undefined) parsed.transforms = parseTransforms(binding.transforms, `${where}.transforms`);
  return parsed;
}

function parseExpressionBinding(binding: Record<string, unknown>, where: string): Binding {
  if (binding.bidirectional === true) throw new ViewModelError(`${where} expression bindings are read-only`);
  const parsed: Extract<Binding, { kind: "expression" }> = { kind: "expression", expression: requiredString(binding, "expression", `${where}.expression`) };
  if (binding.pollMs !== undefined) {
    const poll = requiredNumber(binding, "pollMs", `${where}.pollMs`);
    if (!Number.isInteger(poll) || poll < 0) throw new ViewModelError(`${where}.pollMs must be a whole number of milliseconds >= 0`);
    if (poll > 0 && poll < MIN_EXPRESSION_POLL_MS) throw new ViewModelError(`${where}.pollMs must be 0 (no poll) or at least ${MIN_EXPRESSION_POLL_MS}`);
    if (poll > 0) parsed.pollMs = poll;
  }
  if (binding.enabled !== undefined) parsed.enabled = requiredBoolean(binding, "enabled", `${where}.enabled`);
  if (binding.overlayOptOut !== undefined) parsed.overlayOptOut = requiredBoolean(binding, "overlayOptOut", `${where}.overlayOptOut`);
  if (binding.transforms !== undefined) parsed.transforms = parseTransforms(binding.transforms, `${where}.transforms`);
  return parsed;
}

const STRUCTURE_KEY = /^[A-Za-z0-9_-]{1,64}$/;

function parseExpressionStructureBinding(binding: Record<string, unknown>, where: string): Binding {
  if (binding.bidirectional === true) throw new ViewModelError(`${where} expression structure bindings are read-only`);
  const raw = asRecord(binding.expressions, `${where}.expressions`);
  const expressions: Record<string, string> = {};
  for (const [key, expression] of Object.entries(raw)) {
    if (!STRUCTURE_KEY.test(key)) throw new ViewModelError(`${where}.expressions key "${key}" must be 1 to 64 letters, digits, _ or -`);
    if (typeof expression !== "string") throw new ViewModelError(`${where}.expressions.${key} must be a string`);
    expressions[key] = expression;
  }
  const parsed: Extract<Binding, { kind: "expression-structure" }> = { kind: "expression-structure", expressions };
  if (binding.pollMs !== undefined) {
    const poll = requiredNumber(binding, "pollMs", `${where}.pollMs`);
    if (!Number.isInteger(poll) || poll < 0) throw new ViewModelError(`${where}.pollMs must be a whole number of milliseconds >= 0`);
    if (poll > 0 && poll < MIN_EXPRESSION_POLL_MS) throw new ViewModelError(`${where}.pollMs must be 0 (no poll) or at least ${MIN_EXPRESSION_POLL_MS}`);
    if (poll > 0) parsed.pollMs = poll;
  }
  if (binding.enabled !== undefined) parsed.enabled = requiredBoolean(binding, "enabled", `${where}.enabled`);
  if (binding.overlayOptOut !== undefined) parsed.overlayOptOut = requiredBoolean(binding, "overlayOptOut", `${where}.overlayOptOut`);
  if (binding.transforms !== undefined) parsed.transforms = parseTransforms(binding.transforms, `${where}.transforms`);
  return parsed;
}

function parseQueryBinding(binding: Record<string, unknown>, where: string): Binding {
  if (binding.bidirectional === true) throw new ViewModelError(`${where} query bindings are read-only`);
  const parsed: Extract<Binding, { kind: "query" }> = { kind: "query", path: requiredString(binding, "path", `${where}.path`) };
  if (binding.returnFormat !== undefined) {
    const format = requiredString(binding, "returnFormat", `${where}.returnFormat`);
    if (format !== "auto" && format !== "json" && format !== "dataset" && format !== "scalar") {
      throw new ViewModelError(`${where}.returnFormat must be auto, json, dataset, or scalar`);
    }
    parsed.returnFormat = format;
  }
  if (binding.pollMs !== undefined) {
    const poll = requiredNumber(binding, "pollMs", `${where}.pollMs`);
    if (poll < 0) throw new ViewModelError(`${where}.pollMs must be >= 0`);
    parsed.pollMs = poll;
  }
  if (binding.enabled !== undefined) parsed.enabled = requiredBoolean(binding, "enabled", `${where}.enabled`);
  if (binding.overlayOptOut !== undefined) parsed.overlayOptOut = requiredBoolean(binding, "overlayOptOut", `${where}.overlayOptOut`);
  if (binding.parameters !== undefined) {
    const parameters = asRecord(binding.parameters, `${where}.parameters`);
    parsed.parameters = {};
    for (const [name, value] of Object.entries(parameters)) {
      parsed.parameters[name] = parseQueryParameter(value, `${where}.parameters.${name}`);
    }
  }
  if (binding.transforms !== undefined) parsed.transforms = parseTransforms(binding.transforms, `${where}.transforms`);
  return parsed;
}

const HTTP_SOURCE_NAME = /^[a-z0-9-]{1,63}$/;

function parseHttpBinding(binding: Record<string, unknown>, where: string): Binding {
  if (binding.bidirectional === true) throw new ViewModelError(`${where} HTTP bindings are read-only`);
  const source = requiredString(binding, "source", `${where}.source`);
  if (!HTTP_SOURCE_NAME.test(source)) throw new ViewModelError(`${where}.source must be lowercase letters, digits and -`);
  const path = requiredString(binding, "path", `${where}.path`);
  if (!isRelativeHttpPath(path)) throw new ViewModelError(`${where}.path must be one relative path starting with /`);
  const parsed: Extract<Binding, { kind: "http" }> = { kind: "http", source, path };
  if (binding.query !== undefined) {
    const query = asRecord(binding.query, `${where}.query`);
    parsed.query = {};
    for (const [name, value] of Object.entries(query)) {
      if (typeof value !== "string" && typeof value !== "number" && typeof value !== "boolean") {
        throw new ViewModelError(`${where}.query.${name} must be text, a number or a boolean`);
      }
      parsed.query[name] = value;
    }
  }
  if (binding.jsonPath !== undefined) parsed.jsonPath = requiredString(binding, "jsonPath", `${where}.jsonPath`);
  if (binding.pollMs !== undefined) {
    const poll = requiredNumber(binding, "pollMs", `${where}.pollMs`);
    if (poll !== 0 && poll < MIN_HTTP_POLL_MS) throw new ViewModelError(`${where}.pollMs must be 0 or at least ${MIN_HTTP_POLL_MS}`);
    parsed.pollMs = poll;
  }
  if (binding.enabled !== undefined) parsed.enabled = requiredBoolean(binding, "enabled", `${where}.enabled`);
  if (binding.overlayOptOut !== undefined) parsed.overlayOptOut = requiredBoolean(binding, "overlayOptOut", `${where}.overlayOptOut`);
  if (binding.transforms !== undefined) parsed.transforms = parseTransforms(binding.transforms, `${where}.transforms`);
  return parsed;
}

function isRelativeHttpPath(path: string): boolean {
  if (path.length > 500 || !path.startsWith("/") || path.startsWith("//")) return false;
  if (/[\s\\#?@]/.test(path)) return false;
  return !path.split("/").some((part) => part === ".." || part === "." || /%2[ef]/i.test(part));
}

function parseHistoryBinding(binding: Record<string, unknown>, where: string): HistoryBinding {
  if (binding.bidirectional === true) throw new ViewModelError(`${where} history bindings are read-only`);
  const rangeMs = wholeMillis(binding, "rangeMs", where);
  const intervalMs = wholeMillis(binding, "intervalMs", where);
  if (rangeMs <= 0) throw new ViewModelError(`${where}.rangeMs must be greater than 0`);
  if (intervalMs <= 0) throw new ViewModelError(`${where}.intervalMs must be greater than 0`);
  // The route's window count is `(range / interval) + 1` because the end instant is inclusive.
  const windows = Math.floor(rangeMs / intervalMs) + 1;
  if (windows > MAX_HISTORY_WINDOWS) {
    throw new ViewModelError(`${where} asks for ${windows} windows; at most ${MAX_HISTORY_WINDOWS} are allowed`);
  }
  const parsed: HistoryBinding = { kind: "history", path: requiredString(binding, "path", `${where}.path`), rangeMs, intervalMs };
  if (binding.aggregate !== undefined) {
    const aggregate = requiredString(binding, "aggregate", `${where}.aggregate`);
    if (!(HISTORY_AGGREGATES as readonly string[]).includes(aggregate)) {
      throw new ViewModelError(`${where}.aggregate must be one of ${HISTORY_AGGREGATES.join(", ")}`);
    }
    parsed.aggregate = aggregate as HistoryAggregateName;
  }
  if (binding.pollMs !== undefined) {
    const poll = requiredNumber(binding, "pollMs", `${where}.pollMs`);
    if (poll < 0) throw new ViewModelError(`${where}.pollMs must be >= 0`);
    parsed.pollMs = poll;
  }
  if (binding.enabled !== undefined) parsed.enabled = requiredBoolean(binding, "enabled", `${where}.enabled`);
  if (binding.overlayOptOut !== undefined) parsed.overlayOptOut = requiredBoolean(binding, "overlayOptOut", `${where}.overlayOptOut`);
  if (binding.transforms !== undefined) parsed.transforms = parseTransforms(binding.transforms, `${where}.transforms`);
  return parsed;
}

function wholeMillis(binding: Record<string, unknown>, key: "rangeMs" | "intervalMs", where: string): number {
  const value = requiredNumber(binding, key, `${where}.${key}`);
  if (!Number.isInteger(value)) throw new ViewModelError(`${where}.${key} must be a whole number of milliseconds`);
  return value;
}

function parseQueryParameter(input: unknown, where: string): QueryParameter {
  const record = asRecord(input, where);
  if ("expression" in record) return { expression: requiredString(record, "expression", `${where}.expression`) };
  if ("value" in record) return { value: record.value };
  throw new ViewModelError(`${where} needs value or expression`);
}

function parseReferences(input: unknown, where: string): NonNullable<TagBinding["references"]> {
  const references = asRecord(input, where);
  const parsed: NonNullable<TagBinding["references"]> = {};
  for (const [name, ref] of Object.entries(references)) {
    const source = asRecord(ref, `${where}.${name}`);
    if (source.source === "param" || source.source === "custom") {
      parsed[name] = { source: source.source, name: requiredString(source, "name", `${where}.${name}.name`) };
    } else if (source.source === "prop") {
      parsed[name] = {
        source: "prop",
        nodeId: requiredString(source, "nodeId", `${where}.${name}.nodeId`),
        prop: requiredString(source, "prop", `${where}.${name}.prop`),
      };
    } else {
      throw new ViewModelError(`${where}.${name}.source must be param, prop, or custom`);
    }
  }
  return parsed;
}

function parseTransforms(input: unknown, where: string): Transform[] {
  if (!Array.isArray(input)) throw new ViewModelError(`${where} must be an array`);
  return input.map((entry, index) => parseTransform(entry, `${where}[${index}]`));
}

function parseTransform(input: unknown, where: string): Transform {
  const record = asRecord(input, where);
  const kind = requiredString(record, "kind", `${where}.kind`);
  if (kind === "map") return parseMap(record, where);
  if (kind === "format") return parseFormat(record, where);
  if (kind === "expression") return { kind: "expression", expression: requiredString(record, "expression", `${where}.expression`) };
  if (kind === "script") {
    const script: ScriptTransform = { kind: "script", hook: requiredString(record, "hook", `${where}.hook`) };
    if (record.source !== undefined) {
      if (typeof record.source !== "string") throw new ViewModelError(`${where}.source must be a string`);
      script.source = record.source;
    }
    return script;
  }
  throw new ViewModelError(`${where}.kind is unknown: ${kind}`);
}

function parseMap(record: Record<string, unknown>, where: string): Transform {
  if (!Array.isArray(record.entries)) throw new ViewModelError(`${where}.entries must be an array`);
  const entries: MapEntry[] = record.entries.map((entry, index) => {
    const row = asRecord(entry, `${where}.entries[${index}]`);
    return { input: parseMapInput(row.input, `${where}.entries[${index}].input`), output: parseMapOutput(row.output, `${where}.entries[${index}].output`) };
  });
  const transform: Extract<Transform, { kind: "map" }> = { kind: "map", entries };
  if ("fallback" in record) {
    transform.fallback = record.fallback;
    transform.hasFallback = true;
  }
  return transform;
}

function parseMapInput(input: unknown, where: string): MapEntry["input"] {
  if (input === null || typeof input === "string" || typeof input === "number" || typeof input === "boolean") {
    return { type: "value", value: input };
  }
  const record = asRecord(input, where);
  if ("range" in record) return { type: "range", ...parseRange(requiredString(record, "range", `${where}.range`)) };
  // The in-memory row shape (`MapInput`), as the Designer writes it into a saved view. A row that also carries
  // `value` or `expression` keeps its older meaning below.
  if (record.type === "range" && !("value" in record) && !("expression" in record)) return parseRangeRow(record, where);
  if ("expression" in record) return { type: "expression", expression: requiredString(record, "expression", `${where}.expression`) };
  if ("value" in record) return { type: "value", value: record.value };
  throw new ViewModelError(`${where} needs a value, range, or expression`);
}

function parseRangeRow(record: Record<string, unknown>, where: string): MapEntry["input"] {
  const bound = (key: "min" | "max"): number | null => {
    const value = record[key];
    if (value === null || value === undefined) return null;
    if (typeof value !== "number" || !Number.isFinite(value)) throw new ViewModelError(`${where}.${key} must be a number or null`);
    return value;
  };
  const min = bound("min");
  const max = bound("max");
  if (min === null && max === null) throw new ViewModelError(`${where} range needs a min or a max`);
  return {
    type: "range",
    min,
    max,
    minInclusive: record.minInclusive === true,
    maxInclusive: record.maxInclusive === true,
  };
}

function parseMapOutput(input: unknown, where: string): MapEntry["output"] {
  if (input === null || typeof input === "string" || typeof input === "number" || typeof input === "boolean") {
    return { type: "value", value: input };
  }
  const record = asRecord(input, where);
  if ("expression" in record) return { type: "expression", expression: requiredString(record, "expression", `${where}.expression`) };
  if ("value" in record) return { type: "value", value: record.value };
  throw new ViewModelError(`${where} needs a value or expression`);
}

function parseFormat(record: Record<string, unknown>, where: string): FormatTransform {
  const type = requiredString(record, "type", `${where}.type`);
  if (type !== "numeric" && type !== "datetime") throw new ViewModelError(`${where}.type must be numeric or datetime`);
  const mode = requiredString(record, "mode", `${where}.mode`);
  const allowed = type === "numeric" ? ["pattern", "integer", "number", "percent", "currency"] : ["pattern", "date", "time", "datetime"];
  if (!allowed.includes(mode)) throw new ViewModelError(`${where}.mode must be one of ${allowed.join(", ")}`);
  const transform: FormatTransform = { kind: "format", type, mode: mode as FormatTransform["mode"] };
  if (record.pattern !== undefined) transform.pattern = requiredString(record, "pattern", `${where}.pattern`);
  if (record.style !== undefined) {
    const style = requiredString(record, "style", `${where}.style`);
    if (style !== "full" && style !== "long" && style !== "medium" && style !== "short") {
      throw new ViewModelError(`${where}.style must be full, long, medium, or short`);
    }
    transform.style = style;
  }
  if (record.timeZone !== undefined) transform.timeZone = requiredString(record, "timeZone", `${where}.timeZone`);
  if (record.currency !== undefined) transform.currency = requiredString(record, "currency", `${where}.currency`);
  if (mode === "pattern" && !transform.pattern) throw new ViewModelError(`${where}.pattern is required`);
  return transform;
}

function parseEvents(input: unknown, where: string): ComponentEvent[] {
  if (!Array.isArray(input)) throw new ViewModelError(`${where} must be an array`);
  return input.map((entry, index) => {
    const record = asRecord(entry, `${where}[${index}]`);
    if (!Array.isArray(record.actions)) throw new ViewModelError(`${where}[${index}].actions must be an array`);
    return {
      event: requiredString(record, "event", `${where}[${index}].event`),
      actions: record.actions.map((action, actionIndex) => parseAction(action, `${where}[${index}].actions[${actionIndex}]`)),
    };
  });
}

function parseAction(input: unknown, where: string): Action {
  const record = asRecord(input, where);
  const kind = requiredString(record, "kind", `${where}.kind`);
  const enabled = record.enabled === undefined ? undefined : requiredBoolean(record, "enabled", `${where}.enabled`);
  const stopPropagation = record.stopPropagation === undefined ? undefined : requiredBoolean(record, "stopPropagation", `${where}.stopPropagation`);
  if (kind === "script") {
    const action: Action = { kind, hook: requiredString(record, "hook", `${where}.hook`), enabled, stopPropagation };
    if (record.source !== undefined) {
      if (typeof record.source !== "string") throw new ViewModelError(`${where}.source must be a string`);
      action.source = record.source;
    }
    return action;
  }
  if (kind === "tag-write") {
    const value = record.value;
    if (typeof value !== "string" && typeof value !== "number" && typeof value !== "boolean" && value !== null) {
      throw new ViewModelError(`${where}.value must be a string, number, boolean, or null`);
    }
    return { kind, path: requiredString(record, "path", `${where}.path`), value, enabled, stopPropagation };
  }
  if (kind === "navigation") {
    const mode = requiredString(record, "mode", `${where}.mode`);
    if (mode !== "page" && mode !== "view" && mode !== "url" && mode !== "browser") {
      throw new ViewModelError(`${where}.mode must be page, view, url, or browser`);
    }
    const action: Action = { kind, mode, enabled, stopPropagation };
    if (record.page !== undefined) action.page = requiredString(record, "page", `${where}.page`);
    if (record.view !== undefined) action.view = requiredString(record, "view", `${where}.view`);
    if (record.url !== undefined) action.url = requiredString(record, "url", `${where}.url`);
    if (record.newTab !== undefined) action.newTab = requiredBoolean(record, "newTab", `${where}.newTab`);
    if (record.direction !== undefined) {
      const direction = requiredString(record, "direction", `${where}.direction`);
      if (direction !== "back" && direction !== "forward") throw new ViewModelError(`${where}.direction must be back or forward`);
      action.direction = direction;
    }
    if (record.params !== undefined) action.params = { ...asRecord(record.params, `${where}.params`) };
    if (mode === "page" && !action.page) throw new ViewModelError(`${where}.page is required`);
    if (mode === "view" && !action.view) throw new ViewModelError(`${where}.view is required`);
    if (mode === "url" && !action.url) throw new ViewModelError(`${where}.url is required`);
    if (mode === "url" && linkTarget(action.url!).kind === "blocked") {
      throw new ViewModelError(`${where}.url must be a page path (/...) or an http(s) URL`);
    }
    return action;
  }
  if (kind === "popup") {
    const mode = requiredString(record, "mode", `${where}.mode`);
    if (mode !== "open" && mode !== "close" && mode !== "toggle") throw new ViewModelError(`${where}.mode must be open, close, or toggle`);
    const action: Action = { kind, mode, id: requiredString(record, "id", `${where}.id`), enabled, stopPropagation };
    if (record.view !== undefined) action.view = requiredString(record, "view", `${where}.view`);
    if ((mode === "open" || mode === "toggle") && !action.view) throw new ViewModelError(`${where}.view is required`);
    if (record.params !== undefined) action.params = { ...asRecord(record.params, `${where}.params`) };
    if (record.title !== undefined) action.title = requiredString(record, "title", `${where}.title`);
    if (record.showClose !== undefined) action.showClose = requiredBoolean(record, "showClose", `${where}.showClose`);
    if (record.draggable !== undefined) action.draggable = requiredBoolean(record, "draggable", `${where}.draggable`);
    if (record.resizable !== undefined) action.resizable = requiredBoolean(record, "resizable", `${where}.resizable`);
    if (record.modal !== undefined) action.modal = requiredBoolean(record, "modal", `${where}.modal`);
    if (record.backgroundDismissible !== undefined) {
      action.backgroundDismissible = requiredBoolean(record, "backgroundDismissible", `${where}.backgroundDismissible`);
    }
    if (record.viewportBound !== undefined) action.viewportBound = requiredBoolean(record, "viewportBound", `${where}.viewportBound`);
    if (record.position !== undefined) {
      const position = asRecord(record.position, `${where}.position`);
      action.position = {};
      for (const key of ["top", "left", "width", "height"] as const) {
        if (position[key] !== undefined) action.position[key] = requiredNumber(position, key, `${where}.position.${key}`);
      }
    }
    return action;
  }
  if (kind === "message") {
    const action: Action = {
      kind,
      messageType: parseMessageType(record, `${where}.messageType`),
      scope: parseMessageScope(record.scope ?? "page", `${where}.scope`),
      enabled,
      stopPropagation,
    };
    if (record.payload !== undefined) action.payload = { ...asRecord(record.payload, `${where}.payload`) };
    return action;
  }
  if (kind === "logout") return { kind, enabled, stopPropagation };
  if (kind === "call-method") {
    const method = requiredString(record, "method", `${where}.method`);
    if (!METHOD_NAME.test(method)) throw new ViewModelError(`${where}.method must start with a letter and use letters, digits and '_'`);
    const action: Action = { kind, method, enabled, stopPropagation };
    if (record.target !== undefined) action.target = requiredString(record, "target", `${where}.target`);
    if (record.args !== undefined) action.args = { ...asRecord(record.args, `${where}.args`) };
    return action;
  }
  throw new ViewModelError(`${where}.kind is unknown: ${kind}`);
}

/** Custom methods (HMV-114): a map of method name to its declared parameters and actions. */
function parseMethods(input: unknown, where: string): MethodMap {
  const record = asRecord(input, where);
  const methods: MethodMap = {};
  for (const [name, entry] of Object.entries(record)) {
    const at = `${where}.${name}`;
    if (!METHOD_NAME.test(name)) throw new ViewModelError(`${at}: a method name must start with a letter and use letters, digits and '_'`);
    const def = asRecord(entry, at);
    if (!Array.isArray(def.actions)) throw new ViewModelError(`${at}.actions must be an array`);
    const method: MethodDef = { actions: def.actions.map((action, index) => parseAction(action, `${at}.actions[${index}]`)) };
    if (def.title !== undefined) method.title = requiredString(def, "title", `${at}.title`);
    if (def.params !== undefined) {
      if (!Array.isArray(def.params)) throw new ViewModelError(`${at}.params must be an array`);
      const seen = new Set<string>();
      method.params = def.params.map((param, index) => {
        const paramRecord = asRecord(param, `${at}.params[${index}]`);
        const paramName = requiredString(paramRecord, "name", `${at}.params[${index}].name`);
        if (!METHOD_PARAM_NAME.test(paramName)) throw new ViewModelError(`${at}.params[${index}].name must use letters, digits and '_'`);
        if (seen.has(paramName)) throw new ViewModelError(`${at}.params[${index}].name is declared twice: ${paramName}`);
        seen.add(paramName);
        return paramRecord.default === undefined ? { name: paramName } : { name: paramName, default: paramRecord.default };
      });
    }
    methods[name] = method;
  }
  return methods;
}

const MESSAGE_TYPE = /^[A-Za-z][A-Za-z0-9_.-]{0,99}$/;
export const MESSAGE_SCOPES = ["view", "page", "session"] as const;

function parseMessageType(record: Record<string, unknown>, where: string): string {
  const type = requiredString(record, "messageType", where);
  if (!MESSAGE_TYPE.test(type)) throw new ViewModelError(`${where} must start with a letter and use letters, digits, '_', '-' and '.'`);
  return type;
}

function parseMessageScope(input: unknown, where: string): MessageScope {
  if (typeof input !== "string" || !(MESSAGE_SCOPES as readonly string[]).includes(input)) throw new ViewModelError(`${where} must be view, page, or session`);
  return input as MessageScope;
}

function parseMessageHandlers(input: unknown, where: string): MessageHandler[] {
  if (!Array.isArray(input)) throw new ViewModelError(`${where} must be an array`);
  return input.map((entry, index) => {
    const at = `${where}[${index}]`;
    const record = asRecord(entry, at);
    const messageType = parseMessageType(record, `${at}.messageType`);
    if (!Array.isArray(record.scopes) || record.scopes.length === 0) throw new ViewModelError(`${at}.scopes must list view, page or session`);
    const scopes = [...new Set(record.scopes.map((scope, i) => parseMessageScope(scope, `${at}.scopes[${i}]`)))];
    const script = parseAction(record.script, `${at}.script`);
    if (script.kind !== "script") throw new ViewModelError(`${at}.script must be a script action`);
    return { messageType, scopes, script };
  });
}

function parseId(record: Record<string, unknown>, where: string, ids: Set<string>): string {
  const id = requiredString(record, "id", `${where}.id`);
  if (ids.has(id)) throw new ViewModelError(`duplicate id: ${id}`);
  ids.add(id);
  return id;
}

function asRecord(input: unknown, where: string): Record<string, unknown> {
  if (input === null || typeof input !== "object" || Array.isArray(input)) {
    throw new ViewModelError(`${where} must be an object`);
  }
  return input as Record<string, unknown>;
}

function requiredString(record: Record<string, unknown>, key: string, where: string): string {
  const value = record[key];
  if (typeof value !== "string" || value.length === 0) throw new ViewModelError(`${where} must be a non-empty string`);
  return value;
}

function requiredNumber(record: Record<string, unknown>, key: string, where: string): number {
  const value = record[key];
  if (typeof value !== "number" || !Number.isFinite(value)) throw new ViewModelError(`${where} must be a finite number`);
  return value;
}

function requiredBoolean(record: Record<string, unknown>, key: string, where: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") throw new ViewModelError(`${where} must be a boolean`);
  return value;
}

function nonNegativeInt(record: Record<string, unknown>, key: string, where: string): number {
  const value = requiredNumber(record, key, where);
  if (!Number.isInteger(value) || value < 0) throw new ViewModelError(`${where} must be an integer >= 0`);
  return value;
}

function assertEnum(value: unknown, allowed: readonly string[], where: string): void {
  if (typeof value !== "string" || !allowed.includes(value)) {
    throw new ViewModelError(`${where} must be one of ${allowed.join(", ")}`);
  }
}
