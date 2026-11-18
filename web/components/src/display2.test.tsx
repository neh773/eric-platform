import { readFileSync } from "node:fs";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { readChoices, readPoints, sameValue, themeColor } from "./choices";
import { arcPath, Icon, ProgressBar, SimpleGauge, Sparkline, sparkCoords } from "./display2";
import { iconNames, iconSet } from "./icons";

/** C-11 display components.
 */

const noop = () => {};

describe("simple gauge", () => {
  it("exposes a clamped meter and draws the value arc", () => {
    const { rerender } = render(
      <SimpleGauge nodeId="g" locale="en-US" onChange={noop} props={{ value: 25, min: 0, max: 50, label: "Speed", unit: "rpm", decimals: 0 }} />,
    );
    const meter = screen.getByRole("meter", { name: "Speed" });
    expect(meter).toHaveAttribute("aria-valuenow", "25");
    expect(meter).toHaveAttribute("aria-valuetext", "25 rpm");
    expect(meter).toHaveAttribute("data-fraction", "0.5000");
    expect(document.querySelector(".oip-simple-gauge-arc")).not.toBeNull();
    rerender(<SimpleGauge nodeId="g" locale="en-US" onChange={noop} props={{ value: 500, min: 0, max: 50, label: "Speed" }} />);
    expect(screen.getByRole("meter")).toHaveAttribute("aria-valuenow", "50");
    rerender(<SimpleGauge nodeId="g" locale="en-US" onChange={noop} props={{ value: -5, min: 0, max: 50, label: "Speed" }} />);
    expect(screen.getByRole("meter")).toHaveAttribute("data-fraction", "0.0000");
    expect(document.querySelector(".oip-simple-gauge-arc")).toBeNull();
    rerender(<SimpleGauge nodeId="g" locale="en-US" onChange={noop} props={{ label: "Speed" }} />);
    expect(screen.getByRole("meter")).not.toHaveAttribute("aria-valuenow");
    expect(screen.getByRole("meter")).toHaveAttribute("aria-valuetext", "No value");
  });

  it("uses a theme token for the arc color and builds arcs on the expected side", () => {
    render(<SimpleGauge nodeId="g" locale="en-US" onChange={noop} props={{ value: 10, color: "alarm" }} />);
    expect((document.querySelector(".oip-simple-gauge-arc") as SVGPathElement).style.stroke).toBe("var(--oip-color-alarm)");
    expect(arcPath(50, 50, 40, -180, 0)).toBe("M 10.00 50.00 A 40 40 0 0 1 90.00 50.00");
    expect(arcPath(50, 50, 40, -210, 30)).toContain(" 0 1 1 ");
  });
});

describe("progress bar", () => {
  it("reports value, percent text, and indeterminate state", () => {
    const { rerender } = render(<ProgressBar nodeId="p" locale="en-US" onChange={noop} props={{ value: 30, min: 0, max: 120, label: "Batch" }} />);
    const bar = screen.getByRole("progressbar", { name: "Batch" });
    expect(bar).toHaveAttribute("aria-valuenow", "30");
    expect(screen.getByText("25%")).toBeInTheDocument();
    expect(document.querySelector(".oip-progress-fill")).toHaveStyle({ width: "25%" });
    rerender(<ProgressBar nodeId="p" locale="en-US" onChange={noop} props={{ value: 30, max: 120, label: "Batch", valueFormat: "value" }} />);
    expect(screen.getByText("30.0")).toBeInTheDocument();
    rerender(<ProgressBar nodeId="p" locale="en-US" onChange={noop} props={{ value: 30, label: "Batch", indeterminate: true }} />);
    expect(screen.getByRole("progressbar")).not.toHaveAttribute("aria-valuenow");
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuetext", "Working");
    rerender(<ProgressBar nodeId="p" locale="en-US" onChange={noop} props={{ value: 300, label: "Batch", showValue: false }} />);
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "100");
    expect(document.querySelector(".oip-progress-value")).toBeNull();
  });
});

