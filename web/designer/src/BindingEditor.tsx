import { useContext, useEffect, useRef, useState, type DragEvent } from "react";
import { MIN_EXPRESSION_POLL_MS, parseBinding } from "@oip/hmi-runtime";
import { componentRegistry } from "@oip/components";
import { HISTORY_AGGREGATES } from "@oip/hmi-runtime";
import type {
  JsonSchema,
  Binding,
  BindingPreview,
  BindingRef,
  ExpressionBinding,
  ExpressionStructureBinding,
  HistoryBinding,
  PropertyBinding,
  PropertySource,
  QueryBinding,
  QueryParameter,
  TagBinding,
  ViewDocument,
} from "@oip/hmi-runtime";
import {
  BINDING_KINDS,
  PAGE_PATHS,
  SESSION_PATHS,
  componentNodes,
  defaultBinding,
  defaultRef,
  expressionError,
  placeholders,
  showValue,
  type BindingKind,
} from "./bindingModel";
import { TAG_DRAG_TYPE, decodeDraggedTag } from "./tagDrop";
import { TagSourcesContext } from "./tagSource";
import { TagPickerDialog } from "./TagBrowser";
import "./bindingEditor.css";
import { LiteralInput, TransformEditor } from "./TransformEditor";
import { SchemaForm } from "./schemaForm";

/**
 * Binding editor for one component property: tag (direct or indirect), property, expression, named query, or tag history,
 * followed by the transform chain. Every change is written straight back as a whole `Binding`, so fields this
 * panel does not show are kept.
 */

export const QUERY_NOT_CONNECTED = "Not connected in the Designer: the query runs in a live session.";
export const HISTORY_NOT_CONNECTED = "Not connected in the Designer: history runs in a live session.";

/** Why [binding] cannot be saved yet (the same check a view load runs), or null when it can. */
export function bindingProblem(binding: Binding): string | null {
  try {
    parseBinding(JSON.parse(JSON.stringify(binding)), "binding");
  } catch (error) {
    return error instanceof Error ? error.message.replace(/^binding\.?/, "").trim() : String(error);
  }
  if (binding.kind === "tag" && binding.mode === "indirect") {
    // The runtime would replace a placeholder with no reference by "", which reads a different tag.
    const missing = placeholders(binding.path).filter((name) => !binding.references?.[name]);
    if (missing.length > 0) return `no reference for ${missing.map((name) => `{${name}}`).join(", ")}`;
  }
  return null;
}

/** Why a list of query parameter rows cannot be saved as named parameters, or null. */
export function queryRowsProblem(rows: ReadonlyArray<readonly [string, QueryParameter]>): string | null {
  const seen = new Set<string>();
  for (const [index, [name]] of rows.entries()) {
    if (name.trim() === "") return `parameter ${index + 1} needs a name`;
    if (seen.has(name)) return `two parameters are named "${name}"`;
    seen.add(name);
  }
  return null;
}

/** The authored value of the bound property: used while the binding has no value. */
export interface AuthoredValue {
  schema: JsonSchema;
  value: unknown;
  onChange: (value: unknown) => void;
}

