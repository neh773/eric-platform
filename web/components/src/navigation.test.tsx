import { createEvent, fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { createLinkedTransports, createSession, PageSession, parsePage, parseView, type HmiProject } from "@oip/hmi-runtime";
import {
  HorizontalMenu,
  itemAt,
  MenuTree,
  navEvents,
  parseNavItems,
  readExpanded,
  Tree,
  visibleRows,
  type NavExpandDetail,
  type NavSelectDetail,
} from "./navigation";
import { allowedComponentTypes, componentRegistry } from "./registry";

/** C-18 navigation components.
 */

const items = "Home = /, Plant > Area 1 > Pumps = /pumps, Plant > Area 1 > Tanks = /tanks, Plant > Area 2, Docs = https://example.com/docs";

/** Renders inside a wrapper that records clicks reaching it (the node's onClick in a session) and item events. */
function harness(ui: React.ReactElement) {
  const nodeClick = vi.fn();
  const selects: NavSelectDetail[] = [];
  const expands: NavExpandDetail[] = [];
  const result = render(
    <div data-testid="node" onClick={nodeClick}>
      {ui}
    </div>,
  );
  const node = result.getByTestId("node");
  node.addEventListener(navEvents.select, (event) => selects.push((event as CustomEvent<NavSelectDetail>).detail));
  node.addEventListener(navEvents.expand, (event) => expands.push((event as CustomEvent<NavExpandDetail>).detail));
  return { ...result, nodeClick, selects, expands };
}

describe("navigation items", () => {
  it("builds a hierarchy from text, merging shared parents", () => {
    const parsed = parseNavItems(items);
    expect(parsed.map((item) => item.label)).toEqual(["Home", "Plant", "Docs"]);
    expect(parsed[0]).toMatchObject({ target: "/", items: [] });
    expect(parsed[1]!.items.map((item) => item.label)).toEqual(["Area 1", "Area 2"]);
    expect(parsed[1]!.items[0]!.items.map((item) => [item.label, item.target])).toEqual([
      ["Pumps", "/pumps"],
      ["Tanks", "/tanks"],
    ]);
    expect(itemAt(parsed, "1/0/1")?.label).toBe("Tanks");
    expect(itemAt(parsed, "1/5")).toBeUndefined();
    expect(itemAt(parsed, "x")).toBeUndefined();
    expect(parseNavItems(" , = /nowhere, ")).toEqual([]);
  });

  it("reads a bound array with nested items, icons and disabled entries", () => {
    const parsed = parseNavItems([
      { label: "Overview", url: "/", icon: "home" },
      { text: "Lines", items: [{ label: "Line 1", target: "/line/1", enabled: false }, "Line 2", 7] },
      { target: "/no-label" },
    ]);
    expect(parsed).toHaveLength(2);
    expect(parsed[0]).toMatchObject({ label: "Overview", target: "/", icon: "home", enabled: true });
    expect(parsed[1]!.items.map((item) => [item.label, item.enabled])).toEqual([
      ["Line 1", false],
      ["Line 2", true],
    ]);
  });

  it("reads expanded paths and lists only visible rows", () => {
    expect(readExpanded("1, 1/0, bad, 1, /2")).toEqual(["1", "1/0"]);
    const parsed = parseNavItems(items);
    expect(visibleRows(parsed, new Set()).map((row) => row.path)).toEqual(["0", "1", "2"]);
    expect(visibleRows(parsed, new Set(["1", "1/0"])).map((row) => `${row.path}@${row.depth}`)).toEqual([
      "0@1",
      "1@1",
      "1/0@2",
      "1/0/0@3",
      "1/0/1@3",
      "1/1@2",
      "2@1",
    ]);
  });
});

describe("horizontal menu", () => {
  it("navigates a leaf through the node click and records the item path", () => {
    const onChange = vi.fn();
    const { nodeClick, selects } = harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={onChange} props={{ items, label: "Main" }} />);
    const bar = screen.getByRole("menubar", { name: "Main" });
    const home = within(bar).getByRole("menuitem", { name: "Home" });
    expect(home).toHaveAttribute("href", "/");
    const click = createEvent.click(home);
    fireEvent(home, click);
    expect(click.defaultPrevented).toBe(true);
    expect(onChange).toHaveBeenCalledWith("selected", "0");
    expect(selects).toEqual([{ path: "0", label: "Home", target: "/" }]);
    expect(nodeClick).toHaveBeenCalledTimes(1);
  });

  it("opens submenus without triggering the node's actions", () => {
    const onChange = vi.fn();
    const { nodeClick, selects, expands } = harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={onChange} props={{ items }} />);
    const plant = screen.getByRole("menuitem", { name: "Plant" });
    expect(plant).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(plant);
    expect(plant).toHaveAttribute("aria-expanded", "true");
    expect(expands).toEqual([{ path: "1", label: "Plant", expanded: true }]);
    fireEvent.click(screen.getByRole("menuitem", { name: "Area 1" }));
    const tanks = screen.getByRole("menuitem", { name: "Tanks" });
    expect(nodeClick).not.toHaveBeenCalled();
    fireEvent.click(tanks);
    expect(onChange).toHaveBeenCalledWith("selected", "1/0/1");
    expect(selects.at(-1)).toEqual({ path: "1/0/1", label: "Tanks", target: "/tanks" });
    expect(nodeClick).toHaveBeenCalledTimes(1);
    // A leaf closes the menu.
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("marks the selected branch, closes on Escape and leaves external links to the browser", () => {
    const onChange = vi.fn();
    harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={onChange} props={{ items, selected: "1/0/0" }} />);
    const plant = screen.getByRole("menuitem", { name: "Plant" });
    expect(plant).toHaveAttribute("data-current", "true");
    expect(screen.getByRole("menuitem", { name: "Home" })).not.toHaveAttribute("data-current");
    fireEvent.click(plant);
    fireEvent.keyDown(screen.getByRole("menuitem", { name: "Area 1" }), { key: "Escape" });
    expect(screen.queryByRole("menu")).toBeNull();
    expect(document.activeElement).toBe(plant);
    const docs = screen.getByRole("menuitem", { name: "Docs" });
    expect(docs).toHaveAttribute("href", "https://example.com/docs");
    expect(docs).toHaveAttribute("rel", "noopener noreferrer");
    const click = createEvent.click(docs);
    fireEvent(docs, click);
    expect(click.defaultPrevented).toBe(false);
  });

  it("does nothing for disabled menus, disabled items or blocked schemes", () => {
    const onChange = vi.fn();
    const { nodeClick, rerender } = harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={onChange} props={{ items, enabled: false }} />);
    fireEvent.click(screen.getByRole("menuitem", { name: "Home" }));
    expect(screen.getByRole("menuitem", { name: "Plant" })).toBeDisabled();
    rerender(
      <div data-testid="node" onClick={nodeClick}>
        <HorizontalMenu
          nodeId="m"
          locale="en-US"
          onChange={onChange}
          props={{ items: [{ label: "Off", target: "/off", enabled: false }, { label: "Script", target: "javascript:alert(1)" }] }}
        />
      </div>,
    );
    expect(screen.getByRole("menuitem", { name: "Script" })).not.toHaveAttribute("href");
    fireEvent.click(screen.getByRole("menuitem", { name: "Off" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Script" }));
    expect(onChange).not.toHaveBeenCalled();
    expect(nodeClick).not.toHaveBeenCalled();
  });

  it("moves focus along the bar with the arrow keys", () => {
    harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={() => {}} props={{ items }} />);
    const home = screen.getByRole("menuitem", { name: "Home" });
    home.focus();
    fireEvent.keyDown(home, { key: "ArrowRight" });
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "Plant" }));
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    expect(screen.getByRole("menu", { name: "Plant" })).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("menuitem", { name: "Plant" }), { key: "ArrowLeft" });
    expect(document.activeElement).toBe(home);
  });
});

