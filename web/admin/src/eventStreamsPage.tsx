import { useCallback, useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { ResourceDocument } from "./api";
import {
  draftFromSpec,
  emptyHandler,
  emptyKafkaConnection,
  emptyStream,
  kafkaProblem,
  kafkaSpec,
  specFromDraft,
  streamProblem,
  type EventStreamView,
  type HandlerDraft,
  type KafkaConnectionDraft,
  type SourceType,
  type StreamDraft,
} from "./eventStreamModel";
import type { AdminPageProps } from "./pages";

/**
 * Event streams: a source, optional filter and transform, a buffer, and handlers.
 * Kafka connections used by a Kafka source or handler are on the same screen.
 * Edit as JSON keeps retry, partition, and any other setting the form does not list.
 * Doc source: FEATURES MES-002, MES-003, MES-005, MES-006, MES-007, MES-008, MES-009, SCR-011.
 */

const SOURCES: { value: SourceType; label: string }[] = [
  { value: "listener", label: "Event listener" },
  { value: "tag", label: "Tag" },
  { value: "http", label: "HTTP" },
  { value: "kafka", label: "Kafka" },
];

const HANDLERS = [
  ["logger", "Logger"],
  ["tag", "Tag write"],
  ["database", "Named query"],
  ["http", "HTTP"],
  ["gateway", "Gateway event"],
  ["script", "Script"],
  ["kafka", "Kafka"],
] as const;

interface Editing {
  name: string;
  version: number | null;
  draft: StreamDraft;
  jsonText: string;
  mode: "form" | "json";
}

export function EventStreamsPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [views, setViews] = useState<EventStreamView[]>([]);
  const [resources, setResources] = useState<ResourceDocument[]>([]);
  const [connections, setConnections] = useState<ResourceDocument[]>([]);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [kafka, setKafka] = useState<{ name: string; version: number | null; draft: KafkaConnectionDraft } | null>(null);
  const [testBody, setTestBody] = useState('{\n  "n": 1\n}');
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    if (!client) return;
    const [status, stored, kafkas] = await Promise.all([
      client.eventStreamStatus(),
      client.listResources("events", "gateway", "EventStream"),
      client.listResources("kafka", "gateway", "KafkaConnection").catch(() => [] as ResourceDocument[]),
    ]);
    setViews(status);
    setResources(stored);
    setConnections(kafkas);
  }, [client]);

  useEffect(() => {
    if (!client) return;
    let gone = false;
    void reload().catch((err: unknown) => {
      if (!gone) setFailure(errorMessage(err, "Event streams could not be loaded."));
    });
    return () => {
      gone = true;
    };
  }, [client, reload]);

  async function run(work: () => Promise<void>) {
    setBusy(true);
    setFailure(null);
    setNotice(null);
    try {
      await work();
    } catch (err) {
      setFailure(errorMessage(err, err instanceof Error ? err.message : "The event stream could not be saved."));
    } finally {
      setBusy(false);
    }
  }

  function openNew() {
    const draft = emptyStream();
    setEditing({ name: "", version: null, draft, jsonText: pretty(specFromDraft(draft)), mode: "form" });
    setFailure(null);
    setNotice(null);
  }

  function openResource(resource: ResourceDocument) {
    const draft = draftFromSpec(resource.spec);
    setEditing({
      name: resource.metadata.name,
      version: resource.metadata.version,
      draft,
      jsonText: pretty(resource.spec),
      mode: "form",
    });
    setFailure(null);
    setNotice(null);
  }

  function updateDraft(draft: StreamDraft) {
    setEditing((current) => (current ? { ...current, draft } : current));
  }

  function setMode(mode: "form" | "json") {
    if (!editing || editing.mode === mode) return;
    if (mode === "json") {
      setEditing({ ...editing, mode, jsonText: pretty(specFromDraft(editing.draft)) });
      return;
    }
    try {
      const spec = parseSpec(editing.jsonText);
      setEditing({ ...editing, mode, draft: draftFromSpec(spec) });
      setFailure(null);
    } catch (err) {
      setFailure(err instanceof Error ? err.message : "The JSON could not be read.");
    }
  }

  function save(event: FormEvent) {
    event.preventDefault();
    if (!client || !editing) return;
    void run(async () => {
      const spec = editing.mode === "json" ? parseSpec(editing.jsonText) : specFromDraft(editing.draft);
      const draft = editing.mode === "json" ? draftFromSpec(spec) : editing.draft;
      const problem = streamProblem(editing.name.trim(), draft, editing.version == null);
      if (problem) throw new Error(problem);
      const saved = editing.version == null
        ? await client.createResource("events", "gateway", "EventStream", editing.name.trim(), spec)
        : await client.putResource("events", "gateway", "EventStream", editing.name, {
            apiVersion: "oip/v1",
            kind: "EventStream",
            metadata: { name: editing.name, module: "events", version: editing.version },
            spec,
          });
      openResource(saved);
      setNotice(`Saved ${saved.metadata.name}.`);
      await reload();
    });
  }

  function remove() {
    if (!client || !editing || editing.version == null) return;
    const { name, version } = editing;
    void run(async () => {
      await client.deleteResource("events", "gateway", "EventStream", name, version);
      setEditing(null);
      setNotice(`Deleted ${name}.`);
      await reload();
    });
  }

  function sendTest() {
    if (!client || !editing || editing.version == null) return;
    const name = editing.name;
    void run(async () => {
      let body: unknown = testBody;
      const trimmed = testBody.trim();
      if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        try {
          body = JSON.parse(trimmed) as unknown;
        } catch {
          throw new Error("The test event is not valid JSON.");
        }
      }
      await client.sendTestEvent(name, body);
      setNotice(`Sent a test event to ${name}.`);
      await reload();
    });
  }

  function saveKafka(event: FormEvent) {
    event.preventDefault();
    if (!client || !kafka) return;
    void run(async () => {
      const problem = kafkaProblem(kafka.name.trim(), kafka.draft, kafka.version == null);
      if (problem) throw new Error(problem);
      const spec = kafkaSpec(kafka.draft);
      if (kafka.version == null) {
        await client.createResource("kafka", "gateway", "KafkaConnection", kafka.name.trim(), spec);
      } else {
        await client.putResource("kafka", "gateway", "KafkaConnection", kafka.name, {
          apiVersion: "oip/v1",
          kind: "KafkaConnection",
          metadata: { name: kafka.name, module: "kafka", version: kafka.version },
          spec,
        });
      }
      setKafka(null);
      setNotice(`Saved Kafka connection ${kafka.name.trim()}.`);
      await reload();
    });
  }

  const view = editing ? views.find((item) => item.name === editing.name) : undefined;
  const names = new Set<string>([...resources.map((item) => item.metadata.name), ...views.map((item) => item.name)]);

  return (
    <article className="stack-page">
      <h1>{section.label}</h1>
      <p>
        An event stream takes events from a tag, an HTTP call, Kafka, or another part of the gateway, then runs them
        through a filter, a transform, and one or more handlers.
      </p>
      {failure ? <p role="alert">{failure}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {!client ? <p>Sign in to manage event streams.</p> : null}
      {names.size > 0 ? (
        <table className="data-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Source</th>
              <th>Enabled</th>
              <th>Valid</th>
              <th>Received</th>
              <th>Errors</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {[...names].sort().map((name) => {
              const status = views.find((item) => item.name === name);
              const resource = resources.find((item) => item.metadata.name === name);
              return (
                <tr key={name}>
                  <td>{name}</td>
                  <td>{status?.source ?? "—"}</td>
                  <td>{status ? (status.enabled ? "yes" : "no") : "—"}</td>
                  <td>{status ? (status.valid ? "yes" : "no") : "—"}</td>
                  <td>{status?.eventsReceived ?? "—"}</td>
                  <td>{status?.handlerErrors ?? "—"}</td>
                  <td>
                    {resource && canWrite ? (
                      <button type="button" className="quiet" onClick={() => openResource(resource)}>
                        Edit {name}
                      </button>
                    ) : null}
                    {status && !status.valid && status.error ? <span>{status.error}</span> : null}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : client && !editing ? <p>No event streams yet.</p> : null}
      {canWrite && !editing ? (
        <p>
          <button type="button" onClick={openNew}>New event stream</button>
        </p>
      ) : null}
      {editing ? (
        <form className="stack-form" onSubmit={save} aria-label={editing.version == null ? "New event stream" : `Event stream ${editing.name}`}>
          <h2>{editing.version == null ? "New event stream" : editing.name}</h2>
          <div className="row-actions">
            <button type="button" className="quiet" onClick={() => setMode(editing.mode === "json" ? "form" : "json")}>
              {editing.mode === "json" ? "Edit as form" : "Edit as JSON"}
            </button>
            <button type="button" className="quiet" onClick={() => setEditing(null)}>Close</button>
          </div>
          {editing.mode === "json" ? (
            <label>
              Stream JSON
              <textarea className="json-editor" aria-label="Stream JSON" value={editing.jsonText} onChange={(event) => setEditing({ ...editing, jsonText: event.target.value })} />
            </label>
          ) : (
            <StreamFields draft={editing.draft} name={editing.name} creating={editing.version == null} connections={connections} onName={(name) => setEditing({ ...editing, name })} onChange={updateDraft} />
          )}
          <p>Saving the form writes the fields on the form and keeps extra JSON settings such as retry and partition. Edit as JSON replaces the stored stream with the text above.</p>
          <div className="row-actions">
            <button type="submit" disabled={busy}>Save stream</button>
            {editing.version != null ? (
              <button type="button" className="quiet" disabled={busy} onClick={remove}>Delete {editing.name}</button>
            ) : null}
          </div>
          {view && editing.version != null ? (
            <section aria-label="Stream status">
              <h3>Status</h3>
              {view.error ? <p role="alert">{view.error}</p> : null}
              {view.sourceError ? <p role="alert">{view.sourceError}</p> : null}
              <dl className="meta">
                <div><dt>Valid</dt><dd>{view.valid ? "yes" : "no"}</dd></div>
                <div><dt>Received</dt><dd data-testid="events-received">{view.eventsReceived}</dd></div>
                <div><dt>Dropped</dt><dd>{view.eventsDropped}</dd></div>
                <div><dt>Filtered out</dt><dd>{view.eventsFiltered}</dd></div>
                <div><dt>Emitted</dt><dd>{view.eventsEmitted}</dd></div>
                <div><dt>Handler runs</dt><dd>{view.handlerExecutions}</dd></div>
                <div><dt>Handler errors</dt><dd data-testid="handler-errors">{view.handlerErrors}</dd></div>
                <div><dt>Queued</dt><dd>{view.queued}</dd></div>
                <div><dt>Source connected</dt><dd>{view.sourceConnected == null ? "—" : view.sourceConnected ? "yes" : "no"}</dd></div>
              </dl>
              {canWrite && view.enabled && view.valid ? (
                <div className="stack-form" role="group" aria-label="Send a test event">
                  <label>
                    Test event
                    <textarea aria-label="Test event" value={testBody} onChange={(event) => setTestBody(event.target.value)} />
                  </label>
                  <button type="button" disabled={busy} onClick={sendTest}>Send test event</button>
                </div>
              ) : null}
            </section>
          ) : null}
        </form>
      ) : null}
      <section aria-label="Kafka connections">
        <h2>Kafka connections</h2>
        <p>A Kafka source or handler names one of these connections. A password is a secret reference, never the password itself.</p>
        {connections.length > 0 ? (
          <table className="data-table">
            <thead>
              <tr><th>Name</th><th>Servers</th><th></th></tr>
            </thead>
            <tbody>
              {connections.map((item) => (
                <tr key={item.metadata.name}>
                  <td>{item.metadata.name}</td>
                  <td>{typeof item.spec.bootstrapServers === "string" ? item.spec.bootstrapServers : ""}</td>
                  <td>
                    {canWrite ? (
                      <button
                        type="button"
                        className="quiet"
                        onClick={() => setKafka({
                          name: item.metadata.name,
                          version: item.metadata.version,
                          draft: {
                            enabled: item.spec.enabled !== false,
                            bootstrapServers: typeof item.spec.bootstrapServers === "string" ? item.spec.bootstrapServers : "",
                            securityProtocol: typeof item.spec.securityProtocol === "string" ? item.spec.securityProtocol : "PLAINTEXT",
                            username: typeof item.spec.username === "string" ? item.spec.username : "",
                            password: "",
                          },
                        })}
                      >
                        Edit {item.metadata.name}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <p>No Kafka connections yet.</p>}
        {canWrite && !kafka ? (
          <p><button type="button" onClick={() => setKafka({ name: "", version: null, draft: emptyKafkaConnection() })}>New Kafka connection</button></p>
        ) : null}
        {kafka ? (
          <form className="stack-form" onSubmit={saveKafka} aria-label={kafka.version == null ? "New Kafka connection" : `Kafka connection ${kafka.name}`}>
            <h3>{kafka.version == null ? "New Kafka connection" : kafka.name}</h3>
            {kafka.version == null ? (
              <label>
                Connection name
                <input value={kafka.name} onChange={(event) => setKafka({ ...kafka, name: event.target.value })} autoComplete="off" />
              </label>
            ) : null}
            <label>
              Enabled
              <input type="checkbox" checked={kafka.draft.enabled} onChange={(event) => setKafka({ ...kafka, draft: { ...kafka.draft, enabled: event.target.checked } })} />
            </label>
            <label>
              Bootstrap servers
              <input value={kafka.draft.bootstrapServers} onChange={(event) => setKafka({ ...kafka, draft: { ...kafka.draft, bootstrapServers: event.target.value } })} placeholder="broker:9092" />
            </label>
            <label>
              Security protocol
              <select value={kafka.draft.securityProtocol} onChange={(event) => setKafka({ ...kafka, draft: { ...kafka.draft, securityProtocol: event.target.value } })}>
                {["PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"].map((item) => <option key={item} value={item}>{item}</option>)}
              </select>
            </label>
            <label>
              Username
              <input value={kafka.draft.username} onChange={(event) => setKafka({ ...kafka, draft: { ...kafka.draft, username: event.target.value } })} autoComplete="off" />
            </label>
            <label>
              Password secret
              <input value={kafka.draft.password} onChange={(event) => setKafka({ ...kafka, draft: { ...kafka.draft, password: event.target.value } })} placeholder="secret://kafka-password" autoComplete="off" />
            </label>
            <div className="row-actions">
              <button type="submit" disabled={busy}>Save connection</button>
              {kafka.version != null ? (
                <button
                  type="button"
                  className="quiet"
                  disabled={busy}
                  onClick={() => {
                    if (!client) return;
                    const current = kafka;
                    void run(async () => {
                      await client.deleteResource("kafka", "gateway", "KafkaConnection", current.name, current.version ?? 0);
                      setKafka(null);
                      setNotice(`Deleted Kafka connection ${current.name}.`);
                      await reload();
                    });
                  }}
                >
                  Delete {kafka.name}
                </button>
              ) : null}
              <button type="button" className="quiet" onClick={() => setKafka(null)}>Close</button>
            </div>
          </form>
        ) : null}
      </section>
    </article>
  );
}

function StreamFields({
  draft,
  name,
  creating,
  connections,
  onName,
  onChange,
}: {
  draft: StreamDraft;
  name: string;
  creating: boolean;
  connections: ResourceDocument[];
  onName: (name: string) => void;
  onChange: (draft: StreamDraft) => void;
}) {
  function set<K extends keyof StreamDraft>(key: K, value: StreamDraft[K]) {
    onChange({ ...draft, [key]: value });
  }
  return (
    <div className="stage-grid">
      {creating ? (
        <label>
          Stream name
          <input aria-label="Stream name" value={name} onChange={(event) => onName(event.target.value)} autoComplete="off" />
        </label>
      ) : null}
      <label>
        Enabled
        <input type="checkbox" checked={draft.enabled} onChange={(event) => set("enabled", event.target.checked)} />
      </label>
      <label>
        Encoder
        <select aria-label="Encoder" value={draft.encoder} onChange={(event) => set("encoder", event.target.value)}>
          <option value="json">JSON</option>
          <option value="string">Text</option>
          <option value="bytes">Bytes</option>
        </select>
      </label>
      <label>
        Source
        <select
          aria-label="Source"
          value={draft.sourceType}
          onChange={(event) => onChange({ ...draft, sourceType: event.target.value as SourceType, sourceExtra: {} })}
        >
          {SOURCES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
      </label>
      {draft.sourceType === "tag" ? (
        <>
          <label>
            Tag paths
            <textarea aria-label="Tag paths" value={draft.paths} onChange={(event) => set("paths", event.target.value)} placeholder={"[default]Area/Tank/Level"} />
          </label>
          <label>Value changes<input type="checkbox" checked={draft.onValue} onChange={(event) => set("onValue", event.target.checked)} /></label>
          <label>Quality changes<input type="checkbox" checked={draft.onQuality} onChange={(event) => set("onQuality", event.target.checked)} /></label>
          <label>Timestamp changes<input type="checkbox" checked={draft.onTimestamp} onChange={(event) => set("onTimestamp", event.target.checked)} /></label>
          <label>Include quality and timestamp<input type="checkbox" checked={draft.qualifiedValue} onChange={(event) => set("qualifiedValue", event.target.checked)} /></label>
          <label>Skip the first sample<input type="checkbox" checked={draft.skipInitial} onChange={(event) => set("skipInitial", event.target.checked)} /></label>
        </>
      ) : null}
      {draft.sourceType === "http" ? (
        <>
          <label>Require sign-in<input type="checkbox" checked={draft.requireAuthentication} onChange={(event) => set("requireAuthentication", event.target.checked)} /></label>
          <label>Require HTTPS<input type="checkbox" checked={draft.requireHttps} onChange={(event) => set("requireHttps", event.target.checked)} /></label>
          <label>
            Roles
            <input aria-label="Roles" value={draft.roles} onChange={(event) => set("roles", event.target.value)} placeholder="operator, supervisor" />
          </label>
        </>
      ) : null}
      {draft.sourceType === "kafka" ? (
        <>
          <ConnectionField label="Kafka connection" value={draft.connection} connections={connections} onChange={(connection) => set("connection", connection)} />
          <label>
            Topics
            <textarea aria-label="Topics" value={draft.topics} onChange={(event) => set("topics", event.target.value)} />
          </label>
          <label>
            Consumer group
            <input aria-label="Consumer group" value={draft.groupId} onChange={(event) => set("groupId", event.target.value)} placeholder="leave blank for the default" />
          </label>
          <label>
            Offset reset
            <select aria-label="Offset reset" value={draft.offsetReset} onChange={(event) => set("offsetReset", event.target.value)}>
              <option value="latest">Latest</option>
              <option value="earliest">Earliest</option>
              <option value="none">None</option>
            </select>
          </label>
        </>
      ) : null}
      <label>
        Filter expression
        <textarea aria-label="Filter expression" value={draft.filterExpression} onChange={(event) => set("filterExpression", event.target.value)} placeholder="{event.data.value} > 10" />
      </label>
      <label>
        Filter script
        <textarea aria-label="Filter script" value={draft.filterScript} onChange={(event) => set("filterScript", event.target.value)} placeholder={"def onEventReceived(event, state):\n    return True"} />
      </label>
      <label>
        Transform expression
        <textarea aria-label="Transform expression" value={draft.transformExpression} onChange={(event) => set("transformExpression", event.target.value)} placeholder="{event.data.value} * 2" />
      </label>
      <label>
        Transform script
        <textarea aria-label="Transform script" value={draft.transformScript} onChange={(event) => set("transformScript", event.target.value)} />
      </label>
      <label>
        Quiet period (ms)
        <input aria-label="Quiet period" inputMode="numeric" value={draft.debounceMillis} onChange={(event) => set("debounceMillis", event.target.value)} />
      </label>
      <label>
        Maximum wait (ms)
        <input aria-label="Maximum wait" inputMode="numeric" value={draft.maxWaitMillis} onChange={(event) => set("maxWaitMillis", event.target.value)} />
      </label>
      <label>
        Queue limit
        <input aria-label="Queue limit" inputMode="numeric" value={draft.maxQueueSize} onChange={(event) => set("maxQueueSize", event.target.value)} />
      </label>
      <label>
        When the queue is full
        <select aria-label="When the queue is full" value={draft.overflow} onChange={(event) => set("overflow", event.target.value)}>
          <option value="dropOldest">Drop the oldest event</option>
          <option value="dropNewest">Drop the newest event</option>
        </select>
      </label>
      {draft.handlers.map((handler, index) => (
        <HandlerFields
          key={index}
          index={index}
          handler={handler}
          connections={connections}
          onChange={(next) => {
            const handlers = draft.handlers.slice();
            handlers[index] = next;
            set("handlers", handlers);
          }}
          onRemove={() => set("handlers", draft.handlers.filter((_, item) => item !== index))}
        />
      ))}
      <p>
        <button type="button" className="quiet" onClick={() => set("handlers", [...draft.handlers, emptyHandler()])}>Add handler</button>
      </p>
      <label>
        Error script
        <textarea aria-label="Error script" value={draft.errorScript} onChange={(event) => set("errorScript", event.target.value)} placeholder={"def onError(event, error, state):\n    pass"} />
      </label>
    </div>
  );
}

function HandlerFields({
  handler,
  index,
  connections,
  onChange,
  onRemove,
}: {
  handler: HandlerDraft;
  index: number;
  connections: ResourceDocument[];
  onChange: (handler: HandlerDraft) => void;
  onRemove: () => void;
}) {
  function set<K extends keyof HandlerDraft>(key: K, value: HandlerDraft[K]) {
    onChange({ ...handler, [key]: value });
  }
  return (
    <fieldset aria-label={`Handler ${index + 1}`}>
      <legend>Handler {index + 1}</legend>
      <label>
        Handler type
        <select aria-label="Handler type" value={handler.type} onChange={(event) => onChange({ ...handler, type: event.target.value as HandlerDraft["type"], extra: {} })}>
          {HANDLERS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>
        Handler name
        <input aria-label="Handler name" value={handler.name} onChange={(event) => set("name", event.target.value)} />
      </label>
      <label>
        Handler enabled
        <input type="checkbox" checked={handler.enabled} onChange={(event) => set("enabled", event.target.checked)} />
      </label>
      {handler.type === "logger" ? (
        <>
          <label>
            Level
            <select aria-label="Level" value={handler.level} onChange={(event) => set("level", event.target.value)}>
              {["trace", "debug", "info", "warn", "error"].map((item) => <option key={item} value={item}>{item}</option>)}
            </select>
          </label>
          <label>
            Handler message
            <textarea aria-label="Handler message" value={handler.message} onChange={(event) => set("message", event.target.value)} />
          </label>
        </>
      ) : null}
      {handler.type === "tag" ? (
        <>
          <label>Tag path<input aria-label="Tag path" value={handler.path} onChange={(event) => set("path", event.target.value)} /></label>
          <label>Value expression<input aria-label="Value expression" value={handler.value} onChange={(event) => set("value", event.target.value)} /></label>
        </>
      ) : null}
      {handler.type === "database" ? (
        <>
          <label>Named query<input aria-label="Named query" value={handler.query} onChange={(event) => set("query", event.target.value)} /></label>
          <label>Parameters<textarea aria-label="Parameters" value={handler.parameters} onChange={(event) => set("parameters", event.target.value)} placeholder={"n={event.data.n}"} /></label>
        </>
      ) : null}
      {handler.type === "http" ? (
        <>
          <label>URL<input aria-label="URL" value={handler.url} onChange={(event) => set("url", event.target.value)} /></label>
          <label>
            Method
            <select aria-label="Method" value={handler.method} onChange={(event) => set("method", event.target.value)}>
              {["POST", "PUT", "PATCH"].map((item) => <option key={item} value={item}>{item}</option>)}
            </select>
          </label>
          <label>Body<textarea aria-label="Body" value={handler.body} onChange={(event) => set("body", event.target.value)} /></label>
          <label>Headers<textarea aria-label="Headers" value={handler.headers} onChange={(event) => set("headers", event.target.value)} placeholder={"Content-Type=application/json"} /></label>
        </>
      ) : null}
      {handler.type === "gateway" ? (
        <>
          <label>Gateway event<input aria-label="Gateway event" value={handler.path} onChange={(event) => set("path", event.target.value)} /></label>
          <label>Body<textarea aria-label="Body" value={handler.body} onChange={(event) => set("body", event.target.value)} /></label>
        </>
      ) : null}
      {handler.type === "script" ? (
        <label>
          Handler script
          <textarea aria-label="Handler script" value={handler.source} onChange={(event) => set("source", event.target.value)} placeholder={"def onEventsReceived(events, state):\n    pass"} />
        </label>
      ) : null}
      {handler.type === "kafka" ? (
        <>
          <ConnectionField label="Handler connection" value={handler.connection} connections={connections} onChange={(connection) => set("connection", connection)} />
          <label>Topic<input aria-label="Topic" value={handler.topic} onChange={(event) => set("topic", event.target.value)} /></label>
          <label>Value expression<input aria-label="Value expression" value={handler.value} onChange={(event) => set("value", event.target.value)} /></label>
          <label>Key expression<input aria-label="Key expression" value={handler.key} onChange={(event) => set("key", event.target.value)} /></label>
        </>
      ) : null}
      <p><button type="button" className="quiet" onClick={onRemove}>Remove handler {index + 1}</button></p>
    </fieldset>
  );
}

function ConnectionField({ label, value, connections, onChange }: { label: string; value: string; connections: ResourceDocument[]; onChange: (value: string) => void }) {
  const listId = label.replace(/\s+/g, "-").toLowerCase();
  return (
    <label>
      {label}
      <input aria-label={label} list={listId} value={value} onChange={(event) => onChange(event.target.value)} />
      <datalist id={listId}>
        {connections.map((item) => <option key={item.metadata.name} value={item.metadata.name} />)}
      </datalist>
    </label>
  );
}

function parseSpec(text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    throw new Error("The JSON could not be read.");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("The stream JSON must be an object.");
  return value as Record<string, unknown>;
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2);
}
