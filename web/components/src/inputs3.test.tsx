import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { componentRegistry, defaultsFromSchema } from "./registry";
import {
  BarcodeScannerInput,
  checkPattern,
  FormattedTextField,
  matchesMask,
  MomentaryButton,
  Spinner,
  StateToggle,
  stepValue,
  Timer,
} from "./inputs3";

/** Input batch 3: HMV-013, HMV-028, HMV-053, HMV-059, HMV-073, HMP-057. */

const base = { nodeId: "n", locale: "en-US" };

describe("registry", () => {
  it("lists the new input components with defaults", () => {
    for (const type of ["momentary-button", "state-toggle", "formatted-text-field", "spinner", "timer", "barcode-scanner-input"]) {
      const module = componentRegistry().get(type);
      expect(module?.category, type).toBe("input");
      expect(defaultsFromSchema(module!.schema), type).toBeTypeOf("object");
    }
  });
});

describe("momentary button (HMV-013)", () => {
  afterEach(() => vi.useRealTimers());

  it("writes the press value on press and the release value on release", () => {
    const onChange = vi.fn();
    const onEvent = vi.fn();
    render(<MomentaryButton {...base} props={{ text: "Jog", pressValue: 1, releaseValue: 0 }} onChange={onChange} onEvent={onEvent} />);
    const button = screen.getByRole("button", { name: "Jog" });
    fireEvent.pointerDown(button);
    expect(onChange).toHaveBeenLastCalledWith("value", 1);
    expect(button).toHaveAttribute("aria-pressed", "true");
    fireEvent.pointerUp(button);
    expect(onChange).toHaveBeenLastCalledWith("value", 0);
    expect(onChange).toHaveBeenCalledTimes(2);
    expect(onEvent.mock.calls.map((c) => c[0])).toEqual(["press", "release"]);
  });

  it("releases when the pointer leaves, and never releases twice", () => {
    const onChange = vi.fn();
    render(<MomentaryButton {...base} props={{ text: "Jog" }} onChange={onChange} />);
    const button = screen.getByRole("button");
    fireEvent.pointerDown(button);
    fireEvent.pointerLeave(button);
    fireEvent.pointerUp(button);
    expect(onChange.mock.calls).toEqual([["value", 1], ["value", 0]]);
  });

  it("works from the keyboard", () => {
    const onChange = vi.fn();
    render(<MomentaryButton {...base} props={{ text: "Jog" }} onChange={onChange} />);
    const button = screen.getByRole("button");
    fireEvent.keyDown(button, { key: " " });
    fireEvent.keyUp(button, { key: " " });
    expect(onChange.mock.calls).toEqual([["value", 1], ["value", 0]]);
  });

  it("holds for a fixed time, ignoring the pointer release", () => {
    vi.useFakeTimers();
    const onChange = vi.fn();
    render(<MomentaryButton {...base} props={{ text: "Pulse", holdMs: 500, releaseValue: 7 }} onChange={onChange} />);
    const button = screen.getByRole("button");
    fireEvent.pointerDown(button);
    fireEvent.pointerUp(button);
    expect(onChange.mock.calls).toEqual([["value", 1]]);
    act(() => void vi.advanceTimersByTime(499));
    expect(onChange).toHaveBeenCalledTimes(1);
    act(() => void vi.advanceTimersByTime(1));
    expect(onChange.mock.calls).toEqual([["value", 1], ["value", 7]]);
  });

  it("writes the release value when removed while held, and writes nothing when disabled", () => {
    const onChange = vi.fn();
    const view = render(<MomentaryButton {...base} props={{ text: "Jog" }} onChange={onChange} />);
    fireEvent.pointerDown(screen.getByRole("button"));
    view.unmount();
    expect(onChange.mock.calls).toEqual([["value", 1], ["value", 0]]);
    const off = vi.fn();
    render(<MomentaryButton {...base} props={{ text: "Off", enabled: false }} onChange={off} />);
    fireEvent.pointerDown(screen.getByRole("button", { name: "Off" }));
    expect(off).not.toHaveBeenCalled();
  });
});

