import { act, createEvent, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createLinkedTransports,
  createSession,
  MemoryTagServer,
  PageSession,
  parsePage,
  parseView,
  ViewRenderer,
  type HmiProject,
  type ViewDocument,
} from "@oip/hmi-runtime";
import {
  DateTimeInput,
  DateTimePicker,
  FileUpload,
  fromInputString,
  Link,
  linkTarget,
  monthGrid,
  MultiStateButton,
  OneShotButton,
  PasswordField,
  RadioGroup,
  TextArea,
  toInputString,
} from "./inputs2";
import { allowedComponentTypes, componentModules, componentRegistry, defaultsFromSchema } from "./registry";
import { iconNames } from "./icons";
import { GatewayClient, GatewayProvider } from "./gateway";

/** C-11 input components, schemas and runtime wiring.
 */

const noop = () => {};

afterEach(() => {
  vi.useRealTimers();
});

const expectedDefaults: Record<string, Record<string, unknown>> = {
  "simple-gauge": { value: 0, min: 0, max: 100, label: "Gauge", unit: "", decimals: 1, sweep: 180, thickness: 10, color: "accent" },
  "progress-bar": { value: 0, min: 0, max: 100, label: "Progress", showValue: true, valueFormat: "percent", indeterminate: false, color: "accent" },
  icon: { name: "circle", color: "", label: "", strokeWidth: 2 },
  sparkline: { points: "", label: "Sparkline", autoScale: true, min: 0, max: 100, showLast: true, decimals: 1, color: "accent", historyPath: "" },
  "datetime-input": { label: "Date and time", mode: "datetime", enabled: true },
  "datetime-picker": { label: "Pick a date", showTime: true, weekStart: 1, enabled: true },
  "password-field": { value: "", label: "Password", placeholder: "", enabled: true },
  "radio-group": { value: "", label: "Choose one", options: "a:Option A, b:Option B", orientation: "row", enabled: true },
  "text-area": { value: "", label: "Notes", placeholder: "", rows: 4, maxLength: 2000, deferUpdates: true, enabled: true },
  "one-shot-button": { value: 0, writeValue: 1, text: "Start", writingText: "Writing...", timeoutMs: 5000, enabled: true },
  "multi-state-button": { value: 0, label: "Mode", states: "0:Off, 1:Hand, 2:Auto", orientation: "row", enabled: true },
  link: { text: "Link", url: "/", newTab: false, enabled: true },
};

describe("C-11 registry and schemas", () => {
  it("registers all twelve components with a closed object schema and the expected defaults", () => {
    const registry = componentRegistry();
    const types = allowedComponentTypes();
    for (const [type, defaults] of Object.entries(expectedDefaults)) {
      const module = registry.get(type);
      expect(module, type).toBeDefined();
      expect(types.has(type)).toBe(true);
      expect(module!.schema.$schema).toBe("https://json-schema.org/draft/2020-12/schema");
      expect(module!.schema.type).toBe("object");
      expect(module!.schema.additionalProperties).toBe(false);
      expect(module!.schema.title).toBe(module!.title);
      expect(defaultsFromSchema(module!.schema)).toEqual(defaults);
      for (const [key, child] of Object.entries(module!.schema.properties ?? {})) {
        expect(["string", "number", "integer", "boolean"], `${type}.${key}`).toContain(child.type);
        expect(child.title, `${type}.${key}`).toBeTruthy();
        if (child.enum && child.default !== undefined) expect(child.enum).toContain(child.default);
      }
    }
  });

  it("keeps existing entries first and appends the new ones", () => {
    const order = componentModules.map((module) => module.type);
    expect(order.indexOf("label")).toBe(0);
    expect(order.indexOf("link")).toBe(order.indexOf("multi-state-button") + 1);
    expect(componentModules.length).toBe(new Set(order).size);
  });

  it("offers exactly the icons of the original set in the icon schema", () => {
    const schema = componentRegistry().get("icon")!.schema;
    expect(schema.properties!.name!.enum).toEqual(iconNames);
  });

  it("renders every new component's defaults under both themes", () => {
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
      expect(document.querySelector(".oip-session")).toHaveAttribute("data-theme", theme);
      expect(screen.queryAllByRole("alert")).toHaveLength(0);
      for (const type of Object.keys(expectedDefaults)) {
        expect(document.querySelector(`[data-component='${type}']`)?.children.length, type).toBeGreaterThan(0);
      }
      unmount();
    }
  });
});

