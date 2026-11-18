import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { OnScreenKeyboard } from "./OnScreenKeyboard";
import { PageSession } from "./PageSession";
import { parsePage } from "./pages";
import { parseView } from "./model";
import { createSession } from "./session";
import { createLinkedTransports } from "./tags";
import type { HmiComponentProps, HmiProject } from "./types";

/** HMV-126: touchscreen mode shows an on-screen keyboard for inputs. */

/** A field that commits on blur and on Enter, like the runtime's text, numeric and password fields. */
function Field({ type = "text", inputMode, onCommit }: { type?: string; inputMode?: "numeric" | "decimal"; onCommit: (value: string) => void }) {
  const [draft, setDraft] = useState("");
  return (
    <input
      aria-label="field"
      type={type}
      inputMode={inputMode}
      value={draft}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={() => onCommit(draft)}
      onKeyDown={(event) => {
        if (event.key === "Enter") onCommit(`enter:${draft}`);
      }}
    />
  );
}

const focus = (element: HTMLElement) => act(() => element.focus());
const key = (name: string | RegExp) => screen.getByRole("button", { name });

describe("on-screen keyboard", () => {
  it("stays hidden when touchscreen mode is off", () => {
    render(
      <>
        <Field onCommit={() => undefined} />
        <OnScreenKeyboard enabled={false} />
      </>,
    );
    fireEvent.focus(screen.getByLabelText("field"));
    expect(screen.queryByRole("group", { name: "On-screen keyboard" })).toBeNull();
  });

  it("types letters with shift and backspace through the field's change path, then commits on Enter", () => {
    const commits: string[] = [];
    render(
      <>
        <Field onCommit={(value) => commits.push(value)} />
        <OnScreenKeyboard enabled />
      </>,
    );
    const input = screen.getByLabelText("field");
    focus(input);
    const board = screen.getByRole("group", { name: "On-screen keyboard" });
    expect(board).toHaveAttribute("data-layout", "alpha");
    fireEvent.click(within(board).getByRole("button", { name: "Shift" }));
    fireEvent.click(within(board).getByRole("button", { name: "H" }));
    fireEvent.click(within(board).getByRole("button", { name: "i" }));
    fireEvent.click(within(board).getByRole("button", { name: "x" }));
    fireEvent.click(within(board).getByRole("button", { name: "Backspace" }));
    expect(input).toHaveValue("Hi");
    fireEvent.click(within(board).getByRole("button", { name: "Enter" }));
    expect(commits).toEqual(["enter:Hi", "Hi"]);
    expect(screen.queryByRole("group", { name: "On-screen keyboard" })).toBeNull();
  });

  it("opens the numeric layout for a numeric field and Close leaves the field", () => {
    const commits: string[] = [];
    render(
      <>
        <Field inputMode="decimal" onCommit={(value) => commits.push(value)} />
        <OnScreenKeyboard enabled />
      </>,
    );
    const input = screen.getByLabelText("field");
    focus(input);
    const board = screen.getByRole("group", { name: "On-screen keyboard" });
    expect(board).toHaveAttribute("data-layout", "numeric");
    for (const digit of ["1", "2", ".", "5"]) fireEvent.click(within(board).getByRole("button", { name: digit }));
    expect(input).toHaveValue("12.5");
    fireEvent.click(key("Close keyboard"));
    expect(commits).toEqual(["12.5"]);
    expect(screen.queryByRole("group", { name: "On-screen keyboard" })).toBeNull();
  });

  it("switches a text field between letters and numbers, and covers password fields", () => {
    render(
      <>
        <Field type="password" onCommit={() => undefined} />
        <OnScreenKeyboard enabled />
      </>,
    );
    const input = screen.getByLabelText("field");
    focus(input);
    fireEvent.click(key("Numbers"));
    expect(screen.getByRole("group", { name: "On-screen keyboard" })).toHaveAttribute("data-layout", "numeric");
    fireEvent.click(key("7"));
    fireEvent.click(key("Letters"));
    fireEvent.click(key("a"));
    expect(input).toHaveValue("7a");
  });

  it("ignores checkboxes and read-only inputs", () => {
    render(
      <>
        <input type="checkbox" aria-label="box" />
        <input readOnly aria-label="ro" />
        <OnScreenKeyboard enabled />
      </>,
    );
    focus(screen.getByLabelText("box"));
    expect(screen.queryByRole("group", { name: "On-screen keyboard" })).toBeNull();
    focus(screen.getByLabelText("ro"));
    expect(screen.queryByRole("group", { name: "On-screen keyboard" })).toBeNull();
  });
});

describe("touchscreen mode in a page session", () => {
  const registry = {
    get(type: string) {
      if (type !== "field") return undefined;
      return {
        type,
        title: type,
        schema: { type: "object" as const },
        Component: ({ onChange }: HmiComponentProps) => <input aria-label="entry" onChange={(event) => onChange("value", event.target.value)} />,
      };
    },
    list: () => [],
  };
  const view = parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "home", path: "home" },
    spec: {
      width: 300,
      height: 200,
      params: {},
      root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, children: [{ id: "f", kind: "component", name: "F", type: "field", props: {} }] },
    },
  });
  const project = (touchscreenMode?: boolean): HmiProject => ({
    name: "p",
    views: [view],
    pages: [parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Home" }, spec: { url: "/", primaryView: "home", docks: [] } })],
    ...(touchscreenMode === undefined ? {} : { touchscreenMode }),
  });
  function mount(p: HmiProject, custom: Record<string, unknown> = {}) {
    const [transport] = createLinkedTransports();
    render(<PageSession project={p} registry={registry} transport={transport} session={createSession({ id: "s", custom })} location="/" onLocationChange={vi.fn()} />);
  }

  it("opens for a field when the project sets touchscreen mode", async () => {
    mount(project(true));
    focus(await screen.findByLabelText("entry"));
    expect(screen.getByRole("group", { name: "On-screen keyboard" })).toBeTruthy();
  });

  it("opens when the session sets touchscreen, and not otherwise", async () => {
    mount(project(), { touchscreen: true });
    focus(await screen.findByLabelText("entry"));
    expect(screen.getByRole("group", { name: "On-screen keyboard" })).toBeTruthy();
  });

  it("does not open when neither is set", async () => {
    mount(project());
    focus(await screen.findByLabelText("entry"));
    expect(screen.queryByRole("group", { name: "On-screen keyboard" })).toBeNull();
  });
});
