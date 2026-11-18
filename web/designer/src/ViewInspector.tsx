import { useId, useState, type ReactNode } from "react";
import { DROP_DATA_TYPES, type DropRule, type JsonSchema, type ViewDocument } from "@oip/hmi-runtime";
import { componentModules } from "@oip/components";
import { cleanSecurity, SecurityEditor } from "./SecurityEditor";
import { MethodsEditor, setViewMethods } from "./MethodsEditor";
import { suggestHook } from "./eventModel";

/**
 * The open view's own properties in the Studio, shown while nothing on the canvas is selected:
 * - custom properties (HMP-073): view-level values that bindings read with the `custom` scope;
 * - security (HMP-092, SEC-017): the roles and security levels that may open the view in a session;
 * - drop configuration (HMP-110): what a tag dropped on an empty part of the canvas becomes.
 * Each edit replaces the view, so it is saved with it.
 *
 * Doc source: FEATURES HMP-073, HMP-092, HMP-110.
 */
export function ViewInspector({
  view,
  viewPaths,
  scriptHooks = NO_HOOKS,
  onView,
}: {
  view: ViewDocument;
  viewPaths: readonly string[];
  /** Hook names in the project's script hook set, for a script action in a view method. */
  scriptHooks?: ReadonlySet<string>;
  onView: (view: ViewDocument) => void;
}) {
  function patch(spec: Partial<ViewDocument["spec"]>) {
    const next: ViewDocument = { ...view, spec: { ...view.spec, ...spec } };
    if ("security" in spec) {
      const clean = cleanSecurity(spec.security);
      if (clean) next.spec.security = clean;
      else delete next.spec.security;
    }
    if (next.spec.dropConfig && next.spec.dropConfig.length === 0) delete next.spec.dropConfig;
    onView(next);
  }
  return (
    <div className="oip-view-inspector" aria-label={`View ${view.metadata.name}`} role="group">
      <h2 className="oip-view-inspector-title">View {view.metadata.name}</h2>
      <CustomProps view={view} onChange={(custom) => patch({ custom })} />
      <SecurityEditor
        label="View security"
        rolesLabel="Roles that may open this view"
        security={view.spec.security}
        onChange={(security) => patch({ security })}
      />
      <MethodsEditor
        title="View methods"
        methods={view.spec.methods}
        suggestHook={(name) => suggestHook(view, view.spec.root.id, `method-${name}`)}
        viewPath={view.metadata.path}
        viewPaths={viewPaths}
        projectHooks={scriptHooks}
        onChange={(methods) => onView(setViewMethods(view, methods))}
      />
      <DropConfigEditor rules={view.spec.dropConfig ?? []} viewPaths={viewPaths.filter((path) => path !== view.metadata.path)} onChange={(dropConfig) => patch({ dropConfig })} />
    </div>
  );
}

const NO_HOOKS: ReadonlySet<string> = new Set();
const NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;

/** A typed value as text: JSON for numbers, booleans, null, lists and objects; strings as they are. */
export function showCustom(value: unknown): string {
  return typeof value === "string" ? value : JSON.stringify(value);
}

