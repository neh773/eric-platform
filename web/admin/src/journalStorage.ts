import { problemError, type ApiClient } from "./api";

/**
 * Where the alarm journal stores its rows (DAT-015): the gateway's own journal file, or a named database connection
 * shared with history providers, named queries and reports. The status comes from the journal diagnostics route
 * (gateway.read); changing the connection uses the journal settings route (gateway.write).
 */
export const JOURNAL_STATUS_PATH = "/api/v1/diagnostics/alarms/journal";
export const JOURNAL_SETTINGS_PATH = "/api/v1/alarms/journal/settings";

export interface JournalStorage {
  /** Null: the gateway's own journal file. */
  connection: string | null;
  /** `Valid`, `Pending` or `Faulted`. */
  status: string;
  error: string | null;
  queued: number;
  retrying: boolean;
  lostRows: number;
  lastWriteError: string | null;
}

export function parseJournalStorage(body: unknown): JournalStorage {
  const r = body && typeof body === "object" ? (body as Record<string, unknown>) : {};
  const num = (value: unknown) => (typeof value === "number" && Number.isFinite(value) ? value : 0);
  const str = (value: unknown) => (typeof value === "string" && value ? value : null);
  return {
    connection: r.storage === "connection" ? str(r.connection) : null,
    status: str(r.connectionStatus) ?? "Unknown",
    error: str(r.connectionError),
    queued: num(r.queued),
    retrying: r.retrying === true,
    lostRows: num(r.lostRows),
    lastWriteError: str(r.lastWriteError),
  };
}

export async function loadJournalStorage(client: ApiClient): Promise<JournalStorage> {
  const res = await client.request("GET", JOURNAL_STATUS_PATH);
  if (!res.ok) throw await problemError(res);
  return parseJournalStorage(await res.json());
}

/** [connection] null keeps the rows in the gateway's own journal file. */
export async function saveJournalConnection(client: ApiClient, connection: string | null): Promise<void> {
  const res = await client.request("PUT", JOURNAL_SETTINGS_PATH, { connection });
  if (!res.ok) throw await problemError(res);
}

/** One line for the page: where the rows go and whether that works now. */
export function describeJournalStorage(storage: JournalStorage): string {
  const where = storage.connection ? `Connection ${storage.connection}` : "Built-in journal file";
  const state = storage.retrying ? `${storage.status}; ${storage.queued} row(s) waiting to be written` : storage.status;
  return `${where}: ${state}${storage.error ? ` (${storage.error})` : ""}`;
}

/** `kind name` labels for a connection's users. */
export function describeUse(use: { kind: string; name: string }): string {
  switch (use.kind) {
    case "alarmJournal":
      return "Alarm journal";
    case "historyProvider":
      return `History provider ${use.name}`;
    case "report":
      return `Report ${use.name}`;
    case "namedQuery":
      return `Named query ${use.name}`;
    default:
      return `${use.kind} ${use.name}`;
  }
}
