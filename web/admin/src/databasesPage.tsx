import { useEffect, useState, type FormEvent } from "react";
import { allows, errorMessage } from "./access";
import type { ApiClient, DbConnection, DbRunResult, JdbcDriverInfo, NamedQueryInfo } from "./api";
import { describeJournalStorage, describeUse, loadJournalStorage, saveJournalConnection, type JournalStorage } from "./journalStorage";
import { NamedQueryEditor } from "./namedQueryEditor";
import { TransactionGroups } from "./transactionGroups";
import type { AdminPageProps } from "./pages";

/**
 * Databases: connection health with a test action, named queries with a test run, and an ad hoc query
 * browser (C-17, DES-001 admin variant).
 *
 * Reading needs gateway.read. Testing a connection and the query browser need gateway.write. A test run of
 * a named query goes through the normal execute route, so the query's own role list still applies.
 * The connection password is never requested or shown; the gateway only says whether one is stored.
 *
 * DAT-016: the JDBC drivers panel lists uploaded driver JARs for anyone who can read. Uploading or removing one runs
 * foreign code inside the gateway, so those controls need modules.admin, as installing a module does.
 *
 * DAT-015: each connection lists what uses it (alarm journal, history providers, reports, named queries), and the
 * alarm journal storage panel shows which connection holds the journal rows and whether writing there works now.
 */