/** What the author typed: a number, `true`/`false`, `null`, or JSON list/object when it reads as one; else a string. */
export function readCustom(text: string): unknown {
  const trimmed = text.trim();
  if (trimmed === "") return "";
  if (/^-?\d+(\.\d+)?$/.test(trimmed) || trimmed === "true" || trimmed === "false" || trimmed === "null" || /^[[{]/.test(trimmed)) {
    try {
      return JSON.parse(trimmed) as unknown;
    } catch {
      return text;
    }
  }
  return text;
}

function CustomProps({ view, onChange }: { view: ViewDocument; onChange: (custom: ViewDocument["spec"]["custom"]) => void }) {
  const [name, setName] = useState("");
  const [value, setValue] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const nameId = useId();
  const valueId = useId();
  const entries = Object.entries(view.spec.custom);
  function add() {
    const trimmed = name.trim();
    if (!NAME.test(trimmed)) return setProblem("A custom property name starts with a letter or _ and has only letters, digits and _.");
    if (Object.prototype.hasOwnProperty.call(view.spec.custom, trimmed)) return setProblem(`The view already has a custom property ${trimmed}.`);
    setProblem(null);
    onChange({ ...view.spec.custom, [trimmed]: { value: readCustom(value) } });
    setName("");
    setValue("");
  }
  return (
    <section className="oip-view-section" aria-label="Custom properties">
      <h3>Custom properties</h3>
      {entries.length === 0 ? <p className="oip-view-empty">None yet.</p> : null}
      <ul className="oip-view-rows">
        {entries.map(([key, entry]) => (
          <li key={key} data-custom-prop={key}>
            <Field label={key}>
              {(id) => (
                <input id={id}
                aria-label={`Custom property ${key}`}
                defaultValue={showCustom(entry.value)}
                key={showCustom(entry.value)}
                onBlur={(event) => {
                  const next = readCustom(event.target.value);
                  if (JSON.stringify(next) !== JSON.stringify(entry.value)) onChange({ ...view.spec.custom, [key]: { ...entry, value: next } });
                }}
              />
              )}
            </Field>
            {entry.binding ? <span className="oip-view-note">bound</span> : null}
            <button
              type="button"
              aria-label={`Remove custom property ${key}`}
              onClick={() => {
                const rest = { ...view.spec.custom };
                delete rest[key];
                onChange(rest);
              }}
            >
              Remove
            </button>
          </li>
        ))}
      </ul>
      <div className="oip-view-add">
        <label htmlFor={nameId}>Name</label>
        <input id={nameId} value={name} onChange={(event) => setName(event.target.value)} placeholder="setpoint" />
        <label htmlFor={valueId}>Value</label>
        <input id={valueId} value={value} onChange={(event) => setValue(event.target.value)} placeholder="42, true, text, [1, 2]" />
        <button type="button" onClick={add}>
          Add custom property
        </button>
        {problem ? <p role="alert">{problem}</p> : null}
      </div>
    </section>
  );
}

const COMPONENTS = [...componentModules].filter((module) => module.type !== "embedded-view").sort((a, b) => a.title.localeCompare(b.title));

function propsOf(type: string): string[] {
  const schema: JsonSchema | undefined = componentModules.find((module) => module.type === type)?.schema;
  return Object.keys(schema?.properties ?? {});
}

function DropConfigEditor({ rules, viewPaths, onChange }: { rules: readonly DropRule[]; viewPaths: readonly string[]; onChange: (rules: DropRule[]) => void }) {
  function replace(index: number, rule: DropRule) {
    onChange(rules.map((current, at) => (at === index ? rule : current)));
  }
  return (
    <section className="oip-view-section" aria-label="Drop configuration">
      <h3>Drop configuration</h3>
      <p className="oip-view-note">What a tag dropped on an empty part of this view becomes. The first rule for the tag&apos;s data type wins, then the first rule for any type.</p>
      <ol className="oip-view-rows">
        {rules.map((rule, index) => (
          <li key={index} data-drop-rule={index} role="group" aria-label={`Drop rule ${index + 1}`}>
            <Field label="Data type">
              {(id) => (
                <select id={id} value={rule.dataType} onChange={(event) => replace(index, { ...rule, dataType: event.target.value })}>
                {DROP_DATA_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type === "*" ? "Any" : type}
                  </option>
                ))}
              </select>
              )}
            </Field>
            <Field label="Becomes">
              {(id) => (
                <select id={id}
                value={"view" in rule ? "view" : "component"}
                onChange={(event) =>
                  replace(index, event.target.value === "view" ? { dataType: rule.dataType, view: viewPaths[0] ?? "", param: "tagPath" } : { dataType: rule.dataType, component: "label", prop: "text" })
                }
              >
                <option value="component">Component</option>
                <option value="view">Embedded view</option>
              </select>
              )}
            </Field>
            {"view" in rule ? (
              <>
                <Field label="View">
                  {(id) => (
                    <select id={id} value={rule.view} onChange={(event) => replace(index, { ...rule, view: event.target.value })}>
                    {rule.view && !viewPaths.includes(rule.view) ? <option value={rule.view}>{rule.view}</option> : null}
                    {viewPaths.map((path) => (
                      <option key={path} value={path}>
                        {path}
                      </option>
                    ))}
                  </select>
                  )}
                </Field>
                <Field label="Tag path param">
                  {(id) => (
                    <input id={id} defaultValue={rule.param} key={rule.param} onBlur={(event) => event.target.value.trim() && replace(index, { ...rule, param: event.target.value.trim() })} />
                  )}
                </Field>
              </>
            ) : (
              <>
                <Field label="Component">
                  {(id) => (
                    <select id={id}
                    value={rule.component}
                    onChange={(event) => {
                      const props = propsOf(event.target.value);
                      const prop = props.includes(rule.prop) ? rule.prop : props.includes("value") ? "value" : (props[0] ?? "value");
                      replace(index, { ...rule, component: event.target.value, prop });
                    }}
                  >
                    {COMPONENTS.map((module) => (
                      <option key={module.type} value={module.type}>
                        {module.title}
                      </option>
                    ))}
                  </select>
                  )}
                </Field>
                <Field label="Bound property">
                  {(id) => (
                    <select id={id} value={rule.prop} onChange={(event) => replace(index, { ...rule, prop: event.target.value })}>
                    {propsOf(rule.component).map((prop) => (
                      <option key={prop} value={prop}>
                        {prop}
                      </option>
                    ))}
                  </select>
                  )}
                </Field>
                <label className="oip-view-check">
                  <input type="checkbox" checked={rule.bidirectional === true} onChange={(event) => replace(index, { ...rule, bidirectional: event.target.checked })} />
                  Bidirectional
                </label>
              </>
            )}
            <button type="button" aria-label={`Remove drop rule ${index + 1}`} onClick={() => onChange(rules.filter((_, at) => at !== index))}>
              Remove
            </button>
          </li>
        ))}
      </ol>
      <button type="button" onClick={() => onChange([...rules, { dataType: "*", component: "label", prop: "text" }])}>
        Add drop rule
      </button>
    </section>
  );
}

/** A labelled field whose label names only the field, not the options inside it. */
function Field({ label, children }: { label: string; children: (id: string) => ReactNode }) {
  const id = useId();
  return (
    <div className="oip-view-field">
      <label htmlFor={id}>{label}</label>
      {children(id)}
    </div>
  );
}
