import type { GatewayClient } from "./gateway";

/**
 * Alarm status table logic: parse the gateway's live alarm list, filter, and sort.
 *
 * REST (gateway/server AlarmApi.kt):
 *   GET  /api/v1/alarms?source=&displayPath=&minPriority=  -> { alarms: AlarmEvent[] }  (shelved alarms are omitted)
 *   POST /api/v1/alarms/ack      { eventIds, notes? }
 *   POST /api/v1/alarms/shelve   { sources, durationSeconds }
 *   POST /api/v1/alarms/unshelve { sources }
 */

export const PRIORITIES = ["Diagnostic", "Low", "Medium", "High", "Critical"] as const;
export type Priority = (typeof PRIORITIES)[number];

/** Runtime state codes from the gateway: ClearUnacked 0, ClearAcked 1, ActiveUnacked 2, ActiveAcked 3. */
export type StateKey = "clearedUnacked" | "clearedAcked" | "activeUnacked" | "activeAcked";

export interface AlarmEvent {
  eventId: string;
  source: string;
  name: string;
  displayPath: string;
  tagPath: string;
  priority: string;
  priorityCode: number;
  state: string;
  stateCode: number;
  active: boolean;
  acknowledged: boolean;
  value: unknown;
  activeTime: string;
  clearTime: string | null;
  ackTime: string | null;
  ackUser: string | null;
  ackNotes: string | null;
  notes: string | null;
}

export const ALARM_COLUMNS = {
  state: "State",
  priority: "Priority",
  displayPath: "Display path",
  source: "Source path",
  label: "Label",
  activeTime: "Active time",
  clearTime: "Clear time",
  ackTime: "Ack time",
  ackUser: "Ack user",
  ackNotes: "Ack notes",
  value: "Value",
} as const;
export type AlarmColumn = keyof typeof ALARM_COLUMNS;
export const DEFAULT_COLUMNS: AlarmColumn[] = ["activeTime", "displayPath", "label", "priority", "state", "ackTime"];

export interface AlarmFilterOptions {
  states: Record<StateKey, boolean>;
  priorities: Record<Priority, boolean>;
  search: string;
}

/** Defaults follow the docs: cleared-and-acknowledged and Diagnostic are off. */
export const DEFAULT_STATES: Record<StateKey, boolean> = {
  activeUnacked: true,
  activeAcked: true,
  clearedUnacked: true,
  clearedAcked: false,
};
export const DEFAULT_PRIORITIES: Record<Priority, boolean> = {
  Diagnostic: false,
  Low: true,
  Medium: true,
  High: true,
  Critical: true,
};

export interface AlarmSort {
  column: AlarmColumn;
  direction: "asc" | "desc";
}

export const DEFAULT_SORT: AlarmSort = { column: "activeTime", direction: "desc" };

export function parseAlarms(body: unknown): AlarmEvent[] {
  if (!body || typeof body !== "object") return [];
  const list = (body as { alarms?: unknown }).alarms;
  if (!Array.isArray(list)) return [];
  return list.flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    const r = entry as Record<string, unknown>;
    const eventId = str(r.eventId);
    if (!eventId) return [];
    const active = r.active === true;
    const acknowledged = r.acknowledged === true;
    return [{
      eventId,
      source: str(r.source),
      name: str(r.name),
      displayPath: str(r.displayPath),
      tagPath: str(r.tagPath),
      priority: str(r.priority) || "Medium",
      priorityCode: typeof r.priorityCode === "number" ? r.priorityCode : priorityRank(str(r.priority)),
      state: str(r.state),
      stateCode: typeof r.stateCode === "number" ? r.stateCode : stateCodeOf(active, acknowledged),
      active,
      acknowledged,
      value: r.value,
      activeTime: str(r.activeTime),
      clearTime: optStr(r.clearTime),
      ackTime: optStr(r.ackTime),
      ackUser: optStr(r.ackUser),
      ackNotes: optStr(r.ackNotes),
      notes: optStr(r.notes),
    }];
  });
}

export function stateKey(event: Pick<AlarmEvent, "active" | "acknowledged">): StateKey {
  if (event.active) return event.acknowledged ? "activeAcked" : "activeUnacked";
  return event.acknowledged ? "clearedAcked" : "clearedUnacked";
}

export function priorityRank(priority: string): number {
  const index = PRIORITIES.findIndex((name) => name.toLowerCase() === priority.trim().toLowerCase());
  return index < 0 ? 2 : index;
}

function stateCodeOf(active: boolean, acked: boolean): number {
  return (active ? 2 : 0) + (acked ? 1 : 0);
}

/** Text shown in a cell. Times use the session locale. */
export function cellText(event: AlarmEvent, column: AlarmColumn, locale: string, timeZone?: string): string {
  switch (column) {
    case "state":
      return event.state;
    case "priority":
      return event.priority;
    case "displayPath":
      return event.displayPath;
    case "source":
      return event.source;
    case "label":
      return event.name;
    case "activeTime":
      return formatTime(event.activeTime, locale, timeZone);
    case "clearTime":
      return formatTime(event.clearTime, locale, timeZone);
    case "ackTime":
      return formatTime(event.ackTime, locale, timeZone);
    case "ackUser":
      return event.ackUser ?? "";
    case "ackNotes":
      return event.ackNotes ?? "";
    case "value":
      return event.value == null ? "" : String(event.value);
  }
}

