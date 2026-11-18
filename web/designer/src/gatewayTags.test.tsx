import { render, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ViewRenderer, createSession, parseView, type ViewDocument } from "@oip/hmi-runtime";
import { allowedComponentTypes, componentRegistry, labelSchema } from "@oip/components";
import { createTagRuntime, seedStudio } from "./runtime";
import { isGatewayPath, qualityFromGateway, toTagValue } from "./gatewayTags";
import type { TagReading } from "./gatewayClient";
import { bindDroppedTag, decodeDraggedTag, dropProperty, encodeDraggedTag } from "./tagDrop";
import { blankView } from "./persistence";
import { appendComponent } from "./project";

function labelView(path: string): ViewDocument {
  return parseView(
    {
      apiVersion: "oip.hmi/v1",
      kind: "View",
      metadata: { name: "Probe", path: "probe" },
      spec: {
        params: {},
        width: 200,
        height: 100,
        root: {
          id: "root",
          kind: "container",
          name: "Root",
          layout: "coordinate",
          props: { mode: "fixed" },
          children: [
            {
              id: "label",
              kind: "component",
              name: "Label",
              type: "label",
              frame: { x: 0, y: 0, width: 100, height: 20 },
              props: { text: "" },
              bindings: { text: { kind: "tag", mode: "direct", path, transforms: [{ kind: "format", type: "numeric", mode: "number" }] } },
            },
          ],
        },
      },
    },
    { allowedTypes: allowedComponentTypes() },
  );
}

describe("gateway tag bridge", () => {
  it("reads only provider-qualified paths from the gateway and pushes changes to the preview", async () => {
    let value = 41.5;
    const read = vi.fn(async (paths: string[]): Promise<TagReading[]> =>
      paths.map((path) => ({ path, value, quality: "Good", timestamp: `t-${value}` })),
    );
    const runtime = createTagRuntime(seedStudio, read);
    const { unmount } = render(
      <ViewRenderer
        view={labelView("[default]line/level")}
        registry={componentRegistry()}
        transport={runtime.transport}
        session={createSession({ id: "probe", locale: "en-US" })}
      />,
    );
    const text = () => document.querySelector("[data-node-id='label'] .oip-label");
    await waitFor(() => expect(text()).toHaveTextContent("41.5"));
    expect(read).toHaveBeenCalledWith(["[default]line/level"]);
    expect(runtime.bridge!.paths()).toEqual(["[default]line/level"]);

    value = 77.25;
    await runtime.bridge!.poll();
    await waitFor(() => expect(text()).toHaveTextContent("77.25"));

    unmount();
    await waitFor(() => expect(runtime.bridge!.paths()).toEqual([]));
    runtime.bridge!.dispose();
    runtime.server.close();
  });

  it("keeps the gateway's quality name, so the overlay names the level and subcode (P2-15, TAG-002)", async () => {
    let quality = "Uncertain_LastKnownValue";
    const read = vi.fn(async (paths: string[]): Promise<TagReading[]> => paths.map((path) => ({ path, value: 3, quality, timestamp: `t-${quality}` })));
    const runtime = createTagRuntime(seedStudio, read);
    render(
      <ViewRenderer
        view={labelView("[default]line/level")}
        registry={componentRegistry()}
        transport={runtime.transport}
        session={createSession({ id: "probe", locale: "en-US" })}
      />,
    );
    const overlay = () => document.querySelector<HTMLElement>("[data-node-id='label'] > .oip-quality-overlay");
    await waitFor(() => expect(overlay()).toHaveTextContent("Uncertain: last known value"));
    expect(overlay()!.dataset.label).toBe("Uncertain_LastKnownValue");
    quality = "Error_ExpressionEval";
    await runtime.bridge!.poll();
    await waitFor(() => expect(overlay()).toHaveTextContent("Error: expression eval"));
    expect(overlay()!.dataset.level).toBe("error");
    runtime.bridge!.dispose();
    runtime.server.close();
  });

  it("marks watched tags not connected when the gateway read fails, keeping the last value", async () => {
    let fail = false;
    const runtime = createTagRuntime(seedStudio, async (paths) => {
      if (fail) throw new Error("offline");
      return paths.map((path) => ({ path, value: 5, quality: "Good", timestamp: "t" }));
    });
    runtime.transport.send(JSON.stringify({ type: "subscribe", paths: ["[default]a"] }));
    await runtime.bridge!.poll();
    expect(runtime.server.get("[default]a")).toMatchObject({ value: 5, code: "good" });
    fail = true;
    await runtime.bridge!.poll();
    expect(runtime.server.get("[default]a")).toMatchObject({ value: 5, quality: "bad", code: "bad_not_connected" });
    runtime.bridge!.dispose();
  });

  it("does not poll while offline, and resumes when a gateway is there again", async () => {
    vi.useFakeTimers();
    try {
      const read = vi.fn(async (paths: string[]): Promise<TagReading[]> => paths.map((path) => ({ path, value: 3, quality: "Good", timestamp: "t" })));
      const runtime = createTagRuntime(seedStudio, read);
      runtime.bridge!.setOnline(false);
      runtime.transport.send(JSON.stringify({ type: "subscribe", paths: ["[default]a"] }));
      await vi.advanceTimersByTimeAsync(2_000);
      expect(read).not.toHaveBeenCalled();
      expect(runtime.server.get("[default]a")).toMatchObject({ quality: "bad", code: "bad_not_connected" });

      runtime.bridge!.setOnline(true);
      await vi.advanceTimersByTimeAsync(600);
      expect(read).toHaveBeenCalled();
      expect(runtime.server.get("[default]a")).toMatchObject({ value: 3, code: "good" });
      runtime.bridge!.dispose();
    } finally {
      vi.useRealTimers();
    }
  });

  it("maps gateway quality labels and values onto the preview tag model", () => {
    expect(isGatewayPath("[default]a/b")).toBe(true);
    expect(isGatewayPath("plant/tank/level")).toBe(false);
    expect(qualityFromGateway("Good_Overridden")).toEqual({ quality: "good", code: "good" });
    expect(qualityFromGateway("Bad_NotConnected")).toEqual({ quality: "bad", code: "bad_not_connected" });
    expect(qualityFromGateway("Uncertain_InitialValue")).toEqual({ quality: "uncertain", code: "uncertain_initial" });
    expect(qualityFromGateway("Error_Configuration").quality).toBe("bad");
    expect(toTagValue({ a: 1 })).toBe('{"a":1}');
    expect(toTagValue(undefined)).toBeNull();
  });
});

