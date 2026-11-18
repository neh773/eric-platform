import { useEffect, useState } from "react";
import {
  CUSTOM_NAME,
  findNode,
  positionSchemaFor,
  type ComponentMeta,
  type ComponentNode,
  type ContainerNode,
  type HmiComponentModule,
  type JsonSchema,
  type ViewDocument,
  type ViewNode,
} from "@oip/hmi-runtime";
import { SchemaForm } from "./schemaForm";

/**
 * The Inspector's property categories (HMP-127, P4-10) beside props: position (from the parent container's layout,
 * HMP-115), custom (the designer's own typed values) and meta (name, visibility, tooltip). Custom values have one of
 * the JSON data types: string, number, boolean, object, array or null.
 *
 * Doc source: FEATURES HMP-127, HMP-115.
 */

export const CUSTOM_TYPES = ["string", "number", "boolean", "object", "array", "null"] as const;
export type CustomType = (typeof CUSTOM_TYPES)[number];

export function customTypeOf(value: unknown): CustomType {
  if (value === null || value === undefined) return "null";
  if (Array.isArray(value)) return "array";
  if (typeof value === "object") return "object";
  if (typeof value === "number") return "number";
  if (typeof value === "boolean") return "boolean";
  return "string";
}

/** [value] converted to [type]: kept where it fits, else the type's empty value. */
export function convertCustom(value: unknown, type: CustomType): unknown {
  switch (type) {
    case "string":
      return value === null || value === undefined ? "" : typeof value === "object" ? JSON.stringify(value) : String(value);
    case "number": {
      const number = typeof value === "number" ? value : Number(value);
      return Number.isFinite(number) ? number : 0;
    }
    case "boolean":
      return typeof value === "boolean" ? value : value === "true" || value === 1;
    case "object":
      return value && typeof value === "object" && !Array.isArray(value) ? value : {};
    case "array":
      return Array.isArray(value) ? value : [];
    case "null":
      return null;
  }
}

function mutate(view: ViewDocument, id: string, change: (node: ViewNode) => void): ViewDocument {
  const next = structuredClone(view);
  const node = findNode(next.spec.root, id);
  if (!node) throw new Error(`node ${id} not found`);
  change(node);
  return next;
}

/** The view with component [id]'s custom properties replaced (removed when empty). */
export function setNodeCustom(view: ViewDocument, id: string, custom: Record<string, unknown>): ViewDocument {
  return mutate(view, id, (node) => {
    if (node.kind !== "component") return;
    if (Object.keys(custom).length > 0) node.custom = custom;
    else delete node.custom;
  });
}

/** The view with component [id]'s meta replaced; defaults (visible, no tooltip) are not stored. */
export function setNodeMeta(view: ViewDocument, id: string, name: string, meta: ComponentMeta): ViewDocument {
  return mutate(view, id, (node) => {
    if (name.trim()) node.name = name;
    if (node.kind !== "component") return;
    const next: ComponentMeta = {};
    if (meta.visible === false) next.visible = false;
    if (meta.tooltip) next.tooltip = meta.tooltip;
    if (Object.keys(next).length > 0) node.meta = next;
    else delete node.meta;
  });
}

/** The view with component [id] on variant [name] ("" for none). */
export function setNodeVariant(view: ViewDocument, id: string, name: string): ViewDocument {
  return mutate(view, id, (node) => {
    if (node.kind !== "component") return;
    if (name) node.variant = name;
    else delete node.variant;
  });
}

