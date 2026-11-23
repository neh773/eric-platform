import { useEffect, useRef } from "react";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { HighlightStyle, StreamLanguage, syntaxHighlighting } from "@codemirror/language";
import { forceLinting, linter, lintGutter, type Diagnostic as LintDiagnostic } from "@codemirror/lint";
import { EditorState, RangeSetBuilder, StateEffect, StateField } from "@codemirror/state";
import { Decoration, EditorView, WidgetType, keymap, lineNumbers, type DecorationSet } from "@codemirror/view";
import { tags } from "@lezer/highlight";

const KEYWORDS = new Set([
  "PROGRAM", "END_PROGRAM", "FUNCTION", "END_FUNCTION", "FUNCTION_BLOCK", "END_FUNCTION_BLOCK",
  "VAR", "END_VAR", "VAR_INPUT", "VAR_OUTPUT", "VAR_IN_OUT", "VAR_GLOBAL",
  "IF", "THEN", "ELSIF", "ELSE", "END_IF", "CASE", "OF", "END_CASE",
  "FOR", "TO", "BY", "DO", "END_FOR", "WHILE", "END_WHILE", "REPEAT", "UNTIL", "END_REPEAT",
  "RETURN", "AND", "OR", "NOT", "XOR", "MOD", "TRUE", "FALSE",
]);
const TYPES = new Set(["BOOL", "SINT", "INT", "DINT", "LINT", "USINT", "UINT", "UDINT", "ULINT", "REAL", "LREAL", "BYTE", "WORD", "DWORD", "LWORD", "TIME", "DATE"]);
const NAMES = new Set([
  "TON", "TOF", "TP", "R_TRIG", "F_TRIG", "SR", "RS", "CTU", "CTD", "CTUD",
  "ERIC_PID", "ERIC_AI_SCALE", "ERIC_ALARM_LIMITS", "ERIC_MOTOR", "ERIC_VALVE", "ERIC_RAMP",
  "ERIC_FILTER", "ERIC_TOTALIZER", "ERIC_RUNTIME", "ERIC_HYSTERESIS", "ERIC_SELECT", "ERIC_SPLIT_RANGE",
  "ERIC_TPO", "ERIC_LEADLAG", "ERIC_DEADTIME", "ERIC_SEQUENCER", "ERIC_ALTERNATOR",
]);

