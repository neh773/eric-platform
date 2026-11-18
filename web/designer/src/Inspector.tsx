import { useEffect, useState } from "react";
import type { Binding, BindingPreview, ComponentNode, JsonSchema, StyleClassSet, TagUpdate, ViewDocument, ViewNode } from "@oip/hmi-runtime";
import { componentRegistry } from "@oip/components";
import { previewBinding, resolveContext } from "@oip/hmi-runtime";
import { SchemaForm } from "./schemaForm";
import { StyleEditor, setNodeStyle } from "./StyleEditor";
import { inspectorSchema } from "./inspectorSchema";
import { CustomSection, MetaSection, PositionSection, VariantPicker, setNodeCustom, setNodeMeta, setNodeVariant } from "./PropertyCategories";
import { SecurityEditor, setNodeSecurity } from "./SecurityEditor";
import { parentOf, updateNode } from "./project";
import { StyleClassPicker, setNodeStyleClasses } from "./StyleClassPicker";
import { ContainerInspector } from "./ContainerInspector";
import { ViewInspector } from "./ViewInspector";
import { SelectionPath } from "./SelectionPath";
import { BindingEditor } from "./BindingEditor";
import { bindingSummary, kindLabel, showValue, withBinding } from "./bindingModel";
import { TAG_DRAG_TYPE, decodeDraggedTag, tagBindingForProp } from "./tagDrop";
import { EventEditor } from "./EventEditor";
import { SvgPartsEditor } from "./SvgPartsEditor";
import { PipePointsEditor } from "./PipeTool";
import { MessageHandlersEditor, setNodeMessageHandlers } from "./MessageHandlersEditor";
import { MethodsEditor, setNodeMethods } from "./MethodsEditor";
import { COMPONENT_EVENTS, suggestHook, withNodeEvents } from "./eventModel";

/** Current tag values the Inspector previews bindings against (the Studio's tag server). */
export interface TagLookup {
  get(path: string): TagUpdate | undefined;
}

/** How often bound values in the Inspector are refreshed from the tag lookup. */
const PREVIEW_REFRESH_MS = 500;

export function Inspector({
  view,
  selected,
  styleClasses,
  tags,
  viewPaths = NO_PATHS,
  scriptHooks = NO_HOOKS,
  onView,
  onSelect,
}: {
  view: ViewDocument;
  selected: ViewNode | null;
  styleClasses?: StyleClassSet;
  /** Optional: without it, tag bindings show no live value. */
  tags?: TagLookup;
  /** The project's view paths, offered as popup and navigation targets. */
  viewPaths?: readonly string[];
  /** Hook names in the project's script hook set. */
  scriptHooks?: ReadonlySet<string>;
  onView: (view: ViewDocument) => void;
  /** Selects another node, from the breadcrumb of containers above the selection. */
  onSelect?: (id: string) => void;
}) {
  if (selected?.kind === "container") {
    return (
      <ContainerInspector view={view} node={selected} styleClasses={styleClasses} viewPaths={viewPaths} scriptHooks={scriptHooks} onView={onView} onSelect={onSelect} />
    );
  }
  if (!selected) {
    return (
      <aside className="oip-inspector" data-testid="inspector" aria-label="Inspector">
        <p className="oip-inspector-empty">Select a component or container on the canvas.</p>
        <ViewInspector view={view} viewPaths={viewPaths} scriptHooks={scriptHooks} onView={onView} />
      </aside>
    );
  }
  return (
    <NodeInspector
      key={selected.id}
      view={view}
      selected={selected}
      styleClasses={styleClasses}
      tags={tags}
      viewPaths={viewPaths}
      scriptHooks={scriptHooks}
      onView={onView}
      onSelect={onSelect}
    />
  );
}

const NO_PATHS: readonly string[] = [];
const NO_HOOKS: ReadonlySet<string> = new Set();

