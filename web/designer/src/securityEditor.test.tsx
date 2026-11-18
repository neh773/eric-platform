import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useState } from "react";
import { parseView, type ViewDocument } from "@oip/hmi-runtime";
import { blankView, fromResource, toResource } from "./persistence";
import { cleanSecurity, SecurityEditor, setNodeSecurity } from "./SecurityEditor";
import { ViewInspector } from "./ViewInspector";

/** SEC-017: security levels on views, components and containers, edited in the Studio and kept through a save. */
// Doc source: FEATURES SEC-017.

function withButton(): ViewDocument {
  const view = blankView("reactor", "Reactor");
  view.spec.root.children.push({
    id: "bypass",
    kind: "component",
    name: "Bypass",
    type: "button",
    props: { text: "Bypass" },
    frame: { x: 0, y: 0, width: 80, height: 32 },
    security: { roles: [], levels: ["Authenticated/Roles/operator", "Plant/Night"], requireAll: true },
  });
  return view;
}

describe("security levels in the view model", () => {
  it("round-trips view and component levels through the stored resource", () => {
    const view = withButton();
    view.spec.security = { roles: ["operator"], levels: ["Authenticated/Roles"] };
    const stored = JSON.parse(JSON.stringify(toResource(view, { name: "reactor", version: 2 })));
    expect(stored.spec.document.security).toEqual({ roles: ["operator"], levels: ["Authenticated/Roles"] });
    expect(stored.spec.document.root.children[0].security).toEqual({ roles: [], levels: ["Authenticated/Roles/operator", "Plant/Night"], requireAll: true });
    const back = fromResource(stored);
    expect(back.spec.security).toEqual(view.spec.security);
    expect(back.spec.root.children[0]!.security).toEqual(view.spec.root.children[0]!.security);
  });

  it("refuses a malformed level or requireAll", () => {
    const doc = (security: unknown) => ({
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "v", path: "v" },
      spec: { width: 10, height: 10, root: { id: "r", kind: "container", name: "r", layout: "coordinate", props: {}, children: [] }, security },
    });
    expect(() => parseView(doc({ levels: ["/leading"] }))).toThrow("is not a security level");
    expect(() => parseView(doc({ levels: "Authenticated" }))).toThrow("must be a list");
    expect(() => parseView(doc({ levels: ["Authenticated"], requireAll: "yes" }))).toThrow("requireAll");
    expect(parseView(doc({ levels: ["Control Room/Shift A"] })).spec.security).toEqual({ roles: [], levels: ["Control Room/Shift A"] });
  });

  it("cleans a requirement and drops one that restricts nobody", () => {
    expect(cleanSecurity({ roles: [" ", "operator", "operator"], levels: [] })).toEqual({ roles: ["operator"] });
    expect(cleanSecurity({ roles: [], levels: ["A"], requireAll: true })).toEqual({ roles: [], levels: ["A"] });
    expect(cleanSecurity({ roles: [], levels: [] })).toBeUndefined();
    const cleared = setNodeSecurity(withButton(), "bypass", { roles: [] });
    expect(cleared.spec.root.children[0]!.security).toBeUndefined();
  });
});

function ViewHarness({ initial }: { initial: ViewDocument }) {
  const [view, setView] = useState(initial);
  return (
    <>
      <ViewInspector view={view} viewPaths={[]} onView={setView} />
      <pre data-testid="security">{JSON.stringify(view.spec.security ?? null)}</pre>
    </>
  );
}

function NodeHarness({ initial }: { initial: ViewDocument }) {
  const [view, setView] = useState(initial);
  const node = view.spec.root.children[0]!;
  return (
    <>
      <SecurityEditor
        label="Component security"
        rolesLabel="Roles that receive this component"
        security={node.security}
        onChange={(security) => setView(setNodeSecurity(view, node.id, security))}
      />
      <pre data-testid="security">{JSON.stringify(view.spec.root.children[0]!.security ?? null)}</pre>
    </>
  );
}

const shown = () => JSON.parse(screen.getByTestId("security").textContent!) as unknown;

describe("security editor", () => {
  it("sets a view's levels beside its roles and keeps the levels when the roles are cleared", () => {
    render(<ViewHarness initial={blankView("v", "V")} />);
    const region = screen.getByRole("region", { name: "View security" });
    const levels = within(region).getByRole("textbox", { name: "Security levels" });
    fireEvent.change(levels, { target: { value: "Authenticated/Roles/operator, Plant/Night" } });
    fireEvent.blur(levels);
    expect(shown()).toEqual({ roles: [], levels: ["Authenticated/Roles/operator", "Plant/Night"] });
    fireEvent.click(within(region).getByRole("checkbox", { name: "Require every level" }));
    expect(shown()).toEqual({ roles: [], levels: ["Authenticated/Roles/operator", "Plant/Night"], requireAll: true });
    const roles = () => within(screen.getByRole("region", { name: "View security" })).getByRole("textbox", { name: "Roles that may open this view" });
    fireEvent.change(roles(), { target: { value: "operator" } });
    fireEvent.blur(roles());
    expect(shown()).toMatchObject({ roles: ["operator"] });
    fireEvent.change(roles(), { target: { value: "" } });
    fireEvent.blur(roles());
    expect(shown()).toEqual({ roles: [], levels: ["Authenticated/Roles/operator", "Plant/Night"], requireAll: true });
  });

  it("edits a component's levels and flags a path the gateway would refuse", () => {
    render(<NodeHarness initial={withButton()} />);
    const region = screen.getByRole("region", { name: "Component security" });
    const levels = within(region).getByRole("textbox", { name: "Security levels" });
    expect(levels).toHaveValue("Authenticated/Roles/operator, Plant/Night");
    fireEvent.change(levels, { target: { value: "Plant//Night" } });
    fireEvent.blur(levels);
    expect(within(region).getByRole("alert").textContent).toContain("Plant//Night");
    fireEvent.change(within(region).getByRole("textbox", { name: "Security levels" }), { target: { value: "" } });
    fireEvent.blur(within(region).getByRole("textbox", { name: "Security levels" }));
    expect(shown()).toBeNull();
  });
});
