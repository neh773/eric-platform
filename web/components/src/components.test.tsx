import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { formatNumber } from "./read";
import { Button, Indicator, Label, LinearGauge, NumericDisplay, TextInput, Toggle, TrendLine } from "./widgets";

const noop = () => {};

describe("components", () => {
  it("formats numbers with the session locale and no grouping", () => {
    expect(formatNumber(1234.5, 1, "en-US")).toBe("1234.5");
    expect(formatNumber(1234.5, 1, "de-DE")).toBe("1234,5");
  });

  it("shows a numeric label value as text", () => {
    render(<Label nodeId="n" locale="en-US" props={{ text: 42 }} onChange={noop} />);
    expect(screen.getByText("42")).toBeInTheDocument();
  });

  it("exposes an accessible name for each initial component", () => {
    const { rerender } = render(
      <div>
        <Label nodeId="a" locale="en-US" props={{ text: "Tank farm", align: "center" }} onChange={noop} />
        <NumericDisplay nodeId="b" locale="en-US" props={{ value: 12.5, decimals: 1, suffix: " m" }} onChange={noop} />
        <Button nodeId="c" locale="en-US" props={{ text: "Start pump", enabled: true }} onChange={noop} />
        <Toggle nodeId="d" locale="en-US" props={{ value: true, label: "Pump command" }} onChange={noop} />
        <TextInput nodeId="e" locale="en-US" props={{ value: "note", label: "Note" }} onChange={noop} />
        <Indicator nodeId="f" locale="en-US" props={{ value: true, label: "Pump", onText: "Running", offText: "Stopped" }} onChange={noop} />
        <LinearGauge nodeId="g" locale="en-US" props={{ value: 40, min: 0, max: 80, label: "Level", unit: "m" }} onChange={noop} />
        <TrendLine nodeId="h" locale="en-US" props={{ value: 40, min: 0, max: 80, label: "Level trend" }} onChange={noop} />
      </div>,
    );
    expect(screen.getByText("Tank farm")).toHaveAttribute("data-align", "center");
    expect(screen.getByTestId("numeric-value")).toHaveTextContent("12.5 m");
    expect(screen.getByRole("button", { name: "Start pump" })).toBeEnabled();
    expect(screen.getByRole("switch", { name: "Pump command" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("textbox", { name: "Note" })).toHaveValue("note");
    expect(screen.getByRole("status", { name: "Pump: Running" })).toHaveAttribute("data-state", "on");
    expect(screen.getByRole("meter", { name: "Level" })).toHaveAttribute("aria-valuenow", "40");
    expect(screen.getByRole("img", { name: /Level trend, latest 40\.0/ })).toBeInTheDocument();
    rerender(
      <Indicator nodeId="f" locale="en-US" props={{ value: false, label: "Pump", onText: "Running", offText: "Stopped" }} onChange={noop} />,
    );
    expect(screen.getByRole("status", { name: "Pump: Stopped" })).toHaveAttribute("data-state", "off");
  });

  it("writes from a toggle, a button, and a text field", () => {
    const onChange = vi.fn();
    render(
      <div>
        <Toggle nodeId="d" locale="en-US" props={{ value: false, label: "Pump command" }} onChange={onChange} />
        <Button nodeId="c" locale="en-US" props={{ text: "Start pump", enabled: true, writeValue: true }} onChange={onChange} />
        <Button nodeId="x" locale="en-US" props={{ text: "Disabled", enabled: false, writeValue: true }} onChange={onChange} />
        <TextInput nodeId="e" locale="en-US" props={{ value: "", label: "Note" }} onChange={onChange} />
      </div>,
    );
    fireEvent.click(screen.getByRole("switch", { name: "Pump command" }));
    expect(onChange).toHaveBeenCalledWith("value", true);
    fireEvent.click(screen.getByRole("button", { name: "Start pump" }));
    expect(onChange).toHaveBeenCalledWith("value", true);
    fireEvent.click(screen.getByRole("button", { name: "Disabled" }));
    expect(onChange).toHaveBeenCalledTimes(2);
    const field = screen.getByRole("textbox", { name: "Note" });
    fireEvent.change(field, { target: { value: "hold" } });
    fireEvent.keyDown(field, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("value", "hold");
  });

  it("clamps the gauge fill and appends trend samples", () => {
    const { rerender } = render(<LinearGauge nodeId="g" locale="en-US" props={{ value: 150, min: 0, max: 100, label: "Level" }} onChange={noop} />);
    expect(screen.getByRole("meter")).toHaveAttribute("aria-valuenow", "100");
    expect(document.querySelector(".oip-gauge-fill")).toHaveStyle({ width: "100%" });
    rerender(<TrendLine nodeId="h" locale="en-US" props={{ value: 10, capacity: 3, min: 0, max: 20, label: "Trend" }} onChange={noop} />);
    rerender(<TrendLine nodeId="h" locale="en-US" props={{ value: 20, capacity: 3, min: 0, max: 20, label: "Trend" }} onChange={noop} />);
    expect(screen.getByRole("img")).toHaveAttribute("data-points", "2");
  });
});
