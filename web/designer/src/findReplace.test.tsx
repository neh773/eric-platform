import { act, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ViewDocument } from "@oip/hmi-runtime";
import { DiagnosticsMeter, bindingStats, meteredTransport } from "./diagnostics";
import { FindReplacePanel } from "./FindReplacePanel";
import { DiagnosticsPanel } from "./DiagnosticsPanel";
import { findInProject, replaceHits } from "./findReplace";

function view(path: string, text: string, tagPath: string, script = ""): ViewDocument {
  return {
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: path, path },
    spec: {
      params: {},
      custom: {},
      width: 100,
      height: 100,
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout: "coordinate",
        props: {},
        children: [
          {
            id: "label",
            kind: "component",
            name: "PumpLabel",
            type: "label",
            props: { text },
            bindings: { value: { kind: "tag", path: tagPath } },
            events: script ? [{ event: "onClick", actions: [{ kind: "script", script }] }] : undefined,
          },
        ],
      },
    },
  } as unknown as ViewDocument;
}

const views = [view("a", "Pump 1 speed", "plant/Pump1/speed", "print('Pump1')"), view("b", "Tank", "plant/Tank/level")];

describe("find and replace", () => {
  it("finds names, props, binding paths and script text, with wildcards and case", () => {
    const hits = findInProject(views, { text: "pump" });
    expect(hits.map((h) => h.property).sort()).toEqual(["bindings.value.path", "events.0.actions.0.script", "name", "name", "props.text"].sort());
    expect(findInProject(views, { text: "pump", caseSensitive: true })).toHaveLength(0);
    expect(findInProject(views, { text: "plant/*/level" })).toHaveLength(1);
    expect(findInProject(views, { text: "Pump ? speed" })).toHaveLength(1);
    expect(findInProject(views, { text: "a.b" })).toHaveLength(0);
    expect(findInProject(views, { text: "" })).toEqual([]);
  });

  it("replaces and leaves the source view untouched", () => {
    const hits = findInProject(views, { text: "Pump*" , caseSensitive: true });
    const next = replaceHits(views[0]!, hits, { text: "Pump*", caseSensitive: true }, "Motor");
    expect(JSON.stringify(next)).toContain("Motor");
    expect(JSON.stringify(views[0])).not.toContain("Motor");
  });

  it("panel replaces all through onReplaceViews, reveals a hit, and undoes", () => {
    const onReplaceViews = vi.fn();
    const onReveal = vi.fn();
    render(<FindReplacePanel views={views} onReplaceViews={onReplaceViews} onReveal={onReveal} />);
    fireEvent.change(screen.getByLabelText("Find"), { target: { value: "tank" } });
    expect(screen.getByTestId("find-count").textContent).toBe("2 matches in 1 view");
    fireEvent.click(screen.getByText(/props\.text/));
    expect(onReveal).toHaveBeenCalledWith("b", "label");
    fireEvent.change(screen.getByLabelText("Replace with"), { target: { value: "Vessel" } });
    fireEvent.click(screen.getByText("Replace all"));
    const [changed] = onReplaceViews.mock.calls[0]![0] as ViewDocument[];
    expect(JSON.stringify(changed)).toContain("Vessel");
    fireEvent.click(screen.getByText(/Undo last replace/));
    expect(onReplaceViews.mock.calls[1]![0]).toEqual([views[1]]);
  });
});

describe("diagnostics", () => {
  it("counts bindings, subscriptions and messages", () => {
    expect(bindingStats(views[0]!)).toEqual({ bindings: 1, tagPaths: 1 });
    let now = 0;
    const meter = new DiagnosticsMeter(() => now);
    let handler: (d: string) => void = () => {};
    const t = meteredTransport({ send() {}, onMessage: (h) => ((handler = h), () => {}), onClose: () => () => {}, close() {} }, meter);
    t.onMessage(() => {});
    handler("x");
    handler("y");
    expect(meter.rate()).toBeCloseTo(0.4);
    now = 10_000;
    expect(meter.rate()).toBe(0);
    meter.render(4);
    meter.render(6);
    expect(meter.averageRenderMs()).toBe(5);
  });

  it("shows the gateway log with a level filter", async () => {
    const gatewayLogs = vi.fn().mockResolvedValue([{ ts: "t", level: "ERROR", logger: "gw", message: "boom" }]);
    render(<DiagnosticsPanel view={views[0]!} meter={new DiagnosticsMeter()} client={{ gatewayLogs } as never} onOpenConsole={() => {}} />);
    expect(screen.getByTestId("diag-bindings").textContent).toBe("1");
    fireEvent.click(screen.getByRole("tab", { name: "Gateway log" }));
    expect(await screen.findByText(/boom/)).toBeTruthy();
    expect(gatewayLogs).toHaveBeenCalledWith("INFO", 100);
    await act(async () => {
      fireEvent.change(screen.getByLabelText("Level"), { target: { value: "ERROR" } });
    });
    expect(gatewayLogs).toHaveBeenLastCalledWith("ERROR", 100);
  });
});
