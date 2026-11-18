import { useState } from "react";
import { ScriptBuilder } from "./ScriptBuilder";
import type { Action, CallMethodAction, ComponentEvent, MessageAction, MessageScope, NavigationAction, PopupAction, ScriptAction, TagWriteAction } from "@oip/hmi-runtime";
import { ScriptEditor } from "./ScriptEditor";
import { moveItem } from "./TransformEditor";
import { literalText, parseLiteral } from "./bindingModel";
import { ACTION_KINDS, actionLabel, actionProblem, actionsFor, hookNameProblem, messageTypeProblem, methodNameProblem, newAction, withActions, withNavigationMode, type ActionKind } from "./eventModel";
import "./eventEditor.css";

/**
 * Inspector section for a component's or view's event handlers (D-5): per event, an ordered list of actions the
 * session runs from top to bottom. Event scripts run on the gateway as the signed-in user; the design canvas never
 * runs any of them.
 */
export function EventEditor({
  title,
  eventNames,
  events,
  suggestHook,
  viewPath,
  viewPaths,
  projectHooks,
  onChange,
}: {
  title: string;
  eventNames: readonly string[];
  events: readonly ComponentEvent[] | undefined;
  /** A free hook name for a new script action on [eventName]. */
  suggestHook: (eventName: string) => string;
  /** The view being edited (the default for a new popup or view navigation). */
  viewPath: string;
  /** The project's views, offered for popup and navigation targets. */
  viewPaths: readonly string[];
  /** Hooks in the project's script hook set, for an action whose source is left empty. */
  projectHooks: ReadonlySet<string>;
  onChange: (events: ComponentEvent[]) => void;
}) {
  const [open, setOpen] = useState<string | null>(null);
  const listId = `oip-event-views-${viewPath}`;
  return (
    <section className="oip-events" aria-label={title}>
      <h3>{title}</h3>
      <datalist id={listId}>
        {viewPaths.map((path) => (
          <option key={path} value={path} />
        ))}
      </datalist>
      {eventNames.map((eventName) => {
        const actions = actionsFor(events, eventName);
        const expanded = open === eventName;
        const set = (next: Action[]) => onChange(withActions(events, eventName, next));
        return (
          <div key={eventName} className="oip-event" data-event={eventName}>
            <button
              type="button"
              className="oip-event-toggle"
              aria-expanded={expanded}
              aria-label={`${eventName}: ${actions.length === 0 ? "no actions" : actions.length === 1 ? "1 action" : `${actions.length} actions`}`}
              onClick={() => setOpen(expanded ? null : eventName)}
            >
              <code>{eventName}</code>
              <span className="oip-event-summary">{actions.length === 0 ? "—" : actions.map((action) => actionLabel(action.kind)).join(", ")}</span>
            </button>
            {expanded ? (
              <div className="oip-binding-editor oip-event-body" role="region" aria-label={`Actions for ${eventName}`}>
                {actions.length === 0 ? <p className="oip-binding-muted">No actions. Add one below.</p> : null}
                <ol className="oip-transform-list">
                  {actions.map((action, index) => (
                    <ActionItem
                      key={`${index}-${action.kind}`}
                      action={action}
                      label={`${eventName} action ${index + 1}`}
                      first={index === 0}
                      last={index === actions.length - 1}
                      viewPath={viewPath}
                      listId={listId}
                      projectHooks={projectHooks}
                      onChange={(next) => set(actions.map((entry, at) => (at === index ? next : entry)))}
                      onMove={(to) => set(moveItem(actions, index, to))}
                      onRemove={() => set(actions.filter((_, at) => at !== index))}
                      index={index}
                    />
                  ))}
                </ol>
                <AddAction
                  eventName={eventName}
                  onAdd={(kind) => set([...actions, newAction(kind, { hook: suggestHook(eventName), view: viewPath })])}
                />
              </div>
            ) : null}
          </div>
        );
      })}
    </section>
  );
}

function AddAction({ eventName, onAdd }: { eventName: string; onAdd: (kind: ActionKind) => void }) {
  const [kind, setKind] = useState<ActionKind>("script");
  return (
    <div className="oip-transform-add oip-event-add">
      <select aria-label={`New ${eventName} action type`} value={kind} onChange={(event) => setKind(event.target.value as ActionKind)}>
        {ACTION_KINDS.map((entry) => (
          <option key={entry.kind} value={entry.kind}>
            {entry.label}
          </option>
        ))}
      </select>
      <button type="button" onClick={() => onAdd(kind)}>
        + Add action
      </button>
    </div>
  );
}

