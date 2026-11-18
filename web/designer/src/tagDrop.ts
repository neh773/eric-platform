import type { Binding, ComponentNode, DropRule, JsonSchema, TagBinding, ViewDocument } from "@oip/hmi-runtime";
import { findNode } from "@oip/hmi-runtime";
import { defaultsFromSchema } from "@oip/components";
import { appendComponent, placeAt, updateNode } from "./project";

/**
 * Drag a tag from the tag browser onto a component or onto one of its property rows in the Inspector: the
 * Designer writes a direct tag binding, the same `{ kind: "tag", mode: "direct", path }` binding the HMI runtime
 * already evaluates (hmi-runtime `TagBinding`).
 *
 * A drop on a component on the canvas binds the component type's default property ([DEFAULT_TAG_PROP]). A drop
 * on a property row binds that property ([tagBindingForProp]).
 */

export const TAG_DRAG_TYPE = "application/x-oip-tag";

export interface DraggedTag {
  path: string;
  dataType?: string;
}

const NUMERIC = new Set(["int32", "int64", "float", "double"]);

export function encodeDraggedTag(tag: DraggedTag): string {
  return JSON.stringify(tag);
}

export function decodeDraggedTag(raw: string): DraggedTag | null {
  try {
    const value = JSON.parse(raw) as Partial<DraggedTag>;
    if (typeof value.path !== "string" || !value.path.trim()) return null;
    return { path: value.path, dataType: typeof value.dataType === "string" ? value.dataType : undefined };
  } catch {
    return null;
  }
}

/**
 * The property a tag dropped on a component of each type binds. Every registered component type is listed
 * (a test checks this), so a new component has to choose. `null` means the type has no single live value
 * (tables, menus, view hosts): the drop selects the component and the tag is dropped on a property row instead.
 */
export const DEFAULT_TAG_PROP: Readonly<Record<string, string | null>> = {
  label: "text",
  numeric: "value",
  button: "text",
  toggle: "value",
  "text-input": "value",
  indicator: "value",
  "linear-gauge": "value",
  trend: "value",
  table: null,
  dropdown: "value",
  slider: "value",
  checkbox: "value",
  "alarm-status": null,
  image: "source",
  "alarm-status-table": null,
  "time-series-chart": "value",
  "power-chart": null,
  "embedded-view": null,
  "simple-gauge": "value",
  "xy-chart": null,
  "radial-gauge": "value",
  "tag-browse": null,
  "pie-chart": null,
  "range-selector": null,
  "led": "value",
  "linear-scale": "value",
  "thermometer": "value",
  markdown: "text",
  "view-canvas": null,
  "progress-bar": "value",
  icon: "name",
  sparkline: "points",
  "datetime-input": "value",
  "datetime-picker": "value",
  "password-field": "value",
  "radio-group": "value",
  "text-area": "value",
  "one-shot-button": "value",
  "file-upload": null,
  "multi-state-button": "value",
  link: "text",
  "alarm-journal-table": null,
  "flex-repeater": null,
  carousel: null,
  dashboard: null,
  accordion: null,
  pump: "state",
  valve: "state",
  motor: "state",
  vessel: "level",
  sensor: "value",
  "cylindrical-tank": "level",
  "horizontal-menu": null,
  "menu-tree": null,
  tree: null,
  "numeric-entry": "value",
  "momentary-button": "value",
  "state-toggle": "value",
  "formatted-text-field": "value",
  spinner: "value",
  timer: null,
  "barcode-scanner-input": "value",
  drawing: null,
  "svg-component": null,
  "template-canvas": null,
  pipe: "flow",
  "pdf-viewer": "source",
  audio: "playing",
  "video-player": "playing",
  "inline-frame": "source",
  map: null,
  "leaflet-map": null,
  "analog-indicator": "value",
  form: null,
  "navigation-drawer": null,
};

/**
 * The property a tag dropped on a component of [type] binds, or null. A type missing from [DEFAULT_TAG_PROP]
 * (a component added at run time) falls back to `value`, else `text`, else no property.
 */
export function dropProperty(schema: JsonSchema | undefined, type?: string): string | null {
  const props = Object.keys(schema?.properties ?? {});
  if (type !== undefined && Object.prototype.hasOwnProperty.call(DEFAULT_TAG_PROP, type)) {
    const prop = DEFAULT_TAG_PROP[type];
    return prop && (props.length === 0 || props.includes(prop)) ? prop : null;
  }
  if (props.includes("value")) return "value";
  if (props.includes("text")) return "text";
  return null;
}

