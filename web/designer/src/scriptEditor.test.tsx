import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { INDENT, ScriptEditor, indent, newline, outdent } from "./ScriptEditor";

/** D-5: the script editor's keys. Tab indents, Shift+Tab outdents, Enter keeps the indent, Esc releases Tab. */

afterEach(cleanup);

function setup(initial: string) {
  const state = { value: initial };
  function Harness() {
    const [value, setValue] = useState(initial);
    state.value = value;
    return (
      <>
        <ScriptEditor
          label="Script source"
          value={value}
          onChange={(next) => {
            setValue(next);
          }}
        />
        <button type="button">After</button>
      </>
    );
  }
  render(<Harness />);
  const area = screen.getByRole("textbox", { name: "Script source" }) as HTMLTextAreaElement;
  return { area, value: () => state.value };
}

describe("script editor edits", () => {
  it("indents at the cursor, or every selected line", () => {
    expect(indent("ab", 1, 1)).toEqual({ value: `a${INDENT}b`, start: 5, end: 5 });
    // A selection inside one line is replaced, like typing.
    expect(indent("abc", 1, 2).value).toBe(`a${INDENT}c`);
    const block = indent("if x:\nreturn 1\nreturn 2", 7, 20);
    expect(block.value).toBe(`if x:\n${INDENT}return 1\n${INDENT}return 2`);
    expect(block).toMatchObject({ start: 11, end: 28 });
  });

  it("outdents the current or selected lines by up to one indent", () => {
    expect(outdent(`${INDENT}return 1`, 6, 6)).toEqual({ value: "return 1", start: 2, end: 2 });
    expect(outdent(`  a\n${INDENT}${INDENT}b\nc`, 0, 15).value).toBe(`a\n${INDENT}b\nc`);
    expect(outdent("x", 1, 1)).toEqual({ value: "x", start: 1, end: 1 });
  });

  it("keeps the line's indent on Enter", () => {
    expect(newline(`${INDENT}if x:`, 9, 9)).toEqual({ value: `${INDENT}if x:\n${INDENT}`, start: 14, end: 14 });
    expect(newline("a", 1, 1).value).toBe("a\n");
  });
});

describe("script editor keys", () => {
  it("Tab inserts spaces and keeps focus; Shift+Tab removes them", () => {
    const { area, value } = setup("return value");
    area.focus();
    area.setSelectionRange(0, 0);
    const handled = !fireEvent.keyDown(area, { key: "Tab" });
    expect(handled).toBe(true);
    expect(value()).toBe(`${INDENT}return value`);
    expect(document.activeElement).toBe(area);
    expect(area.selectionStart).toBe(INDENT.length);
    fireEvent.keyDown(area, { key: "Tab", shiftKey: true });
    expect(value()).toBe("return value");
  });

  it("Enter carries the indent to the new line", () => {
    const { area, value } = setup(`if x:\n${INDENT}a = 1`);
    area.focus();
    area.setSelectionRange(area.value.length, area.value.length);
    fireEvent.keyDown(area, { key: "Enter" });
    expect(value()).toBe(`if x:\n${INDENT}a = 1\n${INDENT}`);
  });

  it("after Esc, Tab is left to the browser so focus can move on (no keyboard trap)", () => {
    const { area, value } = setup("a");
    area.focus();
    fireEvent.keyDown(area, { key: "Escape" });
    const prevented = !fireEvent.keyDown(area, { key: "Tab" });
    expect(prevented).toBe(false);
    expect(value()).toBe("a");
    // Typing again re-arms Tab for indenting.
    fireEvent.keyDown(area, { key: "b" });
    expect(!fireEvent.keyDown(area, { key: "Tab" })).toBe(true);
  });

  it("is a monospace text area that describes its keys", () => {
    const { area } = setup("");
    expect(area.tagName).toBe("TEXTAREA");
    expect(area).toHaveAccessibleDescription(/Tab indents; press Esc then Tab to leave the editor/);
    expect(area.closest(".oip-script-editor")).not.toBeNull();
    expect(area).toHaveAttribute("spellcheck", "false");
  });
});
