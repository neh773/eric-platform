import { positionSchemaFor, type ComponentNode, type ContainerNode, type JsonSchema, type ViewNode } from "@oip/hmi-runtime";

export { frameSchema } from "@oip/hmi-runtime";

export function inspectorSchema(node: ViewNode, parent: ContainerNode | null, componentSchema: JsonSchema | undefined): JsonSchema {
  const properties: Record<string, JsonSchema> = {
    name: { type: "string", title: "Name" },
  };
  if (node.kind === "component" && componentSchema) {
    // Each property is drawn by the Inspector with its own binding control (BindingEditor), so bindings
    // are not part of this form and are never rebuilt from it.
    properties.props = { ...componentSchema, title: "Properties" };
  }
  // The position category: what the parent container's layout places this node by (HMP-115).
  Object.assign(properties, positionSchemaFor(parent));
  return { type: "object", title: node.name, properties, additionalProperties: false };
}

export function inspectorValue(node: ComponentNode): Record<string, unknown> {
  return {
    name: node.name,
    props: node.props,
    frame: node.frame,
    flex: node.flex,
  };
}
