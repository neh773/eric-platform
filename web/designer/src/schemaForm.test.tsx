import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { numericSchema } from "@oip/components";
import { frameSchema } from "./inspectorSchema";
import { SchemaForm, validateSchemaValue } from "./schemaForm";
import { appendComponent, updateNode } from "./project";
import { sampleProject } from "./sampleProject";

describe("schema form", () => {
  it("edits an object property with no fixed keys as JSON, keeping the value on invalid text", () => {
    const onChange = vi.fn();
    const schema = { type: "object" as const, properties: { elements: { type: "object" as const, title: "Element overrides (by id)", default: {} } } };
    render(<SchemaForm schema={schema} value={{ elements: { level: { fill: "#00f" } } }} onChange={onChange} />);
    const field = screen.getByRole("textbox", { name: "Element overrides (by id)" });
    expect(JSON.parse((field as HTMLTextAreaElement).value)).toEqual({ level: { fill: "#00f" } });
    fireEvent.change(field, { target: { value: "{ level: " } });
    fireEvent.blur(field);
    expect(screen.getByRole("alert")).toHaveTextContent("Must be JSON");
    fireEvent.change(field, { target: { value: "[1]" } });
    fireEvent.blur(field);
    expect(screen.getByRole("alert")).toHaveTextContent("Must be an object");
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(field, { target: { value: '{"level": {"fill": "#c00", "height": 80}}' } });
    fireEvent.blur(field);
    expect(onChange).toHaveBeenCalledWith({ elements: { level: { fill: "#c00", height: 80 } } });
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("blocks a number outside the schema and keeps the previous value", () => {
    const onChange = vi.fn();
    render(<SchemaForm schema={numericSchema} value={{ value: 1, decimals: 1, prefix: "", suffix: "", announce: true }} onChange={onChange} />);
    const decimals = screen.getByRole("spinbutton", { name: "Decimals" });
    fireEvent.change(decimals, { target: { value: "-1" } });
    expect(screen.getByRole("alert")).toHaveTextContent("Minimum 0");
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(decimals, { target: { value: "2" } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ decimals: 2 }));
  });

  it("renders fields from the schema rather than a fixed component form", () => {
    render(
      <SchemaForm
        schema={{
          type: "object",
          properties: {
            vesselName: { type: "string", title: "Vessel name", default: "" },
          },
        }}
        value={{ vesselName: "North" }}
        onChange={() => {}}
      />,
    );
    expect(screen.getByRole("textbox", { name: "Vessel name" })).toHaveValue("North");
  });

  it("rejects values the schema does not allow", () => {
    expect(validateSchemaValue({ type: "integer", minimum: 0, maximum: 6 }, -1)).toMatch(/Minimum 0/);
    expect(validateSchemaValue({ type: "integer", minimum: 0, maximum: 6 }, 1.5)).toMatch(/integer/);
    expect(validateSchemaValue({ type: "string", enum: ["start", "center"] }, "end")).toMatch(/one of/);
    expect(validateSchemaValue({ type: "array" }, [])).toMatch(/Unsupported schema/);
    expect(frameSchema("percent").properties?.x?.maximum).toBe(1);
    expect(frameSchema("fixed").properties?.width?.minimum).toBe(1);
  });
});

describe("project edits", () => {
  it("updates a component and appends a new one with a coordinate frame", () => {
    const overview = sampleProject.views[0]!;
    const renamed = updateNode(overview, "title", { props: { text: "Holding tank" } });
    const title = renamed.spec.root.children.find((node) => node.id === "title");
    expect(title && title.kind === "component" && title.props.text).toBe("Holding tank");
    const added = appendComponent(renamed, "label", "Label", { text: "Label", align: "start" });
    expect(added.view.spec.root.children.some((node) => node.id === added.id)).toBe(true);
    const created = added.view.spec.root.children.find((node) => node.id === added.id);
    expect(created?.frame?.width).toBe(160);
  });
});
