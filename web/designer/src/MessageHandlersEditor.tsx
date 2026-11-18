import { findNode, type MessageHandler, type MessageScope, type ViewDocument } from "@oip/hmi-runtime";
import { hookNameProblem, messageTypeProblem } from "./eventModel";

/**
 * A component's message handlers (HMP-117, P4-11): each runs a gateway event script when a message of its type is sent
 * at one of the scopes it listens to. The script gets `event.name` (the message type) and `event.payload`.
 *
 * Doc source: FEATURES HMP-117.
 */
const SCOPES: readonly { scope: MessageScope; label: string }[] = [
  { scope: "view", label: "View" },
  { scope: "page", label: "Page" },
  { scope: "session", label: "Session" },
];

export function MessageHandlersEditor({
  handlers,
  suggestHook,
  onChange,
}: {
  handlers: readonly MessageHandler[] | undefined;
  suggestHook: (messageType: string) => string;
  onChange: (handlers: MessageHandler[]) => void;
}) {
  const list = handlers ?? [];
  const set = (index: number, next: MessageHandler) => onChange(list.map((entry, at) => (at === index ? next : entry)));
  return (
    <section className="oip-events oip-message-handlers" aria-label="Message handlers">
      <h3>Message handlers</h3>
      {list.length === 0 ? <p className="oip-binding-muted">No message handlers.</p> : null}
      <ol className="oip-transforms">
        {list.map((handler, index) => {
          const label = `Message handler ${index + 1}`;
          const typeProblem = messageTypeProblem(handler.messageType);
          const hookProblem = hookNameProblem(handler.script.hook);
          return (
            <li key={index} className="oip-transform" aria-label={label} data-message-type={handler.messageType}>
              <div className="oip-binding-grid">
                <label>
                  <span>Message type</span>
                  <input
                    aria-label="Handler message type"
                    aria-invalid={typeProblem ? true : undefined}
                    type="text"
                    value={handler.messageType}
                    onChange={(event) => set(index, { ...handler, messageType: event.target.value })}
                  />
                </label>
                <fieldset className="oip-message-scopes">
                  <legend>Listens at</legend>
                  {SCOPES.map(({ scope, label: scopeLabel }) => (
                    <label key={scope} className="oip-schema-check">
                      <input
                        type="checkbox"
                        checked={handler.scopes.includes(scope)}
                        onChange={(event) => {
                          const scopes = event.target.checked ? [...handler.scopes, scope] : handler.scopes.filter((entry) => entry !== scope);
                          // A handler always listens somewhere.
                          if (scopes.length > 0) set(index, { ...handler, scopes: SCOPES.map((entry) => entry.scope).filter((entry) => scopes.includes(entry)) });
                        }}
                      />
                      {scopeLabel}
                    </label>
                  ))}
                </fieldset>
                <label>
                  <span>Hook name</span>
                  <input
                    aria-label="Handler hook name"
                    aria-invalid={hookProblem ? true : undefined}
                    type="text"
                    value={handler.script.hook}
                    onChange={(event) => set(index, { ...handler, script: { ...handler.script, hook: event.target.value } })}
                  />
                </label>
                <label className="oip-binding-wide">
                  <span>Handler script (event.name, event.payload)</span>
                  <textarea
                    aria-label="Handler script"
                    rows={4}
                    spellCheck={false}
                    value={handler.script.source ?? ""}
                    onChange={(event) => set(index, { ...handler, script: { ...handler.script, source: event.target.value } })}
                  />
                </label>
              </div>
              {typeProblem || hookProblem ? (
                <p className="oip-binding-error" role="alert">
                  {typeProblem ?? hookProblem}
                </p>
              ) : null}
              <button type="button" aria-label={`Remove ${label}`} onClick={() => onChange(list.filter((_, at) => at !== index))}>
                Remove handler
              </button>
            </li>
          );
        })}
      </ol>
      <button
        type="button"
        onClick={() => onChange([...list, { messageType: "refresh", scopes: ["page"], script: { kind: "script", hook: suggestHook("refresh"), source: "" } }])}
      >
        + Add message handler
      </button>
    </section>
  );
}

/** The view with component [id]'s message handlers replaced (removed when empty). */
export function setNodeMessageHandlers(view: ViewDocument, id: string, handlers: MessageHandler[]): ViewDocument {
  const next = structuredClone(view);
  const node = findNode(next.spec.root, id);
  if (!node || node.kind !== "component") throw new Error(`component ${id} not found`);
  if (handlers.length > 0) node.messageHandlers = handlers;
  else delete node.messageHandlers;
  return next;
}