describe("navigate hook", () => {
  it("sends each leaf to its own page path and leaves parents, external and empty targets alone", () => {
    const navigate = vi.fn();
    const { rerender } = harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={() => {}} navigate={navigate} props={{ items }} />);
    fireEvent.click(screen.getByRole("menuitem", { name: "Home" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Plant" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Area 1" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Tanks" }));
    const docs = screen.getByRole("menuitem", { name: "Docs" });
    docs.addEventListener("click", (event) => event.preventDefault());
    fireEvent.click(docs);
    expect(navigate.mock.calls).toEqual([["/"], ["/tanks"]]);
    rerender(
      <div data-testid="node">
        <MenuTree nodeId="t" locale="en-US" onChange={() => {}} navigate={navigate} props={{ items }} />
      </div>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Plant, 2 items" }));
    fireEvent.click(screen.getByRole("button", { name: "Area 1, 2 items" }));
    fireEvent.click(screen.getByRole("link", { name: "Pumps" }));
    expect(navigate).toHaveBeenLastCalledWith("/pumps");
    rerender(
      <div data-testid="node">
        <Tree nodeId="t" locale="en-US" onChange={() => {}} navigate={navigate} props={{ items, expanded: "1" }} />
      </div>,
    );
    fireEvent.click(screen.getByRole("treeitem", { name: "Area 2" }));
    expect(navigate).toHaveBeenCalledTimes(3);
    fireEvent.click(screen.getByRole("treeitem", { name: "Home" }));
    expect(navigate).toHaveBeenLastCalledWith("/");
    expect(navigate).toHaveBeenCalledTimes(4);
  });
});

describe("unsafe targets", () => {
  it("renders menu items with control-character or backslash targets without an href and never navigates", () => {
    const navigate = vi.fn();
    const onChange = vi.fn();
    const sneaky = ["/\t/x", "/\n/x", " //x", "/\\x"];
    const bound = sneaky.map((target, index) => ({ label: `Item ${index}`, target }));
    const { nodeClick, rerender } = harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={onChange} navigate={navigate} props={{ items: bound }} />);
    for (let index = 0; index < sneaky.length; index += 1) {
      const item = screen.getByRole("menuitem", { name: `Item ${index}` });
      expect(item, sneaky[index]).not.toHaveAttribute("href");
      fireEvent.click(item);
    }
    rerender(
      <div data-testid="node" onClick={nodeClick}>
        <MenuTree nodeId="t" locale="en-US" onChange={onChange} navigate={navigate} props={{ items: bound }} />
      </div>,
    );
    for (let index = 0; index < sneaky.length; index += 1) {
      const item = screen.getByText(`Item ${index}`).closest("a")!;
      expect(item, sneaky[index]).not.toHaveAttribute("href");
      fireEvent.click(item);
    }
    rerender(
      <div data-testid="node" onClick={nodeClick}>
        <Tree nodeId="t" locale="en-US" onChange={() => {}} navigate={navigate} props={{ items: bound }} />
      </div>,
    );
    for (let index = 0; index < sneaky.length; index += 1) fireEvent.click(screen.getByRole("treeitem", { name: `Item ${index}` }));
    expect(navigate).not.toHaveBeenCalled();
    expect(onChange).not.toHaveBeenCalled();
    expect(nodeClick).toHaveBeenCalledTimes(sneaky.length);
  });

  it("prefixes page hrefs with the session base", () => {
    harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={() => {}} pageHref={(path) => `/hmi${path}`} props={{ items: "Pumps = /pumps, Docs = https://example.com" }} />);
    expect(screen.getByRole("menuitem", { name: "Pumps" })).toHaveAttribute("href", "/hmi/pumps");
    expect(screen.getByRole("menuitem", { name: "Docs" })).toHaveAttribute("href", "https://example.com");
  });
});

describe("menu keyboard support", () => {
  const keyItems = "Home = /, Plant > Area 1 > Pumps = /pumps, Plant > Area 1 > Tanks = /tanks, Plant > Notes, Plant > Area 2 = /a2, Log";

  it("keeps one tab stop on the menubar and activates leaves without a target with Enter or Space", () => {
    const onChange = vi.fn();
    harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={onChange} props={{ items: keyItems }} />);
    const bar = screen.getByRole("menubar");
    const stops = within(bar).getAllByRole("menuitem").filter((item) => item.tabIndex === 0);
    expect(stops.map((item) => item.textContent)).toEqual(["Home"]);
    const log = screen.getByRole("menuitem", { name: "Log" });
    expect(log).not.toHaveAttribute("href");
    const home = screen.getByRole("menuitem", { name: "Home" });
    home.focus();
    fireEvent.keyDown(home, { key: "End" });
    expect(document.activeElement).toBe(log);
    expect(log.tabIndex).toBe(0);
    expect(screen.getByRole("menuitem", { name: "Home" }).tabIndex).toBe(-1);
    fireEvent.keyDown(log, { key: "Enter" });
    expect(onChange).toHaveBeenLastCalledWith("selected", "2");
    fireEvent.keyDown(log, { key: " " });
    expect(onChange).toHaveBeenCalledTimes(2);
    fireEvent.keyDown(log, { key: "Home" });
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "Home" }));
    fireEvent.keyDown(document.activeElement!, { key: "End" });
    expect(document.activeElement).toBe(log);
  });

  it("opens submenus into focus, moves within them, and closes one level at a time", () => {
    const onChange = vi.fn();
    harness(<HorizontalMenu nodeId="m" locale="en-US" onChange={onChange} props={{ items: keyItems }} />);
    const plant = screen.getByRole("menuitem", { name: "Plant" });
    plant.focus();
    fireEvent.keyDown(plant, { key: "ArrowDown" });
    const area1 = screen.getByRole("menuitem", { name: "Area 1" });
    expect(document.activeElement).toBe(area1);
    fireEvent.keyDown(area1, { key: "ArrowDown" });
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "Notes" }));
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    expect(document.activeElement).toBe(area1);
    fireEvent.keyDown(area1, { key: "ArrowUp" });
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "Area 2" }));
    fireEvent.keyDown(document.activeElement!, { key: "Home" });
    fireEvent.keyDown(area1, { key: "ArrowRight" });
    const pumps = screen.getByRole("menuitem", { name: "Pumps" });
    expect(document.activeElement).toBe(pumps);
    fireEvent.keyDown(pumps, { key: "ArrowLeft" });
    expect(screen.queryByRole("menu", { name: "Area 1" })).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "Area 1" }));
    fireEvent.keyDown(document.activeElement!, { key: "ArrowLeft" });
    expect(screen.queryByRole("menu")).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole("menuitem", { name: "Plant" }));
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    fireEvent.keyDown(document.activeElement!, { key: "Enter" });
    expect(onChange).toHaveBeenLastCalledWith("selected", "1/1");
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it("keeps focus in the menu tree when drilling in and stepping back", () => {
    const onChange = vi.fn();
    harness(<MenuTree nodeId="t" locale="en-US" onChange={onChange} props={{ items: keyItems }} />);
    const log = screen.getByText("Log").closest("a")!;
    expect(log).not.toHaveAttribute("href");
    expect(log.tabIndex).toBe(0);
    fireEvent.keyDown(log, { key: "Enter" });
    expect(onChange).toHaveBeenLastCalledWith("selected", "2");
    fireEvent.click(screen.getByRole("button", { name: "Plant, 3 items" }));
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Area 1, 2 items" }));
    fireEvent.click(screen.getByRole("button", { name: "Area 1, 2 items" }));
    expect(document.activeElement).toBe(screen.getByRole("link", { name: "Pumps" }));
    fireEvent.click(screen.getByRole("button", { name: /Back/ }));
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Area 1, 2 items" }));
    fireEvent.click(screen.getByRole("button", { name: /Back/ }));
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Plant, 3 items" }));
  });
});

