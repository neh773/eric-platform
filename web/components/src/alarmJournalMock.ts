/**
 * Test-only stand-in for GET /api/v1/alarms/journal, shared by the Vitest suite and the Playwright spec.
 * It applies the same filters the gateway documents in its OpenAPI description: start inclusive, end
 * exclusive, comma-separated `*` patterns on source and displayPath, a priority range, an event type
 * list, offset/limit paging, and newest first unless order=asc. Not exported from the package index.
 */

export interface MockJournalRow {
  id: number;
  eventId: string;
  source: string;
  displayPath: string;
  priority: string;
  priorityCode: number;
  eventType: string;
  eventTime: string;
  actor: string | null;
  data: Record<string, unknown>;
}

const PRIORITY_NAMES = ["diagnostic", "low", "medium", "high", "critical"];

export function mockJournalRows(): MockJournalRow[] {
  const base = Date.parse("2026-10-28T08:00:00Z");
  const rows: MockJournalRow[] = [];
  const alarms = [
    { tag: "tank/level", path: "Tank/Level high", priority: "High" },
    { tag: "pump/trip", path: "Pump/Trip", priority: "Critical" },
    { tag: "valve/fault", path: "Valve/Fault", priority: "Low" },
    { tag: "room/temp", path: "Room/Temperature", priority: "Diagnostic" },
  ];
  let id = 0;
  // 30 cycles of active -> ack -> clear spread over the morning, plus one shelve/unshelve pair.
  for (let cycle = 0; cycle < 30; cycle++) {
    const alarm = alarms[cycle % alarms.length]!;
    const source = `prov:default:/tag:${alarm.tag}:/alm:${alarm.path.split("/")[1]}`;
    const eventId = `ev-${cycle}`;
    const at = base + cycle * 60_000;
    const priorityCode = PRIORITY_NAMES.indexOf(alarm.priority.toLowerCase());
    const common = { eventId, source, displayPath: alarm.path, priority: alarm.priority, priorityCode };
    rows.push({ ...common, id: ++id, eventType: "active", eventTime: iso(at), actor: null, data: { eventValue: 100 + cycle } });
    rows.push({ ...common, id: ++id, eventType: "ack", eventTime: iso(at + 10_000), actor: "operator1", data: { ackUser: "operator1", ackNotes: `note ${cycle}` } });
    rows.push({ ...common, id: ++id, eventType: "clear", eventTime: iso(at + 20_000), actor: null, data: { eventValue: 50 } });
  }
  const pump = { eventId: "ev-1", source: "prov:default:/tag:pump/trip:/alm:Trip", displayPath: "Pump/Trip", priority: "Critical", priorityCode: 4 };
  rows.push({ ...pump, id: ++id, eventType: "shelve", eventTime: iso(base + 40 * 60_000), actor: "operator1", data: { actor: "operator1", shelveExpiry: base + 55 * 60_000 } });
  rows.push({ ...pump, id: ++id, eventType: "unshelve", eventTime: iso(base + 45 * 60_000), actor: "admin", data: { actor: "admin" } });
  return rows;
}

/** Status and JSON body for a journal URL (path plus query). */
export function mockJournalResponse(rows: MockJournalRow[], url: string): { status: number; body: unknown } {
  const query = new URL(url, "http://mock").searchParams;
  const bad = (detail: string) => ({ status: 400, body: { type: "urn:oip:problem:bad-request", title: "Bad Request", detail } });
  const start = query.get("start");
  const end = query.get("end");
  const startMs = start ? Date.parse(start) : Number.NEGATIVE_INFINITY;
  const endMs = end ? Date.parse(end) : Number.POSITIVE_INFINITY;
  if (Number.isNaN(startMs) || Number.isNaN(endMs)) return bad("start and end must be ISO-8601 instants");
  const min = priority(query.get("minPriority"), 0);
  const max = priority(query.get("maxPriority"), 4);
  if (min === null || max === null) return bad("unknown priority");
  const types = query.get("eventType")?.split(",").map((part) => part.trim()).filter(Boolean);
  const sources = patterns(query.get("source"));
  const paths = patterns(query.get("displayPath"));
  const offset = Number(query.get("offset") ?? "0");
  const limit = Number(query.get("limit") ?? "100");
  if (!Number.isInteger(limit) || limit < 1 || limit > 1000) return bad("limit must be 1..1000");
  const matched = rows
    .filter((row) => {
      const at = Date.parse(row.eventTime);
      if (at < startMs || at >= endMs) return false;
      if (row.priorityCode < min || row.priorityCode > max) return false;
      if (types && !types.includes(row.eventType)) return false;
      if (sources && !sources.some((re) => re.test(row.source))) return false;
      if (paths && !paths.some((re) => re.test(row.displayPath))) return false;
      return true;
    })
    .sort((a, b) => Date.parse(a.eventTime) - Date.parse(b.eventTime) || a.id - b.id);
  if (query.get("order") !== "asc") matched.reverse();
  return {
    status: 200,
    body: { events: matched.slice(offset, offset + limit), total: matched.length, offset, limit },
  };
}

function priority(raw: string | null, fallback: number): number | null {
  if (raw === null) return fallback;
  const code = Number(raw);
  if (Number.isInteger(code) && code >= 0 && code <= 4) return code;
  const index = PRIORITY_NAMES.indexOf(raw.trim().toLowerCase());
  return index < 0 ? null : index;
}

function patterns(raw: string | null): RegExp[] | null {
  if (!raw) return null;
  return raw
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => new RegExp(`^${part.split("*").map((piece) => piece.replace(/[.+?^${}()|[\]\\]/g, "\\$&")).join(".*")}$`));
}

function iso(millis: number): string {
  return new Date(millis).toISOString().replace(".000Z", "Z");
}
