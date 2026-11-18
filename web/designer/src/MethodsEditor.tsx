import { useState } from "react";
import type { ComponentEvent, MethodDef, MethodMap, MethodParam } from "@oip/hmi-runtime";
import { findNode, type ViewDocument } from "@oip/hmi-runtime";
import { EventEditor } from "./EventEditor";
import { literalText, parseLiteral } from "./bindingModel";
import { methodNameProblem, methodParamProblem } from "./eventModel";

/**
 * Custom methods of a component or a view (HMV-114): a named list of actions with declared parameters that events
 * (the Call method action) and gateway scripts (`component.callMethod(name, args)`) can call. In a method's actions,
 * `{{param}}` stands for the argument of that name in values (tag write value, navigation and popup params, message
 * payload). A script action in a method receives the arguments as `event.payload`.
 */

const RUN = "run";

/** A method's action list as the single event the action editor edits. */
function asEvents(def: MethodDef): ComponentEvent[] {
  return def.actions.length > 0 ? [{ event: RUN, actions: def.actions }] : [];
}

export function MethodsEditor({
  title,
  methods,
  suggestHook,
  viewPath,
  viewPaths,
  projectHooks,
  onChange,
}: {
  title: string;
  methods: MethodMap | undefined;
  /** A free hook name for a new script action in method [name]. */
  suggestHook: (name: string) => string;
  viewPath: string;
  viewPaths: readonly string[];
  projectHooks: ReadonlySet<string>;
  onChange: (methods: MethodMap) => void;
}) {
  const map = methods ?? {};
  const names = Object.keys(map);
  const [newName, setNewName] = useState("");
  const taken = newName !== "" && Object.prototype.hasOwnProperty.call(map, newName);
  const problem = newName === "" ? null : (methodNameProblem(newName) ?? (taken ? "A method of that name exists." : null));
  const set = (name: string, def: MethodDef) => onChange({ ...map, [name]: def });
  return (
    <section className="oip-events oip-methods" aria-label={title}>
      <h3>{title}</h3>
      {names.length === 0 ? <p className="oip-binding-muted">No custom methods.</p> : null}
      {names.map((name) => {
        const def = map[name]!;
        return (
          <div key={name} className="oip-transform" aria-label={`Method ${name}`} data-method={name}>
            <p>
              <code>{name}</code>
              {def.params && def.params.length > 0 ? <span className="oip-binding-muted"> ({def.params.map((param) => param.name).join(", ")})</span> : null}
            </p>
            <ParamList params={def.params ?? []} method={name} onChange={(params) => set(name, withParams(def, params))} />
            <EventEditor
              title={`Actions of ${name}`}
              eventNames={[RUN]}
              events={asEvents(def)}
              suggestHook={() => suggestHook(name)}
              viewPath={viewPath}
              viewPaths={viewPaths}
              projectHooks={projectHooks}
              onChange={(events) => set(name, { ...def, actions: events.find((entry) => entry.event === RUN)?.actions ?? [] })}
            />
            <button
              type="button"
              aria-label={`Remove method ${name}`}
              onClick={() => {
                const next = { ...map };
                delete next[name];
                onChange(next);
              }}
            >
              Remove method
            </button>
          </div>
        );
      })}
      <div className="oip-transform-add">
        <input aria-label="New method name" type="text" value={newName} aria-invalid={problem ? true : undefined} placeholder="methodName" onChange={(event) => setNewName(event.target.value)} />
        <button
          type="button"
          disabled={newName === "" || problem !== null}
          onClick={() => {
            set(newName, { actions: [] });
            setNewName("");
          }}
        >
          + Add method
        </button>
      </div>
      {problem ? (
        <p className="oip-binding-error" role="alert">
          {problem}
        </p>
      ) : null}
    </section>
  );
}

function withParams(def: MethodDef, params: MethodParam[]): MethodDef {
  const next = { ...def };
  if (params.length > 0) next.params = params;
  else delete next.params;
  return next;
}

function ParamList({ params, method, onChange }: { params: MethodParam[]; method: string; onChange: (params: MethodParam[]) => void }) {
  const [name, setName] = useState("");
  const problem = name === "" ? null : (methodParamProblem(name) ?? (params.some((param) => param.name === name) ? "That parameter exists." : null));
  return (
    <div className="oip-method-params" aria-label={`Parameters of ${method}`}>
      {params.map((param, index) => (
        <div key={param.name} className="oip-binding-grid">
          <code>{param.name}</code>
          <label>
            <span>Default</span>
            <input
              aria-label={`Default of ${param.name}`}
              type="text"
              value={param.default === undefined ? "" : literalText(param.default)}
              onChange={(event) => {
                const next = params.map((entry, at) => {
                  if (at !== index) return entry;
                  const { default: _drop, ...rest } = entry;
                  void _drop;
                  return event.target.value === "" ? rest : { ...rest, default: parseLiteral(event.target.value) };
                });
                onChange(next);
              }}
            />
          </label>
          <button type="button" aria-label={`Remove parameter ${param.name}`} onClick={() => onChange(params.filter((_, at) => at !== index))}>
            Remove
          </button>
        </div>
      ))}
      <div className="oip-transform-add">
        <input aria-label={`New parameter of ${method}`} type="text" value={name} aria-invalid={problem ? true : undefined} placeholder="param" onChange={(event) => setName(event.target.value)} />
        <button
          type="button"
          disabled={name === "" || problem !== null}
          onClick={() => {
            onChange([...params, { name }]);
            setName("");
          }}
        >
          + Add parameter
        </button>
      </div>
      {problem ? <small className="oip-binding-error">{problem}</small> : null}
    </div>
  );
}

/** The view with component [id]'s methods replaced (removed when empty). */
export function setNodeMethods(view: ViewDocument, id: string, methods: MethodMap): ViewDocument {
  const next = structuredClone(view);
  const node = findNode(next.spec.root, id);
  if (!node || node.kind !== "component") throw new Error(`component ${id} not found`);
  if (Object.keys(methods).length > 0) node.methods = methods;
  else delete node.methods;
  return next;
}

/** The view with its own methods replaced (removed when empty). */
export function setViewMethods(view: ViewDocument, methods: MethodMap): ViewDocument {
  const next = structuredClone(view);
  if (Object.keys(methods).length > 0) next.spec.methods = methods;
  else delete next.spec.methods;
  return next;
}