export function BindingEditor({
  view,
  nodeId,
  prop,
  binding,
  evaluate,
  authored,
  onChange,
  onClose,
}: {
  view: ViewDocument;
  nodeId: string;
  prop: string;
  /** The binding saved on the property. */
  binding: Binding | undefined;
  /** Live preview of a binding on this property. */
  evaluate: (binding: Binding) => BindingPreview;
  /** The property's own value, editable here because the bound row hides its input. */
  authored?: AuthoredValue;
  /** Called with complete bindings only; `undefined` removes the binding. */
  onChange: (binding: Binding | undefined) => void;
  onClose: () => void;
}) {
  // The draft may be incomplete (an empty path while typing). It is written to the view only when it would load back.
  const [draft, setDraft] = useState<Binding | undefined>(binding);
  // A problem only a field knows about (query parameter rows that cannot become a record yet).
  const [fieldProblem, setFieldProblem] = useState<string | null>(null);
  // Bumped when the saved binding changes from outside, so fields with their own row state start over.
  const [resetKey, setResetKey] = useState(0);
  const committed = useRef(JSON.stringify(binding ?? null));
  useEffect(() => {
    const saved = JSON.stringify(binding ?? null);
    if (saved !== committed.current) {
      committed.current = saved;
      setDraft(binding);
      setFieldProblem(null);
      setResetKey((key) => key + 1);
    }
  }, [binding]);
  function change(next: Binding | undefined, localProblem: string | null = null) {
    setDraft(next);
    setFieldProblem(localProblem);
    if (localProblem || (next && bindingProblem(next))) return;
    committed.current = JSON.stringify(next ?? null);
    onChange(next);
  }
  const problem = fieldProblem ?? (draft ? bindingProblem(draft) : null);
  const preview = draft ? evaluate(draft) : null;
  return (
    <section className="oip-binding-editor" role="region" aria-label={`Binding for ${prop}`}>
      <header className="oip-binding-head">
        <h3>
          Binding: <code>{prop}</code>
        </h3>
        <button type="button" aria-label="Close binding editor" onClick={onClose}>
          ×
        </button>
      </header>
      <div className="oip-binding-kinds" role="radiogroup" aria-label="Binding type">
        {BINDING_KINDS.map(({ kind, label }) => (
          <button
            key={kind}
            type="button"
            role="radio"
            aria-checked={draft?.kind === kind}
            onClick={() => {
              if (draft?.kind !== kind) change(defaultBinding(kind as BindingKind, draft));
            }}
          >
            {label}
          </button>
        ))}
      </div>
      {!draft ? <p className="oip-binding-muted">Not bound. Pick a type to bind this property.</p> : null}
      {draft?.kind === "tag" ? <TagFields view={view} binding={draft} onChange={change} /> : null}
      {draft?.kind === "property" ? <PropertyFields view={view} nodeId={nodeId} binding={draft} onChange={change} /> : null}
      {draft?.kind === "expression" ? <ExpressionFields binding={draft} onChange={change} /> : null}
      {draft?.kind === "expression-structure" ? <StructureFields binding={draft} onChange={change} /> : null}
      {draft?.kind === "query" ? <QueryFields key={resetKey} binding={draft} onChange={change} /> : null}
      {draft?.kind === "history" ? <HistoryFields binding={draft} onChange={change} /> : null}
      {draft ? (
        <>
          <label className="oip-schema-check">
            <input type="checkbox" checked={draft.enabled !== false} onChange={(event) => change(withEnabled(draft, event.target.checked))} />
            <span>Enabled</span>
          </label>
          <label className="oip-schema-check" title="The value still applies; its quality does not put the quality overlay on the component.">
            <input
              type="checkbox"
              checked={draft.overlayOptOut === true}
              onChange={(event) => {
                const next = { ...draft };
                if (event.target.checked) next.overlayOptOut = true;
                else delete next.overlayOptOut;
                change(next);
              }}
            />
            <span>Overlay opt-out</span>
          </label>
          {problem ? (
            <p className="oip-binding-incomplete" data-testid="binding-incomplete">
              Not applied yet: {problem}. Closing the editor or selecting another component discards this unfinished change.
            </p>
          ) : null}
          <ValueLine binding={draft} preview={preview} />
          {authored ? (
            <div className="oip-binding-authored">
              <SchemaForm schema={{ ...authored.schema, title: "Authored value", description: undefined }} value={authored.value} onChange={authored.onChange} />
              <small className="oip-binding-muted">Shown while the binding has no value.</small>
            </div>
          ) : null}
          <TransformEditor
            transforms={draft.transforms ?? []}
            steps={preview?.steps ?? []}
            inputValue={preview?.source.applied ? { value: preview.source.value } : null}
            onChange={(transforms) => {
              const next = { ...draft };
              if (transforms.length > 0) next.transforms = transforms;
              else delete next.transforms;
              change(next);
            }}
          />
          <button type="button" className="oip-binding-remove" onClick={() => change(undefined)}>
            Remove binding
          </button>
        </>
      ) : null}
    </section>
  );
}

function withEnabled(binding: Binding, enabled: boolean): Binding {
  const next = { ...binding };
  if (enabled) delete next.enabled;
  else next.enabled = false;
  return next;
}

