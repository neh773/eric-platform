import type { ContainerNode, JsonSchema, LayoutKind, StyleClassSet, ViewDocument } from "@oip/hmi-runtime";
import { containerModules } from "@oip/components";
import { SchemaForm } from "./schemaForm";
import { StyleEditor, setNodeStyle } from "./StyleEditor";
import { setNodePosition } from "./PropertyCategories";
import { SecurityEditor, setNodeSecurity } from "./SecurityEditor";
import { inspectorSchema } from "./inspectorSchema";
import { parentOf, setCoordinateMode, updateNode } from "./project";
import { StyleClassPicker, setNodeStyleClasses } from "./StyleClassPicker";
import { SelectionPath } from "./SelectionPath";
import { EventEditor } from "./EventEditor";
import { VIEW_EVENTS, suggestHook, withViewEvents } from "./eventModel";

const choice = (title: string, values: string[], fallback: string): JsonSchema => ({ type: "string", title, enum: values, default: fallback });

/** Property schemas for the containers the palette does not create (it creates breakpoint and split containers). */
const LAYOUT_SCHEMAS: Partial<Record<LayoutKind, JsonSchema>> = {
  coordinate: {
    type: "object",
    title: "Coordinate container",
    properties: {
      mode: choice("Mode", ["fixed", "percent"], "fixed"),
      aspectRatio: { type: "string", title: "Aspect ratio (x:y, percent mode)", default: "" },
    },
  },
  flex: {
    type: "object",
    title: "Flex container",
    properties: {
      direction: choice("Direction", ["row", "row-reverse", "column", "column-reverse"], "row"),
      wrap: choice("Wrap", ["nowrap", "wrap", "wrap-reverse"], "nowrap"),
      justify: choice("Justify", ["flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly"], "flex-start"),
      alignItems: choice("Align items", ["flex-start", "flex-end", "center", "baseline", "stretch"], "flex-start"),
    },
  },
  tab: {
    type: "object",
    title: "Tab container",
    properties: { currentTab: { type: "integer", title: "Current tab", minimum: 0, default: 0 } },
  },
};

export function containerPropsSchema(layout: LayoutKind): JsonSchema | undefined {
  return containerModules.find((module) => module.layout === layout)?.schema ?? LAYOUT_SCHEMAS[layout];
}

/** Inspector body for a selected container: name, container properties, and its placement in its parent. */
export function ContainerInspector({
  view,
  node,
  styleClasses,
  viewPaths = [],
  scriptHooks = new Set<string>(),
  onView,
  onSelect,
}: {
  view: ViewDocument;
  node: ContainerNode;
  styleClasses?: StyleClassSet;
  viewPaths?: readonly string[];
  scriptHooks?: ReadonlySet<string>;
  onView: (view: ViewDocument) => void;
  onSelect?: (id: string) => void;
}) {
  const parent = parentOf(view.spec.root, node.id);
  const base = inspectorSchema(node, parent, undefined);
  const propsSchema = containerPropsSchema(node.layout);
  const schema: JsonSchema = {
    ...base,
    properties: { name: base.properties!.name!, ...(propsSchema ? { props: { ...propsSchema, title: "Properties" } } : {}), ...omit(base.properties!, "name") },
  };
  return (
    <aside className="oip-inspector" data-testid="inspector" aria-label="Inspector" data-container={node.layout}>
      <SelectionPath view={view} node={node} onSelect={onSelect} />
      {styleClasses ? (
        <StyleClassPicker node={node} styleClasses={styleClasses} onChange={(names) => onView(setNodeStyleClasses(view, node.id, names))} />
      ) : null}
      <SchemaForm
        schema={schema}
        value={{ name: node.name, props: node.props, frame: node.frame, flex: node.flex, tabLabel: node.tabLabel, size: node.size }}
        onChange={(value) => {
          if (!value || typeof value !== "object" || Array.isArray(value)) return;
          const record = value as Record<string, unknown>;
          const frame = record.frame as ContainerNode["frame"] | undefined;
          // A coordinate mode switch converts the children's frames first, so nothing jumps.
          const nextMode = isRecord(record.props) ? record.props.mode : undefined;
          const base =
            node.layout === "coordinate" && (nextMode === "fixed" || nextMode === "percent") && nextMode !== (node.props.mode ?? "fixed")
              ? setCoordinateMode(view, node.id, nextMode)
              : view;
          const updated = updateNode(base, node.id, {
            name: typeof record.name === "string" ? record.name : node.name,
            props: isRecord(record.props) ? record.props : undefined,
            frame: parent?.layout === "coordinate" && frame ? frame : undefined,
            flex: parent?.layout === "flex" && isRecord(record.flex) ? (record.flex as unknown as ContainerNode["flex"]) : undefined,
          });
          // Tab labels and breakpoint sizes are position fields too (HMP-115).
          onView(parent?.layout === "tab" || parent?.layout === "breakpoint" ? setNodePosition(updated, node.id, parent, record) : updated);
        }}
      />
      <StyleEditor style={node.style} onChange={(style) => onView(setNodeStyle(view, node.id, style))} />
      <SecurityEditor
        label="Container security"
        rolesLabel="Roles that receive this container"
        security={node.security}
        onChange={(security) => onView(setNodeSecurity(view, node.id, security))}
      />
      {node.id === view.spec.root.id ? (
        // The root stands for the view: its startup and shutdown events belong to the view.
        <EventEditor
          title="View events"
          eventNames={VIEW_EVENTS}
          events={view.spec.events}
          suggestHook={(eventName) => suggestHook(view, "view", eventName)}
          viewPath={view.metadata.path}
          viewPaths={viewPaths}
          projectHooks={scriptHooks}
          onChange={(events) => onView(withViewEvents(view, events))}
        />
      ) : null}
    </aside>
  );
}

function omit<T>(record: Record<string, T>, key: string): Record<string, T> {
  const { [key]: _dropped, ...rest } = record;
  return rest;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
