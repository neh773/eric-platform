import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { sessionPath } from "./bindings";
import type { ScriptActionResult, ScriptHost } from "./bindings";
import { parseView } from "./model";
import { parsePage } from "./pages";
import { PageSession } from "./PageSession";
import { createSession, detectDevice, isLocaleTag, isTimeZoneId, sessionForScripts } from "./session";
import { createLinkedTransports, MemoryTagServer } from "./tags";
import type { ComponentRegistry, HmiComponentProps, HmiProject } from "./types";
import { ViewRenderer } from "./ViewRenderer";

/** HMP-084: user, device info and time zone are session properties for bindings and scripts; locale and time zone can be written. */

const device = { type: "browser" as const, id: "dev-7", userAgent: "TestAgent/2", screenWidth: 1920, screenHeight: 1080, timeZone: "Europe/Berlin" };
const known = createSession({
  id: "s1",
  locale: "en-US",
  timeZoneId: "UTC",
  auth: { authenticated: true, userName: "ada", roles: ["operator", "viewer"] },
  device,
});

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "probe") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ props, onChange }: HmiComponentProps) => (
        <button type="button" onClick={() => onChange("value", props.writeValue)}>
          {String(props.text ?? props.value ?? "")}
        </button>
      ),
    };
  },
  list: () => [],
};

function view(children: unknown[]) {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "main", path: "main" },
    spec: { width: 300, height: 200, params: {}, root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, children } },
  });
}

describe("session properties", () => {
  it("reads user, device and time zone by path, and only the locale, time zone and theme are writable", () => {
    expect(sessionPath(known, "auth.userName")).toEqual({ value: "ada", writable: false });
    expect(sessionPath(known, "auth.roles").value).toEqual(["operator", "viewer"]);
    expect(sessionPath(known, "device.id")).toEqual({ value: "dev-7", writable: false });
    expect(sessionPath(known, "device.userAgent")).toEqual({ value: "TestAgent/2", writable: false });
    expect(sessionPath(known, "device.screenWidth").value).toBe(1920);
    expect(sessionPath(known, "device.screenHeight").value).toBe(1080);
    expect(sessionPath(known, "device.timeZone").value).toBe("Europe/Berlin");
    expect(sessionPath(known, "timeZoneId")).toEqual({ value: "UTC", writable: true });
    expect(sessionPath(known, "locale").writable).toBe(true);
  });

  it("detects this browser's device and keeps its id between sessions", () => {
    window.localStorage.removeItem("oip.session.device-id");
    const first = detectDevice();
    expect(first.id.length).toBeGreaterThan(8);
    expect(first.userAgent).toBe(navigator.userAgent);
    expect(first.timeZone.length).toBeGreaterThan(0);
    expect(detectDevice().id).toBe(first.id);
  });

  it("accepts only usable locales and time zones", () => {
    expect(isLocaleTag("de-DE")).toBe(true);
    expect(isLocaleTag("english")).toBe(false);
    expect(isTimeZoneId("Europe/Berlin")).toBe(true);
    expect(isTimeZoneId("Mars/Olympus")).toBe(false);
    expect(sessionForScripts(known)).toMatchObject({ auth: { userName: "ada", roles: ["operator", "viewer"] }, device: { id: "dev-7" }, timeZoneId: "UTC" });
  });

  it("binds a label to the device id and writes the time zone from a binding", async () => {
    const doc = view([
      { id: "dev", kind: "component", name: "Dev", type: "probe", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "session", path: "device.id" } } } },
      { id: "zone", kind: "component", name: "Zone", type: "probe", props: { text: "Zone", writeValue: "Europe/Berlin" }, bindings: { value: { kind: "property", bidirectional: true, source: { scope: "session", path: "timeZoneId" } } } },
      { id: "bad", kind: "component", name: "Bad", type: "probe", props: { text: "Bad", writeValue: "Mars/Olympus" }, bindings: { value: { kind: "property", bidirectional: true, source: { scope: "session", path: "timeZoneId" } } } },
      { id: "now", kind: "component", name: "Now", type: "probe", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "session", path: "timeZoneId" } } } },
      { id: "id", kind: "component", name: "Id", type: "probe", props: { text: "Id", writeValue: "x" }, bindings: { value: { kind: "property", bidirectional: true, source: { scope: "session", path: "device.id" } } } },
    ]);
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<ViewRenderer view={doc} registry={registry} transport={transport} session={known} />);
    expect(await screen.findByText("dev-7")).toBeTruthy();
    expect(screen.getByText("UTC")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Bad" }));
    expect(screen.getByText("UTC")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Id" }));
    expect(screen.getByText("dev-7")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Zone" }));
    await waitFor(() => expect(screen.getAllByText("Europe/Berlin").length).toBeGreaterThan(0));
    server.close();
  });

  it("gives the script host the session, and applies a script's locale and time zone writes", async () => {
    const provided: Array<Record<string, unknown>> = [];
    const host: ScriptHost = {
      transform: (_hook, value) => value,
      setSessionProvider(provider) {
        provided.push(provider());
        this.read = provider;
      },
      action(): Promise<ScriptActionResult> {
        return Promise.resolve({ session: [{ kind: "session", prop: "timeZoneId", value: "Asia/Tokyo" }, { kind: "session", prop: "locale", value: "not a tag" }, { kind: "session", prop: "locale", value: "ja-JP" }] });
      },
    } as ScriptHost & { read?: () => Record<string, unknown> };
    const doc = view([
      { id: "run", kind: "component", name: "Run", type: "probe", props: { text: "Run" }, events: [{ event: "onClick", actions: [{ kind: "script", hook: "setZone" }] }] },
      { id: "zone", kind: "component", name: "Zone", type: "probe", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "session", path: "timeZoneId" } } } },
      { id: "locale", kind: "component", name: "Locale", type: "probe", props: { text: "" }, bindings: { text: { kind: "property", source: { scope: "session", path: "locale" } } } },
    ]);
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<ViewRenderer view={doc} registry={registry} transport={transport} session={known} scriptHost={host} />);
    expect(provided[0]).toMatchObject({ auth: { userName: "ada" }, device: { id: "dev-7", userAgent: "TestAgent/2" }, timeZoneId: "UTC" });
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    await waitFor(() => expect(screen.getByText("Asia/Tokyo")).toBeTruthy());
    expect(screen.getByText("ja-JP")).toBeTruthy();
    server.close();
  });

  it("runs the session startup script with the session and applies its writes", async () => {
    const applied: Array<Record<string, unknown>> = [];
    const calls: string[] = [];
    const host: ScriptHost = {
      transform: (_hook, value) => value,
      setSessionProvider(provider) {
        applied.push(provider());
      },
      action(hook) {
        calls.push(hook);
        return hook === "sessionStartup" ? Promise.resolve({ session: [{ kind: "session", prop: "timeZoneId", value: "America/Chicago" }] }) : undefined;
      },
    };
    const project: HmiProject = {
      name: "p",
      views: [view([])],
      pages: [parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Main" }, spec: { url: "/", primaryView: "main" } })],
    };
    const patches: unknown[] = [];
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<PageSession project={project} registry={registry} transport={transport} session={known} location="/" scriptHost={host} onLocationChange={() => {}} onSessionPatch={(patch) => patches.push(patch)} />);
    await waitFor(() => expect(patches).toEqual([{ timeZoneId: "America/Chicago" }]));
    expect(calls).toContain("sessionStartup");
    expect(applied[0]).toMatchObject({ auth: { userName: "ada" }, device: { timeZone: "Europe/Berlin" } });
    server.close();
  });
});
