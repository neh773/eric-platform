import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AnalogIndicator, analogBands, analogState, readLimits } from "./analogIndicator";
import { allowedComponentTypes, componentRegistry, defaultsFromSchema } from "./registry";

/** HMP-043: moving analog indicator with alarm and setpoint bands. doc source: FEATURES HMP-043 */

const none = { lowLow: null, low: null, high: null, highHigh: null };
const all = { lowLow: 10, low: 20, high: 80, highHigh: 90 };

function mount(props: Record<string, unknown>) {
  return render(<AnalogIndicator nodeId="a" locale="en-US" onChange={() => {}} props={{ label: "Level", min: 0, max: 100, ...props }} />);
}

describe("analog indicator bands and state", () => {
  it("draws one normal band when there are no limits", () => {
    expect(analogBands(0, 100, none)).toEqual([{ from: 0, to: 100, kind: "normal", zone: "normal" }]);
  });

  it("draws alarm, warning and normal bands from the four limits", () => {
    expect(analogBands(0, 100, all).map((band) => [band.zone, band.kind, band.from, band.to])).toEqual([
      ["low-low", "alarm", 0, 10],
      ["low", "warning", 10, 20],
      ["normal", "normal", 20, 80],
      ["high", "warning", 80, 90],
      ["high-high", "alarm", 90, 100],
    ]);
  });

  it("has no band on a side whose limits are left out, and clips limits outside the scale", () => {
    expect(analogBands(0, 100, { ...none, highHigh: 90 }).map((band) => band.zone)).toEqual(["normal", "high-high"]);
    expect(analogBands(0, 100, { ...none, highHigh: 150, lowLow: -5 }).map((band) => band.zone)).toEqual(["normal"]);
    // out of order limits never give a reversed band
    for (const band of analogBands(0, 100, { lowLow: 60, low: 40, high: 30, highHigh: 20 })) expect(band.to).toBeGreaterThan(band.from);
  });

  it("names where a value sits against the limits", () => {
    expect(analogState(null, all)).toBe("none");
    expect(analogState(50, all)).toBe("normal");
    expect(analogState(85, all)).toBe("high");
    expect(analogState(95, all)).toBe("high-high");
    expect(analogState(15, all)).toBe("low");
    expect(analogState(5, all)).toBe("low-low");
    expect(analogState(80, all)).toBe("normal");
    expect(analogState(500, none)).toBe("normal");
  });

  it("reads only finite numbers as limits", () => {
    expect(readLimits({ lowLow: "1", low: Number.NaN, high: 5, highHigh: null })).toEqual({ lowLow: null, low: null, high: 5, highHigh: null });
  });
});

describe("AnalogIndicator", () => {
  it("is a meter that reports the value, the unit and the state in words", () => {
    mount({ value: 95, unit: "bar", ...all });
    const meter = screen.getByRole("meter", { name: "Level" });
    expect(meter).toHaveAttribute("aria-valuenow", "95");
    expect(meter).toHaveAttribute("aria-valuetext", "95.0 bar, high-high");
    expect(meter).toHaveAttribute("data-state", "high-high");
    expect(screen.getByText("High-high")).toBeInTheDocument();
  });

  it("moves the pointer with the value and marks the setpoint", () => {
    const { container, rerender } = mount({ value: 25, setpoint: 60 });
    const pointer = () => container.querySelector<HTMLElement>(".oip-analog-pointer")!;
    expect(pointer().style.left).toBe("25.000%");
    rerender(<AnalogIndicator nodeId="a" locale="en-US" onChange={() => {}} props={{ label: "Level", min: 0, max: 100, value: 75, setpoint: 60 }} />);
    expect(pointer().style.left).toBe("75.000%");
    const mark = container.querySelector<HTMLElement>(".oip-analog-setpoint")!;
    expect(mark.dataset.setpoint).toBe("60");
    expect(mark.style.left).toBe("60.000%");
  });

  it("clamps a pointer outside the scale to its end, and shows no pointer without a value", () => {
    const { container, rerender } = mount({ value: 400 });
    expect(container.querySelector<HTMLElement>(".oip-analog-pointer")!.style.left).toBe("100.000%");
    rerender(<AnalogIndicator nodeId="a" locale="en-US" onChange={() => {}} props={{ label: "Level", value: "bad" }} />);
    expect(container.querySelector(".oip-analog-pointer")).toBeNull();
    expect(screen.getByRole("meter", { name: "Level" })).toHaveAttribute("aria-valuetext", "No value");
  });

  it("lays the bands along the bottom edge when vertical, and hides them on request", () => {
    const { container, rerender } = mount({ value: 50, orientation: "vertical", ...all });
    const band = container.querySelector<HTMLElement>('[data-zone="high-high"]')!;
    expect(band.style.bottom).toBe("90.000%");
    expect(band.style.height).toBe("10.000%");
    rerender(<AnalogIndicator nodeId="a" locale="en-US" onChange={() => {}} props={{ label: "Level", value: 50, showBands: false, ...all }} />);
    expect(container.querySelector(".oip-analog-band")).toBeNull();
  });

  it("is registered in the display palette with defaults", () => {
    const module = componentRegistry().get("analog-indicator")!;
    expect(allowedComponentTypes().has("analog-indicator")).toBe(true);
    expect(module.category).toBe("display");
    expect(module.schema.additionalProperties).toBe(false);
    expect(defaultsFromSchema(module.schema)).toMatchObject({ min: 0, max: 100, showBands: true, orientation: "horizontal" });
  });
});