describe("date-time input", () => {
  it("round-trips local date strings", () => {
    const epoch = new Date(2026, 9, 27, 8, 5).getTime();
    expect(toInputString(epoch, "datetime")).toBe("2026-10-27T08:05");
    expect(toInputString(epoch, "date")).toBe("2026-10-27");
    expect(toInputString(epoch, "time")).toBe("08:05");
    expect(toInputString(null, "datetime")).toBe("");
    expect(fromInputString("2026-10-27T08:05", "datetime", null)).toBe(epoch);
    expect(fromInputString("2026-10-27", "date", null)).toBe(new Date(2026, 9, 27).getTime());
    expect(fromInputString("09:30", "time", epoch)).toBe(new Date(2026, 9, 27, 9, 30).getTime());
    expect(fromInputString("2026-02-31", "date", null)).toBeNull();
    expect(fromInputString("2026-10-27", "datetime", null)).toBeNull();
    expect(fromInputString("nope", "time", null)).toBeNull();
  });

  it("writes epoch ms and rejects values outside min and max", () => {
    const onChange = vi.fn();
    const value = new Date(2026, 0, 10, 12, 0).getTime();
    render(
      <DateTimeInput
        nodeId="d"
        locale="en-US"
        onChange={onChange}
        props={{ label: "Start", value, min: new Date(2026, 0, 1).getTime(), max: new Date(2026, 0, 31).getTime() }}
      />,
    );
    const field = screen.getByLabelText("Start");
    expect(field).toHaveAttribute("type", "datetime-local");
    expect(field).toHaveValue("2026-01-10T12:00");
    fireEvent.change(field, { target: { value: "2026-01-15T06:30" } });
    expect(onChange).toHaveBeenLastCalledWith("value", new Date(2026, 0, 15, 6, 30).getTime());
    fireEvent.change(field, { target: { value: "2026-03-01T00:00" } });
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(field).toHaveAttribute("aria-invalid", "true");
  });

  it("follows the bound value and the mode", () => {
    const value = new Date(2026, 4, 2, 7, 45).getTime();
    const { rerender } = render(<DateTimeInput nodeId="d" locale="en-US" onChange={noop} props={{ label: "When", value, mode: "time" }} />);
    expect(screen.getByLabelText("When")).toHaveAttribute("type", "time");
    expect(screen.getByLabelText("When")).toHaveValue("07:45");
    rerender(<DateTimeInput nodeId="d" locale="en-US" onChange={noop} props={{ label: "When", value: value + 60_000, mode: "time", enabled: false }} />);
    expect(screen.getByLabelText("When")).toHaveValue("07:46");
    expect(screen.getByLabelText("When")).toBeDisabled();
  });
});