/** The binding written for a dropped tag. A numeric tag on a text property gets a number format transform. */
export function tagBindingFor(tag: DraggedTag, propSchema: JsonSchema | undefined): TagBinding {
  const binding: TagBinding = { kind: "tag", mode: "direct", path: tag.path };
  if (propSchema?.type === "string" && tag.dataType && NUMERIC.has(tag.dataType)) {
    binding.transforms = [{ kind: "format", type: "numeric", mode: "number" }];
  }
  return binding;
}

/**
 * The binding written when [tag] is dropped on a property row. A property already bound keeps its transforms and
 * enabled flag (as switching the binding type does), and only the tag path changes. A new binding is
 * [tagBindingFor]'s.
 */
export function tagBindingForProp(tag: DraggedTag, propSchema: JsonSchema | undefined, previous: Binding | undefined): TagBinding {
  if (!previous) return tagBindingFor(tag, propSchema);
  if (previous.kind === "tag") {
    // An indirect binding's references belong to its template; a dropped tag is a direct path.
    const { references: _references, ...rest } = previous;
    return { ...rest, mode: "direct", path: tag.path };
  }
  const binding: TagBinding = { kind: "tag", mode: "direct", path: tag.path };
  if (previous.transforms) binding.transforms = previous.transforms;
  if (previous.enabled !== undefined) binding.enabled = previous.enabled;
  return binding;
}

export function bindDroppedTag(
  view: ViewDocument,
  nodeId: string,
  tag: DraggedTag,
  schemaFor: (type: string) => JsonSchema | undefined,
): { view: ViewDocument; prop: string } | null {
  const node = findNode(view.spec.root, nodeId);
  if (!node || node.kind !== "component") return null;
  const component = node as ComponentNode;
  const type = component.type;
  const schema = schemaFor(type);
  const prop = dropProperty(schema, type);
  if (!prop) return null;
  const binding = tagBindingFor(tag, schema?.properties?.[prop]);
  const defaults = schema ? defaultsFromSchema(schema) : {};
  const props = { ...defaults, ...component.props };
  return {
    view: updateNode(view, nodeId, {
      ...(Object.keys(defaults).some((key) => component.props[key] === undefined) ? { props } : {}),
      bindings: { ...(component.bindings ?? {}), [prop]: binding },
    }),
    prop,
  };
}

/**
 * The view's drop rule for [tag] (HMP-110): the first rule naming the tag's data type, else the first `*` rule.
 * Data types compare without case (`Double` and `double` are the same type). Null when no rule applies.
 */
export function dropRuleFor(rules: readonly DropRule[] | undefined, tag: DraggedTag): DropRule | null {
  if (!rules || rules.length === 0) return null;
  const type = tag.dataType?.toLowerCase();
  return (type ? rules.find((rule) => rule.dataType === type) : undefined) ?? rules.find((rule) => rule.dataType === "*") ?? null;
}

/**
 * A tag dropped on an empty part of the canvas (HMP-110): the view's drop configuration turns it into a component
 * with the rule's property bound to the tag (bidirectional when the rule says so), or into an embedded view whose
 * input param receives the tag path. The new node lands at [at] on a coordinate view. Null when no rule applies,
 * or the rule names a component the Studio does not know.
 */
export function applyDropConfig(
  view: ViewDocument,
  tag: DraggedTag,
  at: { x: number; y: number },
  moduleFor: (type: string) => { title: string; schema: JsonSchema; position?: { width: number; height: number } } | undefined,
  defaultsFor: (schema: JsonSchema) => Record<string, unknown>,
): { view: ViewDocument; id: string; rule: DropRule } | null {
  const rule = dropRuleFor(view.spec.dropConfig, tag);
  if (!rule) return null;
  if ("view" in rule) {
    const module = moduleFor("embedded-view");
    const defaults = module ? defaultsFor(module.schema) : {};
    const added = appendComponent(view, "embedded-view", module?.title ?? "Embedded view", {
      ...defaults,
      path: rule.view,
      params: { [rule.param]: tag.path },
    }, module?.position);
    return { view: placeAt(added.view, added.id, at), id: added.id, rule };
  }
  const module = moduleFor(rule.component);
  if (!module) return null;
  const added = appendComponent(view, rule.component, module.title, defaultsFor(module.schema), module.position);
  const binding = tagBindingFor(tag, module.schema.properties?.[rule.prop]);
  if (rule.bidirectional) binding.bidirectional = true;
  const bound = updateNode(added.view, added.id, { bindings: { [rule.prop]: binding } });
  return { view: placeAt(bound, added.id, at), id: added.id, rule };
}
