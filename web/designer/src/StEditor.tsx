import { useEffect, useRef } from "react";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { HighlightStyle, StreamLanguage, syntaxHighlighting } from "@codemirror/language";
import { forceLinting, linter, lintGutter, type Diagnostic as LintDiagnostic } from "@codemirror/lint";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
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
  ".cm-lintRange-error": { backgroundImage: "none", textDecoration: "underline wavy var(--oip-alarm-1)" },
});

export interface StDiagnostic {
  line: number;
  column: number;
  message: string;
}

/** Structured Text editor. Loaded only when Logic is opened. */
export default function StEditor({ source, diagnostics, onChange }: { source: string; diagnostics: StDiagnostic[]; onChange: (next: string) => void }) {
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
