import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import type { ScriptHost } from "./bindings";
import { parsePage } from "./pages";
import { PageSession } from "./PageSession";
import { createSession } from "./session";
import { createLinkedTransports, MemoryTagServer } from "./tags";
import type { ComponentRegistry, HmiComponentProps, HmiProject, ViewDocument } from "./types";
import { parseView } from "./model";
import { ViewRenderer } from "./ViewRenderer";

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "probe") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ props, onChange }: HmiComponentProps) => (
        <button type="button" onClick={() => onChange("value", 7)}>
          {String(props.text ?? "Set")}
        </button>
      ),
    };
  },
  list: () => [],
};

function recordingHost() {
  const calls: Array<{ hook: string; event: string; payload: unknown }> = [];
  const host: ScriptHost = {
    transform: (_hook, value) => value,
    action(hook, event, _nodeId, _view, _params, _custom, payload) {
      calls.push({ hook, event, payload });
    },
  };
  return { host, calls };
}

function view(path: string, children: unknown[], events?: unknown[]): ViewDocument {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: path, path },
    spec: {
      width: 200,
      height: 80,
      params: {},
      root: { id: "root", kind: "container", name: "Root", layout: "flex", props: {}, children },
      ...(events ? { events } : {}),
    },
  });
}

describe("session and property scripts", () => {
  it("runs session startup, page change, and session shutdown", async () => {
    const { host, calls } = recordingHost();
    const main = view("main", []);
    const pumps = view("pumps", []);
    const project: HmiProject = {
      name: "p",
      views: [main, pumps],
      pages: [
        parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Main" }, spec: { url: "/", primaryView: "main" } }),
        parsePage({ apiVersion: "oip.hmi/v1", kind: "Page", metadata: { name: "Pumps" }, spec: { url: "/pumps", primaryView: "pumps" } }),
      ],
    };
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    function Harness() {
      const [location, setLocation] = useState("/");
      return <PageSession project={project} registry={registry} transport={transport} session={createSession({ id: "s" })} location={location} onLocationChange={setLocation} scriptHost={host} />;
    }
    const mounted = render(<Harness />);
    await waitFor(() => expect(calls.map((call) => call.hook)).toEqual(["sessionStartup", "pageChange"]));
    expect(calls[1]?.payload).toMatchObject({ page: "Main" });
    mounted.unmount();
    expect(calls.map((call) => call.hook)).toContain("sessionShutdown");
    server.close();
  });

  it("runs a property-change script when a component prop changes", () => {
    const { host, calls } = recordingHost();
    const doc = view("main", [
      {
        id: "probe",
        kind: "component",
        name: "Probe",
        type: "probe",
        props: { text: "Set" },
        events: [{ event: "onPropertyChange", actions: [{ kind: "script", hook: "changed" }] }],
      },
    ]);
    const server = new MemoryTagServer();
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<ViewRenderer view={doc} registry={registry} transport={transport} session={createSession({ id: "s" })} scriptHost={host} />);
    fireEvent.click(screen.getByRole("button", { name: "Set" }));
    expect(calls).toEqual([{ hook: "changed", event: "onPropertyChange", payload: { prop: "value", value: 7 } }]);
    server.close();
  });
});
