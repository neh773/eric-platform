import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ViewRenderer, createSession, parseView, type ComponentNode, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes, componentRegistry, importSvg, type SvgImport } from "@oip/components";
import { createTagRuntime, seedStudio } from "./runtime";
import { blankView } from "./persistence";
import { Inspector } from "./Inspector";
import { SvgImportPanel } from "./SvgImportPanel";
import { addSvgComponent, bindSvgPart, boundParam, setSvgPart, suggestParamName, unbindSvgPart } from "./svgModel";

/** HMV-151: importing an SVG in the designer and parameterising its parts. */

const SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"><script>alert(1)</script>
  <g id="body"><rect id="shell" width="200" height="100" fill="#cccccc" onclick="x()"/></g><text id="caption" x="5" y="5">Pump</text></svg>`;

function imported(): Extract<SvgImport, { ok: true }> {
  const result = importSvg(document, SVG);
  if (!result.ok) throw new Error(result.reason);
  return result;
}

function nodeOf(view: ViewDocument): ComponentNode {
  return view.spec.root.children[0] as ComponentNode;
}

describe("SVG component model", () => {
  it("adds a component holding the cleaned markup, sized to the drawing's aspect ratio", () => {
    const { view, id } = addSvgComponent(blankView("v", "V"), imported(), "Pump");
    const node = nodeOf(view);
    expect(node.id).toBe(id);
    expect(node.type).toBe("svg-component");
    expect(String(node.props.svg)).not.toContain("script");
    expect(String(node.props.svg)).not.toContain("onclick");
    expect(node.frame!.width / node.frame!.height).toBeCloseTo(2, 1);
  });

  it("sets and clears a part, binds it to a new parameter holding the current value, and unbinds", () => {
    const { view, id } = addSvgComponent(blankView("v", "V"), imported(), "Pump");
    const colored = setSvgPart(view, id, "fills", "body", "#00ff00");
    expect(nodeOf(colored).props.fills).toEqual({ body: "#00ff00" });
    expect(nodeOf(setSvgPart(colored, id, "fills", "body", undefined)).props.fills).toEqual({});

    const bound = bindSvgPart(colored, id, "fills", "body", suggestParamName("fills", "body"), "#00ff00");
    expect(suggestParamName("fills", "body")).toBe("body_fill");
    expect(bound.spec.params.body_fill).toEqual({ value: "#00ff00", direction: "input" });
    expect(nodeOf(bound).bindings!["fills.body"]).toEqual({ kind: "property", source: { scope: "param", name: "body_fill" } });
    expect(boundParam(nodeOf(bound), "fills", "body")).toBe("body_fill");
    expect(() => bindSvgPart(colored, id, "fills", "body", "bad name", "x")).toThrow();

    const unbound = unbindSvgPart(bound, id, "fills", "body");
    expect(nodeOf(unbound).bindings!["fills.body"]).toBeUndefined();
    expect(unbound.spec.params.body_fill).toBeDefined();
    // The view still parses.
    expect(() => parseView(unbound, { allowedTypes: allowedComponentTypes() })).not.toThrow();
  });

  it("a part bound to a parameter follows the parameter when the view is drawn", async () => {
    const { view, id } = addSvgComponent(blankView("v", "V"), imported(), "Pump");
    const bound = bindSvgPart(
      bindSvgPart(
        bindSvgPart(view, id, "fills", "body", "body_fill", "#112233"),
        id, "visibility", "caption", "show_caption", true),
      id, "texts", "caption", "caption_text", "Pump");
    const parsed = parseView(JSON.parse(JSON.stringify(bound)), { allowedTypes: allowedComponentTypes() });
    const runtime = createTagRuntime(seedStudio, async () => []);
    const draw = (overrides: Record<string, unknown>) => (
      <ViewRenderer view={parsed} registry={componentRegistry()} transport={runtime.transport} session={createSession({ id: "s", locale: "en-US" })} paramOverrides={overrides} />
    );
    const { container, rerender } = render(draw({}));
    const part = (name: string) => container.querySelector(`[id$="-${name}"]`)!;
    await waitFor(() => expect(part("shell")).not.toBeNull());
    expect(part("body").getAttribute("fill")).toBe("#112233");
    expect(part("shell").getAttribute("fill")).toBe("#112233");
    rerender(draw({ body_fill: "#aa0000", show_caption: false, caption_text: "Pump 2" }));
    await waitFor(() => expect(part("shell").getAttribute("fill")).toBe("#aa0000"));
    expect(part("caption").getAttribute("display")).toBe("none");
    expect(part("caption").textContent).toBe("Pump 2");
    runtime.server.close();
  });
});

describe("SvgImportPanel", () => {
  it("previews the parts and what was removed, then adds the component", () => {
    const onAdd = vi.fn();
    render(<SvgImportPanel onAdd={onAdd} />);
    expect(screen.getByRole("button", { name: "Add SVG component" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("SVG markup"), { target: { value: SVG } });
    const status = screen.getByRole("status");
    expect(status).toHaveTextContent("3 parts found: 1 groups, 1 shapes, 1 texts.");
    expect(status).toHaveTextContent("Removed for safety: script, onclick");
    fireEvent.change(screen.getByLabelText("SVG component name"), { target: { value: "Pump" } });
    fireEvent.click(screen.getByRole("button", { name: "Add SVG component" }));
    expect(onAdd).toHaveBeenCalledTimes(1);
    expect(onAdd.mock.calls[0]![1]).toBe("Pump");
  });

  it("says when the markup is not SVG and adds nothing", () => {
    render(<SvgImportPanel onAdd={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("SVG markup"), { target: { value: "<svg><g></svg>" } });
    expect(screen.getByRole("alert")).toHaveTextContent("not valid SVG");
    expect(screen.getByRole("button", { name: "Add SVG component" })).toBeDisabled();
  });

  it("reads a chosen file", async () => {
    render(<SvgImportPanel onAdd={vi.fn()} />);
    const file = new File([SVG], "pump.svg", { type: "image/svg+xml" });
    fireEvent.change(screen.getByLabelText("SVG file"), { target: { files: [file] } });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("3 parts found"));
    expect(screen.getByLabelText("SVG component name")).toHaveValue("pump");
  });
});

describe("SVG parts in the Inspector", () => {
  it("lists the parts, sets a colour and binds visibility to a parameter", () => {
    let current = addSvgComponent(blankView("v", "V"), imported(), "Pump");
    const view = () => current.view;
    const { rerender } = render(<Inspector view={view()} selected={nodeOf(view())} onView={(next) => { current = { ...current, view: next }; }} />);
    const parts = screen.getByTestId("svg-parts");
    expect(within(parts).getByText("shell")).toBeInTheDocument();
    fireEvent.change(within(parts).getByLabelText("Fill of body"), { target: { value: "#abcdef" } });
    expect(nodeOf(view()).props.fills).toEqual({ body: "#abcdef" });
    rerender(<Inspector view={view()} selected={nodeOf(view())} onView={(next) => { current = { ...current, view: next }; }} />);
    fireEvent.click(screen.getByLabelText("Bind Visible of caption to a parameter"));
    fireEvent.change(screen.getByLabelText("Parameter name for Visible of caption"), { target: { value: "show caption" } });
    expect(screen.getByRole("button", { name: "Bind to parameter" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Parameter name for Visible of caption"), { target: { value: "show_caption" } });
    fireEvent.click(screen.getByRole("button", { name: "Bind to parameter" }));
    expect(boundParam(nodeOf(view()), "visibility", "caption")).toBe("show_caption");
    expect(view().spec.params.show_caption!.value).toBe(true);
  });
});