describe("tag drop", () => {
  it("binds a dropped numeric tag to a label's text with a number format, the runtime's tag binding shape", () => {
    const added = appendComponent(blankView("probe", "Probe"), "label", "Label", { text: "x", align: "start" });
    const bound = bindDroppedTag(added.view, added.id, { path: "[default]line/level", dataType: "double" }, (type) =>
      componentRegistry().get(type)?.schema,
    );
    expect(bound?.prop).toBe("text");
    const node = bound!.view.spec.root.children[0]!;
    expect(node.kind === "component" && node.bindings?.text).toEqual({
      kind: "tag",
      mode: "direct",
      path: "[default]line/level",
      transforms: [{ kind: "format", type: "numeric", mode: "number" }],
    });
    // The bound view still parses as a runtime view document.
    expect(() => parseView(bound!.view, { allowedTypes: allowedComponentTypes() })).not.toThrow();
  });

  it("binds value when a component has one, and leaves string tags untransformed", () => {
    const added = appendComponent(blankView("probe", "Probe"), "numeric", "Numeric", { value: 0 });
    const bound = bindDroppedTag(added.view, added.id, { path: "[default]a", dataType: "double" }, (type) => componentRegistry().get(type)?.schema);
    expect(bound?.prop).toBe("value");
    const node = bound!.view.spec.root.children[0]!;
    expect(node.kind === "component" && node.bindings?.value).toEqual({ kind: "tag", mode: "direct", path: "[default]a" });
    const bare = appendComponent(blankView("probe", "Probe"), "numeric-entry", "Numeric entry", {});
    const filled = bindDroppedTag(bare.view, bare.id, { path: "[default]a", dataType: "double" }, (type) => componentRegistry().get(type)?.schema);
    const filledNode = filled!.view.spec.root.children[0]!;
    expect(filledNode.kind === "component" && filledNode.props.inputType).toBe("decimal");
    expect(dropProperty(labelSchema)).toBe("text");
    expect(decodeDraggedTag(encodeDraggedTag({ path: "[default]s", dataType: "string" }))).toEqual({ path: "[default]s", dataType: "string" });
    expect(decodeDraggedTag("not json")).toBeNull();
    expect(bindDroppedTag(added.view, "missing", { path: "[default]a" }, () => undefined)).toBeNull();
  });
});