describe("state toggle (HMV-028)", () => {
  it("writes the opposite of the written state and shows the confirmed state separately", () => {
    const onChange = vi.fn();
    const { rerender } = render(<StateToggle {...base} props={{ label: "Pump", value: false, confirmed: false }} onChange={onChange} />);
    const toggle = screen.getByRole("switch");
    expect(toggle).toHaveAttribute("aria-checked", "false");
    fireEvent.click(toggle);
    expect(onChange).toHaveBeenCalledWith("value", true);
    rerender(<StateToggle {...base} props={{ label: "Pump", value: true, confirmed: false }} onChange={onChange} />);
    expect(toggle).toHaveAttribute("aria-checked", "false");
    expect(toggle).toHaveAttribute("data-pending", "true");
    expect(toggle).toHaveTextContent("(waiting)");
    rerender(<StateToggle {...base} props={{ label: "Pump", value: true, confirmed: true }} onChange={onChange} />);
    expect(toggle).toHaveAttribute("aria-checked", "true");
    expect(toggle).not.toHaveAttribute("data-pending");
    expect(toggle).toHaveTextContent("On");
  });
});

describe("formatted text field (HMV-053)", () => {
  it("checks masks and patterns", () => {
    expect(matchesMask("AB-123", "AA-###")).toBe(true);
    expect(matchesMask("AB-12", "AA-###")).toBe(false);
    expect(matchesMask("A1-123", "AA-###")).toBe(false);
    expect(matchesMask("AB 123", "AA-###")).toBe(false);
    expect(checkPattern("2026-01-05", "", "\\d{4}-\\d{2}-\\d{2}", "")).toEqual({ ok: true });
    expect(checkPattern("2026-1-05", "", "\\d{4}-\\d{2}-\\d{2}", "Use YYYY-MM-DD")).toEqual({ ok: false, reason: "Use YYYY-MM-DD" });
    // The pattern must match the whole text, not a part of it.
    expect(checkPattern("x12", "", "\\d+", "").ok).toBe(false);
    expect(checkPattern("a", "", "a|b", "").ok).toBe(true);
    expect(checkPattern("ab", "", "a|b", "").ok).toBe(false);
    expect(checkPattern("a", "", "(", "").ok).toBe(false);
  });

  it("writes a matching commit and refuses a non-matching one", () => {
    const onChange = vi.fn();
    render(<FormattedTextField {...base} props={{ label: "Lot", value: "", pattern: "[A-Z]{2}\\d{3}" }} onChange={onChange} />);
    const input = screen.getByRole("textbox", { name: "Lot" });
    fireEvent.change(input, { target: { value: "ab12" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveValue("ab12");
    fireEvent.change(input, { target: { value: "AB123" } });
    expect(screen.queryByRole("alert")).toBeNull();
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenCalledExactlyOnceWith("value", "AB123");
  });

  it("refuses on blur and restores the bound value on Escape", () => {
    const onChange = vi.fn();
    render(<FormattedTextField {...base} props={{ label: "Lot", value: "AB123", mask: "AA###" }} onChange={onChange} />);
    const input = screen.getByRole("textbox");
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "AB1" } });
    fireEvent.blur(input);
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("Enter it as AA###.");
    fireEvent.focus(input);
    fireEvent.keyDown(input, { key: "Escape" });
    expect(input).toHaveValue("AB123");
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("spinner (HMV-059)", () => {
  const num = { mode: "number" as const, minimum: 0, maximum: 1, step: 0.1 };

  it("steps numbers within bounds without float noise", () => {
    expect(stepValue(0.1, 1, num)).toBe(0.2);
    expect(stepValue(0.2, 1, num)).toBe(0.3);
    expect(stepValue(0.95, 1, num)).toBe(1);
    expect(stepValue(0.05, -1, num)).toBe(0);
    expect(stepValue("x", 1, num)).toBeNull();
  });

  it("steps dates by days within bounds", () => {
    const date = { mode: "date" as const, minimum: "2026-01-01", maximum: "2026-03-01", step: 7 };
    expect(stepValue("2026-02-25", 1, date)).toBe("2026-03-01");
    expect(stepValue("2026-01-03", -1, date)).toBe("2026-01-01");
    expect(stepValue("2026-01-10", 1, date)).toBe("2026-01-17");
    expect(stepValue("2026-12-31", 0, { ...date, maximum: "" })).toBe("2026-12-31");
    expect(stepValue("junk", 1, date)).toBeNull();
  });

  it("writes on buttons and arrow keys and disables at the bounds", () => {
    const onChange = vi.fn();
    const { rerender } = render(<Spinner {...base} props={{ label: "Speed", value: 5, minimum: 0, maximum: 6, step: 2 }} onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: "Increase" }));
    expect(onChange).toHaveBeenLastCalledWith("value", 6);
    const spin = screen.getByRole("spinbutton", { name: "Speed" });
    fireEvent.keyDown(spin, { key: "ArrowDown" });
    expect(onChange).toHaveBeenLastCalledWith("value", 3);
    rerender(<Spinner {...base} props={{ label: "Speed", value: 6, minimum: 0, maximum: 6, step: 2 }} onChange={onChange} />);
    expect(screen.getByRole("button", { name: "Increase" })).toBeDisabled();
    onChange.mockClear();
    fireEvent.keyDown(spin, { key: "ArrowUp" });
    expect(onChange).not.toHaveBeenCalled();
  });

  it("steps a date value", () => {
    const onChange = vi.fn();
    render(<Spinner {...base} props={{ mode: "date", value: "2026-02-28", minimum: "2026-01-01", maximum: "2026-12-31", step: 1 }} onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: "Increase" }));
    expect(onChange).toHaveBeenCalledWith("value", "2026-03-01");
  });
});

