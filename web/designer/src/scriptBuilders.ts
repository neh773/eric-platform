/**
 * Script builders (HMV-130): forms for the handlers most events need, each producing the text of an event script.
 * The text uses the names the session script runtime already has: `actions.navigate(page=... | view=..., params={...})`,
 * `actions.writeTag(path, value)` and `system.db.runNamedQuery(name, {...})` (a session script may run a named
 * query its roles allow).
 */

export type BuilderKind = "navigate-page" | "navigate-view" | "write-tag" | "named-query";

export const BUILDERS: ReadonlyArray<{ kind: BuilderKind; label: string }> = [
  { kind: "navigate-page", label: "Navigate to page" },
  { kind: "navigate-view", label: "Navigate to view" },
  { kind: "write-tag", label: "Write a value to a tag" },
  { kind: "named-query", label: "Run a named query" },
];

/**
 * A script value from what the author typed: a number, `true` or `false`, `none`, or text. A leading `=` keeps the rest
 * as script code (for example `=event.payload.x`), so a value can come from the event.
 */
export function scriptValue(raw: string): string {
  const text = raw.trim();
  if (text.startsWith("=")) return text.slice(1).trim() || "None";
  if (/^-?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(text)) return text;
  const lower = text.toLowerCase();
  if (lower === "true") return "True";
  if (lower === "false") return "False";
  if (lower === "none") return "None";
  return JSON.stringify(text);
}

const quote = (text: string) => JSON.stringify(text.trim());

/** `name = value` lines as a script dict, or an empty text when none. Blank lines are skipped. */
export function scriptDict(lines: string): { text: string; problem: string | null } {
  const entries: string[] = [];
  for (const line of lines.split("\n")) {
    if (!line.trim()) continue;
    const at = line.indexOf("=");
    const name = (at < 0 ? line : line.slice(0, at)).trim();
    if (!name) return { text: "", problem: `"${line.trim()}" needs a name before the =.` };
    if (at < 0) return { text: "", problem: `"${line.trim()}" needs a value: name = value.` };
    entries.push(`${JSON.stringify(name)}: ${scriptValue(line.slice(at + 1))}`);
  }
  return { text: entries.length > 0 ? `{${entries.join(", ")}}` : "", problem: null };
}

export interface BuilderForm {
  kind: BuilderKind;
  /** Page URL, view path, tag path or query name. */
  target: string;
  /** Tag value (write a tag). */
  value: string;
  /** `name = value` lines: navigation parameters or query parameters. */
  params: string;
}

export function emptyForm(kind: BuilderKind): BuilderForm {
  return { kind, target: "", value: "", params: "" };
}

/** Problem with the form, or null when it can produce a script. */
export function builderProblem(form: BuilderForm): string | null {
  if (!form.target.trim()) {
    return { "navigate-page": "Enter a page URL.", "navigate-view": "Enter a view path.", "write-tag": "Enter a tag path.", "named-query": "Enter a query name." }[form.kind];
  }
  if (form.kind === "navigate-page" && !form.target.trim().startsWith("/")) return "A page URL starts with /.";
  if (form.kind === "write-tag" && !form.value.trim()) return "Enter the value to write.";
  if (form.kind !== "write-tag") return scriptDict(form.params).problem;
  return null;
}

/** The script text for [form], or null when [builderProblem] says it is not complete. */
export function buildScript(form: BuilderForm): string | null {
  if (builderProblem(form)) return null;
  const params = scriptDict(form.params).text;
  switch (form.kind) {
    case "navigate-page":
      return `actions.navigate(page=${quote(form.target)}${params ? `, params=${params}` : ""})`;
    case "navigate-view":
      return `actions.navigate(view=${quote(form.target)}${params ? `, params=${params}` : ""})`;
    case "write-tag":
      return `actions.writeTag(${quote(form.target)}, ${scriptValue(form.value)})`;
    case "named-query":
      return `rows = system.db.runNamedQuery(${quote(form.target)}${params ? `, ${params}` : ""})`;
  }
}

/** [next] added to the script [source]: on its own line after what is there, or alone when the source is empty. */
export function appendScript(source: string, next: string): string {
  const base = source.replace(/\s+$/, "");
  return base ? `${base}\n${next}` : next;
}
