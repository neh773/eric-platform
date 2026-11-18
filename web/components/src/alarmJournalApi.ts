import { PRIORITIES, formatTime, priorityRank, type Priority } from "./alarms";
import type { GatewayClient } from "./gateway";

/**
 * Alarm journal table logic: build the journal query, parse a page of rows, and format cells.
 *
 * REST (gateway/server AlarmApi.kt):
 *   GET /api/v1/alarms/journal?start=&end=&source=&displayPath=&minPriority=&maxPriority=&eventType=&offset=&limit=&order=
 *     -> { events: JournalEvent[], total, offset, limit }
 * start is inclusive, end exclusive (ISO-8601). source and displayPath take comma-separated patterns with
 * `*` wildcards. eventType is a comma-separated list of active, clear, ack, shelve, unshelve. Newest first
 * unless order=asc. limit is 1..1000.
 */

export const JOURNAL_EVENT_TYPES = {
  active: "Active",
  clear: "Cleared",
  ack: "Acknowledged",
  shelve: "Shelved",
  unshelve: "Unshelved",
} as const;
export type JournalEventType = keyof typeof JOURNAL_EVENT_TYPES;
export const JOURNAL_EVENT_KEYS = Object.keys(JOURNAL_EVENT_TYPES) as JournalEventType[];

export interface JournalEvent {
  id: number;
  eventId: string;
  source: string;
  displayPath: string;
  priority: string;
  priorityCode: number;
  /** active, clear, ack, shelve, unshelve; enabled/disabled or a numeric code from newer gateways pass through. */
  eventType: string;
  eventTime: string;
  actor: string | null;
  data: Record<string, unknown>;
}

export interface JournalPage {
  events: JournalEvent[];
  total: number;
  offset: number;
  limit: number;
}

export const JOURNAL_COLUMNS = {
  eventTime: "Event time",
  eventType: "Event",
  priority: "Priority",
  displayPath: "Display path",
  source: "Source path",
  label: "Label",
  eventId: "Event id",
  eventValue: "Value",
  actor: "User",
  ackNotes: "Ack notes",
  shelvedUntil: "Shelved until",
} as const;
export type JournalColumn = keyof typeof JOURNAL_COLUMNS;
export const DEFAULT_JOURNAL_COLUMNS: JournalColumn[] = ["eventTime", "displayPath", "eventType", "priority", "eventValue", "actor"];

export const MAX_JOURNAL_PAGE = 1000;

export interface JournalRange {
  mode: "realtime" | "historical";
  /** Realtime look-back window. */
  minutes: number;
  /** Historical bounds, ISO-8601. Empty means open-ended. */
  start: string;
  end: string;
}

export interface JournalQueryOptions {
  range: JournalRange;
  sourceFilter: string;
  displayPathFilter: string;
  minPriority: Priority;
  maxPriority: Priority;
  eventTypes: Record<JournalEventType, boolean>;
  offset: number;
  limit: number;
  newestFirst: boolean;
}

/**
 * Query path for the options, or an error message when the options cannot produce one (bad historical
 * date, inverted range). `now` is injectable for tests. Returns `null` when no event type is selected,
 * since nothing can match.
 */
export function journalQuery(options: JournalQueryOptions, now: number = Date.now()): { path: string } | { error: string } | null {
  const selected = JOURNAL_EVENT_KEYS.filter((key) => options.eventTypes[key]);
  if (selected.length === 0) return null;
  const params = new URLSearchParams();
  if (options.range.mode === "realtime") {
    const minutes = Math.max(1, options.range.minutes);
    params.set("start", new Date(now - minutes * 60_000).toISOString());
  } else {
    const start = isoOrNull(options.range.start);
    const end = isoOrNull(options.range.end);
    if (start === undefined) return { error: `Start time "${options.range.start}" is not a valid date.` };
    if (end === undefined) return { error: `End time "${options.range.end}" is not a valid date.` };
    if (start && end && Date.parse(end) < Date.parse(start)) return { error: "End time is before start time." };
    if (start) params.set("start", start);
    if (end) params.set("end", end);
  }
  if (options.sourceFilter.trim()) params.set("source", options.sourceFilter.trim());
  if (options.displayPathFilter.trim()) params.set("displayPath", options.displayPathFilter.trim());
  const min = priorityRank(options.minPriority);
  const max = priorityRank(options.maxPriority);
  const [low, high] = min <= max ? [min, max] : [max, min];
  if (low > 0) params.set("minPriority", PRIORITIES[low]!);
  if (high < PRIORITIES.length - 1) params.set("maxPriority", PRIORITIES[high]!);
  // Always named, even with all five selected: an omitted eventType would also return enabled/disabled
  // rows, which no checkbox controls.
  params.set("eventType", selected.join(","));
  if (options.offset > 0) params.set("offset", String(options.offset));
  params.set("limit", String(clampLimit(options.limit)));
  if (!options.newestFirst) params.set("order", "asc");
  return { path: `/api/v1/alarms/journal?${params.toString()}` };
}