describe("menu tree", () => {
  it("drills into a level, steps back, and navigates leaves through the node click", () => {
    const onChange = vi.fn();
    const { nodeClick, selects, expands } = harness(<MenuTree nodeId="t" locale="en-US" onChange={onChange} props={{ items, label: "Site" }} />);
    const nav = screen.getByRole("navigation", { name: "Site" });
    expect(within(nav).getAllByRole("listitem")).toHaveLength(3);
    fireEvent.click(screen.getByRole("button", { name: "Plant, 2 items" }));
    expect(nodeClick).not.toHaveBeenCalled();
    expect(expands).toEqual([{ path: "1", label: "Plant", expanded: true }]);
    fireEvent.click(screen.getByRole("button", { name: "Area 1, 2 items" }));
    expect(nav).toHaveAttribute("data-level", "1/0");
    expect(screen.getByRole("list", { name: "Area 1" })).toBeInTheDocument();
    const pumps = screen.getByRole("link", { name: "Pumps" });
    fireEvent.click(pumps);
    expect(onChange).toHaveBeenCalledWith("selected", "1/0/0");
    expect(selects).toEqual([{ path: "1/0/0", label: "Pumps", target: "/pumps" }]);
    expect(nodeClick).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: /Back/ }));
    expect(expands.at(-1)).toEqual({ path: "1/0", label: "Area 1", expanded: false });
    expect(nav).toHaveAttribute("data-level", "1");
    fireEvent.click(screen.getByRole("button", { name: /Back/ }));
    expect(nav).toHaveAttribute("data-level", "");
    expect(screen.queryByRole("button", { name: /Back/ })).toBeNull();
    expect(nodeClick).toHaveBeenCalledTimes(1);
  });
});

