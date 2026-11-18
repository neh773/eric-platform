import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { componentRegistry, defaultsFromSchema } from "./registry";
import { formatNumericEntry, NumericEntryField, parseNumericEntry } from "./numericEntry";

/** P4-03 (HMP-055): type, format, bounds, commit and cancel of the numeric entry field. */

const bounded = { inputType: "decimal" as const, minimum: 0, maximum: 100 };

function field(props: Record<string, unknown>, onChange = vi.fn(), locale = "en-US") {
  const view = render(<NumericEntryField nodeId="sp" locale={locale} props={{ label: "Setpoint", ...props }} onChange={onChange} />);
  return { input: screen.getByRole("textbox", { name: "Setpoint" }), onChange, ...view };
}

describe("numeric entry parsing", () => {
  it("accepts integers and decimals, with the locale's separators", () => {
    expect(parseNumericEntry("42", "en-US", bounded)).toEqual({ ok: true, value: 42 });
    expect(parseNumericEntry(" 12.5 ", "en-US", bounded)).toEqual({ ok: true, value: 12.5 });
    expect(parseNumericEntry("12,5", "de-DE", bounded)).toEqual({ ok: true, value: 12.5 });
    expect(parseNumericEntry("12.5", "de-DE", bounded)).toEqual({ ok: true, value: 12.5 });
    expect(parseNumericEntry("1234.5", "en-US", { ...bounded, maximum: null })).toEqual({ ok: true, value: 1234.5 });
    expect(parseNumericEntry("-0", "en-US", { ...bounded, minimum: null })).toEqual({ ok: true, value: 0 });
  });

  it("refuses text that is not a plain number", () => {
    for (const text of ["", "abc", "12a", "1.2.3", "1e3", "Infinity", "NaN", "--1", "0x10", "1,5.2", "1 234"]) {
      expect(parseNumericEntry(text, "de-DE", { ...bounded, maximum: null }).ok, text).toBe(false);
    }
    expect(parseNumericEntry("abc", "en-US", bounded)).toEqual({ ok: false, reason: "Enter a number." });
    // Digit grouping is not read while typing, so `1,234` is neither 1.234 nor 1234.
    expect(parseNumericEntry("1,234", "en-US", { ...bounded, maximum: null }).ok).toBe(false);
  });

  it("refuses a fraction for the integer type", () => {
    expect(parseNumericEntry("12.5", "en-US", { ...bounded, inputType: "integer" })).toEqual({ ok: false, reason: "Enter a whole number." });
    expect(parseNumericEntry("12", "en-US", { ...bounded, inputType: "integer" })).toEqual({ ok: true, value: 12 });
    expect(parseNumericEntry("12.0", "en-US", { ...bounded, inputType: "integer" })).toEqual({ ok: true, value: 12 });
  });

  it("refuses a value outside the bounds, and keeps the bounds inclusive", () => {
    expect(parseNumericEntry("100.01", "en-US", bounded)).toEqual({ ok: false, reason: "Enter 100 or less." });
    expect(parseNumericEntry("-1", "en-US", bounded)).toEqual({ ok: false, reason: "Enter 0 or more." });
    expect(parseNumericEntry("100", "en-US", bounded)).toEqual({ ok: true, value: 100 });
    expect(parseNumericEntry("0", "en-US", bounded)).toEqual({ ok: true, value: 0 });
    expect(parseNumericEntry("1000000", "en-US", { inputType: "decimal", minimum: null, maximum: null })).toEqual({ ok: true, value: 1000000 });
  });

  it("formats with the display format", () => {
    expect(formatNumericEntry(1234.5, "en-US", { inputType: "decimal", decimals: 2, grouping: true, suffix: " °C" })).toBe("1,234.50 °C");
    expect(formatNumericEntry(1234.5, "de-DE", { inputType: "decimal", decimals: 1, grouping: false })).toBe("1234,5");
    expect(formatNumericEntry(7, "en-US", { inputType: "integer", decimals: 3, grouping: false, prefix: "#" })).toBe("#7");
    expect(formatNumericEntry(null, "en-US", { inputType: "decimal", decimals: 2, grouping: false })).toBe("");
  });
});

describe("numeric entry field", () => {
  it("is registered, with bounds and a decimal type by default", () => {
    const module = componentRegistry().get("numeric-entry");
    expect(module?.title).toBe("Numeric entry field");
    expect(defaultsFromSchema(module!.schema)).toMatchObject({ inputType: "decimal", minimum: 0, maximum: 100, limitMin: true, limitMax: true });
  });

  it("shows the formatted value, and every digit while editing", () => {
    const { input } = field({ value: 1234.5, decimals: 1, grouping: true, suffix: " kPa", limitMax: false });
    expect(input).toHaveValue("1,234.5 kPa");
    fireEvent.focus(input);
    expect(input).toHaveValue("1234.5");
  });

  it("writes a valid entry on Enter and on leaving the field", () => {
    const { input, onChange } = field({ value: 10 });
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "42.5" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("value", 42.5);
    fireEvent.change(input, { target: { value: "43" } });
    fireEvent.blur(input);
    expect(onChange).toHaveBeenLastCalledWith("value", 43);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("refuses an out-of-range entry and shows why, without writing", () => {
    const { input, onChange } = field({ value: 10, minimum: 0, maximum: 50 });
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "75" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("Enter 50 or less.");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("Enter 50 or less.");
    // The entry stays for the operator to correct, also after leaving the field.
    fireEvent.blur(input);
    expect(input).toHaveValue("75");
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "50" } });
    expect(screen.queryByRole("alert")).toBeNull();
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("value", 50);
  });

  it("refuses a non-numeric entry and a fraction for the integer type", () => {
    const { input, onChange } = field({ value: 3, inputType: "integer" });
    expect(input).toHaveAttribute("inputmode", "numeric");
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "three" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a number.");
    fireEvent.change(input, { target: { value: "3.5" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(screen.getByRole("alert")).toHaveTextContent("Enter a whole number.");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("cancels an edit with Escape: nothing is written and the current value shows again", () => {
    const { input, onChange } = field({ value: 20, decimals: 1 });
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "999" } });
    fireEvent.keyDown(input, { key: "Escape" });
    fireEvent.blur(input);
    expect(onChange).not.toHaveBeenCalled();
    expect(input).toHaveValue("20.0");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("does not write an unchanged value, and follows the bound value when not editing", () => {
    const onChange = vi.fn();
    const { input, rerender } = field({ value: 5, decimals: 0 }, onChange);
    fireEvent.focus(input);
    fireEvent.keyDown(input, { key: "Enter" });
    fireEvent.blur(input);
    expect(onChange).not.toHaveBeenCalled();
    rerender(<NumericEntryField nodeId="sp" locale="en-US" props={{ label: "Setpoint", value: 8, decimals: 0 }} onChange={onChange} />);
    expect(input).toHaveValue("8");
  });

  it("does not take entries while disabled", () => {
    const { input } = field({ value: 1, enabled: false });
    expect(input).toBeDisabled();
  });
});