/** The view with node [id]'s position fields from [record], for the keys its parent's layout uses. */
export function setNodePosition(view: ViewDocument, id: string, parent: ContainerNode | null, record: Record<string, unknown>): ViewDocument {
  const keys = Object.keys(positionSchemaFor(parent));
  return mutate(view, id, (node) => {
    for (const key of keys) {
      const value = record[key];
      if (value === undefined) continue;
      if (key === "frame" && isRecord(value)) {
        const frame = node.frame ?? { x: 0, y: 0, width: 80, height: 32 };
        node.frame = {
          x: numberOr(value.x, frame.x),
          y: numberOr(value.y, frame.y),
          width: numberOr(value.width, frame.width),
          height: numberOr(value.height, frame.height),
          ...(typeof value.rotate === "number" ? { rotate: value.rotate } : frame.rotate !== undefined ? { rotate: frame.rotate } : {}),
        };
      } else if (key === "flex" && isRecord(value)) {
        node.flex = { ...(node.flex ?? { grow: 0, shrink: 1, basis: "auto", display: true }), ...(value as Partial<NonNullable<ViewNode["flex"]>>) };
      } else if (key === "tabLabel" && typeof value === "string") {
        if (value) node.tabLabel = value;
        else delete node.tabLabel;
      } else if (key === "size" && (value === "small" || value === "large")) {
        node.size = value;
      }
    }
  });
}

export function PositionSection({ view, node, parent, onView }: { view: ViewDocument; node: ViewNode; parent: ContainerNode | null; onView: (view: ViewDocument) => void }) {
  const properties = positionSchemaFor(parent);
  if (Object.keys(properties).length === 0) return null;
  const schema: JsonSchema = { type: "object", title: "Position", properties };
  const value = { frame: node.frame, flex: node.flex, tabLabel: node.tabLabel, size: node.size };
  return (
    <div className="oip-category" data-category="position">
      <SchemaForm schema={schema} value={value} onChange={(next) => isRecord(next) && onView(setNodePosition(view, node.id, parent, next))} />
    </div>
  );
}

export function VariantPicker({ node, module, onChange }: { node: ComponentNode; module: HmiComponentModule | undefined; onChange: (name: string) => void }) {
  const variants = module?.variants ?? [];
  if (variants.length === 0) return null;
  return (
    <label className="oip-schema-field oip-variant">
      <span>Variant</span>
      <select value={node.variant ?? ""} onChange={(event) => onChange(event.target.value)}>
        <option value="">Default</option>
        {variants.map((variant) => (
          <option key={variant.name} value={variant.name}>
            {variant.title}
          </option>
        ))}
      </select>
    </label>
  );
}

export function MetaSection({ node, onChange }: { node: ComponentNode; onChange: (name: string, meta: ComponentMeta) => void }) {
  const meta = node.meta ?? {};
  const [name, setName] = useState(node.name);
  useEffect(() => setName(node.name), [node.name]);
  return (
    <fieldset className="oip-schema oip-category" data-category="meta">
      <legend>Meta</legend>
      <label className="oip-schema-field">
        <span>Name</span>
        <input
          type="text"
          value={name}
          onChange={(event) => {
            setName(event.target.value);
            if (event.target.value.trim()) onChange(event.target.value, meta);
          }}
        />
      </label>
      <label className="oip-schema-check">
        <input type="checkbox" checked={meta.visible !== false} onChange={(event) => onChange(node.name, { ...meta, visible: event.target.checked })} />
        <span>Visible</span>
      </label>
      <label className="oip-schema-field">
        <span>Tooltip</span>
        <input type="text" value={meta.tooltip ?? ""} onChange={(event) => onChange(node.name, { ...meta, tooltip: event.target.value })} />
      </label>
    </fieldset>
  );
}

