import { useId, useLayoutEffect, useRef, type KeyboardEvent } from "react";
import "./eventEditor.css";

/**
 * Plain code editor for script sources (D-5): a monospace text area where Tab indents instead of leaving the field.
 *
 * Keys: Tab inserts [INDENT] at the cursor, or indents every selected line; Shift+Tab removes one indent from the
 * current or selected lines; Enter keeps the current line's indent. Escape hands Tab back to the page, so the next
 * Tab (or Shift+Tab) moves focus out as usual and the editor is never a keyboard trap.
 */

export const INDENT = "    ";

export interface Edit {
  value: string;
  start: number;
  end: number;
}

export function ScriptEditor({
  label,
  value,
  onChange,
  rows = 8,
  placeholder,
  hint,
}: {
  /** Accessible name of the text area. */
  label: string;
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  placeholder?: string;
  /** Shown above the key help, for example the function the source is the body of. */
  hint?: string;
}) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const help = useId();
  // Selection to restore once the edited value is rendered.
  const pending = useRef<{ start: number; end: number } | null>(null);
  // After Escape, Tab leaves the editor (until any other key).
  const release = useRef(false);

  useLayoutEffect(() => {
    const area = ref.current;
    const selection = pending.current;
    if (!area || !selection) return;
    pending.current = null;
    area.setSelectionRange(selection.start, selection.end);
  }, [value]);

  function apply(edit: Edit) {
    pending.current = { start: edit.start, end: edit.end };
    onChange(edit.value);
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    const area = event.currentTarget;
    if (event.key === "Escape") {
      release.current = true;
      return;
    }
    if (event.key === "Tab") {
      if (release.current || event.ctrlKey || event.altKey || event.metaKey) return;
      event.preventDefault();
      apply(event.shiftKey ? outdent(area.value, area.selectionStart, area.selectionEnd) : indent(area.value, area.selectionStart, area.selectionEnd));
      return;
    }
    release.current = false;
    if (event.key === "Enter" && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      apply(newline(area.value, area.selectionStart, area.selectionEnd));
    }
  }

  return (
    <div className="oip-script-editor">
      <textarea
        ref={ref}
        aria-label={label}
        aria-describedby={help}
        rows={rows}
        spellCheck={false}
        autoCapitalize="off"
        autoComplete="off"
        autoCorrect="off"
        wrap="off"
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={onKeyDown}
        onBlur={() => {
          release.current = false;
        }}
      />
      <p id={help} className="oip-binding-muted">
        {hint ? `${hint} ` : ""}Tab indents; press Esc then Tab to leave the editor.
      </p>
    </div>
  );
}

/** Tab: spaces at the cursor, or one indent on every line the selection touches. */
export function indent(value: string, start: number, end: number): Edit {
  if (start === end || !value.slice(start, end).includes("\n")) {
    return { value: value.slice(0, start) + INDENT + value.slice(end), start: start + INDENT.length, end: start + INDENT.length };
  }
  const from = lineStart(value, start);
  const lines = value.slice(from, end).split("\n");
  const body = lines.map((line) => INDENT + line).join("\n");
  return { value: value.slice(0, from) + body + value.slice(end), start: start + INDENT.length, end: end + INDENT.length * lines.length };
}

/** Shift+Tab: up to one indent (or the leading spaces it has) off every line the selection touches. */
export function outdent(value: string, start: number, end: number): Edit {
  const from = lineStart(value, start);
  const lines = value.slice(from, end).split("\n");
  let removedFirst = 0;
  let removed = 0;
  const body = lines
    .map((line, index) => {
      const cut = leading(line, INDENT.length);
      if (index === 0) removedFirst = cut;
      removed += cut;
      return line.slice(cut);
    })
    .join("\n");
  return {
    value: value.slice(0, from) + body + value.slice(end),
    start: Math.max(from, start - removedFirst),
    end: Math.max(from, end - removed),
  };
}

/** Enter: a line break plus the current line's indent. */
export function newline(value: string, start: number, end: number): Edit {
  const from = lineStart(value, start);
  const spaces = value.slice(from, from + leading(value.slice(from, start), Infinity));
  const insert = "\n" + spaces;
  return { value: value.slice(0, start) + insert + value.slice(end), start: start + insert.length, end: start + insert.length };
}

function lineStart(value: string, at: number): number {
  return value.lastIndexOf("\n", at - 1) + 1;
}

/** Leading spaces of [line], at most [max]. */
function leading(line: string, max: number): number {
  let count = 0;
  while (count < line.length && count < max && line[count] === " ") count += 1;
  return count;
}