describe("timer (HMV-073)", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("fires tick events and counts while running then stops", () => {
    const onChange = vi.fn();
    const onEvent = vi.fn();
    const { rerender, container } = render(<Timer {...base} props={{ running: true, intervalMs: 200, ticks: 0 }} onChange={onChange} onEvent={onEvent} />);
    expect((container.querySelector(".oip-timer") as HTMLElement).hidden).toBe(true);
    act(() => void vi.advanceTimersByTime(650));
    expect(onEvent.mock.calls.map((c) => c[0])).toEqual(["tick", "tick", "tick"]);
    expect(onChange.mock.calls.map((c) => c[1])).toEqual([1, 2, 3]);
    rerender(<Timer {...base} props={{ running: false, intervalMs: 200, ticks: 3 }} onChange={onChange} onEvent={onEvent} />);
    act(() => void vi.advanceTimersByTime(1000));
    expect(onEvent).toHaveBeenCalledTimes(3);
  });

  it("changes pace with the interval and follows a reset of the count", () => {
    const onChange = vi.fn();
    const { rerender } = render(<Timer {...base} props={{ intervalMs: 1000, ticks: 0 }} onChange={onChange} />);
    rerender(<Timer {...base} props={{ intervalMs: 100, ticks: 0 }} onChange={onChange} />);
    act(() => void vi.advanceTimersByTime(250));
    expect(onChange.mock.calls.map((c) => c[1])).toEqual([1, 2]);
    rerender(<Timer {...base} props={{ intervalMs: 100, ticks: 0 }} onChange={onChange} />);
    act(() => void vi.advanceTimersByTime(100));
    expect(onChange).toHaveBeenLastCalledWith("ticks", 1);
  });
});

describe("barcode scanner input (HMP-057)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => vi.useRealTimers());

  /** Types [text] with [gap] ms between keys on the fake clock, which drives `event.timeStamp`. */
  function type(text: string, gap: number, target: EventTarget = document.body) {
    for (const key of text) {
      target.dispatchEvent(new KeyboardEvent("keydown", { key, bubbles: true }));
      vi.advanceTimersByTime(gap);
    }
    target.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  }

  it("emits one scan for a fast burst ending in Enter", () => {
    const onChange = vi.fn();
    const onEvent = vi.fn();
    render(<BarcodeScannerInput {...base} props={{ minLength: 4, maxGapMs: 50 }} onChange={onChange} onEvent={onEvent} />);
    act(() => type("A1234567", 10));
    expect(onChange).toHaveBeenCalledExactlyOnceWith("value", "A1234567");
    expect(onEvent).toHaveBeenCalledExactlyOnceWith("scan");
    expect(screen.getByText("A1234567")).toBeInTheDocument();
  });

  it("ignores slow typing, short bursts, text fields and a disabled scanner", () => {
    const onChange = vi.fn();
    const view = render(
      <div>
        <input aria-label="field" />
        <BarcodeScannerInput {...base} props={{ minLength: 4, maxGapMs: 50 }} onChange={onChange} />
      </div>,
    );
    act(() => type("SLOW1234", 200));
    act(() => type("123", 5));
    act(() => type("FASTCODE", 5, screen.getByLabelText("field")));
    expect(onChange).not.toHaveBeenCalled();
    // A slow lead-in is dropped; the fast burst after it is the scan.
    act(() => {
      document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "x", bubbles: true }));
      vi.advanceTimersByTime(500);
      type("9876", 5);
    });
    expect(onChange).toHaveBeenCalledExactlyOnceWith("value", "9876");
    view.rerender(<BarcodeScannerInput {...base} props={{ enabled: false }} onChange={onChange} />);
    onChange.mockClear();
    act(() => type("DISABLED1", 5));
    expect(onChange).not.toHaveBeenCalled();
  });

  it("is registered", () => {
    expect(componentRegistry().get("barcode-scanner-input")?.title).toBe("Barcode scanner input");
  });
});