/** The evaluated value: before transforms and after the whole chain. */
function ValueLine({ binding, preview }: { binding: Binding; preview: BindingPreview | null }) {
  if (binding.enabled === false) return <p className="oip-binding-value" data-state="disabled">Disabled: the property keeps its authored value.</p>;
  if (binding.kind === "query" || binding.kind === "history" || binding.kind === "http") {
    return (
      <p className="oip-binding-value" data-state="not_connected" role="status">
        {binding.kind === "history" ? HISTORY_NOT_CONNECTED : QUERY_NOT_CONNECTED}
      </p>
    );
  }
  if (!preview) return null;
  if (!preview.source.applied) {
    return (
      <p className="oip-binding-value" data-state="none" role="status">
        No value yet ({preview.source.code === "good" ? "waiting for a value" : preview.source.code})
      </p>
    );
  }
  const result = preview.result;
  return (
    <p className="oip-binding-value" data-state={result.applied ? result.code : "failed"} role="status" aria-label="Binding value">
      <span>Value {showValue(preview.source.value)}</span>
      {(binding.transforms?.length ?? 0) > 0 ? (
        <span>
          {" "}
          → result {result.applied ? showValue(result.value) : `none (${result.code === "bad_not_connected" ? "not connected" : result.code})`}
        </span>
      ) : null}
    </p>
  );
}

function acceptTagDrop(event: DragEvent<HTMLInputElement>, onPath: (path: string) => void) {
  const raw = event.dataTransfer.getData(TAG_DRAG_TYPE);
  const tag = raw ? decodeDraggedTag(raw) : null;
  const path = tag?.path ?? event.dataTransfer.getData("text/plain").trim();
  if (!path) return;
  event.preventDefault();
  onPath(path);
}

function TagFields({ view, binding, onChange }: { view: ViewDocument; binding: TagBinding; onChange: (binding: Binding) => void }) {
  const names = binding.mode === "indirect" ? placeholders(binding.path) : [];
  const extraRefs = Object.keys(binding.references ?? {}).filter((name) => !names.includes(name));
  const setRef = (name: string, ref: BindingRef) => onChange({ ...binding, references: { ...(binding.references ?? {}), [name]: ref } });
  const sources = useContext(TagSourcesContext);
  const canBrowse = Boolean(sources.gateway || sources.local);
  const [picking, setPicking] = useState(false);
  const browseButton = useRef<HTMLButtonElement | null>(null);
  return (
    <div className="oip-binding-grid">
      <label>
        <span>Mode</span>
        <select aria-label="Tag mode" value={binding.mode} onChange={(event) => onChange({ ...binding, mode: event.target.value as TagBinding["mode"] })}>
          <option value="direct">Direct</option>
          <option value="indirect">Indirect</option>
        </select>
      </label>
      <div className="oip-binding-wide oip-binding-pathrow">
        <label>
          <span>{binding.mode === "indirect" ? "Tag path template" : "Tag path"}</span>
          <input
            aria-label="Tag path"
            type="text"
            value={binding.path}
            placeholder={binding.mode === "indirect" ? "plant/{area}/level" : "Drop a tag or type a path"}
            onDragOver={(event) => {
              if (event.dataTransfer.types.includes(TAG_DRAG_TYPE) || event.dataTransfer.types.includes("text/plain")) event.preventDefault();
            }}
            onDrop={(event) => acceptTagDrop(event, (path) => onChange({ ...binding, path }))}
            onChange={(event) => onChange({ ...binding, path: event.target.value })}
          />
        </label>
        {canBrowse ? (
          <button ref={browseButton} type="button" className="oip-binding-browse" aria-haspopup="dialog" onClick={() => setPicking(true)}>
            Browse…
          </button>
        ) : null}
      </div>
      {picking ? (
        <TagPickerDialog onPick={(tag) => onChange({ ...binding, path: tag.path })} onClose={() => setPicking(false)} returnFocus={browseButton} />
      ) : null}
      {binding.mode === "indirect" ? (
        <div className="oip-binding-wide oip-refs" role="group" aria-label="References">
          {names.length === 0 ? <p className="oip-binding-muted">Add {"{name}"} to the path for each part that comes from a property or parameter.</p> : null}
          {names.map((name) => (
            <RefRow key={name} view={view} name={name} value={binding.references?.[name]} onChange={(ref) => setRef(name, ref)} />
          ))}
          {extraRefs.length > 0 ? (
            <p className="oip-binding-muted">Kept but unused by the path: {extraRefs.join(", ")}</p>
          ) : null}
        </div>
      ) : null}
      <label className="oip-schema-check">
        <input
          type="checkbox"
          checked={binding.bidirectional === true}
          onChange={(event) => {
            const next = { ...binding };
            if (event.target.checked) next.bidirectional = true;
            else delete next.bidirectional;
            onChange(next);
          }}
        />
        <span>Bidirectional</span>
      </label>
    </div>
  );
}

