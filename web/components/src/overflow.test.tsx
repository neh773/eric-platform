import { readFileSync } from "node:fs";
import path from "node:path";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ProgressBar, SimpleGauge } from "./display2";
import { fitSvgText } from "./read";
import { CylindricalTank, Sensor } from "./symbols";
import { LinearGauge, NumericDisplay } from "./widgets";

/** U-1: long numbers stay inside the component's frame. HTML text is clipped with an ellipsis and a tooltip;
 * SVG text is squeezed to the width it has. The real layout is checked in designer/e2e/u1-layout.spec.ts. */

const noop = () => {};
const css = (file: string) => readFileSync(path.resolve(__dirname, file), "utf8").replace(/\s+/g, " ");

describe("fitSvgText", () => {
  it("leaves short text alone and squeezes long text to the width", () => {
    expect(fitSvgText("42.5", 16, 60)).toEqual({});
    expect(fitSvgText("123456789.123", 16, 60)).toEqual({ textLength: 60, lengthAdjust: "spacingAndGlyphs" });
  });
});

describe("numeric overflow", () => {
  it("keeps the numeric display on one clipped line with the whole value in the tooltip", () => {
    render(<NumericDisplay nodeId="n" locale="en-US" onChange={noop} props={{ value: 123456789012.345, decimals: 3, prefix: "~", suffix: " m3/h" }} />);
    const value = screen.getByTestId("numeric-value");
    expect(value).toHaveTextContent("~123456789012.345 m3/h");
    expect(value).toHaveAttribute("title", "~123456789012.345 m3/h");
    const sheet = css("components.css");
    expect(sheet).toMatch(/\.oip-numeric \{[^}]*overflow: hidden;/);
    expect(sheet).toMatch(/\.oip-numeric > span \{[^}]*white-space: nowrap;[^}]*text-overflow: ellipsis;/);
  });

  it("gives the gauge and progress values a tooltip and an ellipsis instead of overlapping the label", () => {
    render(
      <div>
        <LinearGauge nodeId="g" locale="en-US" onChange={noop} props={{ label: "Flow", unit: "kg/h", value: 9876543210.5 }} />
        <ProgressBar nodeId="p" locale="en-US" onChange={noop} props={{ label: "Batch", value: 98765432.1, max: 1e9, valueFormat: "value" }} />
      </div>,
    );
    expect(screen.getByTitle("9876543210.5 kg/h")).toHaveClass("oip-gauge-value");
    expect(screen.getByTitle("98765432.1")).toHaveClass("oip-progress-value");
    expect(css("components.css")).toMatch(/\.oip-gauge-head > span, \.oip-progress-head > span \{[^}]*min-width: 0;[^}]*text-overflow: ellipsis;/);
  });

  it("scales a long simple gauge value to the inside of the arc and leaves a short one alone", () => {
    const { container, rerender } = render(
      <SimpleGauge nodeId="s" locale="en-US" onChange={noop} props={{ label: "Speed", value: 123456789.123, decimals: 3, thickness: 10 }} />,
    );
    const text = () => container.querySelector(".oip-simple-gauge-value")!;
    expect(text()).toHaveTextContent("123456789.123");
    expect(Number(text().getAttribute("textLength"))).toBeLessThanOrEqual(80);
    expect(text()).toHaveAttribute("lengthAdjust", "spacingAndGlyphs");
    rerender(<SimpleGauge nodeId="s" locale="en-US" onChange={noop} props={{ label: "Speed", value: 42, decimals: 0 }} />);
    expect(text()).not.toHaveAttribute("textLength");
  });

  it("scales long sensor and tank readings to the symbol's width", () => {
    const { container } = render(
      <div>
        <Sensor nodeId="a" locale="en-US" onChange={noop} props={{ tag: "PT", value: 1234567.891, decimals: 3, unit: "bar" }} />
        <CylindricalTank nodeId="b" locale="en-US" onChange={noop} props={{ level: 123456.789, max: 1e6, unit: "m3", decimals: 3 }} />
      </div>,
    );
    const sensorValue = container.querySelector(".oip-symbol-sensor .oip-symbol-value")!;
    expect(sensorValue).toHaveTextContent("1234567.891 bar");
    expect(Number(sensorValue.getAttribute("textLength"))).toBeLessThanOrEqual(100);
    const tankText = container.querySelector(".oip-symbol-cylindrical-tank .oip-symbol-text")!;
    expect(tankText).toHaveTextContent("123456.789 m3");
    expect(Number(tankText.getAttribute("textLength"))).toBeLessThanOrEqual(68);
  });
});
