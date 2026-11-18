import { useState } from "react";
import { BUILDERS, appendScript, buildScript, builderProblem, emptyForm, type BuilderForm, type BuilderKind } from "./scriptBuilders";

/** Forms that write a common handler into an event's script (HMV-130). The result is plain script text the author can still edit. */
export function ScriptBuilder({ source, onInsert }: { source: string; onInsert: (source: string) => void }) {
  const [form, setForm] = useState<BuilderForm>(emptyForm("navigate-page"));
  const [shown, setShown] = useState(false);
  const problem = shown ? builderProblem(form) : null;
  const set = (patch: Partial<BuilderForm>) => setForm((current) => ({ ...current, ...patch }));
  const targetLabel = { "navigate-page": "Page URL", "navigate-view": "View path", "write-tag": "Tag path", "named-query": "Query name" }[form.kind];
  return (
    <fieldset className="oip-script-builder">
      <legend>Script builder</legend>
      <div className="oip-binding-grid">
        <label>
          <span>Handler</span>
          <select aria-label="Builder handler" value={form.kind} onChange={(event) => setForm(emptyForm(event.target.value as BuilderKind))}>
            {BUILDERS.map((entry) => (
              <option key={entry.kind} value={entry.kind}>
                {entry.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>{targetLabel}</span>
          <input aria-label={`Builder ${targetLabel.toLowerCase()}`} value={form.target} onChange={(event) => set({ target: event.target.value })} />
        </label>
        {form.kind === "write-tag" ? (
          <label>
            <span>Value</span>
            <input aria-label="Builder value" value={form.value} placeholder="42, true, text, or =event.payload.x" onChange={(event) => set({ value: event.target.value })} />
          </label>
        ) : (
          <label className="oip-binding-wide">
            <span>{form.kind === "named-query" ? "Query parameters" : "Page parameters"}</span>
            <textarea aria-label="Builder parameters" rows={2} value={form.params} placeholder="name = value, one per line" onChange={(event) => set({ params: event.target.value })} />
          </label>
        )}
      </div>
      {problem ? <p role="alert">{problem}</p> : null}
      <button
        type="button"
        onClick={() => {
          setShown(true);
          const text = buildScript(form);
          if (text) onInsert(appendScript(source, text));
        }}
      >
        Add to script
      </button>
    </fieldset>
  );
}