function RefRow({ view, name, value, onChange }: { view: ViewDocument; name: string; value: BindingRef | undefined; onChange: (ref: BindingRef) => void }) {
  const ref = value ?? defaultRef();
  const nodes = componentNodes(view);
  return (
    <div className="oip-ref-row" role="group" aria-label={`Reference ${name}`}>
      <code>{`{${name}}`}</code>
      <select
        aria-label={`${name} source`}
        value={ref.source}
        onChange={(event) => {
          const source = event.target.value;
          if (source === "prop") onChange({ source: "prop", nodeId: nodes[0]?.id ?? "", prop: "" });
          else onChange({ source: source as "param" | "custom", name: "" });
        }}
      >
        <option value="param">View param</option>
        <option value="custom">Custom prop</option>
        <option value="prop">Component prop</option>
      </select>
      {ref.source === "prop" ? (
        <NodePropPicker view={view} label={name} nodeId={ref.nodeId} prop={ref.prop} onChange={(nodeId, prop) => onChange({ source: "prop", nodeId, prop })} />
      ) : (
        <NamePicker
          label={`${name} name`}
          options={Object.keys(ref.source === "param" ? view.spec.params : view.spec.custom)}
          value={ref.name}
          onChange={(text) => onChange({ source: ref.source, name: text })}
        />
      )}
    </div>
  );
}

function NamePicker({ label, options, value, onChange }: { label: string; options: string[]; value: string; onChange: (value: string) => void }) {
  const listId = `oip-names-${label.replace(/[^A-Za-z0-9_-]/g, "-")}`;
  return (
    <>
      <input aria-label={label} type="text" list={listId} value={value} onChange={(event) => onChange(event.target.value)} />
      <datalist id={listId}>
        {options.map((option) => (
          <option key={option} value={option} />
        ))}
      </datalist>
    </>
  );
}

function NodePropPicker({
  view,
  label,
  nodeId,
  prop,
  onChange,
}: {
  view: ViewDocument;
  label: string;
  nodeId: string;
  prop: string;
  onChange: (nodeId: string, prop: string) => void;
}) {
  const nodes = componentNodes(view);
  const node = nodes.find((candidate) => candidate.id === nodeId);
  const props = Object.keys(componentRegistry().get(node?.type ?? "")?.schema.properties ?? node?.props ?? {});
  return (
    <>
      <select aria-label={`${label} component`} value={nodeId} onChange={(event) => onChange(event.target.value, prop)}>
        {node ? null : <option value={nodeId}>{nodeId || "(choose)"}</option>}
        {nodes.map((candidate) => (
          <option key={candidate.id} value={candidate.id}>
            {candidate.name}
          </option>
        ))}
      </select>
      <NamePicker label={`${label} property`} options={props} value={prop} onChange={(text) => onChange(nodeId, text)} />
    </>
  );
}

const SCOPES: Array<{ scope: PropertySource["scope"]; label: string }> = [
  { scope: "prop", label: "Component prop" },
  { scope: "param", label: "View param" },
  { scope: "custom", label: "Custom prop" },
  { scope: "session", label: "Session" },
  { scope: "page", label: "Page" },
];

