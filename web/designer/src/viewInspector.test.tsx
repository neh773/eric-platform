import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useState } from "react";
import type { ViewDocument } from "@oip/hmi-runtime";
import { componentRegistry, defaultsFromSchema } from "@oip/components";
import { blankView, fromResource, toResource } from "./persistence";
import { ViewInspector, readCustom } from "./ViewInspector";
import { applyDropConfig, dropRuleFor } from "./tagDrop";

/** P4-06 (HMP-073, HMP-110), with the view security of P4-04 (HMP-092): view properties edited in the Studio. */

const registry = componentRegistry();
const moduleFor = (type: string) => registry.get(type);

function configured(): ViewDocument {
  const view = blankView("tank", "Tank");
  view.spec.custom = { setpoint: { value: 42 }, area: { value: "North" } };
  view.spec.security = { roles: ["operator"] };
  view.spec.dropConfig = [
    { dataType: "double", component: "numeric-entry", prop: "value", bidirectional: true },
    { dataType: "boolean", component: "indicator", prop: "value" },
    { dataType: "*", view: "tank-card", param: "tagPath" },
  ];
  return view;
}

describe("view properties persistence", () => {
  it("round-trips custom properties, security and drop configuration through the stored resource", () => {
    const view = configured();
    const stored = JSON.parse(JSON.stringify(toResource(view, { name: "tank", version: 3 })));
    expect(stored.spec.document.custom).toEqual({ setpoint: { value: 42 }, area: { value: "North" } });
    expect(stored.spec.document.dropConfig).toHaveLength(3);
    const back = fromResource(stored);
    expect(back.spec.custom).toEqual(view.spec.custom);
    expect(back.spec.security).toEqual({ roles: ["operator"] });
    expect(back.spec.dropConfig).toEqual(view.spec.dropConfig);
    expect(toResource(back, { name: "tank", version: 3 })).toEqual(stored);
  });
});

describe("drop configuration", () => {
  it("picks the rule for the tag's data type, then any type, ignoring case", () => {
    const rules = configured().spec.dropConfig;
    expect(dropRuleFor(rules, { path: "a", dataType: "Double" })).toMatchObject({ component: "numeric-entry" });
    expect(dropRuleFor(rules, { path: "a", dataType: "boolean" })).toMatchObject({ component: "indicator" });
    expect(dropRuleFor(rules, { path: "a", dataType: "string" })).toMatchObject({ view: "tank-card" });
    expect(dropRuleFor(rules, { path: "a" })).toMatchObject({ view: "tank-card" });
    expect(dropRuleFor([], { path: "a", dataType: "double" })).toBeNull();
    expect(dropRuleFor(undefined, { path: "a", dataType: "double" })).toBeNull();
  });

  it("turns a dropped tag into the configured component with a binding, at the drop point", () => {
    const made = applyDropConfig(configured(), { path: "[default]tank/sp", dataType: "double" }, { x: 100, y: 60 }, moduleFor, defaultsFromSchema)!;
    const node = made.view.spec.root.children.find((child) => child.id === made.id)!;
    expect(node).toMatchObject({ kind: "component", type: "numeric-entry", frame: { x: 100, y: 60 } });
    expect((node as { bindings?: unknown }).bindings).toEqual({ value: { kind: "tag", mode: "direct", path: "[default]tank/sp", bidirectional: true } });
    expect((node as { props: Record<string, unknown> }).props).toMatchObject({ inputType: "decimal", minimum: 0, maximum: 100 });
  });

  it("turns a dropped tag into an embedded view whose param receives the tag path", () => {
    const made = applyDropConfig(configured(), { path: "[default]tank/name", dataType: "string" }, { x: 0, y: 0 }, moduleFor, defaultsFromSchema)!;
    const node = made.view.spec.root.children.find((child) => child.id === made.id)!;
    expect(node).toMatchObject({ type: "embedded-view", props: { path: "tank-card", params: { tagPath: "[default]tank/name" } } });
  });

  it("does nothing without a matching rule", () => {
    expect(applyDropConfig(blankView("v", "V"), { path: "x", dataType: "double" }, { x: 0, y: 0 }, moduleFor, defaultsFromSchema)).toBeNull();
  });
});

function Harness({ initial }: { initial: ViewDocument }) {
  const [view, setView] = useState(initial);
  return (
    <>
      <ViewInspector view={view} viewPaths={["tank", "tank-card"]} onView={setView} />
      <pre data-testid="spec">{JSON.stringify({ custom: view.spec.custom, security: view.spec.security, dropConfig: view.spec.dropConfig })}</pre>
    </>
  );
}