const stLanguage = StreamLanguage.define({
  token(stream) {
    if (stream.match("(*")) {
      while (!stream.eol()) {
        if (stream.match("*)")) return "comment";
        stream.next();
      }
      return "comment";
    }
    if (stream.match("//")) {
      stream.skipToEnd();
      return "comment";
    }
    if (stream.match("'")) {
      while (!stream.eol() && !stream.match("'")) stream.next();
      return "string";
    }
    if (stream.match(/^(?:T#|TIME#)[0-9A-Za-z_]+/)) return "number";
    if (stream.match(/^[0-9]+(?:\.[0-9]+)?/)) return "number";
    if (stream.match(/^[A-Za-z_][A-Za-z0-9_]*/)) {
      const word = stream.current().toUpperCase();
      if (KEYWORDS.has(word)) return "keyword";
      if (TYPES.has(word)) return "typeName";
      if (NAMES.has(word)) return "stdlib";
      return "name";
    }
    stream.next();
    return null;
  },
  tokenTable: {
    keyword: tags.keyword,
    comment: tags.comment,
    string: tags.string,
    number: tags.number,
    typeName: tags.typeName,
    stdlib: tags.special(tags.name),
    name: tags.name,
  },
});

const highlight = HighlightStyle.define([
  { tag: tags.keyword, color: "var(--oip-accent)" },
  { tag: tags.comment, color: "var(--oip-ink-3)", fontStyle: "italic" },
  { tag: tags.string, color: "var(--oip-alarm-2)" },
  { tag: tags.number, color: "var(--oip-alarm-4)" },
  { tag: tags.typeName, color: "var(--oip-ok)" },
  { tag: tags.special(tags.name), color: "var(--oip-ok)" },
  { tag: tags.name, color: "var(--oip-ink)" },
]);

const theme = EditorView.theme({
  "&": { backgroundColor: "var(--oip-bg)", color: "var(--oip-ink)" },
  ".cm-scroller": { fontFamily: "var(--oip-font-mono)", lineHeight: "1.45" },
  ".cm-gutters": { backgroundColor: "var(--oip-surface-1)", color: "var(--oip-ink-3)", borderRight: "1px solid var(--oip-line)" },
  ".cm-activeLine": { backgroundColor: "var(--oip-accent-subtle)" },
  ".oip-st-online": { marginLeft: "4px", padding: "0 4px", borderRadius: "3px", fontSize: "0.85em", border: "1px solid var(--oip-line)", backgroundColor: "var(--oip-surface-1)", color: "var(--oip-ink)" },
  ".oip-st-online-true": { backgroundColor: "#1f8f3a", borderColor: "#1f8f3a", color: "#fff" },
  ".oip-st-online-false": { backgroundColor: "#8a8f98", borderColor: "#8a8f98", color: "#fff" },
  ".oip-st-online-struct": { fontStyle: "italic", color: "var(--oip-ink-2)" },
  ".oip-st-forced": { marginLeft: "3px", padding: "0 3px", borderRadius: "2px", backgroundColor: "#e8710a", color: "#fff", fontWeight: "700" },
  ".cm-lintRange-error": { backgroundImage: "none", textDecoration: "underline wavy var(--oip-alarm-1)" },
});

export interface OnlineMark {
  text: string;
  kind: "true" | "false" | "number" | "struct" | "other";
  forced: boolean;
}

/** Online mode: a live value after a variable. BOOL TRUE green, FALSE grey, forced with an orange F. */
class OnlineValue extends WidgetType {
  constructor(readonly mark: OnlineMark, readonly name: string) { super(); }
  eq(other: OnlineValue) { return other.name === this.name && other.mark.text === this.mark.text && other.mark.forced === this.mark.forced; }
  /** Let a right-click on the value reach the editor's context menu handler. */
  ignoreEvent(event: Event) { return event.type !== "contextmenu"; }
  toDOM() {
    const span = document.createElement("span");
    span.className = `oip-st-online oip-st-online-${this.mark.kind}`;
    span.setAttribute("data-online", this.name);
    span.title = this.mark.forced ? `${this.name} is forced` : this.name;
    span.textContent = this.mark.text;
    if (this.mark.forced) {
      const badge = document.createElement("b");
      badge.className = "oip-st-forced";
      badge.textContent = "F";
      badge.setAttribute("aria-label", "forced");
      span.append(badge);
    }
    return span;
  }
}

const setOnline = StateEffect.define<Record<string, OnlineMark> | null>();
const WORD = /[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*/g;
const DECLARATION = /^\s*[A-Za-z_][A-Za-z0-9_]*(?:\s*,\s*[A-Za-z_][A-Za-z0-9_]*)*\s*(?:AT\s+%[A-Z0-9.]+\s*)?:(?!=)/i;

/** Marks every watched variable in the source, outside comments and strings. A structure's members summary goes on its declaration only. */
function onlineDecorations(state: EditorState, values: Record<string, OnlineMark> | null): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>();
  if (!values) return builder.finish();
  let inComment = false;
  for (let n = 1; n <= state.doc.lines; n++) {
    const line = state.doc.line(n);
    // Blank out comments and strings so names inside them are not marked; positions stay the same.
    let text = "";
    for (let i = 0; i < line.text.length; i++) {
      const two = line.text.slice(i, i + 2);
      if (inComment) {
        if (two === "*)") { inComment = false; text += "  "; i++; } else text += " ";
      } else if (two === "(*") { inComment = true; text += "  "; i++; }
      else if (two === "//") { text += " ".repeat(line.text.length - i); break; }
      else if (line.text[i] === "'") {
        const end = line.text.indexOf("'", i + 1);
        const stop = end < 0 ? line.text.length : end + 1;
        text += " ".repeat(stop - i);
        i = stop - 1;
      } else text += line.text[i];
    }
    const declaration = DECLARATION.test(text);
    for (const match of text.matchAll(WORD)) {
      const mark = values[match[0].toLowerCase()];
      if (!mark || (mark.kind === "struct" && !declaration)) continue;
      const end = line.from + match.index! + match[0].length;
      builder.add(end, end, Decoration.widget({ widget: new OnlineValue(mark, match[0]), side: 1 }));
    }
  }
  return builder.finish();
}

const onlineField = StateField.define<{ values: Record<string, OnlineMark> | null; decorations: DecorationSet }>({
  create: (state) => ({ values: null, decorations: onlineDecorations(state, null) }),
  update(current, tr) {
    let values = current.values;
    for (const effect of tr.effects) if (effect.is(setOnline)) values = effect.value;
    if (values === current.values && !tr.docChanged) return current;
    return { values, decorations: onlineDecorations(tr.state, values) };
  },
  provide: (field) => EditorView.decorations.from(field, (value) => value.decorations),
});

/** The (dotted) name at a document position, for the online context menu. */
function nameAt(state: EditorState, pos: number): string | null {
  const line = state.doc.lineAt(pos);
  for (const match of line.text.matchAll(WORD)) {
    const from = line.from + match.index!;
    if (pos >= from && pos <= from + match[0].length) return match[0];
  }
  return null;
}

export interface StDiagnostic {
  line: number;
  column: number;
  message: string;
}

/** Structured Text editor. Loaded only when Logic is opened. */
export default function StEditor({ source, diagnostics, onChange, online = null, onVariableMenu }: {
  source: string;
  diagnostics: StDiagnostic[];
  onChange: (next: string) => void;
  /** Live values by lower-case variable name while online, or null when offline. */
  online?: Record<string, OnlineMark> | null;
  /** Right-click on a watched variable while online. */
  onVariableMenu?: (name: string, x: number, y: number) => void;
}) {
  const menuRef = useRef(onVariableMenu);
  const onlineRef = useRef(online);
  menuRef.current = onVariableMenu;
  onlineRef.current = online;
  const host = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const diagnosticsRef = useRef(diagnostics);
  onChangeRef.current = onChange;
  diagnosticsRef.current = diagnostics;

  useEffect(() => {
    if (!host.current) return undefined;
    const state = EditorState.create({
      doc: source,
      extensions: [
        lineNumbers(),
        history(),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        stLanguage,
        syntaxHighlighting(highlight),
        theme,
        lintGutter(),
        onlineField,
        EditorView.domEventHandlers({
          contextmenu(event, view) {
            const values = onlineRef.current;
            if (!values || !menuRef.current) return false;
            const target = event.target as HTMLElement | null;
            let name = target?.closest?.("[data-online]")?.getAttribute("data-online") ?? null;
            if (!name) {
              const pos = view.posAtCoords({ x: event.clientX, y: event.clientY });
              name = pos == null ? null : nameAt(view.state, pos);
            }
            if (!name || !values[name.toLowerCase()] || values[name.toLowerCase()]!.kind === "struct") return false;
            event.preventDefault();
            menuRef.current(name, event.clientX, event.clientY);
            return true;
          },
        }),
        linter(() => marks(viewRef.current, diagnosticsRef.current)),
        EditorView.contentAttributes.of({ "aria-label": "Structured Text", spellcheck: "false" }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) onChangeRef.current(update.state.doc.toString());
        }),
      ],
    });
    const view = new EditorView({ state, parent: host.current });
    viewRef.current = view;
    const mounted = host.current as HTMLDivElement & { oipEditor?: EditorView };
    mounted.oipEditor = view;
    return () => {
      if (mounted.oipEditor === view) delete mounted.oipEditor;
      view.destroy();
      viewRef.current = null;
    };
  }, []);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const current = view.state.doc.toString();
    if (current !== source) {
      view.dispatch({ changes: { from: 0, to: current.length, insert: source } });
    }
    forceLinting(view);
  }, [source, diagnostics]);

  useEffect(() => {
    viewRef.current?.dispatch({ effects: setOnline.of(online) });
  }, [online]);

  return <div ref={host} className="oip-st-editor" />;
}

function marks(view: EditorView | null, diagnostics: StDiagnostic[]): LintDiagnostic[] {
  if (!view) return [];
  return diagnostics.map((item) => {
    const lineNo = Math.min(Math.max(item.line, 1), view.state.doc.lines);
    const line = view.state.doc.line(lineNo);
    const column = Math.max(item.column, 1);
    const from = Math.min(line.from + column - 1, line.to);
    const to = Math.min(from + 1, line.to);
    return { from, to: to > from ? to : from, severity: "error", message: item.message };
  });
}
