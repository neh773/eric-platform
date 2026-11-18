import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MapView, project, readLayers, readMarkers, readShapes, tileAddress, unproject } from "./map";
import { allowedComponentTypes, componentRegistry, defaultsFromSchema } from "./registry";

/** HMP-045: interactive map. doc source: FEATURES HMP-045. Tiles come from a fake local source, never a provider. */

const TILES = "/fake-tiles/{z}/{x}/{y}.png";

function mount(props: Record<string, unknown>, onChange = vi.fn(), onEvent = vi.fn()) {
  const view = render(<MapView nodeId="m" locale="en-US" onChange={onChange} onEvent={onEvent} props={props} />);
  return { ...view, onChange, onEvent };
}

const markers = JSON.stringify([
  { id: "a", lat: 0, lon: 0, title: "Pump house", popup: "Line 1 <b>intake</b>" },
  { id: "b", lat: 0.0005, lon: 0.0005, title: "Tank farm", layer: "Tanks" },
]);

describe("map maths", () => {
  it("projects and unprojects the same position", () => {
    const p = project(48.2, 16.37, 12);
    const back = unproject(p.x, p.y, 12);
    expect(back.lat).toBeCloseTo(48.2, 6);
    expect(back.lon).toBeCloseTo(16.37, 6);
    expect(project(0, 0, 0)).toEqual({ x: 128, y: 128 });
  });

  it("fills a tile template", () => {
    expect(tileAddress("/t/{z}/{x}/{y}.png", 3, 4, 5)).toBe("/t/3/4/5.png");
  });
});

describe("map props", () => {
  it("has no default tile source and registers as a component", () => {
    const schema = componentRegistry().get("map")!.schema;
    expect(defaultsFromSchema(schema).tileUrl).toBe("");
    expect(schema.additionalProperties).toBe(false);
    expect(allowedComponentTypes().has("map")).toBe(true);
  });

  it("reads layers, markers and shapes, dropping malformed entries", () => {
    expect(readLayers({ tileUrl: TILES, tileLayers: '[{"name":"Flood","url":"/f/{z}/{x}/{y}","overlay":true,"visible":false,"opacity":3},{"name":"","url":"x"},5]' })).toEqual([
      { name: "Base", url: TILES, overlay: false, visible: true, opacity: 1, attribution: "" },
      { name: "Flood", url: "/f/{z}/{x}/{y}", overlay: true, visible: false, opacity: 1, attribution: "" },
    ]);
    expect(readMarkers('[{"lat":1,"lon":2},{"id":"x","lat":95,"lon":0},{"id":"y","lat":"1","lon":2}]').map((m) => m.id)).toEqual(["m0"]);
    expect(readShapes('[{"type":"circle","center":[1,2],"radius":50},{"type":"polygon","points":[[1,2]]},{"type":"line"}]')).toHaveLength(1);
  });
});