function spec() {
  return JSON.parse(screen.getByTestId("spec").textContent!) as Pick<ViewDocument["spec"], "custom" | "security" | "dropConfig">;
}

describe("view inspector", () => {
  it("reads typed custom values", () => {
    expect(readCustom("42")).toBe(42);
    expect(readCustom("true")).toBe(true);
    expect(readCustom("[1, 2]")).toEqual([1, 2]);
    expect(readCustom("North")).toBe("North");
    expect(readCustom("[oops")).toBe("[oops");
  });

  it("adds, edits and removes custom properties, and refuses a bad or repeated name", () => {
    render(<Harness initial={blankView("tank", "Tank")} />);
    const custom = screen.getByRole("region", { name: "Custom properties" });
    fireEvent.change(within(custom).getByLabelText("Name"), { target: { value: "setpoint" } });
    fireEvent.change(within(custom).getByLabelText("Value"), { target: { value: "42.5" } });
    fireEvent.click(within(custom).getByRole("button", { name: "Add custom property" }));
    expect(spec().custom).toEqual({ setpoint: { value: 42.5 } });

    fireEvent.change(within(custom).getByLabelText("Name"), { target: { value: "setpoint" } });
    fireEvent.click(within(custom).getByRole("button", { name: "Add custom property" }));
    expect(within(custom).getByRole("alert")).toHaveTextContent("already has a custom property setpoint");
    fireEvent.change(within(custom).getByLabelText("Name"), { target: { value: "9 lives" } });
    fireEvent.click(within(custom).getByRole("button", { name: "Add custom property" }));
    expect(within(custom).getByRole("alert")).toHaveTextContent("starts with a letter");

    const field = within(custom).getByRole("textbox", { name: "Custom property setpoint" });
    fireEvent.change(field, { target: { value: "50" } });
    fireEvent.blur(field);
    expect(spec().custom).toEqual({ setpoint: { value: 50 } });
    fireEvent.click(within(custom).getByRole("button", { name: "Remove custom property setpoint" }));
    expect(spec().custom).toEqual({});
  });

  it("sets the view's security roles, and clears them", () => {
    render(<Harness initial={blankView("tank", "Tank")} />);
    const roles = () => within(screen.getByRole("region", { name: "View security" })).getByRole("textbox", { name: "Roles that may open this view" });
    fireEvent.change(roles(), { target: { value: "operator, supervisor, operator" } });
    fireEvent.blur(roles());
    expect(spec().security).toEqual({ roles: ["operator", "supervisor"] });
    expect(roles()).toHaveValue("operator, supervisor");
    fireEvent.change(roles(), { target: { value: " " } });
    fireEvent.blur(roles());
    expect(spec().security).toBeUndefined();
  });

  it("edits drop rules: data type, component and property, embedded view, and removal", () => {
    render(<Harness initial={blankView("tank", "Tank")} />);
    const drop = screen.getByRole("region", { name: "Drop configuration" });
    fireEvent.click(within(drop).getByRole("button", { name: "Add drop rule" }));
    expect(spec().dropConfig).toEqual([{ dataType: "*", component: "label", prop: "text" }]);
    const rule = within(drop).getByRole("group", { name: "Drop rule 1" });
    fireEvent.change(within(rule).getByLabelText("Data type"), { target: { value: "double" } });
    fireEvent.change(within(rule).getByLabelText("Component"), { target: { value: "numeric-entry" } });
    fireEvent.click(within(rule).getByLabelText("Bidirectional"));
    expect(spec().dropConfig).toEqual([{ dataType: "double", component: "numeric-entry", prop: "value", bidirectional: true }]);
    fireEvent.change(within(rule).getByLabelText("Bound property"), { target: { value: "maximum" } });
    expect(spec().dropConfig![0]).toMatchObject({ prop: "maximum" });

    fireEvent.click(within(drop).getByRole("button", { name: "Add drop rule" }));
    const second = within(drop).getByRole("group", { name: "Drop rule 2" });
    fireEvent.change(within(second).getByLabelText("Becomes"), { target: { value: "view" } });
    expect(spec().dropConfig![1]).toEqual({ dataType: "*", view: "tank-card", param: "tagPath" });

    fireEvent.click(within(drop).getByRole("button", { name: "Remove drop rule 1" }));
    fireEvent.click(within(drop).getByRole("button", { name: "Remove drop rule 1" }));
    expect(spec().dropConfig).toBeUndefined();
  });
});
