import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { qualityLabel, qualityLevel } from "./bindings";
import { createSession, MemoryTagServer, createLinkedTransports, type ComponentRegistry, type HmiComponentProps } from "./index";
import { parseView } from "./model";
import { ViewRenderer } from "./ViewRenderer";

/**
 * P2-15 (TAG-002): a component bound to a tag whose quality is not Good shows an overlay naming the quality level,
 * and clears it when the quality is Good again. The rule per level: good shows none; uncertain, bad and error each
 * have their own overlay, and the flag names the level and the gateway's subcode.
 */

describe("quality overlay rule per level", () => {
  it("names each level, and the subcode the gateway sent", () => {
    expect(qualityLevel({ code: "good" })).toBe("good");
    expect(qualityLevel({ code: "good", label: "Good_Overridden" })).toBe("good");
    expect(qualityLevel({ code: "uncertain" })).toBe("uncertain");
    expect(qualityLevel({ code: "uncertain_initial" })).toBe("uncertain");
    expect(qualityLevel({ code: "bad" })).toBe("bad");
    expect(qualityLevel({ code: "bad_not_connected" })).toBe("bad");
    // The gateway's Error level arrives as bad, and its name says Error.
    expect(qualityLevel({ code: "bad", label: "Error_ExpressionEval" })).toBe("error");

    expect(qualityLabel({ code: "uncertain" })).toBe("Uncertain");
    expect(qualityLabel({ code: "uncertain", label: "Uncertain_LastKnownValue" })).toBe("Uncertain: last known value");
    expect(qualityLabel({ code: "uncertain_initial" })).toBe("Uncertain: initial value");
    expect(qualityLabel({ code: "bad" })).toBe("Bad");
    expect(qualityLabel({ code: "bad", label: "Bad_AccessDenied" })).toBe("Bad: access denied");
    expect(qualityLabel({ code: "bad", label: "Bad" })).toBe("Bad");
    expect(qualityLabel({ code: "bad", label: "Error_ExpressionEval" })).toBe("Error: expression eval");
    expect(qualityLabel({ code: "bad_not_connected", label: "Bad_NotConnected" })).toBe("Not connected");
  });
});

const registry: ComponentRegistry = {
  get(type: string) {
    if (type !== "probe") return undefined;
    return {
      type,
      title: type,
      schema: { type: "object" },
      Component: ({ nodeId, props }: HmiComponentProps) => <span data-testid={nodeId}>{String(props.text ?? "")}</span>,
    };
  },
  list: () => [],
};

function view() {
  return parseView({
    apiVersion: "oip.hmi/v1",
    kind: "View",
    metadata: { name: "page", path: "page" },
    spec: {
      width: 200,
      height: 100,
      params: {},
      root: {
        id: "root",
        kind: "container",
        name: "Root",
        layout: "flex",
        props: { direction: "column" },
        children: [
          { id: "label", kind: "component", name: "label", type: "probe", props: { text: "" }, bindings: { text: { kind: "tag", mode: "direct", path: "plant/state" } } },
          { id: "sum", kind: "component", name: "sum", type: "probe", props: { text: "" }, bindings: { text: { kind: "expression", expression: "{[default]plant/state} + 1" } } },
        ],
      },
    },
  });
}

const frames: FrameRequestCallback[] = [];

afterEach(() => {
  vi.unstubAllGlobals();
});

/**
 * Tag updates are delivered on microtasks and painted on the next animation frame (HMP-111).
 * happy-dom implements that frame with setImmediate, whose order against setTimeout is not fixed, so a
 * short timeout loop passes or fails depending on the event-loop phase. Queue the frame and run it.
 */
function captureFrames() {
  frames.length = 0;
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    frames.push(callback);
    return frames.length;
  });
  vi.stubGlobal("cancelAnimationFrame", () => {});
}

async function paint() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  await act(async () => {
    for (const frame of frames.splice(0)) frame(0);
  });
}

function overlayOf(id: string): HTMLElement | null {
  return document.querySelector(`[data-node-id='${id}'] > .oip-quality-overlay`);
}

describe("quality overlay on a bound label", () => {
  it("shows the uncertain, bad and error overlays with their names, and clears each when Good", async () => {
    captureFrames();
    const server = new MemoryTagServer();
    server.set("plant/state", 1);
    const [transport, serverSide] = createLinkedTransports();
    server.connect(serverSide);
    render(<ViewRenderer view={view()} registry={registry} transport={transport} session={createSession({ id: "s" })} />);
    await paint();
    // Re-query. A frame can replace the node a previous lookup returned.
    expect(screen.getByTestId("label")).toHaveTextContent("1");
    expect(overlayOf("label")).toBeNull();

    const cases = [
      { quality: "uncertain", code: "uncertain", label: "Uncertain_LastKnownValue", level: "uncertain", text: "Uncertain: last known value" },
      { quality: "bad", code: "bad", label: "Bad_AccessDenied", level: "bad", text: "Bad: access denied" },
      { quality: "bad", code: "bad", label: "Error_ExpressionEval", level: "error", text: "Error: expression eval" },
      { quality: "bad", code: "bad_not_connected", label: "Bad_NotConnected", level: "bad", text: "Not connected" },
    ] as const;
    let value = 1;
    for (const c of cases) {
      value += 1;
      server.set("plant/state", value, c.quality, c.code, c.label);
      await paint();
      for (const id of ["label", "sum"]) {
        const overlay = overlayOf(id);
        expect(overlay, `${id} ${c.label}`).not.toBeNull();
        expect(overlay!.dataset.quality).toBe(c.quality);
        expect(overlay!.dataset.level).toBe(c.level);
        expect(overlay!.dataset.code).toBe(c.code);
        expect(overlay!.dataset.label).toBe(c.label);
        expect(overlay!.querySelector("[role='status']")).toHaveTextContent(c.text);
      }
      // The value still shows under the overlay.
      expect(screen.getByTestId("label")).toHaveTextContent(String(value));

      server.set("plant/state", value, "good");
      await paint();
      expect(overlayOf("label")).toBeNull();
      expect(overlayOf("sum")).toBeNull();
    }
    server.close();
  });
});