describe("icon", () => {
  it("renders every glyph of the original set", () => {
    expect(iconNames.length).toBeGreaterThanOrEqual(20);
    for (const name of iconNames) {
      const { unmount } = render(<Icon nodeId="i" locale="en-US" onChange={noop} props={{ name, label: name }} />);
      const svg = screen.getByRole("img", { name });
      expect(svg).toHaveAttribute("data-icon", name);
      expect(svg.children.length).toBe(iconSet[name].shapes.length);
      unmount();
    }
  });

  it("applies the color prop, falls back to text color, and is decorative without a label", () => {
    const { rerender } = render(<Icon nodeId="i" locale="en-US" onChange={noop} props={{ name: "pump", color: "ok", label: "Pump" }} />);
    expect(screen.getByRole("img", { name: "Pump" }).style.color).toBe("var(--oip-color-ok)");
    rerender(<Icon nodeId="i" locale="en-US" onChange={noop} props={{ name: "pump", color: "#ff8800", label: "Pump" }} />);
    expect(screen.getByRole("img", { name: "Pump" }).style.color).toMatch(/^(#ff8800|rgb\(255, 136, 0\))$/);
    rerender(<Icon nodeId="i" locale="en-US" onChange={noop} props={{ name: "pump", color: "red;background:url(x)", label: "Pump" }} />);
    expect(screen.getByRole("img", { name: "Pump" }).style.color).toBe("currentcolor");
    rerender(<Icon nodeId="i" locale="en-US" onChange={noop} props={{ name: "valve" }} />);
    const svg = document.querySelector("svg.oip-icon")!;
    expect(svg).toHaveAttribute("aria-hidden", "true");
    expect(screen.queryByRole("img")).toBeNull();
    rerender(<Icon nodeId="i" locale="en-US" onChange={noop} props={{ name: "not-an-icon" }} />);
    expect(document.querySelector("svg.oip-icon")!.children.length).toBe(0);
    expect(document.querySelector("svg.oip-icon")).toHaveAttribute("data-icon", "");
  });
});

describe("sparkline", () => {
  it("plots text or bound points, auto scales, and marks the last point", () => {
    const { rerender } = render(<Sparkline nodeId="s" locale="en-US" onChange={noop} props={{ points: "1, 3, x, 2", label: "Flow" }} />);
    expect(screen.getByRole("img", { name: "Flow, 3 points, last 2.0" })).toHaveAttribute("data-points", "3");
    expect(document.querySelector("polyline")).toHaveAttribute("points", "0.00,100.00 50.00,0.00 100.00,50.00");
    expect(document.querySelector(".oip-sparkline-last")).not.toBeNull();
    rerender(
      <Sparkline
        nodeId="s"
        locale="en-US"
        onChange={noop}
        props={{ points: [{ value: 10 }, { value: 30 }], label: "Flow", autoScale: false, min: 0, max: 40, desiredLow: 10, desiredHigh: 20, showLast: false }}
      />,
    );
    expect(document.querySelector("polyline")).toHaveAttribute("points", "0.00,75.00 100.00,25.00");
    const band = document.querySelector(".oip-sparkline-band")!;
    expect(band).toHaveAttribute("y", "50");
    expect(band).toHaveAttribute("height", "25");
    expect(document.querySelector(".oip-sparkline-last")).toBeNull();
    rerender(<Sparkline nodeId="s" locale="en-US" onChange={noop} props={{ label: "Flow" }} />);
    expect(screen.getByRole("img", { name: "Flow, 0 points, last no samples" })).toBeInTheDocument();
    expect(sparkCoords([5], 4, 6)).toEqual([{ x: 50, y: 50 }]);
  });
});

describe("shared helpers", () => {
  it("parses choices, points, values, and colors", () => {
    expect(readChoices("a:Alpha, b , :skip, a:Again")).toEqual([
      { value: "a", label: "Alpha" },
      { value: "b", label: "b" },
    ]);
    expect(readChoices("0:Off, 1:On", true)).toEqual([
      { value: 0, label: "Off" },
      { value: 1, label: "On" },
    ]);
    expect(readChoices([{ value: 2, text: "Two" }, { value: 3, label: "Three" }, 4, null, { label: "no value" }])).toEqual([
      { value: 2, label: "Two" },
      { value: 3, label: "Three" },
      { value: 4, label: "4" },
    ]);
    expect(readPoints([1, "2", { value: 3 }, null, "", Number.NaN])).toEqual([1, 2, 3]);
    expect(sameValue(1, "1")).toBe(true);
    expect(sameValue(true, "true")).toBe(true);
    expect(sameValue(1, 2)).toBe(false);
    expect(sameValue(null, "null")).toBe(false);
    expect(themeColor("warning", "x")).toBe("var(--oip-color-warning)");
    expect(themeColor("rgb(1, 2, 3)", "x")).toBe("rgb(1, 2, 3)");
    expect(themeColor("url(javascript:1)", "x")).toBe("x");
    expect(themeColor("", "x")).toBe("x");
  });

  it("styles every new component with theme tokens only", () => {
    const css = readFileSync("components/src/components2.css", "utf8");
    for (const name of [
      ".oip-simple-gauge", ".oip-progress", ".oip-icon", ".oip-sparkline", ".oip-datetime", ".oip-picker",
      ".oip-password", ".oip-radio", ".oip-textarea", ".oip-oneshot", ".oip-multistate", ".oip-link",
    ]) {
      expect(css).toContain(name);
    }
    expect(css).toContain("var(--oip-color-ink)");
    expect(css).not.toMatch(/#[0-9a-f]{3,8}\b/i);
  });
});
