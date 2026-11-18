import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { DockHost, PanelRestore, useDockLayout } from "./DockHost";
import { STORAGE_KEY, defaultLayout, hidePanel, moveToDock, parseLayout, showPanel, toggleCollapsed } from "./dockLayout";

/** DES-010: panels move between docks, collapse, hide, and the layout is kept per browser. */

function Harness() {
  const [layout, setLayout] = useDockLayout();
  return (
    <>
      <PanelRestore layout={layout} onLayout={setLayout} />
      <DockHost
        layout={layout}
        onLayout={setLayout}
        panels={{ project: <p>tree</p>, outline: <p>nodes</p>, inspector: <p>props</p>, tools: <p>tabs</p> }}
      >
        <section aria-label="Canvas" />
      </DockHost>
    </>
  );
}

beforeEach(() => window.localStorage.clear());

describe("dock layout model", () => {
  it("moves a panel to the end of another dock and shows a hidden one again", () => {
    const moved = moveToDock(defaultLayout(), "inspector", "bottom");
    expect(moved.right).toEqual([]);
    expect(moved.left).toEqual(["project", "outline", "tools"]);
    expect(moved.bottom).toEqual(["inspector"]);
    const hidden = hidePanel(moved, "inspector");
    expect(hidden.bottom).toEqual([]);
    expect(hidden.hidden).toEqual(["inspector"]);
    expect(showPanel(hidden, "inspector").right).toEqual(["inspector"]);
    expect(toggleCollapsed(toggleCollapsed(defaultLayout(), "outline"), "outline").collapsed).toEqual([]);
  });

  it("falls back to the default for malformed storage and adds panels a saved layout lacks", () => {
    expect(parseLayout("not json")).toEqual(defaultLayout());
    expect(parseLayout(null)).toEqual(defaultLayout());
    const old = parseLayout(JSON.stringify({ left: ["inspector", "bogus", "inspector"], bottom: ["project"] }));
    expect(old.left).toEqual(["inspector"]);
    expect(old.bottom).toEqual(["project"]);
    expect([...old.right].sort()).toEqual(["outline", "tools"]);
  });
});

describe("dock host", () => {
  it("moves, collapses and hides panels and restores them", () => {
    render(<Harness />);
    const right = () => screen.getByRole("group", { name: "Right dock" });
    expect(within(right()).getByText("props")).toBeTruthy();
    expect(within(screen.getByRole("group", { name: "Left dock" })).getByText("tree")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Dock Property editor bottom" }));
    expect(screen.queryByRole("group", { name: "Right dock" })).toBeNull();
    expect(within(screen.getByRole("group", { name: "Bottom dock" })).getByText("props")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Collapse Outline" }));
    expect(screen.getByText("nodes").parentElement?.hidden).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Expand Outline" }));
    expect(screen.getByText("nodes").parentElement?.hidden).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Hide Project browser" }));
    expect(screen.queryByText("tree")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show Project browser" }));
    expect(screen.getByText("tree")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Reset layout" }));
    expect(within(right()).getByText("props")).toBeTruthy();
    expect(screen.queryByRole("group", { name: "Bottom dock" })).toBeNull();
  });

  it("keeps the layout in this browser across a remount", () => {
    const first = render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "Dock Property editor left" }));
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? "{}").left).toContain("inspector");
    first.unmount();
    render(<Harness />);
    expect(within(screen.getByRole("group", { name: "Left dock" })).getByText("props")).toBeTruthy();
    expect(screen.queryByRole("group", { name: "Right dock" })).toBeNull();
  });
});