describe("tree", () => {
  it("fires expand and select events with the item path", () => {
    const onChange = vi.fn();
    const { nodeClick, selects, expands } = harness(<Tree nodeId="t" locale="en-US" onChange={onChange} props={{ items, label: "Assets" }} />);
    const tree = screen.getByRole("tree", { name: "Assets" });
    expect(within(tree).getAllByRole("treeitem")).toHaveLength(3);
    const plant = screen.getByRole("treeitem", { name: "Plant" });
    expect(plant).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(plant.querySelector(".oip-tree-twisty")!);
    expect(plant).toHaveAttribute("aria-expanded", "true");
    expect(onChange).toHaveBeenCalledWith("expanded", "1");
    expect(expands).toEqual([{ path: "1", label: "Plant", expanded: true }]);
    expect(nodeClick).not.toHaveBeenCalled();
    const area2 = screen.getByRole("treeitem", { name: "Area 2" });
    expect(area2).toHaveAttribute("aria-level", "2");
    fireEvent.click(area2);
    expect(onChange).toHaveBeenCalledWith("selected", "1/1");
    expect(selects).toEqual([{ path: "1/1", label: "Area 2", target: "" }]);
    expect(nodeClick).toHaveBeenCalledTimes(1);
  });

  it("collapses a branch together with its descendants and follows bound props", () => {
    const onChange = vi.fn();
    const { rerender, expands } = harness(<Tree nodeId="t" locale="en-US" onChange={onChange} props={{ items, expanded: "1, 1/0", selected: "1/0/1" }} />);
    expect(screen.getByRole("treeitem", { name: "Tanks" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("treeitem", { name: "Tanks" })).toHaveAttribute("tabindex", "0");
    fireEvent.click(screen.getByRole("treeitem", { name: "Plant" }).querySelector(".oip-tree-twisty")!);
    expect(onChange).toHaveBeenLastCalledWith("expanded", "");
    expect(expands).toEqual([{ path: "1", label: "Plant", expanded: false }]);
    expect(screen.queryByRole("treeitem", { name: "Area 1" })).toBeNull();
    rerender(
      <div data-testid="node">
        <Tree nodeId="t" locale="en-US" onChange={onChange} props={{ items, expanded: "1" }} />
      </div>,
    );
    expect(screen.getByRole("treeitem", { name: "Area 1" })).toHaveAttribute("aria-expanded", "false");
  });

  it("supports the tree keyboard pattern", () => {
    const onChange = vi.fn();
    const { selects, nodeClick } = harness(<Tree nodeId="t" locale="en-US" onChange={onChange} props={{ items }} />);
    const home = screen.getByRole("treeitem", { name: "Home" });
    expect(home).toHaveAttribute("tabindex", "0");
    home.focus();
    fireEvent.keyDown(home, { key: "ArrowDown" });
    const plant = screen.getByRole("treeitem", { name: "Plant" });
    expect(document.activeElement).toBe(plant);
    fireEvent.keyDown(plant, { key: "ArrowRight" });
    expect(plant).toHaveAttribute("aria-expanded", "true");
    fireEvent.keyDown(plant, { key: "ArrowRight" });
    const area1 = screen.getByRole("treeitem", { name: "Area 1" });
    expect(document.activeElement).toBe(area1);
    fireEvent.keyDown(area1, { key: "ArrowLeft" });
    expect(document.activeElement).toBe(plant);
    fireEvent.keyDown(plant, { key: "ArrowLeft" });
    expect(plant).toHaveAttribute("aria-expanded", "false");
    fireEvent.keyDown(plant, { key: "End" });
    const docs = screen.getByRole("treeitem", { name: "Docs" });
    expect(document.activeElement).toBe(docs);
    fireEvent.keyDown(docs, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("selected", "2");
    expect(selects.at(-1)?.path).toBe("2");
    expect(nodeClick).toHaveBeenCalledTimes(1);
  });

  it("ignores clicks when disabled", () => {
    const onChange = vi.fn();
    const { nodeClick } = harness(<Tree nodeId="t" locale="en-US" onChange={onChange} props={{ items, enabled: false }} />);
    const plant = screen.getByRole("treeitem", { name: "Plant" });
    fireEvent.click(plant.querySelector(".oip-tree-twisty")!);
    fireEvent.click(plant);
    expect(plant).toHaveAttribute("aria-expanded", "false");
    expect(onChange).not.toHaveBeenCalled();
    expect(nodeClick).not.toHaveBeenCalled();
  });
});

describe("menu navigation through the page runtime", () => {
  function project(withAction: boolean): HmiProject {
    const view = (path: string, menu: Record<string, unknown>, events: unknown[]) =>
      parseView(
        {
          apiVersion: "oip.hmi/v1",
          kind: "View",
          metadata: { name: path, path },
          spec: {
            params: {},
            width: 600,
            height: 400,
            root: {
              id: "root",
              kind: "container",
              name: "Root",
              layout: "flex",
              props: { direction: "column" },
              children: [{ id: "menu", kind: "component", name: "Menu", type: "horizontal-menu", props: menu, events }],
            },
          },
        },
        { allowedTypes: allowedComponentTypes() },
      );
    const toPumps = withAction ? [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] }] : [];
    const page = (name: string, url: string, primaryView: string) =>
      parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name }, spec: { url, primaryView, docks: [] } });
    return {
      name: "Nav",
      views: [view("home", { label: "Main", items: "Plant > Pumps = /pumps, Plant > Line 3 = /line/3?mode=auto, Home = /" }, toPumps), view("other", { label: "Other" }, [])],
      pages: [page("Home", "/", "home"), page("Pumps", "/pumps", "other"), page("Line", "/line/:id", "other")],
      sharedDocks: [],
      cornerPriority: "top-bottom",
    };
  }

  it("builds page hrefs with the PageSession base path", async () => {
    const [transport] = createLinkedTransports();
    render(
      <PageSession project={project(false)} registry={componentRegistry()} transport={transport} session={createSession({ id: "b", locale: "en-US" })} location="/" basePath="/hmi" />,
    );
    fireEvent.click(await screen.findByRole("menuitem", { name: "Plant" }));
    expect(screen.getByRole("menuitem", { name: "Pumps" })).toHaveAttribute("href", "/hmi/pumps");
    expect(screen.getByRole("menuitem", { name: "Home" })).toHaveAttribute("href", "/hmi/");
  });

  function mount(withAction: boolean) {
    const onLocationChange = vi.fn();
    const [transport] = createLinkedTransports();
    const session = createSession({ id: "nav", theme: "daylight", locale: "en-US" });
    render(
      <PageSession project={project(withAction)} registry={componentRegistry()} transport={transport} session={session} location="/" onLocationChange={onLocationChange} />,
    );
    return onLocationChange;
  }

  it("sends two items of one menu to two different pages", async () => {
    const onLocationChange = mount(false);
    fireEvent.click(await screen.findByRole("menuitem", { name: "Plant" }));
    expect(onLocationChange).not.toHaveBeenCalled();
    const pumps = screen.getByRole("menuitem", { name: "Pumps" });
    const click = createEvent.click(pumps);
    fireEvent(pumps, click);
    expect(click.defaultPrevented).toBe(true);
    expect(onLocationChange.mock.calls).toEqual([["/pumps"]]);
    fireEvent.click(screen.getByRole("menuitem", { name: "Plant" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Line 3" }));
    expect(onLocationChange.mock.calls).toEqual([["/pumps"], ["/line/3?mode=auto"]]);
  });

  it("still runs the node's onClick action, without a second history entry for the same page", async () => {
    const onLocationChange = mount(true);
    fireEvent.click(await screen.findByRole("menuitem", { name: "Plant" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Pumps" }));
    expect(onLocationChange.mock.calls).toEqual([["/pumps"]]);
    // The item navigates first; the node's action runs after it, as it did before the hook existed.
    fireEvent.click(screen.getByRole("menuitem", { name: "Home" }));
    expect(onLocationChange.mock.calls).toEqual([["/pumps"], ["/"], ["/pumps"]]);
  });

  it("ignores a page path that matches no page", async () => {
    const onLocationChange = vi.fn();
    const [transport] = createLinkedTransports();
    const base = project(false);
    const broken = { ...base, views: [parseView(rawHome, { allowedTypes: allowedComponentTypes() }), base.views[1]!] };
    render(<PageSession project={broken} registry={componentRegistry()} transport={transport} session={createSession({ id: "n", locale: "en-US" })} location="/" onLocationChange={onLocationChange} />);
    fireEvent.click(await screen.findByRole("menuitem", { name: "Nowhere" }));
    expect(onLocationChange).not.toHaveBeenCalled();
  });
});

const rawHome = {
  apiVersion: "oip.hmi/v1",
  kind: "View",
  metadata: { name: "home", path: "home" },
  spec: {
    params: {},
    width: 600,
    height: 400,
    root: {
      id: "root",
      kind: "container",
      name: "Root",
      layout: "flex",
      props: { direction: "column" },
      children: [{ id: "menu", kind: "component", name: "Menu", type: "horizontal-menu", props: { label: "Main", items: "Nowhere = /missing/page/deep" } }],
    },
  },
};