function NodeInspector({
  view,
  selected,
  styleClasses,
  tags,
  viewPaths,
  scriptHooks,
  onView,
  onSelect,
}: {
  view: ViewDocument;
  selected: ComponentNode;
  styleClasses?: StyleClassSet;
  tags?: TagLookup;
  viewPaths: readonly string[];
  scriptHooks: ReadonlySet<string>;
  onView: (view: ViewDocument) => void;
  onSelect?: (id: string) => void;
}) {
  const [editing, setEditing] = useState<string | null>(null);
  // The property row a dragged tag is over.
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const hasBindings = Object.keys(selected.bindings ?? {}).length > 0;
  useRefresh(Boolean(tags) && hasBindings);
  const registry = componentRegistry();
  const module = registry.get(selected.type);
  const parent = parentOf(view.spec.root, selected.id);
  const schema: JsonSchema = inspectorSchema(selected, parent, module?.schema);
  const propsSchema = schema.properties?.props;

  function setBinding(prop: string, binding: Binding | undefined) {
    onView(updateNode(view, selected.id, { bindings: withBinding(selected, prop, binding) }));
  }

  const preview = (binding: Binding, prop: string): BindingPreview =>
    previewBinding(binding, prop, selected.id, new TagLookupMap(tags), resolveContext(view), { hasQuerySource: false, selfId: selected.id });

  return (
    <aside className="oip-inspector" data-testid="inspector" aria-label="Inspector">
      <SelectionPath view={view} node={selected} onSelect={onSelect} />
      {styleClasses ? (
        <StyleClassPicker
          node={selected}
          styleClasses={styleClasses}
          onChange={(names) => onView(setNodeStyleClasses(view, selected.id, names))}
        />
      ) : null}
      {/* Property categories (HMP-127): props, position, custom and meta. */}
      {propsSchema ? (
        <fieldset className="oip-schema oip-props oip-category" data-category="props">
          <legend>Props</legend>
          <VariantPicker node={selected} module={module} onChange={(name) => onView(setNodeVariant(view, selected.id, name))} />
          {Object.entries(propsSchema.properties ?? {}).map(([key, child]) => {
            const label = child.title ?? key;
            const binding = selected.bindings?.[key];
            const open = editing === key;
            const current = binding ? preview(binding, key) : null;
            return (
              <div
                key={key}
                className="oip-prop"
                data-prop={key}
                data-bound={binding ? binding.kind : undefined}
                data-drop-target={dropTarget === key ? "yes" : undefined}
                onDragOver={(event) => {
                  if (!event.dataTransfer.types.includes(TAG_DRAG_TYPE)) return;
                  event.preventDefault();
                  event.dataTransfer.dropEffect = "link";
                  if (dropTarget !== key) setDropTarget(key);
                }}
                onDragLeave={(event) => {
                  if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDropTarget((current) => (current === key ? null : current));
                }}
                onDrop={(event) => {
                  setDropTarget(null);
                  // The binding editor's tag path field takes a drop itself.
                  if (event.defaultPrevented) return;
                  const raw = event.dataTransfer.getData(TAG_DRAG_TYPE);
                  const tag = raw ? decodeDraggedTag(raw) : null;
                  if (!tag) return;
                  event.preventDefault();
                  setBinding(key, tagBindingForProp(tag, child, binding));
                  setEditing(key);
                }}
              >
                <div className="oip-prop-row">
                  <div className="oip-prop-main">
                    {binding ? (
                      <BoundSummary label={label} binding={binding} preview={current} onOpen={() => setEditing(key)} />
                    ) : (
                      <SchemaForm
                        schema={{ ...child, title: label }}
                        value={selected.props[key] === undefined ? child.default : selected.props[key]}
                        onChange={(next) => onView(updateNode(view, selected.id, { props: { [key]: next } }))}
                      />
                    )}
                  </div>
                  <button
                    type="button"
                    className="oip-bind-button"
                    aria-label={`Bind ${label}`}
                    aria-expanded={open}
                    data-bound={binding ? "yes" : "no"}
                    title={binding ? `${kindLabel(binding.kind)} binding` : "Bind this property"}
                    onClick={() => setEditing(open ? null : key)}
                  >
                    <LinkIcon />
                  </button>
                </div>
                {open ? (
                  <BindingEditor
                    view={view}
                    nodeId={selected.id}
                    prop={key}
                    binding={binding}
                    evaluate={(draft) => preview(draft, key)}
                    authored={{
                      schema: child,
                      value: selected.props[key] === undefined ? child.default : selected.props[key],
                      onChange: (next) => onView(updateNode(view, selected.id, { props: { [key]: next } })),
                    }}
                    onChange={(next) => setBinding(key, next)}
                    onClose={() => setEditing(null)}
                  />
                ) : null}
              </div>
            );
          })}
          <UnlistedBindings node={selected} known={Object.keys(propsSchema.properties ?? {})} />
        </fieldset>
      ) : null}
      {selected.type === "pipe" && parent === view.spec.root && view.spec.root.layout === "coordinate" ? <PipePointsEditor view={view} node={selected} onView={onView} /> : null}
      {selected.type === "svg-component" ? <SvgPartsEditor view={view} node={selected} onView={onView} /> : null}
      <PositionSection view={view} node={selected} parent={parent} onView={onView} />
      <CustomSection custom={selected.custom} onChange={(custom) => onView(setNodeCustom(view, selected.id, custom))} />
      <MetaSection node={selected} onChange={(name, meta) => onView(setNodeMeta(view, selected.id, name, meta))} />
      <SecurityEditor
        label="Component security"
        rolesLabel="Roles that receive this component"
        security={selected.security}
        onChange={(security) => onView(setNodeSecurity(view, selected.id, security))}
      />
      <StyleEditor style={selected.style} onChange={(style) => onView(setNodeStyle(view, selected.id, style))} />
      <EventEditor
        title="Events"
        eventNames={COMPONENT_EVENTS}
        events={selected.events}
        suggestHook={(eventName) => suggestHook(view, selected.id, eventName)}
        viewPath={view.metadata.path}
        viewPaths={viewPaths}
        projectHooks={scriptHooks}
        onChange={(events) => onView(withNodeEvents(view, selected.id, events))}
      />
      <MessageHandlersEditor
        handlers={selected.messageHandlers}
        suggestHook={(messageType) => suggestHook(view, selected.id, `on-${messageType}`)}
        onChange={(handlers) => onView(setNodeMessageHandlers(view, selected.id, handlers))}
      />
      <MethodsEditor
        title="Custom methods"
        methods={selected.methods}
        suggestHook={(name) => suggestHook(view, selected.id, `method-${name}`)}
        viewPath={view.metadata.path}
        viewPaths={viewPaths}
        projectHooks={scriptHooks}
        onChange={(methods) => onView(setNodeMethods(view, selected.id, methods))}
      />
    </aside>
  );
}

