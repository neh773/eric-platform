import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SchemaForm } from "./schemaForm";

describe("schema form", () => {
  it("blocks a greeting outside minLength and maxLength", () => {
    const onChange = vi.fn();
    render(
      <SchemaForm
        schema={{
          type: "object",
          properties: { greeting: { type: "string", minLength: 1, maxLength: 200 } },
        }}
        value={{ greeting: "Hello" }}
        errors={{ greeting: "At least 1 character" }}
        onChange={onChange}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("At least 1 character");
    fireEvent.change(screen.getByRole("textbox", { name: "Greeting" }), { target: { value: "Plant" } });
    expect(onChange).toHaveBeenCalledWith({ greeting: "Plant" });
  });

  it("renders nested objects, enums, and arrays from the schema", () => {
    render(
      <SchemaForm
        schema={{
          type: "object",
          properties: {
            mode: { type: "string", enum: ["a", "b"] },
            address: { type: "object", properties: { city: { type: "string", title: "City" } } },
            tags: { type: "array", items: { type: "string" } },
          },
        }}
        value={{ mode: "a", address: { city: "North" }, tags: ["pump"] }}
        errors={{}}
        onChange={() => {}}
      />,
    );
    expect(screen.getByRole("combobox", { name: "Mode" })).toHaveValue("a");
    expect(screen.getByRole("textbox", { name: "City" })).toHaveValue("North");
    expect(screen.getByRole("textbox", { name: "Tags 1" })).toHaveValue("pump");
    expect(screen.getByRole("button", { name: "Add Tags" })).toBeVisible();
  });

  it("does not render credential fields or their values", () => {
    const secret = "argon2id-secret-value";
    render(
      <SchemaForm
        schema={{
          type: "object",
          properties: {
            passwordHash: { type: "string", minLength: 20 },
            passwordHistory: { type: "array", items: { type: "string" } },
          },
        }}
        value={{ passwordHash: secret, passwordHistory: [secret] }}
        errors={{}}
        onChange={() => {}}
      />,
    );
    expect(screen.getByText(/not shown/i)).toBeVisible();
    expect(document.body.textContent).not.toContain("passwordHash");
    expect(document.body.textContent).not.toContain(secret);
  });
});
