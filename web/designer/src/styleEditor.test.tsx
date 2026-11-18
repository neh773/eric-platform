import { useState } from "react";
import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { parseView, type StyleProps, type ViewDocument } from "@oip/hmi-runtime";
import { StyleEditor, setNodeStyle } from "./StyleEditor";
import { Inspector } from "./Inspector";

/** P4-07 (HMP-093): the Studio style editor. */
function Harness({ initial, seen }: { initial?: StyleProps; seen: StyleProps[] }) {
  const [style, setStyle] = useState<StyleProps | undefined>(initial);
  return (
    <StyleEditor
      style={style}
      onChange={(next) => {
        seen.push(next);
        setStyle(next);
      }}
    />
  );
}

function view(): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "Main", path: "main" },
    spec: {
      params: {},
      custom: {},
      width: 400,
      height: 300,
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout: "coordinate",
        props: {},
        children: [{ id: "title", kind: "component", name: "Title", type: "label", props: { text: "Hi" }, frame: { x: 0, y: 0, width: 100, height: 30 } }],
      },
    },
  });
}

describe("StyleEditor", () => {
  it("adds, edits and removes allowed properties", () => {
    const seen: StyleProps[] = [];
    render(<Harness seen={seen} />);
    const editor = screen.getByRole("group", { name: "Style" });
    const picker = within(editor).getByRole("combobox", { name: "Style property to add" });
    // Every property of the subset is offered, nothing else.
    expect(within(picker).getAllByRole("option").map((option) => option.getAttribute("value"))).toContain("borderRadius");
    expect(within(picker).queryByRole("option", { name: /position/ })).toBeNull();

    fireEvent.change(picker, { target: { value: "borderRadius" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Add" }));
    expect(seen.at(-1)).toEqual({ borderRadius: 0 });
    fireEvent.change(within(editor).getByRole("spinbutton", { name: "Border radius (px)" }), { target: { value: "6" } });
    expect(seen.at(-1)).toEqual({ borderRadius: 6 });

    fireEvent.change(picker, { target: { value: "color" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Add" }));
    const color = within(editor).getByRole("textbox", { name: "Text color" });
    fireEvent.change(color, { target: { value: "url(evil)" } });
    // A refused value is shown and not saved.
    expect(within(editor).getByRole("alert")).toHaveTextContent("color must be a color");
    expect(seen.at(-1)).toEqual({ borderRadius: 6, color: "#333333" });
    fireEvent.change(color, { target: { value: "#c0392b" } });
    expect(seen.at(-1)).toEqual({ borderRadius: 6, color: "#c0392b" });

    fireEvent.change(picker, { target: { value: "borderStyle" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Add" }));
    fireEvent.change(within(editor).getByRole("combobox", { name: "Border style" }), { target: { value: "dashed" } });
    expect(seen.at(-1)).toEqual({ borderRadius: 6, color: "#c0392b", borderStyle: "dashed" });

    fireEvent.click(within(editor).getByRole("button", { name: "Remove Border radius" }));
    expect(seen.at(-1)).toEqual({ color: "#c0392b", borderStyle: "dashed" });
    expect(within(editor).getByRole("textbox", { name: "Style CSS" })).toHaveValue("color: #c0392b; border-style: dashed");
  });

  it("applies CSS text in the subset and refuses CSS text outside it", () => {
    const seen: StyleProps[] = [];
    render(<Harness seen={seen} initial={{ color: "red" }} />);
    const editor = screen.getByRole("group", { name: "Style" });
    const css = within(editor).getByRole("textbox", { name: "Style CSS" });
    expect(css).toHaveValue("color: red");

    fireEvent.change(css, { target: { value: "color: blue; position: fixed; background: url(https://example.test/a.png)" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Apply CSS" }));
    const alert = within(editor).getByRole("alert");
    expect(alert).toHaveTextContent("Refused; nothing was changed");
    expect(alert).toHaveTextContent("position is not a supported style property");
    expect(alert).toHaveTextContent('background: "url(https://example.test/a.png)" is not allowed');
    expect(seen).toEqual([]);
    expect(css).toHaveAttribute("aria-invalid", "true");

    fireEvent.change(css, { target: { value: "color: blue; border-radius: 4px; font-weight: 700" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Apply CSS" }));
    expect(within(editor).queryByRole("alert")).toBeNull();
    expect(seen).toEqual([{ color: "blue", borderRadius: 4, fontWeight: 700 }]);
    expect(within(editor).getByRole("spinbutton", { name: "Font weight" })).toHaveValue(700);
  });

  it("edits a selected component's style in the Inspector and keeps it with the view", () => {
    let current = view();
    const { rerender } = render(<Inspector view={current} selected={current.spec.root.children[0]!} onView={(next) => (current = next)} />);
    const editor = screen.getByRole("group", { name: "Style" });
    fireEvent.change(within(editor).getByRole("textbox", { name: "Style CSS" }), { target: { value: "background-color: var(--oip-color-accent); padding: 6px" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Apply CSS" }));
    expect(current.spec.root.children[0]!.style).toEqual({ background: "var(--oip-color-accent)", padding: 6 });
    // The saved document reads back with the same style.
    expect(parseView(JSON.parse(JSON.stringify(current))).spec.root.children[0]!.style).toEqual({ background: "var(--oip-color-accent)", padding: 6 });
    rerender(<Inspector view={current} selected={current.spec.root.children[0]!} onView={(next) => (current = next)} />);
    expect(screen.getByRole("textbox", { name: "Style CSS" })).toHaveValue("background-color: var(--oip-color-accent); padding: 6px");
    expect(setNodeStyle(current, "title", {}).spec.root.children[0]!.style).toBeUndefined();
  });
});