function ActionItem({
  action,
  label,
  index,
  first,
  last,
  viewPath,
  listId,
  projectHooks,
  onChange,
  onMove,
  onRemove,
}: {
  action: Action;
  label: string;
  index: number;
  first: boolean;
  last: boolean;
  viewPath: string;
  listId: string;
  projectHooks: ReadonlySet<string>;
  onChange: (action: Action) => void;
  onMove: (to: number) => void;
  onRemove: () => void;
}) {
  const full = `${label}: ${actionLabel(action.kind)}`;
  const problem = actionProblem(action);
  return (
    <li className="oip-transform oip-action" data-kind={action.kind} aria-label={full}>
      <div className="oip-transform-head">
        <strong>
          {index + 1}. {actionLabel(action.kind)}
        </strong>
        <span className="oip-transform-tools">
          <button type="button" aria-label={`Move ${full} up`} disabled={first} onClick={() => onMove(index - 1)}>
            ↑
          </button>
          <button type="button" aria-label={`Move ${full} down`} disabled={last} onClick={() => onMove(index + 1)}>
            ↓
          </button>
          <button type="button" aria-label={`Remove ${full}`} onClick={onRemove}>
            ×
          </button>
        </span>
      </div>
      {action.kind === "script" ? <ScriptFields action={action} projectHooks={projectHooks} onChange={onChange} /> : null}
      {action.kind === "navigation" ? <NavigationFields action={action} viewPath={viewPath} listId={listId} onChange={onChange} /> : null}
      {action.kind === "popup" ? <PopupFields action={action} listId={listId} onChange={onChange} /> : null}
      {action.kind === "tag-write" ? <TagWriteFields action={action} onChange={onChange} /> : null}
      {action.kind === "message" ? <MessageFields action={action} onChange={onChange} /> : null}
      {action.kind === "call-method" ? <CallMethodFields action={action} onChange={onChange} /> : null}
      {action.kind === "logout" ? <p className="oip-binding-muted">Signs the user out; the session returns to sign-in.</p> : null}
      <div className="oip-action-flags">
        <label className="oip-schema-check">
          <input
            type="checkbox"
            checked={action.enabled !== false}
            onChange={(event) => onChange(flag({ ...action }, "enabled", event.target.checked ? undefined : false))}
          />
          Enabled
        </label>
        <label className="oip-schema-check">
          <input
            type="checkbox"
            checked={action.stopPropagation === true}
            onChange={(event) => onChange(flag({ ...action }, "stopPropagation", event.target.checked ? true : undefined))}
          />
          Stop propagation
        </label>
      </div>
      {problem ? (
        <p className="oip-binding-error" role="alert">
          {problem}
        </p>
      ) : null}
    </li>
  );
}

function flag<T extends Action>(action: T, key: "enabled" | "stopPropagation", value: boolean | undefined): T {
  if (value === undefined) delete action[key];
  else action[key] = value;
  return action;
}

function ScriptFields({ action, projectHooks, onChange }: { action: ScriptAction; projectHooks: ReadonlySet<string>; onChange: (action: ScriptAction) => void }) {
  const source = action.source ?? "";
  const usesProject = action.source === undefined;
  return (
    <div className="oip-binding-grid">
      <RequiredText
        label="Hook name"
        value={action.hook}
        wide
        problem={hookNameProblem}
        onCommit={(hook) => onChange({ ...action, hook })}
      />
      <div className="oip-binding-wide">
        <ScriptEditor
          label="Script source"
          value={source}
          rows={6}
          placeholder={"actions.writeTag('[default]area/flag', True)"}
          hint="Body of runAction(event, component); event.payload holds the DOM event (type, clientX, clientY, x, y)."
          onChange={(text) => {
            const next = { ...action };
            if (text) next.source = text;
            else delete next.source;
            onChange(next);
          }}
        />
      </div>
      <div className="oip-binding-wide">
        <ScriptBuilder
          source={source}
          onInsert={(text) => {
            const next = { ...action };
            if (text) next.source = text;
            else delete next.source;
            onChange(next);
          }}
        />
      </div>
      {usesProject ? (
        <p className="oip-binding-muted oip-binding-wide" data-testid="project-hook-note">
          {projectHooks.has(action.hook)
            ? `Runs the project script hook "${action.hook}".`
            : `Empty: runs the project script hook "${action.hook}", which does not exist yet (Scripts panel).`}
        </p>
      ) : null}
    </div>
  );
}

