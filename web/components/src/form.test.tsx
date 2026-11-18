import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Form, initialDraft, readFormFields, validateForm } from "./form";
import { allowedComponentTypes, componentRegistry } from "./registry";

/** HMP-064: form component with validation and submit actions. doc source: FEATURES HMP-064 */

const FIELDS = [
  { name: "name", label: "Name", type: "text", required: true, minLength: 2 },
  { name: "quantity", label: "Quantity", type: "number", min: 1, max: 10 },
  { name: "email", label: "Email", type: "email" },
  { name: "unit", label: "Unit", type: "select", options: "kg:Kilograms, lb:Pounds" },
  { name: "agree", label: "Agree", type: "checkbox", required: true },
  { name: "code", label: "Code", type: "text", pattern: "[A-Z]{3}-\\d+", message: "Use the format ABC-12" },
];

function setup(props: Record<string, unknown> = {}) {
  const onChange = vi.fn();
  const nodeClick = vi.fn();
  const ui = (extra: Record<string, unknown> = {}) => (
    <div onClick={nodeClick}>
      <Form nodeId="f" locale="en-US" onChange={onChange} props={{ label: "Order", fields: FIELDS, ...props, ...extra }} />
    </div>
  );
  const result = render(ui());
  return { ...result, onChange, nodeClick, rerender: (extra: Record<string, unknown>) => result.rerender(ui(extra)) };
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label, { exact: false }), { target: { value } });
}

describe("form fields and validation", () => {
  it("reads usable fields: valid unique names, a known type, parsed options", () => {
    const fields = readFormFields([
      { name: "a", type: "bogus" },
      { name: "a", label: "Again" },
      { name: "1bad" },
      { label: "No name" },
      "text",
      { name: "u", type: "select", options: "x:One, y:Two" },
    ]);
    expect(fields.map((field) => [field.name, field.type])).toEqual([
      ["a", "text"],
      ["u", "select"],
    ]);
    expect(fields[1]!.options.map((option) => option.value)).toEqual(["x", "y"]);
    expect(readFormFields('[{"name":"j"}]').map((field) => field.name)).toEqual(["j"]);
    expect(readFormFields("not json")).toEqual([]);
  });

  it("ignores a pattern that is not a valid expression instead of failing", () => {
    const [field] = readFormFields([{ name: "p", pattern: "([" }]);
    expect(field!.pattern).toBeNull();
  });

  it("checks required, number range, email, select, length and pattern, and types the values", () => {
    const fields = readFormFields(FIELDS);
    const bad = validateForm(fields, { name: "", quantity: "11", email: "nope", unit: "stone", agree: false, code: "abc" });
    expect(Object.keys(bad.errors).sort()).toEqual(["agree", "code", "email", "name", "quantity", "unit"]);
    expect(bad.errors.name).toBe("Name is required");
    expect(bad.errors.quantity).toBe("Quantity must be at most 10");
    expect(bad.errors.code).toBe("Use the format ABC-12");
    const good = validateForm(fields, { name: "Pump", quantity: "3.5", email: "ops@plant.test", unit: "kg", agree: true, code: "ABC-12" });
    expect(good.errors).toEqual({});
    expect(good.values).toEqual({ name: "Pump", quantity: 3.5, email: "ops@plant.test", unit: "kg", agree: true, code: "ABC-12" });
  });

  it("leaves an optional field out when it is empty", () => {
    const fields = readFormFields(FIELDS);
    const result = validateForm(fields, { name: "Pump", quantity: "", email: "", unit: "", agree: true, code: "" });
    expect(result.errors).toEqual({});
    expect(result.values).toEqual({ name: "Pump", agree: true });
  });

  it("starts from values, then defaults, and writes numbers as text", () => {
    const fields = readFormFields([{ name: "n", type: "number", default: 4 }, { name: "t", default: "x" }, { name: "c", type: "checkbox" }]);
    expect(initialDraft(fields, { t: "given" })).toEqual({ n: "4", t: "given", c: false });
  });
});

describe("Form", () => {
  it("does not submit while a field is invalid: shows each reason, focuses the first, writes nothing, and stops the click", () => {
    const { onChange, nodeClick } = setup();
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(screen.getByText("Name is required")).toBeInTheDocument();
    expect(screen.getByText("Agree must be checked")).toBeInTheDocument();
    const name = screen.getByLabelText("Name", { exact: false });
    expect(name).toHaveAttribute("aria-invalid", "true");
    expect(name).toHaveFocus();
    expect(onChange).not.toHaveBeenCalled();
    expect(nodeClick).not.toHaveBeenCalled();
  });

  it("submits typed values once every field is valid, then lets the click reach the node", () => {
    const { onChange, nodeClick } = setup({ submitCount: 2 });
    type("Name", "Pump 7");
    type("Quantity", "4");
    fireEvent.click(screen.getByRole("checkbox", { name: "Agree" }));
    nodeClick.mockClear();
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(onChange).toHaveBeenCalledWith("values", { name: "Pump 7", quantity: 4, agree: true });
    expect(onChange).toHaveBeenCalledWith("submitCount", 3);
    expect(nodeClick).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("clears a field's reason once it is edited", () => {
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(screen.getByText("Name is required")).toBeInTheDocument();
    type("Name", "Pump");
    expect(screen.queryByText("Name is required")).toBeNull();
  });

  it("submits on Enter in a field, and Enter in a text area adds a line instead", () => {
    const { onChange } = setup({ fields: [{ name: "note", label: "Note", type: "text" }, { name: "long", label: "Long", type: "textarea" }] });
    fireEvent.keyDown(screen.getByLabelText("Long", { exact: false }), { key: "Enter" });
    expect(onChange).not.toHaveBeenCalled();
    type("Note", "hello");
    fireEvent.keyDown(screen.getByLabelText("Note", { exact: false }), { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("values", { note: "hello" });
  });

  it("never posts: the form's own submit is cancelled", () => {
    setup();
    const form = screen.getByRole("form", { name: "Order" });
    const event = new Event("submit", { bubbles: true, cancelable: true });
    form.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
  });

  it("starts over when values arrive from a binding, and reset restores them without a click action", () => {
    const { rerender, nodeClick } = setup({ showReset: true, resetText: "Clear", fields: [{ name: "name", label: "Name" }] });
    type("Name", "typed");
    rerender({ showReset: true, resetText: "Clear", fields: [{ name: "name", label: "Name" }], values: { name: "from tag" } });
    expect(screen.getByLabelText("Name", { exact: false })).toHaveValue("from tag");
    type("Name", "edited");
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(screen.getByLabelText("Name", { exact: false })).toHaveValue("from tag");
    expect(nodeClick).not.toHaveBeenCalled();
  });

  it("disables every control when not enabled and submits nothing", () => {
    const { onChange } = setup({ enabled: false });
    expect(screen.getByRole("button", { name: "Submit" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(onChange).not.toHaveBeenCalled();
  });

  it("says when the field list is not valid JSON", () => {
    setup({ fields: "[{" });
    expect(screen.getByRole("alert")).toHaveTextContent("Fields is not valid JSON");
  });

  it("is registered in the input palette", () => {
    const module = componentRegistry().get("form")!;
    expect(allowedComponentTypes().has("form")).toBe(true);
    expect(module.category).toBe("input");
    expect(module.schema.additionalProperties).toBe(false);
  });
});