/** Bindings on props the component schema does not list. They are kept as they are and listed so nothing is hidden. */
function UnlistedBindings({ node, known }: { node: ComponentNode; known: string[] }) {
  const extra = Object.entries(node.bindings ?? {}).filter(([prop]) => !known.includes(prop));
  if (extra.length === 0) return null;
  return (
    <p className="oip-binding-muted">
      Also bound (kept as authored): {extra.map(([prop, binding]) => `${prop} (${kindLabel(binding.kind)})`).join(", ")}
    </p>
  );
}

function BoundSummary({ label, binding, preview, onOpen }: { label: string; binding: Binding; preview: BindingPreview | null; onOpen: () => void }) {
  const quality = boundQuality(binding, preview);
  return (
    <button type="button" className="oip-bound" aria-label={`${label}: ${kindLabel(binding.kind)} binding`} onClick={onOpen}>
      <span className="oip-bound-label">{label}</span>
      <span className="oip-bound-kind" data-kind={binding.kind}>
        {kindLabel(binding.kind)}
      </span>
      <code className="oip-bound-summary" title={bindingSummary(binding)}>
        {bindingSummary(binding)}
      </code>
      <output className="oip-bound-value" data-quality={quality?.level}>
        {quality ? <span className="oip-quality-dot" data-quality={quality.level} title={quality.label} aria-label={`Quality ${quality.label}`} /> : null}
        {boundValue(binding, preview)}
      </output>
    </button>
  );
}

export function boundValue(binding: Binding, preview: BindingPreview | null): string {
  if (binding.enabled === false) return "disabled";
  if (binding.kind === "query" || binding.kind === "history" || binding.kind === "http") return "not connected";
  if (!preview) return "—";
  if (preview.result.applied) return showValue(preview.result.value);
  if (preview.result.code === "bad_not_connected") return "not connected";
  if (preview.result.code === "good") return "no value yet";
  return preview.result.code;
}

/** Quality of a tag-bound value for the bound-property row, or null for other binding kinds and before any value. */
export function boundQuality(binding: Binding, preview: BindingPreview | null): { level: "good" | "bad" | "uncertain"; label: string } | null {
  if (binding.kind !== "tag" || binding.enabled === false || !preview) return null;
  const code = preview.source.code;
  if (code === "good" && !preview.source.applied) return null;
  const level = code === "good" ? "good" : code.startsWith("bad") ? "bad" : "uncertain";
  return { level, label: code.replace(/_/g, " ") };
}

function LinkIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
      <path
        d="M6.5 9.5l3-3M7 4.5l1.2-1.2a2.6 2.6 0 013.7 3.7L10.7 8.2M9 11.5l-1.2 1.2a2.6 2.6 0 01-3.7-3.7L5.3 7.8"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** Re-render on a timer while there is something live to show. */
function useRefresh(active: boolean) {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!active) return undefined;
    const timer = setInterval(() => setTick((tick) => tick + 1), PREVIEW_REFRESH_MS);
    return () => clearInterval(timer);
  }, [active]);
}

/** A read-only map view over the tag lookup, for hmi-runtime binding evaluation. */
class TagLookupMap extends Map<string, TagUpdate> {
  private readonly lookup: TagLookup | undefined;
  constructor(lookup: TagLookup | undefined) {
    super();
    this.lookup = lookup;
  }
  override get(path: string): TagUpdate | undefined {
    return this.lookup?.get(path);
  }
  override has(path: string): boolean {
    return this.lookup?.get(path) !== undefined;
  }
}