function NavigationFields({
  action,
  viewPath,
  listId,
  onChange,
}: {
  action: NavigationAction;
  viewPath: string;
  listId: string;
  onChange: (action: NavigationAction) => void;
}) {
  return (
    <div className="oip-binding-grid">
      <label>
        <span>Navigate to</span>
        <select aria-label="Navigate to" value={action.mode} onChange={(event) => onChange(withNavigationMode(action, event.target.value as NavigationAction["mode"], viewPath))}>
          <option value="page">Page URL</option>
          <option value="view">View</option>
          <option value="url">Web address</option>
          <option value="browser">Back or forward</option>
        </select>
      </label>
      {action.mode === "page" ? <RequiredText label="Page URL" value={action.page ?? ""} onCommit={(page) => onChange({ ...action, page })} /> : null}
      {action.mode === "view" ? <RequiredText label="View path" value={action.view ?? ""} list={listId} onCommit={(view) => onChange({ ...action, view })} /> : null}
      {action.mode === "url" ? <RequiredText label="Web address" value={action.url ?? ""} onCommit={(url) => onChange({ ...action, url })} /> : null}
      {action.mode === "browser" ? (
        <label>
          <span>Direction</span>
          <select aria-label="Direction" value={action.direction ?? "back"} onChange={(event) => onChange({ ...action, direction: event.target.value as "back" | "forward" })}>
            <option value="back">Back</option>
            <option value="forward">Forward</option>
          </select>
        </label>
      ) : null}
      {action.mode === "url" ? (
        <label className="oip-schema-check oip-binding-wide">
          <input type="checkbox" checked={action.newTab === true} onChange={(event) => onChange(optional({ ...action }, "newTab", event.target.checked || undefined))} />
          Open in a new tab
        </label>
      ) : null}
    </div>
  );
}

function PopupFields({ action, listId, onChange }: { action: PopupAction; listId: string; onChange: (action: PopupAction) => void }) {
  return (
    <div className="oip-binding-grid">
      <label>
        <span>Popup</span>
        <select
          aria-label="Popup mode"
          value={action.mode}
          onChange={(event) => {
            const mode = event.target.value as PopupAction["mode"];
            onChange({ ...action, mode, view: action.view || (mode === "close" ? undefined : action.id) });
          }}
        >
          <option value="open">Open</option>
          <option value="close">Close</option>
          <option value="toggle">Toggle</option>
        </select>
      </label>
      <RequiredText label="Popup id" value={action.id} onCommit={(id) => onChange({ ...action, id })} />
      {action.mode !== "close" ? (
        <>
          <RequiredText label="Popup view" value={action.view ?? ""} list={listId} onCommit={(view) => onChange({ ...action, view })} />
          <label>
            <span>Title</span>
            <input aria-label="Popup title" type="text" value={action.title ?? ""} onChange={(event) => onChange(optional({ ...action }, "title", event.target.value || undefined))} />
          </label>
          <label className="oip-schema-check oip-binding-wide">
            <input type="checkbox" checked={action.modal === true} onChange={(event) => onChange(optional({ ...action }, "modal", event.target.checked || undefined))} />
            Modal
          </label>
        </>
      ) : null}
    </div>
  );
}

function TagWriteFields({ action, onChange }: { action: TagWriteAction; onChange: (action: TagWriteAction) => void }) {
  const [text, setText] = useState(() => literalText(action.value));
  return (
    <div className="oip-binding-grid">
      <RequiredText label="Tag path" value={action.path} wide onCommit={(path) => onChange({ ...action, path })} />
      <label className="oip-binding-wide">
        <span>Value (true, 42, "text", null)</span>
        <input
          aria-label="Value to write"
          type="text"
          value={text}
          onChange={(event) => {
            setText(event.target.value);
            onChange({ ...action, value: tagValue(event.target.value) });
          }}
        />
      </label>
    </div>
  );
}