export function formatTime(value: string | null, locale: string, timeZone?: string): string {
  if (!value) return "";
  const millis = Date.parse(value);
  if (Number.isNaN(millis)) return value;
  return new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "medium", timeZone }).format(millis);
}

export function filterAlarms(events: AlarmEvent[], options: AlarmFilterOptions): AlarmEvent[] {
  const needle = options.search.trim().toLowerCase();
  return events.filter((event) => {
    if (!options.states[stateKey(event)]) return false;
    const priority = PRIORITIES[priorityRank(event.priority)]!;
    if (!options.priorities[priority]) return false;
    if (!needle) return true;
    const haystack = [event.displayPath, event.source, event.name, event.state, event.priority, event.ackUser ?? "", event.ackNotes ?? ""];
    return haystack.some((text) => text.toLowerCase().includes(needle));
  });
}

function compareBy(column: AlarmColumn, left: AlarmEvent, right: AlarmEvent): number {
  switch (column) {
    case "state":
      return left.stateCode - right.stateCode;
    case "priority":
      return left.priorityCode - right.priorityCode;
    case "activeTime":
    case "clearTime":
    case "ackTime":
      return timeOf(left[column]) - timeOf(right[column]);
    case "value": {
      const a = left.value;
      const b = right.value;
      if (typeof a === "number" && typeof b === "number") return a - b;
      return String(a ?? "").localeCompare(String(b ?? ""));
    }
    default:
      return cellText(left, column, "en-US", "UTC").localeCompare(cellText(right, column, "en-US", "UTC"));
  }
}

function timeOf(value: string | null): number {
  if (!value) return Number.NEGATIVE_INFINITY;
  const millis = Date.parse(value);
  return Number.isNaN(millis) ? Number.NEGATIVE_INFINITY : millis;
}

/** Stable sort. Ties fall back to newest active time, then event id. */
export function sortAlarms(events: AlarmEvent[], sort: AlarmSort): AlarmEvent[] {
  const direction = sort.direction === "asc" ? 1 : -1;
  return events
    .map((event, index) => ({ event, index }))
    .sort((a, b) => {
      const primary = compareBy(sort.column, a.event, b.event) * direction;
      if (primary !== 0) return primary;
      const time = timeOf(b.event.activeTime) - timeOf(a.event.activeTime);
      if (time !== 0) return time;
      return a.index - b.index;
    })
    .map((entry) => entry.event);
}

/**
 * Header click cycle. State and priority start descending, as the docs describe; other columns
 * start ascending. A second click reverses.
 */
export function nextAlarmSort(current: AlarmSort, column: AlarmColumn): AlarmSort {
  if (current.column === column) return { column, direction: current.direction === "asc" ? "desc" : "asc" };
  const descFirst = column === "state" || column === "priority" || column === "activeTime";
  return { column, direction: descFirst ? "desc" : "asc" };
}

/** `"activeTime, label"` -> known columns in that order; unknown names are dropped. */
export function parseColumns(value: unknown): AlarmColumn[] {
  if (typeof value !== "string" || !value.trim()) return DEFAULT_COLUMNS;
  const out: AlarmColumn[] = [];
  for (const raw of value.split(",")) {
    const name = raw.trim();
    if (name in ALARM_COLUMNS && !out.includes(name as AlarmColumn)) out.push(name as AlarmColumn);
  }
  return out.length ? out : DEFAULT_COLUMNS;
}

/** `"5, 15, 60"` minutes -> sorted unique positive minutes. */
export function parseShelvingTimes(value: unknown): number[] {
  const fallback = [5, 15, 30, 60];
  if (typeof value !== "string" || !value.trim()) return fallback;
  const minutes = value
    .split(",")
    .map((part) => Number(part.trim()))
    .filter((n) => Number.isFinite(n) && n > 0);
  const unique = [...new Set(minutes)].sort((a, b) => a - b);
  return unique.length ? unique : fallback;
}

export function alarmsQuery(sourceFilter: string, displayPathFilter: string): string {
  const params = new URLSearchParams();
  if (sourceFilter.trim()) params.set("source", sourceFilter.trim());
  if (displayPathFilter.trim()) params.set("displayPath", displayPathFilter.trim());
  const text = params.toString();
  return text ? `/api/v1/alarms?${text}` : "/api/v1/alarms";
}

export const alarmApi = {
  async list(client: GatewayClient, sourceFilter = "", displayPathFilter = ""): Promise<AlarmEvent[]> {
    return parseAlarms(await client.get(alarmsQuery(sourceFilter, displayPathFilter)));
  },
  async acknowledge(client: GatewayClient, eventIds: string[], notes: string): Promise<void> {
    const body: Record<string, unknown> = { eventIds };
    if (notes.trim()) body.notes = notes.trim();
    await client.post("/api/v1/alarms/ack", body);
  },
  async shelve(client: GatewayClient, sources: string[], minutes: number): Promise<void> {
    await client.post("/api/v1/alarms/shelve", { sources, durationSeconds: minutes * 60 });
  },
  async unshelve(client: GatewayClient, sources: string[]): Promise<void> {
    await client.post("/api/v1/alarms/unshelve", { sources });
  },
};

function str(value: unknown): string {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}

function optStr(value: unknown): string | null {
  return typeof value === "string" && value ? value : null;
}