export function clampLimit(limit: number): number {
  if (!Number.isFinite(limit)) return 100;
  return Math.min(MAX_JOURNAL_PAGE, Math.max(1, Math.round(limit)));
}

/** "" -> null (open), a parseable date -> ISO string, anything else -> undefined (invalid). */
function isoOrNull(value: string): string | null | undefined {
  const text = value.trim();
  if (!text) return null;
  const millis = Date.parse(text);
  if (Number.isNaN(millis)) return undefined;
  return new Date(millis).toISOString();
}

export function parseJournalPage(body: unknown): JournalPage {
  const empty: JournalPage = { events: [], total: 0, offset: 0, limit: 0 };
  if (!body || typeof body !== "object") return empty;
  const raw = body as Record<string, unknown>;
  const list = Array.isArray(raw.events) ? raw.events : [];
  const events = list.flatMap((entry): JournalEvent[] => {
    if (!entry || typeof entry !== "object") return [];
    const r = entry as Record<string, unknown>;
    if (typeof r.id !== "number") return [];
    const priority = str(r.priority) || "Medium";
    return [{
      id: r.id,
      eventId: str(r.eventId),
      source: str(r.source),
      displayPath: str(r.displayPath),
      priority,
      priorityCode: typeof r.priorityCode === "number" ? r.priorityCode : priorityRank(priority),
      eventType: str(r.eventType),
      eventTime: str(r.eventTime),
      actor: typeof r.actor === "string" && r.actor ? r.actor : null,
      data: r.data && typeof r.data === "object" && !Array.isArray(r.data) ? (r.data as Record<string, unknown>) : {},
    }];
  });
  return {
    events,
    total: typeof raw.total === "number" ? raw.total : events.length,
    offset: typeof raw.offset === "number" ? raw.offset : 0,
    limit: typeof raw.limit === "number" ? raw.limit : events.length,
  };
}

/** `prov:default:/tag:tank/level:/alm:High` -> `High`; otherwise the last display path segment. */
export function journalLabel(event: Pick<JournalEvent, "source" | "displayPath">): string {
  const marker = event.source.lastIndexOf(":/alm:");
  if (marker >= 0) return event.source.slice(marker + ":/alm:".length);
  const parts = event.displayPath.split("/");
  return parts[parts.length - 1] ?? "";
}

export function eventTypeLabel(type: string): string {
  if (type in JOURNAL_EVENT_TYPES) return JOURNAL_EVENT_TYPES[type as JournalEventType];
  if (type === "enabled") return "Enabled";
  if (type === "disabled") return "Disabled";
  return type;
}

export function journalCellText(event: JournalEvent, column: JournalColumn, locale: string, timeZone?: string): string {
  switch (column) {
    case "eventTime":
      return formatTime(event.eventTime, locale, timeZone);
    case "eventType":
      return eventTypeLabel(event.eventType);
    case "priority":
      return event.priority;
    case "displayPath":
      return event.displayPath;
    case "source":
      return event.source;
    case "label":
      return journalLabel(event);
    case "eventId":
      return event.eventId;
    case "eventValue": {
      const value = event.data.eventValue;
      return value == null ? "" : String(value);
    }
    case "actor":
      return event.actor ?? "";
    case "ackNotes": {
      const notes = event.data.ackNotes;
      return typeof notes === "string" ? notes : "";
    }
    case "shelvedUntil": {
      const expiry = event.data.shelveExpiry;
      return typeof expiry === "number" ? formatTime(new Date(expiry).toISOString(), locale, timeZone) : "";
    }
  }
}

/** Client-side text search over the loaded page. */
export function searchJournal(events: JournalEvent[], search: string, locale: string): JournalEvent[] {
  const needle = search.trim().toLowerCase();
  if (!needle) return events;
  return events.filter((event) =>
    (Object.keys(JOURNAL_COLUMNS) as JournalColumn[]).some((column) =>
      journalCellText(event, column, locale).toLowerCase().includes(needle),
    ),
  );
}

/** `"eventTime, label"` -> known columns in that order; unknown names are dropped. */
export function parseJournalColumns(value: unknown): JournalColumn[] {
  if (typeof value !== "string" || !value.trim()) return DEFAULT_JOURNAL_COLUMNS;
  const out: JournalColumn[] = [];
  for (const raw of value.split(",")) {
    const name = raw.trim();
    if (name in JOURNAL_COLUMNS && !out.includes(name as JournalColumn)) out.push(name as JournalColumn);
  }
  return out.length ? out : DEFAULT_JOURNAL_COLUMNS;
}

/** A priority prop value -> a known priority name, or the fallback. Case-insensitive. */
export function parsePriority(value: unknown, fallback: Priority): Priority {
  if (typeof value !== "string") return fallback;
  return PRIORITIES.find((name) => name.toLowerCase() === value.trim().toLowerCase()) ?? fallback;
}

export const alarmJournalApi = {
  async query(client: GatewayClient, path: string): Promise<JournalPage> {
    return parseJournalPage(await client.get(path));
  },
};

function str(value: unknown): string {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}