describe("MapView", () => {
  it("shows a grid and no images when no tile template is set", () => {
    const { container } = mount({ centerLat: 10, centerLon: 20, zoom: 4 });
    expect(container.querySelectorAll("img")).toHaveLength(0);
    expect(screen.getByText("No tile source")).toBeInTheDocument();
    expect(container.querySelector(".oip-map")).toHaveAttribute("data-has-tiles", "false");
  });

  it("loads tiles only from the configured template, same-origin or http(s)", () => {
    const { container } = mount({ tileUrl: TILES, zoom: 2 });
    const srcs = [...container.querySelectorAll("img")].map((img) => img.getAttribute("src"));
    expect(srcs.length).toBeGreaterThan(0);
    for (const src of srcs) expect(src).toMatch(/^\/fake-tiles\/2\/[0-3]\/[0-3]\.png$/);
    const blocked = mount({ tileUrl: "javascript:alert({z}{x}{y})", zoom: 2 });
    expect(blocked.container.querySelectorAll("img")).toHaveLength(0);
  });

  it("follows bound centre and zoom", () => {
    const { container, rerender } = mount({ tileUrl: TILES, centerLat: 0, centerLon: 0, zoom: 3 });
    expect(container.querySelector(".oip-map")).toHaveAttribute("data-zoom", "3");
    rerender(<MapView nodeId="m" locale="en-US" onChange={() => {}} props={{ tileUrl: TILES, centerLat: 40, centerLon: -3, zoom: 6 }} />);
    expect(container.querySelector(".oip-map")).toHaveAttribute("data-zoom", "6");
    expect(container.querySelector(".oip-map")).toHaveAttribute("data-center", "40.00000,-3.00000");
  });

  it("zooms with the buttons and writes the zoom back, within the limits", () => {
    const { onChange } = mount({ zoom: 5, minZoom: 4, maxZoom: 6 });
    fireEvent.click(screen.getByLabelText("Zoom in"));
    expect(onChange).toHaveBeenCalledWith("zoom", 6);
    expect(screen.getByLabelText("Zoom in")).toBeDisabled();
    fireEvent.click(screen.getByLabelText("Zoom out"));
    fireEvent.click(screen.getByLabelText("Zoom out"));
    expect(onChange).toHaveBeenLastCalledWith("zoom", 4);
    expect(screen.getByLabelText("Zoom out")).toBeDisabled();
  });

  it("can hide the zoom controls", () => {
    mount({ showZoomControls: false });
    expect(screen.queryByLabelText("Zoom in")).toBeNull();
  });

  it("opens a plain-text popup on a marker click and reports the selection", () => {
    const { onChange, onEvent, container } = mount({ zoom: 16, markers });
    fireEvent.click(screen.getByLabelText("Pump house"));
    const popup = screen.getByRole("dialog", { name: "Pump house" });
    expect(within(popup).getByText("Line 1 <b>intake</b>")).toBeInTheDocument();
    expect(popup.querySelector("b")).toBeNull();
    expect(onChange).toHaveBeenCalledWith("selectedMarker", "a");
    expect(onEvent).toHaveBeenCalledWith("markerClick");
    fireEvent.click(within(popup).getByLabelText("Close popup"));
    expect(container.querySelector(".oip-map-popup")).toBeNull();
    expect(onChange).toHaveBeenLastCalledWith("selectedMarker", "");
  });

  it("shows the popup of a bound selected marker", () => {
    mount({ zoom: 16, markers, selectedMarker: "b" });
    expect(screen.getByRole("dialog", { name: "Tank farm" })).toBeInTheDocument();
  });

  it("reports a click on empty map as a position", () => {
    const { onChange, onEvent } = mount({ zoom: 3, centerLat: 0, centerLon: 0 });
    const surface = screen.getByTestId("map-surface");
    fireEvent.pointerDown(surface, { clientX: 100, clientY: 100, button: 0, pointerId: 1 });
    fireEvent.pointerUp(surface, { clientX: 100, clientY: 100, pointerId: 1 });
    expect(onChange).toHaveBeenCalledWith("clickLat", expect.any(Number));
    expect(onChange).toHaveBeenCalledWith("clickLon", expect.any(Number));
    expect(onEvent).toHaveBeenCalledWith("mapClick");
  });

  it("pans by dragging and writes the new centre back", () => {
    const { onChange, container } = mount({ zoom: 4, centerLat: 0, centerLon: 0 });
    const surface = screen.getByTestId("map-surface");
    fireEvent.pointerDown(surface, { clientX: 200, clientY: 200, button: 0, pointerId: 1 });
    fireEvent.pointerMove(surface, { clientX: 150, clientY: 200, pointerId: 1 });
    fireEvent.pointerUp(surface, { clientX: 150, clientY: 200, pointerId: 1 });
    const lon = onChange.mock.calls.find((call) => call[0] === "centerLon")![1] as number;
    expect(lon).toBeGreaterThan(0);
    expect(onChange).toHaveBeenCalledWith("centerLat", 0);
    expect(container.querySelector(".oip-map")).toHaveAttribute("data-zoom", "4");
  });

  it("toggles overlay tile layers and marker layers, and switches base layers", () => {
    const { container } = mount({
      tileUrl: TILES,
      tileLayers: JSON.stringify([
        { name: "Flood", url: "/fake-overlay/{z}/{x}/{y}.png", overlay: true },
        { name: "Alt base", url: "/fake-alt/{z}/{x}/{y}.png" },
      ]),
      markers,
      zoom: 16,
    });
    const layerNames = () => [...container.querySelectorAll(".oip-map-tiles")].map((el) => el.getAttribute("data-layer"));
    expect(layerNames()).toEqual(["Base", "Flood"]);
    fireEvent.click(screen.getByLabelText("Flood"));
    expect(layerNames()).toEqual(["Base"]);
    fireEvent.click(screen.getByLabelText("Alt base"));
    expect(layerNames()).toEqual(["Alt base"]);
    expect(screen.getByLabelText("Tank farm")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Tanks"));
    expect(screen.queryByLabelText("Tank farm")).toBeNull();
    expect(screen.getByLabelText("Pump house")).toBeInTheDocument();
  });

  it("draws shape overlays and hides them with their layer", () => {
    const { container } = mount({
      zoom: 10,
      shapes: JSON.stringify([
        { layer: "Zones", type: "polygon", points: [[0, 0], [0, 0.1], [0.1, 0.1]] },
        { layer: "Zones", type: "circle", center: [0, 0], radius: 5000 },
        { type: "polyline", points: [[0, 0], [0.1, 0.1]], color: "not a colour!" },
      ]),
    });
    expect(container.querySelectorAll("[data-shape]")).toHaveLength(3);
    expect(container.querySelector("[data-shape='polyline']")).toHaveAttribute("stroke", "var(--oip-color-accent)");
    fireEvent.click(screen.getByLabelText("Zones"));
    expect(container.querySelectorAll("[data-shape]")).toHaveLength(1);
  });

  it("shows layer attribution as text", () => {
    mount({ tileUrl: TILES, tileAttribution: "Local test tiles" });
    expect(screen.getByText("Local test tiles")).toBeInTheDocument();
  });

  it("zooms with the keyboard", () => {
    const { onChange } = mount({ zoom: 5 });
    act(() => screen.getByRole("application").focus());
    fireEvent.keyDown(screen.getByRole("application"), { key: "+" });
    expect(onChange).toHaveBeenCalledWith("zoom", 6);
  });
});