describe("date-time picker", () => {
  it("lays out six weeks from the configured first weekday", () => {
    const grid = monthGrid(2026, 9, 1);
    expect(grid).toHaveLength(42);
    expect(grid[0]!.getDay()).toBe(1);
    expect(grid.some((day) => day.getMonth() === 9 && day.getDate() === 1)).toBe(true);
    expect(monthGrid(2026, 9, 0)[0]!.getDay()).toBe(0);
  });

  it("selects a day keeping the time, pages months, and edits the time", () => {
    const onChange = vi.fn();
    const value = new Date(2026, 9, 27, 14, 20).getTime();
    render(<DateTimePicker nodeId="p" locale="en-US" onChange={onChange} props={{ label: "Due", value }} />);
    expect(screen.getByRole("group", { name: "Due" })).toBeInTheDocument();
    expect(screen.getByRole("grid", { name: "October 2026" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "October 27, 2026" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("button", { pressed: true })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "October 29, 2026" }));
    expect(onChange).toHaveBeenLastCalledWith("value", new Date(2026, 9, 29, 14, 20).getTime());
    fireEvent.click(screen.getByRole("button", { name: "Next month" }));
    expect(screen.getByRole("grid", { name: "November 2026" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Time"), { target: { value: "06:05" } });
    expect(onChange).toHaveBeenLastCalledWith("value", new Date(2026, 9, 27, 6, 5).getTime());
  });

  it("hides the time field and disables days when asked", () => {
    const onChange = vi.fn();
    render(<DateTimePicker nodeId="p" locale="en-US" onChange={onChange} props={{ label: "Due", value: new Date(2026, 0, 5).getTime(), showTime: false, enabled: false }} />);
    expect(screen.queryByLabelText("Time")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "January 6, 2026" }));
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe("password field", () => {
  it("never renders the value into DOM text or attributes", () => {
    const secret = "s3cret-Value-91";
    const { container, rerender } = render(<PasswordField nodeId="pw" locale="en-US" onChange={noop} props={{ label: "Password", value: secret }} />);
    const field = screen.getByLabelText("Password") as HTMLInputElement;
    expect(field).toHaveAttribute("type", "password");
    expect(field.value).toBe(secret);
    expect(field.hasAttribute("value")).toBe(false);
    expect(container.textContent).not.toContain(secret);
    expect(container.innerHTML).not.toContain(secret);
    expect(document.body.innerHTML).not.toContain(secret);
    rerender(<PasswordField nodeId="pw" locale="en-US" onChange={noop} props={{ label: "Password", value: "other-Secret-2" }} />);
    expect(field.value).toBe("other-Secret-2");
    expect(document.body.innerHTML).not.toContain("other-Secret-2");
  });

  it("writes on Enter and on blur, and not when unchanged", () => {
    const onChange = vi.fn();
    render(<PasswordField nodeId="pw" locale="en-US" onChange={onChange} props={{ label: "Password", value: "" }} />);
    const field = screen.getByLabelText("Password") as HTMLInputElement;
    fireEvent.focus(field);
    fireEvent.change(field, { target: { value: "typed-1" } });
    fireEvent.keyDown(field, { key: "Enter" });
    expect(onChange).toHaveBeenLastCalledWith("value", "typed-1");
    expect(document.body.innerHTML).not.toContain("typed-1");
    fireEvent.change(field, { target: { value: "" } });
    fireEvent.blur(field);
    expect(onChange).toHaveBeenCalledTimes(1);
  });
});

describe("radio group", () => {
  it("checks the bound option and writes the chosen value", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <RadioGroup nodeId="r" locale="en-US" onChange={onChange} props={{ label: "Line", value: "b", options: "a:Line A, b:Line B, c:Line C" }} />,
    );
    expect(screen.getByRole("radiogroup", { name: "Line" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Line B" })).toBeChecked();
    expect(screen.getAllByRole("radio").filter((radio) => (radio as HTMLInputElement).checked)).toHaveLength(1);
    fireEvent.click(screen.getByRole("radio", { name: "Line C" }));
    expect(onChange).toHaveBeenLastCalledWith("value", "c");
    rerender(
      <RadioGroup nodeId="r" locale="en-US" onChange={onChange} props={{ label: "Line", value: 2, options: [{ value: 1, label: "One" }, { value: 2, label: "Two" }], orientation: "column" }} />,
    );
    expect(screen.getByRole("radio", { name: "Two" })).toBeChecked();
    expect(screen.getByRole("radiogroup")).toHaveAttribute("data-orientation", "column");
    fireEvent.click(screen.getByRole("radio", { name: "One" }));
    expect(onChange).toHaveBeenLastCalledWith("value", 1);
    rerender(<RadioGroup nodeId="r" locale="en-US" onChange={onChange} props={{ label: "Line", value: "", options: "a, b", enabled: false }} />);
    expect(screen.getAllByRole("radio").every((radio) => !(radio as HTMLInputElement).checked)).toBe(true);
    expect(screen.getByRole("radio", { name: "a" })).toBeDisabled();
  });
});

describe("text area", () => {
  it("defers the write until blur by default", () => {
    const onChange = vi.fn();
    render(<TextArea nodeId="t" locale="en-US" onChange={onChange} props={{ label: "Shift notes", value: "start", rows: 6 }} />);
    const field = screen.getByRole("textbox", { name: "Shift notes" });
    expect(field).toHaveValue("start");
    expect(field).toHaveAttribute("rows", "6");
    fireEvent.focus(field);
    fireEvent.change(field, { target: { value: "line one\nline two" } });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.blur(field);
    expect(onChange).toHaveBeenCalledWith("value", "line one\nline two");
  });

  it("writes every edit when deferUpdates is off and follows the bound value", () => {
    const onChange = vi.fn();
    const { rerender } = render(<TextArea nodeId="t" locale="en-US" onChange={onChange} props={{ label: "Notes", value: "", deferUpdates: false }} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Notes" }), { target: { value: "x" } });
    expect(onChange).toHaveBeenCalledWith("value", "x");
    rerender(<TextArea nodeId="t" locale="en-US" onChange={onChange} props={{ label: "Notes", value: "from tag", deferUpdates: false }} />);
    expect(screen.getByRole("textbox", { name: "Notes" })).toHaveValue("from tag");
  });
});

describe("one-shot button", () => {
  it("writes once and stays disabled until the bound value equals the written value", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <OneShotButton nodeId="o" locale="en-US" onChange={onChange} props={{ text: "Reset counter", writingText: "Resetting", value: 0, writeValue: 1 }} />,
    );
    const button = screen.getByRole("button", { name: "Reset counter" });
    fireEvent.click(button);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith("value", 1);
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent("Resetting");
    expect(button).toHaveAttribute("aria-busy", "true");
    fireEvent.click(button);
    expect(onChange).toHaveBeenCalledTimes(1);
    rerender(<OneShotButton nodeId="o" locale="en-US" onChange={onChange} props={{ text: "Reset counter", writingText: "Resetting", value: 2, writeValue: 1 }} />);
    expect(button).toBeDisabled();
    rerender(<OneShotButton nodeId="o" locale="en-US" onChange={onChange} props={{ text: "Reset counter", writingText: "Resetting", value: "1", writeValue: 1 }} />);
    expect(button).toBeEnabled();
    expect(button).toHaveTextContent("Reset counter");
  });

  it("re-enables when the timeout expires without a matching value", () => {
    vi.useFakeTimers();
    const onChange = vi.fn();
    render(<OneShotButton nodeId="o" locale="en-US" onChange={onChange} props={{ text: "Pulse", value: 0, writeValue: true, timeoutMs: 1500 }} />);
    const button = screen.getByRole("button", { name: "Pulse" });
    fireEvent.click(button);
    expect(onChange).toHaveBeenCalledWith("value", true);
    expect(button).toBeDisabled();
    act(() => {
      vi.advanceTimersByTime(1499);
    });
    expect(button).toBeDisabled();
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  it("does nothing while disabled", () => {
    const onChange = vi.fn();
    render(<OneShotButton nodeId="o" locale="en-US" onChange={onChange} props={{ text: "Pulse", enabled: false }} />);
    fireEvent.click(screen.getByRole("button", { name: "Pulse" }));
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe("multi-state button", () => {
  it("presses exactly the state matching the value and writes one state value per press", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <MultiStateButton nodeId="m" locale="en-US" onChange={onChange} props={{ label: "Pump mode", value: 1, states: "0:Off, 1:Hand, 2:Auto, 1:Duplicate" }} />,
    );
    const group = screen.getByRole("group", { name: "Pump mode" });
    expect(group.querySelectorAll("button")).toHaveLength(3);
    expect(screen.getAllByRole("button", { pressed: true })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Hand" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByRole("button", { name: "Auto" }));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith("value", 2);
    fireEvent.click(screen.getByRole("button", { name: "Hand" }));
    expect(onChange).toHaveBeenCalledTimes(1);
    rerender(<MultiStateButton nodeId="m" locale="en-US" onChange={onChange} props={{ label: "Pump mode", value: "2", states: "0:Off, 1:Hand, 2:Auto" }} />);
    expect(screen.getAllByRole("button", { pressed: true }).map((button) => button.textContent)).toEqual(["Auto"]);
    rerender(<MultiStateButton nodeId="m" locale="en-US" onChange={onChange} props={{ label: "Pump mode", value: 9, states: "0:Off, 1:Hand, 2:Auto" }} />);
    expect(screen.queryAllByRole("button", { pressed: true })).toHaveLength(0);
    rerender(
      <MultiStateButton nodeId="m" locale="en-US" onChange={onChange} props={{ label: "Valve", value: "open", states: [{ value: "open", text: "Open" }, { value: "shut", text: "Shut" }], enabled: false }} />,
    );
    expect(screen.getByRole("button", { name: "Open" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByRole("button", { name: "Shut" }));
    expect(onChange).toHaveBeenCalledTimes(1);
  });
});

describe("link", () => {
  it("classifies targets", () => {
    expect(linkTarget("/pumps")).toEqual({ kind: "page", path: "/pumps" });
    expect(linkTarget(" https://example.com/a ")).toEqual({ kind: "external", url: "https://example.com/a" });
    expect(linkTarget("//evil.example")).toEqual({ kind: "blocked" });
    expect(linkTarget("javascript:alert(1)")).toEqual({ kind: "blocked" });
    expect(linkTarget("pumps")).toEqual({ kind: "blocked" });
    // Browsers drop tabs and newlines and read a backslash as a slash, which would turn these into //host.
    for (const sneaky of ["/\t/x", "/\n/x", "/\r/x", " //x", "/\\x", "/ /x", "/\u0000/x", "https://a.example/\t/x"]) {
      expect(linkTarget(sneaky), JSON.stringify(sneaky)).toEqual({ kind: "blocked" });
    }
    expect(linkTarget(" /pumps?line=3 ")).toEqual({ kind: "page", path: "/pumps?line=3" });
  });

  it("keeps page links in the document and lets external links leave", () => {
    const { rerender } = render(<Link nodeId="l" locale="en-US" onChange={noop} props={{ text: "Pumps", url: "/pumps" }} />);
    const anchor = screen.getByRole("link", { name: "Pumps" });
    expect(anchor).toHaveAttribute("href", "/pumps");
    const pageClick = createEvent.click(anchor);
    fireEvent(anchor, pageClick);
    expect(pageClick.defaultPrevented).toBe(true);
    rerender(<Link nodeId="l" locale="en-US" onChange={noop} props={{ text: "Vendor", url: "https://example.com", newTab: true }} />);
    const external = screen.getByRole("link", { name: "Vendor" });
    expect(external).toHaveAttribute("target", "_blank");
    expect(external).toHaveAttribute("rel", "noopener noreferrer");
    rerender(<Link nodeId="l" locale="en-US" onChange={noop} props={{ text: "Bad", url: "javascript:alert(1)" }} />);
    const blocked = screen.getByRole("link", { name: "Bad" });
    expect(blocked).not.toHaveAttribute("href");
    expect(blocked).toHaveAttribute("aria-disabled", "true");
  });

  it("renders control-character and backslash targets without an href and does not follow them", () => {
    const navigate = vi.fn();
    for (const url of ["/\t/x", "/\n/x", " //x", "/\\x"]) {
      const { unmount } = render(<Link nodeId="l" locale="en-US" onChange={noop} navigate={navigate} props={{ text: "Sneaky", url }} />);
      const anchor = screen.getByRole("link", { name: "Sneaky" });
      expect(anchor, JSON.stringify(url)).not.toHaveAttribute("href");
      fireEvent.click(anchor);
      unmount();
    }
    expect(navigate).not.toHaveBeenCalled();
  });

  it("builds a page href with the session base", () => {
    render(<Link nodeId="l" locale="en-US" onChange={noop} pageHref={(path) => `/hmi${path}`} props={{ text: "Pumps", url: "/pumps" }} />);
    expect(screen.getByRole("link", { name: "Pumps" })).toHaveAttribute("href", "/hmi/pumps");
  });

  it("follows a page path through the navigate hook and leaves other targets alone", () => {
    const navigate = vi.fn();
    const { rerender } = render(<Link nodeId="l" locale="en-US" onChange={noop} navigate={navigate} props={{ text: "Pumps", url: "/pumps?line=3" }} />);
    fireEvent.click(screen.getByRole("link", { name: "Pumps" }));
    expect(navigate).toHaveBeenCalledWith("/pumps?line=3");
    rerender(<Link nodeId="l" locale="en-US" onChange={noop} navigate={navigate} props={{ text: "Off", url: "/pumps", enabled: false }} />);
    fireEvent.click(screen.getByRole("link", { name: "Off" }));
    rerender(<Link nodeId="l" locale="en-US" onChange={noop} navigate={navigate} props={{ text: "Vendor", url: "https://example.com" }} />);
    const external = screen.getByRole("link", { name: "Vendor" });
    external.addEventListener("click", (event) => event.preventDefault());
    fireEvent.click(external);
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it("navigates through the page runtime and not when disabled", async () => {
    const types = allowedComponentTypes();
    const linkView = (enabled: boolean) =>
      parseView(
        {
          apiVersion: "oip.hmi/v1",
          kind: "View",
          metadata: { name: "Home", path: "home" },
          spec: {
            params: {},
            width: 400,
            height: 200,
            root: {
              id: "root",
              kind: "container",
              name: "Root",
              layout: "flex",
              props: { direction: "column" },
              children: [
                {
                  id: "to-pumps",
                  kind: "component",
                  name: "To pumps",
                  type: "link",
                  props: { text: "Pumps", url: "/pumps", enabled },
                  events: [{ event: "onClick", actions: [{ kind: "navigation", mode: "page", page: "/pumps" }] }],
                },
              ],
            },
          },
        },
        { allowedTypes: types },
      );
    const pumps = parseView(
      {
        apiVersion: "oip.hmi/v1",
        kind: "View",
        metadata: { name: "Pumps", path: "pumps" },
        spec: { params: {}, width: 400, height: 200, root: { id: "p", kind: "container", name: "P", layout: "flex", props: {}, children: [] } },
      },
      { allowedTypes: types },
    );
    const project = (enabled: boolean): HmiProject => ({
      name: "Links",
      views: [linkView(enabled), pumps],
      pages: [
        parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Home" }, spec: { url: "/", primaryView: "home", docks: [] } }),
        parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Pumps" }, spec: { url: "/pumps", primaryView: "pumps", docks: [] } }),
      ],
      sharedDocks: [],
      cornerPriority: "top-bottom",
    });
    const onLocationChange = vi.fn();
    const [transport] = createLinkedTransports();
    const session = createSession({ id: "links", theme: "daylight", locale: "en-US" });
    const { rerender } = render(
      <PageSession project={project(true)} registry={componentRegistry()} transport={transport} session={session} location="/" onLocationChange={onLocationChange} />,
    );
    const anchor = await screen.findByRole("link", { name: "Pumps" });
    const click = createEvent.click(anchor);
    fireEvent(anchor, click);
    expect(click.defaultPrevented).toBe(true);
    expect(onLocationChange).toHaveBeenCalledWith("/pumps");
    rerender(
      <PageSession project={project(false)} registry={componentRegistry()} transport={transport} session={session} location="/" onLocationChange={onLocationChange} />,
    );
    fireEvent.click(await screen.findByRole("link", { name: "Pumps" }));
    expect(onLocationChange).toHaveBeenCalledTimes(1);
  });
});

describe("bidirectional tag writes through the view renderer", () => {
  function controlsView(): ViewDocument {
    const bind = (path: string) => ({ value: { kind: "tag", mode: "direct", path, bidirectional: true } });
    return parseView(
      {
        apiVersion: "oip.hmi/v1",
        kind: "View",
        metadata: { name: "Controls", path: "controls" },
        spec: {
          params: {},
          width: 400,
          height: 400,
          root: {
            id: "root",
            kind: "container",
            name: "Root",
            layout: "flex",
            props: { direction: "column" },
            children: [
              { id: "oneshot", kind: "component", name: "Reset", type: "one-shot-button", props: { text: "Reset", writeValue: 1, timeoutMs: 60000 }, bindings: bind("line/reset") },
              { id: "mode", kind: "component", name: "Mode", type: "multi-state-button", props: { label: "Mode", states: "0:Off, 1:Hand, 2:Auto" }, bindings: bind("line/mode") },
              { id: "recipe", kind: "component", name: "Recipe", type: "radio-group", props: { label: "Recipe", options: "r1:Recipe 1, r2:Recipe 2" }, bindings: bind("line/recipe") },
              { id: "notes", kind: "component", name: "Notes", type: "text-area", props: { label: "Notes" }, bindings: bind("line/notes") },
            ],
          },
        },
      },
      { allowedTypes: allowedComponentTypes() },
    );
  }

  it("writes the one-shot, multi-state, radio and text area values to the tags and reads them back", async () => {
    const server = new MemoryTagServer();
    server.set("line/reset", 0);
    server.set("line/mode", 0);
    server.set("line/recipe", "r1");
    server.set("line/notes", "");
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<ViewRenderer view={controlsView()} registry={componentRegistry()} transport={transport} session={createSession({ id: "w", locale: "en-US" })} />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Off" })).toHaveAttribute("aria-pressed", "true"));
    fireEvent.click(screen.getByRole("button", { name: "Auto" }));
    await waitFor(() => expect(server.get("line/mode")?.value).toBe(2));
    expect(screen.getAllByRole("button", { pressed: true }).map((button) => button.textContent)).toEqual(["Auto"]);

    fireEvent.click(screen.getByRole("radio", { name: "Recipe 2" }));
    await waitFor(() => expect(server.get("line/recipe")?.value).toBe("r2"));
    await waitFor(() => expect(screen.getByRole("radio", { name: "Recipe 2" })).toBeChecked());

    const notes = screen.getByRole("textbox", { name: "Notes" });
    fireEvent.focus(notes);
    fireEvent.change(notes, { target: { value: "checked seals" } });
    fireEvent.blur(notes);
    await waitFor(() => expect(server.get("line/notes")?.value).toBe("checked seals"));

    // The one-shot locks on press and unlocks when the tag reads back the written value.
    const reset = screen.getByRole("button", { name: "Reset" });
    fireEvent.click(reset);
    expect(reset).toBeDisabled();
    await waitFor(() => expect(server.get("line/reset")?.value).toBe(1));
    await waitFor(() => expect(reset).toBeEnabled());
    server.close();
  });
});

/** HMP-047: the file upload component sends the picked file to the project's upload handler.
 */
describe("FileUpload", () => {
  const base = { nodeId: "up1", locale: "en", onChange: () => undefined };
  const sent: Array<{ url: string; init: RequestInit }> = [];
  function client(replyStatus = 200) {
    sent.length = 0;
    const fetchMock = async (url: string, init?: RequestInit) => {
      sent.push({ url, init: init ?? {} });
      if (url.endsWith("/api/v1/auth/session")) return new Response(JSON.stringify({ csrfToken: "tok" }), { status: 200 });
      return replyStatus === 200
        ? new Response(JSON.stringify({ filename: "a.csv", size: 3, value: null, actions: [] }), { status: 200 })
        : new Response(JSON.stringify({ detail: "the file is larger than the 100 byte limit" }), { status: replyStatus });
    };
    return new GatewayClient({ fetch: fetchMock });
  }
  function renderUpload(props: Record<string, unknown>, gateway: GatewayClient) {
    return render(
      <GatewayProvider client={gateway}>
        <FileUpload {...base} props={{ project: "plant", hook: "importCsv", ...props }} />
      </GatewayProvider>,
    );
  }
  const pick = (file: File) => fireEvent.change(screen.getByTestId("file-upload-input"), { target: { files: [file] } });

  it("posts the raw file with its type, name and CSRF token", async () => {
    renderUpload({ view: "Main" }, client());
    pick(new File(["a,b"], "my data.csv", { type: "text/csv" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("my data.csv sent"));
    const post = sent.find((s) => s.init.method === "POST")!;
    expect(post.url).toBe("/api/v1/hmi/projects/plant/upload/importCsv?node=up1&view=Main");
    const headers = post.init.headers as Record<string, string>;
    expect(headers["Content-Type"]).toBe("text/csv");
    expect(headers["X-File-Name"]).toBe("my%20data.csv");
    expect(headers["X-CSRF-Token"]).toBe("tok");
    expect(post.init.body).toBeInstanceOf(File);
  });

  it("refuses a file over maxBytes without sending it", async () => {
    renderUpload({ maxBytes: 2 }, client());
    pick(new File(["abc"], "big.csv", { type: "text/csv" }));
    expect((await screen.findByRole("alert")).textContent).toContain("larger than 2 bytes");
    expect(sent.some((s) => s.init.method === "POST")).toBe(false);
  });

  it("shows the gateway's refusal", async () => {
    renderUpload({}, client(413));
    pick(new File(["abc"], "big.csv", { type: "text/csv" }));
    expect((await screen.findByRole("alert")).textContent).toContain("larger than the 100 byte limit");
  });

  it("is disabled without a project and hook, and is registered as an input", () => {
    renderUpload({ project: "", hook: "" }, client());
    expect((screen.getByRole("button") as HTMLButtonElement).disabled).toBe(true);
    expect(componentRegistry().get("file-upload")?.category).toBe("input");
  });
});