export function CustomSection({ custom, onChange }: { custom: Record<string, unknown> | undefined; onChange: (custom: Record<string, unknown>) => void }) {
  const entries = Object.entries(custom ?? {});
  const [name, setName] = useState("");
  const [type, setType] = useState<CustomType>("string");
  const [problem, setProblem] = useState<string | null>(null);

  function add() {
    const key = name.trim();
    if (!CUSTOM_NAME.test(key)) {
      setProblem("Start with a letter or _; use letters, digits and _.");
      return;
    }
    if (custom && Object.hasOwn(custom, key)) {
      setProblem(`${key} already exists.`);
      return;
    }
    onChange({ ...(custom ?? {}), [key]: convertCustom(undefined, type) });
    setName("");
    setProblem(null);
  }

  return (
    <fieldset className="oip-schema oip-category" data-category="custom">
      <legend>Custom</legend>
      {entries.length === 0 ? <p className="oip-binding-muted">No custom properties. Bindings read them as custom.&lt;name&gt;.</p> : null}
      {entries.map(([key, value]) => (
        <CustomRow
          key={key}
          name={key}
          value={value}
          onChange={(next) => onChange({ ...(custom ?? {}), [key]: next })}
          onRemove={() => {
            const next = { ...(custom ?? {}) };
            delete next[key];
            onChange(next);
          }}
        />
      ))}
      <div className="oip-custom-add">
        <input
          type="text"
          aria-label="New custom property name"
          placeholder="name"
          value={name}
          aria-invalid={problem ? true : undefined}
          onChange={(event) => {
            setName(event.target.value);
            setProblem(null);
          }}
        />
        <select aria-label="New custom property type" value={type} onChange={(event) => setType(event.target.value as CustomType)}>
          {CUSTOM_TYPES.map((entry) => (
            <option key={entry} value={entry}>
              {entry}
            </option>
          ))}
        </select>
        <button type="button" onClick={add}>
          Add custom property
        </button>
      </div>
      {problem ? (
        <p role="alert" className="oip-style-field-error">
          {problem}
        </p>
      ) : null}
    </fieldset>
  );
}

function CustomRow({ name, value, onChange, onRemove }: { name: string; value: unknown; onChange: (value: unknown) => void; onRemove: () => void }) {
  const type = customTypeOf(value);
  const [json, setJson] = useState(() => (type === "object" || type === "array" ? JSON.stringify(value, null, 1) : ""));
  const [problem, setProblem] = useState<string | null>(null);
  useEffect(() => {
    if (type === "object" || type === "array") setJson(JSON.stringify(value, null, 1));
    setProblem(null);
  }, [value, type]);
  const label = `Custom ${name}`;
  return (
    <div className="oip-custom-row" data-custom={name} data-type={type}>
      <span className="oip-custom-name">{name}</span>
      <select aria-label={`Type of ${name}`} value={type} onChange={(event) => onChange(convertCustom(value, event.target.value as CustomType))}>
        {CUSTOM_TYPES.map((entry) => (
          <option key={entry} value={entry}>
            {entry}
          </option>
        ))}
      </select>
      {type === "string" ? <input type="text" aria-label={label} value={String(value)} onChange={(event) => onChange(event.target.value)} /> : null}
      {type === "number" ? (
        <input
          type="number"
          aria-label={label}
          value={String(value)}
          onChange={(event) => {
            const number = Number(event.target.value);
            if (event.target.value.trim() !== "" && Number.isFinite(number)) onChange(number);
          }}
        />
      ) : null}
      {type === "boolean" ? <input type="checkbox" aria-label={label} checked={value === true} onChange={(event) => onChange(event.target.checked)} /> : null}
      {type === "object" || type === "array" ? (
        <textarea
          aria-label={label}
          rows={3}
          spellCheck={false}
          value={json}
          aria-invalid={problem ? true : undefined}
          onChange={(event) => {
            setJson(event.target.value);
            try {
              const parsed: unknown = JSON.parse(event.target.value);
              if (customTypeOf(parsed) !== type) throw new Error(`Enter a JSON ${type}.`);
              setProblem(null);
              onChange(parsed);
            } catch (error) {
              setProblem(error instanceof SyntaxError ? "Not valid JSON." : error instanceof Error ? error.message : String(error));
            }
          }}
        />
      ) : null}
      {type === "null" ? <span className="oip-binding-muted">null</span> : null}
      <button type="button" aria-label={`Remove custom ${name}`} onClick={onRemove}>
        ×
      </button>
      {problem ? (
        <p role="alert" className="oip-style-field-error">
          {problem}
        </p>
      ) : null}
    </div>
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}
