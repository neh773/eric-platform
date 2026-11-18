import { useCallback, useEffect, useState, type MouseEvent, type PointerEvent } from "react";

/**
 * Touchscreen mode (HMV-126): while it is on, focusing a text, numeric, formatted text or password input opens an
 * on-screen keyboard that types into that field. Keys write through the input's own change path (a bubbling `input`
 * event, so React `onChange` handlers run), Enter sends an Enter key press and leaves the field (so a field that
 * commits on Enter or on blur commits), and Close just leaves it. The keyboard never takes focus from the field.
 */

export type KeyboardLayout = "alpha" | "numeric";
type Editable = HTMLInputElement | HTMLTextAreaElement;

const NON_TEXT_TYPES = new Set(["checkbox", "radio", "range", "color", "file", "button", "submit", "reset", "image", "hidden", "date", "datetime-local", "time", "month", "week"]);
const ALPHA_ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
const NUMERIC_ROWS = [["7", "8", "9"], ["4", "5", "6"], ["1", "2", "3"], ["-", "0", "."]];

/** Whether the keyboard opens for [element]: an enabled, writable text-like input or a text area. */
export function isKeyboardTarget(element: EventTarget | null): element is Editable {
  if (!(element instanceof HTMLTextAreaElement) && !(element instanceof HTMLInputElement)) return false;
  if (element.disabled || element.readOnly) return false;
  if (element instanceof HTMLInputElement && NON_TEXT_TYPES.has(element.type)) return false;
  return true;
}

/** The layout that suits [field]: numbers for numeric inputs, letters for the rest. */
export function layoutFor(field: Editable): KeyboardLayout {
  const mode = field.inputMode;
  if (field instanceof HTMLInputElement && (field.type === "number" || field.type === "tel")) return "numeric";
  return mode === "numeric" || mode === "decimal" || mode === "tel" ? "numeric" : "alpha";
}

function setValue(field: Editable, value: string): void {
  const prototype = field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(prototype, "value")?.set?.call(field, value);
  field.dispatchEvent(new Event("input", { bubbles: true }));
}

function selection(field: Editable): [number, number] {
  try {
    const start = field.selectionStart;
    const end = field.selectionEnd;
    if (start !== null && end !== null) return [start, end];
  } catch {
    // Some input types (number) have no selection.
  }
  return [field.value.length, field.value.length];
}

function place(field: Editable, caret: number): void {
  try {
    field.setSelectionRange(caret, caret);
  } catch {
    // No selection on this input type.
  }
}

/** Types [text] at the caret of [field], replacing any selection. */
export function typeInto(field: Editable, text: string): void {
  const [start, end] = selection(field);
  setValue(field, field.value.slice(0, start) + text + field.value.slice(end));
  place(field, start + text.length);
}

/** Deletes the selection of [field], or the character before the caret. */
export function backspaceIn(field: Editable): void {
  const [start, end] = selection(field);
  if (start === end && start === 0) return;
  const from = start === end ? start - 1 : start;
  setValue(field, field.value.slice(0, from) + field.value.slice(end));
  place(field, from);
}

/** Enter: a text area gets a new line; any other field gets an Enter key press and is left, so its value commits. */
export function enterIn(field: Editable): void {
  if (field instanceof HTMLTextAreaElement) {
    typeInto(field, "\n");
    return;
  }
  field.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", code: "Enter", bubbles: true, cancelable: true }));
  field.blur();
}

const keep = (event: MouseEvent | PointerEvent) => event.preventDefault();

export function OnScreenKeyboard({ enabled }: { enabled: boolean }) {
  const [field, setField] = useState<Editable | null>(null);
  const [layout, setLayout] = useState<KeyboardLayout>("alpha");
  const [shift, setShift] = useState(false);

  useEffect(() => {
    if (!enabled) {
      setField(null);
      return undefined;
    }
    const onIn = (event: FocusEvent) => {
      if (!isKeyboardTarget(event.target)) {
        setField(null);
        return;
      }
      setField(event.target);
      setLayout(layoutFor(event.target));
      setShift(false);
      event.target.scrollIntoView?.({ block: "center" });
    };
    const onOut = () => setField(null);
    document.addEventListener("focusin", onIn);
    document.addEventListener("focusout", onOut);
    return () => {
      document.removeEventListener("focusin", onIn);
      document.removeEventListener("focusout", onOut);
    };
  }, [enabled]);

  const press = useCallback(
    (text: string) => {
      if (!field) return;
      typeInto(field, shift ? text.toUpperCase() : text);
      setShift(false);
    },
    [field, shift],
  );

  if (!enabled || !field) return null;
  const key = (label: string, onPress: () => void, extra?: { wide?: boolean; name?: string; pressed?: boolean }) => (
    <button
      key={extra?.name ?? label}
      type="button"
      className="oip-osk-key"
      data-wide={extra?.wide ? "true" : undefined}
      aria-label={extra?.name}
      aria-pressed={extra?.pressed}
      onPointerDown={keep}
      onMouseDown={keep}
      onClick={onPress}
    >
      {label}
    </button>
  );
  return (
    <div className="oip-osk" role="group" aria-label="On-screen keyboard" data-layout={layout} onPointerDown={keep} onMouseDown={keep}>
      {layout === "alpha" ? (
        <>
          {ALPHA_ROWS.map((row, index) => (
            <div className="oip-osk-row" key={row}>
              {index === 2 ? key("⇧", () => setShift(!shift), { name: "Shift", pressed: shift, wide: true }) : null}
              {[...row].map((letter) => key(shift ? letter.toUpperCase() : letter, () => press(letter)))}
              {index === 2 ? key("⌫", () => backspaceIn(field), { name: "Backspace", wide: true }) : null}
            </div>
          ))}
          <div className="oip-osk-row">
            {key("123", () => setLayout("numeric"), { name: "Numbers", wide: true })}
            {key("Space", () => press(" "), { name: "Space", wide: true })}
            {key(".", () => press("."))}
            {key("Enter", () => enterIn(field), { name: "Enter", wide: true })}
            {key("Close", () => field.blur(), { name: "Close keyboard", wide: true })}
          </div>
        </>
      ) : (
        <>
          {NUMERIC_ROWS.map((row) => (
            <div className="oip-osk-row" key={row.join("")}>
              {row.map((digit) => key(digit, () => press(digit)))}
            </div>
          ))}
          <div className="oip-osk-row">
            {key("⌫", () => backspaceIn(field), { name: "Backspace", wide: true })}
            {key("Enter", () => enterIn(field), { name: "Enter", wide: true })}
            {key("Close", () => field.blur(), { name: "Close keyboard", wide: true })}
            {field.inputMode === "numeric" || field.inputMode === "decimal" || (field instanceof HTMLInputElement && (field.type === "number" || field.type === "tel"))
              ? null
              : key("ABC", () => setLayout("alpha"), { name: "Letters", wide: true })}
          </div>
        </>
      )}
    </div>
  );
}