export function DatabasesPage({ section, client, session }: AdminPageProps) {
  const canWrite = allows(session, "gateway.write");
  const [connections, setConnections] = useState<DbConnection[] | null>(null);
  const [queries, setQueries] = useState<NamedQueryInfo[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [testing, setTesting] = useState<NamedQueryInfo | null>(null);
  /** The named query in the editor: its name, "" for a new one, or null when closed. */
  const [editing, setEditing] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    Promise.all([client.dbConnections(), client.namedQueries()])
      .then(([nextConnections, nextQueries]) => {
        if (cancelled) return;
        setConnections(nextConnections);
        setQueries(nextQueries);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setConnections(null);
        setError(errorMessage(err, "Could not load the database connections"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, reloadToken]);

  async function testConnection(name: string) {
    if (!client || pending) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      const result = await client.testDbConnection(name);
      setConnections((current) => (current ?? []).map((item) => (item.name === name ? result : item)));
      setNotice(result.status === "Valid" ? `Connection ${name} is valid.` : `Connection ${name} is ${result.status.toLowerCase()}.`);
    } catch (err) {
      setError(errorMessage(err, "Could not test the connection"));
    } finally {
      setPending(false);
    }
  }

  return (
    <article data-section={section.id}>
      <h1>{section.label}</h1>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}

      <h2>Connections</h2>
      {connections ? (
        <ConnectionTable items={connections} canWrite={canWrite} pending={pending} onTest={(name) => void testConnection(name)} />
      ) : error ? null : (
        <p>Loading the connections.</p>
      )}
      {connections ? (
        <p>
          <button type="button" disabled={pending} onClick={() => setReloadToken((token) => token + 1)}>
            Reload
          </button>
        </p>
      ) : null}

      {client ? <TransactionGroups client={client} canWrite={canWrite} /> : null}

      <h2>Named queries</h2>
      {connections ? <QueryTable items={queries} canWrite={canWrite} onTest={(query) => setTesting(query)} onEdit={(query) => setEditing(query)} /> : null}
      {canWrite && connections ? (
        <p>
          <button type="button" onClick={() => setEditing("")}>
            New named query
          </button>
        </p>
      ) : null}
      {editing !== null && client && connections ? (
        <NamedQueryEditor
          key={editing}
          client={client}
          name={editing === "" ? null : editing}
          connections={connections}
          onSaved={() => setReloadToken((token) => token + 1)}
          onClose={() => setEditing(null)}
        />
      ) : null}
      {testing && client ? <QueryTest key={testing.name} client={client} query={testing} onClose={() => setTesting(null)} /> : null}

      {client && connections ? <DriversPanel client={client} session={session} onChanged={() => setReloadToken((token) => token + 1)} /> : null}

      {client && connections ? (
        <JournalStoragePanel
          client={client}
          connections={connections}
          canWrite={canWrite}
          onSaved={() => setReloadToken((token) => token + 1)}
        />
      ) : null}

      {canWrite && client && connections ? <QueryBrowser client={client} connections={connections} /> : null}
    </article>
  );
}

function formatBytes(size: number): string {
  return size >= 1024 * 1024 ? `${(size / (1024 * 1024)).toFixed(1)} MiB` : `${Math.max(1, Math.round(size / 1024))} KiB`;
}

function DriversPanel({ client, session, onChanged }: { client: ApiClient; session: AdminPageProps["session"]; onChanged: () => void }) {
  const canUpload = allows(session, "modules.admin");
  const [drivers, setDrivers] = useState<JdbcDriverInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [id, setId] = useState("");
  const [driverClass, setDriverClass] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [token, setToken] = useState(0);

  useEffect(() => {
    let cancelled = false;
    client
      .jdbcDrivers()
      .then((items) => {
        if (cancelled) return;
        setDrivers(items);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, "Could not load the JDBC drivers"));
      });
    return () => {
      cancelled = true;
    };
  }, [client, token]);

  async function upload(event: FormEvent) {
    event.preventDefault();
    if (!file || !id.trim() || pending) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      const stored = await client.uploadJdbcDriver(id.trim(), await file.arrayBuffer(), driverClass.trim() || undefined);
      setNotice(`Driver ${stored.id} stored (${stored.driverClass}).`);
      setId("");
      setDriverClass("");
      setFile(null);
      setToken((n) => n + 1);
      onChanged();
    } catch (err) {
      setError(errorMessage(err, "Could not upload the driver"));
    } finally {
      setPending(false);
    }
  }

  async function remove(driver: string) {
    if (pending) return;
    setPending(true);
    setError(null);
    setNotice(null);
    try {
      await client.removeJdbcDriver(driver);
      setNotice(`Driver ${driver} removed.`);
      setToken((n) => n + 1);
      onChanged();
    } catch (err) {
      setError(errorMessage(err, "Could not remove the driver"));
    } finally {
      setPending(false);
    }
  }

  return (
    <section aria-label="JDBC drivers">
      <h2>JDBC drivers</h2>
      <p className="muted">Oracle, IBM DB2 and Snowflake connections use a driver JAR you upload here; their licences do not allow shipping them.</p>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      {drivers ? (
        <table className="data-table" aria-label="Uploaded JDBC drivers">
          <thead>
            <tr>
              <th>Id</th>
              <th>Driver class</th>
              <th>Size</th>
              <th>Uploaded by</th>
              <th>Used by</th>
              {canUpload ? <th>Actions</th> : null}
            </tr>
          </thead>
          <tbody>
            {drivers.length === 0 ? (
              <tr>
                <td colSpan={canUpload ? 6 : 5}>No drivers uploaded.</td>
              </tr>
            ) : null}
            {drivers.map((driver) => (
              <tr key={driver.id}>
                <td>{driver.id}</td>
                <td>{driver.driverClass}</td>
                <td>{formatBytes(driver.size)}</td>
                <td>{driver.uploadedBy}</td>
                <td data-testid={`driver-used-by-${driver.id}`}>{driver.usedBy.join(", ") || "Nothing"}</td>
                {canUpload ? (
                  <td>
                    <button type="button" disabled={pending || driver.usedBy.length > 0} aria-label={`Remove ${driver.id}`} onClick={() => void remove(driver.id)}>
                      Remove
                    </button>
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      ) : error ? null : (
        <p>Loading the drivers.</p>
      )}
      {canUpload ? (
        <form aria-label="Upload a JDBC driver" onSubmit={(event) => void upload(event)}>
          <label>
            Driver id
            <input value={id} onChange={(event) => setId(event.target.value)} placeholder="oracle-23" />
          </label>
          <label>
            Driver class (only when the JAR names none)
            <input value={driverClass} onChange={(event) => setDriverClass(event.target.value)} />
          </label>
          <label>
            Driver JAR
            <input type="file" accept=".jar,application/java-archive" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
          </label>
          <button type="submit" disabled={pending || !file || !id.trim()}>
            Upload driver
          </button>
        </form>
      ) : null}
    </section>
  );
}

function ConnectionTable({
  items,
  canWrite,
  pending,
  onTest,
}: {
  items: DbConnection[];
  canWrite: boolean;
  pending: boolean;
  onTest: (name: string) => void;
}) {
  return (
    <table className="data-table" aria-label="Database connections">
      <thead>
        <tr>
          <th>Name</th>
          <th>Driver</th>
          <th>URL</th>
          <th>Status</th>
          <th>Pool</th>
          <th>Password</th>
          <th>Query browser</th>
          <th>Used by</th>
          {canWrite ? <th>Actions</th> : null}
        </tr>
      </thead>
      <tbody>
        {items.length === 0 ? (
          <tr>
            <td colSpan={canWrite ? 9 : 8}>No database connections.</td>
          </tr>
        ) : null}
        {items.map((item) => (
          <tr key={item.name}>
            <td>
              {item.name}
              {item.defaultConnection ? " (default)" : ""}
            </td>
            <td>{item.driver}</td>
            <td>
              <code>{item.url}</code>
            </td>
            <td data-testid={`db-status-${item.name}`}>
              {item.status}
              {item.error ? <span className="muted"> ({item.error})</span> : null}
            </td>
            <td>
              {item.activeConnections}/{item.maxConnections} active, {item.queriesPerSecond}/s
            </td>
            <td>{item.passwordSet ? "Stored" : "None"}</td>
            <td>{item.allowBrowserWrites ? "Reads and writes" : "Read only"}</td>
            <td data-testid={`db-used-by-${item.name}`}>{item.usedBy.length === 0 ? "Nothing" : item.usedBy.map(describeUse).join(", ")}</td>
            {canWrite ? (
              <td className="row-actions">
                <button type="button" disabled={pending} onClick={() => onTest(item.name)}>
                  Test {item.name}
                </button>
              </td>
            ) : null}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function QueryTable({ items, canWrite, onTest, onEdit }: { items: NamedQueryInfo[]; canWrite: boolean; onTest: (query: NamedQueryInfo) => void; onEdit: (name: string) => void }) {
  return (
    <table className="data-table" aria-label="Named queries">
      <thead>
        <tr>
          <th>Name</th>
          <th>Type</th>
          <th>Connection</th>
          <th>Statement</th>
          <th>Parameters</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        {items.length === 0 ? (
          <tr>
            <td colSpan={6}>No named queries.</td>
          </tr>
        ) : null}
        {items.map((query) => (
          <tr key={query.name}>
            <td>
              {query.name}
              {query.enabled ? "" : " (disabled)"}
            </td>
            <td>{query.queryType}</td>
            <td>{query.connection}</td>
            <td>
              <code>{query.procedure ? `call ${query.procedure}` : query.sql}</code>
            </td>
            <td>{query.parameters.map((p) => `${p.name}: ${p.dataType}${p.mode === "in" ? "" : ` (${p.mode})`}`).join(", ")}</td>
            <td className="row-actions">
              <button type="button" disabled={!query.enabled} onClick={() => onTest(query)}>
                Test {query.name}
              </button>
              {canWrite ? (
                <button type="button" onClick={() => onEdit(query.name)}>
                  Edit {query.name}
                </button>
              ) : null}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Parameter values are sent as typed text; the gateway coerces them to the declared types. */
export function QueryTest({ client, query, onClose }: { client: ApiClient; query: NamedQueryInfo; onClose: () => void }) {
  const inputs = query.parameters.filter((p) => p.mode !== "out");
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(inputs.map((p) => [p.name, ""])));
  const [result, setResult] = useState<DbRunResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    setResult(null);
    try {
      const parameters: Record<string, unknown> = {};
      for (const param of inputs) parameters[param.name] = parameterValue(values[param.name] ?? "");
      setResult(await client.runNamedQuery(query.name, parameters));
    } catch (err) {
      setError(errorMessage(err, "The query failed"));
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="stack-form" aria-label={`Test ${query.name}`} onSubmit={(event) => void submit(event)}>
      <h2>Test {query.name}</h2>
      {inputs.length === 0 ? <p>This query takes no parameters.</p> : null}
      {inputs.map((param) => (
        <label key={param.name}>
          {param.name} ({param.dataType})
          <input
            name={param.name}
            value={values[param.name] ?? ""}
            onChange={(event) => setValues((current) => ({ ...current, [param.name]: event.target.value }))}
          />
        </label>
      ))}
      {error ? <p role="alert">{error}</p> : null}
      <div className="inline-form">
        <button type="submit" disabled={pending}>
          Run {query.name}
        </button>
        <button type="button" onClick={onClose}>
          Close
        </button>
      </div>
      {result ? <ResultView label="Named query result" result={result} /> : null}
    </form>
  );
}

/** How often the journal storage status is read again while the page is open. */
const JOURNAL_POLL_MS = 2_000;

function JournalStoragePanel({
  client,
  connections,
  canWrite,
  onSaved,
}: {
  client: ApiClient;
  connections: DbConnection[];
  canWrite: boolean;
  onSaved: () => void;
}) {
  const [storage, setStorage] = useState<JournalStorage | null>(null);
  const [choice, setChoice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const read = () =>
      loadJournalStorage(client)
        .then((next) => {
          if (!cancelled) setStorage(next);
        })
        .catch((err: unknown) => {
          if (!cancelled) setError(errorMessage(err, "Could not read the alarm journal status"));
        });
    void read();
    const timer = window.setInterval(() => void read(), JOURNAL_POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [client]);

  const selected = choice ?? storage?.connection ?? "";

  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await saveJournalConnection(client, selected === "" ? null : selected);
      setStorage(await loadJournalStorage(client));
      setChoice(null);
      onSaved();
    } catch (err) {
      setError(errorMessage(err, "Could not change the alarm journal storage"));
    } finally {
      setPending(false);
    }
  }

  return (
    <section aria-label="Alarm journal storage">
      <h2>Alarm journal storage</h2>
      <p data-testid="journal-storage-status">{storage ? describeJournalStorage(storage) : "Reading the alarm journal status."}</p>
      {storage && storage.lostRows > 0 ? <p role="alert">{`${storage.lostRows} journal row(s) could not be stored.`}</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {canWrite ? (
        <form className="inline-form" aria-label="Change journal storage" onSubmit={(event) => void submit(event)}>
          <label>
            Journal connection
            <select value={selected} onChange={(event) => setChoice(event.target.value)}>
              <option value="">Built-in journal file</option>
              {storage?.connection && !connections.some((c) => c.name === storage.connection) ? (
                <option value={storage.connection}>{`${storage.connection} (missing)`}</option>
              ) : null}
              {connections.map((c) => (
                <option key={c.name} value={c.name}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" disabled={pending || storage === null}>
            Save journal storage
          </button>
        </form>
      ) : null}
    </section>
  );
}

function QueryBrowser({ client, connections }: { client: ApiClient; connections: DbConnection[] }) {
  const [connection, setConnection] = useState(() => connections.find((c) => c.defaultConnection)?.name ?? connections[0]?.name ?? "");
  const [sql, setSql] = useState("");
  const [maxRows, setMaxRows] = useState("500");
  const [result, setResult] = useState<DbRunResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const selected = connections.find((c) => c.name === connection);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!connection || !sql.trim()) return;
    setPending(true);
    setError(null);
    setResult(null);
    try {
      const limit = Number.parseInt(maxRows, 10);
      setResult(await client.browseDatabase(connection, sql, Number.isFinite(limit) && limit > 0 ? limit : 500));
    } catch (err) {
      setError(errorMessage(err, "The statement failed"));
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="stack-form" aria-label="Query browser" onSubmit={(event) => void submit(event)}>
      <h2>Query browser</h2>
      <p>
        Runs one statement. Reads run in a read-only transaction, which keeps table data unchanged; a
        browse-only connection should still use a database login that can only read.{" "}
        {selected?.allowBrowserWrites
          ? "This connection also accepts statements that change data."
          : "Statements that change data are refused on this connection."}
      </p>
      <label>
        Connection
        <select value={connection} onChange={(event) => setConnection(event.target.value)}>
          {connections.map((c) => (
            <option key={c.name} value={c.name}>
              {c.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        SQL statement
        <textarea rows={5} spellCheck={false} value={sql} onChange={(event) => setSql(event.target.value)} />
      </label>
      <label>
        Row limit
        <input type="number" min={1} max={10000} value={maxRows} onChange={(event) => setMaxRows(event.target.value)} />
      </label>
      {error ? <p role="alert">{error}</p> : null}
      <button type="submit" disabled={pending || !connection || !sql.trim()}>
        Run statement
      </button>
      {result ? <ResultView label="Query browser result" result={result} /> : null}
    </form>
  );
}

export function ResultView({ label, result }: { label: string; result: DbRunResult }) {
  if (result.type === "update") return <p role="status">{`${result.rowsAffected} row(s) affected.`}</p>;
  if (result.type === "scalar") {
    return (
      <p role="status">
        Value: <code>{formatCell(result.value)}</code>
      </p>
    );
  }
  if (result.type === "procedure" && result.columns.length === 0) {
    const entries = Object.entries(result.outputs);
    const rows: [string, unknown][] = result.returnValue !== null || entries.length === 0 ? [["return value", result.returnValue], ...entries] : entries;
    return <Table label={label} columns={["Output", "Value"]} rows={rows.map(([name, value]) => [name, value])} truncated={false} />;
  }
  return <Table label={label} columns={result.columns} rows={result.rows} truncated={result.truncated} />;
}

function Table({ label, columns, rows, truncated }: { label: string; columns: string[]; rows: unknown[][]; truncated: boolean }) {
  return (
    <>
      <table className="data-table" aria-label={label}>
        <thead>
          <tr>
            {columns.map((column, index) => (
              <th key={`${index}-${column}`}>{column}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={Math.max(columns.length, 1)}>No rows.</td>
            </tr>
          ) : null}
          {rows.map((row, r) => (
            <tr key={r}>
              {columns.map((_, c) => (
                <td key={c}>{formatCell(row[c])}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">
        {rows.length} row(s){truncated ? "; more rows were not returned (row limit reached)" : ""}.
      </p>
    </>
  );
}

export function formatCell(value: unknown): string {
  if (value === null || value === undefined) return "NULL";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

/** Empty text is SQL NULL. Anything else is sent as typed; the gateway coerces it to the declared type or refuses it. */
export function parameterValue(raw: string): string | null {
  return raw === "" ? null : raw;
}