function PropertyFields({ view, nodeId, binding, onChange }: { view: ViewDocument; nodeId: string; binding: PropertyBinding; onChange: (binding: Binding) => void }) {
  const source = binding.source;
  const setSource = (next: PropertySource) => onChange({ ...binding, source: next });
  return (
    <div className="oip-binding-grid">
      <label className="oip-binding-wide">
        <span>Source</span>
        <select
          aria-label="Property source"
          value={source.scope}
          onChange={(event) => {
            const scope = event.target.value as PropertySource["scope"];
            if (scope === "prop") setSource({ scope, nodeId, prop: "" });
            else if (scope === "param" || scope === "custom") setSource({ scope, name: "" });
            else setSource({ scope, path: "" });
          }}
        >
          {SCOPES.map((entry) => (
            <option key={entry.scope} value={entry.scope}>
              {entry.label}
            </option>
          ))}
        </select>
      </label>
      <div className="oip-binding-wide oip-ref-row">
        {source.scope === "prop" ? (
          <NodePropPicker view={view} label="Source" nodeId={source.nodeId} prop={source.prop} onChange={(id, prop) => setSource({ scope: "prop", nodeId: id, prop })} />
        ) : source.scope === "param" || source.scope === "custom" ? (
          <NamePicker
            label="Source name"
            options={Object.keys(source.scope === "param" ? view.spec.params : view.spec.custom)}
            value={source.name}
            onChange={(name) => setSource({ scope: source.scope, name })}
          />
        ) : (
          <NamePicker
            label="Source path"
            options={source.scope === "session" ? SESSION_PATHS : PAGE_PATHS}
            value={source.path}
            onChange={(path) => setSource({ scope: source.scope, path })}
          />
        )}
      </div>
      {source.scope === "session" || source.scope === "page" ? (
        <p className="oip-binding-muted oip-binding-wide">Session and page values resolve in a live session.</p>
      ) : null}
      <label className="oip-schema-check">
        <input
          type="checkbox"
          checked={binding.bidirectional === true}
          onChange={(event) => {
            const next = { ...binding };
            if (event.target.checked) next.bidirectional = true;
            else delete next.bidirectional;
            onChange(next);
          }}
        />
        <span>Bidirectional</span>
      </label>
    </div>
  );
}

