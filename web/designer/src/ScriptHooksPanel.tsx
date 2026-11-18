import { useState, type FormEvent } from "react";
import type { ScriptHookSet } from "./persistence";
import { ScriptEditor } from "./ScriptEditor";
import { hookNameProblem } from "./eventModel";
import "./eventEditor.css";

/**
 * The project's script hooks (D-5): named Python sources a script transform or event script runs by `hook` when the
 * view does not carry its own source. Saved with the project (`ScriptHooks` resource); the gateway runs them as the
 * session user.
 */
export function ScriptHooksPanel({ hooks, onChange }: { hooks: ScriptHookSet; onChange: (hooks: ScriptHookSet) => void }) {
  const names = Object.keys(hooks).sort((a, b) => a.localeCompare(b));
  const [selected, setSelected] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const current = selected !== null && hooks[selected] ? selected : (names[0] ?? null);

  function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = name.trim();
    const problem = hookNameProblem(trimmed) ?? (hooks[trimmed] ? "A hook with that name already exists." : null);
    setError(problem);
    if (problem) return;
    onChange({ ...hooks, [trimmed]: { source: "" } });
    setSelected(trimmed);
    setName("");
  }

  return (
    <section className="oip-script-hooks" data-testid="script-hooks-panel" aria-label="Script hooks">
      <p className="oip-binding-muted">
        Project scripts run on the gateway as the signed-in user. A script action or transform with an empty source runs the hook of its name.
      </p>
      {names.length === 0 ? (
        <p className="oip-binding-muted">No script hooks yet.</p>
      ) : (
        <ul aria-label="Hooks">
          {names.map((hook) => (
            <li key={hook}>
              <button type="button" aria-current={hook === current ? "true" : undefined} onClick={() => setSelected(hook)}>
                {hook}
              </button>
            </li>
          ))}
        </ul>
      )}
      <form aria-label="New script hook" onSubmit={add}>
        <input aria-label="New hook name" placeholder="New hook name" value={name} onChange={(event) => setName(event.target.value)} />
        <button type="submit">Add hook</button>
      </form>
      {error ? (
        <p className="oip-binding-error" role="alert">
          {error}
        </p>
      ) : null}
      {current ? (
        <div className="oip-binding-editor" role="region" aria-label={`Hook ${current}`}>
          <strong>
            <code>{current}</code>
          </strong>
          <ScriptEditor
            label="Hook source"
            value={hooks[current]?.source ?? ""}
            rows={12}
            hint="Body of transform(value, context) for a transform, or runAction(event, component) for an event."
            onChange={(source) => onChange({ ...hooks, [current]: { source } })}
          />
          <button
            type="button"
            className="oip-binding-remove"
            onClick={() => {
              const { [current]: _removed, ...rest } = hooks;
              onChange(rest);
              setSelected(null);
            }}
          >
            Remove hook {current}
          </button>
        </div>
      ) : null}
    </section>
  );
}
