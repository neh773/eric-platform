import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { createLinkedTransports, createSession, parseView, themes, ViewRenderer } from "@oip/hmi-runtime";
import { allowedComponentTypes, componentModules, componentRegistry, defaultsFromSchema } from "../registry";
import {
  CylindricalTank,
  equipmentVisual,
  levelAlarm,
  levelPercent,
  Motor,
  Pump,
  Sensor,
  symbolState,
  symbolStates,
  symbolVisual,
  symbolVisuals,
  Valve,
  Vessel,
} from "./index";

/** C-18 symbols.
 */

const noop = () => {};

function body(container: HTMLElement): SVGElement {
  return container.querySelector(".oip-symbol-body") as SVGElement;
}

describe("symbol state", () => {
  it("maps names, aliases, booleans and codes to states", () => {
    expect(symbolState("running")).toBe("running");
    expect(symbolState("Open")).toBe("running");
    expect(symbolState(" closed ")).toBe("stopped");
    expect(symbolState("Stopping")).toBe("transition");
    expect(symbolState("TRIPPED")).toBe("fault");
    expect(symbolState(true)).toBe("running");
    expect(symbolState(false)).toBe("stopped");
    expect([0, 1, 2, 3].map(symbolState)).toEqual(["stopped", "running", "transition", "fault"]);
    expect(symbolState("2")).toBe("transition");
    expect(symbolState(4)).toBe("unknown");
    expect(symbolState(1.5)).toBe("unknown");
    expect(symbolState("sideways")).toBe("unknown");
    expect(symbolState(null)).toBe("unknown");
    expect(symbolState(undefined)).toBe("unknown");
  });

  it("keeps color for abnormal states and greys for normal ones", () => {
    expect(symbolStates).toEqual(["running", "stopped", "transition", "fault", "unknown"]);
    for (const state of symbolStates) {
      const visual = symbolVisual(state);
      const tokens = [visual.fill, visual.stroke];
      for (const token of tokens) expect(token, state).toMatch(/^var\(--oip-color-[a-z-]+\)$/);
      const colored = tokens.some((token) => /alarm|warning|ok|accent/.test(token));
      expect(colored, state).toBe(visual.abnormal);
    }
    expect(symbolVisuals.fault).toMatchObject({ stroke: "var(--oip-color-alarm)", badge: "!", abnormal: true });
    expect(symbolVisuals.running.fill).toBe("var(--oip-color-muted)");
    expect(symbolVisuals.stopped.fill).toBe("var(--oip-color-surface)");
    expect(symbolVisuals.transition.dash).not.toBe("");
    expect(symbolVisuals.unknown.badge).toBe("?");
  });

  it("colors running and stopped equipment by default and keeps greys in the muted palette", () => {
    // U-1: a pump added from the palette (state "stopped", palette "state") must not draw in greys.
    expect(equipmentVisual("running", "state").fill).toBe("var(--oip-color-running)");
    expect(equipmentVisual("stopped", undefined).fill).toBe("var(--oip-color-stopped)");
    expect(equipmentVisual("running", "muted")).toBe(symbolVisuals.running);
    expect(equipmentVisual("stopped", "muted")).toBe(symbolVisuals.stopped);
    for (const state of ["transition", "fault", "unknown"] as const) expect(equipmentVisual(state, "state")).toBe(symbolVisuals[state]);
    expect(equipmentVisual("fault", "state").badge).toBe("!");
    // Every theme defines both colors, and neither is one of the greys.
    for (const theme of Object.values(themes)) {
      for (const token of ["color-running", "color-stopped"] as const) {
        expect(theme[token]).toMatch(/^#[0-9a-f]{6}$/);
        expect([theme["color-muted"], theme["color-surface"], theme["color-idle"], theme["color-line"]]).not.toContain(theme[token]);
      }
    }
  });

  it("clamps the level to 0-100 percent and flags limit crossings", () => {
    expect(levelPercent(50)).toBe(50);
    expect(levelPercent(150)).toBe(100);
    expect(levelPercent(-20)).toBe(0);
    expect(levelPercent(5, 0, 10)).toBe(50);
    expect(levelPercent(30, 10, 20)).toBe(100);
    expect(levelPercent(5, 10, 10)).toBe(0);
    expect(levelPercent(Number.NaN)).toBeNull();
    expect(levelPercent("45")).toBe(45);
    expect(levelPercent(" 120 ")).toBe(100);
    expect(levelPercent("")).toBeNull();
    expect(levelPercent("full")).toBeNull();
    expect(levelPercent(null)).toBeNull();
    expect(levelAlarm(95, 10, 90)).toBe("high");
    expect(levelAlarm(5, 10, 90)).toBe("low");
    expect(levelAlarm(50, 10, 90)).toBe("");
    expect(levelAlarm(100, 0, 100)).toBe("");
    expect(levelAlarm(0, 0, 100)).toBe("");
    expect(levelAlarm(null, 10, 90)).toBe("");
  });
});

describe("symbol components", () => {
  it("draws each state with its documented visual, in either palette", () => {
    for (const Symbol of [Pump, Motor, Valve]) {
      for (const [value, state, palette] of [
        ["running", "running", undefined],
        [0, "stopped", undefined],
        ["running", "running", "muted"],
        [0, "stopped", "muted"],
        ["transition", "transition", undefined],
        ["fault", "fault", undefined],
        ["??", "unknown", "muted"],
      ] as const) {
        const { container, unmount } = render(<Symbol nodeId="s" locale="en-US" onChange={noop} props={{ state: value, label: "P-101", palette }} />);
        const root = container.querySelector(".oip-symbol")!;
        expect(root).toHaveAttribute("data-state", state);
        const visual = equipmentVisual(state, palette);
        const shape = body(container);
        expect(shape.style.fill).toBe(visual.fill);
        expect(shape.style.stroke).toBe(visual.stroke);
        expect(shape.style.strokeDasharray).toBe(visual.dash);
        const badge = container.querySelector(".oip-symbol-badge");
        if (visual.badge) expect(badge).toHaveAttribute("data-badge", visual.badge);
        else expect(badge).toBeNull();
        expect(root.hasAttribute("data-abnormal")).toBe(visual.abnormal);
        unmount();
      }
    }
  });

  it("names the symbol and its state for assistive technology", () => {
    render(
      <div>
        <Pump nodeId="a" locale="en-US" onChange={noop} props={{ state: "running", label: "P-101" }} />
        <Valve nodeId="b" locale="en-US" onChange={noop} props={{ state: true, label: "XV-7" }} />
        <Valve nodeId="c" locale="en-US" onChange={noop} props={{ state: "closed", label: "XV-8", orientation: "vertical" }} />
        <Motor nodeId="d" locale="en-US" onChange={noop} props={{ state: 3, label: "M-2" }} />
      </div>,
    );
    expect(screen.getByRole("img", { name: "P-101: running" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "XV-7: open" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "XV-8: closed" })).toHaveAttribute("data-orientation", "vertical");
    expect(screen.getByRole("img", { name: "M-2: fault" })).toHaveAttribute("data-abnormal", "true");
  });

  it("fills vessels and tanks to the clamped level", () => {
    const { rerender, container } = render(
      <CylindricalTank nodeId="t" locale="en-US" onChange={noop} props={{ level: 40, label: "T-1" }} />,
    );
    const tank = screen.getByRole("img", { name: "T-1: level 40%" });
    expect(tank).toHaveAttribute("data-level", "40.0");
    // The shell spans y 16..84, so 40% puts the surface at 84 - 68 * 0.4.
    expect(container.querySelector(".oip-symbol-surface")).toHaveAttribute("cy", String(84 - 68 * 0.4));
    rerender(<CylindricalTank nodeId="t" locale="en-US" onChange={noop} props={{ level: 140, label: "T-1" }} />);
    expect(screen.getByRole("img")).toHaveAttribute("data-level", "100.0");
    expect(container.querySelector(".oip-symbol-surface")).toHaveAttribute("cy", "16");
    rerender(<CylindricalTank nodeId="t" locale="en-US" onChange={noop} props={{ level: -3, label: "T-1" }} />);
    expect(screen.getByRole("img")).toHaveAttribute("data-level", "0.0");
    expect(container.querySelector(".oip-symbol-liquid")).toBeNull();
    rerender(<CylindricalTank nodeId="t" locale="en-US" onChange={noop} props={{ label: "T-1" }} />);
    expect(screen.getByRole("img", { name: "T-1: level no value" })).toHaveAttribute("data-level", "");
    rerender(<CylindricalTank nodeId="t" locale="en-US" onChange={noop} props={{ level: 3.2, min: 0, max: 4, unit: "m", decimals: 1, label: "T-1" }} />);
    expect(screen.getByRole("img", { name: "T-1: level 3.2 m" })).toHaveAttribute("data-level", "80.0");

    rerender(<Vessel nodeId="v" locale="en-US" onChange={noop} props={{ level: 250, max: 200, label: "V-1" }} />);
    expect(screen.getByRole("img", { name: "V-1: level 100%" })).toHaveAttribute("data-level", "100.0");
    const liquid = container.querySelector(".oip-symbol-liquid")!;
    expect(liquid).toHaveAttribute("y", "8");
    expect(liquid).toHaveAttribute("height", "80");
    rerender(<Vessel nodeId="v" locale="en-US" onChange={noop} props={{ level: -10, label: "V-1" }} />);
    expect(container.querySelector(".oip-symbol-liquid")).toHaveAttribute("height", "0");
  });

  it("colors the liquid only when a level limit is crossed", () => {
    const { rerender, container } = render(
      <CylindricalTank nodeId="t" locale="en-US" onChange={noop} props={{ level: 50, alarmHigh: 90, alarmLow: 10, label: "T-1" }} />,
    );
    const liquid = () => container.querySelector(".oip-symbol-liquid") as SVGElement;
    expect(liquid().style.fill).toBe("var(--oip-color-idle)");
    expect(screen.getByRole("img")).not.toHaveAttribute("data-alarm");
    rerender(<CylindricalTank nodeId="t" locale="en-US" onChange={noop} props={{ level: 95, alarmHigh: 90, alarmLow: 10, label: "T-1" }} />);
    expect(liquid().style.fill).toBe("var(--oip-color-warning)");
    expect(screen.getByRole("img", { name: "T-1: level 95%, high level" })).toHaveAttribute("data-alarm", "high");
    rerender(<Vessel nodeId="v" locale="en-US" onChange={noop} props={{ level: 5, alarmLow: 10, state: "fault", label: "V-1" }} />);
    expect(screen.getByRole("img", { name: "V-1: level 5%, low level, fault" })).toHaveAttribute("data-state", "fault");
  });

  it("shows a sensor reading with its function letters", () => {
    const { rerender } = render(
      <Sensor nodeId="s" locale="de-DE" onChange={noop} props={{ tag: "PT", value: 4.25, unit: "bar", decimals: 2, label: "PT-12" }} />,
    );
    expect(screen.getByRole("img", { name: "PT-12: 4,25 bar" })).toHaveAttribute("data-state", "stopped");
    expect(screen.getByText("PT")).toBeInTheDocument();
    rerender(<Sensor nodeId="s" locale="en-US" onChange={noop} props={{ tag: "TT", state: "fault", label: "TT-3" }} />);
    expect(screen.getByRole("img", { name: "TT-3: no value, fault" })).toHaveAttribute("data-abnormal", "true");
  });
});

const expectedDefaults: Record<string, Record<string, unknown>> = {
  pump: { state: "stopped", label: "Pump", palette: "state", direction: "right" },
  valve: { state: "stopped", label: "Valve", palette: "state", orientation: "horizontal", actuator: true },
  motor: { state: "stopped", label: "Motor", palette: "state", direction: "right" },
  vessel: { state: "normal", label: "Vessel", level: 0, min: 0, max: 100, alarmLow: 0, alarmHigh: 100, showLevel: true },
  sensor: { state: "normal", label: "Sensor", tag: "PT", unit: "", decimals: 1 },
  "cylindrical-tank": {
    state: "normal",
    label: "Tank",
    level: 0,
    min: 0,
    max: 100,
    alarmLow: 0,
    alarmHigh: 100,
    showLevel: true,
    unit: "",
    decimals: 0,
  },
  "horizontal-menu": { label: "Main menu", items: "Home = /, Pumps = /pumps", selected: "", align: "start", enabled: true },
  "menu-tree": { label: "Menu", items: "Home = /, Pumps = /pumps", selected: "", backLabel: "Back", enabled: true },
  tree: { label: "Tree", items: "Plant > Area 1, Plant > Area 2", selected: "", expanded: "", showLines: true, enabled: true },
};

describe("C-18 registry and schemas", () => {
  it("appends the nine components with closed schemas and the expected defaults", () => {
    const registry = componentRegistry();
    const order = componentModules.map((module) => module.type);
    expect(order.slice(-Object.keys(expectedDefaults).length)).toEqual(Object.keys(expectedDefaults));
    for (const [type, defaults] of Object.entries(expectedDefaults)) {
      const module = registry.get(type)!;
      expect(allowedComponentTypes().has(type)).toBe(true);
      expect(module.schema.additionalProperties).toBe(false);
      expect(module.schema.title).toBe(module.title);
      expect(defaultsFromSchema(module.schema)).toEqual(defaults);
      for (const [key, child] of Object.entries(module.schema.properties ?? {})) {
        expect(["string", "number", "integer", "boolean"], `${type}.${key}`).toContain(child.type);
        expect(child.title, `${type}.${key}`).toBeTruthy();
        if (child.enum && child.default !== undefined) expect(child.enum).toContain(child.default);
      }
    }
  });

  it("renders every new component's defaults in a session under both themes", () => {
    for (const theme of ["daylight", "nightwatch"]) {
      const view = parseView(
        {
          apiVersion: "oip.hmi/v1",
          kind: "View",
          metadata: { name: "All", path: "all" },
          spec: {
            params: {},
            width: 800,
            height: 600,
            root: {
              id: "root",
              kind: "container",
              name: "Root",
              layout: "flex",
              props: { direction: "column" },
              children: Object.keys(expectedDefaults).map((type) => ({
                id: type,
                kind: "component",
                name: type,
                type,
                props: defaultsFromSchema(componentRegistry().get(type)!.schema),
              })),
            },
          },
        },
        { allowedTypes: allowedComponentTypes() },
      );
      const [transport] = createLinkedTransports();
      const { unmount } = render(
        <ViewRenderer view={view} registry={componentRegistry()} transport={transport} session={createSession({ id: theme, theme, locale: "en-US" })} />,
      );
      expect(screen.queryAllByRole("alert")).toHaveLength(0);
      for (const type of Object.keys(expectedDefaults)) {
        expect(document.querySelector(`[data-component='${type}']`)?.children.length, type).toBeGreaterThan(0);
      }
      expect(screen.getByRole("img", { name: "Pump: stopped" })).toBeInTheDocument();
      expect(screen.getByRole("menubar", { name: "Main menu" })).toBeInTheDocument();
      expect(screen.getByRole("tree", { name: "Tree" })).toBeInTheDocument();
      unmount();
    }
  });
});