function ExpressionFields({ binding, onChange }: { binding: ExpressionBinding; onChange: (binding: Binding) => void }) {
  const error = expressionError(binding.expression);
  return (
    <div className="oip-binding-grid">
      <label className="oip-binding-wide">
        <span>Expression</span>
        <textarea
          aria-label="Expression"
          aria-invalid={error ? true : undefined}
          rows={3}
          spellCheck={false}
          value={binding.expression}
          placeholder="{this.props.value} * 2"
          onChange={(event) => onChange({ ...binding, expression: event.target.value })}
        />
      </label>
      <label>
        <span>Poll (ms)</span>
        <input
          aria-label="Poll interval"
          aria-describedby="oip-expression-poll-hint"
          type="number"
          min={0}
          step={100}
          value={binding.pollMs ?? 0}
          onChange={(event) => {
            // HMP-118: 0 (or empty) is no poll; the parser refuses an interval under the minimum and the editor says so.
            const next = { ...binding };
            const value = Number(event.target.value);
            if (event.target.value !== "" && Number.isFinite(value) && value !== 0) next.pollMs = value;
            else delete next.pollMs;
            onChange(next);
          }}
        />
      </label>
      <p id="oip-expression-poll-hint" className="oip-binding-muted">
        Re-evaluates every interval as well as when a value it reads changes. 0 is no poll; the shortest is {MIN_EXPRESSION_POLL_MS} ms.
      </p>
      {error ? (
        <p className="oip-binding-error oip-binding-wide" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function HistoryFields({ binding, onChange }: { binding: HistoryBinding; onChange: (binding: Binding) => void }) {
  const setNumber = (key: "rangeMs" | "intervalMs" | "pollMs", raw: string) => {
    const value = Number(raw);
    const next = { ...binding };
    if (key === "pollMs") {
      if (Number.isFinite(value) && value > 0) next.pollMs = value;
      else delete next.pollMs;
    } else if (Number.isInteger(value) && value > 0) {
      next[key] = value;
    }
    onChange(next);
  };
  return (
    <div className="oip-binding-grid">
      <label className="oip-binding-wide">
        <span>Tag path</span>
        <input
          aria-label="History tag path"
          type="text"
          value={binding.path}
          placeholder="[default]plant/level"
          onChange={(event) => onChange({ ...binding, path: event.target.value })}
          onDragOver={(event) => {
            if (event.dataTransfer.types.includes(TAG_DRAG_TYPE) || event.dataTransfer.types.includes("text/plain")) event.preventDefault();
          }}
          onDrop={(event) => acceptTagDrop(event, (path) => onChange({ ...binding, path }))}
        />
      </label>
      <label>
        <span>Range (ms)</span>
        <input aria-label="History range" type="number" min={1} step={1} value={binding.rangeMs} onChange={(event) => setNumber("rangeMs", event.target.value)} />
      </label>
      <label>
        <span>Interval (ms)</span>
        <input aria-label="History interval" type="number" min={1} step={1} value={binding.intervalMs} onChange={(event) => setNumber("intervalMs", event.target.value)} />
      </label>
      <label>
        <span>Aggregate</span>
        <select
          aria-label="History aggregate"
          value={binding.aggregate ?? "Average"}
          onChange={(event) => onChange({ ...binding, aggregate: event.target.value as HistoryBinding["aggregate"] })}
        >
          {HISTORY_AGGREGATES.map((mode) => (
            <option key={mode} value={mode}>
              {mode}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>Poll (ms)</span>
        <input aria-label="History poll" type="number" min={0} value={binding.pollMs ?? 0} onChange={(event) => setNumber("pollMs", event.target.value)} />
      </label>
    </div>
  );
}

/** Why a list of expression structure rows cannot be saved as named expressions, or null. */
export function structureRowsProblem(rows: ReadonlyArray<readonly [string, string]>): string | null {
  const seen = new Set<string>();
  for (const [index, [name]] of rows.entries()) {
    if (name.trim() === "") return `expression ${index + 1} needs a name`;
    if (seen.has(name)) return `two expressions are named "${name}"`;
    seen.add(name);
  }
  return null;
}

function StructureFields({ binding, onChange }: { binding: ExpressionStructureBinding; onChange: (binding: Binding, problem: string | null) => void }) {
  // Rows stay a list while editing, so a rename that collides with another name never drops an expression.
  const [rows, setRows] = useState<Array<[string, string]>>(() => Object.entries(binding.expressions));
  const emit = (next: Array<[string, string]>, base: ExpressionStructureBinding = binding) => {
    setRows(next);
    const problem = structureRowsProblem(next);
    onChange(problem ? base : { ...base, expressions: Object.fromEntries(next) }, problem);
  };
  return (
    <div className="oip-binding-grid">
      <div className="oip-binding-wide oip-refs" role="group" aria-label="Named expressions">
        {rows.map(([name, expression], index) => {
          const error = expressionError(expression);
          return (
            <div key={index} className="oip-ref-row" role="group" aria-label={`Expression ${index + 1}`}>
              <input
                aria-label={`Expression ${index + 1} name`}
                type="text"
                value={name}
                onChange={(event) => emit(rows.map((row, at) => (at === index ? [event.target.value, expression] : row)))}
              />
              <input
                aria-label={`Expression ${index + 1} text`}
                aria-invalid={error ? true : undefined}
                type="text"
                spellCheck={false}
                value={expression}
                placeholder="{this.props.value} * 2"
                onChange={(event) => emit(rows.map((row, at) => (at === index ? [name, event.target.value] : row)))}
              />
              <button type="button" aria-label={`Remove expression ${index + 1}`} onClick={() => emit(rows.filter((_, at) => at !== index))}>
                ×
              </button>
              {error ? (
                <p className="oip-binding-error" role="alert">
                  {error}
                </p>
              ) : null}
            </div>
          );
        })}
        <button type="button" onClick={() => emit([...rows, [uniqueStructureName(rows.map(([name]) => name)), ""]])}>
          + Named expression
        </button>
      </div>
      <label>
        <span>Poll (ms)</span>
        <input
          aria-label="Poll interval"
          type="number"
          min={0}
          step={100}
          value={binding.pollMs ?? 0}
          onChange={(event) => {
            const next = { ...binding };
            const value = Number(event.target.value);
            if (event.target.value !== "" && Number.isFinite(value) && value !== 0) next.pollMs = value;
            else delete next.pollMs;
            emit(rows, next);
          }}
        />
      </label>
    </div>
  );
}

function uniqueStructureName(taken: string[]): string {
  let index = taken.length + 1;
  while (taken.includes(`key${index}`)) index += 1;
  return `key${index}`;
}

function QueryFields({ binding, onChange }: { binding: QueryBinding; onChange: (binding: Binding, problem: string | null) => void }) {
  // Rows stay a list while editing, so a rename that collides with another name never drops a parameter.
  const [params, setRows] = useState<Array<[string, QueryParameter]>>(() => Object.entries(binding.parameters ?? {}));
  const emit = (next: QueryBinding, rows = params) => {
    const problem = queryRowsProblem(rows);
    const out = { ...next };
    if (!problem) {
      if (rows.length > 0) out.parameters = Object.fromEntries(rows);
      else delete out.parameters;
    }
    onChange(out, problem);
  };
  const setParams = (rows: Array<[string, QueryParameter]>) => {
    setRows(rows);
    emit(binding, rows);
  };
  return (
    <div className="oip-binding-grid">
      <label className="oip-binding-wide">
        <span>Named query</span>
        <input aria-label="Named query" type="text" value={binding.path} placeholder="folder/query" onChange={(event) => emit({ ...binding, path: event.target.value })} />
      </label>
      <label>
        <span>Return format</span>
        <select
          aria-label="Return format"
          value={binding.returnFormat ?? "auto"}
          onChange={(event) => emit({ ...binding, returnFormat: event.target.value as QueryBinding["returnFormat"] })}
        >
          {(["auto", "json", "dataset", "scalar"] as const).map((format) => (
            <option key={format} value={format}>
              {format}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>Poll (ms)</span>
        <input
          aria-label="Poll interval"
          type="number"
          min={0}
          value={binding.pollMs ?? 0}
          onChange={(event) => {
            const next = { ...binding };
            const value = Number(event.target.value);
            if (Number.isFinite(value) && value > 0) next.pollMs = value;
            else delete next.pollMs;
            emit(next);
          }}
        />
      </label>
      <div className="oip-binding-wide oip-refs" role="group" aria-label="Query parameters">
        {params.map(([name, parameter], index) => {
          const isExpression = "expression" in parameter;
          const replaceAt = (entry: [string, QueryParameter]) => setParams(params.map((current, at) => (at === index ? entry : current)));
          return (
            <div key={index} className="oip-ref-row" role="group" aria-label={`Parameter ${index + 1}`}>
              <input aria-label={`Parameter ${index + 1} name`} type="text" value={name} onChange={(event) => replaceAt([event.target.value, parameter])} />
              <select
                aria-label={`Parameter ${index + 1} kind`}
                value={isExpression ? "expression" : "value"}
                onChange={(event) => replaceAt([name, event.target.value === "expression" ? { expression: "" } : { value: "" }])}
              >
                <option value="value">Value</option>
                <option value="expression">Expression</option>
              </select>
              {isExpression ? (
                <input
                  aria-label={`Parameter ${index + 1} expression`}
                  type="text"
                  value={parameter.expression}
                  onChange={(event) => replaceAt([name, { expression: event.target.value }])}
                />
              ) : (
                <LiteralInput label={`Parameter ${index + 1} value`} value={parameter.value} onChange={(value) => replaceAt([name, { value }])} />
              )}
              <button type="button" aria-label={`Remove parameter ${index + 1}`} onClick={() => setParams(params.filter((_, at) => at !== index))}>
                ×
              </button>
            </div>
          );
        })}
        <button type="button" onClick={() => setParams([...params, [uniqueName(params.map(([name]) => name)), { value: "" }]])}>
          + Parameter
        </button>
      </div>
    </div>
  );
}

function uniqueName(taken: string[]): string {
  let index = taken.length + 1;
  while (taken.includes(`param${index}`)) index += 1;
  return `param${index}`;
}
