import { useEffect, useState, type FormEvent } from "react";
import { errorMessage } from "./access";
import { ApiError, type ApiClient } from "./api";

const SETTINGS_KEYS = [
  "mode",
  "standbyActivityLevel",
  "startupAllowanceMillis",
  "syncTimeoutSeconds",
  "recoveryMode",
  "masterHost",
  "masterPort",
  "pingRateMillis",
  "pingTimeoutMillis",
  "pingMaxMissed",
  "historyMode",
  "useActiveUptime",
  "connectionName",
] as const;

function text(value: unknown, fallback: string): string {
  return typeof value === "string" || typeof value === "number" ? String(value) : fallback;
}

function settingsFrom(status: Record<string, unknown>): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  for (const key of SETTINGS_KEYS) {
    if (status[key] !== undefined) body[key] = status[key];
  }
  return body;
}

/** RED-002 and RED-003: pair mode, master address, and heartbeat, saved through PUT /api/v1/redundancy. */
export function RedundancyPanel({ client, canWrite }: { client: ApiClient | undefined; canWrite: boolean }) {
  const [mode, setMode] = useState("independent");
  const [host, setHost] = useState("127.0.0.1");
  const [port, setPort] = useState("8060");
  const [ping, setPing] = useState("1000");
  const [missed, setMissed] = useState("30");
  const [saved, setSaved] = useState<Record<string, unknown>>({});
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("{}");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!client) return;
    let cancelled = false;
    client
      .redundancy()
      .then((status) => {
        if (cancelled || typeof status.mode !== "string") return;
        setMode(text(status.mode, "independent"));
        setHost(text(status.masterHost, "127.0.0.1"));
        setPort(text(status.masterPort, "8060"));
        setPing(text(status.pingRateMillis, "1000"));
        setMissed(text(status.pingMaxMissed, "30"));
        setSaved(settingsFrom(status));
      })
      .catch(() => {
        if (!cancelled) setSaved({});
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  function bodyFromForm(): Record<string, unknown> {
    return {
      ...saved,
      mode,
      masterHost: host.trim(),
      masterPort: Number(port),
      pingRateMillis: Number(ping),
      pingMaxMissed: Number(missed),
    };
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!client) return;
    setError(null);
    setNotice(null);
    try {
      const body = jsonMode ? (JSON.parse(jsonText) as Record<string, unknown>) : bodyFromForm();
      const next = await client.saveRedundancy(body);
      setSaved(settingsFrom(next));
      if (typeof next.mode === "string") setMode(next.mode);
      setNotice("Redundancy pair saved. The master address is in use now.");
    } catch (err) {
      setError(err instanceof ApiError ? errorMessage(err, "Could not save the pair") : err instanceof Error ? err.message : "Could not save the pair");
    }
  }

  async function act(action: "failover" | "assume" | "resync") {
    if (!client) return;
    setError(null);
    try {
      await client.redundancyAction(action);
      setNotice(action === "failover" ? "Failover requested." : action === "assume" ? "This node took control." : "A full sync was requested.");
    } catch (err) {
      setError(errorMessage(err, "The redundancy action was refused"));
    }
  }

  return (
    <section aria-label="Redundancy pair">
      <h2>Redundancy pair</h2>
      <form aria-label="Redundancy settings" onSubmit={(event) => void save(event)}>
        <label>
          Role
          <select aria-label="Role" value={mode} disabled={!canWrite || jsonMode} onChange={(event) => setMode(event.target.value)}>
            <option value="independent">Independent</option>
            <option value="master">Master</option>
            <option value="backup">Backup</option>
          </select>
        </label>
        <label>
          Master host
          <input aria-label="Master host" value={host} disabled={!canWrite || jsonMode} onChange={(event) => setHost(event.target.value)} />
        </label>
        <label>
          Master port
          <input aria-label="Master port" inputMode="numeric" value={port} disabled={!canWrite || jsonMode} onChange={(event) => setPort(event.target.value)} />
        </label>
        <label>
          Ping rate (ms)
          <input aria-label="Ping rate" inputMode="numeric" value={ping} disabled={!canWrite || jsonMode} onChange={(event) => setPing(event.target.value)} />
        </label>
        <label>
          Missed pings
          <input aria-label="Missed pings" inputMode="numeric" value={missed} disabled={!canWrite || jsonMode} onChange={(event) => setMissed(event.target.value)} />
        </label>
        <label>
          <input
            type="checkbox"
            checked={jsonMode}
            onChange={(event) => {
              const on = event.target.checked;
              if (on) setJsonText(JSON.stringify(bodyFromForm(), null, 2));
              setJsonMode(on);
            }}
          />
          Edit as JSON
        </label>
        {jsonMode ? <textarea aria-label="Redundancy JSON" value={jsonText} onChange={(event) => setJsonText(event.target.value)} rows={10} /> : null}
        {canWrite ? <button type="submit">Save pair</button> : null}
      </form>
      {canWrite ? (
        <p>
          <button type="button" onClick={() => void act("failover")}>Request failover</button>
          <button type="button" onClick={() => void act("assume")}>Take control</button>
          <button type="button" onClick={() => void act("resync")}>Force sync</button>
        </p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
    </section>
  );
}