function CallMethodFields({ action, onChange }: { action: CallMethodAction; onChange: (action: CallMethodAction) => void }) {
  const [args, setArgs] = useState(() => (action.args ? JSON.stringify(action.args) : ""));
  const [problem, setProblem] = useState<string | null>(null);
  return (
    <div className="oip-binding-grid">
      <RequiredText label="Method name" value={action.method} problem={methodNameProblem} onCommit={(method) => onChange({ ...action, method })} />
      <label>
        <span>Target (a component id, view, or empty for this component)</span>
        <input
          aria-label="Method target"
          type="text"
          value={action.target ?? ""}
          onChange={(event) => onChange(optional({ ...action }, "target", event.target.value === "" ? undefined : event.target.value))}
        />
      </label>
      <label className="oip-binding-wide">
        <span>Arguments (a JSON object by parameter name)</span>
        <input
          aria-label="Method arguments"
          type="text"
          value={args}
          aria-invalid={problem ? true : undefined}
          onChange={(event) => {
            const text = event.target.value;
            setArgs(text);
            if (text.trim() === "") {
              setProblem(null);
              onChange(optional({ ...action }, "args", undefined));
              return;
            }
            try {
              const value: unknown = JSON.parse(text);
              if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Enter a JSON object.");
              setProblem(null);
              onChange({ ...action, args: value as Record<string, unknown> });
            } catch (error) {
              setProblem(error instanceof SyntaxError ? "Not valid JSON." : error instanceof Error ? error.message : String(error));
            }
          }}
        />
        {problem ? <small className="oip-binding-error">{problem}</small> : null}
      </label>
    </div>
  );
}

function MessageFields({ action, onChange }: { action: MessageAction; onChange: (action: MessageAction) => void }) {
  const [payload, setPayload] = useState(() => (action.payload ? JSON.stringify(action.payload) : ""));
  const [problem, setProblem] = useState<string | null>(null);
  return (
    <div className="oip-binding-grid">
      <RequiredText label="Message type" value={action.messageType} problem={messageTypeProblem} onCommit={(messageType) => onChange({ ...action, messageType })} />
      <label>
        <span>Scope</span>
        <select aria-label="Message scope" value={action.scope} onChange={(event) => onChange({ ...action, scope: event.target.value as MessageScope })}>
          <option value="view">View</option>
          <option value="page">Page</option>
          <option value="session">Session</option>
        </select>
      </label>
      <label className="oip-binding-wide">
        <span>Payload (a JSON object)</span>
        <input
          aria-label="Message payload"
          type="text"
          value={payload}
          aria-invalid={problem ? true : undefined}
          onChange={(event) => {
            const text = event.target.value;
            setPayload(text);
            if (text.trim() === "") {
              setProblem(null);
              onChange(optional({ ...action }, "payload", undefined));
              return;
            }
            try {
              const value: unknown = JSON.parse(text);
              if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Enter a JSON object.");
              setProblem(null);
              onChange({ ...action, payload: value as Record<string, unknown> });
            } catch (error) {
              setProblem(error instanceof SyntaxError ? "Not valid JSON." : error instanceof Error ? error.message : String(error));
            }
          }}
        />
        {problem ? <small className="oip-binding-error">{problem}</small> : null}
      </label>
    </div>
  );
}

/** A typed literal as a tag value: JSON scalars as such, anything else as the text typed. */
export function tagValue(text: string): TagWriteAction["value"] {
  const value = parseLiteral(text);
  if (value === null || typeof value === "string" || typeof value === "number" || typeof value === "boolean") return value;
  return text;
}

function optional<T extends object, K extends keyof T>(record: T, key: K, value: T[K] | undefined): T {
  if (value === undefined) delete record[key];
  else record[key] = value;
  return record;
}

/**
 * A text field the action cannot run without. What is typed is only stored once it is valid, so the view always
 * opens again; until then the field shows the problem and the stored value stays as it was.
 */
function RequiredText({
  label,
  value,
  list,
  wide,
  problem,
  onCommit,
}: {
  label: string;
  value: string;
  list?: string;
  wide?: boolean;
  problem?: (text: string) => string | null;
  onCommit: (value: string) => void;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const shown = draft ?? value;
  const check = (text: string) => (text.trim() === "" ? `Enter a ${label.toLowerCase()}.` : (problem?.(text) ?? null));
  const error = draft === null ? null : check(draft);
  return (
    <label className={wide ? "oip-binding-wide" : undefined}>
      <span>{label}</span>
      <input
        aria-label={label}
        aria-invalid={error ? true : undefined}
        type="text"
        list={list}
        value={shown}
        onChange={(event) => {
          const text = event.target.value;
          if (check(text)) {
            setDraft(text);
            return;
          }
          setDraft(null);
          onCommit(text);
        }}
        onBlur={() => setDraft(null)}
      />
      {error ? <small className="oip-binding-error">{error} Kept: {value}</small> : null}
    </label>
  );
}
